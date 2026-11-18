package com.snoworca.naruserve.proxy;

import static org.junit.Assert.assertEquals;

import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.TestApp;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import org.junit.After;
import org.junit.Test;

/**
 * The fixed request headers of a proxy (FR-PROXY-005): {@code .requestHeader(name, value)} sends exactly one line of
 * the name with the configured value in place of every client line of that name in any letter case, and
 * {@code .removeRequestHeader(name)} drops every client line of the name. Both apply after hop-by-hop removal,
 * {@code Host} handling and the forwarding headers, and only the later call for a name counts. The configured values
 * take the {@code .requestHeader} slot of the backend header block, after the forwarding lines and in the call order
 * of the calls that count (FR-PROXY-003 AC-8, design D-053). The in-memory adapter's peer is 127.0.0.1 over plain
 * HTTP, so every block carries {@code X-Forwarded-For: 127.0.0.1} and {@code X-Forwarded-Proto: http}.
 */
public class ProxyFixedRequestHeadersTest {

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
        ClientRequest r = ClientRequest.of(method, "/api/x");
        for (int i = 0; i < headerPairs.length; i += 2) {
            r.header(headerPairs[i], headerPairs[i + 1]);
        }
        return r;
    }

    /**
     * Sends {@code request} from 127.0.0.1 over plain HTTP to an in-memory app with {@code options} applied to a proxy
     * to {@code backend} at /api; asserts 200 ok and returns what the backend received.
     */
    private RawBackend.Received forward(RawBackend backend, UnaryOperator<Proxy> options, ClientRequest request) {
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter("127.0.0.1", false);
        TestApp t = TestApp.create(adapter);
        RestApp app = t.app();
        app.use("/api", options.apply(Proxy.to(backend.url())));
        t.start();
        started.add(app);

        ClientResponse r = adapter.send(request);

        assertEquals("status", 200, r.status());
        assertEquals("ok", r.text());
        return backend.onlyRequest();
    }

    private RawBackend.Received forward(UnaryOperator<Proxy> options, ClientRequest request) throws IOException {
        return forward(okBackend(), options, request);
    }

    /** The header lines of {@code got} whose name is {@code name}, case-insensitively, as received. */
    private static List<String> linesNamed(RawBackend.Received got, String name) {
        List<String> out = new ArrayList<>();
        for (String line : got.headerLines()) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).equalsIgnoreCase(name)) {
                out.add(line);
            }
        }
        return out;
    }

    private static List<String> one(String line) {
        return Collections.singletonList(line);
    }

    private static final List<String> NONE = Collections.emptyList();

    // ---- AC-1 ----

    // FR-PROXY-005 AC-1: with .requestHeader("X-Gateway-Source", "gateway") the backend receives exactly one
    // X-Gateway-Source: gateway when the client sent x-gateway-source: spoofed (and a second copy in other letters)
    @Test
    public void fr_PROXY_005_AC1_configuredValueReplacesSpoofedClientLines() throws IOException {
        RawBackend.Received got = forward(p -> p.requestHeader("X-Gateway-Source", "gateway"),
                request("GET", "x-gateway-source", "spoofed", "X-GATEWAY-SOURCE", "spoofed-too"));

        assertEquals(got.head, one("X-Gateway-Source: gateway"), linesNamed(got, "X-Gateway-Source"));
    }

    // FR-PROXY-005 AC-1: every forwarded request carries the line, also one whose client sent none of that name
    @Test
    public void fr_PROXY_005_AC1_lineIsSentWhenTheClientSentNone() throws IOException {
        RawBackend.Received got = forward(p -> p.requestHeader("X-Gateway-Source", "gateway"),
                request("GET", "X-Other", "o"));

        assertEquals(got.head, one("X-Gateway-Source: gateway"), linesNamed(got, "X-Gateway-Source"));
        assertEquals(got.head, one("X-Other: o"), linesNamed(got, "X-Other"));
    }

    // ---- AC-2 ----

    // FR-PROXY-005 AC-2: with .requestHeader("X-Forwarded-Prefix", "/api/orders") the backend receives exactly one
    // X-Forwarded-Prefix: /api/orders, also when the client sent its own X-Forwarded-Prefix
    @Test
    public void fr_PROXY_005_AC2_forwardedPrefixIsTheConfiguredValueOnly() throws IOException {
        RawBackend.Received got = forward(p -> p.requestHeader("X-Forwarded-Prefix", "/api/orders"),
                request("GET", "X-Forwarded-Prefix", "/evil", "x-forwarded-prefix", "/evil2"));

        assertEquals(got.head, one("X-Forwarded-Prefix: /api/orders"), linesNamed(got, "X-Forwarded-Prefix"));
    }

    // ---- AC-3 ----

    // FR-PROXY-005 AC-3: with .removeRequestHeader("X-User-Id") a client X-User-Id: admin (in any letter case) does
    // not arrive at the backend, while the other client lines still do
    @Test
    public void fr_PROXY_005_AC3_removedClientLinesDoNotArrive() throws IOException {
        RawBackend.Received got = forward(p -> p.removeRequestHeader("X-User-Id"),
                request("GET", "X-User-Id", "admin", "x-user-id", "root", "X-Keep", "k"));

        assertEquals(got.head, NONE, linesNamed(got, "X-User-Id"));
        assertEquals(got.head, one("X-Keep: k"), linesNamed(got, "X-Keep"));
    }

    // FR-PROXY-005 AC-3: with .removeRequestHeader("X-User-Id").requestHeader("X-User-Id", "u") exactly one
    // X-User-Id: u arrives
    @Test
    public void fr_PROXY_005_AC3_removeThenSetSendsTheConfiguredValue() throws IOException {
        RawBackend.Received got = forward(p -> p.removeRequestHeader("X-User-Id").requestHeader("X-User-Id", "u"),
                request("GET", "X-User-Id", "admin"));

        assertEquals(got.head, one("X-User-Id: u"), linesNamed(got, "X-User-Id"));
    }

    // ---- AC-4 ----

    // FR-PROXY-005 AC-4: with .requestHeader("X-A", "1").removeRequestHeader("X-A") no X-A line arrives, neither the
    // configured value nor the client's
    @Test
    public void fr_PROXY_005_AC4_laterRemovalCancelsTheValueAndTheClientLine() throws IOException {
        RawBackend.Received got = forward(p -> p.requestHeader("X-A", "1").removeRequestHeader("X-A"),
                request("GET", "X-A", "client"));

        assertEquals(got.head, NONE, linesNamed(got, "X-A"));
    }

    // FR-PROXY-005 AC-4: with .requestHeader("X-A", "1").requestHeader("X-A", "2") exactly one X-A: 2 arrives
    @Test
    public void fr_PROXY_005_AC4_laterValueWins() throws IOException {
        RawBackend.Received got = forward(p -> p.requestHeader("X-A", "1").requestHeader("X-A", "2"),
                request("GET", "X-A", "client"));

        assertEquals(got.head, one("X-A: 2"), linesNamed(got, "X-A"));
    }

    // FR-PROXY-005 AC-4: names compare case-insensitively, so calls for x-a and X-A are calls for the same name and
    // the later one counts, with the name letters it wrote
    @Test
    public void fr_PROXY_005_AC4_callsForTheSameNameInOtherLettersAreTheSameName() throws IOException {
        RawBackend.Received set = forward(p -> p.requestHeader("x-a", "1").requestHeader("X-A", "2"),
                request("GET", "X-A", "client"));
        assertEquals(set.head, one("X-A: 2"), linesNamed(set, "X-A"));

        RawBackend.Received removed = forward(p -> p.requestHeader("X-A", "1").removeRequestHeader("x-a"),
                request("GET", "x-A", "client"));
        assertEquals(removed.head, NONE, linesNamed(removed, "X-A"));
    }

    // ---- AC-5 ----

    // FR-PROXY-005 AC-5: with .requestHeader("Via", "1.1 gw") the backend receives Via: 1.1 gw, one line, because the
    // proxy adds no Via of its own and the fixed value replaces the client's
    @Test
    public void fr_PROXY_005_AC5_viaIsTheConfiguredValue() throws IOException {
        RawBackend.Received plain = forward(p -> p.requestHeader("Via", "1.1 gw"), request("GET"));
        assertEquals(plain.head, one("Via: 1.1 gw"), linesNamed(plain, "Via"));

        RawBackend.Received replaced = forward(p -> p.requestHeader("Via", "1.1 gw"),
                request("GET", "Via", "1.0 client"));
        assertEquals(replaced.head, one("Via: 1.1 gw"), linesNamed(replaced, "Via"));
    }

    // ---- applied after the proxy's own processing ----

    // FR-PROXY-005 AC-1: the fixed line is sent after hop-by-hop removal, so a name the client listed in Connection
    // (whose client line is dropped) still carries the configured value, exactly once
    @Test
    public void fr_PROXY_005_AC1_aConnectionListedNameStillCarriesTheConfiguredValue() throws IOException {
        RawBackend.Received got = forward(p -> p.requestHeader("X-Gateway-Source", "gateway"),
                request("GET", "Connection", "X-Gateway-Source", "X-Gateway-Source", "spoofed"));

        assertEquals(got.head, one("X-Gateway-Source: gateway"), linesNamed(got, "X-Gateway-Source"));
    }

    // FR-PROXY-005 AC-1: a fixed Cookie replaces every client Cookie line, the merged one included
    @Test
    public void fr_PROXY_005_AC1_fixedCookieReplacesTheMergedClientCookie() throws IOException {
        RawBackend.Received got = forward(p -> p.requestHeader("Cookie", "s=1"),
                request("GET", "Cookie", "a=1", "cookie", "b=2"));

        assertEquals(got.head, one("Cookie: s=1"), linesNamed(got, "Cookie"));
    }

    // FR-PROXY-005 AC-3: removing Cookie drops every client Cookie line, so no merged line is sent
    @Test
    public void fr_PROXY_005_AC3_removedCookieDropsEveryClientCookieLine() throws IOException {
        RawBackend.Received got = forward(p -> p.removeRequestHeader("cookie"),
                request("GET", "Cookie", "a=1", "Cookie", "b=2"));

        assertEquals(got.head, NONE, linesNamed(got, "Cookie"));
    }

    // FR-PROXY-005 AC-3, AC-4 (IR-PROXY-001 derived proxies): the proxy derived with the rules drops and replaces the
    // client lines, while the proxy it was derived from, mounted beside it, forwards them unchanged
    @Test
    public void fr_PROXY_005_AC3_rulesApplyOnlyToTheDerivedProxy() throws IOException {
        RawBackend backend = okBackend();
        Proxy base = Proxy.to(backend.url());
        Proxy ruled = base.removeRequestHeader("X-User-Id").requestHeader("X-A", "1");

        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter("127.0.0.1", false);
        TestApp t = TestApp.create(adapter);
        RestApp app = t.app();
        app.use("/api", ruled);
        app.use("/raw", base);
        t.start();
        started.add(app);
        assertEquals(200, adapter.send(request("GET", "X-User-Id", "admin", "X-A", "client")).status());
        ClientRequest toBase = ClientRequest.of("GET", "/raw/x").header("X-User-Id", "admin").header("X-A", "client");
        assertEquals(200, adapter.send(toBase).status());

        List<RawBackend.Received> got = backend.requests();
        assertEquals(2, got.size());
        RawBackend.Received viaRuled = got.get(0);
        assertEquals(viaRuled.head, NONE, linesNamed(viaRuled, "X-User-Id"));
        assertEquals(viaRuled.head, one("X-A: 1"), linesNamed(viaRuled, "X-A"));
        RawBackend.Received viaBase = got.get(1);
        assertEquals(viaBase.head, one("X-User-Id: admin"), linesNamed(viaBase, "X-User-Id"));
        assertEquals(viaBase.head, one("X-A: client"), linesNamed(viaBase, "X-A"));
    }

    // ---- FR-PROXY-003 AC-8 with the .requestHeader slot (wave-12 LP-9) ----

    // FR-PROXY-003 AC-8, FR-PROXY-005 AC-1..AC-4: the whole backend header block is, in order, Host; the client headers
    // in received order without the removed and replaced ones and with the merged Cookie at the first Cookie position;
    // X-Forwarded-For, X-Forwarded-Proto and X-Forwarded-Host; the .requestHeader values in the call order of the calls
    // that count (a name set again moves to its later call); then the framing header and Connection: close (D-053)
    @Test
    public void fr_PROXY_003_AC8_fr_PROXY_005_wholeBlockWithFixedRequestHeaders() throws IOException {
        RawBackend backend = okBackend();

        RawBackend.Received got = forward(backend, p -> p
                        .requestHeader("X-Gateway-Source", "gateway")
                        .removeRequestHeader("X-User-Id")
                        .requestHeader("X-Forwarded-Prefix", "/api/orders")
                        .requestHeader("X-A", "1")
                        .requestHeader("Via", "1.1 gw")
                        .removeRequestHeader("Via")
                        .requestHeader("x-gateway-source", "gw2"),
                request("POST", "Cookie", "a=1", "X-User-Id", "admin", "X-A", "client", "Host", "gw.example",
                        "x-gateway-source", "spoofed", "X-Forwarded-For", "1.2.3.4", "Via", "1.0 client",
                        "Cookie", "b=2", "X-Forwarded-Prefix", "/p", "Connection", "keep-alive, X-Hop", "X-Hop", "h",
                        "X-B", "2", "Content-Length", "5")
                        .body("hello".getBytes(StandardCharsets.ISO_8859_1)));

        assertEquals(got.head, Arrays.asList("Host: 127.0.0.1:" + backend.port(), "Cookie: a=1; b=2", "X-B: 2",
                "X-Forwarded-For: 127.0.0.1", "X-Forwarded-Proto: http", "X-Forwarded-Host: gw.example",
                "X-Forwarded-Prefix: /api/orders", "X-A: 1", "x-gateway-source: gw2", "Content-Length: 5",
                "Connection: close"), got.headerLines());
    }

    // FR-PROXY-003 AC-8, FR-PROXY-005 AC-1: a fixed Cookie leaves the client Cookie position empty and takes its place
    // in the .requestHeader slot, after the forwarding lines
    @Test
    public void fr_PROXY_003_AC8_fr_PROXY_005_fixedCookieTakesTheRequestHeaderSlot() throws IOException {
        RawBackend backend = okBackend();

        RawBackend.Received got = forward(backend, p -> p.requestHeader("Cookie", "s=1"),
                request("GET", "Cookie", "a=1", "X-B", "2", "Cookie", "b=2"));

        assertEquals(got.head, Arrays.asList("Host: 127.0.0.1:" + backend.port(), "X-B: 2",
                "X-Forwarded-For: 127.0.0.1", "X-Forwarded-Proto: http", "Cookie: s=1", "Connection: close"),
                got.headerLines());
    }
}
