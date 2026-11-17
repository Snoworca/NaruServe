package com.snoworca.naruserve.proxy;

import static org.junit.Assert.assertEquals;

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
import com.snoworca.naruserve.testing.StubJsonCodec;
import com.snoworca.naruserve.testing.TestApp;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Test;

/**
 * The proxy's request framing checks (SEC-PROXY-003): Content-Length and Transfer-Encoding are read as lists of all
 * their field lines, and an ambiguous framing answers 400 or 501 before any backend connection. The in-memory adapter
 * hands the proxy header combinations that TinyHttpd would refuse first (FR-ENGINE-003), so the proxy's own check is
 * what these tests see.
 */
public class ProxyRequestFramingTest {

    private static final byte[] HELLO = "hello".getBytes(StandardCharsets.ISO_8859_1);

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

    private ConnectionCounter counter() throws IOException {
        ConnectionCounter c = new ConnectionCounter();
        closeables.add(c);
        return c;
    }

    /** Mounts a proxy to {@code url} at /api on the default in-memory app and starts it. */
    private void mount(String url) {
        app.use("/api", Proxy.to(url));
        t.start();
        started.add(app);
    }

    /** POST /api/x with the given header name/value pairs, in order, and {@code body}. */
    private static ClientRequest post(byte[] body, String... headerPairs) {
        ClientRequest r = ClientRequest.of("POST", "/api/x");
        for (int i = 0; i < headerPairs.length; i += 2) {
            r.header(headerPairs[i], headerPairs[i + 1]);
        }
        return r.body(body);
    }

    /** Sends {@code request} to a proxy whose backend counts connections; asserts the status and that none opened. */
    private void assertRefusedWithoutConnection(int status, ClientRequest request) throws IOException {
        ConnectionCounter backend = counter();
        mount(backend.url());

        ClientResponse r = adapter.send(request);

        assertEquals("status", status, r.status());
        assertEquals("backend connections opened", 0, backend.connectionsBeforeSentinel());
    }

    /**
     * Sends {@code request} to a proxy whose target is {@code https} on a listener that counts connections; asserts
     * the status and that none opened. The framing answer comes before the https placeholder (502) of the proxy.
     */
    private void assertHttpsAnswersWithoutConnection(int status, ClientRequest request) throws IOException {
        ConnectionCounter backend = counter();
        mount(backend.httpsUrl());

        ClientResponse r = adapter.send(request);

        assertEquals("status", status, r.status());
        assertEquals("backend connections opened", 0, backend.connectionsBeforeSentinel());
    }

    /** Sends {@code request} to a recording backend; asserts it arrived with one {@code Content-Length: 5} line. */
    private void assertForwardedWithSingleLengthFive(ClientRequest request) throws IOException {
        RawBackend backend = okBackend();
        mount(backend.url());

        ClientResponse r = adapter.send(request);

        assertEquals("status", 200, r.status());
        assertEquals("ok", r.text());
        RawBackend.Received got = backend.onlyRequest();
        List<String> lengthLines = new ArrayList<>();
        for (String line : got.headerLines()) {
            if (line.regionMatches(true, 0, "Content-Length:", 0, "Content-Length:".length())) {
                lengthLines.add(line);
            }
        }
        assertEquals(Collections.singletonList("Content-Length: 5"), lengthLines);
        assertEquals("hello", new String(got.body, StandardCharsets.ISO_8859_1));
    }

    /** Sends {@code request} to a recording backend; asserts it was forwarded with its body intact. */
    private void assertForwardedChunkedBody(ClientRequest request) throws IOException {
        RawBackend backend = okBackend();
        mount(backend.url());

        ClientResponse r = adapter.send(request);

        assertEquals("status", 200, r.status());
        assertEquals("ok", r.text());
        assertEquals("hello", new String(backend.onlyRequest().body, StandardCharsets.ISO_8859_1));
    }

    // ---- AC-1 ----

    // SEC-PROXY-003 AC-1: Content-Length together with Transfer-Encoding is 400 and no backend connection is opened
    @Test
    public void sec_PROXY_003_AC1_contentLengthWithTransferEncodingGives400WithoutConnecting() throws IOException {
        assertRefusedWithoutConnection(400,
                post(HELLO, "Content-Length", "5", "Transfer-Encoding", "chunked"));
    }

    // SEC-PROXY-003 AC-1: the order of the two headers does not matter
    @Test
    public void sec_PROXY_003_AC1_transferEncodingBeforeContentLengthGives400WithoutConnecting() throws IOException {
        assertRefusedWithoutConnection(400,
                post(HELLO, "Transfer-Encoding", "chunked", "Content-Length", "5"));
    }

    // ---- AC-2 ----

    // SEC-PROXY-003 AC-2: two Content-Length lines 5 and 6 are 400 without a backend connection
    @Test
    public void sec_PROXY_003_AC2_twoDifferingContentLengthLinesGive400() throws IOException {
        assertRefusedWithoutConnection(400, post(HELLO, "Content-Length", "5", "Content-Length", "6"));
    }

    // SEC-PROXY-003 AC-2: the list Content-Length: 5, 6 is 400 without a backend connection
    @Test
    public void sec_PROXY_003_AC2_differingContentLengthListGives400() throws IOException {
        assertRefusedWithoutConnection(400, post(HELLO, "Content-Length", "5, 6"));
    }

    // SEC-PROXY-003 AC-2: Content-Length: abc is 400 without a backend connection
    @Test
    public void sec_PROXY_003_AC2_nonNumericContentLengthGives400() throws IOException {
        assertRefusedWithoutConnection(400, post(HELLO, "Content-Length", "abc"));
    }

    // SEC-PROXY-003 AC-2: Content-Length: -1 is 400 without a backend connection
    @Test
    public void sec_PROXY_003_AC2_negativeContentLengthGives400() throws IOException {
        assertRefusedWithoutConnection(400, post(HELLO, "Content-Length", "-1"));
    }

    // SEC-PROXY-003 AC-2: a Content-Length beyond a long is 400 without a backend connection
    @Test
    public void sec_PROXY_003_AC2_contentLengthBeyondALongGives400() throws IOException {
        assertRefusedWithoutConnection(400, post(HELLO, "Content-Length", "99999999999999999999"));
    }

    // SEC-PROXY-003 AC-2: a signed Content-Length (+5) is not digits, so 400 without a backend connection
    @Test
    public void sec_PROXY_003_AC2_plusSignedContentLengthGives400() throws IOException {
        assertRefusedWithoutConnection(400, post(HELLO, "Content-Length", "+5"));
    }

    // ---- AC-3 ----

    // SEC-PROXY-003 AC-3: Transfer-Encoding: gzip (last coding not chunked) is 400 without a backend connection
    @Test
    public void sec_PROXY_003_AC3_gzipOnlyGives400() throws IOException {
        assertRefusedWithoutConnection(400, post(HELLO, "Transfer-Encoding", "gzip"));
    }

    // SEC-PROXY-003 AC-3: Transfer-Encoding: chunked, chunked is 400 without a backend connection
    @Test
    public void sec_PROXY_003_AC3_chunkedTwiceInOneLineGives400() throws IOException {
        assertRefusedWithoutConnection(400, post(HELLO, "Transfer-Encoding", "chunked, chunked"));
    }

    // SEC-PROXY-003 AC-3: two Transfer-Encoding: chunked lines are 400 without a backend connection
    @Test
    public void sec_PROXY_003_AC3_chunkedOnTwoLinesGives400() throws IOException {
        assertRefusedWithoutConnection(400,
                post(HELLO, "Transfer-Encoding", "chunked", "Transfer-Encoding", "chunked"));
    }

    // ---- AC-4 ----

    // SEC-PROXY-003 AC-4: Transfer-Encoding: gzip, chunked is 501 without a backend connection
    @Test
    public void sec_PROXY_003_AC4_codingBeforeChunkedGives501() throws IOException {
        assertRefusedWithoutConnection(501, post(HELLO, "Transfer-Encoding", "gzip, chunked"));
    }

    // SEC-PROXY-003 AC-4: gzip and chunked on separate lines are one list, so 501 without a backend connection
    @Test
    public void sec_PROXY_003_AC4_codingBeforeChunkedOnSeparateLinesGives501() throws IOException {
        assertRefusedWithoutConnection(501,
                post(HELLO, "Transfer-Encoding", "gzip", "Transfer-Encoding", "chunked"));
    }

    // ---- https target: the framing answer comes before the https placeholder ----

    // SEC-PROXY-003 AC-1: Content-Length with Transfer-Encoding is 400 for an https target too, without connecting
    @Test
    public void sec_PROXY_003_AC1_httpsTargetContentLengthWithTransferEncodingGives400() throws IOException {
        assertHttpsAnswersWithoutConnection(400,
                post(HELLO, "Content-Length", "5", "Transfer-Encoding", "chunked"));
    }

    // SEC-PROXY-003 AC-2: two Content-Length lines 5 and 6 are 400 for an https target too, without connecting
    @Test
    public void sec_PROXY_003_AC2_httpsTargetTwoDifferingContentLengthLinesGive400() throws IOException {
        assertHttpsAnswersWithoutConnection(400, post(HELLO, "Content-Length", "5", "Content-Length", "6"));
    }

    // SEC-PROXY-003 AC-4: Transfer-Encoding: gzip, chunked is 501 for an https target too, without connecting
    @Test
    public void sec_PROXY_003_AC4_httpsTargetCodingBeforeChunkedGives501() throws IOException {
        assertHttpsAnswersWithoutConnection(501, post(HELLO, "Transfer-Encoding", "gzip, chunked"));
    }

    // SEC-PROXY-003 AC-2: a well-framed request (Content-Length: 5) to an https target still meets the https
    // placeholder, 502, until the TLS requirement (SEC-PROXY-005) arrives
    @Test
    public void sec_PROXY_003_AC2_httpsTargetWellFramedRequestStillGives502() throws IOException {
        assertHttpsAnswersWithoutConnection(502, post(HELLO, "Content-Length", "5"));
    }

    // ---- AC-5 ----

    // SEC-PROXY-003 AC-5: Content-Length: 5, 5 is forwarded with the single line Content-Length: 5
    @Test
    public void sec_PROXY_003_AC5_equalContentLengthListIsForwardedAsOneLine() throws IOException {
        assertForwardedWithSingleLengthFive(post(HELLO, "Content-Length", "5, 5"));
    }

    // SEC-PROXY-003 AC-5: a Content-Length padded with whitespace is forwarded as Content-Length: 5
    @Test
    public void sec_PROXY_003_AC5_paddedContentLengthIsForwardedAsOneLine() throws IOException {
        assertForwardedWithSingleLengthFive(post(HELLO, "Content-Length", "  5 "));
    }

    // SEC-PROXY-003 AC-5: the lines Content-Length: 5 and Content-Length: 05 are equal numbers, forwarded as one line
    @Test
    public void sec_PROXY_003_AC5_numericallyEqualContentLengthLinesAreForwardedAsOneLine() throws IOException {
        assertForwardedWithSingleLengthFive(post(HELLO, "Content-Length", "5", "Content-Length", "05"));
    }

    // SEC-PROXY-003 AC-5: Transfer-Encoding: CHUNKED is the chunked coding (case-insensitive) and is forwarded
    @Test
    public void sec_PROXY_003_AC5_upperCaseChunkedIsForwarded() throws IOException {
        assertForwardedChunkedBody(post(HELLO, "Transfer-Encoding", "CHUNKED"));
    }

    // SEC-PROXY-003 AC-5: Transfer-Encoding: chunked, (an empty trailing element) is forwarded
    @Test
    public void sec_PROXY_003_AC5_chunkedWithEmptyTrailingElementIsForwarded() throws IOException {
        assertForwardedChunkedBody(post(HELLO, "Transfer-Encoding", "chunked,"));
    }

    // ---- AC-6 ----

    // SEC-PROXY-003 AC-6: chunked with a parameter is a coding other than chunked, so 400 without a connection
    @Test
    public void sec_PROXY_003_AC6_chunkedWithParameterGives400() throws IOException {
        assertRefusedWithoutConnection(400, post(HELLO, "Transfer-Encoding", "chunked;x=1"));
    }

    // SEC-PROXY-003 AC-6: an empty Transfer-Encoding value is 400 without a backend connection
    @Test
    public void sec_PROXY_003_AC6_emptyTransferEncodingGives400() throws IOException {
        assertRefusedWithoutConnection(400, post(HELLO, "Transfer-Encoding", ""));
    }

    // SEC-PROXY-003 AC-6: a parameterised coding before chunked is 501 without a backend connection
    @Test
    public void sec_PROXY_003_AC6_parameterisedCodingBeforeChunkedGives501() throws IOException {
        assertRefusedWithoutConnection(501, post(HELLO, "Transfer-Encoding", "gzip;q=1, chunked"));
    }

    // ---- AC-7 ----

    /** One framing case: the request headers and the status and backend request count they must give. */
    private static final class Case {
        final String name;
        final String[] headers;
        final int status;
        final int connections;

        Case(String name, int status, int connections, String... headers) {
            this.name = name;
            this.status = status;
            this.connections = connections;
            this.headers = headers;
        }
    }

    private static final List<Case> VERSION_CASES = Arrays.asList(
            new Case("CL 5", 200, 1, "Content-Length", "5"),
            new Case("CL 5, 5", 200, 1, "Content-Length", "5, 5"),
            new Case("CL 5 and 6", 400, 0, "Content-Length", "5", "Content-Length", "6"),
            new Case("CL with TE", 400, 0, "Content-Length", "5", "Transfer-Encoding", "chunked"),
            new Case("TE chunked", 200, 1, "Transfer-Encoding", "chunked"),
            new Case("TE gzip", 400, 0, "Transfer-Encoding", "gzip"),
            new Case("TE gzip, chunked", 501, 0, "Transfer-Encoding", "gzip, chunked"));

    /** For each case, its name with the status and backend request count seen when the adapter reports {@code version}. */
    private List<String> observe(String version) throws IOException {
        List<String> out = new ArrayList<>();
        for (Case c : VERSION_CASES) {
            RawBackend backend = okBackend();
            VersionReportingAdapter versioned = new VersionReportingAdapter(version);
            RestApp a = RestApp.builder().httpAdapter(versioned).jsonCodec(new StubJsonCodec()).build();
            a.use("/api", Proxy.to(backend.url()));
            a.listen(0);
            started.add(a);

            ClientResponse r = versioned.delegate.send(post(HELLO, c.headers));

            out.add(c.name + " -> " + r.status() + " / " + backend.requestCount());
        }
        return out;
    }

    private static List<String> expected() {
        List<String> out = new ArrayList<>();
        for (Case c : VERSION_CASES) {
            out.add(c.name + " -> " + c.status + " / " + c.connections);
        }
        return out;
    }

    // SEC-PROXY-003 AC-7: the same CL/TE headers give the same answer whether the adapter reports HTTP/1.0 or HTTP/1.1
    @Test
    public void sec_PROXY_003_AC7_framingAnswerDoesNotDependOnTheReportedHttpVersion() throws IOException {
        List<String> http10 = observe("HTTP/1.0");
        List<String> http11 = observe("HTTP/1.1");
        List<String> http2 = observe("HTTP/2.0");

        assertEquals("HTTP/1.1", expected(), http11);
        assertEquals("HTTP/1.0 answers as HTTP/1.1", http11, http10);
        assertEquals("HTTP/2.0 answers as HTTP/1.1", http11, http2);
    }

    // ---- test support ----

    /**
     * A loopback listener that records the client port of every accepted connection and closes it at once, so a proxy
     * that connects sees the backend close before any response (502). {@link #connectionsBeforeSentinel} closes the
     * race between a connect that already completed and the acceptor thread that has not yet taken it.
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

        String httpsUrl() {
            return "https://127.0.0.1:" + server.getLocalPort();
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

    /** An in-memory adapter whose exchanges report {@code version} as the request's HTTP version. */
    private static final class VersionReportingAdapter implements HttpAdapter {
        final InMemoryHttpAdapter delegate = new InMemoryHttpAdapter();
        private final String version;

        VersionReportingAdapter(String version) {
            this.version = version;
        }

        @Override
        public void bind(String host, int port, ExchangeHandler handler) throws IOException {
            delegate.bind(host, port, exchange -> handler.handle(new VersionedExchange(exchange, version)));
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

    /** An exchange that reports a fixed HTTP version and passes everything else to its delegate. */
    private static final class VersionedExchange implements HttpExchange {
        private final HttpExchange d;
        private final String version;

        VersionedExchange(HttpExchange d, String version) {
            this.d = d;
            this.version = version;
        }

        @Override
        public String httpVersion() {
            return version;
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
            return d.requestBody();
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
