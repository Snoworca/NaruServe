package com.snoworca.naruserve.utils.auth.internal;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The key-strength rules of {@code build()}: at least 32 bytes, at least 8 distinct byte values, and not a known
 * example value. A finding names the problem and the byte count, never the key.
 */
// @req SEC-AUTH-001
public final class SecretRules {

    /** The minimum HS256 key length (RFC 7518 §3.2). */
    public static final int MIN_BYTES = 32;

    /** The sample secret the auth guide shows; refused like the other examples. */
    public static final String DOCS_SAMPLE = "naru-docs-sample-secret-do-not-use-in-production";

    /** The well-known example secrets, compared after trimming and ignoring case and padding characters. */
    public static final List<String> EXAMPLES = Collections.unmodifiableList(Arrays.asList(
            "secret", "changeme", "change-me-now", "your-256-bit-secret", DOCS_SAMPLE));

    /** How to make a real one; every missing or weak key message carries it. */
    public static final String HOW_TO = "Generate one with: openssl rand -base64 32 — or, for a temporary key, build "
            + "with .mode(AuthMode.DEV), or with .mode(AuthMode.fromEnv()) with NARU_ENV=dev.";

    private SecretRules() {
    }

    /** The finding for {@code key}, or null when it is strong enough. */
    // @req SEC-AUTH-001
    public static @Nullable String weakness(byte[] key) {
        if (isExample(key)) {
            return "the signing key is a published example value. " + HOW_TO;
        }
        if (key.length < MIN_BYTES) {
            return "the signing key has " + key.length + " bytes; at least " + MIN_BYTES + " bytes are required. "
                    + HOW_TO;
        }
        boolean[] seen = new boolean[256];
        int distinct = 0;
        for (byte b : key) {
            if (!seen[b & 0xff]) {
                seen[b & 0xff] = true;
                distinct++;
            }
        }
        if (distinct < 8) {
            return "the signing key has only " + distinct + (distinct == 1 ? " distinct byte value" : " distinct byte values")
                    + "; at least 8 are required. "
                    + HOW_TO;
        }
        return null;
    }

    private static boolean isExample(byte[] key) {
        String text = strip(new String(key, StandardCharsets.UTF_8));
        for (String example : EXAMPLES) {
            if (text.equals(strip(example))) {
                return true;
            }
        }
        return false;
    }

    /** Lower case without padding characters ({@code = * - _ . 0 x} and spaces) at either end. */
    private static String strip(String s) {
        String t = s.trim().toLowerCase(Locale.ROOT);
        int a = 0;
        int b = t.length();
        while (a < b && isPad(t.charAt(a))) {
            a++;
        }
        while (b > a && isPad(t.charAt(b - 1))) {
            b--;
        }
        return t.substring(a, b);
    }

    private static boolean isPad(char c) {
        return c == '=' || c == '*' || c == '-' || c == '_' || c == '.' || c == '0' || c == ' ' || c == 'x';
    }
}
