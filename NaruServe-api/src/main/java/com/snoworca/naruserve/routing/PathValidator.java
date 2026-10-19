package com.snoworca.naruserve.routing;

import com.snoworca.naruserve.internal.PercentDecoder;
import org.jspecify.annotations.Nullable;

/**
 * Raw request path check run before the router lookup. It answers accept or reject only; it never returns a
 * decoded path. Text from the first {@code '?'} is ignored.
 */
// @req FR-ROUTE-004
public final class PathValidator {

    private PathValidator() {
    }

    /**
     * False for a raw char {@code <= 0x20}, {@code 0x7F}, {@code '#'} or above {@code 0x7F}; malformed
     * {@code %XX}; percent bytes that are not UTF-8; an encoded {@code '/'} or NUL; a {@code .} or {@code ..}
     * segment in raw or encoded form.
     */
    // @req FR-ROUTE-004
    public static boolean isValid(String rawPath) {
        String path = stripQuery(rawPath);
        // Only origin-form paths reach the router; "*", authority and absolute forms are the adapter's job.
        if (!path.startsWith("/")) {
            return false;
        }
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c <= 0x20 || c >= 0x7F || c == '#') {
                return false;
            }
        }
        for (String raw : path.split("/", -1)) {
            String seg = decode(raw);
            if (seg == null || seg.indexOf('/') >= 0 || seg.indexOf('\0') >= 0 || seg.equals(".") || seg.equals("..")) {
                return false;
            }
        }
        return true;
    }

    static String stripQuery(String rawPath) {
        int q = rawPath.indexOf('?');
        return q < 0 ? rawPath : rawPath.substring(0, q);
    }

    /** One UTF-8 percent-decoding pass; '+' stays '+'. Null for malformed {@code %XX} or invalid UTF-8. */
    // @req FR-ROUTE-005
    static @Nullable String decode(String segment) {
        return PercentDecoder.decode(segment, false);
    }
}
