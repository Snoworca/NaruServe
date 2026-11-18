package com.snoworca.naruserve.proxy;

import static org.junit.Assert.assertArrayEquals;
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
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Test;

/**
 * The forwarded request target (FR-PROXY-006): the method token, the path and the query exactly as the client encoded
 * them and the body bytes unchanged, with the target's base path followed by the raw {@code originalUrl()} by default
 * and by the remainder {@code url()} under {@code .stripPrefix(true)}, observed at a recording backend under the
 * {@code /api/orders} mount, through the in-memory adapter and over a real TinyHttpd socket.
 */
public class ProxyRequestTargetTest {

    private static final String JSON = "{\"name\":\"한글\",\"qty\":2}";

    private final InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
    private final TestApp t = TestApp.create(adapter);
    private final RestApp app = t.app();
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

    /** A backend that answers 200 with body {@code ok} and records what it received. */
    private RawBackend okBackend() throws IOException {
        RawBackend b = RawBackend.answering(RawBackend.response(200, "ok"));
        closeables.add(b);
        return b;
    }

    /** {@code url} with {@code <port>} replaced by the port of {@code backend}. */
    private static String target(String url, RawBackend backend) {
        return url.replace("<port>", Integer.toString(backend.port()));
    }

    /**
     * Mounts {@code proxy} at /api/orders on the in-memory app, sends {@code request}, asserts 200 ok and returns what
     * the backend received.
     */
    private RawBackend.Received forward(RawBackend backend, Proxy proxy, ClientRequest request) {
        app.use("/api/orders", proxy);
        t.start();
        started.add(app);

        ClientResponse r = adapter.send(request);

        assertEquals("status", 200, r.status());
        assertEquals("ok", r.text());
        return backend.onlyRequest();
    }

    /** The request line the backend receives for {@code GET target} through {@code url} (with {@code <port>}). */
    private String forwardedLine(String url, boolean stripPrefix, String requestTarget) throws IOException {
        RawBackend backend = okBackend();
        Proxy proxy = Proxy.to(target(url, backend));
        if (stripPrefix) {
            proxy = proxy.stripPrefix(true);
        }
        return forward(backend, proxy, ClientRequest.of("GET", requestTarget)).requestLine();
    }

    /** Starts a TinyHttpd app with {@code proxy} at /api/orders and returns its port. */
    private int listenTiny(Proxy proxy) {
        RestApp g = RestApp.builder().httpAdapter(new TinyHttpdAdapter()).jsonCodec(new StubJsonCodec())
                .logger(new RecordingLogger()).build();
        started.add(g);
        g.use("/api/orders", proxy);
        return g.listen(0).port();
    }

    /**
     * Writes {@code head} (the request line and header lines without the blank line; {@code Host} and
     * {@code Connection: close} are added) and {@code body} to the TinyHttpd port, asserts 200 ok and returns what the
     * backend received.
     */
    private static RawBackend.Received forwardOverSocket(RawBackend backend, int port, String head, byte[] body)
            throws IOException {
        String response;
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            s.setSoTimeout(20000);
            OutputStream out = new BufferedOutputStream(s.getOutputStream());
            out.write((head + "\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
            out.write(body);
            out.flush();
            response = readToEnd(s.getInputStream());
        }
        assertTrue(response, response.startsWith("HTTP/1.1 200 "));
        assertTrue(response, response.endsWith("\r\n\r\nok"));
        return backend.onlyRequest();
    }

    private static String readToEnd(InputStream in) throws IOException {
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) >= 0; ) {
            all.write(buf, 0, n);
        }
        return new String(all.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    // ---- AC-1 ----

    // FR-PROXY-006 AC-1: POST /api/orders/query?x=1&y=%ED%95%9C with a JSON body arrives as POST, with that exact
    // request target and the JSON body bytes the client sent
    @Test
    public void fr_PROXY_006_AC1_postWithJsonBodyArrivesWithMethodTargetAndBodyUnchanged() throws IOException {
        RawBackend backend = okBackend();
        byte[] sent = JSON.getBytes(StandardCharsets.UTF_8);

        RawBackend.Received got = forward(backend, Proxy.to(backend.url()),
                ClientRequest.of("POST", "/api/orders/query?x=1&y=%ED%95%9C")
                        .header("Content-Type", "application/json")
                        .header("Content-Length", Integer.toString(sent.length)).body(sent));

        assertEquals("POST /api/orders/query?x=1&y=%ED%95%9C HTTP/1.1", got.requestLine());
        assertArrayEquals(sent, got.body);
    }

    // FR-PROXY-006 AC-1: over a real TinyHttpd socket the same POST arrives with the method, the exact request target
    // and the identical JSON body bytes
    @Test(timeout = 30000)
    public void fr_PROXY_006_AC1_postWithJsonBodyOverASocketArrivesUnchanged() throws IOException {
        RawBackend backend = okBackend();
        byte[] sent = JSON.getBytes(StandardCharsets.UTF_8);
        int port = listenTiny(Proxy.to(backend.url()));

        RawBackend.Received got = forwardOverSocket(backend, port,
                "POST /api/orders/query?x=1&y=%ED%95%9C HTTP/1.1\r\nContent-Type: application/json\r\n"
                        + "Content-Length: " + sent.length, sent);

        assertEquals("POST /api/orders/query?x=1&y=%ED%95%9C HTTP/1.1", got.requestLine());
        assertArrayEquals(sent, got.body);
    }

    // FR-PROXY-006 AC-1: an extension method token reaches the backend as the client sent it, not as a normalized
    // method name
    @Test
    public void fr_PROXY_006_AC1_theMethodTokenIsForwardedAsSent() throws IOException {
        RawBackend backend = okBackend();

        RawBackend.Received got = forward(backend, Proxy.to(backend.url()),
                ClientRequest.of("PROPFIND", "/api/orders/query?x=1"));

        assertEquals("PROPFIND /api/orders/query?x=1 HTTP/1.1", got.requestLine());
    }

    // FR-PROXY-006 AC-1: a mixed-case extension method token keeps its case, since method tokens are case-sensitive
    @Test
    public void fr_PROXY_006_AC1_aMixedCaseMethodTokenKeepsItsCase() throws IOException {
        RawBackend backend = okBackend();

        RawBackend.Received got = forward(backend, Proxy.to(backend.url()),
                ClientRequest.of("PropFind", "/api/orders/q"));

        assertEquals("PropFind /api/orders/q HTTP/1.1", got.requestLine());
    }

    // FR-PROXY-006 AC-1: a percent-encoded path, in either hexadecimal case, is forwarded as encoded, neither decoded
    // nor re-encoded
    @Test
    public void fr_PROXY_006_AC1_anEncodedPathArrivesAsEncodedByDefault() throws IOException {
        assertEquals("GET /api/orders/%ED%95%9c%20x/%e2%82%AC?y=%ED%95%9C HTTP/1.1",
                forwardedLine("http://127.0.0.1:<port>", false, "/api/orders/%ED%95%9c%20x/%e2%82%AC?y=%ED%95%9C"));
    }

    // ---- AC-2 ----

    // FR-PROXY-006 AC-2: by default the mount prefix is kept, so /api/orders/query arrives as /api/orders/query
    @Test
    public void fr_PROXY_006_AC2_theMountPrefixIsKeptByDefault() throws IOException {
        assertEquals("GET /api/orders/query HTTP/1.1",
                forwardedLine("http://127.0.0.1:<port>", false, "/api/orders/query"));
    }

    // FR-PROXY-006 AC-2: with .stripPrefix(true) the mount prefix is removed, so /api/orders/query arrives as /query
    @Test
    public void fr_PROXY_006_AC2_stripPrefixRemovesTheMountPrefix() throws IOException {
        assertEquals("GET /query HTTP/1.1",
                forwardedLine("http://127.0.0.1:<port>", true, "/api/orders/query"));
    }

    // FR-PROXY-006 AC-2: the package-private accessor reports stripPrefix false on Proxy.to(url) and true after
    // .stripPrefix(true)
    @Test
    public void fr_PROXY_006_AC2_stripPrefixIsOffByDefault() {
        assertFalse(Proxy.to("http://h").stripPrefix());
        assertTrue(Proxy.to("http://h").stripPrefix(true).stripPrefix());
    }

    // FR-PROXY-006 AC-2: under .stripPrefix(true) a request to exactly the mount path still goes out as an
    // origin-form target starting with "/", the remainder url() of the mount
    @Test
    public void fr_PROXY_006_AC2_stripPrefixOnTheMountPathItselfForwardsTheRoot() throws IOException {
        assertEquals("GET / HTTP/1.1", forwardedLine("http://127.0.0.1:<port>", true, "/api/orders"));
    }

    // FR-PROXY-006 AC-2: on a mount with a path parameter, .stripPrefix(true) removes the prefix actually matched
    @Test
    public void fr_PROXY_006_AC2_stripPrefixRemovesTheMatchedParameterPrefix() throws IOException {
        RawBackend backend = okBackend();
        app.use("/t/:tenant", Proxy.to(backend.url()).stripPrefix(true));
        t.start();
        started.add(app);

        ClientResponse r = adapter.send(ClientRequest.of("GET", "/t/acme/orders/7?x=1"));

        assertEquals(200, r.status());
        assertEquals("GET /orders/7?x=1 HTTP/1.1", backend.onlyRequest().requestLine());
    }

    // FR-PROXY-006 AC-2: under .stripPrefix(true) a percent-encoded remainder is forwarded as encoded, neither decoded
    // nor re-encoded
    @Test
    public void fr_PROXY_006_AC2_anEncodedRemainderArrivesAsEncodedUnderStripPrefix() throws IOException {
        assertEquals("GET /%ED%95%9c%20x/%e2%82%AC?y=%ED%95%9C HTTP/1.1",
                forwardedLine("http://127.0.0.1:<port>", true, "/api/orders/%ED%95%9c%20x/%e2%82%AC?y=%ED%95%9C"));
    }

    // FR-PROXY-006 AC-2: a WebSocket upgrade to an app.ws route runs the prefix middleware with a request whose url()
    // is the percent-decoded full path, not a remainder of the checked originalUrl(); under .stripPrefix(true) the
    // proxy answers 400 before any backend connection instead of writing the decoded CR LF into the request line
    @Test
    public void fr_PROXY_006_AC2_aRemainderThatIsNotARawSuffixIsRefusedBeforeAnyConnection() throws IOException {
        RawBackend backend = okBackend();
        app.ws("/api/orders/:id", (req, ws) -> { });
        app.use("/api/orders", Proxy.to(backend.url()).stripPrefix(true));
        t.start();
        started.add(app);

        ClientResponse r = adapter.send(ClientRequest.upgrade("/api/orders/x%0D%0AX-Injected:%20a"));

        assertEquals(400, r.status());
        assertEquals("backend requests", 0, backend.requestCount());
    }

    // ---- AC-3 ----

    // FR-PROXY-006 AC-3: Proxy.to("http://h/base").stripPrefix(true) forwards /api/orders/query?x=1 as /base/query?x=1
    @Test
    public void fr_PROXY_006_AC3_basePathFollowedByTheRemainderUnderStripPrefix() throws IOException {
        assertEquals("GET /base/query?x=1 HTTP/1.1",
                forwardedLine("http://127.0.0.1:<port>/base", true, "/api/orders/query?x=1"));
    }

    // FR-PROXY-006 AC-3: Proxy.to("http://h/base") forwards /api/orders/query?x=1 as /base/api/orders/query?x=1
    @Test
    public void fr_PROXY_006_AC3_basePathFollowedByTheOriginalUrlByDefault() throws IOException {
        assertEquals("GET /base/api/orders/query?x=1 HTTP/1.1",
                forwardedLine("http://127.0.0.1:<port>/base", false, "/api/orders/query?x=1"));
    }

    // FR-PROXY-006 AC-3: a base path with a trailing slash under .stripPrefix(true) builds no double slash
    @Test
    public void fr_PROXY_006_AC3_basePathWithTrailingSlashUnderStripPrefixBuildsNoDoubleSlash() throws IOException {
        assertEquals("GET /base/query?x=1 HTTP/1.1",
                forwardedLine("http://127.0.0.1:<port>/base/", true, "/api/orders/query?x=1"));
    }

    // ---- AC-4 ----

    // FR-PROXY-006 AC-4: without stripPrefix the query ?a=%41&b=+&c arrives exactly as the client encoded it
    @Test
    public void fr_PROXY_006_AC4_queryArrivesAsEncodedWithoutStripPrefix() throws IOException {
        assertEquals("GET /api/orders/query?a=%41&b=+&c HTTP/1.1",
                forwardedLine("http://127.0.0.1:<port>", false, "/api/orders/query?a=%41&b=+&c"));
    }

    // FR-PROXY-006 AC-4: with .stripPrefix(true) the query ?a=%41&b=+&c arrives exactly as the client encoded it
    @Test
    public void fr_PROXY_006_AC4_queryArrivesAsEncodedWithStripPrefix() throws IOException {
        assertEquals("GET /query?a=%41&b=+&c HTTP/1.1",
                forwardedLine("http://127.0.0.1:<port>", true, "/api/orders/query?a=%41&b=+&c"));
    }

    // FR-PROXY-006 AC-4: with .stripPrefix(true) on the mount path itself the query stays as encoded after "/"
    @Test
    public void fr_PROXY_006_AC4_queryOnTheMountPathItselfStaysAsEncodedWithStripPrefix() throws IOException {
        assertEquals("GET /base/?a=%41&b=+&c HTTP/1.1",
                forwardedLine("http://127.0.0.1:<port>/base", true, "/api/orders?a=%41&b=+&c"));
    }

    // FR-PROXY-006 AC-4: an empty query keeps its question mark with stripPrefix
    @Test
    public void fr_PROXY_006_AC4_anEmptyQueryKeepsItsQuestionMarkWithStripPrefix() throws IOException {
        assertEquals("GET /query? HTTP/1.1", forwardedLine("http://127.0.0.1:<port>", true, "/api/orders/query?"));
    }

    // FR-PROXY-006 AC-4: an empty query keeps its question mark without stripPrefix
    @Test
    public void fr_PROXY_006_AC4_anEmptyQueryKeepsItsQuestionMarkWithoutStripPrefix() throws IOException {
        assertEquals("GET /api/orders/query? HTTP/1.1",
                forwardedLine("http://127.0.0.1:<port>", false, "/api/orders/query?"));
    }

    // FR-PROXY-006 AC-4: over a real TinyHttpd socket the query ?a=%41&b=+&c arrives as encoded with and without
    // stripPrefix
    @Test(timeout = 30000)
    public void fr_PROXY_006_AC4_queryOverASocketArrivesAsEncodedWithAndWithoutStripPrefix() throws IOException {
        RawBackend plainBackend = okBackend();
        RawBackend.Received plain = forwardOverSocket(plainBackend, listenTiny(Proxy.to(plainBackend.url())),
                "GET /api/orders/query?a=%41&b=+&c HTTP/1.1", new byte[0]);
        RawBackend strippedBackend = okBackend();
        RawBackend.Received stripped = forwardOverSocket(strippedBackend,
                listenTiny(Proxy.to(strippedBackend.url()).stripPrefix(true)),
                "GET /api/orders/query?a=%41&b=+&c HTTP/1.1", new byte[0]);

        assertEquals("GET /api/orders/query?a=%41&b=+&c HTTP/1.1", plain.requestLine());
        assertEquals("GET /query?a=%41&b=+&c HTTP/1.1", stripped.requestLine());
    }
}
