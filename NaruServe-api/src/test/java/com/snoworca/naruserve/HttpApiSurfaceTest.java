package com.snoworca.naruserve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.routing.RouterPolicy;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.TestApp;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Modifier;
import java.lang.reflect.TypeVariable;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.junit.Test;

public class HttpApiSurfaceTest {

    // IR-JSON-002 AC-8: body and json left this list in wave 5; FR-COOKIE-002 AC-8: so did the cookie methods.
    private static final List<String> LATER_WAVE_NAMES = Arrays.asList("form", "file", "files", "parts",
            "download", "cache", "sse");

    /** "name(paramSimpleNames)->returnSimpleName" for every public method a type declares. */
    private static Set<String> signatures(Class<?> type) {
        Set<String> out = new TreeSet<>();
        for (java.lang.reflect.Method m : type.getDeclaredMethods()) {
            if (m.isSynthetic()) {
                continue;
            }
            StringBuilder sb = new StringBuilder(m.getName()).append('(');
            Class<?>[] ps = m.getParameterTypes();
            for (int i = 0; i < ps.length; i++) {
                sb.append(i == 0 ? "" : ",").append(ps[i].getSimpleName());
            }
            out.add(sb.append(")->").append(m.getReturnType().getSimpleName()).toString());
        }
        return out;
    }

    private static Set<String> setOf(String... s) {
        return new TreeSet<>(Arrays.asList(s));
    }

    /** "Type.name(params)#return" and "Type.name(params)#pN" for every @Nullable position. */
    private static Set<String> nullablePositions(Class<?>... types) {
        Set<String> out = new TreeSet<>();
        for (Class<?> type : types) {
            for (java.lang.reflect.Method m : type.getDeclaredMethods()) {
                String key = type.getSimpleName() + "." + m.getName() + Arrays.toString(m.getParameterTypes());
                if (m.getAnnotatedReturnType().isAnnotationPresent(Nullable.class)) {
                    out.add(key + "#return");
                }
                AnnotatedType[] ps = m.getAnnotatedParameterTypes();
                for (int i = 0; i < ps.length; i++) {
                    if (ps[i].isAnnotationPresent(Nullable.class)) {
                        out.add(key + "#p" + i);
                    }
                }
            }
        }
        return out;
    }

    // Compiles only when a literal null second argument resolves to the read overload (which returns a value).
    @SuppressWarnings("unused")
    private static @Nullable Object readWithLiteralNull(Request req) {
        return req.attribute("k", null);
    }

    // Compiles only when every RouteBuilder method returns RouteBuilder.
    @SuppressWarnings("unused")
    private static RouteBuilder chain(RestApp app, RouteHandler h1, RouteHandler h2, RouteHandler h3) {
        return app.route("/u/:id").get(h1).put(h2).delete(h3).post(h1).patch(h2).head(h3).options(h1);
    }

    // SDS-AC-1; IR-HTTP-001 AC-1; IR-APP-004 AC-3 adds application and service from wave 4; SDS-AC-11 of
    // docs/sds/2026-10-19.naruserve.naruserve-v1-wave-5-3.sds.md; FR-COOKIE-002 AC-8 adds the cookie readers
    @Test
    public void sdsAc1_IR_HTTP_001_AC1_requestDeclaresTheWave3Methods() throws Exception {
        assertTrue(Request.class.isInterface());
        assertTrue(Modifier.isPublic(Request.class.getModifiers()));
        assertEquals("com.snoworca.naruserve", Request.class.getPackage().getName());
        assertEquals(setOf("method()->Method", "path()->String", "rawPath()->String", "param(String)->String",
                "query(String)->String", "queries(String)->List", "header(String)->String", "headers(String)->List",
                "remoteAddress()->String", "isSecure()->boolean", "lastEventId()->String",
                "attribute(String,Object)->void", "attribute(String,Class)->Object",
                "application()->ApplicationContext", "service(Class)->Object",
                // wave 5: IR-JSON-002 AC-8
                "body(Class)->Object", "body(TypeRef)->Object",
                // wave 5: FR-COOKIE-002 AC-8
                "cookie(String)->Cookie", "getCookies()->Cookie[]"), signatures(Request.class));
        for (String name : new String[] {"queries", "headers"}) {
            assertEquals(name, "java.util.List<java.lang.String>",
                    Request.class.getMethod(name, String.class).getGenericReturnType().getTypeName());
        }
    }

    // SDS-AC-1; IR-HTTP-001 AC-1
    @Test
    public void sdsAc1_IR_HTTP_001_AC1_attributeOverloadsWriteVoidReadGenericNullable() throws Exception {
        java.lang.reflect.Method write = Request.class.getMethod("attribute", String.class, Object.class);
        assertEquals(void.class, write.getReturnType());
        assertTrue(write.getAnnotatedParameterTypes()[1].isAnnotationPresent(Nullable.class));

        java.lang.reflect.Method read = Request.class.getMethod("attribute", String.class, Class.class);
        TypeVariable<java.lang.reflect.Method>[] tps = read.getTypeParameters();
        assertEquals(1, tps.length);
        assertEquals(tps[0], read.getGenericReturnType());
        assertEquals("java.lang.Class<" + tps[0].getName() + ">", read.getGenericParameterTypes()[1].getTypeName());
        assertTrue(read.getAnnotatedReturnType().isAnnotationPresent(Nullable.class));
    }

    // SDS-AC-2; IR-HTTP-001 AC-2; SDS-AC-11 (wave 5-3); FR-COOKIE-002 AC-8 adds addCookie
    @Test
    public void sdsAc2_IR_HTTP_001_AC2_responseStatusAndHeaderReturnReceiverWritersReturnVoid() throws Exception {
        assertTrue(Response.class.isInterface());
        assertTrue(Modifier.isPublic(Response.class.getModifiers()));
        assertEquals(setOf("status(int)->Response", "header(String,String)->Response", "send(String)->void",
                "send(byte[])->void", "html(String)->void", "stream(InputStream,String)->void", "end()->void",
                "isCommitted()->boolean", "abort(Throwable)->void",
                // wave 5: IR-JSON-002 AC-8
                "json(Object)->void",
                // wave 5: FR-COOKIE-002 AC-8
                "addCookie(Cookie)->Response"), signatures(Response.class));
        assertEquals(InputStream.class, Response.class.getMethod("stream", InputStream.class, String.class)
                .getParameterTypes()[0]);
    }

    // SDS-AC-2 (wave 4-2); IR-APP-004 AC-3: the IR-HTTP-001 AC-3 surface test is re-pointed to expect these
    @Test
    public void sdsAc2_IR_APP_004_AC3_requestDeclaresApplicationAndGenericService() throws Exception {
        assertEquals(ApplicationContext.class, Request.class.getMethod("application").getReturnType());
        java.lang.reflect.Method service = Request.class.getMethod("service", Class.class);
        assertEquals(1, service.getTypeParameters().length);
        assertEquals(service.getTypeParameters()[0], service.getGenericReturnType());
        assertEquals("java.lang.Class<" + service.getTypeParameters()[0].getName() + ">",
                service.getGenericParameterTypes()[0].getTypeName());
    }

    // SDS-AC-2; IR-HTTP-001 AC-3, re-pointed by IR-APP-004 AC-3: service and application are expected from wave 4;
    // SDS-AC-11 (wave 5-3); FR-COOKIE-002 AC-8: cookie, getCookies and addCookie are expected from wave 5
    @Test
    public void sdsAc2_IR_HTTP_001_AC3_laterWaveMethodNamesAreAbsent() {
        for (Class<?> type : new Class<?>[] {Request.class, Response.class}) {
            for (java.lang.reflect.Method m : type.getMethods()) {
                assertFalse(type.getSimpleName() + "." + m.getName(), LATER_WAVE_NAMES.contains(m.getName()));
            }
        }
    }

    // SDS-AC-3; IR-HTTP-001 AC-4; IR-HTTP-005 AC-5; SDS-AC-11 (wave 5-3); FR-COOKIE-002 AC-8
    @Test
    public void sdsAc3_IR_HTTP_001_AC4_packageNullMarkedAndNullableOnlyOnListedPositions() throws Exception {
        assertNotNull(Request.class.getPackage().getAnnotation(NullMarked.class));
        assertEquals(setOf(
                "Request.query[class java.lang.String]#return",
                "Request.header[class java.lang.String]#return",
                "Request.lastEventId[]#return",
                "Request.attribute[class java.lang.String, class java.lang.Class]#return",
                "Request.attribute[class java.lang.String, class java.lang.Object]#p1",
                "Response.abort[class java.lang.Throwable]#p0",
                // wave 5: IR-JSON-002 AC-8
                "Request.body[class java.lang.Class]#return",
                "Request.body[class com.snoworca.naruserve.TypeRef]#return",
                "Response.json[class java.lang.Object]#p0",
                // wave 5: FR-COOKIE-002 AC-8
                "Request.cookie[class java.lang.String]#return"),
                nullablePositions(Request.class, Response.class));
        assertEquals(setOf(), nullablePositions(RestApp.class, RouteBuilder.class, RouteHandler.class));
    }

    // SDS-AC-11 (wave 5-3); FR-COOKIE-002 AC-8
    @Test
    public void sdsAc11_FR_COOKIE_002_AC8_cookieMethodsAreDeclaredAndOnlyCookieReturnIsNullable() throws Exception {
        java.lang.reflect.Method cookie = Request.class.getMethod("cookie", String.class);
        assertTrue(cookie.getAnnotatedReturnType().isAnnotationPresent(Nullable.class));
        assertFalse(cookie.getAnnotatedParameterTypes()[0].isAnnotationPresent(Nullable.class));
        java.lang.reflect.Method all = Request.class.getMethod("getCookies");
        assertEquals(Cookie[].class, all.getReturnType());
        assertFalse(all.getAnnotatedReturnType().isAnnotationPresent(Nullable.class));
        java.lang.reflect.Method add = Response.class.getMethod("addCookie", Cookie.class);
        assertEquals(Response.class, add.getReturnType());
        assertFalse(add.getAnnotatedParameterTypes()[0].isAnnotationPresent(Nullable.class));
    }

    // SDS-AC-5; IR-HTTP-002 AC-1
    @Test
    public void sdsAc5_IR_HTTP_002_AC1_routeHandlerIsFunctionalWithOneHandleMethod() throws Exception {
        assertTrue(RouteHandler.class.isInterface());
        assertTrue(Modifier.isPublic(RouteHandler.class.getModifiers()));
        assertNotNull(RouteHandler.class.getAnnotation(FunctionalInterface.class));
        int abstractCount = 0;
        for (java.lang.reflect.Method m : RouteHandler.class.getMethods()) {
            if (Modifier.isAbstract(m.getModifiers())) {
                abstractCount++;
            }
        }
        assertEquals(1, abstractCount);
        java.lang.reflect.Method handle = RouteHandler.class.getMethod("handle", Request.class, Response.class);
        assertEquals(void.class, handle.getReturnType());
        assertEquals(Arrays.<Class<?>>asList(Exception.class), Arrays.asList(handle.getExceptionTypes()));
    }

    // SDS-AC-5; IR-HTTP-002 AC-2
    @Test
    public void sdsAc5_IR_HTTP_002_AC2_javaLambdaThrowingIOExceptionIsARouteHandler() {
        IOException thrown = new IOException("boom");
        RouteHandler h = (req, res) -> {
            throw thrown;
        };
        try {
            invoke(h);
            fail("handle must rethrow the checked exception");
        } catch (Exception e) {
            assertSame(thrown, e);
        }
    }

    private static void invoke(RouteHandler h) throws Exception {
        // The lambda ignores both arguments, so no Request or Response implementation is needed here.
        h.handle(null, null);
    }

    // SDS-AC-6; IR-HTTP-005 AC-1, AC-2, AC-3, AC-5; IR-APP-002 AC-2 (wave 4 SDS-AC-5: re-pointed to the wave 4 set);
    // SDS-AC-3 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-5-4.sds.md; IR-ENGINE-001 AC-7: the wave 5 set
    @Test
    public void sdsAc6_IR_HTTP_005_AC1_AC2_AC3_AC5_IR_APP_002_AC2_restAppDeclaresExactlyTheWave4Methods() {
        assertTrue(RestApp.class.isInterface());
        assertTrue(Modifier.isPublic(RestApp.class.getModifiers()));
        assertEquals(setOf(
                "get(String,RouteHandler)->RestApp", "post(String,RouteHandler)->RestApp",
                "put(String,RouteHandler)->RestApp", "patch(String,RouteHandler)->RestApp",
                "delete(String,RouteHandler)->RestApp", "head(String,RouteHandler)->RestApp",
                "options(String,RouteHandler)->RestApp",
                "route(Method,String,RouteHandler)->RestApp", "route(MethodSet,String,RouteHandler)->RestApp",
                "route(String)->RouteBuilder",
                "routerPolicy(RouterPolicy)->RestApp",
                // wave 4: IR-APP-001, FR-APP-001
                "builder()->Builder", "create()->RestApp",
                // wave 4: IR-APP-002
                "use(Middleware)->RestApp", "use(String,Middleware)->RestApp",
                "get(String,Middleware,RouteHandler)->RestApp", "post(String,Middleware,RouteHandler)->RestApp",
                "put(String,Middleware,RouteHandler)->RestApp", "patch(String,Middleware,RouteHandler)->RestApp",
                "delete(String,Middleware,RouteHandler)->RestApp", "head(String,Middleware,RouteHandler)->RestApp",
                "options(String,Middleware,RouteHandler)->RestApp",
                "route(Method,String,Middleware,RouteHandler)->RestApp",
                "route(MethodSet,String,Middleware,RouteHandler)->RestApp",
                // wave 4: IR-APP-003
                "onError(ErrorHandler)->RestApp",
                // wave 4: IR-APP-005
                "onStart(LifecycleCallback)->RestApp", "onStop(LifecycleCallback)->RestApp",
                "listen(int)->ServerHandle", "shutdown(long,TimeUnit)->void", "state()->AppState",
                // wave 4: IR-APP-006
                "logger()->Logger", "logger(Logger)->RestApp",
                // wave 4: IR-APP-007
                "accessLog(Configurer)->RestApp",
                // wave 5: IR-JSON-002 AC-1, AC-2
                "jsonCodec(JsonCodec)->RestApp", "jsonLimit(long)->RestApp",
                // wave 5: IR-ENGINE-001 AC-1, AC-7
                "server(Configurer)->RestApp", "trustProxy(boolean)->RestApp"), signatures(RestApp.class));
        assertEquals(0, RestApp.class.getInterfaces().length);
        List<String> statics = new java.util.ArrayList<>();
        for (java.lang.reflect.Method m : RestApp.class.getDeclaredMethods()) {
            if (Modifier.isStatic(m.getModifiers()) && !m.isSynthetic()) {
                statics.add(m.getName());
            }
        }
        java.util.Collections.sort(statics);
        assertEquals(Arrays.asList("builder", "create"), statics);
    }

    // SDS-AC-6; IR-HTTP-005 AC-3
    @Test
    public void sdsAc6_IR_HTTP_005_AC3_routerPolicyTakesTheRoutingRouterPolicy() throws Exception {
        assertEquals(RestApp.class, RestApp.class.getMethod("routerPolicy", RouterPolicy.class).getReturnType());
    }

    // SDS-AC-6; IR-HTTP-005 AC-4
    @Test
    public void sdsAc6_IR_HTTP_005_AC4_noServeMethod() {
        for (java.lang.reflect.Method m : RestApp.class.getMethods()) {
            assertFalse(m.getName(), m.getName().equals("serve"));
        }
    }

    // SDS-AC-7; IR-HTTP-006 AC-1; IR-APP-002 AC-3
    @Test
    public void sdsAc7_IR_HTTP_006_AC1_routeBuilderMethodsTakeAHandlerAndReturnTheBuilder() {
        assertTrue(RouteBuilder.class.isInterface());
        assertTrue(Modifier.isPublic(RouteBuilder.class.getModifiers()));
        assertEquals(setOf("get(RouteHandler)->RouteBuilder", "post(RouteHandler)->RouteBuilder",
                "put(RouteHandler)->RouteBuilder", "patch(RouteHandler)->RouteBuilder",
                "delete(RouteHandler)->RouteBuilder", "head(RouteHandler)->RouteBuilder",
                "options(RouteHandler)->RouteBuilder",
                // wave 4: IR-APP-002 AC-3
                "use(Middleware)->RouteBuilder"), signatures(RouteBuilder.class));
    }

    // IR-HTTP-006 AC-2
    @Test
    public void IR_HTTP_006_AC2_routeBuilderHandlersEachReachOnlyTheirOwnMethod() {
        TestApp t = TestApp.create();
        List<String> calls = new java.util.ArrayList<>();
        t.app().route("/u/:id")
                .get((req, res) -> {
                    calls.add("h1 " + req.param("id"));
                    res.send("h1");
                })
                .put((req, res) -> {
                    calls.add("h2 " + req.param("id"));
                    res.send("h2");
                })
                .delete((req, res) -> {
                    calls.add("h3 " + req.param("id"));
                    res.send("h3");
                });
        for (String[] c : new String[][] {{"GET", "h1"}, {"PUT", "h2"}, {"DELETE", "h3"}}) {
            InMemoryHttpAdapter.ClientResponse r = t.send(c[0], "/u/1");
            assertEquals(c[0], 200, r.status());
            assertEquals(c[0], c[1], new String(r.body(), java.nio.charset.StandardCharsets.UTF_8));
        }
        assertEquals(Arrays.asList("h1 1", "h2 1", "h3 1"), calls);
        assertEquals(405, t.send("POST", "/u/1").status());
        assertEquals(3, calls.size());
    }

    // SDS-AC-5 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-4-3.sds.md; FR-APP-002 AC-5 (re-points FR-HTTP-008 AC-6)
    @Test
    public void sdsAc5_FR_APP_002_AC5_builderAndCreateAreTheOnlyPublicStaticRestAppFactoriesOfRestApp() {
        Set<String> factories = new TreeSet<>();
        for (java.lang.reflect.Method m : RestApp.class.getDeclaredMethods()) {
            if (Modifier.isPublic(m.getModifiers()) && Modifier.isStatic(m.getModifiers())) {
                factories.add(m.getName() + "->" + m.getReturnType().getSimpleName());
            }
        }
        assertEquals(setOf("builder->Builder", "create->RestApp"), factories);
    }
}
