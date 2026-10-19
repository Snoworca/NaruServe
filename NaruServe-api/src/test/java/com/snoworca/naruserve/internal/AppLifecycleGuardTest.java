package com.snoworca.naruserve.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.AccessLogOptions;
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

    /** Every guarded call by method name, run against {@code app} and a RouteBuilder obtained in NEW. */
    private static Map<String, Consumer<DefaultRestApp>> guardedCalls(RouteBuilder early) {
        Map<String, Consumer<DefaultRestApp>> c = new LinkedHashMap<>();
        c.put("get", a -> a.get("/n", H));
        c.put("post", a -> a.post("/n", H));
        c.put("put", a -> a.put("/n", H));
        c.put("patch", a -> a.patch("/n", H));
        c.put("delete", a -> a.delete("/n", H));
        c.put("head", a -> a.head("/n", H));
        c.put("options", a -> a.options("/n", H));
        c.put("route", a -> a.route(Method.GET, "/n", H));
        c.put("route ", a -> a.route(MethodSet.of(Method.GET), "/n", H));
        c.put("get ", a -> a.get("/n", MW, H));
        c.put("post    ", a -> a.post("/n", null, H)); // guard runs before the null check
        c.put("post ", a -> a.post("/n", MW, H));
        c.put("put ", a -> a.put("/n", MW, H));
        c.put("patch ", a -> a.patch("/n", MW, H));
        c.put("delete ", a -> a.delete("/n", MW, H));
        c.put("head ", a -> a.head("/n", MW, H));
        c.put("options ", a -> a.options("/n", MW, H));
        c.put("route  ", a -> a.route(Method.GET, "/n", MW, H));
        c.put("route   ", a -> a.route(MethodSet.of(Method.GET), "/n", MW, H));
        c.put("use", a -> a.use(MW));
        c.put("use ", a -> a.use("/p", MW));
        c.put("accessLog", a -> a.accessLog(o -> o.enabled(false)));
        c.put("logger", a -> a.logger(new ConsoleLogger()));
        c.put("onStart", a -> a.onStart(ctx -> { }));
        c.put("onStop", a -> a.onStop(ctx -> { }));
        c.put("onError", a -> a.onError((e, req, res) -> { }));
        c.put("routerPolicy", a -> a.routerPolicy(RouterPolicy.strict()));
        c.put("get  ", a -> early.get(H));
        c.put("use  ", a -> early.use(MW));
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
    public void sdsAc3_FR_APP_002_AC3_everyConfigurationMethodIsRefusedOutsideNewNamingMethodAndState() {
        DefaultRestApp app = newApp();
        RouteBuilder early = app.route("/b");
        Logger original = app.logger();
        app.get("/x", H);
        List<String> problems = new ArrayList<>();
        app.onStart(ctx -> problems.addAll(rejectAll(app, early, AppState.STARTING)));
        app.onStop(ctx -> problems.addAll(rejectAll(app, early, AppState.STOPPING)));
        app.listen(0);
        problems.addAll(rejectAll(app, early, AppState.RUNNING));
        app.shutdown(1, TimeUnit.SECONDS);
        problems.addAll(rejectAll(app, early, AppState.TERMINATED));
        assertEquals(new ArrayList<String>(), problems);
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
        if (type == Middleware.class) {
            return MW;
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
            return (Configurer<AccessLogOptions>) o -> { };
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
        throw new AssertionError("no test argument for " + type);
    }

    private static Object[] argsFor(java.lang.reflect.Method m) {
        Class<?>[] types = m.getParameterTypes();
        Object[] args = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            args[i] = argFor(types[i]);
        }
        return args;
    }

    // SDS-AC-4; FR-APP-002 AC-4; re-pointed to IR-STATIC-001 AC-5: multipart, formLimit, both staticFiles overloads,
    // defaultCache and mimeTypes().register are refused after listen()
    @Test
    public void sdsAc4_FR_APP_002_AC4_reflectionFindsEveryRestAppAndRouteBuilderRegistrationRefusedInRunning()
            throws Exception {
        DefaultRestApp app = newApp();
        RouteBuilder early = app.route("/b");
        app.listen(0);
        assertEquals(AppState.RUNNING, app.state());
        List<String> accepted = new ArrayList<>();
        int checked = 0;
        List<String> guarded = new ArrayList<>();
        for (java.lang.reflect.Method m : RestApp.class.getMethods()) {
            if (Modifier.isStatic(m.getModifiers()) || m.getReturnType() != RestApp.class) {
                continue;
            }
            checked++;
            guarded.add(m.getName() + "/" + m.getParameterCount());
            try {
                m.invoke(app, argsFor(m));
                accepted.add("RestApp." + m.getName());
            } catch (InvocationTargetException e) {
                if (!(e.getCause() instanceof IllegalStateException)) {
                    accepted.add("RestApp." + m.getName() + " threw " + e.getCause());
                }
            }
        }
        for (java.lang.reflect.Method m : RouteBuilder.class.getMethods()) {
            if (m.getReturnType() != RouteBuilder.class) {
                continue;
            }
            checked++;
            try {
                m.invoke(early, argsFor(m));
                accepted.add("RouteBuilder." + m.getName());
            } catch (InvocationTargetException e) {
                if (!(e.getCause() instanceof IllegalStateException)) {
                    accepted.add("RouteBuilder." + m.getName() + " threw " + e.getCause());
                }
            }
        }
        assertEquals(new ArrayList<String>(), accepted);
        assertTrue("checked " + checked, checked >= 30);
        for (String wave6 : new String[] {"multipart/1", "formLimit/1", "staticFiles/2", "staticFiles/3",
                "defaultCache/1"}) {
            assertTrue(wave6 + " in " + guarded, guarded.contains(wave6));
        }
        try {
            app.mimeTypes().register("webp", "image/webp");
            fail("mimeTypes().register accepted in RUNNING");
        } catch (IllegalStateException expected) {
            // guarded like the RestApp setters
        }
        // Not guarded: reads and route(String).
        app.logger();
        assertEquals(AppState.RUNNING, app.state());
        RouteBuilder late = app.route("/late");
        try {
            late.get(H);
            fail("a builder obtained in RUNNING still refuses registration");
        } catch (IllegalStateException expected) {
            // guarded at the registration, not at route(String)
        }
        app.shutdown(1, TimeUnit.SECONDS);
    }
}
