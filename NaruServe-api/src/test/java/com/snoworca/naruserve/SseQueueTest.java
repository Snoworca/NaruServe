package com.snoworca.naruserve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.testing.InMemoryEventStream;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.TestApp;
import java.util.ArrayList;
import java.util.Arrays;
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
 * The SSE outbound queue, drain loop and heartbeat over the in-memory adapter. SDS-AC-n cites
 * docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-10.sds.md.
 */
public class SseQueueTest {

    private final InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
    private final TestApp t = TestApp.create(adapter);
    private final AtomicReference<SseClient> client = new AtomicReference<>();
    private final CountDownLatch opened = new CountDownLatch(1);

    @After
    public void stop() {
        SseClient c = client.get();
        if (c != null) {
            c.close();
        }
        t.app().shutdown(2, TimeUnit.SECONDS);
    }

    /** Registers GET /events, whose handler opens the stream, hands the client over and returns. */
    private void openingRoute() {
        t.app().get("/events", (req, res) -> {
            client.set(res.sse());
            opened.countDown();
        });
    }

    private Future<ClientResponse> open() throws InterruptedException {
        t.start();
        Future<ClientResponse> f = adapter.submit(ClientRequest.of("GET", "/events"));
        assertTrue("the handler did not open the stream", opened.await(5, TimeUnit.SECONDS));
        return f;
    }

    /** A data-only event whose serialised form is exactly 100 bytes: "data: " + 92 chars + "\n\n". */
    private static String data92(char c) {
        char[] chars = new char[92];
        Arrays.fill(chars, c);
        return new String(chars);
    }

    private static void awaitWriter(InMemoryEventStream es) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (es.writerThreads().isEmpty()) {
            assertTrue("no write started", System.nanoTime() < deadline);
            Thread.sleep(5);
        }
    }

    // SDS-AC-2; FR-SSE-002 AC-3 (T25)
    @Test(timeout = 20000)
    public void sdsAc2_FR_SSE_002_AC3_anIdleStreamGetsAPingAfterEachHeartbeatInterval() throws Exception {
        t.app().sse(o -> o.heartbeatInterval(1, TimeUnit.SECONDS));
        AtomicLong openedAt = new AtomicLong();
        t.app().get("/events", (req, res) -> {
            client.set(res.sse());
            openedAt.set(System.nanoTime());
            opened.countDown();
        });
        Future<ClientResponse> f = open();
        InMemoryEventStream es = adapter.nextEventStream(5, TimeUnit.SECONDS);
        // timed by the write instants, not by when the waits wake, so a slow test thread cannot skew them
        es.awaitText(": ping\n\n: ping\n\n: ping\n\n", 5000);
        long first = TimeUnit.NANOSECONDS.toMillis(es.writtenAt(": ping\n\n") - openedAt.get());
        long gap1 = TimeUnit.NANOSECONDS.toMillis(es.writtenAt(": ping\n\n: ping\n\n") - es.writtenAt(": ping\n\n"));
        long gap2 = TimeUnit.NANOSECONDS.toMillis(es.writtenAt(": ping\n\n: ping\n\n: ping\n\n")
                - es.writtenAt(": ping\n\n: ping\n\n"));
        // FR-SSE-002 AC-3: the first ping within 1 s + 500 ms of the stream opening (nothing written since), each
        // later one after the next interval
        assertTrue("first ping " + first + " ms after the open", first <= 1500);
        assertTrue("ping gap " + gap1 + " ms", gap1 >= 900 && gap1 <= 1500);
        assertTrue("ping gap " + gap2 + " ms", gap2 >= 900 && gap2 <= 1500);
        client.get().close();
        ClientResponse r = f.get(5, TimeUnit.SECONDS);
        assertTrue(r.text(), r.text().startsWith(": ping\n\n: ping\n\n: ping\n\n"));
        assertFalse(r.abnormallyClosed());
    }

    // SDS-AC-2; FR-SSE-002 AC-3 (T25)
    @Test(timeout = 20000)
    public void sdsAc2_FR_SSE_002_AC3_anEventEvery300MsKeepsThePingAwayFor3Seconds() throws Exception {
        t.app().sse(o -> o.heartbeatInterval(1, TimeUnit.SECONDS));
        openingRoute();
        Future<ClientResponse> f = open();
        InMemoryEventStream es = adapter.nextEventStream(5, TimeUnit.SECONDS);
        SseClient c = client.get();
        for (int i = 0; i < 10; i++) {
            assertTrue(c.send("e" + i));
            Thread.sleep(300);
        }
        assertFalse(es.text(), es.text().contains(": ping"));
        c.close();
        assertFalse(f.get(5, TimeUnit.SECONDS).text().contains(": ping"));
    }

    // SDS-AC-3; FR-SSE-002 AC-4
    @Test(timeout = 20000)
    public void sdsAc3_FR_SSE_002_AC4_theSendThatOverflowsTheQueueReturnsFalseAndEndsTheStream() throws Exception {
        t.app().sse(o -> o.sendQueueSize(1024));
        AtomicInteger closes = new AtomicInteger();
        t.app().get("/events", (req, res) -> {
            SseClient c = res.sse();
            c.onClose(closes::incrementAndGet);
            client.set(c);
            opened.countDown();
        });
        Future<ClientResponse> f = open();
        InMemoryEventStream es = adapter.nextEventStream(5, TimeUnit.SECONDS);
        SseClient c = client.get();
        CountDownLatch release = es.hold();
        String held = "data: " + data92('h') + "\n\n";
        assertEquals(100, held.length());
        assertTrue(c.send(data92('h')));
        awaitWriter(es); // the held event left the queue and its write waits on the latch
        List<Boolean> results = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            results.add(c.send(data92((char) ('a' + i))));
        }
        assertEquals(Arrays.asList(true, true, true, true, true, true, true, true, true, true, false), results);
        assertFalse(c.isOpen());
        assertFalse(c.send("later"));
        release.countDown();
        ClientResponse r = f.get(5, TimeUnit.SECONDS);
        assertEquals(held, r.text());
        assertFalse("the body did not end normally", r.abnormallyClosed());
        assertEquals(1, closes.get());
        assertFalse(c.send("after the end"));
        assertEquals(1, closes.get());
    }

    // SDS-AC-5; FR-SSE-002 AC-6
    @Test(timeout = 20000)
    public void sdsAc5_FR_SSE_002_AC6_eventsQueuedByAnotherThreadPrecedeTheHandlerThreadsLaterSend() throws Exception {
        t.app().get("/events", (req, res) -> {
            SseClient c = res.sse();
            client.set(c);
            CountDownLatch bQueued = new CountDownLatch(1);
            Thread other = new Thread(() -> {
                c.send("A");
                c.send("B");
                bQueued.countDown();
            });
            other.start();
            if (!bQueued.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("B was not queued");
            }
            c.send("C");
            c.close();
            opened.countDown();
        });
        Future<ClientResponse> f = open();
        assertEquals("data: A\n\ndata: B\n\ndata: C\n\n", f.get(5, TimeUnit.SECONDS).text());
    }

    // SDS-AC-14; FR-SSE-002 AC-2
    @Test(timeout = 20000)
    public void sdsAc14_FR_SSE_002_AC2_aSendFromAnotherThreadIsWrittenByTheDrainOnTheAdaptersThread()
            throws Exception {
        openingRoute();
        Future<ClientResponse> f = open();
        InMemoryEventStream es = adapter.nextEventStream(5, TimeUnit.SECONDS);
        SseClient c = client.get();
        assertTrue(c.send("x"));
        es.awaitText("data: x\n\n", 5000);
        assertFalse(es.writerThreads().contains(Thread.currentThread()));
        assertEquals(1, es.writerThreads().size());
        assertNotNull(es.attached());
        assertSame(es.attached(), es.drained());
        c.close();
        assertEquals("data: x\n\n", f.get(5, TimeUnit.SECONDS).text());
    }
}
