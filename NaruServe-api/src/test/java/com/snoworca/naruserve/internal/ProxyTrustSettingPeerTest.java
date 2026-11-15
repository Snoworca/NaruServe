package com.snoworca.naruserve.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.Configurer;
import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.TestApp;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;

/** FR-HTTP-034 AC-5: the one package-private peer trust helper of ProxyTrustSetting. */
public class ProxyTrustSettingPeerTest {

    /**
     * "isFromTrustedProxy remoteAddress isSecure" of an app configured by {@code trust} over the non-secure socket peer
     * {@code peer}, for a request that carries X-Forwarded-For 203.0.113.7 and X-Forwarded-Proto https.
     */
    private static String fromRequest(String peer, Configurer<RestApp> trust) {
        TestApp t = TestApp.create(new InMemoryHttpAdapter(peer, false));
        trust.configure(t.app());
        t.app().get("/t", (req, res) -> res.send(req.isFromTrustedProxy() + " " + req.remoteAddress() + " "
                + req.isSecure()));
        return t.send("GET", "/t", "X-Forwarded-For", "203.0.113.7", "X-Forwarded-Proto", "https").text();
    }

    /**
     * The helper gives {@code expected}, and the request reports the same answer through isFromTrustedProxy() and
     * through the forwarding decision of remoteAddress() and isSecure(): a trusted peer follows both headers, an
     * untrusted one follows neither.
     */
    private static void same(boolean expected, ProxyTrustSetting setting, String peer, Configurer<RestApp> trust) {
        assertEquals(setting + " " + peer, expected, setting.trustsPeer(peer));
        String forwarded = expected ? "true 203.0.113.7 true" : "false " + peer + " false";
        assertEquals("request " + peer, forwarded, fromRequest(peer, trust));
    }

    // FR-HTTP-034 AC-5: the helper over the settings of AC-1 to AC-4 gives the answers of isFromTrustedProxy()
    @Test
    public void FR_HTTP_034_AC5_helperGivesTheSameAnswersAsIsFromTrustedProxy() {
        // AC-1 settings
        same(false, ProxyTrustSetting.NONE, "10.0.0.1", a -> { });
        same(false, ProxyTrustSetting.legacy(false), "10.0.0.1", a -> a.trustProxy(false));
        same(false, ProxyTrustSetting.hops(0), "10.0.0.1", a -> a.trustProxyHops(0));
        same(false, ProxyTrustSetting.cidrs(), "10.0.0.1", a -> a.trustProxyCidrs(new String[0]));
        // AC-2 settings
        same(true, ProxyTrustSetting.hops(1), "10.0.0.1", a -> a.trustProxyHops(1));
        same(true, ProxyTrustSetting.hops(3), "10.0.0.1", a -> a.trustProxyHops(3));
        same(true, ProxyTrustSetting.legacy(true), "10.0.0.1", a -> a.trustProxy(true));
        // AC-3 settings
        ProxyTrustSetting cidr = ProxyTrustSetting.cidrs("10.0.0.0/8");
        same(true, cidr, "10.1.2.3", a -> a.trustProxyCidrs("10.0.0.0/8"));
        same(false, cidr, "192.168.0.1", a -> a.trustProxyCidrs("10.0.0.0/8"));
        // AC-4 settings
        ProxyTrustSetting all = ProxyTrustSetting.cidrs("10.0.0.0/8", "0.0.0.0/0", "::/0");
        Configurer<RestApp> allApp = a -> a.trustProxyCidrs("10.0.0.0/8", "0.0.0.0/0", "::/0");
        same(false, all, "", allApp);
        same(false, all, "unknown", allApp);
        same(true, all, "::1", allApp);
    }

    // FR-HTTP-034 AC-5: isFromTrustedProxy() and the remoteAddress()/isSecure() forwarding decision call that helper,
    // and ProxyTrustSetting keeps no second peer trust predicate
    @Test
    public void FR_HTTP_034_AC5_requestAndForwardingDecisionCallTheOneHelper() throws IOException {
        String setting = source("ProxyTrustSetting.java");
        assertTrue(setting, setting.contains("    boolean trustsPeer(String peer) {"));
        assertFalse("second peer trust predicate", setting.contains("peerTrusted"));
        assertTrue("remoteAddress", body(setting, "String remoteAddress(String peer,").contains("trustsPeer(peer)"));
        assertTrue("isSecure", body(setting, "boolean isSecure(String peer,").contains("trustsPeer(peer)"));
        String request = source("DefaultRequest.java");
        assertTrue("isFromTrustedProxy",
                body(request, "public boolean isFromTrustedProxy()").contains(".trustsPeer(ex.remoteAddress())"));
    }

    private static String source(String name) throws IOException {
        File f = new File(System.getProperty("user.dir"), "src/main/java/com/snoworca/naruserve/internal/" + name);
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    /** The text of the method whose declaration starts with {@code declaration}, up to its closing brace line. */
    private static String body(String source, String declaration) {
        int start = source.indexOf(declaration);
        assertTrue(declaration, start >= 0);
        int end = source.indexOf("\n    }\n", start);
        assertTrue(declaration, end > start);
        return source.substring(start, end);
    }
}
