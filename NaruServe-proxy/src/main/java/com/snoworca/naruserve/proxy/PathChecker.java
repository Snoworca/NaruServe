package com.snoworca.naruserve.proxy;

/**
 * The request-target character and path segment checks of the proxy (design D-050, D-052). The middleware applies
 * {@link #isSafeTarget} to the raw {@code originalUrl()} of every request, whatever {@code stripPrefix} says, and
 * {@code Proxy.to} is to apply {@link #isSafePath} to its base path (design D-072). A check only answers whether the
 * string passes; what a failure answers (400 for a request, {@code IllegalArgumentException} for {@code Proxy.to}) is
 * the caller's. Percent-encodings compare case-insensitively.
 *
 * <p>A path passes when it is empty or starts with {@code /}, no segment is {@code .} or {@code ..} or holds
 * {@code ;}, no segment is empty except one trailing {@code /}, it holds no percent-encoded unreserved character, no
 * {@code %2F}, {@code %5C} or {@code %00} and no raw backslash, and its once-decoded form holds none of those
 * encodings again ({@code %252e}). Path and query both hold no control character or space (0x00 to 0x20), no 0x7F,
 * no non-ASCII character, no {@code #} and no {@code %} without two hexadecimal digits after it. The query gets the
 * character rules only.
 */
// @req SEC-PROXY-001
final class PathChecker {

    private PathChecker() { }

    /**
     * Whether a raw origin-form request target, a path followed by an optional {@code ?} and query, passes. A target
     * whose path does not start with {@code /} (absolute-form, authority-form, {@code *}) never passes.
     */
    // @req SEC-PROXY-001
    static boolean isSafeTarget(String rawTarget) {
        int q = rawTarget.indexOf('?');
        String path = q < 0 ? rawTarget : rawTarget.substring(0, q);
        if (path.isEmpty()) {
            return false;
        }
        return isSafePath(path) && (q < 0 || charactersPass(rawTarget.substring(q + 1), false));
    }

    /** Whether a raw path passes; the empty path (no base path) does. */
    // @req SEC-PROXY-001
    static boolean isSafePath(String rawPath) {
        if (rawPath.isEmpty()) {
            return true;
        }
        if (rawPath.charAt(0) != '/' || !charactersPass(rawPath, true)) {
            return false;
        }
        int start = 1;
        while (true) {
            int end = rawPath.indexOf('/', start);
            if (end < 0) {
                // The last segment may be empty: one trailing slash, or the root path "/".
                return start == rawPath.length() || segmentPasses(rawPath, start, rawPath.length());
            }
            if (end == start || !segmentPasses(rawPath, start, end)) {
                return false;
            }
            start = end + 1;
        }
    }

    /** A non-empty segment is neither {@code .} nor {@code ..} and holds no {@code ;}. */
    private static boolean segmentPasses(String path, int start, int end) {
        int length = end - start;
        if ((length == 1 || length == 2) && path.charAt(start) == '.' && path.charAt(end - 1) == '.') {
            return false;
        }
        for (int i = start; i < end; i++) {
            if (path.charAt(i) == ';') {
                return false;
            }
        }
        return true;
    }

    /**
     * The character rules of a path ({@code inPath}) or a query: printable ASCII except {@code #}, every {@code %}
     * followed by two hexadecimal digits, and in a path no raw backslash and no encoding {@link #rejectedInPath}
     * refuses, directly or behind one {@code %25}.
     */
    private static boolean charactersPass(String s, boolean inPath) {
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c <= 0x20 || c >= 0x7F || c == '#' || (inPath && c == '\\')) {
                return false;
            }
            if (c != '%') {
                continue;
            }
            int b = hexByte(s, i + 1);
            if (b < 0) {
                return false;
            }
            if (inPath && (rejectedInPath(b) || (b == '%' && rejectedInPath(hexByte(s, i + 3))))) {
                return false;
            }
            i += 2;
        }
        return true;
    }

    /**
     * Whether a decoded byte may not appear percent-encoded in a path: an unreserved character (ALPHA, DIGIT,
     * {@code -}, {@code .}, {@code _}, {@code ~}), the slash, the backslash or NUL. A negative value (no encoding)
     * is not rejected.
     */
    private static boolean rejectedInPath(int b) {
        return (b >= 'A' && b <= 'Z') || (b >= 'a' && b <= 'z') || (b >= '0' && b <= '9') || b == '-' || b == '.'
                || b == '_' || b == '~' || b == '/' || b == '\\' || b == 0;
    }

    /** The byte the two ASCII hexadecimal digits at {@code at} spell, or -1 when there are not two there. */
    private static int hexByte(String s, int at) {
        if (at + 1 >= s.length()) {
            return -1;
        }
        int hi = hexDigit(s.charAt(at));
        int lo = hexDigit(s.charAt(at + 1));
        return hi < 0 || lo < 0 ? -1 : hi * 16 + lo;
    }

    /** The value of one ASCII hexadecimal digit, or -1; {@code Character.digit} would also take non-ASCII digits. */
    private static int hexDigit(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }
}
