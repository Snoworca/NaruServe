package com.snoworca.naruserve.tinyhttpd;

import static com.snoworca.naruserve.tinyhttpd.RawWebSocketClient.TEXT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.Logger;
import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.ServerOptions;
import com.snoworca.naruserve.spi.ErrorRenderer;
import com.snoworca.naruserve.spi.ServerSettings;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.tinyhttpd.RawTargetSocketTest.Http;
import com.snoworca.naruserve.tinyhttpd.SseSocketTest.Stream;
import java.io.Closeable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/**
 * T26 in the isolatedTest task, one JVM per class, so the only app threads alive are this app's: 60 WebSocket sessions
 * and 60 SSE streams stay within the thread total and a heap bound. SDS-AC-n cites
 * docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-13.sds.md.
 */
public class LoadIsolatedSocketTest {

    /** The thread total of one app under the default limits (PERF-APP-001 AC-2). */
    static final int THREAD_BOUND = 452;

    private static final int CONNECTIONS = 60;
    private static final long HEAP_BOUND = 64L * 1024 * 1024;

    private static long usedHeapAfterGc() throws InterruptedException {
        Runtime rt = Runtime.getRuntime();
        for (int i = 0; i < 3; i++) {
            System.gc();
            Thread.sleep(50);
        }
        return rt.totalMemory() - rt.freeMemory();
    }

    private static void assertWithin5s(String what, long startNanos) {
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        assertTrue(what + " got its message after " + ms + " ms", ms <= 5000);
    }

    // SDS-AC-1; PERF-APP-001 AC-1: 60 WebSocket sessions and 60 SSE streams from 127.0.0.1 under the default limits
    // each get one message or event within 5 s; with all 120 open the app has at most THREAD_BOUND threads and the
    // used heap grew by less than 64 MiB (T26)
    @Test(timeout = 120000)
    public void sdsAc1_PERF_APP_001_AC1_120LongConnectionsStayWithinTheThreadAndHeapBounds() throws Exception {
        RestApp app = Http.app(new TinyHttpdAdapter(), new RecordingLogger());
        app.ws("/ws", (req, ws) -> ws.sendText("hello"));
        app.get("/events", (req, res) -> res.sse().send("one"));
        int port = app.listen(0).port();
        List<Closeable> open = new ArrayList<>();
        try {
            long before = usedHeapAfterGc();
            for (int i = 0; i < CONNECTIONS; i++) {
                long wsStart = System.nanoTime();
                RawWebSocketClient ws = RawWebSocketClient.open(port, "/ws");
                open.add(ws);
                assertEquals(ws.handshake, 101, ws.status());
                ws.setReadTimeout(5000);
                assertEquals("hello", ws.readFrame(TEXT).text());
                assertWithin5s("WebSocket " + i, wsStart);
                long sseStart = System.nanoTime();
                Stream sse = new Stream(port, Http.get("/events"));
                open.add(sse);
                sse.setReadTimeout(5000);
                sse.readHeaders();
                sse.readUntil("data: one\n\n");
                assertWithin5s("SSE stream " + i, sseStart);
            }
            int threads = Http.appThreads(port).size();
            long grown = usedHeapAfterGc() - before;
            // each SSE stream holds a worker and each session a read thread, so a name filter that missed the
            // app's threads would count fewer than 2 * CONNECTIONS
            assertTrue("app threads with 120 connections open: " + threads,
                    threads >= 2 * CONNECTIONS && threads <= THREAD_BOUND);
            assertTrue("used heap grew by " + grown + " bytes", grown < HEAP_BOUND);
        } finally {
            Http.closeAll(open);
            app.shutdown(2, TimeUnit.SECONDS);
        }
    }

    // SDS-AC-2; PERF-APP-001 AC-2: the thread total from the defaults is maxWorkers + maxSseStreams (192), a read
    // and a sender thread per upgraded connection (128 + 128), the idle scheduler, the TinyHttpd acceptor and
    // watchdog and naruserve-keeper: 452, the T26 bound
    @Test
    public void sdsAc2_PERF_APP_001_AC2_theDefaultsAddUpToTheThreadBound() throws Exception {
        RestApp app = Http.app(new TinyHttpdAdapter(), new RecordingLogger());
        AtomicReference<ServerOptions> fresh = new AtomicReference<>();
        app.server(fresh::set);
        // DefaultServerOptions keeps its values private; freeze is the snapshot listen() hands to the adapter
        Method freeze = fresh.get().getClass().getDeclaredMethod("freeze", int.class, Logger.class,
                ErrorRenderer.class);
        freeze.setAccessible(true);
        ErrorRenderer renderer = status -> "{}";
        ServerSettings s = (ServerSettings) freeze.invoke(fresh.get(), 0, new RecordingLogger(), renderer);
        int pool = s.maxWorkers() + s.maxSseStreams();
        assertEquals(192, pool);
        assertEquals(128, s.maxUpgradedConnections());
        int idleScheduler = 1;
        int acceptorWatchdogKeeper = 3;
        int total = pool + s.maxUpgradedConnections() + s.maxUpgradedConnections() + idleScheduler
                + acceptorWatchdogKeeper;
        assertEquals(452, total);
        assertEquals(THREAD_BOUND, total);
    }
}
