package com.snoworca.naruserve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.internal.DefaultRestApp;
import com.snoworca.naruserve.spi.HttpExchange;
import com.snoworca.naruserve.spi.StreamDrain;
import com.snoworca.naruserve.spi.UpgradeHandler;
import com.snoworca.naruserve.spi.WebSocketChannel;
import com.snoworca.naruserve.spi.WebSocketEndpoint;
import com.snoworca.naruserve.spi.WebSocketSettings;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.InMemoryWebSocketChannel;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.testing.TestApp;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/** Upgrade dispatch. SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-3.sds.md. */
public class WebSocketDispatchTest {

    private final InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
    private final TestApp t = TestApp.create(adapter);
    private final List<String> log = Collections.synchronizedList(new ArrayList<String>());
    private final AtomicInteger handled = new AtomicInteger();

    /** What the test does as the peer while the connection is open. */
    interface Peer {
        void run(InMemoryWebSocketChannel channel) throws Exception;
    }

    /** A GET to {@code target} with {@code Connection: Upgrade} and {@code Upgrade: websocket}, then the pairs. */
    static ClientRequest upgradeRequest(String target, String... headerPairs) {
        ClientRequest r = ClientRequest.of("GET", target).header("Connection", "Upgrade").header("Upgrade", "websocket");
        for (int i = 0; i < headerPairs.length; i += 2) {
            r.header(headerPairs[i], headerPairs[i + 1]);
        }
        return r;
    }

    /** Submits an accepted upgrade, runs {@code peer} on its channel, closes from the peer and returns the response. */
    static ClientResponse accepted(InMemoryHttpAdapter a, ClientRequest r, Peer peer) throws Exception {
        Future<ClientResponse> f = a.submit(r);
        InMemoryWebSocketChannel channel = a.nextChannel(5, TimeUnit.SECONDS);
        try {
            peer.run(channel);
        } finally {
            channel.clientClose(1000, ""); // a failed assertion must not leave the read loop blocked
        }
        return f.get(10, TimeUnit.SECONDS);
    }

    static ClientResponse accepted(InMemoryHttpAdapter a, ClientRequest r) throws Exception {
        return accepted(a, r, ch -> { });
    }

    /** The standard error body of FR-APP-006 with a request id. */
    static void assertError(int status, String code, String message, ClientResponse r) {
        assertEquals(status, r.status());
        assertEquals("application/json; charset=UTF-8", r.header("Content-Type"));
        String body = new String(r.body(), StandardCharsets.UTF_8);
        assertTrue(body, body.matches("\\{\"error\":\\{\"code\":\"" + code + "\",\"message\":\"" + message
                + "\",\"requestId\":\"[0-9a-f]{16}\"}}"));
    }

    // SDS-AC-1; FR-WS-001 AC-1
    @Test(timeout = 20000)
    public void sdsAc1_FR_WS_001_AC1_globalAndPrefixMiddlewareReturnBeforeTheUpgrade() throws Exception {
        t.app().use((req, res, next) -> {
            log.add("global");
            next.run();
            log.add("global returned");
        });
        t.app().use("/ws", (req, res, next) -> {
            log.add("prefix");
            next.run();
            log.add("prefix returned");
        });
        t.app().ws("/ws/:room", (req, ws) -> log.add("handler " + req.param("room")));
        t.start();
        ClientResponse r = accepted(adapter, upgradeRequest("/ws/chat"));
        assertEquals(101, r.status());
        assertEquals(Arrays.asList("global", "prefix", "prefix returned", "global returned", "handler chat"), log);
    }

    // SDS-AC-2; FR-WS-001 AC-3
    @Test(timeout = 20000)
    public void sdsAc2_FR_WS_001_AC3_handlerRunsOnceFromOnOpenAfter101AndNeverOnARefusal() throws Exception {
        UpgradeExchange ex = new UpgradeExchange();
        DefaultRestApp app = new DefaultRestApp();
        app.ws("/ws", (req, ws) -> {
            handled.incrementAndGet();
            log.add("101 " + ex.wrote101 + " open " + ex.channel.isOpen());
        });
        app.freeze();
        app.dispatcher().handle(ex);
        assertEquals(1, handled.get());
        assertEquals(Collections.singletonList("101 true open true"), log);

        t.app().ws("/ws", (req, ws) -> handled.incrementAndGet());
        t.start();
        adapter.refuseWebSocket(new HttpException(503));
        assertError(503, "SERVICE_UNAVAILABLE", "Service Unavailable", adapter.send(upgradeRequest("/ws")));
        assertEquals(1, handled.get());
    }

    // SDS-AC-4; FR-WS-001 AC-8
    @Test(timeout = 20000)
    public void sdsAc4_FR_WS_001_AC8_closedUpgradeFinishesOnceWithoutAnHttpResponse() throws Exception {
        RecordingLogger logger = new RecordingLogger();
        UpgradeExchange ex = new UpgradeExchange();
        DefaultRestApp app = new DefaultRestApp();
        app.logger(logger);
        app.ws("/ws", (req, ws) -> handled.incrementAndGet());
        app.freeze();
        app.dispatcher().handle(ex);
        assertEquals(1, handled.get());
        assertEquals(0, ex.finishesWhenWebSocketReturned);
        assertEquals(1, ex.finishes.get());
        assertEquals(0, ex.commits.get());
        assertEquals(0, ex.aborts.get());
        assertEquals(logger.lines.toString(), 0, logger.count("WARN"));
    }

    // SDS-AC-5; FR-WS-001 AC-8
    @Test(timeout = 20000)
    public void sdsAc5_FR_WS_001_AC8_refusalGoesToTheErrorPathAndAFailureAfterOpenIsLogged() throws Exception {
        t.app().ws("/ws", (req, ws) -> handled.incrementAndGet());
        t.start();
        adapter.refuseWebSocket(new HttpException(400));
        assertError(400, "BAD_REQUEST", "Bad Request", adapter.send(upgradeRequest("/ws")));
        assertEquals(0, handled.get());

        RecordingLogger logger = new RecordingLogger();
        UpgradeExchange ex = new UpgradeExchange();
        ex.afterOpen = new RuntimeException("connection lost");
        DefaultRestApp app = new DefaultRestApp();
        app.logger(logger);
        app.ws("/ws", (req, ws) -> handled.incrementAndGet());
        app.freeze();
        app.dispatcher().handle(ex);
        assertEquals(1, handled.get());
        boolean found = false;
        for (String line : new ArrayList<>(logger.lines)) {
            found |= line.matches("ERROR websocket [0-9a-f]{16} failed connection lost");
        }
        assertTrue(logger.lines.toString(), found);
        assertEquals(1, ex.finishes.get());
        assertEquals(0, ex.commits.get());
        assertEquals(0, ex.aborts.get());
    }

    // SDS-AC-5; FR-WS-001 AC-8
    @Test(timeout = 20000)
    public void sdsAc5_FR_WS_001_AC8_ioFailureAfterOpenIsLoggedAndAFailedFinishAborts() throws Exception {
        RecordingLogger logger = new RecordingLogger();
        UpgradeExchange ex = new UpgradeExchange();
        ex.afterOpen = new java.io.IOException("reset");
        ex.finishFails = true;
        DefaultRestApp app = new DefaultRestApp();
        app.logger(logger);
        app.ws("/ws", (req, ws) -> handled.incrementAndGet());
        app.freeze();
        app.dispatcher().handle(ex);
        assertEquals(1, handled.get());
        boolean found = false;
        for (String line : new ArrayList<>(logger.lines)) {
            found |= line.matches("ERROR websocket [0-9a-f]{16} failed reset");
        }
        assertTrue(logger.lines.toString(), found);
        assertEquals(1, ex.finishes.get());
        assertEquals(0, ex.commits.get());
        assertEquals(1, ex.aborts.get());
    }

    // SDS-AC-7 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-6.sds.md; FR-WS-007 AC-8: after
    // HttpExchange.webSocket returned, the exchange finishes only once the session's held sender task ended
    @Test(timeout = 20000)
    public void sdsAc7_FR_WS_007_AC8_theExchangeFinishesOnlyAfterTheHeldSenderTaskEnded() throws Exception {
        CountDownLatch closed = new CountDownLatch(1);
        t.app().ws("/ws", (req, ws) -> {
            ws.onText(text -> ws.sendText("reply"));
            ws.onClose((code, reason) -> closed.countDown());
        });
        t.start();
        Future<ClientResponse> f = adapter.submit(upgradeRequest("/ws"));
        InMemoryWebSocketChannel ch = adapter.nextChannel(5, TimeUnit.SECONDS);
        CountDownLatch release = ch.hold("sendText");
        try {
            ch.clientText("go");
            ch.clientClose(1000, "");
            // onClose ran on the exchange thread, so HttpExchange.webSocket returns right after it
            assertTrue(closed.await(5, TimeUnit.SECONDS));
            Thread.sleep(300);
            assertTrue("the exchange finished while the sender task was held", !f.isDone());
        } finally {
            release.countDown();
        }
        assertEquals(101, f.get(5, TimeUnit.SECONDS).status());
    }

    // SDS-AC-7 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-6.sds.md; FR-WS-007 AC-8: when
    // HttpExchange.webSocket throws after open, the exchange still finishes only after the held sender task ended
    @Test(timeout = 20000)
    public void sdsAc7_FR_WS_007_AC8_aThrowAfterOpenStillWaitsForTheHeldSenderTask() throws Exception {
        UpgradeExchange ex = new UpgradeExchange();
        ex.afterOpen = new RuntimeException("connection lost");
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch writing = new CountDownLatch(1);
        ex.channel.beforeSend = () -> {
            writing.countDown();
            release.await(10, TimeUnit.SECONDS);
        };
        DefaultRestApp app = new DefaultRestApp();
        app.logger(new RecordingLogger());
        app.ws("/ws", (req, ws) -> ws.sendText("x"));
        app.freeze();
        Thread exchange = new Thread(() -> {
            try {
                app.dispatcher().handle(ex);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
        exchange.start();
        try {
            assertTrue(writing.await(5, TimeUnit.SECONDS));
            Thread.sleep(300);
            assertEquals("the exchange finished while the sender task was held", 0, ex.finishes.get());
            assertTrue(exchange.isAlive());
        } finally {
            release.countDown();
        }
        exchange.join(5000);
        assertEquals(1, ex.finishes.get());
    }

    // SDS-AC-11; FR-WS-004 AC-1, FR-WS-004 AC-4, FR-WS-001 AC-7
    @Test(timeout = 20000)
    public void sdsAc11_FR_WS_004_AC1_AC4_plainGetToAWebSocketOnlyPathGets426AndAGetRouteServesIt() throws Exception {
        t.app().ws("/live", (req, ws) -> handled.incrementAndGet());
        t.app().get("/both", (req, res) -> res.send("g"));
        t.app().ws("/both", (req, ws) -> handled.incrementAndGet());
        ClientResponse r = t.send("GET", "/live");
        assertError(426, "UPGRADE_REQUIRED", "Upgrade Required", r);
        assertEquals("websocket", r.header("Upgrade"));
        assertEquals("Upgrade", r.header("Connection"));
        assertEquals(0, handled.get());

        ClientResponse g = t.send("GET", "/both");
        assertEquals(200, g.status());
        assertEquals("g", g.text());
        assertEquals(0, handled.get());
        assertEquals(101, accepted(adapter, upgradeRequest("/both")).status());
        assertEquals(1, handled.get());
    }

    // FR-WS-001 AC-4
    @Test(timeout = 20000)
    public void FR_WS_001_AC4_sessionStaysOpenAfterTheHandlerReturned() throws Exception {
        LinkedBlockingQueue<String> got = new LinkedBlockingQueue<>();
        t.app().ws("/ws", (req, ws) -> ws.onText(got::add));
        t.start();
        ClientResponse r = accepted(adapter, upgradeRequest("/ws"), ch -> {
            Thread.sleep(500);
            ch.clientText("later");
            assertEquals("later", got.poll(5, TimeUnit.SECONDS));
        });
        assertEquals(101, r.status());
    }

    // FR-WS-001 AC-5
    @Test(timeout = 20000)
    public void FR_WS_001_AC5_noMiddlewareRunsPerMessage() throws Exception {
        AtomicInteger mw = new AtomicInteger();
        CountDownLatch messages = new CountDownLatch(5);
        t.app().use((req, res, next) -> {
            mw.incrementAndGet();
            next.run();
        });
        t.app().ws("/ws", (req, ws) -> {
            ws.onText(text -> messages.countDown());
            ws.onBinary(data -> messages.countDown());
        });
        t.start();
        accepted(adapter, upgradeRequest("/ws"), ch -> {
            ch.clientText("1");
            ch.clientBinary(new byte[] {1});
            ch.clientText("2");
            ch.clientBinary(new byte[] {2});
            ch.clientText("3");
            assertTrue(messages.await(5, TimeUnit.SECONDS));
        });
        assertEquals(1, mw.get());
    }

    static final class Clock {
    }

    // FR-WS-001 AC-6
    @Test(timeout = 20000)
    public void FR_WS_001_AC6_reqServiceInTheHandlerIsTheOnStartInstance() throws Exception {
        Clock clock = new Clock();
        AtomicReference<Object> seen = new AtomicReference<>();
        t.app().onStart(ctx -> ctx.register(Clock.class, clock));
        t.app().ws("/ws", (req, ws) -> seen.set(req.service(Clock.class)));
        t.start();
        accepted(adapter, upgradeRequest("/ws"));
        assertSame(clock, seen.get());
    }

    /** Hand-written exchange: webSocket marks 101, opens {@link #channel}, then the peer closes it at once. */
    static final class UpgradeExchange implements HttpExchange {
        final AtomicInteger commits = new AtomicInteger();
        final AtomicInteger finishes = new AtomicInteger();
        final AtomicInteger aborts = new AtomicInteger();
        final PeerChannel channel = new PeerChannel();
        volatile boolean wrote101;
        volatile int finishesWhenWebSocketReturned = -1;
        /** An IOException or RuntimeException webSocket throws after onOpen; null closes normally. */
        volatile Exception afterOpen;
        volatile boolean finishFails;

        @Override
        public String methodToken() {
            return "GET";
        }

        @Override
        public String rawPath() {
            return "/ws";
        }

        @Override
        public String rawQuery() {
            return null;
        }

        @Override
        public String httpVersion() {
            return "HTTP/1.1";
        }

        @Override
        public List<Map.Entry<String, String>> headers() {
            return Arrays.<Map.Entry<String, String>>asList(new SimpleImmutableEntry<>("Connection", "Upgrade"),
                    new SimpleImmutableEntry<>("Upgrade", "websocket"));
        }

        @Override
        public InputStream requestBody() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public String remoteAddress() {
            return "127.0.0.1";
        }

        @Override
        public boolean isSecure() {
            return false;
        }

        @Override
        public OutputStream commit(int status, List<Map.Entry<String, String>> headers, long contentLength) {
            commits.incrementAndGet();
            return new ByteArrayOutputStream();
        }

        @Override
        public void finish() throws IOException {
            finishes.incrementAndGet();
            if (finishFails) {
                throw new IOException("connection gone");
            }
        }

        @Override
        public void abort() {
            aborts.incrementAndGet();
        }

        @Override
        public void sendFile(int status, List<Map.Entry<String, String>> headers, File file, long offset,
                long length) {
            throw new AssertionError("sendFile");
        }

        @Override
        public void upgrade(List<Map.Entry<String, String>> headers, UpgradeHandler handler) {
            throw new AssertionError("upgrade");
        }

        @Override
        public void eventStream(int status, List<Map.Entry<String, String>> headers, StreamDrain drain) {
            throw new AssertionError("eventStream");
        }

        @Override
        public void webSocket(WebSocketSettings settings, WebSocketEndpoint endpoint) throws IOException {
            wrote101 = true;
            channel.open = true;
            try {
                endpoint.onOpen(channel);
                Exception e = afterOpen;
                if (e instanceof IOException) {
                    throw (IOException) e;
                }
                if (e != null) {
                    throw (RuntimeException) e;
                }
                channel.open = false;
                endpoint.onClose(1000, "");
            } finally {
                finishesWhenWebSocketReturned = finishes.get();
            }
        }
    }

    interface Hold {
        void run() throws Exception;
    }

    static final class PeerChannel implements WebSocketChannel {
        volatile boolean open; // set only by webSocket, right before onOpen
        /** Runs at the start of each sendText; null does nothing. */
        volatile Hold beforeSend;

        @Override
        public void sendText(String text) {
            Hold h = beforeSend;
            if (h != null) {
                try {
                    h.run();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        }

        @Override
        public void sendBinary(byte[] data) {
        }

        @Override
        public void ping(byte[] payload) {
        }

        @Override
        public void close(int code, String reason) {
            open = false;
        }

        @Override
        public void abort() {
            open = false;
        }

        @Override
        public boolean isOpen() {
            return open;
        }
    }
}
