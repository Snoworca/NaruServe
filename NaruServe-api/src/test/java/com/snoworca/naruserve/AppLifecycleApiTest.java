package com.snoworca.naruserve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.Test;

public class AppLifecycleApiTest {

    static final class UserService {
    }

    private static Set<String> setOf(String... s) {
        return new TreeSet<>(Arrays.asList(s));
    }

    private static Set<String> declared(Class<?> type) {
        Set<String> out = new TreeSet<>();
        for (java.lang.reflect.Method m : type.getDeclaredMethods()) {
            if (!m.isSynthetic()) {
                out.add(m.getName() + Arrays.toString(m.getParameterTypes()) + "->" + m.getReturnType().getSimpleName());
            }
        }
        return out;
    }

    private static int abstractCount(Class<?> type) {
        int n = 0;
        for (java.lang.reflect.Method m : type.getMethods()) {
            if (Modifier.isAbstract(m.getModifiers())) {
                n++;
            }
        }
        return n;
    }

    private static RestApp newApp(InMemoryHttpAdapter adapter) {
        return RestApp.builder().httpAdapter(adapter).jsonCodec(new RestAppBuilderTest.NoopCodec()).build();
    }

    private static void expectNpe(Runnable r) {
        try {
            r.run();
            fail("expected NullPointerException");
        } catch (NullPointerException expected) {
            // expected
        }
    }

    private static void expectIae(Runnable r) {
        try {
            r.run();
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    /** Runs {@code body} as the onStart callback of a started app, then shuts the app down. */
    private static ApplicationContext withStartedContext(LifecycleCallback body) {
        RestApp app = newApp(new InMemoryHttpAdapter());
        AtomicReference<ApplicationContext> captured = new AtomicReference<>();
        app.onStart(ctx -> {
            captured.set(ctx);
            body.handle(ctx);
        });
        app.listen(0);
        app.shutdown(1, TimeUnit.SECONDS);
        return captured.get();
    }

    // SDS-AC-1; IR-APP-004 AC-1
    @Test
    public void sdsAc1_IR_APP_004_AC1_registryAndContextDeclareExactlyTheirMethods() {
        assertTrue(ServiceRegistry.class.isInterface());
        assertEquals(setOf("register[class java.lang.Class, class java.lang.Object]->void",
                "registerExternal[class java.lang.Class, class java.lang.Object]->void",
                "get[class java.lang.Class]->Object", "getOrNull[class java.lang.Class]->Object",
                "contains[class java.lang.Class]->boolean"), declared(ServiceRegistry.class));
        assertTrue(ServiceRegistry.class.isAssignableFrom(ApplicationContext.class));
        assertEquals(setOf("attribute[class java.lang.String, class java.lang.Object]->void",
                "attribute[class java.lang.String, class java.lang.Class]->Object",
                "own[interface java.lang.AutoCloseable]->void", "logger[]->Logger"),
                declared(ApplicationContext.class));
        assertTrue(RuntimeException.class.isAssignableFrom(ServiceNotFoundException.class));
    }

    // SDS-AC-1; IR-APP-004 AC-2
    @Test
    public void sdsAc1_IR_APP_004_AC2_getMissThrowsNamingTheTypeWhileGetOrNullAndContainsReportAbsence() {
        UserService s = new UserService();
        ApplicationContext ctx = withStartedContext(c -> c.register(UserService.class, s));
        assertSame(s, ctx.get(UserService.class));
        assertTrue(ctx.contains(UserService.class));
        try {
            ctx.get(String.class);
            fail("expected ServiceNotFoundException");
        } catch (ServiceNotFoundException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(String.class.getName()));
        }
        try {
            withStartedContext(c -> { }).get(UserService.class);
            fail("expected ServiceNotFoundException");
        } catch (ServiceNotFoundException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(UserService.class.getName()));
        }
        assertNull(ctx.getOrNull(String.class));
        assertFalse(ctx.contains(String.class));
    }

    // SDS-AC-3; IR-APP-004 AC-4
    @Test
    public void sdsAc3_IR_APP_004_AC4_typedNullRemovesNullArgumentsThrowAndWrongTypeIsClassCast() {
        List<String> done = new ArrayList<>();
        ApplicationContext ctx = withStartedContext(c -> {
            expectNpe(() -> c.register(null, new UserService()));
            expectNpe(() -> c.register(UserService.class, null));
            expectNpe(() -> c.registerExternal(null, new UserService()));
            expectNpe(() -> c.registerExternal(UserService.class, null));
            expectNpe(() -> c.own(null));
            done.add("start");
        });
        assertEquals(Arrays.asList("start"), done);
        expectNpe(() -> ctx.get(null));
        expectNpe(() -> ctx.getOrNull(null));
        expectNpe(() -> ctx.contains(null));
        expectNpe(() -> ctx.attribute(null, "v"));
        expectNpe(() -> ctx.attribute(null, String.class));
        expectNpe(() -> ctx.attribute("k", (Class<?>) null));

        ctx.attribute("k", 1);
        assertEquals(Integer.valueOf(1), ctx.attribute("k", Integer.class));
        Object v = null;
        ctx.attribute("k", v);
        assertNull(ctx.attribute("k", Integer.class));

        ctx.attribute("s", "x");
        try {
            ctx.attribute("s", Integer.class);
            fail("expected ClassCastException");
        } catch (ClassCastException expected) {
            // expected
        }
    }

    // SDS-AC-4; IR-APP-004 AC-5
    @Test
    public void sdsAc4_IR_APP_004_AC5_onlyGetOrNullAndTheAttributeReadAndValueAreNullable() throws Exception {
        assertTrue(ServiceRegistry.class.getMethod("getOrNull", Class.class).getAnnotatedReturnType()
                .isAnnotationPresent(Nullable.class));
        assertFalse(ServiceRegistry.class.getMethod("get", Class.class).getAnnotatedReturnType()
                .isAnnotationPresent(Nullable.class));
        assertFalse(Request.class.getMethod("service", Class.class).getAnnotatedReturnType()
                .isAnnotationPresent(Nullable.class));
        assertTrue(ApplicationContext.class.getMethod("attribute", String.class, Class.class)
                .getAnnotatedReturnType().isAnnotationPresent(Nullable.class));
        assertTrue(ApplicationContext.class.getMethod("attribute", String.class, Object.class)
                .getAnnotatedParameterTypes()[1].isAnnotationPresent(Nullable.class));
    }

    // SDS-AC-5; IR-APP-005 AC-1, AC-2
    @Test
    public void sdsAc5_IR_APP_005_AC1_AC2_appStateOrderAndLifecycleCallbackShape() throws Exception {
        assertEquals(Arrays.asList(AppState.NEW, AppState.STARTING, AppState.RUNNING, AppState.STOPPING,
                AppState.FAILED, AppState.TERMINATED), Arrays.asList(AppState.values()));
        assertEquals(AppState.NEW, newApp(new InMemoryHttpAdapter()).state());

        assertNotNull(LifecycleCallback.class.getAnnotation(FunctionalInterface.class));
        assertEquals(1, abstractCount(LifecycleCallback.class));
        java.lang.reflect.Method handle = LifecycleCallback.class.getMethod("handle", ApplicationContext.class);
        assertEquals(void.class, handle.getReturnType());
        assertEquals(Arrays.<Class<?>>asList(Exception.class), Arrays.asList(handle.getExceptionTypes()));
        assertEquals(RestApp.class, RestApp.class.getMethod("onStart", LifecycleCallback.class).getReturnType());
        assertEquals(RestApp.class, RestApp.class.getMethod("onStop", LifecycleCallback.class).getReturnType());
    }

    // SDS-AC-5; IR-APP-005 AC-3
    @Test
    public void sdsAc5_IR_APP_005_AC3_serverHandleHasPortAddressStateReadingTheLiveState() {
        assertEquals(setOf("port[]->int", "address[]->String", "state[]->AppState"), declared(ServerHandle.class));
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        RestApp app = newApp(adapter);
        ServerHandle h = app.listen(0);
        assertEquals(adapter.boundPort(), h.port());
        assertEquals("127.0.0.1", h.address());
        assertEquals(AppState.RUNNING, h.state());
        assertEquals(AppState.RUNNING, app.state());
        app.shutdown(1, TimeUnit.SECONDS);
        assertEquals(AppState.TERMINATED, h.state());
        assertEquals(AppState.TERMINATED, app.state());
    }

    // SDS-AC-6; IR-APP-005 AC-2, AC-4
    @Test
    public void sdsAc6_IR_APP_005_AC4_badPortTimeoutUnitAndNullCallbackThrowBeforeAnyStateChange() {
        RestApp app = newApp(new InMemoryHttpAdapter());
        expectIae(() -> app.listen(-1));
        expectIae(() -> app.listen(65536));
        assertEquals(AppState.NEW, app.state());
        expectIae(() -> app.shutdown(-1, TimeUnit.SECONDS));
        expectNpe(() -> app.shutdown(1, null));
        expectNpe(() -> app.onStart(null));
        expectNpe(() -> app.onStop(null));
        assertEquals(AppState.NEW, app.state());
    }

    // SDS-AC-7; IR-APP-005 AC-5
    @Test
    public void sdsAc7_IR_APP_005_AC5_appStartExceptionIsUncheckedAndCarriesTheCheckedCause() throws Exception {
        assertSame(RuntimeException.class, AppStartException.class.getSuperclass());
        IOException cause = new IOException("disk");
        AppStartException e = AppStartException.class.getConstructor(String.class, Throwable.class)
                .newInstance("m", cause);
        assertEquals("m", e.getMessage());
        assertSame(cause, e.getCause());
        assertEquals("m", new AppStartException("m").getMessage());

        RestApp app = newApp(new InMemoryHttpAdapter());
        app.onStart(ctx -> {
            throw cause;
        });
        try {
            app.listen(0);
            fail("expected AppStartException");
        } catch (AppStartException thrown) {
            assertSame(cause, thrown.getCause());
        }
    }

    // SDS-AC-11; IR-APP-006 AC-4
    @Test
    public void sdsAc11_IR_APP_006_AC4_loggerSetterReturnsTheAppAndNullThrows() {
        RestApp app = newApp(new InMemoryHttpAdapter());
        assertEquals(ConsoleLogger.class, app.logger().getClass());
        Logger l = new RestAppBuilderTest.RecordingLogger();
        assertSame(app, app.logger(l));
        assertSame(l, app.logger());
        expectNpe(() -> app.logger(null));
        assertSame(l, app.logger());

        Logger given = new RestAppBuilderTest.RecordingLogger();
        RestApp built = RestApp.builder().httpAdapter(new InMemoryHttpAdapter())
                .jsonCodec(new RestAppBuilderTest.NoopCodec()).logger(given).build();
        assertSame(given, built.logger());
        built.listen(0);
        assertSame(given, built.logger());
        built.shutdown(1, TimeUnit.SECONDS);
        assertSame(given, built.logger());
    }
}
