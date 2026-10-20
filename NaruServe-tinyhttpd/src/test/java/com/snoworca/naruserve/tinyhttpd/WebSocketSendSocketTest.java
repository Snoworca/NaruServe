package com.snoworca.naruserve.tinyhttpd;

import static com.snoworca.naruserve.tinyhttpd.RawWebSocketClient.TEXT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.tinyhttpd.RawTargetSocketTest.Http;
import com.snoworca.naruserve.tinyhttpd.RawWebSocketClient.Frame;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.After;
import org.junit.Test;

/**
 * T23 over a loopback socket: whole ordered messages from many threads, and stalled sessions that never delay a
 * healthy one. SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-6.sds.md.
 */
public class WebSocketSendSocketTest {

    private static final Pattern TAGGED = Pattern.compile("t([0-7])-([0-9]+)-(x+)");
    private static final String PAD = repeat('x', 200);
    /** One stalled-session message: 512 KiB, shared, so 20 sessions queueing 16 each hold no more than one copy. */
    private static final String BIG = repeat('s', 512 * 1024);

    private final RestApp app = Http.app(new TinyHttpdAdapter(), new RecordingLogger());
    private final BlockingQueue<String> closes = new LinkedBlockingQueue<>();

    @After
    public void stop() {
        app.shutdown(2, TimeUnit.SECONDS);
    }

    private static String repeat(char c, int n) {
        char[] a = new char[n];
        Arrays.fill(a, c);
        return new String(a);
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
            ws.onClose((code, reason) -> closes.add(code + " " + reason));
            ws.onText(text -> {
                if ("stall".equals(text)) {
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
        int n = 0;
        for (Thread t : Http.threads("naruserve-" + port + "-ws-sender-")) {
            for (StackTraceElement e : t.getStackTrace()) {
                String c = e.getClassName();
                if ((c.startsWith("sun.nio.ch.") || c.startsWith("java.net."))
                        && e.getMethodName().toLowerCase(java.util.Locale.ROOT).contains("write")) {
                    n++;
                    break;
                }
            }
        }
        return n;
    }

    /** Waits up to 10 s until {@code n} sender threads of {@code port} are blocked in a write. */
    private static void awaitBlockedWriters(int port, int n) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (blockedWriters(port) < n && System.nanoTime() < end) {
            Thread.sleep(5);
        }
        assertEquals("sender threads blocked in a write", n, blockedWriters(port));
    }

    private static void closeAll(List<RawWebSocketClient> clients) {
        for (RawWebSocketClient c : clients) {
            try {
                c.close();
            } catch (IOException e) {
                // already gone
            }
        }
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
            closeAll(stalled);
        }
    }

    // SDS-AC-5; FR-WS-007 AC-5: with writeTimeout(1 s) TinyHttpd aborts each stalled socket, so every stalled
    // session ends within 2 s after its write blocked and its sender thread is free for others
    @Test(timeout = 60000)
    public void sdsAc5_FR_WS_007_AC5_writeTimeoutFreesEachStalledSessionWithinTwoSeconds() throws Exception {
        app.server(o -> o.writeTimeout(1, TimeUnit.SECONDS));
        app.webSocket(o -> o.sendQueueSize(64 * 1024 * 1024));
        int port = listenStalling();
        List<RawWebSocketClient> stalled = stalled(port, 20);
        try {
            awaitBlockedWriters(port, 20);
            long blocked = System.nanoTime(); // no earlier than any of the 20 writes blocked
            for (int i = 0; i < 20; i++) {
                String closed = closes.poll(10, TimeUnit.SECONDS);
                assertNotNull("stalled session " + i + " never ended", closed);
            }
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (blockedWriters(port) > 0 && System.nanoTime() < end) {
                Thread.sleep(5);
            }
            long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - blocked);
            assertEquals("sender threads still blocked", 0, blockedWriters(port));
            assertTrue("stalled sender threads released " + ms + " ms after their writes blocked", ms <= 2000);
            try (RawWebSocketClient healthy = RawWebSocketClient.open(port, "/ws")) {
                healthy.text("hi");
                assertEquals("hello", healthy.readFrame(TEXT).text());
            }
        } finally {
            closeAll(stalled);
        }
    }
}
