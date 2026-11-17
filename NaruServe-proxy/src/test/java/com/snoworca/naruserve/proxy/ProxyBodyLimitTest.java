package com.snoworca.naruserve.proxy;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.BodyAlreadyConsumedException;
import com.snoworca.naruserve.BodyTooLargeException;
import com.snoworca.naruserve.HttpException;
import com.snoworca.naruserve.Middleware;
import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.spi.ExchangeHandler;
import com.snoworca.naruserve.spi.HttpAdapter;
import com.snoworca.naruserve.spi.HttpExchange;
import com.snoworca.naruserve.spi.JsonCodec;
import com.snoworca.naruserve.spi.StreamDrain;
import com.snoworca.naruserve.spi.UpgradeHandler;
import com.snoworca.naruserve.spi.WebSocketEndpoint;
import com.snoworca.naruserve.spi.WebSocketSettings;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.testing.StubJsonCodec;
import com.snoworca.naruserve.tinyhttpd.TinyHttpdAdapter;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Test;

/**
 * The proxy's body limit and client body failures (FR-PROXY-008): a declared length over {@code .bodyLimit(n)} or
 * over the app's {@code rawBodyLimit} answers 413 before any backend connection; a chunked body that passes the limit
 * while it is forwarded closes the backend connection and reaches the error flow as {@code HttpException(413)} with
 * the {@link BodyTooLargeException} as cause; any other exception while reading the client body closes the backend
 * connection and reaches the error flow unwrapped; a body earlier code took answers 500 without connecting.
 */
public class ProxyBodyLimitTest {

    private static final long MIB = 1024L * 1024L;

    private final List<Closeable> closeables = new ArrayList<>();
    private final List<RestApp> started = new ArrayList<>();
    /** The errors that reached the app's onError, in order. */
    private final List<Throwable> errors = Collections.synchronizedList(new ArrayList<Throwable>());
    private final CountDownLatch firstError = new CountDownLatch(1);

    @After
    public void tearDown() throws IOException {
        for (RestApp a : started) {
            a.shutdown(2, TimeUnit.SECONDS);
        }
        for (Closeable c : closeables) {
            c.close();
        }
    }

    // ---- apps ----

    /** An app on {@code adapter} with {@code codec}; {@link #captureErrors} goes after its mounts. */
    private RestApp app(HttpAdapter adapter, JsonCodec codec) {
        RestApp a = RestApp.builder().httpAdapter(adapter).jsonCodec(codec).logger(new RecordingLogger()).build();
        started.add(a);
        return a;
    }

    /**
     * Registers, after the mounts it covers, an onError handler that records the error and leaves the response to
     * the default error flow, so the client sees the status the core chooses.
     */
    private RestApp captureErrors(RestApp a) {
        return a.onError((error, req, res) -> {
            errors.add(error);
            firstError.countDown();
        });
    }

    /** The only error that reached onError. */
    private Throwable onlyError() {
        synchronized (errors) {
            assertEquals("errors that reached onError: " + errors, 1, errors.size());
            return errors.get(0);
        }
    }

    private ObservingBackend observingBackend() throws IOException {
        ObservingBackend b = new ObservingBackend();
        closeables.add(b);
        return b;
    }

    private RawBackend okBackend() throws IOException {
        RawBackend b = RawBackend.answering(RawBackend.response(200, "ok"));
        closeables.add(b);
        return b;
    }

    /** {@code method /api/x} with the given header name/value pairs, in order, and {@code body}. */
    private static ClientRequest request(String method, byte[] body, String... headerPairs) {
        ClientRequest r = ClientRequest.of(method, "/api/x");
        for (int i = 0; i < headerPairs.length; i += 2) {
            r.header(headerPairs[i], headerPairs[i + 1]);
        }
        return r.body(body);
    }

    /** {@code n} pseudo-random bytes from {@code seed}. */
    private static byte[] randomBytes(int n, long seed) {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        return b;
    }

    // ---- AC-1 ----

    // FR-PROXY-008 AC-1: a Content-Length over .bodyLimit(n) answers 413 and no backend connection is opened
    @Test
    public void fr_PROXY_008_AC1_contentLengthOverTheBodyLimitAnswers413WithoutConnecting() throws IOException {
        ObservingBackend backend = observingBackend();
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        RestApp a = app(adapter, new StubJsonCodec());
        a.use("/api", Proxy.to(backend.url()).bodyLimit(1024));
        captureErrors(a).listen(0);

        ClientResponse r = adapter.send(request("POST", new byte[2048], "Content-Length", "2048"));

        assertEquals("status", 413, r.status());
        assertEquals("backend connections opened", 0, backend.connectionsBeforeSentinel());
    }

    // FR-PROXY-008 AC-1: a Content-Length over the app's rawBodyLimit on a mount without .bodyLimit answers 413 and no
    // backend connection is opened
    @Test
    public void fr_PROXY_008_AC1_contentLengthOverTheAppRawBodyLimitAnswers413WithoutConnecting() throws IOException {
        ObservingBackend backend = observingBackend();
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        RestApp a = app(adapter, new StubJsonCodec());
        a.rawBodyLimit(1024);
        a.use("/api", Proxy.to(backend.url()));
        captureErrors(a).listen(0);

        ClientResponse r = adapter.send(request("POST", new byte[2048], "Content-Length", "2048"));

        assertEquals("status", 413, r.status());
        assertEquals("backend connections opened", 0, backend.connectionsBeforeSentinel());
    }

    // FR-PROXY-008 AC-1: the Content-Length list 2048, 2048 (one agreed length, SEC-PROXY-003) over .bodyLimit(1024)
    // also answers 413 before any backend connection, not only after the copy passed the limit
    @Test
    public void fr_PROXY_008_AC1_contentLengthListOverTheBodyLimitAnswers413WithoutConnecting() throws IOException {
        ObservingBackend backend = observingBackend();
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        RestApp a = app(adapter, new StubJsonCodec());
        a.use("/api", Proxy.to(backend.url()).bodyLimit(1024));
        captureErrors(a).listen(0);

        ClientResponse r = adapter.send(request("POST", new byte[2048], "Content-Length", "2048, 2048"));

        assertEquals("status", 413, r.status());
        assertEquals("backend connections opened", 0, backend.connectionsBeforeSentinel());
    }

    // FR-PROXY-008 AC-1: on a mount without .bodyLimit the Content-Length list 2048, 2048 (one agreed length) over the
    // app's rawBodyLimit answers 413 and no backend connection is opened: req.bodyStream() refuses it before any read
    // (FR-HTTP-031 AC-4), not only after the copy passed the limit
    @Test
    public void fr_PROXY_008_AC1_contentLengthListOverTheAppRawBodyLimitAnswers413WithoutConnecting()
            throws IOException {
        ObservingBackend backend = observingBackend();
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        RestApp a = app(adapter, new StubJsonCodec());
        a.rawBodyLimit(1024);
        a.use("/api", Proxy.to(backend.url()));
        captureErrors(a).listen(0);

        ClientResponse r = adapter.send(request("POST", new byte[2048], "Content-Length", "2048, 2048"));

        assertEquals("status", 413, r.status());
        assertEquals("backend connections opened", 0, backend.connectionsBeforeSentinel());
    }

    // FR-PROXY-008 AC-1: over a TinyHttpd socket, which frames "Content-Length: 2048, 2048" as 2048 bytes, the list over
    // the app's rawBodyLimit on a mount without .bodyLimit answers 413 and no backend connection is opened
    @Test(timeout = 30000)
    public void fr_PROXY_008_AC1_contentLengthListOverTheAppRawBodyLimitOverASocketAnswers413WithoutConnecting()
            throws IOException {
        ObservingBackend backend = observingBackend();
        RestApp a = app(new TinyHttpdAdapter(), new StubJsonCodec());
        a.rawBodyLimit(1024);
        a.use("/api", Proxy.to(backend.url()));
        int port = captureErrors(a).listen(0).port();

        String response = exchange(port, "POST /api/x HTTP/1.1\r\nContent-Length: 2048, 2048", new byte[2048]);

        assertTrue(response, response.startsWith("HTTP/1.1 413 "));
        assertEquals("backend connections opened", 0, backend.connectionsBeforeSentinel());
    }

    // FR-PROXY-008 AC-1: over a TinyHttpd socket a Content-Length over .bodyLimit(n) answers 413 and no backend
    // connection is opened
    @Test(timeout = 30000)
    public void fr_PROXY_008_AC1_contentLengthOverTheBodyLimitOverASocketAnswers413WithoutConnecting()
            throws IOException {
        ObservingBackend backend = observingBackend();
        RestApp a = app(new TinyHttpdAdapter(), new StubJsonCodec());
        a.use("/api", Proxy.to(backend.url()).bodyLimit(1024));
        int port = captureErrors(a).listen(0).port();

        String response = exchange(port, "POST /api/x HTTP/1.1\r\nContent-Length: 2048", new byte[2048]);

        assertTrue(response, response.startsWith("HTTP/1.1 413 "));
        assertEquals("backend connections opened", 0, backend.connectionsBeforeSentinel());
    }

    // ---- AC-2 ----

    // FR-PROXY-008 AC-2: on an app whose rawBodyLimit is 1 MiB a mount with .bodyLimit(2097152) forwards a 1.5 MiB
    // body completely, sent with Content-Length and chunked
    @Test(timeout = 60000)
    public void fr_PROXY_008_AC2_bodyLimitAboveTheAppLimitForwardsALargerBody() throws IOException {
        RawBackend backend = okBackend();
        RestApp a = app(new TinyHttpdAdapter(), new StubJsonCodec());
        a.rawBodyLimit(MIB);
        a.use("/api", Proxy.to(backend.url()).bodyLimit(2097152));
        int port = captureErrors(a).listen(0).port();
        byte[] body = randomBytes((int) (MIB + MIB / 2), 81);

        String byLength = exchange(port, "POST /api/x HTTP/1.1\r\nContent-Length: " + body.length, body);
        String chunked = exchange(port, "POST /api/x HTTP/1.1\r\nTransfer-Encoding: chunked",
                chunkedCoding(body, 65536));

        assertTrue(byLength, byLength.startsWith("HTTP/1.1 200 "));
        assertTrue(chunked, chunked.startsWith("HTTP/1.1 200 "));
        List<RawBackend.Received> got = backend.requests();
        assertEquals(2, got.size());
        assertArrayEquals(body, got.get(0).body);
        assertArrayEquals(body, got.get(1).body);
    }

    // FR-PROXY-008 AC-2: on an app whose rawBodyLimit is 1 MiB a mount with .bodyLimit(1024) answers 413 to a 2048-byte
    // Content-Length body without connecting, while a mount without the option forwards the same body
    @Test(timeout = 30000)
    public void fr_PROXY_008_AC2_bodyLimitBelowTheAppLimitAnswers413() throws IOException {
        ObservingBackend limited = observingBackend();
        RawBackend open = okBackend();
        RestApp a = app(new TinyHttpdAdapter(), new StubJsonCodec());
        a.rawBodyLimit(MIB);
        a.use("/small", Proxy.to(limited.url()).bodyLimit(1024));
        a.use("/api", Proxy.to(open.url()));
        int port = captureErrors(a).listen(0).port();
        byte[] body = randomBytes(2048, 82);

        String small = exchange(port, "POST /small/x HTTP/1.1\r\nContent-Length: 2048", body);
        String whole = exchange(port, "POST /api/x HTTP/1.1\r\nContent-Length: 2048", body);

        assertTrue(small, small.startsWith("HTTP/1.1 413 "));
        assertEquals("backend connections opened", 0, limited.connectionsBeforeSentinel());
        assertTrue(whole, whole.startsWith("HTTP/1.1 200 "));
        assertArrayEquals(body, open.onlyRequest().body);
    }

    // ---- AC-3 ----

    // FR-PROXY-008 AC-3: a chunked body that passes .bodyLimit(n) while it is forwarded closes the backend connection
    // before the request is complete, the client receives 413 and onError sees HttpException 413 whose cause is a
    // com.snoworca.naruserve.BodyTooLargeException
    @Test(timeout = 60000)
    public void fr_PROXY_008_AC3_chunkedBodyPassingTheLimitClosesTheBackendAndAnswers413() throws Exception {
        ObservingBackend backend = observingBackend();
        RestApp a = app(new TinyHttpdAdapter(), new StubJsonCodec());
        a.use("/api", Proxy.to(backend.url()).bodyLimit(1024));
        int port = captureErrors(a).listen(0).port();

        String response = exchange(port, "POST /api/x HTTP/1.1\r\nTransfer-Encoding: chunked",
                chunkedCoding(randomBytes(4096, 83), 512));

        assertTrue(response, response.startsWith("HTTP/1.1 413 "));
        Throwable error = onlyError();
        assertEquals(HttpException.class, error.getClass());
        assertEquals(413, ((HttpException) error).status());
        assertNotNull("cause", error.getCause());
        assertEquals(BodyTooLargeException.class, error.getCause().getClass());
        ObservingBackend.Connection c = backend.awaitOnlyConnectionClosed(20, TimeUnit.SECONDS);
        assertFalse("the backend received the last chunk: " + c.text(), c.text().endsWith("\r\n0\r\n\r\n"));
    }

    // ---- AC-4 ----

    // FR-PROXY-008 AC-4: the unchecked HttpException 400, 408 and 413 a body stream throws from read() (as the
    // TinyHttpd body stream does) close the backend connection before the request is complete and reach onError
    // unwrapped, the client receiving that status and not 502
    @Test(timeout = 60000)
    public void fr_PROXY_008_AC4_uncheckedHttpExceptionFromTheBodyStreamReachesOnErrorUnwrapped() throws Exception {
        for (int status : new int[] {400, 408, 413}) {
            HttpException thrown = new HttpException(status);

            ClientResponse r = sendWithFailingBody(thrown, "Content-Length", "10");

            assertEquals("status for " + status, status, r.status());
        }
    }

    // FR-PROXY-008 AC-4: an IOException a body stream throws from read() closes the backend connection and reaches
    // onError unwrapped, the client receiving 500 and not 502
    @Test(timeout = 30000)
    public void fr_PROXY_008_AC4_ioExceptionFromTheBodyStreamReachesOnErrorUnwrapped() throws Exception {
        ClientResponse r = sendWithFailingBody(new IOException("client went away"), "Content-Length", "10");

        assertEquals("status", 500, r.status());
    }

    // FR-PROXY-008 AC-4: any other RuntimeException a body stream throws while a chunked body is copied closes the
    // backend connection and reaches onError unwrapped
    @Test(timeout = 30000)
    public void fr_PROXY_008_AC4_runtimeExceptionWhileCopyingAChunkedBodyReachesOnErrorUnwrapped() throws Exception {
        ClientResponse r = sendWithFailingBody(new IllegalStateException("adapter stream failed"),
                "Transfer-Encoding", "chunked");

        assertEquals("status", 500, r.status());
    }

    /**
     * Sends POST /api/x with a 10-byte body through an in-memory adapter whose request body gives 5 bytes and then
     * throws {@code failure}; asserts that the proxy connected, that the backend connection was closed without a
     * complete request and that {@code failure} itself reached onError. Returns the client response.
     */
    private ClientResponse sendWithFailingBody(Throwable failure, String... headerPairs) throws Exception {
        errors.clear();
        ObservingBackend backend = observingBackend();
        FailingBodyAdapter adapter = new FailingBodyAdapter(5, failure);
        RestApp a = app(adapter, new StubJsonCodec());
        a.use("/api", Proxy.to(backend.url()));
        captureErrors(a).listen(0);

        ClientResponse r = adapter.delegate.send(request("POST", "abcdefghij".getBytes(StandardCharsets.ISO_8859_1),
                headerPairs));

        assertSame("the exception that reached onError", failure, onlyError());
        ObservingBackend.Connection c = backend.awaitOnlyConnectionClosed(20, TimeUnit.SECONDS);
        assertFalse("the backend received the whole body: " + c.text(), c.text().endsWith("abcdefghij")
                || c.text().endsWith("\r\n0\r\n\r\n"));
        return r;
    }

    // FR-PROXY-008 AC-4: a client that sends Content-Length: 10, 5 body bytes and disconnects over a real TinyHttpd
    // socket (design D-069) makes the proxy close the backend connection, and the read failure reaches onError
    // unwrapped: the body stream's own exception, not a 502 and not the proxy's internal carrier
    @Test(timeout = 60000)
    public void fr_PROXY_008_AC4_clientDisconnectOverASocketReachesOnErrorUnwrapped() throws Exception {
        ObservingBackend backend = observingBackend();
        RestApp a = app(new TinyHttpdAdapter(), new StubJsonCodec());
        a.use("/api", Proxy.to(backend.url()));
        int port = captureErrors(a).listen(0).port();

        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            OutputStream out = s.getOutputStream();
            out.write(("POST /api/x HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: 10\r\n\r\nabcde")
                    .getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            assertTrue("the proxy connected to the backend", backend.awaitAccepted(20, TimeUnit.SECONDS));
        }

        ObservingBackend.Connection c = backend.awaitOnlyConnectionClosed(20, TimeUnit.SECONDS);
        String text = c.text();
        int bodyStart = text.indexOf("\r\n\r\n");
        assertTrue("body bytes at the backend: " + text, bodyStart < 0 || text.length() - bodyStart - 4 < 10);
        assertTrue("an error reached onError", firstError.await(20, TimeUnit.SECONDS));
        Throwable error = onlyError();
        assertFalse("wrapped in the proxy's carrier: " + error, error instanceof RequestBodyCopier.ClientBodyFailure);
        boolean readFailure = error instanceof IOException
                || error instanceof HttpException && ((HttpException) error).status() != 502;
        assertTrue("the client read failure itself: " + error, readFailure);
        assertFalse("wrapped: " + error, error instanceof HttpException && error.getCause() instanceof IOException
                && ((HttpException) error).status() == 502);
    }

    // ---- AC-5 ----

    // FR-PROXY-008 AC-5: a body an earlier middleware read with req.body(...) answers 500 without a backend
    // connection, and the BodyAlreadyConsumedException from req.bodyStream reaches onError unwrapped
    @Test(timeout = 30000)
    public void fr_PROXY_008_AC5_bodyReadWithReqBodyAnswers500WithoutConnecting() throws IOException {
        ObservingBackend backend = observingBackend();
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        RestApp a = app(adapter, new TextJsonCodec());
        AtomicReference<Object> parsed = new AtomicReference<>();
        a.use("/api", (req, res, next) -> {
            parsed.set(req.body(String.class));
            next.run();
        });
        a.use("/api", Proxy.to(backend.url()));
        captureErrors(a).listen(0);

        ClientResponse r = adapter.send(request("POST", "\"hi\"".getBytes(StandardCharsets.UTF_8),
                "Content-Type", "application/json", "Content-Length", "4"));

        assertEquals("the middleware read the body", "\"hi\"", parsed.get());
        assertEquals("status", 500, r.status());
        assertEquals("backend connections opened", 0, backend.connectionsBeforeSentinel());
        assertEquals(BodyAlreadyConsumedException.class, onlyError().getClass());
    }

    // FR-PROXY-008 AC-5: a body an earlier middleware partly read through req.bodyStream() answers 500 without a
    // backend connection, and the BodyAlreadyConsumedException from req.bodyStream reaches onError unwrapped, on a
    // mount with and without .bodyLimit
    @Test(timeout = 30000)
    public void fr_PROXY_008_AC5_bodyPartlyReadThroughBodyStreamAnswers500WithoutConnecting() throws IOException {
        ObservingBackend backend = observingBackend();
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        RestApp a = app(adapter, new StubJsonCodec());
        Middleware readOneByte = (req, res, next) -> {
            assertEquals('h', req.bodyStream().read());
            next.run();
        };
        a.use("/plain", readOneByte);
        a.use("/plain", Proxy.to(backend.url()));
        a.use("/limited", readOneByte);
        a.use("/limited", Proxy.to(backend.url()).bodyLimit(4096));
        captureErrors(a).listen(0);
        byte[] hello = "hello".getBytes(StandardCharsets.ISO_8859_1);

        ClientResponse plain = adapter.send(ClientRequest.of("POST", "/plain/x").header("Content-Length", "5")
                .body(hello));
        ClientResponse limited = adapter.send(ClientRequest.of("POST", "/limited/x").header("Content-Length", "5")
                .body(hello));

        assertEquals("status without .bodyLimit", 500, plain.status());
        assertEquals("status with .bodyLimit", 500, limited.status());
        assertEquals("backend connections opened", 0, backend.connectionsBeforeSentinel());
        synchronized (errors) {
            assertEquals(2, errors.size());
            assertEquals(BodyAlreadyConsumedException.class, errors.get(0).getClass());
            assertEquals(BodyAlreadyConsumedException.class, errors.get(1).getClass());
        }
    }

    // FR-PROXY-008 AC-5: a body an earlier middleware took through req.bodyStream() under another limit makes the
    // proxy's req.bodyStream(n) throw IllegalStateException, which answers 500 without a backend connection and
    // reaches onError unwrapped
    @Test(timeout = 30000)
    public void fr_PROXY_008_AC5_bodyTakenUnderAnotherLimitAnswers500WithoutConnecting() throws IOException {
        ObservingBackend backend = observingBackend();
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        RestApp a = app(adapter, new StubJsonCodec());
        a.use("/api", (req, res, next) -> {
            req.bodyStream();
            next.run();
        });
        a.use("/api", Proxy.to(backend.url()).bodyLimit(4096));
        captureErrors(a).listen(0);

        ClientResponse r = adapter.send(request("POST", "hello".getBytes(StandardCharsets.ISO_8859_1),
                "Content-Length", "5"));

        assertEquals("status", 500, r.status());
        assertEquals("backend connections opened", 0, backend.connectionsBeforeSentinel());
        assertEquals(IllegalStateException.class, onlyError().getClass());
    }

    // ---- sockets ----

    /** {@code body} in the chunked coding with chunks of {@code size} bytes and the last chunk. */
    private static byte[] chunkedCoding(byte[] body, int size) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int off = 0; off < body.length; off += size) {
            int n = Math.min(size, body.length - off);
            byte[] line = (Integer.toHexString(n) + "\r\n").getBytes(StandardCharsets.ISO_8859_1);
            out.write(line, 0, line.length);
            out.write(body, off, n);
            out.write('\r');
            out.write('\n');
        }
        byte[] last = "0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
        out.write(last, 0, last.length);
        return out.toByteArray();
    }

    /**
     * Writes {@code head} (the request line and header lines without the blank line; {@code Host} and
     * {@code Connection: close} are added) and {@code body} to the port and returns everything the server sent until
     * it closed, as ISO-8859-1 text.
     */
    private static String exchange(int port, String head, byte[] body) throws IOException {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            s.setSoTimeout(20000);
            OutputStream out = new BufferedOutputStream(s.getOutputStream());
            out.write((head + "\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.write(body);
            out.flush();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            try {
                InputStream in = s.getInputStream();
                for (int n; (n = in.read(b)) >= 0; ) {
                    buf.write(b, 0, n);
                }
            } catch (SocketException reset) {
                // a reset after the response counts as the end
            }
            return new String(buf.toByteArray(), StandardCharsets.ISO_8859_1);
        }
    }

    // ---- test support ----

    /** A JSON codec whose decode returns the body text as it is; it encodes like {@link StubJsonCodec}. */
    private static final class TextJsonCodec implements JsonCodec {
        private final StubJsonCodec stub = new StubJsonCodec();

        @Override
        @SuppressWarnings("unchecked")
        public <T> T decode(Reader reader, Type type) throws IOException {
            StringBuilder text = new StringBuilder();
            char[] c = new char[256];
            for (int n; (n = reader.read(c)) >= 0; ) {
                text.append(c, 0, n);
            }
            return (T) text.toString();
        }

        @Override
        public void encode(Object value, Writer writer) throws IOException {
            stub.encode(value, writer);
        }
    }

    /**
     * A loopback backend that records, per accepted connection, the client port and every byte until the proxy
     * closes the connection, without answering. {@link #connectionsBeforeSentinel} closes the race between a connect
     * that already completed and the acceptor thread that has not yet taken it.
     */
    private static final class ObservingBackend implements Closeable {

        /** One accepted connection. */
        static final class Connection {
            final int clientPort;
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            volatile boolean closedByPeer;

            Connection(int clientPort) {
                this.clientPort = clientPort;
            }

            String text() {
                synchronized (bytes) {
                    return new String(bytes.toByteArray(), StandardCharsets.ISO_8859_1);
                }
            }
        }

        private final ServerSocket server;
        private final Thread acceptor;
        private final List<Connection> connections = new ArrayList<>();
        private final List<Socket> open = Collections.synchronizedList(new ArrayList<Socket>());

        ObservingBackend() throws IOException {
            server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
            acceptor = new Thread(this::acceptLoop, "observing-backend-" + server.getLocalPort());
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
                open.add(s);
                Connection c = new Connection(s.getPort());
                synchronized (this) {
                    connections.add(c);
                    notifyAll();
                }
                Thread reader = new Thread(() -> read(s, c), "observing-backend-reader");
                reader.setDaemon(true);
                reader.start();
            }
        }

        private void read(Socket s, Connection c) {
            try {
                s.setSoTimeout(30000);
                InputStream in = s.getInputStream();
                byte[] b = new byte[4096];
                for (int n; (n = in.read(b)) >= 0; ) {
                    synchronized (c.bytes) {
                        c.bytes.write(b, 0, n);
                    }
                }
                c.closedByPeer = true;
            } catch (EOFException | SocketException closed) {
                c.closedByPeer = true;
            } catch (IOException stillOpen) {
                // a read timeout: the proxy kept the connection open, closedByPeer stays false
            } finally {
                synchronized (this) {
                    notifyAll();
                }
                try {
                    s.close();
                } catch (IOException ignored) {
                    // closed either way
                }
            }
        }

        boolean awaitAccepted(long amount, TimeUnit unit) throws InterruptedException {
            long deadline = System.nanoTime() + unit.toNanos(amount);
            synchronized (this) {
                while (connections.isEmpty()) {
                    long left = deadline - System.nanoTime();
                    if (left <= 0) {
                        return false;
                    }
                    TimeUnit.NANOSECONDS.timedWait(this, left);
                }
                return true;
            }
        }

        /** Waits until the only connection was closed by the proxy and returns it. */
        Connection awaitOnlyConnectionClosed(long amount, TimeUnit unit) throws InterruptedException {
            assertTrue("the proxy connected to the backend", awaitAccepted(amount, unit));
            long deadline = System.nanoTime() + unit.toNanos(amount);
            synchronized (this) {
                assertEquals("backend connections", 1, connections.size());
                Connection c = connections.get(0);
                while (!c.closedByPeer) {
                    long left = deadline - System.nanoTime();
                    if (left <= 0) {
                        throw new AssertionError("the proxy did not close the backend connection; got " + c.text());
                    }
                    TimeUnit.NANOSECONDS.timedWait(this, left);
                }
                return c;
            }
        }

        /**
         * Connects a sentinel, waits until the acceptor took it, and returns how many connections it accepted before
         * the sentinel: the accept queue is first-in first-out, so every connection the proxy completed earlier is
         * counted.
         */
        int connectionsBeforeSentinel() throws IOException {
            try (Socket sentinel = new Socket(InetAddress.getByName("127.0.0.1"), server.getLocalPort())) {
                int port = sentinel.getLocalPort();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (true) {
                    synchronized (this) {
                        for (int i = 0; i < connections.size(); i++) {
                            if (connections.get(i).clientPort == port) {
                                return i;
                            }
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
            synchronized (open) {
                for (Socket s : open) {
                    s.close();
                }
            }
            try {
                acceptor.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * An in-memory adapter whose request body gives its first {@code passBytes} bytes and then throws
     * {@code failure} from every read, as an adapter body stream that fails in the middle of a body.
     */
    private static final class FailingBodyAdapter implements HttpAdapter {
        final InMemoryHttpAdapter delegate = new InMemoryHttpAdapter();
        private final int passBytes;
        private final Throwable failure;

        FailingBodyAdapter(int passBytes, Throwable failure) {
            this.passBytes = passBytes;
            this.failure = failure;
        }

        @Override
        public void bind(String host, int port, ExchangeHandler handler) throws IOException {
            delegate.bind(host, port, exchange -> handler.handle(new FailingBodyExchange(exchange, this)));
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

        InputStream failingBody(InputStream in) {
            return new FilterInputStream(in) {
                private int given;

                @Override
                public int read() throws IOException {
                    byte[] one = new byte[1];
                    return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    if (given >= passBytes) {
                        if (failure instanceof IOException) {
                            throw (IOException) failure;
                        }
                        throw (RuntimeException) failure;
                    }
                    int n = super.read(b, off, Math.min(len, passBytes - given));
                    if (n > 0) {
                        given += n;
                    }
                    return n;
                }
            };
        }
    }

    /** An exchange whose request body fails as its {@link FailingBodyAdapter} says; the rest goes to its delegate. */
    private static final class FailingBodyExchange implements HttpExchange {
        private final HttpExchange d;
        private final FailingBodyAdapter adapter;

        FailingBodyExchange(HttpExchange d, FailingBodyAdapter adapter) {
            this.d = d;
            this.adapter = adapter;
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
            return adapter.failingBody(d.requestBody());
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
}
