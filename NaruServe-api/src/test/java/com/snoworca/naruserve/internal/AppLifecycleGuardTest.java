package com.snoworca.naruserve.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.AccessLogOptions;
import com.snoworca.naruserve.AsyncRouteHandler;
import com.snoworca.naruserve.Deferred;
import com.snoworca.naruserve.ErrorMiddleware;
import com.snoworca.naruserve.ParamHandler;
import com.snoworca.naruserve.Router;
import com.snoworca.naruserve.AppState;
import com.snoworca.naruserve.Configurer;
import com.snoworca.naruserve.ConsoleLogger;
import com.snoworca.naruserve.ErrorHandler;
import com.snoworca.naruserve.LifecycleCallback;
import com.snoworca.naruserve.Logger;
import com.snoworca.naruserve.Method;
import com.snoworca.naruserve.MethodSet;
import com.snoworca.naruserve.Middleware;
import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.RouteBuilder;
import com.snoworca.naruserve.RouteHandler;
import com.snoworca.naruserve.routing.RouterPolicy;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.StubJsonCodec;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.Test;

/** NEW-state guard. SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-4-3.sds.md. */
public class AppLifecycleGuardTest {

    private static final RouteHandler H = (req, res) -> { };
    private static final Middleware MW = (req, res, next) -> next.run();

    private static DefaultRestApp newApp() {
        return (DefaultRestApp) RestApp.builder().httpAdapter(new InMemoryHttpAdapter())
                .jsonCodec(new StubJsonCodec()).build();
    }

    /** Configuration/WS guards only (CON-APP-010 AC-3 AC-5), run against {@code app} and a RouteBuilder obtained in NEW. */
    private static Map<String, Consumer<DefaultRestApp>> guardedCalls(RouteBuilder early) {
        Map<String, Consumer<DefaultRestApp>> c = new LinkedHashMap<>();
        c.put("accessLog", a -> a.accessLog(o -> o.enabled(false)));
        c.put("logger", a -> a.logger(new ConsoleLogger()));
        c.put("onStart", a -> a.onStart(ctx -> { }));
        c.put("onStop", a -> a.onStop(ctx -> { }));
        c.put("onError", a -> a.onError((e, req, res) -> { }));
        c.put("routerPolicy", a -> a.routerPolicy(RouterPolicy.strict()));
        c.put("server", a -> a.server(o -> { }));
        c.put("ws", a -> a.ws("/ws", (req, ws) -> { }));
        c.put("webSocket", a -> a.webSocket(o -> { }));
        c.put("sse", a -> a.sse(o -> { }));
        c.put("jsonCodec", a -> a.jsonCodec(new StubJsonCodec()));
        c.put("multipart", a -> a.multipart(o -> { }));
        c.put("group", a -> a.group("/group", g -> { }));
        return c;
    }

    private static List<String> rejectAll(DefaultRestApp app, RouteBuilder early, AppState state) {
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, Consumer<DefaultRestApp>> e : guardedCalls(early).entrySet()) {
            String method = e.getKey().trim();
            try {
                e.getValue().accept(app);
                problems.add(e.getKey() + " accepted in " + state);
            } catch (IllegalStateException ex) {
                String m = String.valueOf(ex.getMessage());
                if (!m.contains(method) || !m.contains(state.name())) {
                    problems.add(e.getKey() + " message lacks method or state: " + m);
                }
            }
        }
        return problems;
    }

    // SDS-AC-3; FR-APP-002 AC-3
    @Test
    public void configurationAndWebSocketMethodsRemainRefusedOutsideNewNamingMethodAndState() {
        DefaultRestApp app = newApp();
        RouteBuilder early = app.route("/b");
        Logger original = app.logger();
        app.get("/x", H);
        List<String> problems = new ArrayList<>();
        // A state is added only after its rejectAll returned: a callback that dies on a non-ISE exception (which
        // the lifecycle error logging would swallow in STOPPING) or never runs leaves it missing.
        List<AppState> completed = new ArrayList<>();
        app.onStart(ctx -> {
            problems.addAll(rejectAll(app, early, AppState.STARTING));
            completed.add(AppState.STARTING);
        });
        app.onStop(ctx -> {
            problems.addAll(rejectAll(app, early, AppState.STOPPING));
            completed.add(AppState.STOPPING);
        });
        app.listen(0);
        problems.addAll(rejectAll(app, early, AppState.RUNNING));
        app.shutdown(1, TimeUnit.SECONDS);
        problems.addAll(rejectAll(app, early, AppState.TERMINATED));
        assertEquals(new ArrayList<String>(), problems);
        assertEquals(java.util.Arrays.asList(AppState.STARTING, AppState.STOPPING), completed);
        // The refused calls changed nothing.
        assertEquals(1, app.registrations().size());
        assertSame(original, app.logger());
    }

    // SDS-AC-3; FR-APP-002 AC-3
    @Test
    public void sdsAc3_FR_APP_002_AC3_requireConfigurableNamesMethodAndStateAndPassesInNew() {
        AppLifecycle lifecycle = new AppLifecycle();
        assertEquals(AppState.NEW, lifecycle.state());
        lifecycle.requireConfigurable("get");
        lifecycle.beginStart();
        try {
            lifecycle.requireConfigurable("routerPolicy");
            fail("routerPolicy accepted in STARTING");
        } catch (IllegalStateException e) {
            assertEquals("routerPolicy not allowed in state STARTING", e.getMessage());
        }
    }

    private static Object argFor(Class<?> type) {
        if (type == String.class) {
            return "/r";
        }
        if (type == RouteHandler.class) {
            return H;
        }
        if (type == Middleware.class) { return MW; }
        if (type == Middleware[].class) { return new Middleware[]{MW}; }
        if (type == Object.class) { return "/r"; }
        if (type == Router.class) { return Router.create(); }
        if (type == ParamHandler.class) { return (ParamHandler) (q, s, n, value, name) -> n.run(); }
        if (type == ErrorMiddleware.class) { return (ErrorMiddleware) (e, q, s, n) -> n.error(e); }
        if (type == AsyncRouteHandler.class) {
            return (AsyncRouteHandler) (q, s, n) -> { n.run(); return Deferred.resolved(); };
        }
        if (type == Method.class) {
            return Method.GET;
        }
        if (type == MethodSet.class) {
            return MethodSet.of(Method.GET);
        }
        if (type == ErrorHandler.class) {
            return (ErrorHandler) (e, req, res) -> { };
        }
        if (type == LifecycleCallback.class) {
            return (LifecycleCallback) ctx -> { };
        }
        if (type == Logger.class) {
            return new ConsoleLogger();
        }
        if (type == Configurer.class) {
            return (Configurer<Object>) o -> { };
        }
        if (type == RouterPolicy.class) {
            return RouterPolicy.strict();
        }
        // wave 5: IR-JSON-002 AC-1, AC-2 put jsonCodec and jsonLimit under the same guard
        if (type == com.snoworca.naruserve.spi.JsonCodec.class) {
            return new StubJsonCodec();
        }
        if (type == long.class) {
            return 5L;
        }
        // wave 5: IR-ENGINE-001 AC-1 puts trustProxy under the same guard
        if (type == boolean.class) {
            return true;
        }
        // wave 6: IR-STATIC-001 AC-5 puts both staticFiles overloads and defaultCache under the same guard
        if (type == java.io.File.class) {
            return new java.io.File("public");
        }
        if (type == com.snoworca.naruserve.CachePolicy.class) {
            return com.snoworca.naruserve.CachePolicy.noCache();
        }
        // wave 7: IR-WS-001 AC-3 puts ws and webSocket under the same guard
        if (type == com.snoworca.naruserve.WebSocketHandler.class) {
            return (com.snoworca.naruserve.WebSocketHandler) (req, ws) -> { };
        }
        throw new AssertionError("no test argument for " + type);
    }

    private static Object[] argsFor(java.lang.reflect.Method m) {
        Class<?>[] types = m.getParameterTypes();
        Object[] args = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            if (i == 0 && (m.getName().equals("method") || m.getName().equals("methodAsync"))) args[i] = "GET";
            else args[i] = argFor(types[i]);
        }
        return args;
    }

    // SDS-AC-4; FR-APP-002 AC-4; CON-APP-002 AC-1; re-pointed to IR-STATIC-001 AC-5: multipart, formLimit, both staticFiles overloads,
    // defaultCache and mimeTypes().register are refused after listen()
    @Test
    public void reflectionPartitionsEveryMutationIntoLiveHttpAndGuardedConfiguration() throws Exception {
        // CON-APP-010 AC-3 AC-5; FR-APP-021 AC-3 AC-4: inspect inherited erased-generic registrations too.
        DefaultRestApp app = newApp();
        RouteBuilder early = app.route("/b");
        app.listen(0);
        assertEquals(AppState.RUNNING, app.state());
        java.util.Set<String> http = new java.util.TreeSet<>(java.util.Arrays.asList(
                "route", "get", "post", "put", "patch", "delete", "head", "options", "use", "param",
                "method", "methodAsync", "getAsync", "useAsync"));
        java.util.Set<String> configuration = new java.util.TreeSet<>(java.util.Arrays.asList(
                "group", "cors", "routerPolicy", "onError", "onStart", "onStop", "logger", "accessLog",
                "jsonCodec", "jsonLimit", "server", "trustProxy", "multipart", "formLimit", "staticFiles",
                "defaultCache", "ws", "webSocket", "sse"));
        java.util.Set<String> observedHttp = new java.util.TreeSet<>(), observedConfiguration = new java.util.TreeSet<>();
        int checked = 0;
        for (java.lang.reflect.Method m : RestApp.class.getMethods()) {
            if (Modifier.isStatic(m.getModifiers()) || m.isSynthetic()) continue;
            Class<?> returns = m.getReturnType();
            if (returns != RestApp.class && returns != RouteBuilder.class && m.getDeclaringClass() != com.snoworca.naruserve.HttpRoutes.class) continue;
            checked++;
            assertTrue("unclassified mutation " + m, http.contains(m.getName()) || configuration.contains(m.getName()));
            boolean live = http.contains(m.getName());
            (live ? observedHttp : observedConfiguration).add(m.getName());
            try {
                Object result = m.invoke(app, argsFor(m));
                if (!live) fail("configuration accepted in RUNNING: " + m);
                if (returns == RouteBuilder.class) assertTrue(result instanceof RouteBuilder);
                else assertSame(m.toString(), app, result);
            } catch (InvocationTargetException e) {
                if (live) throw new AssertionError("live HTTP mutation refused: " + m, e.getCause());
                assertTrue(m + " threw " + e.getCause(), e.getCause() instanceof IllegalStateException);
                assertTrue(e.getCause().getMessage(), e.getCause().getMessage().contains(m.getName()));
                assertTrue(e.getCause().getMessage(), e.getCause().getMessage().contains("RUNNING"));
            }
        }
        for (java.lang.reflect.Method m : RouteBuilder.class.getMethods()) {
            assertEquals(m.toString(), RouteBuilder.class, m.getReturnType());
            checked++;
            try { assertSame(m.toString(), early, m.invoke(early, argsFor(m))); }
            catch (InvocationTargetException e) { throw new AssertionError("live builder mutation refused: " + m, e.getCause()); }
        }
        assertEquals(http, observedHttp);
        assertEquals(configuration, observedConfiguration);
        assertTrue("exhaustive mutations checked " + checked, checked >= 70);
        try { app.mimeTypes().register("webp", "image/webp"); fail("mimeTypes().register accepted in RUNNING"); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("RUNNING")); }
        // An inherited async registration and the String-builder path are visible to real requests after listen.
        app.getAsync("/live-async", (q, s, n) -> { s.send("async"); return Deferred.resolved(); });
        app.route("/live-builder").get((q, s) -> s.send("builder"));
        InMemoryHttpAdapter transport = (InMemoryHttpAdapter) app.httpAdapter();
        assertEquals("async", transport.send(InMemoryHttpAdapter.ClientRequest.of("GET", "/live-async")).text());
        assertEquals("builder", transport.send(InMemoryHttpAdapter.ClientRequest.of("GET", "/live-builder")).text());
        app.shutdown(1, TimeUnit.SECONDS);
        assertEquals(AppState.TERMINATED, app.state());
    }
    // CON-APP-010 AC-3 AC-5; FR-APP-021 AC-4: advanced path builders retain application shutdown guards.
    @Test
    public void advancedAndStringBuildersRejectCallbacksAfterApplicationTermination() {
        DefaultRestApp app = newApp();
        RouteBuilder stringBuilder = app.route("/string");
        RouteBuilder advancedBuilder = app.route((Object) new String[]{"/advanced"});
        app.listen(0);
        app.shutdown(1, TimeUnit.SECONDS);
        assertEquals(AppState.TERMINATED, app.state());
        for (RouteBuilder builder : new RouteBuilder[]{stringBuilder, advancedBuilder}) {
            try {
                builder.get((req, res) -> res.send("late"));
                fail(builder.getClass().getName() + " accepted callback after TERMINATED");
            } catch (IllegalStateException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("TERMINATED"));
            }
        }
    }

}
