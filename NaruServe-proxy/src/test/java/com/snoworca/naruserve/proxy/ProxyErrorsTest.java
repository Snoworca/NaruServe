package com.snoworca.naruserve.proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.Middleware;
import com.snoworca.naruserve.Request;
import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.testing.StubJsonCodec;
import com.snoworca.naruserve.tinyhttpd.TinyHttpdAdapter;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.After;
import org.junit.Test;

/**
 * Backend failures before the response is relayed (FR-PROXY-015): a connection that cannot be opened is
 * {@code CONNECT_FAILED}, a response that never completes its header block is {@code INVALID_RESPONSE}, both 502
 * through the error flow without a body or a log entry of the proxy's own; an early backend response that arrives
 * while the request body is still being written is relayed on a best-effort basis, and a write ended by the write or
 * total deadline keeps that deadline's kind.
 */
public class ProxyErrorsTest {

    /** How long a test waits for a request or an event that must happen. */
    private static final long WAIT_SECONDS = 20;
    private static final String UNAUTHORIZED =
            "HTTP/1.1 401 Unauthorized\r\nContent-Length: 12\r\nContent-Type: text/plain\r\n\r\nunauthorized";

    private final InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
    private final RecordingLogger log = new RecordingLogger();
    private final RestApp app =
            RestApp.builder().httpAdapter(adapter).jsonCodec(new StubJsonCodec()).logger(log).build();
    private final List<Throwable> errors = Collections.synchronizedList(new ArrayList<Throwable>());
    private final List<Closeable> closeables = new ArrayList<>();
    private final List<RestApp> started = new ArrayList<>();
    /** Released at tear-down: scripts that hold their connection wait on it. */
    private final CountDownLatch hold = new CountDownLatch(1);
    private int routes;

    @After
    public void tearDown() throws IOException {
        BackendClient.plainSockets = BackendClient.SYSTEM_SOCKETS;
        BackendClient.resolver = BackendClient.SYSTEM_RESOLVER;
        hold.countDown();
        for (RestApp a : started) {
            a.shutdown(2, TimeUnit.SECONDS);
        }
        for (Closeable c : closeables) {
            c.close();
        }
    }

    // ---- AC-1, AC-2 ----

    // FR-PROXY-015 AC-1: when no backend listens on the target port, the client receives 502 and onError receives a
    // ProxyException of kind CONNECT_FAILED whose cause is a java.net.ConnectException
    @Test(timeout = 30000)
    public void fr_PROXY_015_AC1_aRefusedConnectionIsConnectFailedCausedByConnectException() throws Exception {
        String path = mount(Proxy.to("http://127.0.0.1:" + closedPort()));
        start();

        ClientResponse r = get(path);

        assertEquals(502, r.status());
        ProxyException e = onlyProxyError();
        assertSame(ProxyException.Kind.CONNECT_FAILED, e.kind());
        assertTrue("cause: " + e.getCause(), e.getCause() instanceof ConnectException);
        assertNull("message", e.getMessage());
    }

    // FR-PROXY-015 AC-2: for a target whose host name does not resolve (http://backend.invalid:8081), the client
    // receives 502 and onError receives a ProxyException CONNECT_FAILED whose cause is a java.net.UnknownHostException
    @Test(timeout = 30000)
    public void fr_PROXY_015_AC2_anUnresolvableHostIsConnectFailedCausedByUnknownHostException() throws Exception {
        String path = mount(Proxy.to("http://backend.invalid:8081"));
        start();

        ClientResponse r = get(path);

        assertEquals(502, r.status());
        ProxyException e = onlyProxyError();
        assertSame(ProxyException.Kind.CONNECT_FAILED, e.kind());
        assertTrue("cause: " + e.getCause(), e.getCause() instanceof UnknownHostException);
        assertNull("message", e.getMessage());
    }

    // ---- AC-3 ----

    // FR-PROXY-015 AC-3: an onError that answers a ProxyException with res.status(e.status()) and a JSON body naming
    // kind() makes the client receive 502 with exactly that JSON body when the backend is down; the proxy writes no
    // response body of its own
    @Test(timeout = 30000)
    public void fr_PROXY_015_AC3_onErrorAnswersByKindAndTheProxyWritesNoBodyOfItsOwn() throws Exception {
        String path = mount(Proxy.to("http://127.0.0.1:" + closedPort()));
        app.onError((error, req, res) -> {
            errors.add(error);
            ProxyException e = (ProxyException) error;
            res.status(e.status()).type("application/json").send("{\"error\":\"" + e.kind() + "\"}");
        });
        app.listen(0);
        started.add(app);

        ClientResponse r = get(path);

        assertEquals(502, r.status());
        assertEquals("{\"error\":\"CONNECT_FAILED\"}", r.text());
        assertEquals("errors that reached onError: " + errors, 1, errors.size());
    }

    // ---- AC-4 ----

    // FR-PROXY-015 AC-4: on an app without onError, a request to a stopped backend gets the default error response with
    // status 502 and the standard reason phrase, and its body names neither the target host nor the target port
    @Test(timeout = 30000)
    public void fr_PROXY_015_AC4_theDefaultErrorResponseIs502AndDoesNotNameTheTarget() throws Exception {
        int stopped = closedPort();
        RestApp g = RestApp.builder().httpAdapter(new TinyHttpdAdapter()).jsonCodec(new StubJsonCodec())
                .logger(new RecordingLogger()).build();
        started.add(g);
        g.use("/api", Proxy.to("http://127.0.0.1:" + stopped));
        int port = g.listen(0).port();

        String response = exchange(port, "GET /api/x HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n");

        assertEquals("HTTP/1.1 502 Bad Gateway", response.substring(0, response.indexOf("\r\n")));
        String body = response.substring(response.indexOf("\r\n\r\n") + 4);
        assertFalse("the body names the target host: " + body, body.contains("127.0.0.1"));
        assertFalse("the body names the target port: " + body, body.contains(Integer.toString(stopped)));
    }

    // ---- AC-5 ----

    // FR-PROXY-015 AC-5: calling the middleware directly with a recording logger, the failures of AC-1 (refused),
    // AC-2 (unresolvable host) and AC-7 (closed during the body) each throw their ProxyException and the proxy calls
    // the app logger for none of them
    @Test(timeout = 60000)
    public void fr_PROXY_015_AC5_failuresBeforeTheResponseAreNotLoggedByTheProxy() throws Exception {
        ScriptedBackend closing = backend((b, index, in, out, s) -> {
            readHead(in);
            readFully(in, new byte[64 * 1024]);
        });
        List<Throwable> thrown = Collections.synchronizedList(new ArrayList<Throwable>());
        String refused = mountDirect(Proxy.to("http://127.0.0.1:" + closedPort()), thrown);
        String unresolvable = mountDirect(Proxy.to("http://backend.invalid:8081"), thrown);
        String closedDuringBody = mountDirect(Proxy.to(closing.url()).bodyLimit(128 << 20), thrown);
        start();
        List<String> before = new ArrayList<>(log.lines);

        get(refused);
        get(unresolvable);
        long length = 16L << 20;
        send(ClientRequest.of("POST", closedDuringBody + "/x").header("Content-Length", Long.toString(length))
                .body(new byte[(int) length]));

        List<ProxyException.Kind> kinds = new ArrayList<>();
        synchronized (thrown) {
            for (Throwable t : thrown) {
                assertTrue("a ProxyException: " + t, t instanceof ProxyException);
                kinds.add(((ProxyException) t).kind());
            }
        }
        assertEquals(Arrays.asList(ProxyException.Kind.CONNECT_FAILED, ProxyException.Kind.CONNECT_FAILED,
                ProxyException.Kind.INVALID_RESPONSE), kinds);
        assertEquals("logged errors", Collections.emptyList(), log.errors);
        // The only new records are the app's own access lines of the three requests, each answered 299 by the test;
        // an access line may land a moment after its response.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (log.lines.size() < before.size() + 3 && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        Thread.sleep(100);
        List<String> added = new ArrayList<>(log.lines).subList(before.size(), log.lines.size());
        assertEquals("records after the requests: " + added, 3, added.size());
        assertEquals("records other than the access lines: " + added, added,
                RecordingLogger.matching(added, "INFO (GET|POST) /m\\d+/x 299 .*"));
    }

    // ---- AC-6 ----

    // FR-PROXY-015 AC-6: a backend that reads part of a 1 MiB request body, sends a complete 401, closes only its
    // writing direction (FIN) and reads and discards the rest of the body makes the client receive that 401
    @Test(timeout = 30000)
    public void fr_PROXY_015_AC6_anEarly401AfterAHalfCloseReachesTheClient() throws Exception {
        ScriptedBackend backend = backend((b, index, in, out, s) -> {
            readHead(in);
            readFully(in, new byte[64 * 1024]);
            out.write(UNAUTHORIZED.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            s.shutdownOutput();
            drain(in);
        });
        String path = mount(Proxy.to(backend.url()).bodyLimit(8 << 20));
        start();
        int length = 1 << 20;

        ClientResponse r = send(ClientRequest.of("POST", path + "/x").header("Content-Length",
                Integer.toString(length)).body(new byte[length]));

        assertEquals("errors: " + errors, 401, r.status());
        assertEquals("unauthorized", r.text());
        assertEquals("errors that reached onError", Collections.emptyList(), errors);
    }

    // ---- AC-7 ----

    // FR-PROXY-015 AC-7: a backend that reads part of a large request body and then closes the connection without any
    // response gives the client 502 and onError a ProxyException of kind INVALID_RESPONSE
    @Test(timeout = 30000)
    public void fr_PROXY_015_AC7_aBackendThatClosesDuringTheBodyIsInvalidResponse() throws Exception {
        ScriptedBackend backend = backend((b, index, in, out, s) -> {
            readHead(in);
            readFully(in, new byte[64 * 1024]);
        });
        long length = 16L << 20;
        CountingBody body = new CountingBody(length);
        String path = mountWithBody(Proxy.to(backend.url()).bodyLimit(128 << 20), body);
        start();

        ClientResponse r = send(ClientRequest.of("POST", path + "/x").header("Content-Length", Long.toString(length)));

        assertEquals(502, r.status());
        assertSame(ProxyException.Kind.INVALID_RESPONSE, onlyProxyError().kind());
        assertTrue("the body transfer stopped early: " + body.read.get(), body.read.get() < length);
    }

    // ---- AC-8 ----

    // FR-PROXY-015 AC-8: once a write of the request body to the backend has failed, the proxy reads no further byte
    // of the client body (a counting body stream): here the failed write is followed by an early 401 the backend sent
    // and half-closed, which is relayed
    @Test(timeout = 30000)
    public void fr_PROXY_015_AC8_noClientBodyByteIsReadAfterAFailedWriteAndTheEarlyResponseIsRelayed()
            throws Exception {
        ScriptedBackend backend = backend((b, index, in, out, s) -> {
            readHead(in);
            out.write(UNAUTHORIZED.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            s.shutdownOutput();
            drain(in);
        });
        long length = 4L << 20;
        CountingBody body = new CountingBody(length);
        AtomicLong readAtFailure = failWritesAfter(256 * 1024, body);
        String path = mountWithBody(Proxy.to(backend.url()).bodyLimit(128 << 20), body);
        start();

        ClientResponse r = send(ClientRequest.of("POST", path + "/x").header("Content-Length", Long.toString(length)));

        assertEquals("errors: " + errors, 401, r.status());
        assertEquals("unauthorized", r.text());
        assertTrue("a write failed", readAtFailure.get() >= 0);
        assertEquals("client body bytes read after the failed write", readAtFailure.get(), body.read.get());
        assertTrue("the body was not read to its end: " + body.read.get(), body.read.get() < length);
        assertEquals("closes of the client body that read", 0, body.readsAfterClose.get());
    }

    // FR-PROXY-015 AC-8: as above when the backend sends no response before its FIN: the failed write is followed by an
    // incomplete head, so the request ends with INVALID_RESPONSE, and still no client body byte is read after the
    // failed write
    @Test(timeout = 30000)
    public void fr_PROXY_015_AC8_noClientBodyByteIsReadAfterAFailedWriteWithoutAResponse() throws Exception {
        ScriptedBackend backend = backend((b, index, in, out, s) -> {
            readHead(in);
            s.shutdownOutput();
            drain(in);
        });
        long length = 4L << 20;
        CountingBody body = new CountingBody(length);
        AtomicLong readAtFailure = failWritesAfter(256 * 1024, body);
        String path = mountWithBody(Proxy.to(backend.url()).bodyLimit(128 << 20), body);
        start();

        ClientResponse r = send(ClientRequest.of("POST", path + "/x").header("Content-Length", Long.toString(length)));

        assertEquals(502, r.status());
        ProxyException e = onlyProxyError();
        assertSame(ProxyException.Kind.INVALID_RESPONSE, e.kind());
        assertNotNull("cause", e.getCause());
        assertTrue("a write failed", readAtFailure.get() >= 0);
        assertEquals("client body bytes read after the failed write", readAtFailure.get(), body.read.get());
        assertEquals("closes of the client body that read", 0, body.readsAfterClose.get());
    }

    // ---- AC-9 ----

    // FR-PROXY-015 AC-9: with .writeTimeout(1, SECONDS).bodyLimit(128 << 20), a 64 MiB body to a backend that never
    // reads the request body gives 504 WRITE_TIMEOUT, not INVALID_RESPONSE: the early-response rule does not apply
    @Test(timeout = 60000)
    public void fr_PROXY_015_AC9_aWriteTimeoutDuringTheBodyKeepsItsKind() throws Exception {
        assertDeadlineKind(Proxy.to(neverReadingBackend().url()).writeTimeout(1, TimeUnit.SECONDS)
                .bodyLimit(128 << 20), ProxyException.Kind.WRITE_TIMEOUT);
    }

    // FR-PROXY-015 AC-9: with .totalTimeout(1, SECONDS).bodyLimit(128 << 20), the same request gives 504
    // TOTAL_TIMEOUT, not INVALID_RESPONSE
    @Test(timeout = 60000)
    public void fr_PROXY_015_AC9_aTotalTimeoutDuringTheBodyKeepsItsKind() throws Exception {
        assertDeadlineKind(Proxy.to(neverReadingBackend().url()).totalTimeout(1, TimeUnit.SECONDS)
                .bodyLimit(128 << 20), ProxyException.Kind.TOTAL_TIMEOUT);
    }

    private ScriptedBackend neverReadingBackend() throws IOException {
        return backend((b, index, in, out, s) -> {
            readHead(in);
            hold.await(WAIT_SECONDS * 2, TimeUnit.SECONDS);
        });
    }

    private void assertDeadlineKind(Proxy p, ProxyException.Kind kind) throws Exception {
        long length = 64L << 20;
        CountingBody body = new CountingBody(length);
        String path = mountWithBody(p, body);
        start();

        ClientResponse r = send(ClientRequest.of("POST", path + "/x").header("Content-Length", Long.toString(length)));

        assertEquals(504, r.status());
        ProxyException e = onlyProxyError();
        assertSame(kind, e.kind());
        assertTrue("the socket buffers filled before the body ended: " + body.read.get(), body.read.get() < length);
    }

    // ---- helpers ----

    /** Mounts {@code proxy} at a new path and returns that path. */
    private String mount(Proxy proxy) {
        String path = "/m" + routes++;
        app.use(path, proxy);
        return path;
    }

    /**
     * Mounts at a new path a middleware that calls {@code proxy.handle} directly and keeps what it throws in
     * {@code thrown}, answering 299 itself, so no failure reaches the error flow and its logging.
     */
    private String mountDirect(Proxy proxy, List<Throwable> thrown) {
        String path = "/m" + routes++;
        Middleware direct = (req, res, next) -> {
            try {
                proxy.handle(req, res, next);
            } catch (Exception e) {
                thrown.add(e);
                res.status(299).send("caught");
            }
        };
        app.use(path, direct);
        return path;
    }

    /** Mounts {@code proxy} at a new path behind a request wrapper whose {@code bodyStream} is {@code body}. */
    private String mountWithBody(Proxy proxy, InputStream body) {
        String path = "/m" + routes++;
        app.use(path, (req, res, next) -> {
            Request wrapped = (Request) java.lang.reflect.Proxy.newProxyInstance(Request.class.getClassLoader(),
                    new Class<?>[] {Request.class}, (self, method, args) -> {
                        if (method.getName().equals("bodyStream")) {
                            return body;
                        }
                        try {
                            return method.invoke(req, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
            proxy.handle(wrapped, res, next);
        });
        return path;
    }

    /**
     * Makes every plain backend socket fail its writes once more than {@code limit} bytes were written, as a backend
     * that closed would; the returned value is the number of client body bytes read when the first write failed, or
     * -1 while none failed.
     */
    private static AtomicLong failWritesAfter(final long limit, final CountingBody body) {
        final AtomicLong readAtFailure = new AtomicLong(-1);
        BackendClient.plainSockets = () -> new Socket() {
            @Override
            public OutputStream getOutputStream() throws IOException {
                return new FilterOutputStream(super.getOutputStream()) {
                    private long written;

                    @Override
                    public void write(int b) throws IOException {
                        write(new byte[] {(byte) b}, 0, 1);
                    }

                    @Override
                    public void write(byte[] b, int off, int len) throws IOException {
                        if (written + len > limit) {
                            readAtFailure.compareAndSet(-1, body.read.get());
                            throw new SocketException("Broken pipe (simulated)");
                        }
                        out.write(b, off, len);
                        written += len;
                    }
                };
            }
        };
        return readAtFailure;
    }

    private void start() {
        app.onError((error, req, res) -> errors.add(error));
        app.listen(0);
        started.add(app);
    }

    private ClientResponse get(String path) throws Exception {
        return send(ClientRequest.of("GET", path + "/x"));
    }

    /** Sends {@code request}, bounded so a request that never ends fails the test instead of hanging it. */
    private ClientResponse send(ClientRequest request) throws Exception {
        return adapter.submit(request).get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    private ScriptedBackend backend(ScriptedBackend.Script script) throws IOException {
        ScriptedBackend b = new ScriptedBackend(script);
        closeables.add(b);
        return b;
    }

    /** The one error that reached onError, a ProxyException. */
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

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int matched = 0;
        byte[] end = {'\r', '\n', '\r', '\n'};
        while (matched < 4) {
            int b = in.read();
            if (b < 0) {
                throw new EOFException("the connection closed inside the head: " + head);
            }
            head.write(b);
            matched = b == end[matched] ? matched + 1 : (b == '\r' ? 1 : 0);
        }
        return new String(head.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    private static void readFully(InputStream in, byte[] into) throws IOException {
        for (int off = 0; off < into.length; ) {
            int n = in.read(into, off, into.length - off);
            if (n < 0) {
                throw new EOFException("the body ended after " + off + " bytes");
            }
            off += n;
        }
    }

    /** Reads and discards until the proxy closes the connection. */
    private static void drain(InputStream in) {
        byte[] buf = new byte[8192];
        try {
            while (in.read(buf) >= 0) {
                // discard
            }
        } catch (IOException e) {
            // a reset is a close as well
        }
    }

    /** Writes {@code raw} to 127.0.0.1:{@code port} and reads the response as ISO-8859-1 until the server closes. */
    private static String exchange(int port, String raw) throws IOException {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            s.setSoTimeout(10_000);
            s.getOutputStream().write(raw.getBytes(StandardCharsets.ISO_8859_1));
            s.getOutputStream().flush();
            ByteArrayOutputStream got = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            try {
                for (int n; (n = s.getInputStream().read(buf)) >= 0; ) {
                    got.write(buf, 0, n);
                }
            } catch (SocketException reset) {
                // a reset after the close counts as the end
            }
            String text = new String(got.toByteArray(), StandardCharsets.ISO_8859_1);
            if (text.indexOf("\r\n") < 0) {
                throw new EOFException("no status line: " + text);
            }
            return text;
        }
    }

    /**
     * A client body of {@code length} zero bytes that counts the bytes handed out and any read after its close; its
     * close reads nothing.
     */
    private static final class CountingBody extends InputStream {
        private final long length;
        final AtomicLong read = new AtomicLong();
        final AtomicInteger readsAfterClose = new AtomicInteger();
        private volatile boolean closed;

        CountingBody(long length) {
            this.length = length;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (closed) {
                readsAfterClose.incrementAndGet();
            }
            long left = length - read.get();
            if (left <= 0) {
                return -1;
            }
            int n = (int) Math.min(len, left);
            Arrays.fill(b, off, off + n, (byte) 0);
            read.addAndGet(n);
            return n;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    /**
     * A JDK-only backend over a raw {@link ServerSocket} on 127.0.0.1 (D-070) that runs its {@link Script} for each
     * connection on its own thread and then closes the connection.
     */
    private static final class ScriptedBackend implements Closeable {
        /** What the backend does with its {@code index}-th connection (0-based) on socket {@code s}. */
        interface Script {
            void run(ScriptedBackend backend, int index, InputStream in, OutputStream out, Socket s) throws Exception;
        }

        private final ServerSocket server;
        private final Script script;
        private final Thread acceptor;
        private final List<Socket> sockets = Collections.synchronizedList(new ArrayList<Socket>());
        private final AtomicInteger connections = new AtomicInteger();

        ScriptedBackend(Script script) throws IOException {
            this.script = script;
            server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
            acceptor = new Thread(this::acceptLoop, "errors-backend-" + server.getLocalPort());
            acceptor.setDaemon(true);
            acceptor.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getLocalPort();
        }

        private void acceptLoop() {
            while (!server.isClosed()) {
                Socket s;
                try {
                    s = server.accept();
                } catch (IOException e) {
                    return;
                }
                int index = connections.getAndIncrement();
                sockets.add(s);
                Thread t = new Thread(() -> serve(s, index), "errors-backend-conn");
                t.setDaemon(true);
                t.start();
            }
        }

        private void serve(Socket s, int index) {
            try {
                s.setSoTimeout(30_000);
                script.run(this, index, s.getInputStream(), s.getOutputStream(), s);
            } catch (Exception e) {
                // the connection is over either way
            } finally {
                try {
                    s.close();
                } catch (IOException ignored) {
                    // closed either way
                }
            }
        }

        @Override
        public void close() throws IOException {
            server.close();
            synchronized (sockets) {
                for (Socket s : sockets) {
                    try {
                        s.close();
                    } catch (IOException ignored) {
                        // closed either way
                    }
                }
            }
            try {
                acceptor.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
