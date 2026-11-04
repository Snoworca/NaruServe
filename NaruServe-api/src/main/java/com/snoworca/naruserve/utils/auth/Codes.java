package com.snoworca.naruserve.utils.auth;

/** The error-code rule shared by the auth exceptions; no regular expression. */
// @req IR-AUTH-003
final class Codes {

    private Codes() {
    }

    /** {@code [a-z][a-z0-9_]{0,63}}, or with {@code upperAllowed} also a Core spelling {@code [A-Z][A-Z0-9_]{0,63}}. */
    // @req IR-AUTH-003
    static String check(String code, boolean upperAllowed) {
        if (!matches(code, false) && !(upperAllowed && matches(code, true))) {
            throw new IllegalArgumentException("auth error code must match [a-z][a-z0-9_]{0,63}"
                    + (upperAllowed ? " or [A-Z][A-Z0-9_]{0,63}" : "") + ": " + code);
        }
        return code;
    }

    private static boolean matches(String code, boolean upper) {
        int n = code.length();
        if (n < 1 || n > 64 || !(upper ? isUpper(code.charAt(0)) : isLower(code.charAt(0)))) {
            return false;
        }
        for (int i = 1; i < n; i++) {
            char c = code.charAt(i);
            if (!((upper ? isUpper(c) : isLower(c)) || c >= '0' && c <= '9' || c == '_')) {
                return false;
            }
        }
        return true;
    }

    private static boolean isLower(char c) {
        return c >= 'a' && c <= 'z';
    }

    private static boolean isUpper(char c) {
        return c >= 'A' && c <= 'Z';
    }
}
