package com.snoworca.naruserve.utils.auth.internal;

import com.snoworca.naruserve.utils.auth.AuthUnavailableException;
import com.snoworca.naruserve.utils.auth.password.HashCost;
import com.snoworca.naruserve.utils.auth.password.PasswordHasher;
import com.snoworca.naruserve.utils.auth.password.Pbkdf2PasswordHasher;
import java.util.concurrent.Callable;
import org.jspecify.annotations.Nullable;

/**
 * The password hashing of one Auth: the configured {@link PasswordHasher} (a {@link Pbkdf2PasswordHasher} at the
 * resolved {@link HashCost} by default), every hash and verify inside the Auth's {@link HashSlots}. A missing user, a
 * hash the hasher does not accept, or a malformed hash in the default format still costs one verification against a
 * dummy hash, so timing does not tell them
 * apart. The cost is resolved on first use, so building an Auth costs no measurement unless it seeds a password.
 */
// @req IR-IAM-003
// @req SEC-TOKEN-007
// @req PERF-TOKEN-001
public final class Passwords {

    /** The prefix of the default format. */
    public static final String PREFIX = Pbkdf2PasswordHasher.PREFIX;

    private final @Nullable PasswordHasher configured;
    private final HashCost cost;
    private final HashTiming.Measurer measurer;
    private final @Nullable HashSlots slots;
    private volatile @Nullable HashTiming timing;
    private volatile @Nullable PasswordHasher hasher;
    private volatile @Nullable String dummy;

    /** PBKDF2 at exactly {@code iterations} rounds, outside any slot pool. */
    // @req IR-IAM-003
    public Passwords(int iterations) {
        this(null, HashCost.fixed(iterations), null);
    }

    /** {@code configured} (or PBKDF2 at {@code cost}) inside {@code slots} (none: the caller's thread). */
    // @req SEC-TOKEN-007
    public Passwords(@Nullable PasswordHasher configured, HashCost cost, @Nullable HashSlots slots) {
        this(configured, cost, slots, Pbkdf2::nanosFor);
    }

    /** As above, measuring the cost with {@code measurer}. */
    public Passwords(@Nullable PasswordHasher configured, HashCost cost, @Nullable HashSlots slots,
            HashTiming.Measurer measurer) {
        this.configured = configured;
        this.cost = cost;
        this.slots = slots;
        this.measurer = measurer;
    }

    /** The cost, resolved on this device on the first call. */
    // @req PERF-TOKEN-001
    public HashTiming timing() {
        HashTiming t = timing;
        if (t == null) {
            synchronized (this) {
                t = timing;
                if (t == null) {
                    t = HashTiming.resolve(cost, measurer);
                    timing = t;
                }
            }
        }
        return t;
    }

    /** The configured cost. */
    public HashCost cost() {
        return cost;
    }

    /** The hasher of new hashes and stored ones. */
    // @req SEC-TOKEN-007
    public PasswordHasher hasher() {
        PasswordHasher h = hasher;
        if (h == null) {
            h = configured != null ? configured : new Pbkdf2PasswordHasher(timing().iterations());
            hasher = h;
        }
        return h;
    }

    /** The slot pool, or null. */
    public @Nullable HashSlots slots() {
        return slots;
    }

    /** Makes the dummy hash now (the start-time doctor calls it), so no request pays for it. */
    public void prepare() {
        inSlot(new Callable<String>() {
            @Override
            public String call() {
                return dummy();
            }
        });
    }

    private String dummy() {
        String d = dummy;
        if (d == null) {
            d = hasher().hash(new char[] {'d', 'u', 'm', 'm', 'y'});
            dummy = d;
        }
        return d;
    }

    /** A new hash of {@code password}; {@link AuthUnavailableException} when no slot is free in time. */
    // @req IR-IAM-003
    public String hash(final char[] password) {
        return inSlot(new Callable<String>() {
            @Override
            public String call() {
                return hasher().hash(password);
            }
        });
    }

    /** True when {@code password} matches {@code hash}; a null or foreign hash is checked against a dummy and fails. */
    // @req IR-IAM-003
    public boolean verify(final char[] password, final @Nullable String hash) {
        return inSlot(new Callable<Boolean>() {
            @Override
            public Boolean call() {
                PasswordHasher h = hasher();
                boolean real = hash != null && h.accepts(hash) && !malformed(hash);
                String target = real && hash != null ? hash : dummy();
                boolean ok = h.verify(password, target);
                return ok && real;
            }
        });
    }

    /** What one login verification found: whether the password matched, and whether the stored hash should be redone. */
    public static final class LoginCheck {
        private final boolean matched;
        private final boolean rehash;

        /** A result; auth builds them for a custom authenticator's answer. */
        public LoginCheck(boolean matched, boolean rehash) {
            this.matched = matched;
            this.rehash = rehash;
        }

        /** True when the password matched the stored hash. */
        public boolean matched() {
            return matched;
        }

        /** True when the password matched and the stored hash is legacy or below the target cost. */
        public boolean needsRehash() {
            return rehash;
        }
    }

    /**
     * R38: the work of one login attempt, to run inside one hash slot, doing exactly one PBKDF2 computation whatever
     * the account: the real verify at the stored iteration count for a hash in the default format, the legacy verify
     * plus a target-cost dummy PBKDF2 for a hash another (legacy) hasher accepts, and a target-cost dummy PBKDF2 for a
     * null, unknown or missing hash (unknown users and users without a password) and for a string in the default
     * format that does not parse (malformed, or a cost above the stored-iteration limit). The dummy is the hash made at
     * start ({@link #prepare()}).
     */
    // @req SEC-TOKEN-009
    public LoginCheck verifyForLogin(char[] password, @Nullable String stored) {
        PasswordHasher h = hasher();
        if (stored != null && h.accepts(stored) && !malformed(stored)) {
            // legacy: a format the hasher migrates away from (a non-primary member of a chain); the default PBKDF2
            // format and the configured primary's own format are verified once, like any target-cost hash
            boolean legacy = !stored.startsWith(PREFIX) && h.needsRehash(stored);
            boolean ok = h.verify(password, stored);
            if (legacy) {
                h.verify(password, dummy()); // the legacy check is cheap: pad it with one target-cost PBKDF2
            }
            return new LoginCheck(ok, ok && h.needsRehash(stored));
        }
        h.verify(password, dummy());
        return new LoginCheck(false, false);
    }

    /**
     * True for a string in the default PBKDF2 format that does not parse (malformed, or a cost above
     * {@link Pbkdf2PasswordHasher#MAX_STORED_ITERATIONS}): the hasher refuses it without deriving anything, so it is
     * treated like an unknown hash and costs the target-cost dummy instead (SEC-TOKEN-009).
     */
    // @req SEC-TOKEN-009
    private static boolean malformed(String stored) {
        return stored.startsWith(PREFIX)
                && Pbkdf2.parseStored(stored, PREFIX, Pbkdf2PasswordHasher.MAX_STORED_ITERATIONS) == null;
    }

    private <T> T inSlot(Callable<T> work) {
        HashSlots s = slots;
        try {
            return s == null ? work.call() : s.call(work);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("password hashing failed", e);
        }
    }
}
