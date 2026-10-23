package com.snoworca.naruserve.internal;

import java.util.Locale;

/** The {@code http://host:port} form of a bind address, for {@code ServerHandle.url()} and the start line. */
// @req FR-DX-013
public final class ServerUrls {

    private ServerUrls() {
    }

    /** True for the IPv4 and IPv6 wildcard addresses, with or without the IPv6 brackets. */
    // @req FR-DX-013
    static boolean isWildcard(String address) {
        String a = unbracket(address);
        return a.equals("0.0.0.0") || a.equals("::") || a.equals("::0") || a.equals("0:0:0:0:0:0:0:0");
    }

    /** {@code http://<address>:<port>}: {@code localhost} for a wildcard, brackets around an IPv6 literal. */
    // @req FR-DX-013
    public static String of(String address, int port) {
        return "http://" + host(address) + ":" + port;
    }

    /** The host part: {@code localhost} for a wildcard, an IPv6 literal in brackets, else the address as it is. */
    static String host(String address) {
        if (isWildcard(address)) {
            return "localhost";
        }
        return bracketed(address);
    }

    /** The address as a URL host: an IPv6 literal gets brackets, everything else is unchanged. */
    static String bracketed(String address) {
        String a = unbracket(address);
        return a.indexOf(':') >= 0 ? "[" + a.toLowerCase(Locale.ROOT).replace("%", "%25") + "]"
                : address;
    }

    private static String unbracket(String address) {
        return address.startsWith("[") && address.endsWith("]") ? address.substring(1, address.length() - 1)
                : address;
    }
}
