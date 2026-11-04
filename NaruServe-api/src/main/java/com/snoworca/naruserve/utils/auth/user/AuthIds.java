package com.snoworca.naruserve.utils.auth.user;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Maps {@link AuthUser#getId()} strings to numeric and UUID store keys. A value that does not parse gives null, so a
 * store adapter's {@code findById} answers "not found" (404) rather than failing (500). Back to a String id is
 * {@code String.valueOf(n)} or {@code uuid.toString()}.
 */
// @req DR-IAM-001
public final class AuthIds {

    private AuthIds() {
    }

    /** The long of a canonical decimal id ("0" or no leading zero, no sign, no spaces), or null. */
    // @req DR-IAM-001
    public static @Nullable Long parseLong(String id) {
        int n = id.length();
        if (n == 0 || n > 19 || n > 1 && id.charAt(0) == '0') {
            return null;
        }
        long value = 0;
        for (int i = 0; i < n; i++) {
            char c = id.charAt(i);
            if (c < '0' || c > '9') {
                return null;
            }
            int digit = c - '0';
            if (value > (Long.MAX_VALUE - digit) / 10) {
                return null;
            }
            value = value * 10 + digit;
        }
        return value;
    }

    /** The UUID of a lower-case canonical 8-4-4-4-12 hex id, or null. */
    // @req DR-IAM-001
    public static @Nullable UUID parseUuid(String id) {
        if (id.length() != 36) {
            return null;
        }
        for (int i = 0; i < 36; i++) {
            char c = id.charAt(i);
            boolean dash = i == 8 || i == 13 || i == 18 || i == 23;
            if (dash ? c != '-' : !(c >= '0' && c <= '9' || c >= 'a' && c <= 'f')) {
                return null;
            }
        }
        return UUID.fromString(id);
    }
}
