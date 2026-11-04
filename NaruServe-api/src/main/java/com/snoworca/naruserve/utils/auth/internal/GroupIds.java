package com.snoworca.naruserve.utils.auth.internal;

/** The group id rule {@code [a-z0-9._-]{1,64}}, checked before any group store write. */
// @req DR-IAM-002
public final class GroupIds {

    private GroupIds() {
    }

    /** True when {@code id} is a valid group id. */
    // @req DR-IAM-002
    public static boolean isValid(String id) {
        int n = id.length();
        if (n < 1 || n > 64) {
            return false;
        }
        for (int i = 0; i < n; i++) {
            char c = id.charAt(i);
            if (!(c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '.' || c == '_' || c == '-')) {
                return false;
            }
        }
        return true;
    }
}
