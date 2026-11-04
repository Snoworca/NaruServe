package com.snoworca.naruserve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.InMemoryWebSocketChannel;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.testing.TestApp;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/** req.requestId() (C7) and req.socketAddress() (C6a) on the in-memory adapter. */
public class RequestIdAndSocketAddressTest {

    private static final Pattern BODY_RID = Pattern.compile("\"requestId\":\"([0-9a-f]{16})\"");
    private static final Pattern LINE_RID = Pattern.compile("rid=([0-9a-f]{16})$");

    private static String bodyRid(ClientResponse r) {
        Matcher m = BODY_RID.matcher(r.text());
        assertTrue(r.text(), m.find());
        return m.group(1);
    }

    private static String lineRid(String line) {
        Matcher m = LINE_RID.matcher(line);
        assertTrue(line, m.find());
        return m.group(1);
    }

    // IR-HTTP-010 AC-1: both are default methods throwing UnsupportedOperationException, as application() does
    @Test
    public void IR_HTTP_010_AC1_defaultMethodsThrowUnsupportedOperationExceptionAndDefaultRequestOverrides()
            throws Exception {
        java.lang.reflect.Method socket = Request.class.getMethod("socketAddress");
        java.lang.reflect.Method id = Request.class.getMethod("requestId");
        assertTrue(socket.isDefault());
        assertTrue(id.isDefault());
        assertEquals(String.class, socket.getReturnType());
        assertEquals(String.class, id.getReturnType());
        assertFalse(socket.getAnnotatedReturnType().isAnnotationPresent(org.jspecify.annotations.Nullable.class));
        assertFalse(id.getAnnotatedReturnType().isAnnotationPresent(org.jspecify.annotations.Nullable.class));
        Request minimal = new MinimalRequest();
        try {
            minimal.socketAddress();
            fail("socketAddress() of a bare Request");
        } catch (UnsupportedOperationException e) {
            assertEquals(MinimalRequest.class.getName() + " has no socket address", e.getMessage());
        }
        try {
            minimal.requestId();
            fail("requestId() of a bare Request");
        } catch (UnsupportedOperationException e) {
            assertEquals(MinimalRequest.class.getName() + " has no request id", e.getMessage());
        }
        assertEquals(com.snoworca.naruserve.internal.DefaultRequest.class,
                com.snoworca.naruserve.internal.DefaultRequest.class.getMethod("socketAddress").getDeclaringClass());
        assertEquals(com.snoworca.naruserve.internal.DefaultRequest.class,
                com.snoworca.naruserve.internal.DefaultRequest.class.getMethod("requestId").getDeclaringClass());
    }

    // FR-HTTP-029 AC-1: the id a middleware reads equals the requestId of the 400 standard error body. The standard
    // JSON body is what the dispatcher's error path writes (FR-APP-006 AC-7); in the common HTTP profile an uncaught
    // HttpException is answered by the Express-style finalhandler page instead, so the request that reaches the error
    // path here is a WebSocket upgrade whose middleware chain throws HttpException(400) before 101.
    @Test(timeout = 20000)
    public void FR_HTTP_029_AC1_requestIdEqualsTheErrorBodyRequestId() {
        TestApp t = TestApp.create();
        List<String> seen = Collections.synchronizedList(new ArrayList<String>());
        t.app().use((req, res, next) -> {
            seen.add(req.requestId());
            next.run();
        });
        t.app().use((req, res, next) -> {
            throw new HttpException(400);
        });
        t.app().ws("/ws", (req, ws) -> { });
        t.start();
        ClientResponse r = t.send("GET", "/ws", "Connection", "Upgrade", "Upgrade", "websocket");
        assertEquals(r.text(), 400, r.status());
        assertEquals(1, seen.size());
        assertFalse(seen.get(0).isEmpty());
        assertEquals(bodyRid(r), seen.get(0));
    }

    // FR-HTTP-029 AC-2: the id read in a 200 handler equals the rid of its access log line
    @Test
    public void FR_HTTP_029_AC2_requestIdEqualsTheAccessLogRid() {
        RecordingLogger log = new RecordingLogger();
        TestApp t = TestApp.create();
        t.app().logger(log);
        List<String> seen = Collections.synchronizedList(new ArrayList<String>());
        t.app().get("/ok", (req, res) -> {
            seen.add(req.requestId());
            res.send("ok");
        });
        assertEquals(200, t.send("GET", "/ok").status());
        List<String> lines = RecordingLogger.matching(log.info(), "GET /ok 200 .*");
        assertEquals(log.info().toString(), 1, lines.size());
        assertEquals(lineRid(lines.get(0)), seen.get(0));
    }

    // FR-HTTP-029 AC-3: global middleware, mounted Router middleware, route handler and ErrorMiddleware see one id
    @Test
    public void FR_HTTP_029_AC3_everyLayerSeesTheSameRequestId() {
        TestApp t = TestApp.create();
        List<String> seen = Collections.synchronizedList(new ArrayList<String>());
        t.app().use((req, res, next) -> {
            seen.add("global=" + req.requestId());
            next.run();
        });
        Router r = Router.create();
        r.use((Middleware) (req, res, next) -> {
            seen.add("router=" + req.requestId());
            next.run();
        });
        r.get("/x", (Middleware) (req, res, next) -> {
            seen.add("route=" + req.requestId());
            throw new IllegalStateException("boom");
        });
        r.use((ErrorMiddleware) (error, req, res, next) -> {
            seen.add("error=" + req.requestId());
            res.status(500).send("handled");
        });
        t.app().use("/api", r);
        ClientResponse response = t.send("GET", "/api/x");
        assertEquals("handled", response.text());
        assertEquals(seen.toString(), 4, seen.size());
        String id = seen.get(0).substring("global=".length());
        assertFalse(id.isEmpty());
        assertEquals(Collections.singletonList("global=" + id), seen.subList(0, 1));
        assertEquals("router=" + id, seen.get(1));
        assertEquals("route=" + id, seen.get(2));
        assertEquals("error=" + id, seen.get(3));
    }

    // FR-HTTP-029 AC-4: two sequential requests give different ids
    @Test
    public void FR_HTTP_029_AC4_sequentialRequestsGetDifferentIds() {
        TestApp t = TestApp.create();
        t.app().get("/id", (req, res) -> res.send(req.requestId()));
        String first = t.send("GET", "/id").text();
        String second = t.send("GET", "/id").text();
        assertFalse(first.isEmpty());
        assertFalse(second.isEmpty());
        assertNotEquals(first, second);
    }

    // FR-HTTP-029 AC-5; SEC-HTTP-001 AC-5: a WebSocket upgrade middleware before 101 reads the handshake-line id and
    // the same socketAddress() as an ordinary request from that peer
    @Test(timeout = 20000)
    public void FR_HTTP_029_AC5_SEC_HTTP_001_AC5_upgradeMiddlewareReadsTheHandshakeIdAndSocketAddress()
            throws Exception {
        RecordingLogger log = new RecordingLogger();
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter("10.0.0.5", false);
        TestApp t = TestApp.create(adapter);
        t.app().logger(log);
        List<String> ids = Collections.synchronizedList(new ArrayList<String>());
        List<String> sockets = Collections.synchronizedList(new ArrayList<String>());
        t.app().use((req, res, next) -> {
            ids.add(req.requestId());
            sockets.add(req.socketAddress());
            next.run();
        });
        t.app().ws("/ws", (req, ws) -> { });
        t.app().get("/plain", (req, res) -> res.send(req.socketAddress()));
        t.start();
        Future<ClientResponse> f = adapter.submit(ClientRequest.upgrade("/ws", "X-Forwarded-For", "203.0.113.7"));
        InMemoryWebSocketChannel ch = adapter.nextChannel(5, TimeUnit.SECONDS);
        String open = RecordingLogger.awaitMatching(log::info, "GET /ws 101 .*", 1).get(0);
        ch.clientClose(1000, "");
        assertEquals(101, f.get(5, TimeUnit.SECONDS).status());
        assertEquals(1, ids.size());
        assertFalse(ids.get(0).isEmpty());
        assertEquals(lineRid(open), ids.get(0));
        String plain = adapter.send(ClientRequest.of("GET", "/plain").header("X-Forwarded-For", "203.0.113.7")).text();
        assertEquals("10.0.0.5", plain);
        assertEquals("10.0.0.5", sockets.get(0));
    }

    private static TestApp who(String peer) {
        TestApp t = TestApp.create(new InMemoryHttpAdapter(peer, false));
        t.app().get("/who", (req, res) -> res.send(req.socketAddress() + " " + req.remoteAddress()));
        return t;
    }

    // SEC-HTTP-001 AC-1: default configuration ignores every forwarding header for both values
    @Test
    public void SEC_HTTP_001_AC1_defaultConfigurationGivesThePeerForBoth() {
        TestApp t = who("10.0.0.5");
        assertEquals("10.0.0.5 10.0.0.5", t.send("GET", "/who", "X-Forwarded-For", "203.0.113.7",
                "X-Forwarded-Proto", "https", "Forwarded", "for=198.51.100.1;proto=https").text());
    }

    // SEC-HTTP-001 AC-2: socketAddress() stays the peer under legacy, hop and CIDR trust
    @Test
    public void SEC_HTTP_001_AC2_socketAddressIgnoresEveryTrustSetting() {
        TestApp legacy = who("10.0.0.5");
        legacy.app().trustProxy(true);
        TestApp hops = who("10.0.0.5");
        hops.app().trustProxyHops(1);
        TestApp cidrs = who("10.0.0.5");
        cidrs.app().trustProxyCidrs("10.0.0.0/8");
        for (TestApp t : new TestApp[] {legacy, hops, cidrs}) {
            assertEquals("10.0.0.5 203.0.113.7", t.send("GET", "/who", "X-Forwarded-For", "203.0.113.7").text());
        }
    }

    // SEC-HTTP-001 AC-3: loopback peers come back as the adapter string, IPv6 unchanged
    @Test
    public void SEC_HTTP_001_AC3_loopbackPeerStringsAreUnchanged() {
        assertEquals("127.0.0.1 127.0.0.1", who("127.0.0.1").send("GET", "/who").text());
        assertEquals("::1 ::1", who("::1").send("GET", "/who").text());
    }

    // SEC-HTTP-001 AC-6: socketAddress() comes from the exchange in DefaultRequest; the fixture adapter reads no
    // forwarding header
    @Test
    public void SEC_HTTP_001_AC6_socketAddressIsTheExchangeValueAndNoAdapterReadsForwardedHeaders()
            throws IOException {
        String root = System.getProperty("user.dir");
        File source = new File(root, "src/main/java/com/snoworca/naruserve/internal/DefaultRequest.java");
        assertTrue(source.getPath(), source.isFile());
        String text = new String(java.nio.file.Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);
        int at = text.indexOf("public String socketAddress()");
        assertTrue("DefaultRequest declares socketAddress()", at >= 0);
        String body = text.substring(at, text.indexOf('}', at));
        assertTrue(body, body.contains("ex.remoteAddress()"));
        assertFalse(body, body.toLowerCase(Locale.ROOT).contains("forwarded"));
        File fixture = new File(root, "src/testFixtures/java/com/snoworca/naruserve/testing/InMemoryHttpAdapter.java");
        String adapter = new String(java.nio.file.Files.readAllBytes(fixture.toPath()), StandardCharsets.UTF_8);
        // TinyHttpd and Servlet adapters: CrossAdapterTest.FR_ENGINE_008_AC4_noAdapterClassReadsForwardedHeaders
        assertFalse(adapter.toLowerCase(Locale.ROOT).contains("forwarded"));
    }

    /** The smallest Request: every abstract method throws; socketAddress and requestId stay the defaults. */
    static final class MinimalRequest implements Request {
        private static UnsupportedOperationException no() {
            return new UnsupportedOperationException("test double");
        }

        @Override public Method method() { throw no(); }
        @Override public String path() { throw no(); }
        @Override public String rawPath() { throw no(); }
        @Override public String param(String name) { throw no(); }
        @Override public @org.jspecify.annotations.Nullable String query(String name) { throw no(); }
        @Override public List<String> queries(String name) { throw no(); }
        @Override public @org.jspecify.annotations.Nullable String header(String name) { throw no(); }
        @Override public List<String> headers(String name) { throw no(); }
        @Override public String remoteAddress() { throw no(); }
        @Override public boolean isSecure() { throw no(); }
        @Override public @org.jspecify.annotations.Nullable String lastEventId() { throw no(); }
        @Override public void attribute(String key, @org.jspecify.annotations.Nullable Object value) { throw no(); }
        @Override public <T> @org.jspecify.annotations.Nullable T attributeAs(String key, Class<T> type) { throw no(); }
    }
}
