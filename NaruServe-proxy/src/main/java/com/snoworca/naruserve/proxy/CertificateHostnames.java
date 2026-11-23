package com.snoworca.naruserve.proxy;

import java.security.cert.Certificate;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import org.jspecify.annotations.Nullable;

/**
 * The proxy's own certificate host name check (SEC-PROXY-005 AC-10), run by {@link TlsConnector} after every
 * handshake whatever the socket factory did with the endpoint identification. The target host is matched against the
 * subject alternative names of the session's peer leaf certificate only:
 * <ul>
 * <li>a DNS host name against the {@code dNSName} entries, ASCII case-insensitively and with one trailing root dot
 * ignored, where {@code *} is allowed only as the whole leftmost label of an entry with at least one more label and
 * matches exactly one non-empty label;</li>
 * <li>an IPv4 or IPv6 literal against the {@code iPAddress} entries, compared as address bytes parsed here without
 * any name lookup;</li>
 * <li>never against the subject common name: a certificate without a matching entry, or without subject alternative
 * names, is rejected.</li>
 * </ul>
 * Uses only Java 8 and Android API 21 APIs ({@link X509Certificate#getSubjectAlternativeNames()}).
 */
// @req SEC-PROXY-005
final class CertificateHostnames implements HostnameVerifier {

    /** The {@code GeneralName} tag of a {@code dNSName} entry. */
    private static final int DNS_NAME = 2;
    /** The {@code GeneralName} tag of an {@code iPAddress} entry. */
    private static final int IP_ADDRESS = 7;

    @Override
    public boolean verify(@Nullable String host, @Nullable SSLSession session) {
        if (host == null || session == null) {
            return false;
        }
        Certificate[] chain;
        try {
            chain = session.getPeerCertificates();
        } catch (SSLPeerUnverifiedException | RuntimeException e) {
            return false;
        }
        if (chain == null || chain.length == 0 || !(chain[0] instanceof X509Certificate)) {
            return false;
        }
        return matches(host, (X509Certificate) chain[0]);
    }

    /** Whether {@code cert}'s subject alternative names name {@code host} under the rules of this class. */
    static boolean matches(String host, X509Certificate cert) {
        Collection<List<?>> names;
        try {
            names = cert.getSubjectAlternativeNames();
        } catch (CertificateParsingException | RuntimeException e) {
            return false;
        }
        if (names == null) {
            return false;
        }
        byte[] address = host.indexOf(':') >= 0 ? ipv6(host) : ipv4(host);
        if (address == null && (host.indexOf(':') >= 0 || numericLastLabel(host))) {
            return false; // a malformed or legacy-form address is not a DNS name either
        }
        String dnsHost = address == null ? normalizeHost(host) : null;
        if (address == null && dnsHost == null) {
            return false;
        }
        for (List<?> entry : names) {
            if (entry == null || entry.size() < 2 || !(entry.get(0) instanceof Integer)
                    || !(entry.get(1) instanceof String)) {
                continue;
            }
            int type = (Integer) entry.get(0);
            String value = (String) entry.get(1);
            if (address != null && type == IP_ADDRESS) {
                byte[] san = value.indexOf(':') >= 0 ? ipv6(value) : ipv4(value);
                if (san != null && Arrays.equals(address, san)) {
                    return true;
                }
            } else if (dnsHost != null && type == DNS_NAME && dnsMatches(dnsHost, value)) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code host} in ASCII lower case without one trailing root dot, or {@code null} when it is empty, has an empty
     * label or holds a {@code *}.
     */
    private static @Nullable String normalizeHost(String host) {
        String h = lowerAscii(host.endsWith(".") ? host.substring(0, host.length() - 1) : host);
        if (h.isEmpty() || h.indexOf('*') >= 0 || !labelsNonEmpty(h)) {
            return null;
        }
        return h;
    }

    /** Whether the normalized DNS {@code host} matches the {@code dNSName} entry {@code pattern}. */
    private static boolean dnsMatches(String host, String pattern) {
        String p = lowerAscii(pattern.endsWith(".") ? pattern.substring(0, pattern.length() - 1) : pattern);
        if (p.isEmpty() || !labelsNonEmpty(p)) {
            return false;
        }
        if (p.startsWith("*.")) {
            String rest = p.substring(2);
            if (rest.indexOf('*') >= 0) {
                return false;
            }
            int dot = host.indexOf('.');
            return dot > 0 && host.substring(dot + 1).equals(rest);
        }
        return p.indexOf('*') < 0 && host.equals(p);
    }

    /** Whether no dot-separated label of {@code s} is empty. */
    private static boolean labelsNonEmpty(String s) {
        return !s.startsWith(".") && !s.endsWith(".") && !s.contains("..");
    }

    /** {@code s} with ASCII {@code A-Z} lowered and every other character kept. */
    private static String lowerAscii(String s) {
        char[] c = s.toCharArray();
        for (int i = 0; i < c.length; i++) {
            if (c[i] >= 'A' && c[i] <= 'Z') {
                c[i] = (char) (c[i] + ('a' - 'A'));
            }
        }
        return new String(c);
    }

    /** Whether the last label of {@code host}, a trailing root dot aside, is only decimal digits. */
    private static boolean numericLastLabel(String host) {
        String h = host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
        int start = h.lastIndexOf('.') + 1;
        if (start >= h.length()) {
            return false;
        }
        for (int i = start; i < h.length(); i++) {
            if (h.charAt(i) < '0' || h.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * The proxy's one IPv4 literal parser, also behind the literal checks of {@link TargetUrl} and
     * {@link BackendRequest}: the four bytes of a dotted-quad IPv4 literal of decimal octets {@code 0..255} without
     * leading zeros, or {@code null}.
     */
    static byte @Nullable [] ipv4(String s) {
        byte[] out = new byte[4];
        int start = 0;
        for (int octet = 0; octet < 4; octet++) {
            int end = octet < 3 ? s.indexOf('.', start) : s.length();
            if (end < 0 || end == start || end - start > 3 || (end - start > 1 && s.charAt(start) == '0')) {
                return null;
            }
            int value = 0;
            for (int i = start; i < end; i++) {
                char c = s.charAt(i);
                if (c < '0' || c > '9') {
                    return null;
                }
                value = value * 10 + (c - '0');
            }
            if (value > 255) {
                return null;
            }
            out[octet] = (byte) value;
            start = end + 1;
        }
        return out;
    }

    /**
     * The proxy's one IPv6 literal parser, also behind the literal checks of {@link TargetUrl} and
     * {@link BackendRequest}: the sixteen bytes of an IPv6 literal (RFC 4291 text form: up to eight groups of one to
     * four hexadecimal digits, at most one {@code ::}, optionally ending in a dotted-quad IPv4 part), or {@code null}.
     * No zone index, no brackets, no name lookup.
     */
    static byte @Nullable [] ipv6(String s) {
        int[] groups = new int[8];
        int count = 0;
        int gap = -1;
        int i = 0;
        int n = s.length();
        if (s.startsWith("::")) {
            gap = 0;
            i = 2;
        } else if (s.startsWith(":")) {
            return null;
        }
        while (i < n) {
            if (count == 8) {
                return null;
            }
            int end = i;
            while (end < n && s.charAt(end) != ':') {
                end++;
            }
            String part = s.substring(i, end);
            if (part.indexOf('.') >= 0) {
                byte[] v4 = end == n && count <= 6 ? ipv4(part) : null;
                if (v4 == null) {
                    return null;
                }
                groups[count++] = ((v4[0] & 0xff) << 8) | (v4[1] & 0xff);
                groups[count++] = ((v4[2] & 0xff) << 8) | (v4[3] & 0xff);
                i = end;
                break;
            }
            if (part.isEmpty() || part.length() > 4) {
                return null;
            }
            int value = 0;
            for (int k = 0; k < part.length(); k++) {
                int d = hexDigit(part.charAt(k));
                if (d < 0) {
                    return null;
                }
                value = value * 16 + d;
            }
            groups[count++] = value;
            if (end == n) {
                i = end;
                break;
            }
            // end is a ':'
            if (end + 1 < n && s.charAt(end + 1) == ':') {
                if (gap >= 0) {
                    return null;
                }
                gap = count;
                i = end + 2;
            } else if (end + 1 == n) {
                return null; // a trailing single ':'
            } else {
                i = end + 1;
            }
        }
        if ((gap < 0 && count != 8) || (gap >= 0 && count > 7)) {
            return null;
        }
        byte[] out = new byte[16];
        int[] full = new int[8];
        if (gap < 0) {
            full = groups;
        } else {
            int tail = count - gap;
            System.arraycopy(groups, 0, full, 0, gap);
            System.arraycopy(groups, gap, full, 8 - tail, tail);
        }
        for (int g = 0; g < 8; g++) {
            out[2 * g] = (byte) (full[g] >> 8);
            out[2 * g + 1] = (byte) full[g];
        }
        return out;
    }

    /** The value of the ASCII hexadecimal digit {@code c}, or {@code -1}. */
    private static int hexDigit(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }
}
