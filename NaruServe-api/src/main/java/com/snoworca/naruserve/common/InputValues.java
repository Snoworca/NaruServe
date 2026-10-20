package com.snoworca.naruserve.common;

import com.snoworca.naruserve.HttpException;
import org.jspecify.annotations.Nullable;

/** Consistent client-input errors for all request convenience accessors. */
// @req FR-DX-003
public final class InputValues {
    private InputValues() { }
    public static String required(@Nullable String value, String name) {
        if (value == null || isBlank(value)) throw new HttpException(400, name + " is required");
        return value;
    }
    /** Required-value whitespace follows Unicode whitespace and space characters, without trimming returned data. */
    private static boolean isBlank(String value) {
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            if (!Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint)) return false;
            offset += Character.charCount(codePoint);
        }
        return true;
    }

    public static int integer(@Nullable String value, String name, int defaultValue) {
        if (value == null) return defaultValue;
        try { return Integer.parseInt(value); }
        catch (NumberFormatException error) { throw new HttpException(400, name + " must be a 32-bit integer"); }
    }
}
