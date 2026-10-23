package com.snoworca.naruserve.tinyhttpd;

import static com.snoworca.naruserve.tinyhttpd.RawWebSocketClient.CLOSE;
import static com.snoworca.naruserve.tinyhttpd.RawWebSocketClient.TEXT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.testing.NoLeftoverThreads;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.tinyhttpd.RawTargetSocketTest.Http;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;

/**
 * The WebSocket closing participant over TinyHttpd (T21, T44). SDS-AC-n cites
 * docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-7.sds.md.
 */
public class WebSocketShutdownSocketTest {

    // I-015: no naruserve-* thread outlives the test
    @Rule
    public final NoLeftoverThreads noLeftoverThreads = new NoLeftoverThreads();

    /** One stalled-session message: 512 KiB. */
    private static final String BIG = Http.repeat('s', 512 * 1024);

    private final RestApp app = Http.app(new TinyHttpdAdapter(), new RecordingLogger());
    private final List<RawWebSocketClient> clients = new ArrayList<>();
    /** onClose of the stalled sessions, as System.nanoTime() of the call. */
    private final BlockingQueue<Long> stalledClosed = new LinkedBlockingQueue<>();
    private final BlockingQueue<String> healthyClosed = new LinkedBlockingQueue<>();

    @After
    public void stop() {
        app.shutdown(2, TimeUnit.SECONDS);
        Http.closeAll(clients);
    }

    /** The internal registry size of {@code app}, read reflectively since it is package-private in the core. */
    static int registrySize(RestApp app) throws Exception {
        return (Integer) Http.invoke(Http.invoke(app, "webSocketRegistry"), "size");
    }

    /** /stall queues 16 x 512 KiB on "stall"; /ok answers "hello" to any text. */
    private int listen() {
        app.webSocket(o -> o.sendQueueSize(64 * 1024 * 1024));
        app.ws("/stall", (req, ws) -> {
            ws.onClose((code, reason) -> stalledClosed.add(System.nanoTime()));
            ws.onText(text -> {
                for (int i = 0; i < 16; i++) {
                    ws.sendText(BIG);
                }
            });
        });
        app.ws("/ok", (req, ws) -> {
            ws.onClose((code, reason) -> healthyClosed.add(code + " " + reason));
            ws.onText(text -> ws.sendText("hello"));
        });
        return app.listen(0).port();
    }

    /**
     * The state and top frames of the sender threads of {@code port}, for a failed wait: this wait failed once in two
     * of four full Java 8 runs (expected 2 blocked, found 1) and never alone, so the next failure must say why.
     */
    private static String senderThreads(int port) {
        StringBuilder sb = new StringBuilder(" [sender threads:");
        for (Thread t : Http.threads("naruserve-" + port + "-ws-sender-")) {
            StackTraceElement[] st = t.getStackTrace();
            sb.append("\n  ").append(t.getName()).append(' ').append(t.getState());
            for (int i = 0; i < Math.min(4, st.length); i++) {
                sb.append("\n    at ").append(st[i]);
            }
        }
        return sb.append("\n]").toString();
    }

    private RawWebSocketClient open(int port, String path) throws IOException {
        RawWebSocketClient c = RawWebSocketClient.open(port, path);
        clients.add(c);
        assertEquals(c.handshake, 101, c.status());
        return c;
    }

    // SDS-AC-9; FR-WS-010 AC-4, AC-2: with two sessions whose writes blocked on clients that stopped reading, both
    // are aborted within 1 s plus 200 ms of the participant starting and their sockets end; a healthy session
    // receives close 1001 and its onClose runs once with 1001, though its client never answers the close (T21, T44)
    @Test(timeout = 60000)
    public void sdsAc9_FR_WS_010_AC4_AC2_twoStalledSessionsAbortedWithin1200MsHealthyGets1001() throws Exception {
        int port = listen();
        RawWebSocketClient s1 = open(port, "/stall");
        RawWebSocketClient s2 = open(port, "/stall");
        s1.text("stall"); // and never reads again
        s2.text("stall");
        RawWebSocketClient healthy = open(port, "/ok");
        healthy.text("hi");
        assertEquals("hello", healthy.readFrame(TEXT).text());
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (Http.blockedThreads(port).size() < 2 && System.nanoTime() < end) {
            Thread.sleep(5);
        }
        assertEquals("sender threads blocked in a write" + senderThreads(port), 2, Http.blockedThreads(port).size());
        Thread stopper = new Thread(() -> app.shutdown(5, TimeUnit.SECONDS));
        long start = System.nanoTime();
        stopper.start();
        for (int i = 0; i < 2; i++) {
            Long closed = stalledClosed.poll(10, TimeUnit.SECONDS);
            assertNotNull("stalled session " + i + " never ended", closed);
            long ms = TimeUnit.NANOSECONDS.toMillis(closed - start);
            assertTrue("stalled session aborted " + ms + " ms after shutdown began", ms <= 1200);
        }
        RawWebSocketClient.Frame close = healthy.readFrame(CLOSE);
        assertEquals(1001, close.closeCode());
        assertEquals("server shutdown", close.closeReason());
        s1.readToEnd(5000);
        s2.readToEnd(5000);
        long ended = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue("stalled client sockets ended " + ended + " ms after shutdown began", ended <= 1200);
        stopper.join(10000);
        assertTrue("shutdown did not return", !stopper.isAlive());
        assertEquals("1001 server shutdown", healthyClosed.poll(10, TimeUnit.SECONDS));
        assertNull("onClose ran twice", healthyClosed.poll(200, TimeUnit.MILLISECONDS));
    }

    // SDS-AC-12; FR-WS-010 AC-8: after shutdown returned no naruserve-<port>-ws- thread of the app is alive and its
    // session registry is empty (T44)
    @Test(timeout = 30000)
    public void sdsAc12_FR_WS_010_AC8_noWsThreadOfThePortAndAnEmptyRegistryAfterShutdown() throws Exception {
        int port = listen();
        RawWebSocketClient healthy = open(port, "/ok");
        healthy.text("hi");
        assertEquals("hello", healthy.readFrame(TEXT).text());
        assertTrue("no idle thread", !Http.threads("naruserve-" + port + "-ws-idle").isEmpty());
        assertTrue("no sender thread", !Http.threads("naruserve-" + port + "-ws-sender-").isEmpty());
        app.shutdown(2, TimeUnit.SECONDS);
        // a thread whose executor reports terminated may still be leaving its run method
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (!Http.threads("naruserve-" + port + "-ws-").isEmpty() && System.nanoTime() < end) {
            Thread.sleep(5);
        }
        assertEquals(new ArrayList<Thread>(), Http.threads("naruserve-" + port + "-ws-"));
        assertEquals(0, registrySize(app));
    }
}
