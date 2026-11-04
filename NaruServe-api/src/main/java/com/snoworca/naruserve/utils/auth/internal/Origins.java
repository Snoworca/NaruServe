package com.snoworca.naruserve.utils.auth.internal;

import com.snoworca.naruserve.Request;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The Origin check of cookie mode: the request's {@code Origin}, or the origin of its {@code Referer} when there is no
 * {@code Origin}, must be the request's own origin or a trusted one. {@code Origin: null} and a request with neither
 * header fail closed. Origins compare as {@code scheme://host[:port]}, lower case, default port dropped.
 */
// @req SEC-FLOW-007
public final class Origins {

    private Origins() {
    }

    /** The origin of an absolute http or https URL, or null for anything else ({@code null}, relative, userinfo). */
    // @req SEC-FLOW-007
    public static @Nullable String of(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        String scheme;
        if (lower.startsWith("https://")) {
            scheme = "https";
        } else if (lower.startsWith("http://")) {
            scheme = "http";
        } else {
            return null;
        }
        int start = scheme.length() + 3;
        int end = start;
        while (end < lower.length() && "/?#".indexOf(lower.charAt(end)) < 0) {
            end++;
        }
        return authority(scheme, lower.substring(start, end));
    }

    /** {@code scheme://host[:port]} of a host[:port] authority, or null when it is malformed. */
    private static @Nullable String authority(String scheme, String hostPort) {
        if (hostPort.isEmpty() || hostPort.indexOf('@') >= 0) {
            return null;
        }
        String host = hostPort;
        String port = null;
        int colon = hostPort.lastIndexOf(':');
        int bracket = hostPort.lastIndexOf(']');
        if (colon > bracket) {
            host = hostPort.substring(0, colon);
            port = hostPort.substring(colon + 1);
        }
        if (host.isEmpty() || port != null && !validPort(port)) {
            return null;
        }
        boolean defaultPort = port == null || scheme.equals("http") && Integer.parseInt(port) == 80
                || scheme.equals("https") && Integer.parseInt(port) == 443;
        return scheme + "://" + host + (defaultPort ? "" : ":" + Integer.parseInt(port));
    }

    private static boolean validPort(String port) {
        if (port.isEmpty() || port.length() > 5) {
            return false;
        }
        for (int i = 0; i < port.length(); i++) {
            if (port.charAt(i) < '0' || port.charAt(i) > '9') {
                return false;
            }
        }
        int p = Integer.parseInt(port);
        return p >= 1 && p <= 65535;
    }

    /** The request's own origin from {@code req.isSecure()} and its Host header; empty host when it has none. */
    // @req SEC-FLOW-007
    public static String requestOrigin(Request req) {
        String scheme = req.isSecure() ? "https" : "http";
        String host = req.header("Host");
        String origin = host == null ? null : authority(scheme, host.trim().toLowerCase(Locale.ROOT));
        return origin == null ? scheme + "://" : origin;
    }

    /** True when the request's Origin, else its Referer, is the request's own origin or in {@code trusted}. */
    // @req SEC-FLOW-007
    public static boolean passes(Request req, Set<String> trusted) {
        String header = req.header("Origin");
        if (header == null) {
            header = req.header("Referer");
        }
        if (header == null) {
            return false;
        }
        String origin = of(header.trim());
        if (origin == null) {
            return false;
        }
        String own = requestOrigin(req);
        if (!own.endsWith("://") && origin.equals(own) || trusted.contains(origin)) {
            return true;
        }
        for (String t : trusted) {
            if (origin.equals(of(t.trim()))) {
                return true;
            }
        }
        return false;
    }
}
