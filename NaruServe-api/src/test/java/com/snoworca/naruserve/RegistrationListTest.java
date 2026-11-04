package com.snoworca.naruserve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.TestApp;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.junit.Test;

/** C4: app.registrations(), app.onRegistration(RegistrationListener) and the validate, watcher, apply, record order. */
public class RegistrationListTest {

    private static final RouteHandler H = (req, res) -> res.send("h");
    private static final Middleware MW = (req, res, next) -> next.run();
    private static final AsyncRouteHandler ASYNC = (req, res, next) -> {
        next.run();
        return Deferred.resolved();
    };
    private static final ErrorMiddleware EMW = (error, req, res, next) -> next.error(error);
    private static final ErrorHandler EH = (error, req, res) -> { };
    private static final ParamHandler PH = (req, res, next, value, name) -> next.run();

    /** A fresh app with a recording watcher. */
    private static final class Fixture {
        final InMemoryHttpAdapter adapter;
        final TestApp t;
        final RestApp app;
        final List<Registration> watched = Collections.synchronizedList(new ArrayList<Registration>());

        Fixture() {
            this(new InMemoryHttpAdapter());
        }

        Fixture(InMemoryHttpAdapter adapter) {
            this.adapter = adapter;
            this.t = TestApp.create(adapter);
            this.app = t.app();
            app.onRegistration(watched::add);
        }

        int status(String path) {
            t.start();
            return adapter.send(ClientRequest.of("GET", path)).status();
        }
    }

    private interface Registrar {
        void register(RestApp app) throws Exception;
    }

    private static List<Registration> recorded(Registrar r) throws Exception {
        RestApp app = TestApp.create().app();
        r.register(app);
        return app.registrations();
    }

    private static Registration only(Registrar r) throws Exception {
        List<Registration> list = recorded(r);
        assertEquals(list.toString(), 1, list.size());
        return list.get(0);
    }

    private static List<RegistrationKind> kinds(List<Registration> list) {
        List<RegistrationKind> kinds = new ArrayList<>();
        for (Registration r : list) {
            kinds.add(r.getKind());
        }
        return kinds;
    }

    // IR-APP-008 AC-1: an unmodifiable list in registration order, readable in NEW and after listen
    @Test
    public void IR_APP_008_AC1_registrationsIsAReadOnlyListInRegistrationOrder() throws Exception {
        assertEquals(List.class, RestApp.class.getMethod("registrations").getReturnType());
        TestApp t = TestApp.create();
        assertEquals(Collections.emptyList(), t.app().registrations());
        t.app().get("/a", H).post("/b", H);
        List<Registration> list = t.app().registrations();
        assertEquals("/a", list.get(0).getPath());
        assertEquals("/b", list.get(1).getPath());
        try {
            list.add(list.get(0));
            fail("registrations() is modifiable");
        } catch (UnsupportedOperationException expected) {
            // read-only
        }
        t.start();
        assertEquals(2, t.app().registrations().size());
        t.app().shutdown(1, TimeUnit.SECONDS);
        assertEquals(2, t.app().registrations().size());
    }

    // IR-APP-008 AC-1: iterating never throws ConcurrentModificationException while another thread registers
    @Test(timeout = 60000)
    public void IR_APP_008_AC1_iterationIsSafeWhileAnotherThreadAddsRoutes() throws Exception {
        TestApp t = TestApp.create();
        t.app().get("/first", H);
        t.start();
        CountDownLatch go = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch readerStarted = new CountDownLatch(1);
        Thread writer = new Thread(() -> {
            try {
                go.await();
                readerStarted.await(10, TimeUnit.SECONDS);
                for (int i = 0; i < 1000; i++) {
                    t.app().get("/r" + i, H);
                }
            } catch (Throwable e) {
                failure.set(e);
            }
        });
        writer.start();
        go.countDown();
        int rounds = 0;
        try {
            while (writer.isAlive() || rounds == 0) {
                int n = 0;
                for (Registration r : t.app().registrations()) {
                    assertNotNull(r.getKind());
                    n++;
                }
                assertTrue(n >= 1);
                rounds++;
                readerStarted.countDown();
            }
        } catch (ConcurrentModificationException e) {
            throw new AssertionError("iteration failed", e);
        }
        writer.join();
        assertNull(failure.get());
        assertEquals(1001, t.app().registrations().size());
        t.app().shutdown(1, TimeUnit.SECONDS);
    }

    // IR-APP-008 AC-2: onRegistration returns the app, refuses null and is refused outside NEW
    @Test
    public void IR_APP_008_AC2_onRegistrationIsAGuardedConfigurationMethod() throws Exception {
        assertEquals(RestApp.class,
                RestApp.class.getMethod("onRegistration", RegistrationListener.class).getReturnType());
        TestApp t = TestApp.create();
        try {
            t.app().onRegistration(null);
            fail("null listener accepted");
        } catch (NullPointerException expected) {
            // NPE
        }
        RegistrationListener l = r -> { };
        assertSame(t.app(), t.app().onRegistration(l));
        t.start();
        List<Registration> late = new ArrayList<>();
        try {
            t.app().onRegistration(late::add);
            fail("onRegistration accepted in RUNNING");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("onRegistration") && e.getMessage().contains("RUNNING"));
        }
        assertEquals(Collections.emptyList(), t.app().registrations());
        t.app().get("/after", H);
        assertEquals("the refused listener was not installed", Collections.emptyList(), late);
        t.app().shutdown(1, TimeUnit.SECONDS);
    }

    // IR-APP-008 AC-3: RegistrationListener is a NaruServe @FunctionalInterface with one abstract method
    @Test
    public void IR_APP_008_AC3_registrationListenerIsAFunctionalInterface() {
        assertTrue(RegistrationListener.class.isInterface());
        assertTrue(RegistrationListener.class.isAnnotationPresent(FunctionalInterface.class));
        assertEquals("com.snoworca.naruserve", RegistrationListener.class.getPackage().getName());
        int abstractMethods = 0;
        for (java.lang.reflect.Method m : RegistrationListener.class.getMethods()) {
            if (java.lang.reflect.Modifier.isAbstract(m.getModifiers())) {
                abstractMethods++;
                assertEquals(1, m.getParameterTypes().length);
                assertEquals(Registration.class, m.getParameterTypes()[0]);
            }
        }
        assertEquals(1, abstractMethods);
        assertEquals(0, RegistrationListener.class.getInterfaces().length);
    }

    // IR-APP-008 AC-4: the record accessors and the exact kind constants; nullable positions only where null occurs
    @Test
    public void IR_APP_008_AC4_recordAccessorsAndKindConstants() throws Exception {
        List<String> constants = new ArrayList<>();
        for (RegistrationKind k : RegistrationKind.values()) {
            constants.add(k.name());
        }
        assertEquals(Arrays.asList("ROUTE", "WEBSOCKET", "STATIC", "ROUTER_MOUNT", "GROUP", "CORS", "MIDDLEWARE",
                "PATH_MIDDLEWARE", "ERROR_MIDDLEWARE", "PARAM"), constants);
        Map<String, Boolean> nullable = new LinkedHashMap<>();
        for (String name : new String[] {"getKind", "getMethod", "getPath", "getPathSource", "getTarget", "getMount",
                "isStringPath"}) {
            java.lang.reflect.Method m = Registration.class.getMethod(name);
            nullable.put(name, m.getAnnotatedReturnType().isAnnotationPresent(org.jspecify.annotations.Nullable.class));
        }
        Map<String, Boolean> expected = new LinkedHashMap<>();
        expected.put("getKind", false);
        expected.put("getMethod", true);
        expected.put("getPath", true);
        expected.put("getPathSource", false);
        expected.put("getTarget", false);
        expected.put("getMount", true);
        expected.put("isStringPath", false);
        assertEquals(expected, nullable);
        assertEquals(RegistrationKind.class, Registration.class.getMethod("getKind").getReturnType());
        assertEquals(Registration.class, Registration.class.getMethod("getMount").getReturnType());
        assertEquals(boolean.class, Registration.class.getMethod("isStringPath").getReturnType());
        Registration r = only(app -> app.get("/x", H));
        assertEquals(RegistrationKind.ROUTE, r.getKind());
        assertEquals("GET", r.getMethod());
        assertEquals("/x", r.getPath());
        assertEquals("/x", r.getPathSource());
        assertTrue(r.isStringPath());
        assertSame(H, r.getTarget());
        assertNull(r.getMount());
    }

    // FR-APP-025 AC-1: every route-family API yields one ROUTE record with the registered path
    @Test
    public void FR_APP_025_AC1_everyRouteApiYieldsOneRouteRecord() throws Exception {
        Map<String, Registrar> calls = new LinkedHashMap<>();
        calls.put("GET", a -> a.get("/p", H));
        calls.put("POST", a -> a.post("/p", H));
        calls.put("PUT", a -> a.put("/p", H));
        calls.put("PATCH", a -> a.patch("/p", H));
        calls.put("DELETE", a -> a.delete("/p", H));
        calls.put("HEAD", a -> a.head("/p", H));
        calls.put("OPTIONS", a -> a.options("/p", H));
        calls.put("GET mw", a -> a.get("/p", MW, H));
        calls.put("POST mw", a -> a.post("/p", MW, H));
        calls.put("PUT mw", a -> a.put("/p", MW, H));
        calls.put("PATCH mw", a -> a.patch("/p", MW, H));
        calls.put("DELETE mw", a -> a.delete("/p", MW, H));
        calls.put("HEAD mw", a -> a.head("/p", MW, H));
        calls.put("OPTIONS mw", a -> a.options("/p", MW, H));
        calls.put("GET object", a -> a.get((Object) "/p", H));
        calls.put("POST object", a -> a.post((Object) "/p", H));
        calls.put("GET middleware", a -> a.get("/p", MW));
        calls.put("PUT middleware", a -> a.put("/p", MW));
        calls.put("GET varargs", a -> a.get("/p", MW, MW));
        calls.put("DELETE varargs", a -> a.delete("/p", MW, MW));
        calls.put("route Method", a -> a.route(Method.GET, "/p", H));
        calls.put("route MethodSet", a -> a.route(MethodSet.of(Method.POST), "/p", H));
        calls.put("route Method mw", a -> a.route(Method.PUT, "/p", MW, H));
        calls.put("route MethodSet mw", a -> a.route(MethodSet.of(Method.PATCH), "/p", MW, H));
        calls.put("method", a -> a.method("PURGE", "/p", MW));
        calls.put("method varargs", a -> a.method("GET", "/p", MW, MW));
        calls.put("methodAsync", a -> a.methodAsync("GET", "/p", ASYNC));
        calls.put("getAsync", a -> a.getAsync("/p", ASYNC));
        String[] verbs = {"GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"};
        for (String verb : verbs) {
            calls.put(verb + " fixed middleware", a -> verbCall(a, verb, 0));
            calls.put(verb + " varargs middleware", a -> verbCall(a, verb, 1));
            calls.put(verb + " object handler", a -> verbCall(a, verb, 2));
            calls.put(verb + " leading middleware", a -> verbCall(a, verb, 3));
        }
        for (Map.Entry<String, Registrar> e : calls.entrySet()) {
            Registration r = only(e.getValue());
            assertEquals(e.getKey(), RegistrationKind.ROUTE, r.getKind());
            assertEquals(e.getKey(), "/p", r.getPath());
            assertNotNull(e.getKey(), r.getMethod());
        }
        assertEquals("PURGE", only(calls.get("method")).getMethod());
        assertEquals("DELETE", only(calls.get("DELETE mw")).getMethod());
        assertEquals("GET,POST", only(a -> a.route(MethodSet.of(Method.GET, Method.POST), "/p", H)).getMethod());
        // each method of a route(path) builder is one ROUTE record
        List<Registration> builder = recorded(a -> a.route("/b").get(H).post(H).put(H).patch(H).delete(H).head(H)
                .options(H).method("PURGE", MW));
        assertEquals(8, builder.size());
        List<String> methods = new ArrayList<>();
        for (Registration r : builder) {
            assertEquals(RegistrationKind.ROUTE, r.getKind());
            assertEquals("/b", r.getPath());
            methods.add(r.getMethod());
        }
        assertEquals(Arrays.asList("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "PURGE"), methods);
        assertEquals(Arrays.asList(RegistrationKind.ROUTE), kinds(recorded(a -> a.route((Object) "/o").get(H))));
        // the route-level use(mw) of a builder is a builder method too: one ROUTE record with no method
        List<Registration> withUse = recorded(a -> a.route("/u").use(MW).get(H));
        assertEquals(Arrays.asList(RegistrationKind.ROUTE, RegistrationKind.ROUTE), kinds(withUse));
        assertNull(withUse.get(0).getMethod());
        assertSame(MW, withUse.get(0).getTarget());
        assertEquals("/u", withUse.get(0).getPath());
        assertEquals("GET", withUse.get(1).getMethod());
        assertEquals(Collections.emptyList(), recorded(a -> a.route("/none")));
        assertEquals(RegistrationKind.WEBSOCKET, only(a -> a.ws("/ws", (req, ws) -> { })).getKind());
        assertEquals("/ws", only(a -> a.ws("/ws", (req, ws) -> { })).getPath());
        Router router = Router.create();
        Registration mount = only(a -> a.use("/api", router));
        assertEquals(RegistrationKind.ROUTER_MOUNT, mount.getKind());
        assertEquals("/api", mount.getPath());
        assertEquals(RegistrationKind.ROUTER_MOUNT, only(a -> a.use(Router.create())).getKind());
        Registration group = only(a -> a.group("/g", g -> { }));
        assertEquals(RegistrationKind.GROUP, group.getKind());
        assertEquals("/g", group.getPath());
        assertEquals(RegistrationKind.PARAM, only(a -> a.param("id", PH)).getKind());
        assertNull(only(a -> a.param("id", PH)).getMethod());
        assertNull(only(a -> a.use(MW)).getMethod());
    }

    /** One verb through overload family {@code family}: 0 fixed Middleware, 1 Middleware..., 2 (Object) RouteHandler, 3 String, Middleware, RouteHandler. */
    private static void verbCall(RestApp a, String verb, int family) {
        Object p = "/p";
        switch (verb) {
            case "GET": if (family == 0) a.get(p, MW); else if (family == 1) a.get(p, MW, MW); else if (family == 2) a.get(p, H); else a.get("/p", MW, H); break;
            case "POST": if (family == 0) a.post(p, MW); else if (family == 1) a.post(p, MW, MW); else if (family == 2) a.post(p, H); else a.post("/p", MW, H); break;
            case "PUT": if (family == 0) a.put(p, MW); else if (family == 1) a.put(p, MW, MW); else if (family == 2) a.put(p, H); else a.put("/p", MW, H); break;
            case "PATCH": if (family == 0) a.patch(p, MW); else if (family == 1) a.patch(p, MW, MW); else if (family == 2) a.patch(p, H); else a.patch("/p", MW, H); break;
            case "DELETE": if (family == 0) a.delete(p, MW); else if (family == 1) a.delete(p, MW, MW); else if (family == 2) a.delete(p, H); else a.delete("/p", MW, H); break;
            case "HEAD": if (family == 0) a.head(p, MW); else if (family == 1) a.head(p, MW, MW); else if (family == 2) a.head(p, H); else a.head("/p", MW, H); break;
            default: if (family == 0) a.options(p, MW); else if (family == 1) a.options(p, MW, MW); else if (family == 2) a.options(p, H); else a.options("/p", MW, H); break;
        }
    }

    // FR-APP-025 AC-2: staticFiles and cors are one STATIC and one CORS record, never MIDDLEWARE
    @Test
    public void FR_APP_025_AC2_staticFilesAndCorsAreOneRecordEach() throws Exception {
        File dir = new File("public");
        assertEquals(Arrays.asList(RegistrationKind.STATIC), kinds(recorded(a -> a.staticFiles("/s", dir))));
        assertEquals(Arrays.asList(RegistrationKind.STATIC),
                kinds(recorded(a -> a.staticFiles("/s", dir, o -> { }))));
        assertEquals(Arrays.asList(RegistrationKind.CORS), kinds(recorded(a -> a.cors(o -> o.allowedOrigins("https://a.example")))));
        assertEquals("/s", only(a -> a.staticFiles("/s", dir)).getPath());
    }

    // FR-APP-025 AC-3: path-less middleware is MIDDLEWARE, any other path is PATH_MIDDLEWARE
    @Test
    public void FR_APP_025_AC3_middlewareKindsFollowThePath() throws Exception {
        List<Registrar> pathless = Arrays.<Registrar>asList(a -> a.use(MW), a -> a.use("", MW), a -> a.use("/", MW),
                a -> a.use((Object) "", MW), a -> a.useAsync(ASYNC), a -> a.useAsync("/", ASYNC));
        for (Registrar r : pathless) {
            assertEquals(RegistrationKind.MIDDLEWARE, only(r).getKind());
        }
        List<Registrar> pathed = Arrays.<Registrar>asList(a -> a.use("/api", MW), a -> a.use(Pattern.compile("^/a"), MW),
                a -> a.use(new Object[] {"/a", "/b"}, MW), a -> a.useAsync("/api", ASYNC));
        for (Registrar r : pathed) {
            assertEquals(RegistrationKind.PATH_MIDDLEWARE, only(r).getKind());
        }
        assertEquals("/api", only(a -> a.use("/api", MW)).getPath());
    }

    // FR-APP-025 AC-4: use(ErrorMiddleware), use(Object, ErrorMiddleware) and onError are ERROR_MIDDLEWARE
    @Test
    public void FR_APP_025_AC4_errorLayersAreErrorMiddleware() throws Exception {
        for (Registrar r : Arrays.<Registrar>asList(a -> a.use(EMW), a -> a.use("/api", EMW), a -> a.onError(EH))) {
            assertEquals(RegistrationKind.ERROR_MIDDLEWARE, only(r).getKind());
        }
    }

    // FR-APP-025 AC-5: one shared call-order list with nothing else
    @Test
    public void FR_APP_025_AC5_kindsFollowCallOrder() throws Exception {
        File dir = new File("public");
        List<Registration> list = recorded(a -> a.cors(o -> o.allowedOrigins("https://a.example")).use(MW).get("/a", H)
                .staticFiles("/", dir).use("/api", (Middleware) (req, res, next) -> next.run()).onError(EH));
        assertEquals(Arrays.asList(RegistrationKind.CORS, RegistrationKind.MIDDLEWARE, RegistrationKind.ROUTE,
                RegistrationKind.STATIC, RegistrationKind.PATH_MIDDLEWARE, RegistrationKind.ERROR_MIDDLEWARE), kinds(list));
    }

    // FR-APP-025 AC-6: the target is the object the caller registered, never a Core-created wrapper
    @Test
    public void FR_APP_025_AC6_targetIsTheRegisteredObject() throws Exception {
        assertSame(MW, only(a -> a.use(MW)).getTarget());
        assertSame(MW, only(a -> a.use("/api", MW)).getTarget());
        Router router = Router.create();
        assertSame(router, only(a -> a.use("/api", router)).getTarget());
        assertSame(EMW, only(a -> a.use(EMW)).getTarget());
        assertSame(ASYNC, only(a -> a.useAsync(ASYNC)).getTarget());
        assertSame(ASYNC, only(a -> a.useAsync("/api", ASYNC)).getTarget());
        WebSocketHandler ws = (req, session) -> { };
        assertSame(ws, only(a -> a.ws("/ws", ws)).getTarget());
        assertSame(EH, only(a -> a.onError(EH)).getTarget());
        assertSame(PH, only(a -> a.param("id", PH)).getTarget());
        // decisions documented in docs/api/app.md: a route's target is its last callback (the handler)
        assertSame(H, only(a -> a.get("/p", H)).getTarget());
        assertSame(H, only(a -> a.get("/p", MW, H)).getTarget());
        assertSame(H, only(a -> a.get((Object) "/p", H)).getTarget());
        assertSame(H, only(a -> a.route("/p").get(H)).getTarget());
        assertSame(ASYNC, only(a -> a.getAsync("/p", ASYNC)).getTarget());
        Middleware last = (req, res, next) -> res.send("last");
        assertSame(last, only(a -> a.get("/p", MW, last)).getTarget());
        Configurer<CorsOptions> cors = o -> o.allowedOrigins("https://a.example");
        assertSame(cors, only(a -> a.cors(cors)).getTarget());
        File dir = new File("public");
        assertSame(dir, only(a -> a.staticFiles("/s", dir)).getTarget());
        Configurer<RouteGroup> group = g -> { };
        assertSame(group, only(a -> a.group("/g", group)).getTarget());
    }

    // FR-APP-025 AC-7: the same ErrorMiddleware twice is two records with that target
    @Test
    public void FR_APP_025_AC7_sameErrorMiddlewareTwiceIsTwoRecords() throws Exception {
        List<Registration> list = recorded(a -> a.get("/a", H).use(EMW).use(EMW));
        assertEquals(3, list.size());
        assertSame(EMW, list.get(1).getTarget());
        assertSame(EMW, list.get(2).getTarget());
    }

    // FR-APP-026 AC-1: a mounted router's routes follow its ROUTER_MOUNT record with the prefixed path
    @Test
    public void FR_APP_026_AC1_mountedRouterRoutesAreExpanded() throws Exception {
        Fixture f = new Fixture();
        Router r = Router.create();
        r.get("/users", H);
        f.app.use("/api", r);
        List<Registration> list = f.app.registrations();
        assertEquals(Arrays.asList(RegistrationKind.ROUTER_MOUNT, RegistrationKind.ROUTE), kinds(list));
        assertEquals("/api", list.get(0).getPath());
        assertEquals("/api/users", list.get(1).getPath());
        assertSame(list.get(0), list.get(1).getMount());
        assertSame(H, list.get(1).getTarget());
        assertEquals(list, f.watched);
        assertEquals(200, f.status("/api/users"));
    }

    // FR-APP-026 AC-2: GROUP first, recorded after the configurer returns; each inner route is one ROUTE record
    @Test
    public void FR_APP_026_AC2_groupRecordPrecedesItsRoutes() throws Exception {
        Fixture f = new Fixture();
        f.app.group("/v1", g -> g.get("/x", H));
        List<Registration> list = f.app.registrations();
        assertEquals(Arrays.asList(RegistrationKind.GROUP, RegistrationKind.ROUTE), kinds(list));
        assertEquals("/v1", list.get(0).getPath());
        assertEquals("/v1/x", list.get(1).getPath());
        assertEquals(list, f.watched);
        Fixture failing = new Fixture();
        IllegalStateException boom = new IllegalStateException("configurer failed");
        try {
            failing.app.group("/v2", g -> {
                g.get("/y", H);
                throw boom;
            });
            fail("group swallowed the configurer exception");
        } catch (IllegalStateException e) {
            assertSame(boom, e);
        }
        assertEquals(Collections.emptyList(), failing.app.registrations());
        assertEquals(Collections.emptyList(), failing.watched);
    }

    // FR-APP-026 AC-3: nested routers are expanded with both prefixes
    @Test
    public void FR_APP_026_AC3_nestedRoutersUseBothPrefixes() throws Exception {
        Fixture f = new Fixture();
        Router outer = Router.create();
        Router inner = Router.create();
        outer.use("/b", inner);
        inner.get("/c", H);
        f.app.use("/a", outer);
        List<Registration> list = f.app.registrations();
        assertEquals(Arrays.asList(RegistrationKind.ROUTER_MOUNT, RegistrationKind.ROUTER_MOUNT, RegistrationKind.ROUTE),
                kinds(list));
        assertEquals("/a", list.get(0).getPath());
        assertEquals("/a/b", list.get(1).getPath());
        assertEquals("/a/b/c", list.get(2).getPath());
        assertSame(list.get(1), list.get(2).getMount());
        assertSame(list.get(0), list.get(1).getMount());
        assertEquals(200, f.status("/a/b/c"));
    }

    // FR-APP-026 AC-4: a route added after listen to a mounted router reaches the watcher and the end of the list
    @Test
    public void FR_APP_026_AC4_lateRouterAdditionsAreExpandedAndAppended() throws Exception {
        Fixture f = new Fixture();
        Router r = Router.create();
        f.app.use("/api", r);
        f.app.get("/other", H);
        f.t.start();
        int before = f.watched.size();
        r.get("/late", H);
        assertEquals(before + 1, f.watched.size());
        Registration late = f.watched.get(before);
        assertEquals(RegistrationKind.ROUTE, late.getKind());
        assertEquals("/api/late", late.getPath());
        List<Registration> list = f.app.registrations();
        assertSame(late, list.get(list.size() - 1));
        assertSame(list.get(0), late.getMount());
        assertEquals(200, f.status("/api/late"));
        // the same router under a second mount is expanded once per mount
        Router shared = Router.create();
        Fixture twice = new Fixture();
        twice.app.use("/x", shared).use("/y", shared);
        shared.get("/z", H);
        List<String> paths = new ArrayList<>();
        for (Registration reg : twice.app.registrations()) {
            paths.add(reg.getPath());
        }
        assertEquals(Arrays.asList("/x", "/y", "/x/z", "/y/z"), paths);
    }

    // FR-APP-026 AC-5: a Pattern or array path, at the mount or inside, is told apart through the record alone
    @Test
    public void FR_APP_026_AC5_nonStringPathsAreMarked() throws Exception {
        Fixture f = new Fixture();
        Router r = Router.create();
        r.get("/in", H);
        Pattern p = Pattern.compile("^/x");
        f.app.use(p, r);
        Router s = Router.create();
        s.get(new Object[] {"/a", "/b"}, H);
        f.app.use("/s", s);
        f.app.get("/plain", H);
        List<Registration> list = f.app.registrations();
        assertEquals(5, list.size());
        assertFalse(list.get(0).isStringPath());
        assertNull(list.get(0).getPath());
        assertSame(p, list.get(0).getPathSource());
        assertFalse(list.get(1).isStringPath());
        assertNull(list.get(1).getPath());
        assertEquals("/in", list.get(1).getPathSource());
        assertTrue(list.get(2).isStringPath());
        assertFalse(list.get(3).isStringPath());
        assertTrue(list.get(3).getPathSource() instanceof Object[]);
        assertTrue(list.get(4).isStringPath());
        assertEquals("/plain", list.get(4).getPath());
    }

    // FR-APP-026 AC-6: a watcher exception for a mounted router registration propagates and leaves nothing behind
    @Test
    public void FR_APP_026_AC6_watcherVetoThroughAMountedRouter() throws Exception {
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        TestApp t = TestApp.create(adapter);
        IllegalStateException veto = new IllegalStateException("out of scope");
        t.app().onRegistration(reg -> {
            if ("/api/late".equals(reg.getPath())) {
                throw veto;
            }
        });
        Router r = Router.create();
        t.app().use("/api", r);
        t.start();
        int before = t.app().registrations().size();
        try {
            r.get("/late", H);
            fail("veto ignored");
        } catch (IllegalStateException e) {
            assertSame(veto, e);
        }
        assertEquals(before, t.app().registrations().size());
        assertEquals(404, adapter.send(ClientRequest.of("GET", "/api/late")).status());
    }

    // FR-APP-026 AC-7: mounting a router that already holds routes is vetoed as a whole, before or after listen
    @Test
    public void FR_APP_026_AC7_mountWithContentsIsWatchedBeforeItTakesEffect() throws Exception {
        for (boolean afterListen : new boolean[] {false, true}) {
            InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
            TestApp t = TestApp.create(adapter);
            List<String> seen = new ArrayList<>();
            IllegalStateException veto = new IllegalStateException("pattern route");
            t.app().onRegistration(reg -> {
                seen.add(reg.getKind() + " " + reg.getPath());
                if (t.app().state() == AppState.RUNNING) {
                    // FR-APP-026 AC-7: the mount is not live while the watcher decides
                    seen.add("live=" + adapter.send(ClientRequest.of("GET", "/m/ok")).status());
                }
                if (!reg.isStringPath()) {
                    throw veto;
                }
            });
            Router r = Router.create();
            r.get("/ok", H);
            r.get(Pattern.compile("^/re$"), H);
            if (afterListen) {
                t.start();
            }
            try {
                t.app().use("/m", r);
                fail("veto ignored");
            } catch (IllegalStateException e) {
                assertSame(veto, e);
            }
            assertEquals(afterListen
                    ? Arrays.asList("ROUTER_MOUNT /m", "live=404", "ROUTE /m/ok", "live=404", "ROUTE null", "live=404")
                    : Arrays.asList("ROUTER_MOUNT /m", "ROUTE /m/ok", "ROUTE null"), seen);
            assertEquals(Collections.emptyList(), t.app().registrations());
            t.start();
            assertEquals(404, adapter.send(ClientRequest.of("GET", "/m/ok")).status());
            // the vetoed mount is not live: a later route on r is not reported to this app
            seen.clear();
            r.get("/later", H);
            assertEquals(Collections.emptyList(), seen);
            assertEquals(404, adapter.send(ClientRequest.of("GET", "/m/later")).status());
            t.app().shutdown(1, TimeUnit.SECONDS);
        }
    }

    // SEC-APP-002 AC-1: an invalid registration throws the 0.10.0 exception type before the watcher
    @Test
    public void SEC_APP_002_AC1_invalidRegistrationNeverReachesTheWatcher() throws Exception {
        Fixture f = new Fixture();
        try {
            f.app.get("/a", (RouteHandler) null);
            fail("null handler accepted");
        } catch (NullPointerException expected) {
            // 0.10.0 type
        }
        try {
            f.app.use((Object) Integer.valueOf(42), MW);
            fail("integer path accepted");
        } catch (IllegalArgumentException expected) {
            // 0.10.0 type
        }
        assertEquals(Collections.emptyList(), f.watched);
        assertEquals(Collections.emptyList(), f.app.registrations());
        assertEquals(404, f.status("/a"));
    }

    // SEC-APP-002 AC-2: the watcher runs on the registering thread before the registration takes effect
    @Test
    public void SEC_APP_002_AC2_watcherRunsBeforeApplyAndRecord() throws Exception {
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        TestApp t = TestApp.create(adapter);
        List<String> inside = new ArrayList<>();
        Thread caller = Thread.currentThread();
        t.app().onRegistration(reg -> {
            if ("/w".equals(reg.getPath())) {
                inside.add("thread=" + (Thread.currentThread() == caller));
                inside.add("listed=" + t.app().registrations().contains(reg));
                inside.add("status=" + adapter.send(ClientRequest.of("GET", "/w")).status());
            }
        });
        t.start();
        RouteHandler h = (req, res) -> res.send("w");
        t.app().get("/w", h);
        assertEquals(Arrays.asList("thread=true", "listed=false", "status=404"), inside);
        List<Registration> list = t.app().registrations();
        assertEquals("/w", list.get(list.size() - 1).getPath());
        assertEquals("w", adapter.send(ClientRequest.of("GET", "/w")).text());
        t.app().shutdown(1, TimeUnit.SECONDS);
    }

    // SEC-APP-002 AC-3: a watcher exception vetoes get, use, use(path, router), param and use(ErrorMiddleware); each
    // vetoed layer would change the observed answer if it had been applied
    @Test
    public void SEC_APP_002_AC3_watcherExceptionVetoesTheRegistration() throws Exception {
        Router answering = Router.create();
        answering.get("/", (req, res) -> res.send("router"));
        Map<String, Registrar> calls = new LinkedHashMap<>();
        calls.put("get", a -> a.get("/w/:id", (req, res) -> res.send("route")));
        calls.put("use", a -> a.use((Middleware) (req, res, next) -> res.send("use")));
        calls.put("use(path, router)", a -> a.use("/w/1", answering));
        calls.put("param", a -> a.param("id", (req, res, next, value, name) -> res.send("param")));
        calls.put("use(ErrorMiddleware)", a -> a.use((ErrorMiddleware) (error, req, res, next) -> res.send("error")));
        for (Map.Entry<String, Registrar> e : calls.entrySet()) {
            InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
            TestApp t = TestApp.create(adapter);
            IllegalStateException veto = new IllegalStateException("veto " + e.getKey());
            boolean[] armed = {false};
            t.app().onRegistration(reg -> {
                if (armed[0]) {
                    throw veto;
                }
            });
            // a base route the vetoed layers would act on; it fails with 500 so error middleware would be visible
            if (e.getKey().equals("param") || e.getKey().equals("use(ErrorMiddleware)")) {
                t.app().get("/w/:id", (Middleware) (req, res, next) -> {
                    throw new IllegalStateException("base");
                });
            }
            int before = t.app().registrations().size();
            armed[0] = true;
            try {
                e.getValue().register(t.app());
                fail(e.getKey() + " ignored the veto");
            } catch (IllegalStateException thrown) {
                assertSame(e.getKey(), veto, thrown);
            }
            assertEquals(e.getKey(), before, t.app().registrations().size());
            t.start();
            String answer = adapter.send(ClientRequest.of("GET", "/w/1")).text();
            for (String marker : new String[] {"route", "use", "router", "param", "error"}) {
                assertFalse(e.getKey() + " applied: " + answer, answer.equals(marker));
            }
            t.app().shutdown(1, TimeUnit.SECONDS);
        }
    }

    // SEC-APP-002 AC-4: for every kind, invalid inputs throw before the watcher; accepted records are applied
    @Test
    public void SEC_APP_002_AC4_acceptedRecordsAreAlwaysAppliedAndRecorded() throws Exception {
        Fixture f = new Fixture();
        File dir = new File("public");
        Router foreign = new Router() {
            @Override public RouteBuilder route(Object path) { throw new UnsupportedOperationException(); }
            @Override public Router method(String token, Object path, Middleware... callbacks) { return this; }
        };
        List<Registrar> invalid = Arrays.<Registrar>asList(
                a -> a.get("/a", (RouteHandler) null), a -> a.get((String) null, H), a -> a.method("BAD TOKEN", "/a", MW),
                a -> a.method("GET", "/a"), a -> a.route("/a").get((RouteHandler) null), a -> a.ws("/ws", null),
                a -> a.staticFiles("noslash", dir), a -> a.staticFiles("/s", null), a -> a.use("/r", (Router) null),
                a -> a.use("/r", foreign), a -> a.group("noslash", g -> { }), a -> a.cors(null),
                a -> a.use((Middleware) null), a -> a.use((Object) Integer.valueOf(1), MW),
                a -> a.use("/e", (ErrorMiddleware) null), a -> a.onError(null), a -> a.param(null, PH),
                a -> a.param("id", null), a -> a.useAsync("/x", null));
        for (Registrar r : invalid) {
            try {
                r.register(f.app);
                fail("invalid registration accepted");
            } catch (NullPointerException | IllegalArgumentException expected) {
                // rejected before the watcher
            }
            assertEquals(Collections.emptyList(), f.watched);
            assertEquals(Collections.emptyList(), f.app.registrations());
        }
        List<Registrar> valid = Arrays.<Registrar>asList(a -> a.get("/a", H), a -> a.ws("/ws", (req, ws) -> { }),
                a -> a.staticFiles("/s", dir), a -> a.use("/r", Router.create()), a -> a.group("/g", g -> g.get("/x", H)),
                a -> a.cors(o -> o.allowedOrigins("https://a.example")), a -> a.use(MW), a -> a.use("/p", MW), a -> a.use(EMW),
                a -> a.onError(EH), a -> a.param("id", PH), a -> a.route("/b").get(H));
        for (Registrar r : valid) {
            r.register(f.app);
        }
        assertEquals(f.watched, f.app.registrations());
        List<RegistrationKind> seen = kinds(f.watched);
        for (RegistrationKind k : RegistrationKind.values()) {
            assertTrue(k + " in " + seen, seen.contains(k));
        }
        assertEquals(200, f.status("/a"));
        assertEquals(200, f.status("/g/x"));
    }

    // SEC-APP-002 AC-5: after listen the live kinds are watched and recorded, configuration kinds stay refused
    @Test
    public void SEC_APP_002_AC5_postStartScopeIsUnchanged() throws Exception {
        Fixture f = new Fixture();
        f.t.start();
        f.app.get("/g", H).use(MW).use("/p", MW).use("/r", Router.create()).param("id", PH).use(EMW);
        assertEquals(Arrays.asList(RegistrationKind.ROUTE, RegistrationKind.MIDDLEWARE, RegistrationKind.PATH_MIDDLEWARE,
                RegistrationKind.ROUTER_MOUNT, RegistrationKind.PARAM, RegistrationKind.ERROR_MIDDLEWARE),
                kinds(f.app.registrations()));
        assertEquals(f.watched, f.app.registrations());
        File dir = new File("public");
        Map<String, Registrar> refused = new LinkedHashMap<>();
        refused.put("cors", a -> a.cors(o -> o.allowedOrigins("https://a.example")));
        refused.put("ws", a -> a.ws("/ws", (req, ws) -> { }));
        refused.put("staticFiles", a -> a.staticFiles("/s", dir));
        refused.put("group", a -> a.group("/g2", g -> g.get("/x", H)));
        refused.put("onError", a -> a.onError(EH));
        refused.put("trustProxy", a -> a.trustProxy(true));
        int size = f.app.registrations().size();
        for (Map.Entry<String, Registrar> e : refused.entrySet()) {
            try {
                e.getValue().register(f.app);
                fail(e.getKey() + " accepted after listen");
            } catch (IllegalStateException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains(e.getKey()));
            }
            assertEquals(e.getKey(), size, f.app.registrations().size());
            assertEquals(e.getKey(), size, f.watched.size());
        }
        f.app.shutdown(1, TimeUnit.SECONDS);
    }

    // SEC-APP-002 AC-5: a watcher cannot start the app under a NEW-only registration it is deciding on
    @Test
    public void SEC_APP_002_AC5_watcherCannotListenOrFreezeMidRegistration() throws Exception {
        TestApp t = TestApp.create();
        List<String> outcome = new ArrayList<>();
        t.app().onRegistration(reg -> {
            try {
                t.app().listen(0);
                outcome.add("listened");
            } catch (IllegalStateException e) {
                outcome.add("refused");
            }
        });
        t.app().cors(o -> o.allowedOrigins("https://a.example"));
        assertEquals(Collections.singletonList("refused"), outcome);
        assertEquals(AppState.NEW, t.app().state());
        assertEquals(Arrays.asList(RegistrationKind.CORS), kinds(t.app().registrations()));
    }

    // FR-APP-026 AC-4: a terminated app no longer watches or records the registrations of a Router it mounted
    @Test
    public void FR_APP_026_AC4_terminatedAppLeavesTheRouterAlone() throws Exception {
        Router shared = Router.create();
        TestApp old = TestApp.create();
        old.app().onRegistration(reg -> {
            if (old.app().state() != AppState.NEW) {
                throw new IllegalStateException("old app watcher");
            }
        });
        old.app().use("/x", shared);
        old.start();
        old.app().shutdown(1, TimeUnit.SECONDS);
        Fixture live = new Fixture();
        live.app.use("/y", shared);
        shared.get("/z", H);
        assertEquals("/y/z", live.app.registrations().get(1).getPath());
        assertEquals(1, old.app().registrations().size());
    }

    // SEC-APP-002: decision — registering from inside a watcher is refused, and the first throwing watcher stops
    @Test
    public void SEC_APP_002_reentrantRegistrationAndSeveralWatchers() throws Exception {
        TestApp t = TestApp.create();
        List<String> calls = new ArrayList<>();
        t.app().onRegistration(reg -> {
            calls.add("first " + reg.getPath());
            if ("/a".equals(reg.getPath())) {
                t.app().get("/nested", H);
            }
        });
        t.app().onRegistration(reg -> calls.add("second " + reg.getPath()));
        try {
            t.app().get("/a", H);
            fail("re-entrant registration accepted");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("watcher"));
        }
        assertEquals(Collections.singletonList("first /a"), calls);
        assertEquals(Collections.emptyList(), t.app().registrations());
        calls.clear();
        t.app().get("/b", H);
        assertEquals(Arrays.asList("first /b", "second /b"), calls);
    }
}
