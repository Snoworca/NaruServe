package com.snoworca.naruserve.utils.auth.internal;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The keys auth counts failures under in a {@code LoginThrottle}, one prefix per key kind so kinds never collide: the
 * per-user current-password confirmation (SEC-FLOW-015), the key-only per-IP counter of API keys (SEC-APIKEY-005) and
 * the three login keys over the normalized login name (SEC-FLOW-011): {@code (user, IP)}, IP and user-only. The IP part is {@code req.remoteAddress()} after proxy trust;
 * IPv6 addresses of one {@code /64} share a key, an IPv4-mapped IPv6 address counts as its IPv4 address in every
 * spelling ({@code ::ffff:1.2.3.4}, {@code ::ffff:102:304}, {@code 0:0:0:0:0:ffff:1.2.3.4}).
 */
// @req SEC-FLOW-015
// @req SEC-FLOW-011
public final class ThrottleKeys {

    private ThrottleKeys() {
    }

    /** The current-password confirmation limit of a user id. */
    // @req SEC-FLOW-015
    public static String passwordConfirm(String userId) {
        return "pwconfirm:" + userId;
    }

    /** The key-only per-IP counter of failed API key authentications (SEC-APIKEY-005), apart from every login key. */
    // @req SEC-APIKEY-005
    public static String apiKeyIp(String ip) {
        return "apikey:ip:" + ipKey(ip);
    }

    /** The {@code (user, IP)} login key; {@code name} is already normalized. */
    // @req SEC-FLOW-011
    public static String loginUserIp(String name, String ip) {
        return "login:ui:" + ipKey(ip) + "|" + name;
    }

    /** The IP login key. */
    // @req SEC-FLOW-011
    public static String loginIp(String ip) {
        return "login:ip:" + ipKey(ip);
    }

    /** The user-only login key; {@code name} is already normalized. */
    // @req SEC-FLOW-011
    public static String loginUser(String name) {
        return "login:u:" + name;
    }

    /**
     * The address as counted: IPv4 as written, IPv6 reduced to its {@code /64} prefix (the first four groups, zero
     * compression expanded), an IPv4-mapped IPv6 address (dotted or hex) as its dotted IPv4 address; anything
     * unparsable as written.
     */
    // @req SEC-FLOW-011
    public static String ipKey(String ip) {
        String a = ip.trim().toLowerCase(Locale.ROOT);
        if (a.startsWith("[") && a.endsWith("]")) {
            a = a.substring(1, a.length() - 1);
        }
        int zone = a.indexOf('%');
        if (zone >= 0) {
            a = a.substring(0, zone);
        }
        if (a.indexOf(':') < 0) {
            return a;
        }
        if (a.startsWith("::ffff:") && a.indexOf('.') > 0) {
            return a.substring(7);
        }
        String hex = a.indexOf('.') < 0 ? a : dottedTailAsHex(a);
        String[] groups = hex == null ? null : expand(hex);
        if (groups == null) {
            return a;
        }
        if (isIpv4Mapped(groups)) { // ::ffff:c000:201 and 0:0:0:0:0:ffff:1.2.3.4 count as 192.0.2.1 and 1.2.3.4
            int high = Integer.parseInt(groups[6], 16);
            int low = Integer.parseInt(groups[7], 16);
            return (high >> 8) + "." + (high & 0xff) + "." + (low >> 8) + "." + (low & 0xff);
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            b.append(i == 0 ? "" : ":").append(Integer.toHexString(Integer.parseInt(groups[i], 16)));
        }
        return b.append("::/64").toString();
    }

    /** True for {@code 0:0:0:0:0:ffff:x:y}, the IPv4-mapped range {@code ::ffff:0:0/96}. */
    private static boolean isIpv4Mapped(String[] groups) {
        for (int i = 0; i < 5; i++) {
            if (Integer.parseInt(groups[i], 16) != 0) {
                return false;
            }
        }
        return Integer.parseInt(groups[5], 16) == 0xffff;
    }

    /**
     * The literal with its trailing dotted IPv4 part written as two hex groups ({@code ::ffff:1.2.3.4} becomes
     * {@code ::ffff:102:304}), or null when that part is not four decimal octets of ASCII digits.
     */
    private static @Nullable String dottedTailAsHex(String a) {
        int colon = a.lastIndexOf(':');
        String[] octets = a.substring(colon + 1).split("\\.", -1);
        if (octets.length != 4) {
            return null;
        }
        int[] v = new int[4];
        for (int i = 0; i < 4; i++) {
            String o = octets[i];
            if (o.isEmpty() || o.length() > 3) {
                return null;
            }
            for (int j = 0; j < o.length(); j++) {
                if (o.charAt(j) < '0' || o.charAt(j) > '9') {
                    return null;
                }
            }
            v[i] = Integer.parseInt(o);
            if (v[i] > 255) {
                return null;
            }
        }
        return a.substring(0, colon + 1) + Integer.toHexString(v[0] << 8 | v[1]) + ":"
                + Integer.toHexString(v[2] << 8 | v[3]);
    }

    /** The eight hex groups of an IPv6 literal, or null when it is not one. */
    private static String @Nullable [] expand(String a) {
        int dbl = a.indexOf("::");
        if (dbl >= 0 && a.indexOf("::", dbl + 1) >= 0) {
            return null;
        }
        String[] head = dbl < 0 ? a.split(":", -1) : split(a.substring(0, dbl));
        String[] tail = dbl < 0 ? new String[0] : split(a.substring(dbl + 2));
        int missing = 8 - head.length - tail.length;
        if (dbl < 0 ? head.length != 8 : missing < 1) {
            return null;
        }
        String[] out = new String[8];
        int o = 0;
        for (String h : head) {
            out[o++] = h;
        }
        for (int i = 0; dbl >= 0 && i < missing; i++) {
            out[o++] = "0";
        }
        for (String t : tail) {
            out[o++] = t;
        }
        for (String g : out) {
            if (g.isEmpty() || g.length() > 4) {
                return null;
            }
            for (int i = 0; i < g.length(); i++) {
                if (Character.digit(g.charAt(i), 16) < 0) {
                    return null;
                }
            }
        }
        return out;
    }

    private static String[] split(String s) {
        return s.isEmpty() ? new String[0] : s.split(":", -1);
    }
}
