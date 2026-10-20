package com.snoworca.naruserve.tinyhttpd;

import static com.snoworca.naruserve.tinyhttpd.RawWebSocketClient.CLOSE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.tinyhttpd.RawTargetSocketTest.Http;
import com.snoworca.naruserve.tinyhttpd.RawWebSocketClient.Frame;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Test;

/**
 * T21 over a loopback socket: the close code onClose reports and handler failures after 101.
 * SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-5.sds.md.
 */
public class WebSocketSessionSocketTest {

    private final RecordingLogger logger = new RecordingLogger();
    private final RestApp app = Http.app(new TinyHttpdAdapter(), logger);
    private final BlockingQueue<String> events = new ArrayBlockingQueue<>(64);

    @After
    public void stop() {
        app.shutdown(2, TimeUnit.SECONDS);
    }

    /** Text "close" closes with (4000, "bye"); every onClose is recorded. */
    private int listen() throws Exception {
        app.ws("/ws", (req, ws) -> {
            ws.onText(t -> {
                if ("close".equals(t)) {
                    ws.close(4000, "bye");
                }
            });
            ws.onClose((code, reason) -> events.add("close " + code + " " + reason));
        });
        app.ws("/fail", (req, ws) -> {
            ws.onError(e -> events.add("error " + e.getClass().getSimpleName() + " " + e.getMessage()));
            ws.onClose((code, reason) -> events.add("close " + code + " " + reason));
            throw new IOException("no db");
        });
        app.ws("/bug", (req, ws) -> {
            ws.onError(e -> events.add("error " + e.getClass().getSimpleName() + " " + e.getMessage()));
            ws.onClose((code, reason) -> events.add("close " + code + " " + reason));
            throw new IllegalStateException("handler bug");
        });
        return app.listen(0).port();
    }

    private String event() throws InterruptedException {
        return events.poll(5, TimeUnit.SECONDS);
    }

    // SDS-AC-12; FR-WS-006 AC-9: a close started by the client reports the client's code and reason
    @Test(timeout = 20000)
    public void sdsAc12_FR_WS_006_AC9_clientCloseReportsTheClientPair() throws Exception {
        try (RawWebSocketClient c = RawWebSocketClient.open(listen(), "/ws")) {
            assertEquals(c.handshake, 101, c.status());
            byte[] reason = "done".getBytes(StandardCharsets.UTF_8);
            byte[] payload = new byte[2 + reason.length];
            payload[0] = 0x03;
            payload[1] = (byte) 0xE8;
            System.arraycopy(reason, 0, payload, 2, reason.length);
            c.frame(true, CLOSE, payload, true);
            assertEquals(1000, c.readFrame(CLOSE).closeCode());
            assertEquals("close 1000 done", event());
        }
    }

    // SDS-AC-12; FR-WS-006 AC-9: a close the core started reports its own pair when the client never answers the
    // close frame and drops the socket, where TinyHttpd itself delivers 1006
    @Test(timeout = 20000)
    public void sdsAc12_FR_WS_006_AC9_coreCloseWinsOverASilentClient() throws Exception {
        try (RawWebSocketClient c = RawWebSocketClient.open(listen(), "/ws")) {
            assertEquals(c.handshake, 101, c.status());
            c.text("close");
            Frame f = c.readFrame(CLOSE);
            assertEquals(4000, f.closeCode());
        } // closed without a close frame of its own
        assertEquals("close 4000 bye", event());
    }

    // SDS-AC-2 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-6.sds.md; FR-WS-007 AC-6: over TinyHttpd a
    // 2000-byte message on the empty queue of sendQueueSize(1000) is dropped, the send returns normally, the client
    // gets close (1008, "send queue overflow"), and onClose reports that pair though the client never answers
    @Test(timeout = 20000)
    public void sdsAc2_FR_WS_007_AC6_overflowClosesWith1008ToASilentClient() throws Exception {
        char[] big = new char[2000];
        java.util.Arrays.fill(big, 'x');
        app.webSocket(o -> o.sendQueueSize(1000));
        app.ws("/big", (req, ws) -> {
            ws.onClose((code, reason) -> events.add("close " + code + " " + reason));
            ws.onText(t -> {
                ws.sendText(new String(big));
                events.add("returned");
            });
        });
        try (RawWebSocketClient c = RawWebSocketClient.open(app.listen(0).port(), "/big")) {
            assertEquals(c.handshake, 101, c.status());
            c.text("go");
            Frame f = c.readFrame(CLOSE);
            assertEquals(1008, f.closeCode());
            assertEquals("send queue overflow", f.closeReason());
            assertEquals("returned", event());
        } // closed without a close frame of its own
        assertEquals("close 1008 send queue overflow", event());
    }

    // SDS-AC-13; FR-WS-006 AC-10: a handler exception after 101 reaches the onError listener registered before it and
    // closes 1011; the client sees the 101 head and then a close frame, no other status line
    @Test(timeout = 20000)
    public void sdsAc13_FR_WS_006_AC10_handlerExceptionClosesWith1011After101() throws Exception {
        int port = listen();
        String[][] cases = {{"/fail", "IOException", "no db"}, {"/bug", "IllegalStateException", "handler bug"}};
        for (String[] k : cases) {
            try (RawWebSocketClient c = RawWebSocketClient.open(port, k[0])) {
                assertEquals(c.handshake, 101, c.status());
                assertEquals(1, c.handshake.split("HTTP/1.1", -1).length - 1);
                Frame f = c.readFrame(CLOSE);
                assertEquals(1011, f.closeCode());
                c.frame(true, CLOSE, new byte[] {0x03, (byte) 0xF3}, true);
                assertTrue("TCP connection closed after the close handshake", c.endOfStream());
            }
            assertEquals("error " + k[1] + " " + k[2], event());
            assertEquals("close 1011 ", event());
            int found = 0;
            for (String line : new ArrayList<>(logger.lines)) {
                found += line.matches("ERROR websocket [0-9a-f]{16} handler failed " + k[2]) ? 1 : 0;
            }
            assertEquals(logger.lines.toString(), 1, found);
        }
        // Nothing escaped into TinyHttpd: its own failure line would name the exception a second time.
        assertEquals(logger.lines.toString(), 2, logger.errors.size());
    }

    // SDS-AC-1 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-12.sds.md; FR-APP-019 AC-8, AC-9: over TinyHttpd
    // the 101 line is in the log before the client sent any frame, and the close line carries the client's code and
    // the handshake's rid (T38)
    @Test(timeout = 20000)
    public void wave7SdsAc1_FR_APP_019_AC8_AC9_handshakeLineBeforeTheFirstFrameThenTheCloseLine() throws Exception {
        try (RawWebSocketClient c = RawWebSocketClient.open(listen(), "/ws")) {
            assertEquals(c.handshake, 101, c.status());
            String open = RecordingLogger.awaitMatching(() -> logger.lines, "INFO GET /ws 101 \\d+ms in=0 out=0 rid=[0-9a-f]{16}", 1).get(0);
            String rid = open.substring(open.length() - 16);
            c.frame(true, CLOSE, new byte[] {0x03, (byte) 0xE8}, true);
            assertEquals(1000, c.readFrame(CLOSE).closeCode());
            RecordingLogger.awaitMatching(() -> logger.lines, "INFO WS /ws 1000 \\d+ms rid=" + rid, 1).get(0);
        }
        assertEquals("close 1000 ", event());
        int lines = 0;
        for (String line : new ArrayList<>(logger.lines)) {
            lines += line.startsWith("INFO GET /ws ") || line.startsWith("INFO WS /ws ") ? 1 : 0;
        }
        assertEquals(logger.lines.toString(), 2, lines);
    }
}
