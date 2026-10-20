package com.snoworca.naruserve.servlet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.SseClient;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/**
 * Open SSE streams at shutdown: the closing notice and the end of each body, and the cut at the deadline of a
 * stream whose client stopped reading. SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-8-8.sds.md.
 */
public class ServletStopSseTest extends SseJettyTest {

    /** The D-344 margin of shutdown (FR-APP-020). */
    private static final long MARGIN_MILLIS = 3000;

    private final ServletSseAsyncTest.AsyncProbe completes = new ServletSseAsyncTest.AsyncProbe();
    private ServletAdapter adapter;

    private void start() throws Exception {
        adapter = new ServletAdapter();
        container.filter(completes);
        app = SharedApp.onJetty(container, adapter, "/*", null, a -> {
            a.sse(o -> o.sendQueueSize(16 * 1024 * 1024));
            a.get("/events", (req, res) -> {
                SseClient c = res.sse();
                c.send("hello", "1");
                opened.add(c);
            });
        });
    }

    private void awaitOpen(int n) throws InterruptedException {
        SharedApp.until(() -> adapter.pool().open() == n, 5, TimeUnit.SECONDS);
        assertEquals("open streams", n, adapter.pool().open());
    }

    private void awaitCompletes(int n) throws InterruptedException {
        SharedApp.until(() -> completes.completes.get() >= n, 2, TimeUnit.SECONDS);
        assertEquals("AsyncContext.complete() calls", n, completes.completes.get());
    }

    /**
     * Waits until the stream's drain thread is parked inside a body write, so the non-reading client has filled
     * every buffer on the way and stop cannot end the stream before the deadline; fails if that never happens.
     */
    private static void awaitBlockedWrite() throws InterruptedException {
        if (!SharedApp.until(ServletStopSseTest::drainBlockedInWrite, 10, TimeUnit.SECONDS)) {
            fail("the drain never blocked in a write: the socket buffers took the whole queue");
        }
    }

    private static boolean drainBlockedInWrite() {
        for (Thread t : ServletSseLimitTest.sseThreads(java.util.Collections.<Thread>emptySet())) {
            if (t.getState() == Thread.State.RUNNABLE) {
                continue;
            }
            for (StackTraceElement e : t.getStackTrace()) {
                if (e.getClassName().equals(ServletEventStream.FlushingStream.class.getName())
                        && e.getMethodName().equals("write")) {
                    return true;
                }
            }
        }
        return false;
    }

    // FR-SVL-006 AC-2 SDS-AC-2: closing notice, end of each chunked body, one complete per stream, executor down
    @Test
    public void sdsAc2_FR_SVL_006_AC2_shutdownEndsEveryOpenStream() throws Exception {
        start();
        for (int i = 0; i < 2; i++) {
            SseProbe p = SseProbe.open(container.port(), "/events");
            probes.add(p);
            p.awaitPayload("data: 1");
            next();
        }
        awaitOpen(2);
        long at = System.nanoTime();
        app.shutdown(2, TimeUnit.SECONDS);
        app = null;
        for (SseProbe p : probes) {
            p.awaitPayload("event: shutdown\ndata: shutdown\n\n");
            assertTrue("the chunked body did not end", p.awaitEnd((int) (2000 + MARGIN_MILLIS)));
        }
        long took = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - at);
        assertTrue("streams ended after " + took + " ms", took <= 2000 + MARGIN_MILLIS);
        awaitCompletes(2);
        assertEquals(0, adapter.pool().open());
        try {
            adapter.pool().submit(() -> { });
            fail("the SSE executor still accepts work after shutdown");
        } catch (RejectedExecutionException expected) {
            // shut down
        }
    }

    // FR-SVL-006 AC-3 SDS-AC-3: a stream whose client stopped reading is cut by complete() at the deadline
    @Test
    public void sdsAc3_FR_SVL_006_AC3_nonReadingClientIsCutAtTheDeadline() throws Exception {
        start();
        try (Socket s = new Socket()) {
            s.setReceiveBufferSize(4096);
            s.connect(new InetSocketAddress("127.0.0.1", container.port()), 5000);
            s.setSoTimeout(10000);
            s.getOutputStream().write("GET /events HTTP/1.1\r\nHost: localhost\r\n\r\n"
                    .getBytes(StandardCharsets.ISO_8859_1));
            InputStream in = s.getInputStream();
            byte[] head = new byte[256];
            assertTrue(in.read(head) > 0);
            assertTrue(new String(head, StandardCharsets.ISO_8859_1).startsWith("HTTP/1.1 200"));
            SseClient c = next();
            // The client reads nothing more: ~15 MiB queued under the 16 MiB queue fills every buffer on the way.
            char[] chunk = new char[64 * 1024];
            Arrays.fill(chunk, 'x');
            String data = new String(chunk);
            for (int i = 0; i < 240; i++) {
                assertTrue("send " + i, c.send(data));
            }
            awaitBlockedWrite();
            awaitOpen(1);
            long at = System.nanoTime();
            adapter.stop(1, TimeUnit.SECONDS);
            long took = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - at);
            assertTrue("stop took " + took + " ms", took >= 900 && took <= 2000);
            awaitCompletes(1);
            awaitOpen(0);
        }
    }
}
