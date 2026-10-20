package com.snoworca.naruserve.testing;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.HttpException;
import com.snoworca.naruserve.internal.DefaultRestApp;
import com.snoworca.naruserve.routing.RoutingConfigException;
import com.snoworca.naruserve.spi.HttpAdapter;
import com.snoworca.naruserve.spi.HttpExchange;
import com.snoworca.naruserve.spi.StreamDrain;
import com.snoworca.naruserve.spi.WebSocketChannel;
import com.snoworca.naruserve.spi.WebSocketEndpoint;
import com.snoworca.naruserve.spi.WebSocketSettings;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class InMemoryHttpAdapterTest {

    /** Records the raw request line of every exchange and answers 204. */
    private static final class Recorder {
        final List<String[]> seen = new ArrayList<>();
        final AtomicInteger calls = new AtomicInteger();
        final InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();

        Recorder() throws IOException {
            adapter.bind("127.0.0.1", 0, this::handle);
        }

        private void handle(HttpExchange exchange) {
            calls.incrementAndGet();
            seen.add(new String[] {exchange.methodToken(), exchange.rawPath(), exchange.rawQuery()});
            try {
                exchange.commit(204, Collections.<Map.Entry<String, String>>emptyList(), 0);
                exchange.finish();
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        }

        String[] send(String method, String target) {
            ClientResponse r = adapter.send(ClientRequest.of(method, target));
            assertEquals(204, r.status());
            return seen.get(seen.size() - 1);
        }
    }

    // SDS-AC-3; IR-HTTP-003 AC-3, FR-HTTP-001 AC-4
    @Test
    public void sdsAc3_IR_HTTP_003_AC3_FR_HTTP_001_AC4_rawPathAndRawQueryTravelUndecoded() throws IOException {
        Recorder r = new Recorder();
        assertArrayEquals(new String[] {"GET", "/a%20b", "x=%41"}, r.send("GET", "/a%20b?x=%41"));
        assertArrayEquals(new String[] {"GET", "/a%20b", "q=%20"}, r.send("GET", "/a%20b?q=%20"));
        assertArrayEquals(new String[] {"GET", "/plain", null}, r.send("GET", "/plain"));
        assertArrayEquals(new String[] {"GET", "/e", ""}, r.send("GET", "/e?"));
        assertArrayEquals(new String[] {"/a%20b", "x=%41"}, InMemoryHttpAdapter.splitTarget("/a%20b?x=%41"));
        assertNull(InMemoryHttpAdapter.splitTarget("/plain")[1]);
    }

    // SDS-AC-8; FR-HTTP-001 AC-1
    @Test
    public void sdsAc8_FR_HTTP_001_AC1_adapterIsAFixturesClassImplementingHttpAdapter() {
        assertTrue(HttpAdapter.class.isAssignableFrom(InMemoryHttpAdapter.class));
        String location = InMemoryHttpAdapter.class.getProtectionDomain().getCodeSource().getLocation().getPath();
        assertTrue(location, location.contains("testFixtures") || location.contains("test-fixtures"));
    }

    // SDS-AC-8; FR-HTTP-001 AC-3
    @Test
    public void sdsAc8_FR_HTTP_001_AC3_freezeErrorReachesTheCallerUnwrapped() {
        TestApp t = TestApp.create();
        t.app().get("/x", (req, res) -> { });
        t.app().get("/x", (req, res) -> { });
        try {
            t.start();
            fail("duplicate GET /x accepted");
        } catch (RoutingConfigException e) {
            assertEquals(RoutingConfigException.class, e.getClass());
            assertNull(e.getCause());
            assertTrue(e.getMessage(), e.getMessage().contains("GET /x conflicts with GET /x"));
        }
        TestApp lazy = TestApp.create();
        lazy.app().get("/y", (req, res) -> { });
        lazy.app().get("/y", (req, res) -> { });
        try {
            lazy.send("GET", "/y");
            fail("send started a conflicting table");
        } catch (RoutingConfigException e) {
            assertEquals(RoutingConfigException.class, e.getClass());
        }
    }

    // SDS-AC-8; FR-HTTP-001 AC-3
    @Test
    public void sdsAc8_FR_HTTP_001_AC3_startIsIdempotentAndThenRefusesRegistration() {
        TestApp t = TestApp.create();
        assertSame(t, t.start());
        assertSame(t, t.start());
        try {
            t.app().get("/late", (req, res) -> { });
            fail("registration after start");
        } catch (IllegalStateException expected) {
            // frozen by start
        }
    }

    // SDS-AC-8; FR-HTTP-001 AC-2
    @Test
    public void sdsAc8_FR_HTTP_001_AC2_requestsRunThroughTheAppDispatcherOnTheInMemoryAdapter() throws IOException {
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        TestApp t = TestApp.create(adapter);
        t.app().post("/p", (req, res) -> { });
        ClientResponse viaHarness = t.send("GET", "/p");
        assertEquals(405, viaHarness.status());
        assertEquals("POST, OPTIONS", viaHarness.header("Allow"));
        // FR-HTTP-022 AC-2 (wave 4 successor of FR-HTTP-003): the 405 carries the standard error body.
        assertEquals(String.valueOf(viaHarness.body().length), viaHarness.header("Content-Length"));
        assertEquals("application/json; charset=UTF-8", viaHarness.header("Content-Type"));
        String body = new String(viaHarness.body(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(body, body.matches("\\{\"error\":\\{\"code\":\"METHOD_NOT_ALLOWED\","
                + "\"message\":\"Method Not Allowed\",\"requestId\":\"[0-9a-f]{16}\"}}"));
        // The adapter handed to create() is the one bound: it reaches the same dispatcher directly.
        ClientResponse direct = adapter.send(ClientRequest.of("GET", "/nothing"));
        assertEquals(404, direct.status());
        // No socket: an in-memory adapter binds and serves a port a real ServerSocket already holds.
        try (ServerSocket held = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            DefaultRestApp other = new DefaultRestApp();
            other.freeze();
            InMemoryHttpAdapter onHeldPort = new InMemoryHttpAdapter();
            onHeldPort.bind("127.0.0.1", held.getLocalPort(), other.dispatcher());
            assertEquals(held.getLocalPort(), onHeldPort.boundPort());
            assertEquals(404, onHeldPort.send(ClientRequest.of("GET", "/x")).status());
        }
        try {
            t.send("GET", "/p", "odd");
            fail("odd header pairs accepted");
        } catch (IllegalArgumentException expected) {
            // name without value
        }
    }

    // SDS-AC-9; FR-HTTP-001 AC-5
    @Test
    public void sdsAc9_FR_HTTP_001_AC5_authorityFormConnectGets501WithoutCallingTheHandler() throws IOException {
        Recorder r = new Recorder();
        ClientResponse response = r.adapter.send(ClientRequest.of("CONNECT", "host:443"));
        assertEquals(501, response.status());
        assertEquals("0", response.header("Content-Length"));
        assertEquals(0, response.body().length);
        assertFalse(response.abnormallyClosed());
        assertEquals(0, r.calls.get());
    }

    // SDS-AC-10; FR-HTTP-001 AC-6, FR-HTTP-001 AC-7
    @Test
    public void sdsAc10_FR_HTTP_001_AC6_AC7_absoluteFormIsRewrittenAndAsteriskPassesAsIs() throws IOException {
        Recorder r = new Recorder();
        assertArrayEquals(new String[] {"GET", "/a", "x=1"}, r.send("GET", "http://example.com/a?x=1"));
        assertArrayEquals(new String[] {"GET", "/", null}, r.send("GET", "https://example.com"));
        assertArrayEquals(new String[] {"GET", "/", "y=2"}, r.send("GET", "http://example.com:8080?y=2"));
        assertArrayEquals(new String[] {"OPTIONS", "*", null}, r.send("OPTIONS", "*"));
        assertArrayEquals(new String[] {"/a", "x=1"}, InMemoryHttpAdapter.splitTarget("http://example.com/a?x=1"));
        assertArrayEquals(new String[] {"*", null}, InMemoryHttpAdapter.splitTarget("*"));
    }

    // SDS-AC-12 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-4-3.sds.md; FR-APP-009 AC-7
    @Test
    public void sdsAc12_FR_APP_009_AC7_harnessBuildsThroughTheBuilderWithStubCodecAndStartsByListen() {
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        TestApp t = TestApp.create(adapter);
        DefaultRestApp app = (DefaultRestApp) t.app();
        assertSame(adapter, app.httpAdapter());
        assertTrue(app.jsonCodec() instanceof StubJsonCodec);
        assertEquals(com.snoworca.naruserve.AppState.NEW, app.state());
        t.start();
        t.start();
        assertEquals(com.snoworca.naruserve.AppState.RUNNING, app.state());
        assertTrue(adapter.boundPort() > 0);
        // The harness exchange handler is the gated one listen() bound, not the bare dispatcher.
        assertEquals(404, t.send("GET", "/none").status());

        TestApp dup = TestApp.create();
        dup.app().get("/d", (req, res) -> { });
        dup.app().get("/d", (req, res) -> { });
        try {
            dup.start();
            fail("duplicate GET /d accepted");
        } catch (RoutingConfigException e) {
            assertEquals(RoutingConfigException.class, e.getClass());
        }
    }

    // SDS-AC-12 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-4-3.sds.md; FR-APP-009 AC-7
    @Test
    public void sdsAc12_FR_APP_009_AC7_stubCodecIsAFixturesJsonCodecWithoutGson() throws IOException {
        assertTrue(com.snoworca.naruserve.spi.JsonCodec.class.isAssignableFrom(StubJsonCodec.class));
        java.io.StringWriter w = new java.io.StringWriter();
        new StubJsonCodec().encode(null, w);
        assertEquals("null", w.toString());
    }

    // Wave 7 part 1: SDS-AC-n below cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-1.sds.md.

    /** Records every callback and the thread it ran on. */
    private static final class RecordingEndpoint implements WebSocketEndpoint {
        final List<String> events = Collections.synchronizedList(new ArrayList<String>());
        final List<Thread> threads = Collections.synchronizedList(new ArrayList<Thread>());
        final CountDownLatch opened = new CountDownLatch(1);
        volatile WebSocketChannel channel;

        private void record(String event) {
            events.add(event);
            threads.add(Thread.currentThread());
        }

        @Override
        public void onOpen(WebSocketChannel channel) {
            this.channel = channel;
            record("open");
            opened.countDown();
        }

        @Override
        public void onText(String text) {
            record("text:" + text);
        }

        @Override
        public void onBinary(byte[] data) {
            record("binary:" + Arrays.toString(data));
        }

        @Override
        public void onClose(int code, String reason) {
            record("close:" + code + ":" + reason);
        }
    }

    /** Binds a handler that runs {@code endpoint} through webSocket and fails the test on an IOException. */
    private static InMemoryHttpAdapter webSocketAdapter(RecordingEndpoint endpoint) throws IOException {
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        adapter.bind("127.0.0.1", 0, exchange -> {
            try {
                exchange.webSocket(new WebSocketSettings(1024, 0), endpoint);
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        });
        return adapter;
    }

    // SDS-AC-7; IR-WS-002 AC-8, IR-WS-002 AC-4
    @Test(timeout = 20000)
    public void sdsAc7_IR_WS_002_AC8_webSocketRecords101OpensOnTheExchangeThreadAndDeliversPeerEventsInOrder()
            throws Exception {
        RecordingEndpoint endpoint = new RecordingEndpoint();
        AtomicReference<Thread> exchangeThread = new AtomicReference<>();
        AtomicReference<List<String>> atReturn = new AtomicReference<>();
        AtomicReference<Throwable> laterCommit = new AtomicReference<>();
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        adapter.bind("127.0.0.1", 0, exchange -> {
            exchangeThread.set(Thread.currentThread());
            try {
                exchange.webSocket(new WebSocketSettings(1024, 0), endpoint);
            } catch (IOException e) {
                throw new AssertionError(e);
            }
            atReturn.set(new ArrayList<>(endpoint.events));
            try {
                exchange.commit(200, Collections.<Map.Entry<String, String>>emptyList(), 0);
            } catch (Throwable t) {
                laterCommit.set(t);
            }
        });
        Future<ClientResponse> f = adapter.submit(ClientRequest.of("GET", "/ws").header("Upgrade", "websocket"));
        InMemoryWebSocketChannel channel = adapter.nextChannel(5, TimeUnit.SECONDS);
        assertTrue(endpoint.opened.await(5, TimeUnit.SECONDS));
        assertSame(channel, endpoint.channel);
        assertTrue(channel.isOpen());
        endpoint.channel.sendText("hello");
        endpoint.channel.sendBinary(new byte[] {9});
        endpoint.channel.ping(new byte[0]);
        List<Object> sent = channel.sent();
        assertEquals(2, sent.size());
        assertEquals("hello", sent.get(0));
        assertArrayEquals(new byte[] {9}, (byte[]) sent.get(1));
        assertEquals(1, channel.pings());

        channel.clientText("one");
        channel.clientBinary(new byte[] {1, 2});
        try {
            f.get(150, TimeUnit.MILLISECONDS);
            fail("webSocket returned while the connection was open");
        } catch (TimeoutException expected) {
            // still open
        }
        channel.clientClose(1000, "done");
        ClientResponse r = f.get(5, TimeUnit.SECONDS);
        assertEquals(101, r.status());
        List<String> want = Arrays.asList("open", "text:one", "binary:[1, 2]", "close:1000:done");
        assertEquals(want, endpoint.events);
        assertEquals("webSocket returned before onClose", want, atReturn.get());
        assertNotSame(Thread.currentThread(), exchangeThread.get());
        for (Thread t : endpoint.threads) {
            assertSame(exchangeThread.get(), t);
        }
        assertTrue(String.valueOf(laterCommit.get()), laterCommit.get() instanceof IllegalStateException);
        assertFalse(channel.isOpen());
    }

    // SDS-AC-8; IR-WS-002 AC-8
    @Test(timeout = 20000)
    public void sdsAc8_IR_WS_002_AC8_closeIsEchoedByThePeerAndAbortGives1006() throws Exception {
        RecordingEndpoint closing = new RecordingEndpoint();
        InMemoryHttpAdapter adapter = webSocketAdapter(closing);
        Future<ClientResponse> f = adapter.submit(ClientRequest.of("GET", "/ws"));
        InMemoryWebSocketChannel channel = adapter.nextChannel(5, TimeUnit.SECONDS);
        assertTrue(closing.opened.await(5, TimeUnit.SECONDS));
        assertEquals(-1, channel.closeCode());
        channel.close(4000, "bye");
        ClientResponse r = f.get(5, TimeUnit.SECONDS);
        assertEquals(4000, channel.closeCode());
        assertEquals("bye", channel.closeReason());
        assertFalse(channel.aborted());
        assertFalse(channel.isOpen());
        assertEquals(Arrays.asList("open", "close:4000:bye"), closing.events);
        assertFalse(r.abnormallyClosed());
        try {
            channel.sendText("late");
            fail("sendText after close");
        } catch (IOException expected) {
            // closed
        }

        RecordingEndpoint aborted = new RecordingEndpoint();
        InMemoryHttpAdapter second = webSocketAdapter(aborted);
        Future<ClientResponse> g = second.submit(ClientRequest.of("GET", "/ws"));
        InMemoryWebSocketChannel other = second.nextChannel(5, TimeUnit.SECONDS);
        assertTrue(aborted.opened.await(5, TimeUnit.SECONDS));
        other.abort();
        ClientResponse r2 = g.get(5, TimeUnit.SECONDS);
        assertTrue(other.aborted());
        assertFalse(other.isOpen());
        assertEquals(-1, other.closeCode());
        assertEquals(Arrays.asList("open", "close:1006:"), aborted.events);
        assertTrue(r2.abnormallyClosed());
    }

    // SDS-AC-9; IR-WS-002 AC-6
    @Test(timeout = 20000)
    public void sdsAc9_IR_WS_002_AC6_aRefusedHandshakeLeavesTheExchangeUnstartedForTheErrorPath() throws Exception {
        HttpException refusal = new HttpException(503, "busy");
        RecordingEndpoint endpoint = new RecordingEndpoint();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        adapter.refuseWebSocket(refusal);
        adapter.bind("127.0.0.1", 0, exchange -> {
            try {
                exchange.webSocket(new WebSocketSettings(1024, 0), endpoint);
            } catch (HttpException e) {
                thrown.set(e);
                // What the dispatcher's error path does with the same exchange.
                try {
                    exchange.commit(e.status(), Collections.<Map.Entry<String, String>>singletonList(
                            new java.util.AbstractMap.SimpleEntry<>("Content-Length", "0")), 0);
                    exchange.finish();
                } catch (IOException io) {
                    throw new AssertionError(io);
                }
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        });
        ClientResponse r = adapter.send(ClientRequest.of("GET", "/ws"));
        assertSame(refusal, thrown.get());
        assertEquals(503, r.status());
        assertFalse(r.abnormallyClosed());
        assertEquals(Collections.emptyList(), endpoint.events);
        boolean opened = true;
        try {
            adapter.nextChannel(100, TimeUnit.MILLISECONDS);
        } catch (AssertionError expected) {
            opened = false;
        }
        assertFalse("a refused handshake opened a channel", opened);

        adapter.refuseWebSocket(null);
        Future<ClientResponse> f = adapter.submit(ClientRequest.of("GET", "/ws"));
        InMemoryWebSocketChannel channel = adapter.nextChannel(5, TimeUnit.SECONDS);
        channel.clientClose(1000, "");
        assertEquals(101, f.get(5, TimeUnit.SECONDS).status());
    }

    // SDS-AC-14 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-10.sds.md; FR-SSE-002 AC-2, AC-8
    @Test(timeout = 20000)
    public void sdsAc14_FR_SSE_002_AC2_AC8_eventStreamAttachesAndDrainsOneStreamOnAnotherThread() throws Exception {
        AtomicReference<OutputStream> attached = new AtomicReference<>();
        AtomicReference<OutputStream> drained = new AtomicReference<>();
        AtomicReference<Thread> handlerThread = new AtomicReference<>();
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        adapter.bind("127.0.0.1", 0, exchange -> {
            handlerThread.set(Thread.currentThread());
            try {
                exchange.eventStream(200, Collections.<Map.Entry<String, String>>emptyList(), new StreamDrain() {
                    @Override
                    public void attach(OutputStream out) {
                        attached.set(out);
                    }

                    @Override
                    public void drain(OutputStream out) throws IOException {
                        drained.set(out);
                        out.write("x".getBytes(StandardCharsets.UTF_8));
                    }
                });
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        });
        Future<ClientResponse> f = adapter.submit(ClientRequest.of("GET", "/events"));
        InMemoryEventStream es = adapter.nextEventStream(5, TimeUnit.SECONDS);
        ClientResponse r = f.get(5, TimeUnit.SECONDS);
        assertEquals("x", r.text());
        assertEquals("x", es.text());
        assertSame(attached.get(), es.attached());
        assertSame(drained.get(), es.drained());
        assertSame(es.attached(), es.drained());
        assertEquals(1, es.writerThreads().size());
        assertFalse(es.writerThreads().contains(handlerThread.get()));
    }
}
