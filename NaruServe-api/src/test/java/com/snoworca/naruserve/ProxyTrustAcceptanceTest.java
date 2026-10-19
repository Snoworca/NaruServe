package com.snoworca.naruserve;

import static org.junit.Assert.assertEquals;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.TestApp;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

/** app.trustProxy and X-Forwarded-*. SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-5-4.sds.md. */
public class ProxyTrustAcceptanceTest {

    private static TestApp app(boolean trust, String remote, boolean secure) {
        TestApp t = TestApp.create(new InMemoryHttpAdapter(remote, secure));
        t.app().trustProxy(trust);
        t.app().get("/who", (req, res) -> res.send(req.remoteAddress() + " " + req.isSecure()));
        return t;
    }

    private static String who(TestApp t, String... headers) {
        return new String(t.send("GET", "/who", headers).body(), StandardCharsets.UTF_8);
    }

    // FR-ENGINE-008 AC-1
    @Test
    public void FR_ENGINE_008_AC1_withoutTrustProxyForwardedHeadersAreIgnored() {
        TestApp t = app(false, "127.0.0.1", false);
        assertEquals("127.0.0.1 false", who(t, "X-Forwarded-For", "203.0.113.7", "X-Forwarded-Proto", "https",
                "Forwarded", "for=198.51.100.1;proto=https"));
    }

    // SDS-AC-8; FR-ENGINE-008 AC-2
    @Test
    public void sdsAc8_FR_ENGINE_008_AC2_trustedModeUsesTheLeftMostForAndTheFirstProto() {
        TestApp t = app(true, "127.0.0.1", false);
        assertEquals("203.0.113.7 false", who(t, "X-Forwarded-For", "203.0.113.7, 10.0.0.1"));
        assertEquals("203.0.113.7 false", who(t, "X-Forwarded-For", " 203.0.113.7 ,10.0.0.1",
                "X-Forwarded-For", "198.51.100.9"));
        assertEquals("127.0.0.1 true", who(t, "X-Forwarded-Proto", "https"));
        assertEquals("127.0.0.1 true", who(t, "x-forwarded-proto", "HTTPS, http"));
        assertEquals("127.0.0.1 false", who(t, "X-Forwarded-Proto", "http"));
        assertEquals("127.0.0.1 false", who(t));

        TestApp secure = app(true, "10.1.2.3", true);
        assertEquals("10.1.2.3 true", who(secure));
        assertEquals("10.1.2.3 false", who(secure, "X-Forwarded-Proto", "http"));
    }

    // SDS-AC-9; FR-ENGINE-008 AC-3
    @Test
    public void sdsAc9_FR_ENGINE_008_AC3_forwardedAloneAndBlankEntriesKeepTheExchangeValues() {
        TestApp t = app(true, "10.1.2.3", false);
        assertEquals("10.1.2.3 false", who(t, "Forwarded", "for=198.51.100.1;proto=https"));
        assertEquals("10.1.2.3 false", who(t, "X-Forwarded-For", " , 10.0.0.1"));
        assertEquals("10.1.2.3 false", who(t, "X-Forwarded-For", ""));
        assertEquals("10.1.2.3 false", who(t, "X-Forwarded-Proto", " "));
    }
}
