package com.snoworca.naruserve.common;

/** Shared HTTP header list formatting without platform-dependent String.join. */
// @req FR-DX-010
public final class HttpHeaderValues {
    private HttpHeaderValues() { }

    public static String commaSeparated(Iterable<String> values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() != 0) result.append(", ");
            result.append(value);
        }
        return result.toString();
    }
}
