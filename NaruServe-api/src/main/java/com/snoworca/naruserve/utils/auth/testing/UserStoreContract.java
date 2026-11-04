package com.snoworca.naruserve.utils.auth.testing;

import com.snoworca.naruserve.utils.auth.user.AuthUser;
import com.snoworca.naruserve.utils.auth.user.DuplicateUserException;
import com.snoworca.naruserve.utils.auth.user.UserStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.jspecify.annotations.Nullable;

/**
 * The checks of {@link AuthTesting#verifyUserStore}, in order: CRUD (with "a deleted id is never reused"),
 * {@code DuplicateUserException}, the password hash round trip (through a reopened instance too when a reopen is
 * given), {@code changePassword} (null hash and ver mismatch), the field-method results and, for a store
 * declaring {@code supportsAtomicFieldWrites()}, that {@code update()} leaves the security fields alone. Every user
 * it creates has a fresh random name starting with {@code contract-}, so one store can be verified any number of
 * times, and is deleted again before it returns. A broken clause throws {@link AssertionError} whose message starts
 * with {@code UserStore contract (<clause>)}; an exception thrown by the store becomes the cause of one. Uses no test
 * framework.
 */
// @req FR-IAM-004
// @req REL-IAM-003
final class UserStoreContract {

    /** A store call that may throw anything. */
    private interface Call<T> {
        T run() throws Exception;
    }

    private final UserStore store;
    private final @Nullable Callable<? extends UserStore> reopen;
    private final String run;
    private final List<String> created = new ArrayList<>();

    private UserStoreContract(UserStore store, @Nullable Callable<? extends UserStore> reopen) {
        this.store = store;
        this.reopen = reopen;
        this.run = "contract-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    // @req FR-IAM-004
    // @req REL-IAM-003
    static void verify(UserStore store) {
        verify(store, null);
    }

    /** {@link #verify(UserStore)}, and with {@code reopen} the hash round trip through a fresh instance too. */
    // @req FR-IAM-004
    static void verify(UserStore store, @Nullable Callable<? extends UserStore> reopen) {
        final UserStoreContract c = new UserStoreContract(Objects.requireNonNull(store, "store"), reopen);
        java.util.List<AssertionError> failed = new java.util.ArrayList<>(); // OPS-AUTH-032 AC-1: every clause runs
        try {
            run(failed, c::crud);
            run(failed, c::duplicates);
            run(failed, c::hashRoundTrip);
            run(failed, c::changePassword);
            run(failed, c::fieldMethods);
            run(failed, c::list); // OPS-AUTH-029 AC-4
            if (store.supportsAtomicFieldWrites()) {
                run(failed, c::atomicUpdate);
            }
        } finally {
            c.cleanUp();
        }
        if (failed.size() == 1) {
            throw failed.get(0);
        }
        if (!failed.isEmpty()) {
            StringBuilder m = new StringBuilder("UserStore contract: " + failed.size() + " clauses failed:");
            for (int i = 0; i < failed.size(); i++) {
                m.append("\n  ").append(i + 1).append(". ").append(failed.get(i).getMessage());
            }
            Throwable first = failed.get(0).getCause() != null ? failed.get(0).getCause() : failed.get(0);
            AssertionError all = new AssertionError(m.toString(), first); // the store's own exception stays the cause
            for (AssertionError f : failed) {
                all.addSuppressed(f);
            }
            throw all;
        }
    }

    private interface Clause {
        void check();
    }

    private static void run(java.util.List<AssertionError> failed, Clause clause) {
        try {
            clause.check();
        } catch (AssertionError e) {
            failed.add(e);
        }
    }

    // ------------------------------------------------------------------------------------------- the clauses

    /** list() either is not implemented (UnsupportedOperationException) or returns a page, never null. */
    private void list() {
        com.snoworca.naruserve.utils.auth.user.UserPage page;
        try {
            page = store.list(0, 10, null);
        } catch (UnsupportedOperationException none) {
            return;
        } catch (Exception e) {
            throw fail("list", "list(0, 10, null) threw " + e, e);
        }
        if (page == null) {
            throw fail("list", "list(0, 10, null) returned null; return a UserPage, or leave the default, which "
                    + "throws UnsupportedOperationException", null);
        }
    }

    /** Create, find by id and by name, update, delete, and the answers for a deleted id. */
    // @req FR-IAM-004
    private void crud() {
        String clause = "CRUD";
        String name = run + "-crud";
        AuthUser u = create(clause, user(name, hash("crud")).roles("contract-role").attribute("contract", "1").build());
        String id = u.getId();
        check(clause, u.getUsername().equals(name), "create() returned the username " + u.getUsername() + " for " + name);
        AuthUser byId = mustFind(clause, id);
        check(clause, byId.getUsername().equals(name), "findById returned the username " + byId.getUsername());
        check(clause, byId.isEnabled(), "a user created enabled was read back disabled");
        check(clause, byId.getRoles().equals(Collections.singleton("contract-role")),
                "the roles written by create() came back as " + byId.getRoles());
        check(clause, "1".equals(byId.getAttribute("contract")), "the attributes written by create() came back as "
                + byId.getAttributes().keySet());
        AuthUser byName = call(clause, "findByUsername", () -> store.findByUsername(name));
        check(clause, byName != null && id.equals(byName.getId()), "findByUsername(" + name + ") did not return the "
                + "created user");

        AuthUser updated = call(clause, "update", () -> store.update(byId.toBuilder().attribute("contract", "2").build()));
        check(clause, updated != null, "update() of an existing user returned null");
        check(clause, "2".equals(mustFind(clause, id).getAttribute("contract")),
                "update() did not store a changed attribute");

        check(clause, call(clause, "delete", () -> store.delete(id)), "delete() of an existing user returned false");
        check(clause, call(clause, "findById", () -> store.findById(id)) == null,
                "findById returned a deleted user");
        check(clause, call(clause, "findByUsername", () -> store.findByUsername(name)) == null,
                "findByUsername returned a deleted user");
        check(clause, !call(clause, "delete", () -> store.delete(id)), "delete() of a deleted user returned true");
        check(clause, call(clause, "update", () -> store.update(byId)) == null,
                "update() of a deleted user did not return null");
        check(clause, call(clause, "findById", () -> store.findById(id)) == null,
                "update() of a deleted user brought it back");
        // the newest user was just deleted: a MAX(id)+1 or SQLite rowid store would hand its id out again
        AuthUser next = create(clause, user(run + "-crud-next", hash("crud-next")).build());
        check(clause, !id.equals(next.getId()), "create() after delete(" + id + ") returned the deleted id again: a "
                + "deleted id is never reused, or the new user inherits the old one's group memberships and API keys "
                + "(SQLite: INTEGER PRIMARY KEY AUTOINCREMENT, or random ids)");
    }

    /** create and update throw DuplicateUserException on an existing username. */
    // @req FR-IAM-004
    private void duplicates() {
        String clause = "DuplicateUserException";
        String name = run + "-dup";
        create(clause, user(name, hash("dup")).build());
        try {
            AuthUser second = store.create(user(name, hash("dup2")).build());
            if (second.getId() != null) {
                created.add(second.getId());
            }
            throw fail(clause, "create() accepted a second user named " + name
                    + "; a unique-key violation must throw DuplicateUserException", null);
        } catch (DuplicateUserException expected) {
            // the contract
        } catch (Exception e) {
            throw fail(clause, "create() of a second user named " + name + " threw " + e.getClass().getName()
                    + " instead of DuplicateUserException", e);
        }
        AuthUser other = create(clause, user(run + "-dup-other", hash("dup3")).build());
        try {
            store.update(other.toBuilder().username(name).build());
            throw fail(clause, "update() renamed a user to the existing name " + name
                    + "; a unique-key violation must throw DuplicateUserException", null);
        } catch (DuplicateUserException expected) {
            // the contract
        } catch (Exception e) {
            throw fail(clause, "update() to the existing name " + name + " threw " + e.getClass().getName()
                    + " instead of DuplicateUserException", e);
        }
    }

    /** The hash written by create() and kept through update() reads back unchanged. */
    // @req FR-IAM-004
    private void hashRoundTrip() {
        String clause = "password hash round trip";
        String name = run + "-hash";
        String hash = hash("round-trip");
        AuthUser u = create(clause, user(name, hash).build());
        AuthUser byId = mustFind(clause, u.getId());
        check(clause, hash.equals(byId.getPasswordHash()), "the hash written by create() came back from findById as "
                + describe(byId.getPasswordHash()) + " (a store persisting AuthUser with Gson loses the transient "
                + "passwordHash field)");
        AuthUser byName = call(clause, "findByUsername", () -> store.findByUsername(name));
        check(clause, byName != null && hash.equals(byName.getPasswordHash()),
                "the hash written by create() did not come back from findByUsername");
        call(clause, "update", () -> store.update(byId.toBuilder().attribute("contract", "reload").build()));
        check(clause, hash.equals(mustFind(clause, u.getId()).getPasswordHash()),
                "the hash did not survive update() of another field");
        Callable<? extends UserStore> open = reopen;
        if (open == null) {
            return;
        }
        UserStore fresh = call(clause, "reopen", open::call);
        check(clause, fresh != null, "reopen returned null");
        try {
            AuthUser again = call(clause, "findById", () -> fresh.findById(u.getId()));
            check(clause, again != null, "findById(" + u.getId() + ") on the reopened store returned null for a saved "
                    + "user");
            check(clause, hash.equals(again.getPasswordHash()), "the hash written by create() came back from the "
                    + "reopened store as " + describe(again.getPasswordHash()) + ": the saved form loses it (a store "
                    + "persisting AuthUser with Gson loses the transient passwordHash field)");
            AuthUser againByName = call(clause, "findByUsername", () -> fresh.findByUsername(name));
            check(clause, againByName != null && hash.equals(againByName.getPasswordHash()),
                    "the hash written by create() did not come back from findByUsername on the reopened store");
        } finally {
            if (fresh != store && fresh instanceof AutoCloseable) {
                try {
                    ((AutoCloseable) fresh).close();
                } catch (Exception ignored) {
                    // best effort: the contract result stands
                }
            }
        }
    }

    /** changePassword with a null stored hash, with a ver mismatch, with a wrong hash, and on a deleted user. */
    // @req FR-IAM-004
    private void changePassword() {
        String clause = "changePassword";
        AuthUser sso = create(clause, user(run + "-sso", null).build());
        long v0 = mustFind(clause, sso.getId()).getTokenVersion();
        String first = hash("first");
        check(clause, call(clause, "changePassword", () -> store.changePassword(sso.getId(), null, v0, first)),
                "changePassword(id, null, ver, newHash) on a user with a null password hash returned false: a null "
                        + "expectedOldHash must match a null stored hash");
        AuthUser afterFirst = mustFind(clause, sso.getId());
        check(clause, first.equals(afterFirst.getPasswordHash()) && afterFirst.getTokenVersion() == v0 + 1,
                "changePassword on a null-hash user must store the new hash and raise ver by one (ver " + v0 + " -> "
                        + afterFirst.getTokenVersion() + ")");

        String old = hash("old");
        AuthUser u = create(clause, user(run + "-pw", old).build());
        String id = u.getId();
        long v = mustFind(clause, id).getTokenVersion();
        String next = hash("next");
        check(clause, !call(clause, "changePassword", () -> store.changePassword(id, old, v + 1, next)),
                "changePassword with a ver mismatch (expectedVer " + (v + 1) + ", stored " + v + ") returned true: "
                        + "expectedVer must match");
        check(clause, !call(clause, "changePassword", () -> store.changePassword(id, hash("wrong"), v, next)),
                "changePassword with a wrong expectedOldHash returned true");
        check(clause, !call(clause, "changePassword", () -> store.changePassword(id, null, v, next)),
                "changePassword with expectedOldHash null on a user with a hash returned true: null matches only a "
                        + "null stored hash");
        AuthUser untouched = mustFind(clause, id);
        check(clause, old.equals(untouched.getPasswordHash()) && untouched.getTokenVersion() == v,
                "a refused changePassword changed the row (ver " + v + " -> " + untouched.getTokenVersion() + ")");
        check(clause, call(clause, "changePassword", () -> store.changePassword(id, old, v, next)),
                "changePassword with the stored hash and ver returned false");
        AuthUser changed = mustFind(clause, id);
        check(clause, next.equals(changed.getPasswordHash()) && changed.getTokenVersion() == v + 1,
                "changePassword must store the new hash and raise ver by one (ver " + v + " -> "
                        + changed.getTokenVersion() + ")");
        check(clause, !call(clause, "changePassword", () -> store.changePassword(id, next, v, hash("again"))),
                "changePassword with the version from before the change (ver mismatch) returned true");
        delete(clause, id);
        check(clause, !call(clause, "changePassword", () -> store.changePassword(id, next, v + 1, hash("gone"))),
                "changePassword on a deleted user returned true");
    }

    /** updatePasswordHash, setEnabled, bumpTokenVersion and setRoles: results and stored values. */
    // @req FR-IAM-004
    private void fieldMethods() {
        String clause = "field methods";
        String h1 = hash("f1");
        AuthUser u = create(clause, user(run + "-fields", h1).roles("contract-role").build());
        String id = u.getId();
        long v = mustFind(clause, id).getTokenVersion();

        String h2 = hash("f2");
        check(clause, !call(clause, "updatePasswordHash", () -> store.updatePasswordHash(id, hash("wrong"), h2)),
                "updatePasswordHash with a wrong expectedOldHash returned true");
        check(clause, h1.equals(mustFind(clause, id).getPasswordHash()),
                "a refused updatePasswordHash changed the hash");
        check(clause, call(clause, "updatePasswordHash", () -> store.updatePasswordHash(id, h1, h2)),
                "updatePasswordHash with the stored hash returned false");
        AuthUser rehashed = mustFind(clause, id);
        check(clause, h2.equals(rehashed.getPasswordHash()), "updatePasswordHash did not store the new hash");
        check(clause, rehashed.getTokenVersion() == v, "updatePasswordHash changed ver (" + v + " -> "
                + rehashed.getTokenVersion() + "); a rehash keeps the token version");

        AuthUser sso = create(clause, user(run + "-fields-sso", null).build());
        String h3 = hash("f3");
        check(clause, call(clause, "updatePasswordHash", () -> store.updatePasswordHash(sso.getId(), null, h3)),
                "updatePasswordHash(id, null, newHash) on a user with a null hash returned false");
        check(clause, h3.equals(mustFind(clause, sso.getId()).getPasswordHash()),
                "updatePasswordHash on a null-hash user did not store the new hash");

        check(clause, call(clause, "setEnabled", () -> store.setEnabled(id, false)), "setEnabled(id, false) returned false");
        check(clause, !mustFind(clause, id).isEnabled(), "setEnabled(id, false) did not disable the user");
        check(clause, call(clause, "setEnabled", () -> store.setEnabled(id, true)), "setEnabled(id, true) returned false");
        check(clause, mustFind(clause, id).isEnabled(), "setEnabled(id, true) did not enable the user");

        check(clause, call(clause, "bumpTokenVersion", () -> store.bumpTokenVersion(id)), "bumpTokenVersion returned false");
        long bumped = mustFind(clause, id).getTokenVersion();
        check(clause, bumped == v + 1, "bumpTokenVersion must raise ver by one (" + v + " -> " + bumped + ")");

        final Set<String> roles = new LinkedHashSet<>(Arrays.asList("contract-a", "contract-b"));
        check(clause, call(clause, "setRoles", () -> store.setRoles(id, roles)), "setRoles returned false");
        check(clause, mustFind(clause, id).getRoles().equals(roles), "setRoles stored "
                + mustFind(clause, id).getRoles() + " instead of " + roles);
        check(clause, call(clause, "setRoles", () -> store.setRoles(id, Collections.<String>emptySet())),
                "setRoles(id, no roles) returned false");
        check(clause, mustFind(clause, id).getRoles().isEmpty(), "setRoles(id, no roles) kept roles");
        check(clause, mustFind(clause, id).getTokenVersion() == bumped,
                "setEnabled or setRoles changed ver; only bumpTokenVersion and changePassword raise it");

        delete(clause, id);
        check(clause, !call(clause, "updatePasswordHash", () -> store.updatePasswordHash(id, h2, h1)),
                "updatePasswordHash on a deleted user returned true");
        check(clause, !call(clause, "setEnabled", () -> store.setEnabled(id, false)),
                "setEnabled on a deleted user returned true");
        check(clause, !call(clause, "bumpTokenVersion", () -> store.bumpTokenVersion(id)),
                "bumpTokenVersion on a deleted user returned true");
        check(clause, !call(clause, "setRoles", () -> store.setRoles(id, roles)),
                "setRoles on a deleted user returned true");
    }

    /** A store declaring atomic field writes must not write passwordHash, ver, enabled or roles from update(). */
    // @req FR-IAM-004
    // @req REL-IAM-003
    private void atomicUpdate() {
        String clause = "update() and the security fields";
        AuthUser u = create(clause, user(run + "-atomic", hash("atomic")).roles("contract-role").build());
        AuthUser before = mustFind(clause, u.getId());
        AuthUser passed = before.toBuilder().passwordHash(hash("overwritten"))
                .tokenVersion(before.getTokenVersion() + 7).enabled(!before.isEnabled())
                .roles("contract-overwritten").attribute("contract", "atomic").build();
        call(clause, "update", () -> store.update(passed));
        AuthUser after = mustFind(clause, u.getId());
        List<String> broken = new ArrayList<>();
        if (!Objects.equals(before.getPasswordHash(), after.getPasswordHash())) {
            broken.add("update() overwrote passwordHash from the passed row");
        }
        if (before.getTokenVersion() != after.getTokenVersion()) {
            broken.add("update() overwrote ver from the passed row (" + before.getTokenVersion() + " -> "
                    + after.getTokenVersion() + ")");
        }
        if (before.isEnabled() != after.isEnabled()) {
            broken.add("update() overwrote enabled from the passed row (" + before.isEnabled() + " -> "
                    + after.isEnabled() + ")");
        }
        if (!before.getRoles().equals(after.getRoles())) {
            broken.add("update() overwrote roles from the passed row (" + before.getRoles() + " -> "
                    + after.getRoles() + ")");
        }
        if (!broken.isEmpty()) {
            StringBuilder m = new StringBuilder();
            for (String b : broken) {
                m.append(m.length() == 0 ? "" : "; ").append(b);
            }
            m.append(". A store returning true from supportsAtomicFieldWrites() changes the security fields only "
                    + "through its field methods; return false (SINGLE_INSTANCE) if update() writes the whole row");
            throw fail(clause, m.toString(), null);
        }
    }

    // ------------------------------------------------------------------------------------------- helpers

    private static AuthUser.Builder user(String name, @Nullable String hash) {
        return AuthUser.builder().username(name.toLowerCase(Locale.ROOT)).passwordHash(hash);
    }

    private String hash(String what) {
        return "$contract$" + run + "$" + what;
    }

    private static String describe(@Nullable String hash) {
        return hash == null ? "null" : "a different value";
    }

    private AuthUser create(String clause, AuthUser user) {
        AuthUser stored = call(clause, "create", () -> store.create(user));
        check(clause, stored != null, "create() returned null");
        String id = stored.getId();
        check(clause, id != null, "create() returned a user without an id");
        created.add(id);
        return stored;
    }

    private void delete(String clause, String id) {
        check(clause, call(clause, "delete", () -> store.delete(id)), "delete() of an existing user returned false");
    }

    private AuthUser mustFind(String clause, @Nullable String id) {
        if (id == null) {
            throw fail(clause, "a user without an id", null);
        }
        AuthUser u = call(clause, "findById", () -> store.findById(id));
        check(clause, u != null, "findById(" + id + ") returned null for an existing user");
        return u;
    }

    private void cleanUp() {
        for (String id : created) {
            try {
                store.delete(id);
            } catch (Exception | AssertionError ignored) {
                // best effort: the contract result stands
            }
        }
    }

    private static <T> T call(String clause, String method, Call<T> call) {
        try {
            return call.run();
        } catch (Exception e) {
            throw fail(clause, method + " threw " + e, e);
        }
    }

    private static void check(String clause, boolean holds, String message) {
        if (!holds) {
            throw fail(clause, message, null);
        }
    }

    private static AssertionError fail(String clause, String message, @Nullable Throwable cause) {
        AssertionError e = new AssertionError("UserStore contract (" + clause + "): " + message);
        if (cause != null) {
            e.initCause(cause);
        }
        return e;
    }
}
