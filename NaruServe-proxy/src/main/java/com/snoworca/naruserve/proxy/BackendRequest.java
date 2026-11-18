package com.snoworca.naruserve.proxy;

import com.snoworca.naruserve.HttpException;
import com.snoworca.naruserve.common.HttpHeaderValues;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The request head the proxy writes to the backend (design D-053): the request line, {@code Host}, the client header
 * lines in received order without the ones the proxy removes and with the {@code Cookie} lines merged, the forwarding
 * headers, the {@code .requestHeader} values, then the framing header and {@code Connection: close}, all written in
 * ISO-8859-1.
 */
// @req FR-PROXY-003
final class BackendRequest {

    /**
     * The lower-case forwarding names never copied from the client, whatever the trust setting: the proxy writes
     * {@code X-Forwarded-For}, {@code X-Forwarded-Proto} and {@code X-Forwarded-Host} itself and drops the others,
     * which no front proxy is guaranteed to have cleaned (SEC-PROXY-002).
     */
    static final Set<String> CLIENT_FORWARDING_NAMES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "forwarded", "x-forwarded-for", "x-forwarded-proto", "x-forwarded-host", "x-real-ip", "x-forwarded-port",
            "x-forwarded-prefix")));

    private BackendRequest() { }

    /**
     * Answers 400 when a client header line has a name that is not an RFC 9110 token or a value that
     * {@link HttpHeaderValues#isFieldValue} rejects. Every line is checked, including those the proxy drops.
     */
    // @req FR-PROXY-003
    static void checkClientHeaders(List<Map.Entry<String, String>> rawHeaders) {
        for (Map.Entry<String, String> h : rawHeaders) {
            if (!HttpHeaderValues.isToken(h.getKey()) || !HttpHeaderValues.isFieldValue(h.getValue())) {
                throw new HttpException(400);
            }
        }
    }

    /**
     * The value of the client's one {@code Host} line without OWS, or null when there is no {@code Host} line or its
     * value is empty. More than one {@code Host} line, whatever the values, answers 400: the target would be ambiguous
     * (RFC 9112 §3.2).
     */
    // @req FR-PROXY-004
    static @Nullable String receivedHost(List<Map.Entry<String, String>> rawHeaders) {
        @Nullable String host = null;
        boolean seen = false;
        for (Map.Entry<String, String> h : rawHeaders) {
            if (h.getKey().equalsIgnoreCase("Host")) {
                if (seen) {
                    throw new HttpException(400);
                }
                seen = true;
                host = HeaderRules.trimOws(h.getValue());
            }
        }
        return host == null || host.isEmpty() ? null : host;
    }

    /**
     * The forwarding header lines in the design D-053 slot, in this order: {@code X-Forwarded-For} with
     * {@code forwardedFor} when it is not null, {@code X-Forwarded-Proto} with {@code https} or {@code http} from
     * {@code secure}, and {@code X-Forwarded-Host} with the received {@code Host} when there is one.
     */
    // @req SEC-PROXY-002
    // @req FR-PROXY-004
    static List<Map.Entry<String, String>> forwardingHeaders(@Nullable String forwardedFor, boolean secure,
            @Nullable String receivedHost) {
        List<Map.Entry<String, String>> lines = new ArrayList<>(3);
        if (forwardedFor != null) {
            lines.add(new SimpleImmutableEntry<>("X-Forwarded-For", forwardedFor));
        }
        lines.add(new SimpleImmutableEntry<>("X-Forwarded-Proto", secure ? "https" : "http"));
        if (receivedHost != null) {
            lines.add(new SimpleImmutableEntry<>("X-Forwarded-Host", receivedHost));
        }
        return lines;
    }

    /**
     * The {@code X-Forwarded-For} value, or null to send none (design D-055): the socket address without surrounding
     * brackets and {@code %zone} suffix, preceded for a {@code trusted} peer by the value of every client
     * {@code X-Forwarded-For} line in received order, each without OWS and skipped when that leaves it empty, joined
     * with {@code ", "}. Null when the socket address is not an IP literal ({@link #socketLiteral}), whatever the
     * client sent.
     */
    // @req SEC-PROXY-002
    static @Nullable String forwardedFor(List<Map.Entry<String, String>> rawHeaders, boolean trusted,
            @Nullable String socketAddress) {
        @Nullable String peer = socketLiteral(socketAddress);
        if (peer == null) {
            return null;
        }
        StringBuilder value = new StringBuilder();
        if (trusted) {
            for (Map.Entry<String, String> h : rawHeaders) {
                if (!h.getKey().equalsIgnoreCase("X-Forwarded-For")) {
                    continue;
                }
                String v = HeaderRules.trimOws(h.getValue());
                if (!v.isEmpty()) {
                    value.append(v).append(", ");
                }
            }
        }
        return value.append(peer).toString();
    }

    /**
     * {@code address} without surrounding brackets and without a {@code %zone} suffix when what remains is an IPv4
     * or IPv6 literal, otherwise null. The literal is kept as written, not normalised, as Express {@code req.ip}.
     * The check is plain text parsing: nothing here resolves a name.
     */
    // @req SEC-PROXY-002
    static @Nullable String socketLiteral(@Nullable String address) {
        if (address == null) {
            return null;
        }
        String a = address;
        if (a.length() >= 2 && a.charAt(0) == '[' && a.charAt(a.length() - 1) == ']') {
            a = a.substring(1, a.length() - 1);
        }
        int zone = a.indexOf('%');
        if (zone >= 0) {
            a = a.substring(0, zone);
        }
        return isIpv4(a) || isIpv6(a) ? a : null;
    }

    /** Whether {@code s} is four dot-separated decimal octets 0 to 255 without leading zeros (RFC 3986 IPv4address). */
    // @req SEC-PROXY-002
    static boolean isIpv4(String s) {
        String[] octets = s.split("\\.", -1);
        if (octets.length != 4) {
            return false;
        }
        for (String o : octets) {
            if (o.isEmpty() || o.length() > 3 || (o.length() > 1 && o.charAt(0) == '0')) {
                return false;
            }
            for (int i = 0; i < o.length(); i++) {
                if (o.charAt(i) < '0' || o.charAt(i) > '9') {
                    return false;
                }
            }
            if (Integer.parseInt(o) > 255) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether {@code s} is an IPv6 literal (RFC 4291 §2.2): eight groups of one to four hex digits, or fewer around
     * one {@code ::}, the last two groups optionally written as an IPv4 address.
     */
    // @req SEC-PROXY-002
    static boolean isIpv6(String s) {
        int gap = s.indexOf("::");
        if (gap < 0) {
            return groups(s, true) == 8;
        }
        if (s.indexOf("::", gap + 1) >= 0) {
            return false;
        }
        int before = groups(s.substring(0, gap), false);
        int after = groups(s.substring(gap + 2), true);
        return before >= 0 && after >= 0 && before + after <= 7;
    }

    /**
     * The number of 16-bit groups {@code part} writes, colon-separated, or -1 when it is not a group list; an empty
     * {@code part} is no group. When {@code atEnd} the last element may be an IPv4 address, counted as two groups.
     */
    private static int groups(String part, boolean atEnd) {
        if (part.isEmpty()) {
            return 0;
        }
        String[] elements = part.split(":", -1);
        int count = 0;
        for (int i = 0; i < elements.length; i++) {
            String e = elements[i];
            if (atEnd && i == elements.length - 1 && e.indexOf('.') >= 0) {
                if (!isIpv4(e)) {
                    return -1;
                }
                count += 2;
            } else if (isHexGroup(e)) {
                count++;
            } else {
                return -1;
            }
        }
        return count;
    }

    private static boolean isHexGroup(String e) {
        if (e.isEmpty() || e.length() > 4) {
            return false;
        }
        for (int i = 0; i < e.length(); i++) {
            char c = e.charAt(i);
            // ASCII only: Character.digit would also accept full-width and other Unicode digits.
            if (!(c >= '0' && c <= '9' || c >= 'a' && c <= 'f' || c >= 'A' && c <= 'F')) {
                return false;
            }
        }
        return true;
    }

    /**
     * The client header lines to forward, in received order with duplicates and the reported name letters: without
     * the names in {@link HeaderRules#REQUEST_NOT_FORWARDED}, the forwarding names of
     * {@link #CLIENT_FORWARDING_NAMES} and those listed in a {@code Connection} line, and with
     * every {@code Cookie} line joined by {@code "; "} into one line at the position (and with the name letters) of
     * the first. The lines must have passed {@link #checkClientHeaders}.
     */
    // @req FR-PROXY-003
    // @req SEC-PROXY-002
    static List<Map.Entry<String, String>> forwardedClientHeaders(List<Map.Entry<String, String>> rawHeaders) {
        Set<String> connectionNames = HeaderRules.connectionNames(rawHeaders);
        List<Map.Entry<String, String>> out = new ArrayList<>(rawHeaders.size());
        int cookieAt = -1;
        String cookieName = "";
        @Nullable StringBuilder cookie = null;
        for (Map.Entry<String, String> h : rawHeaders) {
            String lower = h.getKey().toLowerCase(Locale.ROOT);
            if (HeaderRules.REQUEST_NOT_FORWARDED.contains(lower) || CLIENT_FORWARDING_NAMES.contains(lower)
                    || connectionNames.contains(lower)) {
                continue;
            }
            if (!lower.equals("cookie")) {
                out.add(h);
            } else if (cookie == null) {
                cookieAt = out.size();
                cookieName = h.getKey();
                cookie = new StringBuilder(h.getValue());
                out.add(h);
            } else {
                cookie.append("; ").append(h.getValue());
            }
        }
        if (cookie != null) {
            out.set(cookieAt, new SimpleImmutableEntry<>(cookieName, cookie.toString()));
        }
        return out;
    }

    /**
     * The backend request head as ISO-8859-1 bytes, one byte per character, ending with the blank line: the
     * {@code requestLine}, {@code Host: host}, then each list in order, then {@code framing} when it is not null and
     * {@code Connection: close}.
     */
    // @req FR-PROXY-003
    static byte[] head(String requestLine, String host, List<Map.Entry<String, String>> clientHeaders,
            List<Map.Entry<String, String>> forwardingHeaders, List<Map.Entry<String, String>> requestHeaderValues,
            Map.@Nullable Entry<String, String> framing) {
        StringBuilder head = new StringBuilder(requestLine).append("\r\n");
        appendLine(head, "Host", host);
        appendLines(head, clientHeaders);
        appendLines(head, forwardingHeaders);
        appendLines(head, requestHeaderValues);
        if (framing != null) {
            appendLine(head, framing.getKey(), framing.getValue());
        }
        appendLine(head, "Connection", "close");
        head.append("\r\n");
        return head.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private static void appendLines(StringBuilder head, List<Map.Entry<String, String>> lines) {
        for (Map.Entry<String, String> h : lines) {
            appendLine(head, h.getKey(), h.getValue());
        }
    }

    private static void appendLine(StringBuilder head, String name, String value) {
        head.append(name).append(": ").append(value).append("\r\n");
    }
}
