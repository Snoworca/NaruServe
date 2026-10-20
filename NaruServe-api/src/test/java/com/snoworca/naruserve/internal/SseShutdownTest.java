package com.snoworca.naruserve.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.ConsoleLogger;
import com.snoworca.naruserve.spi.StreamDrain;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.RecordingExchange;
import com.snoworca.naruserve.testing.TestApp;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/**
 * The SSE closing participant and the registry's force close. SDS-AC-n cites
 * docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-11.sds.md.
 */
public class SseShutdownTest {

    private static final String NOTICE = "event: shutdown\ndata: shutdown\n\n";

    private static SseRegistry registry(int sendQueueSize) {
        DefaultSseOptions o = new DefaultSseOptions();
        o.sendQueueSize(sendQueueSize);
        return new SseRegistry(o, null, new SafeLogger(new ConsoleLogger()));
    }

    /** A stream opened through res.sse() on this thread, so it is registered in {@code registry}. */
    private static DefaultSseClient open(RecordingExchange ex, SseRegistry registry) {
        DefaultResponse res = new DefaultResponse(ex, false);
        res.sseRegistry(registry);
        return (DefaultSseClient) res.sse();
    }

    /** Sends {@code data} from a thread other than the handler thread, so the event is queued. */
    private static boolean sendFromAnotherThread(DefaultSseClient c, String data) throws InterruptedException {
        AtomicReference<Boolean> sent = new AtomicReference<>();
        Thread t = new Thread(() -> sent.set(c.send(data)));
        t.start();
        t.join(5000);
        return sent.get();
    }

    private static Thread drainAsync(StreamDrain d, OutputStream out) {
        Thread t = new Thread(() -> {
            try {
                d.drain(out);
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        });
        t.start();
        return t;
    }

    private static void waitFor(Thread t, Thread.State state) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (t.getState() != state && System.nanoTime() < end) {
            Thread.sleep(1);
        }
        assertEquals(state, t.getState());
    }

    // SDS-AC-9; FR-SSE-005 AC-1: a stream registering on another thread, blocked on the registry lock while
    // closeAll waits for it too, is either refused or in the snapshot; after the flag register is false
    @Test(timeout = 20000)
    public void sdsAc9_FR_SSE_005_AC1_aStreamBlockedOnTheRegistryLockIsRefusedOrInTheSnapshot() throws Exception {
        // The registrar blocked on the lock gets it before closeAll starts: it is registered and in the snapshot.
        SseRegistry first = registry(262144);
        DefaultSseClient early = new DefaultSseClient(
                new DefaultResponse(new RecordingExchange("GET", "/e"), false), first, Thread.currentThread(), "e");
        AtomicReference<Boolean> earlyRegistered = new AtomicReference<>();
        Thread blocked = new Thread(() -> earlyRegistered.set(first.register(early)));
        synchronized (first) {
            blocked.start();
            waitFor(blocked, Thread.State.BLOCKED);
        }
        blocked.join(5000);
        assertEquals(Boolean.TRUE, earlyRegistered.get());
        first.closeAll(System.nanoTime());
        assertFalse("a registered stream missed the snapshot", early.isOpen());
        assertEquals("shutdown", early.cause());
        // Racing for the lock with closeAll, the registrar is refused or in the snapshot, never left out.
        for (int round = 0; round < 20; round++) {
            SseRegistry registry = registry(262144);
            DefaultSseClient c = new DefaultSseClient(new DefaultResponse(new RecordingExchange("GET", "/e"), false),
                    registry, Thread.currentThread(), "r" + round);
            AtomicReference<Boolean> registered = new AtomicReference<>();
            Thread registrar = new Thread(() -> registered.set(registry.register(c)));
            Thread closer = new Thread(() -> registry.closeAll(System.nanoTime()));
            synchronized (registry) {
                registrar.start();
                waitFor(registrar, Thread.State.BLOCKED);
                closer.start();
                waitFor(closer, Thread.State.BLOCKED);
            }
            registrar.join(5000);
            closer.join(5000);
            assertEquals(SseRegistry.State.CLOSED, registry.state());
            if (registered.get()) {
                assertFalse("a registered stream missed the snapshot", c.isOpen());
                assertEquals("shutdown", c.cause());
            } else {
                assertTrue(c.isOpen());
            }
            DefaultSseClient late = new DefaultSseClient(
                    new DefaultResponse(new RecordingExchange("GET", "/e"), false), registry, Thread.currentThread(),
                    "late");
            assertFalse(registry.register(late));
        }
    }

    // SDS-AC-10; FR-SSE-005 AC-2: two queued events, then the notice, then the end of the body; onClose runs once
    @Test(timeout = 20000)
    public void sdsAc10_FR_SSE_005_AC2_twoQueuedEventsThenTheNoticeThenTheEndAndOnCloseOnce() throws Exception {
        SseRegistry registry = registry(262144);
        RecordingExchange ex = new RecordingExchange("GET", "/events");
        DefaultSseClient c = open(ex, registry);
        AtomicInteger closes = new AtomicInteger();
        c.onClose(closes::incrementAndGet);
        assertTrue(sendFromAnotherThread(c, "one"));
        assertTrue(sendFromAnotherThread(c, "two"));
        registry.closeAll(System.nanoTime());
        assertFalse(c.isOpen());
        assertEquals("shutdown", c.cause());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Thread d = drainAsync(ex.drain(), out);
        d.join(5000);
        assertFalse(d.isAlive());
        assertEquals("data: one\n\ndata: two\n\n" + NOTICE, new String(out.toByteArray(), StandardCharsets.UTF_8));
        assertEquals(1, ex.finishes.get());
        assertEquals(0, ex.aborts.get());
        assertEquals(1, closes.get());
        assertEquals(0, registry.size());
    }

    // SDS-AC-10; FR-SSE-005 AC-3: 10 bytes below sendQueueSize the notice is still queued past the limit and the
    // stream ends with cause shutdown, never overflow
    @Test(timeout = 20000)
    public void sdsAc10_FR_SSE_005_AC3_tenBytesBelowTheLimitTheNoticeStillArrivesWithoutOverflow() throws Exception {
        int limit = 100;
        char[] pad = new char[limit - 10 - "data: \n\n".length()];
        Arrays.fill(pad, 'x');
        String data = new String(pad);
        SseRegistry registry = registry(limit);
        RecordingExchange ex = new RecordingExchange("GET", "/events");
        DefaultSseClient c = open(ex, registry);
        AtomicInteger closes = new AtomicInteger();
        c.onClose(closes::incrementAndGet);
        assertTrue(sendFromAnotherThread(c, data));
        assertEquals(limit - 10, c.queuedBytes());
        registry.closeAll(System.nanoTime());
        assertEquals("shutdown", c.cause());
        assertEquals(limit - 10 + NOTICE.length(), c.queuedBytes());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Thread d = drainAsync(ex.drain(), out);
        d.join(5000);
        assertEquals("data: " + data + "\n\n" + NOTICE, new String(out.toByteArray(), StandardCharsets.UTF_8));
        assertEquals("shutdown", c.cause());
        assertEquals(1, ex.finishes.get());
        assertEquals(0, ex.aborts.get());
        assertEquals(1, closes.get());
    }

    // SDS-AC-11; FR-SSE-005 AC-4: with 50 open streams, one of them inside a write that never returns, closeAll
    // returns within 200 ms
    @Test(timeout = 20000)
    public void sdsAc11_FR_SSE_005_AC4_fiftyStreamsOneHeldWriteCloseAllReturnsWithin200Ms() throws Exception {
        SseRegistry registry = registry(262144);
        List<DefaultSseClient> clients = new ArrayList<>();
        List<RecordingExchange> exchanges = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            RecordingExchange ex = new RecordingExchange("GET", "/events");
            exchanges.add(ex);
            clients.add(open(ex, registry));
        }
        CountDownLatch inWrite = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        OutputStream held = new OutputStream() {
            @Override
            public void write(int b) {
                write(new byte[] {(byte) b}, 0, 1);
            }

            @Override
            public void write(byte[] b, int off, int len) {
                inWrite.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        assertTrue(sendFromAnotherThread(clients.get(0), "blocked"));
        Thread d = drainAsync(exchanges.get(0).drain(), held);
        try {
            assertTrue(inWrite.await(5, TimeUnit.SECONDS));
            assertEquals(50, registry.size());
            long t0 = System.nanoTime();
            registry.closeAll(System.nanoTime() + TimeUnit.SECONDS.toNanos(30));
            long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            assertTrue("closeAll took " + ms + " ms", ms < 200);
            for (DefaultSseClient c : clients) {
                assertFalse(c.isOpen());
                assertEquals("shutdown", c.cause());
            }
            assertTrue("closeAll waited for the held drain", d.isAlive());
        } finally {
            release.countDown();
        }
        d.join(5000);
        assertFalse(d.isAlive());
    }

    // SDS-AC-12; FR-SSE-005 AC-6: terminate clears the queue of every stream left and the registry
    @Test(timeout = 20000)
    public void sdsAc12_FR_SSE_005_AC6_terminateLeavesAnEmptyRegistryAndNoQueuedEvent() throws Exception {
        SseRegistry registry = registry(262144);
        List<DefaultSseClient> clients = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            DefaultSseClient c = open(new RecordingExchange("GET", "/events"), registry);
            assertTrue(sendFromAnotherThread(c, "queued " + i));
            clients.add(c);
        }
        registry.closeAll(System.nanoTime());
        assertEquals(3, registry.size()); // closeAll ends the streams but leaves them to their drains
        for (DefaultSseClient c : clients) {
            assertTrue(c.queuedBytes() > 0);
        }
        registry.terminate();
        assertEquals(0, registry.size());
        for (DefaultSseClient c : clients) {
            assertEquals(0, c.queuedBytes());
            assertFalse(c.isOpen());
            assertEquals("shutdown", c.cause());
        }
    }

    // SDS-AC-12; FR-SSE-005 AC-6: after shutdown returned the app's registry is empty and holds no queued event
    @Test(timeout = 20000)
    public void sdsAc12_FR_SSE_005_AC6_afterShutdownTheAppRegistryIsEmpty() throws Exception {
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        TestApp t = TestApp.create(adapter);
        DefaultRestApp app = (DefaultRestApp) t.app();
        AtomicReference<DefaultSseClient> client = new AtomicReference<>();
        CountDownLatch opened = new CountDownLatch(1);
        app.get("/events", (req, res) -> {
            client.set((DefaultSseClient) res.sse());
            opened.countDown();
        });
        t.start();
        Thread request = new Thread(() -> t.send("GET", "/events"));
        request.start();
        assertTrue(opened.await(5, TimeUnit.SECONDS));
        assertEquals(1, app.sseRegistry().size());
        app.shutdown(2, TimeUnit.SECONDS);
        request.join(5000);
        assertFalse("the request did not end", request.isAlive());
        assertEquals(0, app.sseRegistry().size());
        assertEquals(0, client.get().queuedBytes());
        assertEquals("shutdown", client.get().cause());
    }

    @SuppressWarnings("unchecked")
    private static List<Runnable> participants(DefaultRestApp app, ShutdownParticipants.Phase phase)
            throws Exception {
        Field f = ShutdownParticipants.class.getDeclaredField("participants");
        f.setAccessible(true);
        Map<ShutdownParticipants.Phase, List<ShutdownParticipants.DeadlineParticipant>> map =
                (Map<ShutdownParticipants.Phase, List<ShutdownParticipants.DeadlineParticipant>>)
                        f.get(app.shutdownParticipants());
        List<Runnable> out = new ArrayList<>();
        List<ShutdownParticipants.DeadlineParticipant> list = map.get(phase);
        if (list != null) {
            for (ShutdownParticipants.DeadlineParticipant p : list) {
                out.add(() -> p.run(System.nanoTime())); // FR-APP-020 AC-1: the list holds deadline participants
            }
        }
        return out;
    }

    // SDS-AC-8 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-12.sds.md; FR-APP-020 AC-3: with the flag set
    // between two registrations the first stream is in the snapshot, and res.sse() after the flag still starts the
    // event stream: the client gets the headers, the closing notice and the end of the body, onClose runs once and
    // send returns false
    @Test(timeout = 20000)
    public void wave7SdsAc8_FR_APP_020_AC3_sseAfterTheFlagGetsTheNoticeAndEndsAtOnce() throws Exception {
        SseRegistry registry = registry(262144);
        RecordingExchange first = new RecordingExchange("GET", "/events");
        DefaultSseClient before = open(first, registry);
        registry.closeAll(System.nanoTime());
        assertEquals("shutdown", before.cause());
        RecordingExchange ex = new RecordingExchange("GET", "/events");
        DefaultSseClient late = open(ex, registry);
        AtomicInteger closes = new AtomicInteger();
        late.onClose(closes::incrementAndGet);
        assertFalse(late.isOpen());
        assertEquals("shutdown", late.cause());
        assertFalse(late.send("after"));
        assertFalse(sendFromAnotherThread(late, "after"));
        assertEquals(200, ex.status);
        assertNotNull(ex.drain());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Thread d = drainAsync(ex.drain(), out);
        d.join(5000);
        assertFalse(d.isAlive());
        assertEquals(NOTICE, new String(out.toByteArray(), StandardCharsets.UTF_8));
        assertEquals(1, ex.finishes.get());
        assertEquals(0, ex.aborts.get());
        assertEquals(1, closes.get());
        assertEquals(1, registry.size()); // only the stream registered before the flag; its drain never ran here
    }

    // SDS-AC-13; FR-SSE-005 AC-1: freeze twice registers the SSE participants once; the SSE closeAll is the first
    // before-wait participant, ahead of the WebSocket one, and terminate is a force-close participant
    @Test(timeout = 20000)
    public void sdsAc13_FR_SSE_005_AC1_freezeRegistersOnceAndTheSseCloseAllRunsBeforeTheWebSocketOne()
            throws Exception {
        DefaultRestApp app = (DefaultRestApp) TestApp.create(new InMemoryHttpAdapter()).app();
        int beforeWait = participants(app, ShutdownParticipants.Phase.BEFORE_WAIT).size();
        int forceClose = participants(app, ShutdownParticipants.Phase.FORCE_CLOSE).size();
        app.freeze();
        app.freeze();
        List<Runnable> before = participants(app, ShutdownParticipants.Phase.BEFORE_WAIT);
        List<Runnable> force = participants(app, ShutdownParticipants.Phase.FORCE_CLOSE);
        assertEquals("SSE and WebSocket closeAll", beforeWait + 2, before.size());
        assertEquals("SSE and WebSocket terminate", forceClose + 2, force.size());
        SseRegistry sse = app.sseRegistry();
        assertNotNull(sse);
        assertEquals(SseRegistry.State.OPEN, sse.state());
        DefaultSseClient left = open(new RecordingExchange("GET", "/events"), sse);
        assertTrue(sendFromAnotherThread(left, "queued"));
        before.get(beforeWait).run();
        assertEquals(SseRegistry.State.CLOSED, sse.state());
        assertEquals("shutdown", left.cause());
        assertEquals("the WebSocket closeAll ran first", WebSocketRegistry.State.OPEN,
                app.webSocketRegistry().state());
        DefaultSseClient c = new DefaultSseClient(new DefaultResponse(new RecordingExchange("GET", "/e"), false),
                sse, Thread.currentThread(), "x");
        assertFalse(sse.register(c));
        assertNull(c.cause());
        assertEquals(1, sse.size());
        force.get(forceClose + 1).run(); // after the WebSocket terminate
        assertEquals(0, sse.size());
        assertEquals(0, left.queuedBytes());
    }
}
