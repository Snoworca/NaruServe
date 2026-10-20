package com.snoworca.naruserve.tinyhttpd;

import static com.snoworca.naruserve.tinyhttpd.RawWebSocketClient.CLOSE;
import static com.snoworca.naruserve.tinyhttpd.RawWebSocketClient.TEXT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.AppState;
import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.SseClient;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.tinyhttpd.RawTargetSocketTest.Http;
import com.snoworca.naruserve.tinyhttpd.SseSocketTest.Stream;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Test;

/**
 * T44 over TinyHttpd: WebSocket sessions and SSE streams, stalled and healthy, at shutdown. SDS-AC-n cites
 * docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-12.sds.md.
 */
public class LongConnectionShutdownSocketTest {

    private static final String NOTICE = "event: shutdown\ndata: shutdown\n\n";
    /** The D-344 margin over the shutdown timeout. */
    private static final long MARGIN_MS = 3000;
    /** One stalled-session message: 512 KiB; one stalled-stream event: 64 KiB. */
    private static final String BIG_WS;
    private static final String BIG_SSE;

    static {
        char[] a = new char[512 * 1024];
        Arrays.fill(a, 's');
        BIG_WS = new String(a);
        BIG_SSE = new String(a, 0, 64 * 1024);
    }

    private final TinyHttpdAdapter adapter = new TinyHttpdAdapter();
    private final RecordingLogger log = new RecordingLogger();
    private final RestApp app = Http.app(adapter, log);
    private final List<AutoCloseableSocket> sockets = new ArrayList<>();
    private final BlockingQueue<Long> stalledClosed = new LinkedBlockingQueue<>();
    private final BlockingQueue<SseClient> opened = new LinkedBlockingQueue<>();

    private interface AutoCloseableSocket {
        void close() throws IOException;
    }

    @After
    public void stop() {
        app.shutdown(2, TimeUnit.SECONDS);
        for (AutoCloseableSocket s : sockets) {
            try {
                s.close();
            } catch (IOException e) {
                // already gone
            }
        }
        Http.stopQuietly(adapter);
    }

    /** /stall queues 16 x 512 KiB on any text; /ok answers "hello"; /events opens a stream; /x answers "ok". */
    private int listen() {
        app.webSocket(o -> o.sendQueueSize(64 * 1024 * 1024));
        app.sse(o -> o.sendQueueSize(16 * 1024 * 1024));
        app.ws("/stall", (req, ws) -> {
            ws.onClose((code, reason) -> stalledClosed.add(System.nanoTime()));
            ws.onText(text -> {
                for (int i = 0; i < 16; i++) {
                    ws.sendText(BIG_WS);
                }
            });
        });
        app.ws("/ok", (req, ws) -> ws.onText(text -> ws.sendText("hello")));
        app.get("/events", (req, res) -> opened.add(res.sse()));
        app.get("/x", (req, res) -> res.send("ok"));
        return app.listen(0).port();
    }

    private RawWebSocketClient ws(int port, String path) throws IOException {
        RawWebSocketClient c = RawWebSocketClient.open(port, path);
        sockets.add(c::close);
        assertEquals(c.handshake, 101, c.status());
        return c;
    }

    private Stream stream(int port) throws IOException {
        Stream s = new Stream(port, Http.get("/events"));
        sockets.add(s::close);
        s.readHeaders();
        return s;
    }

    private SseClient nextClient() throws InterruptedException {
        SseClient c = opened.poll(10, TimeUnit.SECONDS);
        assertNotNull("no stream opened", c);
        return c;
    }

    /** A session whose client stopped reading while 8 MiB are queued to it; its sender blocks in a write. */
    private RawWebSocketClient stalledSession(int port, int blockedBefore) throws Exception {
        RawWebSocketClient c = ws(port, "/stall");
        c.text("stall"); // and never reads again
        // A sender still filling the socket buffers leaves its write between frames; one that stays inside the write
        // for 20 samples in a row is blocked by the client that stopped reading.
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        int stable = 0;
        while (stable < 20 && System.nanoTime() < end) {
            stable = blockedWriters(port) > blockedBefore ? stable + 1 : 0;
            Thread.sleep(10);
        }
        assertEquals("sender threads blocked in a write", blockedBefore + 1, blockedWriters(port));
        return c;
    }

    /** Sender threads of {@code port} now inside a socket write. */
    private static int blockedWriters(int port) {
        int n = 0;
        for (Thread t : Http.threads("naruserve-" + port + "-ws-sender-")) {
            for (StackTraceElement e : t.getStackTrace()) {
                String c = e.getClassName();
                if ((c.startsWith("sun.nio.ch.") || c.startsWith("java.net."))
                        && e.getMethodName().toLowerCase(Locale.ROOT).contains("write")) {
                    n++;
                    break;
                }
            }
        }
        return n;
    }

    /** Reads until end of stream or a reset; fails when the socket stays open for {@code millis}. */
    private static void readToEnd(RawWebSocketClient c, long millis) throws IOException {
        c.setReadTimeout((int) Math.max(200, millis));
        try {
            while (true) {
                c.readFrame();
            }
        } catch (SocketTimeoutException e) {
            fail("the stalled WebSocket client socket is still open");
        } catch (IOException endOrReset) {
            // end of stream or reset: the server closed the connection
        }
    }

    /** Waits up to 5 s until {@code n} logger lines match {@code regex}. */
    private void awaitLines(String regex, int n) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        int seen = 0;
        while (System.nanoTime() < end) {
            seen = 0;
            for (String line : new ArrayList<>(log.lines)) {
                seen += line.matches(regex) ? 1 : 0;
            }
            if (seen >= n) {
                return;
            }
            Thread.sleep(5);
        }
        throw new AssertionError(seen + " lines matching " + regex + ", expected " + n + ": " + log.lines);
    }

    // SDS-AC-9; FR-APP-020 AC-8, AC-6, AC-7, FR-APP-019 AC-9, AC-10: two stalled WebSocket sessions, a healthy one,
    // a reading SSE stream and a stalled one; shutdown(1 s) returns within 1 s + 3 s, the healthy session gets 1001,
    // the reading stream the notice, both stalled WebSocket sockets and the stalled stream end by then, and the
    // access log has the 1001 close lines and the SSE lines with cause=shutdown (T44)
    @Test(timeout = 60000)
    public void wave7SdsAc9_FR_APP_020_AC8_AC6_AC7_oneSecondShutdownClosesStalledAndHealthyConnections()
            throws Exception {
        int port = listen();
        RawWebSocketClient s1 = stalledSession(port, 0);
        RawWebSocketClient s2 = stalledSession(port, 1);
        RawWebSocketClient healthy = ws(port, "/ok");
        healthy.text("hi");
        assertEquals("hello", healthy.readFrame(TEXT).text());
        Stream reader = stream(port);
        SseClient a = nextClient();
        Stream stalled = stream(port); // never reads after the headers
        SseClient b = nextClient();
        assertTrue(a.send("one"));
        reader.readUntil("data: one\n\n");
        for (int i = 0; i < 128; i++) { // 8 MiB, beyond the socket buffers of a client that never reads
            assertTrue("stalled send refused", b.send(BIG_SSE));
        }
        long start = System.nanoTime();
        app.shutdown(1, TimeUnit.SECONDS);
        long shutdownMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue("shutdown took " + shutdownMs + " ms", shutdownMs <= 1000 + MARGIN_MS);
        assertEquals(1001, healthy.readFrame(CLOSE).closeCode());
        reader.readAll();
        assertTrue(reader.payload(), reader.payload().endsWith(NOTICE));
        long left = 1000 + MARGIN_MS - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        readToEnd(s1, left);
        readToEnd(s2, left);
        stalled.setReadTimeout((int) Math.max(200, left));
        try {
            stalled.readAll();
        } catch (SocketTimeoutException e) {
            fail("the stalled SSE client socket is still open");
        } catch (IOException reset) {
            // a reset: the adapter stop closed the connection
        }
        assertTrue("the stalled stream was not cut before its notice", !stalled.payload().endsWith(NOTICE));
        assertEquals(AppState.TERMINATED, app.state());
        awaitLines("INFO WS /ok 1001 \\d+ms rid=[0-9a-f]{16}", 1);
        awaitLines("INFO WS /stall 1001 \\d+ms rid=[0-9a-f]{16}", 2);
        awaitLines("INFO GET /events 200 \\d+ms in=0 out=\\d+ rid=[0-9a-f]{16} sse start=\\d+ cause=shutdown", 2);
    }

    // SDS-AC-9; FR-APP-020 AC-2: with a 500 ms timeout and one stalled session, the WebSocket close wait ends within
    // 500 ms + 200 ms and shutdown returns within 500 ms + 3 s (T44)
    @Test(timeout = 60000)
    public void wave7SdsAc9_FR_APP_020_AC2_halfSecondTimeoutBoundsTheCloseWaitAndTheReturn() throws Exception {
        int port = listen();
        RawWebSocketClient s = stalledSession(port, 0);
        long start = System.nanoTime();
        app.shutdown(500, TimeUnit.MILLISECONDS);
        long shutdownMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue("shutdown took " + shutdownMs + " ms", shutdownMs <= 500 + MARGIN_MS);
        Long closed = stalledClosed.poll(10, TimeUnit.SECONDS);
        assertNotNull("the stalled session never ended", closed);
        long waitMs = TimeUnit.NANOSECONDS.toMillis(closed - start);
        assertTrue("the close wait ended " + waitMs + " ms after shutdown began", waitMs <= 500 + 200);
        readToEnd(s, 500 + MARGIN_MS - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
    }

    // SDS-AC-9; FR-APP-020 AC-4: while a stalled session holds the WebSocket close wait the adapter still accepts:
    // a plain request gets 503 with Connection: close and the standard error body, an upgrade is refused without 101,
    // and the before-wait phase ends within 1 s + 200 ms (T44)
    @Test(timeout = 60000)
    public void wave7SdsAc9_FR_APP_020_AC4_requestsDuringTheGoodbyeGet503WithConnectionClose() throws Exception {
        int port = listen();
        stalledSession(port, 0);
        Thread stopper = new Thread(() -> app.shutdown(5, TimeUnit.SECONDS));
        long start = System.nanoTime();
        stopper.start();
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (app.state() != AppState.STOPPING && System.nanoTime() < end) {
            Thread.sleep(1);
        }
        assertEquals(AppState.STOPPING, app.state());
        String r = Http.send(port, Http.get("/x"));
        assertEquals(r, 503, Http.status(r));
        assertEquals("close", Http.header(r, "Connection"));
        String body = r.substring(r.indexOf("\r\n\r\n") + 4);
        assertTrue(body, body.matches("\\{\"error\":\\{\"code\":\"SERVICE_UNAVAILABLE\",\"message\":\"Service "
                + "Unavailable\",\"requestId\":\"[0-9a-f]{16}\"}}"));
        try (RawWebSocketClient upgrade = RawWebSocketClient.open(port, "/ok")) {
            assertEquals(upgrade.handshake, 503, upgrade.status());
            assertEquals("close", Http.header(upgrade.handshake + "\r\n\r\n", "Connection"));
        }
        Long closed = stalledClosed.poll(10, TimeUnit.SECONDS);
        assertNotNull("the stalled session never ended", closed);
        long waitMs = TimeUnit.NANOSECONDS.toMillis(closed - start);
        assertTrue("the before-wait phase ended " + waitMs + " ms after shutdown began", waitMs <= 1000 + 200);
        stopper.join(10000);
        assertTrue("shutdown did not return", !stopper.isAlive());
    }
}
