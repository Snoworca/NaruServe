package com.snoworca.naruserve.routing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class RouterPriorityTest {

    private static String handler(Router<String> r, Method m, String path) {
        Router.Route<String> route = r.find(m, Router.decodeSegments(path, RouterPolicy.strict()));
        return route == null ? "none" : route.handler;
    }

    private static Router<String> router(String... regs) {
        Router<String> r = new Router<>(RouterPolicy.strict());
        for (String reg : regs) {
            String[] parts = reg.split(" ");
            r.add(Method.valueOf(parts[0]), parts[1], reg);
        }
        r.freeze();
        return r;
    }

    // SDS-AC-11; FR-ROUTE-007 AC-1
    @Test
    public void sdsAc11_FR_ROUTE_007_AC1_literalBeatsVariableInBothOrders() {
        List<Router<String>> routers = Arrays.asList(
                router("GET /users/:id", "GET /users/me"), router("GET /users/me", "GET /users/:id"));
        for (Router<String> r : routers) {
            assertEquals("GET /users/me", handler(r, Method.GET, "/users/me"));
            assertEquals("GET /users/:id", handler(r, Method.GET, "/users/42"));
        }
    }

    // SDS-AC-11; FR-ROUTE-007 AC-2
    @Test
    public void sdsAc11_FR_ROUTE_007_AC2_leftmostDifferingSegmentDecides() {
        assertEquals("GET /a/b/:y", handler(router("GET /a/:x/c", "GET /a/b/:y"), Method.GET, "/a/b/c"));
        assertEquals("GET /a/b/:y", handler(router("GET /a/b/:y", "GET /a/:x/c"), Method.GET, "/a/b/c"));
        RoutePattern ax = RoutePattern.parse("/a/:x/c");
        RoutePattern ab = RoutePattern.parse("/a/b/:y");
        assertTrue(ab.compareSpecificity(ax) > 0);
        assertTrue(ax.compareSpecificity(ab) < 0);
        assertEquals(0, ax.compareSpecificity(RoutePattern.parse("/a/:z/c")));
    }

    // SDS-AC-11; FR-ROUTE-007 AC-3
    @Test
    public void sdsAc11_FR_ROUTE_007_AC3_priorityIsPerMethod() {
        Router<String> r = router("GET /users/me", "DELETE /users/:id");
        assertEquals("DELETE /users/:id", handler(r, Method.DELETE, "/users/me"));
        assertEquals("GET /users/me", handler(r, Method.GET, "/users/me"));
    }
}
