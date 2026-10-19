package com.snoworca.naruserve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.spi.JsonCodec;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.TestApp;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.junit.Test;

public class AppEntryApiTest {

    private static List<java.lang.reflect.Method> abstractMethods(Class<?> type) {
        List<java.lang.reflect.Method> out = new ArrayList<>();
        for (java.lang.reflect.Method m : type.getMethods()) {
            if (Modifier.isAbstract(m.getModifiers())) {
                out.add(m);
            }
        }
        return out;
    }

    private static Set<String> setOf(String... s) {
        return new TreeSet<>(Arrays.asList(s));
    }

    private static Set<String> abstractNames(Class<?> type) {
        Set<String> out = new TreeSet<>();
        for (java.lang.reflect.Method m : abstractMethods(type)) {
            out.add(m.getName() + Arrays.toString(m.getParameterTypes()) + "->" + m.getReturnType().getSimpleName());
        }
        return out;
    }

    // SDS-AC-5; IR-APP-002 AC-1
    @Test
    public void sdsAc5_IR_APP_002_AC1_middlewareIsFunctionalAndNextHasExactlyRunAndError() throws Exception {
        assertNotNull(Middleware.class.getAnnotation(FunctionalInterface.class));
        assertEquals(1, abstractMethods(Middleware.class).size());
        java.lang.reflect.Method handle = Middleware.class.getMethod("handle", Request.class, Response.class,
                Next.class);
        assertEquals(void.class, handle.getReturnType());
        assertEquals(Arrays.<Class<?>>asList(Exception.class), Arrays.asList(handle.getExceptionTypes()));

        assertTrue(Next.class.isInterface());
        assertEquals(setOf("run[]->void", "error[class java.lang.Throwable]->void"), abstractNames(Next.class));
        assertEquals(2, Next.class.getMethods().length);

        Middleware mw = (req, res, next) -> next.run();
        assertNotNull(mw);
    }

    // SDS-AC-5; IR-APP-003 AC-1
    @Test
    public void sdsAc5_IR_APP_003_AC1_errorHandlerIsFunctionalAndOnErrorReturnsTheApp() throws Exception {
        assertNotNull(ErrorHandler.class.getAnnotation(FunctionalInterface.class));
        assertEquals(1, abstractMethods(ErrorHandler.class).size());
        java.lang.reflect.Method handle = ErrorHandler.class.getMethod("handle", Throwable.class, Request.class,
                Response.class);
        assertEquals(void.class, handle.getReturnType());
        assertEquals(Arrays.<Class<?>>asList(Exception.class), Arrays.asList(handle.getExceptionTypes()));
        assertEquals(RestApp.class, RestApp.class.getMethod("onError", ErrorHandler.class).getReturnType());

        RestApp app = TestApp.create().app();
        assertSame(app, app.onError((error, req, res) -> res.status(500).send("x")));
    }

    // SDS-AC-5; IR-APP-001 AC-4
    @Test
    public void sdsAc5_IR_APP_001_AC4_jsonCodecHasExactlyDecodeAndEncodeOverJavaTypes() throws Exception {
        assertEquals("com.snoworca.naruserve.spi", JsonCodec.class.getPackage().getName());
        assertEquals(setOf("decode[class java.io.Reader, interface java.lang.reflect.Type]->Object",
                "encode[class java.lang.Object, class java.io.Writer]->void"), abstractNames(JsonCodec.class));
        java.lang.reflect.Method decode = JsonCodec.class.getMethod("decode", Reader.class, Type.class);
        assertEquals(1, decode.getTypeParameters().length);
        assertEquals(decode.getTypeParameters()[0], decode.getGenericReturnType());
        assertEquals(Arrays.<Class<?>>asList(IOException.class), Arrays.asList(decode.getExceptionTypes()));
        java.lang.reflect.Method encode = JsonCodec.class.getMethod("encode", Object.class, Writer.class);
        assertEquals(Arrays.<Class<?>>asList(IOException.class), Arrays.asList(encode.getExceptionTypes()));
        assertTrue(encode.getAnnotatedParameterTypes()[0].isAnnotationPresent(Nullable.class));
        assertNotNull(JsonCodec.class.getPackage().getAnnotation(NullMarked.class));
    }

    // SDS-AC-5; IR-APP-001 AC-4, IR-APP-002 AC-5
    @Test
    public void sdsAc5_IR_APP_001_AC4_IR_APP_002_AC5_noGsonAndNoFutureTypeOnTheEntrySurface() {
        for (Class<?> type : new Class<?>[] {RestApp.class, RestApp.Builder.class, RouteBuilder.class,
                Middleware.class, Next.class, ErrorHandler.class, JsonCodec.class, HttpException.class}) {
            for (java.lang.reflect.Method m : type.getMethods()) {
                List<Class<?>> types = new ArrayList<>(Arrays.asList(m.getParameterTypes()));
                types.add(m.getReturnType());
                types.addAll(Arrays.asList(m.getExceptionTypes()));
                for (Class<?> t : types) {
                    String n = t.getName();
                    assertFalse(type.getSimpleName() + "." + m.getName() + " uses " + n,
                            n.startsWith("com.google.gson") || java.util.concurrent.Future.class.isAssignableFrom(t)
                                    || n.equals("java.util.concurrent.CompletionStage"));
                }
            }
        }
    }

    // SDS-AC-6; IR-APP-002 AC-3
    @Test
    public void sdsAc6_IR_APP_002_AC3_routeBuilderUseAppliesOnlyEarlierMiddlewareInUseOrder() {
        TestApp t = TestApp.create();
        List<String> calls = new ArrayList<>();
        Middleware m1 = (req, res, next) -> {
            calls.add("m1");
            next.run();
        };
        Middleware m2 = (req, res, next) -> {
            calls.add("m2");
            next.run();
        };
        RouteBuilder rb = t.app().route("/a");
        assertSame(rb, rb.use(m1));
        rb.post((req, res) -> {
            calls.add("post");
            res.send("p");
        });
        rb.use(m2).get((req, res) -> {
            calls.add("get");
            res.send("g");
        });

        InMemoryHttpAdapter.ClientResponse g = t.send("GET", "/a");
        assertEquals(200, g.status());
        assertEquals("g", new String(g.body(), StandardCharsets.UTF_8));
        assertEquals(Arrays.asList("m1", "m2", "get"), calls);

        calls.clear();
        assertEquals(200, t.send("POST", "/a").status());
        assertEquals(Arrays.asList("m1", "post"), calls);
    }

    // SDS-AC-6; IR-APP-002 AC-2
    @Test
    public void sdsAc6_IR_APP_002_AC2_routeMiddlewareOverloadRunsBeforeTheHandler() {
        TestApp t = TestApp.create();
        List<String> calls = new ArrayList<>();
        Middleware mw = (req, res, next) -> {
            calls.add("mw");
            next.run();
        };
        assertSame(t.app(), t.app().get("/x", mw, (req, res) -> {
            calls.add("h");
            res.send("x");
        }));
        assertEquals(200, t.send("GET", "/x").status());
        assertEquals(Arrays.asList("mw", "h"), calls);
    }

    // SDS-AC-7; IR-APP-002 AC-4
    @Test
    @SuppressWarnings("ConstantConditions")
    public void sdsAc7_IR_APP_002_AC4_nullArgumentsAndRelativePrefixAreRefused() {
        RestApp app = TestApp.create().app();
        Middleware mw = (req, res, next) -> next.run();
        RouteHandler h = (req, res) -> res.send("x");
        List<Runnable> npe = Arrays.asList(
                () -> app.use(AppEntryApiTest.<Middleware>nullOf()),
                () -> app.use("/api", AppEntryApiTest.<Middleware>nullOf()),
                () -> app.use(AppEntryApiTest.<String>nullOf(), mw),
                () -> app.get("/x", AppEntryApiTest.<Middleware>nullOf(), h),
                () -> app.get("/x", mw, AppEntryApiTest.<RouteHandler>nullOf()),
                () -> app.post("/x", AppEntryApiTest.<Middleware>nullOf(), h),
                () -> app.put("/x", AppEntryApiTest.<Middleware>nullOf(), h),
                () -> app.patch("/x", AppEntryApiTest.<Middleware>nullOf(), h),
                () -> app.delete("/x", AppEntryApiTest.<Middleware>nullOf(), h),
                () -> app.head("/x", AppEntryApiTest.<Middleware>nullOf(), h),
                () -> app.options("/x", AppEntryApiTest.<Middleware>nullOf(), h),
                () -> app.route(Method.GET, "/x", AppEntryApiTest.<Middleware>nullOf(), h),
                () -> app.route(MethodSet.of(Method.GET), "/x", mw, AppEntryApiTest.<RouteHandler>nullOf()),
                () -> app.route("/x").use(AppEntryApiTest.<Middleware>nullOf()));
        for (int i = 0; i < npe.size(); i++) {
            try {
                npe.get(i).run();
                fail("case " + i + " must throw NullPointerException");
            } catch (NullPointerException expected) {
            }
        }
        try {
            app.use("api", mw);
            fail("prefix without leading /");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("api"));
        }
        // Nothing was recorded: the app still freezes and answers 404 for /x.
        TestApp t = TestApp.create();
        try {
            t.app().get("/x", AppEntryApiTest.<Middleware>nullOf(), h);
        } catch (NullPointerException expected) {
        }
        assertEquals(404, t.send("GET", "/x").status());
    }

    // SDS-AC-11; IR-APP-003 AC-5
    @Test
    public void sdsAc11_IR_APP_003_AC5_onErrorNullThrowsAndOnErrorIsRefusedAfterFreeze() {
        TestApp t = TestApp.create();
        try {
            t.app().onError(AppEntryApiTest.<ErrorHandler>nullOf());
            fail("onError(null)");
        } catch (NullPointerException expected) {
        }
        RouteBuilder early = t.app().route("/late");
        t.start();
        try {
            early.use((req, res, next) -> next.run());
            fail("RouteBuilder.use after freeze");
        } catch (IllegalStateException expected) {
        }
        try {
            t.app().onError((error, req, res) -> res.send("x"));
            fail("onError after freeze");
        } catch (IllegalStateException expected) {
        }
        try {
            t.app().use((req, res, next) -> next.run());
            fail("use after freeze");
        } catch (IllegalStateException expected) {
        }
    }

    @SuppressWarnings({"unchecked", "TypeParameterUnusedInFormals"})
    private static <T> T nullOf() {
        return (T) (Object) null;
    }
}
