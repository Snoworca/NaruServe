package com.snoworca.naruserve.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.Method;
import com.snoworca.naruserve.testing.RecordingExchange;
import com.snoworca.naruserve.spi.WebSocketChannel;
import com.snoworca.naruserve.testing.RecordingLogger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/** The app-wide sender. SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-4.sds.md. */
public class WebSocketSenderTest {

    private final WebSocketSender sender = new WebSocketSender();

    private DefaultWebSocketSession session() {
        RecordingExchange ex = new RecordingExchange("GET", "/ws");
        return new DefaultWebSocketSession(new DefaultRequest(ex, Method.GET, Collections.<String, String>emptyMap()),
                null, new SafeLogger(new RecordingLogger()), "0000000000000000", sender);
    }

    // FR-WS-005 AC-7 sender slot
    // SDS-AC-10; FR-WS-005 AC-5: two tasks of one session never overlap, keep submit order and run on a sender
    // thread, not the submitting one
    @Test(timeout = 20000)
    public void sdsAc10_FR_WS_005_AC5_tasksOfOneSessionRunOneAtATimeInSubmitOrder() throws Exception {
        DefaultWebSocketSession s = session();
        List<Integer> order = Collections.synchronizedList(new ArrayList<Integer>());
        AtomicInteger running = new AtomicInteger();
        AtomicBoolean overlapped = new AtomicBoolean();
        List<Thread> threads = Collections.synchronizedList(new ArrayList<Thread>());
        CountDownLatch done = new CountDownLatch(200);
        for (int i = 0; i < 200; i++) {
            int n = i;
            sender.submit(s, () -> {
                if (running.incrementAndGet() != 1) {
                    overlapped.set(true);
                }
                threads.add(Thread.currentThread());
                order.add(n);
                Thread.yield();
                running.decrementAndGet();
                done.countDown();
            });
        }
        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertFalse("two tasks of one session overlapped", overlapped.get());
        List<Integer> expected = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            expected.add(i);
        }
        assertEquals(expected, order);
        for (Thread t : threads) {
            assertNotEquals(Thread.currentThread(), t);
        }
    }

    // FR-WS-005 AC-7 sender slot
    // SDS-AC-10; FR-WS-005 AC-5: a write still running for one session never holds back another session's write
    @Test(timeout = 20000)
    public void sdsAc10_FR_WS_005_AC5_aStalledSessionNeverHoldsBackAnotherSession() throws Exception {
        DefaultWebSocketSession stalled = session();
        DefaultWebSocketSession other = session();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch otherRan = new CountDownLatch(1);
        CountDownLatch stalledSecond = new CountDownLatch(1);
        sender.submit(stalled, () -> {
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        sender.submit(stalled, stalledSecond::countDown);
        sender.submit(other, otherRan::countDown);
        assertTrue("other session's write waited for the stalled one", otherRan.await(5, TimeUnit.SECONDS));
        assertEquals(1, stalledSecond.getCount());
        release.countDown();
        assertTrue(stalledSecond.await(5, TimeUnit.SECONDS));
    }

    // FR-WS-005 AC-7 sender slot
    // SDS-AC-10; FR-WS-005 AC-5: a task that throws does not stop the session's later tasks
    @Test(timeout = 20000)
    public void sdsAc10_FR_WS_005_AC5_aThrowingTaskDoesNotBlockTheSlot() throws Exception {
        DefaultWebSocketSession s = session();
        CountDownLatch after = new CountDownLatch(1);
        sender.submit(s, () -> {
            throw new IllegalStateException("write bug");
        });
        sender.submit(s, after::countDown);
        assertTrue(after.await(5, TimeUnit.SECONDS));
    }

    // SDS-AC-10; FR-WS-005 AC-5: a task that throws an Error frees the slot, so the session's next write still runs
    @Test(timeout = 20000)
    public void sdsAc10_FR_WS_005_AC5_anErrorInATaskFreesTheSlot() throws Exception {
        DefaultWebSocketSession s = session();
        CountDownLatch thrown = new CountDownLatch(1);
        sender.submit(s, () -> {
            thrown.countDown();
            throw new AssertionError("expected by the test: an Error inside a write");
        });
        assertTrue(thrown.await(5, TimeUnit.SECONDS));
        CountDownLatch after = new CountDownLatch(1);
        for (long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5); after.getCount() > 0
                && System.nanoTime() < end; ) {
            // The Error unwinds asynchronously; a write submitted before the slot is freed waits behind it and is
            // dropped with it, so keep submitting until one runs.
            sender.submit(s, after::countDown);
            after.await(100, TimeUnit.MILLISECONDS);
        }
        assertEquals(0, after.getCount());
    }

    /** A channel that stays open and records each call with its thread. */
    static final class RecordingChannel implements WebSocketChannel {
        final BlockingQueue<String> calls = new LinkedBlockingQueue<>();
        final BlockingQueue<Thread> threads = new LinkedBlockingQueue<>();

        private void record(String call) {
            threads.add(Thread.currentThread());
            calls.add(call);
        }

        @Override
        public void sendText(String text) {
            record("text " + text);
        }

        @Override
        public void sendBinary(byte[] data) {
            record("binary " + data.length);
        }

        @Override
        public void ping(byte[] payload) {
            record("ping " + payload.length);
        }

        @Override
        public void close(int code, String reason) {
            record("close " + code + " " + reason);
        }

        @Override
        public void abort() {
            record("abort");
        }

        @Override
        public boolean isOpen() {
            return true;
        }
    }

    // SDS-AC-8; FR-WS-005 AC-7: ws.ping() hands channel.ping(new byte[0]) to the session's sender slot, behind the
    // write already running there, on a sender thread
    @Test(timeout = 20000)
    public void sdsAc8_FR_WS_005_AC7_pingGoesThroughTheSenderSlot() throws Exception {
        DefaultWebSocketSession s = session();
        RecordingChannel ch = new RecordingChannel();
        s.onOpen(ch);
        CountDownLatch release = new CountDownLatch(1);
        sender.submit(s, () -> {
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        s.ping();
        assertNull("ping overtook the running write", ch.calls.poll(200, TimeUnit.MILLISECONDS));
        release.countDown();
        assertEquals("ping 0", ch.calls.poll(5, TimeUnit.SECONDS));
        assertTrue(ch.threads.take().getName().startsWith("naruserve-ws-sender-"));
    }

    // SDS-AC-9; FR-WS-005 AC-8: close makes isOpen() false at once though the channel is still open, drops later
    // pings, sends the close through the slot and reports the sent pair whatever code the peer echoed
    @Test(timeout = 20000)
    public void sdsAc9_FR_WS_005_AC8_closeIsImmediateDropsLaterPingsAndReportsTheSentPair() throws Exception {
        DefaultWebSocketSession s = session();
        RecordingChannel ch = new RecordingChannel();
        s.onOpen(ch);
        BlockingQueue<String> closed = new LinkedBlockingQueue<>();
        s.onClose((code, reason) -> closed.add(code + " " + reason));
        s.close(4000, "bye");
        assertFalse(s.isOpen());
        s.ping();
        s.close(1000, "again");
        assertEquals("close 4000 bye", ch.calls.poll(5, TimeUnit.SECONDS));
        assertTrue(ch.threads.take().getName().startsWith("naruserve-ws-sender-"));
        s.onClose(1000, "");
        assertEquals("4000 bye", closed.poll(5, TimeUnit.SECONDS));
        assertNull("a ping or second close reached the channel", ch.calls.poll(200, TimeUnit.MILLISECONDS));
    }
}
