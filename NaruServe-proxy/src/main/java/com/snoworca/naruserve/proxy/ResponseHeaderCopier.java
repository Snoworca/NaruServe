package com.snoworca.naruserve.proxy;

import com.snoworca.naruserve.Response;
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
     * Copies {@code lines} (backend name as received, value without surrounding whitespace) onto {@code res}. The
     * names and values must already have passed the {@code res.header} check.
     */
    // @req FR-PROXY-011
    static void copy(List<Map.Entry<String, String>> lines, Response res) {
        Set<String> connectionNames = connectionNames(lines);
        Set<String> seen = new HashSet<>();
        for (Map.Entry<String, String> line : lines) {
            String name = line.getKey();
            String lower = name.toLowerCase(Locale.ROOT);
            if (HeaderRules.RESPONSE_MANAGED.contains(lower) || connectionNames.contains(lower)) {
                continue;
            }
            if (lower.equals("set-cookie") || lower.equals("vary") || !seen.add(lower)) {
                res.append(name, line.getValue());
            } else {
                res.header(name, line.getValue());
            }
        }
    }

    /** The lower-case names listed in every {@code Connection} line: comma-separated, OWS trimmed, empty skipped. */
    // @req FR-PROXY-011
    private static Set<String> connectionNames(List<Map.Entry<String, String>> lines) {
        Set<String> names = new HashSet<>();
        for (Map.Entry<String, String> line : lines) {
            if (!line.getKey().equalsIgnoreCase("Connection")) {
                continue;
            }
            for (String token : line.getValue().split(",", -1)) {
                String trimmed = HeaderRules.trimOws(token);
                if (!trimmed.isEmpty()) {
                    names.add(trimmed.toLowerCase(Locale.ROOT));
                }
            }
        }
        return names;
    }
}
