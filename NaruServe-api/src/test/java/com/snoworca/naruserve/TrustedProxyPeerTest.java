package com.snoworca.naruserve;

import static org.junit.Assert.assertEquals;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.TestApp;
import org.junit.Test;

/** FR-HTTP-034: req.isFromTrustedProxy() follows the app's proxy trust setting for the direct peer. */
public class TrustedProxyPeerTest {

    /** An app over socket peer {@code peer} answering {@code /t} with "isFromTrustedProxy remoteAddress". */
    private static TestApp app(String peer, Configurer<RestApp> trust) {
        TestApp t = TestApp.create(new InMemoryHttpAdapter(peer, false));
        trust.configure(t.app());
        t.app().get("/t", (req, res) -> res.send(req.isFromTrustedProxy() + " " + req.remoteAddress()));
        return t;
    }

    private static String answer(String peer, Configurer<RestApp> trust, String... headers) {
        return app(peer, trust).send("GET", "/t", headers).text();
    }

    private static boolean trusted(String peer, Configurer<RestApp> trust) {
        String text = answer(peer, trust);
        return Boolean.parseBoolean(text.substring(0, text.indexOf(' ')));
    }

    // FR-HTTP-034 AC-1: no trust setting, trustProxyHops(0) and an empty trustProxyCidrs() trust no peer
    @Test
    public void FR_HTTP_034_AC1_noSettingZeroHopsAndEmptyCidrsAreNotTrusted() {
        assertEquals(false, trusted("10.0.0.1", a -> { }));
        assertEquals(false, trusted("10.0.0.1", a -> a.trustProxy(false)));
        assertEquals(false, trusted("10.0.0.1", a -> a.trustProxyHops(0)));
        assertEquals(false, trusted("10.0.0.1", a -> a.trustProxyCidrs(new String[0])));
    }

    // FR-HTTP-034 AC-2: trustProxyHops(1), trustProxyHops(3) and the legacy trustProxy(true) trust the peer
    @Test
    public void FR_HTTP_034_AC2_positiveHopsAndLegacyTrustAreTrusted() {
        assertEquals(true, trusted("10.0.0.1", a -> a.trustProxyHops(1)));
        assertEquals(true, trusted("10.0.0.1", a -> a.trustProxyHops(3)));
        assertEquals(true, trusted("10.0.0.1", a -> a.trustProxy(true)));
    }

    // FR-HTTP-034 AC-3: under trustProxyCidrs("10.0.0.0/8") only a peer inside the CIDR is trusted, and remoteAddress
    // follows X-Forwarded-For exactly for that peer
    @Test
    public void FR_HTTP_034_AC3_cidrModeTrustsOnlyAPeerInsideTheCidr() {
        Configurer<RestApp> cidr = a -> a.trustProxyCidrs("10.0.0.0/8");
        assertEquals(true, trusted("10.1.2.3", cidr));
        assertEquals(false, trusted("192.168.0.1", cidr));
        assertEquals("true 203.0.113.7", answer("10.1.2.3", cidr, "X-Forwarded-For", "203.0.113.7"));
        assertEquals("false 192.168.0.1", answer("192.168.0.1", cidr, "X-Forwarded-For", "203.0.113.7"));
    }

    // FR-HTTP-034 AC-4: under trustProxyCidrs(...) a socket address that is not an IP address is not trusted
    @Test
    public void FR_HTTP_034_AC4_unparsablePeerIsNotTrustedInCidrMode() {
        Configurer<RestApp> cidr = a -> a.trustProxyCidrs("10.0.0.0/8", "0.0.0.0/0", "::/0");
        assertEquals(false, trusted("", cidr));
        assertEquals(false, trusted("unknown", cidr));
        assertEquals(false, trusted("10.1.2", cidr));
        assertEquals(true, trusted("::1", cidr));
    }
}
