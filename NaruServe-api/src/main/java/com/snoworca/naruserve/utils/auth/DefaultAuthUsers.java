package com.snoworca.naruserve.utils.auth;

import com.snoworca.naruserve.Logger;
import com.snoworca.naruserve.utils.TimeSource;
import com.snoworca.naruserve.utils.auth.apikey.ApiKeyStore;
import com.snoworca.naruserve.utils.auth.group.GroupAdminStore;
import com.snoworca.naruserve.utils.auth.group.GroupStore;
import com.snoworca.naruserve.utils.auth.internal.AuthArgumentException;
import com.snoworca.naruserve.utils.auth.internal.EventCalls;
import com.snoworca.naruserve.utils.auth.internal.Passwords;
import com.snoworca.naruserve.utils.auth.internal.ThrottleKeys;
import com.snoworca.naruserve.utils.auth.internal.UserLocks;
import com.snoworca.naruserve.utils.auth.internal.Usernames;
import com.snoworca.naruserve.utils.auth.user.AuthUser;
import com.snoworca.naruserve.utils.auth.user.AuthUsers;
import com.snoworca.naruserve.utils.auth.user.DuplicateUserException;
import com.snoworca.naruserve.utils.auth.user.UserMutator;
import com.snoworca.naruserve.utils.auth.user.UserStore;
import com.snoworca.naruserve.utils.auth.spi.LoginThrottle;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;

/** {@link AuthUsers} over the configured store, events and hashing. */
// @req IR-IAM-003
final class DefaultAuthUsers implements AuthUsers {

    /** One store call. */
    private interface StoreCall<T> {
        T run() throws Exception;
    }

    /** Failed current-password confirmations per user id before a 429 (SEC-FLOW-015). */
    static final int CONFIRM_MAX = 5;
    /** The window of that limit: 15 minutes. */
    static final long CONFIRM_WINDOW_MILLIS = 15 * 60_000L;

    private final UserStore store;
    private final @Nullable GroupStore groups;
    private final @Nullable ApiKeyStore apiKeys;
    private final LoginThrottle throttle;
    private final List<AuthEvents> events;
    private final Passwords passwords;
    private final TimeSource time;
    private final Logger logger;
    private final @Nullable Changed changed;
    private volatile @Nullable NewPasswords newPasswords;
    private volatile @Nullable ApiKeyOwners keyOwners;

    /** What auth does after this facade changed a user: forget it in the principal cache, and close its sockets. */
    @FunctionalInterface
    interface Changed {
        void user(String id, boolean revoked);
    }

    DefaultAuthUsers(UserStore store, @Nullable GroupStore groups, @Nullable ApiKeyStore apiKeys,
            List<AuthEvents> events, Passwords passwords, TimeSource time, Logger logger, LoginThrottle throttle) {
        this(store, groups, apiKeys, events, passwords, time, logger, throttle, null);
    }

    DefaultAuthUsers(UserStore store, @Nullable GroupStore groups, @Nullable ApiKeyStore apiKeys,
            List<AuthEvents> events, Passwords passwords, TimeSource time, Logger logger, LoginThrottle throttle,
            @Nullable Changed changed) {
        this.changed = changed;
        this.store = store;
        this.groups = groups;
        this.apiKeys = apiKeys;
        this.throttle = throttle;
        this.events = events;
        this.passwords = passwords;
        this.time = time;
        this.logger = logger;
    }

    /** OPS-AUTH-022 AC-4: warns about created roles the RolePolicy does not declare. */
    interface RoleWarning {
        void warn(Iterable<String> roles);
    }

    private volatile @Nullable RoleWarning roleWarning;

    void roleWarning(RoleWarning w) {
        this.roleWarning = w;
    }

    /** The new-password check of {@link #create} (FR-TOKEN-004); auth sets it once after building. */
    void passwords(NewPasswords check) {
        this.newPasswords = check;
    }

    /** The per-owner key side, so a deletion clears the owner's pending marker (FR-APIKEY-008). */
    void keyOwners(ApiKeyOwners owners) {
        this.keyOwners = owners;
    }

    private volatile @Nullable ConfirmationHolds holds;

    /** The per-user confirmation holds shared with the password endpoints (SEC-FLOW-015). */
    void confirmationHolds(ConfirmationHolds h) {
        this.holds = h;
    }

    private <T> T call(String what, StoreCall<T> c) {
        try {
            return c.run();
        } catch (DuplicateUserException | AuthRejection | AuthUnavailableException e) {
            throw e;
        } catch (Exception e) {
            logger.error("auth: the user store failed in " + what + "; answering 503", e);
            throw new AuthUnavailableException("the user store failed", e);
        }
    }

    private AuthEventContext ctx() {
        return new AuthEventContext(UserOrigin.APP, null, null, time.nowMillis());
    }

    private static @Nullable AuthUser strip(@Nullable AuthUser u) {
        return u == null ? null : EventCalls.copy(u);
    }

    private static String normalize(String name) {
        if (name == null) { // OPS-AUTH-029 AC-5
            throw new AuthArgumentException("a username is required", true);
        }
        try {
            return Usernames.normalize(name);
        } catch (IllegalArgumentException e) {
            throw new AuthArgumentException(e.getMessage() == null ? "invalid username" : e.getMessage(), true);
        }
    }

    // @req IR-IAM-003
    @Override
    public @Nullable AuthUser findById(final String id) {
        return strip(call("findById", () -> store.findById(id)));
    }

    // @req IR-IAM-003
    @Override
    public @Nullable AuthUser findByUsername(String username) {
        final String n = normalize(username);
        return strip(call("findByUsername", () -> store.findByUsername(n)));
    }

    // @req IR-IAM-003
    @Override
    public AuthUser create(String username, char[] password, String... roles) {
        if (password == null) { // OPS-AUTH-027 AC-4: a shown argument refusal, not an NPE
            throw new AuthArgumentException("a password is required; a service account is created with create(name, "
                    + "null, u -> u.toBuilder().serviceAccount(true).build())", true);
        }
        return create(username, password, null, roles);
    }

    // @req IR-IAM-003
    // @req SEC-AUTH-008
    // @req FR-TOKEN-004
    @Override
    public AuthUser create(String username, char @Nullable [] password, @Nullable UserMutator init,
            String... roles) {
        try {
            return createUser(username, password, init, roles);
        } finally {
            wipe(password);
        }
    }

    /** Overwrites a password buffer with {@code '\0'} (SEC-AUTH-008 AC-3). */
    private static void wipe(char @Nullable [] password) {
        if (password != null) {
            Arrays.fill(password, '\0');
        }
    }

    private AuthUser createUser(String username, char @Nullable [] password, @Nullable UserMutator init,
            String... roles) {
        String name = normalize(username);
        AuthUser candidate = AuthUser.builder().username(name).roles(Arrays.asList(roles)).build();
        if (init != null) {
            AuthUser changed = Objects.requireNonNull(init.apply(candidate), "init returned null");
            requireSecurityFieldsKept(candidate, changed, true);
            candidate = changed;
        }
        if (candidate.isServiceAccount() && password != null) {
            throw new AuthArgumentException("a service account has no password");
        }
        if (!candidate.isServiceAccount() && password == null) {
            throw new AuthArgumentException("a user needs a password unless init marks it a service account");
        }
        NewPasswords check = newPasswords;
        if (password != null && check != null) {
            String problem = check.problem(password); // before any hash slot (FR-TOKEN-004)
            if (problem != null) {
                throw new AuthArgumentException(problem, true); // OPS-AUTH-018 AC-1: the rule, never the password
            }
        }
        RoleWarning w = roleWarning;
        if (w != null) {
            w.warn(candidate.getRoles());
        }
        final AuthUser row = password == null ? candidate
                : candidate.toBuilder().passwordHash(passwords.hash(password)).build();
        final AuthUser seen = EventCalls.copy(row);
        final AuthEventContext ctx = ctx();
        for (final AuthEvents l : events) {
            EventCalls.before(logger, "beforeUserCreate", () -> l.beforeUserCreate(seen, ctx));
        }
        AuthUser created = call("create", () -> store.create(row));
        final AuthUser out = EventCalls.copy(created);
        for (final AuthEvents l : events) {
            EventCalls.after(logger, "onUserCreated", () -> l.onUserCreated(out, ctx));
        }
        return out;
    }

    /** {@link IllegalArgumentException} when {@code after} changes a security field of {@code before}. */
    private static void requireSecurityFieldsKept(AuthUser before, AuthUser after, boolean creating) {
        if (after.getPasswordHash() != null) {
            throw new AuthArgumentException("a mutator must not set a password hash; use the password methods");
        }
        if (!Objects.equals(before.getId(), after.getId()) || !before.getUsername().equals(after.getUsername())) {
            throw new AuthArgumentException("a mutator must not change the id or username; use rename");
        }
        if (before.getTokenVersion() != after.getTokenVersion() || before.isEnabled() != after.isEnabled()
                || !before.getRoles().equals(after.getRoles())) {
            throw new AuthArgumentException("a mutator must not change the token version, enabled flag or roles; "
                    + "change roles or the enabled flag with admin A4, PATCH /auth/admin/users/:id by default"); // OPS-AUTH-024 AC-10
        }
        if (!creating && before.isServiceAccount() != after.isServiceAccount()) {
            throw new AuthArgumentException("the service-account flag is set at creation and never changed");
        }
    }

    // @req IR-IAM-003
    @Override
    public @Nullable AuthUser update(final String id, UserMutator mutator) {
        return update(id, mutator, ctx());
    }

    /** {@link #update(String, UserMutator)} with the event context of its caller ({@code PATCH /auth/me}: SELF). */
    // @req FR-FLOW-006
    @Nullable AuthUser update(final String id, UserMutator mutator, AuthEventContext ctx) {
        ReentrantLock lock = UserLocks.of(id);
        lock.lock();
        try {
            final AuthUser current = call("findById", () -> store.findById(id));
            if (current == null) {
                return null;
            }
            AuthUser before = EventCalls.copy(current);
            AuthUser changed = Objects.requireNonNull(mutator.apply(before), "mutator returned null");
            requireSecurityFieldsKept(before, changed, false);
            final AuthUser row = changed.toBuilder().passwordHash(current.getPasswordHash()).build();
            Set<UserChange> changes = row.getAttributes().equals(current.getAttributes())
                    ? Collections.<UserChange>emptySet() : EnumSet.of(UserChange.ATTRIBUTES);
            return write(current, row, changes, ctx);
        } finally {
            lock.unlock();
        }
    }

    // @req IR-IAM-003
    @Override
    public @Nullable AuthUser rename(final String id, String newUsername) {
        String name = normalize(newUsername);
        ReentrantLock lock = UserLocks.of(id);
        lock.lock();
        try {
            final AuthUser current = call("findById", () -> store.findById(id));
            if (current == null) {
                return null;
            }
            if (current.getUsername().equals(name)) {
                return EventCalls.copy(current);
            }
            return write(current, current.toBuilder().username(name).build(), EnumSet.of(UserChange.USERNAME), ctx());
        } finally {
            lock.unlock();
        }
    }

    private @Nullable AuthUser write(AuthUser current, final AuthUser row, final Set<UserChange> changes,
            final AuthEventContext ctx) {
        final AuthUser before = EventCalls.copy(current);
        final AuthUser after = EventCalls.copy(row);
        for (final AuthEvents l : events) {
            EventCalls.before(logger, "beforeUserUpdate", () -> l.beforeUserUpdate(before, after, ctx));
        }
        AuthUser stored = call("update", () -> store.update(row));
        forget(current.getId(), false);
        if (stored == null) {
            return null;
        }
        final AuthUser out = EventCalls.copy(stored);
        if (!changes.isEmpty()) {
            for (final AuthEvents l : events) {
                EventCalls.after(logger, "onUserUpdated", () -> l.onUserUpdated(before, out, changes, ctx));
            }
        }
        return out;
    }

    /**
     * R11: group memberships first, then (with API keys on) every key of the user, then the user row; a failing step
     * answers 503 and keeps the row, so a retry continues where it stopped and no membership or key outlives the row.
     */
    // @req IR-IAM-003
    // @req FR-IAM-008
    @Override
    public boolean delete(final String id) {
        return delete(id, ctx());
    }

    /** The cache forgets a user auth just changed (SEC-AUTH-007); a deleted user's connections close too. */
    private void forget(@Nullable String id, boolean revoked) {
        Changed c = changed;
        if (c != null && id != null) {
            c.user(id, revoked);
        }
    }

    /** {@link #delete(String)} with the event context of its caller (an admin delete passes origin ADMIN). */
    // @req FR-IAM-008
    // @req FR-APIKEY-008
    boolean delete(final String id, final AuthEventContext ctx) {
        ReentrantLock lock = UserLocks.of(id);
        lock.lock();
        try {
            AuthUser current = call("findById", () -> store.findById(id));
            if (current == null) {
                return false;
            }
            final AuthUser seen = EventCalls.copy(current);
            for (final AuthEvents l : events) {
                EventCalls.before(logger, "beforeUserDelete", () -> l.beforeUserDelete(seen, ctx));
            }
            final GroupStore g = groups;
            if (g instanceof GroupAdminStore) {
                call("removeUser", () -> {
                    ((GroupAdminStore) g).removeUser(id);
                    return Boolean.TRUE;
                });
            }
            final ApiKeyStore keys = apiKeys;
            final ApiKeyOwners owners = keyOwners;
            boolean deleted;
            if (keys != null && owners != null) {
                // FR-APIKEY-008: the keys, the pending marker, then the row, all inside the key owner lock, so an
                // issuance waiting for that lock finds no owner and inserts nothing
                final boolean[] removed = new boolean[1];
                try {
                    owners.deleteKeys(id, () -> {
                        call("deleteAllByOwner", () -> keys.deleteAllByOwner(id));
                        return Boolean.TRUE;
                    }, () -> {
                        removed[0] = call("delete", () -> store.delete(id));
                        return Boolean.TRUE;
                    });
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new AuthUnavailableException("deleting the user failed", e);
                }
                deleted = removed[0];
            } else {
                if (keys != null) {
                    call("deleteAllByOwner", () -> keys.deleteAllByOwner(id));
                }
                deleted = call("delete", () -> store.delete(id));
            }
            forget(id, deleted);
            if (deleted) {
                for (final AuthEvents l : events) {
                    EventCalls.after(logger, "onUserDeleted", () -> l.onUserDeleted(seen, ctx));
                }
            }
            return deleted;
        } finally {
            lock.unlock();
        }
    }

    /**
     * SEC-FLOW-015: one limit per user id shared by every current-password confirmation, 5 failures per 15 minutes;
     * over it the call throws {@code AuthRejection(429, too_many_attempts)} with {@code getRetryAfterSeconds()} and
     * computes no hash. A success does not reset the count. The array is wiped after the call.
     */
    // @req IR-IAM-003
    // @req SEC-FLOW-015
    // @req SEC-AUTH-008
    // @req SEC-IAM-002
    @Override
    public boolean checkPassword(final String id, char[] password) {
        // one confirmation per user at a time: parallel calls cannot all pass the limit check before any failure is
        // counted (the check, the hash and the count run under the user's confirmation stripe and, with auth, under the
        // per-user hold shared with the confirmation endpoints)
        ReentrantLock confirm = CONFIRM_LOCKS[(id.hashCode() & 0x7fffffff) % CONFIRM_LOCKS.length];
        boolean locked;
        try {
            locked = confirm.tryLock(CONFIRM_WAIT_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            locked = false;
        }
        if (!locked) {
            wipe(password);
            throw new AuthUnavailableException("another password confirmation of this user did not finish in time");
        }
        ConfirmationHolds h = holds;
        Object hold = null;
        if (h != null) {
            hold = h.hold(id, CONFIRM_WAIT_MILLIS);
            if (hold == null) {
                confirm.unlock();
                wipe(password);
                throw new AuthUnavailableException("another password confirmation of this user did not finish in time");
            }
        }
        try {
            final String key = ThrottleKeys.passwordConfirm(id);
            long blocked = call("the password-confirmation limit",
                    () -> throttle.retryAfter(key, CONFIRM_MAX, CONFIRM_WINDOW_MILLIS));
            if (blocked > 0) {
                throw tooMany(blocked);
            }
            AuthUser u = call("findById", () -> store.findById(id));
            String hash = u == null || u.isServiceAccount() ? null : u.getPasswordHash();
            boolean ok = passwords.verify(password, hash);
            if (!ok) {
                call("the password-confirmation limit",
                        () -> throttle.recordFailure(key, CONFIRM_MAX, CONFIRM_WINDOW_MILLIS));
            }
            return ok;
        } finally {
            if (h != null) {
                h.release(id, hold);
            }
            confirm.unlock();
            wipe(password);
        }
    }

    private static final ReentrantLock[] CONFIRM_LOCKS = new ReentrantLock[64];
    /** The longest wait for the confirmation stripe: the 5-second slot budget (SEC-IAM-002 AC-3). */
    static final long CONFIRM_WAIT_MILLIS = 5_000L;

    static {
        for (int i = 0; i < CONFIRM_LOCKS.length; i++) {
            CONFIRM_LOCKS[i] = new ReentrantLock();
        }
    }

    /** 429 {@code too_many_attempts} with the remaining block time rounded up to whole seconds. */
    static AuthRejection tooMany(long blockedMillis) {
        long seconds = Math.max(1L, (blockedMillis + 999L) / 1000L);
        return new AuthRejection(AuthErrorCode.TOO_MANY_ATTEMPTS.getStatus(), AuthErrorCode.TOO_MANY_ATTEMPTS.getCode(),
                "too many attempts; try again later", seconds);
    }
}
