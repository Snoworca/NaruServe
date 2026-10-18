package com.snoworca.naruserve.routing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Map;
import org.junit.Test;

public class RouteMatchTest {

    private static Router<String> router() {
        Router<String> r = new Router<>(RouterPolicy.strict());
        r.add(Method.GET, "/x/:a/:b", "get");
        r.add(Method.POST, "/p", "post");
        r.addWebSocket("/w", "ws");
        r.freeze();
        return r;
    }

    private static void assertNoMatchFields(RouteMatch<String> m) {
        assertNull(m.handler());
        assertNull(m.method());
        assertNull(m.routeKind());
        assertFalse(m.autoHead());
        assertTrue(m.params().isEmpty());
    }

    private static void assertUnmodifiable(Map<String, String> params) {
        try {
            params.put("c", "3");
            fail("params must be unmodifiable");
        } catch (UnsupportedOperationException expected) {
            // expected
        }
    }

    // SDS-AC-1; IR-ROUTE-004 AC-1
    @Test
    public void sdsAc1_IR_ROUTE_004_AC1_exactlySixKindsAndNoPublicConstructor() {
        assertEquals(
                Arrays.asList(RouteMatch.Kind.MATCHED, RouteMatch.Kind.METHOD_MISMATCH, RouteMatch.Kind.AUTO_OPTIONS,
                        RouteMatch.Kind.WEBSOCKET_ONLY, RouteMatch.Kind.UNSUPPORTED_METHOD, RouteMatch.Kind.NO_MATCH),
                Arrays.asList(RouteMatch.Kind.values()));
        assertTrue(Modifier.isFinal(RouteMatch.class.getModifiers()));
        assertEquals("RouteMatch is built only by Router", 0, RouteMatch.class.getConstructors().length);
    }

    // SDS-AC-1; IR-ROUTE-004 AC-1
    @Test
    public void sdsAc1_IR_ROUTE_004_AC1_matchedCarriesHandlerParamsMethodKind() {
        RouteMatch<String> m = router().lookup("GET", "/x/1/2", false);
        assertEquals(RouteMatch.Kind.MATCHED, m.kind());
        assertEquals("get", m.handler());
        assertEquals(Method.GET, m.method());
        assertEquals(RouteKind.HTTP, m.routeKind());
        assertFalse(m.autoHead());
        assertNull(m.allow());
        assertEquals(Arrays.asList("a", "b"), Arrays.asList(m.params().keySet().toArray()));
        assertEquals("1", m.params().get("a"));
        assertEquals("2", m.params().get("b"));
    }

    // SDS-AC-1; IR-ROUTE-004 AC-1
    @Test
    public void sdsAc1_IR_ROUTE_004_AC1_paramsUnmodifiable() {
        assertUnmodifiable(router().lookup("GET", "/x/1/2", false).params());
        assertUnmodifiable(router().lookup("GET", "/nope", false).params());
        assertUnmodifiable(router().lookup("PUT", "/p", false).params());
    }

    // SDS-AC-1; IR-ROUTE-004 AC-1
    @Test
    public void sdsAc1_IR_ROUTE_004_AC1_mismatchAndAutoOptionsCarryOnlyAllow() {
        RouteMatch<String> mismatch = router().lookup("PUT", "/p", false);
        assertEquals(RouteMatch.Kind.METHOD_MISMATCH, mismatch.kind());
        assertNoMatchFields(mismatch);
        assertEquals(Method.of(Method.POST, Method.OPTIONS), mismatch.allow());

        RouteMatch<String> options = router().lookup("OPTIONS", "/p", false);
        assertEquals(RouteMatch.Kind.AUTO_OPTIONS, options.kind());
        assertNoMatchFields(options);
        assertEquals(Method.of(Method.POST, Method.OPTIONS), options.allow());
    }

    // SDS-AC-1; IR-ROUTE-004 AC-1
    @Test
    public void sdsAc1_IR_ROUTE_004_AC1_otherKindsCarryNoFields() {
        Router<String> r = router();
        RouteMatch<String> ws = r.lookup("POST", "/w", false);
        RouteMatch<String> unsupported = r.lookup("FOO", "/p", false);
        RouteMatch<String> none = r.lookup("GET", "/nope", false);
        assertEquals(RouteMatch.Kind.WEBSOCKET_ONLY, ws.kind());
        assertEquals(RouteMatch.Kind.UNSUPPORTED_METHOD, unsupported.kind());
        assertEquals(RouteMatch.Kind.NO_MATCH, none.kind());
        for (RouteMatch<String> m : Arrays.asList(ws, unsupported, none)) {
            assertNoMatchFields(m);
            assertNull(m.allow());
        }
    }

    // SDS-AC-1; IR-ROUTE-004 AC-1
    @Test
    public void sdsAc1_IR_ROUTE_004_AC1_everyKindReachable() {
        Router<String> r = router();
        EnumSet<RouteMatch.Kind> seen = EnumSet.noneOf(RouteMatch.Kind.class);
        seen.add(r.lookup("GET", "/x/1/2", false).kind());
        seen.add(r.lookup("PUT", "/p", false).kind());
        seen.add(r.lookup("OPTIONS", "/p", false).kind());
        seen.add(r.lookup("GET", "/w", false).kind());
        seen.add(r.lookup("FOO", "/p", false).kind());
        seen.add(r.lookup("GET", "/nope", false).kind());
        assertEquals(EnumSet.allOf(RouteMatch.Kind.class), seen);
    }
}
