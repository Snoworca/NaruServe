package com.snoworca.naruserve.proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.InMemoryWebSocketChannel;
import com.snoworca.naruserve.testing.TestApp;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Test;

/**
 * The proxy's method handling (FR-PROXY-009): the method token goes out as received, a token that is not an RFC 9110
 * token is 400, CONNECT and TRACE are 405 with {@code Allow}, a WebSocket upgrade goes to {@code next()} without a
 * backend connection, another {@code Upgrade} is forwarded without that header, and a forwarded request never reaches
 * a later middleware.
 */
public class ProxyMethodTest {

    private static final String ALLOW = "GET, HEAD, POST, PUT, DELETE, PATCH, OPTIONS";

    private final InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
    private final TestApp t = TestApp.create(adapter);
    private final RestApp app = t.app();
    private final List<Closeable> closeables = new ArrayList<>();
    private boolean started;

    @After
    public void tearDown() throws IOException {
        if (started) {
            app.shutdown(2, TimeUnit.SECONDS);
        }
        for (Closeable c : closeables) {
            c.close();
        }
    }

    private void start() {
        t.start();
        started = true;
    }

    /** A backend that answers 200 with body {@code ok} and records what it received. */
    private RawBackend okBackend() throws IOException {
        RawBackend b = RawBackend.answering(RawBackend.response(200, "ok"));
        closeables.add(b);
        return b;
    }

    private ConnectionCounter counter() throws IOException {
        ConnectionCounter c = new ConnectionCounter();
        closeables.add(c);
        return c;
    }

    /** Mounts a proxy to a connection-counting backend at /api/orders, sends {@code request}, returns the response. */
    private ClientResponse sendToCountedBackend(ConnectionCounter backend, ClientRequest request) {
        app.use("/api/orders", Proxy.to(backend.url()));
        start();
        return adapter.send(request);
    }

    private static void assertMethodNotAllowed(ClientResponse r) {
        assertEquals("status", 405, r.status());
        assertEquals("Allow", ALLOW, r.header("Allow"));
    }

    /** Every header line of {@code got} whose name is {@code name}, compared case-insensitively. */
    private static List<String> linesNamed(RawBackend.Received got, String name) {
        List<String> lines = new ArrayList<>();
        for (String line : got.headerLines()) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase(name)) {
                lines.add(line);
            }
        }
        return lines;
    }

    // ---- AC-1 ----

    // FR-PROXY-009 AC-1: PATCH, PROPFIND and PURGE arrive at the backend with the same method token over HTTP/1.1
    @Test
    public void fr_PROXY_009_AC1_standardAndExtensionMethodsArriveWithTheSameToken() throws IOException {
        RawBackend backend = okBackend();
        app.use("/api/orders", Proxy.to(backend.url()));
        start();

        for (String method : Arrays.asList("PATCH", "PROPFIND", "PURGE")) {
            ClientResponse r = adapter.send(ClientRequest.of(method, "/api/orders/x"));
            assertEquals(method + " status", 200, r.status());
            assertEquals(method, "ok", r.text());
        }

        List<String> lines = new ArrayList<>();
        for (RawBackend.Received got : backend.requests()) {
            lines.add(got.requestLine());
        }
        assertEquals(Arrays.asList("PATCH /api/orders/x HTTP/1.1", "PROPFIND /api/orders/x HTTP/1.1",
                "PURGE /api/orders/x HTTP/1.1"), lines);
    }

    // ---- AC-2 ----

    // FR-PROXY-009 AC-2: a method that is not an RFC 9110 token (GE(T through the in-memory adapter) is 400 and no
    // backend connection is opened
    @Test
    public void fr_PROXY_009_AC2_aMethodThatIsNotATokenIs400WithoutABackendConnection() throws IOException {
        ConnectionCounter backend = counter();

        ClientResponse r = sendToCountedBackend(backend, ClientRequest.of("GE(T", "/api/orders/x"));

        assertEquals("status", 400, r.status());
        assertEquals("backend connections opened", 0, backend.connectionsBeforeSentinel());
    }

    // ---- AC-3 ----

    // FR-PROXY-009 AC-3: origin-form CONNECT /api/orders/x is 405 with Allow and no backend connection is opened
    @Test
    public void fr_PROXY_009_AC3_originFormConnectIs405WithAllow() throws IOException {
        ConnectionCounter backend = counter();

        ClientResponse r = sendToCountedBackend(backend, ClientRequest.of("CONNECT", "/api/orders/x"));

        assertMethodNotAllowed(r);
        assertEquals("backend connections opened", 0, backend.connectionsBeforeSentinel());
    }

    // FR-PROXY-009 AC-3: TRACE /api/orders/x is 405 with Allow and no backend connection is opened
    @Test
    public void fr_PROXY_009_AC3_traceIs405WithAllow() throws IOException {
        ConnectionCounter backend = counter();

        ClientResponse r = sendToCountedBackend(backend, ClientRequest.of("TRACE", "/api/orders/x"));

        assertMethodNotAllowed(r);
        assertEquals("backend connections opened", 0, backend.connectionsBeforeSentinel());
    }

    // FR-PROXY-009 AC-3: lower-case trace is matched case-insensitively, 405 with Allow and no backend connection
    @Test
    public void fr_PROXY_009_AC3_lowerCaseTraceIs405WithAllow() throws IOException {
        ConnectionCounter backend = counter();

        ClientResponse r = sendToCountedBackend(backend, ClientRequest.of("trace", "/api/orders/x"));

        assertMethodNotAllowed(r);
        assertEquals("backend connections opened", 0, backend.connectionsBeforeSentinel());
    }

    // ---- AC-4 ----

    // FR-PROXY-009 AC-4: a WebSocket upgrade makes the proxy call next() and opens no backend connection
    @Test
    public void fr_PROXY_009_AC4_aWebSocketUpgradeGoesToNextWithoutABackendConnection() throws IOException {
        ConnectionCounter backend = counter();
        app.use("/api/orders", Proxy.to(backend.url()));
        app.use("/api/orders", (req, res, next) -> res.status(200).send("after proxy"));
        start();

        ClientResponse r = adapter.send(ClientRequest.upgrade("/api/orders/x"));

        assertEquals("status", 200, r.status());
        assertEquals("after proxy", r.text());
        assertEquals("backend connections opened", 0, backend.connectionsBeforeSentinel());
    }

    // FR-PROXY-009 AC-4: the upgrade is judged with each header's lines joined by ", ", as Router.isWebSocketUpgrade
    // judges it: the upgrade token is on the first Connection line and websocket on the last Upgrade line, so reading
    // only the first or only the last line of each header misses one of them
    @Test
    public void fr_PROXY_009_AC4_anUpgradeSplitOverHeaderLinesIsJudgedOnTheJoinedLines() throws IOException {
        ConnectionCounter backend = counter();
        app.use("/api/orders", Proxy.to(backend.url()));
        app.use("/api/orders", (req, res, next) -> res.status(200).send("after proxy"));
        start();

        ClientResponse r = adapter.send(ClientRequest.of("GET", "/api/orders/x")
                .header("Connection", "Upgrade").header("Upgrade", "h2c").header("connection", "keep-alive")
                .header("upgrade", "WebSocket"));

        assertEquals("status", 200, r.status());
        assertEquals("after proxy", r.text());
        assertEquals("backend connections opened", 0, backend.connectionsBeforeSentinel());
    }

    // ---- AC-5 ----

    /** Sends a WebSocket upgrade to /api/orders/live, closes from the peer and returns the response. */
    private ClientResponse upgradeToLive() throws Exception {
        Future<ClientResponse> f = adapter.submit(ClientRequest.upgrade("/api/orders/live"));
        InMemoryWebSocketChannel channel = adapter.nextChannel(5, TimeUnit.SECONDS);
        channel.clientClose(1000, "");
        return f.get(10, TimeUnit.SECONDS);
    }

    // FR-PROXY-009 AC-5: with the proxy registered before app.ws, the upgrade reaches the app.ws handler, never the
    // backend
    @Test(timeout = 20000)
    public void fr_PROXY_009_AC5_proxyBeforeWsRouteLetsTheUpgradeReachTheWsHandler() throws Exception {
        ConnectionCounter backend = counter();
        AtomicInteger handled = new AtomicInteger();
        app.use("/api/orders", Proxy.to(backend.url()));
        app.ws("/api/orders/live", (req, ws) -> handled.incrementAndGet());
        start();

        ClientResponse r = upgradeToLive();

        assertEquals("status", 101, r.status());
        assertEquals("app.ws handler runs", 1, handled.get());
        assertEquals("backend connections opened", 0, backend.connectionsBeforeSentinel());
    }

    // FR-PROXY-009 AC-5: with app.ws registered before the proxy, the upgrade reaches the app.ws handler, never the
    // backend
    @Test(timeout = 20000)
    public void fr_PROXY_009_AC5_wsRouteBeforeProxyLetsTheUpgradeReachTheWsHandler() throws Exception {
        ConnectionCounter backend = counter();
        AtomicInteger handled = new AtomicInteger();
        app.ws("/api/orders/live", (req, ws) -> handled.incrementAndGet());
        app.use("/api/orders", Proxy.to(backend.url()));
        start();

        ClientResponse r = upgradeToLive();

        assertEquals("status", 101, r.status());
        assertEquals("app.ws handler runs", 1, handled.get());
        assertEquals("backend connections opened", 0, backend.connectionsBeforeSentinel());
    }

    // ---- AC-6 ----

    // FR-PROXY-009 AC-6: a GET with Upgrade: h2c and Connection: Upgrade, HTTP2-Settings arrives as an HTTP/1.1
    // request without Upgrade and HTTP2-Settings, and no HTTP/2 preface is sent to the backend
    @Test
    public void fr_PROXY_009_AC6_anH2cUpgradeIsForwardedWithoutUpgradeAndHttp2Settings() throws IOException {
        RawBackend backend = okBackend();
        app.use("/api/orders", Proxy.to(backend.url()));
        start();

        ClientResponse r = adapter.send(ClientRequest.of("GET", "/api/orders/x").header("Upgrade", "h2c")
                .header("Connection", "Upgrade, HTTP2-Settings").header("HTTP2-Settings", "AAMAAABkAARAAAAAAAIAAAAA"));

        assertEquals("status", 200, r.status());
        assertEquals("ok", r.text());
        RawBackend.Received got = backend.onlyRequest();
        assertEquals("GET /api/orders/x HTTP/1.1", got.requestLine());
        assertEquals("Upgrade lines", Collections.<String>emptyList(), linesNamed(got, "Upgrade"));
        assertEquals("HTTP2-Settings lines", Collections.<String>emptyList(), linesNamed(got, "HTTP2-Settings"));
        assertEquals("Connection lines", Collections.singletonList("Connection: close"), linesNamed(got, "Connection"));
        assertEquals("no body, so no HTTP/2 preface after the head", 0, got.body.length);
        assertFalse("no HTTP/2 preface", got.head.startsWith("PRI "));
    }

    // FR-PROXY-009 AC-6: a POST carrying Upgrade: websocket is not a WebSocket upgrade; it arrives with its body and
    // without Upgrade
    @Test
    public void fr_PROXY_009_AC6_aPostWithUpgradeWebsocketIsForwardedWithoutUpgrade() throws IOException {
        RawBackend backend = okBackend();
        app.use("/api/orders", Proxy.to(backend.url()));
        start();

        ClientResponse r = adapter.send(ClientRequest.of("POST", "/api/orders/x").header("Connection", "Upgrade")
                .header("Upgrade", "websocket").header("Content-Length", "5")
                .body("hello".getBytes(StandardCharsets.ISO_8859_1)));

        assertEquals("status", 200, r.status());
        RawBackend.Received got = backend.onlyRequest();
        assertEquals("POST /api/orders/x HTTP/1.1", got.requestLine());
        assertEquals("Upgrade lines", Collections.<String>emptyList(), linesNamed(got, "Upgrade"));
        assertEquals("HTTP2-Settings lines", Collections.<String>emptyList(), linesNamed(got, "HTTP2-Settings"));
        assertEquals("hello", new String(got.body, StandardCharsets.ISO_8859_1));
    }

    // ---- AC-7 ----

    // FR-PROXY-009 AC-7: with two proxies on /api/orders, the first-registered backend's 404 is the answer and the
    // second backend is never contacted
    @Test
    public void fr_PROXY_009_AC7_aBackend404DoesNotFallThroughToTheSecondProxy() throws IOException {
        RawBackend first = RawBackend.answering(RawBackend.response(404, "nf"));
        closeables.add(first);
        ConnectionCounter second = counter();
        app.use("/api/orders", Proxy.to(first.url()));
        app.use("/api/orders", Proxy.to(second.url()));
        start();

        ClientResponse r = adapter.send(ClientRequest.of("GET", "/api/orders/x"));

        assertEquals("status", 404, r.status());
        assertEquals("nf", r.text());
        assertEquals("first backend requests", 1, first.requestCount());
        assertEquals("second backend connections", 0, second.connectionsBeforeSentinel());
    }

    // FR-PROXY-009 AC-7: with two proxies on /api/orders, an unreachable first backend is answered through the error
    // flow and the second backend is never contacted
    @Test
    public void fr_PROXY_009_AC7_anUnreachableFirstBackendDoesNotFallThroughToTheSecondProxy() throws IOException {
        int closedPort;
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            closedPort = s.getLocalPort();
        }
        ConnectionCounter second = counter();
        app.use("/api/orders", Proxy.to("http://127.0.0.1:" + closedPort).connectTimeout(2, TimeUnit.SECONDS));
        app.use("/api/orders", Proxy.to(second.url()));
        start();

        ClientResponse r = adapter.send(ClientRequest.of("GET", "/api/orders/x"));

        assertTrue("an error status from the first proxy, got " + r.status(), r.status() >= 500);
        assertEquals("second backend connections", 0, second.connectionsBeforeSentinel());
    }

    /** A listener that only counts the connections it accepts. */
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

        /**
         * Connects a sentinel, waits until the acceptor took it, and returns how many connections it accepted before
         * the sentinel: the accept queue is first-in first-out, so every connection the proxy completed earlier is
         * counted.
         */
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
