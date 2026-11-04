package com.snoworca.naruserve.utils.auth.internal;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;

/**
 * PBKDF2-HMAC-SHA256 (RFC 8018) over {@link Mac}, written by hand: {@code SecretKeyFactory("PBKDF2WithHmacSHA256")}
 * is API 26+ on Android. This method wipes its own UTF-8 copy of the password; the copy {@link SecretKeySpec} keeps
 * inside the {@link Mac} is not reachable to wipe.
 */
// @req IR-IAM-003
// @req SEC-TOKEN-007
public final class Pbkdf2 {

    private Pbkdf2() {
    }

    /** The {@code length}-byte key derived from {@code password} and {@code salt} with {@code iterations} rounds. */
    // @req IR-IAM-003
    public static byte[] derive(char[] password, byte[] salt, int iterations, int length) {
        Mac mac;
        try {
            mac = Mac.getInstance("HmacSHA256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
        return derive(password, salt, iterations, length, mac);
    }

    /**
     * {@link #derive(char[], byte[], int, int)} over the given HmacSHA256 {@code mac}: the password key is set with
     * one {@code init}, never once per iteration, so a long password costs the same as a short one.
     */
    // @req SEC-TOKEN-007
    public static byte[] derive(char[] password, byte[] salt, int iterations, int length, Mac mac) {
        if (iterations < 1 || length < 1) {
            throw new IllegalArgumentException("iterations and length must be positive");
        }
        ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(password));
        byte[] pw = new byte[encoded.remaining()];
        encoded.get(pw);
        if (encoded.hasArray()) {
            Arrays.fill(encoded.array(), (byte) 0);
        }
        try {
            // HMAC keys may be empty in RFC 2104, but SecretKeySpec refuses them; one zero byte is the same HMAC key.
            mac.init(new SecretKeySpec(pw.length == 0 ? new byte[1] : pw, "HmacSHA256"));
            int hLen = mac.getMacLength();
            int blocks = (length + hLen - 1) / hLen;
            byte[] out = new byte[length];
            byte[] u = new byte[hLen];
            byte[] t = new byte[hLen];
            for (int i = 1; i <= blocks; i++) {
                mac.update(salt);
                mac.update(new byte[] {(byte) (i >>> 24), (byte) (i >>> 16), (byte) (i >>> 8), (byte) i});
                mac.doFinal(u, 0);
                System.arraycopy(u, 0, t, 0, hLen);
                for (int r = 1; r < iterations; r++) {
                    mac.update(u);
                    mac.doFinal(u, 0);
                    for (int k = 0; k < hLen; k++) {
                        t[k] ^= u[k];
                    }
                }
                int off = (i - 1) * hLen;
                System.arraycopy(t, 0, out, off, Math.min(hLen, length - off));
            }
            return out;
        } catch (InvalidKeyException | javax.crypto.ShortBufferException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        } finally {
            Arrays.fill(pw, (byte) 0);
        }
    }

    /**
     * The nanoseconds one derivation of {@code iterations} rounds takes on this device, after a short warm-up run (at
     * most 20,000 rounds), so a high fixed cost is timed with one full derivation.
     */
    // @req PERF-TOKEN-001
    public static long nanosFor(int iterations) {
        char[] pw = {'c', 'a', 'l', 'i', 'b', 'r', 'a', 't', 'e'};
        byte[] salt = new byte[16];
        derive(pw, salt, Math.min(iterations, 20_000), 32);
        long start = System.nanoTime();
        derive(pw, salt, iterations, 32);
        return Math.max(1L, System.nanoTime() - start);
    }

    /** The parts of a well-formed stored PBKDF2 string. */
    public static final class Stored {
        /** The rounds the string asks for. */
        public final int iterations;
        /** The salt. */
        public final byte[] salt;
        /** The 32-byte derived key. */
        public final byte[] key;

        Stored(int iterations, byte[] salt, byte[] key) {
            this.iterations = iterations;
            this.salt = salt;
            this.key = key;
        }
    }

    /**
     * The parts of {@code stored} in the format {@code <prefix><iterations>$<salt>$<hash>} (unpadded base64url, a salt
     * of 8 bytes or more and a 32-byte hash), or null when it is not well formed or asks for more than
     * {@code maxIterations} rounds, so a stored string never sets an unbounded cost for a request.
     */
    // @req SEC-TOKEN-007
    // @req SEC-TOKEN-009
    public static @Nullable Stored parseStored(String stored, String prefix, int maxIterations) {
        if (!stored.startsWith(prefix)) {
            return null;
        }
        String[] parts = stored.substring(prefix.length()).split("\\$", -1);
        if (parts.length != 3 || parts[0].isEmpty() || parts[0].length() > 8) {
            return null;
        }
        for (int i = 0; i < parts[0].length(); i++) {
            char c = parts[0].charAt(i);
            if (c < '0' || c > '9') {
                return null;
            }
        }
        try {
            int rounds = Integer.parseInt(parts[0]);
            byte[] salt = Base64Url.decode(parts[1]);
            byte[] key = Base64Url.decode(parts[2]);
            if (rounds < 1 || rounds > maxIterations || key.length != 32 || salt.length < 8) {
                return null;
            }
            return new Stored(rounds, salt, key);
        } catch (RuntimeException malformed) {
            return null;
        }
    }
}
