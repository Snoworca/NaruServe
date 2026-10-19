package com.snoworca.naruserve.internal;

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
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/**
 * Dispatcher checks and handler invocation. SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-3-3.sds.md
 * (written SDS-3-AC-n where the handler is reached).
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
        assertEquals(0, r.body().length);
        assertFalse(r.abnormallyClosed());
    }

    private void anyOnRootAndVariable() {
        router.add(Method.any(), "/", recording);
        router.add(Method.any(), "/:x", recording);
        start();
    }

    // SDS-AC-1; FR-HTTP-002 AC-2
    @Test
    public void sdsAc1_FR_HTTP_002_AC2_optionsAsteriskIsAnswered204BeforePathValidation() {
        anyOnRootAndVariable();
        ClientResponse r = send("OPTIONS", "*");
        assertEquals(204, r.status());
        assertEquals("GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS", r.header("Allow"));
        assertNull(r.header("Content-Length"));
        assertEquals(0, r.body().length);
        assertEquals(0, calls.get());
    }

    // SDS-AC-1; FR-HTTP-002 AC-2, FR-HTTP-002 AC-3
    @Test
    public void sdsAc1_FR_HTTP_002_AC2_AC3_validatorRejectionIs400BeforeAnyMatch() {
        anyOnRootAndVariable();
        assertEmpty(400, send("GET", "/%2e%2e"));
        assertEmpty(400, send("GET", "/a%2Fb"));
        assertEquals(0, calls.get());
    }

    // SDS-AC-2; FR-HTTP-002 AC-4, FR-HTTP-005 AC-3
    @Test
    public void sdsAc2_FR_HTTP_002_AC4_FR_HTTP_005_AC3_preRequestRejectionsNeverReachTheHandler() {
        anyOnRootAndVariable();
        assertEmpty(501, send("FOO", "/x"));
        assertEmpty(400, send("GET", "abc"));
        assertEmpty(400, send("GET", "*"));
        assertEquals(0, calls.get());
    }

    // SDS-AC-3; FR-HTTP-003 AC-2
    @Test
    public void sdsAc3_FR_HTTP_003_AC2_methodMismatchListsAllowInDeclarationOrder() {
        router.add(Method.POST, "/p", recording);
        router.add(Method.GET, "/r", recording);
        start();
        ClientResponse p = send("GET", "/p");
        assertEmpty(405, p);
        assertEquals("POST, OPTIONS", p.header("Allow"));
        ClientResponse r = send("DELETE", "/r");
        assertEmpty(405, r);
        assertEquals("GET, HEAD, OPTIONS", r.header("Allow"));
        assertEquals(0, calls.get());
    }

    // SDS-AC-3; FR-HTTP-003 AC-7
    @Test
    public void sdsAc3_FR_HTTP_003_AC7_anyExceptDeleteGives405WithAllow() {
        router.add(Method.any().except(Method.DELETE), "/t", recording);
        start();
        ClientResponse r = send("DELETE", "/t");
        assertEmpty(405, r);
        assertEquals("GET, HEAD, POST, PUT, PATCH, OPTIONS", r.header("Allow"));
        assertEquals(0, calls.get());
    }

    // SDS-AC-3; FR-HTTP-005 AC-1
    @Test
    public void sdsAc3_FR_HTTP_005_AC1_optionsAsteriskAllowIsMethodAnyWithNoRoutes() {
        start();
        ClientResponse r = send("OPTIONS", "*");
        assertEquals(204, r.status());
        assertEquals(Dispatcher.allowHeader(Method.any()), r.header("Allow"));
        assertEquals("GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS", r.header("Allow"));
        assertEquals(0, r.body().length);
    }

    // SDS-AC-3; FR-HTTP-003 AC-2
    @Test
    public void sdsAc3_FR_HTTP_003_AC2_allowHeaderJoinsInMethodOrder() {
        assertEquals("GET, POST, OPTIONS", Dispatcher.allowHeader(MethodSet.of(Method.OPTIONS, Method.POST, Method.GET)));
        assertEquals("DELETE", Dispatcher.allowHeader(MethodSet.of(Method.DELETE)));
    }

    // SDS-AC-4; FR-HTTP-003 AC-6
    @Test
    public void sdsAc4_FR_HTTP_003_AC6_splitUpgradeHeadersAreJoinedBeforeLookup() {
        // With both kinds on /ws only the upgrade flag selects the WebSocket route; the HTTP handler must not run.
        router.addWebSocket("/ws", recording);
        router.add(Method.GET, "/ws", recording);
        start();
        ClientResponse r = send("GET", "/ws", "Connection", "keep-alive", "Connection", "Upgrade",
                "Upgrade", "websocket", "Upgrade", "h2c");
        assertEmpty(426, r);
        assertEquals("websocket", r.header("Upgrade"));
        assertEquals("Upgrade", r.header("Connection"));
        assertFalse(r.upgradeHandedOver());
        assertEquals(0, calls.get());
    }

    // SDS-AC-5; FR-HTTP-003 AC-5, FR-HTTP-003 AC-6
    @Test
    public void sdsAc5_FR_HTTP_003_AC5_AC6_webSocketOnlyRouteGives426ForPlainAndUpgradeGet() {
        router.addWebSocket("/ws", recording);
        start();
        ClientResponse plain = send("GET", "/ws");
        assertEmpty(426, plain);
        assertEquals("websocket", plain.header("Upgrade"));
        assertEquals("Upgrade", plain.header("Connection"));
        ClientResponse upgrade = send("GET", "/ws", "Connection", "Upgrade", "Upgrade", "websocket");
        assertEmpty(426, upgrade);
        assertEquals("websocket", upgrade.header("Upgrade"));
        assertEquals("Upgrade", upgrade.header("Connection"));
        assertFalse(upgrade.upgradeHandedOver());
        assertEquals(0, calls.get());
    }

    // SDS-AC-6; FR-HTTP-003 AC-1, FR-HTTP-003 AC-4
    @Test
    public void sdsAc6_FR_HTTP_003_AC1_AC4_noMatchAndUnsupportedCarryContentLengthZero() {
        router.add(Method.GET, "/r", recording);
        start();
        assertEmpty(404, send("GET", "/missing"));
        assertEmpty(501, send("BREW", "/r"));
        assertEmpty(501, send("TRACE", "/r"));
        assertEquals(0, calls.get());
    }

    // SDS-AC-6; FR-HTTP-003 AC-3
    @Test
    public void sdsAc6_FR_HTTP_003_AC3_automaticOptionsIs204WithoutContentLength() {
        router.add(Method.GET, "/r", recording);
        router.add(Method.POST, "/r", recording);
        start();
        ClientResponse r = send("OPTIONS", "/r");
        assertEquals(204, r.status());
        assertEquals("GET, HEAD, POST, OPTIONS", r.header("Allow"));
        assertNull(r.header("Content-Length"));
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

    // SDS-3-AC-1; FR-HTTP-002 AC-3
    @Test
    public void sds3Ac1_FR_HTTP_002_AC3_doubleEncodedDotsReachVariableRouteOnceDecoded() {
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

    // SDS-3-AC-7; FR-HTTP-004 AC-1, FR-HTTP-004 AC-2, FR-HTTP-007 AC-4
    @Test
    public void sds3Ac7_FR_HTTP_004_AC1_AC2_FR_HTTP_007_AC4_autoHeadRunsGetHandlerInHeadMode() {
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

    // SDS-3-AC-8; FR-HTTP-006 AC-1
    @Test
    public void sds3Ac8_FR_HTTP_006_AC1_runtimeExceptionBeforeAnyWriteGives500() {
        router.add(Method.GET, "/f", (req, res) -> {
            throw new IllegalArgumentException("boom");
        });
        start();
        assertEmpty(500, send("GET", "/f"));
    }

    // SDS-3-AC-8; FR-HTTP-006 AC-2
    @Test
    public void sds3Ac8_FR_HTTP_006_AC2_checkedExceptionAfterStatusDropsStatusAndHeaders() {
        router.add(Method.GET, "/f", (req, res) -> {
            res.status(201).header("X-A", "1");
            throw new IOException("boom");
        });
        start();
        ClientResponse r = send("GET", "/f");
        assertEmpty(500, r);
        assertNull(r.header("X-A"));
        assertEquals(1, r.headers().size());
    }

    // SDS-3-AC-9; FR-HTTP-006 AC-3
    @Test
    public void sds3Ac9_FR_HTTP_006_AC3_throwWhileStreamingAborts() {
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

    // SDS-3-AC-9; FR-HTTP-006 AC-3
    @Test
    public void sds3Ac9_FR_HTTP_006_AC3_throwAfterSendKeepsTheSentBytes() {
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

    // SDS-3-AC-10; FR-HTTP-006 AC-4
    @Test
    public void sds3Ac10_FR_HTTP_006_AC4_returnWithoutBodyEndsTheResponse() {
        router.add(Method.GET, "/e", capturing((req, res) -> res.status(202).header("X-A", "1")));
        start();
        ClientResponse r = send("GET", "/e");
        assertEmpty(202, r);
        assertEquals("1", r.header("X-A"));
        assertEquals(ResponseState.COMPLETED, last.get().state());
    }

    // SDS-3-AC-10; FR-HTTP-006 AC-4, FR-HTTP-014 AC-7
    @Test
    public void sds3Ac10_FR_HTTP_006_AC4_pendingStreamCopiesOnTheHandlerThreadAfterReturn() {
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
        start();
        ClientResponse r = send("GET", "/s");
        assertEquals("abc", new String(r.body(), StandardCharsets.UTF_8));
        assertFalse(threads.isEmpty());
        for (Thread th : threads) {
            assertSame(handlerThread.get(0), th);
        }
    }

    // SDS-3-AC-9; FR-HTTP-006 AC-3
    @Test
    public void sds3Ac9_FR_HTTP_006_AC3_throwWhileStreamingIsAbortedByTheCoreNotTheAdapter() {
        router.add(Method.GET, "/s", capturing((req, res) -> {
            res.stream(new ByteArrayInputStream(new byte[10]), "text/plain");
            throw new IOException("after stream");
        }));
        router.freeze();
        ResponseStreamTest.RecordingExchange ex = new ResponseStreamTest.RecordingExchange("GET", "/s");
        new Dispatcher(router).handle(ex);
        assertEquals(1, ex.commits);
        assertEquals(1, ex.aborts);
        assertEquals(0, ex.finishes);
        assertEquals(ResponseState.ABORTED, last.get().state());
    }

    // SDS-3-AC-9; FR-HTTP-006 AC-3, FR-HTTP-014 AC-5
    @Test
    public void sds3Ac9_FR_HTTP_006_AC3_errorAfterStreamClosesTheInputBeforePropagating() {
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
        ResponseStreamTest.RecordingExchange ex = new ResponseStreamTest.RecordingExchange("GET", "/s");
        try {
            new Dispatcher(router).handle(ex);
            org.junit.Assert.fail("expected the Error to propagate");
        } catch (StackOverflowError e) {
            assertSame(thrown, e);
        }
        assertEquals(1, closes.get());
        assertEquals(1, ex.aborts);
        assertEquals(ResponseState.ABORTED, last.get().state());
    }

    // SDS-3-AC-8; FR-HTTP-006 AC-2
    @Test
    public void sds3Ac8_FR_HTTP_006_AC2_failureAnswer500MovesTheResponseOutOfNew() {
        router.add(Method.GET, "/f", capturing((req, res) -> {
            res.status(201).header("X-A", "1");
            throw new IOException("boom");
        }));
        router.freeze();
        ResponseStreamTest.RecordingExchange ex = new ResponseStreamTest.RecordingExchange("GET", "/f");
        new Dispatcher(router).handle(ex);
        assertEquals(500, ex.status);
        assertEquals(1, ex.commits);
        assertEquals(1, ex.headers.size());
        assertEquals(java.util.Collections.singletonList("0"), ex.committed("Content-Length"));
        assertEquals(ResponseState.COMPLETED, last.get().state());
        try {
            last.get().send("late");
            org.junit.Assert.fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertEquals(1, ex.commits);
        }
    }
}
