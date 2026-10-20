package com.snoworca.naruserve.servlet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.HttpException;
import com.snoworca.naruserve.SseClient;
import com.snoworca.naruserve.testing.LiveThreads;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/**
 * The Servlet adapter's own SSE counter and executor. SDS-AC-n cites
 * docs/sds/2026-10-19.naruserve.naruserve-v1-wave-8-7.sds.md.
 */
public class ServletSseLimitTest extends SseJettyTest {

    /** SSE threads left alive by earlier tests in this JVM, which this test does not count. */
    final java.util.Set<Thread> earlier = new java.util.HashSet<>(sseThreads(java.util.Collections.emptySet()));

    /** The live threads named {@code naruserve-servlet-sse-*} that are not in {@code skip}. */
    static List<Thread> sseThreads(java.util.Set<Thread> skip) {
        List<Thread> out = LiveThreads.withPrefix("naruserve-servlet-sse-");
        out.removeAll(skip);
        return out;
    }

    SseProbe open(int port) throws IOException {
        SseProbe p = SseProbe.open(port, "/events");
        probes.add(p);
        return p;
    }

    /** Opens streams until one is answered 200 or 10 s passed. */
    SseProbe openAccepted(int port) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < end) {
            SseProbe p = open(port);
            if (p.status() == 200) {
                return p;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("no stream accepted");
    }

    // SDS-AC-5; FR-SVL-005 AC-1
    @Test(timeout = 60000)
    public void sdsAc5_FR_SVL_005_AC1_aThirdStreamGets503UnstartedAndTheSlotIsFreedOnEndIoExceptionAndAbort()
            throws Exception {
        AtomicReference<String> refused = new AtomicReference<>();
        jetty(a -> {
            a.server(o -> o.maxSseStreams(2));
            a.sse(o -> o.heartbeatInterval(1, TimeUnit.SECONDS));
            a.get("/events", (req, res) -> {
                try {
                    opened.add(res.sse());
                } catch (HttpException e) {
                    refused.set(e.status() + " " + res.isCommitted());
                    throw e;
                }
            });
        });
        int port = container.port();
        assertEquals(200, open(port).status());
        assertEquals(200, open(port).status());
        SseClient first = next();
        next();
        SharedApp.Reply third = SharedApp.http(port, "GET", "/events", null);
        assertEquals(503, third.status);
        assertTrue(third.text(), third.isStandardError("SERVICE_UNAVAILABLE"));
        assertEquals("503 false", refused.get());

        // the end of a stream by close()
        first.close();
        openAccepted(port);
        next();
        // the end by an IOException: the client of the second stream closes its socket
        probes.get(1).close();
        openAccepted(port);
        next();
    }

    // SDS-AC-5; FR-SVL-005 AC-1: abort() on an SSE exchange frees its slot
    @Test(timeout = 60000)
    public void sdsAc5_FR_SVL_005_AC1_abortOnAnSseExchangeFreesItsSlot() throws Exception {
        BlockingQueue<ServletExchange> exchanges = new LinkedBlockingQueue<>();
        ServletAdapter adapter = new ServletAdapter();
        app = SharedApp.listen(adapter, log, a -> {
            a.server(o -> o.maxSseStreams(1));
            // after the abort the drain's next write, at the latest the heartbeat, fails at once and ends it
            a.sse(o -> o.heartbeatInterval(1, TimeUnit.SECONDS));
            a.get("/events", (req, res) -> opened.add(res.sse()));
        });
        // NaruServeServlet's own sequence, keeping each exchange so the test can abort it.
        container.servlet(SharedApp.capturing(adapter, exchanges), "/*");
        int port = container.port();
        assertEquals(200, open(port).status());
        next();
        assertEquals(503, SharedApp.http(port, "GET", "/events", null).status);
        exchanges.poll(5, TimeUnit.SECONDS).abort();
        exchanges.clear();
        openAccepted(port);
        next();
    }

    // SDS-AC-6; FR-SVL-005 AC-2, CON-SVL-008 AC-1
    @Test(timeout = 60000)
    public void sdsAc6_FR_SVL_005_AC2_theDrainRunsOnABoundedDaemonExecutorAfterServiceReturned() throws Exception {
        List<String> handlerThreads = new ArrayList<>();
        CountDownLatch serviceReturned = new CountDownLatch(2);
        container.filter((req, res, chain) -> {
            chain.doFilter(req, res);
            serviceReturned.countDown();
        });
        jetty(a -> {
            a.server(o -> o.maxSseStreams(2));
            a.get("/events", (req, res) -> {
                synchronized (handlerThreads) {
                    handlerThreads.add(Thread.currentThread().getName());
                }
                opened.add(res.sse());
            });
        });
        int port = container.port();
        open(port);
        open(port);
        SseClient a = next();
        SseClient b = next();
        assertTrue("service() returned while both streams are open", serviceReturned.await(10, TimeUnit.SECONDS));
        assertTrue(a.isOpen() && b.isOpen());
        assertTrue(a.send("after service"));
        assertTrue(b.send("after service"));
        probes.get(0).awaitPayload("data: after service\n\n");
        probes.get(1).awaitPayload("data: after service\n\n");

        List<Thread> drains = sseThreads(earlier);
        assertFalse("no drain thread", drains.isEmpty());
        assertTrue(drains.toString(), drains.size() <= 2);
        for (Thread t : drains) {
            assertTrue(t.getName(), t.getName().matches("naruserve-servlet-sse-\\d+"));
            assertTrue(t.getName() + " is a daemon", t.isDaemon());
            assertFalse(t.getName() + " ran a handler", handlerThreads.contains(t.getName()));
        }
        for (String h : handlerThreads) {
            assertFalse(h, h.startsWith("naruserve-servlet-sse-"));
        }
        // a second round of streams reuses the bound executor
        a.close();
        b.close();
        openAccepted(port);
        openAccepted(port);
        next();
        next();
        assertTrue(sseThreads(earlier).toString(), sseThreads(earlier).size() <= 2);
    }
}
