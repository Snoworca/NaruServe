package com.snoworca.naruserve.proxy;

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
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Test;

/**
 * The client request headers the proxy forwards to the backend (FR-PROXY-003): received order and duplicates kept,
 * hop-by-hop and Expect lines removed, Cookie lines merged, no Via, the header check before any connection, the head
 * written in ISO-8859-1 and the D-053 layout of the backend header block. The in-memory adapter hands the proxy header
 * lines TinyHttpd would refuse or rewrite first, so the proxy's own handling is what these tests see.
 */
public class ProxyRequestHeadersTest {

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

    /** Mounts a proxy to {@code url} at /api on the in-memory app and starts it. */
    private void mount(String url) {
        app.use("/api", Proxy.to(url));
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

    /** Sends {@code request} through the proxy to a recording backend; asserts 200 ok and returns what it received. */
    private RawBackend.Received forward(ClientRequest request) throws IOException {
        RawBackend backend = okBackend();
        mount(backend.url());

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

    // ---- AC-1 ----

    // FR-PROXY-003 AC-1: X-A: 1, X-B: 2, X-A: 3 arrive as three lines in that order with their values unchanged
    @Test
    public void fr_PROXY_003_AC1_interleavedDuplicatesArriveInReceivedOrder() throws IOException {
        RawBackend.Received got = forward(request("GET", "X-A", "1", "X-B", "2", "X-A", "3"));

        assertEquals(Arrays.asList("X-A: 1", "X-B: 2", "X-A: 3"), linesNamed(got, "X-A", "X-B"));
    }

    // FR-PROXY-003 AC-1: an unmanaged header arrives with the name letters the adapter reported
    @Test
    public void fr_PROXY_003_AC1_unmanagedHeaderKeepsTheReportedNameLetters() throws IOException {
        RawBackend.Received got = forward(request("GET", "x-MiXed-NAME", "v 1", "accept", "text/plain"));

        assertEquals(Collections.singletonList("x-MiXed-NAME: v 1"), linesNamed(got, "x-mixed-name"));
        assertEquals(Collections.singletonList("accept: text/plain"), linesNamed(got, "Accept"));
    }

    // ---- AC-2 ----

    // FR-PROXY-003 AC-2: none of the client's hop-by-hop lines arrives; the only Connection line is the proxy's own
    @Test
    public void fr_PROXY_003_AC2_hopByHopLinesDoNotArrive() throws IOException {
        RawBackend.Received got = forward(request("GET", "Connection", "keep-alive", "Keep-Alive", "timeout=99",
                "Proxy-Connection", "keep-alive", "TE", "trailers", "Upgrade", "h2c", "Proxy-Authenticate",
                "Basic realm=\"c\"", "Proxy-Authorization", "Basic YTpi", "Trailer", "X-Sum", "X-Keep", "1"));

        assertEquals(Collections.singletonList("Connection: close"), linesNamed(got, "Connection"));
        assertEquals(Collections.<String>emptyList(), linesNamed(got, "Keep-Alive", "Proxy-Connection", "TE",
                "Upgrade", "Proxy-Authenticate", "Proxy-Authorization", "Trailer", "Transfer-Encoding"));
        assertEquals(Collections.singletonList("X-Keep: 1"), linesNamed(got, "X-Keep"));
    }

    // FR-PROXY-003 AC-2: the client's Transfer-Encoding line does not arrive; the framing line is the proxy's own
    @Test
    public void fr_PROXY_003_AC2_clientTransferEncodingDoesNotArrive() throws IOException {
        RawBackend.Received got = forward(request("POST", "Transfer-Encoding", "chunked", "X-Keep", "1").body(HELLO));

        assertEquals(Collections.<String>emptyList(), linesNamed(got, "Transfer-Encoding"));
        assertEquals(Collections.singletonList("Content-Length: 5"), linesNamed(got, "Content-Length"));
        assertEquals(Collections.singletonList("Connection: close"), linesNamed(got, "Connection"));
        assertEquals("hello", new String(got.body, StandardCharsets.ISO_8859_1));
    }

    // FR-PROXY-003 AC-2: for Connection: close, X-Hop the client's X-Hop line does not arrive
    @Test
    public void fr_PROXY_003_AC2_headerNamedInConnectionDoesNotArrive() throws IOException {
        RawBackend.Received got = forward(request("GET", "Connection", "close, X-Hop", "X-Hop", "secret",
                "X-Keep", "1"));

        assertEquals(Collections.<String>emptyList(), linesNamed(got, "X-Hop"));
        assertEquals(Collections.singletonList("X-Keep: 1"), linesNamed(got, "X-Keep"));
        assertEquals(Collections.singletonList("Connection: close"), linesNamed(got, "Connection"));
    }

    // FR-PROXY-003 AC-2: Connection names match case-insensitively, with OWS trimmed, across several Connection lines
    @Test
    public void fr_PROXY_003_AC2_connectionNamesMatchCaseInsensitivelyAcrossLines() throws IOException {
        RawBackend.Received got = forward(request("GET", "X-HOP-ONE", "1", "connection", " x-hop-one ,\tX-Hop-Two",
                "Connection", "x-hop-three", "x-hop-two", "2", "X-Hop-Three", "3", "X-Keep", "1"));

        assertEquals(Collections.<String>emptyList(), linesNamed(got, "X-Hop-One", "X-Hop-Two", "X-Hop-Three"));
        assertEquals(Collections.singletonList("X-Keep: 1"), linesNamed(got, "X-Keep"));
    }

    // ---- AC-3 ----

    // FR-PROXY-003 AC-3: Expect: 100-continue does not arrive and the body still reaches the backend (in memory)
    @Test
    public void fr_PROXY_003_AC3_expectDoesNotArriveAndTheBodyIsDelivered() throws IOException {
        RawBackend.Received got = forward(request("POST", "Expect", "100-continue", "Content-Length", "5")
                .body(HELLO));

        assertEquals(Collections.<String>emptyList(), linesNamed(got, "Expect"));
        assertEquals("hello", new String(got.body, StandardCharsets.ISO_8859_1));
    }

    // FR-PROXY-003 AC-3: over a TinyHttpd socket, a client that sends Expect: 100-continue gets 100 Continue, its body
    // reaches the backend without the Expect line and the client receives the backend's final response
    @Test(timeout = 30000)
    public void fr_PROXY_003_AC3_overASocketTheExpectingClientGetsTheFinalResponse() throws IOException {
        RawBackend backend = okBackend();
        RestApp g = RestApp.builder().httpAdapter(new TinyHttpdAdapter()).jsonCodec(new StubJsonCodec())
                .logger(new RecordingLogger()).build();
        started.add(g);
        g.use("/api", Proxy.to(backend.url()));
        int port = g.listen(0).port();

        String interim;
        String rest;
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            s.setSoTimeout(10000);
            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();
            out.write(("POST /api/x HTTP/1.1\r\nHost: 127.0.0.1\r\nExpect: 100-continue\r\nContent-Length: 5"
                    + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            interim = readHead(in);
            out.write(HELLO);
            out.flush();
            rest = readToEnd(in);
        }

        assertTrue(interim, interim.startsWith("HTTP/1.1 100 "));
        assertTrue(rest, rest.startsWith("HTTP/1.1 200 "));
        assertTrue(rest, rest.endsWith("\r\n\r\nok"));
        RawBackend.Received got = backend.onlyRequest();
        assertEquals(Collections.<String>emptyList(), linesNamed(got, "Expect"));
        assertEquals("hello", new String(got.body, StandardCharsets.ISO_8859_1));
    }

    // ---- AC-4 ----

    // FR-PROXY-003 AC-4: Cookie: a=1 and Cookie: b=2 arrive as Cookie: a=1; b=2 at the first Cookie position
    @Test
    public void fr_PROXY_003_AC4_cookieLinesMergeAtTheFirstCookiePosition() throws IOException {
        RawBackend.Received got = forward(request("GET", "X-Before", "0", "Cookie", "a=1", "X-Mid", "m",
                "Cookie", "b=2", "X-After", "9"));

        assertEquals(Arrays.asList("X-Before: 0", "Cookie: a=1; b=2", "X-Mid: m", "X-After: 9"),
                linesNamed(got, "X-Before", "Cookie", "X-Mid", "X-After"));
    }

    // FR-PROXY-003 AC-4: Cookie lines merge whatever the case of their names, in received order
    @Test
    public void fr_PROXY_003_AC4_cookieLinesOfAnyCaseMergeInReceivedOrder() throws IOException {
        RawBackend.Received got = forward(request("GET", "cookie", "a=1", "COOKIE", "b=2", "Cookie", "c=3"));

        assertEquals(Collections.singletonList("cookie: a=1; b=2; c=3"), linesNamed(got, "Cookie"));
    }

    // FR-PROXY-003 AC-4: a single Cookie line arrives unchanged
    @Test
    public void fr_PROXY_003_AC4_singleCookieLineArrivesUnchanged() throws IOException {
        RawBackend.Received got = forward(request("GET", "Cookie", "a=1; b=2"));

        assertEquals(Collections.singletonList("Cookie: a=1; b=2"), linesNamed(got, "Cookie"));
    }

    // ---- AC-5 ----

    // FR-PROXY-003 AC-5: the proxy adds no Via, and OPTIONS with Max-Forwards: 0 is forwarded with it unchanged
    @Test
    public void fr_PROXY_003_AC5_noViaAndMaxForwardsZeroIsForwarded() throws IOException {
        RawBackend.Received got = forward(request("OPTIONS", "Max-Forwards", "0"));

        assertTrue(got.requestLine(), got.requestLine().startsWith("OPTIONS "));
        assertEquals(Collections.singletonList("Max-Forwards: 0"), linesNamed(got, "Max-Forwards"));
        assertEquals(Collections.<String>emptyList(), linesNamed(got, "Via"));
    }

    // ---- AC-6 ----

    /** Sends {@code request} to a proxy whose backend counts connections; asserts 400 and that none opened. */
    private void assertRefusedWithoutConnection(ClientRequest request) throws IOException {
        ConnectionCounter backend = new ConnectionCounter();
        closeables.add(backend);
        mount(backend.url());

        ClientResponse r = adapter.send(request);

        assertEquals("status", 400, r.status());
        assertEquals("backend connections opened", 0, backend.connectionsBeforeSentinel());
    }

    // FR-PROXY-003 AC-6: a header name holding a space is not a token: 400 without a backend connection
    @Test
    public void fr_PROXY_003_AC6_nameWithSpaceGives400WithoutConnecting() throws IOException {
        assertRefusedWithoutConnection(request("GET", "X Bad", "1"));
    }

    // FR-PROXY-003 AC-6: a header name holding a separator is not a token: 400 without a backend connection
    @Test
    public void fr_PROXY_003_AC6_nameWithSeparatorGives400WithoutConnecting() throws IOException {
        assertRefusedWithoutConnection(request("GET", "X(Bad)", "1"));
    }

    // FR-PROXY-003 AC-6: an empty header name is not a token: 400 without a backend connection
    @Test
    public void fr_PROXY_003_AC6_emptyNameGives400WithoutConnecting() throws IOException {
        assertRefusedWithoutConnection(request("GET", "", "1"));
    }

    // FR-PROXY-003 AC-6: a header value holding NUL fails isFieldValue: 400 without a backend connection
    @Test
    public void fr_PROXY_003_AC6_valueWithNulGives400WithoutConnecting() throws IOException {
        assertRefusedWithoutConnection(request("GET", "X-A", "a\u0000b"));
    }

    // FR-PROXY-003 AC-6: a header value holding CR LF fails isFieldValue: 400 without a backend connection
    @Test
    public void fr_PROXY_003_AC6_valueWithCrLfGives400WithoutConnecting() throws IOException {
        assertRefusedWithoutConnection(request("GET", "X-A", "a\r\nX-Injected: 1"));
    }

    // FR-PROXY-003 AC-6: a header value holding a character above U+00FF fails isFieldValue: 400 without connecting
    @Test
    public void fr_PROXY_003_AC6_valueAboveLatin1Gives400WithoutConnecting() throws IOException {
        assertRefusedWithoutConnection(request("GET", "X-A", "aĊb"));
    }

    // FR-PROXY-003 AC-6: a header the proxy drops (hop-by-hop) is still checked: 400 without a backend connection
    @Test
    public void fr_PROXY_003_AC6_droppedHeaderIsStillChecked() throws IOException {
        assertRefusedWithoutConnection(request("GET", "Keep-Alive", "a\u0000b"));
    }

    // FR-PROXY-003 AC-6: the header check comes before the framing check (D-050), so a bad header with a 501 framing
    // (Transfer-Encoding: gzip, chunked) answers 400
    @Test
    public void fr_PROXY_003_AC6_headerCheckComesBeforeTheFramingCheck() throws IOException {
        assertRefusedWithoutConnection(request("POST", "X-A", "a\u0000b", "Transfer-Encoding", "gzip, chunked")
                .body(HELLO));
    }

    // ---- AC-7 ----

    // FR-PROXY-003 AC-7: a value holding U+00E9 arrives as the single byte 0xE9 (head written in ISO-8859-1)
    @Test
    public void fr_PROXY_003_AC7_latin1ValueArrivesAsOneByte() throws IOException {
        RawBackend.Received got = forward(request("GET", "X-Name", "café"));

        byte[] head = got.head.getBytes(StandardCharsets.ISO_8859_1);
        byte[] expected = "X-Name: café\r\n".getBytes(StandardCharsets.ISO_8859_1);
        assertEquals(14, expected.length);
        assertEquals((byte) 0xE9, expected[11]);
        assertTrue(got.head, indexOf(head, expected) >= 0);
        assertEquals(Collections.singletonList("X-Name: café"), linesNamed(got, "X-Name"));
    }

    // ---- AC-8 ----

    // FR-PROXY-003 AC-8: Host first; then the client headers in received order without the removed ones and with the
    // merged Cookie at the first Cookie position; the framing header and Connection: close last
    @Test
    public void fr_PROXY_003_AC8_backendHeaderBlockLayout() throws IOException {
        RawBackend backend = okBackend();
        mount(backend.url());

        ClientResponse r = adapter.send(request("POST", "Cookie", "a=1", "X-A", "1", "Host", "client.example",
                "Connection", "keep-alive, X-Hop", "X-Hop", "h", "Expect", "100-continue", "Cookie", "b=2",
                "Content-Length", "5", "X-B", "2", "Keep-Alive", "timeout=9").body(HELLO));

        assertEquals("status", 200, r.status());
        List<String> lines = backend.onlyRequest().headerLines();
        assertEquals(lines.toString(), "Host: 127.0.0.1:" + backend.port(), lines.get(0));
        assertEquals(lines.toString(), Arrays.asList("Content-Length: 5", "Connection: close"),
                lines.subList(lines.size() - 2, lines.size()));
        assertEquals(Arrays.asList("Cookie: a=1; b=2", "X-A: 1", "X-B: 2"), lines.subList(1, 4));
        assertEquals(lines.toString(), 1, linesNamed(backend.onlyRequest(), "Host").size());
        assertEquals(lines.toString(), Collections.<String>emptyList(),
                linesNamed(backend.onlyRequest(), "X-Hop", "Expect", "Keep-Alive"));
    }

    // FR-PROXY-003 AC-8: a request without a body ends its block with Connection: close and no framing header
    @Test
    public void fr_PROXY_003_AC8_bodylessRequestEndsWithConnectionClose() throws IOException {
        RawBackend.Received got = forward(request("GET", "X-A", "1", "Cookie", "a=1", "Cookie", "b=2"));

        List<String> lines = got.headerLines();
        assertTrue(lines.toString(), lines.get(0).startsWith("Host: "));
        assertEquals(Arrays.asList("X-A: 1", "Cookie: a=1; b=2"), lines.subList(1, 3));
        assertEquals("Connection: close", lines.get(lines.size() - 1));
        assertEquals(Collections.<String>emptyList(), linesNamed(got, "Content-Length", "Transfer-Encoding"));
    }

    // ---- test support ----

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /** Reads one response head up to and including the blank line, as ISO-8859-1 text. */
    private static String readHead(InputStream in) throws IOException {
        StringBuilder head = new StringBuilder();
        while (head.length() < 4 || !head.substring(head.length() - 4).equals("\r\n\r\n")) {
            int b = in.read();
            if (b < 0) {
                throw new EOFException("EOF inside a response head: " + head);
            }
            head.append((char) b);
        }
        return head.toString();
    }

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
