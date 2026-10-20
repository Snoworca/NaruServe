package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.RecordingExchange;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.Method;
import com.snoworca.naruserve.MethodSet;
import com.snoworca.naruserve.RouteHandler;
import com.snoworca.naruserve.routing.Router;
import com.snoworca.naruserve.routing.RouterPolicy;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.RecordingLogger;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/**
 * Dispatcher checks and handler invocation. A bare SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-3-3.sds.md;
 * cases re-pointed to the wave 4 successors name their SDS.
 */
public class DispatcherTest {

    private final AtomicInteger calls = new AtomicInteger();
    private final RouteHandler recording = (req, res) -> calls.incrementAndGet();
    private final Router<RouteHandler> router = new Router<>(RouterPolicy.strict());
    private final InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();

    private void start() {
        router.freeze();
        adapter.bind("127.0.0.1", 0, new Dispatcher(router));
    }

    private ClientResponse send(String method, String target, String... headerPairs) {
        ClientRequest request = ClientRequest.of(method, target);
        for (int i = 0; i < headerPairs.length; i += 2) {
            request.header(headerPairs[i], headerPairs[i + 1]);
        }
        return adapter.send(request);
    }

    private static void assertEmpty(int status, ClientResponse r) {
        assertEquals(status, r.status());
        assertEquals("0", r.header("Content-Length"));
        assertEquals(0L, r.contentLength()); // SDS-AC-6: automatic answers commit with length 0
        assertEquals(0, r.body().length);
        assertFalse(r.abnormallyClosed());
    }

    private void anyOnRootAndVariable() {
        router.add(Method.any(), "/", recording);
        router.add(Method.any(), "/:x", recording);
        start();
    }

    /** The standard error body of FR-APP-006: JSON type, exact Content-Length, code and reason message. */
    static void assertError(int status, String code, String message, ClientResponse r) {
        assertEquals(status, r.status());
        assertEquals("application/json; charset=UTF-8", r.header("Content-Type"));
        assertEquals(String.valueOf(r.body().length), r.header("Content-Length"));
        String body = new String(r.body(), StandardCharsets.UTF_8);
        assertTrue(body, body.matches("\\{\"error\":\\{\"code\":\"" + code + "\",\"message\":\"" + message
                + "\",\"requestId\":\"[0-9a-f]{16}\"}}"));
        assertFalse(r.abnormallyClosed());
    }

    // SDS-AC-19, SDS-AC-13 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-4-4.sds.md; FR-HTTP-021 AC-2, FR-HTTP-023 AC-2
    @Test
    public void sdsAc19_FR_HTTP_021_AC2_FR_HTTP_023_AC2_optionsAsteriskIsAnswered204BeforePathValidation() {
        anyOnRootAndVariable();
        ClientResponse r = send("OPTIONS", "*");
        assertEquals(204, r.status());
        assertEquals("GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS", r.header("Allow"));
        assertNull(r.header("Content-Length"));
        assertEquals(0, r.body().length);
        assertEquals(0, calls.get());
    }

    // SDS-AC-13, SDS-AC-14 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-4-4.sds.md; FR-HTTP-021 AC-2, FR-HTTP-021 AC-3
    @Test
    public void sdsAc13_FR_HTTP_021_AC2_AC3_validatorRejectionIs400WithTheStandardBodyBeforeAnyMatch() {
        anyOnRootAndVariable();
        assertError(400, "BAD_REQUEST", "Bad Request", send("GET", "/%2e%2e"));
        assertError(400, "BAD_REQUEST", "Bad Request", send("GET", "/a%2Fb"));
        assertEquals(0, calls.get());
    }

    // SDS-AC-13, SDS-AC-14 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-4-4.sds.md; FR-HTTP-021 AC-4, FR-HTTP-023 AC-3
    @Test
    public void sdsAc13_FR_HTTP_021_AC4_FR_HTTP_023_AC3_preRequestRejectionsNeverReachTheHandler() {
        anyOnRootAndVariable();
        assertError(501, "NOT_IMPLEMENTED", "Not Implemented", send("FOO", "/x"));
        assertError(501, "NOT_IMPLEMENTED", "Not Implemented", send("get", "/x"));
        assertError(400, "BAD_REQUEST", "Bad Request", send("GET", "abc"));
        assertError(400, "BAD_REQUEST", "Bad Request", send("GET", "*"));
        assertEquals(0, calls.get());
    }

    // SDS-AC-13 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-4-4.sds.md; FR-HTTP-021 AC-2
    @Test
    public void sdsAc13_FR_HTTP_021_AC2_checksRunInOrderAsteriskSlashValidatorToken() {
        anyOnRootAndVariable();
        // A non-'/' path and a validator rejection both win over an unknown token: 400 before 501.
        assertEquals(400, send("FOO", "abc").status());
        assertEquals(400, send("FOO", "/%2e%2e").status());
        assertEquals(400, send("POST", "*").status());
        assertEquals(400, send("FOO", "*").status());
        assertEquals(204, send("OPTIONS", "*").status());
        assertEquals(0, calls.get());
    }

    // SDS-AC-13 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-4-4.sds.md; FR-HTTP-021 AC-2
    @Test
    public void sdsAc13_FR_HTTP_021_AC2_methodOfMatchesDeclarationNamesExactly() {
        for (Method m : Method.values()) {
            assertEquals(m, Dispatcher.methodOf(m.name()));
        }
        assertNull(Dispatcher.methodOf("get"));
        assertNull(Dispatcher.methodOf("BREW"));
        assertNull(Dispatcher.methodOf(""));
    }

    // SDS-AC-14 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-4-4.sds.md; FR-HTTP-021 AC-3
    @Test
    public void sdsAc14_FR_HTTP_021_AC3_headTokenRejectionKeepsTheLengthAndWritesNoBytes() {
        anyOnRootAndVariable();
        ClientResponse r = send("HEAD", "/%2e%2e");
        assertEquals(400, r.status());
        assertEquals("application/json; charset=UTF-8", r.header("Content-Type"));
        int length = StandardErrors.json(400, "Bad Request", "0000000000000000").length;
        assertEquals(String.valueOf(length), r.header("Content-Length"));
        assertEquals(0, r.body().length);
        assertFalse(r.abnormallyClosed());
    }

    // SDS-AC-15 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-4-4.sds.md; FR-HTTP-022 AC-2, FR-HTTP-026 AC-2
    @Test
    public void sdsAc15_FR_HTTP_022_AC2_methodMismatchListsAllowInDeclarationOrder() {
        router.add(Method.POST, "/p", recording);
        router.add(Method.GET, "/r", recording);
        start();
        ClientResponse p = send("GET", "/p");
        assertError(405, "METHOD_NOT_ALLOWED", "Method Not Allowed", p);
        assertEquals("POST, OPTIONS", p.header("Allow"));
        ClientResponse r = send("DELETE", "/r");
        assertError(405, "METHOD_NOT_ALLOWED", "Method Not Allowed", r);
        assertEquals("GET, HEAD, OPTIONS", r.header("Allow"));
        assertEquals(0, calls.get());
    }

    // SDS-AC-15 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-4-4.sds.md; FR-HTTP-022 AC-7, FR-HTTP-026 AC-7
    @Test
    public void sdsAc15_FR_HTTP_022_AC7_anyExceptDeleteGives405WithAllow() {
        router.add(Method.any().except(Method.DELETE), "/t", recording);
        start();
        ClientResponse r = send("DELETE", "/t");
        assertError(405, "METHOD_NOT_ALLOWED", "Method Not Allowed", r);
        assertEquals("GET, HEAD, POST, PUT, PATCH, OPTIONS", r.header("Allow"));
        assertEquals(0, calls.get());
    }

    // SDS-AC-19 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-4-4.sds.md; FR-HTTP-023 AC-1
    @Test
    public void sdsAc19_FR_HTTP_023_AC1_optionsAsteriskAllowIsMethodAnyWithNoRoutes() {
        start();
        ClientResponse r = send("OPTIONS", "*");
        assertEquals(204, r.status());
        assertEquals(Dispatcher.allowHeader(Method.any()), r.header("Allow"));
        assertEquals("GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS", r.header("Allow"));
        assertNull(r.header("Content-Length"));
        assertEquals(0, r.body().length);
    }

    // SDS-AC-19 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-4-4.sds.md; FR-HTTP-022 AC-2, FR-HTTP-026 AC-2
    @Test
    public void sdsAc19_FR_HTTP_022_AC2_allowHeaderJoinsInMethodOrder() {
        assertEquals("GET, POST, OPTIONS", Dispatcher.allowHeader(MethodSet.of(Method.OPTIONS, Method.POST, Method.GET)));
        assertEquals("DELETE", Dispatcher.allowHeader(MethodSet.of(Method.DELETE)));
    }

    // SDS-AC-18 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-4-4.sds.md; FR-HTTP-022 AC-5, FR-HTTP-026 AC-5, FR-HTTP-026 AC-6; the upgrade is
    // re-pointed to SDS-AC-3 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-3.sds.md, FR-WS-001 AC-7
    @Test(timeout = 20000)
    public void sdsAc18_FR_HTTP_022_AC5_AC6_splitUpgradeHeadersAreJoinedBeforeLookup() throws Exception {
        // With both kinds on /ws only the upgrade flag selects the WebSocket route; the HTTP handler must not run.
        router.addWebSocket("/ws", recording);
        router.add(Method.GET, "/ws", recording);
        start();
        ClientResponse r = handshake("GET", "/ws", "Connection", "keep-alive", "Connection", "Upgrade",
                "Upgrade", "websocket", "Upgrade", "h2c");
        assertEquals(101, r.status());
        assertFalse(r.abnormallyClosed());
        assertFalse(r.upgradeHandedOver());
        assertEquals(0, calls.get());
    }

    /** Submits an upgrade, closes its channel from the peer and returns the response. */
    private ClientResponse handshake(String method, String target, String... headerPairs) throws Exception {
        ClientRequest request = ClientRequest.of(method, target);
        for (int i = 0; i < headerPairs.length; i += 2) {
            request.header(headerPairs[i], headerPairs[i + 1]);
        }
        Future<ClientResponse> f = adapter.submit(request);
        adapter.nextChannel(5, TimeUnit.SECONDS).clientClose(1000, "");
        return f.get(10, TimeUnit.SECONDS);
    }

    // SDS-AC-18 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-4-4.sds.md; FR-HTTP-022 AC-5, FR-HTTP-026 AC-5, FR-HTTP-026 AC-6; the plain case is
    // re-pointed to FR-WS-004 AC-1 and the upgrade case to SDS-AC-3 of
    // docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-3.sds.md, FR-WS-001 AC-7 (the handshake from wave 7)
    @Test(timeout = 20000)
    public void sdsAc18_FR_HTTP_022_AC5_AC6_webSocketOnlyRouteGives426ForPlainAndUpgradeGet() throws Exception {
        router.addWebSocket("/ws", recording);
        start();
        ClientResponse plain = send("GET", "/ws");
        assertError(426, "UPGRADE_REQUIRED", "Upgrade Required", plain);
        assertEquals("websocket", plain.header("Upgrade"));
        assertEquals("Upgrade", plain.header("Connection"));
        ClientResponse upgrade = handshake("GET", "/ws", "Connection", "Upgrade", "Upgrade", "websocket");
        assertEquals(101, upgrade.status());
        assertFalse(upgrade.abnormallyClosed());
        assertFalse(upgrade.upgradeHandedOver());
        assertEquals(0, calls.get());
    }

    // CON-HTTP-008 AC-1: body writers, end, stream, automatic answers and failure answers start the response once at most
    @Test
    public void con_HTTP_008_AC1_everyResponsePathStartsTheResponseAtMostOnce() {
        router.add(Method.GET, "/send", (req, res) -> res.send("x"));
        router.add(Method.GET, "/html", (req, res) -> res.html("<p>x</p>"));
        router.add(Method.GET, "/bytes", (req, res) -> res.send(new byte[] {1, 2}));
        router.add(Method.GET, "/end", (req, res) -> res.status(202).end());
        router.add(Method.GET, "/stream", (req, res) -> res.stream(new ByteArrayInputStream(new byte[10]), "text/plain"));
        router.add(Method.GET, "/none", (req, res) -> res.status(202));
        router.add(Method.GET, "/before", (req, res) -> {
            throw new IllegalStateException("before");
        });
        router.add(Method.GET, "/after", (req, res) -> {
            res.send("x");
            throw new IllegalStateException("after");
        });
        router.add(Method.GET, "/afterStream", (req, res) -> {
            res.stream(new ByteArrayInputStream(new byte[10]), "text/plain");
            throw new IllegalStateException("after stream");
        });
        router.add(Method.GET, "/twice", (req, res) -> {
            res.send("x");
            res.send("y");
        });
        router.freeze();
        String[][] cases = {{"GET", "/send"}, {"GET", "/html"}, {"GET", "/bytes"}, {"GET", "/end"}, {"GET", "/stream"},
            {"GET", "/none"}, {"GET", "/before"}, {"GET", "/after"}, {"GET", "/afterStream"}, {"GET", "/twice"},
            {"HEAD", "/send"}, {"GET", "/missing"}, {"DELETE", "/send"}, {"OPTIONS", "/send"}, {"OPTIONS", "*"},
            {"FOO", "/send"}, {"GET", "abc"}, {"GET", "/%2e%2e"}};
        for (String[] c : cases) {
            RecordingExchange ex = new RecordingExchange(c[0], c[1]);
            new Dispatcher(router).handle(ex);
            assertTrue(c[0] + " " + c[1] + ": commits " + ex.commits.get() + ", sendFiles " + ex.sendFiles.get(),
                    ex.commits.get() + ex.sendFiles.get() <= 1);
        }
    }

    // SDS-AC-15, SDS-AC-17 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-4-4.sds.md; FR-HTTP-022 AC-1, FR-HTTP-022 AC-4, FR-HTTP-026 AC-1, FR-HTTP-026 AC-4
    @Test
    public void sdsAc15_FR_HTTP_022_AC1_AC4_noMatchAndUnsupportedCarryTheStandardBody() {
        router.add(Method.GET, "/r", recording);
        start();
        assertError(404, "NOT_FOUND", "Not Found", send("GET", "/missing"));
        assertError(501, "NOT_IMPLEMENTED", "Not Implemented", send("BREW", "/r"));
        assertError(501, "NOT_IMPLEMENTED", "Not Implemented", send("TRACE", "/r"));
        assertError(501, "NOT_IMPLEMENTED", "Not Implemented", send("CONNECT", "/"));
        assertEquals(0, calls.get());
    }

    // SDS-AC-15 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-4-4.sds.md; FR-HTTP-022 AC-1, FR-HTTP-026 AC-1
    @Test
    public void sdsAc15_FR_HTTP_022_AC1_fallbackKeepsMiddlewareHeadersAndLeavesAnAnsweredResponse() {
        router.freeze();
        MiddlewarePipeline pipeline = new MiddlewarePipeline(RouterPolicy.strict());
        pipeline.addGlobal((req, res, next) -> {
            res.header("X-Mw", "1");
            next.run();
        });
        pipeline.addPrefix("/done", (req, res, next) -> res.status(418).send("tea"));
        adapter.bind("127.0.0.1", 0, new Dispatcher(router, pipeline,
                new ErrorPath(java.util.Collections.emptyList(), new SafeLogger(new RecordingLogger())),
                new AccessLog()));
        ClientResponse r = send("GET", "/missing");
        assertError(404, "NOT_FOUND", "Not Found", r);
        assertEquals("1", r.header("X-Mw"));
        ClientResponse done = send("GET", "/done");
        assertEquals(418, done.status());
        assertEquals("tea", new String(done.body(), StandardCharsets.UTF_8));
    }

    // SDS-AC-15 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-4-4.sds.md; FR-HTTP-022 AC-1, FR-HTTP-026 AC-1
    @Test
    public void sdsAc15_FR_HTTP_022_AC1_fallbackLeavesAResponseThatAlreadyLeftNew() {
        router.freeze();
        MiddlewarePipeline pipeline = new MiddlewarePipeline(RouterPolicy.strict());
        pipeline.addGlobal((req, res, next) -> {
            res.status(202).stream(new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8)), "text/plain");
            next.run();
        });
        adapter.bind("127.0.0.1", 0, new Dispatcher(router, pipeline,
                new ErrorPath(java.util.Collections.emptyList(), new SafeLogger(new RecordingLogger())),
                new AccessLog()));
        ClientResponse r = send("GET", "/missing");
        assertEquals(202, r.status());
        assertEquals("abc", new String(r.body(), StandardCharsets.UTF_8));
        assertFalse(r.abnormallyClosed());
    }

    // SDS-AC-16 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-4-4.sds.md; FR-HTTP-022 AC-3, FR-HTTP-026 AC-3
    @Test
    public void sdsAc16_FR_HTTP_022_AC3_automaticOptionsIs204WithoutContentLength() {
        router.add(Method.GET, "/r", recording);
        router.add(Method.POST, "/r", recording);
        start();
        ClientResponse r = send("OPTIONS", "/r");
        assertEquals(204, r.status());
        assertEquals("GET, HEAD, POST, OPTIONS", r.header("Allow"));
        assertNull(r.header("Content-Length"));
        assertNull(r.header("Content-Type"));
        assertEquals(0L, r.contentLength());
        assertEquals(0, r.body().length);
        assertFalse(r.abnormallyClosed());
        assertEquals(0, calls.get());
    }

    private final AtomicReference<DefaultResponse> last = new AtomicReference<>();

    /** Wraps {@code h} so the test sees the DefaultResponse the Dispatcher built. */
    private RouteHandler capturing(RouteHandler h) {
        return (req, res) -> {
            last.set((DefaultResponse) res);
            h.handle(req, res);
        };
    }

    // SDS-AC-13 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-4-4.sds.md; FR-HTTP-021 AC-3
    @Test
    public void sdsAc13_FR_HTTP_021_AC3_doubleEncodedDotsReachVariableRouteOnceDecoded() {
        List<String> params = new ArrayList<>();
        router.add(Method.GET, "/:x", (req, res) -> {
            params.add(req.param("x"));
            res.send("ok");
        });
        start();
        ClientResponse r = send("GET", "/%252e%252e");
        assertEquals(200, r.status());
        assertEquals("%2e%2e", params.get(0));
    }

    // SDS-AC-7; FR-HTTP-004 AC-1, FR-HTTP-004 AC-2, FR-HTTP-007 AC-4
    @Test
    public void sdsAc7_FR_HTTP_004_AC1_AC2_FR_HTTP_007_AC4_autoHeadRunsGetHandlerInHeadMode() {
        List<Method> methods = new ArrayList<>();
        router.add(Method.GET, "/h", capturing((req, res) -> {
            methods.add(req.method());
            res.status(200).header("X-A", "1").send("hello");
        }));
        start();
        ClientResponse head = send("HEAD", "/h");
        assertEquals(200, head.status());
        assertEquals("1", head.header("X-A"));
        assertEquals("5", head.header("Content-Length"));
        assertEquals(0, head.body().length);
        assertFalse(head.abnormallyClosed());
        assertTrue(last.get().bodyless());
        ClientResponse get = send("GET", "/h");
        assertEquals("hello", new String(get.body(), StandardCharsets.UTF_8));
        assertEquals(Method.HEAD, methods.get(0));
        assertEquals(Method.GET, methods.get(1));
    }

    // SDS-AC-3, SDS-AC-14 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-4-5.sds.md; FR-HTTP-024 AC-1
    @Test
    public void sdsAc14_FR_HTTP_024_AC1_runtimeExceptionBeforeAnyWriteGivesTheStandard500() {
        router.add(Method.GET, "/f", (req, res) -> {
            throw new IllegalArgumentException("boom");
        });
        start();
        assertError(500, "INTERNAL_ERROR", "Internal Server Error", send("GET", "/f"));
    }

    // SDS-AC-14 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-4-5.sds.md; FR-HTTP-024 AC-2
    @Test
    public void sdsAc14_FR_HTTP_024_AC2_checkedExceptionAfterStatusDropsStatusAndHeaders() {
        router.add(Method.GET, "/f", (req, res) -> {
            res.status(201).header("X-A", "1");
            throw new IOException("boom");
        });
        start();
        ClientResponse r = send("GET", "/f");
        assertError(500, "INTERNAL_ERROR", "Internal Server Error", r);
        assertNull(r.header("X-A"));
        assertEquals(2, r.headers().size()); // Content-Type and Content-Length only
    }

    // SDS-AC-5 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-4-5.sds.md; FR-HTTP-024 AC-3
    @Test
    public void sdsAc5_FR_HTTP_024_AC3_throwWhileStreamingAborts() {
        AtomicInteger closes = new AtomicInteger();
        InputStream in = new ByteArrayInputStream(new byte[100]) {
            @Override
            public void close() {
                closes.incrementAndGet();
            }
        };
        router.add(Method.GET, "/s", capturing((req, res) -> {
            res.stream(in, "text/plain");
            throw new IllegalStateException("after stream");
        }));
        start();
        ClientResponse r = send("GET", "/s");
        assertTrue(r.abnormallyClosed());
        assertEquals(200, r.status());
        assertEquals(ResponseState.ABORTED, last.get().state());
        assertEquals(1, closes.get());
    }

    // SDS-AC-5 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-4-5.sds.md; FR-HTTP-024 AC-3
    @Test
    public void sdsAc5_FR_HTTP_024_AC3_throwAfterSendKeepsTheSentBytes() {
        router.add(Method.GET, "/s", capturing((req, res) -> {
            res.send("x");
            throw new IllegalStateException("after send");
        }));
        start();
        ClientResponse r = send("GET", "/s");
        assertEquals(200, r.status());
        assertEquals("x", new String(r.body(), StandardCharsets.UTF_8));
        assertFalse(r.abnormallyClosed());
        assertEquals(ResponseState.COMPLETED, last.get().state());
    }

    // SDS-AC-15 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-4-5.sds.md; FR-HTTP-024 AC-4
    @Test
    public void sdsAc15_FR_HTTP_024_AC4_returnWithoutBodyEndsTheResponseWithOneWarn() {
        RecordingLogger log = new RecordingLogger();
        router.add(Method.GET, "/e", capturing((req, res) -> res.status(202).header("X-A", "1")));
        router.add(Method.GET, "/m", (req, res) -> res.send("never"));
        router.freeze();
        MiddlewarePipeline pipeline = new MiddlewarePipeline(RouterPolicy.strict());
        pipeline.addPrefix("/m", (req, res, next) -> { });
        adapter.bind("127.0.0.1", 0, new Dispatcher(router, pipeline,
                new ErrorPath(java.util.Collections.emptyList(), new SafeLogger(log)), new AccessLog()));
        ClientResponse r = send("GET", "/e");
        assertEmpty(202, r);
        assertEquals("1", r.header("X-A"));
        assertEquals(ResponseState.COMPLETED, last.get().state());
        assertEquals(java.util.Collections.singletonList("WARN response not finished by the handler: GET /e"),
                log.lines);
        log.lines.clear();
        assertEmpty(200, send("GET", "/m"));
        assertEquals(java.util.Collections.singletonList("WARN response not finished by the handler: GET /m"),
                log.lines);
    }

    // SDS-AC-15 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-4-5.sds.md; FR-HTTP-024 AC-4, FR-HTTP-014 AC-7
    @Test
    public void sdsAc15_FR_HTTP_024_AC4_pendingStreamCopiesOnTheHandlerThreadAfterReturnWithoutWarn() {
        RecordingLogger log = new RecordingLogger();
        List<Thread> threads = new ArrayList<>();
        InputStream in = new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public synchronized int read(byte[] b, int off, int len) {
                threads.add(Thread.currentThread());
                return super.read(b, off, len);
            }
        };
        List<Thread> handlerThread = new ArrayList<>();
        router.add(Method.GET, "/s", (req, res) -> {
            handlerThread.add(Thread.currentThread());
            res.stream(in, "text/plain");
            assertTrue(threads.isEmpty());
        });
        router.freeze();
        adapter.bind("127.0.0.1", 0, new Dispatcher(router, new MiddlewarePipeline(RouterPolicy.strict()),
                new ErrorPath(java.util.Collections.emptyList(), new SafeLogger(log)), new AccessLog()));
        ClientResponse r = send("GET", "/s");
        assertEquals("abc", new String(r.body(), StandardCharsets.UTF_8));
        assertTrue(log.lines.isEmpty());
        assertFalse(threads.isEmpty());
        for (Thread th : threads) {
            assertSame(handlerThread.get(0), th);
        }
    }

    // SDS-AC-5 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-4-5.sds.md; FR-HTTP-024 AC-3; CON-APP-005 AC-1
    @Test
    public void sdsAc5_FR_HTTP_024_AC3_throwWhileStreamingIsAbortedByTheCoreNotTheAdapter() {
        router.add(Method.GET, "/s", capturing((req, res) -> {
            res.stream(new ByteArrayInputStream(new byte[10]), "text/plain");
            throw new IOException("after stream");
        }));
        router.freeze();
        RecordingExchange ex = new RecordingExchange("GET", "/s");
        new Dispatcher(router).handle(ex);
        assertEquals(1, ex.commits.get());
        assertEquals(1, ex.aborts.get());
        assertEquals(0, ex.finishes.get());
        assertEquals(ResponseState.ABORTED, last.get().state());
    }

    // SDS-AC-5 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-4-5.sds.md; FR-HTTP-024 AC-3, FR-HTTP-014 AC-5
    @Test
    public void sdsAc5_FR_HTTP_024_AC3_errorAfterStreamClosesTheInputBeforePropagating() {
        AtomicInteger closes = new AtomicInteger();
        InputStream in = new ByteArrayInputStream(new byte[10]) {
            @Override
            public void close() {
                closes.incrementAndGet();
            }
        };
        StackOverflowError thrown = new StackOverflowError("handler");
        router.add(Method.GET, "/s", capturing((req, res) -> {
            res.stream(in, "text/plain");
            throw thrown;
        }));
        router.freeze();
        RecordingExchange ex = new RecordingExchange("GET", "/s");
        try {
            new Dispatcher(router).handle(ex);
            org.junit.Assert.fail("expected the Error to propagate");
        } catch (StackOverflowError e) {
            assertSame(thrown, e);
        }
        assertEquals(1, closes.get());
        assertEquals(1, ex.aborts.get());
        assertEquals(ResponseState.ABORTED, last.get().state());
    }

    // SDS-AC-14 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-4-5.sds.md; FR-HTTP-024 AC-2
    @Test
    public void sdsAc14_FR_HTTP_024_AC2_failureAnswer500MovesTheResponseOutOfNew() {
        router.add(Method.GET, "/f", capturing((req, res) -> {
            res.status(201).header("X-A", "1");
            throw new IOException("boom");
        }));
        router.freeze();
        RecordingExchange ex = new RecordingExchange("GET", "/f");
        new Dispatcher(router).handle(ex);
        assertEquals(500, ex.status);
        assertEquals(1, ex.commits.get());
        assertEquals(2, ex.headers.size());
        assertEquals(java.util.Collections.singletonList("application/json; charset=UTF-8"),
                ex.committed("Content-Type"));
        assertEquals(java.util.Collections.singletonList(String.valueOf(ex.body.size())),
                ex.committed("Content-Length"));
        assertTrue(ex.body.size() > 0);
        assertEquals(ResponseState.COMPLETED, last.get().state());
        try {
            last.get().send("late");
            org.junit.Assert.fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertEquals(1, ex.commits.get());
        }
    }

    // SDS-AC-13 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-4-3.sds.md; FR-APP-009 AC-8
    @Test
    public void sdsAc13_FR_APP_009_AC8_dispatcherBoundWithoutListenDispatchesUngatedInNew() {
        DefaultRestApp app = new DefaultRestApp();
        app.get("/g", recording);
        app.freeze();
        assertEquals(com.snoworca.naruserve.AppState.NEW, app.state());
        assertTrue(app.dispatcher() instanceof Dispatcher);
        assertFalse(app.dispatcher() instanceof GatedExchangeHandler);
        adapter.bind("127.0.0.1", 0, app.dispatcher());
        assertEquals(200, send("GET", "/g").status());
        assertEquals(1, calls.get());
    }

    // SDS-AC-8 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-4-8.sds.md; FR-APP-019 AC-5
    @Test
    public void sdsAc8_FR_APP_019_AC5_inCountsTheBodyBytesReadNotTheDeclaredLength() {
        RecordingLogger log = new RecordingLogger();
        AtomicReference<com.snoworca.naruserve.spi.HttpExchange> current = new AtomicReference<>();
        router.add(Method.POST, "/b", (req, res) -> {
            InputStream body = ((DefaultRequest) req).body().stream(current.get().requestBody());
            assertEquals(4, body.read(new byte[4], 0, 4));
            res.send("ok");
        });
        router.add(Method.POST, "/n", (req, res) -> res.send("ok"));
        router.freeze();
        Dispatcher d = new Dispatcher(router, new MiddlewarePipeline(RouterPolicy.strict()),
                new ErrorPath(java.util.Collections.emptyList(), new SafeLogger(log)),
                new AccessLog(new DefaultAccessLogOptions(), new SafeLogger(log)));
        adapter.bind("127.0.0.1", 0, ex -> {
            current.set(ex);
            d.handle(ex);
        });
        byte[] ten = new byte[10];
        assertEquals(200, adapter.send(ClientRequest.of("POST", "/b").header("Content-Length", "10").body(ten))
                .status());
        assertEquals(1, log.lines.size());
        assertTrue(log.lines.get(0), log.lines.get(0).matches("INFO POST /b 200 \\d+ms in=4 out=2 rid=[0-9a-f]{16}"));
        log.lines.clear();
        adapter.send(ClientRequest.of("POST", "/n").header("Content-Length", "10").body(ten));
        assertEquals(1, log.lines.size());
        assertTrue(log.lines.get(0), log.lines.get(0).matches("INFO POST /n 200 \\d+ms in=0 out=2 rid=[0-9a-f]{16}"));
    }

    // SDS-AC-2 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-12.sds.md; FR-APP-019 AC-8: an accepted upgrade
    // gets one 101 line and its close line, and onDone adds none; a refusal before onOpen (a refused Origin 403, and
    // the adapter's 426 version and 503 limit refusals, injected here through refuseWebSocket) gets one line with its
    // status and no 101 line (T38)
    @Test(timeout = 20000)
    public void wave7SdsAc2_FR_APP_019_AC8_oneHandshakeLinePerSessionAndRefusalsLogTheirStatusOnly()
            throws Exception {
        RecordingLogger log = new RecordingLogger();
        InMemoryHttpAdapter a = new InMemoryHttpAdapter();
        com.snoworca.naruserve.RestApp app = com.snoworca.naruserve.RestApp.builder().httpAdapter(a)
                .jsonCodec(new com.snoworca.naruserve.testing.StubJsonCodec()).logger(log).build();
        app.ws("/ws", (req, ws) -> { });
        app.ws("/guarded", (req, ws) -> { });
        app.webSocket(o -> o.allowedOrigins("https://good.example"));
        app.listen(0);
        try {
            Future<ClientResponse> f = a.submit(ClientRequest.upgrade("/ws", "Origin", "https://good.example"));
            a.nextChannel(5, TimeUnit.SECONDS).clientClose(1000, "");
            assertEquals(101, f.get(5, TimeUnit.SECONDS).status());
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (log.info().size() < 2 && System.nanoTime() < end) {
                Thread.sleep(5);
            }
            Thread.sleep(50); // a line from onDone would follow the close line at once
            assertEquals(log.info().toString(), 2, log.info().size());
            assertTrue(log.info().get(0), log.info().get(0).matches("GET /ws 101 \\d+ms in=0 out=0 rid=[0-9a-f]{16}"));
            assertTrue(log.info().get(1), log.info().get(1).matches("WS /ws 1000 \\d+ms rid=[0-9a-f]{16}"));
            log.lines.clear();
            assertEquals(403, a.send(ClientRequest.upgrade("/guarded", "Origin", "https://evil.example")).status());
            a.refuseWebSocket(new com.snoworca.naruserve.HttpException(426).withHeader("Sec-WebSocket-Version", "13"));
            assertEquals(426, a.send(ClientRequest.upgrade("/ws", "Sec-WebSocket-Version", "12")).status());
            a.refuseWebSocket(new com.snoworca.naruserve.HttpException(503).withHeader("Connection", "close"));
            assertEquals(503, a.send(ClientRequest.upgrade("/ws", "Origin", "https://good.example")).status());
            List<String> lines = log.info();
            assertEquals(lines.toString(), 3, lines.size());
            assertTrue(lines.get(0), lines.get(0).matches("GET /guarded 403 \\d+ms in=0 out=\\d+ rid=[0-9a-f]{16}"));
            assertTrue(lines.get(1), lines.get(1).matches("GET /ws 426 \\d+ms in=0 out=\\d+ rid=[0-9a-f]{16}"));
            assertTrue(lines.get(2), lines.get(2).matches("GET /ws 503 \\d+ms in=0 out=\\d+ rid=[0-9a-f]{16}"));
        } finally {
            app.shutdown(2, TimeUnit.SECONDS);
        }
    }

    // SDS-AC-2 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-12.sds.md; FR-APP-019 AC-8: upgraded101() turns
    // true only when upgraded() ended the response
    @Test
    public void wave7SdsAc2_FR_APP_019_AC8_upgraded101IsTrueOnlyAfterUpgraded() {
        DefaultResponse res = new DefaultResponse(new RecordingExchange("GET", "/ws"), false);
        assertFalse(res.upgraded101());
        res.upgraded();
        assertTrue(res.upgraded101());
        assertEquals(101, res.statusCode());
        DefaultResponse sent = new DefaultResponse(new RecordingExchange("GET", "/ws"), false);
        sent.status(426).send("no");
        sent.upgraded();
        assertFalse(sent.upgraded101());
    }
}
