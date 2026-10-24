package com.snoworca.naruserve.tinyhttpd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.spi.ExchangeHandler;
import com.snoworca.naruserve.spi.HttpExchange;
import com.snoworca.naruserve.spi.ServerSettings;
import com.snoworca.naruserve.testing.LiveThreads;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.testing.StubJsonCodec;
import com.snoworca.naruserve.testing.TestSupport;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Test;

/** Raw request target over a real socket. SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-5-5.sds.md. */
public class RawTargetSocketTest {

    private final TinyHttpdAdapter adapter = new TinyHttpdAdapter();

    @After
    public void stop() {
        Http.stopQuietly(adapter);
    }

    // SDS-AC-4; FR-ENGINE-011 AC-6
    @Test(timeout = 20000)
    public void sdsAc4_FR_ENGINE_011_AC6_rawPathAndRawQueryReachTheCoreUndecoded() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        adapter.bind(Http.settings().build(), Http.ok(ex -> seen.set(ex.rawPath() + "|" + ex.rawQuery())));
        int port = adapter.boundPort();

        assertEquals(200, Http.status(Http.send(port, Http.get("/a%20b?x=%41"))));
        assertEquals("/a%20b|x=%41", seen.get());

        assertEquals(200, Http.status(Http.send(port, Http.get("/a//b"))));
        assertEquals("/a//b|null", seen.get());

        assertEquals(200, Http.status(Http.send(port, Http.get("/q?"))));
        assertEquals("/q|", seen.get());
    }

    // FR-ENGINE-011 AC-7; IR-HTTP-003 AC-1 (issue #45: interleaved duplicates keep their received order)
    @Test(timeout = 20000)
    public void FR_ENGINE_011_AC7_requestLineHeadersAndRemoteAddressReachTheCoreUnchanged() throws Exception {
        try {
            adapter.boundPort();
            fail("boundPort before bind must throw");
        } catch (IllegalStateException expected) {
            // the SPI contract
        }
        AtomicReference<String> seen = new AtomicReference<>();
        AtomicReference<List<Map.Entry<String, String>>> headers = new AtomicReference<>();
        adapter.bind(Http.settings().build(), Http.ok(ex -> {
            seen.set(ex.methodToken() + " " + ex.httpVersion() + " " + ex.remoteAddress() + " " + ex.isSecure());
            headers.set(ex.headers());
        }));
        int port = adapter.boundPort();
        assertTrue(port > 0);

        // two names interleaved: the SPI promises received order with duplicates kept (IR-HTTP-003 AC-1)
        String raw = "PATCH /x HTTP/1.1\r\nHost: 127.0.0.1\r\nX-One: a\r\nX-Two: 1\r\nx-one: b\r\nX-Two: 2\r\n"
                + "Connection: close\r\n\r\n";
        assertEquals(200, Http.status(Http.send(port, raw)));
        assertEquals("PATCH HTTP/1.1 127.0.0.1 false", seen.get());
        List<String> received = new ArrayList<>();
        for (Map.Entry<String, String> h : headers.get()) {
            received.add(h.getKey() + "=" + h.getValue());
        }
        assertEquals(java.util.Arrays.asList("Host=127.0.0.1", "X-One=a", "X-Two=1", "x-one=b", "X-Two=2",
                "Connection=close"), received);
    }

    // FR-ENGINE-011 AC-2 (exchange bridge)
    @Test(timeout = 20000)
    public void FR_ENGINE_011_AC2_exchangeOutlivesHandleAndGuardsStartAndAbort() throws Exception {
        AtomicReference<String> guards = new AtomicReference<>("");
        adapter.bind(Http.settings().build(), ex -> new Thread(() -> {
            StringBuilder sb = new StringBuilder();
            try {
                List<Map.Entry<String, String>> h = Collections.<Map.Entry<String, String>>singletonList(
                        new SimpleEntry<>("Content-Length", "5"));
                OutputStream out = ex.commit(200, h, 5);
                try {
                    ex.commit(200, h, 5);
                } catch (IllegalStateException e) {
                    sb.append("second-commit ");
                }
                out.write("later".getBytes(StandardCharsets.UTF_8));
                ex.finish();
            } catch (IOException e) {
                sb.append(e);
                ex.abort();
            }
            guards.set(sb.toString());
        }).start());
        String response = Http.send(adapter.boundPort(), Http.get("/async"));
        assertEquals(200, Http.status(response));
        assertEquals("later", Http.body(response));
        assertEquals("second-commit ", guards.get());

        TinyHttpdAdapter other = new TinyHttpdAdapter();
        AtomicReference<Throwable> afterAbort = new AtomicReference<>();
        java.util.concurrent.CountDownLatch abortHandled = new java.util.concurrent.CountDownLatch(1);
        try {
            other.bind(Http.settings().build(), ex -> {
                try {
                    OutputStream out = ex.commit(200, Collections.<Map.Entry<String, String>>emptyList(), -1);
                    new Thread(ex::abort).start();
                    long deadline = System.currentTimeMillis() + 5000;
                    while (System.currentTimeMillis() < deadline) {
                        out.write('x');
                        Thread.sleep(10);
                    }
                    afterAbort.set(new AssertionError("write never failed"));
                } catch (Throwable e) {
                    afterAbort.set(e);
                } finally {
                    abortHandled.countDown();
                }
            });
            Http.send(other.boundPort(), Http.get("/abort"));
            assertTrue("handler must observe cancellation", abortHandled.await(3, TimeUnit.SECONDS));
            assertTrue(String.valueOf(afterAbort.get()), afterAbort.get() instanceof IOException);
        } finally {
            Http.stopQuietly(other);
        }
    }

    /** Socket and settings helpers shared by the socket tests of this module. */
    static final class Http {

        private Http() {
        }

        /** Settings with the documented defaults, port 0, a recording logger and a fixed renderer. */
        static ServerSettings.Builder settings() {
            return TestSupport.settings();
        }

        /** An app over {@code adapter}, logging to {@code log}. */
        static RestApp app(TinyHttpdAdapter adapter, RecordingLogger log) {
            return RestApp.builder().httpAdapter(adapter).jsonCodec(new StubJsonCodec()).logger(log).build();
        }

        interface Probe {
            void see(HttpExchange exchange) throws Exception;
        }

        /** A handler that runs {@code probe} and answers 200 with body {@code ok} (or the probe failure). */
        static ExchangeHandler ok(Probe probe) {
            return ex -> {
                String body = "ok";
                try {
                    probe.see(ex);
                } catch (Exception e) {
                    body = String.valueOf(e);
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                try {
                    List<Map.Entry<String, String>> h = Collections.<Map.Entry<String, String>>singletonList(
                            new SimpleEntry<>("Content-Length", Integer.toString(bytes.length)));
                    OutputStream out = ex.commit(200, h, bytes.length);
                    out.write(bytes);
                    ex.finish();
                } catch (IOException e) {
                    ex.abort();
                }
            };
        }

        static String get(String target, String... headerLines) {
            StringBuilder sb = new StringBuilder("GET ").append(target).append(" HTTP/1.1\r\nHost: 127.0.0.1\r\n");
            for (String h : headerLines) {
                sb.append(h).append("\r\n");
            }
            return sb.append("Connection: close\r\n\r\n").toString();
        }

        static String send(int port, String raw) throws IOException {
            return send("127.0.0.1", port, raw);
        }

        /** Writes {@code raw} as ISO-8859-1 and reads until the server closes (10 s read timeout). */
        static String send(String host, int port, String raw) throws IOException {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(host, port), 5000);
                s.setSoTimeout(10000);
                OutputStream out = s.getOutputStream();
                try {
                    out.write(raw.getBytes(StandardCharsets.ISO_8859_1));
                    out.flush();
                } catch (SocketException e) {
                    // The server may answer and close before reading everything; read what it sent.
                }
                return readAll(s.getInputStream());
            }
        }

        static String readAll(InputStream in) throws IOException {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            try {
                for (int n; (n = in.read(b)) >= 0; ) {
                    buf.write(b, 0, n);
                }
            } catch (SocketException e) {
                if (buf.size() == 0) {
                    throw e;
                }
            }
            return new String(buf.toByteArray(), StandardCharsets.ISO_8859_1);
        }

        /** The status code of the first response in {@code response}. */
        static int status(String response) {
            if (!response.startsWith("HTTP/1.1 ")) {
                throw new AssertionError("not an HTTP response: " + response);
            }
            return Integer.parseInt(response.substring(9, 12));
        }

        /** Every {@code name} header value of a raw response, trimmed, in order. */
        static List<String> headers(String response, String name) {
            List<String> out = new ArrayList<>();
            for (String line : response.substring(0, Math.max(0, response.indexOf("\r\n\r\n"))).split("\r\n")) {
                int c = line.indexOf(':');
                if (c > 0 && line.substring(0, c).equalsIgnoreCase(name)) {
                    out.add(line.substring(c + 1).trim());
                }
            }
            return out;
        }

        /** The first {@code name} header value of a raw response, trimmed; null when absent. */
        static String header(String response, String name) {
            List<String> values = headers(response, name);
            return values.isEmpty() ? null : values.get(0);
        }

        /**
         * Live threads named {@code name}; a name ending in '-' is a prefix. Exact full names keep port 80 from
         * matching port 8080.
         */
        static List<Thread> threads(String name) {
            if (name.endsWith("-")) {
                return LiveThreads.withPrefix(name);
            }
            List<Thread> out = new ArrayList<>();
            for (Thread t : Thread.getAllStackTraces().keySet()) {
                if (t.isAlive() && t.getName().equals(name)) {
                    out.add(t);
                }
            }
            return out;
        }

        /** Sender threads of {@code port} now inside a socket write, which a client that stopped reading keeps blocked. */
        static Set<Thread> blockedThreads(int port) {
            Set<Thread> out = new HashSet<>();
            for (Thread t : threads("naruserve-" + port + "-ws-sender-")) {
                if (inSocketWrite(t)) out.add(t);
            }
            return out;
        }

        /** A native socket write (or its native poll), on both Java 8 and later JDKs. */
        static boolean inSocketWrite(Thread thread) {
            boolean nativeCall = false;
            boolean socketWrite = false;
            for (StackTraceElement frame : thread.getStackTrace()) {
                nativeCall |= frame.isNativeMethod();
                String name = frame.getClassName();
                socketWrite |= (name.startsWith("sun.nio.ch.") || name.startsWith("java.net."))
                        && frame.getMethodName().toLowerCase(Locale.ROOT).contains("write");
            }
            return nativeCall && socketWrite;
        }

        /** Names of the live threads of the app on {@code port}: naruserve-*, tinyhttpd-acceptor-port, tinyhttpd-watchdog. */
        static List<String> appThreads(int port) {
            List<String> out = new ArrayList<>();
            for (Thread t : Thread.getAllStackTraces().keySet()) {
                String n = t.getName();
                if (t.isAlive() && (n.startsWith("naruserve-") || n.equals("tinyhttpd-acceptor-" + port)
                        || n.equals("tinyhttpd-watchdog"))) {
                    out.add(n);
                }
            }
            return out;
        }

        /** The live threads that are not in {@code before}. */
        static List<Thread> startedSince(Set<Thread> before) {
            List<Thread> out = new ArrayList<>();
            for (Thread t : Thread.getAllStackTraces().keySet()) {
                if (t.isAlive() && !before.contains(t)) {
                    out.add(t);
                }
            }
            return out;
        }

        /** The result of the no-argument method {@code method} of {@code target}, which may be package-private. */
        static Object invoke(Object target, String method) throws Exception {
            Method m = target.getClass().getDeclaredMethod(method);
            m.setAccessible(true);
            return m.invoke(target);
        }

        /** A string of {@code n} times {@code c}. */
        static String repeat(char c, int n) {
            char[] a = new char[n];
            Arrays.fill(a, c);
            return new String(a);
        }

        /** Closes every entry, ignoring the IOException of one the server already closed. */
        static void closeAll(List<? extends Closeable> open) {
            for (Closeable c : open) {
                try {
                    c.close();
                } catch (IOException ignored) {
                    // the server may have closed it first
                }
            }
        }

        static String body(String response) {
            int i = response.indexOf("\r\n\r\n");
            return i < 0 ? "" : response.substring(i + 4);
        }

        static void stopQuietly(TinyHttpdAdapter adapter) {
            try {
                adapter.stop(1, TimeUnit.SECONDS);
                adapter.release();
            } catch (RuntimeException ignored) {
                // never bound
            }
        }
    }
}
