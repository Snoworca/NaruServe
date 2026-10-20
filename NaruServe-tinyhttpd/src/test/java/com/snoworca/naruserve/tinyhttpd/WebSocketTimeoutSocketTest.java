package com.snoworca.naruserve.tinyhttpd;

import static com.snoworca.naruserve.tinyhttpd.RawWebSocketClient.CLOSE;
import static com.snoworca.naruserve.tinyhttpd.RawWebSocketClient.PING;
import static com.snoworca.naruserve.tinyhttpd.RawWebSocketClient.PONG;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.tinyhttpd.RawTargetSocketTest.Http;
import com.snoworca.naruserve.tinyhttpd.RawWebSocketClient.Frame;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Test;

/**
 * T21 over a loopback socket: TinyHttpd's ping timeout, NaruServe's idle timeout and the handshake bound.
 * SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-6.sds.md.
 */
public class WebSocketTimeoutSocketTest {

    private final RestApp app = Http.app(new TinyHttpdAdapter(), new RecordingLogger());
    private final BlockingQueue<String> events = new ArrayBlockingQueue<>(64);

    @After
    public void stop() {
        app.shutdown(2, TimeUnit.SECONDS);
    }

    private int listen() {
        app.ws("/ws", (req, ws) -> ws.onClose((code, reason) -> events.add("close " + code + " " + reason)));
        return app.listen(0).port();
    }

    private static long msSince(long start) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }

    // SDS-AC-8; FR-WS-008 AC-2: with pingInterval(1 s) a client that sends nothing and answers no Ping gets a Ping
    // and then close 1001 within 3 s, and onClose receives 1001
    @Test(timeout = 20000)
    public void sdsAc8_FR_WS_008_AC2_aSilentClientGetsAPingThenClose1001WithinThreeSeconds() throws Exception {
        app.webSocket(o -> o.pingInterval(1, TimeUnit.SECONDS));
        try (RawWebSocketClient c = RawWebSocketClient.open(listen(), "/ws")) {
            assertEquals(c.handshake, 101, c.status());
            long start = System.nanoTime();
            c.setReadTimeout(5000);
            assertEquals(PING, c.readFrame().opcode);
            Frame f = c.readFrame(CLOSE);
            long ms = msSince(start);
            assertEquals(1001, f.closeCode());
            assertTrue("close after " + ms + " ms", ms <= 3000);
        }
        String closed = events.poll(5, TimeUnit.SECONDS);
        assertNotNull(closed);
        assertTrue(closed, closed.startsWith("close 1001"));
    }

    // SDS-AC-11; FR-WS-008 AC-4: a client that pings every 300 ms but sends no message gets a Pong for each Ping and
    // close (1001, "idle timeout") from the idle scheduler within 2 s; it answers the close and onClose gets 1001
    @Test(timeout = 20000)
    public void sdsAc11_FR_WS_008_AC4_aPingingClientIsClosedByTheIdleTimeout() throws Exception {
        app.webSocket(o -> o.idleTimeout(1, TimeUnit.SECONDS).pingInterval(30, TimeUnit.SECONDS));
        try (RawWebSocketClient c = RawWebSocketClient.open(listen(), "/ws")) {
            assertEquals(c.handshake, 101, c.status());
            long start = System.nanoTime();
            int pongs = 0;
            Frame close = null;
            while (close == null && msSince(start) < 5000) {
                c.frame(true, PING, "p".getBytes(StandardCharsets.UTF_8), true);
                Frame f = c.readFrame();
                if (f.opcode == PONG) {
                    pongs++;
                    Thread.sleep(300);
                } else {
                    assertEquals(CLOSE, f.opcode);
                    close = f;
                }
            }
            long ms = msSince(start);
            assertNotNull("no close within 5 s", close);
            assertEquals(1001, close.closeCode());
            assertEquals("idle timeout", close.closeReason());
            assertTrue("closed after " + ms + " ms", ms <= 2000);
            assertTrue("pongs " + pongs, pongs >= 2);
            c.frame(true, CLOSE, new byte[] {0x03, (byte) 0xE9}, true);
            assertTrue("TCP connection closed after the close handshake", c.endOfStream());
        }
        assertEquals("close 1001 idle timeout", events.poll(5, TimeUnit.SECONDS));
    }

    // SDS-AC-11; FR-WS-008 AC-7: headerTimeout(1 s) bounds the handshake: a client that stalls inside the request
    // headers of an upgrade is disconnected within 2 s
    @Test(timeout = 20000)
    public void sdsAc11_FR_WS_008_AC7_headerTimeoutCutsAStalledHandshakeWithinTwoSeconds() throws Exception {
        app.server(o -> o.headerTimeout(1, TimeUnit.SECONDS));
        int port = listen();
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            s.setSoTimeout(5000);
            s.getOutputStream().write("GET /ws HTTP/1.1\r\n".getBytes(StandardCharsets.ISO_8859_1));
            s.getOutputStream().flush();
            long start = System.nanoTime();
            InputStream in = s.getInputStream();
            try {
                while (in.read() >= 0) {
                    // whatever the server answers before it closes
                }
            } catch (SocketTimeoutException e) {
                throw new AssertionError("still connected after " + msSince(start) + " ms");
            } catch (IOException reset) {
                // a reset ends the connection too
            }
            long ms = msSince(start);
            assertTrue("disconnected after " + ms + " ms", ms <= 2000);
        }
    }
}
