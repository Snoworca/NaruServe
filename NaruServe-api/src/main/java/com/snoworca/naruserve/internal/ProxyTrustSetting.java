package com.snoworca.naruserve.internal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The app's one immutable proxy trust setting: none, the legacy {@code trustProxy(true)} rule, hop mode
 * ({@code trustProxyHops(n)}) or CIDR mode ({@code trustProxyCidrs(...)}). {@link DefaultRequest} resolves
 * {@code remoteAddress()} and {@code isSecure()} through it for every adapter; no adapter reads a forwarding header.
 * Hop and CIDR mode combine every X-Forwarded-For line in arrival order, skip blank entries, read the list from the
 * right and never read Forwarded.
 */
// @req IR-ENGINE-003
// @req SEC-ENGINE-001
// @req SEC-ENGINE-002
// @req SEC-ENGINE-003
final class ProxyTrustSetting {

    private enum Mode { NONE, LEGACY, HOPS, CIDRS }

    static final ProxyTrustSetting NONE = new ProxyTrustSetting(Mode.NONE, 0, Collections.<String>emptyList(),
            new Cidr[0]);
    static final ProxyTrustSetting LEGACY = new ProxyTrustSetting(Mode.LEGACY, 0, Collections.<String>emptyList(),
            new Cidr[0]);

    private final Mode mode;
    private final int hops;
    private final List<String> cidrText;
    private final Cidr[] cidrs;

    private ProxyTrustSetting(Mode mode, int hops, List<String> cidrText, Cidr[] cidrs) {
        this.mode = mode;
        this.hops = hops;
        this.cidrText = cidrText;
        this.cidrs = cidrs;
    }

    /** {@code trustProxy(trust)}. */
    static ProxyTrustSetting legacy(boolean trust) {
        return trust ? LEGACY : NONE;
    }

    /** {@code trustProxyHops(n)}: {@link IllegalArgumentException} below 0; 0 trusts no peer. */
    // @req IR-ENGINE-003
    static ProxyTrustSetting hops(int n) {
        if (n < 0) {
            throw new IllegalArgumentException("trustProxyHops must be >= 0: " + n);
        }
        return new ProxyTrustSetting(Mode.HOPS, n, Collections.<String>emptyList(), new Cidr[0]);
    }

    /**
     * {@code trustProxyCidrs(values)}: {@link NullPointerException} for a null array or element,
     * {@link IllegalArgumentException} naming the value for anything that is not an IP literal with an optional
     * {@code /prefix}; an address without a prefix is the one host. No name is resolved.
     */
    // @req IR-ENGINE-003
    static ProxyTrustSetting cidrs(String... values) {
        Objects.requireNonNull(values, "cidrs");
        Cidr[] parsed = new Cidr[values.length];
        List<String> text = new ArrayList<>(values.length);
        for (int i = 0; i < values.length; i++) {
            String v = Objects.requireNonNull(values[i], "cidrs[" + i + "]");
            parsed[i] = Cidr.parse(v);
            text.add(v);
        }
        return new ProxyTrustSetting(Mode.CIDRS, 0, Collections.unmodifiableList(text), parsed);
    }

    boolean legacyFlag() {
        return mode == Mode.LEGACY;
    }

    int hopCount() {
        return mode == Mode.HOPS ? hops : 0;
    }

    List<String> cidrList() {
        return cidrText;
    }

    /** {@code req.remoteAddress()} for the socket {@code peer} and every X-Forwarded-For line. */
    // @req SEC-ENGINE-001
    // @req SEC-ENGINE-002
    String remoteAddress(String peer, List<String> forwardedFor) {
        switch (mode) {
            case LEGACY:
                return ProxyTrust.remoteAddress(forwardedFor.isEmpty() ? null : forwardedFor.get(0), peer);
            case HOPS:
            case CIDRS:
                List<String> forwarded = entries(forwardedFor);
                int at = clientIndex(peer, forwarded);
                return at < 0 ? peer : forwarded.get(at);
            default:
                return peer;
        }
    }

    /**
     * {@code req.isSecure()}: in hop and CIDR mode X-Forwarded-Proto counts only from a trusted peer; one value is used
     * as is, several are matched by the position of the chosen X-Forwarded-For entry counted from the right, and a
     * count different from X-Forwarded-For or a missing position gives false.
     */
    // @req SEC-ENGINE-003
    boolean isSecure(String peer, boolean exchangeSecure, List<String> forwardedFor, List<String> forwardedProto) {
        switch (mode) {
            case LEGACY:
                return ProxyTrust.isSecure(forwardedProto.isEmpty() ? null : forwardedProto.get(0), exchangeSecure);
            case HOPS:
            case CIDRS:
                if (!peerTrusted(peer)) {
                    return exchangeSecure;
                }
                List<String> protos = entries(forwardedProto);
                if (protos.isEmpty()) {
                    return exchangeSecure;
                }
                if (protos.size() == 1) {
                    return protos.get(0).equalsIgnoreCase("https");
                }
                List<String> forwarded = entries(forwardedFor);
                if (protos.size() != forwarded.size()) {
                    return false;
                }
                int fromRight = mode == Mode.HOPS ? hops : forwarded.size() - clientIndex(peer, forwarded);
                if (fromRight < 1 || fromRight > protos.size()) {
                    return false;
                }
                return protos.get(protos.size() - fromRight).equalsIgnoreCase("https");
            default:
                return exchangeSecure;
        }
    }

    private boolean peerTrusted(String peer) {
        return mode == Mode.HOPS ? hops >= 1 : mode == Mode.CIDRS && trusted(peer);
    }

    /**
     * The index in {@code entries} of the client address, or -1 for the socket peer: hop mode takes the nth entry from
     * the right (the left-most when there are fewer), CIDR mode the first entry from the right outside every trusted
     * CIDR (the left-most when all are inside).
     */
    private int clientIndex(String peer, List<String> entries) {
        if (entries.isEmpty() || !peerTrusted(peer)) {
            return -1;
        }
        if (mode == Mode.HOPS) {
            return Math.max(0, entries.size() - hops);
        }
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (!trusted(entries.get(i))) {
                return i;
            }
        }
        return 0;
    }

    private boolean trusted(String address) {
        byte[] ip = Cidr.address(address);
        if (ip == null) {
            return false;
        }
        for (Cidr c : cidrs) {
            if (c.contains(ip)) {
                return true;
            }
        }
        return false;
    }

    /** Every comma-separated, trimmed, non-blank entry of {@code lines}, in arrival order. */
    private static List<String> entries(List<String> lines) {
        if (lines.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            for (String part : line.split(",", -1)) {
                String e = part.trim();
                if (!e.isEmpty()) {
                    out.add(e);
                }
            }
        }
        return out;
    }

    /** One parsed CIDR: network bytes (4 or 16) and prefix bits. */
    // @req IR-ENGINE-003
    static final class Cidr {
        private final byte[] network;
        private final int prefix;

        private Cidr(byte[] network, int prefix) {
            this.network = network;
            this.prefix = prefix;
        }

        static Cidr parse(String value) {
            int slash = value.indexOf('/');
            String host = slash < 0 ? value : value.substring(0, slash);
            byte[] ip = host.indexOf(':') >= 0 ? ipv6(host) : ipv4(host);
            if (ip == null) {
                throw invalid(value);
            }
            int bits = ip.length * 8;
            int prefix = bits;
            if (slash >= 0) {
                String p = value.substring(slash + 1);
                if (p.isEmpty() || p.length() > 3) {
                    throw invalid(value);
                }
                for (int i = 0; i < p.length(); i++) {
                    if (p.charAt(i) < '0' || p.charAt(i) > '9') {
                        throw invalid(value);
                    }
                }
                prefix = Integer.parseInt(p);
                if (prefix > bits) {
                    throw invalid(value);
                }
            }
            if (ip.length == 16 && mapped(ip)) {
                // An IPv4-mapped CIDR is matched as IPv4, as a mapped peer address is.
                if (prefix < 96) {
                    throw invalid(value);
                }
                ip = Arrays.copyOfRange(ip, 12, 16);
                prefix -= 96;
            }
            byte[] net = Arrays.copyOf(ip, ip.length);
            for (int i = 0; i < net.length; i++) {
                int keep = Math.max(0, Math.min(8, prefix - i * 8));
                net[i] = (byte) (net[i] & (0xff << (8 - keep)));
            }
            return new Cidr(net, prefix);
        }

        private static IllegalArgumentException invalid(String value) {
            return new IllegalArgumentException("trustProxyCidrs: not an IP address or CIDR: " + value);
        }

        boolean contains(byte[] ip) {
            if (ip.length != network.length) {
                return false;
            }
            for (int i = 0; i < network.length; i++) {
                int keep = Math.max(0, Math.min(8, prefix - i * 8));
                int mask = (0xff << (8 - keep)) & 0xff;
                if ((ip[i] & mask) != (network[i] & 0xff)) {
                    return false;
                }
            }
            return true;
        }

        /**
         * The bytes of an adapter or X-Forwarded-For address: an IPv4 literal, or an IPv6 literal with optional
         * brackets and zone; an IPv4-mapped IPv6 address gives its 4 IPv4 bytes. Null for anything else.
         */
        static byte @Nullable [] address(String text) {
            String a = text.trim();
            if (a.startsWith("[") && a.endsWith("]")) {
                a = a.substring(1, a.length() - 1);
            }
            if (a.indexOf(':') < 0) {
                return ipv4(a);
            }
            int zone = a.indexOf('%');
            byte[] v6 = ipv6(zone < 0 ? a : a.substring(0, zone));
            if (v6 == null) {
                return null;
            }
            return mapped(v6) ? Arrays.copyOfRange(v6, 12, 16) : v6;
        }

        /** True for {@code ::ffff:a.b.c.d}. */
        private static boolean mapped(byte[] v6) {
            for (int i = 0; i < 10; i++) {
                if (v6[i] != 0) {
                    return false;
                }
            }
            return (v6[10] & 0xff) == 0xff && (v6[11] & 0xff) == 0xff;
        }

        static byte @Nullable [] ipv4(String a) {
            String[] parts = a.split("\\.", -1);
            if (parts.length != 4) {
                return null;
            }
            byte[] out = new byte[4];
            for (int i = 0; i < 4; i++) {
                String p = parts[i];
                if (p.isEmpty() || p.length() > 3) {
                    return null;
                }
                int v = 0;
                for (int j = 0; j < p.length(); j++) {
                    char c = p.charAt(j);
                    if (c < '0' || c > '9') {
                        return null;
                    }
                    v = v * 10 + (c - '0');
                }
                if (v > 255) {
                    return null;
                }
                out[i] = (byte) v;
            }
            return out;
        }

        static byte @Nullable [] ipv6(String a) {
            if (a.isEmpty() || a.contains(":::")) {
                return null;
            }
            int gap = a.indexOf("::");
            if (gap >= 0 && a.indexOf("::", gap + 1) >= 0) {
                return null;
            }
            List<Integer> head = new ArrayList<>();
            List<Integer> tail = new ArrayList<>();
            if (gap < 0) {
                if (!groups(a, head, true)) {
                    return null;
                }
                if (head.size() != 8) {
                    return null;
                }
            } else {
                if (!groups(a.substring(0, gap), head, false) || !groups(a.substring(gap + 2), tail, true)) {
                    return null;
                }
                if (head.size() + tail.size() > 7) {
                    return null;
                }
            }
            byte[] out = new byte[16];
            int i = 0;
            for (int g : head) {
                out[i++] = (byte) (g >> 8);
                out[i++] = (byte) g;
            }
            i = 16 - tail.size() * 2;
            for (int g : tail) {
                out[i++] = (byte) (g >> 8);
                out[i++] = (byte) g;
            }
            return out;
        }

        /**
         * Appends the 16-bit groups of {@code s} (possibly empty); a dotted IPv4 is allowed as the last group only when
         * {@code last}; false if malformed.
         */
        private static boolean groups(String s, List<Integer> out, boolean last) {
            if (s.isEmpty()) {
                return true;
            }
            String[] parts = s.split(":", -1);
            for (int i = 0; i < parts.length; i++) {
                String p = parts[i];
                if (last && i == parts.length - 1 && p.indexOf('.') >= 0) {
                    byte[] v4 = ipv4(p);
                    if (v4 == null) {
                        return false;
                    }
                    out.add(((v4[0] & 0xff) << 8) | (v4[1] & 0xff));
                    out.add(((v4[2] & 0xff) << 8) | (v4[3] & 0xff));
                    continue;
                }
                if (p.isEmpty() || p.length() > 4) {
                    return false;
                }
                int v = 0;
                for (int j = 0; j < p.length(); j++) {
                    char c = p.charAt(j);
                    int d = c >= '0' && c <= '9' ? c - '0' : c >= 'a' && c <= 'f' ? c - 'a' + 10
                            : c >= 'A' && c <= 'F' ? c - 'A' + 10 : -1;
                    if (d < 0) {
                        return false;
                    }
                    v = v * 16 + d;
                }
                out.add(v);
            }
            return true;
        }
    }
}
