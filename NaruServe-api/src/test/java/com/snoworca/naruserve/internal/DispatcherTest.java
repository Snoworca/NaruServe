package com.snoworca.naruserve.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import com.snoworca.naruserve.Method;
import com.snoworca.naruserve.MethodSet;
import com.snoworca.naruserve.RouteHandler;
import com.snoworca.naruserve.routing.Router;
import com.snoworca.naruserve.routing.RouterPolicy;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/**
 * Dispatcher checks that answer without a handler call. Handler invocation (SDS-AC-7..SDS-AC-10) needs
 * DefaultRequest and DefaultResponse, which wave-3 parts 4 and 5 add; so does the SDS-AC-1 case that
 * /%252e%252e reaches /:x with param x = %2e%2e, still to be added here then.
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
}
