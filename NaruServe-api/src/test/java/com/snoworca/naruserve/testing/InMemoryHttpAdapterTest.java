package com.snoworca.naruserve.testing;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.internal.DefaultRestApp;
import com.snoworca.naruserve.routing.RoutingConfigException;
import com.snoworca.naruserve.spi.HttpAdapter;
import com.snoworca.naruserve.spi.HttpExchange;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
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
        assertEquals("0", viaHarness.header("Content-Length"));
        assertEquals(0, viaHarness.body().length);
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
}
