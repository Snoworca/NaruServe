package com.snoworca.naruserve.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/** The internal session. SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-5.sds.md. */
public class DefaultWebSocketSessionTest {

    private static final String RID = "00000000000000aa";

    private final RecordingLogger logger = new RecordingLogger();
    private final WebSocketUpgrade upgrade = new WebSocketUpgrade(new DefaultWebSocketOptions(), null,
            new SafeLogger(logger));
    private final BlockingQueue<String> events = new LinkedBlockingQueue<>();

    private DefaultWebSocketSession session() {
        return WebSocketSenderTest.session(upgrade, RID, null);
    }

    private DefaultWebSocketSession open(WebSocketSenderTest.RecordingChannel ch) {
        DefaultWebSocketSession s = session();
        s.onOpen(ch);
        s.onClose((code, reason) -> events.add(code + " " + reason));
        return s;
    }

    // SDS-AC-1; FR-WS-006 AC-1: nextId counts up from 1 as 16 lowercase hex digits and the session keeps its id
    @Test
    public void sdsAc1_FR_WS_006_AC1_nextIdIsSixteenHexDigitsFromOne() {
        assertEquals("0000000000000001", session().id());
        assertEquals("0000000000000002", upgrade.nextId());
        for (int i = 3; i < 26; i++) {
            upgrade.nextId();
        }
        assertEquals("000000000000001a", upgrade.nextId());
    }

    // SDS-AC-3; FR-WS-006 AC-2: concurrent writes are all readable, onClose listeners still read them, and the map is
    // empty once the listeners ran
    @Test(timeout = 20000)
    public void sdsAc3_FR_WS_006_AC2_attributesTakeConcurrentWritesAndAreClearedAfterOnClose() throws Exception {
        DefaultWebSocketSession s = session();
        s.onOpen(new WebSocketSenderTest.RecordingChannel());
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            int n = i;
            Thread t = new Thread(() -> {
                for (int j = 0; j < 100; j++) {
                    s.attribute("t" + n + "-" + j, n * 1000 + j);
                }
            });
            threads.add(t);
            t.start();
        }
        for (Thread t : threads) {
            t.join();
        }
        for (int i = 0; i < 8; i++) {
            for (int j = 0; j < 100; j++) {
                assertEquals(Integer.valueOf(i * 1000 + j), s.attribute("t" + i + "-" + j, Integer.class));
            }
        }
        assertEquals(800, s.attributeCount());
        s.onClose((code, reason) -> events.add("seen " + s.attribute("t7-99", Integer.class)));
        s.onClose(1000, "");
        assertEquals("seen 7099", events.poll(5, TimeUnit.SECONDS));
        assertEquals(0, s.attributeCount());
    }

    // SDS-AC-4; FR-WS-006 AC-3: listeners run once, on the calling thread in registration order, a throwing one
    // logged at ERROR without stopping the next; a server close followed by an abort still counts once
    @Test(timeout = 20000)
    public void sdsAc4_FR_WS_006_AC3_onCloseListenersRunOnce() throws Exception {
        Thread caller = Thread.currentThread();
        DefaultWebSocketSession s = session();
        s.onOpen(new WebSocketSenderTest.RecordingChannel());
        List<String> calls = Collections.synchronizedList(new ArrayList<String>());
        s.onClose((code, reason) -> {
            calls.add("first " + code + " " + reason + " " + (Thread.currentThread() == caller));
            throw new IllegalStateException("listener broke");
        });
        s.onClose((code, reason) -> calls.add("second " + code + " " + reason));
        s.onClose(1000, "done");
        s.onClose(1000, "done");
        s.onClose(1006, "");
        assertEquals(Arrays.asList("first 1000 done true", "second 1000 done"), calls);
        assertEquals(1, RecordingLogger.countMatching(logger.lines, "ERROR websocket " + RID + " close listener failed listener broke"));

        WebSocketSenderTest.RecordingChannel ch = new WebSocketSenderTest.RecordingChannel();
        DefaultWebSocketSession server = open(ch);
        server.close(4000, "bye");
        server.onClose(1006, ""); // the peer never answered and the socket was aborted
        server.onClose(1006, "");
        assertEquals("4000 bye", events.poll(5, TimeUnit.SECONDS));
        assertNull(events.poll(200, TimeUnit.MILLISECONDS));
    }

    // SDS-AC-9; FR-WS-006 AC-6: an onError listener that throws is logged once and ignored, the next one still runs
    // and the 1011 close is sent, without recursing into fail
    @Test(timeout = 20000)
    public void sdsAc9_FR_WS_006_AC6_throwingOnErrorIsLoggedAndTheCloseStillGoesOut() throws Exception {
        WebSocketSenderTest.RecordingChannel ch = new WebSocketSenderTest.RecordingChannel();
        DefaultWebSocketSession s = open(ch);
        List<String> calls = Collections.synchronizedList(new ArrayList<String>());
        RuntimeException boom = new RuntimeException("boom");
        s.onError(e -> {
            calls.add("first " + (e == boom));
            throw new IllegalStateException("onError broke");
        });
        s.onError(e -> calls.add("second " + (e == boom)));
        s.fail(boom, "listener");
        assertEquals(Arrays.asList("first true", "second true"), calls);
        assertEquals("close 1011 ", ch.calls.poll(5, TimeUnit.SECONDS));
        assertFalse(s.isOpen());
        assertEquals(1, RecordingLogger.countMatching(logger.lines, "ERROR websocket " + RID + " error listener failed onError broke"));
        assertEquals(1, RecordingLogger.countMatching(logger.lines, "ERROR websocket " + RID + " listener failed boom"));
        assertNull(ch.calls.poll(200, TimeUnit.MILLISECONDS));
    }

    private static void assertRefused(String message, int code, String reason) {
        try {
            DefaultWebSocketSession.checkClose(code, reason);
            fail("close(" + code + ") accepted");
        } catch (IllegalArgumentException e) {
            assertEquals(message, e.getMessage());
        }
    }

    private static String bytes(int n) {
        char[] c = new char[n];
        Arrays.fill(c, 'r');
        return new String(c);
    }

    // SDS-AC-11; FR-WS-006 AC-8: only the sendable RFC 6455 codes and reasons up to 123 UTF-8 bytes pass, also on a
    // closed session
    @Test(timeout = 20000)
    public void sdsAc11_FR_WS_006_AC8_closeCodeAndReasonAreChecked() throws Exception {
        for (int code : new int[] {1005, 1016, 2000, 999, 5000, 1004, 1006, 1015, 2999}) {
            assertRefused("close code must be 1000-1003, 1007-1014 or 3000-4999: " + code, code, "");
        }
        String reason124 = bytes(122) + "é"; // 122 + 2 bytes
        assertRefused("close reason over 123 UTF-8 bytes: 124", 1000, reason124);
        for (int code : new int[] {1000, 1003, 1007, 1012, 1014, 3000, 4999}) {
            DefaultWebSocketSession.checkClose(code, "x");
        }
        DefaultWebSocketSession.checkClose(1000, bytes(123));

        WebSocketSenderTest.RecordingChannel ch = new WebSocketSenderTest.RecordingChannel();
        DefaultWebSocketSession s = open(ch);
        try {
            s.close(1005, "");
            fail("close(1005) accepted");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        assertTrue("a refused close closed the session", s.isOpen());
        s.close(1012, "");
        assertEquals("close 1012 ", ch.calls.poll(5, TimeUnit.SECONDS));
        try {
            s.close(1000, reason124);
            fail("a 124-byte reason was accepted on a closed session");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    // SDS-AC-12; FR-WS-006 AC-9: the first close the core starts is what onClose reports, whatever the adapter
    // delivers; with none, the adapter's code
    @Test(timeout = 20000)
    public void sdsAc12_FR_WS_006_AC9_onCloseReportsTheFirstCoreClose() throws Exception {
        String[][] sources = {{"1008", ""}, {"1001", ""}, {"1011", ""}, {"4000", "user"}};
        for (String[] src : sources) {
            WebSocketSenderTest.RecordingChannel ch = new WebSocketSenderTest.RecordingChannel();
            DefaultWebSocketSession s = open(ch);
            s.closeBy(Integer.parseInt(src[0]), src[1]);
            assertFalse(s.isOpen());
            assertEquals("close " + src[0] + " " + src[1], ch.calls.poll(5, TimeUnit.SECONDS));
            s.onClose(1006, "");
            assertEquals(src[0] + " " + src[1], events.poll(5, TimeUnit.SECONDS));
        }

        WebSocketSenderTest.RecordingChannel ch = new WebSocketSenderTest.RecordingChannel();
        DefaultWebSocketSession s = open(ch);
        s.closeBy(1001, "idle");
        s.close(1000, "later");
        s.closeBy(1011, "");
        s.fail(new RuntimeException("late"), "listener");
        s.onClose(1000, "peer");
        assertEquals("1001 idle", events.poll(5, TimeUnit.SECONDS));
        assertEquals("close 1001 idle", ch.calls.poll(5, TimeUnit.SECONDS));
        assertNull("a second close reached the channel", ch.calls.poll(200, TimeUnit.MILLISECONDS));

        DefaultWebSocketSession client = open(new WebSocketSenderTest.RecordingChannel());
        client.onClose(1000, "client");
        assertEquals("1000 client", events.poll(5, TimeUnit.SECONDS));
        DefaultWebSocketSession lost = open(new WebSocketSenderTest.RecordingChannel());
        lost.onClose(1006, "");
        assertEquals("1006 ", events.poll(5, TimeUnit.SECONDS));
    }

    // SDS-AC-12; FR-WS-006 AC-9: over the in-memory adapter a client close that arrives while the core's close frame
    // is still held in the sender still reports the core's pair
    @Test(timeout = 20000)
    public void sdsAc12_FR_WS_006_AC9_clientCloseRacingAHeldCoreCloseReportsTheCorePair() throws Exception {
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        TestApp t = TestApp.create(adapter);
        BlockingQueue<DefaultWebSocketSession> sessions = new LinkedBlockingQueue<>();
        t.app().ws("/ws", (req, ws) -> {
            sessions.add((DefaultWebSocketSession) ws);
            ws.onText(text -> ws.close(4000, "bye"));
            ws.onClose((code, reason) -> events.add(code + " " + reason));
        });
        t.start();
        Future<ClientResponse> f = adapter.submit(ClientRequest.of("GET", "/ws").header("Connection", "Upgrade")
                .header("Upgrade", "websocket"));
        InMemoryWebSocketChannel ch = adapter.nextChannel(5, TimeUnit.SECONDS);
        CountDownLatch held = ch.hold("close");
        try {
            ch.clientText("close");
            ch.clientClose(1000, "client");
            assertEquals("4000 bye", events.poll(5, TimeUnit.SECONDS));
            assertEquals("the core's close frame went out while held", -1, ch.closeCode());
        } finally {
            held.countDown();
        }
        assertEquals(101, f.get(10, TimeUnit.SECONDS).status());
        DefaultWebSocketSession s = sessions.poll(5, TimeUnit.SECONDS);
        assertFalse(s.isOpen());
        assertEquals(0, s.attributeCount());
        assertNull(events.poll(200, TimeUnit.MILLISECONDS));
    }

    // SDS-AC-4; FR-WS-006 AC-3: over the in-memory adapter a server close whose frame is still held, followed by an
    // abort of the socket, runs each onClose listener once with the server's pair
    @Test(timeout = 20000)
    public void sdsAc4_FR_WS_006_AC3_serverCloseThenAbortRunsListenersOnce() throws Exception {
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        TestApp t = TestApp.create(adapter);
        BlockingQueue<com.snoworca.naruserve.WebSocketSession> sessions = new LinkedBlockingQueue<>();
        t.app().ws("/ws", (req, ws) -> {
            sessions.add(ws);
            ws.onClose((code, reason) -> events.add(code + " " + reason));
        });
        t.start();
        Future<ClientResponse> f = adapter.submit(ClientRequest.of("GET", "/ws").header("Connection", "Upgrade")
                .header("Upgrade", "websocket"));
        InMemoryWebSocketChannel ch = adapter.nextChannel(5, TimeUnit.SECONDS);
        CountDownLatch held = ch.hold("close");
        try {
            sessions.poll(5, TimeUnit.SECONDS).close(4000, "bye");
            ch.abort();
            assertEquals("4000 bye", events.poll(5, TimeUnit.SECONDS));
        } finally {
            held.countDown();
        }
        f.get(10, TimeUnit.SECONDS);
        assertTrue(ch.aborted());
        assertNull("onClose ran twice", events.poll(200, TimeUnit.MILLISECONDS));
    }

    // SDS-AC-13; FR-WS-006 AC-10: a handler exception reaches only the onError listeners registered before the throw
    @Test(timeout = 20000)
    public void sdsAc13_FR_WS_006_AC10_handlerFailureSeesOnlyEarlierOnErrorListeners() throws Exception {
        List<String> calls = Collections.synchronizedList(new ArrayList<String>());
        DefaultWebSocketSession s = WebSocketSenderTest.session(upgrade, RID, (req, ws) -> {
            ws.onError(e -> calls.add("before " + e.getMessage()));
            throw new java.io.IOException("no db");
        });
        WebSocketSenderTest.RecordingChannel ch = new WebSocketSenderTest.RecordingChannel();
        s.onOpen(ch);
        s.onError(e -> calls.add("after"));
        assertEquals(Collections.singletonList("before no db"), calls);
        assertEquals("close 1011 ", ch.calls.poll(5, TimeUnit.SECONDS));
        assertEquals(1, RecordingLogger.countMatching(logger.lines, "ERROR websocket " + RID + " handler failed no db"));
    }
}
