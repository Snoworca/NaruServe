package com.snoworca.naruserve.proxy;

import java.util.Locale;

/**
 * The backend origin and base path of {@code Proxy.to(url)}, parsed by hand (design D-048, D-072): {@code java.net.URI}
 * takes lenient forms (registry-based authorities, unbracketed scope ids) this grammar refuses.
 *
 * <p>Accepted: the scheme {@code http} or {@code https} in any letter case, {@code ://}, a required host, an optional
 * {@code :port} of decimal digits in {@code 1..65535}, and an optional base path starting with {@code /}. The host is
 * a name of dot-separated labels of ASCII letters, digits, {@code -} and {@code _} (see {@link #isHostName}); a
 * dotted-quad IPv4 literal (a name whose last label is a number must be one); or a bracketed IPv6 literal without a
 * zone id. The base path loses every trailing
 * {@code /} and must then pass {@link PathChecker#isSafePath}. A query, a fragment and user information are refused.
 * {@code null} throws {@code NullPointerException}; every other refusal throws {@code IllegalArgumentException}.
 */
// @req FR-PROXY-001
final class TargetUrl {

    /** {@code http} or {@code https}, lower case. */
    final String scheme;
    /**
     * The authority as written in the URL (host literal and the port when one was written), used by
     * {@code Proxy.describe()} and, after the scheme, as the backend name in the relayed-body failure log record
     * (FR-PROXY-016); the {@code Host} value comes from {@link #hostHeader()}.
     */
    final String authority;
    /** The host as written, IPv6 brackets kept: the form a {@code Host} header carries. */
    final String hostLiteral;
    /** The host to connect to: {@link #hostLiteral} without IPv6 brackets. */
    final String host;
    /** The port to connect to: the written port, or 80 for {@code http} and 443 for {@code https}. */
    final int port;
    /** The base path without trailing slashes; empty for none. */
    final String basePath;

    private TargetUrl(String scheme, String authority, String hostLiteral, String host, int port, String basePath) {
        this.scheme = scheme;
        this.authority = authority;
        this.hostLiteral = hostLiteral;
        this.host = host;
        this.port = port;
        this.basePath = basePath;
    }

    // @req FR-PROXY-001
    static TargetUrl parse(String url) {
        if (url == null) {
            throw new NullPointerException("url");
        }
        int sep = url.indexOf("://");
        if (sep <= 0) {
            throw new IllegalArgumentException("not an http or https URL");
        }
        String scheme = url.substring(0, sep).toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("not an http or https URL");
        }
        if (url.indexOf('?') >= 0 || url.indexOf('#') >= 0) {
            throw new IllegalArgumentException("a target URL has no query or fragment");
        }
        String rest = url.substring(sep + 3);
        int slash = rest.indexOf('/');
        String authority = slash < 0 ? rest : rest.substring(0, slash);
        String basePath = slash < 0 ? "" : rest.substring(slash);
        if (authority.indexOf('@') >= 0) {
            throw new IllegalArgumentException("a target URL has no user information");
        }
        String hostLiteral;
        String host;
        String portText;
        if (authority.startsWith("[")) {
            int close = authority.indexOf(']');
            if (close < 0) {
                throw new IllegalArgumentException("unclosed IPv6 literal");
            }
            hostLiteral = authority.substring(0, close + 1);
            host = authority.substring(1, close);
            if (!isIpv6(host)) {
                throw new IllegalArgumentException("not an IPv6 literal without a zone id");
            }
            if (close + 1 == authority.length()) {
                portText = null;
            } else if (authority.charAt(close + 1) == ':') {
                portText = authority.substring(close + 2);
            } else {
                throw new IllegalArgumentException("bad authority");
            }
        } else {
            int colon = authority.indexOf(':');
            hostLiteral = colon < 0 ? authority : authority.substring(0, colon);
            host = hostLiteral;
            portText = colon < 0 ? null : authority.substring(colon + 1);
            if (host.isEmpty()) {
                throw new IllegalArgumentException("no host");
            }
            if (!isHostName(host)) {
                throw new IllegalArgumentException("not an ASCII host name or IPv4 literal");
            }
        }
        int port = portText == null ? defaultPort(scheme) : parsePort(portText);
        int end = basePath.length();
        while (end > 0 && basePath.charAt(end - 1) == '/') {
            end--;
        }
        basePath = basePath.substring(0, end);
        if (!PathChecker.isSafePath(basePath)) {
            throw new IllegalArgumentException("the base path fails the request path checks");
        }
        return new TargetUrl(scheme, authority, hostLiteral, host, port, basePath);
    }

    /**
     * The {@code Host} value for this target: {@link #hostLiteral} (IPv6 brackets kept), followed by {@code :port}
     * only when {@link #port} is not the default of {@link #scheme} (80 for {@code http}, 443 for {@code https}).
     */
    // @req FR-PROXY-004
    String hostHeader() {
        return port == defaultPort(scheme) ? hostLiteral : hostLiteral + ":" + port;
    }

    /** The default port of {@code scheme}: 443 for {@code https}, 80 for {@code http}. */
    private static int defaultPort(String scheme) {
        return "https".equals(scheme) ? 443 : 80;
    }

    /** One to five ASCII decimal digits spelling {@code 1..65535}; {@code Integer.parseInt} would take a sign. */
    private static int parsePort(String s) {
        if (s.isEmpty() || s.length() > 5) {
            throw new IllegalArgumentException("bad port");
        }
        int port = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                throw new IllegalArgumentException("bad port");
            }
            port = port * 10 + (c - '0');
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("bad port");
        }
        return port;
    }

    /**
     * One or more dot-separated labels (see {@link #isLabel}), at most 253 characters, optionally followed by one
     * trailing root dot; any other empty label (a leading dot, {@code ..}) is refused. A name whose last label (the
     * root dot aside) is a number, decimal or {@code 0x} hexadecimal, must be a dotted-quad IPv4 literal: resolvers
     * read {@code 4294967295}, {@code 127.1} or {@code 0x7f.1} as addresses too.
     */
    private static boolean isHostName(String s) {
        int end = s.endsWith(".") ? s.length() - 1 : s.length();
        if (end == 0 || end > 253) {
            return false;
        }
        int start = 0;
        while (true) {
            int dot = s.indexOf('.', start);
            int labelEnd = dot < 0 ? end : dot;
            if (!isLabel(s, start, labelEnd)) {
                return false;
            }
            if (labelEnd == end) {
                return !isNumber(s, start, end) || isIpv4(s);
            }
            start = labelEnd + 1;
        }
    }

    /**
     * Whether {@code s[start, end)} is 1..63 ASCII letters, digits, {@code -} and {@code _}, holding at least one
     * letter or digit and neither starting nor ending with {@code -}.
     */
    private static boolean isLabel(String s, int start, int end) {
        int length = end - start;
        if (length < 1 || length > 63 || s.charAt(start) == '-' || s.charAt(end - 1) == '-') {
            return false;
        }
        boolean alphanumeric = false;
        for (int i = start; i < end; i++) {
            char c = s.charAt(i);
            boolean digit = c >= '0' && c <= '9';
            boolean letter = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
            if (!digit && !letter && c != '-' && c != '_') {
                return false;
            }
            alphanumeric |= digit || letter;
        }
        return alphanumeric;
    }

    /** Whether {@code s[start, end)} is a non-empty run of decimal digits or {@code 0x} and hexadecimal digits. */
    private static boolean isNumber(String s, int start, int end) {
        if (start >= end) {
            return false;
        }
        int from = start;
        boolean hex = end - start >= 2 && s.charAt(start) == '0' && (s.charAt(start + 1) == 'x'
                || s.charAt(start + 1) == 'X');
        if (hex) {
            from = start + 2;
        }
        for (int i = from; i < end; i++) {
            char c = s.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (hex && ((c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')));
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether {@code s} is a dotted-quad IPv4 literal: the proxy's one literal grammar, the parser of the certificate
     * {@code iPAddress} match (SEC-PROXY-005), so a target host accepted here is one that match can parse.
     */
    private static boolean isIpv4(String s) {
        return CertificateHostnames.ipv4(s) != null;
    }

    /** Whether {@code s} is an IPv6 literal without a zone id, by the same parser as {@link #isIpv4}. */
    private static boolean isIpv6(String s) {
        return CertificateHostnames.ipv6(s) != null;
    }
}
