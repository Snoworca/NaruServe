package com.snoworca.naruserve.utils.auth.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import org.jspecify.annotations.Nullable;

/**
 * The API key format {@code nk_<prefix8>_<secret32>}, both parts base62 so the key is an RFC 9110 {@code token68}, and
 * the stored form of the secret: its SHA-256 as 64 lower-case hex characters. 32 base62 characters from
 * {@link SecureRandom} carry about 190 bits, so a fast unsalted hash is enough and no hash slot is used.
 */
// @req SEC-APIKEY-003
public final class ApiKeyFormat {

    /** The base62 alphabet. */
    public static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    private static final int PREFIX = 8;
    private static final int SECRET = 32;
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private ApiKeyFormat() {
    }

    /** The two parts of a key. */
    public static final class Key {
        private final String prefix;
        private final String secret;

        Key(String prefix, String secret) {
            this.prefix = prefix;
            this.secret = secret;
        }

        /** The 8 prefix characters, the store's lookup key. */
        public String prefix() {
            return prefix;
        }

        /** The 32 secret characters; never stored or logged. */
        public String secret() {
            return secret;
        }

        /** The whole key, returned to the caller exactly once at issuance. */
        public String plain() {
            return "nk_" + prefix + "_" + secret;
        }

        /** The prefix only; the secret is never shown. */
        @Override
        public String toString() {
            return "nk_" + prefix + "_********";
        }
    }

    /** A new key; every character is drawn uniformly with {@code nextInt(62)}, never a biased modulo. */
    // @req SEC-APIKEY-003
    public static Key generate(SecureRandom random) {
        return new Key(draw(random, PREFIX), draw(random, SECRET));
    }

    private static String draw(SecureRandom random, int n) {
        char[] out = new char[n];
        for (int i = 0; i < n; i++) {
            out[i] = ALPHABET.charAt(random.nextInt(ALPHABET.length()));
        }
        return new String(out);
    }

    /** The parts of an exact {@code nk_<8 base62>_<32 base62>} value, or null. */
    // @req SEC-APIKEY-003
    public static @Nullable Key parse(String value) {
        if (value.length() != 3 + PREFIX + 1 + SECRET || !value.startsWith("nk_") || value.charAt(3 + PREFIX) != '_') {
            return null;
        }
        String prefix = value.substring(3, 3 + PREFIX);
        String secret = value.substring(3 + PREFIX + 1);
        return isBase62(prefix) && isBase62(secret) ? new Key(prefix, secret) : null;
    }

    private static boolean isBase62(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9')) {
                return false;
            }
        }
        return true;
    }

    /** The lower-case hex SHA-256 of {@code secret}, 64 characters. */
    // @req SEC-APIKEY-003
    public static String secretHash(String secret) {
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing from this runtime", e);
        }
        char[] out = new char[digest.length * 2];
        for (int i = 0; i < digest.length; i++) {
            out[2 * i] = HEX[(digest[i] >> 4) & 0xF];
            out[2 * i + 1] = HEX[digest[i] & 0xF];
        }
        return new String(out);
    }
}
