package com.snoworca.naruserve.routing;

import static org.junit.Assert.assertEquals;

import com.snoworca.naruserve.Method;
import org.junit.Test;

public class RouterTraceConnectTest {

    private static Router<String> router(RouterPolicy policy, Method... extra) {
        Router<String> r = new Router<>(policy);
        r.add(Method.any(), "/x", "any");
        r.add(Method.GET, "/g", "get");
        for (Method m : extra) {
            r.add(m, "/x", m.name());
        }
        r.freeze();
        return r;
    }

    private static RouteMatch.Kind kind(Router<String> r, String token, String path) {
        return r.lookup(token, path, false).kind();
    }

    // SDS-AC-10; FR-ROUTE-014 AC-1
    @Test
    public void sdsAc10_FR_ROUTE_014_AC1_defaultPolicyUnsupported() {
        Router<String> r = router(RouterPolicy.strict());
        for (String token : new String[] {"TRACE", "CONNECT"}) {
            assertEquals(RouteMatch.Kind.UNSUPPORTED_METHOD, kind(r, token, "/x"));
            assertEquals(RouteMatch.Kind.UNSUPPORTED_METHOD, kind(r, token, "/nope"));
        }
    }

    private static void assertEnabledRoutes(Method enabled, Method disabled, RouterPolicy policy) {
        Router<String> r = router(policy, enabled);
        RouteMatch<String> m = r.lookup(enabled.name(), "/x", false);
        assertEquals(RouteMatch.Kind.MATCHED, m.kind());
        assertEquals(enabled.name(), m.handler());
        assertEquals(enabled, m.method());
        assertEquals(RouteMatch.Kind.NO_MATCH, kind(r, enabled.name(), "/y"));
        assertEquals(RouteMatch.Kind.METHOD_MISMATCH, kind(r, enabled.name(), "/g"));
        assertEquals(RouteMatch.Kind.UNSUPPORTED_METHOD, kind(r, disabled.name(), "/x"));
        // Method.any() never serves the enabled method.
        Router<String> anyOnly = router(policy);
        assertEquals(RouteMatch.Kind.METHOD_MISMATCH, kind(anyOnly, enabled.name(), "/x"));
    }

    // SDS-AC-10; FR-ROUTE-014 AC-2
    @Test
    public void sdsAc10_FR_ROUTE_014_AC2_traceEnabled() {
        assertEnabledRoutes(Method.TRACE, Method.CONNECT, RouterPolicy.builder().enableTrace(true).build());
    }

    // SDS-AC-10; FR-ROUTE-014 AC-3
    @Test
    public void sdsAc10_FR_ROUTE_014_AC3_connectEnabled() {
        assertEnabledRoutes(Method.CONNECT, Method.TRACE, RouterPolicy.builder().enableConnect(true).build());
    }
}
