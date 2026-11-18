package com.snoworca.naruserve.proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.testing.StubJsonCodec;
import com.snoworca.naruserve.testing.TestApp;
import com.snoworca.naruserve.tinyhttpd.TinyHttpdAdapter;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.After;
import org.junit.Test;

/**
 * The forwarding headers the proxy writes to the backend (SEC-PROXY-002): {@code X-Forwarded-For} as the socket
 * address, after the client's values only for a peer {@code req.isFromTrustedProxy()} trusts, written without brackets
 * or a zone and left out when the socket address is not an IP literal (design D-055); {@code X-Forwarded-Proto} from
 * {@code req.isSecure()}; {@code X-Forwarded-Host} from the received {@code Host}; and the client's {@code Forwarded},
 * {@code X-Real-IP}, {@code X-Forwarded-Port} and {@code X-Forwarded-Prefix} dropped under every trust setting. The
 * three lines take the forwarding slot of the backend header block (FR-PROXY-003 AC-8, design D-053). The in-memory
 * adapter reports the address it was built with as the socket address, so a test picks the peer it needs.
 */
public class ProxyForwardedHeadersTest {

    /** No trust setting: the default of a new app. */
    private static final Consumer<RestApp> NO_TRUST = a -> { };
    private static final Consumer<RestApp> CIDR_10 = a -> a.trustProxyCidrs("10.0.0.0/8");
    private static final Consumer<RestApp> ONE_HOP = a -> a.trustProxyHops(1);
    private static final Consumer<RestApp> LEGACY = a -> a.trustProxy(true);

    private final List<Closeable> closeables = new ArrayList<>();
    private final List<RestApp> started = new ArrayList<>();

    @After
    public void tearDown() throws IOException {
        for (RestApp a : started) {
            a.shutdown(2, TimeUnit.SECONDS);
        }
        for (Closeable c : closeables) {
            c.close();
        }
    }

    private RawBackend okBackend() throws IOException {
        RawBackend b = RawBackend.answering(RawBackend.response(200, "ok"));
        closeables.add(b);
        return b;
    }

    /** {@code method /api/x} with the given header name/value pairs, in order. */
    private static ClientRequest request(String method, String... headerPairs) {
        return requestTo(method, "/api/x", headerPairs);
    }

    private static ClientRequest requestTo(String method, String target, String... headerPairs) {
        ClientRequest r = ClientRequest.of(method, target);
        for (int i = 0; i < headerPairs.length; i += 2) {
            r.header(headerPairs[i], headerPairs[i + 1]);
        }
        return r;
    }

    /**
     * Sends {@code request} from the peer {@code socketAddress} (over TLS when {@code secure}) to an in-memory app
     * with {@code trust} applied and a proxy to {@code backend} at /api; asserts 200 ok and returns what the backend
     * received.
     */
    private RawBackend.Received forward(String socketAddress, boolean secure, Consumer<RestApp> trust,
            RawBackend backend, ClientRequest request) {
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter(socketAddress, secure);
        TestApp t = TestApp.create(adapter);
        RestApp app = t.app();
        trust.accept(app);
        app.use("/api", Proxy.to(backend.url()));
        t.start();
        started.add(app);

        ClientResponse r = adapter.send(request);

        assertEquals("status", 200, r.status());
        assertEquals("ok", r.text());
        return backend.onlyRequest();
    }

    private RawBackend.Received forward(String socketAddress, Consumer<RestApp> trust, ClientRequest request)
            throws IOException {
        return forward(socketAddress, false, trust, okBackend(), request);
    }

    private static List<String> values(RawBackend.Received got, String name) {
        return got.headerValues(name);
    }

    private static List<String> one(String value) {
        return Collections.singletonList(value);
    }

    private static final List<String> NONE = Collections.emptyList();

    /** The header lines of {@code got} whose name is one of {@code names}, case-insensitively. */
    private static List<String> linesNamed(RawBackend.Received got, String... names) {
        List<String> out = new ArrayList<>();
        for (String line : got.headerLines()) {
            int colon = line.indexOf(':');
            for (String n : names) {
                if (colon > 0 && line.substring(0, colon).equalsIgnoreCase(n)) {
                    out.add(line);
                }
            }
        }
        return out;
    }

    // ---- AC-1 ----

    // SEC-PROXY-002 AC-1: with no trust setting a plain-HTTP client at 127.0.0.1 sending X-Forwarded-For: 1.2.3.4,
    // X-Forwarded-Proto: https, X-Forwarded-Host: evil.example and Host: gw.example makes the backend receive exactly
    // X-Forwarded-For: 127.0.0.1, X-Forwarded-Proto: http and X-Forwarded-Host: gw.example, one line each
    @Test
    public void sec_PROXY_002_AC1_untrustedClientValuesAreReplaced() throws IOException {
        RawBackend.Received got = forward("127.0.0.1", NO_TRUST, request("GET", "X-Forwarded-For", "1.2.3.4",
                "X-Forwarded-Proto", "https", "X-Forwarded-Host", "evil.example", "Host", "gw.example"));

        assertEquals(got.head, Arrays.asList("X-Forwarded-For: 127.0.0.1", "X-Forwarded-Proto: http",
                "X-Forwarded-Host: gw.example"),
                linesNamed(got, "X-Forwarded-For", "X-Forwarded-Proto", "X-Forwarded-Host"));
    }

    // SEC-PROXY-002 AC-1: the same over a real TinyHttpd socket, whose socket address is 127.0.0.1
    @Test(timeout = 30000)
    public void sec_PROXY_002_AC1_overATinyHttpdSocket() throws IOException {
        RawBackend backend = okBackend();
        int port = tinyHttpdProxy(Proxy.to(backend.url()), "gw.example");

        String response = exchange(port, "GET /api/x HTTP/1.1\r\nX-Forwarded-For: 1.2.3.4\r\n"
                + "X-Forwarded-Proto: https\r\nX-Forwarded-Host: evil.example\r\nHost: gw.example\r\n"
                + "Connection: close\r\n\r\n");

        assertTrue(response, response.startsWith("HTTP/1.1 200 "));
        RawBackend.Received got = backend.onlyRequest();
        assertEquals(got.head, Arrays.asList("X-Forwarded-For: 127.0.0.1", "X-Forwarded-Proto: http",
                "X-Forwarded-Host: gw.example"),
                linesNamed(got, "X-Forwarded-For", "X-Forwarded-Proto", "X-Forwarded-Host"));
    }

    // SEC-PROXY-002 AC-1: the forwarding lines are written even when the client sent none of them, and a TLS
    // connection with no trust setting gives X-Forwarded-Proto: https (req.isSecure() of the connection)
    @Test
    public void sec_PROXY_002_AC1_linesAreWrittenWhenTheClientSentNone() throws IOException {
        RawBackend.Received plain = forward("127.0.0.1", NO_TRUST, request("GET", "Host", "gw.example"));
        RawBackend.Received tls = forward("127.0.0.1", true, NO_TRUST, okBackend(),
                request("GET", "Host", "gw.example", "X-Forwarded-Proto", "http"));

        assertEquals(plain.head, one("127.0.0.1"), values(plain, "X-Forwarded-For"));
        assertEquals(plain.head, one("http"), values(plain, "X-Forwarded-Proto"));
        assertEquals(tls.head, one("https"), values(tls, "X-Forwarded-Proto"));
    }

    // ---- AC-2 ----

    // SEC-PROXY-002 AC-2: an IPv6 loopback peer reported plain, in brackets, with a zone id, or both gives an
    // X-Forwarded-For value without '[', ']' or '%': the address as reported, brackets and zone removed, not normalised
    @Test
    public void sec_PROXY_002_AC2_ipv6LoopbackIsWrittenWithoutBracketsOrZone() throws IOException {
        String[][] cases = {
            {"::1", "::1"},
            {"[::1]", "::1"},
            {"::1%lo0", "::1"},
            {"[::1%lo0]", "::1"},
            {"0:0:0:0:0:0:0:1", "0:0:0:0:0:0:0:1"},
            {"[0:0:0:0:0:0:0:1%1]", "0:0:0:0:0:0:0:1"},
            {"[fe80::1%en0]", "fe80::1"},
            {"::ffff:127.0.0.1", "::ffff:127.0.0.1"},
        };
        for (String[] c : cases) {
            RawBackend.Received got = forward(c[0], NO_TRUST, request("GET", "Host", "gw.example"));

            List<String> xff = values(got, "X-Forwarded-For");
            assertEquals(c[0] + " -> " + got.head, one(c[1]), xff);
            assertFalse(c[0], xff.get(0).contains("[") || xff.get(0).contains("]") || xff.get(0).contains("%"));
        }
    }

    // SEC-PROXY-002 AC-2: a trusted IPv6 peer (legacy trust) reported in brackets with a zone is appended without
    // them after the client value
    @Test
    public void sec_PROXY_002_AC2_trustedIpv6PeerIsAppendedWithoutBracketsOrZone() throws IOException {
        RawBackend.Received got = forward("[::1%lo0]", LEGACY,
                request("GET", "Host", "gw.example", "X-Forwarded-For", "1.2.3.4"));

        assertEquals(got.head, one("1.2.3.4, ::1"), values(got, "X-Forwarded-For"));
    }

    // ---- AC-3 ----

    // SEC-PROXY-002 AC-3: under trustProxyCidrs("10.0.0.0/8") a client outside the CIDR sending X-Forwarded-For:
    // 1.2.3.4 makes the backend receive only its socket address
    @Test
    public void sec_PROXY_002_AC3_clientOutsideTheCidrGetsOnlyItsSocketAddress() throws IOException {
        RawBackend.Received got = forward("192.168.1.9", CIDR_10,
                request("GET", "Host", "gw.example", "X-Forwarded-For", "1.2.3.4"));

        assertEquals(got.head, one("192.168.1.9"), values(got, "X-Forwarded-For"));
    }

    // SEC-PROXY-002 AC-3: the untrusted peer's two X-Forwarded-For lines are both dropped, whatever their name letters
    @Test
    public void sec_PROXY_002_AC3_everyClientLineIsDroppedForAnUntrustedPeer() throws IOException {
        RawBackend.Received got = forward("127.0.0.1", CIDR_10, request("GET", "Host", "gw.example",
                "X-Forwarded-For", "1.2.3.4", "x-forwarded-for", "5.6.7.8"));

        assertEquals(got.head, Collections.singletonList("X-Forwarded-For: 127.0.0.1"),
                linesNamed(got, "X-Forwarded-For"));
    }

    // ---- AC-4 ----

    // SEC-PROXY-002 AC-4: a client inside the CIDR sending X-Forwarded-For: 1.2.3.4 makes the backend receive
    // X-Forwarded-For: 1.2.3.4, <socket address>
    @Test
    public void sec_PROXY_002_AC4_trustedPeerAppendsItsSocketAddress() throws IOException {
        RawBackend.Received got = forward("10.1.2.3", CIDR_10,
                request("GET", "Host", "gw.example", "X-Forwarded-For", "1.2.3.4"));

        assertEquals(got.head, Collections.singletonList("X-Forwarded-For: 1.2.3.4, 10.1.2.3"),
                linesNamed(got, "X-Forwarded-For"));
    }

    // SEC-PROXY-002 AC-4: two lines 1.2.3.4 and 5.6.7.8 become the single line 1.2.3.4, 5.6.7.8, <socket address>;
    // the name matches in any letter case
    @Test
    public void sec_PROXY_002_AC4_twoClientLinesJoinIntoOneLine() throws IOException {
        RawBackend.Received got = forward("10.1.2.3", CIDR_10, request("GET", "Host", "gw.example",
                "X-Forwarded-For", "1.2.3.4", "X-A", "1", "x-forwarded-for", "5.6.7.8"));

        assertEquals(got.head, Collections.singletonList("X-Forwarded-For: 1.2.3.4, 5.6.7.8, 10.1.2.3"),
                linesNamed(got, "X-Forwarded-For"));
    }

    // SEC-PROXY-002 AC-4: a client line keeps its own list as sent, OWS around a line is trimmed, and a line that is
    // empty once trimmed adds nothing (no empty list element)
    @Test
    public void sec_PROXY_002_AC4_clientLinesAreTrimmedAndEmptyOnesSkipped() throws IOException {
        RawBackend.Received got = forward("10.1.2.3", CIDR_10, request("GET", "Host", "gw.example",
                "X-Forwarded-For", " 1.2.3.4,9.9.9.9 ", "X-Forwarded-For", "", "X-Forwarded-For", " \t",
                "X-Forwarded-For", "5.6.7.8"));

        assertEquals(got.head, one("1.2.3.4,9.9.9.9, 5.6.7.8, 10.1.2.3"), values(got, "X-Forwarded-For"));
    }

    // SEC-PROXY-002 AC-4: a trusted peer whose client sent no X-Forwarded-For sends its socket address alone
    @Test
    public void sec_PROXY_002_AC4_trustedPeerWithoutClientValuesSendsItsAddress() throws IOException {
        RawBackend.Received got = forward("10.1.2.3", CIDR_10, request("GET", "Host", "gw.example"));

        assertEquals(got.head, one("10.1.2.3"), values(got, "X-Forwarded-For"));
    }

    // ---- AC-5 ----

    // SEC-PROXY-002 AC-5: a client inside the CIDR connected over plain HTTP sending X-Forwarded-Proto: https makes
    // the backend receive X-Forwarded-Proto: https, the value of req.isSecure()
    @Test
    public void sec_PROXY_002_AC5_trustedPeerProtoFollowsIsSecure() throws IOException {
        RawBackend.Received got = forward("10.1.2.3", CIDR_10,
                request("GET", "Host", "gw.example", "X-Forwarded-Proto", "https"));

        assertEquals(got.head, Collections.singletonList("X-Forwarded-Proto: https"),
                linesNamed(got, "X-Forwarded-Proto"));
    }

    // SEC-PROXY-002 AC-5: the client value is not copied as is: X-Forwarded-Proto: HTTPS from a trusted peer is
    // written as the scheme req.isSecure() gives, in lower case
    @Test
    public void sec_PROXY_002_AC5_theProtoIsTheSchemeNotTheClientText() throws IOException {
        RawBackend.Received got = forward("10.1.2.3", CIDR_10,
                request("GET", "Host", "gw.example", "X-Forwarded-Proto", "HTTPS"));

        assertEquals(got.head, Collections.singletonList("X-Forwarded-Proto: https"),
                linesNamed(got, "X-Forwarded-Proto"));
    }

    // SEC-PROXY-002 AC-5: a trusted peer's X-Forwarded-Proto that is not https never reaches the backend as sent:
    // gopher and a list https, http (req.isSecure() is false for both) give http under CIDR, one-hop and legacy trust
    @Test
    public void sec_PROXY_002_AC5_aTrustedNonSchemeValueIsReplacedByIsSecure() throws IOException {
        Object[][] settings = {{"10.1.2.3", CIDR_10}, {"127.0.0.1", ONE_HOP}, {"127.0.0.1", LEGACY}};
        for (Object[] s : settings) {
            @SuppressWarnings("unchecked")
            Consumer<RestApp> trust = (Consumer<RestApp>) s[1];
            RawBackend.Received gopher = forward((String) s[0], trust,
                    request("GET", "Host", "gw.example", "X-Forwarded-Proto", "gopher"));

            assertEquals(gopher.head, Collections.singletonList("X-Forwarded-Proto: http"),
                    linesNamed(gopher, "X-Forwarded-Proto"));
        }
        RawBackend.Received list = forward("10.1.2.3", CIDR_10,
                request("GET", "Host", "gw.example", "X-Forwarded-Proto", "https, http"));
        assertEquals(list.head, Collections.singletonList("X-Forwarded-Proto: http"),
                linesNamed(list, "X-Forwarded-Proto"));
    }

    // ---- AC-6 ----

    // SEC-PROXY-002 AC-6: under trustProxyHops(1) and the legacy trustProxy(true) the peer 127.0.0.1 is trusted, so
    // AC-4 gives 1.2.3.4, 127.0.0.1 and 1.2.3.4, 5.6.7.8, 127.0.0.1, and AC-5 gives https
    @Test
    public void sec_PROXY_002_AC6_hopsAndLegacyTrustGiveTheTrustedResults() throws IOException {
        for (Consumer<RestApp> trust : Arrays.asList(ONE_HOP, LEGACY)) {
            RawBackend.Received single = forward("127.0.0.1", trust,
                    request("GET", "Host", "gw.example", "X-Forwarded-For", "1.2.3.4"));
            RawBackend.Received two = forward("127.0.0.1", trust, request("GET", "Host", "gw.example",
                    "X-Forwarded-For", "1.2.3.4", "X-Forwarded-For", "5.6.7.8"));
            RawBackend.Received proto = forward("127.0.0.1", trust,
                    request("GET", "Host", "gw.example", "X-Forwarded-Proto", "https"));

            assertEquals(single.head, Collections.singletonList("X-Forwarded-For: 1.2.3.4, 127.0.0.1"),
                    linesNamed(single, "X-Forwarded-For"));
            assertEquals(two.head, Collections.singletonList("X-Forwarded-For: 1.2.3.4, 5.6.7.8, 127.0.0.1"),
                    linesNamed(two, "X-Forwarded-For"));
            assertEquals(proto.head, Collections.singletonList("X-Forwarded-Proto: https"),
                    linesNamed(proto, "X-Forwarded-Proto"));
        }
    }

    // ---- AC-7 ----

    // SEC-PROXY-002 AC-7: under no trust, a trusted CIDR peer, one hop and legacy trust, a client X-Forwarded-Host:
    // evil.example is replaced by the received Host and the client's Forwarded, X-Real-IP, X-Forwarded-Port and
    // X-Forwarded-Prefix (any name letters) do not arrive
    @Test
    public void sec_PROXY_002_AC7_hostIsReplacedAndOtherForwardingHeadersAreDropped() throws IOException {
        Object[][] settings = {
            {"127.0.0.1", NO_TRUST}, {"10.1.2.3", CIDR_10}, {"127.0.0.1", ONE_HOP}, {"127.0.0.1", LEGACY},
        };
        for (Object[] s : settings) {
            @SuppressWarnings("unchecked")
            Consumer<RestApp> trust = (Consumer<RestApp>) s[1];
            RawBackend.Received got = forward((String) s[0], trust, request("GET",
                    "X-Forwarded-Host", "evil.example", "Forwarded", "for=6.6.6.6;host=evil.example;proto=https",
                    "Host", "gw.example", "X-Real-IP", "6.6.6.6", "x-forwarded-port", "443",
                    "X-FORWARDED-PREFIX", "/evil", "x-real-ip", "7.7.7.7", "X-A", "1"));

            assertEquals(got.head, Collections.singletonList("X-Forwarded-Host: gw.example"),
                    linesNamed(got, "X-Forwarded-Host"));
            assertEquals(got.head, NONE,
                    linesNamed(got, "Forwarded", "X-Real-IP", "X-Forwarded-Port", "X-Forwarded-Prefix"));
            assertEquals(got.head, one("1"), values(got, "X-A"));
        }
    }

    // SEC-PROXY-002 AC-7 (wave-13 LP-10): the client's X-Forwarded-Host never reaches the backend ahead of the
    // proxy's own line, so a backend that reads the first value sees the received Host
    @Test
    public void sec_PROXY_002_AC7_clientForwardedHostIsNotForwardedAheadOfTheProxyLine() throws IOException {
        RawBackend.Received got = forward("127.0.0.1", LEGACY,
                request("GET", "Host", "gw.example", "X-Forwarded-Host", "attacker"));

        assertEquals(got.head, one("gw.example"), values(got, "X-Forwarded-Host"));
    }

    // SEC-PROXY-002 AC-7: with no received Host the client's X-Forwarded-Host is still dropped and none is sent
    @Test
    public void sec_PROXY_002_AC7_noHostMeansNoForwardedHost() throws IOException {
        RawBackend.Received got = forward("127.0.0.1", LEGACY,
                request("GET", "X-Forwarded-Host", "evil.example"));

        assertEquals(got.head, NONE, values(got, "X-Forwarded-Host"));
    }

    // ---- AC-8 ----

    // SEC-PROXY-002 AC-8: a socket address that is empty, unknown, a host name, a bracketed host name or no valid IP
    // literal (a full-width hex digit included) gives no X-Forwarded-For line, for an untrusted and a trusted
    // (legacy) peer, with and without a client line
    @Test
    public void sec_PROXY_002_AC8_unusableSocketAddressSendsNoForwardedFor() throws IOException {
        String[] unusable = {"", "unknown", "client.example", "[client.example]", "999.1.2.3", "1.2.3", "::1::2",
            "[::1", "%lo0", "[]", "::\uFF21", "1:2:3:4:5:6:7:8:9", "::1:2:3:4:5:6:7:8", "1::2:3:4:5:6:7:8", "01.2.3.4",
            "00001::", "1:2:3:4:5:1.2.3.4", "1.2.3.4::"};
        for (String address : unusable) {
            for (Consumer<RestApp> trust : Arrays.asList(NO_TRUST, LEGACY)) {
                RawBackend.Received without = forward(address, trust, request("GET", "Host", "gw.example"));
                RawBackend.Received with = forward(address, trust,
                        request("GET", "Host", "gw.example", "X-Forwarded-For", "1.2.3.4"));

                assertEquals("'" + address + "' " + without.head, NONE, values(without, "X-Forwarded-For"));
                assertEquals("'" + address + "' " + with.head, NONE, values(with, "X-Forwarded-For"));
                assertEquals(with.head, one("http"), values(with, "X-Forwarded-Proto"));
                assertEquals(with.head, one("gw.example"), values(with, "X-Forwarded-Host"));
            }
        }
    }

    // SEC-PROXY-002 AC-8: the other side of the usability rule: IPv4 and IPv6 literals at the edges of the grammar
    // (the unspecified address, a trailing or leading gap, eight groups, an embedded IPv4) are usable and are written
    // as reported
    @Test
    public void sec_PROXY_002_AC8_literalsAtTheGrammarEdgesAreUsable() throws IOException {
        String[] usable = {"::", "1::", "1:2:3:4:5:6:7::", "1:2:3:4:5:6:7:8", "1:2:3:4:5:6:1.2.3.4", "::1.2.3.4",
            "0.0.0.0", "255.255.255.255", "ABCD:ef01::"};
        for (String address : usable) {
            RawBackend.Received got = forward(address, NO_TRUST, request("GET", "Host", "gw.example"));

            assertEquals(address + " " + got.head, Collections.singletonList("X-Forwarded-For: " + address),
                    linesNamed(got, "X-Forwarded-For"));
        }
    }

    // ---- AC-9 ----

    // SEC-PROXY-002 AC-9: a request reported with raw path /api/orders/x and Host: a.example sends X-Forwarded-Host:
    // a.example, and under .preserveHost(true) Host: a.example; the value comes from the Host header only
    @Test
    public void sec_PROXY_002_AC9_forwardedHostComesFromTheHostHeader() throws IOException {
        RawBackend plain = okBackend();
        RawBackend preserving = okBackend();
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        TestApp t = TestApp.create(adapter);
        RestApp app = t.app();
        app.use("/api", Proxy.to(plain.url()));
        app.use("/keep", Proxy.to(preserving.url()).preserveHost(true));
        t.start();
        started.add(app);

        assertEquals(200, adapter.send(requestTo("GET", "/api/orders/x", "Host", "a.example")).status());
        assertEquals(200, adapter.send(requestTo("GET", "/keep/orders/x", "Host", "a.example")).status());

        assertEquals(one("a.example"), values(plain.onlyRequest(), "X-Forwarded-Host"));
        assertEquals(one("127.0.0.1:" + plain.port()), values(plain.onlyRequest(), "Host"));
        assertEquals(one("a.example"), values(preserving.onlyRequest(), "X-Forwarded-Host"));
        assertEquals(one("a.example"), values(preserving.onlyRequest(), "Host"));
    }

    // ---- FR-PROXY-003 AC-8 (wave-12 LP-9) ----

    // FR-PROXY-003 AC-8: the whole backend header block is, in order, Host; the client headers in received order
    // without the removed and replaced ones and with the merged Cookie at the first Cookie position; X-Forwarded-For,
    // X-Forwarded-Proto and X-Forwarded-Host; then the framing header and Connection: close (D-053)
    @Test
    public void fr_PROXY_003_AC8_forwardingLinesTakeTheirSlotInTheWholeBlock() throws IOException {
        RawBackend backend = okBackend();

        RawBackend.Received got = forward("127.0.0.1", false, NO_TRUST, backend, request("POST",
                "Cookie", "a=1", "X-Forwarded-For", "1.2.3.4", "X-A", "1", "Host", "gw.example",
                "X-Forwarded-Host", "evil.example", "Forwarded", "for=1.2.3.4", "Connection", "keep-alive, X-Hop",
                "X-Hop", "h", "X-Real-IP", "1.2.3.4", "Cookie", "b=2", "X-Forwarded-Proto", "https",
                "Content-Length", "5", "X-Forwarded-Port", "443", "X-Forwarded-Prefix", "/p", "X-B", "2")
                .body("hello".getBytes(StandardCharsets.ISO_8859_1)));

        assertEquals(got.head, Arrays.asList("Host: 127.0.0.1:" + backend.port(), "Cookie: a=1; b=2", "X-A: 1",
                "X-B: 2", "X-Forwarded-For: 127.0.0.1", "X-Forwarded-Proto: http", "X-Forwarded-Host: gw.example",
                "Content-Length: 5", "Connection: close"), got.headerLines());
    }

    // FR-PROXY-003 AC-8: the same layout for a trusted peer, whose X-Forwarded-For line carries the client values
    @Test
    public void fr_PROXY_003_AC8_trustedPeerBlockLayout() throws IOException {
        RawBackend backend = okBackend();

        RawBackend.Received got = forward("10.1.2.3", false, CIDR_10, backend, request("GET",
                "X-Forwarded-For", "1.2.3.4", "X-A", "1", "Host", "gw.example", "X-Forwarded-Proto", "https"));

        assertEquals(got.head, Arrays.asList("Host: 127.0.0.1:" + backend.port(), "X-A: 1",
                "X-Forwarded-For: 1.2.3.4, 10.1.2.3", "X-Forwarded-Proto: https", "X-Forwarded-Host: gw.example",
                "Connection: close"), got.headerLines());
    }

    // ---- test support ----

    /** A TinyHttpd app with {@code proxy} at /api and the extra allowed host names; returns its port. */
    private int tinyHttpdProxy(Proxy proxy, String... allowedHosts) {
        RestApp g = RestApp.builder().httpAdapter(new TinyHttpdAdapter()).jsonCodec(new StubJsonCodec())
                .logger(new RecordingLogger()).build();
        started.add(g);
        g.server(o -> o.allowedHosts(allowedHosts));
        g.use("/api", proxy);
        return g.listen(0).port();
    }

    private static String exchange(int port, String request) throws IOException {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            s.setSoTimeout(10000);
            OutputStream out = s.getOutputStream();
            out.write(request.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            try {
                InputStream in = s.getInputStream();
                for (int n; (n = in.read(b)) >= 0; ) {
                    buf.write(b, 0, n);
                }
            } catch (SocketException reset) {
                // a reset after the close counts as the end
            }
            return new String(buf.toByteArray(), StandardCharsets.ISO_8859_1);
        }
    }
}
