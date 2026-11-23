package com.snoworca.naruserve.proxy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Copies the backend response header lines onto the client response, before {@code res.relay}: lines are taken in
 * received order with duplicates, names compare case-insensitively (RFC 9110 §5.1), the first line of a name goes
 * through {@code res.header} so it replaces an app-set value and its later lines through {@code res.append}.
 * {@code Set-Cookie} and {@code Vary} lines are always appended, so app cookies and an app {@code Vary} stay. The
 * hop-by-hop names, every name listed in a {@code Connection} line and {@code Content-Length} are not copied; the
 * length reaches the client only as the length argument of {@code res.relay}.
 */
// @req FR-PROXY-011
final class ResponseHeaderCopier {

    private ResponseHeaderCopier() { }

    /**
     * Copies {@code lines} (backend name as received, value without surrounding whitespace) into {@code res}, the
     * writes held so the proxy can make them on the client response after its last deadline check before the
     * hand-off (FR-PROXY-017). The names and values must already have passed the {@code res.header} check. Every line
     * whose lower-case name is in {@code hiddenNames} (the {@code .removeResponseHeader} names, FR-PROXY-012) is
     * neither written nor returned, so an app value of the name stays and the {@code Location} rewrite does not put
     * it back. Returns the lines it copied, in order, for the {@code Location} rewrite that follows (FR-PROXY-013).
     */
    // @req FR-PROXY-011
    // @req FR-PROXY-012
    // @req FR-PROXY-013
    // @req FR-PROXY-017
    static List<Map.Entry<String, String>> copy(List<Map.Entry<String, String>> lines, Set<String> hiddenNames,
            PendingResponseHeaders res) {
        Set<String> connectionNames = HeaderRules.connectionNames(lines);
        Set<String> seen = new HashSet<>();
        List<Map.Entry<String, String>> copied = new ArrayList<>();
        for (Map.Entry<String, String> line : lines) {
            String name = line.getKey();
            String lower = name.toLowerCase(Locale.ROOT);
            if (HeaderRules.RESPONSE_MANAGED.contains(lower) || connectionNames.contains(lower)
                    || hiddenNames.contains(lower)) {
                continue;
            }
            copied.add(line);
            if (lower.equals("set-cookie") || lower.equals("vary") || !seen.add(lower)) {
                res.append(name, line.getValue());
            } else {
                res.header(name, line.getValue());
            }
        }
        return copied;
    }
}
