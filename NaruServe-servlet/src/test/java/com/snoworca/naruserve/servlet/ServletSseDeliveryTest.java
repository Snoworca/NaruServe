package com.snoworca.naruserve.servlet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.SseClient;
import com.snoworca.naruserve.testing.RecordingLogger;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;

/**
 * SSE writes reach the client at once (each write flushed), and a closed client frees its stream in time.
 * SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-8-7.sds.md.
 */
public class ServletSseDeliveryTest {

    @Rule
    public final ContainerRule container = new ContainerRule();

    private final RecordingLogger log = new RecordingLogger();
    private final List<SseProbe> probes = new ArrayList<>();
    private final BlockingQueue<SseClient> opened = new LinkedBlockingQueue<>();
    private RestApp app;

    @After
    public void stop() throws IOException {
        for (SseProbe p : probes) {
            p.close();
        }
        if (app != null) {
            app.shutdown(2, TimeUnit.SECONDS);
        }
    }

    private SseProbe open(String path) throws IOException {
        SseProbe p = SseProbe.open(container.port(), path);
        probes.add(p);
        return p;
    }

    private SseClient next() throws InterruptedException {
        SseClient c = opened.poll(10, TimeUnit.SECONDS);
        assertNotNull("no stream opened", c);
        return c;
    }

    private static long millisSince(long nanos, long at) {
        return TimeUnit.NANOSECONDS.toMillis(at - nanos);
    }

    // SDS-AC-8; FR-SVL-005 AC-4
    @Test(timeout = 60000)
    public void sdsAc8_FR_SVL_005_AC4_sendsAndHeartbeatsReachTheClientWithin500ms() throws Exception {
        CountDownLatch seenEarly = new CountDownLatch(1);
        AtomicBoolean earlyBeforeReturn = new AtomicBoolean();
        app = ServletSseLimitTest.jetty(container, log, a -> {
            a.sse(o -> o.heartbeatInterval(1, TimeUnit.SECONDS));
            a.get("/events", (req, res) -> opened.add(res.sse()));
            a.get("/early", (req, res) -> {
                SseClient c = res.sse();
                c.send("early");
                // the handler still runs: the event must already be at the client
                earlyBeforeReturn.set(seenEarly.await(5, TimeUnit.SECONDS));
                c.close();
            });
        });

        // a send from another thread after the handler returned
        SseProbe p = open("/events");
        long opened0 = System.nanoTime();
        SseClient c = next();
        // the handler returned once its drain runs on an SSE thread
        long drainBy = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (ServletSseLimitTest.sseThreads(Collections.<Thread>emptySet()).isEmpty()
                && System.nanoTime() < drainBy) {
            Thread.sleep(5);
        }
        assertFalse("no drain thread", ServletSseLimitTest.sseThreads(Collections.<Thread>emptySet()).isEmpty());
        // The best of up to three rounds is held to the bounds, so one scheduler stall of a loaded host does not
        // fail them; a buffering write path misses them in every round.
        long bestEvent = Long.MAX_VALUE;
        long bestPing = Long.MAX_VALUE;
        for (int round = 0; round < 3 && (bestEvent > 500 || bestPing > 1500); round++) {
            String event = "data: later" + round + "\n\n";
            long sent = System.nanoTime();
            assertTrue(c.send("later" + round));
            long arrived = p.awaitPayload(event);
            bestEvent = Math.min(bestEvent, millisSince(sent, arrived));

            // a heartbeat to an idle client: heartbeatInterval after the last write, plus 500 ms
            String payload = p.payload();
            int pingsBefore = SseProbe.count(payload.substring(0, payload.indexOf(event)), ": ping\n\n");
            long ping = p.awaitCount(": ping\n\n", pingsBefore + 1);
            bestPing = Math.min(bestPing, millisSince(arrived, ping));
            assertTrue(millisSince(opened0, ping) >= 900);
        }
        assertTrue("event after " + bestEvent + " ms (best round)", bestEvent <= 500);
        assertTrue("ping after " + bestPing + " ms (best round)", bestPing <= 1500);
        c.close();

        // a send by the handler thread before the drain starts
        SseProbe early = open("/early");
        early.awaitPayload("data: early\n\n");
        seenEarly.countDown();
        assertTrue(early.awaitEnd(5000));
        assertTrue("the early event reached the client while the handler ran", earlyBeforeReturn.get());
    }

    // SDS-AC-10; FR-SVL-005 AC-6
    @Test(timeout = 60000)
    public void sdsAc10_FR_SVL_005_AC6_aClosedClientEndsWithCauseClientAndFreesItsThreadWithin3s()
            throws Exception {
        AtomicInteger closes = new AtomicInteger();
        app = ServletSseLimitTest.jetty(container, log, a -> {
            a.server(o -> o.maxSseStreams(1));
            a.sse(o -> o.heartbeatInterval(1, TimeUnit.SECONDS));
            a.get("/events", (req, res) -> {
                SseClient c = res.sse();
                c.onClose(closes::incrementAndGet);
                opened.add(c);
            });
        });
        SseProbe p = open("/events");
        SseClient c = next();
        p.close();
        long closed = System.nanoTime();
        SseProbe again = null;
        long end = closed + TimeUnit.SECONDS.toNanos(10);
        while (again == null && System.nanoTime() < end) {
            SseProbe q = open("/events");
            if (q.status() == 200) {
                again = q;
            } else {
                Thread.sleep(5);
            }
        }
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - closed);
        assertNotNull("no stream accepted after the client closed", again);
        assertTrue("the slot and thread came back after " + ms + " ms", ms <= 3000);
        next();
        assertFalse(c.isOpen());
        assertEquals("client", ServletSseLimitTest.cause(c));
        assertEquals(1, closes.get());
        String regex = "INFO GET /events 200 .* cause=client";
        RecordingLogger.awaitMatching(() -> log.lines, regex, 1);
    }
}
