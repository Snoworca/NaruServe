package com.snoworca.naruserve.internal;

import org.jspecify.annotations.Nullable;

/**
 * Reads a request {@code Content-Length} field value as the declared body length. A comma-separated list whose
 * elements, trimmed of optional whitespace, all agree on one value is that value (RFC 9112 section 6.3 item 5), as
 * the HTTP adapter frames it; anything else is no declared length, and the adapter frames the body.
 */
// @req FR-HTTP-031
// @req FR-JSON-001
final class DeclaredLength {

    private DeclaredLength() {
    }

    /**
     * The length every element of {@code value} agrees on, or -1 when the value is absent, an element is empty or not
     * ASCII decimal digits that fit a {@code long}, or two elements differ.
     */
    // @req FR-HTTP-031
    // @req FR-JSON-001
    static long of(@Nullable String value) {
        if (value == null) {
            return -1;
        }
        long agreed = -1;
        int start = 0;
        while (true) {
            int comma = value.indexOf(',', start);
            int end = comma < 0 ? value.length() : comma;
            long n = digits(value, start, end);
            if (n < 0 || (agreed >= 0 && n != agreed)) {
                return -1;
            }
            agreed = n;
            if (comma < 0) {
                return agreed;
            }
            start = comma + 1;
        }
    }

    /** {@code s[from, to)} without surrounding spaces and tabs as a decimal, or -1 when empty, not digits or too large. */
    private static long digits(String s, int from, int to) {
        while (from < to && isOws(s.charAt(from))) {
            from++;
        }
        while (to > from && isOws(s.charAt(to - 1))) {
            to--;
        }
        if (from == to) {
            return -1;
        }
        long n = 0;
        for (int i = from; i < to; i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
            int d = c - '0';
            if (n > (Long.MAX_VALUE - d) / 10) {
                return -1;
            }
            n = n * 10 + d;
        }
        return n;
    }

    private static boolean isOws(char c) {
        return c == ' ' || c == '\t';
    }
}
