package com.snoworca.naruserve.proxy;

import com.snoworca.naruserve.Response;
import java.util.ArrayList;
import java.util.Collections;
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
     * names and values must already have passed the {@code res.header} check. Returns the lines it copied, in
     * order, for the {@code Location} rewrite that follows the copy (FR-PROXY-013).
     */
    // @req FR-PROXY-011
    // @req FR-PROXY-013
    static List<Map.Entry<String, String>> copy(List<Map.Entry<String, String>> lines, Response res) {
        return copy(lines, Collections.<String>emptySet(), res);
    }

    /**
     * As {@link #copy(List, Response)}, and also skips every line whose lower-case name is in {@code hiddenNames}
     * (the {@code .removeResponseHeader} names, FR-PROXY-012): such a line is neither written nor returned, so an
     * app value of the name stays and the {@code Location} rewrite does not put it back.
     */
    // @req FR-PROXY-012
    static List<Map.Entry<String, String>> copy(List<Map.Entry<String, String>> lines, Set<String> hiddenNames,
            Response res) {
        PendingResponseHeaders pending = new PendingResponseHeaders();
        List<Map.Entry<String, String>> copied = copy(lines, hiddenNames, pending);
        pending.applyTo(res);
        return copied;
    }

    /**
     * As {@link #copy(List, Set, Response)}, but holds the writes in {@code pending} instead of making them on the
     * client response, so the proxy can make them after its last deadline check before the hand-off (FR-PROXY-017).
     */
    // @req FR-PROXY-012
    // @req FR-PROXY-017
    static List<Map.Entry<String, String>> copy(List<Map.Entry<String, String>> lines, Set<String> hiddenNames,
            PendingResponseHeaders res) {
        Set<String> connectionNames = connectionNames(lines);
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
