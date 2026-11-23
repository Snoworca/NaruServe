package com.snoworca.naruserve.proxy;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * One IPv4/IPv6 literal grammar (RFC 3986 IPv4address, RFC 4291 §2.2 text form) at every call site of the proxy:
 * the certificate {@code iPAddress} match (SEC-PROXY-005), the peer literal of {@code X-Forwarded-For}
 * (SEC-PROXY-002) and the target URL host (FR-PROXY-001). Each row runs through all three, so a host the target URL
 * accepts is one the certificate check can parse, and the same peer is forwarded.
 */
public class IpLiteralGrammarTest {

    /** {text, IPv4?, IPv6?}. */
    private static final Object[][] CASES = {
        {"0.0.0.0", true, false},
        {"127.0.0.1", true, false},
        {"255.255.255.255", true, false},
        {"256.1.1.1", false, false},
        {"1.2.3.1000", false, false},
        {"1.2.3", false, false},
        {"1.2.3.4.5", false, false},
        {"01.2.3.4", false, false},
        {"1.2.3.04", false, false},
        {"0.0.0.00", false, false},
        {"1.2.3.4.", false, false},
        {"1..3.4", false, false},
        {".1.2.3.4", false, false},
        {"::", false, true},
        {"::1", false, true},
        {"1::", false, true},
        {"1:2:3:4:5:6:7:8", false, true},
        {"ABCD:ef01::", false, true},
        {"1:2:3:4:5:6:7::", false, true},
        {"::2:3:4:5:6:7:8", false, true},
        {"1::2:3:4:5:6:7", false, true},
        {"::ffff:1.2.3.4", false, true},
        {"::1.2.3.4", false, true},
        {"1:2:3:4:5:6:1.2.3.4", false, true},
        {"1:2:3:4:5:6:7:8:9", false, false},
        {"1:2:3:4:5:6:7", false, false},
        {"1:2:3:4::5:6:7:8", false, false},
        {"1::2::3", false, false},
        {":::", false, false},
        {":1::", false, false},
        {"1::2:", false, false},
        {":1:2:3:4:5:6:7:8", false, false},
        {"1:2:3:4:5:6:7:8:", false, false},
        {"1:2:3:4:5:6:7:1.2.3.4", false, false},
        {"1::2:3:4:5:6:1.2.3.4", false, false},
        {"1.2.3.4::", false, false},
        {"::1.2.3.4:1", false, false},
        {"::01.2.3.4", false, false},
        {"::256.2.3.4", false, false},
        {"12345::", false, false},
        {"g::", false, false},
        {"1:2", false, false},
        {"", false, false},
    };

    // SEC-PROXY-005, SEC-PROXY-002, FR-PROXY-001: the certificate matcher's byte parser, the X-Forwarded-For peer
    // literal check and the target URL host check accept and refuse the same IPv4 and IPv6 literals
    @Test
    public void everyCallSiteAppliesTheSameLiteralGrammar() {
        for (Object[] row : CASES) {
            String s = (String) row[0];
            boolean v4 = (Boolean) row[1];
            boolean v6 = (Boolean) row[2];
            assertEquals("CertificateHostnames.ipv4(" + s + ")", v4, CertificateHostnames.ipv4(s) != null);
            assertEquals("CertificateHostnames.ipv6(" + s + ")", v6, CertificateHostnames.ipv6(s) != null);
            assertEquals("BackendRequest.socketLiteral(" + s + ")", v4 || v6, BackendRequest.socketLiteral(s) != null);
            if (!s.isEmpty()) {
                assertEquals("BackendRequest.socketLiteral([" + s + "])", v4 || v6,
                        BackendRequest.socketLiteral("[" + s + "]") != null);
            }
            if (s.indexOf(':') >= 0) {
                assertEquals("TargetUrl host [" + s + "]", v6, parses("http://[" + s + "]/"));
            } else if (!s.isEmpty()) {
                // Digits and dots only: the last label is a number, so the host must be an IPv4 literal.
                assertEquals("TargetUrl host " + s, v4, parses("http://" + s + "/"));
            }
        }
    }

    private static boolean parses(String url) {
        try {
            TargetUrl.parse(url);
            return true;
        } catch (IllegalArgumentException refused) {
            return false;
        }
    }
}
