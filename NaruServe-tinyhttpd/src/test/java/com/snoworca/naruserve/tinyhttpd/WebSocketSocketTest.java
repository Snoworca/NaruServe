package com.snoworca.naruserve.tinyhttpd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.spi.WebSocketSettings;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.tinyhttpd.RawTargetSocketTest.Http;
import com.snoworca.naruserve.tinyhttpd.TinyHttpdWebSocketTest.Recording;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.After;
import org.junit.Test;

/**
 * T19 and the upgrade part of T35 over a TinyHttpd loopback socket. SDS-AC-n cites
 * docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-4.sds.md.
 */
public class WebSocketSocketTest {

    private static final Pattern BODY_RID = Pattern.compile("\"requestId\":\"([0-9a-f]{16})\"");

    private final RecordingLogger log = new RecordingLogger();
    private RestApp app;
    private final TinyHttpdAdapter adapter = new TinyHttpdAdapter();

    @After
    public void stop() {
        if (app != null) {
            app.shutdown(2, TimeUnit.SECONDS);
        }
        Http.stopQuietly(adapter);
    }

    private int echoApp(BlockingQueue<String> texts) throws Exception {
        app = Http.app(new TinyHttpdAdapter(), log);
        for (String path : new String[] {"/ws", "/nokey", "/v12"}) {
            app.ws(path, (req, ws) -> ws.onText(texts::add));
        }
        return app.listen(0).port();
    }

    /** The access log line of the one request for {@code path}. */
    private String accessLine(String path) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            for (String l : log.info()) {
                if (l.startsWith("GET " + path + " ")) {
                    return l;
                }
            }
            Thread.sleep(50);
        }
        throw new AssertionError("no access line for " + path + ": " + log.lines);
    }

    /** A handshake for {@code path} with {@code extra} header lines; Connection also carries close, so a refusal
     * ends the connection and the whole answer can be read. */
    private static String handshake(String path, String... extra) {
        return RawWebSocketClient.request(path, "Upgrade, close", extra);
    }

    private static final String KEY = "Sec-WebSocket-Key: " + RawWebSocketClient.KEY;

    // SDS-AC-7; FR-WS-002 AC-1 (T19): 101 with the RFC 6455 sample Accept, then a text frame reaches onText
    @Test(timeout = 20000)
    public void sdsAc7_FR_WS_002_AC1_handshakeGets101WithAcceptAndTextReachesOnText() throws Exception {
        BlockingQueue<String> texts = new ArrayBlockingQueue<>(4);
        int port = echoApp(texts);
        try (RawWebSocketClient c = RawWebSocketClient.open(port, "/ws")) {
            assertEquals(c.handshake, 101, c.status());
            assertEquals(RawWebSocketClient.ACCEPT, c.header("Sec-WebSocket-Accept"));
            c.text("hello");
            assertEquals("hello", texts.poll(5, TimeUnit.SECONDS));
        }
    }

    // FR-WS-002 AC-2 (T19): no Sec-WebSocket-Key gets 400 with the standard body and the request's id, no upgrade
    @Test(timeout = 20000)
    public void FR_WS_002_AC2_missingKeyGets400WithTheStandardBodyAndRequestId() throws Exception {
        int port = echoApp(new ArrayBlockingQueue<String>(1));
        String r = Http.send(port, handshake("/nokey", "Sec-WebSocket-Version: 13"));
        assertEquals(r, 400, Http.status(r));
        assertNull(r, Http.header(r, "Sec-WebSocket-Accept"));
        assertTrue(r, Http.body(r).contains("\"code\":\"BAD_REQUEST\""));
        Matcher m = BODY_RID.matcher(Http.body(r));
        assertTrue(r, m.find());
        assertTrue(accessLine("/nokey"), accessLine("/nokey").endsWith("rid=" + m.group(1)));
    }

    // FR-WS-002 AC-3 (T19): version 12 gets 426 + Sec-WebSocket-Version: 13, the request id, and a 426 access line
    @Test(timeout = 20000)
    public void FR_WS_002_AC3_version12Gets426WithVersionHeaderAndA426AccessLine() throws Exception {
        int port = echoApp(new ArrayBlockingQueue<String>(1));
        String r = Http.send(port, handshake("/v12", KEY, "Sec-WebSocket-Version: 12"));
        assertEquals(r, 426, Http.status(r));
        assertEquals(r, "13", Http.header(r, "Sec-WebSocket-Version"));
        Matcher m = BODY_RID.matcher(Http.body(r));
        assertTrue(r, m.find());
        String line = accessLine("/v12");
        assertTrue(line, line.startsWith("GET /v12 426 "));
        assertTrue(line, line.endsWith("rid=" + m.group(1)));
    }

    // FR-WS-002 AC-4: a refused Origin is the core's 403 (FR-WS-003) with the standard body and the request's id
    @Test(timeout = 20000)
    public void FR_WS_002_AC4_refusedOriginGets403WithTheStandardBodyAndRequestId() throws Exception {
        int port = echoApp(new ArrayBlockingQueue<String>(1));
        String r = Http.send(port, handshake("/ws", KEY, "Sec-WebSocket-Version: 13", "Origin: http://evil.example"));
        assertEquals(r, 403, Http.status(r));
        assertNull(r, Http.header(r, "Sec-WebSocket-Accept"));
        Matcher m = BODY_RID.matcher(Http.body(r));
        assertTrue(r, m.find());
        String line = accessLine("/ws");
        assertTrue(line, line.startsWith("GET /ws 403 "));
        assertTrue(line, line.endsWith("rid=" + m.group(1)));
    }

    // SDS-AC-3; FR-WS-002 AC-5, AC-4: the third upgrade with maxUpgradedConnections(2) gets 503 + Retry-After and
    // carries neither Sec-WebSocket-Accept nor Sec-WebSocket-Protocol
    @Test(timeout = 20000)
    public void sdsAc3_FR_WS_002_AC5_thirdUpgradeGets503WithoutAcceptHeader() throws Exception {
        BlockingQueue<String> texts = new ArrayBlockingQueue<>(4);
        app = Http.app(new TinyHttpdAdapter(), log);
        app.server(o -> o.maxUpgradedConnections(2));
        app.ws("/ws", (req, ws) -> ws.onText(texts::add));
        int port = app.listen(0).port();
        try (RawWebSocketClient a = RawWebSocketClient.open(port, "/ws");
                RawWebSocketClient b = RawWebSocketClient.open(port, "/ws")) {
            assertEquals(101, a.status());
            assertEquals(101, b.status());
            // Both read loops run once each echoes a message.
            a.text("a");
            b.text("b");
            assertNotNull(texts.poll(5, TimeUnit.SECONDS));
            assertNotNull(texts.poll(5, TimeUnit.SECONDS));
            String r = Http.send(port, handshake("/ws", KEY, "Sec-WebSocket-Version: 13"));
            assertEquals(r, 503, Http.status(r));
            assertEquals(r, "5", Http.header(r, "Retry-After"));
            assertNull(r, Http.header(r, "Sec-WebSocket-Accept"));
            assertNull(r, Http.header(r, "Sec-WebSocket-Protocol"));
            assertTrue(r, BODY_RID.matcher(Http.body(r)).find());
        }
    }

    // SDS-AC-3; FR-WS-002 AC-5, AC-4: an upgrade whose body is above maxDrainBytes gets 413 without Accept
    @Test(timeout = 20000)
    public void sdsAc3_FR_WS_002_AC5_oversizedUpgradeBodyGets413WithoutAcceptHeader() throws Exception {
        int port = echoApp(new ArrayBlockingQueue<String>(1));
        // The declared 70000 bytes are above TinyHttpd's 64 KiB maxDrainBytes; none are sent, so the close is a FIN.
        String r = Http.send(port, handshake("/ws", KEY, "Sec-WebSocket-Version: 13",
                "Content-Length: 70000"));
        assertEquals(r, 413, Http.status(r));
        assertNull(r, Http.header(r, "Sec-WebSocket-Accept"));
        assertNull(r, Http.header(r, "Sec-WebSocket-Protocol"));
        assertTrue(r, BODY_RID.matcher(Http.body(r)).find());
    }

    /** Binds the adapter with a handler that runs ex.webSocket on the worker and records how it ended. */
    private BlockingQueue<Object> bindWebSocket(Recording endpoint) throws Exception {
        BlockingQueue<Object> ended = new ArrayBlockingQueue<>(1);
        adapter.bind(Http.settings().build(), ex -> {
            try {
                ex.webSocket(new WebSocketSettings(4096, 30000), endpoint);
                ex.finish();
                ended.add("returned");
            } catch (Throwable t) {
                ended.add(t);
                ex.abort();
            }
        });
        return ended;
    }

    // SDS-AC-5; FR-WS-002 AC-7: aborting an open session closes the kept UpgradedConnection, so the client reads end
    // of stream within 1 s
    @Test(timeout = 20000)
    public void sdsAc5_FR_WS_002_AC7_abortGivesTheClientEndOfStreamWithinOneSecond() throws Exception {
        Recording endpoint = new Recording();
        BlockingQueue<Object> ended = bindWebSocket(endpoint);
        try (RawWebSocketClient c = RawWebSocketClient.open(adapter.boundPort(), "/ws")) {
            assertEquals(101, c.status());
            assertEquals("open", endpoint.events.poll(5, TimeUnit.SECONDS));
            c.setReadTimeout(1000);
            long t0 = System.nanoTime();
            endpoint.channel.abort();
            assertTrue("end of stream within 1 s", c.endOfStream());
            assertTrue(System.nanoTime() - t0 < TimeUnit.SECONDS.toNanos(1));
        }
        assertEquals("returned", ended.poll(10, TimeUnit.SECONDS));
    }

    // SDS-AC-7; FR-WS-002 AC-6: the exchange lock is free while the read loop runs, so another thread's synchronized
    // exchange call returns at once (refused: the exchange was started by the 101) and an abort from that thread ends
    // the read loop
    @Test(timeout = 20000)
    public void sdsAc7_FR_WS_002_AC6_exchangeLockIsFreeWhileTheReadLoopRuns() throws Exception {
        Recording endpoint = new Recording();
        AtomicReference<com.snoworca.naruserve.spi.HttpExchange> exchange = new AtomicReference<>();
        BlockingQueue<Object> ended = new ArrayBlockingQueue<>(1);
        adapter.bind(Http.settings().build(), ex -> {
            exchange.set(ex);
            try {
                ex.webSocket(new WebSocketSettings(4096, 30000), endpoint);
                ex.finish();
                ended.add("returned");
            } catch (Throwable t) {
                ended.add(t);
                ex.abort();
            }
        });
        try (RawWebSocketClient c = RawWebSocketClient.open(adapter.boundPort(), "/ws")) {
            assertEquals(101, c.status());
            assertEquals("open", endpoint.events.poll(5, TimeUnit.SECONDS));
            BlockingQueue<Object> commit = new ArrayBlockingQueue<>(1);
            Thread other = new Thread(() -> {
                try {
                    exchange.get().commit(200, Collections.emptyList(), 0);
                    commit.add("committed");
                } catch (Throwable t) {
                    commit.add(t);
                }
                endpoint.channel.abort();
            });
            other.start();
            Object r = commit.poll(5, TimeUnit.SECONDS);
            assertNotNull("commit blocked: the read loop holds the exchange lock", r);
            assertTrue(String.valueOf(r), r instanceof IllegalStateException);
            assertTrue(c.endOfStream());
        }
        assertEquals("returned", ended.poll(10, TimeUnit.SECONDS));
        assertEquals("close 1006", endpoint.events.poll(5, TimeUnit.SECONDS));
    }
}
