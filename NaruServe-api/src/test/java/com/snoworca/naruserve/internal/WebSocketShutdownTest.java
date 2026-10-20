package com.snoworca.naruserve.internal;

import static com.snoworca.naruserve.internal.WebSocketRegistryTest.closeAllAsync;
import static com.snoworca.naruserve.internal.WebSocketRegistryTest.eventually;
import static com.snoworca.naruserve.internal.WebSocketRegistryTest.holdingHook;
import static com.snoworca.naruserve.internal.WebSocketRegistryTest.upgradeRequest;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.WebSocketSession;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.InMemoryWebSocketChannel;
import com.snoworca.naruserve.testing.TestApp;
import java.util.Collections;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.After;
import org.junit.Test;

/** The WebSocket closing participant. SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-7.sds.md. */
public class WebSocketShutdownTest {

    private final InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
    private final TestApp t = TestApp.create(adapter);
    private final DefaultRestApp app = (DefaultRestApp) t.app();
    private final BlockingQueue<WebSocketSession> handled = new LinkedBlockingQueue<>();
    private CountDownLatch heldWrite = new CountDownLatch(0);

    @After
    public void stop() {
        heldWrite.countDown();
        adapter.beforeOpen(null);
        app.shutdown(2, TimeUnit.SECONDS);
    }

    private WebSocketRegistry start() {
        app.ws("/ws", (req, ws) -> {
            handled.add(ws);
            ws.onText(text -> {
                ws.sendText("first");
                for (int i = 0; i < 50; i++) {
                    ws.sendText("m" + i);
                }
            });
        });
        t.start();
        return app.webSocketRegistry();
    }

    /** What one closeAll over a reserved session left behind, for the two tests of FR-WS-010 AC-1. */
    private static final class ReservedRun {
        InMemoryWebSocketChannel b;
        boolean aliveAtRelease;
        long started;
        long released;
        long ended;
    }

    /**
     * A session {@code b} is reserved (held before onOpen) when closeAll starts; it is released 250 ms into the 1 s
     * close wait. With {@code opened} the opened session and the next upgrade are checked on the way, which costs time
     * the timing test must not spend before the release. Ends once closeAll returned and both upgrades answered.
     */
    private ReservedRun closeAllOverReservedSession(boolean opened) throws Exception {
        WebSocketRegistry registry = start();
        Future<ClientResponse> fa = adapter.submit(upgradeRequest());
        InMemoryWebSocketChannel a = adapter.nextChannel(5, TimeUnit.SECONDS);
        handled.poll(5, TimeUnit.SECONDS);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        adapter.beforeOpen(holdingHook(entered, release));
        Future<ClientResponse> fb = adapter.submit(upgradeRequest());
        ReservedRun run = new ReservedRun();
        run.b = adapter.nextChannel(5, TimeUnit.SECONDS);
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        adapter.beforeOpen(null);
        assertEquals(2, registry.size());
        run.started = System.nanoTime();
        long started = run.started;
        AtomicLong ended = new AtomicLong();
        Thread closer = new Thread(() -> {
            registry.closeAll(started + TimeUnit.SECONDS.toNanos(5));
            ended.set(System.nanoTime());
        }, "test-close-all");
        closer.start();
        try {
            assertTrue(eventually(() -> registry.state() == WebSocketRegistry.State.CLOSING));
            assertTrue("the close wait ended at once", closer.isAlive());
            if (opened) {
                assertTrue("opened session got no close", eventually(() -> a.closeCode() == 1001));
                assertEquals("server shutdown", a.closeReason());
                WebSocketRegistryTest.assert503Close(adapter.send(upgradeRequest()));
            }
            // release 250 ms into the 1 s close wait: late enough for a wait that skipped the reserved session to
            // have ended, early enough to tell a wake on its close from the 1 s timeout
            long left = started + TimeUnit.MILLISECONDS.toNanos(250) - System.nanoTime();
            if (left > 0) {
                TimeUnit.NANOSECONDS.sleep(left);
            }
        } finally {
            run.aliveAtRelease = closer.isAlive();
            run.released = System.nanoTime();
            release.countDown();
        }
        closer.join(5000);
        assertFalse(closer.isAlive());
        run.ended = ended.get();
        fa.get(5, TimeUnit.SECONDS);
        fb.get(5, TimeUnit.SECONDS);
        return run;
    }

    // SDS-AC-6; FR-WS-010 AC-1: a session reserved before the flag is in the snapshot (the close wait waits for it,
    // an opened one gets 1001) and an upgrade after the flag is refused
    @Test(timeout = 20000)
    public void sdsAc6_FR_WS_010_AC1_sessionReservedBeforeTheFlagIsInTheSnapshotAndTheNextReserveFails()
            throws Exception {
        ReservedRun run = closeAllOverReservedSession(true);
        // b opened while the wait still ran (CLOSING: close 1001, no abort); an ended wait would have aborted it
        assertEquals(1001, run.b.closeCode());
        assertFalse("the reserved session was aborted", run.b.aborted());
        assertNull("the late session's handler ran", handled.poll(100, TimeUnit.MILLISECONDS));
    }

    // SDS-AC-6; FR-WS-010 AC-1, timing half: the close wait ends on the reserved session's close, not on its 1 s
    // timeout. Released 250 ms in, with nothing else in between, so a host too slow to tell the two apart fails here
    // (release after 600 ms) instead of passing without the check.
    @Test(timeout = 20000)
    public void sdsAc6_FR_WS_010_AC1_closeWaitEndsOnTheReservedSessionsCloseNotOnTheTimeout() throws Exception {
        ReservedRun run = closeAllOverReservedSession(false);
        long releaseMs = TimeUnit.NANOSECONDS.toMillis(run.released - run.started);
        assertTrue("the release came " + releaseMs + " ms after closeAll started, too late to tell a wake from the "
                + "1 s timeout", releaseMs <= 600);
        assertTrue("the close wait did not wait for the reserved session", run.aliveAtRelease);
        long afterRelease = TimeUnit.NANOSECONDS.toMillis(run.ended - run.released);
        long total = TimeUnit.NANOSECONDS.toMillis(run.ended - run.started);
        assertTrue("the wait did not end on the reserved session's close: " + afterRelease + " ms after release",
                afterRelease < 300);
        assertTrue("the wait ran into the 1 s timeout: ended " + total + " ms after closeAll started", total < 900);
    }

    // SDS-AC-7; FR-WS-010 AC-3: 50 messages queued behind a blocked write are never sent once the participant
    // started, and a later sendText returns normally and sends nothing
    @Test(timeout = 20000)
    public void sdsAc7_FR_WS_010_AC3_fiftyQueuedBehindAHeldWriteAreNeverSent() throws Exception {
        WebSocketRegistry registry = start();
        Future<ClientResponse> f = adapter.submit(upgradeRequest());
        InMemoryWebSocketChannel ch = adapter.nextChannel(5, TimeUnit.SECONDS);
        DefaultWebSocketSession s = (DefaultWebSocketSession) handled.poll(5, TimeUnit.SECONDS);
        heldWrite = ch.hold("sendText");
        int bytes = "first".length();
        for (int i = 0; i < 50; i++) {
            bytes += ("m" + i).length();
        }
        int total = bytes;
        ch.clientText("go");
        assertTrue("50 messages not queued", eventually(() -> s.sender().queuedBytes(s) == total));
        Thread closer = closeAllAsync(registry, 5000);
        assertTrue(eventually(() -> registry.state() == WebSocketRegistry.State.CLOSING));
        assertTrue("queue not discarded", eventually(() -> s.sender().queuedBytes(s) == "first".length()));
        s.sendText("late"); // returns normally
        heldWrite.countDown();
        closer.join(5000);
        assertFalse(closer.isAlive());
        f.get(5, TimeUnit.SECONDS);
        assertEquals(Collections.<Object>singletonList("first"), ch.sent());
        assertEquals(1001, ch.closeCode());
    }

    // SDS-AC-8; FR-WS-010 AC-5: with a 300 ms shutdown timeout and one session whose close never returns, the close
    // wait ends within 300 ms plus 200 ms of slack and the session is aborted
    @Test(timeout = 20000)
    public void sdsAc8_FR_WS_010_AC5_threeHundredMillisecondShutdownEndsTheCloseWaitWithin500Ms() throws Exception {
        start();
        AtomicLong afterCloseAll = new AtomicLong();
        app.shutdownParticipants().add(ShutdownParticipants.Phase.BEFORE_WAIT,
                () -> afterCloseAll.set(System.nanoTime()));
        Future<ClientResponse> f = adapter.submit(upgradeRequest());
        InMemoryWebSocketChannel ch = adapter.nextChannel(5, TimeUnit.SECONDS);
        handled.poll(5, TimeUnit.SECONDS);
        heldWrite = ch.hold("close");
        long start = System.nanoTime();
        app.shutdown(300, TimeUnit.MILLISECONDS);
        long ms = TimeUnit.NANOSECONDS.toMillis(afterCloseAll.get() - start);
        assertTrue("close wait took " + ms + " ms", ms <= 500);
        assertTrue("close wait ended early, after " + ms + " ms", ms >= 250);
        assertTrue("held session not aborted", ch.aborted());
        heldWrite.countDown();
        f.get(5, TimeUnit.SECONDS);
    }

    // SDS-AC-10; FR-WS-010 AC-6: a reserved session opening during the close wait sends close 1001 as its only task
    // and the same wait waits for it; it is not aborted when that close returned
    @Test(timeout = 20000)
    public void sdsAc10_FR_WS_010_AC6_sessionOpeningMidWaitIsWaitedForAndNotAbortedWhenItsCloseReturned()
            throws Exception {
        WebSocketRegistry registry = start();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        adapter.beforeOpen(holdingHook(entered, release));
        Future<ClientResponse> f = adapter.submit(upgradeRequest());
        InMemoryWebSocketChannel ch = adapter.nextChannel(5, TimeUnit.SECONDS);
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        Thread closer = closeAllAsync(registry, 5000);
        assertTrue(eventually(() -> registry.state() == WebSocketRegistry.State.CLOSING));
        long released = System.nanoTime();
        release.countDown();
        closer.join(5000);
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - released);
        assertTrue("the wait ran out instead of ending on the close: " + ms + " ms", ms < 900);
        assertEquals(1001, ch.closeCode());
        assertEquals("server shutdown", ch.closeReason());
        assertFalse("aborted although its close returned", ch.aborted());
        assertTrue(ch.sent().isEmpty());
        f.get(5, TimeUnit.SECONDS);
        assertNull(handled.poll(100, TimeUnit.MILLISECONDS));
    }

    // SDS-AC-10; FR-WS-010 AC-6: a session opening mid-wait whose close does not return by the end of the wait is
    // aborted then
    @Test(timeout = 20000)
    public void sdsAc10_FR_WS_010_AC6_sessionOpeningMidWaitWithAHeldCloseIsAbortedAtTheEnd() throws Exception {
        WebSocketRegistry registry = start();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        adapter.beforeOpen(holdingHook(entered, release));
        Future<ClientResponse> f = adapter.submit(upgradeRequest());
        InMemoryWebSocketChannel ch = adapter.nextChannel(5, TimeUnit.SECONDS);
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        heldWrite = ch.hold("close");
        Thread closer = closeAllAsync(registry, 5000);
        assertTrue(eventually(() -> registry.state() == WebSocketRegistry.State.CLOSING));
        release.countDown();
        closer.join(5000);
        assertFalse(closer.isAlive());
        assertTrue("held session not aborted", ch.aborted());
        assertEquals("the held close reached the channel", -1, ch.closeCode());
        heldWrite.countDown();
        f.get(5, TimeUnit.SECONDS);
        assertNull(handled.poll(100, TimeUnit.MILLISECONDS));
    }

    // SDS-AC-11; FR-WS-010 AC-7: a reserved session opening after the close wait ended is aborted within 100 ms of
    // its onOpen with no close frame, and its handler never runs
    @Test(timeout = 20000)
    public void sdsAc11_FR_WS_010_AC7_sessionOpeningAfterTheWaitIsAbortedWithoutAClose() throws Exception {
        WebSocketRegistry registry = start();
        AtomicLong opened = new AtomicLong();
        CountDownLatch entered = new CountDownLatch(1);
        adapter.beforeOpen(() -> {
            entered.countDown();
            try {
                eventually(() -> registry.state() == WebSocketRegistry.State.ENDED);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            opened.set(System.nanoTime());
        });
        Future<ClientResponse> f = adapter.submit(upgradeRequest());
        InMemoryWebSocketChannel ch = adapter.nextChannel(5, TimeUnit.SECONDS);
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        Thread closer = closeAllAsync(registry, 100);
        closer.join(5000);
        assertEquals(WebSocketRegistry.State.ENDED, registry.state());
        assertTrue("not aborted", eventually(ch::aborted));
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - opened.get());
        assertTrue("aborted " + ms + " ms after onOpen", ms <= 100);
        assertEquals("a close frame was sent", -1, ch.closeCode());
        assertTrue(ch.sent().isEmpty());
        f.get(5, TimeUnit.SECONDS);
        assertNull("the ws handler ran", handled.poll(100, TimeUnit.MILLISECONDS));
        assertEquals(0, registry.size());
    }

    // SDS-AC-13; FR-WS-010 AC-1: freeze builds the registry once; closeAll runs in BEFORE_WAIT and terminate in
    // FORCE_CLOSE, so after shutdown the sender executor has terminated and the registry is empty
    @Test(timeout = 20000)
    public void sdsAc13_FR_WS_010_AC1_freezeTwiceRegistersOnceAndParticipantsRunInTheirPhases() throws Exception {
        app.ws("/ws", (req, ws) -> handled.add(ws));
        app.freeze();
        WebSocketRegistry registry = app.webSocketRegistry();
        app.freeze();
        assertSame(registry, app.webSocketRegistry());
        t.start();
        Future<ClientResponse> f = adapter.submit(upgradeRequest());
        adapter.nextChannel(5, TimeUnit.SECONDS);
        DefaultWebSocketSession s = (DefaultWebSocketSession) handled.poll(5, TimeUnit.SECONDS);
        AtomicBoolean beforeWaitSawEnded = new AtomicBoolean();
        AtomicBoolean beforeWaitSawLiveSender = new AtomicBoolean();
        AtomicBoolean forceCloseSawTerminated = new AtomicBoolean();
        app.shutdownParticipants().add(ShutdownParticipants.Phase.BEFORE_WAIT, () -> {
            beforeWaitSawEnded.set(registry.state() == WebSocketRegistry.State.ENDED);
            beforeWaitSawLiveSender.set(!s.sender().executor().isShutdown());
        });
        app.shutdownParticipants().add(ShutdownParticipants.Phase.FORCE_CLOSE,
                () -> forceCloseSawTerminated.set(s.sender().executor().isTerminated()));
        app.shutdown(2, TimeUnit.SECONDS);
        f.get(5, TimeUnit.SECONDS);
        assertTrue("closeAll did not run before the later BEFORE_WAIT participant", beforeWaitSawEnded.get());
        assertTrue("the sender stopped before the adapter stop", beforeWaitSawLiveSender.get());
        assertTrue("terminate did not run before the later FORCE_CLOSE participant", forceCloseSawTerminated.get());
        assertEquals(0, registry.size());
    }
}
