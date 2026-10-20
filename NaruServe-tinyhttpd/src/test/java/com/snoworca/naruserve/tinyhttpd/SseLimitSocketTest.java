package com.snoworca.naruserve.tinyhttpd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.HttpException;
import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.SseClient;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.tinyhttpd.RawTargetSocketTest.Http;
import com.snoworca.naruserve.tinyhttpd.SseSocketTest.Stream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Test;

/**
 * The NaruServe SSE counter of the TinyHttpd adapter. SDS-AC-n cites
 * docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-11.sds.md.
 */
public class SseLimitSocketTest {

    private final RecordingLogger log = new RecordingLogger();
    private final TinyHttpdAdapter adapter = new TinyHttpdAdapter();
    private final List<Stream> streams = new ArrayList<>();
    private final BlockingQueue<SseClient> opened = new LinkedBlockingQueue<>();
    private RestApp app;

    @After
    public void stop() throws IOException {
        for (Stream s : streams) {
            s.close();
        }
        if (app != null) {
            app.shutdown(2, TimeUnit.SECONDS);
        }
        Http.stopQuietly(adapter);
    }

    /** The end reason the core recorded for {@code c}, read reflectively since the client class is internal. */
    static String cause(SseClient c) throws Exception {
        Method m = c.getClass().getDeclaredMethod("cause");
        m.setAccessible(true);
        return (String) m.invoke(c);
    }

    /** Opens a stream to /events and returns its status line and headers. */
    private String open(int port) throws IOException {
        Stream s = new Stream(port, Http.get("/events"));
        streams.add(s);
        return s.readHeaders();
    }

    private SseClient next() throws InterruptedException {
        SseClient c = opened.poll(10, TimeUnit.SECONDS);
        assertNotNull("no stream opened", c);
        return c;
    }

    // SDS-AC-2; FR-SSE-003 AC-4: with maxSseStreams(2) and two open streams a third res.sse() is answered 503 with
    // the standard error body and its request id, the exchange still unstarted; after one stream ended a new
    // res.sse() is accepted
    @Test(timeout = 30000)
    public void sdsAc2_FR_SSE_003_AC4_aThirdStreamGets503WithTheRequestIdAndAFreedSlotIsReused() throws Exception {
        AtomicReference<String> refused = new AtomicReference<>();
        app = Http.app(adapter, log);
        app.server(o -> o.maxSseStreams(2));
        app.get("/events", (req, res) -> {
            try {
                opened.add(res.sse());
            } catch (HttpException e) {
                refused.set(e.status() + " " + res.isCommitted());
                throw e;
            }
        });
        int port = app.listen(0).port();
        assertEquals(200, Http.status(open(port)));
        assertEquals(200, Http.status(open(port)));
        SseClient first = next();
        next();
        String third = Http.send(port, Http.get("/events"));
        assertEquals(third, 503, Http.status(third));
        assertTrue(third, Http.body(third).matches("\\{\"error\":\\{\"code\":\"SERVICE_UNAVAILABLE\","
                + "\"message\":\"[^\"]*\",\"requestId\":\"[0-9a-f]{16}\"}}"));
        assertEquals("503 false", refused.get());
        first.close();
        streams.get(0).readAll();
        String accepted = null;
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (accepted == null && System.nanoTime() < end) {
            String head = open(port);
            if (Http.status(head) == 200) {
                accepted = head;
            } else {
                Thread.sleep(20);
            }
        }
        assertNotNull("no stream accepted after one ended", accepted);
        next().close();
    }

    // SDS-AC-6; FR-SSE-003 AC-7, AC-3: with maxWorkers(2), maxSseStreams(3) and heartbeat 1 s, two plain GETs that
    // wait 200 ms are answered while three streams are open; after the three clients closed their sockets the
    // streams end with cause client and maxSseStreams new streams are open within two heartbeats plus 1 s
    @Test(timeout = 60000)
    public void sdsAc6_FR_SSE_003_AC7_AC3_closedClientsFreeTheirSlotsWithin3SecondsAndStreamsKeepTheHttpWorkers()
            throws Exception {
        app = Http.app(adapter, log);
        app.server(o -> o.maxWorkers(2).maxSseStreams(3));
        app.sse(o -> o.heartbeatInterval(1, TimeUnit.SECONDS));
        app.get("/events", (req, res) -> opened.add(res.sse()));
        app.get("/slow", (req, res) -> {
            Thread.sleep(200);
            res.send("slow");
        });
        int port = app.listen(0).port();
        List<SseClient> old = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            assertEquals(200, Http.status(open(port)));
            old.add(next());
        }
        ExecutorService plain = Executors.newFixedThreadPool(2);
        try {
            Future<String> a = plain.submit(() -> Http.send(port, Http.get("/slow")));
            Future<String> b = plain.submit(() -> Http.send(port, Http.get("/slow")));
            assertEquals("slow", Http.body(a.get(10, TimeUnit.SECONDS)));
            assertEquals("slow", Http.body(b.get(10, TimeUnit.SECONDS)));
        } finally {
            plain.shutdownNow();
        }
        for (SseClient c : old) {
            assertTrue(c.isOpen());
        }
        for (Stream s : streams) {
            s.close();
        }
        streams.clear();
        long closed = System.nanoTime();
        int accepted = 0;
        long end = closed + TimeUnit.SECONDS.toNanos(10);
        while (accepted < 3 && System.nanoTime() < end) {
            if (Http.status(open(port)) == 200) {
                accepted++;
                next();
            } else {
                Thread.sleep(5);
            }
        }
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - closed);
        assertEquals(3, accepted);
        assertTrue("new streams open after " + ms + " ms", ms <= 3000);
        for (SseClient c : old) {
            assertEquals("client", cause(c));
        }
    }
}
