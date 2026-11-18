package com.snoworca.naruserve.proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.testing.StubJsonCodec;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.NoRouteToHostException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import javax.net.ssl.SSLException;
import org.junit.After;
import org.junit.Test;

/**
 * Proxy retries (FR-PROXY-018): off by default; with {@code .retries(n)} a TCP-level connect failure is retried for
 * every method and a backend 502 or 503 only for a bodyless GET, HEAD or OPTIONS (any bodyless method under
 * {@code .retryUnsafeMethods(true)}), each retry on a new connection after {@code .retryDelay}, inside the total
 * timeout.
 */
public class ProxyRetryTest {

    /** How long a test waits for a response that must arrive. */
    private static final long WAIT_SECONDS = 20;

    private final InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
    private final RestApp app = RestApp.builder().httpAdapter(adapter).jsonCodec(new StubJsonCodec())
            .logger(new RecordingLogger()).build();
    private final List<Throwable> errors = Collections.synchronizedList(new ArrayList<Throwable>());
    private final List<Closeable> closeables = new ArrayList<>();
    private boolean started;
    private int routes;

    @After
    public void tearDown() throws IOException {
        BackendClient.plainSockets = BackendClient.SYSTEM_SOCKETS;
        if (started) {
            app.shutdown(2, TimeUnit.SECONDS);
        }
        for (Closeable c : closeables) {
            c.close();
        }
    }

    // ---- AC-1 ----

    // FR-PROXY-018 AC-1: Proxy.to(url) reports 0 retries, a 200-millisecond retry delay and retryUnsafeMethods false
    @Test
    public void fr_PROXY_018_AC1_theDefaultsAreNoRetryA200MillisecondDelayAndSafeMethodsOnly() {
        Proxy p = Proxy.to("http://127.0.0.1:8080");

        assertEquals(0, p.retries());
        assertEquals(TimeUnit.MILLISECONDS.toNanos(200), p.retryDelayNanos());
        assertFalse(p.retryUnsafeMethods());
    }

    // FR-PROXY-018 AC-1: a bodyless GET through Proxy.to(url) to a backend that always answers 503 arrives exactly
    // once, and through Proxy.to(url).retries(2).retries(0) also exactly once
    @Test(timeout = 30000)
    public void fr_PROXY_018_AC1_withoutRetriesA503RequestArrivesOnce() throws Exception {
        RawBackend byDefault = always(503);
        RawBackend setBackToZero = always(503);
        String a = mount(Proxy.to(byDefault.url()));
        String b = mount(Proxy.to(setBackToZero.url()).retries(2).retries(0));
        start();

        assertEquals(503, send(ClientRequest.of("GET", a + "/x")).status());
        assertEquals(503, send(ClientRequest.of("GET", b + "/x")).status());

        assertEquals(1, byDefault.requestCount());
        assertEquals(1, setBackToZero.requestCount());
    }

    // ---- AC-2 ----

    // FR-PROXY-018 AC-2: with .retries(2) and a backend that always answers 503, a bodyless GET arrives exactly 3 times
    // and the client receives the third 503 response unchanged
    @Test(timeout = 30000)
    public void fr_PROXY_018_AC2_aBodylessGetIsRetriedTwiceAndTheThird503IsRelayed() throws Exception {
        RawBackend backend = numbered(503);
        String path = mount(Proxy.to(backend.url()).retries(2).retryDelay(20, TimeUnit.MILLISECONDS));
        start();

        ClientResponse r = send(ClientRequest.of("GET", path + "/x"));

        assertEquals(3, backend.requestCount());
        assertEquals(503, r.status());
        assertEquals("attempt-2", r.text());
        assertEquals("2", r.header("X-Attempt"));
        assertEquals("errors that reached onError: " + errors, 0, errors.size());
    }

    // FR-PROXY-018 AC-2: a GET with Content-Length: 0 is bodyless too and arrives exactly 3 times
    @Test(timeout = 30000)
    public void fr_PROXY_018_AC2_aGetWithContentLengthZeroIsRetried() throws Exception {
        RawBackend backend = numbered(503);
        String path = mount(Proxy.to(backend.url()).retries(2).retryDelay(20, TimeUnit.MILLISECONDS));
        start();

        ClientResponse r = send(ClientRequest.of("GET", path + "/x").header("Content-Length", "0"));

        assertEquals(3, backend.requestCount());
        assertEquals("attempt-2", r.text());
    }

    // FR-PROXY-018 AC-2: a bodyless POST and a GET with a body each arrive exactly once
    @Test(timeout = 30000)
    public void fr_PROXY_018_AC2_aBodylessPostAndAGetWithABodyAreNotRetried() throws Exception {
        RawBackend posts = numbered(503);
        RawBackend gets = numbered(503);
        String p = mount(Proxy.to(posts.url()).retries(2).retryDelay(20, TimeUnit.MILLISECONDS));
        String g = mount(Proxy.to(gets.url()).retries(2).retryDelay(20, TimeUnit.MILLISECONDS));
        start();

        ClientResponse post = send(ClientRequest.of("POST", p + "/x"));
        ClientResponse get = send(ClientRequest.of("GET", g + "/x").header("Content-Length", "5")
                .body("hello".getBytes(StandardCharsets.ISO_8859_1)));

        assertEquals(1, posts.requestCount());
        assertEquals(1, gets.requestCount());
        assertEquals("attempt-0", post.text());
        assertEquals("attempt-0", get.text());
        assertEquals("hello", new String(gets.onlyRequest().body, StandardCharsets.ISO_8859_1));
    }

    // FR-PROXY-018 AC-2 (FR-PROXY-019, REL-PROXY-001): on a .maxConcurrent(1) mount the retries of one request take no
    // second slot, every retried connection is closed by the proxy, and the next request still gets the slot
    @Test(timeout = 30000)
    public void fr_PROXY_018_AC2_retriesKeepTheOneSlotAndCloseEveryRetriedConnection() throws Exception {
        RawBackend backend = numbered(503);
        String path = mount(Proxy.to(backend.url()).retries(2).maxConcurrent(1)
                .retryDelay(20, TimeUnit.MILLISECONDS));
        start();

        ClientResponse first = send(ClientRequest.of("GET", path + "/x"));
        ClientResponse second = send(ClientRequest.of("GET", path + "/x"));

        assertEquals(503, first.status());
        assertEquals("attempt-2", first.text());
        assertEquals(503, second.status());
        assertEquals("attempt-5", second.text());
        assertEquals(6, backend.requestCount());
        assertEquals("errors that reached onError: " + errors, 0, errors.size());
    }

    // ---- AC-3 ----

    // FR-PROXY-018 AC-3: with .retries(2) and a backend that always answers 502, a bodyless HEAD and a bodyless OPTIONS
    // each arrive exactly 3 times
    @Test(timeout = 30000)
    public void fr_PROXY_018_AC3_bodylessHeadAndOptionsAreRetriedOn502() throws Exception {
        RawBackend heads = numbered(502);
        RawBackend options = numbered(502);
        String h = mount(Proxy.to(heads.url()).retries(2).retryDelay(20, TimeUnit.MILLISECONDS));
        String o = mount(Proxy.to(options.url()).retries(2).retryDelay(20, TimeUnit.MILLISECONDS));
        start();

        ClientResponse head = send(ClientRequest.of("HEAD", h + "/x"));
        ClientResponse opt = send(ClientRequest.of("OPTIONS", o + "/x"));

        assertEquals(3, heads.requestCount());
        assertEquals(3, options.requestCount());
        assertEquals(502, head.status());
        assertEquals(502, opt.status());
        assertEquals("attempt-2", opt.text());
    }

    // ---- AC-4 ----

    // FR-PROXY-018 AC-4: with .retries(2) a GET arrives exactly once when the backend answers 504
    @Test(timeout = 30000)
    public void fr_PROXY_018_AC4_a504IsNotRetried() throws Exception {
        RawBackend backend = numbered(504);
        String path = mount(Proxy.to(backend.url()).retries(2).retryDelay(20, TimeUnit.MILLISECONDS));
        start();

        ClientResponse r = send(ClientRequest.of("GET", path + "/x"));

        assertEquals(504, r.status());
        assertEquals(1, backend.requestCount());
    }

    // FR-PROXY-018 AC-4: with .retries(2) a GET arrives exactly once when the backend never answers and the read
    // timeout passes
    @Test(timeout = 30000)
    public void fr_PROXY_018_AC4_aReadTimeoutIsNotRetried() throws Exception {
        HoldingServer backend = holding();
        String path = mount(Proxy.to("http://127.0.0.1:" + backend.port()).retries(2)
                .retryDelay(20, TimeUnit.MILLISECONDS).readTimeout(500, TimeUnit.MILLISECONDS));
        start();

        ClientResponse r = send(ClientRequest.of("GET", path + "/x"));

        assertEquals(504, r.status());
        assertSame(ProxyException.Kind.READ_TIMEOUT, onlyProxyError().kind());
        // Time for a retry that should not happen to reach the backend.
        Thread.sleep(300);
        assertEquals(1, backend.connections());
    }

    // FR-PROXY-018 AC-4: with .retries(2) a GET arrives exactly once when the backend sends a malformed response
    // (INVALID_RESPONSE)
    @Test(timeout = 30000)
    public void fr_PROXY_018_AC4_anInvalidResponseIsNotRetried() throws Exception {
        RawBackend backend = track(RawBackend.answering("not an http response\r\n\r\n"));
        String path = mount(Proxy.to(backend.url()).retries(2).retryDelay(20, TimeUnit.MILLISECONDS));
        start();

        ClientResponse r = send(ClientRequest.of("GET", path + "/x"));

        assertEquals(502, r.status());
        assertSame(ProxyException.Kind.INVALID_RESPONSE, onlyProxyError().kind());
        Thread.sleep(300);
        assertEquals(1, backend.requestCount());
    }

    // ---- AC-5 ----

    // FR-PROXY-018 AC-5: with .retries(2) and no backend listening, a POST with a body is attempted with exactly 3
    // connection attempts and the client receives 502 with kind() CONNECT_FAILED
    @Test(timeout = 30000)
    public void fr_PROXY_018_AC5_aRefusedConnectionIsRetriedForAPostWithABody() throws Exception {
        AtomicInteger attempts = countConnectAttempts();
        String path = mount(Proxy.to("http://127.0.0.1:" + closedPort()).retries(2)
                .retryDelay(20, TimeUnit.MILLISECONDS));
        start();

        ClientResponse r = send(ClientRequest.of("POST", path + "/x").header("Content-Length", "5")
                .body("hello".getBytes(StandardCharsets.ISO_8859_1)));

        assertEquals(502, r.status());
        assertSame(ProxyException.Kind.CONNECT_FAILED, onlyProxyError().kind());
        assertEquals(3, attempts.get());
    }

    // FR-PROXY-018 AC-5: the body of a POST whose first connection was refused is sent whole over the connection of
    // the retry, since a connect failure comes before any body byte is sent
    @Test(timeout = 30000)
    public void fr_PROXY_018_AC5_theBodyOfARetriedPostArrivesWhole() throws Exception {
        RawBackend backend = always(200);
        final AtomicInteger attempts = new AtomicInteger();
        BackendClient.plainSockets = () -> new Socket() {
            @Override
            public void connect(SocketAddress endpoint, int timeout) throws IOException {
                if (attempts.getAndIncrement() == 0) {
                    throw new ConnectException("Connection refused (simulated)");
                }
                super.connect(endpoint, timeout);
            }
        };
        String path = mount(Proxy.to(backend.url()).retries(2).retryDelay(20, TimeUnit.MILLISECONDS));
        start();

        ClientResponse r = send(ClientRequest.of("POST", path + "/x").body("hello".getBytes(StandardCharsets.ISO_8859_1)));

        assertEquals(200, r.status());
        assertEquals(2, attempts.get());
        assertEquals("hello", new String(backend.onlyRequest().body, StandardCharsets.ISO_8859_1));
    }

    // ---- AC-6 ----

    // FR-PROXY-018 AC-6: a connect failure caused by ConnectException, UnknownHostException or NoRouteToHostException
    // is retryable; one caused by a connect SocketTimeoutException or an SSLException is not
    @Test
    public void fr_PROXY_018_AC6_theRetryDecisionClassifiesConnectFailuresByCause() {
        assertTrue(RetryPolicy.retriesConnectFailure(
                new ProxyException(ProxyException.Kind.CONNECT_FAILED, new ConnectException("refused"))));
        assertTrue(RetryPolicy.retriesConnectFailure(
                new ProxyException(ProxyException.Kind.CONNECT_FAILED, new UnknownHostException("backend.invalid"))));
        assertTrue(RetryPolicy.retriesConnectFailure(
                new ProxyException(ProxyException.Kind.CONNECT_FAILED, new NoRouteToHostException("no route"))));

        assertFalse(RetryPolicy.retriesConnectFailure(
                new ProxyException(ProxyException.Kind.CONNECT_TIMEOUT, new SocketTimeoutException("connect"))));
        assertFalse(RetryPolicy.retriesConnectFailure(
                new ProxyException(ProxyException.Kind.CONNECT_FAILED, new SocketTimeoutException("connect"))));
        assertFalse(RetryPolicy.retriesConnectFailure(
                new ProxyException(ProxyException.Kind.CONNECT_FAILED, new SSLException("handshake"))));
        assertFalse(RetryPolicy.retriesConnectFailure(
                new ProxyException(ProxyException.Kind.CONNECT_FAILED, null)));
        assertFalse(RetryPolicy.retriesConnectFailure(
                new ProxyException(ProxyException.Kind.READ_TIMEOUT, new SocketTimeoutException("read"))));
    }

    // ---- AC-7 ----

    // FR-PROXY-018 AC-7: with .retries(2).connectTimeout(1 s) and an https backend that accepts TCP and never answers
    // the TLS handshake, the backend sees exactly one TCP connection and the client receives 504 CONNECT_TIMEOUT
    @Test(timeout = 30000)
    public void fr_PROXY_018_AC7_aTlsHandshakeTimeoutIsNotRetried() throws Exception {
        HoldingServer backend = holding();
        String path = mount(Proxy.to("https://127.0.0.1:" + backend.port()).retries(2)
                .retryDelay(20, TimeUnit.MILLISECONDS).connectTimeout(1, TimeUnit.SECONDS));
        start();

        ClientResponse r = send(ClientRequest.of("GET", path + "/x"));

        assertEquals(504, r.status());
        assertSame(ProxyException.Kind.CONNECT_TIMEOUT, onlyProxyError().kind());
        Thread.sleep(300);
        assertEquals(1, backend.connections());
    }

    // ---- AC-8 ----

    // FR-PROXY-018 AC-8: with .retryUnsafeMethods(true).retries(1) and a backend that always answers 503, a bodyless
    // DELETE arrives exactly twice and a DELETE with a body exactly once
    @Test(timeout = 30000)
    public void fr_PROXY_018_AC8_unsafeMethodsAreRetriedOnlyWhenBodyless() throws Exception {
        RawBackend bodyless = numbered(503);
        RawBackend withBody = numbered(503);
        String a = mount(Proxy.to(bodyless.url()).retryUnsafeMethods(true).retries(1)
                .retryDelay(20, TimeUnit.MILLISECONDS));
        String b = mount(Proxy.to(withBody.url()).retryUnsafeMethods(true).retries(1)
                .retryDelay(20, TimeUnit.MILLISECONDS));
        start();

        ClientResponse first = send(ClientRequest.of("DELETE", a + "/x"));
        ClientResponse second = send(ClientRequest.of("DELETE", b + "/x").header("Content-Length", "2")
                .body("ok".getBytes(StandardCharsets.ISO_8859_1)));

        assertEquals(2, bodyless.requestCount());
        assertEquals("attempt-1", first.text());
        assertEquals(1, withBody.requestCount());
        assertEquals("attempt-0", second.text());
    }

    // FR-PROXY-018 AC-8 (AC-2): without .retryUnsafeMethods(true) a bodyless DELETE arrives once under .retries(1)
    @Test(timeout = 30000)
    public void fr_PROXY_018_AC8_withoutTheSwitchABodylessDeleteIsNotRetried() throws Exception {
        RawBackend backend = numbered(503);
        String path = mount(Proxy.to(backend.url()).retries(1).retryDelay(20, TimeUnit.MILLISECONDS));
        start();

        send(ClientRequest.of("DELETE", path + "/x"));

        assertEquals(1, backend.requestCount());
    }

    // ---- AC-9 ----

    // FR-PROXY-018 AC-9: between two arrivals of the same retried request the backend observes at least 200
    // milliseconds when .retryDelay is not set
    @Test(timeout = 30000)
    public void fr_PROXY_018_AC9_theDefaultDelayIsAtLeast200Milliseconds() throws Exception {
        assertRetryGapAtLeast(p -> p.retries(1), 200);
    }

    // FR-PROXY-018 AC-9: with .retryDelay(300, MILLISECONDS) the backend observes at least 300 milliseconds between
    // two arrivals
    @Test(timeout = 30000)
    public void fr_PROXY_018_AC9_aConfiguredDelayIsObserved() throws Exception {
        assertRetryGapAtLeast(p -> p.retries(1).retryDelay(300, TimeUnit.MILLISECONDS), 300);
    }

    // FR-PROXY-018 AC-9 (REL-PROXY-001): a request whose worker thread is interrupted during the wait before a retry
    // makes no further attempt, so its retries never reach the backend back to back without the delay; the client
    // receives the last failure
    @Test(timeout = 30000)
    public void fr_PROXY_018_AC9_anInterruptedWaitEndsTheRetries() throws Exception {
        final AtomicInteger attempts = new AtomicInteger();
        final List<Thread> workers = Collections.synchronizedList(new ArrayList<Thread>());
        BackendClient.plainSockets = () -> new Socket() {
            @Override
            public void connect(SocketAddress endpoint, int timeout) throws IOException {
                if (attempts.getAndIncrement() == 0) {
                    final Thread worker = Thread.currentThread();
                    workers.add(worker);
                    Thread interrupter = new Thread(() -> {
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            return;
                        }
                        worker.interrupt();
                    });
                    interrupter.setDaemon(true);
                    interrupter.start();
                }
                throw new ConnectException("Connection refused (simulated)");
            }
        };
        String path = mount(Proxy.to("http://127.0.0.1:" + closedPort()).retries(3)
                .retryDelay(400, TimeUnit.MILLISECONDS));
        start();

        ClientResponse r = send(ClientRequest.of("GET", path + "/x"));

        assertEquals(502, r.status());
        assertSame(ProxyException.Kind.CONNECT_FAILED, onlyProxyError().kind());
        assertEquals("connection attempts", 1, attempts.get());
    }

    // FR-PROXY-018 AC-2 (REL-PROXY-001, FR-PROXY-019): the connection of a retried 503 is closed by the proxy before
    // the retry, even when the backend answered with Connection: keep-alive and keeps it open
    @Test(timeout = 30000)
    public void fr_PROXY_018_AC2_theConnectionOfARetried503IsClosedByTheProxy() throws Exception {
        ProxyBackendConnectionTest.ConnectionBackend backend = track(new ProxyBackendConnectionTest.ConnectionBackend(
                "HTTP/1.1 503 X\r\nConnection: keep-alive\r\nContent-Length: 4\r\n\r\nbusy", true));
        String path = mount(Proxy.to(backend.url()).retries(2).retryDelay(20, TimeUnit.MILLISECONDS));
        start();

        ClientResponse r = send(ClientRequest.of("GET", path + "/x"));

        assertEquals(503, r.status());
        assertEquals("busy", r.text());
        for (int i = 0; i < 3; i++) {
            assertTrue("connection " + i + " closed by the proxy",
                    backend.connection(i).closedByProxy.await(3, TimeUnit.SECONDS));
        }
        assertEquals(3, backend.connectionsSnapshot().size());
    }

    // ---- AC-10 ----

    // FR-PROXY-018 AC-10: with .retries(5).retryDelay(1 s).totalTimeout(2500 ms) and a backend that keeps refusing
    // connections, exactly 3 connection attempts are made and the client receives 502 CONNECT_FAILED right after the
    // third failure, about 2 seconds after the request started, without waiting for a fourth attempt
    @Test(timeout = 30000)
    public void fr_PROXY_018_AC10_aWaitThatWouldPassTheTotalDeadlineIsSkipped() throws Exception {
        AtomicInteger attempts = countConnectAttempts();
        String path = mount(Proxy.to("http://127.0.0.1:" + closedPort()).retries(5)
                .retryDelay(1, TimeUnit.SECONDS).totalTimeout(2500, TimeUnit.MILLISECONDS));
        start();

        long begin = System.nanoTime();
        ClientResponse r = send(ClientRequest.of("GET", path + "/x"));
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);

        assertEquals(502, r.status());
        assertSame(ProxyException.Kind.CONNECT_FAILED, onlyProxyError().kind());
        assertEquals(3, attempts.get());
        assertTrue("answered after " + elapsedMillis + " ms", elapsedMillis >= 1900);
        assertTrue("answered after " + elapsedMillis + " ms", elapsedMillis < 2450);
    }

    // ---- AC-11 ----

    // FR-PROXY-018 AC-11: with Proxy p = Proxy.to(url).retries(2) mounted on /a and p.retries(0) on /b, a bodyless GET
    // to a backend that always answers 503 arrives 3 times through /a and once through /b
    @Test(timeout = 30000)
    public void fr_PROXY_018_AC11_aDerivedProxyKeepsItsOwnRetryCount() throws Exception {
        RawBackend backend = numbered(503);
        Proxy p = Proxy.to(backend.url()).retries(2).retryDelay(20, TimeUnit.MILLISECONDS);
        app.use("/a", p);
        app.use("/b", p.retries(0));
        start();

        send(ClientRequest.of("GET", "/a/x"));
        assertEquals(3, backend.requestCount());
        send(ClientRequest.of("GET", "/b/x"));
        assertEquals(4, backend.requestCount());
    }

    // ---- helpers ----

    /**
     * Sends a bodyless GET through {@code Proxy.to(backend)} with {@code options} applied, to a backend that always
     * answers 503, and checks the gap between the two arrivals.
     */
    private void assertRetryGapAtLeast(UnaryOperator<Proxy> options, long minimumMillis) throws Exception {
        final List<Long> arrivals = Collections.synchronizedList(new ArrayList<Long>());
        RawBackend backend = track(RawBackend.start((index, request, out) -> {
            arrivals.add(System.nanoTime());
            out.write(RawBackend.response(503, "busy").getBytes(StandardCharsets.ISO_8859_1));
        }));
        Proxy aimed = options.apply(Proxy.to(backend.url()));
        String path = mount(aimed);
        start();

        send(ClientRequest.of("GET", path + "/x"));

        assertEquals(2, arrivals.size());
        long gapMillis = TimeUnit.NANOSECONDS.toMillis(arrivals.get(1) - arrivals.get(0));
        assertTrue("gap " + gapMillis + " ms", gapMillis >= minimumMillis);
    }

    /** A backend that answers every request with {@code status}, a body and a header naming the 0-based attempt. */
    private RawBackend numbered(int status) throws IOException {
        return track(RawBackend.start((index, request, out) -> {
            String body = "attempt-" + index;
            out.write(("HTTP/1.1 " + status + " X\r\nX-Attempt: " + index + "\r\nContent-Length: " + body.length()
                    + "\r\n\r\n" + body).getBytes(StandardCharsets.ISO_8859_1));
        }));
    }

    private RawBackend always(int status) throws IOException {
        return track(RawBackend.answering(RawBackend.response(status, "s" + status)));
    }

    private <T extends Closeable> T track(T c) {
        closeables.add(c);
        return c;
    }

    private HoldingServer holding() throws IOException {
        return track(new HoldingServer());
    }

    /** Counts the connect calls of every plain backend socket from now on. */
    private static AtomicInteger countConnectAttempts() {
        final AtomicInteger attempts = new AtomicInteger();
        BackendClient.plainSockets = () -> new Socket() {
            @Override
            public void connect(SocketAddress endpoint, int timeout) throws IOException {
                attempts.incrementAndGet();
                super.connect(endpoint, timeout);
            }
        };
        return attempts;
    }

    private String mount(Proxy proxy) {
        String path = "/m" + routes++;
        app.use(path, proxy);
        return path;
    }

    private void start() {
        app.onError((error, req, res) -> {
            errors.add(error);
            res.status(error instanceof ProxyException ? ((ProxyException) error).status() : 500).send("");
        });
        app.listen(0);
        started = true;
    }

    private ClientResponse send(ClientRequest request) throws Exception {
        return adapter.submit(request).get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    private ProxyException onlyProxyError() {
        synchronized (errors) {
            assertEquals("errors that reached onError: " + errors, 1, errors.size());
            Throwable t = errors.get(0);
            assertTrue("a ProxyException: " + t, t instanceof ProxyException);
            return (ProxyException) t;
        }
    }

    /** A loopback port no backend listens on: bound once to find it, then closed. */
    private static int closedPort() throws IOException {
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return s.getLocalPort();
        }
    }

    /**
     * A loopback server that accepts every connection, counts it, reads whatever arrives and never answers; each
     * connection is held on its own thread until the server closes.
     */
    static final class HoldingServer implements Closeable {
        private final ServerSocket server;
        private final AtomicInteger connections = new AtomicInteger();
        private final List<Socket> sockets = Collections.synchronizedList(new ArrayList<Socket>());
        private final CountDownLatch closed = new CountDownLatch(1);

        HoldingServer() throws IOException {
            server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
            Thread acceptor = new Thread(this::acceptLoop, "holding-backend-" + server.getLocalPort());
            acceptor.setDaemon(true);
            acceptor.start();
        }

        int port() {
            return server.getLocalPort();
        }

        int connections() {
            return connections.get();
        }

        private void acceptLoop() {
            while (!server.isClosed()) {
                try {
                    final Socket s = server.accept();
                    connections.incrementAndGet();
                    sockets.add(s);
                    Thread reader = new Thread(() -> drain(s), "holding-connection");
                    reader.setDaemon(true);
                    reader.start();
                } catch (IOException e) {
                    // the server socket was closed
                }
            }
        }

        private void drain(Socket s) {
            try {
                InputStream in = s.getInputStream();
                byte[] buffer = new byte[1024];
                while (in.read(buffer) >= 0) {
                    // read and drop, never answer
                }
                closed.await(10, TimeUnit.SECONDS);
            } catch (IOException | InterruptedException e) {
                // the connection ended
            }
        }

        @Override
        public void close() throws IOException {
            closed.countDown();
            server.close();
            synchronized (sockets) {
                for (Socket s : sockets) {
                    s.close();
                }
            }
        }
    }
}
