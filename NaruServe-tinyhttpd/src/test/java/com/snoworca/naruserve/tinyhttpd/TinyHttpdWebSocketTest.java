package com.snoworca.naruserve.tinyhttpd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.HttpException;
import com.snoworca.naruserve.spi.WebSocketChannel;
import com.snoworca.naruserve.spi.WebSocketEndpoint;
import com.snoworca.naruserve.spi.WebSocketSettings;
import com.snoworca.naruserve.tinyhttpd.RawTargetSocketTest.Http;
import com.snoworca.tinyhttpd.TinyHttpd;
import com.snoworca.tinyhttpd.http.Handler;
import com.snoworca.tinyhttpd.http.ResponseWrapper;
import com.snoworca.tinyhttpd.http.UpgradedConnection;
import com.snoworca.tinyhttpd.websocket.WebSocketHandler;
import java.util.ArrayList;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Test;

/**
 * The TinyHttpd upgrade class against a plain TinyHttpd server, so each test controls the response it hands in.
 * SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-4.sds.md.
 */
public class TinyHttpdWebSocketTest {

    private static final WebSocketSettings SETTINGS = new WebSocketSettings(4096, 15000);
    private static final String NO_KEY = "GET /ws HTTP/1.1\r\nHost: 127.0.0.1\r\nUpgrade: websocket\r\n"
            + "Connection: Upgrade\r\nSec-WebSocket-Version: 13\r\n\r\n";
    private static final String VERSION_12 = "GET /ws HTTP/1.1\r\nHost: 127.0.0.1\r\nUpgrade: websocket\r\n"
            + "Connection: Upgrade\r\nSec-WebSocket-Key: " + RawWebSocketClient.KEY
            + "\r\nSec-WebSocket-Version: 12\r\n\r\n";

    private TinyHttpd server;
    private final TinyHttpdAdapter adapter = new TinyHttpdAdapter();

    /** What one run of the upgrade class did on the TinyHttpd worker. */
    private final BlockingQueue<Thread> handlerThreads = new ArrayBlockingQueue<>(4);
    private final AtomicReference<Throwable> thrown = new AtomicReference<>();
    private final BlockingQueue<String> ran = new ArrayBlockingQueue<>(4);
    private final AtomicInteger committed = new AtomicInteger();
    private final AtomicReference<String> acceptAfterRun = new AtomicReference<>("unset");
    private final AtomicReference<String> protocolAfterRun = new AtomicReference<>("unset");

    @After
    public void stop() {
        if (server != null) {
            server.stop(0);
        }
        Http.stopQuietly(adapter);
    }

    /** Endpoint recording each call with its thread. */
    static final class Recording implements WebSocketEndpoint {
        final List<String> calls = new CopyOnWriteArrayList<>();
        final BlockingQueue<String> events = new ArrayBlockingQueue<>(16);
        volatile Thread openThread;
        volatile WebSocketChannel channel;
        volatile RuntimeException throwOnOpen;
        volatile RuntimeException throwOnText;

        @Override
        public void onOpen(WebSocketChannel channel) {
            this.channel = channel;
            openThread = Thread.currentThread();
            record("open");
            if (throwOnOpen != null) {
                throw throwOnOpen;
            }
        }

        @Override
        public void onText(String text) {
            record("text " + text);
            if (throwOnText != null) {
                throw throwOnText;
            }
        }

        @Override
        public void onBinary(byte[] data) {
            record("binary " + data.length);
        }

        @Override
        public void onClose(int code, String reason) {
            record("close " + code);
        }

        private void record(String e) {
            calls.add(e);
            events.add(e);
        }
    }

    /** A plain TinyHttpd server whose handler runs the upgrade class over {@code wrap(res)} and records the run. */
    private int serve(Recording endpoint, ResponseFactory wrap) throws Exception {
        Handler h = (req, res) -> {
            handlerThreads.add(Thread.currentThread());
            com.snoworca.tinyhttpd.http.Response r = wrap.wrap(res);
            try {
                new TinyHttpdWebSocket(req, r, SETTINGS, endpoint, committed::incrementAndGet).run();
                ran.add("returned");
            } catch (Throwable t) {
                thrown.set(t);
                ran.add("threw");
            }
            acceptAfterRun.set(res.getHeader("Sec-WebSocket-Accept"));
            protocolAfterRun.set(res.getHeader("Sec-WebSocket-Protocol"));
            if (!res.isCommitted()) {
                res.setStatus(204); // the core's error path answers here in the adapter; this test only records
            }
        };
        server = TinyHttpdAdapter.configure(Http.settings().build(), h).build();
        server.start();
        return server.getPort();
    }

    interface ResponseFactory {
        com.snoworca.tinyhttpd.http.Response wrap(com.snoworca.tinyhttpd.http.Response res);
    }

    /**
     * A TinyHttpd response whose upgrade records that the handshake set Accept, sets Sec-WebSocket-Protocol as a
     * negotiated subprotocol would, and throws {@code e}.
     */
    private static ResponseFactory upgradeThrows(com.snoworca.tinyhttpd.http.HttpException e,
            AtomicReference<String> acceptSeen) {
        return res -> new ResponseWrapper(res) {
            @Override
            public UpgradedConnection upgrade(String protocol) throws com.snoworca.tinyhttpd.http.HttpException {
                acceptSeen.set(getHeader("Sec-WebSocket-Accept"));
                setHeader("Sec-WebSocket-Protocol", "chat");
                throw e;
            }
        };
    }

    private HttpException thrownHttpException() throws InterruptedException {
        assertEquals("threw", ran.poll(10, TimeUnit.SECONDS));
        Throwable t = thrown.get();
        assertTrue(String.valueOf(t), t instanceof HttpException);
        return (HttpException) t;
    }

    private static List<Map.Entry<String, String>> headers(String... pairs) {
        List<Map.Entry<String, String>> out = new ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.add(new SimpleImmutableEntry<>(pairs[i], pairs[i + 1]));
        }
        return out;
    }

    /** Records the four configuration calls; the base class keeps its own behaviour. */
    static final class RecordingHandler extends WebSocketHandler {
        final List<String> calls = new ArrayList<>();

        RecordingHandler() {
            super(handshake -> null);
        }

        @Override
        public WebSocketHandler allowAnyOrigin() {
            calls.add("allowAnyOrigin");
            return super.allowAnyOrigin();
        }

        @Override
        public WebSocketHandler maxFramePayloadBytes(int bytes) {
            calls.add("maxFramePayloadBytes " + bytes);
            return super.maxFramePayloadBytes(bytes);
        }

        @Override
        public WebSocketHandler maxMessageBytes(int bytes) {
            calls.add("maxMessageBytes " + bytes);
            return super.maxMessageBytes(bytes);
        }

        @Override
        public WebSocketHandler pingInterval(int millis) {
            calls.add("pingInterval " + millis);
            return super.pingInterval(millis);
        }
    }

    // SDS-AC-1; FR-WS-002 AC-6
    @Test
    public void sdsAc1_FR_WS_002_AC6_configureAllowsAnyOriginAndPassesTheLimitToBothLimitsAndThePingMillis() {
        RecordingHandler h = new RecordingHandler();
        assertSame(h, TinyHttpdWebSocket.configure(h, SETTINGS));
        List<String> sorted = new ArrayList<>(h.calls);
        Collections.sort(sorted);
        assertEquals(Arrays.asList("allowAnyOrigin", "maxFramePayloadBytes 4096", "maxMessageBytes 4096",
                "pingInterval 15000"), sorted);
    }

    // FR-WS-002 AC-10: the handler doing the framing is loaded from TinyHttpd's websocket artifact; the build's
    // checkDependencyRules task keeps every other module off com.snoworca.tinyhttpd artifacts
    @Test
    public void FR_WS_002_AC10_framingComesFromTheTinyHttpdWebsocketArtifact() {
        String from = WebSocketHandler.class.getProtectionDomain().getCodeSource().getLocation().toString();
        assertTrue(from, from.contains("tinyhttpd-websocket") || from.contains("/websocket/build/"));
    }

    // SDS-AC-1; FR-WS-002 AC-6: handle runs on the calling worker, and nothing reaches the endpoint before onOpen
    @Test(timeout = 20000)
    public void sdsAc1_FR_WS_002_AC6_handleRunsOnTheCallingWorkerAndTheFactoryRunsNoUserCode() throws Exception {
        Recording endpoint = new Recording();
        int port = serve(endpoint, res -> res);
        try (RawWebSocketClient c = RawWebSocketClient.open(port, "/ws", "Origin: http://elsewhere.example")) {
            assertEquals(c.handshake, 101, c.status());
            assertEquals(RawWebSocketClient.ACCEPT, c.header("Sec-WebSocket-Accept"));
            assertEquals("open", endpoint.events.poll(5, TimeUnit.SECONDS));
            assertSame(handlerThreads.poll(5, TimeUnit.SECONDS), endpoint.openThread);
            assertEquals(Collections.singletonList("open"), endpoint.calls);
            c.text("hi");
            assertEquals("text hi", endpoint.events.poll(5, TimeUnit.SECONDS));
        }
        assertEquals("close 1006", endpoint.events.poll(5, TimeUnit.SECONDS));
        assertEquals("returned", ran.poll(5, TimeUnit.SECONDS));
    }

    // SDS-AC-2; FR-WS-002 AC-4: no Sec-WebSocket-Key gives a NaruServe 400
    @Test(timeout = 20000)
    public void sdsAc2_FR_WS_002_AC4_missingKeyBecomesNaruServe400() throws Exception {
        Recording endpoint = new Recording();
        int port = serve(endpoint, res -> res);
        try (RawWebSocketClient c = RawWebSocketClient.raw(port, NO_KEY)) {
            HttpException e = thrownHttpException();
            assertEquals(400, e.status());
            assertEquals(Collections.emptyList(), e.headers());
            assertEquals(204, c.status());
        }
        assertTrue(endpoint.calls.isEmpty());
    }

    // SDS-AC-2; FR-WS-002 AC-4: version 12 gives a NaruServe 426 carrying Sec-WebSocket-Version: 13
    @Test(timeout = 20000)
    public void sdsAc2_FR_WS_002_AC4_version12BecomesNaruServe426WithTheVersionHeader() throws Exception {
        int port = serve(new Recording(), res -> res);
        try (RawWebSocketClient c = RawWebSocketClient.raw(port, VERSION_12)) {
            HttpException e = thrownHttpException();
            assertEquals(426, e.status());
            assertEquals(headers("Sec-WebSocket-Version", "13"), e.headers());
        }
    }

    // SDS-AC-2, SDS-AC-3, SDS-AC-4; FR-WS-002 AC-4, AC-5, AC-8: an upgrade refusing with 413 maps to a NaruServe 413,
    // drops the Accept header TinyHttpd already set, and never marks the exchange started
    @Test(timeout = 20000)
    public void sdsAc2_sdsAc3_sdsAc4_FR_WS_002_AC4_AC5_AC8_upgrade413MapsDropsAcceptAndLeavesUnstarted()
            throws Exception {
        AtomicReference<String> acceptSeen = new AtomicReference<>();
        Recording endpoint = new Recording();
        com.snoworca.tinyhttpd.http.HttpException refusal =
                new com.snoworca.tinyhttpd.http.HttpException(413, "upgrade request body too large");
        int port = serve(endpoint, upgradeThrows(refusal, acceptSeen));
        try (RawWebSocketClient c = RawWebSocketClient.open(port, "/ws")) {
            HttpException e = thrownHttpException();
            assertEquals(413, e.status());
            assertEquals("upgrade request body too large", e.getMessage());
            assertSame(refusal, e.getCause());
            assertEquals(Collections.emptyList(), e.headers());
            assertEquals(RawWebSocketClient.ACCEPT, acceptSeen.get());
            assertNull(acceptAfterRun.get());
            assertNull(protocolAfterRun.get());
            assertEquals(0, committed.get());
            assertEquals(204, c.status());
            assertNull(c.header("Sec-WebSocket-Accept"));
            assertNull(c.header("Sec-WebSocket-Protocol"));
        }
        assertTrue(endpoint.calls.isEmpty());
    }

    // SDS-AC-2, SDS-AC-3, SDS-AC-4; FR-WS-002 AC-4, AC-5, AC-8: 503 + Retry-After keeps its header in order
    @Test(timeout = 20000)
    public void sdsAc2_sdsAc3_sdsAc4_FR_WS_002_AC4_AC5_AC8_upgrade503KeepsRetryAfterAndLeavesUnstarted()
            throws Exception {
        AtomicReference<String> acceptSeen = new AtomicReference<>();
        int port = serve(new Recording(), upgradeThrows(new com.snoworca.tinyhttpd.http.HttpException(503,
                "too many upgraded connections").withHeader("Retry-After", "5").withHeader("X-Second", "2"),
                acceptSeen));
        try (RawWebSocketClient c = RawWebSocketClient.open(port, "/ws")) {
            HttpException e = thrownHttpException();
            assertEquals(503, e.status());
            assertEquals(headers("Retry-After", "5", "X-Second", "2"), e.headers());
            assertNotNull(acceptSeen.get());
            assertNull(acceptAfterRun.get());
            assertNull(protocolAfterRun.get());
            assertEquals(0, committed.get());
        }
    }

    // SDS-AC-4; FR-WS-002 AC-8: onCommitted runs once when 101 was written
    @Test(timeout = 20000)
    public void sdsAc4_FR_WS_002_AC8_onCommittedRunsOnceAfter101() throws Exception {
        Recording endpoint = new Recording();
        int port = serve(endpoint, res -> res);
        try (RawWebSocketClient c = RawWebSocketClient.open(port, "/ws")) {
            assertEquals(101, c.status());
            assertEquals("open", endpoint.events.poll(5, TimeUnit.SECONDS));
            assertEquals(1, committed.get());
        }
    }

    // SDS-AC-4; FR-WS-002 AC-8: over the TinyHttpd exchange, a real 413 from the upgrade (body above maxDrainBytes)
    // leaves the exchange unstarted, so the error answer commits on it without IllegalStateException
    @Test(timeout = 20000)
    public void sdsAc4_FR_WS_002_AC8_errorPathCommitsOnTheSameExchangeAfterARefusedUpgrade() throws Exception {
        AtomicReference<Object> outcome = new AtomicReference<>();
        BlockingQueue<Object> done = new ArrayBlockingQueue<>(1);
        adapter.bind(Http.settings().build(), ex -> {
            try {
                ex.webSocket(SETTINGS, new Recording());
                outcome.set("upgraded");
            } catch (HttpException e) {
                try {
                    ex.commit(e.status(), headers("Content-Length", "0"), 0);
                    ex.finish();
                    outcome.set(e.status());
                } catch (Throwable t) {
                    outcome.set(t);
                    ex.abort();
                }
            } catch (Throwable t) {
                outcome.set(t);
                ex.abort();
            }
            done.add(Boolean.TRUE);
        });
        // The declared body alone is above TinyHttpd's 64 KiB maxDrainBytes; sending none keeps the close a FIN.
        try (RawWebSocketClient c = RawWebSocketClient.open(adapter.boundPort(), "/ws", "Content-Length: 70000")) {
            assertNotNull(done.poll(10, TimeUnit.SECONDS));
            assertEquals(413, outcome.get());
            assertEquals(413, c.status());
            assertNull(c.header("Sec-WebSocket-Accept"));
        }
    }

    // SDS-AC-6; FR-WS-002 AC-9: a throwing endpoint call is caught, the channel aborted and TinyHttpd's callback
    // returns normally, so TinyHttpd never sends its own 1011 close
    @Test(timeout = 20000)
    public void sdsAc6_FR_WS_002_AC9_throwingEndpointIsCaughtAndTheChannelAborted() throws Exception {
        Recording endpoint = new Recording();
        endpoint.throwOnOpen = new IllegalStateException("endpoint bug");
        int port = serve(endpoint, res -> res);
        try (RawWebSocketClient c = RawWebSocketClient.open(port, "/ws")) {
            assertEquals(101, c.status());
            assertEquals("open", endpoint.events.poll(5, TimeUnit.SECONDS));
            assertTrue("no close frame, the socket just ends", c.endOfStream());
        }
        assertEquals("returned", ran.poll(10, TimeUnit.SECONDS));
        assertNull(thrown.get());
        assertEquals("close 1006", endpoint.events.poll(5, TimeUnit.SECONDS));
        assertFalse(endpoint.channel.isOpen());
    }

    // SDS-AC-6; FR-WS-002 AC-9: a throwing endpoint call after onOpen is caught the same way: the channel kept from
    // onOpen is aborted and TinyHttpd's callback returns normally
    @Test(timeout = 20000)
    public void sdsAc6_FR_WS_002_AC9_throwingOnTextIsCaughtAndTheChannelAborted() throws Exception {
        Recording endpoint = new Recording();
        endpoint.throwOnText = new IllegalStateException("endpoint bug");
        int port = serve(endpoint, res -> res);
        try (RawWebSocketClient c = RawWebSocketClient.open(port, "/ws")) {
            assertEquals(101, c.status());
            assertEquals("open", endpoint.events.poll(5, TimeUnit.SECONDS));
            c.text("boom");
            assertEquals("text boom", endpoint.events.poll(5, TimeUnit.SECONDS));
            assertTrue("no close frame, the socket just ends", c.endOfStream());
        }
        assertEquals("returned", ran.poll(10, TimeUnit.SECONDS));
        assertNull(thrown.get());
        assertEquals("close 1006", endpoint.events.poll(5, TimeUnit.SECONDS));
    }
}
