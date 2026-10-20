package com.snoworca.naruserve.tinyhttpd;

import static com.snoworca.naruserve.tinyhttpd.RawWebSocketClient.TEXT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.testing.NoLeftoverThreads;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.tinyhttpd.RawTargetSocketTest.Http;
import com.snoworca.naruserve.tinyhttpd.RawWebSocketClient.Frame;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;

/**
 * T23 over a loopback socket: whole ordered messages from many threads, and stalled sessions that never delay a
 * healthy one. SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-6.sds.md.
 */
public class WebSocketSendSocketTest {

    // I-015: no naruserve-* thread outlives the test
    @Rule
    public final NoLeftoverThreads noLeftoverThreads = new NoLeftoverThreads();

    private static final Pattern TAGGED = Pattern.compile("t([0-7])-([0-9]+)-(x+)");
    private static final String PAD = Http.repeat('x', 200);
    /** One stalled-session message: 512 KiB, shared, so 20 sessions queueing 16 each hold no more than one copy. */
    private static final String BIG = Http.repeat('s', 512 * 1024);

    private final RestApp app = Http.app(new TinyHttpdAdapter(), new RecordingLogger());
    private final BlockingQueue<String> closes = new LinkedBlockingQueue<>();
    /** Per session id: System.nanoTime() when its stall writes were queued, no later than its first write blocked. */
    private final Map<String, Long> stallNanos = new ConcurrentHashMap<>();
    /** Per session id: System.nanoTime() when its onClose ran. */
    private final Map<String, Long> closeNanos = new ConcurrentHashMap<>();

    @After
    public void stop() {
        app.shutdown(2, TimeUnit.SECONDS);
    }

    // SDS-AC-5; FR-WS-007 AC-4: 8 threads x 500 tagged messages to one session reach the client whole, each
    // thread's messages in its send order (T23)
    @Test(timeout = 60000)
    public void sdsAc5_FR_WS_007_AC4_eightThreadsOf500MessagesArriveWholeAndInSendOrder() throws Exception {
        app.webSocket(o -> o.sendQueueSize(16 * 1024 * 1024));
        app.ws("/ws", (req, ws) -> ws.onText(text -> {
            for (int i = 0; i < 8; i++) {
                int n = i;
                Thread t = new Thread(() -> {
                    for (int seq = 0; seq < 500; seq++) {
                        ws.sendText("t" + n + "-" + seq + "-" + PAD);
                    }
                });
                t.start();
            }
        }));
        try (RawWebSocketClient c = RawWebSocketClient.open(app.listen(0).port(), "/ws")) {
            assertEquals(c.handshake, 101, c.status());
            c.setReadTimeout(10000);
            c.text("go");
            int[] next = new int[8];
            for (int k = 0; k < 4000; k++) {
                Frame f = c.readFrame(TEXT);
                assertTrue("a fragmented frame", f.fin);
                Matcher m = TAGGED.matcher(f.text());
                assertTrue("not one whole message: " + f.text(), m.matches());
                int thread = Integer.parseInt(m.group(1));
                assertEquals("thread " + thread + " out of order", next[thread], Integer.parseInt(m.group(2)));
                assertEquals(PAD, m.group(3));
                next[thread]++;
            }
            for (int n : next) {
                assertEquals(500, n);
            }
        }
    }

    /** "stall" queues 16 x 512 KiB; "hi" answers "hello". */
    private int listenStalling() {
        app.ws("/ws", (req, ws) -> {
            ws.onClose((code, reason) -> {
                closeNanos.put(ws.id(), System.nanoTime());
                closes.add(code + " " + reason);
            });
            ws.onText(text -> {
                if ("stall".equals(text)) {
                    stallNanos.put(ws.id(), System.nanoTime());
                    for (int i = 0; i < 16; i++) {
                        ws.sendText(BIG);
                    }
                } else {
                    ws.sendText("hello");
                }
            });
        });
        return app.listen(0).port();
    }

    private static List<RawWebSocketClient> stalled(int port, int n) throws IOException {
        List<RawWebSocketClient> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            RawWebSocketClient c = RawWebSocketClient.open(port, "/ws");
            out.add(c);
            assertEquals(c.handshake, 101, c.status());
            c.text("stall"); // and never reads again
        }
        return out;
    }

    /** Sender threads of {@code port} now inside a socket write, which a client that stopped reading keeps blocked. */
    private static int blockedWriters(int port) {
        return Http.blockedThreads(port).size();
    }

    /**
     * Records when each sender thread of {@code port} was first seen blocked in a write and forgets it once it is
     * seen free again; returns the longest of the blocks that ended, in ms.
     */
    private static long sampleWriters(int port, Map<Thread, Long> blockedSince) {
        long now = System.nanoTime();
        Set<Thread> blocked = Http.blockedThreads(port);
        for (Thread t : blocked) {
            if (!blockedSince.containsKey(t)) {
                blockedSince.put(t, now);
            }
        }
        long longest = 0;
        for (Iterator<Map.Entry<Thread, Long>> it = blockedSince.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Thread, Long> e = it.next();
            if (!blocked.contains(e.getKey())) {
                longest = Math.max(longest, TimeUnit.NANOSECONDS.toMillis(now - e.getValue()));
                it.remove();
            }
        }
        return longest;
    }

    /** Waits up to 10 s until {@code n} sender threads of {@code port} are blocked in a write. */
    private static void awaitBlockedWriters(int port, int n) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (blockedWriters(port) < n && System.nanoTime() < end) {
            Thread.sleep(5);
        }
        assertEquals("sender threads blocked in a write", n, blockedWriters(port));
    }

    // SDS-AC-5; FR-WS-007 AC-5: with 20 sessions whose clients stopped reading while the server writes to them, a
    // sendText to a healthy session reaches its client within 1 s (T23)
    @Test(timeout = 60000)
    public void sdsAc5_FR_WS_007_AC5_twentyStalledSessionsNeverDelayAHealthyOne() throws Exception {
        app.webSocket(o -> o.sendQueueSize(64 * 1024 * 1024));
        int port = listenStalling();
        List<RawWebSocketClient> stalled = stalled(port, 20);
        try (RawWebSocketClient healthy = RawWebSocketClient.open(port, "/ws")) {
            assertEquals(healthy.handshake, 101, healthy.status());
            awaitBlockedWriters(port, 20);
            for (int i = 0; i < 3; i++) {
                long start = System.nanoTime();
                healthy.text("hi");
                assertEquals("hello", healthy.readFrame(TEXT).text());
                long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                assertTrue("healthy reply after " + ms + " ms", ms <= 1000);
            }
        } finally {
            Http.closeAll(stalled);
        }
    }

    // SDS-AC-5; FR-WS-007 AC-5: with writeTimeout(1 s) TinyHttpd aborts each stalled socket, so every stalled
    // session ends within 2 s after its write blocked and its sender thread is free for others. Each session and
    // each sender thread is timed on its own, so the 20 need not be blocked at once. The 2 s of the AC is asserted
    // as is: the write timeout is 1 s, so a healthy run leaves 1 s of slack for a loaded host.
    @Test(timeout = 60000)
    public void sdsAc5_FR_WS_007_AC5_writeTimeoutFreesEachStalledSessionWithinTwoSeconds() throws Exception {
        app.server(o -> o.writeTimeout(1, TimeUnit.SECONDS));
        app.webSocket(o -> o.sendQueueSize(64 * 1024 * 1024));
        int port = listenStalling();
        Map<Thread, Long> blockedSince = new HashMap<>();
        long longestBlock = 0;
        List<RawWebSocketClient> stalled = stalled(port, 20);
        try {
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while ((closeNanos.size() < 20 || !blockedSince.isEmpty() || blockedWriters(port) > 0)
                    && System.nanoTime() < end) {
                longestBlock = Math.max(longestBlock, sampleWriters(port, blockedSince));
                Thread.sleep(5);
            }
            assertEquals("stalled sessions that ended", 20, closeNanos.size());
            assertEquals("stalled sessions that queued their writes", 20, stallNanos.size());
            for (Map.Entry<String, Long> e : stallNanos.entrySet()) {
                long ms = TimeUnit.NANOSECONDS.toMillis(closeNanos.get(e.getKey()) - e.getValue());
                assertTrue("stalled session " + e.getKey() + " ended " + ms + " ms after its writes were queued",
                        ms <= 2000);
            }
            assertEquals("sender threads still blocked", 0, blockedWriters(port));
            assertTrue("no sender thread was seen blocked in a write", longestBlock > 0);
            assertTrue("a stalled sender thread was released " + longestBlock + " ms after its write blocked",
                    longestBlock <= 2000);
            try (RawWebSocketClient healthy = RawWebSocketClient.open(port, "/ws")) {
                healthy.text("hi");
                assertEquals("hello", healthy.readFrame(TEXT).text());
            }
        } finally {
            Http.closeAll(stalled);
        }
    }
}
