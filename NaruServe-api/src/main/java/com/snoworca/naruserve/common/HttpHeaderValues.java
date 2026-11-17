package com.snoworca.naruserve.common;

/**
 * Shared HTTP header helpers: the public header name and value predicates ({@link #isToken}, {@link #isFieldValue})
 * that {@code res.header} validates with, and list formatting without platform-dependent String.join.
 */
// @req FR-DX-010
public final class HttpHeaderValues {
    private HttpHeaderValues() { }

    /** RFC 9110 token, shared by header, cookie and media-type validation. */
    public static boolean isToken(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean alnum = c >= '0' && c <= '9' || c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z';
            if (!alnum && "!#$%&'*+-.^_`|~".indexOf(c) < 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Header field value: no control character (CR, LF, NUL, DEL, ...) except HTAB, and no char above U+00FF. An
     * adapter writing the low byte would turn U+010A into LF (cf. Node CVE-2018-12116), so headers stay ISO-8859-1,
     * one byte per char. With {@link #isToken} for the name, this is the {@code res.header} check.
     */
    // @req IR-HTTP-012
    public static boolean isFieldValue(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 && c != '\t' || c == 0x7F || c > 0xFF) {
                return false;
            }
        }
        return true;
    }

    public static String commaSeparated(Iterable<String> values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() != 0) result.append(", ");
            result.append(value);
        }
        return result.toString();
    }
}
