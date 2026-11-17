package com.snoworca.naruserve.proxy;

import com.snoworca.naruserve.common.HttpHeaderValues;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The header names the proxy manages and the checks of the header names and values given to the {@link Proxy}
 * header options. Names are held in lower case and compared case-insensitively (RFC 9110 §5.1).
 */
// @req FR-PROXY-002
final class HeaderRules {

    /** The hop-by-hop names: they describe one connection and are never relayed across the proxy. */
    static final Set<String> HOP_BY_HOP = names("connection", "keep-alive", "proxy-connection", "te",
            "transfer-encoding", "upgrade", "proxy-authenticate", "proxy-authorization", "trailer");

    /** The request names never copied from the client: hop-by-hop, Host, Content-Length and Expect. */
    static final Set<String> REQUEST_NOT_FORWARDED = union(HOP_BY_HOP, names("host", "content-length", "expect"));

    /** The request names the proxy writes or removes itself: those never copied and the forwarding headers. */
    static final Set<String> REQUEST_MANAGED = union(REQUEST_NOT_FORWARDED, names("forwarded", "x-forwarded-for",
            "x-forwarded-proto", "x-forwarded-host"));

    /** The response names the proxy writes or removes itself: hop-by-hop and Content-Length. */
    static final Set<String> RESPONSE_MANAGED = union(HOP_BY_HOP, names("content-length"));

    private HeaderRules() { }

    /**
     * Checks the name of a {@code removeRequestHeader} call.
     *
     * @throws IllegalArgumentException for a name that is not a token or that the proxy manages
     */
    // @req FR-PROXY-002
    static void checkRequestRule(String name, String option) {
        checkName(name, REQUEST_MANAGED, option);
    }

    /**
     * Checks the name and value of a {@code requestHeader} call.
     *
     * @throws IllegalArgumentException as {@link #checkRequestRule(String, String)}, or for a value that is not a
     *     header field value
     */
    // @req FR-PROXY-002
    static void checkRequestRule(String name, String value, String option) {
        requireNonNull(name, value, option);
        checkName(name, REQUEST_MANAGED, option);
        checkValue(value, option);
    }

    /**
     * Checks the name of a {@code removeResponseHeader} call.
     *
     * @throws IllegalArgumentException for a name that is not a token or that the proxy manages
     */
    // @req FR-PROXY-002
    static void checkResponseRule(String name, String option) {
        checkName(name, RESPONSE_MANAGED, option);
    }

    /**
     * Checks the name and value of a {@code responseHeader} call.
     *
     * @throws IllegalArgumentException as {@link #checkResponseRule(String, String)}, or for a value that is not a
     *     header field value
     */
    // @req FR-PROXY-002
    static void checkResponseRule(String name, String value, String option) {
        requireNonNull(name, value, option);
        checkName(name, RESPONSE_MANAGED, option);
        checkValue(value, option);
    }

    /** A null name or value is a NullPointerException before any check of the other argument (FR-PROXY-002 AC-7). */
    // @req FR-PROXY-002
    private static void requireNonNull(String name, String value, String option) {
        if (name == null) {
            throw new NullPointerException(option + " name");
        }
        if (value == null) {
            throw new NullPointerException(option + " value");
        }
    }

    /**
     * The token check comes before the managed-name lookup, so only ASCII names reach the case folding: a non-ASCII
     * character such as the Kelvin sign, which lower-cases to {@code k}, never makes a name look managed.
     */
    // @req FR-PROXY-002
    private static void checkName(String name, Set<String> managed, String option) {
        if (name == null) {
            throw new NullPointerException(option + " name");
        }
        if (!HttpHeaderValues.isToken(name)) {
            throw new IllegalArgumentException(option + ": the header name is not an RFC 9110 token");
        }
        if (managed.contains(name.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException(option + ": the proxy manages the header " + name);
        }
    }

    // @req FR-PROXY-002
    private static void checkValue(String value, String option) {
        if (!HttpHeaderValues.isFieldValue(value)) {
            // The value is not echoed: it may hold a credential or the control characters that made it invalid.
            throw new IllegalArgumentException(option + ": the header value holds a character a header cannot carry");
        }
    }

    /**
     * The lower-case names listed in every {@code Connection} line of {@code lines}: comma-separated elements, OWS
     * trimmed, names compared case-insensitively. Only token elements count, so the case folding sees ASCII only.
     */
    // @req FR-PROXY-003
    static Set<String> connectionNames(List<Map.Entry<String, String>> lines) {
        Set<String> names = new HashSet<>();
        for (Map.Entry<String, String> line : lines) {
            if (!line.getKey().equalsIgnoreCase("Connection")) {
                continue;
            }
            for (String element : line.getValue().split(",", -1)) {
                String name = trimOws(element);
                if (HttpHeaderValues.isToken(name)) {
                    names.add(name.toLowerCase(Locale.ROOT));
                }
            }
        }
        return names;
    }

    /** {@code s} without leading and trailing spaces and horizontal tabs (RFC 9110 OWS). */
    // @req FR-PROXY-011
    static String trimOws(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && (s.charAt(start) == ' ' || s.charAt(start) == '\t')) {
            start++;
        }
        while (end > start && (s.charAt(end - 1) == ' ' || s.charAt(end - 1) == '\t')) {
            end--;
        }
        return s.substring(start, end);
    }

    private static Set<String> names(String... lowerCaseNames) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(lowerCaseNames)));
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> all = new HashSet<>(a);
        all.addAll(b);
        return Collections.unmodifiableSet(all);
    }
}
