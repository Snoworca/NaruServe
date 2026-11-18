package com.snoworca.naruserve.proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
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
 * The four timeouts of a proxied exchange (FR-PROXY-017): connect, write, read and total, each set per mount, the
 * first one reached applies, 504 with the timeout's kind before the response was handed to {@code res.relay} and an
 * aborted client connection with the kind in the app logger after it. Timing tests configure a few seconds at most;
 * the defaults are read through the package-private accessors only. The last two tests carry the wave-16 finding LP-1
 * (REL-PROXY-001 AC-1): a client that resets while the backend is silent mid-body is only noticed through a backend
 * timeout, which then frees the backend connection and the slot over real TinyHttpd sockets.
 */
public class ProxyTimeoutsTest {

    /** How long a test waits for a request or an event that must happen. */
    private static final long WAIT_SECONDS = 20;
    /** The bound under which a timing test's response must arrive when its timeout works. */
    private static final long LATE_MILLIS = 6000;

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
        hold.countDown();
        for (RestApp a : started) {
            a.shutdown(2, TimeUnit.SECONDS);
        }
        for (Closeable c : closeables) {
            c.close();
        }
    }

    // ---- AC-1, AC-10 ----

    // FR-PROXY-017 AC-1: the configured values of Proxy.to(url), read without waiting through the package-private
    // accessors, are connect 10 s, write 60 s, read 60 s and total 5 min.
    // FR-PROXY-017 AC-10: the defaults are checked only here, through the accessors; every timing test below
    // configures a few seconds at most.
    @Test
    public void fr_PROXY_017_AC1_AC10_theDefaultsAreReadThroughTheAccessors() {
        Proxy p = Proxy.to("http://127.0.0.1:8081");

        assertEquals(TimeUnit.SECONDS.toNanos(10), p.connectTimeoutNanos());
        assertEquals(TimeUnit.SECONDS.toNanos(60), p.writeTimeoutNanos());
        assertEquals(TimeUnit.SECONDS.toNanos(60), p.readTimeoutNanos());
        assertEquals(TimeUnit.MINUTES.toNanos(5), p.totalTimeoutNanos());
        assertEquals(10_000, p.connectTimeoutMillis());
        assertEquals(60_000, p.writeTimeoutMillis());
        assertEquals(60_000, p.readTimeoutMillis());
    }

    // ---- AC-2 ----

    // FR-PROXY-017 AC-2: the connect timeout goes in milliseconds to Socket.connect(address, timeout) for http and
    // https targets, and a SocketTimeoutException from that call is 504 CONNECT_TIMEOUT
    @Test(timeout = 30000)
    public void fr_PROXY_017_AC2_theConnectTimeoutGoesToSocketConnectAndItsTimeoutIsConnectTimeout()
            throws Exception {
        final List<Integer> connectTimeouts = Collections.synchronizedList(new ArrayList<Integer>());
        BackendClient.plainSockets = () -> new Socket() {
            @Override
            public void connect(SocketAddress endpoint, int timeout) throws IOException {
                connectTimeouts.add(timeout);
                throw new SocketTimeoutException("connect timed out");
            }
        };
        String http = mount(Proxy.to("http://127.0.0.1:9").connectTimeout(1500, TimeUnit.MILLISECONDS));
        String https = mount(Proxy.to("https://127.0.0.1:9").connectTimeout(2, TimeUnit.SECONDS));
        start();

        ClientResponse viaHttp = get(http);
        ClientResponse viaHttps = get(https);

        assertEquals(Arrays.asList(1500, 2000), connectTimeouts);
        assertEquals(504, viaHttp.status());
        assertEquals(504, viaHttps.status());
        assertKinds(ProxyException.Kind.CONNECT_TIMEOUT, ProxyException.Kind.CONNECT_TIMEOUT);
    }

    // ---- AC-3 ----

    // FR-PROXY-017 AC-3: with .connectTimeout(1, SECONDS), an https backend that accepts the TCP connection and never
    // answers the TLS handshake gives 504 after about 1 second, and onError receives CONNECT_TIMEOUT
    @Test(timeout = 30000)
    public void fr_PROXY_017_AC3_aSilentTlsHandshakeIsConnectTimeoutAfterTheConnectTimeout() throws Exception {
        ScriptedBackend backend = backend((b, index, in, out) -> drainUntilClosed(b, in));
        String path = mount(Proxy.to("https://127.0.0.1:" + backend.port()).connectTimeout(1, TimeUnit.SECONDS));
        start();

        long t0 = System.nanoTime();
        ClientResponse r = get(path);
        long millis = elapsedMillis(t0);

        assertEquals(504, r.status());
        assertKinds(ProxyException.Kind.CONNECT_TIMEOUT);
        assertAbout(1000, millis);
    }

    // ---- AC-4 ----

    // FR-PROXY-017 AC-4: with .readTimeout(1, SECONDS), a backend that reads the request and never answers gives 504
    // after about 1 second, and onError receives READ_TIMEOUT
    @Test(timeout = 30000)
    public void fr_PROXY_017_AC4_aSilentBackendIsReadTimeoutAfterTheReadTimeout() throws Exception {
        ScriptedBackend backend = backend((b, index, in, out) -> {
            readHead(in);
            drainUntilClosed(b, in);
        });
        String path = mount(Proxy.to(backend.url()).readTimeout(1, TimeUnit.SECONDS));
        start();

        long t0 = System.nanoTime();
        ClientResponse r = get(path);
        long millis = elapsedMillis(t0);

        assertEquals(504, r.status());
        assertKinds(ProxyException.Kind.READ_TIMEOUT);
        assertAbout(1000, millis);
    }

    // ---- AC-5 ----

    // FR-PROXY-017 AC-5: with .writeTimeout(1, SECONDS).bodyLimit(128 << 20), a 64 MiB body to a backend that never
    // reads the request body gives 504 WRITE_TIMEOUT about 1 second after the socket buffers fill (the proxy's last
    // read of the client body)
    @Test(timeout = 60000)
    public void fr_PROXY_017_AC5_aBackendThatNeverReadsTheBodyIsWriteTimeout() throws Exception {
        ScriptedBackend backend = backend((b, index, in, out) -> {
            readHead(in);
            hold.await(WAIT_SECONDS * 2, TimeUnit.SECONDS);
        });
        long length = 64L << 20;
        PacedBody body = new PacedBody(length, length, 0);
        Proxy p = Proxy.to(backend.url()).writeTimeout(1, TimeUnit.SECONDS).bodyLimit(128 << 20);
        String path = mountWithBody(p, body);
        start();

        ClientResponse r = send(ClientRequest.of("POST", path + "/x").header("Content-Length", Long.toString(length)));
        long afterLastRead = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - body.lastReadNanos);

        assertEquals(504, r.status());
        assertKinds(ProxyException.Kind.WRITE_TIMEOUT);
        assertTrue("the socket buffers filled before the body ended: " + body.sent, body.sent < length);
        assertAbout(1000, afterLastRead);
    }

    // ---- AC-6 ----

    // FR-PROXY-017 AC-6: with .writeTimeout(1, SECONDS), a client body sent in pieces separated by pauses longer than
    // 1 second to a backend that keeps reading gets the backend's response and no WRITE_TIMEOUT; the backend reads
    // body bytes before the last piece is handed out, so the pauses fall inside the backend exchange
    @Test(timeout = 60000)
    public void fr_PROXY_017_AC6_waitingForClientBodyBytesIsNotWriteTime() throws Exception {
        int piece = 20_000;
        int length = 3 * piece;
        AtomicLong firstBodyByteNanos = new AtomicLong();
        ScriptedBackend backend = backend((b, index, in, out) -> {
            readHead(in);
            byte[] got = new byte[length];
            for (int off = 0; off < length; ) {
                int n = in.read(got, off, length - off);
                if (n < 0) {
                    throw new EOFException("the body ended after " + off + " bytes");
                }
                firstBodyByteNanos.compareAndSet(0, System.nanoTime());
                off += n;
            }
            out.write(RawBackend.response(200, "got " + length).getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
        });
        PacedBody body = new PacedBody(length, piece, 1300);
        String path = mountWithBody(Proxy.to(backend.url()).writeTimeout(1, TimeUnit.SECONDS), body);
        start();

        ClientResponse r = send(ClientRequest.of("POST", path + "/x").header("Content-Length",
                Integer.toString(length)));

        assertEquals("errors: " + errors, 200, r.status());
        assertEquals("got " + length, r.text());
        assertEquals("errors that reached onError", Collections.emptyList(), errors);
        assertTrue("the backend read body bytes before the last piece was handed out",
                firstBodyByteNanos.get() != 0 && firstBodyByteNanos.get() - body.lastPieceNanos < 0);
    }

    // ---- AC-7 ----

    // FR-PROXY-017 AC-7: with .readTimeout(1, SECONDS).totalTimeout(2, SECONDS), a backend that trickles one byte of
    // its header block every 500 ms gives 504 TOTAL_TIMEOUT after about 2 seconds
    @Test(timeout = 30000)
    public void fr_PROXY_017_AC7_aTrickledHeaderBlockIsTotalTimeout() throws Exception {
        ScriptedBackend backend = backend((b, index, in, out) -> {
            readHead(in);
            trickle(out, "HTTP/1.1 200 OK\r\nX-Slow: " + repeat('a', 200));
        });
        String path = mount(Proxy.to(backend.url()).readTimeout(1, TimeUnit.SECONDS)
                .totalTimeout(2, TimeUnit.SECONDS));
        start();

        long t0 = System.nanoTime();
        ClientResponse r = get(path);
        long millis = elapsedMillis(t0);

        assertEquals(504, r.status());
        assertKinds(ProxyException.Kind.TOTAL_TIMEOUT);
        assertAbout(2000, millis);
    }

    // FR-PROXY-017 AC-7: with .readTimeout(1, SECONDS).totalTimeout(2, SECONDS), a backend that sends its header block
    // and then one body byte every 500 ms makes the client connection abort after about 2 seconds, and the app logger
    // receives a ProxyException TOTAL_TIMEOUT
    @Test(timeout = 30000)
    public void fr_PROXY_017_AC7_aTrickledBodyAbortsTheClientAndLogsTotalTimeout() throws Exception {
        ScriptedBackend backend = backend((b, index, in, out) -> {
            readHead(in);
            out.write("HTTP/1.1 200 OK\r\nContent-Length: 1000\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            trickle(out, repeat('b', 1000));
        });
        String path = mount(Proxy.to(backend.url()).readTimeout(1, TimeUnit.SECONDS)
                .totalTimeout(2, TimeUnit.SECONDS));
        start();

        long t0 = System.nanoTime();
        ClientResponse r = get(path);
        long millis = elapsedMillis(t0);

        assertEquals(200, r.status());
        assertTrue("the client connection was aborted", r.abnormallyClosed());
        assertTrue("body bytes that arrived: " + r.body().length, r.body().length < 1000);
        assertAbout(2000, millis);
        assertLoggedOnce(ProxyException.Kind.TOTAL_TIMEOUT);
        assertEquals("errors that reached onError", Collections.emptyList(), errors);
    }

    // ---- AC-8 ----

    // FR-PROXY-017 AC-8: with .readTimeout(60, SECONDS).totalTimeout(1, SECONDS), a backend that never answers gives
    // 504 TOTAL_TIMEOUT after about 1 second: the first timeout reached applies
    @Test(timeout = 30000)
    public void fr_PROXY_017_AC8_theFirstTimeoutReachedApplies() throws Exception {
        ScriptedBackend backend = backend((b, index, in, out) -> {
            readHead(in);
            drainUntilClosed(b, in);
        });
        String path = mount(Proxy.to(backend.url()).readTimeout(60, TimeUnit.SECONDS)
                .totalTimeout(1, TimeUnit.SECONDS));
        start();

        long t0 = System.nanoTime();
        ClientResponse r = get(path);
        long millis = elapsedMillis(t0);

        assertEquals(504, r.status());
        assertKinds(ProxyException.Kind.TOTAL_TIMEOUT);
        assertAbout(1000, millis);
        assertTrue("the backend observed its connection closed",
                backend.closedByProxy.await(WAIT_SECONDS, TimeUnit.SECONDS));
    }

    // FR-PROXY-017 AC-8: the total timeout bounds the request while the client trickles its body: with
    // .totalTimeout(2, SECONDS) and a client body arriving one byte every 500 ms, the request ends with 504
    // TOTAL_TIMEOUT at the first client read after the deadline, about 2 seconds, not when the copy buffer next fills
    @Test(timeout = 60000)
    public void fr_PROXY_017_AC8_theTotalTimeoutEndsATrickledClientBody() throws Exception {
        // The request head may still sit in the proxy's copy buffer, so the backend only drains until the close.
        ScriptedBackend backend = backend((b, index, in, out) -> drainUntilClosed(b, in));
        PacedBody body = new PacedBody(100_000, 1, 500);
        String path = mountWithBody(Proxy.to(backend.url()).totalTimeout(2, TimeUnit.SECONDS), body);
        start();

        long t0 = System.nanoTime();
        ClientResponse r = send(ClientRequest.of("POST", path + "/x").header("Content-Length", "100000"));
        long millis = elapsedMillis(t0);

        assertEquals(504, r.status());
        assertKinds(ProxyException.Kind.TOTAL_TIMEOUT);
        assertAbout(2000, millis);
        assertTrue("the backend observed its connection closed",
                backend.closedByProxy.await(WAIT_SECONDS, TimeUnit.SECONDS));
    }

    // FR-PROXY-017 AC-7, FR-PROXY-017 AC-8 (FND-001): a timeout reached after the backend head was read but before
    // the response is handed to res.relay is a 504 through onError, not a relayed status and an aborted client. With
    // .totalTimeout(1, SECONDS).maxConcurrent(1), a backend that sends its header block and then stays silent, and a
    // request whose application() stalls the request thread 1500 ms (the proxy reads the app logger after the head,
    // while it prepares the hand-off), the total deadline fires before res.relay: the client gets 504, onError
    // receives TOTAL_TIMEOUT, the app logger receives no ProxyException, and the slot is returned
    @Test(timeout = 30000)
    public void fr_PROXY_017_AC7_AC8_aTotalTimeoutAfterTheHeadButBeforeTheRelayIsA504() throws Exception {
        ScriptedBackend backend = backend((b, index, in, out) -> {
            readHead(in);
            out.write("HTTP/1.1 200 OK\r\nContent-Length: 1000\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            drainUntilClosed(b, in);
        });
        Proxy p = Proxy.to(backend.url()).totalTimeout(1, TimeUnit.SECONDS).maxConcurrent(1);
        String path = mountWithSlowApplication(p, 1500);
        start();

        ClientResponse r = get(path);

        assertEquals("errors: " + errors + ", logged: " + log.lines, 504, r.status());
        assertTrue("the 504 is a complete response", !r.abnormallyClosed());
        assertKinds(ProxyException.Kind.TOTAL_TIMEOUT);
        synchronized (log.errors) {
            for (Throwable logged : log.errors) {
                assertTrue("no ProxyException is logged: " + log.lines, !(logged instanceof ProxyException));
            }
        }
        awaitCounterZero(p);
        assertTrue("the backend observed its connection closed",
                backend.closedByProxy.await(WAIT_SECONDS, TimeUnit.SECONDS));
    }

    // FR-PROXY-017 AC-7, FR-PROXY-017 AC-8 (FND-101): the 504 of a timeout reached after the backend head was read
    // carries none of the backend's header lines (Set-Cookie, Cache-Control, a custom header) and, like every error
    // before the hand-off, none of the .responseHeader values, which FR-PROXY-012 applies to the relayed response
    // only, after the backend header copy
    @Test(timeout = 30000)
    public void fr_PROXY_017_AC7_AC8_a504AfterTheHeadCarriesNoBackendHeaders() throws Exception {
        ScriptedBackend backend = backend((b, index, in, out) -> {
            readHead(in);
            out.write(("HTTP/1.1 200 OK\r\nSet-Cookie: sid=backend; Path=/\r\nCache-Control: public, max-age=600\r\n"
                    + "X-Backend: yes\r\nStrict-Transport-Security: max-age=0\r\nContent-Length: 1000\r\n\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            drainUntilClosed(b, in);
        });
        Proxy p = Proxy.to(backend.url()).totalTimeout(1, TimeUnit.SECONDS).maxConcurrent(1)
                .responseHeader("Strict-Transport-Security", "max-age=31536000");
        String path = mountWithSlowApplication(p, 1500);
        start();

        ClientResponse r = get(path);

        assertEquals("errors: " + errors + ", logged: " + log.lines, 504, r.status());
        assertKinds(ProxyException.Kind.TOTAL_TIMEOUT);
        for (String name : Arrays.asList("Set-Cookie", "Cache-Control", "X-Backend", "Strict-Transport-Security")) {
            assertEquals("no " + name + " on the 504: " + r.headers(), null, r.header(name));
        }
        awaitCounterZero(p);
    }

    // ---- AC-9 ----

    // FR-PROXY-017 AC-9: timeouts are set per mount: on one backend that answers after 3 seconds, a mount with
    // .readTimeout(1, SECONDS) gives 504 while a mount with .readTimeout(5, MINUTES) relays the response, and the
    // accessor of the latter reports 300000 ms
    @Test(timeout = 60000)
    public void fr_PROXY_017_AC9_timeoutsAreSetPerMount() throws Exception {
        ScriptedBackend backend = backend((b, index, in, out) -> {
            readHead(in);
            Thread.sleep(3000);
            out.write(RawBackend.response(200, "late").getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
        });
        Proxy patient = Proxy.to(backend.url()).readTimeout(5, TimeUnit.MINUTES);
        String impatientPath = mount(Proxy.to(backend.url()).readTimeout(1, TimeUnit.SECONDS));
        String patientPath = mount(patient);
        start();

        ClientResponse impatient = get(impatientPath);
        ClientResponse relayed = get(patientPath);

        assertEquals(504, impatient.status());
        assertKinds(ProxyException.Kind.READ_TIMEOUT);
        assertEquals(200, relayed.status());
        assertEquals("late", relayed.text());
        assertEquals(300_000, patient.readTimeoutMillis());
    }

    // ---- LP-1 (wave-16): REL-PROXY-001 AC-1 with a silent backend ----

    // REL-PROXY-001 AC-1 (LP-1), FR-PROXY-017 AC-4: over real TinyHttpd sockets on a .maxConcurrent(1) mount, a
    // client that resets while the backend is silent mid-body makes the backend observe its connection closed once
    // the read timeout passes, and the next request reaches the backend
    @Test(timeout = 90000)
    public void rel_PROXY_001_AC1_aResetDuringASilentBodyEndsByTheReadTimeout() throws Exception {
        assertAQuietDisconnectFreesTheMount(Proxy.to(lp1Backend().url()).readTimeout(1, TimeUnit.SECONDS)
                .maxConcurrent(1));
    }

    // REL-PROXY-001 AC-1 (LP-1), FR-PROXY-017 AC-7: as above with .totalTimeout(2, SECONDS) and the default 60 s read
    // timeout, so only the total-timeout watchdog ends the exchange
    @Test(timeout = 90000)
    public void rel_PROXY_001_AC1_aResetDuringASilentBodyEndsByTheTotalTimeout() throws Exception {
        assertAQuietDisconnectFreesTheMount(Proxy.to(lp1Backend().url()).totalTimeout(2, TimeUnit.SECONDS)
                .maxConcurrent(1));
    }

    private ScriptedBackend lp1Backend;

    /**
     * The LP-1 backend: its first connection gets {@code Content-Length: 1000000} and 64 KiB of body, then silence
     * until the proxy closes it; every later connection gets 200 {@code ok}.
     */
    private ScriptedBackend lp1Backend() throws IOException {
        lp1Backend = backend((b, index, in, out) -> {
            readHead(in);
            if (index == 0) {
                out.write("HTTP/1.1 200 X\r\nContent-Length: 1000000\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                out.write(new byte[64 * 1024]);
                out.flush();
                drainUntilClosed(b, in);
            } else {
                out.write(RawBackend.response(200, "ok").getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
            }
        });
        return lp1Backend;
    }

    private void assertAQuietDisconnectFreesTheMount(Proxy p) throws Exception {
        RestApp a = RestApp.builder().httpAdapter(new TinyHttpdAdapter()).jsonCodec(new StubJsonCodec())
                .logger(new RecordingLogger()).build();
        started.add(a);
        a.use("/api", p);
        int port = a.listen(0).port();

        try (Socket client = new Socket()) {
            client.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            client.setSoTimeout(10_000);
            client.getOutputStream().write("GET /api/big HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n"
                    .getBytes(StandardCharsets.ISO_8859_1));
            client.getOutputStream().flush();
            String head = readHead(client.getInputStream());
            assertTrue(head, head.startsWith("HTTP/1.1 200 "));
            readFully(client.getInputStream(), new byte[1024]);
            assertEquals("the relay is in flight", 1, p.concurrencyCounter().get());
            // Let the relay hand the 64 KiB to the socket buffers, so it waits on the silent backend, not a write.
            Thread.sleep(300);
            client.setSoLinger(true, 0);
        }

        assertTrue("the backend observed its connection closed",
                lp1Backend.closedByProxy.await(WAIT_SECONDS, TimeUnit.SECONDS));
        awaitCounterZero(p);

        String next = exchange(port, "GET /api/next HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n");

        assertTrue(next, next.startsWith("HTTP/1.1 200 "));
        assertTrue(next, next.endsWith("ok"));
        assertEquals("backend connections", 2, lp1Backend.connections.get());
    }

    // ---- helpers ----

    /** Mounts {@code proxy} at a new path and returns that path. */
    private String mount(Proxy proxy) {
        String path = "/m" + routes++;
        app.use(path, proxy);
        return path;
    }

    /**
     * Mounts {@code proxy} at a new path behind a request wrapper whose {@code bodyStream} is {@code body}, so the
     * test paces the client body and sees when the proxy last read it.
     */
    private String mountWithBody(Proxy proxy, InputStream body) {
        String path = "/m" + routes++;
        app.use(path, (req, res, next) -> {
            Request paced = (Request) java.lang.reflect.Proxy.newProxyInstance(Request.class.getClassLoader(),
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
            proxy.handle(paced, res, next);
        });
        return path;
    }

    /**
     * Mounts {@code proxy} at a new path behind a request wrapper whose {@code application()} sleeps {@code millis}
     * before it passes the call on. The proxy calls it once, for the app logger, after the backend head was read and
     * before the hand-off to {@code res.relay}, so the request thread stalls in that window.
     */
    private String mountWithSlowApplication(Proxy proxy, long millis) {
        String path = "/m" + routes++;
        app.use(path, (req, res, next) -> {
            Request slow = (Request) java.lang.reflect.Proxy.newProxyInstance(Request.class.getClassLoader(),
                    new Class<?>[] {Request.class}, (self, method, args) -> {
                        if (method.getName().equals("application") && (args == null || args.length == 0)) {
                            Thread.sleep(millis);
                        }
                        try {
                            return method.invoke(req, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
            proxy.handle(slow, res, next);
        });
        return path;
    }

    private void start() {
        app.onError((error, req, res) -> errors.add(error));
        app.listen(0);
        started.add(app);
    }

    private ClientResponse get(String path) throws Exception {
        return send(ClientRequest.of("GET", path + "/x"));
    }

    /** Sends {@code request}, bounded so a timeout that never fires fails the test instead of hanging it. */
    private ClientResponse send(ClientRequest request) throws Exception {
        return adapter.submit(request).get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    private ScriptedBackend backend(ScriptedBackend.Script script) throws IOException {
        ScriptedBackend b = new ScriptedBackend(script);
        closeables.add(b);
        return b;
    }

    /** The errors that reached onError are ProxyExceptions of {@code kinds}, in order. */
    private void assertKinds(ProxyException.Kind... kinds) {
        List<ProxyException.Kind> got = new ArrayList<>();
        synchronized (errors) {
            for (Throwable t : errors) {
                assertTrue("a ProxyException: " + t, t instanceof ProxyException);
                got.add(((ProxyException) t).kind());
            }
        }
        assertEquals("errors that reached onError: " + errors, Arrays.asList(kinds), got);
    }

    /** The app logger received exactly one error, a ProxyException of {@code kind}, waiting for it a while. */
    private void assertLoggedOnce(ProxyException.Kind kind) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (log.errors.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        synchronized (log.errors) {
            assertEquals("errors logged: " + log.lines, 1, log.errors.size());
            Throwable logged = log.errors.get(0);
            assertTrue("a ProxyException: " + logged, logged instanceof ProxyException);
            assertSame(kind, ((ProxyException) logged).kind());
        }
    }

    /** {@code millis} is about {@code expected}: not early, and well before {@link #LATE_MILLIS}. */
    private static void assertAbout(long expected, long millis) {
        assertTrue("after " + millis + " ms, expected about " + expected, millis >= expected - 100);
        assertTrue("after " + millis + " ms, expected about " + expected, millis < LATE_MILLIS);
    }

    private static long elapsedMillis(long t0) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
    }

    private static void awaitCounterZero(Proxy p) throws InterruptedException {
        AtomicInteger counter = p.concurrencyCounter();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (counter.get() != 0) {
            if (System.nanoTime() > deadline) {
                fail("the slot was not returned: counter=" + counter.get());
            }
            Thread.sleep(5);
        }
    }

    private static String repeat(char c, int n) {
        char[] chars = new char[n];
        Arrays.fill(chars, c);
        return new String(chars);
    }

    /** Writes {@code text} one byte every 500 ms until it is out or a write fails. */
    private static void trickle(OutputStream out, String text) throws IOException, InterruptedException {
        for (byte b : text.getBytes(StandardCharsets.ISO_8859_1)) {
            out.write(b);
            out.flush();
            Thread.sleep(500);
        }
    }

    /** Reads until the proxy closes the connection, then reports the close. */
    private static void drainUntilClosed(ScriptedBackend b, InputStream in) {
        byte[] buf = new byte[8192];
        try {
            while (in.read(buf) >= 0) {
                // discard
            }
        } catch (IOException e) {
            // a reset is a close as well
        }
        b.closedByProxy.countDown();
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

    /** Writes {@code raw} to 127.0.0.1:{@code port} and reads the response as ISO-8859-1 until the server closes. */
    private static String exchange(int port, String raw) throws IOException {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            s.setSoTimeout(10_000);
            s.getOutputStream().write(raw.getBytes(StandardCharsets.ISO_8859_1));
            s.getOutputStream().flush();
            ByteArrayOutputStream got = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = s.getInputStream().read(buf)) >= 0; ) {
                got.write(buf, 0, n);
            }
            return new String(got.toByteArray(), StandardCharsets.ISO_8859_1);
        }
    }

    /**
     * A client body of {@code length} bytes of {@code 'a'} handed out in pieces of {@code piece} bytes, with a pause
     * of {@code pauseMillis} before every piece after the first; it records how much was read and when.
     */
    private static final class PacedBody extends InputStream {
        private final long length;
        private final long piece;
        private final long pauseMillis;
        volatile long sent;
        volatile long lastReadNanos = System.nanoTime();
        /** When the last piece started to be handed out, after the last pause; 0 before then. */
        volatile long lastPieceNanos;

        PacedBody(long length, long piece, long pauseMillis) {
            this.length = length;
            this.piece = piece;
            this.pauseMillis = pauseMillis;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (sent >= length) {
                return -1;
            }
            if (pauseMillis > 0 && sent > 0 && sent % piece == 0) {
                try {
                    Thread.sleep(pauseMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(e);
                }
            }
            if (sent % piece == 0 && length - sent <= piece) {
                lastPieceNanos = System.nanoTime();
            }
            int n = (int) Math.min(len, Math.min(length - sent, piece - sent % piece));
            Arrays.fill(b, off, off + n, (byte) 'a');
            sent += n;
            lastReadNanos = System.nanoTime();
            return n;
        }
    }

    /**
     * A JDK-only backend over a raw {@link ServerSocket} on 127.0.0.1 (D-070) that runs its {@link Script} for each
     * connection on its own thread and then closes the connection.
     */
    private static final class ScriptedBackend implements Closeable {
        /** What the backend does with its {@code index}-th connection (0-based). */
        interface Script {
            void run(ScriptedBackend backend, int index, InputStream in, OutputStream out) throws Exception;
        }

        private final ServerSocket server;
        private final Script script;
        private final Thread acceptor;
        private final List<Socket> sockets = Collections.synchronizedList(new ArrayList<Socket>());
        final AtomicInteger connections = new AtomicInteger();
        final CountDownLatch closedByProxy = new CountDownLatch(1);

        ScriptedBackend(Script script) throws IOException {
            this.script = script;
            server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
            acceptor = new Thread(this::acceptLoop, "scripted-backend-" + server.getLocalPort());
            acceptor.setDaemon(true);
            acceptor.start();
        }

        int port() {
            return server.getLocalPort();
        }

        String url() {
            return "http://127.0.0.1:" + port();
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
                Thread t = new Thread(() -> serve(s, index), "scripted-backend-conn");
                t.setDaemon(true);
                t.start();
            }
        }

        private void serve(Socket s, int index) {
            try {
                s.setSoTimeout(30_000);
                script.run(this, index, s.getInputStream(), s.getOutputStream());
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
