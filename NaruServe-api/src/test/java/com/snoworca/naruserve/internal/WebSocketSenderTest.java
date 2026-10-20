package com.snoworca.naruserve.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.Method;
import com.snoworca.naruserve.testing.RecordingExchange;
import com.snoworca.naruserve.spi.WebSocketChannel;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.InMemoryWebSocketChannel;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.testing.TestApp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/**
 * The app-wide sender. SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-4.sds.md; the tests whose
 * names carry FR-WS-007 cite docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-6.sds.md.
 */
public class WebSocketSenderTest {

    private final WebSocketUpgrade upgrade = new WebSocketUpgrade(new DefaultWebSocketOptions(), null,
            new SafeLogger(new RecordingLogger()));
    private final WebSocketSender sender = upgrade.sender();

    private DefaultWebSocketSession session() {
        RecordingExchange ex = new RecordingExchange("GET", "/ws");
        return new DefaultWebSocketSession(upgrade.nextId(), "0000000000000000", upgrade, null,
                new DefaultRequest(ex, Method.GET, Collections.<String, String>emptyMap()));
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
        assertTrue(ch.threads.take().getName().matches("naruserve-\\d+-ws-sender-\\d+"));
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
        assertTrue(ch.threads.take().getName().matches("naruserve-\\d+-ws-sender-\\d+"));
        s.onClose(1000, "");
        assertEquals("4000 bye", closed.poll(5, TimeUnit.SECONDS));
        assertNull("a ping or second close reached the channel", ch.calls.poll(200, TimeUnit.MILLISECONDS));
    }

    static final SafeLogger LOG = new SafeLogger(new RecordingLogger());

    static WebSocketUpgrade upgrade(DefaultWebSocketOptions options) {
        return new WebSocketUpgrade(options, null, LOG);
    }

    static DefaultWebSocketSession session(WebSocketUpgrade up) {
        RecordingExchange ex = new RecordingExchange("GET", "/ws");
        return new DefaultWebSocketSession(up.nextId(), "0000000000000000", up, null,
                new DefaultRequest(ex, Method.GET, Collections.<String, String>emptyMap()));
    }

    interface Check {
        boolean ok();
    }

    /** Polls {@code check} for up to 5 s. */
    static boolean eventually(Check check) throws InterruptedException {
        for (long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5); System.nanoTime() < end; ) {
            if (check.ok()) {
                return true;
            }
            Thread.sleep(10);
        }
        return check.ok();
    }

    /** Live threads whose name starts with {@code prefix}. */
    static List<Thread> threadsNamed(String prefix) {
        List<Thread> out = new ArrayList<>();
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.isAlive() && t.getName().startsWith(prefix)) {
                out.add(t);
            }
        }
        return out;
    }

    private static ClientRequest upgradeRequest() {
        return ClientRequest.of("GET", "/ws").header("Connection", "Upgrade").header("Upgrade", "websocket");
    }

    /**
     * A channel open until abort. Each call is recorded in {@code started} when it starts and in {@code calls} when it
     * returns, with the thread and time of the first call of each method; {@link #hold} holds the next call of one
     * method until its latch is counted down.
     */
    static final class HoldingChannel implements WebSocketChannel {
        final BlockingQueue<String> started = new LinkedBlockingQueue<>();
        final BlockingQueue<String> calls = new LinkedBlockingQueue<>();
        final List<Thread> threads = new CopyOnWriteArrayList<>();
        final Map<String, Long> firstNanos = new ConcurrentHashMap<>();
        final Map<String, Thread> firstThread = new ConcurrentHashMap<>();
        private final Map<String, CountDownLatch> holds = new ConcurrentHashMap<>();
        volatile boolean open = true;

        CountDownLatch hold(String method) {
            CountDownLatch latch = new CountDownLatch(1);
            holds.put(method, latch);
            return latch;
        }

        private void enter(String method, String call) {
            firstNanos.putIfAbsent(method, System.nanoTime());
            firstThread.putIfAbsent(method, Thread.currentThread());
            threads.add(Thread.currentThread());
            started.add(call);
            CountDownLatch latch = holds.remove(method);
            if (latch != null) {
                try {
                    latch.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            calls.add(call);
        }

        @Override
        public void sendText(String text) {
            enter("sendText", "text " + text);
        }

        @Override
        public void sendBinary(byte[] data) {
            enter("sendBinary", "binary " + data.length);
        }

        @Override
        public void ping(byte[] payload) {
            enter("ping", "ping " + payload.length);
        }

        @Override
        public void close(int code, String reason) {
            enter("close", "close " + code + " " + reason);
        }

        @Override
        public void abort() {
            open = false;
            enter("abort", "abort");
        }

        @Override
        public boolean isOpen() {
            return open;
        }
    }

    // SDS-AC-1; FR-WS-007 AC-1: text counts its UTF-8 bytes, binary its length, ping 0; the held message in flight
    // stays counted, the count never passes sendQueueSize(1000) and drops to 0 once every write returned
    @Test(timeout = 20000)
    public void sdsAc1_FR_WS_007_AC1_queuedBytesCountUtf8AndNeverExceedTheLimit() throws Exception {
        DefaultWebSocketOptions o = new DefaultWebSocketOptions();
        o.sendQueueSize(1000);
        WebSocketUpgrade up = upgrade(o);
        WebSocketSender sender = up.sender();
        DefaultWebSocketSession s = session(up);
        HoldingChannel ch = new HoldingChannel();
        s.onOpen(ch);
        CountDownLatch release = ch.hold("sendText");
        s.sendText("é");
        assertEquals("text é", ch.started.poll(5, TimeUnit.SECONDS));
        assertEquals("the held message in flight is still counted", 2, sender.queuedBytes(s));
        s.sendBinary(new byte[8]);
        assertEquals(10, sender.queuedBytes(s));
        s.ping();
        assertEquals("a ping is not counted", 10, sender.queuedBytes(s));
        for (int i = 0; i < 99; i++) {
            s.sendText("0123456789");
            assertTrue(sender.queuedBytes(s) <= 1000);
        }
        assertEquals(1000, sender.queuedBytes(s));
        assertTrue("sends up to sendQueueSize keep the session open", s.isOpen());
        release.countDown();
        assertTrue(eventually(() -> sender.queuedBytes(s) == 0));
        assertTrue(s.isOpen());
        assertFalse(ch.started.contains("close 1008 send queue overflow"));
        for (String text : new String[] {"", "a", "é", "€", "😀", "\uD83D", "x\uDE00y", "\uD83Dz"}) {
            assertEquals(text, text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                    DefaultWebSocketSession.utf8Length(text));
        }
    }

    // SDS-AC-3; FR-WS-007 AC-2: one ThreadPoolExecutor(max, max, 60 s, LinkedBlockingQueue) whose core threads time
    // out, with daemon threads named after the port read when each thread is made
    @Test(timeout = 20000)
    public void sdsAc3_FR_WS_007_AC2_theSenderExecutorIsBoundedAndNamesItsThreadsAfterThePort() throws Exception {
        int[] port = {4242};
        WebSocketSender s = new WebSocketSender(128, () -> port[0], LOG);
        ThreadPoolExecutor e = s.executor();
        assertEquals(128, e.getCorePoolSize());
        assertEquals(128, e.getMaximumPoolSize());
        assertTrue(e.allowsCoreThreadTimeOut());
        assertEquals(60000L, e.getKeepAliveTime(TimeUnit.MILLISECONDS));
        assertTrue(e.getQueue() instanceof LinkedBlockingQueue);
        BlockingQueue<Thread> ran = new LinkedBlockingQueue<>();
        CountDownLatch hold = new CountDownLatch(1);
        assertTrue(s.queue(session(), () -> {
            ran.add(Thread.currentThread());
            try {
                hold.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }, 0, 1));
        Thread first = ran.poll(5, TimeUnit.SECONDS);
        assertEquals("naruserve-4242-ws-sender-1", first.getName());
        assertTrue(first.isDaemon());
        port[0] = 4343; // the first thread is busy, so the next session's drain makes a second thread
        assertTrue(s.queue(session(), () -> ran.add(Thread.currentThread()), 0, 1));
        assertEquals("naruserve-4343-ws-sender-2", ran.poll(5, TimeUnit.SECONDS).getName());
        hold.countDown();
    }

    // SDS-AC-3; FR-WS-007 AC-2: the app's sender is limited to its maxUpgradedConnections and named after its port
    @Test(timeout = 20000)
    public void sdsAc3_FR_WS_007_AC2_theAppsSenderHasMaxUpgradedConnectionsThreads() throws Exception {
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        TestApp t = TestApp.create(adapter);
        BlockingQueue<DefaultWebSocketSession> opened = new LinkedBlockingQueue<>();
        t.app().server(o -> o.maxUpgradedConnections(7));
        t.app().ws("/ws", (req, ws) -> {
            opened.add((DefaultWebSocketSession) ws);
            ws.sendText("hi");
        });
        t.start();
        try {
            Future<ClientResponse> f = adapter.submit(upgradeRequest());
            InMemoryWebSocketChannel ch = adapter.nextChannel(5, TimeUnit.SECONDS);
            DefaultWebSocketSession s = opened.poll(5, TimeUnit.SECONDS);
            assertEquals(7, s.sender().executor().getMaximumPoolSize());
            assertTrue(eventually(() -> ch.sent().size() == 1));
            assertFalse(threadsNamed("naruserve-" + adapter.boundPort() + "-ws-sender-").isEmpty());
            ch.clientClose(1000, "");
            f.get(10, TimeUnit.SECONDS);
        } finally {
            t.app().shutdown(2, TimeUnit.SECONDS);
        }
    }

    // SDS-AC-4; FR-WS-007 AC-2: with the internal keep-alive at 200 ms the idle sender threads end within 1 s
    @Test(timeout = 20000)
    public void sdsAc4_FR_WS_007_AC2_keepAlive200msEndsIdleThreadsWithinOneSecond() throws Exception {
        WebSocketSender s = new WebSocketSender(128, () -> 1, LOG);
        s.keepAlive(200);
        assertEquals(200L, s.executor().getKeepAliveTime(TimeUnit.MILLISECONDS));
        CountDownLatch done = new CountDownLatch(3);
        for (int i = 0; i < 3; i++) {
            assertTrue(s.queue(session(), done::countDown, 0, 1));
        }
        assertTrue(done.await(5, TimeUnit.SECONDS));
        long last = System.nanoTime();
        while (s.executor().getPoolSize() > 0 && System.nanoTime() - last < TimeUnit.SECONDS.toNanos(5)) {
            Thread.sleep(10);
        }
        long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - last);
        assertEquals(0, s.executor().getPoolSize());
        assertTrue("threads ended after " + tookMs + " ms", tookMs <= 1000);
    }

    // SDS-AC-5; FR-WS-007 AC-3: a held sendText holds back only the second sendText of its own session, while
    // another session's sendText completes meanwhile on its own drain task and thread
    @Test(timeout = 20000)
    public void sdsAc5_FR_WS_007_AC3_aHeldSendTextBlocksOnlyTheSecondOfItsSession() throws Exception {
        WebSocketUpgrade up = upgrade(new DefaultWebSocketOptions());
        DefaultWebSocketSession a = session(up);
        DefaultWebSocketSession b = session(up);
        HoldingChannel cha = new HoldingChannel();
        HoldingChannel chb = new HoldingChannel();
        a.onOpen(cha);
        b.onOpen(chb);
        CountDownLatch release = cha.hold("sendText");
        a.sendText("first");
        assertEquals("text first", cha.started.poll(5, TimeUnit.SECONDS));
        a.sendText("second");
        b.sendText("other");
        assertEquals("text other", chb.calls.poll(5, TimeUnit.SECONDS));
        assertNotEquals(cha.firstThread.get("sendText"), chb.firstThread.get("sendText"));
        assertNull("the second write started before the first returned", cha.started.poll(200, TimeUnit.MILLISECONDS));
        release.countDown();
        assertEquals("text first", cha.calls.poll(5, TimeUnit.SECONDS));
        assertEquals("text second", cha.calls.poll(5, TimeUnit.SECONDS));
    }

    // SDS-AC-6; FR-WS-007 AC-7: 200 sessions closed by client close, overflow and abort leave no slot, no queued
    // byte and no attribute behind
    @Test(timeout = 60000)
    public void sdsAc6_FR_WS_007_AC7_twoHundredClosedSessionsReleaseEverySlotAndAttribute() throws Exception {
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        TestApp t = TestApp.create(adapter);
        List<DefaultWebSocketSession> sessions = Collections.synchronizedList(new ArrayList<DefaultWebSocketSession>());
        BlockingQueue<String> closes = new LinkedBlockingQueue<>();
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            big.append('x');
        }
        t.app().webSocket(o -> o.sendQueueSize(100));
        t.app().ws("/ws", (req, ws) -> {
            sessions.add((DefaultWebSocketSession) ws);
            ws.attribute("user", "u");
            ws.onClose((code, reason) -> closes.add(code + " " + reason));
            ws.onText(text -> {
                ws.sendText("echo");
                if ("overflow".equals(text)) {
                    ws.sendText(big.toString());
                } else if ("abort".equals(text)) {
                    ((DefaultWebSocketSession) ws).abort();
                }
            });
        });
        t.start();
        try {
            for (int i = 0; i < 200; i++) {
                Future<ClientResponse> f = adapter.submit(upgradeRequest());
                InMemoryWebSocketChannel ch = adapter.nextChannel(5, TimeUnit.SECONDS);
                if (i % 3 == 0) {
                    ch.clientText("hello");
                    ch.clientClose(1000, "");
                    assertEquals("1000 ", closes.poll(5, TimeUnit.SECONDS));
                } else if (i % 3 == 1) {
                    ch.clientText("overflow");
                    assertEquals("1008 send queue overflow", closes.poll(5, TimeUnit.SECONDS));
                } else {
                    ch.clientText("abort");
                    assertEquals("1006 ", closes.poll(5, TimeUnit.SECONDS));
                }
                f.get(10, TimeUnit.SECONDS);
            }
            assertEquals(200, sessions.size());
            WebSocketSender sender = sessions.get(0).sender();
            assertTrue("slots left: " + sender.slots(), eventually(() -> sender.slots() == 0));
            // FR-WS-007 AC-7 through the internal session registry (docs/sds/...-wave-7-7.sds.md)
            WebSocketRegistry registry = ((DefaultRestApp) t.app()).webSocketRegistry();
            assertTrue("sessions left in the registry: " + registry.size(), eventually(() -> registry.size() == 0));
            for (DefaultWebSocketSession s : sessions) {
                assertEquals(0, s.attributeCount());
                assertEquals(0, sender.queuedBytes(s));
                assertTrue(sender.idle(s));
            }
        } finally {
            t.app().shutdown(2, TimeUnit.SECONDS);
        }
    }

    // SDS-AC-2, SDS-AC-6; FR-WS-007 AC-6, AC-7: an overflow empties the queue but keeps the write in flight counted;
    // last() discards what was queued and refuses later writes; release drops the slot
    @Test(timeout = 20000)
    public void sdsAc2_sdsAc6_FR_WS_007_AC6_AC7_overflowAndLastEmptyTheQueueAndReleaseDropsTheSlot()
            throws Exception {
        WebSocketSender s = new WebSocketSender(4, () -> 1, LOG);
        DefaultWebSocketSession ses = session();
        CountDownLatch hold = new CountDownLatch(1);
        CountDownLatch running = new CountDownLatch(1);
        List<String> ran = Collections.synchronizedList(new ArrayList<String>());
        assertTrue(s.queue(ses, () -> {
            running.countDown();
            try {
                hold.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            ran.add("held");
        }, 5, 100));
        assertTrue(running.await(5, TimeUnit.SECONDS));
        assertTrue(s.queue(ses, () -> ran.add("queued"), 50, 100));
        assertEquals(55, s.queuedBytes(ses));
        assertFalse("55 + 50 > 100 overflows", s.queue(ses, () -> ran.add("dropped"), 50, 100));
        assertEquals("the overflow emptied the queue, the write in flight stays counted", 5, s.queuedBytes(ses));
        assertTrue(s.queue(ses, () -> ran.add("queued again"), 5, 100));
        s.last(ses, () -> ran.add("close"));
        assertEquals("last() discards what was queued", 5, s.queuedBytes(ses));
        assertFalse(s.queue(ses, () -> ran.add("late"), 1, 100));
        assertFalse(s.idle(ses));
        assertEquals(1, s.slots());
        hold.countDown();
        assertTrue(eventually(() -> s.idle(ses)));
        assertEquals(Arrays.asList("held", "close"), ran);
        assertEquals(0, s.queuedBytes(ses));
        s.release(ses);
        assertEquals(0, s.slots());
    }

    // SDS-AC-2; FR-WS-007 AC-6: one message above the limit overflows an empty queue
    @Test(timeout = 20000)
    public void sdsAc2_FR_WS_007_AC6_aMessageAloneAboveTheLimitOverflowsAnEmptyQueue() {
        WebSocketSender s = new WebSocketSender(4, () -> 1, LOG);
        DefaultWebSocketSession ses = session();
        assertFalse(s.queue(ses, () -> {
            throw new AssertionError("dropped write ran");
        }, 101, 100));
        assertEquals(0, s.queuedBytes(ses));
        assertTrue(s.idle(ses));
    }

    // SDS-AC-3 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-13.sds.md; PERF-APP-001 AC-3: a sender thread is
    // made with a requested stack of 262144 bytes, read through the thread factory's hook
    @Test(timeout = 20000)
    public void wave7s13SdsAc3_PERF_APP_001_AC3_senderThreadsRequestA256KiBStack() throws Exception {
        WebSocketSender s = new WebSocketSender(128, () -> 1, LOG);
        assertEquals("no thread made yet", 0L, s.requestedStack());
        CountDownLatch ran = new CountDownLatch(1);
        assertTrue(s.queue(session(), ran::countDown, 0, 1));
        assertTrue(ran.await(10, TimeUnit.SECONDS));
        assertEquals(262144L, s.requestedStack());
    }
}
