package com.snoworca.naruserve.proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.HttpException;
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
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Test;

/**
 * The end of a proxied request seen from the sockets (REL-PROXY-001): a client that disconnects while a response body
 * is relayed over a real TinyHttpd socket makes the proxy close its backend connection and return its slot (the
 * in-memory adapter never reports an abort, design D-069), and the paths that end inside the middleware (502, 413
 * while forwarding, a read timeout the app answers itself) never leak a slot of a limited mount.
 */
public class ProxyLifetimeSocketTest {

    /** How long a test waits for something that must happen. */
    private static final long WAIT_SECONDS = 20;

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

    // ---- AC-1 ----

    // REL-PROXY-001 AC-1: over real TinyHttpd sockets, when the client disconnects while a response body is being
    // relayed, the backend observes its connection closed, and on a .maxConcurrent(1) mount the next request reaches
    // the backend
    @Test(timeout = 90000)
    public void rel_PROXY_001_AC1_aClientDisconnectDuringTheRelayClosesTheBackendAndReturnsTheSlot()
            throws Exception {
        LifetimeBackend backend = backend(0);
        backend.mode = LifetimeBackend.Mode.DRIP;
        Proxy p = Proxy.to(backend.url()).maxConcurrent(1);
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
            byte[] some = new byte[1024];
            readFully(client.getInputStream(), some);
            assertEquals("the relay is in flight", 1, p.concurrencyCounter().get());
            // A reset rather than a FIN, so the next write of the relay fails at once.
            client.setSoLinger(true, 0);
        }

        assertTrue("the backend observed its connection closed",
                backend.closedByProxy.await(WAIT_SECONDS, TimeUnit.SECONDS));
        awaitCounterZero(p);
        backend.mode = LifetimeBackend.Mode.ANSWER_OK;

        String next = exchange(port, "GET /api/next HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n");

        assertTrue(next, next.startsWith("HTTP/1.1 200 "));
        assertTrue(next, next.endsWith("ok"));
        assertEquals("backend connections", 2, backend.connections.get());
    }

    // ---- AC-2 ----

    // REL-PROXY-001 AC-2: on a .maxConcurrent(2) mount, after 20 requests to a stopped backend answered 502, 20 chunked
    // requests over .bodyLimit(1024) answered 413 and 20 read timeouts the app's onError answers with 200, a request
    // sent after the backend is restored reaches the backend
    @Test(timeout = 120000)
    public void rel_PROXY_001_AC2_failedRequestsNeverLeakASlot() throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            port = probe.getLocalPort();
        }
        Proxy p = Proxy.to("http://127.0.0.1:" + port).bodyLimit(1024).readTimeout(100, TimeUnit.MILLISECONDS)
                .maxConcurrent(2);
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        TestApp t = TestApp.create(adapter);
        RestApp app = t.app();
        app.use("/api", p);
        app.onError((error, req, res) -> {
            if (isReadTimeout(error)) {
                res.status(200).send("timed out");
            } else {
                res.sendStatus(error instanceof HttpException ? ((HttpException) error).status() : 500);
            }
        });
        t.start();
        started.add(app);

        for (int i = 0; i < 20; i++) {
            ClientResponse r = adapter.send(ClientRequest.of("GET", "/api/down/" + i));
            assertEquals("request " + i + " to the stopped backend", 502, r.status());
        }
        assertEquals("counter after the stopped backend", 0, p.concurrencyCounter().get());

        LifetimeBackend backend = backend(port);
        backend.mode = LifetimeBackend.Mode.DRAIN;
        byte[] over = new byte[4096];
        for (int i = 0; i < 20; i++) {
            ClientResponse r = adapter.send(ClientRequest.of("POST", "/api/big/" + i)
                    .header("Transfer-Encoding", "chunked").body(over));
            assertEquals("chunked request " + i + " over the body limit", 413, r.status());
        }
        awaitConnections("connections of the over-limit requests", backend.connections, 20);

        backend.mode = LifetimeBackend.Mode.SILENT;
        for (int i = 0; i < 20; i++) {
            ClientResponse r = adapter.send(ClientRequest.of("GET", "/api/slow/" + i));
            assertEquals("read timeout " + i + " answered by onError", 200, r.status());
            assertEquals("timed out", r.text());
        }
        awaitConnections("connections after the read timeouts", backend.connections, 40);

        backend.mode = LifetimeBackend.Mode.ANSWER_OK;
        ClientResponse restored = adapter.send(ClientRequest.of("GET", "/api/restored"));

        assertEquals("the request after the backend is restored", 200, restored.status());
        assertEquals("ok", restored.text());
        assertEquals("the restored request reached the backend", 41, backend.connections.get());
        awaitCounterZero(p);
    }

    /**
     * A read timeout as it reaches the error flow: the {@code ProxyException} of kind {@code READ_TIMEOUT}, or the 502
     * whose cause is the socket's {@link SocketTimeoutException} until that classification exists.
     */
    private static boolean isReadTimeout(Throwable error) {
        if (error instanceof ProxyException) {
            return ((ProxyException) error).kind() == ProxyException.Kind.READ_TIMEOUT;
        }
        return error instanceof HttpException && error.getCause() instanceof SocketTimeoutException;
    }

    // ---- fixtures ----

    private LifetimeBackend backend(int port) throws IOException {
        LifetimeBackend b = new LifetimeBackend(port);
        closeables.add(b);
        return b;
    }

    /**
     * Waits until the counter of {@code p} is back at zero: the core reports a relayed response done a moment before
     * it closes the relayed stream, which is where the slot is returned.
     */
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

    /**
     * Waits until {@code connections} reaches {@code expected}: a proxy connect returns once the kernel accepted it, a
     * moment before the backend's accept loop counts it.
     */
    private static void awaitConnections(String what, AtomicInteger connections, int expected)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (connections.get() < expected && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(what, expected, connections.get());
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
            int n;
            while ((n = s.getInputStream().read(buf)) >= 0) {
                got.write(buf, 0, n);
            }
            return new String(got.toByteArray(), StandardCharsets.ISO_8859_1);
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
        int off = 0;
        while (off < into.length) {
            int n = in.read(into, off, into.length - off);
            if (n < 0) {
                throw new EOFException("the body ended after " + off + " bytes");
            }
            off += n;
        }
    }

    /**
     * A JDK-only backend over a raw {@link ServerSocket} on 127.0.0.1 (D-070), bound to a given port or a free one,
     * that serves each connection on its own thread in the behaviour {@link #mode} names when the connection arrives.
     */
    private static final class LifetimeBackend implements Closeable {
        enum Mode {
            /** Sends a head with a large Content-Length, then body bytes in small pieces until the write fails. */
            DRIP,
            /** Reads and discards everything until the proxy closes the connection. */
            DRAIN,
            /** Reads the request head and never answers, until the proxy closes the connection. */
            SILENT,
            /** Reads the request head and answers 200 {@code ok}. */
            ANSWER_OK
        }

        private final ServerSocket server;
        private final Thread acceptor;
        private final List<Socket> sockets = Collections.synchronizedList(new ArrayList<Socket>());
        final AtomicInteger connections = new AtomicInteger();
        final CountDownLatch closedByProxy = new CountDownLatch(1);
        volatile Mode mode = Mode.ANSWER_OK;

        LifetimeBackend(int port) throws IOException {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 50);
            acceptor = new Thread(this::acceptLoop, "lifetime-backend-" + server.getLocalPort());
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
                connections.incrementAndGet();
                sockets.add(s);
                Mode m = mode;
                Thread t = new Thread(() -> serve(s, m), "lifetime-backend-conn");
                t.setDaemon(true);
                t.start();
            }
        }

        private void serve(Socket s, Mode m) {
            try {
                s.setSoTimeout(30_000);
                InputStream in = s.getInputStream();
                OutputStream out = s.getOutputStream();
                switch (m) {
                    case DRIP:
                        readHead(in);
                        drip(out);
                        return;
                    case DRAIN:
                    case SILENT:
                        drain(in);
                        return;
                    default:
                        readHead(in);
                        out.write("HTTP/1.1 200 X\r\nContent-Length: 2\r\n\r\nok".getBytes(StandardCharsets.ISO_8859_1));
                        out.flush();
                }
            } catch (IOException e) {
                // the connection is over either way
            } finally {
                try {
                    s.close();
                } catch (IOException ignored) {
                    // closed either way
                }
            }
        }

        /** Sends a 100 MB response in 1 KiB pieces every few milliseconds until the proxy closes the connection. */
        private void drip(OutputStream out) {
            byte[] piece = new byte[1024];
            try {
                out.write("HTTP/1.1 200 X\r\nContent-Length: 104857600\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
                while (System.nanoTime() < deadline) {
                    out.write(piece);
                    out.flush();
                    Thread.sleep(5);
                }
            } catch (IOException e) {
                closedByProxy.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private void drain(InputStream in) {
            byte[] buf = new byte[8192];
            try {
                while (in.read(buf) >= 0) {
                    // discard
                }
            } catch (IOException e) {
                // a reset is a close as well
            }
            closedByProxy.countDown();
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
