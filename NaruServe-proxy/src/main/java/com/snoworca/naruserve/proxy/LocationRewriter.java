package com.snoworca.naruserve.proxy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The backend {@code Location} rewrite of {@code .rewriteLocation(true)}, the default (design D-061): a value that is
 * an absolute URL, or a network-path reference {@code //authority/...} read with the target scheme, whose origin is
 * the target's and whose path equals the target base path or continues it with {@code /}, loses its origin and base
 * path to the client-side prefix; the rest of the reference (the path after the base path, the query and the
 * fragment) is kept as received and an empty resulting path becomes {@code /}. Every other value is left unchanged:
 * relative references, other origins, other paths and values whose host and port the {@code Proxy.to} grammar
 * refuses.
 *
 * <p>The origin is parsed by hand with the grammar of {@link TargetUrl#parse}, not {@code java.net.URI}, after the
 * user information (everything up to the last {@code @} of the authority) is dropped and an empty port ({@code :}
 * ending the authority) is read as omitted, as RFC 3986 §3.2 and §6.2.3 have it: the origin is the scheme, host and
 * port alone. The scheme and host compare case-insensitively and an omitted port is 80 for {@code http} and 443 for
 * {@code https} (RFC 3986 §6.2.2.1). The base path compares on the raw characters, so {@code /basement} does not continue
 * {@code /base} and percent-encodings are not decoded. The result never carries an origin: it holds no part of the
 * received {@code Host} or {@code X-Forwarded-Host}, and a resulting path a client would read as a network-path
 * reference (two leading slashes or backslashes, tabs and line breaks skipped) gets a leading {@code /.}, so it stays
 * a path on the client's origin.
 */
// @req FR-PROXY-013
final class LocationRewriter {

    private LocationRewriter() { }

    /**
     * Rewrites every {@code Location} line among {@code copied}, the backend lines the header copy put in
     * {@code res}, and puts them back in {@code res} in the same order when any of them changed; the writes are held
     * for the client response (FR-PROXY-017). {@code clientPrefix} is {@code req.baseUrl()}, the raw matched mount
     * prefix, under {@code .stripPrefix(true)} and the empty string otherwise.
     */
    // @req FR-PROXY-013
    // @req FR-PROXY-017
    static void apply(List<Map.Entry<String, String>> copied, TargetUrl target, String clientPrefix,
            PendingResponseHeaders res) {
        List<String> values = new ArrayList<>();
        boolean changed = false;
        for (Map.Entry<String, String> line : copied) {
            if (!line.getKey().equalsIgnoreCase("Location")) {
                continue;
            }
            String value = rewrite(line.getValue(), target, clientPrefix);
            changed |= !value.equals(line.getValue());
            values.add(value);
        }
        if (!changed) {
            return;
        }
        res.header("Location", values.get(0));
        for (int i = 1; i < values.size(); i++) {
            res.append("Location", values.get(i));
        }
    }

    /**
     * {@code location} rewritten for {@code target} with {@code clientPrefix} in place of the origin and base path,
     * or {@code location} itself when the rule does not apply. {@code clientPrefix} is empty or starts with
     * {@code /} and is placed as given.
     */
    // @req FR-PROXY-013
    static String rewrite(String location, TargetUrl target, String clientPrefix) {
        String scheme;
        int authorityStart;
        if (location.startsWith("//")) {
            scheme = target.scheme;
            authorityStart = 2;
        } else {
            int colon = location.indexOf(':');
            if (colon <= 0 || !location.startsWith("//", colon + 1)) {
                return location;
            }
            scheme = location.substring(0, colon);
            authorityStart = colon + 3;
        }
        int authorityEnd = authorityStart;
        while (authorityEnd < location.length() && "/?#".indexOf(location.charAt(authorityEnd)) < 0) {
            authorityEnd++;
        }
        // The origin is the scheme, host and port alone: user information (up to the last '@', RFC 3986 §3.2.1) is
        // dropped and an empty port is the omitted one (RFC 3986 §3.2.3), so neither hides the target's origin.
        String authority = location.substring(authorityStart, authorityEnd);
        authority = authority.substring(authority.lastIndexOf('@') + 1);
        if (authority.endsWith(":")) {
            authority = authority.substring(0, authority.length() - 1);
        }
        TargetUrl origin;
        try {
            // The authority holds no '/', '?' or '#', so the parse sees a scheme and an authority without a path.
            origin = TargetUrl.parse(scheme + "://" + authority);
        } catch (IllegalArgumentException notAnHttpOrigin) {
            return location;
        }
        if (!origin.scheme.equals(target.scheme) || origin.port != target.port
                || !origin.host.equalsIgnoreCase(target.host)) {
            return location;
        }
        int pathEnd = authorityEnd;
        while (pathEnd < location.length() && location.charAt(pathEnd) != '?' && location.charAt(pathEnd) != '#') {
            pathEnd++;
        }
        // The base path holds no '?' or '#', so a match never runs past the path.
        String base = target.basePath;
        int afterBase = authorityEnd + base.length();
        if (!location.startsWith(base, authorityEnd) || (afterBase != pathEnd && location.charAt(afterBase) != '/')) {
            return location;
        }
        String path = clientPrefix + location.substring(afterBase, pathEnd);
        if (path.isEmpty()) {
            path = "/";
        }
        if (readsAsNetworkPath(path)) {
            // "/." keeps the reference a path on the client's origin: dot-segment removal (RFC 3986 §5.2.4) and the
            // browser URL parser turn "/.//x" into the path "//x" without reading "x" as an authority.
            path = "/." + path;
        }
        return path + location.substring(pathEnd);
    }

    /**
     * Whether a client would read {@code path} as a network-path reference: its first two characters other than tab,
     * line feed and carriage return are each {@code /} or {@code \} (browsers read {@code \} as {@code /} and drop
     * tabs and line breaks).
     */
    // @req FR-PROXY-013
    private static boolean readsAsNetworkPath(String path) {
        int slashes = 0;
        for (int i = 0; i < path.length() && slashes < 2; i++) {
            char c = path.charAt(i);
            if (c == '\t' || c == '\n' || c == '\r') {
                continue;
            }
            if (c != '/' && c != '\\') {
                return false;
            }
            slashes++;
        }
        return slashes == 2;
    }
}
