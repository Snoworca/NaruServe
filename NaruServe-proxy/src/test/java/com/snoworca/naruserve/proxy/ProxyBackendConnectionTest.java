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
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Test;

/**
 * The proxy's backend HTTP/1.1 client (FR-PROXY-020): its own client over {@link java.net.Socket}, a new connection
 * for every attempt with {@code Connection: close}, no reuse, and no wait on a backend that keeps the connection open.
 */
public class ProxyBackendConnectionTest {

    private static final long WAIT_SECONDS = 20;
    private static final String OK_KEEP_ALIVE =
            "HTTP/1.1 200 OK\r\nConnection: keep-alive\r\nContent-Length: 2\r\n\r\nok";

    private final InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
    private final RestApp app = RestApp.builder().httpAdapter(adapter).jsonCodec(new StubJsonCodec())
            .logger(new RecordingLogger()).build();
    private final List<Throwable> errors = Collections.synchronizedList(new ArrayList<Throwable>());
    private final List<Closeable> closeables = new ArrayList<>();
    private boolean started;

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

    // FR-PROXY-020 AC-1: two sequential requests through one mount reach the backend over two separate TCP connections
    @Test(timeout = 30000)
    public void fr_PROXY_020_AC1_twoSequentialRequestsUseTwoConnections() throws Exception {
        ConnectionBackend backend = backend("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok", false);
        app.use("/p", Proxy.to(backend.url()));
        start();

        assertEquals(200, send(ClientRequest.of("GET", "/p/one")).status());
        assertEquals(200, send(ClientRequest.of("GET", "/p/two")).status());

        assertEquals(2, backend.connections.size());
        assertEquals("one request per connection", Collections.nCopies(2, 1), backend.requestsPerConnection());
        assertTrue("two distinct client ports: " + backend.remotePorts(),
                !backend.remotePorts().get(0).equals(backend.remotePorts().get(1)));
    }

    // FR-PROXY-020 AC-1: a request retried twice under .retries(2) reaches the backend over three separate connections
    @Test(timeout = 30000)
    public void fr_PROXY_020_AC1_aRequestRetriedTwiceUsesThreeConnections() throws Exception {
        ConnectionBackend backend = backend("HTTP/1.1 503 X\r\nContent-Length: 4\r\n\r\nbusy", false);
        app.use("/p", Proxy.to(backend.url()).retries(2).retryDelay(20, TimeUnit.MILLISECONDS));
        start();

        assertEquals(503, send(ClientRequest.of("GET", "/p/x")).status());

        assertEquals(3, backend.connections.size());
        assertEquals("one request per connection", Collections.nCopies(3, 1), backend.requestsPerConnection());
    }

    // ---- AC-2 ----

    // FR-PROXY-020 AC-2: every backend request carries exactly one Connection: close line, and when the backend answers
    // with Connection: keep-alive and a complete Content-Length body the proxy still closes that connection after the
    // response and sends the next request over a new connection
    @Test(timeout = 30000)
    public void fr_PROXY_020_AC2_connectionCloseIsSentAndAKeepAliveAnswerIsNotReused() throws Exception {
        ConnectionBackend backend = backend(OK_KEEP_ALIVE, true);
        app.use("/p", Proxy.to(backend.url()));
        start();

        ClientResponse first = send(ClientRequest.of("GET", "/p/one").header("Connection", "keep-alive"));
        assertEquals("ok", first.text());
        BackendConnection c0 = backend.connection(0);
        assertTrue("the proxy closed the kept-alive connection", c0.closedByProxy.await(5, TimeUnit.SECONDS));

        ClientResponse second = send(ClientRequest.of("POST", "/p/two").header("Content-Length", "3")
                .body("abc".getBytes(StandardCharsets.ISO_8859_1)));
        assertEquals("ok", second.text());
        assertTrue(backend.connection(1).closedByProxy.await(5, TimeUnit.SECONDS));

        assertEquals(2, backend.connections.size());
        assertEquals(Collections.nCopies(2, 1), backend.requestsPerConnection());
        for (BackendConnection c : backend.connectionsSnapshot()) {
            assertEquals(c.heads.toString(), Collections.singletonList("Connection: close"),
                    linesNamed(c.heads.get(0), "Connection"));
        }
    }

    // ---- AC-3 ----

    // FR-PROXY-020 AC-3: the main class files of NaruServe-proxy reference neither java/net/HttpURLConnection nor any
    // class under java/net/http/, and they do reference java/net/Socket
    @Test
    public void fr_PROXY_020_AC3_noMainClassReferencesHttpURLConnectionOrJavaNetHttp()
            throws IOException, URISyntaxException {
        File classes = new File(Proxy.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        assertTrue(classes + " is the main classes directory", classes.isDirectory());
        List<File> files = new ArrayList<>();
        collect(classes, files);
        assertTrue(files.toString(), files.size() > 5);
        boolean socket = false;
        for (File f : files) {
            for (String utf8 : utf8Constants(f)) {
                assertFalse(f + " references " + utf8, utf8.contains("java/net/HttpURLConnection"));
                assertFalse(f + " references " + utf8, utf8.contains("java/net/http/"));
                socket |= utf8.equals("java/net/Socket") || utf8.contains("Ljava/net/Socket;");
            }
        }
        assertTrue("some main class references java/net/Socket", socket);
    }

    // FR-PROXY-020 AC-3: a backend connection is made through a java.net.Socket from the proxy's plain socket factory
    @Test(timeout = 30000)
    public void fr_PROXY_020_AC3_backendConnectionsAreMadeThroughJavaNetSocket() throws Exception {
        ConnectionBackend backend = backend("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok", false);
        final AtomicInteger created = new AtomicInteger();
        BackendClient.plainSockets = () -> {
            created.incrementAndGet();
            return new Socket();
        };
        app.use("/p", Proxy.to(backend.url()));
        start();

        assertEquals("ok", send(ClientRequest.of("GET", "/p/x")).text());

        assertEquals(1, created.get());
        assertEquals(1, backend.connections.size());
    }

    // ---- AC-4 ----

    // FR-PROXY-020 AC-4: when the backend sends a complete Content-Length response and then keeps the connection open,
    // the client receives the complete response without waiting for the backend to close, and on a .maxConcurrent(1)
    // mount the next request reaches the backend
    @Test(timeout = 30000)
    public void fr_PROXY_020_AC4_aBackendThatKeepsTheConnectionOpenDelaysNeitherTheClientNorTheSlot()
            throws Exception {
        ConnectionBackend backend = backend(OK_KEEP_ALIVE, true);
        app.use("/p", Proxy.to(backend.url()).maxConcurrent(1));
        start();

        long begin = System.nanoTime();
        Future<ClientResponse> first = adapter.submit(ClientRequest.of("GET", "/p/one"));
        ClientResponse r = first.get(3, TimeUnit.SECONDS);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);
        assertEquals(200, r.status());
        assertEquals("ok", r.text());
        assertTrue("answered after " + elapsedMillis + " ms", elapsedMillis < 3000);
        assertFalse("the backend never closes on its own", backend.connection(0).closedByBackend);

        ClientResponse second = adapter.submit(ClientRequest.of("GET", "/p/two")).get(3, TimeUnit.SECONDS);
        assertEquals(200, second.status());
        assertEquals("ok", second.text());
        assertEquals(2, backend.connections.size());
        assertEquals("errors that reached onError: " + errors, 0, errors.size());
    }

    // ---- helpers ----

    private ConnectionBackend backend(String response, boolean keepOpen) throws IOException {
        ConnectionBackend b = new ConnectionBackend(response, keepOpen);
        closeables.add(b);
        return b;
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

    /** The header lines of {@code head} named {@code name} (case-insensitive), as received. */
    private static List<String> linesNamed(String head, String name) {
        List<String> lines = new ArrayList<>();
        String[] parts = head.split("\r\n", -1);
        for (int i = 1; i < parts.length; i++) {
            int colon = parts[i].indexOf(':');
            if (colon > 0 && parts[i].substring(0, colon).equalsIgnoreCase(name)) {
                lines.add(parts[i]);
            }
        }
        return lines;
    }

    private static void collect(File dir, List<File> into) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File c : children) {
            if (c.isDirectory()) {
                collect(c, into);
            } else if (c.getName().endsWith(".class")) {
                into.add(c);
            }
        }
    }

    /** Every CONSTANT_Utf8 entry of a class file's constant pool: class names, descriptors and strings. */
    private static List<String> utf8Constants(File classFile) throws IOException {
        List<String> values = new ArrayList<>();
        try (DataInputStream in = new DataInputStream(new FileInputStream(classFile))) {
            in.readInt();
            in.readUnsignedShort();
            in.readUnsignedShort();
            int count = in.readUnsignedShort();
            for (int i = 1; i < count; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1:
                        values.add(in.readUTF());
                        break;
                    case 3:
                    case 4:
                    case 9:
                    case 10:
                    case 11:
                    case 12:
                    case 17:
                    case 18:
                        in.readInt();
                        break;
                    case 5:
                    case 6:
                        in.readLong();
                        i++;
                        break;
                    case 7:
                    case 8:
                    case 16:
                    case 19:
                    case 20:
                        in.readUnsignedShort();
                        break;
                    case 15:
                        in.readUnsignedByte();
                        in.readUnsignedShort();
                        break;
                    default:
                        throw new IOException(classFile + ": unknown constant pool tag " + tag);
                }
            }
        }
        return values;
    }

    /** One accepted backend connection: the request heads read on it and how it ended. */
    static final class BackendConnection {
        final int remotePort;
        final List<String> heads = Collections.synchronizedList(new ArrayList<String>());
        /** Counted down when the proxy closes its side: the backend reads end of stream. */
        final CountDownLatch closedByProxy = new CountDownLatch(1);
        volatile boolean closedByBackend;

        BackendConnection(int remotePort) {
            this.remotePort = remotePort;
        }
    }

    /**
     * A loopback backend that serves every connection on its own thread: it reads request heads (and a
     * {@code Content-Length} body), answers each with {@code response}, and then either closes the connection or,
     * with {@code keepOpen}, keeps it open and keeps reading, recording a further request or the proxy's close.
     */
    static final class ConnectionBackend implements Closeable {
        private final ServerSocket server;
        private final byte[] response;
        private final boolean keepOpen;
        final List<BackendConnection> connections = Collections.synchronizedList(new ArrayList<BackendConnection>());
        private final List<Socket> sockets = Collections.synchronizedList(new ArrayList<Socket>());

        ConnectionBackend(String response, boolean keepOpen) throws IOException {
            this.response = response.getBytes(StandardCharsets.ISO_8859_1);
            this.keepOpen = keepOpen;
            server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
            Thread acceptor = new Thread(this::acceptLoop, "connection-backend-" + server.getLocalPort());
            acceptor.setDaemon(true);
            acceptor.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getLocalPort();
        }

        BackendConnection connection(int index) throws InterruptedException {
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (connections.size() <= index && System.nanoTime() < until) {
                Thread.sleep(10);
            }
            return connections.get(index);
        }

        List<BackendConnection> connectionsSnapshot() {
            synchronized (connections) {
                return new ArrayList<>(connections);
            }
        }

        List<Integer> requestsPerConnection() {
            List<Integer> counts = new ArrayList<>();
            for (BackendConnection c : connectionsSnapshot()) {
                counts.add(c.heads.size());
            }
            return counts;
        }

        List<Integer> remotePorts() {
            List<Integer> ports = new ArrayList<>();
            for (BackendConnection c : connectionsSnapshot()) {
                ports.add(c.remotePort);
            }
            return ports;
        }

        private void acceptLoop() {
            while (!server.isClosed()) {
                try {
                    final Socket s = server.accept();
                    sockets.add(s);
                    final BackendConnection c = new BackendConnection(s.getPort());
                    connections.add(c);
                    Thread t = new Thread(() -> serve(s, c), "connection-backend-serve");
                    t.setDaemon(true);
                    t.start();
                } catch (IOException e) {
                    // the server socket was closed
                }
            }
        }

        private void serve(Socket s, BackendConnection c) {
            try {
                s.setSoTimeout(15_000);
                InputStream in = s.getInputStream();
                OutputStream out = s.getOutputStream();
                while (true) {
                    String head = readHead(in);
                    if (head == null) {
                        c.closedByProxy.countDown();
                        return;
                    }
                    c.heads.add(head);
                    skipBody(in, head);
                    out.write(response);
                    out.flush();
                    if (!keepOpen) {
                        c.closedByBackend = true;
                        s.close();
                        return;
                    }
                }
            } catch (IOException e) {
                // the connection ended
            }
        }

        /** The head up to the blank line, or null at end of stream before any byte of it. */
        private static String readHead(InputStream in) throws IOException {
            StringBuilder head = new StringBuilder();
            while (head.length() < 4 || !head.substring(head.length() - 4).equals("\r\n\r\n")) {
                int b = in.read();
                if (b < 0) {
                    if (head.length() == 0) {
                        return null;
                    }
                    throw new IOException("end of stream inside a request head");
                }
                head.append((char) b);
            }
            return head.substring(0, head.length() - 4);
        }

        private static void skipBody(InputStream in, String head) throws IOException {
            List<String> lengths = linesNamed(head, "Content-Length");
            if (lengths.isEmpty()) {
                return;
            }
            String line = lengths.get(0);
            long n = Long.parseLong(line.substring(line.indexOf(':') + 1).trim());
            for (long i = 0; i < n; i++) {
                if (in.read() < 0) {
                    throw new IOException("end of stream inside a request body");
                }
            }
        }

        @Override
        public void close() throws IOException {
            server.close();
            synchronized (sockets) {
                for (Socket s : sockets) {
                    s.close();
                }
            }
        }
    }
}
