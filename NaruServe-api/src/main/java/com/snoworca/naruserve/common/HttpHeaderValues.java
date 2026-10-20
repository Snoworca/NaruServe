package com.snoworca.naruserve.common;

/** Shared HTTP header list formatting without platform-dependent String.join. */
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


    public static String commaSeparated(Iterable<String> values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() != 0) result.append(", ");
            result.append(value);
        }
        return result.toString();
    }
}
