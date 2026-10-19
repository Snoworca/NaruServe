package com.snoworca.naruserve.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.Method;
import com.snoworca.naruserve.MethodSet;
import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.RouteBuilder;
import com.snoworca.naruserve.RouteHandler;
import com.snoworca.naruserve.routing.RouteKind;
import com.snoworca.naruserve.routing.RouterPolicy;
import com.snoworca.naruserve.routing.RoutingConfigException;
import com.snoworca.naruserve.spi.ExchangeHandler;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.TestApp;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Scanner;
import java.util.jar.JarEntry;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarFile;
import org.jspecify.annotations.NullMarked;
import org.junit.Test;

public class DefaultRestAppTest {

    private final RouteHandler h = (req, res) -> { };

    private static ClientResponse send(DefaultRestApp app, String method, String target) {
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        adapter.bind("127.0.0.1", 0, app.dispatcher());
        return adapter.send(ClientRequest.of(method, target));
    }

    private static void assertRegistration(DefaultRestApp.Registration r, MethodSet methods, String pattern,
            RouteHandler handler, RouteKind kind) {
        assertEquals(methods, r.methods);
        assertEquals(pattern, r.pattern);
        assertSame(handler, r.handler);
        assertEquals(kind, r.kind);
    }

    // SDS-AC-11; IR-HTTP-006 AC-4
    @Test
    public void sdsAc11_IR_HTTP_006_AC4_appGetAndBuilderGetRecordTheSameRegistration() {
        DefaultRestApp app = new DefaultRestApp();
        app.get("/x", h);
        app.route("/x").get(h);
        List<DefaultRestApp.Registration> regs = app.registrations();
        assertEquals(2, regs.size());
        assertRegistration(regs.get(0), MethodSet.of(Method.GET), "/x", h, RouteKind.HTTP);
        assertRegistration(regs.get(1), MethodSet.of(Method.GET), "/x", h, RouteKind.HTTP);
    }

    // SDS-AC-11; IR-HTTP-006 AC-3
    @Test
    public void sdsAc11_IR_HTTP_006_AC3_appGetPlusBuilderGetIsADuplicateAtFreeze() {
        DefaultRestApp app = new DefaultRestApp();
        app.get("/x", h);
        app.route("/x").get(h);
        try {
            app.freeze();
            fail("duplicate GET /x accepted");
        } catch (RoutingConfigException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("GET /x conflicts with GET /x"));
        }
    }

    // SDS-AC-11; FR-HTTP-007 AC-1
    @Test
    public void sdsAc11_FR_HTTP_007_AC1_eachRegistrationMethodRecordsItsMethods() {
        DefaultRestApp app = new DefaultRestApp();
        RestApp same = app.get("/g", h).post("/po", h).put("/pu", h).patch("/pa", h).delete("/d", h)
                .head("/h", h).options("/o", h).route(Method.PATCH, "/p", h)
                .route(Method.get().post().delete(), "/users/:id", h);
        assertSame(app, same);
        RouteBuilder b = app.route("/u/:id");
        assertSame(b, b.get(h).post(h).put(h).patch(h).delete(h).head(h).options(h));
        List<DefaultRestApp.Registration> regs = app.registrations();
        Method[] expected = {Method.GET, Method.POST, Method.PUT, Method.PATCH, Method.DELETE, Method.HEAD,
            Method.OPTIONS, Method.PATCH};
        for (int i = 0; i < expected.length; i++) {
            assertEquals(MethodSet.of(expected[i]), regs.get(i).methods);
        }
        assertRegistration(regs.get(8), Method.get().post().delete(), "/users/:id", h, RouteKind.HTTP);
        for (int i = 0; i < 7; i++) {
            assertRegistration(regs.get(9 + i), MethodSet.of(expected[i]), "/u/:id", h, RouteKind.HTTP);
        }
        assertEquals(16, regs.size());
    }

    // SDS-AC-11; FR-HTTP-007 AC-1
    @Test
    public void sdsAc11_FR_HTTP_007_AC1_unregisteredMethodOfAMethodSetRouteIs405() {
        DefaultRestApp app = new DefaultRestApp();
        app.route(Method.get().post().delete(), "/users/:id", h);
        app.freeze();
        ClientResponse r = send(app, "PUT", "/users/1");
        assertEquals(405, r.status());
        assertEquals("GET, HEAD, POST, DELETE, OPTIONS", r.header("Allow"));
    }

    // SDS-AC-12; FR-HTTP-008 AC-2
    @Test
    public void sdsAc12_FR_HTTP_008_AC2_badPatternThrowsAtTheRegistrationCall() {
        DefaultRestApp app = new DefaultRestApp();
        for (String bad : Arrays.asList("x", "/a/:", "/a*", "/a//b")) {
            try {
                app.get(bad, h);
                fail("accepted " + bad);
            } catch (RoutingConfigException e) {
                assertTrue(e.getMessage(), e.getMessage().contains(bad));
            }
        }
        try {
            app.route("/b/:").put(h);
            fail("builder accepted /b/:");
        } catch (RoutingConfigException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("/b/:"));
        }
        assertEquals(0, app.registrations().size());
    }

    // SDS-AC-12; FR-HTTP-008 AC-4
    @Test
    public void sdsAc12_FR_HTTP_008_AC4_duplicateThrowsOnlyAtFreezeAndLeavesTheAppUnfrozen() {
        DefaultRestApp app = new DefaultRestApp();
        app.get("/x", h);
        app.get("/x", h);
        assertEquals(2, app.registrations().size());
        try {
            app.freeze();
            fail("duplicate accepted");
        } catch (RoutingConfigException e) {
            assertEquals(RoutingConfigException.class, e.getClass());
            assertEquals(null, e.getCause());
        }
        try {
            app.freeze();
            fail("second freeze of the same duplicate accepted");
        } catch (RoutingConfigException expected) {
            // still unfrozen, so freeze runs again
        }
        try {
            app.dispatcher();
            fail("dispatcher after failed freeze");
        } catch (IllegalStateException expected) {
            // still unfrozen
        }
        app.post("/y", h);
        assertEquals(3, app.registrations().size());
    }

    // SDS-AC-12; FR-HTTP-008 AC-4
    @Test
    public void sdsAc12_FR_HTTP_008_AC4_dispatcherBeforeFreezeThrowsAndFreezeRunsOnce() {
        DefaultRestApp app = new DefaultRestApp();
        try {
            app.dispatcher();
            fail("dispatcher before freeze");
        } catch (IllegalStateException expected) {
            // not frozen
        }
        app.get("/x", h);
        app.freeze();
        ExchangeHandler first = app.dispatcher();
        assertTrue(first instanceof Dispatcher);
        app.freeze();
        assertSame(first, app.dispatcher());
    }

    // SDS-AC-13; FR-HTTP-008 AC-3
    @Test
    public void sdsAc13_FR_HTTP_008_AC3_policySetAfterGetAppliesToIt() {
        AtomicInteger hits = new AtomicInteger();
        RouteHandler hit = (req, res) -> {
            hits.incrementAndGet();
            res.send("hit");
        };
        TestApp strict = TestApp.create();
        strict.app().get("/a/b", hit);
        assertEquals(404, strict.send("GET", "/a//b").status());
        assertEquals(0, hits.get());

        TestApp merged = TestApp.create();
        RestApp app = merged.app();
        app.get("/a/b", hit);
        assertSame(app, app.routerPolicy(RouterPolicy.builder().mergeSlashes(true).build()));
        ClientResponse r = merged.send("GET", "/a//b"); // the harness freezes here, after the policy was set
        assertEquals(200, r.status());
        assertEquals("hit", new String(r.body(), StandardCharsets.UTF_8));
        assertEquals(1, hits.get());
        ClientResponse mismatch = merged.send("DELETE", "/a//b");
        assertEquals(405, mismatch.status());
        assertEquals("GET, HEAD, OPTIONS", mismatch.header("Allow"));
    }

    // SDS-AC-13; FR-HTTP-008 AC-5
    @Test
    public void sdsAc13_FR_HTTP_008_AC5_everyCallAfterFreezeThrowsAndRecordsNothing() {
        DefaultRestApp app = new DefaultRestApp();
        RouteBuilder early = app.route("/b");
        app.get("/x", h);
        app.freeze();
        List<Runnable> calls = Arrays.<Runnable>asList(
            () -> app.get("/a", h), () -> app.post("/a", h), () -> app.put("/a", h), () -> app.patch("/a", h),
            () -> app.delete("/a", h), () -> app.head("/a", h), () -> app.options("/a", h),
            () -> app.route(Method.GET, "/a", h), () -> app.route(Method.any(), "/a", h),
            () -> app.routerPolicy(RouterPolicy.strict()), () -> app.webSocketRoute("/ws", h),
            () -> early.get(h), () -> early.post(h), () -> early.put(h), () -> early.patch(h),
            () -> early.delete(h), () -> early.head(h), () -> early.options(h),
            () -> app.route("/late").get(h));
        for (int i = 0; i < calls.size(); i++) {
            try {
                calls.get(i).run();
                fail("call " + i + " accepted after freeze");
            } catch (IllegalStateException expected) {
                // frozen
            }
        }
        assertEquals(1, app.registrations().size());
    }

    // SDS-AC-13; FR-HTTP-008 AC-7
    @Test
    public void sdsAc13_FR_HTTP_008_AC7_webSocketHookRecordsAWebSocketRegistration() {
        DefaultRestApp app = new DefaultRestApp();
        assertSame(app, app.webSocketRoute("/ws", h));
        assertRegistration(app.registrations().get(0), MethodSet.of(Method.GET), "/ws", h, RouteKind.WEBSOCKET);
        app.freeze();
        ClientResponse r = send(app, "GET", "/ws");
        assertEquals(426, r.status());
        assertEquals("websocket", r.header("Upgrade"));
    }

    // SDS-AC-14; FR-HTTP-008 AC-1
    @Test
    public void sdsAc14_FR_HTTP_008_AC1_implementationIsAPublicInternalClassWithAPublicCtor() throws Exception {
        assertEquals("com.snoworca.naruserve.internal", DefaultRestApp.class.getPackage().getName());
        assertTrue(Modifier.isPublic(DefaultRestApp.class.getModifiers()));
        assertTrue(RestApp.class.isAssignableFrom(DefaultRestApp.class));
        Constructor<DefaultRestApp> ctor = DefaultRestApp.class.getConstructor();
        assertTrue(Modifier.isPublic(ctor.getModifiers()));
        assertTrue(DefaultRestApp.class.getPackage().isAnnotationPresent(NullMarked.class));
        String rel = "src/main/java/com/snoworca/naruserve/internal/package-info.java";
        File info = new File(System.getProperty("user.dir"), rel);
        if (!info.isFile()) {
            info = new File(System.getProperty("user.dir"), "NaruServe-api/" + rel);
        }
        String text = read(info);
        assertTrue(text, text.contains("not part of the API contract"));
    }

    // SDS-AC-14; FR-HTTP-008 AC-6; FR-APP-001 AC-1
    @Test
    public void sdsAc14_FR_HTTP_008_AC6_noPublicFactoryInTheApiPackageReturnsRestApp() throws Exception {
        List<String> offenders = new ArrayList<>();
        int checked = 0;
        for (String name : apiPackageClassNames()) {
            Class<?> c = Class.forName("com.snoworca.naruserve." + name);
            checked++;
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                if (Modifier.isPublic(m.getModifiers()) && Modifier.isStatic(m.getModifiers())
                        && (RestApp.class.isAssignableFrom(m.getReturnType())
                                || RestApp.Builder.class.isAssignableFrom(m.getReturnType()))) {
                    offenders.add(c.getName() + "." + m.getName());
                }
            }
            for (Constructor<?> k : c.getDeclaredConstructors()) {
                if (Modifier.isPublic(k.getModifiers()) && RestApp.class.isAssignableFrom(c)) {
                    offenders.add(c.getName() + "()");
                }
            }
        }
        assertTrue("checked " + checked, checked >= 7);
        // AC-6 is a wave 3 statement; wave 4 re-points it: builder() and create() are the only public static
        // factories of a RestApp or its builder (FR-APP-002 AC-5, FR-APP-001 AC-1).
        java.util.Collections.sort(offenders);
        assertEquals(java.util.Arrays.asList("com.snoworca.naruserve.RestApp.builder",
                "com.snoworca.naruserve.RestApp.create"), offenders);
    }

    /** Simple names of the top-level and nested classes compiled into package com.snoworca.naruserve. */
    private static List<String> apiPackageClassNames() throws Exception {
        File location = new File(RestApp.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<String> entries = new ArrayList<>();
        if (location.isDirectory()) {
            String[] names = new File(location, "com/snoworca/naruserve").list();
            if (names != null) {
                for (String n : names) {
                    entries.add("com/snoworca/naruserve/" + n);
                }
            }
        } else {
            try (JarFile jar = new JarFile(location)) {
                for (Enumeration<JarEntry> e = jar.entries(); e.hasMoreElements(); ) {
                    entries.add(e.nextElement().getName());
                }
            }
        }
        List<String> out = new ArrayList<>();
        String prefix = "com/snoworca/naruserve/";
        for (String entry : entries) {
            String rest = entry.substring(Math.min(entry.length(), prefix.length()));
            if (entry.startsWith(prefix) && rest.endsWith(".class") && rest.indexOf('/') < 0) {
                out.add(rest.substring(0, rest.length() - 6));
            }
        }
        return out;
    }

    private static String read(File f) throws IOException {
        try (Scanner s = new Scanner(f, StandardCharsets.UTF_8.name())) {
            return s.useDelimiter("\\A").hasNext() ? s.next() : "";
        }
    }

    // SDS-AC-5 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-4-3.sds.md; FR-APP-002 AC-5
    @Test
    public void sdsAc5_FR_APP_002_AC5_freezeInNewRejectsThroughTheOneGuardNamingTheMethod() {
        DefaultRestApp app = new DefaultRestApp();
        app.get("/x", h);
        app.freeze();
        assertEquals(com.snoworca.naruserve.AppState.NEW, app.state());
        List<Runnable> calls = Arrays.<Runnable>asList(() -> app.get("/a", h),
                () -> app.use((req, res, next) -> next.run()), () -> app.onStart(ctx -> { }),
                () -> app.logger(new com.snoworca.naruserve.ConsoleLogger()), () -> app.routerPolicy(RouterPolicy.strict()));
        String[] names = {"get", "use", "onStart", "logger", "routerPolicy"};
        for (int i = 0; i < calls.size(); i++) {
            try {
                calls.get(i).run();
                fail(names[i] + " accepted after freeze");
            } catch (IllegalStateException e) {
                assertTrue(e.getMessage(), e.getMessage().contains(names[i]));
            }
        }
        assertEquals(1, app.registrations().size());
    }
}
