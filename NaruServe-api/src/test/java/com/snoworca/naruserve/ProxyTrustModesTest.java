package com.snoworca.naruserve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.InMemoryWebSocketChannel;
import com.snoworca.naruserve.testing.TestApp;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/** C6b: app.trustProxyHops(int), app.trustProxyCidrs(String...), the read accessors and the XFF/XFP rules. */
public class ProxyTrustModesTest {

    /** An app over peer {@code peer} answering {@code /who} with "remoteAddress isSecure". */
    private static TestApp app(String peer, boolean secure, Configurer<RestApp> trust) {
        TestApp t = TestApp.create(new InMemoryHttpAdapter(peer, secure));
        trust.configure(t.app());
        t.app().get("/who", (req, res) -> res.send(req.remoteAddress() + " " + req.isSecure()));
        return t;
    }

    private static String who(TestApp t, String... headers) {
        return t.send("GET", "/who", headers).text();
    }

    private static String hops(int n, String... headers) {
        return who(app("10.0.0.1", false, a -> a.trustProxyHops(n)), headers);
    }

    private static String cidrs(String peer, String[] cidrs, String... headers) {
        return who(app(peer, false, a -> a.trustProxyCidrs(cidrs)), headers);
    }

    private static String[] c(String... cidrs) {
        return cidrs;
    }

    // IR-ENGINE-003 AC-1: both are RestApp methods under the NEW-state guard
    @Test
    public void IR_ENGINE_003_AC1_bothMethodsReturnTheAppAndAreRefusedAfterListen() throws Exception {
        assertEquals(RestApp.class, RestApp.class.getMethod("trustProxyHops", int.class).getReturnType());
        assertEquals(RestApp.class, RestApp.class.getMethod("trustProxyCidrs", String[].class).getReturnType());
        assertTrue(RestApp.class.getMethod("trustProxyCidrs", String[].class).isVarArgs());
        TestApp t = TestApp.create();
        assertSame(t.app(), t.app().trustProxyHops(1));
        assertSame(t.app(), t.app().trustProxyCidrs("10.0.0.0/8"));
        t.start();
        try {
            t.app().trustProxyHops(2);
            fail("trustProxyHops accepted in RUNNING");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("trustProxyHops") && e.getMessage().contains("RUNNING"));
        }
        try {
            t.app().trustProxyCidrs("192.168.0.0/16");
            fail("trustProxyCidrs accepted in RUNNING");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("trustProxyCidrs") && e.getMessage().contains("RUNNING"));
        }
        assertEquals(Collections.singletonList("10.0.0.0/8"), t.app().trustProxyCidrs());
        t.app().shutdown(1, TimeUnit.SECONDS);
    }

    // IR-ENGINE-003 AC-2: a negative hop count is refused; 0 keeps the adapter values
    @Test
    public void IR_ENGINE_003_AC2_negativeHopsAreRefusedAndZeroTrustsNothing() {
        try {
            TestApp.create().app().trustProxyHops(-1);
            fail("trustProxyHops(-1) accepted");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("-1"));
        }
        assertEquals("10.0.0.1 false", hops(0, "X-Forwarded-For", "203.0.113.7", "X-Forwarded-Proto", "https"));
    }

    // IR-ENGINE-003 AC-3: null and malformed CIDRs are refused before any network call, valid ones are accepted
    @Test
    public void IR_ENGINE_003_AC3_cidrValidation() {
        RestApp app = TestApp.create().app();
        try {
            app.trustProxyCidrs((String[]) null);
            fail("null array accepted");
        } catch (NullPointerException expected) {
            // NPE
        }
        try {
            app.trustProxyCidrs("10.0.0.0/8", null);
            fail("null element accepted");
        } catch (NullPointerException expected) {
            // NPE
        }
        for (String bad : new String[] {"10.0.0.0/33", "fd00::/129", "abc/8", "10.0.0/8", "300.0.0.1/8", "10.0.0.0/-1",
                "10.0.0.0/", "fd00:::1/8", "localhost"}) {
            try {
                app.trustProxyCidrs(bad);
                fail(bad + " accepted");
            } catch (IllegalArgumentException e) {
                assertTrue(e.getMessage(), e.getMessage().contains(bad));
            }
        }
        assertEquals(Collections.emptyList(), app.trustProxyCidrs());
        app.trustProxyCidrs("10.0.0.0/8", "192.168.1.0/24", "fd00::/8");
        assertEquals(Arrays.asList("10.0.0.0/8", "192.168.1.0/24", "fd00::/8"), app.trustProxyCidrs());
    }

    // IR-ENGINE-003 AC-4: the accessors are non-null and report the current setting
    @Test
    public void IR_ENGINE_003_AC4_accessorsReportTheSetting() throws Exception {
        assertEquals(boolean.class, RestApp.class.getMethod("trustProxy").getReturnType());
        assertEquals(int.class, RestApp.class.getMethod("trustProxyHops").getReturnType());
        assertEquals(List.class, RestApp.class.getMethod("trustProxyCidrs").getReturnType());
        RestApp fresh = TestApp.create().app();
        assertFalse(fresh.trustProxy());
        assertEquals(0, fresh.trustProxyHops());
        assertEquals(Collections.emptyList(), fresh.trustProxyCidrs());
        RestApp legacy = TestApp.create().app().trustProxy(true);
        assertTrue(legacy.trustProxy());
        RestApp two = TestApp.create().app().trustProxyHops(2);
        assertEquals(2, two.trustProxyHops());
        TestApp t = TestApp.create();
        t.app().trustProxyCidrs("10.0.0.0/8");
        List<String> list = t.app().trustProxyCidrs();
        assertEquals(Collections.singletonList("10.0.0.0/8"), list);
        try {
            list.add("192.168.0.0/16");
            fail("CIDR list is modifiable");
        } catch (UnsupportedOperationException expected) {
            // read-only
        }
        t.start();
        assertFalse(t.app().trustProxy());
        assertEquals(0, t.app().trustProxyHops());
        assertEquals(Collections.singletonList("10.0.0.0/8"), t.app().trustProxyCidrs());
        t.app().shutdown(1, TimeUnit.SECONDS);
    }

    // IR-ENGINE-003: decision — the last of trustProxy, trustProxyHops and trustProxyCidrs replaces the setting
    @Test
    public void IR_ENGINE_003_AC4_theLastTrustCallWins() {
        RestApp app = TestApp.create().app();
        app.trustProxy(true).trustProxyHops(2);
        assertFalse(app.trustProxy());
        assertEquals(2, app.trustProxyHops());
        app.trustProxyCidrs("10.0.0.0/8");
        assertEquals(0, app.trustProxyHops());
        assertEquals(Collections.singletonList("10.0.0.0/8"), app.trustProxyCidrs());
        app.trustProxy(true);
        assertTrue(app.trustProxy());
        assertEquals(Collections.emptyList(), app.trustProxyCidrs());
        app.trustProxy(false);
        assertFalse(app.trustProxy());
    }

    // SEC-ENGINE-001 AC-1: the nth entry from the right
    @Test
    public void SEC_ENGINE_001_AC1_hopModeCountsFromTheRight() {
        assertEquals("203.0.113.7 false", hops(1, "X-Forwarded-For", "198.51.100.9, 203.0.113.7"));
        assertEquals("198.51.100.9 false", hops(2, "X-Forwarded-For", "198.51.100.9, 203.0.113.7"));
    }

    // SEC-ENGINE-001 AC-2: a client-supplied left entry is not the client address in hop mode, but is under legacy
    @Test
    public void SEC_ENGINE_001_AC2_spoofedLeftEntryIsDefeated() {
        assertEquals("203.0.113.7 false", hops(1, "X-Forwarded-For", "1.2.3.4, 203.0.113.7"));
        assertEquals("1.2.3.4 false", who(app("10.0.0.1", false, a -> a.trustProxy(true)),
                "X-Forwarded-For", "1.2.3.4, 203.0.113.7"));
    }

    // SEC-ENGINE-001 AC-3: several header lines combine in arrival order
    @Test
    public void SEC_ENGINE_001_AC3_headerLinesCombineInArrivalOrder() {
        assertEquals("203.0.113.7 false", hops(1, "X-Forwarded-For", "198.51.100.9", "X-Forwarded-For", "203.0.113.7"));
        assertEquals("198.51.100.9 false", hops(2, "X-Forwarded-For", "198.51.100.9", "X-Forwarded-For", "203.0.113.7"));
    }

    // SEC-ENGINE-001 AC-4: no X-Forwarded-For keeps the socket peer
    @Test
    public void SEC_ENGINE_001_AC4_noForwardedForKeepsThePeer() {
        assertEquals("10.0.0.1 false", hops(1));
    }

    // SEC-ENGINE-001 AC-5: Forwarded is never read in hop mode
    @Test
    public void SEC_ENGINE_001_AC5_forwardedIsNotRead() {
        assertEquals("10.0.0.1 false", hops(1, "Forwarded", "for=198.51.100.1;proto=https"));
    }

    // SEC-ENGINE-001: decision — fewer entries than hops gives the left-most entry, as Express trust proxy = n
    @Test
    public void SEC_ENGINE_001_fewerEntriesThanHopsGiveTheLeftMostEntry() {
        assertEquals("198.51.100.9 false", hops(3, "X-Forwarded-For", "198.51.100.9, 203.0.113.7"));
        assertEquals("203.0.113.7 false", hops(1, "X-Forwarded-For", "203.0.113.7, , "));
    }

    // SEC-ENGINE-002 AC-1: trusted entries are skipped from the right
    @Test
    public void SEC_ENGINE_002_AC1_trustedEntriesAreSkipped() {
        assertEquals("203.0.113.7 false", cidrs("10.0.0.1", c("10.0.0.0/8"), "X-Forwarded-For", "203.0.113.7, 10.0.0.2"));
    }

    // SEC-ENGINE-002 AC-2: an untrusted peer's forwarding headers are ignored
    @Test
    public void SEC_ENGINE_002_AC2_untrustedPeerIgnoresForwardingHeaders() {
        assertEquals("198.51.100.20 false", cidrs("198.51.100.20", c("10.0.0.0/8"), "X-Forwarded-For", "203.0.113.7",
                "X-Forwarded-Proto", "https"));
        assertEquals("100.0.0.1 false", cidrs("100.0.0.1", c("10.0.0.0/8"), "X-Forwarded-For", "203.0.113.7"));
    }

    // SEC-ENGINE-002 AC-3: the client-supplied left entry loses
    @Test
    public void SEC_ENGINE_002_AC3_spoofedLeftEntryIsDefeated() {
        assertEquals("203.0.113.7 false", cidrs("10.0.0.1", c("10.0.0.0/8"), "X-Forwarded-For", "1.2.3.4, 203.0.113.7"));
    }

    // SEC-ENGINE-002 AC-4: IPv6 CIDRs and several CIDRs
    @Test
    public void SEC_ENGINE_002_AC4_ipv6AndSeveralCidrs() {
        assertEquals("2001:db8::5 false", cidrs("fd00::1", c("fd00::/8"), "X-Forwarded-For", "2001:db8::5"));
        String[] both = c("10.0.0.0/8", "fd00::/8");
        assertEquals("2001:db8::5 false", cidrs("fd00::1", both, "X-Forwarded-For", "2001:db8::5"));
        assertEquals("203.0.113.7 false", cidrs("10.0.0.1", both, "X-Forwarded-For", "203.0.113.7, fd00::9, 10.0.0.2"));
        assertEquals("198.51.100.20 false", cidrs("198.51.100.20", both, "X-Forwarded-For", "203.0.113.7"));
    }

    // SEC-ENGINE-002 AC-5: header lines combine before the scan
    @Test
    public void SEC_ENGINE_002_AC5_headerLinesCombine() {
        assertEquals("203.0.113.7 false", cidrs("10.0.0.1", c("10.0.0.0/8"),
                "X-Forwarded-For", "1.2.3.4, 203.0.113.7", "X-Forwarded-For", "10.0.0.2"));
    }

    // SEC-ENGINE-002 AC-7: a trusted peer without X-Forwarded-For keeps the peer; Forwarded is not read
    @Test
    public void SEC_ENGINE_002_AC7_trustedPeerWithoutForwardedForKeepsThePeer() {
        assertEquals("10.0.0.1 false", cidrs("10.0.0.1", c("10.0.0.0/8")));
        assertEquals("10.0.0.1 false", cidrs("10.0.0.1", c("10.0.0.0/8"), "Forwarded", "for=198.51.100.1;proto=https"));
    }

    // SEC-ENGINE-002: decisions — every entry trusted gives the left-most, a bare address is a one-host CIDR and an
    // IPv4-mapped IPv6 peer matches an IPv4 CIDR
    @Test
    public void SEC_ENGINE_002_documentedEdgeDecisions() {
        assertEquals("10.0.0.3 false", cidrs("10.0.0.1", c("10.0.0.0/8"), "X-Forwarded-For", "10.0.0.3, 10.0.0.2"));
        assertEquals("203.0.113.7 false", cidrs("10.0.0.1", c("10.0.0.1"), "X-Forwarded-For", "203.0.113.7"));
        assertEquals("10.0.0.2 false", cidrs("10.0.0.2", c("10.0.0.1"), "X-Forwarded-For", "203.0.113.7"));
        assertEquals("203.0.113.7 false", cidrs("::ffff:10.0.0.1", c("10.0.0.0/8"), "X-Forwarded-For", "203.0.113.7"));
        assertEquals("not-an-ip false", cidrs("10.0.0.1", c("10.0.0.0/8"), "X-Forwarded-For", "not-an-ip, 10.0.0.2"));
    }

    // SEC-ENGINE-003 AC-1: a single X-Forwarded-Proto value is used as is
    @Test
    public void SEC_ENGINE_003_AC1_singleValueIsUsedAsIs() {
        assertEquals("10.0.0.1 true", hops(1, "X-Forwarded-Proto", "https"));
        assertEquals("10.0.0.1 false", hops(1, "X-Forwarded-Proto", "http"));
        assertEquals("198.51.100.9 true", hops(2, "X-Forwarded-For", "198.51.100.9, 203.0.113.7",
                "X-Forwarded-Proto", "https"));
        assertEquals("10.0.0.1 true", hops(1, "X-Forwarded-Proto", "HTTPS"));
        assertEquals("10.0.0.1 false", who(app("10.0.0.1", true, a -> a.trustProxyHops(1)), "X-Forwarded-Proto", "http"));
    }

    // SEC-ENGINE-003 AC-1: decision — the position rule applies to two or more values only, so one value is used
    // as is even when hop mode counts past the X-Forwarded-For list; https compares case-insensitively and a trusted
    // value replaces the adapter's TLS flag in both directions
    @Test
    public void SEC_ENGINE_003_AC1_oneValueIsUsedAsIsWhateverTheHopCount() {
        assertEquals("203.0.113.7 true", hops(2, "X-Forwarded-For", "203.0.113.7", "X-Forwarded-Proto", "https"));
        assertEquals("10.0.0.1 true", hops(3, "X-Forwarded-Proto", "https"));
    }

    // SEC-ENGINE-003 AC-2: hop-mode list by position from the right
    @Test
    public void SEC_ENGINE_003_AC2_hopModeListByPosition() {
        assertEquals("198.51.100.9 true", hops(2, "X-Forwarded-For", "198.51.100.9, 203.0.113.7",
                "X-Forwarded-Proto", "https, http"));
        assertEquals("203.0.113.7 false", hops(1, "X-Forwarded-For", "198.51.100.9, 203.0.113.7",
                "X-Forwarded-Proto", "https, http"));
        assertEquals("198.51.100.9 true", hops(2, "X-Forwarded-For", "198.51.100.9, 203.0.113.7",
                "X-Forwarded-Proto", "https", "X-Forwarded-Proto", "http"));
    }

    // SEC-ENGINE-003 AC-3: CIDR-mode list by the position of the first untrusted X-Forwarded-For entry
    @Test
    public void SEC_ENGINE_003_AC3_cidrModeListByPosition() {
        assertEquals("203.0.113.7 true", cidrs("10.0.0.1", c("10.0.0.0/8"), "X-Forwarded-For", "203.0.113.7, 10.0.0.2",
                "X-Forwarded-Proto", "https, http"));
    }

    // SEC-ENGINE-003 AC-4: two or more values with a different count than X-Forwarded-For fail closed
    @Test
    public void SEC_ENGINE_003_AC4_countMismatchFailsClosed() {
        assertEquals("10.0.0.2 false", hops(1, "X-Forwarded-For", "198.51.100.9, 203.0.113.7, 10.0.0.2",
                "X-Forwarded-Proto", "https, https"));
        assertEquals("10.0.0.1 false", hops(1, "X-Forwarded-Proto", "https, https"));
        assertEquals("203.0.113.7 false", cidrs("10.0.0.1", c("10.0.0.0/8"), "X-Forwarded-For", "203.0.113.7",
                "X-Forwarded-Proto", "https, https"));
    }

    // SEC-ENGINE-003 AC-5: a missing position fails closed
    @Test
    public void SEC_ENGINE_003_AC5_missingPositionFailsClosed() {
        assertEquals("198.51.100.9 false", hops(3, "X-Forwarded-For", "198.51.100.9, 203.0.113.7",
                "X-Forwarded-Proto", "https, https"));
    }

    // SEC-ENGINE-003 AC-6: an untrusted peer and a trusted peer without the header keep the adapter value
    @Test
    public void SEC_ENGINE_003_AC6_untrustedPeerAndAbsentHeaderKeepTheAdapterValue() {
        assertEquals("198.51.100.20 false", cidrs("198.51.100.20", c("10.0.0.0/8"), "X-Forwarded-Proto", "https"));
        assertEquals("10.0.0.1 false", hops(0, "X-Forwarded-Proto", "https"));
        assertEquals("10.0.0.1 false", hops(1));
        assertEquals("10.0.0.1 true", who(app("10.0.0.1", true, a -> a.trustProxyHops(1))));
        assertEquals("10.0.0.1 false", cidrs("10.0.0.1", c("10.0.0.0/8")));
    }

    // SEC-ENGINE-003 AC-8: a WebSocket upgrade uses the same trust setting before 101 as an ordinary request
    @Test(timeout = 20000)
    public void SEC_ENGINE_003_AC8_webSocketUpgradeUsesTheSameTrustSetting() throws Exception {
        List<Configurer<RestApp>> settings = Arrays.<Configurer<RestApp>>asList(a -> a.trustProxyHops(1),
                a -> a.trustProxyCidrs("10.0.0.0/8"));
        for (Configurer<RestApp> setting : settings) {
            InMemoryHttpAdapter adapter = new InMemoryHttpAdapter("10.0.0.1", false);
            TestApp t = TestApp.create(adapter);
            setting.configure(t.app());
            List<String> seen = Collections.synchronizedList(new ArrayList<String>());
            t.app().use((req, res, next) -> {
                seen.add(req.remoteAddress() + " " + req.isSecure());
                next.run();
            });
            t.app().ws("/ws", (req, ws) -> { });
            t.app().get("/who", (req, res) -> res.send(req.remoteAddress() + " " + req.isSecure()));
            t.start();
            Future<ClientResponse> f = adapter.submit(ClientRequest.upgrade("/ws", "X-Forwarded-For", "203.0.113.7",
                    "X-Forwarded-Proto", "https", "Origin", "https://localhost", "Host", "localhost"));
            InMemoryWebSocketChannel ch = adapter.nextChannel(5, TimeUnit.SECONDS);
            ch.clientClose(1000, "");
            assertEquals(101, f.get(5, TimeUnit.SECONDS).status());
            assertEquals("203.0.113.7 true", seen.get(0));
            String plain = adapter.send(ClientRequest.of("GET", "/who").header("X-Forwarded-For", "203.0.113.7")
                    .header("X-Forwarded-Proto", "https")).text();
            assertEquals(seen.get(0), plain);
            t.app().shutdown(1, TimeUnit.SECONDS);
        }
    }

    private static String restAppSource() throws IOException {
        File f = new File(System.getProperty("user.dir"), "src/main/java/com/snoworca/naruserve/RestApp.java");
        return new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    // CON-ENGINE-001 AC-2: the Javadoc of trustProxy(boolean) states the risk and names both replacements
    @Test
    public void CON_ENGINE_001_AC2_trustProxyJavadocNamesTheRiskAndTheReplacements() throws IOException {
        String src = restAppSource();
        int method = src.indexOf("RestApp trustProxy(boolean trust);");
        assertTrue("trustProxy(boolean) declaration", method > 0);
        int doc = src.lastIndexOf("/**", method);
        String javadoc = src.substring(doc, src.indexOf("*/", doc));
        assertTrue(javadoc, javadoc.contains("trustProxyHops"));
        assertTrue(javadoc, javadoc.contains("trustProxyCidrs"));
        assertTrue(javadoc, javadoc.contains("client"));
        assertTrue(javadoc, javadoc.contains("left-most"));
    }

    // CON-ENGINE-001 AC-4: trustProxy(boolean) keeps its signature, guard and is not deprecated
    @Test
    public void CON_ENGINE_001_AC4_trustProxyKeepsItsSignatureAndIsNotDeprecated() throws Exception {
        java.lang.reflect.Method m = RestApp.class.getMethod("trustProxy", boolean.class);
        assertEquals(RestApp.class, m.getReturnType());
        assertNull(m.getAnnotation(Deprecated.class));
        TestApp t = TestApp.create();
        t.start();
        try {
            t.app().trustProxy(true);
            fail("trustProxy accepted in RUNNING");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("trustProxy"));
        }
        t.app().shutdown(1, TimeUnit.SECONDS);
    }
}
