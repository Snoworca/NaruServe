package com.snoworca.naruserve.utils.auth.password;

import com.snoworca.naruserve.ConsoleLogger;
import com.snoworca.naruserve.Logger;
import com.snoworca.naruserve.utils.auth.internal.Blowfish;
import com.snoworca.naruserve.utils.auth.internal.ConstantTime;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;

/**
 * The legacy bcrypt verifier, written in-house (no library): it reads {@code $2a$}, {@code $2b$} and {@code $2y$}
 * hashes (OpenBSD, jBCrypt, PHP {@code password_hash}) and refuses {@code $2x$}, the marker of the old crypt_blowfish
 * sign-extension bug. A stored cost above {@link Builder#maxCost(int)} (default 14) is refused before any work and
 * logged as one ERROR (at most once a minute), so a planted cost-31 hash cannot pin a CPU. Like every bcrypt, only the first 72 UTF-8 bytes of
 * a password count. {@link #needsRehash(String)} is always true: a successful bcrypt check should move the account to
 * PBKDF2. {@link #hash(char[])} works too (for tests and migrations), but PBKDF2 stays the default.
 *
 * <pre>{@code
 * PasswordHasher legacy = PasswordHashers.bcrypt();
 * boolean ok = legacy.verify(attempt, "$2y$10$...");
 * }</pre>
 */
// @req SEC-TOKEN-008
public final class BcryptPasswordHasher implements PasswordHasher {

    private static final String ALPHABET = "./ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int[] INDEX = new int[128];
    private static final SecureRandom RANDOM = new SecureRandom();

    static {
        Arrays.fill(INDEX, -1);
        for (int i = 0; i < ALPHABET.length(); i++) {
            INDEX[ALPHABET.charAt(i)] = i;
        }
    }

    private final int maxCost;
    private final int cost;
    private final Logger logger;
    private final AtomicLong lastCostError = new AtomicLong(Long.MIN_VALUE);

    private BcryptPasswordHasher(Builder b) {
        this.maxCost = b.maxCost;
        this.cost = b.cost;
        this.logger = b.logger != null ? b.logger : new ConsoleLogger();
    }

    /** A builder: cost 10 for new hashes, stored cost at most 14, errors to the console logger. */
    // @req SEC-TOKEN-008
    public static Builder builder() {
        return new Builder();
    }

    /** The highest stored cost {@link #verify} runs. */
    public int getMaxCost() {
        return maxCost;
    }

    /** True for {@code $2a$}, {@code $2b$} and {@code $2y$} strings; {@code $2x$} and anything else are not bcrypt here. */
    // @req SEC-TOKEN-008
    @Override
    public boolean accepts(String stored) {
        return stored.startsWith("$2a$") || stored.startsWith("$2b$") || stored.startsWith("$2y$");
    }

    // @req SEC-TOKEN-008
    @Override
    public String hash(char[] password) {
        if (password.length == 0) {
            throw new IllegalArgumentException("an empty password cannot be hashed");
        }
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        byte[] key = key(password);
        try {
            byte[] digest = Blowfish.bcrypt(key, salt, cost);
            return "$2b$" + (cost < 10 ? "0" : "") + cost + "$" + encode(salt) + encode(digest);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    // @req SEC-TOKEN-008
    @Override
    public boolean verify(char[] password, String stored) {
        if (!accepts(stored) || stored.length() != 60 || stored.charAt(6) != '$' || password.length == 0) {
            return false;
        }
        char c1 = stored.charAt(4);
        char c2 = stored.charAt(5);
        if (c1 < '0' || c1 > '9' || c2 < '0' || c2 > '9') {
            return false;
        }
        int storedCost = (c1 - '0') * 10 + (c2 - '0');
        if (storedCost < 4) {
            return false;
        }
        if (storedCost > maxCost) {
            long now = System.nanoTime();
            long last = lastCostError.get();
            if (last != Long.MIN_VALUE && now - last < 60_000_000_000L || !lastCostError.compareAndSet(last, now)) {
                return false; // logged within the last minute
            }
            logger.error("auth: a stored bcrypt hash asks for cost " + storedCost + ", above the limit " + maxCost
                    + "; it is refused without hashing (raise BcryptPasswordHasher.builder().maxCost(n) if it is real; "
                    + "logged at most once a minute)");
            return false;
        }
        String rest = stored.substring(7);
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c >= 128 || INDEX[c] < 0) {
                return false;
            }
        }
        byte[] salt = decode(rest.substring(0, 22), 16);
        byte[] expected = decode(rest.substring(22), 23);
        byte[] key = key(password);
        try {
            byte[] actual = Blowfish.bcrypt(key, salt, storedCost);
            return ConstantTime.equals(actual, expected);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    /** Always true: a bcrypt account moves to the default hasher on its next successful login. */
    // @req SEC-TOKEN-008
    @Override
    public boolean needsRehash(String stored) {
        return true;
    }

    /** The UTF-8 password with its terminating zero byte, cut to the 72 bytes bcrypt reads. */
    private static byte[] key(char[] password) {
        ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(password));
        int n = Math.min(encoded.remaining() + 1, 72);
        byte[] key = new byte[n];
        encoded.get(key, 0, Math.min(encoded.remaining(), n));
        if (encoded.hasArray()) {
            Arrays.fill(encoded.array(), (byte) 0);
        }
        return key;
    }

    /** bcrypt's base64: its own alphabet, no padding. */
    static String encode(byte[] d) {
        StringBuilder b = new StringBuilder();
        int off = 0;
        while (off < d.length) {
            int c1 = d[off++] & 0xff;
            b.append(ALPHABET.charAt(c1 >>> 2));
            c1 = (c1 & 0x03) << 4;
            if (off >= d.length) {
                b.append(ALPHABET.charAt(c1));
                break;
            }
            int c2 = d[off++] & 0xff;
            b.append(ALPHABET.charAt(c1 | (c2 >>> 4)));
            c1 = (c2 & 0x0f) << 2;
            if (off >= d.length) {
                b.append(ALPHABET.charAt(c1));
                break;
            }
            c2 = d[off++] & 0xff;
            b.append(ALPHABET.charAt(c1 | (c2 >>> 6)));
            b.append(ALPHABET.charAt(c2 & 0x3f));
        }
        return b.toString();
    }

    /** The first {@code length} bytes of bcrypt-base64 {@code s} (every character already checked). */
    static byte[] decode(String s, int length) {
        byte[] out = new byte[length];
        int o = 0;
        int i = 0;
        while (o < length && i + 1 < s.length()) {
            int c1 = INDEX[s.charAt(i++)];
            int c2 = INDEX[s.charAt(i++)];
            out[o++] = (byte) ((c1 << 2) | (c2 >>> 4));
            if (o >= length || i >= s.length()) {
                break;
            }
            int c3 = INDEX[s.charAt(i++)];
            out[o++] = (byte) (((c2 & 0x0f) << 4) | (c3 >>> 2));
            if (o >= length || i >= s.length()) {
                break;
            }
            int c4 = INDEX[s.charAt(i++)];
            out[o++] = (byte) (((c3 & 0x03) << 6) | c4);
        }
        return out;
    }

    @Override
    public String toString() {
        return "BcryptPasswordHasher{cost=" + cost + ", maxCost=" + maxCost + "}";
    }

    /** Configures a {@link BcryptPasswordHasher}. */
    public static final class Builder {
        private int maxCost = 14;
        private int cost = 10;
        private @Nullable Logger logger;

        private Builder() {
        }

        /** The highest stored cost {@code verify} runs (4..31); 14 when not called. */
        // @req SEC-TOKEN-008
        public Builder maxCost(int maxCost) {
            if (maxCost < 4 || maxCost > 31) {
                throw new IllegalArgumentException("bcrypt maxCost must be 4..31: " + maxCost);
            }
            this.maxCost = maxCost;
            return this;
        }

        /** The cost of new hashes (4..31); 10 when not called. */
        public Builder cost(int cost) {
            if (cost < 4 || cost > 31) {
                throw new IllegalArgumentException("bcrypt cost must be 4..31: " + cost);
            }
            this.cost = cost;
            return this;
        }

        /** Where the refused-cost ERROR goes; the console logger when not called. */
        public Builder logger(Logger logger) {
            this.logger = Objects.requireNonNull(logger, "logger");
            return this;
        }

        /** The hasher. */
        public BcryptPasswordHasher build() {
            return new BcryptPasswordHasher(this);
        }
    }
}
