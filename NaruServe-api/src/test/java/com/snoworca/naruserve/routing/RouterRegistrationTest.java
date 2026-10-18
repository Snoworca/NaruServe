package com.snoworca.naruserve.routing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.Method;
import org.jspecify.annotations.Nullable;
import org.junit.Test;

public class RouterRegistrationTest {

    private static Router.@Nullable Route<String> find(Router<String> r, Method m, String path) {
        return r.find(m, Router.decodeSegments(path, RouterPolicy.strict()));
    }

    // SDS-AC-1; CON-ROUTE-001 AC-2; FR-ROUTE-002 AC-1
    @Test
    public void sdsAc1_CON_ROUTE_001_AC2_FR_ROUTE_002_AC1_methodSetRegistrationServesEveryMember() {
        Router<String> r = new Router<>(RouterPolicy.strict());
        r.add(Method.get().delete().post(), "/items", "h");
        r.freeze();
        for (Method m : Method.of(Method.GET, Method.DELETE, Method.POST)) {
            Router.Route<String> route = find(r, m, "/items");
            assertEquals(m.name(), "h", route == null ? null : route.handler);
        }
        assertNull(find(r, Method.PUT, "/items"));
    }

    // SDS-AC-1; FR-ROUTE-002 AC-2
    @Test
    public void sdsAc1_FR_ROUTE_002_AC2_perMethodHandlersOnOnePattern() {
        Router<String> r = new Router<>(RouterPolicy.strict());
        r.add(Method.GET, "/users/:id", "h1");
        r.add(Method.DELETE, "/users/:id", "h2");
        r.freeze();
        assertEquals("h1", find(r, Method.GET, "/users/1").handler);
        assertEquals("h2", find(r, Method.DELETE, "/users/1").handler);
    }

    // CON-ROUTE-001 AC-1
    @Test
    public void CON_ROUTE_001_AC1_routerClassesLiveInApiMainUnderNaruServePackage() throws Exception {
        for (Class<?> c : new Class<?>[] {Router.class, RoutePattern.class, PathValidator.class, RouterPolicy.class,
            RouteKind.class, RoutingConfigException.class}) {
            assertEquals("com.snoworca.naruserve.routing", c.getPackage().getName());
            String where = c.getProtectionDomain().getCodeSource().getLocation().toURI().getPath();
            // Main output is the classes dir or, when test fixtures are applied, the module jar.
            String w = where.replace('\\', '/');
            assertTrue(where, w.contains("NaruServe-api/build/classes/java/main")
                    || w.matches(".*/NaruServe-api/build/libs/NaruServe-api-[^/]*\\.jar"));
        }
    }

    // SDS-AC-1; CON-ROUTE-001 AC-2
    @Test
    public void sdsAc1_CON_ROUTE_001_AC2_noRequestResponseHandlerOrAppTypeInApiMain() {
        String[] names = {"Request", "Response", "RouteHandler", "RestApp"};
        String[] pkgs = {"com.snoworca.naruserve.", "com.snoworca.naruserve.routing."};
        for (String p : pkgs) {
            for (String n : names) {
                try {
                    Class.forName(p + n);
                    fail(p + n + " must not exist in wave 2");
                } catch (ClassNotFoundException expected) {
                    // absent, as required
                }
            }
        }
    }

    // SDS-AC-4; FR-ROUTE-002 AC-3
    @Test
    public void sdsAc4_FR_ROUTE_002_AC3_addWebSocketStoresWebSocketKindAsGet() {
        Router<String> r = new Router<>(RouterPolicy.strict());
        r.addWebSocket("/ws", "w");
        Router.Route<String> route = r.routes.get(0);
        assertEquals(RouteKind.WEBSOCKET, route.kind);
        assertEquals(Method.GET, route.method);
        assertEquals("w", route.handler);
        assertNull("a WebSocket route is not an HTTP GET route", find(r, Method.GET, "/ws"));
    }

    // SDS-AC-4; FR-ROUTE-008 AC-5
    @Test
    public void sdsAc4_FR_ROUTE_008_AC5_webSocketAndGetOnOnePatternFreeze() {
        Router<String> r = new Router<>(RouterPolicy.strict());
        r.addWebSocket("/chat", "w");
        r.add(Method.GET, "/chat", "g");
        r.freeze();
        assertEquals(RouteKind.HTTP, find(r, Method.GET, "/chat").kind);
    }

    // SDS-AC-4; FR-ROUTE-002 AC-3
    @Test
    public void sdsAc4_FR_ROUTE_002_AC3_twoWebSocketRoutesOnOnePatternConflictAsGet() {
        Router<String> r = new Router<>(RouterPolicy.strict());
        r.addWebSocket("/chat", "w1");
        r.addWebSocket("/chat", "w2");
        try {
            r.freeze();
            fail();
        } catch (RoutingConfigException e) {
            assertEquals("GET /chat conflicts with GET /chat", e.getMessage());
        }
    }
}
