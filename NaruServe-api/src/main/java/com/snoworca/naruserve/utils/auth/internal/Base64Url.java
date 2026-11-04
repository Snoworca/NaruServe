package com.snoworca.naruserve.utils.auth.internal;

/**
 * Unpadded base64url (RFC 4648 §5) written by hand: {@code java.util.Base64} is API 26+. Decoding is strict, so one
 * byte string has exactly one accepted encoding: padding, the standard alphabet, whitespace and non-zero unused low
 * bits of the last character are rejected.
 */
// @req SEC-TOKEN-001
public final class Base64Url {

    private static final char[] ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toCharArray();
    private static final int[] VALUES = new int[128];

    static {
        java.util.Arrays.fill(VALUES, -1);
        for (int i = 0; i < ALPHABET.length; i++) {
            VALUES[ALPHABET[i]] = i;
        }
    }

    private Base64Url() {
    }

    /** The unpadded base64url text of {@code bytes}. */
    // @req SEC-TOKEN-001
    public static String encode(byte[] bytes) {
        StringBuilder b = new StringBuilder((bytes.length * 4 + 2) / 3);
        int i = 0;
        for (; i + 2 < bytes.length; i += 3) {
            int v = (bytes[i] & 0xff) << 16 | (bytes[i + 1] & 0xff) << 8 | bytes[i + 2] & 0xff;
            b.append(ALPHABET[v >>> 18]).append(ALPHABET[v >>> 12 & 63]).append(ALPHABET[v >>> 6 & 63])
                    .append(ALPHABET[v & 63]);
        }
        int rest = bytes.length - i;
        if (rest == 1) {
            int v = (bytes[i] & 0xff) << 16;
            b.append(ALPHABET[v >>> 18]).append(ALPHABET[v >>> 12 & 63]);
        } else if (rest == 2) {
            int v = (bytes[i] & 0xff) << 16 | (bytes[i + 1] & 0xff) << 8;
            b.append(ALPHABET[v >>> 18]).append(ALPHABET[v >>> 12 & 63]).append(ALPHABET[v >>> 6 & 63]);
        }
        return b.toString();
    }

    /** The bytes of a canonical unpadded base64url text; {@link IllegalArgumentException} for anything else. */
    // @req SEC-TOKEN-001
    public static byte[] decode(String text) {
        int n = text.length();
        if (n % 4 == 1) {
            throw new IllegalArgumentException("base64url length " + n + " is not valid");
        }
        byte[] out = new byte[n * 3 / 4];
        int o = 0;
        int i = 0;
        for (; i + 3 < n; i += 4) {
            int v = value(text, i) << 18 | value(text, i + 1) << 12 | value(text, i + 2) << 6 | value(text, i + 3);
            out[o++] = (byte) (v >>> 16);
            out[o++] = (byte) (v >>> 8);
            out[o++] = (byte) v;
        }
        int rest = n - i;
        if (rest == 2) {
            int last = value(text, i + 1);
            if ((last & 15) != 0) {
                throw new IllegalArgumentException("base64url text is not canonical");
            }
            out[o] = (byte) (value(text, i) << 2 | last >>> 4);
        } else if (rest == 3) {
            int last = value(text, i + 2);
            if ((last & 3) != 0) {
                throw new IllegalArgumentException("base64url text is not canonical");
            }
            int v = value(text, i) << 12 | value(text, i + 1) << 6 | last;
            out[o++] = (byte) (v >>> 10);
            out[o] = (byte) (v >>> 2);
        }
        return out;
    }

    private static int value(String text, int index) {
        char c = text.charAt(index);
        int v = c < 128 ? VALUES[c] : -1;
        if (v < 0) {
            throw new IllegalArgumentException("base64url text has an invalid character at " + index);
        }
        return v;
    }
}
