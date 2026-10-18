package com.snoworca.naruserve.routing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.Method;
import org.junit.Test;

public class RouterWebSocketTest {

    private static RouteMatch.Kind kind(Router<String> r, String token, String path) {
        return r.lookup(token, path, false).kind();
    }

    // SDS-AC-11; FR-ROUTE-015 AC-2
    @Test
    public void sdsAc11_FR_ROUTE_015_AC2_upgradeFlagHelper() {
        assertTrue(Router.isWebSocketUpgrade("GET", "keep-alive, Upgrade", "WebSocket"));
        assertTrue(Router.isWebSocketUpgrade("GET", "upgrade", " websocket "));
        assertTrue(Router.isWebSocketUpgrade("GET", "Upgrade", "h2c, websocket"));
        assertFalse(Router.isWebSocketUpgrade("GET", "keep-alive, Upgrade", "h2c"));
        assertFalse(Router.isWebSocketUpgrade("GET", "keep-alive", "websocket"));
        assertFalse(Router.isWebSocketUpgrade("GET", "upgraded", "websocket"));
        assertFalse(Router.isWebSocketUpgrade("POST", "Upgrade", "websocket"));
        assertFalse(Router.isWebSocketUpgrade("get", "Upgrade", "websocket"));
        assertFalse(Router.isWebSocketUpgrade("GET", null, "websocket"));
        assertFalse(Router.isWebSocketUpgrade("GET", "Upgrade", null));
        assertFalse(Router.isWebSocketUpgrade("GET", null, null));
    }

    // SDS-AC-12; FR-ROUTE-016 AC-1
    @Test
    public void sdsAc12_FR_ROUTE_016_AC1_webSocketOnlyPath() {
        Router<String> r = new Router<>(RouterPolicy.strict());
        r.addWebSocket("/w", "ws");
        r.freeze();
        assertEquals(RouteMatch.Kind.WEBSOCKET_ONLY, kind(r, "GET", "/w"));
        assertEquals(RouteMatch.Kind.WEBSOCKET_ONLY, kind(r, "HEAD", "/w"));
        assertEquals(RouteMatch.Kind.WEBSOCKET_ONLY, kind(r, "POST", "/w"));
        assertEquals(RouteMatch.Kind.AUTO_OPTIONS, kind(r, "OPTIONS", "/w"));
    }

    // SDS-AC-12; FR-ROUTE-016 AC-2
    @Test
    public void sdsAc12_FR_ROUTE_016_AC2_webSocketAndPost() {
        Router<String> r = new Router<>(RouterPolicy.strict());
        r.addWebSocket("/w", "ws");
        r.add(Method.POST, "/w", "post");
        r.freeze();
        assertEquals(RouteMatch.Kind.WEBSOCKET_ONLY, kind(r, "GET", "/w"));
        assertEquals(RouteMatch.Kind.WEBSOCKET_ONLY, kind(r, "HEAD", "/w"));
        RouteMatch<String> post = r.lookup("POST", "/w", false);
        assertEquals(RouteMatch.Kind.MATCHED, post.kind());
        assertEquals("post", post.handler());
    }

    // SDS-AC-12; FR-ROUTE-016 AC-3; FR-ROUTE-015 AC-1; FR-ROUTE-015 AC-3
    @Test
    public void sdsAc12_FR_ROUTE_016_AC3_FR_ROUTE_015_AC1_AC3_webSocketAndGetCoexist() {
        Router<String> r = new Router<>(RouterPolicy.strict());
        r.addWebSocket("/w", "ws");
        r.add(Method.GET, "/w", "get");
        r.freeze();
        RouteMatch<String> get = r.lookup("GET", "/w", false);
        assertEquals("get", get.handler());
        assertEquals(RouteKind.HTTP, get.routeKind());
        RouteMatch<String> head = r.lookup("HEAD", "/w", false);
        assertEquals("get", head.handler());
        assertTrue(head.autoHead());
        RouteMatch<String> ws = r.lookup("GET", "/w", true);
        assertEquals(RouteMatch.Kind.MATCHED, ws.kind());
        assertEquals("ws", ws.handler());
        assertEquals(RouteKind.WEBSOCKET, ws.routeKind());
        assertEquals(Method.GET, ws.method());
        assertFalse(ws.autoHead());
    }

    // SDS-AC-13; FR-ROUTE-017 AC-1
    @Test
    public void sdsAc13_FR_ROUTE_017_AC1_upgradeFallsBackToGet() {
        Router<String> r = new Router<>(RouterPolicy.strict());
        r.add(Method.GET, "/g", "get");
        r.freeze();
        RouteMatch<String> m = r.lookup("GET", "/g", true);
        assertEquals(RouteMatch.Kind.MATCHED, m.kind());
        assertEquals("get", m.handler());
        assertEquals(RouteKind.HTTP, m.routeKind());
        assertEquals(Method.GET, m.method());
    }

    // SDS-AC-13; FR-ROUTE-017 AC-2; FR-ROUTE-017 AC-3
    @Test
    public void sdsAc13_FR_ROUTE_017_AC2_AC3_upgradeFallbackMismatchAndNoMatch() {
        Router<String> r = new Router<>(RouterPolicy.strict());
        r.add(Method.POST, "/p", "post");
        r.freeze();
        assertEquals(RouteMatch.Kind.METHOD_MISMATCH, r.lookup("GET", "/p", true).kind());
        assertEquals(RouteMatch.Kind.NO_MATCH, r.lookup("GET", "/none", true).kind());
    }
}
