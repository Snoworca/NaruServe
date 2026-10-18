package com.snoworca.naruserve.routing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.Method;
import com.snoworca.naruserve.MethodSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class RouterAutoResponseTest {

    private static final RouterPolicy STRICT = RouterPolicy.strict();

    private static Router<String> router() {
        return new Router<>(STRICT);
    }

    private static MethodSet allow(Router<String> r, String token, String path) {
        RouteMatch<String> m = r.lookup(token, path, false);
        assertEquals(RouteMatch.Kind.METHOD_MISMATCH, m.kind());
        MethodSet allow = m.allow();
        assertEquals(allow, r.allowFor(Router.decodeSegments(path, STRICT)));
        return allow;
    }

    // SDS-AC-5; FR-ROUTE-009 AC-1; FR-ROUTE-009 AC-2; IR-ROUTE-004 AC-2
    @Test
    public void sdsAc5_FR_ROUTE_009_AC1_AC2_unmatchedPathIsNoMatchIncludingOptions() {
        Router<String> r = router();
        r.add(Method.GET, "/users", "users");
        r.freeze();
        for (Method m : Method.any()) {
            assertEquals(m.name(), RouteMatch.Kind.NO_MATCH, r.lookup(m.name(), "/nope", false).kind());
        }
        assertEquals(RouteMatch.Kind.NO_MATCH, r.lookup("OPTIONS", "/nope", false).kind());
        assertEquals(RouteMatch.Kind.NO_MATCH, r.lookup("GET", "/nope", true).kind());
    }

    // SDS-AC-5; FR-ROUTE-009 AC-3
    @Test
    public void sdsAc5_FR_ROUTE_009_AC3_webSocketOnlyPathIsNeverNoMatch() {
        Router<String> r = router();
        r.addWebSocket("/w", "ws");
        r.freeze();
        for (Method m : Method.any()) {
            for (boolean upgrade : new boolean[] {false, true}) {
                assertNotEquals(m + " " + upgrade, RouteMatch.Kind.NO_MATCH, r.lookup(m.name(), "/w", upgrade).kind());
            }
        }
    }

    // SDS-AC-6; FR-ROUTE-010 AC-1
    @Test
    public void sdsAc6_FR_ROUTE_010_AC1_anyExceptDelete() {
        Router<String> r = router();
        r.add(Method.any().except(Method.DELETE), "/r", "r");
        r.freeze();
        MethodSet allow = allow(r, "DELETE", "/r");
        assertEquals(Method.of(Method.GET, Method.HEAD, Method.POST, Method.PUT, Method.PATCH, Method.OPTIONS), allow);
        assertEquals("GET, HEAD, POST, PUT, PATCH, OPTIONS", allow.toString());
    }

    // SDS-AC-6; FR-ROUTE-010 AC-2
    @Test
    public void sdsAc6_FR_ROUTE_010_AC2_unionOverEveryMatchingPattern() {
        Router<String> r = router();
        r.add(Method.POST, "/a/:id", "post");
        r.add(Method.GET, "/a/me", "get");
        r.freeze();
        MethodSet allow = allow(r, "PUT", "/a/me");
        assertEquals("GET, HEAD, POST, OPTIONS", allow.toString());
        List<Method> order = new ArrayList<>();
        for (Method m : allow) {
            order.add(m);
        }
        assertEquals(Arrays.asList(Method.GET, Method.HEAD, Method.POST, Method.OPTIONS), order);
    }

    // SDS-AC-6; FR-ROUTE-010 AC-3
    @Test
    public void sdsAc6_FR_ROUTE_010_AC3_noHeadWithoutGet() {
        Router<String> r = router();
        r.add(Method.POST, "/p", "post");
        r.freeze();
        assertEquals("POST, OPTIONS", allow(r, "GET", "/p").toString());
    }

    // SDS-AC-6; FR-ROUTE-010 AC-4
    @Test
    public void sdsAc6_FR_ROUTE_010_AC4_webSocketCountsAsGet() {
        Router<String> r = router();
        r.addWebSocket("/w", "ws");
        r.add(Method.POST, "/w", "post");
        r.freeze();
        assertEquals("GET, HEAD, POST, OPTIONS", allow(r, "PUT", "/w").toString());
    }

    // SDS-AC-7; FR-ROUTE-011 AC-1
    @Test
    public void sdsAc7_FR_ROUTE_011_AC1_headResolvesToGet() {
        Router<String> r = router();
        r.add(Method.GET, "/x", "get");
        r.freeze();
        RouteMatch<String> m = r.lookup("HEAD", "/x", false);
        assertEquals(RouteMatch.Kind.MATCHED, m.kind());
        assertEquals("get", m.handler());
        assertEquals(Method.GET, m.method());
        assertEquals(RouteKind.HTTP, m.routeKind());
        assertTrue(m.autoHead());
        assertFalse(r.lookup("GET", "/x", false).autoHead());
    }

    // SDS-AC-7; FR-ROUTE-011 AC-2
    @Test
    public void sdsAc7_FR_ROUTE_011_AC2_explicitHeadWins() {
        Router<String> r = router();
        r.add(Method.GET, "/x", "get");
        r.add(Method.HEAD, "/x", "head");
        r.freeze();
        RouteMatch<String> m = r.lookup("HEAD", "/x", false);
        assertEquals("head", m.handler());
        assertEquals(Method.HEAD, m.method());
        assertFalse(m.autoHead());
    }

    // SDS-AC-7; FR-ROUTE-011 AC-3
    @Test
    public void sdsAc7_FR_ROUTE_011_AC3_headVariableBeatsGetLiteral() {
        Router<String> r = router();
        r.add(Method.HEAD, "/x/:id", "head");
        r.add(Method.GET, "/x/me", "get");
        r.freeze();
        RouteMatch<String> m = r.lookup("HEAD", "/x/me", false);
        assertEquals("head", m.handler());
        assertEquals("me", m.params().get("id"));
        assertFalse(m.autoHead());
    }

    // SDS-AC-8; FR-ROUTE-012 AC-1
    @Test
    public void sdsAc8_FR_ROUTE_012_AC1_autoOptionsAllow() {
        Router<String> r = router();
        r.add(Method.GET, "/x", "get");
        r.add(Method.POST, "/x", "post");
        r.freeze();
        RouteMatch<String> m = r.lookup("OPTIONS", "/x", false);
        assertEquals(RouteMatch.Kind.AUTO_OPTIONS, m.kind());
        assertEquals("GET, HEAD, POST, OPTIONS", String.valueOf(m.allow()));
        assertEquals(r.allowFor(Router.decodeSegments("/x", STRICT)), m.allow());
    }

    // SDS-AC-8; FR-ROUTE-012 AC-2
    @Test
    public void sdsAc8_FR_ROUTE_012_AC2_explicitOptionsWins() {
        Router<String> r = router();
        r.add(Method.GET, "/x", "get");
        r.add(Method.OPTIONS, "/x", "options");
        r.freeze();
        RouteMatch<String> m = r.lookup("OPTIONS", "/x", false);
        assertEquals(RouteMatch.Kind.MATCHED, m.kind());
        assertEquals("options", m.handler());
        assertEquals(Method.OPTIONS, m.method());
    }

    // SDS-AC-8; FR-ROUTE-012 AC-3
    @Test
    public void sdsAc8_FR_ROUTE_012_AC3_webSocketOnlyOptionsAllow() {
        Router<String> r = router();
        r.addWebSocket("/w", "ws");
        r.freeze();
        RouteMatch<String> m = r.lookup("OPTIONS", "/w", false);
        assertEquals(RouteMatch.Kind.AUTO_OPTIONS, m.kind());
        assertEquals("GET, HEAD, OPTIONS", String.valueOf(m.allow()));
    }

    // SDS-AC-9; FR-ROUTE-013 AC-1; FR-ROUTE-013 AC-2; IR-ROUTE-004 AC-2
    @Test
    public void sdsAc9_FR_ROUTE_013_AC1_AC2_unknownTokensUnsupported() {
        Router<String> r = router();
        r.add(Method.GET, "/x", "get");
        r.freeze();
        for (String token : new String[] {"FOO", "get", "Get", "PROPFIND", "", " GET", "GET "}) {
            assertEquals(token, RouteMatch.Kind.UNSUPPORTED_METHOD, r.lookup(token, "/x", false).kind());
            assertEquals(token, RouteMatch.Kind.UNSUPPORTED_METHOD, r.lookup(token, "/nope", false).kind());
            assertEquals(token, RouteMatch.Kind.UNSUPPORTED_METHOD, r.lookup(token, "/x", true).kind());
        }
        assertEquals(RouteMatch.Kind.MATCHED, r.lookup("GET", "/x", false).kind());
    }

    // w2-P2-003; FR-ROUTE-010 AC-1; FR-ROUTE-012 AC-1
    @Test
    public void w2P2_003_FR_ROUTE_010_AC1_FR_ROUTE_012_AC1_allowForBeforeFreezeIsRejected() {
        Router<String> r = router();
        r.add(Method.GET, "/x", "get");
        try {
            r.allowFor(Router.decodeSegments("/x", STRICT));
            org.junit.Assert.fail("allowFor ran on an unfrozen router");
        } catch (IllegalStateException expected) {
            // same guard as lookup
        }
    }
}
