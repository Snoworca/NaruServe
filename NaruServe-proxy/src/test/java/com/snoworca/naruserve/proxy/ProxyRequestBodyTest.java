package com.snoworca.naruserve.proxy;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.spi.ExchangeHandler;
import com.snoworca.naruserve.spi.HttpAdapter;
import com.snoworca.naruserve.spi.HttpExchange;
import com.snoworca.naruserve.spi.StreamDrain;
import com.snoworca.naruserve.spi.UpgradeHandler;
import com.snoworca.naruserve.spi.WebSocketEndpoint;
import com.snoworca.naruserve.spi.WebSocketSettings;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.testing.StubJsonCodec;
import com.snoworca.naruserve.testing.TestApp;
import com.snoworca.naruserve.tinyhttpd.TinyHttpdAdapter;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;
import org.junit.After;
import org.junit.Test;

/**
 * The request body the proxy sends to the backend (FR-PROXY-007): streamed from {@code req.bodyStream(...)} with a
 * framing header for the body actually sent — chunked for a chunked request, the same Content-Length for a
 * Content-Length request, and for a request with neither a one-byte pre-read that sends no body (Content-Length: 0
 * for POST, PUT and PATCH only) or chunked transfer. The in-memory adapter hands the proxy requests TinyHttpd would
 * frame first (a body without length headers); a real TinyHttpd socket carries the disconnect and large-body cases.
 */
public class ProxyRequestBodyTest {

    private static final byte[] HELLO = "hello".getBytes(StandardCharsets.ISO_8859_1);
    /** The largest mount body limit, 1 GiB. */
    private static final long GIB = 1073741824L;

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

    /** Mounts {@code proxy} at /api on the in-memory app and starts it. */
    private void mount(Proxy proxy) {
        app.use("/api", proxy);
        t.start();
        started.add(app);
    }

    /** {@code method /api/x} with the given header name/value pairs, in order. */
    private static ClientRequest request(String method, String... headerPairs) {
        ClientRequest r = ClientRequest.of(method, "/api/x");
        for (int i = 0; i < headerPairs.length; i += 2) {
            r.header(headerPairs[i], headerPairs[i + 1]);
        }
        return r;
    }

    /** Sends {@code request} through the in-memory app to a recording backend; asserts 200 ok, returns the request. */
    private RawBackend.Received forward(ClientRequest request) throws IOException {
        RawBackend backend = okBackend();
        mount(Proxy.to(backend.url()));

        ClientResponse r = adapter.send(request);

        assertEquals("status", 200, r.status());
        assertEquals("ok", r.text());
        return backend.onlyRequest();
    }

    /** The backend header lines whose name (case-insensitive) is one of {@code names}, in received order. */
    private static List<String> linesNamed(RawBackend.Received got, String... names) {
        Set<String> wanted = new HashSet<>();
        for (String n : names) {
            wanted.add(n.toLowerCase(Locale.ROOT));
        }
        List<String> out = new ArrayList<>();
        for (String line : got.headerLines()) {
            int colon = line.indexOf(':');
            if (colon > 0 && wanted.contains(line.substring(0, colon).toLowerCase(Locale.ROOT))) {
                out.add(line);
            }
        }
        return out;
    }

    /** The Content-Length and Transfer-Encoding lines the backend received, in order. */
    private static List<String> framingLines(RawBackend.Received got) {
        return linesNamed(got, "Content-Length", "Transfer-Encoding");
    }

    /** The backend's last two header lines. */
    private static List<String> lastTwoLines(RawBackend.Received got) {
        List<String> lines = got.headerLines();
        return lines.subList(lines.size() - 2, lines.size());
    }

    /** {@code n} pseudo-random bytes from {@code seed}. */
    private static byte[] randomBytes(int n, long seed) {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        return b;
    }

    // ---- real TinyHttpd sockets ----

    /** Starts a TinyHttpd app with {@code proxy} at /api and returns its port. */
    private int listenTiny(Proxy proxy) {
        RestApp g = RestApp.builder().httpAdapter(new TinyHttpdAdapter()).jsonCodec(new StubJsonCodec())
                .logger(new RecordingLogger()).build();
        started.add(g);
        g.use("/api", proxy);
        return g.listen(0).port();
    }

    /** {@code body} in the chunked coding with chunks of the given sizes, cycled, and the last chunk. */
    private static byte[] chunkedCoding(byte[] body, int... sizes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int i = 0;
        for (int off = 0; off < body.length; i++) {
            int n = Math.min(sizes[i % sizes.length], body.length - off);
            byte[] size = (Integer.toHexString(n) + "\r\n").getBytes(StandardCharsets.ISO_8859_1);
            out.write(size, 0, size.length);
            out.write(body, off, n);
            out.write('\r');
            out.write('\n');
            off += n;
        }
        byte[] last = "0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
        out.write(last, 0, last.length);
        return out.toByteArray();
    }

    /**
     * Writes {@code head} (the request line and header lines without the blank line; {@code Host} and
     * {@code Connection: close} are added) and {@code body} to the TinyHttpd port and returns everything the server
     * sent until it closed, as ISO-8859-1 text.
     */
    private static String exchange(int port, String head, byte[] body) throws IOException {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            s.setSoTimeout(20000);
            OutputStream out = new BufferedOutputStream(s.getOutputStream());
            out.write((head + "\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.write(body);
            out.flush();
            return readToEnd(s.getInputStream());
        }
    }

    /** Sends one request over a TinyHttpd socket to a proxy with {@code proxy}; asserts 200 ok, returns the request. */
    private RawBackend.Received forwardOverSocket(RawBackend backend, Proxy proxy, String head, byte[] body)
            throws IOException {
        int port = listenTiny(proxy);

        String response = exchange(port, head, body);

        assertTrue(response, response.startsWith("HTTP/1.1 200 "));
        assertTrue(response, response.endsWith("\r\n\r\nok"));
        return backend.onlyRequest();
    }

    // ---- AC-1 ----

    // FR-PROXY-007 AC-1: a bodyless GET arrives with neither Content-Length nor Transfer-Encoding
    @Test
    public void fr_PROXY_007_AC1_bodylessGetArrivesWithoutLengthHeaders() throws IOException {
        RawBackend.Received got = forward(request("GET", "X-A", "1"));

        assertEquals(Collections.<String>emptyList(), framingLines(got));
        assertEquals(0, got.body.length);
    }

    // FR-PROXY-007 AC-1: over a TinyHttpd socket a bodyless GET arrives without length headers, and the pre-read of
    // its empty body ends at once instead of waiting for the client connection to close
    @Test(timeout = 30000)
    public void fr_PROXY_007_AC1_bodylessGetOverASocketArrivesWithoutLengthHeaders() throws IOException {
        RawBackend backend = okBackend();

        RawBackend.Received got = forwardOverSocket(backend, Proxy.to(backend.url()), "GET /api/x HTTP/1.1",
                new byte[0]);

        assertEquals(Collections.<String>emptyList(), framingLines(got));
        assertEquals(0, got.body.length);
    }

    // ---- AC-2 ----

    // FR-PROXY-007 AC-2: bodyless POST, PUT and PATCH without length headers arrive with Content-Length: 0
    @Test
    public void fr_PROXY_007_AC2_bodylessPostPutPatchArriveWithContentLengthZero() throws IOException {
        RawBackend backend = okBackend();
        mount(Proxy.to(backend.url()));

        for (String method : Arrays.asList("POST", "PUT", "PATCH")) {
            assertEquals(method, 200, adapter.send(request(method, "X-A", "1")).status());
        }

        List<RawBackend.Received> got = backend.requests();
        assertEquals(3, got.size());
        for (RawBackend.Received r : got) {
            assertEquals(r.requestLine(), Collections.singletonList("Content-Length: 0"), framingLines(r));
            assertEquals(r.requestLine(), Arrays.asList("Content-Length: 0", "Connection: close"), lastTwoLines(r));
            assertEquals(r.requestLine(), 0, r.body.length);
        }
    }

    // FR-PROXY-007 AC-2: bodyless DELETE, OPTIONS and an extension method arrive with no length header
    @Test
    public void fr_PROXY_007_AC2_bodylessDeleteOptionsAndExtensionMethodArriveWithoutLengthHeader() throws IOException {
        RawBackend backend = okBackend();
        mount(Proxy.to(backend.url()));

        for (String method : Arrays.asList("DELETE", "OPTIONS", "PURGE")) {
            assertEquals(method, 200, adapter.send(request(method, "X-A", "1")).status());
        }

        List<RawBackend.Received> got = backend.requests();
        assertEquals(3, got.size());
        for (RawBackend.Received r : got) {
            assertEquals(r.requestLine(), Collections.<String>emptyList(), framingLines(r));
            assertEquals(r.requestLine(), 0, r.body.length);
        }
    }

    // FR-PROXY-007 AC-2: over a TinyHttpd socket a POST without length headers or body arrives with Content-Length: 0
    @Test(timeout = 30000)
    public void fr_PROXY_007_AC2_bodylessPostOverASocketArrivesWithContentLengthZero() throws IOException {
        RawBackend backend = okBackend();

        RawBackend.Received got = forwardOverSocket(backend, Proxy.to(backend.url()), "POST /api/x HTTP/1.1",
                new byte[0]);

        assertEquals(Collections.singletonList("Content-Length: 0"), framingLines(got));
        assertEquals(0, got.body.length);
    }

    // ---- AC-3 ----

    // FR-PROXY-007 AC-3: a chunked request arrives chunked, without Content-Length, framing header before Connection
    @Test
    public void fr_PROXY_007_AC3_chunkedRequestArrivesChunkedWithoutContentLength() throws IOException {
        RawBackend.Received got = forward(request("POST", "Transfer-Encoding", "chunked").body(HELLO));

        assertEquals(Collections.singletonList("Transfer-Encoding: chunked"), framingLines(got));
        assertEquals(Arrays.asList("Transfer-Encoding: chunked", "Connection: close"), lastTwoLines(got));
        assertArrayEquals(HELLO, got.body);
    }

    // FR-PROXY-007 AC-3: over a TinyHttpd socket a client chunked body of several chunks arrives chunked and the backend
    // decodes exactly the bytes the client sent
    @Test(timeout = 30000)
    public void fr_PROXY_007_AC3_chunkedBodyOverASocketDecodesToTheClientBytes() throws IOException {
        RawBackend backend = okBackend();
        byte[] body = randomBytes(50000, 3);

        RawBackend.Received got = forwardOverSocket(backend, Proxy.to(backend.url()),
                "POST /api/x HTTP/1.1\r\nTransfer-Encoding: chunked", chunkedCoding(body, 1, 7000, 13, 30000));

        assertEquals(Collections.singletonList("Transfer-Encoding: chunked"), framingLines(got));
        assertArrayEquals(body, got.body);
    }

    // ---- AC-4 ----

    // FR-PROXY-007 AC-4: a Content-Length: 5 request arrives with the single line Content-Length: 5 and those 5 bytes
    @Test
    public void fr_PROXY_007_AC4_contentLengthRequestArrivesWithTheSameLength() throws IOException {
        RawBackend.Received got = forward(request("POST", "Content-Length", "5").body(HELLO));

        assertEquals(Collections.singletonList("Content-Length: 5"), framingLines(got));
        assertArrayEquals(HELLO, got.body);
    }

    // FR-PROXY-007 AC-4: a Content-Length: 0 request arrives with Content-Length: 0 and no body
    @Test
    public void fr_PROXY_007_AC4_contentLengthZeroArrivesAsContentLengthZero() throws IOException {
        RawBackend.Received got = forward(request("POST", "Content-Length", "0"));

        assertEquals(Collections.singletonList("Content-Length: 0"), framingLines(got));
        assertEquals(0, got.body.length);
    }

    // FR-PROXY-007 AC-4: over a TinyHttpd socket a Content-Length body arrives with that one length and its bytes
    @Test(timeout = 30000)
    public void fr_PROXY_007_AC4_contentLengthBodyOverASocketArrivesWithTheSameLength() throws IOException {
        RawBackend backend = okBackend();
        byte[] body = randomBytes(20000, 4);

        RawBackend.Received got = forwardOverSocket(backend, Proxy.to(backend.url()),
                "PUT /api/x HTTP/1.1\r\nContent-Length: 20000", body);

        assertEquals(Collections.singletonList("Content-Length: 20000"), framingLines(got));
        assertArrayEquals(body, got.body);
    }

    // ---- AC-5 ----

    // FR-PROXY-007 AC-5: a POST that carries a body without length headers (as the servlet adapter gets over HTTP/2)
    // arrives chunked with the whole body, larger than one copy buffer
    @Test
    public void fr_PROXY_007_AC5_bodyWithoutLengthHeadersArrivesChunked() throws IOException {
        byte[] body = randomBytes(20000, 5);

        RawBackend.Received got = forward(request("POST", "X-A", "1").body(body));

        assertEquals(Collections.singletonList("Transfer-Encoding: chunked"), framingLines(got));
        assertEquals(Arrays.asList("Transfer-Encoding: chunked", "Connection: close"), lastTwoLines(got));
        assertArrayEquals(body, got.body);
    }

    // FR-PROXY-007 AC-5: a one-byte body without length headers (only the pre-read byte) arrives chunked, also for a
    // method other than POST, PUT and PATCH
    @Test
    public void fr_PROXY_007_AC5_oneByteBodyWithoutLengthHeadersArrivesChunked() throws IOException {
        RawBackend backend = okBackend();
        mount(Proxy.to(backend.url()));

        assertEquals(200, adapter.send(request("PUT").body(new byte[] {(byte) 0xff})).status());
        assertEquals(200, adapter.send(request("DELETE").body(HELLO)).status());

        List<RawBackend.Received> got = backend.requests();
        assertEquals(2, got.size());
        assertEquals(Collections.singletonList("Transfer-Encoding: chunked"), framingLines(got.get(0)));
        assertArrayEquals(new byte[] {(byte) 0xff}, got.get(0).body);
        assertEquals(Collections.singletonList("Transfer-Encoding: chunked"), framingLines(got.get(1)));
        assertArrayEquals(HELLO, got.get(1).body);
    }

    // ---- AC-6 ----

    // FR-PROXY-007 AC-6: a client that sends Content-Length: 10, 5 body bytes and disconnects (over a real TinyHttpd
    // socket, D-069) leaves the backend with a closed connection and fewer than 10 body bytes
    @Test(timeout = 60000)
    public void fr_PROXY_007_AC6_clientDisconnectInsideTheBodyClosesTheBackendConnection() throws Exception {
        try (ByteCountingBackend backend = new ByteCountingBackend()) {
            int port = listenTiny(Proxy.to(backend.url()));

            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", port), 5000);
                OutputStream out = s.getOutputStream();
                out.write(("POST /api/x HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: 10\r\n\r\nabcde")
                        .getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
                assertTrue("the proxy connected to the backend", backend.awaitAccepted(20, TimeUnit.SECONDS));
            }

            assertTrue("the backend connection was closed", backend.awaitClosed(20, TimeUnit.SECONDS));
            assertTrue("body bytes at the backend: " + backend.bodyBytes(), backend.bodyBytes() < 10);
            assertEquals("connections", 1, backend.accepted());
        }
    }

    // ---- AC-7 ----

    // FR-PROXY-007 AC-7: an urlencoded body arrives byte for byte
    @Test(timeout = 30000)
    public void fr_PROXY_007_AC7_urlencodedBodyArrivesByteForByte() throws IOException {
        RawBackend backend = okBackend();
        byte[] body = "a=1&b=%20x+y&c=%E2%9C%93&d=&e".getBytes(StandardCharsets.ISO_8859_1);

        RawBackend.Received got = forwardOverSocket(backend, Proxy.to(backend.url()),
                "POST /api/x HTTP/1.1\r\nContent-Type: application/x-www-form-urlencoded\r\nContent-Length: "
                        + body.length, body);

        assertArrayEquals(body, got.body);
    }

    // FR-PROXY-007 AC-7: a binary body holding every byte value arrives byte for byte, by length and chunked
    @Test(timeout = 30000)
    public void fr_PROXY_007_AC7_binaryBodyArrivesByteForByte() throws IOException {
        byte[] body = new byte[256 * 4];
        for (int i = 0; i < body.length; i++) {
            body[i] = (byte) (i * 7);
        }
        RawBackend backend = okBackend();
        int port = listenTiny(Proxy.to(backend.url()));

        String byLength = exchange(port, "POST /api/x HTTP/1.1\r\nContent-Type: application/octet-stream"
                + "\r\nContent-Length: " + body.length, body);
        String chunked = exchange(port, "POST /api/x HTTP/1.1\r\nContent-Type: application/octet-stream"
                + "\r\nTransfer-Encoding: chunked", chunkedCoding(body, 255, 3, 1000));

        assertTrue(byLength, byLength.startsWith("HTTP/1.1 200 "));
        assertTrue(chunked, chunked.startsWith("HTTP/1.1 200 "));
        List<RawBackend.Received> got = backend.requests();
        assertEquals(2, got.size());
        assertArrayEquals(body, got.get(0).body);
        assertArrayEquals(body, got.get(1).body);
        assertEquals(Collections.singletonList("Transfer-Encoding: chunked"), framingLines(got.get(1)));
    }

    // FR-PROXY-007 AC-7: a large (6 MiB) Content-Length body arrives byte for byte through a mount with a bodyLimit
    @Test(timeout = 60000)
    public void fr_PROXY_007_AC7_largeContentLengthBodyArrivesByteForByte() throws IOException {
        RawBackend backend = okBackend();
        byte[] body = randomBytes(6 * 1024 * 1024, 71);

        RawBackend.Received got = forwardOverSocket(backend, Proxy.to(backend.url()).bodyLimit(16L * 1024 * 1024),
                "POST /api/x HTTP/1.1\r\nContent-Length: " + body.length, body);

        assertEquals(Collections.singletonList("Content-Length: " + body.length), framingLines(got));
        assertArrayEquals(body, got.body);
    }

    // FR-PROXY-007 AC-7: a large (6 MiB) chunked body arrives byte for byte through a mount with a bodyLimit
    @Test(timeout = 60000)
    public void fr_PROXY_007_AC7_largeChunkedBodyArrivesByteForByte() throws IOException {
        RawBackend backend = okBackend();
        byte[] body = randomBytes(6 * 1024 * 1024, 72);

        RawBackend.Received got = forwardOverSocket(backend, Proxy.to(backend.url()).bodyLimit(16L * 1024 * 1024),
                "POST /api/x HTTP/1.1\r\nTransfer-Encoding: chunked", chunkedCoding(body, 65536, 1, 4093));

        assertEquals(Collections.singletonList("Transfer-Encoding: chunked"), framingLines(got));
        assertArrayEquals(body, got.body);
    }

    // FR-PROXY-007 AC-7: a proxy JVM started with -Xmx64m forwards a 1 GiB chunked body through a mount with
    // .bodyLimit(1073741824) without an OutOfMemoryError, every byte arriving at the backend
    @Test(timeout = 600000)
    public void fr_PROXY_007_AC7_oneGibChunkedBodyThroughA64MiBHeap() throws Exception {
        assertHugeBodyForwarded("chunked");
    }

    // FR-PROXY-007 AC-7: the same 1 GiB body sent with Content-Length passes a -Xmx64m proxy JVM with
    // .bodyLimit(1073741824) without an OutOfMemoryError
    @Test(timeout = 600000)
    public void fr_PROXY_007_AC7_oneGibContentLengthBodyThroughA64MiBHeap() throws Exception {
        assertHugeBodyForwarded("length");
    }

    /** Runs {@link HugeBodyMain} in a child JVM with -Xmx64m and asserts it reports the whole body forwarded. */
    private static void assertHugeBodyForwarded(String framing) throws Exception {
        String classPath = System.getProperty("java.class.path");
        String java = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        ProcessBuilder pb = new ProcessBuilder(java, "-Xmx64m", "-XX:+ExitOnOutOfMemoryError", "-cp", classPath,
                HugeBodyMain.class.getName(), framing, Long.toString(GIB));
        pb.redirectErrorStream(true);
        Process p = pb.start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread drain = new Thread(() -> {
            byte[] b = new byte[4096];
            try (InputStream in = p.getInputStream()) {
                for (int n; (n = in.read(b)) >= 0; ) {
                    synchronized (output) {
                        if (output.size() < 64 * 1024) {
                            output.write(b, 0, n);
                        }
                    }
                }
            } catch (IOException ignored) {
                // the child ended
            }
        }, "huge-body-child-output");
        drain.setDaemon(true);
        drain.start();
        boolean ended = p.waitFor(540, TimeUnit.SECONDS);
        if (!ended) {
            p.destroyForcibly();
        }
        drain.join(5000);
        String text;
        synchronized (output) {
            text = new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
        assertTrue("the child JVM ended in time: " + text, ended);
        assertEquals("child exit code; output: " + text, 0, p.exitValue());
        assertTrue(text, text.contains("FORWARDED " + GIB + " "));
    }

    // ---- AC-8 ----

    // FR-PROXY-007 AC-8: for a body without length headers the one-byte pre-read reads the client body after
    // req.bodyStream(...) took the exchange input and before the backend connection is opened; the concurrency slot
    // and the total-timeout watchdog go between the pre-read and the connect (design D-050, D-056)
    @Test(timeout = 30000)
    public void fr_PROXY_007_AC8_preReadRunsAfterBodyStreamAndBeforeTheBackendConnect() throws Exception {
        List<String> steps = Collections.synchronizedList(new ArrayList<String>());
        StepRecordingAdapter recording = new StepRecordingAdapter(steps);
        try (AcceptRecordingBackend backend = new AcceptRecordingBackend(steps)) {
            RestApp a = RestApp.builder().httpAdapter(recording).jsonCodec(new StubJsonCodec()).build();
            a.use("/api", Proxy.to(backend.url()));
            a.listen(0);
            started.add(a);

            ClientResponse r = recording.delegate.send(request("POST", "X-A", "1").body(HELLO));

            assertEquals("status", 200, r.status());
            List<String> seen;
            synchronized (steps) {
                seen = new ArrayList<>(steps);
            }
            assertEquals(Arrays.asList("bodyStream", "preRead", "connect"), firstOfEach(seen));
        }
    }

    /** The first occurrence of each step name, in order. */
    private static List<String> firstOfEach(List<String> steps) {
        List<String> out = new ArrayList<>();
        for (String s : steps) {
            if (!out.contains(s)) {
                out.add(s);
            }
        }
        return out;
    }

    // ---- test support ----

    /** Every byte until the server closes, as ISO-8859-1 text. */
    private static String readToEnd(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        try {
            for (int n; (n = in.read(b)) >= 0; ) {
                buf.write(b, 0, n);
            }
        } catch (SocketException reset) {
            // a reset after the close counts as the end
        }
        return new String(buf.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    /** Reads one head up to and excluding the blank line, as ISO-8859-1 text. */
    private static String readHead(InputStream in) throws IOException {
        StringBuilder head = new StringBuilder();
        while (head.length() < 4 || !head.substring(head.length() - 4).equals("\r\n\r\n")) {
            int b = in.read();
            if (b < 0) {
                throw new EOFException("EOF inside a head: " + head);
            }
            head.append((char) b);
        }
        return head.substring(0, head.length() - 4);
    }

    /** One CRLF- or LF-terminated line without its terminator. */
    private static String readLine(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        for (int b; (b = in.read()) != '\n'; ) {
            if (b < 0) {
                throw new EOFException("EOF inside a line");
            }
            if (b != '\r') {
                line.append((char) b);
            }
        }
        return line.toString();
    }

    /**
     * A backend that accepts connections, reads one request head and then counts the bytes after it until the proxy
     * closes the connection, without answering; it tells whether a connection was accepted and closed.
     */
    private static final class ByteCountingBackend implements Closeable {
        private final ServerSocket server;
        private final Thread acceptor;
        private final AtomicInteger accepted = new AtomicInteger();
        private final AtomicInteger bodyBytes = new AtomicInteger(-1);
        private final AtomicBoolean closedByPeer = new AtomicBoolean();
        private final Object lock = new Object();

        ByteCountingBackend() throws IOException {
            server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
            acceptor = new Thread(this::serve, "byte-counting-backend-" + server.getLocalPort());
            acceptor.setDaemon(true);
            acceptor.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getLocalPort();
        }

        private void serve() {
            while (!server.isClosed()) {
                try (Socket s = server.accept()) {
                    signal(accepted::incrementAndGet);
                    s.setSoTimeout(30000);
                    InputStream in = s.getInputStream();
                    int count = 0;
                    try {
                        readHead(in);
                        byte[] b = new byte[64];
                        for (int n; (n = in.read(b)) >= 0; ) {
                            count += n;
                        }
                        int total = count;
                        signal(() -> {
                            bodyBytes.set(total);
                            closedByPeer.set(true);
                        });
                    } catch (EOFException | SocketException closedEarly) {
                        // closed inside the head or reset: the request never completed here
                        int total = count;
                        signal(() -> {
                            bodyBytes.set(total);
                            closedByPeer.set(true);
                        });
                    } catch (SocketTimeoutException stillOpen) {
                        // the proxy kept the connection open: closedByPeer stays false
                    }
                } catch (IOException e) {
                    // the listener was closed
                }
            }
        }

        private void signal(Runnable change) {
            synchronized (lock) {
                change.run();
                lock.notifyAll();
            }
        }

        boolean awaitAccepted(long amount, TimeUnit unit) throws InterruptedException {
            return await(amount, unit, () -> accepted.get() > 0);
        }

        boolean awaitClosed(long amount, TimeUnit unit) throws InterruptedException {
            return await(amount, unit, closedByPeer::get);
        }

        private boolean await(long amount, TimeUnit unit, java.util.function.BooleanSupplier done)
                throws InterruptedException {
            long deadline = System.nanoTime() + unit.toNanos(amount);
            synchronized (lock) {
                while (!done.getAsBoolean()) {
                    long left = deadline - System.nanoTime();
                    if (left <= 0) {
                        return false;
                    }
                    TimeUnit.NANOSECONDS.timedWait(lock, left);
                }
                return true;
            }
        }

        int accepted() {
            return accepted.get();
        }

        int bodyBytes() {
            return bodyBytes.get();
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

    /**
     * A backend that records {@code connect} in the step list when it accepts a connection, then reads a request with
     * a chunked or Content-Length body (or none) and answers 200 ok.
     */
    private static final class AcceptRecordingBackend implements Closeable {
        private final ServerSocket server;
        private final Thread acceptor;

        AcceptRecordingBackend(List<String> steps) throws IOException {
            server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
            acceptor = new Thread(() -> {
                while (!server.isClosed()) {
                    try (Socket s = server.accept()) {
                        steps.add("connect");
                        s.setSoTimeout(10000);
                        InputStream in = s.getInputStream();
                        String head = readHead(in).toLowerCase(Locale.ROOT);
                        if (head.contains("\r\ntransfer-encoding: chunked")) {
                            while (true) {
                                int size = Integer.parseInt(readLine(in).trim(), 16);
                                if (size == 0) {
                                    readLine(in);
                                    break;
                                }
                                for (int i = 0; i < size; i++) {
                                    if (in.read() < 0) {
                                        throw new EOFException("EOF inside a chunk");
                                    }
                                }
                                readLine(in);
                            }
                        }
                        OutputStream out = s.getOutputStream();
                        out.write(RawBackend.response(200, "ok").getBytes(StandardCharsets.ISO_8859_1));
                        out.flush();
                    } catch (IOException | RuntimeException e) {
                        // the listener was closed, or a broken exchange ends this connection only
                    }
                }
            }, "accept-recording-backend-" + server.getLocalPort());
            acceptor.setDaemon(true);
            acceptor.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getLocalPort();
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

    /**
     * An in-memory adapter whose exchanges record {@code bodyStream} when the core takes the request input (which
     * {@code req.bodyStream(...)} does) and {@code preRead} on the first read of that input.
     */
    private static final class StepRecordingAdapter implements HttpAdapter {
        final InMemoryHttpAdapter delegate = new InMemoryHttpAdapter();
        private final List<String> steps;

        StepRecordingAdapter(List<String> steps) {
            this.steps = steps;
        }

        @Override
        public void bind(String host, int port, ExchangeHandler handler) throws IOException {
            delegate.bind(host, port, exchange -> handler.handle(new StepRecordingExchange(exchange, steps)));
        }

        @Override
        public int boundPort() {
            return delegate.boundPort();
        }

        @Override
        public void stop(long timeout, TimeUnit unit) {
            delegate.stop(timeout, unit);
        }

        @Override
        public void release() {
            delegate.release();
        }
    }

    /** An exchange whose request input records the steps; everything else goes to its delegate. */
    private static final class StepRecordingExchange implements HttpExchange {
        private final HttpExchange d;
        private final List<String> steps;

        StepRecordingExchange(HttpExchange d, List<String> steps) {
            this.d = d;
            this.steps = steps;
        }

        @Override
        public String httpVersion() {
            return d.httpVersion();
        }

        @Override
        public String methodToken() {
            return d.methodToken();
        }

        @Override
        public String rawPath() {
            return d.rawPath();
        }

        @Override
        public String rawQuery() {
            return d.rawQuery();
        }

        @Override
        public List<Map.Entry<String, String>> headers() {
            return d.headers();
        }

        @Override
        public InputStream requestBody() {
            steps.add("bodyStream");
            return new FilterInputStream(d.requestBody()) {
                @Override
                public int read() throws IOException {
                    steps.add("preRead");
                    return super.read();
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    steps.add("preRead");
                    return super.read(b, off, len);
                }
            };
        }

        @Override
        public String remoteAddress() {
            return d.remoteAddress();
        }

        @Override
        public boolean isSecure() {
            return d.isSecure();
        }

        @Override
        public OutputStream commit(int status, List<Map.Entry<String, String>> headers, long contentLength)
                throws IOException {
            return d.commit(status, headers, contentLength);
        }

        @Override
        public void finish() throws IOException {
            d.finish();
        }

        @Override
        public void abort() {
            d.abort();
        }

        @Override
        public void sendFile(int status, List<Map.Entry<String, String>> headers, File file, long offset, long length)
                throws IOException {
            d.sendFile(status, headers, file, offset, length);
        }

        @Override
        public void upgrade(List<Map.Entry<String, String>> headers, UpgradeHandler handler) throws IOException {
            d.upgrade(headers, handler);
        }

        @Override
        public void eventStream(int status, List<Map.Entry<String, String>> headers, StreamDrain drain)
                throws IOException {
            d.eventStream(status, headers, drain);
        }

        @Override
        public Runnable retainRequest() {
            return d.retainRequest();
        }

        @Override
        public void onAbort(Runnable listener) {
            d.onAbort(listener);
        }

        @Override
        public int responseStatus() {
            return d.responseStatus();
        }

        @Override
        public long responseBytesWritten() {
            return d.responseBytesWritten();
        }

        @Override
        public void webSocket(WebSocketSettings settings, WebSocketEndpoint endpoint) throws IOException {
            d.webSocket(settings, endpoint);
        }
    }

    /**
     * The child JVM of the 1 GiB test: a TinyHttpd app with the proxy at /api and {@code .bodyLimit(1 GiB)}, a sink
     * backend that decodes and checksums the body without keeping it, and a client that generates the body on the
     * fly. Prints {@code FORWARDED <bytes> <crc>} and exits 0 when the backend saw every byte the client sent.
     * Arguments: {@code chunked} or {@code length}, then the body size in bytes.
     */
    public static final class HugeBodyMain {

        private HugeBodyMain() { }

        public static void main(String[] args) throws Exception {
            boolean chunked = args[0].equals("chunked");
            long total = Long.parseLong(args[1]);
            ServerSocket sinkServer = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
            Thread sink = new Thread(() -> sinkOne(sinkServer), "sink-backend");
            sink.setDaemon(true);
            sink.start();
            RestApp g = RestApp.builder().httpAdapter(new TinyHttpdAdapter()).jsonCodec(new StubJsonCodec())
                    .logger(new RecordingLogger()).build();
            g.use("/api", Proxy.to("http://127.0.0.1:" + sinkServer.getLocalPort()).bodyLimit(GIB));
            int port = g.listen(0).port();
            CRC32 sent = new CRC32();
            String response;
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", port), 5000);
                s.setSoTimeout(120000);
                OutputStream out = new BufferedOutputStream(s.getOutputStream(), 65536);
                out.write(("POST /api/x HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n"
                        + (chunked ? "Transfer-Encoding: chunked" : "Content-Length: " + total) + "\r\n\r\n")
                        .getBytes(StandardCharsets.ISO_8859_1));
                byte[] block = new byte[65536];
                long seed = 0x9E3779B97F4A7C15L;
                for (long left = total; left > 0; ) {
                    int n = (int) Math.min(block.length, left);
                    for (int i = 0; i < n; i += 8) {
                        seed ^= seed << 13;
                        seed ^= seed >>> 7;
                        seed ^= seed << 17;
                        for (int k = 0; k < 8 && i + k < n; k++) {
                            block[i + k] = (byte) (seed >>> (8 * k));
                        }
                    }
                    sent.update(block, 0, n);
                    if (chunked) {
                        out.write((Integer.toHexString(n) + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
                        out.write(block, 0, n);
                        out.write('\r');
                        out.write('\n');
                    } else {
                        out.write(block, 0, n);
                    }
                    left -= n;
                }
                if (chunked) {
                    out.write("0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                }
                out.flush();
                response = readToEnd(s.getInputStream());
            }
            g.shutdown(2, TimeUnit.SECONDS);
            String expected = total + " " + Long.toHexString(sent.getValue());
            System.out.println("response: " + response);
            if (response.startsWith("HTTP/1.1 200 ") && response.endsWith("\r\n\r\n" + expected)) {
                System.out.println("FORWARDED " + expected);
                System.exit(0);
            }
            System.out.println("NOT FORWARDED, expected " + expected);
            System.exit(1);
        }

        /** Serves one request: decodes its body into a byte count and CRC-32 and answers them as the 200 body. */
        private static void sinkOne(ServerSocket server) {
            try (Socket s = server.accept()) {
                s.setSoTimeout(120000);
                InputStream in = new BufferedInputStream(s.getInputStream(), 65536);
                String head = readHead(in).toLowerCase(Locale.ROOT);
                CRC32 crc = new CRC32();
                long count = 0;
                byte[] b = new byte[65536];
                if (head.contains("\r\ntransfer-encoding: chunked")) {
                    while (true) {
                        long size = Long.parseLong(readLine(in).trim(), 16);
                        if (size == 0) {
                            while (!readLine(in).isEmpty()) {
                                // trailer lines are dropped
                            }
                            break;
                        }
                        for (long left = size; left > 0; ) {
                            int n = in.read(b, 0, (int) Math.min(b.length, left));
                            if (n < 0) {
                                throw new EOFException("EOF inside a chunk");
                            }
                            crc.update(b, 0, n);
                            count += n;
                            left -= n;
                        }
                        readLine(in);
                    }
                } else {
                    int at = head.indexOf("\r\ncontent-length:");
                    long length = 0;
                    if (at >= 0) {
                        int end = head.indexOf("\r\n", at + 2);
                        length = Long.parseLong(head.substring(at + 17, end < 0 ? head.length() : end).trim());
                    }
                    for (long left = length; left > 0; ) {
                        int n = in.read(b, 0, (int) Math.min(b.length, left));
                        if (n < 0) {
                            throw new EOFException("EOF inside the body");
                        }
                        crc.update(b, 0, n);
                        count += n;
                        left -= n;
                    }
                }
                String body = count + " " + Long.toHexString(crc.getValue());
                OutputStream out = s.getOutputStream();
                out.write(RawBackend.response(200, body).getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
            } catch (IOException | RuntimeException e) {
                System.out.println("sink failed: " + e);
            }
        }
    }
}
