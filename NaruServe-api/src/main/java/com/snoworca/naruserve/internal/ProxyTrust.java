package com.snoworca.naruserve.internal;

import org.jspecify.annotations.Nullable;

/**
 * {@code app.trustProxy(true)}: the left-most entry of the first X-Forwarded-For header and the first value of the
 * first X-Forwarded-Proto header replace the exchange values; a blank entry keeps them. Forwarded is never read.
 */
// @req FR-ENGINE-008
final class ProxyTrust {

    private ProxyTrust() {
    }

    /** {@code forwardedFor} is the first X-Forwarded-For header when trusted, otherwise null. */
    // @req FR-ENGINE-008
    static String remoteAddress(@Nullable String forwardedFor, String exchangeAddress) {
        String forwarded = firstEntry(forwardedFor);
        return forwarded != null ? forwarded : exchangeAddress;
    }

    /** {@code forwardedProto} is the first X-Forwarded-Proto header when trusted, otherwise null. */
    // @req FR-ENGINE-008
    static boolean isSecure(@Nullable String forwardedProto, boolean exchangeSecure) {
        String proto = firstEntry(forwardedProto);
        return proto != null ? proto.equalsIgnoreCase("https") : exchangeSecure;
    }

    /** The trimmed first comma-separated entry of {@code v}; null when absent or blank. */
    private static @Nullable String firstEntry(@Nullable String v) {
        if (v == null) {
            return null;
        }
        int comma = v.indexOf(',');
        String entry = (comma < 0 ? v : v.substring(0, comma)).trim();
        return entry.isEmpty() ? null : entry;
    }
}
