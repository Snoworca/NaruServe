package com.snoworca.naruserve.proxy;

import static org.junit.Assert.assertEquals;
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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Assume;
import org.junit.Test;

/**
 * The {@code Host} the proxy sends to the backend (FR-PROXY-004): the target host, with {@code :port} only for a
 * port that is not the scheme default and an IPv6 literal in brackets; the received {@code Host} as
 * {@code X-Forwarded-Host} in the forwarding-header slot of design D-053; the received {@code Host} unchanged under
 * {@code .preserveHost(true)}; the target host and no {@code X-Forwarded-Host} when the adapter reports no
 * {@code Host}; and 400 before any backend connection for more than one {@code Host} line (design D-050, D-051). The
 * in-memory adapter hands the proxy the header lines as given, so a request without {@code Host} or with two of them
 * reaches the proxy's own handling.
 */
public class ProxyHostHeaderTest {

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

    /** Sends {@code request} through {@code proxy} to {@code backend}; asserts 200 ok and returns what it received. */
    private RawBackend.Received forward(RawBackend backend, Proxy proxy, ClientRequest request) {
        mount(proxy);

        ClientResponse r = adapter.send(request);

        assertEquals("status", 200, r.status());
        assertEquals("ok", r.text());
        return backend.onlyRequest();
    }

    /** Every {@code X-Forwarded-Host} value the backend received. */
    private static List<String> forwardedHosts(RawBackend.Received got) {
        return got.headerValues("X-Forwarded-Host");
    }

    // ---- AC-1 ----

    // FR-PROXY-004 AC-1: Proxy.to("http://127.0.0.1:<port>") sends Host: 127.0.0.1:<port> for a request whose Host is
    // gw.example, and the backend receives X-Forwarded-Host: gw.example
    @Test
    public void fr_PROXY_004_AC1_hostIsTheTargetAndTheReceivedHostIsForwarded() throws IOException {
        RawBackend backend = okBackend();

        RawBackend.Received got = forward(backend, Proxy.to(backend.url()), request("GET", "Host", "gw.example"));

        assertEquals(Collections.singletonList("127.0.0.1:" + backend.port()), got.headerValues("Host"));
        assertEquals(Collections.singletonList("gw.example"), forwardedHosts(got));
    }

    // FR-PROXY-004 AC-1: Host stays the first line and X-Forwarded-Host takes the forwarding slot of D-053, after the
    // client headers and before the framing header and Connection: close
    @Test
    public void fr_PROXY_004_AC1_xForwardedHostTakesTheForwardingSlot() throws IOException {
        RawBackend backend = okBackend();

        RawBackend.Received got = forward(backend, Proxy.to(backend.url()), request("POST", "X-A", "1", "Host",
                "gw.example", "X-B", "2", "Content-Length", "5").body("hello".getBytes(StandardCharsets.ISO_8859_1)));

        assertEquals(got.head, Arrays.asList("Host: 127.0.0.1:" + backend.port(), "X-A: 1", "X-B: 2",
                "X-Forwarded-Host: gw.example", "Content-Length: 5", "Connection: close"), got.headerLines());
    }

    // FR-PROXY-004 AC-1: over a TinyHttpd socket whose allowlist admits gw.example, the real client Host line becomes
    // X-Forwarded-Host and the backend gets the target as Host
    @Test(timeout = 30000)
    public void fr_PROXY_004_AC1_overATinyHttpdSocket() throws IOException {
        RawBackend backend = okBackend();
        int port = tinyHttpdProxy(Proxy.to(backend.url()), "gw.example");

        String response = exchange(port, "GET /api/x HTTP/1.1\r\nHost: gw.example\r\nConnection: close\r\n\r\n");

        assertTrue(response, response.startsWith("HTTP/1.1 200 "));
        RawBackend.Received got = backend.onlyRequest();
        assertEquals(Collections.singletonList("127.0.0.1:" + backend.port()), got.headerValues("Host"));
        assertEquals(Collections.singletonList("gw.example"), forwardedHosts(got));
    }

    // ---- AC-2 ----

    // FR-PROXY-004 AC-2: the Host of http://h, http://h:80 and https://h:443 is h; of https://h:8443 it is h:8443; of
    // http://[::1]:8081 it is [::1]:8081
    @Test
    public void fr_PROXY_004_AC2_hostOfTheSrsExamples() {
        assertEquals("h", TargetUrl.parse("http://h").hostHeader());
        assertEquals("h", TargetUrl.parse("http://h:80").hostHeader());
        assertEquals("h", TargetUrl.parse("https://h:443").hostHeader());
        assertEquals("h:8443", TargetUrl.parse("https://h:8443").hostHeader());
        assertEquals("[::1]:8081", TargetUrl.parse("http://[::1]:8081").hostHeader());
    }

    // FR-PROXY-004 AC-2: the default port is the default of the URL's own scheme, so http://h:443 and https://h:80
    // keep their port; an IPv6 literal on its default port is bracketed without a port; a base path and the scheme's
    // letter case do not change the Host
    @Test
    public void fr_PROXY_004_AC2_theDefaultPortBelongsToTheScheme() {
        assertEquals("h:443", TargetUrl.parse("http://h:443").hostHeader());
        assertEquals("h:80", TargetUrl.parse("https://h:80").hostHeader());
        assertEquals("h", TargetUrl.parse("https://h").hostHeader());
        assertEquals("[::1]", TargetUrl.parse("http://[::1]").hostHeader());
        assertEquals("[::1]", TargetUrl.parse("https://[::1]:443").hostHeader());
        assertEquals("[2001:db8::1]:8443", TargetUrl.parse("https://[2001:db8::1]:8443").hostHeader());
        assertEquals("h", TargetUrl.parse("HTTP://h:80/base/").hostHeader());
        assertEquals("api.example:8081", TargetUrl.parse("http://api.example:8081/v1").hostHeader());
    }

    // FR-PROXY-004 AC-2: the Host the proxy writes for http://h, http://h:80, https://h:443, https://h:8443 and
    // http://[::1]:8081 (backends a test cannot connect to) is the target Host of the SRS examples
    @Test
    public void fr_PROXY_004_AC2_theProxyWritesTheTargetHostOfTheSrsExamples() {
        assertEquals("h", Proxy.to("http://h").backendHost("gw.example"));
        assertEquals("h", Proxy.to("http://h:80").backendHost("gw.example"));
        assertEquals("h", Proxy.to("https://h:443").backendHost(null));
        assertEquals("h:8443", Proxy.to("https://h:8443").backendHost("gw.example"));
        assertEquals("[::1]:8081", Proxy.to("http://[::1]:8081").backendHost(null));
    }

    // FR-PROXY-004 AC-3 and AC-4: under .preserveHost(true) the proxy writes the received Host, and the target Host
    // when there is none
    @Test
    public void fr_PROXY_004_AC3_AC4_preserveHostChoosesTheReceivedHostWhenThereIsOne() {
        Proxy preserving = Proxy.to("http://h:80").preserveHost(true);

        assertEquals("gw.example", preserving.backendHost("gw.example"));
        assertEquals("h", preserving.backendHost(null));
    }

    // FR-PROXY-004 AC-2: Proxy.to("http://[::1]:<port>") sends Host: [::1]:<port> to an IPv6 loopback backend
    @Test
    public void fr_PROXY_004_AC2_ipv6TargetSendsABracketedHost() throws IOException {
        ServerSocket v6;
        try {
            v6 = new ServerSocket(0, 50, InetAddress.getByName("::1"));
        } catch (IOException e) {
            Assume.assumeNoException("no IPv6 loopback on this machine", e);
            return;
        }
        closeables.add(v6);
        AtomicReference<String> head = new AtomicReference<>();
        Thread acceptor = new Thread(() -> recordOnce(v6, head), "v6-backend");
        acceptor.setDaemon(true);
        acceptor.start();
        mount(Proxy.to("http://[::1]:" + v6.getLocalPort()));

        ClientResponse r = adapter.send(request("GET", "Host", "gw.example"));

        assertEquals(200, r.status());
        List<String> lines = Arrays.asList(head.get().split("\r\n", -1));
        assertEquals(head.get(), "Host: [::1]:" + v6.getLocalPort(), lines.get(1));
        assertTrue(head.get(), lines.contains("X-Forwarded-Host: gw.example"));
    }

    // ---- AC-3 ----

    // FR-PROXY-004 AC-3: with .preserveHost(true) the backend receives the client's Host: gw.example unchanged and
    // also X-Forwarded-Host: gw.example
    @Test
    public void fr_PROXY_004_AC3_preserveHostSendsTheReceivedHost() throws IOException {
        RawBackend backend = okBackend();

        RawBackend.Received got = forward(backend, Proxy.to(backend.url()).preserveHost(true),
                request("GET", "Host", "gw.example"));

        assertEquals("Host: gw.example", got.headerLines().get(0));
        assertEquals(Collections.singletonList("gw.example"), got.headerValues("Host"));
        assertEquals(Collections.singletonList("gw.example"), forwardedHosts(got));
    }

    // FR-PROXY-004 AC-3: the received Host is kept byte for byte, letter case and port included
    @Test
    public void fr_PROXY_004_AC3_preserveHostKeepsLetterCaseAndPort() throws IOException {
        RawBackend backend = okBackend();

        RawBackend.Received got = forward(backend, Proxy.to(backend.url()).preserveHost(true),
                request("GET", "host", "GW.Example:8080"));

        assertEquals("Host: GW.Example:8080", got.headerLines().get(0));
        assertEquals(Collections.singletonList("GW.Example:8080"), forwardedHosts(got));
    }

    // FR-PROXY-004 AC-3: without the option (and with .preserveHost(false)) Host is replaced as in AC-1
    @Test
    public void fr_PROXY_004_AC3_withoutTheOptionHostIsReplaced() throws IOException {
        RawBackend backend = okBackend();

        RawBackend.Received got = forward(backend, Proxy.to(backend.url()).preserveHost(true).preserveHost(false),
                request("GET", "Host", "gw.example"));

        assertEquals("Host: 127.0.0.1:" + backend.port(), got.headerLines().get(0));
        assertEquals(1, got.headerValues("Host").size());
        assertEquals(Collections.singletonList("gw.example"), forwardedHosts(got));
    }

    // ---- AC-4 ----

    // FR-PROXY-004 AC-4: a request the adapter reports without Host gets the target host as Host and no
    // X-Forwarded-Host
    @Test
    public void fr_PROXY_004_AC4_noReceivedHostWithoutPreserveHost() throws IOException {
        RawBackend backend = okBackend();

        RawBackend.Received got = forward(backend, Proxy.to(backend.url()), request("GET", "X-A", "1"));

        assertEquals("Host: 127.0.0.1:" + backend.port(), got.headerLines().get(0));
        assertEquals(1, got.headerValues("Host").size());
        assertEquals(Collections.<String>emptyList(), forwardedHosts(got));
    }

    // FR-PROXY-004 AC-4: the same under .preserveHost(true): there is no received Host to keep
    @Test
    public void fr_PROXY_004_AC4_noReceivedHostWithPreserveHost() throws IOException {
        RawBackend backend = okBackend();

        RawBackend.Received got = forward(backend, Proxy.to(backend.url()).preserveHost(true),
                request("GET", "X-A", "1"));

        assertEquals("Host: 127.0.0.1:" + backend.port(), got.headerLines().get(0));
        assertEquals(1, got.headerValues("Host").size());
        assertEquals(Collections.<String>emptyList(), forwardedHosts(got));
    }

    // FR-PROXY-004 AC-4: an empty Host value names no host, so it counts as no Host, with and without .preserveHost
    // (this reading of "reports no Host" is proposed to the SRS as an AC-4 clarification)
    @Test
    public void fr_PROXY_004_AC4_anEmptyHostCountsAsNoHost() throws IOException {
        RawBackend plain = okBackend();
        RawBackend preserving = okBackend();
        app.use("/api", Proxy.to(plain.url()));
        app.use("/keep", Proxy.to(preserving.url()).preserveHost(true));
        t.start();
        started.add(app);

        ClientResponse r1 = adapter.send(request("GET", "Host", ""));
        ClientResponse r2 = adapter.send(ClientRequest.of("GET", "/keep/x").header("Host", ""));

        assertEquals(200, r1.status());
        assertEquals(200, r2.status());
        assertEquals(Collections.singletonList("127.0.0.1:" + plain.port()), plain.onlyRequest().headerValues("Host"));
        assertEquals(Collections.<String>emptyList(), forwardedHosts(plain.onlyRequest()));
        assertEquals(Collections.singletonList("127.0.0.1:" + preserving.port()),
                preserving.onlyRequest().headerValues("Host"));
        assertEquals(Collections.<String>emptyList(), forwardedHosts(preserving.onlyRequest()));
    }

    // ---- AC-5 ----

    /** Sends each request to {@code proxy} whose backend counts connections; asserts 400 for all and none opened. */
    private void assertRefusedWithoutConnection(ConnectionCounter backend, Proxy proxy, ClientRequest... requests)
            throws IOException {
        mount(proxy);

        for (ClientRequest request : requests) {
            ClientResponse r = adapter.send(request);
            assertEquals("status", 400, r.status());
        }
        assertEquals("backend connections opened", 0, backend.connectionsBeforeSentinel());
    }

    // FR-PROXY-004 AC-5: two Host lines get 400 and no backend connection is opened, whatever their values and name
    // letter case
    @Test
    public void fr_PROXY_004_AC5_twoHostLinesGet400WithoutConnecting() throws IOException {
        ConnectionCounter backend = counter();

        assertRefusedWithoutConnection(backend, Proxy.to(backend.url()),
                request("GET", "Host", "gw.example", "Host", "evil.example"),
                request("GET", "Host", "gw.example", "Host", "gw.example"),
                request("GET", "Host", "gw.example", "X-A", "1", "host", "evil.example"),
                request("GET", "Host", "", "Host", "gw.example"));
    }

    // FR-PROXY-004 AC-5: the same under .preserveHost(true), where the received Host would be sent
    @Test
    public void fr_PROXY_004_AC5_twoHostLinesGet400UnderPreserveHost() throws IOException {
        ConnectionCounter backend = counter();

        assertRefusedWithoutConnection(backend, Proxy.to(backend.url()).preserveHost(true),
                request("GET", "Host", "gw.example", "Host", "evil.example"));
    }

    // FR-PROXY-004 AC-5: the multiple-Host check comes before the framing answers (D-050), so two Host lines with an
    // unsupported Transfer-Encoding (a 501 on its own) answer 400
    @Test
    public void fr_PROXY_004_AC5_theHostCheckComesBeforeTheFramingAnswers() throws IOException {
        ConnectionCounter backend = counter();

        assertRefusedWithoutConnection(backend, Proxy.to(backend.url()),
                request("POST", "Host", "a", "Host", "b", "Transfer-Encoding", "gzip, chunked")
                        .body("hello".getBytes(StandardCharsets.ISO_8859_1)));
    }

    // FR-PROXY-004 AC-5: on an https target, which the proxy does not reach yet (502), two Host lines answer the
    // header check's 400 (D-050: the header checks come first)
    @Test
    public void fr_PROXY_004_AC5_theHostCheckComesBeforeTheTargetScheme() {
        mount(Proxy.to("https://127.0.0.1:1"));

        ClientResponse r = adapter.send(request("GET", "Host", "a", "Host", "b"));

        assertEquals(400, r.status());
    }

    // FR-PROXY-004 AC-5: over a TinyHttpd socket two Host lines get 400 and no backend connection, whichever of the
    // adapter and the proxy refuses them
    @Test(timeout = 30000)
    public void fr_PROXY_004_AC5_overATinyHttpdSocket() throws IOException {
        ConnectionCounter backend = counter();
        int port = tinyHttpdProxy(Proxy.to(backend.url()), "gw.example", "evil.example");

        String response = exchange(port, "GET /api/x HTTP/1.1\r\nHost: gw.example\r\nHost: evil.example\r\n"
                + "Connection: close\r\n\r\n");

        assertTrue(response, response.startsWith("HTTP/1.1 400 "));
        assertEquals("backend connections", 0, backend.connectionsBeforeSentinel());
    }

    // ---- test support ----

    /** A TinyHttpd app with {@code proxy} at /api and the extra allowed host names; returns its port. */
    private int tinyHttpdProxy(Proxy proxy, String... allowedHosts) {
        RestApp g = RestApp.builder().httpAdapter(new TinyHttpdAdapter()).jsonCodec(new StubJsonCodec())
                .logger(new RecordingLogger()).build();
        started.add(g);
        g.server(o -> o.allowedHosts(allowedHosts));
        g.use("/api", proxy);
        return g.listen(0).port();
    }

    private static String exchange(int port, String request) throws IOException {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            s.setSoTimeout(10000);
            OutputStream out = s.getOutputStream();
            out.write(request.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            try {
                InputStream in = s.getInputStream();
                for (int n; (n = in.read(b)) >= 0; ) {
                    buf.write(b, 0, n);
                }
            } catch (SocketException reset) {
                // a reset after the close counts as the end
            }
            return new String(buf.toByteArray(), StandardCharsets.ISO_8859_1);
        }
    }

    /** Reads one request head on {@code server} into {@code head} and answers 200 with an empty body. */
    private static void recordOnce(ServerSocket server, AtomicReference<String> head) {
        try (Socket s = server.accept()) {
            s.setSoTimeout(10_000);
            InputStream in = s.getInputStream();
            StringBuilder text = new StringBuilder();
            while (text.length() < 4 || !text.substring(text.length() - 4).equals("\r\n\r\n")) {
                int b = in.read();
                if (b < 0) {
                    return;
                }
                text.append((char) b);
            }
            head.set(text.substring(0, text.length() - 4));
            OutputStream out = s.getOutputStream();
            out.write("HTTP/1.1 200 X\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
        } catch (IOException e) {
            // the test fails on the status or the missing head instead
        }
    }

    /**
     * A loopback listener that records the client port of every accepted connection and closes it at once.
     * {@link #connectionsBeforeSentinel} closes the race between a connect that already completed and the acceptor
     * thread that has not yet taken it (copied from ProxyRequestHeadersTest).
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
