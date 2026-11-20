package com.snoworca.naruserve.proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.Response;
import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.testing.StubJsonCodec;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.HandshakeCompletedListener;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * The proxy's own closes of an https backend connection (FR-PROXY-020, FR-PROXY-017, FR-PROXY-018, SEC-PROXY-005):
 * the end of the relayed body, the release on an error path and the close before a retry each close the plain socket
 * under TLS first, never starting with the {@code SSLSocket}, whose TLS 1.3 duplex close sends {@code close_notify}
 * and then reads until the backend answers or the read timeout passes. Every backend here is limited to TLS 1.3, keeps its connection open
 * after it answered and never reads again, so a close that waited for it would hold the worker and the slot for one
 * more read timeout. Every path also closes the {@code SSLSocket} itself once the plain socket under it is closed, so
 * no TLS socket is left for its finalizer (on Android its native SSL state, which StrictMode reports as a leak): with
 * the transport gone that close can send and read nothing, so it never waits for the backend. The tests see the
 * proxy's {@code close()} calls themselves, through a delegating {@code SSLSocket} that records each call, its thread
 * and whether the plain socket was already closed, not the socket's closed state, which the JDK also sets on its own
 * when a read fails. An abort on another thread closes only the plain socket; the {@code SSLSocket} is closed by the
 * relayed body's close, on the copy thread once its read returned or, for an abort while no copy is running, on the
 * aborting thread, so no thread closes it under a read (on Android, Conscrypt's close waits for a pending read).
 */
public class ProxyTlsCloseTest {

    private static final char[] PASSWORD = "changeit".toCharArray();
    private static final String[] TLS13 = {"TLSv1.3"};

    private static File dir;
    private static SSLContext server;
    private static SSLContext client;

    private final List<KeptOpenBackend> backends = new ArrayList<>();
    private final List<RestApp> apps = new ArrayList<>();

    @BeforeClass
    public static void makeCertificate() throws Exception {
        dir = java.nio.file.Files.createTempDirectory("proxy-tls-close").toFile();
        keytool("-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=localhost",
                "-ext", "SAN=ip:127.0.0.1", "-validity", "2", "-keystore", "server.p12");
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = new FileInputStream(new File(dir, "server.p12"))) {
            ks.load(in, PASSWORD);
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, PASSWORD);
        server = SSLContext.getInstance("TLS");
        server.init(kmf.getKeyManagers(), null, null);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        client = SSLContext.getInstance("TLS");
        client.init(null, tmf.getTrustManagers(), null);
    }

    @AfterClass
    public static void removeCertificate() {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                f.delete();
            }
        }
        dir.delete();
    }

    @After
    public void stop() throws Exception {
        BackendClient.resolver = BackendClient.SYSTEM_RESOLVER;
        TlsConnector.endpointIdentification = true;
        for (RestApp app : apps) {
            app.shutdown(2, TimeUnit.SECONDS);
        }
        for (KeptOpenBackend b : backends) {
            b.close();
        }
    }

    // @req FR-PROXY-020 AC-4
    // @req SEC-PROXY-005
    // FR-PROXY-020 AC-4 over https (TLS 1.3): a backend that sent a complete Content-Length response and keeps the
    // connection open delays neither the client nor the slot; the end of the relayed body closes the plain socket,
    // so with maxConcurrent(1) a second request is answered at once instead of one read timeout later
    @Test(timeout = 60000)
    public void fr_PROXY_020_AC4_theEndOfAnHttpsBodyFreesTheSlotWithoutWaitingForTheBackend() throws Exception {
        KeptOpenBackend backend = start(new String[] {"HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok"});
        InMemoryHttpAdapter adapter = mount(Proxy.to(backend.url()).sslSocketFactory(client.getSocketFactory())
                .readTimeout(5, TimeUnit.SECONDS).maxConcurrent(1));

        long begin = System.nanoTime();
        ClientResponse first = adapter.submit(ClientRequest.of("GET", "/p/one")).get(20, TimeUnit.SECONDS);
        ClientResponse second = adapter.submit(ClientRequest.of("GET", "/p/two")).get(20, TimeUnit.SECONDS);
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);

        assertEquals(200, first.status());
        assertEquals("ok", first.text());
        assertEquals("second status after " + millis + " ms", 200, second.status());
        assertEquals("ok", second.text());
        assertEquals(2, backend.connections.get());
        assertTrue("two https responses with readTimeout 5 s took " + millis + " ms", millis < 2500);
    }

    // @req FR-PROXY-017 AC-4
    // @req SEC-PROXY-005
    // FR-PROXY-017 AC-4 over https (TLS 1.3): a backend that reads the request and never answers gives 504
    // READ_TIMEOUT about one read timeout after the request; the release on the error path closes the plain socket
    // and does not read for a second read timeout
    @Test(timeout = 60000)
    public void fr_PROXY_017_AC4_anHttpsReadTimeoutIsAnsweredAfterOneReadTimeout() throws Exception {
        KeptOpenBackend backend = start(new String[] {null});
        InMemoryHttpAdapter adapter = mount(Proxy.to(backend.url()).sslSocketFactory(client.getSocketFactory())
                .readTimeout(2, TimeUnit.SECONDS).maxConcurrent(1));

        long begin = System.nanoTime();
        ClientResponse r = adapter.submit(ClientRequest.of("GET", "/p/silent")).get(20, TimeUnit.SECONDS);
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);

        assertEquals(504, r.status());
        assertTrue("504 READ_TIMEOUT with readTimeout 2 s arrived after " + millis + " ms",
                millis >= 1500 && millis < 3500);
    }

    // @req FR-PROXY-018
    // @req FR-PROXY-020 AC-4
    // @req SEC-PROXY-005
    // FR-PROXY-018 over https (TLS 1.3): the close of a 503 attempt before its retry closes the plain socket, so a
    // backend that keeps the 503 connection open does not delay the retry by one read timeout
    @Test(timeout = 60000)
    public void fr_PROXY_018_theCloseBeforeAnHttpsRetryDoesNotWaitForTheBackend() throws Exception {
        KeptOpenBackend backend = start(new String[] {"HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\n\r\n",
                "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok"});
        InMemoryHttpAdapter adapter = mount(Proxy.to(backend.url()).sslSocketFactory(client.getSocketFactory())
                .readTimeout(5, TimeUnit.SECONDS).retries(1).retryDelay(10, TimeUnit.MILLISECONDS)
                .maxConcurrent(1));

        long begin = System.nanoTime();
        ClientResponse r = adapter.submit(ClientRequest.of("GET", "/p/retried")).get(20, TimeUnit.SECONDS);
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);

        assertEquals("status after " + millis + " ms", 200, r.status());
        assertEquals("ok", r.text());
        assertEquals(2, backend.connections.get());
        assertTrue("a retried https request with readTimeout 5 s took " + millis + " ms", millis < 2500);
    }

    // @req SEC-PROXY-005
    // @req FR-PROXY-020 AC-4
    // @req FR-PROXY-019
    // SEC-PROXY-005 / FR-PROXY-019 over https (TLS 1.3): the end of the relayed body closes the SSLSocket the proxy
    // created, after its plain socket, and the two responses still take nothing near a read timeout
    @Test(timeout = 60000)
    public void sec_PROXY_005_theEndOfAnHttpsBodyClosesTheSslSocket() throws Exception {
        KeptOpenBackend backend = start(new String[] {"HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok"});
        RecordingSslSockets sockets = new RecordingSslSockets(client.getSocketFactory());
        InMemoryHttpAdapter adapter = mount(Proxy.to(backend.url()).sslSocketFactory(sockets)
                .readTimeout(5, TimeUnit.SECONDS).maxConcurrent(1));

        long begin = System.nanoTime();
        ClientResponse first = adapter.submit(ClientRequest.of("GET", "/p/one")).get(20, TimeUnit.SECONDS);
        ClientResponse second = adapter.submit(ClientRequest.of("GET", "/p/two")).get(20, TimeUnit.SECONDS);
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);

        assertEquals(200, first.status());
        assertEquals(200, second.status());
        assertEquals(2, sockets.created.size());
        assertAllClosed(sockets);
        assertTrue("two https responses with readTimeout 5 s took " + millis + " ms", millis < 2500);
    }

    // @req SEC-PROXY-005
    // @req FR-PROXY-017 AC-4
    // @req FR-PROXY-019
    // SEC-PROXY-005 / FR-PROXY-017 AC-4 over https (TLS 1.3): the release on the 504 READ_TIMEOUT path closes the
    // SSLSocket after its plain socket, and the 504 still arrives about one read timeout after the request
    @Test(timeout = 60000)
    public void sec_PROXY_005_theReleaseOnAnHttpsReadTimeoutClosesTheSslSocket() throws Exception {
        KeptOpenBackend backend = start(new String[] {null});
        RecordingSslSockets sockets = new RecordingSslSockets(client.getSocketFactory());
        InMemoryHttpAdapter adapter = mount(Proxy.to(backend.url()).sslSocketFactory(sockets)
                .readTimeout(2, TimeUnit.SECONDS).maxConcurrent(1));

        long begin = System.nanoTime();
        ClientResponse r = adapter.submit(ClientRequest.of("GET", "/p/silent")).get(20, TimeUnit.SECONDS);
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);

        assertEquals(504, r.status());
        assertEquals(1, sockets.created.size());
        assertAllClosed(sockets);
        assertTrue("504 READ_TIMEOUT with readTimeout 2 s arrived after " + millis + " ms",
                millis >= 1500 && millis < 3500);
    }

    // @req SEC-PROXY-005
    // @req FR-PROXY-018
    // @req FR-PROXY-019
    // SEC-PROXY-005 / FR-PROXY-018 over https (TLS 1.3): the close of the 503 attempt before its retry closes that
    // attempt's SSLSocket after its plain socket, the relayed attempt's SSLSocket is closed at the end of its body, and
    // the retried request still takes nothing near a read timeout
    @Test(timeout = 60000)
    public void sec_PROXY_005_theCloseBeforeAnHttpsRetryClosesTheSslSocket() throws Exception {
        KeptOpenBackend backend = start(new String[] {"HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\n\r\n",
                "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok"});
        RecordingSslSockets sockets = new RecordingSslSockets(client.getSocketFactory());
        InMemoryHttpAdapter adapter = mount(Proxy.to(backend.url()).sslSocketFactory(sockets)
                .readTimeout(5, TimeUnit.SECONDS).retries(1).retryDelay(10, TimeUnit.MILLISECONDS)
                .maxConcurrent(1));

        long begin = System.nanoTime();
        ClientResponse r = adapter.submit(ClientRequest.of("GET", "/p/retried")).get(20, TimeUnit.SECONDS);
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);

        assertEquals("status after " + millis + " ms", 200, r.status());
        assertEquals(2, sockets.created.size());
        assertAllClosed(sockets);
        assertTrue("a retried https request with readTimeout 5 s took " + millis + " ms", millis < 2500);
    }

    // @req SEC-PROXY-005
    // @req FR-PROXY-019
    // SEC-PROXY-005 over https (TLS 1.3): a host name the held verifier rejects after a completed handshake fails the
    // request with 502 and closes the SSLSocket the failed connect created, after its plain socket
    @Test(timeout = 60000)
    public void sec_PROXY_005_aRejectedHostNameClosesTheSslSocket() throws Exception {
        KeptOpenBackend backend = start(new String[] {"HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok"});
        BackendClient.resolver = host -> InetAddress.getByName("127.0.0.1");
        TlsConnector.endpointIdentification = false; // only the held verifier checks the host, after the handshake
        RecordingSslSockets sockets = new RecordingSslSockets(client.getSocketFactory());
        InMemoryHttpAdapter adapter = mount(Proxy.to("https://other-host.test:" + backend.port())
                .sslSocketFactory(sockets).readTimeout(5, TimeUnit.SECONDS).maxConcurrent(1));

        long begin = System.nanoTime();
        ClientResponse r = adapter.submit(ClientRequest.of("GET", "/p/wrong-host")).get(20, TimeUnit.SECONDS);
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);

        assertEquals(502, r.status());
        assertEquals(1, sockets.created.size());
        assertAllClosed(sockets);
        assertTrue("a rejected https host name took " + millis + " ms", millis < 2500);
    }

    // @req SEC-PROXY-005
    // @req FR-PROXY-019
    // @req REL-PROXY-001
    // SEC-PROXY-005 / FR-PROXY-019 over https (TLS 1.3), thread safety of the SSLSocket close: while the request thread
    // is blocked reading a relayed body the backend stopped sending (readTimeout 10 s), res.abort on another thread runs
    // the release there; it closes only the plain socket, which ends the blocked read, and returns at once. The
    // SSLSocket's close() runs afterwards on the thread that owned the read, never on the aborting thread, so no close
    // runs under a pending read (Conscrypt's close would wait for it)
    @Test(timeout = 60000)
    public void sec_PROXY_005_anAbortOnAnotherThreadClosesTheSslSocketWithoutWaitingForTheReader() throws Exception {
        KeptOpenBackend backend = start(new String[] {"HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\nab"});
        RecordingSslSockets sockets = new RecordingSslSockets(client.getSocketFactory());
        final AtomicReference<Response> captured = new AtomicReference<>();
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        RestApp app = RestApp.builder().httpAdapter(adapter).jsonCodec(new StubJsonCodec())
                .logger(new RecordingLogger()).build();
        app.use((req, res, next) -> {
            captured.set(res);
            next.run();
        });
        app.use("/p", Proxy.to(backend.url()).sslSocketFactory(sockets).readTimeout(10, TimeUnit.SECONDS)
                .maxConcurrent(1));
        app.listen(0);
        apps.add(app);

        Future<ClientResponse> pending = adapter.submit(ClientRequest.of("GET", "/p/stalled"));
        assertTrue("the backend wrote its partial response", backend.written.await(20, TimeUnit.SECONDS));
        Thread.sleep(500); // the request thread reads the two body bytes and blocks reading the third
        assertEquals(1, sockets.created.size());
        assertEquals("the SSLSocket was closed before the abort", 0, sockets.created.get(0).closes.size());

        Thread aborting = Thread.currentThread();
        long begin = System.nanoTime();
        captured.get().abort(null);
        long abortMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);
        ClientResponse r = pending.get(20, TimeUnit.SECONDS);
        long endMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);

        assertTrue("the response ended abnormally", r.abnormallyClosed());
        assertAllClosed(sockets);
        for (CloseCall c : sockets.created.get(0).closes) {
            assertTrue("the SSLSocket's close() ran on the thread that called res.abort, " + c.thread.getName()
                    + ", while the request thread could still be reading it", c.thread != aborting);
        }
        assertTrue("res.abort on another thread took " + abortMillis + " ms", abortMillis < 1000);
        assertTrue("the aborted response ended " + endMillis + " ms after res.abort", endMillis < 2500);
    }

    // @req SEC-PROXY-005
    // @req FR-PROXY-019
    // @req REL-PROXY-001
    // SEC-PROXY-005 / FR-PROXY-019 over https (TLS 1.3): an abort after res.relay took the body but before its copy
    // started (the handler has not returned yet) closes the relayed body at once, which closes the SSLSocket after its
    // plain socket; the abort listener's release that follows closes nothing more
    @Test(timeout = 60000)
    public void sec_PROXY_005_anAbortBeforeTheCopyStartsClosesTheSslSocket() throws Exception {
        KeptOpenBackend backend = start(new String[] {"HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\nab"});
        RecordingSslSockets sockets = new RecordingSslSockets(client.getSocketFactory());
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        RestApp app = RestApp.builder().httpAdapter(adapter).jsonCodec(new StubJsonCodec())
                .logger(new RecordingLogger()).build();
        app.use((req, res, next) -> {
            next.run(); // the proxy has handed its body to res.relay; the copy runs once this handler returns
            res.abort(null);
        });
        app.use("/p", Proxy.to(backend.url()).sslSocketFactory(sockets).readTimeout(10, TimeUnit.SECONDS)
                .maxConcurrent(1));
        app.listen(0);
        apps.add(app);

        long begin = System.nanoTime();
        ClientResponse r = adapter.submit(ClientRequest.of("GET", "/p/aborted")).get(20, TimeUnit.SECONDS);
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);

        assertTrue("the response ended abnormally", r.abnormallyClosed());
        assertEquals(1, sockets.created.size());
        assertAllClosed(sockets);
        assertTrue("the aborted https response took " + millis + " ms", millis < 2500);
    }

    /**
     * Asserts that the proxy called {@code close()} on every SSLSocket it created, waiting up to 5 s for a close still
     * under way, that each call came after the plain socket under it was closed, and that the real socket is closed.
     */
    private static void assertAllClosed(RecordingSslSockets sockets) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        for (RecordingSocket s : sockets.created) {
            while (s.closes.isEmpty() && System.nanoTime() < until) {
                Thread.sleep(10);
            }
            assertTrue("the proxy never called close() on an SSLSocket it created: " + s.d, !s.closes.isEmpty());
            for (CloseCall c : s.closes) {
                assertTrue("the SSLSocket's close() on " + c.thread.getName() + " ran before its plain socket's close",
                        c.plainClosed);
            }
            assertTrue("the SSLSocket is still open after close(): " + s.d, s.d.isClosed());
        }
    }

    private KeptOpenBackend start(String[] responses) throws IOException {
        KeptOpenBackend b = new KeptOpenBackend(responses);
        backends.add(b);
        return b;
    }

    private InMemoryHttpAdapter mount(Proxy proxy) {
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        RestApp app = RestApp.builder().httpAdapter(adapter).jsonCodec(new StubJsonCodec())
                .logger(new RecordingLogger()).build();
        app.use("/p", proxy);
        app.listen(0);
        apps.add(app);
        return adapter;
    }

    /**
     * A TLS 1.3 backend that, on its n-th connection, reads the request head, writes {@code responses[n]} (the last
     * one past the end; nothing for null) and then keeps the connection open without reading or closing it until
     * closed.
     */
    private static final class KeptOpenBackend {
        final AtomicInteger connections = new AtomicInteger();
        /** Counted down once a response was written and flushed. */
        final CountDownLatch written = new CountDownLatch(1);
        private final SSLServerSocket listener;
        private final CountDownLatch hold = new CountDownLatch(1);
        private final List<Socket> open = new ArrayList<>();

        KeptOpenBackend(final String[] responses) throws IOException {
            listener = (SSLServerSocket) server.getServerSocketFactory().createServerSocket(0, 50,
                    InetAddress.getByName("127.0.0.1"));
            listener.setEnabledProtocols(TLS13);
            Thread acceptor = new Thread(() -> {
                while (!listener.isClosed()) {
                    final Socket s;
                    try {
                        s = listener.accept();
                    } catch (IOException e) {
                        return;
                    }
                    synchronized (open) {
                        open.add(s);
                    }
                    final int n = connections.getAndIncrement();
                    final String response = responses[Math.min(n, responses.length - 1)];
                    Thread handler = new Thread(() -> serve((SSLSocket) s, response));
                    handler.setDaemon(true);
                    handler.start();
                }
            }, "kept-open-tls13-backend");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        String url() {
            return "https://127.0.0.1:" + port();
        }

        int port() {
            return listener.getLocalPort();
        }

        private void serve(SSLSocket ssl, String response) {
            try {
                ssl.startHandshake();
                InputStream in = ssl.getInputStream();
                byte[] end = {'\r', '\n', '\r', '\n'};
                int matched = 0;
                for (int b; matched < 4 && (b = in.read()) != -1; ) {
                    matched = b == end[matched] ? matched + 1 : (b == '\r' ? 1 : 0);
                }
                if (response != null) {
                    OutputStream out = ssl.getOutputStream();
                    out.write(response.getBytes(StandardCharsets.ISO_8859_1));
                    out.flush();
                    written.countDown();
                }
                hold.await(50, TimeUnit.SECONDS); // keeps the connection open and never reads again
            } catch (Exception e) {
                // the proxy went away or the test ended
            }
        }

        void close() throws IOException {
            hold.countDown();
            listener.close();
            synchronized (open) {
                for (Socket s : open) {
                    try {
                        s.close();
                    } catch (IOException ignored) {
                        // closing either way
                    }
                }
            }
        }
    }

    /**
     * The given real JDK socket factory, wrapping every {@code SSLSocket} it layers over a plain socket in a
     * {@link RecordingSocket}, so a test can see the proxy's {@code close()} calls on it. The wrapped sockets are the
     * factory's own; every call reaches them unchanged.
     */
    private static final class RecordingSslSockets extends SSLSocketFactory {
        final List<RecordingSocket> created = new CopyOnWriteArrayList<>();
        private final SSLSocketFactory factory;

        RecordingSslSockets(SSLSocketFactory factory) {
            this.factory = factory;
        }

        @Override
        public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
            RecordingSocket ssl = new RecordingSocket((SSLSocket) factory.createSocket(s, host, port, autoClose), s);
            created.add(ssl);
            return ssl;
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return factory.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return factory.getSupportedCipherSuites();
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            return factory.createSocket(host, port);
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
            return factory.createSocket(host, port, localHost, localPort);
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            return factory.createSocket(host, port);
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort)
                throws IOException {
            return factory.createSocket(address, port, localAddress, localPort);
        }
    }

    /** One {@code close()} call on a {@link RecordingSocket}: its thread and whether the plain socket was closed. */
    private static final class CloseCall {
        final Thread thread;
        final boolean plainClosed;

        CloseCall(Thread thread, boolean plainClosed) {
            this.thread = thread;
            this.plainClosed = plainClosed;
        }
    }

    /**
     * Delegates the abstract {@link SSLSocket} methods and the stream, timeout and close methods to the real socket
     * {@code d}, and records every {@code close()} call with its thread and whether {@code plain}, the socket under
     * TLS, was already closed, before passing it on.
     */
    private static final class RecordingSocket extends SSLSocket {
        final SSLSocket d;
        final List<CloseCall> closes = new CopyOnWriteArrayList<>();
        private final Socket plain;

        RecordingSocket(SSLSocket d, Socket plain) {
            this.d = d;
            this.plain = plain;
        }

        @Override
        public void close() throws IOException {
            closes.add(new CloseCall(Thread.currentThread(), plain.isClosed()));
            d.close();
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return d.getInputStream();
        }

        @Override
        public OutputStream getOutputStream() throws IOException {
            return d.getOutputStream();
        }

        @Override
        public boolean isConnected() {
            return d.isConnected();
        }

        @Override
        public boolean isClosed() {
            return d.isClosed();
        }

        @Override
        public void setSoTimeout(int timeout) throws SocketException {
            d.setSoTimeout(timeout);
        }

        @Override
        public int getSoTimeout() throws SocketException {
            return d.getSoTimeout();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return d.getSupportedCipherSuites();
        }

        @Override
        public String[] getEnabledCipherSuites() {
            return d.getEnabledCipherSuites();
        }

        @Override
        public void setEnabledCipherSuites(String[] suites) {
            d.setEnabledCipherSuites(suites);
        }

        @Override
        public String[] getSupportedProtocols() {
            return d.getSupportedProtocols();
        }

        @Override
        public String[] getEnabledProtocols() {
            return d.getEnabledProtocols();
        }

        @Override
        public void setEnabledProtocols(String[] protocols) {
            d.setEnabledProtocols(protocols);
        }

        @Override
        public SSLSession getSession() {
            return d.getSession();
        }

        @Override
        public void addHandshakeCompletedListener(HandshakeCompletedListener listener) {
            d.addHandshakeCompletedListener(listener);
        }

        @Override
        public void removeHandshakeCompletedListener(HandshakeCompletedListener listener) {
            d.removeHandshakeCompletedListener(listener);
        }

        @Override
        public void startHandshake() throws IOException {
            d.startHandshake();
        }

        @Override
        public void setUseClientMode(boolean mode) {
            d.setUseClientMode(mode);
        }

        @Override
        public boolean getUseClientMode() {
            return d.getUseClientMode();
        }

        @Override
        public void setNeedClientAuth(boolean need) {
            d.setNeedClientAuth(need);
        }

        @Override
        public boolean getNeedClientAuth() {
            return d.getNeedClientAuth();
        }

        @Override
        public void setWantClientAuth(boolean want) {
            d.setWantClientAuth(want);
        }

        @Override
        public boolean getWantClientAuth() {
            return d.getWantClientAuth();
        }

        @Override
        public void setEnableSessionCreation(boolean flag) {
            d.setEnableSessionCreation(flag);
        }

        @Override
        public boolean getEnableSessionCreation() {
            return d.getEnableSessionCreation();
        }

        @Override
        public String toString() {
            return d.toString();
        }
    }

    private static void keytool(String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(new File(System.getProperty("java.home"), "bin/keytool").getPath());
        command.addAll(Arrays.asList(args));
        command.addAll(Arrays.asList("-storetype", "PKCS12", "-storepass", "changeit", "-keypass", "changeit"));
        Process p = new ProcessBuilder(command).directory(dir).redirectErrorStream(true).start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        for (int n; (n = p.getInputStream().read(buf)) != -1; ) {
            out.write(buf, 0, n);
        }
        if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new AssertionError("keytool failed: " + new String(out.toByteArray(), StandardCharsets.UTF_8));
        }
    }
}
