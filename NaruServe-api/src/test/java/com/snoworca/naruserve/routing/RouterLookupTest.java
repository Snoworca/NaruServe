package com.snoworca.naruserve.routing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.Method;
import org.junit.Test;

public class RouterLookupTest {

    private static Router<String> frozen() {
        Router<String> r = new Router<>(RouterPolicy.strict());
        r.add(Method.GET, "/files/:name", "files");
        r.add(Method.GET, "/a/b", "ab");
        r.freeze();
        return r;
    }

    private static void assertRejected(Router<String> r, String token, String path) {
        try {
            r.lookup(token, path, false);
            fail("accepted " + token + " " + path);
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(path));
        }
    }

    // SDS-AC-2; IR-ROUTE-004 AC-4
    @Test
    public void sdsAc2_IR_ROUTE_004_AC4_rejectedPathThrowsBeforeTokenIsRead() {
        Router<String> r = frozen();
        assertRejected(r, "FOO", "/a%zz");
        assertRejected(r, "GET", "/a%zz");
        assertRejected(r, "GET", "/a/..");
        assertRejected(r, "TRACE", "/a/..");
    }

    // SDS-AC-3; IR-ROUTE-004 AC-5
    @Test
    public void sdsAc3_IR_ROUTE_004_AC5_lookupBeforeFreezeThrows() {
        Router<String> r = new Router<>(RouterPolicy.strict());
        r.add(Method.GET, "/a", "a");
        try {
            r.lookup("GET", "/a", false);
            fail("lookup before freeze");
        } catch (IllegalStateException expected) {
            // expected
        }
    }

    // SDS-AC-3; IR-ROUTE-004 AC-5
    @Test
    public void sdsAc3_IR_ROUTE_004_AC5_lookupAfterFailedFreezeThrows() {
        Router<String> r = new Router<>(RouterPolicy.strict());
        r.add(Method.GET, "/a", "1");
        r.add(Method.GET, "/a", "2");
        try {
            r.freeze();
            fail("freeze passed");
        } catch (RoutingConfigException expected) {
            // expected
        }
        try {
            r.lookup("GET", "/a", false);
            fail("lookup after failed freeze");
        } catch (IllegalStateException expected) {
            // expected
        }
    }

    // SDS-AC-3; IR-ROUTE-004 AC-5
    @Test
    public void sdsAc3_IR_ROUTE_004_AC5_addAfterFreezeThrows() {
        Router<String> r = frozen();
        try {
            r.add(Method.GET, "/z", "z");
            fail("add after freeze");
        } catch (IllegalStateException expected) {
            // expected
        }
        try {
            r.addWebSocket("/z", "z");
            fail("addWebSocket after freeze");
        } catch (IllegalStateException expected) {
            // expected
        }
    }

    // SDS-AC-3; IR-ROUTE-004 AC-5
    @Test
    public void sdsAc3_IR_ROUTE_004_AC5_upgradeIgnoredUnlessGet() {
        Router<String> r = new Router<>(RouterPolicy.strict());
        r.add(Method.POST, "/w", "post");
        r.addWebSocket("/w", "ws");
        r.freeze();
        RouteMatch<String> post = r.lookup("POST", "/w", true);
        assertEquals(RouteMatch.Kind.MATCHED, post.kind());
        assertEquals("post", post.handler());
        assertEquals(RouteKind.HTTP, post.routeKind());
        assertEquals(RouteMatch.Kind.WEBSOCKET_ONLY, r.lookup("HEAD", "/w", true).kind());
        assertEquals(RouteMatch.Kind.METHOD_MISMATCH, r.lookup("PUT", "/w", true).kind());
        assertEquals(RouteMatch.Kind.AUTO_OPTIONS, r.lookup("OPTIONS", "/w", true).kind());
    }

    // SDS-AC-4; IR-ROUTE-004 AC-3
    @Test
    public void sdsAc4_IR_ROUTE_004_AC3_precedenceOnOnePath() {
        Router<String> r = new Router<>(RouterPolicy.strict());
        r.add(Method.GET, "/x", "get");
        r.add(Method.OPTIONS, "/x", "options");
        r.add(Method.HEAD, "/x", "head");
        r.addWebSocket("/x", "ws");
        r.freeze();
        // A route of the request method wins over automatic OPTIONS and automatic HEAD.
        assertEquals("options", r.lookup("OPTIONS", "/x", false).handler());
        RouteMatch<String> head = r.lookup("HEAD", "/x", false);
        assertEquals("head", head.handler());
        assertFalse(head.autoHead());
        assertEquals("get", r.lookup("GET", "/x", false).handler());
        assertEquals(RouteMatch.Kind.METHOD_MISMATCH, r.lookup("DELETE", "/x", false).kind());

        Router<String> s = new Router<>(RouterPolicy.strict());
        s.add(Method.GET, "/y", "get");
        s.add(Method.POST, "/y", "post");
        s.addWebSocket("/y", "ws");
        s.freeze();
        // Automatic OPTIONS and automatic HEAD win over WebSocket-only and method mismatch.
        assertEquals(RouteMatch.Kind.AUTO_OPTIONS, s.lookup("OPTIONS", "/y", false).kind());
        RouteMatch<String> autoHead = s.lookup("HEAD", "/y", false);
        assertEquals(RouteMatch.Kind.MATCHED, autoHead.kind());
        assertTrue(autoHead.autoHead());
        assertEquals(RouteMatch.Kind.METHOD_MISMATCH, s.lookup("PUT", "/y", false).kind());

        Router<String> t = new Router<>(RouterPolicy.strict());
        t.add(Method.POST, "/z", "post");
        t.addWebSocket("/z", "ws");
        t.freeze();
        // WebSocket-only wins over method mismatch for GET and HEAD.
        assertEquals(RouteMatch.Kind.WEBSOCKET_ONLY, t.lookup("GET", "/z", false).kind());
        assertEquals(RouteMatch.Kind.METHOD_MISMATCH, t.lookup("PUT", "/z", false).kind());
    }

    // SDS-AC-4; IR-ROUTE-004 AC-4
    @Test
    public void sdsAc4_IR_ROUTE_004_AC4_rawPathDecodedOnceIntoParams() {
        RouteMatch<String> m = frozen().lookup("GET", "/files/%252e", false);
        assertEquals(RouteMatch.Kind.MATCHED, m.kind());
        assertEquals("files", m.handler());
        assertEquals("%2e", m.params().get("name"));
        assertEquals("files", frozen().lookup("GET", "/files/x?q=%zz", false).handler());
    }
}
