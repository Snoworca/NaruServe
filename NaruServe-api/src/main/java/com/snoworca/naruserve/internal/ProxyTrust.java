package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.spi.HttpExchange;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * {@code app.trustProxy(true)}: the left-most entry of the first X-Forwarded-For header and the first value of the
 * first X-Forwarded-Proto header replace the exchange values; a blank entry keeps them. Forwarded is never read.
 */
// @req FR-ENGINE-008
final class ProxyTrust {

    private ProxyTrust() {
    }

    // @req FR-ENGINE-008
    static String remoteAddress(HttpExchange ex, boolean trust) {
        String forwarded = trust ? firstEntry(ex, "X-Forwarded-For") : null;
        return forwarded != null ? forwarded : ex.remoteAddress();
    }

    // @req FR-ENGINE-008
    static boolean isSecure(HttpExchange ex, boolean trust) {
        String proto = trust ? firstEntry(ex, "X-Forwarded-Proto") : null;
        return proto != null ? proto.equalsIgnoreCase("https") : ex.isSecure();
    }

    /** The trimmed first comma-separated entry of the first {@code name} header; null when absent or blank. */
    private static @Nullable String firstEntry(HttpExchange ex, String name) {
        for (Map.Entry<String, String> h : ex.headers()) {
            if (h.getKey().equalsIgnoreCase(name)) {
                String v = h.getValue();
                int comma = v.indexOf(',');
                String entry = (comma < 0 ? v : v.substring(0, comma)).trim();
                return entry.isEmpty() ? null : entry;
            }
        }
        return null;
    }
}
