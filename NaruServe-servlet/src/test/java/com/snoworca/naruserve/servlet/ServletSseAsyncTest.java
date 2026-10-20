package com.snoworca.naruserve.servlet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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
import java.util.concurrent.atomic.AtomicInteger;
import javax.servlet.AsyncContext;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;

/**
 * SSE under AsyncContext: timeout 0 against a lowered container default, and one complete() per stream.
 * SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-8-7.sds.md.
 */
public class ServletSseAsyncTest {

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

    /**
     * Hands the chain a request whose {@code startAsync()} lowers the container default timeout to 1 s and returns a
     * context that counts its {@code complete()} calls; after the chain it records the async timeout in effect.
     */
    static final class AsyncProbe implements ContainerRule.Body {
        final List<Long> timeouts = Collections.synchronizedList(new ArrayList<Long>());
        final AtomicInteger completes = new AtomicInteger();

        @Override
        public void filter(ServletRequest req, ServletResponse res, FilterChain chain)
                throws IOException, ServletException {
            HttpServletRequestWrapper w = new HttpServletRequestWrapper((HttpServletRequest) req) {
                @Override
                public AsyncContext startAsync() {
                    AsyncContext real = super.startAsync();
                    real.setTimeout(1000); // the container default, lowered for the test
                    return RecordingRequest.delegating(AsyncContext.class, real, name -> {
                        if (name.equals("complete")) {
                            completes.incrementAndGet();
                        }
                    });
                }
            };
            chain.doFilter(w, res);
            if (w.isAsyncStarted()) {
                timeouts.add(w.getAsyncContext().getTimeout());
            }
        }
    }

    private SseClient next() throws InterruptedException {
        SseClient c = opened.poll(10, TimeUnit.SECONDS);
        assertNotNull("no stream opened", c);
        return c;
    }

    private static void await(AtomicInteger n, int value) throws InterruptedException {
        SharedApp.until(() -> n.get() >= value, 5, TimeUnit.SECONDS);
    }

    /** Waits until every slot is back and no SSE thread runs drain code of the adapter any more. */
    private static void awaitDrainEnded(ServletAdapter adapter) throws InterruptedException {
        if (!SharedApp.until(() -> adapter.pool().open() == 0 && !inDrain(), 5, TimeUnit.SECONDS)) {
            fail("the drain did not end");
        }
    }

    private static boolean inDrain() {
        for (Thread t : ServletSseLimitTest.sseThreads(Collections.<Thread>emptySet())) {
            for (StackTraceElement e : t.getStackTrace()) {
                if (e.getClassName().startsWith(ServletEventStream.class.getName())) {
                    return true;
                }
            }
        }
        return false;
    }

    // SDS-AC-7; FR-SVL-005 AC-3
    @Test(timeout = 60000)
    public void sdsAc7_FR_SVL_005_AC3_everyStreamHasTimeout0AndOutlivesA1sContainerDefault() throws Exception {
        AsyncProbe probe = new AsyncProbe();
        container.filter(probe);
        app = ServletSseLimitTest.jetty(container, log, a -> {
            a.sse(o -> o.heartbeatInterval(1, TimeUnit.SECONDS));
            a.get("/events", (req, res) -> opened.add(res.sse()));
        });
        int port = container.port();
        SseProbe first = SseProbe.open(port, "/events");
        probes.add(first);
        probes.add(SseProbe.open(port, "/events"));
        SseClient c = next();
        next();
        long t0 = System.nanoTime();
        Thread.sleep(2500);
        first.awaitPayload(": ping\n\n");
        long after = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        assertTrue("past 2.5 s: " + after, after >= 2500);
        int pings = first.payload().split(": ping\n\n", -1).length - 1;
        assertTrue("heartbeats after the 1 s default: " + first.payload(), pings >= 2);
        assertTrue("the container did not end the stream", c.isOpen());
        assertTrue(c.send("still here"));
        first.awaitPayload("data: still here\n\n");
        assertEquals(2, probe.timeouts.size());
        for (long t : probe.timeouts) {
            assertEquals(0L, t);
        }
    }

    // SDS-AC-9; FR-SVL-005 AC-5
    @Test(timeout = 60000)
    public void sdsAc9_FR_SVL_005_AC5_eachDrainEndCompletesOnceAndRunsOnCloseOnce() throws Exception {
        AsyncProbe probe = new AsyncProbe();
        container.filter(probe);
        AtomicInteger closes = new AtomicInteger();
        CountDownLatch handlerEnd = new CountDownLatch(1);
        app = ServletSseLimitTest.jetty(container, log, a -> {
            a.sse(o -> o.heartbeatInterval(1, TimeUnit.SECONDS));
            a.get("/events", (req, res) -> {
                SseClient c = res.sse();
                c.onClose(closes::incrementAndGet);
                opened.add(c);
            });
            // the core's own end report: the handler closes the stream before it returns
            a.get("/ended", (req, res) -> {
                SseClient c = res.sse();
                c.onClose(closes::incrementAndGet);
                c.send("bye");
                c.close();
                handlerEnd.countDown();
            });
        });
        int port = container.port();

        // close()
        SseProbe byClose = SseProbe.open(port, "/events");
        probes.add(byClose);
        next().close();
        assertTrue(byClose.awaitEnd(5000));
        await(probe.completes, 1);
        await(closes, 1);
        assertEquals(1, probe.completes.get());
        assertEquals(1, closes.get());

        // the client closes its socket
        SseProbe byClient = SseProbe.open(port, "/events");
        SseClient c = next();
        byClient.close();
        await(probe.completes, 2);
        await(closes, 2);
        assertEquals(2, probe.completes.get());
        assertEquals(2, closes.get());
        assertFalse(c.isOpen());

        // the core's end report
        SharedApp.Reply ended = SharedApp.http(port, "GET", "/ended", null);
        assertTrue(handlerEnd.await(5, TimeUnit.SECONDS));
        assertEquals("data: bye\n\n", ended.text());
        await(probe.completes, 3);
        await(closes, 3);
        assertEquals(3, probe.completes.get());
        assertEquals(3, closes.get());
    }

    // SDS-AC-9; FR-SVL-005 AC-5: abort() on an SSE exchange completes the AsyncContext once
    @Test(timeout = 60000)
    public void sdsAc9_FR_SVL_005_AC5_abortOnAnSseExchangeCompletesOnce() throws Exception {
        AsyncProbe probe = new AsyncProbe();
        container.filter(probe);
        BlockingQueue<ServletExchange> exchanges = new LinkedBlockingQueue<>();
        ServletAdapter adapter = new ServletAdapter();
        app = SharedApp.listen(adapter, log, a -> {
            a.sse(o -> o.heartbeatInterval(1, TimeUnit.SECONDS));
            a.get("/events", (req, res) -> opened.add(res.sse()));
        });
        container.servlet(SharedApp.capturing(adapter, exchanges), "/*");
        SseProbe p = SseProbe.open(container.port(), "/events");
        probes.add(p);
        SseClient c = next();
        ServletExchange ex = exchanges.poll(5, TimeUnit.SECONDS);
        ex.abort();
        ex.abort();
        assertTrue(p.awaitEnd(5000));
        await(probe.completes, 1);
        awaitDrainEnded(adapter); // the drain's own end comes after the abort and must not complete again
        assertEquals(1, probe.completes.get());
        SharedApp.until(() -> !c.isOpen(), 5, TimeUnit.SECONDS);
        assertFalse(c.isOpen());
    }
}
