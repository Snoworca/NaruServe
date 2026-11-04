package com.snoworca.naruserve.utils.auth;

import com.snoworca.naruserve.utils.auth.internal.DeviceTokens;
import com.snoworca.naruserve.utils.auth.internal.FailureFloor;
import com.snoworca.naruserve.utils.auth.internal.HashSlots;
import com.snoworca.naruserve.utils.auth.internal.LoginLimits;
import com.snoworca.naruserve.utils.auth.internal.Lockouts;
import com.snoworca.naruserve.utils.auth.internal.Passwords;
import com.snoworca.naruserve.utils.auth.internal.RehashQueue;
import com.snoworca.naruserve.utils.auth.internal.Usernames;
import com.snoworca.naruserve.utils.auth.spi.Authenticator;
import com.snoworca.naruserve.utils.auth.spi.DeviceToken;
import com.snoworca.naruserve.utils.auth.spi.DeviceTrust;
import com.snoworca.naruserve.utils.auth.user.AuthUser;
import com.snoworca.naruserve.utils.auth.user.UserStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.jspecify.annotations.Nullable;

/**
 * The password-login sequence behind {@code POST /auth/login}: the check order
 * of 00 R34 (2) and the constant work of R38.
 *
 * <ol>
 * <li>A device token that passes signature, expiry and fingerprint defers the IP key; otherwise the IP key is checked
 * first.</li>
 * <li>The {@code (user, IP)} key.</li>
 * <li>{@code findByUsername}; trust holds when the token's user id and version match the stored user and the
 * {@link DeviceTrust} has not invalidated the token. A trusted device is exempt from the user-only and IP keys;
 * otherwise the deferred IP key and the user-only key are checked.</li>
 * <li>One hash slot running exactly one PBKDF2 computation ({@link Passwords#verifyForLogin}).</li>
 * </ol>
 *
 * <p>A limit hit in steps 1 to 3 is {@link Kind#THROTTLED} (429, no slot, no hash, no floor). Every outcome of an attempt
 * that reached step 4 other than success, the 503 of a full slot queue included, is answered through the
 * {@link FailureFloor}. A failure counts under the keys that applied and, with a trusted device, against the device
 * token (5 per 15 minutes invalidate it). A success issues a new device token and queues a rehash of a legacy or
 * below-target hash outside the slot.
 */
// @req SEC-FLOW-012
// @req SEC-TOKEN-009
// @req SEC-FLOW-013
// @req FR-FLOW-004
final class PasswordLogin {

    /** The per-device-token failure limit: 5 failures ... */
    static final int DEVICE_FAILURES = 5;
    /** ... within 15 minutes. */
    static final long DEVICE_WINDOW_MILLIS = 15 * 60_000L;

    /** How an attempt ended. */
    enum Kind {
        /** The password matched an enabled user. */
        SUCCESS,
        /** Wrong name or password, disabled user, or no password: 401 {@code invalid_credentials}. */
        INVALID_CREDENTIALS,
        /** A login limit blocked the request before the hash: 429 {@code too_many_attempts}. */
        THROTTLED,
        /** No hash slot was free (or hashing failed): 503. */
        UNAVAILABLE
    }

    /** The outcome of one login request. */
    static final class Outcome {
        final Kind kind;
        final @Nullable AuthUser user;
        final long retryAfterMillis;
        final boolean trustedDevice;
        final DeviceTokens.@Nullable Issued deviceToken;
        final @Nullable Throwable error;

        Outcome(Kind kind, @Nullable AuthUser user, long retryAfterMillis, boolean trustedDevice,
                DeviceTokens.@Nullable Issued deviceToken, @Nullable Throwable error) {
            this.kind = kind;
            this.user = user;
            this.retryAfterMillis = retryAfterMillis;
            this.trustedDevice = trustedDevice;
            this.deviceToken = deviceToken;
            this.error = error;
        }

        /** True when the answer waits for the failure floor: every attempt that reached the hash step and failed. */
        boolean floored() {
            return kind == Kind.INVALID_CREDENTIALS || kind == Kind.UNAVAILABLE;
        }

        @Override
        public String toString() {
            return "Outcome{" + kind + (trustedDevice ? ", trusted device" : "") + "}";
        }
    }

    /** Receives the outcome; on the after-hash executor, or on the caller for a throttled request. */
    @FunctionalInterface
    interface Done {
        void done(Outcome outcome);
    }

    private final UserStore users;
    private final LoginLimits limits;
    private final DeviceTokens deviceTokens;
    private final DeviceTrust trust;
    private final Passwords passwords;
    private final @Nullable RehashQueue rehash;
    private final Executor afterHash;
    private final @Nullable Lockouts lockouts;
    private final @Nullable Authenticator custom;

    /** A sequence finishing each attempt (counting, device token, outcome) on the caller of the hash callback. */
    PasswordLogin(UserStore users, LoginLimits limits, DeviceTokens deviceTokens, DeviceTrust trust,
            Passwords passwords, @Nullable RehashQueue rehash) {
        this(users, limits, deviceTokens, trust, passwords, rehash, Runnable::run);
    }

    /**
     * A sequence whose work after the hash (store writes and the outcome callback) runs on {@code afterHash}, so the
     * hash threads only hash (HashSlots callbacks must not block); a rejected task runs on the hash thread.
     */
    PasswordLogin(UserStore users, LoginLimits limits, DeviceTokens deviceTokens, DeviceTrust trust,
            Passwords passwords, @Nullable RehashQueue rehash, Executor afterHash) {
        this(users, limits, deviceTokens, trust, passwords, rehash, afterHash, null, null);
    }

    /**
     * The sequence of {@code POST /auth/login}: as above, with the optional hard lockout (FR-FLOW-009: a correct
     * password of a locked account without a trusted device fails exactly like a wrong one, after the hash) and the
     * optional custom {@link Authenticator} that replaces the store-and-hasher check of step 4 (IR-TOKEN-001).
     */
    // @req FR-FLOW-009
    // @req IR-TOKEN-001
    PasswordLogin(UserStore users, LoginLimits limits, DeviceTokens deviceTokens, DeviceTrust trust,
            Passwords passwords, @Nullable RehashQueue rehash, Executor afterHash, @Nullable Lockouts lockouts,
            @Nullable Authenticator custom) {
        this.lockouts = lockouts;
        this.custom = custom;
        this.users = users;
        this.limits = limits;
        this.deviceTokens = deviceTokens;
        this.trust = trust;
        this.passwords = passwords;
        this.rehash = rehash;
        this.afterHash = afterHash;
    }

    /**
     * One login of {@code name} with {@code password} from {@code ip}, carrying {@code deviceToken} or null. Steps 1 to
     * 3 run on the caller's thread and a store or throttle failure there is thrown (503, not an attempt); step 4 runs
     * in a hash slot without holding the caller. The password array is owned by this call afterwards and wiped.
     */
    // @req SEC-FLOW-012
    // @req SEC-TOKEN-009
    void attempt(String name, final char[] password, final String ip, @Nullable String deviceToken, final Done done)
            throws Exception {
        attempt(name, password, ip, deviceToken, null, done);
    }

    /** {@link #attempt(String, char[], String, String, Done)} with the event context a custom authenticator sees. */
    // @req SEC-FLOW-012
    // @req IR-TOKEN-001
    void attempt(final String name, final char[] password, final String ip, @Nullable String deviceToken,
            @Nullable AuthEventContext context, final Done done) throws Exception {
        final AuthEventContext ctx = context != null ? context : new AuthEventContext(null, null, null, 0L);
        final String key = key(name);
        final DeviceToken device;
        final AuthUser user;
        final boolean trusted;
        try {
            device = deviceToken == null || deviceToken.isEmpty() ? null : deviceTokens.read(deviceToken, key);
            long wait = 0;
            if (device == null) {
                wait = limits.ipWait(ip); // step 1
            }
            if (wait <= 0) {
                wait = limits.userIpWait(key, ip); // step 2
            }
            if (wait > 0) {
                Arrays.fill(password, '\0');
                done.done(new Outcome(Kind.THROTTLED, null, wait, false, null, null));
                return;
            }
            user = valid(name) ? users.findByUsername(key) : null; // step 3
            trusted = device != null && user != null && device.getUserId().equals(user.getId())
                    && device.getTokenVersion() == user.getTokenVersion() && !trust.isInvalidated(device);
            if (!trusted) {
                if (device != null) {
                    wait = limits.ipWait(ip); // the deferred IP key
                }
                if (wait <= 0) {
                    wait = limits.userWait(key);
                }
                if (wait > 0) {
                    Arrays.fill(password, '\0');
                    done.done(new Outcome(Kind.THROTTLED, null, wait, false, null, null));
                    return;
                }
            }
        } catch (Exception e) {
            Arrays.fill(password, '\0');
            throw e;
        }
        final String stored = user == null ? null : user.getPasswordHash();
        final Authenticator auth = custom;
        final AuthUser[] found = {user};
        Callable<Passwords.LoginCheck> work = auth == null
                ? () -> passwords.verifyForLogin(password, stored) // step 4
                : () -> {
                    // the custom check replaces the store and hasher; it still runs in the slot and its failures
                    // still wait for the floor
                    char[] copy = Arrays.copyOf(password, password.length);
                    AuthUser u;
                    try {
                        u = auth.authenticate(name, copy, ctx);
                    } finally {
                        Arrays.fill(copy, '\0');
                    }
                    found[0] = u;
                    return new Passwords.LoginCheck(u != null, false);
                };
        HashSlots slots = passwords.slots();
        HashSlots.Callback<Passwords.LoginCheck> after = (check, error) -> {
            Completion rest = new Completion(password, trusted, done) {
                @Override
                Outcome work() throws Exception {
                    return finish(key, ip, found[0], trusted ? device : null, password, check, error);
                }
            };
            try {
                afterHash.execute(rest);
            } catch (RejectedExecutionException stopping) {
                rest.run();
            }
        };
        if (slots == null) {
            Passwords.LoginCheck check = null;
            Throwable error = null;
            try {
                check = work.call();
            } catch (Exception e) {
                error = e;
            }
            after.done(check, error);
        } else {
            slots.submit(work, after);
        }
    }

    /**
     * The work after the hash of one attempt, queued on the after-hash executor. It runs once: either {@link #run} or,
     * when a stopping executor drops it, {@link #abandon}, which wipes the password and answers
     * {@link Kind#UNAVAILABLE} so the caller is never left without an outcome.
     */
    abstract static class Completion implements Runnable {
        private final char[] password;
        private final boolean trusted;
        private final Done done;
        private final java.util.concurrent.atomic.AtomicBoolean once = new java.util.concurrent.atomic.AtomicBoolean();

        Completion(char[] password, boolean trusted, Done done) {
            this.password = password;
            this.trusted = trusted;
            this.done = done;
        }

        abstract Outcome work() throws Exception;

        @Override
        public final void run() {
            if (!once.compareAndSet(false, true)) {
                return;
            }
            Outcome o;
            try {
                o = work();
            } catch (Exception e) {
                o = new Outcome(Kind.UNAVAILABLE, null, 0, trusted, null, e);
            } catch (Error e) {
                Arrays.fill(password, '\0');
                try {
                    done.done(new Outcome(Kind.UNAVAILABLE, null, 0, trusted, null, e)); // answer, then propagate
                } catch (RuntimeException answer) {
                    e.addSuppressed(answer);
                }
                throw e;
            } finally {
                Arrays.fill(password, '\0');
            }
            done.done(o);
        }

        /** The executor dropped this completion (auth is stopping): wipe and answer 503. */
        // @req SEC-TOKEN-009
        final void abandon() {
            if (!once.compareAndSet(false, true)) {
                return;
            }
            Arrays.fill(password, '\0');
            done.done(new Outcome(Kind.UNAVAILABLE, null, 0, trusted, null,
                    new AuthUnavailableException("auth is stopping")));
        }
    }

    /** The counting and the success work after the hash, outside the slot. */
    private Outcome finish(String key, String ip, @Nullable AuthUser user, @Nullable DeviceToken trusted,
            char[] password, Passwords.@Nullable LoginCheck check, @Nullable Throwable error) throws Exception {
        boolean device = trusted != null;
        if (error != null || check == null) {
            return new Outcome(Kind.UNAVAILABLE, null, 0, device, null, error);
        }
        if (custom != null && user != null && user.getId() != null && check.matched()) {
            final String found = user.getId();
            user = users.findById(found); // a custom authenticator's answer is judged on the stored row
            if (trusted != null && !trusted.getUserId().equals(found)) {
                trusted = null; // the device token named another user: no device exemption or device counting
            }
        }
        device = trusted != null;
        Lockouts lock = lockouts;
        String id = user == null ? null : user.getId();
        boolean locked = lock != null && id != null && !device && lock.isLocked(id);
        if (check.matched() && user != null && user.isEnabled() && !user.isServiceAccount() && id != null
                && !locked) {
            if (check.needsRehash() && rehash != null) {
                rehash.offer(user, Arrays.copyOf(password, password.length));
            }
            if (lock != null) {
                lock.release(id); // a success forgets the consecutive failures
            }
            DeviceTokens.Issued issued = deviceTokens.issue(id, user.getTokenVersion(), key);
            trust.issued(issued.content());
            return new Outcome(Kind.SUCCESS, user, 0, device, issued, null);
        }
        if (lock != null && id != null && !locked) {
            lock.recordFailure(id);
        }
        Exception first = null;
        try {
            limits.recordFailure(key, ip, device);
        } catch (Exception e) {
            first = e;
        }
        if (trusted != null) {
            try {
                trust.recordFailure(trusted, DEVICE_FAILURES, DEVICE_WINDOW_MILLIS); // counted even if a key failed
            } catch (Exception e) {
                first = first != null ? first : e;
            }
        }
        if (first != null) {
            throw first;
        }
        return new Outcome(Kind.INVALID_CREDENTIALS, null, 0, device, null, null);
    }

    /**
     * Answers {@code outcome} with {@code send}: at once for a success or a throttled request, through {@code floor}
     * for every other outcome (the request started at {@code startNanos} on the floor's clock).
     */
    // @req SEC-TOKEN-009
    static void respond(Outcome outcome, FailureFloor floor, long startNanos, Runnable send) {
        if (outcome.floored()) {
            floor.send(startNanos, send);
        } else {
            send.run();
        }
    }

    /**
     * The counting key of a login name: the normalized name, or for a name no user can have (blank, longer than 190
     * characters) {@code #} plus the SHA-256 of its text, a fixed length that fits every throttle key column.
     */
    private static String key(String name) {
        try {
            return Usernames.normalize(name);
        } catch (IllegalArgumentException invalid) {
            return "#" + sha256(name);
        }
    }

    private static String sha256(String text) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder(64);
            for (byte x : d) {
                b.append(Character.forDigit((x >> 4) & 0xf, 16)).append(Character.forDigit(x & 0xf, 16));
            }
            return b.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static boolean valid(String name) {
        try {
            Usernames.normalize(name);
            return true;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }
}
