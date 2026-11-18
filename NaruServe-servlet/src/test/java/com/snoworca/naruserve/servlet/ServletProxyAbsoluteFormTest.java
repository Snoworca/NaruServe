package com.snoworca.naruserve.servlet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.proxy.Proxy;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;

/**
 * SEC-PROXY-001 AC-8, servlet adapter half: an absolute-form request line sent over a raw socket to embedded Jetty
 * reaches the proxy mounted at /api/orders, which forwards it only to the configured 127.0.0.1 backend and never to
 * the host named in the request target; the traversal inside an absolute-form target gets 400 with no backend
 * connection. The TinyHttpd half lives in ProxyPathChecksTest of :NaruServe-proxy.
 */
// @req SEC-PROXY-001
public class ServletProxyAbsoluteFormTest {

    @Rule
    public final ContainerRule container = new ContainerRule();

    private final List<RecordingBackend> backends = new ArrayList<>();

    @After
    public void closeBackends() throws IOException {
        for (RecordingBackend b : backends) {
            b.close();
        }
    }

    /** A Jetty-hosted app with the proxy to {@code backend} at /api/orders; {@code dispatched} counts middleware runs. */
    private void servletProxy(RecordingBackend backend, AtomicInteger dispatched) throws Exception {
        SharedApp.onJetty(container, "/*", app -> {
            app.use((req, res, next) -> {
                dispatched.incrementAndGet();
                next.run();
            });
            app.use("/api/orders", Proxy.to(backend.url()));
        });
    }

    private SharedApp.Reply send(String requestLineAndHost) throws IOException {
        byte[] bytes = (requestLineAndHost + "Connection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1);
        return SharedApp.raw(container.port(), bytes);
    }

    // SEC-PROXY-001 AC-8: over the servlet adapter GET http://evil.example/api/orders/x is admitted to middleware and
    // forwarded only to the configured target host, with exactly one backend connection and no other host contacted
    @Test(timeout = 30000)
    public void sec_PROXY_001_AC8_absoluteFormOverServletAdapter() throws Exception {
        RecordingBackend backend = backend();
        AtomicInteger dispatched = new AtomicInteger();
        servletProxy(backend, dispatched);

        SharedApp.Reply r = send("GET http://evil.example/api/orders/x HTTP/1.1\r\nHost: evil.example\r\n");

        assertEquals(r.text(), 200, r.status);
        assertEquals(RecordingBackend.BODY, r.text());
        assertEquals("middleware runs", 1, dispatched.get());
        assertEquals("backend connections", 1, backend.connections());
        String head = backend.onlyHead();
        assertEquals("GET /api/orders/x HTTP/1.1", requestLine(head));
        assertEquals(Collections.singletonList("127.0.0.1:" + backend.port()), headerValues(head, "Host"));
    }

    // SEC-PROXY-001 AC-8: over the servlet adapter GET http://h/api/orders/%2e%2e/x gets 400 with no backend
    // connection; the adapter admits it to middleware, so the 400 is the proxy's path check
    @Test(timeout = 30000)
    public void sec_PROXY_001_AC8_absoluteFormTraversalOverServletAdapterGets400() throws Exception {
        RecordingBackend backend = backend();
        AtomicInteger dispatched = new AtomicInteger();
        servletProxy(backend, dispatched);

        SharedApp.Reply r = send("GET http://h/api/orders/%2e%2e/x HTTP/1.1\r\nHost: h\r\n");

        assertEquals(r.text(), 400, r.status);
        assertEquals("middleware runs", 1, dispatched.get());
        assertEquals("backend connections", 0, backend.connectionsSettled());
    }

    private RecordingBackend backend() throws IOException {
        RecordingBackend b = new RecordingBackend();
        backends.add(b);
        return b;
    }

    private static String requestLine(String head) {
        int end = head.indexOf("\r\n");
        return end < 0 ? head : head.substring(0, end);
    }

    private static List<String> headerValues(String head, String name) {
        List<String> values = new ArrayList<>();
        String[] lines = head.split("\r\n", -1);
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0 && lines[i].substring(0, colon).equalsIgnoreCase(name)) {
                values.add(lines[i].substring(colon + 1).trim());
            }
        }
        return values;
    }

    /**
     * A JDK-only backend on 127.0.0.1: it records the head of every request it accepts and answers 200 with
     * {@link #BODY}. It is the only listening target the proxy can reach, so its connection count is the number of
     * connections the proxy opened.
     */
    static final class RecordingBackend implements AutoCloseable {
        static final String BODY = "from the configured backend";

        private final ServerSocket server;
        private final Thread acceptor;
        private final AtomicInteger connections = new AtomicInteger();
        private final List<String> heads = Collections.synchronizedList(new ArrayList<String>());

        RecordingBackend() throws IOException {
            server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
            acceptor = new Thread(this::acceptLoop, "servlet-proxy-backend");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        String url() {
            return "http://127.0.0.1:" + port();
        }

        int port() {
            return server.getLocalPort();
        }

        int connections() {
            return connections.get();
        }

        /** The connection count after a short wait, so a late connection the proxy might open is still seen. */
        int connectionsSettled() throws InterruptedException {
            Thread.sleep(200);
            return connections.get();
        }

        String onlyHead() {
            synchronized (heads) {
                assertEquals("backend requests " + heads, 1, heads.size());
                return heads.get(0);
            }
        }

        private void acceptLoop() {
            while (!server.isClosed()) {
                try (Socket s = server.accept()) {
                    connections.incrementAndGet();
                    s.setSoTimeout(10000);
                    heads.add(readHead(s.getInputStream()));
                    byte[] body = BODY.getBytes(StandardCharsets.ISO_8859_1);
                    OutputStream out = s.getOutputStream();
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: " + body.length
                            + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                    out.write(body);
                    out.flush();
                } catch (SocketException e) {
                    if (server.isClosed()) {
                        return;
                    }
                } catch (IOException e) {
                    // a broken test connection; keep accepting
                }
            }
        }

        private static String readHead(InputStream in) throws IOException {
            ByteArrayOutputStream acc = new ByteArrayOutputStream();
            int matched = 0;
            byte[] end = {'\r', '\n', '\r', '\n'};
            int b;
            while ((b = in.read()) >= 0) {
                acc.write(b);
                matched = b == end[matched] ? matched + 1 : (b == '\r' ? 1 : 0);
                if (matched == 4) {
                    String head = new String(acc.toByteArray(), StandardCharsets.ISO_8859_1);
                    return head.substring(0, head.length() - 4);
                }
            }
            throw new IOException("backend connection closed before the request head ended");
        }

        @Override
        public void close() throws IOException {
            server.close();
            try {
                acceptor.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            assertTrue("backend acceptor stopped", !acceptor.isAlive());
        }
    }
}
