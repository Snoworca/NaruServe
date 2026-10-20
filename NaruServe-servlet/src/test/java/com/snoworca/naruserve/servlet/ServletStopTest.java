package com.snoworca.naruserve.servlet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.SseClient;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;

/**
 * The two-call stop of the Servlet adapter: refuse at once, wait for in-flight requests until the deadline, then
 * release. SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-8-8.sds.md.
 */
public class ServletStopTest {

    @Rule
    public final ContainerRule container = new ContainerRule();

    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch proceed = new CountDownLatch(1);
    private final List<SseProbe> probes = new ArrayList<>();
    private ServletAdapter adapter;
    private RestApp app;

    @After
    public void shutdown() throws Exception {
        proceed.countDown();
        for (SseProbe p : probes) {
            p.close();
        }
        if (app != null) {
            app.shutdown(2, TimeUnit.SECONDS);
        }
    }

    private void start() throws Exception {
        adapter = new ServletAdapter();
        app = SharedApp.listen(adapter, a -> {
            a.get("/slow", (req, res) -> {
                entered.countDown();
                try {
                    proceed.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                res.send("done");
            });
            a.get("/fast", (req, res) -> res.send("ok"));
            a.get("/events", (req, res) -> {
                SseClient c = res.sse();
                c.send("hello", "1");
            });
        });
        container.servlet(new NaruServeServlet(adapter, ""), "/*");
    }

    /** The slow request on its own thread; its reply lands in the returned reference. */
    private AtomicReference<SharedApp.Reply> slow(Thread[] holder) {
        AtomicReference<SharedApp.Reply> reply = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                reply.set(SharedApp.http(container.port(), "GET", "/slow", null));
            } catch (Exception e) {
                // the assertion on the reply reports it
            }
        });
        t.start();
        holder[0] = t;
        return reply;
    }

    /** Runs {@code adapter.stop(timeout, SECONDS)} on its own thread; the elapsed millis land in {@code took}. */
    private Thread stopAsync(long timeout, long[] took) {
        Thread t = new Thread(() -> {
            long at = System.nanoTime();
            adapter.stop(timeout, TimeUnit.SECONDS);
            took[0] = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - at);
        });
        t.start();
        return t;
    }

    private static void assertRefused(SharedApp.Reply r) {
        assertEquals(503, r.status);
        assertEquals("close", r.header("Connection"));
        String body = r.text();
        assertTrue(body, body.startsWith("{\"error\":{\"code\":") && body.contains("\"requestId\""));
    }

    private void awaitStopping() throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!adapter.stopping() && System.nanoTime() < end) {
            Thread.sleep(5);
        }
        assertTrue("stop did not begin", adapter.stopping());
    }

    // FR-SVL-006 AC-1 SDS-AC-1: a new request is refused at once and stop returns as soon as the slow one finishes
    @Test
    public void sdsAc1_FR_SVL_006_AC1_refusesAtOnceAndReturnsWhenTheSlowRequestEnds() throws Exception {
        start();
        Thread[] slowThread = new Thread[1];
        AtomicReference<SharedApp.Reply> slowReply = slow(slowThread);
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        long[] took = {-1};
        Thread stopper = stopAsync(2, took);
        awaitStopping();
        long at = System.nanoTime();
        assertRefused(SharedApp.http(container.port(), "GET", "/fast", null));
        assertTrue("503 not at once", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - at) < 1000);
        assertTrue("stop returned while a request was in flight", stopper.isAlive());
        Thread.sleep(300);
        proceed.countDown();
        stopper.join(5000);
        slowThread[0].join(5000);
        assertFalse(stopper.isAlive());
        assertTrue("stop waited " + took[0] + " ms", took[0] >= 250 && took[0] < 1900);
        assertEquals(200, slowReply.get().status);
        assertEquals("done", slowReply.get().text());
    }

    // FR-SVL-006 AC-1 SDS-AC-1: a request that outlasts the deadline does not hold stop past deadline plus 1 s
    @Test
    public void sdsAc1_FR_SVL_006_AC1_stopReturnsByTheDeadlinePlusOneSecond() throws Exception {
        start();
        Thread[] slowThread = new Thread[1];
        slow(slowThread);
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        long[] took = {-1};
        Thread stopper = stopAsync(1, took);
        stopper.join(5000);
        assertFalse(stopper.isAlive());
        assertTrue("stop took " + took[0] + " ms", took[0] >= 900 && took[0] <= 2000);
        assertTrue("the slow request is not interrupted", slowThread[0].isAlive());
        proceed.countDown();
        slowThread[0].join(5000);
    }

    // FR-SVL-006 AC-4 SDS-AC-4: release detaches the handler, so later requests are answered 503
    @Test
    public void sdsAc4_FR_SVL_006_AC4_releaseDetachesTheHandler() throws Exception {
        start();
        assertEquals(200, SharedApp.http(container.port(), "GET", "/fast", null).status);
        adapter.release();
        assertNull(adapter.handler());
        assertRefused(SharedApp.http(container.port(), "GET", "/fast", null));
    }

    // FR-SVL-006 AC-4 SDS-AC-4: stop and release are idempotent
    @Test
    public void sdsAc4_FR_SVL_006_AC4_stopAndReleaseAreIdempotent() throws Exception {
        start();
        adapter.stop(1, TimeUnit.SECONDS);
        adapter.stop(1, TimeUnit.SECONDS);
        adapter.release();
        adapter.release();
        assertRefused(SharedApp.http(container.port(), "GET", "/fast", null));
        app.shutdown(1, TimeUnit.SECONDS);
        app = null;
    }

    // FR-SVL-006 AC-4 SDS-AC-4: stop before bind is harmless
    @Test
    public void sdsAc4_FR_SVL_006_AC4_stopBeforeBindIsHarmless() {
        ServletAdapter a = new ServletAdapter();
        long at = System.nanoTime();
        a.stop(1, TimeUnit.SECONDS);
        a.release();
        a.stop(1, TimeUnit.SECONDS);
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - at) < 500);
        assertNull(a.handler());
    }

    // FR-SVL-006 AC-4 SDS-AC-4: after shutdown no naruserve-servlet-sse-* thread is alive within 2 s
    @Test
    public void sdsAc4_FR_SVL_006_AC4_noSseThreadWithinTwoSecondsOfShutdown() throws Exception {
        start();
        SseProbe p = SseProbe.open(container.port(), "/events");
        probes.add(p);
        p.awaitPayload("data: 1");
        long seen = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (ServletSseLimitTest.sseThreads(java.util.Collections.<Thread>emptySet()).isEmpty()
                && System.nanoTime() < seen) {
            Thread.sleep(10);
        }
        assertFalse("the stream runs on an SSE thread", ServletSseLimitTest.sseThreads(
                java.util.Collections.<Thread>emptySet()).isEmpty());
        app.shutdown(1, TimeUnit.SECONDS);
        app = null;
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        List<Thread> left = ServletSseLimitTest.sseThreads(java.util.Collections.<Thread>emptySet());
        while (!left.isEmpty() && System.nanoTime() < end) {
            Thread.sleep(20);
            left = ServletSseLimitTest.sseThreads(java.util.Collections.<Thread>emptySet());
        }
        assertEquals(new ArrayList<Thread>(), left);
    }
}
