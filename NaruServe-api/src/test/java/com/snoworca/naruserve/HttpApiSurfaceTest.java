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
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.junit.Test;

public class HttpApiSurfaceTest {

    // IR-JSON-002 AC-8: body and json left this list in wave 5; FR-COOKIE-002 AC-8: so did the cookie methods;
    // IR-MULTIPART-002 AC-6: form, file, files and parts left it in wave 6; IR-STATIC-002 AC-5: so did cache and download;
    // IR-SSE-001 AC-1: sse left it in wave 7, so no later-wave name is left.
    private static final List<String> LATER_WAVE_NAMES = Collections.<String>emptyList();

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

    // Compiles only when a literal null second argument resolves to deletion without a cast.
    @SuppressWarnings("unused")
    private static @Nullable Object readWithLiteralNull(Request req) {
        req.attribute("k", null);
        return req.attributeAs("k", Object.class);
    }

    // Compiles only when every RouteBuilder method returns RouteBuilder.
    @SuppressWarnings("unused")
    private static RouteBuilder chain(RestApp app, RouteHandler h1, RouteHandler h2, RouteHandler h3) {
        return app.route("/u/:id").get(h1).put(h2).delete(h3).post(h1).patch(h2).head(h3).options(h1);
    }

    // SDS-AC-1; IR-HTTP-001 AC-1; IR-APP-004 AC-3 adds application and service from wave 4; SDS-AC-11 of
    // docs/sds/2026-10-19.naruserve.naruserve-v1-wave-5-3.sds.md; FR-COOKIE-002 AC-8 adds the cookie readers; IR-MULTIPART-002 AC-6 adds
    // form, file, files and parts
    @Test
    public void sdsAc1_IR_HTTP_001_AC1_requestDeclaresTheWave3Methods() throws Exception {
        assertTrue(Request.class.isInterface());
        assertTrue(Modifier.isPublic(Request.class.getModifiers()));
        assertEquals("com.snoworca.naruserve", Request.class.getPackage().getName());
        assertEquals(setOf("method()->Method", "path()->String", "rawPath()->String", "param(String)->String",
                // CON-APP-010 AC-5; FR-HTTP-027 AC-1 AC-3 AC-6; FR-HTTP-028 AC-1
                "methodToken()->String", "url()->String", "originalUrl()->String", "baseUrl()->String",
                "paramOrNull(String)->String", "paramValues(String)->List", "pathParams()->Map",
                "param(String,String)->void", "paramValues(String,List)->void",
                "query(String)->String", "queries(String)->List", "header(String)->String", "headers(String)->List",
                "remoteAddress()->String", "isSecure()->boolean", "lastEventId()->String",
                "requireQuery(String)->String", "requireHeader(String)->String", "requireForm(String)->String",
                "requireFile(String)->UploadFile", "queryInt(String,int)->int", "formInt(String,int)->int", "paramInt(String)->int",
                "removeAttribute(String)->void", "attribute(String,Object)->void", "attributeAs(String,Class)->Object",
                "application()->ApplicationContext", "service(Class)->Object",
                // wave 5: IR-JSON-002 AC-8
                "body(Class)->Object", "body(TypeRef)->Object",
                // wave 5: FR-COOKIE-002 AC-8
                "cookie(String)->Cookie", "getCookies()->Cookie[]",
                // wave 6: IR-MULTIPART-002 AC-6
                "form(String)->String", "file(String)->UploadFile", "files(String)->List", "parts()->List"),
                signatures(Request.class));
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

        java.lang.reflect.Method read = Request.class.getMethod("attributeAs", String.class, Class.class);
        TypeVariable<java.lang.reflect.Method>[] tps = read.getTypeParameters();
        assertEquals(1, tps.length);
        assertEquals(tps[0], read.getGenericReturnType());
        assertEquals("java.lang.Class<" + tps[0].getName() + ">", read.getGenericParameterTypes()[1].getTypeName());
        assertTrue(read.getAnnotatedReturnType().isAnnotationPresent(Nullable.class));
    }

    // SDS-AC-2; IR-HTTP-001 AC-2; SDS-AC-11 (wave 5-3); FR-COOKIE-002 AC-8 adds addCookie; IR-STATIC-002 AC-5 adds
    // cache, file and download
    @Test
    public void sdsAc2_IR_HTTP_001_AC2_responseStatusAndHeaderReturnReceiverWritersReturnVoid() throws Exception {
        assertTrue(Response.class.isInterface());
        assertTrue(Modifier.isPublic(Response.class.getModifiers()));
        assertEquals(setOf("status(int)->Response", "header(String,String)->Response", "send(Object)->void", "html(String)->void", "stream(InputStream,String)->void", "end()->void",
                "isCommitted()->boolean", "abort(Throwable)->void",
                // wave 5: IR-JSON-002 AC-8
                "json(Object)->void",
                // wave 5: FR-COOKIE-002 AC-8
                "addCookie(Cookie)->Response",
                // wave 6: IR-STATIC-002 AC-5
                "cache(CachePolicy)->Response", "file(File)->void", "download(File,String)->void",
                // wave 7: IR-SSE-001 AC-1
                "sse()->SseClient",
                // FR-DX-004 AC-1 AC-2 AC-3 AC-4
                "location(String)->Response", "type(String)->Response", "redirect(String)->void",
                "redirect(int,String)->void", "sendStatus(int)->void",
                // FR-DX-005 AC-1 AC-2 AC-3
                "cookie(String,String)->Response", "cookie(String,String,Configurer)->Response",
                "clearCookie(String)->Response", "clearCookie(String,Configurer)->Response"),
                signatures(Response.class));
        assertEquals(InputStream.class, Response.class.getMethod("stream", InputStream.class, String.class)
                .getParameterTypes()[0]);
    }

    // SDS-AC-1 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-9.sds.md; IR-SSE-001 AC-1
    @Test
    public void sdsAc1_IR_SSE_001_AC1_sseIsADefaultMethodThatDefaultResponseOverrides() throws Exception {
        java.lang.reflect.Method sse = Response.class.getMethod("sse");
        assertTrue(sse.isDefault());
        assertEquals(SseClient.class, sse.getReturnType());
        assertFalse(sse.getAnnotatedReturnType().isAnnotationPresent(Nullable.class));
        java.lang.reflect.Method override = com.snoworca.naruserve.internal.DefaultResponse.class
                .getDeclaredMethod("sse");
        assertEquals(SseClient.class, override.getReturnType());
        assertTrue(Modifier.isPublic(override.getModifiers()));
        try {
            new BareResponse().sse();
            fail("the default sse() returned");
        } catch (UnsupportedOperationException e) {
            assertEquals(BareResponse.class.getName() + " has no SSE writer", e.getMessage());
        }
    }

    /** A Response that overrides no default method. */
    private static final class BareResponse implements Response {
        @Override
        public Response status(int code) {
            return this;
        }

        @Override
        public Response header(String name, String value) {
            return this;
        }

        @Override
        public void send(@Nullable Object body) {
        }

        @Override
        public void html(String body) {
        }

        @Override
        public void stream(InputStream in, String contentType) {
        }

        @Override
        public void end() {
        }

        @Override
        public boolean isCommitted() {
            return false;
        }

        @Override
        public void abort(@Nullable Throwable error) {
        }
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
    // SDS-AC-11 (wave 5-3); FR-COOKIE-002 AC-8: cookie, getCookies and addCookie are expected from wave 5;
    // IR-MULTIPART-002 AC-6: form, file, files and parts are expected from wave 6; IR-STATIC-002 AC-5: cache and
    // download too
    @Test
    public void sdsAc2_IR_HTTP_001_AC3_laterWaveMethodNamesAreAbsent() {
        for (Class<?> type : new Class<?>[] {Request.class, Response.class}) {
            for (java.lang.reflect.Method m : type.getMethods()) {
                assertFalse(type.getSimpleName() + "." + m.getName(), LATER_WAVE_NAMES.contains(m.getName()));
            }
        }
    }

    // SDS-AC-3; IR-HTTP-001 AC-4; IR-HTTP-005 AC-5; SDS-AC-11 (wave 5-3); FR-COOKIE-002 AC-8; IR-MULTIPART-002 AC-6;
    // IR-STATIC-002 AC-5: the parameter of defaultCache is the only new RestApp @Nullable position
    @Test
    public void sdsAc3_IR_HTTP_001_AC4_packageNullMarkedAndNullableOnlyOnListedPositions() throws Exception {
        assertNotNull(Request.class.getPackage().getAnnotation(NullMarked.class));
        assertEquals(setOf(
                // CON-APP-010 AC-5; FR-HTTP-027 AC-1 AC-6
                "Request.paramOrNull[class java.lang.String]#return",
                "Request.param[class java.lang.String, class java.lang.String]#p1",
                "Request.query[class java.lang.String]#return",
                "Request.header[class java.lang.String]#return",
                "Request.lastEventId[]#return",
                "Request.attributeAs[class java.lang.String, class java.lang.Class]#return",
                "Request.attribute[class java.lang.String, class java.lang.Object]#p1",
                "Response.abort[class java.lang.Throwable]#p0",
                "Response.send[class java.lang.Object]#p0",
                // wave 5: IR-JSON-002 AC-8
                "Request.body[class java.lang.Class]#return",
                "Request.body[class com.snoworca.naruserve.TypeRef]#return",
                "Response.json[class java.lang.Object]#p0",
                // wave 5: FR-COOKIE-002 AC-8
                "Request.cookie[class java.lang.String]#return",
                // wave 6: IR-MULTIPART-002 AC-6
                "Request.form[class java.lang.String]#return",
                "Request.file[class java.lang.String]#return",
                "UploadFile.filename[]#return",
                "UploadFile.contentType[]#return"),
                nullablePositions(Request.class, Response.class, UploadFile.class));
        assertEquals(setOf("RestApp.defaultCache[class com.snoworca.naruserve.CachePolicy]#p0"),
                nullablePositions(RestApp.class, RouteBuilder.class, RouteHandler.class));
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
    // SDS-AC-3 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-5-4.sds.md; IR-ENGINE-001 AC-7: the wave 5 set;
    // IR-STATIC-002 AC-5: the wave 6 set
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
                "group(String,Configurer)->RestApp", // FR-DX-009 AC-1
                "cors(Configurer)->RestApp", // FR-DX-010 AC-1
                "routerPolicy(RouterPolicy)->RestApp",
                // wave 4: IR-APP-001, FR-APP-001
                "builder()->Builder", "create()->RestApp",
                // FR-DX-012 AC-1: the one-call entry point (IR-ENGINE-001 AC-7, IR-STATIC-002 AC-5, IR-APP-002 AC-2)
                "start(int,Configurer)->ServerHandle",
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
                "server(Configurer)->RestApp", "trustProxy(boolean)->RestApp",
                // wave 6: IR-MULTIPART-001 AC-1, AC-4
                "multipart(Configurer)->RestApp", "formLimit(long)->RestApp",
                // wave 6: IR-STATIC-001 AC-1, IR-STATIC-002 AC-2, AC-4
                "staticFiles(String,File)->RestApp", "staticFiles(String,File,Configurer)->RestApp",
                "mimeTypes()->MimeTypes", "defaultCache(CachePolicy)->RestApp",
                // wave 7: IR-WS-001 AC-1, AC-6
                "ws(String,WebSocketHandler)->RestApp", "webSocket(Configurer)->RestApp",
                // wave 7: IR-SSE-001 AC-4
                "sse(Configurer)->RestApp"), signatures(RestApp.class));
        // CON-APP-010 AC-5; FR-APP-021 AC-3: inherited methods are checked exhaustively below.
        assertEquals(Arrays.<Class<?>>asList(HttpRoutes.class), Arrays.asList(RestApp.class.getInterfaces()));
        assertEquals("com.snoworca.naruserve.HttpRoutes<com.snoworca.naruserve.RestApp>",
                RestApp.class.getGenericInterfaces()[0].getTypeName());
        List<String> statics = new java.util.ArrayList<>();
        for (java.lang.reflect.Method m : RestApp.class.getDeclaredMethods()) {
            if (Modifier.isStatic(m.getModifiers()) && !m.isSynthetic()) {
                statics.add(m.getName());
            }
        }
        java.util.Collections.sort(statics);
        assertEquals(Arrays.asList("builder", "create", "start"), statics);
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
                "use(Middleware)->RouteBuilder",
                // CON-APP-010 AC-5; FR-APP-021 AC-2; FR-APP-022 AC-6
                "method(String,Middleware[])->RouteBuilder", "get(Middleware[])->RouteBuilder",
                "post(Middleware[])->RouteBuilder", "put(Middleware[])->RouteBuilder",
                "patch(Middleware[])->RouteBuilder", "delete(Middleware[])->RouteBuilder",
                "head(Middleware[])->RouteBuilder", "options(Middleware[])->RouteBuilder"), signatures(RouteBuilder.class));
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
        // CON-APP-010 AC-3 AC-5; FR-HTTP-028 AC-5: unmatched method falls through, handlers remain uncalled.
        InMemoryHttpAdapter.ClientResponse miss = t.send("POST", "/u/1");
        assertEquals(404, miss.status());
        assertEquals("text/html; charset=utf-8", miss.header("Content-Type"));
        assertTrue(miss.text(), miss.text().contains("Cannot POST /u/1"));
        assertEquals(3, calls.size());
    }

    // SDS-AC-5 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-4-3.sds.md; FR-APP-002 AC-5 (re-points FR-HTTP-008 AC-6)
    @Test
    public void sdsAc5_FR_APP_002_AC5_builderAndCreateAreTheOnlyPublicStaticRestAppFactoriesOfRestApp() {
        // FR-DX-012: start(int, Configurer) is a public static too but returns a ServerHandle, not a RestApp or its
        // builder, so the AC (the only statics that return a RestApp or its Builder) stays true.
        Set<String> factories = new TreeSet<>();
        for (java.lang.reflect.Method m : RestApp.class.getDeclaredMethods()) {
            if (Modifier.isPublic(m.getModifiers()) && Modifier.isStatic(m.getModifiers())
                    && (RestApp.class.isAssignableFrom(m.getReturnType())
                            || RestApp.Builder.class.isAssignableFrom(m.getReturnType()))) {
                factories.add(m.getName() + "->" + m.getReturnType().getSimpleName());
            }
        }
        assertEquals(setOf("builder->Builder", "create->RestApp"), factories);
    }
    // CON-APP-010 AC-5; FR-APP-021 AC-3; FR-APP-023 AC-1; FR-ROUTE-020/021; FR-HTTP-028 AC-1
    @Test
    public void commonHttpInheritedSurfaceIsAnExactVersionedInventory() throws Exception {
        assertEquals(setOf(
                "route(Object)->RouteBuilder", "method(String,Object,Middleware[])->Object",
                "use(Object,Middleware)->Object", "use(Middleware)->Object",
                "use(Object,Router)->Object", "use(Router)->Object",
                "use(Object,ErrorMiddleware)->Object", "use(ErrorMiddleware)->Object",
                "param(String,ParamHandler)->Object",
                "methodAsync(String,Object,AsyncRouteHandler)->Object", "getAsync(Object,AsyncRouteHandler)->Object",
                "useAsync(Object,AsyncRouteHandler)->Object", "useAsync(AsyncRouteHandler)->Object",
                "get(Object,Middleware[])->Object", "post(Object,Middleware[])->Object",
                "put(Object,Middleware[])->Object", "patch(Object,Middleware[])->Object",
                "delete(Object,Middleware[])->Object", "head(Object,Middleware[])->Object", "options(Object,Middleware[])->Object",
                "get(Object,RouteHandler)->Object", "post(Object,RouteHandler)->Object",
                "put(Object,RouteHandler)->Object", "patch(Object,RouteHandler)->Object",
                "delete(Object,RouteHandler)->Object", "head(Object,RouteHandler)->Object", "options(Object,RouteHandler)->Object",
                "terminal(RouteHandler)->Middleware"), signatures(HttpRoutes.class));
        assertEquals("java.util.List<java.lang.String>", Request.class.getMethod("paramValues", String.class).getGenericReturnType().getTypeName());
        assertEquals("java.util.Map<java.lang.String, java.lang.Object>", Request.class.getMethod("pathParams").getGenericReturnType().getTypeName());
        assertTrue(RestApp.class.getMethod("method", String.class, Object.class, Middleware[].class).isVarArgs());
        assertTrue(RouteBuilder.class.getMethod("method", String.class, Middleware[].class).isVarArgs());
        assertEquals(RouteBuilder.class, RestApp.class.getMethod("route", Object.class).getReturnType());
    }

}
