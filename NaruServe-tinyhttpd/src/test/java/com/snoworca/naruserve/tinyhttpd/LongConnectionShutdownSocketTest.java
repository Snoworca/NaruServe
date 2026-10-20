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
import com.snoworca.naruserve.testing.NoLeftoverThreads;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.tinyhttpd.RawTargetSocketTest.Http;
import com.snoworca.naruserve.tinyhttpd.SseSocketTest.Stream;
import java.io.Closeable;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;

/**
 * T44 over TinyHttpd: WebSocket sessions and SSE streams, stalled and healthy, at shutdown. SDS-AC-n cites
 * docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-12.sds.md.
 */
public class LongConnectionShutdownSocketTest {

    // I-015: no naruserve-* thread outlives the test
    @Rule
    public final NoLeftoverThreads noLeftoverThreads = new NoLeftoverThreads();

    private static final String NOTICE = "event: shutdown\ndata: shutdown\n\n";
    /** The D-344 margin over the shutdown timeout. */
    private static final long MARGIN_MS = 3000;
    /** One stalled-session message: 512 KiB; one stalled-stream event: 64 KiB. */
    private static final String BIG_WS = Http.repeat('s', 512 * 1024);
    private static final String BIG_SSE = Http.repeat('s', 64 * 1024);

    private final TinyHttpdAdapter adapter = new TinyHttpdAdapter();
    private final RecordingLogger log = new RecordingLogger();
    private final RestApp app = Http.app(adapter, log);
    private final List<Closeable> sockets = new ArrayList<>();
    private final BlockingQueue<Long> stalledClosed = new LinkedBlockingQueue<>();
    private final BlockingQueue<SseClient> opened = new LinkedBlockingQueue<>();

    @After
    public void stop() {
        app.shutdown(2, TimeUnit.SECONDS);
        Http.closeAll(sockets);
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
        sockets.add(c);
        assertEquals(c.handshake, 101, c.status());
        return c;
    }

    private Stream stream(int port) throws IOException {
        Stream s = Stream.open(port, Http.get("/events"));
        sockets.add(s);
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
            stable = Http.blockedThreads(port).size() > blockedBefore ? stable + 1 : 0;
            Thread.sleep(10);
        }
        assertEquals("sender threads blocked in a write", blockedBefore + 1, Http.blockedThreads(port).size());
        return c;
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
        s1.readToEnd(left);
        s2.readToEnd(left);
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
        RecordingLogger.awaitMatching(() -> log.lines, "INFO WS /ok 1001 \\d+ms rid=[0-9a-f]{16}", 1);
        RecordingLogger.awaitMatching(() -> log.lines, "INFO WS /stall 1001 \\d+ms rid=[0-9a-f]{16}", 2);
        RecordingLogger.awaitMatching(() -> log.lines, "INFO GET /events 200 \\d+ms in=0 out=\\d+ rid=[0-9a-f]{16} sse start=\\d+ cause=shutdown", 2);
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
        s.readToEnd(500 + MARGIN_MS - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
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
