package com.snoworca.naruserve.tinyhttpd;

import static com.snoworca.naruserve.tinyhttpd.RawWebSocketClient.BINARY;
import static com.snoworca.naruserve.tinyhttpd.RawWebSocketClient.CLOSE;
import static com.snoworca.naruserve.tinyhttpd.RawWebSocketClient.CONT;
import static com.snoworca.naruserve.tinyhttpd.RawWebSocketClient.PING;
import static com.snoworca.naruserve.tinyhttpd.RawWebSocketClient.PONG;
import static com.snoworca.naruserve.tinyhttpd.RawWebSocketClient.TEXT;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.WebSocketSession;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.tinyhttpd.RawTargetSocketTest.Http;
import com.snoworca.naruserve.tinyhttpd.RawWebSocketClient.Frame;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Test;

/**
 * T20 and T21: RFC 6455 enforcement by TinyHttpd's websocket module, driven frame by frame over a loopback socket.
 * SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-4.sds.md.
 */
public class WebSocketProtocolSocketTest {

    private final RestApp app = Http.app(new TinyHttpdAdapter(), new RecordingLogger());
    /** Session events in arrival order: "text ...", "binary n", "close code reason", "isOpen ...". */
    private final BlockingQueue<String> events = new ArrayBlockingQueue<>(64);
    private final BlockingQueue<byte[]> binaries = new ArrayBlockingQueue<>(8);
    private final AtomicReference<WebSocketSession> session = new AtomicReference<>();

    @After
    public void stop() {
        app.shutdown(2, TimeUnit.SECONDS);
    }

    /** Listens with an app.ws route recording every event; text "close" closes with (4000, "bye"). */
    private int listen() throws Exception {
        app.ws("/ws", (req, ws) -> {
            session.set(ws);
            ws.onText(t -> {
                events.add("text " + t);
                if ("close".equals(t)) {
                    ws.close(4000, "bye");
                    events.add("isOpen " + ws.isOpen());
                    ws.ping(); // dropped: the session is closing
                } else if ("ping me".equals(t)) {
                    ws.ping();
                }
            });
            ws.onBinary(b -> {
                binaries.add(b);
                events.add("binary " + b.length);
            });
            ws.onClose((code, reason) -> events.add("close " + code + " " + reason));
        });
        return app.listen(0).port();
    }

    private RawWebSocketClient open(int port) throws Exception {
        RawWebSocketClient c = RawWebSocketClient.open(port, "/ws");
        assertEquals(c.handshake, 101, c.status());
        return c;
    }

    private String event() throws InterruptedException {
        return events.poll(5, TimeUnit.SECONDS);
    }

    private static byte[] bytes(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) (i * 7 + 3);
        }
        return b;
    }

    private static void assertClosedWith(RawWebSocketClient c, int code) throws Exception {
        Frame f = c.readFrame(CLOSE);
        assertEquals(code, f.closeCode());
        assertTrue("TCP connection closed after the close frame", c.endOfStream());
    }

    // FR-WS-005 AC-1 (T20): an unmasked client frame closes with 1002, also reported to onClose
    @Test(timeout = 20000)
    public void FR_WS_005_AC1_unmaskedFrameClosesWith1002() throws Exception {
        try (RawWebSocketClient c = open(listen())) {
            c.frame(true, TEXT, "hi".getBytes(StandardCharsets.UTF_8), false);
            assertClosedWith(c, 1002);
        }
        String e = event();
        assertTrue(e, e.startsWith("close 1002"));
    }

    // FR-WS-005 AC-2 (T20): C3 28 is invalid UTF-8: close 1007 and no onText
    @Test(timeout = 20000)
    public void FR_WS_005_AC2_invalidUtf8ClosesWith1007AndNoOnText() throws Exception {
        try (RawWebSocketClient c = open(listen())) {
            c.frame(true, TEXT, new byte[] {(byte) 0xC3, 0x28}, true);
            assertClosedWith(c, 1007);
        }
        String e = event();
        assertTrue(e, e.startsWith("close 1007")); // the first event: onText never ran
    }

    // FR-WS-005 AC-3 (T20): with the default limit, 1048576 bytes arrive whole and one frame of 1048577 closes 1009
    @Test(timeout = 30000)
    public void FR_WS_005_AC3_defaultLimitAcceptsOneMebibyteAndRefusesOneByteMore() throws Exception {
        int port = listen();
        char[] big = new char[1048576];
        Arrays.fill(big, 'a');
        try (RawWebSocketClient c = open(port)) {
            c.text(new String(big));
            String e = event();
            assertEquals(1048576 + 5, e.length());
            assertTrue(e.startsWith("text aaaa"));
            c.frameHeader(true, TEXT, 1048577);
            assertClosedWith(c, 1009);
        }
    }

    // SDS-AC-11; FR-WS-005 AC-4 (T20): maxMessageSize(4096) reaches both TinyHttpd limits: 4097 bytes as one frame
    // and as fragments 2048 + 2048 + 1 each close 1009, and 4096 bytes in four fragments arrive once, in order
    @Test(timeout = 20000)
    public void sdsAc11_FR_WS_005_AC4_limit4096RefusesOneFrameAndFragmentsAndJoinsFourFragments() throws Exception {
        app.webSocket(o -> o.maxMessageSize(4096));
        int port = listen();
        try (RawWebSocketClient c = open(port)) {
            c.frameHeader(true, BINARY, 4097);
            assertClosedWith(c, 1009);
        }
        assertTrue(event().startsWith("close 1009"));
        byte[] over = bytes(4097);
        try (RawWebSocketClient c = open(port)) {
            c.frame(false, BINARY, Arrays.copyOfRange(over, 0, 2048), true);
            c.frame(false, CONT, Arrays.copyOfRange(over, 2048, 4096), true);
            c.frame(true, CONT, Arrays.copyOfRange(over, 4096, 4097), true);
            assertClosedWith(c, 1009);
        }
        assertTrue(event().startsWith("close 1009"));
        byte[] whole = bytes(4096);
        try (RawWebSocketClient c = open(port)) {
            for (int i = 0; i < 4; i++) {
                c.frame(i == 3, i == 0 ? BINARY : CONT, Arrays.copyOfRange(whole, i * 1024, i * 1024 + 1024), true);
            }
            assertEquals("binary 4096", event());
            assertArrayEquals(whole, binaries.poll(5, TimeUnit.SECONDS));
        }
        assertTrue(event().startsWith("close 1006"));
        assertNull(binaries.poll());
    }

    // SDS-AC-11; FR-WS-005 AC-5 (T20): "Hel" + "lo" arrive once as "Hello"; a Ping between them gets its Pong
    @Test(timeout = 20000)
    public void sdsAc11_FR_WS_005_AC5_fragmentsJoinAndAnInterleavedPingIsAnswered() throws Exception {
        try (RawWebSocketClient c = open(listen())) {
            c.frame(false, TEXT, "Hel".getBytes(StandardCharsets.UTF_8), true);
            c.frame(true, PING, "mid".getBytes(StandardCharsets.UTF_8), true);
            Frame pong = c.readFrame(PONG);
            assertEquals("mid", pong.text());
            c.frame(true, CONT, "lo".getBytes(StandardCharsets.UTF_8), true);
            assertEquals("text Hello", event());
        }
    }

    // SDS-AC-11; FR-WS-005 AC-6 (T20): a Ping of 126 bytes and a Ping with FIN clear each close 1002
    @Test(timeout = 20000)
    public void sdsAc11_FR_WS_005_AC6_oversizedOrFragmentedPingClosesWith1002() throws Exception {
        int port = listen();
        try (RawWebSocketClient c = open(port)) {
            c.frameHeader(true, PING, 126);
            assertClosedWith(c, 1002);
        }
        try (RawWebSocketClient c = open(port)) {
            c.frame(false, PING, new byte[0], true);
            assertClosedWith(c, 1002);
        }
    }

    // SDS-AC-8; FR-WS-005 AC-7 (T21): a client Ping "abc" gets Pong "abc", and ws.ping() reaches the client as a Ping
    // written through the session's sender slot
    @Test(timeout = 20000)
    public void sdsAc8_FR_WS_005_AC7_pingIsAnsweredAndWsPingSendsAPing() throws Exception {
        try (RawWebSocketClient c = open(listen())) {
            c.frame(true, PING, "abc".getBytes(StandardCharsets.UTF_8), true);
            Frame pong = c.readFrame();
            assertEquals(PONG, pong.opcode);
            assertEquals("abc", pong.text());
            c.text("ping me");
            assertEquals("text ping me", event());
            Frame ping = c.readFrame();
            assertEquals(PING, ping.opcode);
            assertTrue(ping.fin);
            assertEquals(0, ping.payload.length);
        }
    }

    // SDS-AC-9; FR-WS-005 AC-8: close(4000, "bye") sends that frame, isOpen() is false at once, a later ping is
    // dropped, and after the client echoes 1000 onClose still gets (4000, "bye") and the TCP connection closes
    @Test(timeout = 20000)
    public void sdsAc9_FR_WS_005_AC8_closeHandshakeReportsTheSentCodeWhateverTheClientEchoes() throws Exception {
        try (RawWebSocketClient c = open(listen())) {
            c.text("close");
            assertEquals("text close", event());
            assertEquals("isOpen false", event());
            Frame f = c.readFrame();
            assertEquals(CLOSE, f.opcode);
            assertEquals(4000, f.closeCode());
            assertEquals("bye", f.closeReason());
            c.frame(true, CLOSE, new byte[] {0x03, (byte) 0xE8}, true);
            assertEquals("close 4000 bye", event());
            assertTrue("no Ping after close, then end of stream", c.endOfStream());
        }
        assertFalse(session.get().isOpen());
    }
}
