package com.snoworca.naruserve.utils.auth.internal;

/**
 * Byte comparison whose time does not depend on where the arrays differ. {@code MessageDigest.isEqual} is not used:
 * older Android versions compared it with an early exit.
 */
// @req SEC-TOKEN-001
public final class ConstantTime {

    private ConstantTime() {
    }

    /** True for arrays of equal length and content; the whole shorter-or-equal length is always walked. */
    // @req SEC-TOKEN-001
    public static boolean equals(byte[] a, byte[] b) {
        int diff = a.length ^ b.length;
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            diff |= a[i] ^ b[i];
        }
        return diff == 0;
    }
}
