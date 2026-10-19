package com.snoworca.naruserve.routing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.Method;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.junit.Test;

public class RouterRegistrationTest {

    private static Router.@Nullable Route<String> find(Router<String> r, Method m, String path) {
        return r.find(m, Router.decodeSegments(path, RouterPolicy.strict()));
    }

    // SDS-AC-8; CON-ROUTE-006 AC-2; FR-ROUTE-002 AC-1
    @Test
    public void sdsAc8_CON_ROUTE_006_AC2_FR_ROUTE_002_AC1_methodSetRegistrationServesEveryMember() {
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

    // CON-ROUTE-006 AC-1
    @Test
    public void CON_ROUTE_006_AC1_routerClassesLiveInApiMainUnderNaruServePackage() throws Exception {
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

    // SDS-AC-8; CON-ROUTE-006 AC-2
    @Test
    public void sdsAc8_CON_ROUTE_006_AC2_noRoutingClassReferencesRequestResponseHandlerOrApp() throws Exception {
        Pattern forbidden = Pattern.compile(
                "com/snoworca/naruserve/(Request|Response|RouteHandler|RestApp)(?![A-Za-z0-9_$])");
        Map<String, byte[]> classes = routingClassFiles();
        assertTrue(classes.keySet().toString(), classes.containsKey("com/snoworca/naruserve/routing/Router.class"));
        for (Map.Entry<String, byte[]> e : classes.entrySet()) {
            // ISO-8859-1 keeps every byte, so the ASCII class names in the constant pool survive the decode.
            Matcher m = forbidden.matcher(new String(e.getValue(), StandardCharsets.ISO_8859_1));
            if (m.find()) {
                fail(e.getKey() + " references " + m.group());
            }
        }
        // The scan bites: the same matcher finds RouteHandler in RestApp, a class that does reference it.
        String restApp = "com/snoworca/naruserve/RestApp.class";
        byte[] restAppBytes = readAll(Router.class.getClassLoader().getResourceAsStream(restApp));
        assertTrue(forbidden.matcher(new String(restAppBytes, StandardCharsets.ISO_8859_1)).find());
    }

    /** Every compiled class of the routing package, from the classes dir or from the module jar. */
    private static Map<String, byte[]> routingClassFiles() throws Exception {
        String prefix = "com/snoworca/naruserve/routing/";
        Map<String, byte[]> out = new TreeMap<>();
        File where = new File(Router.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (where.isDirectory()) {
            File[] files = new File(where, prefix).listFiles();
            assertNotNull(where.toString(), files);
            for (File f : files) {
                if (f.getName().endsWith(".class")) {
                    out.put(prefix + f.getName(), readAll(new FileInputStream(f)));
                }
            }
        } else {
            try (JarFile jar = new JarFile(where)) {
                for (Enumeration<JarEntry> en = jar.entries(); en.hasMoreElements(); ) {
                    JarEntry je = en.nextElement();
                    String n = je.getName();
                    if (n.startsWith(prefix) && n.endsWith(".class") && n.indexOf('/', prefix.length()) < 0) {
                        out.put(n, readAll(jar.getInputStream(je)));
                    }
                }
            }
        }
        return out;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (InputStream s = in) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            for (int n; (n = s.read(chunk)) > 0; ) {
                buf.write(chunk, 0, n);
            }
            return buf.toByteArray();
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
