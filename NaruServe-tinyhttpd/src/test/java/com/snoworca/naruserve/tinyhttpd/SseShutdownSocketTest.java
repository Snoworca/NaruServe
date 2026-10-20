package com.snoworca.naruserve.tinyhttpd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.SseClient;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.tinyhttpd.RawTargetSocketTest.Http;
import com.snoworca.naruserve.tinyhttpd.SseSocketTest.Stream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Test;

/**
 * The SSE closing participant over TinyHttpd (T44). SDS-AC-n cites
 * docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-11.sds.md.
 */
public class SseShutdownSocketTest {

    private static final String NOTICE = "event: shutdown\ndata: shutdown\n\n";
    /** One stalled-stream event: 64 KiB of data. */
    private static final String BIG;

    static {
        char[] a = new char[64 * 1024];
        Arrays.fill(a, 's');
        BIG = new String(a);
    }

    private final TinyHttpdAdapter adapter = new TinyHttpdAdapter();
    private final RestApp app = Http.app(adapter, new RecordingLogger());
    private final BlockingQueue<SseClient> opened = new LinkedBlockingQueue<>();
    private final BlockingQueue<SseClient> closed = new LinkedBlockingQueue<>();
    /** The shutdown timeout of the test and the D-344 margin over it. */
    private static final long TIMEOUT_MS = 1000;
    private static final long MARGIN_MS = 3000;

    @After
    public void stop() {
        app.shutdown(2, TimeUnit.SECONDS);
        Http.stopQuietly(adapter);
    }

    /** The internal SSE registry size of {@code app}, read reflectively since it is package-private in the core. */
    private static int registrySize(RestApp app) throws Exception {
        Method m = app.getClass().getDeclaredMethod("sseRegistry");
        m.setAccessible(true);
        Object registry = m.invoke(app);
        Method size = registry.getClass().getDeclaredMethod("size");
        size.setAccessible(true);
        return (Integer) size.invoke(registry);
    }

    private static int queuedBytes(SseClient c) throws Exception {
        Method m = c.getClass().getDeclaredMethod("queuedBytes");
        m.setAccessible(true);
        return (Integer) m.invoke(c);
    }

    private int listen() {
        app.sse(o -> o.sendQueueSize(16 * 1024 * 1024));
        app.get("/events", (req, res) -> {
            SseClient c = res.sse();
            c.onClose(() -> closed.add(c));
            opened.add(c);
        });
        return app.listen(0).port();
    }

    private SseClient next() throws InterruptedException {
        SseClient c = opened.poll(10, TimeUnit.SECONDS);
        assertNotNull("no stream opened", c);
        return c;
    }

    // SDS-AC-12; FR-SSE-005 AC-2, AC-5, AC-6: at shutdown a reading client gets its two events, the notice and the
    // end of the chunked body; a client that stopped reading is force-closed by the adapter stop within the
    // shutdown timeout plus the margin, keeps cause shutdown although its drain write fails; each onClose runs
    // once and the registry is empty after shutdown returned (T44)
    @Test(timeout = 60000)
    public void sdsAc12_FR_SSE_005_AC2_AC5_AC6_noticeThenEndForAReaderStalledClientForceClosedRegistryEmpty()
            throws Exception {
        int port = listen();
        try (Stream reader = new Stream(port, Http.get("/events"))) {
            reader.readHeaders();
            SseClient a = next();
            try (Stream stalled = new Stream(port, Http.get("/events"))) {
                stalled.readHeaders(); // and never reads again
                stalledCase(reader, a, stalled);
            }
        }
    }

    /** Both streams are open; reader reads, stalled never reads again. */
    private void stalledCase(Stream reader, SseClient a, Stream stalled) throws Exception {
        SseClient b = next();
        assertTrue(a.send("one"));
        assertTrue(a.send("two"));
        reader.readUntil("data: two\n\n");
        for (int i = 0; i < 128; i++) { // 8 MiB, far beyond the socket buffers of a client that never reads
            assertTrue("stalled send refused", b.send(BIG));
        }
        assertTrue("the stalled stream's queue is empty", queuedBytes(b) > 0);
        long start = System.nanoTime();
        app.shutdown(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        long shutdownMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue("shutdown took " + shutdownMs + " ms", shutdownMs <= TIMEOUT_MS + MARGIN_MS);
        String raw = reader.readAll();
        String body = raw.substring(raw.indexOf("\r\n\r\n") + 4);
        assertEquals("data: one\n\ndata: two\n\n" + NOTICE, reader.payload());
        assertTrue(body, body.endsWith("0\r\n\r\n"));
        long left = TIMEOUT_MS + MARGIN_MS - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        stalled.setReadTimeout((int) Math.max(200, left)); // the socket must end within the timeout plus the margin
        try {
            stalled.readAll();
        } catch (SocketTimeoutException e) {
            fail("the stalled client socket is still open");
        } catch (IOException reset) {
            // a reset: the adapter stop closed the connection
        }
        assertEquals("shutdown", SseLimitSocketTest.cause(a));
        assertEquals("shutdown", SseLimitSocketTest.cause(b));
        List<SseClient> ran = Arrays.asList(closed.poll(5, TimeUnit.SECONDS), closed.poll(5, TimeUnit.SECONDS));
        assertTrue("onClose of the reading stream", ran.contains(a));
        assertTrue("onClose of the stalled stream", ran.contains(b));
        assertNull("an onClose ran twice", closed.poll(200, TimeUnit.MILLISECONDS));
        assertEquals(0, registrySize(app));
        assertEquals(0, queuedBytes(a));
        assertEquals(0, queuedBytes(b));
    }
}
