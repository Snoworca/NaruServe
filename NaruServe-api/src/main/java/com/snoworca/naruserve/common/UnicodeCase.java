package com.snoworca.naruserve.common;

/** Locale-independent simple case folding shared by routing keys, matching and middleware prefixes. */
// @req FR-ROUTE-006
// @req FR-ROUTE-008
public final class UnicodeCase {
    private UnicodeCase() { }

    private static int fold(int codePoint) {
        return Character.toLowerCase(Character.toUpperCase(codePoint));
    }

    /** Compares whole Unicode code points, including supplementary letters, without changing original values. */
    public static boolean equals(String left, String right) {
        int l = 0;
        int r = 0;
        while (l < left.length() && r < right.length()) {
            int lc = left.codePointAt(l);
            int rc = right.codePointAt(r);
            if (fold(lc) != fold(rc)) return false;
            l += Character.charCount(lc);
            r += Character.charCount(rc);
        }
        return l == left.length() && r == right.length();
    }

    /** A conflict key using exactly the same code-point equivalence as {@link #equals}. */
    public static String fold(String value) {
        StringBuilder result = new StringBuilder(value.length());
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            result.appendCodePoint(fold(codePoint));
            offset += Character.charCount(codePoint);
        }
        return result.toString();
    }
}
