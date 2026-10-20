package com.snoworca.naruserve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.testing.InMemoryEventStream;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.testing.StubJsonCodec;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Test;

/**
 * SSE stream lifetime over the in-memory adapter: close, write failures, onClose, HEAD and shutdown from onClose.
 * SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-10.sds.md.
 */
public class SseLifetimeTest {

    /** A service registered in onStart; java.time.Clock is API 26, so the test brings its own type. */
    static final class Clock {
    }

    private final InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
    private final RecordingLogger log = new RecordingLogger();
    private final RestApp app = RestApp.builder().httpAdapter(adapter).jsonCodec(new StubJsonCodec()).logger(log)
            .build();
    private final AtomicReference<SseClient> client = new AtomicReference<>();
    private final CountDownLatch opened = new CountDownLatch(1);
    private final AtomicInteger closes = new AtomicInteger();

    @After
    public void stop() {
        SseClient c = client.get();
        if (c != null) {
            c.close();
        }
        app.shutdown(2, TimeUnit.SECONDS);
    }

    private void openingRoute() {
        app.get("/events", (req, res) -> {
            SseClient c = res.sse();
            c.onClose(closes::incrementAndGet);
            client.set(c);
            opened.countDown();
        });
    }

    private Future<ClientResponse> open() throws InterruptedException {
        app.listen(0);
        Future<ClientResponse> f = adapter.submit(ClientRequest.of("GET", "/events"));
        assertTrue("the handler did not open the stream", opened.await(5, TimeUnit.SECONDS));
        return f;
    }

    // SDS-AC-8; FR-SSE-004 AC-3
    @Test(timeout = 20000)
    public void sdsAc8_FR_SSE_004_AC3_closeFromAnotherThreadWritesTheQueueEndsTheBodyAndRunsOnCloseOnce()
            throws Exception {
        openingRoute();
        Future<ClientResponse> f = open();
        InMemoryEventStream es = adapter.nextEventStream(5, TimeUnit.SECONDS);
        SseClient c = client.get();
        CountDownLatch release = es.hold();
        assertTrue(c.send("a"));
        assertTrue(c.send("b"));
        assertTrue(c.send("c"));
        Thread closer = new Thread(c::close);
        closer.start();
        closer.join(5000);
        assertFalse(c.isOpen());
        release.countDown();
        ClientResponse r = f.get(5, TimeUnit.SECONDS);
        assertEquals("data: a\n\ndata: b\n\ndata: c\n\n", r.text());
        assertFalse("the chunked body did not end normally", r.abnormallyClosed());
        assertEquals(1, closes.get());
        c.close();
        assertEquals(1, closes.get());
        assertFalse(c.send("late"));
    }

    // SDS-AC-9; FR-SSE-004 AC-5
    @Test(timeout = 20000)
    public void sdsAc9_FR_SSE_004_AC5_anIOExceptionOnTheThirdDrainWriteEndsTheStreamAndRunsOnCloseOnce()
            throws Exception {
        openingRoute();
        Future<ClientResponse> f = open();
        InMemoryEventStream es = adapter.nextEventStream(5, TimeUnit.SECONDS);
        SseClient c = client.get();
        es.failWrite(3, new IOException("peer gone"));
        assertTrue(c.send("1"));
        assertTrue(c.send("2"));
        c.send("3");
        ClientResponse r = f.get(5, TimeUnit.SECONDS);
        assertEquals("data: 1\n\ndata: 2\n\n", r.text());
        assertTrue(r.abnormallyClosed());
        assertEquals(1, closes.get());
        assertFalse(c.isOpen());
        assertFalse(c.send("4"));
        assertEquals(1, closes.get());
    }

    // SDS-AC-9; FR-SSE-004 AC-5
    @Test(timeout = 20000)
    public void sdsAc9_FR_SSE_004_AC5_aRuntimeExceptionFromADrainWriteEndsTheStreamTheSameWay() throws Exception {
        openingRoute();
        Future<ClientResponse> f = open();
        InMemoryEventStream es = adapter.nextEventStream(5, TimeUnit.SECONDS);
        SseClient c = client.get();
        es.failWrite(1, new IllegalStateException("broken"));
        c.send("1");
        ClientResponse r = f.get(5, TimeUnit.SECONDS);
        assertEquals("", r.text());
        assertTrue(r.abnormallyClosed());
        assertEquals(1, closes.get());
        assertFalse(c.send("2"));
    }

    // SDS-AC-10; FR-SSE-004 AC-4
    @Test(timeout = 20000)
    public void sdsAc10_FR_SSE_004_AC4_listenersRunOnceInOrderAThrowIsLoggedWithTheRidAndALateOneRunsAtOnce()
            throws Exception {
        List<String> order = Collections.synchronizedList(new ArrayList<String>());
        app.get("/events", (req, res) -> {
            SseClient c = res.sse();
            c.onClose(() -> order.add("first"));
            c.onClose(() -> {
                throw new IllegalStateException("boom");
            });
            c.onClose(() -> order.add("third"));
            client.set(c);
            opened.countDown();
        });
        Future<ClientResponse> f = open();
        SseClient c = client.get();
        c.close();
        f.get(5, TimeUnit.SECONDS);
        assertEquals(Arrays.asList("first", "third"), order);
        List<String> errors = new ArrayList<>();
        for (String line : new ArrayList<>(log.lines)) {
            if (line.startsWith("ERROR ")) {
                errors.add(line);
            }
        }
        assertEquals(errors.toString(), 1, errors.size());
        assertTrue(errors.get(0), errors.get(0).matches("ERROR sse \\S+ onClose listener failed boom"));
        Thread caller = Thread.currentThread();
        AtomicReference<Thread> ranOn = new AtomicReference<>();
        c.onClose(() -> {
            ranOn.set(Thread.currentThread());
            order.add("late");
        });
        assertSame(caller, ranOn.get());
        assertEquals(Arrays.asList("first", "third", "late"), order);
        c.close();
        assertEquals(Arrays.asList("first", "third", "late"), order);
    }

    // SDS-AC-11; FR-SSE-004 AC-6
    @Test(timeout = 20000)
    public void sdsAc11_FR_SSE_004_AC6_headOnAnSseRouteGetsTheHeadersNoBodyAndAnEndedClient() throws Exception {
        List<String> seen = new ArrayList<>();
        app.get("/events", (req, res) -> {
            SseClient c = res.sse();
            seen.add("send " + c.send("x"));
            seen.add("open " + c.isOpen());
            c.onClose(closes::incrementAndGet);
            seen.add("closes " + closes.get());
        });
        app.listen(0);
        ClientResponse r = adapter.send(ClientRequest.of("HEAD", "/events"));
        assertEquals(200, r.status());
        assertEquals("text/event-stream; charset=UTF-8", r.header("Content-Type"));
        assertEquals("no-cache, no-transform", r.header("Cache-Control"));
        assertEquals(0, r.body().length);
        assertFalse(r.abnormallyClosed());
        assertEquals(Arrays.asList("send false", "open false", "closes 1"), seen);
        assertEquals(1, closes.get());
        try {
            adapter.nextEventStream(100, TimeUnit.MILLISECONDS);
            fail("HEAD started an event stream");
        } catch (AssertionError expected) {
            // no eventStream call
        }
    }

    // SDS-AC-12; FR-SSE-004 AC-7, AC-8 (T41)
    @Test(timeout = 20000)
    public void sdsAc12_FR_SSE_004_AC7_AC8_shutdownFromOnCloseReturnsAtOnceAndTheAppTerminates() throws Exception {
        Clock clock = new Clock();
        app.onStart(ctx -> ctx.register(Clock.class, clock));
        AtomicReference<Boolean> sameClock = new AtomicReference<>();
        AtomicLong shutdownMillis = new AtomicLong(-1);
        CountDownLatch listenerDone = new CountDownLatch(1);
        app.get("/events", (req, res) -> {
            sameClock.set(req.service(Clock.class) == clock);
            SseClient c = res.sse();
            c.onClose(() -> {
                long t0 = System.nanoTime();
                app.shutdown(5, TimeUnit.SECONDS);
                shutdownMillis.set(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0));
                listenerDone.countDown();
            });
            c.send("x");
            c.close();
        });
        app.listen(0);
        Future<ClientResponse> f = adapter.submit(ClientRequest.of("GET", "/events"));
        assertTrue("onClose did not finish", listenerDone.await(10, TimeUnit.SECONDS));
        assertTrue("shutdown took " + shutdownMillis.get() + " ms", shutdownMillis.get() < 500);
        assertEquals("data: x\n\n", f.get(5, TimeUnit.SECONDS).text());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (app.state() != AppState.TERMINATED && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(AppState.TERMINATED, app.state());
        assertEquals(Boolean.TRUE, sameClock.get());
    }
}
