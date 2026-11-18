package com.snoworca.naruserve.proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.HttpException;
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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Test;

/**
 * The proxy's request-target and path checks through the middleware (SEC-PROXY-001): a rejected raw
 * {@code originalUrl()} answers 400 before any backend connection, an accepted one arrives at the backend byte for
 * byte, and the configured target origin is the only one ever reached. The in-memory adapter hands the proxy raw
 * targets TinyHttpd would refuse first, so the proxy's own check is what most of these tests see; one test drives a
 * real TinyHttpd socket with absolute-form targets.
 */
public class ProxyPathChecksTest {

    /** The rejected targets of AC-1 to AC-7, every one under the {@code /api/orders} mount. */
    private static final List<String> REJECTED_BY_AC1_TO_AC7 = Collections.unmodifiableList(Arrays.asList(
            // AC-1
            "/api/orders/../other/x", "/api/orders/%2e%2e/other/x", "/api/orders/%2E%2E/other/x",
            "/api/orders/..;/other/x", "/api/orders/.;x/other/x", "/api/orders/./x",
            // AC-2
            "/api/orders/a%2fb", "/api/orders/a%2Fb", "/api/orders/a%5cb", "/api/orders/a%00b", "/api/orders/a\\b",
            // AC-3
            "/api/orders/%70rivate/x", "/api/orders/%7Ex", "/api/orders/%41", "/api/orders/private;x/y",
            // AC-4
            "/api/orders/%252e%252e/other/x", "/api/orders/a%252fb", "/api/orders/%2570rivate",
            // AC-5
            "/api/orders//private/x", "/api/orders/private//x",
            // AC-6
            "/api/orders/a\u0001b", "/api/orders/a b", "/api/orders/a\u007Fb", "/api/orders/aéb",
            "/api/orders/x#f", "/api/orders/x?q=%zz", "/api/orders/x%", "/api/orders/x?q=a b",
            "/api/orders/x?q=\u0009", "/api/orders/x?q=ÿ", "/api/orders/x?q=#",
            // AC-7
            "/api/orders//evil.example/x", "/api/orders/%2F%2Fevil.example"));

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

    private ConnectionCounter counter() throws IOException {
        ConnectionCounter c = new ConnectionCounter();
        closeables.add(c);
        return c;
    }

    private RawBackend okBackend() throws IOException {
        RawBackend b = RawBackend.answering(RawBackend.response(200, "ok"));
        closeables.add(b);
        return b;
    }

    /** Mounts {@code proxy} at /api/orders on the in-memory app and starts it. */
    private void mount(Proxy proxy) {
        app.use("/api/orders", proxy);
        t.start();
        started.add(app);
    }

    /** Sends GET {@code target} and asserts 400 for each; then asserts the counter accepted no connection. */
    private void assertAllRejectedWithoutConnection(ConnectionCounter backend, List<String> targets)
            throws IOException {
        for (String target : targets) {
            ClientResponse r = adapter.send(ClientRequest.of("GET", target));
            assertEquals("status for " + target, 400, r.status());
        }
        assertEquals("backend connections", 0, backend.connectionsBeforeSentinel());
    }

    /** Sends GET {@code target} through a proxy to a recording backend; asserts 200 and returns the request line. */
    private String forwardedRequestLine(String target) throws IOException {
        RawBackend backend = okBackend();
        mount(Proxy.to(backend.url()));

        ClientResponse r = adapter.send(ClientRequest.of("GET", target));

        assertEquals("status for " + target, 200, r.status());
        assertEquals("ok", r.text());
        return backend.onlyRequest().requestLine();
    }

    // ---- AC-1 ----

    // SEC-PROXY-001 AC-1: '.' and '..' segments, raw, encoded or with ';', get 400 and open no backend connection
    @Test
    public void sec_PROXY_001_AC1_dotSegmentsGet400WithoutABackendConnection() throws IOException {
        ConnectionCounter backend = counter();
        mount(Proxy.to(backend.url()));

        assertAllRejectedWithoutConnection(backend, Arrays.asList("/api/orders/../other/x",
                "/api/orders/%2e%2e/other/x", "/api/orders/%2E%2E/other/x", "/api/orders/..;/other/x",
                "/api/orders/.;x/other/x", "/api/orders/./x"));
    }

    // SEC-PROXY-001 AC-1: the 400 reaches the error flow as a plain HttpException(400) with a null message (D-051)
    @Test
    public void sec_PROXY_001_AC1_theRejectionIsAPlainHttpException400WithNoMessage() throws IOException {
        ConnectionCounter backend = counter();
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<Throwable>());
        app.use("/api/orders", Proxy.to(backend.url()));
        // Registered after the mount, like Express error middleware, so it sees what the proxy throws.
        app.onError((error, req, res) -> {
            errors.add(error);
            res.sendStatus(error instanceof HttpException ? ((HttpException) error).status() : 500);
        });
        t.start();
        started.add(app);

        ClientResponse r = adapter.send(ClientRequest.of("GET", "/api/orders/../other/x"));

        assertEquals(400, r.status());
        assertEquals(1, errors.size());
        Throwable e = errors.get(0);
        assertSame(HttpException.class, e.getClass());
        assertEquals(400, ((HttpException) e).status());
        assertNull(e.getMessage());
        assertEquals("backend connections", 0, backend.connectionsBeforeSentinel());
    }

    // ---- AC-2 ----

    // SEC-PROXY-001 AC-2: an encoded slash, backslash or NUL and a raw backslash get 400 with no backend connection
    @Test
    public void sec_PROXY_001_AC2_encodedSeparatorsAndRawBackslashGet400WithoutABackendConnection()
            throws IOException {
        ConnectionCounter backend = counter();
        mount(Proxy.to(backend.url()));

        assertAllRejectedWithoutConnection(backend, Arrays.asList("/api/orders/a%2fb", "/api/orders/a%2Fb",
                "/api/orders/a%5cb", "/api/orders/a%00b", "/api/orders/a\\b"));
    }

    // ---- AC-3 ----

    // SEC-PROXY-001 AC-3: encoded unreserved characters and ';' get 400 with no backend connection
    @Test
    public void sec_PROXY_001_AC3_encodedUnreservedAndSemicolonGet400WithoutABackendConnection() throws IOException {
        ConnectionCounter backend = counter();
        mount(Proxy.to(backend.url()));

        assertAllRejectedWithoutConnection(backend, Arrays.asList("/api/orders/%70rivate/x", "/api/orders/%7Ex",
                "/api/orders/%41", "/api/orders/private;x/y"));
    }

    // ---- AC-4 ----

    // SEC-PROXY-001 AC-4: double encodings get 400 with no backend connection
    @Test
    public void sec_PROXY_001_AC4_doubleEncodingsGet400WithoutABackendConnection() throws IOException {
        ConnectionCounter backend = counter();
        mount(Proxy.to(backend.url()));

        assertAllRejectedWithoutConnection(backend, Arrays.asList("/api/orders/%252e%252e/other/x",
                "/api/orders/a%252fb", "/api/orders/%2570rivate"));
    }

    // ---- AC-5 ----

    // SEC-PROXY-001 AC-5: empty segments get 400 with no backend connection
    @Test
    public void sec_PROXY_001_AC5_emptySegmentsGet400WithoutABackendConnection() throws IOException {
        ConnectionCounter backend = counter();
        mount(Proxy.to(backend.url()));

        assertAllRejectedWithoutConnection(backend, Arrays.asList("/api/orders//private/x",
                "/api/orders/private//x"));
    }

    // SEC-PROXY-001 AC-5: one trailing slash is kept: /api/orders/x/ arrives as /api/orders/x/
    @Test
    public void sec_PROXY_001_AC5_oneTrailingSlashArrivesUnchanged() throws IOException {
        assertEquals("GET /api/orders/x/ HTTP/1.1", forwardedRequestLine("/api/orders/x/"));
    }

    // ---- AC-6 ----

    // SEC-PROXY-001 AC-6: controls, space, 0x7F, non-ASCII, '#' and a bad '%' in the path or the query get 400 with
    // no backend connection
    @Test
    public void sec_PROXY_001_AC6_badCharactersGet400WithoutABackendConnection() throws IOException {
        ConnectionCounter backend = counter();
        mount(Proxy.to(backend.url()));

        assertAllRejectedWithoutConnection(backend, Arrays.asList("/api/orders/a\u0000b", "/api/orders/a\u001Fb",
                "/api/orders/a b", "/api/orders/a\u007Fb", "/api/orders/a\u0080b", "/api/orders/한",
                "/api/orders/x#frag", "/api/orders/x%", "/api/orders/x%4g", "/api/orders/x?q=%zz",
                "/api/orders/x?q=%", "/api/orders/x?q=a b", "/api/orders/x?q=\r", "/api/orders/x?q=\u007F",
                "/api/orders/x?q=é", "/api/orders/x?q=1#frag"));
    }

    // SEC-PROXY-001 AC-6: a percent-encoded UTF-8 query arrives at the backend byte for byte
    @Test
    public void sec_PROXY_001_AC6_encodedQueryArrivesByteForByte() throws IOException {
        assertEquals("GET /api/orders/x?x=1&y=%ED%95%9C HTTP/1.1",
                forwardedRequestLine("/api/orders/x?x=1&y=%ED%95%9C"));
    }

    // SEC-PROXY-001 AC-6: '@', ':', '!' and '$' in the path and the query arrive at the backend byte for byte
    @Test
    public void sec_PROXY_001_AC6_subDelimitersArriveByteForByte() throws IOException {
        assertEquals("GET /api/orders/a@b:c!d$e?f=@:!$ HTTP/1.1",
                forwardedRequestLine("/api/orders/a@b:c!d$e?f=@:!$"));
    }

    // ---- AC-7 ----

    // SEC-PROXY-001 AC-7: /api/orders/@evil.example/x is forwarded unchanged to the configured target host only
    @Test
    public void sec_PROXY_001_AC7_atSignPathGoesToTheConfiguredTarget() throws IOException {
        RawBackend backend = okBackend();
        mount(Proxy.to(backend.url()));

        ClientResponse r = adapter.send(ClientRequest.of("GET", "/api/orders/@evil.example/x"));

        assertEquals(200, r.status());
        RawBackend.Received got = backend.onlyRequest();
        assertEquals("GET /api/orders/@evil.example/x HTTP/1.1", got.requestLine());
        assertEquals(Collections.singletonList("127.0.0.1:" + backend.port()), got.headerValues("Host"));
    }

    // SEC-PROXY-001 AC-7: '//' and an encoded '//' after the mount get 400 with no backend connection
    @Test
    public void sec_PROXY_001_AC7_doubleSlashAuthorityPathsGet400WithoutABackendConnection() throws IOException {
        ConnectionCounter backend = counter();
        mount(Proxy.to(backend.url()));

        assertAllRejectedWithoutConnection(backend, Arrays.asList("/api/orders//evil.example/x",
                "/api/orders/%2F%2Fevil.example"));
    }

    // ---- AC-8 ----

    // SEC-PROXY-001 AC-8: over the in-memory adapter, which keeps only the path and query of an absolute-form target
    // like TinyHttpd, GET http://evil.example/api/orders/x reaches only the configured target, and the traversal
    // inside an absolute-form target gets 400 with no backend connection
    @Test
    public void sec_PROXY_001_AC8_absoluteFormTargetsOverTheInMemoryAdapter() throws IOException {
        RawBackend backend = okBackend();
        mount(Proxy.to(backend.url()));

        ClientResponse forwarded = adapter.send(ClientRequest.of("GET", "http://evil.example/api/orders/x"));
        ClientResponse traversal = adapter.send(ClientRequest.of("GET", "http://h/api/orders/%2e%2e/x"));

        assertEquals(200, forwarded.status());
        assertEquals(400, traversal.status());
        RawBackend.Received got = backend.onlyRequest();
        assertEquals("GET /api/orders/x HTTP/1.1", got.requestLine());
        assertEquals(Collections.singletonList("127.0.0.1:" + backend.port()), got.headerValues("Host"));
    }

    /** A TinyHttpd app with the proxy to {@code backendUrl} at /api/orders and the extra allowed host names. */
    private int tinyHttpdProxy(String backendUrl, String... allowedHosts) {
        RestApp g = RestApp.builder().httpAdapter(new TinyHttpdAdapter()).jsonCodec(new StubJsonCodec())
                .logger(new RecordingLogger()).build();
        started.add(g);
        if (allowedHosts.length > 0) {
            g.server(o -> o.allowedHosts(allowedHosts));
        }
        g.use("/api/orders", Proxy.to(backendUrl));
        return g.listen(0).port();
    }

    // SEC-PROXY-001 AC-8: over a TinyHttpd socket whose allowlist admits the names, GET
    // http://evil.example/api/orders/x reaches only the configured target and GET http://h/api/orders/%2e%2e/x gets
    // the proxy's 400
    @Test(timeout = 30000)
    public void sec_PROXY_001_AC8_absoluteFormTargetsOverATinyHttpdSocket() throws IOException {
        RawBackend backend = okBackend();
        int port = tinyHttpdProxy(backend.url(), "evil.example", "h");

        String evil = exchange(port, "GET http://evil.example/api/orders/x HTTP/1.1\r\nHost: evil.example\r\n"
                + "Connection: close\r\n\r\n");
        String traversal = exchange(port, "GET http://h/api/orders/%2e%2e/x HTTP/1.1\r\nHost: h\r\n"
                + "Connection: close\r\n\r\n");

        assertTrue(evil, evil.startsWith("HTTP/1.1 200 "));
        assertTrue(traversal, traversal.startsWith("HTTP/1.1 400 "));
        RawBackend.Received got = backend.onlyRequest();
        assertEquals("GET /api/orders/x HTTP/1.1", got.requestLine());
        assertEquals(Collections.singletonList("127.0.0.1:" + backend.port()), got.headerValues("Host"));
    }

    // SEC-PROXY-001 AC-8: over a TinyHttpd socket with the default allowlist the adapter answers a host name it does
    // not allow before the proxy runs (421, its DNS rebinding defence), and still no backend connection is opened;
    // an IP-literal absolute-form traversal reaches the proxy and gets 400
    @Test(timeout = 30000)
    public void sec_PROXY_001_AC8_absoluteFormTargetsOverADefaultTinyHttpdSocket() throws IOException {
        ConnectionCounter backend = counter();
        int port = tinyHttpdProxy(backend.url());

        String evil = exchange(port, "GET http://evil.example/api/orders/x HTTP/1.1\r\nHost: evil.example\r\n"
                + "Connection: close\r\n\r\n");
        String named = exchange(port, "GET http://h/api/orders/%2e%2e/x HTTP/1.1\r\nHost: h\r\n"
                + "Connection: close\r\n\r\n");
        String literal = exchange(port, "GET http://127.0.0.1/api/orders/%2e%2e/x HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                + "Connection: close\r\n\r\n");

        assertTrue(evil, evil.startsWith("HTTP/1.1 421 "));
        assertTrue(named, named.startsWith("HTTP/1.1 421 "));
        assertTrue(literal, literal.startsWith("HTTP/1.1 400 "));
        assertEquals("backend connections", 0, backend.connectionsBeforeSentinel());
    }

    // ---- AC-9 ----

    // SEC-PROXY-001 AC-9: with .stripPrefix(true) every target AC-1 to AC-7 rejects still gets 400 with no backend
    // connection
    @Test
    public void sec_PROXY_001_AC9_stripPrefixKeepsEveryRejection() throws IOException {
        ConnectionCounter backend = counter();
        mount(Proxy.to(backend.url()).stripPrefix(true));

        assertAllRejectedWithoutConnection(backend, REJECTED_BY_AC1_TO_AC7);
    }

    // SEC-PROXY-001 AC-9: the same set is rejected without stripPrefix, so AC-9 compares like with like
    @Test
    public void sec_PROXY_001_AC9_theSameSetIsRejectedWithoutStripPrefix() throws IOException {
        ConnectionCounter backend = counter();
        mount(Proxy.to(backend.url()));

        assertAllRejectedWithoutConnection(backend, REJECTED_BY_AC1_TO_AC7);
    }

    // SEC-PROXY-001 AC-9: on a mount with a path parameter, a traversal in the matched prefix itself is rejected with
    // and without stripPrefix, because the check reads the raw originalUrl() and not the remainder url()
    @Test
    public void sec_PROXY_001_AC9_theMatchedPrefixIsCheckedToo() throws IOException {
        ConnectionCounter backend = counter();
        app.use("/t/:tenant", Proxy.to(backend.url()).stripPrefix(true));
        app.use("/u/:tenant", Proxy.to(backend.url()));
        t.start();
        started.add(app);

        assertAllRejectedWithoutConnection(backend, Arrays.asList("/t/../x", "/t/%2e%2e/x", "/t/..;/x",
                "/t/%41/x", "/t/a%2fb/x", "/u/../x", "/u/%2e%2e/x", "/u/a%2fb/x"));
    }

    // ---- D-050: the path check comes before the target scheme and the framing answers ----

    // SEC-PROXY-001 AC-1: a rejected path on an https target answers the path check's 400, not the 502 of a target
    // the proxy does not reach yet
    @Test
    public void sec_PROXY_001_AC1_thePathCheckComesBeforeTheTargetScheme() throws IOException {
        mount(Proxy.to("https://127.0.0.1:1"));

        ClientResponse r = adapter.send(ClientRequest.of("GET", "/api/orders/../other/x"));

        assertEquals(400, r.status());
    }

    // SEC-PROXY-001 AC-2: a rejected path with an unsupported Transfer-Encoding answers the path check's 400, not the
    // framing answer 501
    @Test
    public void sec_PROXY_001_AC2_thePathCheckComesBeforeTheFramingAnswers() throws IOException {
        ConnectionCounter backend = counter();
        mount(Proxy.to(backend.url()));

        ClientResponse unsupported = adapter.send(ClientRequest.of("POST", "/api/orders/x")
                .header("Transfer-Encoding", "gzip, chunked"));
        ClientResponse both = adapter.send(ClientRequest.of("POST", "/api/orders/a%2fb")
                .header("Transfer-Encoding", "gzip, chunked"));

        assertEquals("the framing answer alone", 501, unsupported.status());
        assertEquals(400, both.status());
        assertEquals("backend connections", 0, backend.connectionsBeforeSentinel());
    }

    private static String exchange(int port, String request) throws IOException {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            s.setSoTimeout(10000);
            OutputStream out = s.getOutputStream();
            out.write(request.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            return readToEnd(s.getInputStream());
        }
    }

    private static String readToEnd(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] b = new byte[4096];
        try {
            for (int n; (n = in.read(b)) >= 0; ) {
                buf.write(b, 0, n);
            }
        } catch (SocketException reset) {
            // a reset after the close counts as the end
        }
        return new String(buf.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    /**
     * A loopback listener that records the client port of every accepted connection and closes it at once.
     * {@link #connectionsBeforeSentinel} closes the race between a connect that already completed and the acceptor
     * thread that has not yet taken it (copied from ProxyRequestFramingTest).
     */
    private static final class ConnectionCounter implements Closeable {
        private final ServerSocket server;
        private final List<Integer> acceptedPorts = Collections.synchronizedList(new ArrayList<Integer>());
        private final Thread acceptor;

        ConnectionCounter() throws IOException {
            server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
            acceptor = new Thread(() -> {
                while (!server.isClosed()) {
                    try (Socket s = server.accept()) {
                        acceptedPorts.add(s.getPort());
                    } catch (IOException e) {
                        // the listener was closed
                    }
                }
            }, "connection-counter-" + server.getLocalPort());
            acceptor.setDaemon(true);
            acceptor.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getLocalPort();
        }

        /** How many connections were accepted before a sentinel connection (the accept queue is FIFO). */
        int connectionsBeforeSentinel() throws IOException {
            try (Socket sentinel = new Socket(InetAddress.getByName("127.0.0.1"), server.getLocalPort())) {
                Integer port = sentinel.getLocalPort();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (true) {
                    synchronized (acceptedPorts) {
                        int at = acceptedPorts.indexOf(port);
                        if (at >= 0) {
                            return at;
                        }
                    }
                    if (System.nanoTime() > deadline) {
                        throw new AssertionError("the sentinel connection was not accepted within 5 seconds");
                    }
                    Thread.yield();
                }
            }
        }

        @Override
        public void close() throws IOException {
            server.close();
            try {
                acceptor.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
