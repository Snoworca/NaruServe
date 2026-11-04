package com.snoworca.naruserve.utils.auth.password;

import com.snoworca.naruserve.utils.auth.internal.Base64Url;
import com.snoworca.naruserve.utils.auth.internal.ConstantTime;
import com.snoworca.naruserve.utils.auth.internal.Pbkdf2;
import java.security.SecureRandom;
import java.util.Arrays;
import org.jspecify.annotations.Nullable;

/**
 * The default {@link PasswordHasher}: PBKDF2-HMAC-SHA256 written over {@code javax.crypto.Mac} (Android 5.0 has no
 * {@code PBKDF2WithHmacSHA256}), in the string format {@code pbkdf2-sha256$<iterations>$<salt>$<hash>} with a 16-byte
 * salt and a 32-byte key, both unpadded base64url. The iteration count travels in the string, so changing the cost
 * never breaks stored hashes; {@link #needsRehash(String)} is true when the stored count is below 80 % or above 125 %
 * of this hasher's count. Auth builds it from {@link HashCost}; build one directly for code outside auth.
 *
 * <pre>{@code
 * PasswordHasher h = new Pbkdf2PasswordHasher(600_000);
 * String stored = h.hash(password);
 * boolean ok = h.verify(attempt, stored);
 * }</pre>
 */
// @req SEC-TOKEN-007
// @req PERF-TOKEN-001
public final class Pbkdf2PasswordHasher implements PasswordHasher {

    /** The prefix of every hash string this hasher makes and accepts. */
    public static final String PREFIX = "pbkdf2-sha256$";

    /**
     * The most iterations a stored hash may ask for (and {@code HashCost.fixed} may set): a larger count is refused
     * without hashing, so a planted hash cannot hold a hash slot for long.
     */
    public static final int MAX_STORED_ITERATIONS = 2_000_000;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final int iterations;

    /** A hasher making new hashes with {@code iterations} rounds (at least 1). */
    // @req SEC-TOKEN-007
    public Pbkdf2PasswordHasher(int iterations) {
        if (iterations < 1 || iterations > MAX_STORED_ITERATIONS) {
            throw new IllegalArgumentException("PBKDF2 iterations must be 1.." + MAX_STORED_ITERATIONS + ": "
                    + iterations);
        }
        this.iterations = iterations;
    }

    /** The rounds of new hashes. */
    public int getIterations() {
        return iterations;
    }

    // @req SEC-TOKEN-007
    @Override
    public boolean accepts(String stored) {
        return stored.startsWith(PREFIX);
    }

    // @req SEC-TOKEN-007
    @Override
    public String hash(char[] password) {
        if (password.length == 0) {
            throw new IllegalArgumentException("an empty password cannot be hashed");
        }
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        byte[] key = Pbkdf2.derive(password, salt, iterations, 32);
        try {
            return PREFIX + iterations + "$" + Base64Url.encode(salt) + "$" + Base64Url.encode(key);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    // @req SEC-TOKEN-007
    @Override
    public boolean verify(char[] password, String stored) {
        Pbkdf2.Stored p = parse(stored);
        if (p == null || password.length == 0) {
            return false;
        }
        byte[] actual = Pbkdf2.derive(password, p.salt, p.iterations, p.key.length);
        try {
            return ConstantTime.equals(actual, p.key);
        } finally {
            Arrays.fill(actual, (byte) 0);
        }
    }

    /** True when the stored count is below 80 % or above 125 % of {@link #getIterations()}, or it is not a PBKDF2 hash. */
    // @req PERF-TOKEN-001
    @Override
    public boolean needsRehash(String stored) {
        Pbkdf2.Stored p = parse(stored);
        if (p == null) {
            return true;
        }
        long stored100 = 100L * p.iterations;
        return stored100 < 80L * iterations || stored100 > 125L * iterations;
    }

    /** A well-formed stored string with a bounded cost, or null. */
    private static Pbkdf2.@Nullable Stored parse(String stored) {
        return Pbkdf2.parseStored(stored, PREFIX, MAX_STORED_ITERATIONS);
    }

    @Override
    public String toString() {
        return "Pbkdf2PasswordHasher{iterations=" + iterations + "}";
    }
}
