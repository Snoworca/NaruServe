package com.snoworca.naruserve;

import static com.snoworca.naruserve.WebSocketDispatchTest.accepted;
import static com.snoworca.naruserve.WebSocketDispatchTest.upgradeRequest;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryWebSocketChannel;
import com.snoworca.naruserve.testing.TestApp;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Test;

/** The send queue bound over the in-memory adapter. SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-6.sds.md. */
public class WebSocketSendQueueTest {

    private final InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
    private final TestApp t = TestApp.create(adapter);
    private final BlockingQueue<String> events = new LinkedBlockingQueue<>();

    @After
    public void stop() {
        t.app().shutdown(2, TimeUnit.SECONDS);
    }

    private static String repeat(char c, int n) {
        char[] a = new char[n];
        Arrays.fill(a, c);
        return new String(a);
    }

    private String event() throws InterruptedException {
        return events.poll(5, TimeUnit.SECONDS);
    }

    private static List<Object> sent(InMemoryWebSocketChannel ch, int n) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        List<Object> s = ch.sent();
        while (s.size() < n && System.nanoTime() < end) {
            Thread.sleep(10);
            s = ch.sent();
        }
        return s;
    }

    /** sendQueueSize(1000); each text message runs {@code onText} with the session. */
    private void listen(WebSocketSession.TextListener onText, WebSocketSession[] session) {
        t.app().webSocket(o -> o.sendQueueSize(1000));
        t.app().ws("/ws", (req, ws) -> {
            session[0] = ws;
            ws.onClose((code, reason) -> events.add("close " + code + " " + reason));
            ws.onText(onText);
        });
        t.start();
    }

    // SDS-AC-2; FR-WS-007 AC-6: a send that would pass sendQueueSize is dropped with the queue, the session closes
    // with (1008, "send queue overflow") behind the write in flight, and the send returns normally
    @Test(timeout = 20000)
    public void sdsAc2_FR_WS_007_AC6_overflowClosesWith1008AndTheSendReturnsNormally() throws Exception {
        WebSocketSession[] ws = new WebSocketSession[1];
        listen(text -> {
            ws[0].sendText(repeat('a', 600));
            ws[0].sendText(repeat('b', 600));
            ws[0].sendText("after");
            events.add("returned, open " + ws[0].isOpen());
        }, ws);
        accepted(adapter, upgradeRequest("/ws"), ch -> {
            CountDownLatch release = ch.hold("sendText");
            ch.clientText("go");
            assertEquals("returned, open false", event());
            release.countDown();
            assertEquals("close 1008 send queue overflow", event());
            assertEquals(1008, ch.closeCode());
            assertEquals("send queue overflow", ch.closeReason());
            assertEquals(Collections.<Object>singletonList(repeat('a', 600)), ch.sent());
        });
    }

    // SDS-AC-2; FR-WS-007 AC-6: a 2000-byte message alone overflows the empty queue of sendQueueSize(1000)
    @Test(timeout = 20000)
    public void sdsAc2_FR_WS_007_AC6_aMessageAloneAboveTheLimitOverflowsAnEmptyQueue() throws Exception {
        WebSocketSession[] ws = new WebSocketSession[1];
        listen(text -> {
            ws[0].sendBinary(new byte[2000]);
            events.add("returned");
        }, ws);
        accepted(adapter, upgradeRequest("/ws"), ch -> {
            ch.clientText("go");
            assertEquals("returned", event());
            assertEquals("close 1008 send queue overflow", event());
            assertEquals(1008, ch.closeCode());
            assertTrue(ch.sent().isEmpty());
        });
    }

    // SDS-AC-1; FR-WS-007 AC-1: sendJson counts its encoded text, so 1000 bytes of JSON pass and one more overflows
    @Test(timeout = 20000)
    public void sdsAc1_FR_WS_007_AC1_sendJsonCountsItsEncodedText() throws Exception {
        WebSocketSession[] ws = new WebSocketSession[1];
        StringBuilder digits = new StringBuilder("1");
        while (digits.length() < 1000) {
            digits.append('0');
        }
        java.math.BigInteger thousandDigits = new java.math.BigInteger(digits.toString());
        listen(text -> {
            if ("fits".equals(text)) {
                ws[0].sendJson(thousandDigits);
            } else {
                ws[0].sendJson(thousandDigits.multiply(java.math.BigInteger.TEN));
            }
        }, ws);
        accepted(adapter, upgradeRequest("/ws"), ch -> {
            ch.clientText("fits");
            assertEquals(Collections.<Object>singletonList(digits.toString()), sent(ch, 1));
            ch.clientText("overflows");
            assertEquals("close 1008 send queue overflow", event());
            assertEquals(1, ch.sent().size());
        });
    }
}
