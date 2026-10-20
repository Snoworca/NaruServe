package com.snoworca.naruserve.internal;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The upgrade's Origin rule, built once at freeze: no Origin, a same-origin Origin, a listed origin, or any origin
 * when {@code *} is listed. Origins compare as {@code scheme://host:port} with lowercase scheme and host and the
 * default port of http (80) or https (443) filled in; an entry or Origin that does not parse never matches.
 */
// @req FR-WS-003
final class OriginCheck {

    private final Set<String> allowed = new HashSet<>();
    private final boolean any;

    // @req FR-WS-003
    OriginCheck(List<String> entries) {
        boolean star = false;
        for (String e : entries) {
            if ("*".equals(e)) {
                star = true;
            } else {
                String n = normalise(e);
                if (n != null) {
                    allowed.add(n);
                }
            }
        }
        this.any = star;
    }

    /** {@code host} is the request's Host header; {@code secure} picks https over http for the same-origin tuple. */
    // @req FR-WS-003
    boolean admits(@Nullable String origin, @Nullable String host, boolean secure) {
        if (origin == null || any) {
            return true;
        }
        String o = normalise(origin); // the literal "null" does not parse
        if (o == null) {
            return false;
        }
        if (allowed.contains(o)) {
            return true;
        }
        return host != null && o.equals(tuple(secure ? "https" : "http", host.trim()));
    }

    /** {@code scheme://authority} normalised, or null when it does not parse. */
    // @req FR-WS-003
    static @Nullable String normalise(String origin) {
        int sep = origin.indexOf("://");
        if (sep < 0) {
            return null;
        }
        return tuple(origin.substring(0, sep).toLowerCase(Locale.ROOT), origin.substring(sep + 3));
    }

    /** {@code scheme://host:port} from {@code host[:port]} (IPv6 in brackets), or null when it does not parse. */
    // @req FR-WS-003
    private static @Nullable String tuple(String scheme, String authority) {
        int defaultPort = "http".equals(scheme) ? 80 : "https".equals(scheme) ? 443 : -1;
        if (defaultPort < 0) {
            return null;
        }
        String host;
        String port;
        if (authority.startsWith("[")) {
            int end = authority.indexOf(']');
            if (end < 0) {
                return null;
            }
            host = authority.substring(0, end + 1);
            String rest = authority.substring(end + 1);
            if (!rest.isEmpty() && !rest.startsWith(":")) {
                return null;
            }
            port = rest.isEmpty() ? null : rest.substring(1);
        } else {
            int colon = authority.indexOf(':');
            host = colon < 0 ? authority : authority.substring(0, colon);
            port = colon < 0 ? null : authority.substring(colon + 1);
        }
        if (host.isEmpty() || !plainHost(host)) {
            return null;
        }
        int p = defaultPort;
        if (port != null) {
            if (port.isEmpty() || port.length() > 5) {
                return null;
            }
            for (int i = 0; i < port.length(); i++) {
                if (port.charAt(i) < '0' || port.charAt(i) > '9') {
                    return null;
                }
            }
            p = Integer.parseInt(port);
        }
        return scheme + "://" + host.toLowerCase(Locale.ROOT) + ":" + p;
    }

    /** No path, query, fragment, userinfo, whitespace or second colon outside brackets. */
    private static boolean plainHost(String host) {
        boolean bracketed = host.startsWith("[");
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (c <= ' ' || c == '/' || c == '?' || c == '#' || c == '@' || c == ':' && !bracketed) {
                return false;
            }
        }
        return true;
    }
}
