package com.snoworca.naruserve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.internal.DefaultRestApp;
import com.snoworca.naruserve.internal.DefaultWebSocketOptions;
import com.snoworca.naruserve.testing.TestApp;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.junit.Test;

/** app.ws, WebSocketHandler, WebSocketSession and WebSocketOptions. SDS-AC-n cites wave-7-2. */
public class WebSocketApiTest {

    private final WebSocketHandler h = (req, ws) -> { };

    /** "name(paramSimpleNames)->returnSimpleName" for every non-synthetic method a type declares. */
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

    private static DefaultWebSocketOptions options(TestApp t) {
        return ((DefaultRestApp) t.app()).webSocketOptions();
    }

    private static void assertIae(String message, Runnable r) {
        try {
            r.run();
            fail("accepted: " + message);
        } catch (IllegalArgumentException e) {
            assertEquals(message, e.getMessage());
        }
    }

    private static void assertNpe(Runnable r) {
        try {
            r.run();
            fail("null accepted");
        } catch (NullPointerException expected) {
            // expected
        }
    }

    // Compiles only when a literal null second argument resolves to the read overload (which returns a value).
    @SuppressWarnings("unused")
    private static @Nullable Object readWithLiteralNull(WebSocketSession ws) {
        return ws.attribute("k", null);
    }

    // IR-WS-001 AC-1
    @Test
    public void IR_WS_001_AC1_wsReturnsTheAppAndWebSocketHandlerIsASingleMethodFunctionalInterface() throws Exception {
        TestApp t = TestApp.create();
        assertSame(t.app(), t.app().ws("/ws/rooms/:room", (req, ws) -> ws.sendText(req.param("room"))));
        java.lang.reflect.Method ws = RestApp.class.getMethod("ws", String.class, WebSocketHandler.class);
        assertEquals(RestApp.class, ws.getReturnType());
        assertTrue(WebSocketHandler.class.isInterface());
        assertTrue(Modifier.isPublic(WebSocketHandler.class.getModifiers()));
        assertNotNull(WebSocketHandler.class.getAnnotation(FunctionalInterface.class));
        assertEquals(setOf("handle(Request,WebSocketSession)->void"), signatures(WebSocketHandler.class));
        java.lang.reflect.Method handle = WebSocketHandler.class.getMethod("handle", Request.class,
                WebSocketSession.class);
        assertEquals(Collections.<Class<?>>singletonList(Exception.class), Arrays.asList(handle.getExceptionTypes()));
    }

    // SDS-AC-1; IR-WS-001 AC-2
    @Test
    public void sdsAc1_IR_WS_001_AC2_wsPathFollowsTheRoutePatternSyntax() {
        TestApp t = TestApp.create();
        t.app().ws("/a/:id", h);
        // A plain GET on a WebSocket-only route is answered 426; an unmatched path is 404.
        assertEquals(426, t.send("GET", "/a/1").status());
        assertEquals(404, t.send("GET", "/a").status());
    }

    // SDS-AC-1; IR-WS-001 AC-2
    @Test
    public void sdsAc1_IR_WS_001_AC2_anInvalidPatternThrowsWhatAppGetThrowsAndNullsAreRefused() {
        String bad = "/a/:id/:id";
        Class<?> fromGet = null;
        try {
            TestApp.create().app().get(bad, (req, res) -> { });
            fail("app.get accepted " + bad);
        } catch (RuntimeException e) {
            fromGet = e.getClass();
        }
        try {
            TestApp.create().app().ws(bad, h);
            fail("app.ws accepted " + bad);
        } catch (RuntimeException e) {
            assertEquals(fromGet, e.getClass());
        }
        RestApp app = TestApp.create().app();
        assertNpe(() -> app.ws(null, h));
        assertNpe(() -> app.ws("/a", null));
    }

    // SDS-AC-2; IR-WS-001 AC-3
    @Test
    public void sdsAc2_IR_WS_001_AC3_wsAndWebSocketAfterListenThrowIllegalStateBeforeTheNullChecks() {
        TestApp t = TestApp.create();
        t.start();
        try {
            t.app().ws(null, null);
            fail("ws accepted after listen()");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("ws"));
        }
        try {
            t.app().ws("/late", h);
            fail("ws accepted after listen()");
        } catch (IllegalStateException expected) {
            // expected
        }
        AtomicBoolean ran = new AtomicBoolean();
        try {
            t.app().webSocket(o -> {
                ran.set(true);
                o.maxMessageSize(1);
            });
            fail("webSocket accepted after listen()");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("webSocket"));
        }
        try {
            t.app().webSocket(null);
            fail("webSocket(null) accepted after listen()");
        } catch (IllegalStateException expected) {
            // expected
        }
        assertFalse(ran.get());
        assertEquals(1048576, options(t).maxMessageSize());
        assertEquals(404, t.send("GET", "/late").status());
    }

    // IR-WS-001 AC-4
    @Test
    public void IR_WS_001_AC4_webSocketSessionDeclaresExactlyTheListedMethods() throws Exception {
        assertTrue(WebSocketSession.class.isInterface());
        assertTrue(Modifier.isPublic(WebSocketSession.class.getModifiers()));
        assertEquals(setOf("id()->String", "sendText(String)->void", "sendBinary(byte[])->void",
                "sendJson(Object)->void", "ping()->void", "close()->void", "close(int,String)->void",
                "isOpen()->boolean", "onText(TextListener)->void", "onBinary(BinaryListener)->void",
                "onJson(Class,JsonListener)->void", "onClose(CloseListener)->void", "onError(ErrorListener)->void",
                "attribute(String,Object)->void", "attribute(String,Class)->Object"),
                signatures(WebSocketSession.class));
        java.lang.reflect.Method write = WebSocketSession.class.getMethod("attribute", String.class, Object.class);
        assertTrue(write.getAnnotatedParameterTypes()[1].isAnnotationPresent(Nullable.class));
        java.lang.reflect.Method read = WebSocketSession.class.getMethod("attribute", String.class, Class.class);
        assertEquals(1, read.getTypeParameters().length);
        assertEquals(read.getTypeParameters()[0], read.getGenericReturnType());
        assertTrue(read.getAnnotatedReturnType().isAnnotationPresent(Nullable.class));
        java.lang.reflect.Method sendJson = WebSocketSession.class.getMethod("sendJson", Object.class);
        assertTrue(sendJson.getAnnotatedParameterTypes()[0].isAnnotationPresent(Nullable.class));
        java.lang.reflect.Method onJson = WebSocketSession.class.getMethod("onJson", Class.class,
                WebSocketSession.JsonListener.class);
        assertEquals(1, onJson.getTypeParameters().length);
    }

    // IR-WS-001 AC-4: close() equals close(1000, "")
    @Test
    public void IR_WS_001_AC4_closeWithoutArgumentsIsCloseNormal() {
        List<String> calls = new ArrayList<>();
        WebSocketSession ws = new RecordingSession(calls);
        ws.close();
        assertEquals(Collections.singletonList("close(1000,)"), calls);
    }

    // IR-WS-001 AC-5 (reflection part; the two-listener order is the session's, FR-WS-006)
    @Test
    public void IR_WS_001_AC5_listenersAreFunctionalInterfacesThatThrowException() throws Exception {
        Object[][] listeners = {
            {WebSocketSession.TextListener.class, "onText", new Class<?>[] {String.class}},
            {WebSocketSession.BinaryListener.class, "onBinary", new Class<?>[] {byte[].class}},
            {WebSocketSession.JsonListener.class, "onJson", new Class<?>[] {Object.class}},
            {WebSocketSession.CloseListener.class, "onClose", new Class<?>[] {int.class, String.class}},
            {WebSocketSession.ErrorListener.class, "onError", new Class<?>[] {Throwable.class}},
        };
        for (Object[] l : listeners) {
            Class<?> type = (Class<?>) l[0];
            assertNotNull(type.getSimpleName(), type.getAnnotation(FunctionalInterface.class));
            assertEquals(type.getSimpleName(), 1, type.getDeclaredMethods().length);
            java.lang.reflect.Method m = type.getMethod((String) l[1], (Class<?>[]) l[2]);
            assertEquals(void.class, m.getReturnType());
            assertEquals(Collections.<Class<?>>singletonList(Exception.class), Arrays.asList(m.getExceptionTypes()));
        }
        AnnotatedType value = WebSocketSession.JsonListener.class.getMethod("onJson", Object.class)
                .getAnnotatedParameterTypes()[0];
        assertTrue(value.isAnnotationPresent(Nullable.class));
    }

    // IR-WS-001 AC-6
    @Test
    public void IR_WS_001_AC6_webSocketReturnsTheAppAndEverySetterReturnsTheOptions() throws Exception {
        TestApp t = TestApp.create();
        assertSame(t.app(), t.app().webSocket(o -> o.allowedOrigins("https://a.example").maxMessageSize(4096)
                .sendQueueSize(8192).pingInterval(2, TimeUnit.SECONDS).idleTimeout(10, TimeUnit.MINUTES)));
        assertEquals(RestApp.class, RestApp.class.getMethod("webSocket", Configurer.class).getReturnType());
        assertTrue(WebSocketOptions.class.isInterface());
        assertTrue(Modifier.isPublic(WebSocketOptions.class.getModifiers()));
        assertEquals(setOf("allowedOrigins(String[])->WebSocketOptions", "maxMessageSize(int)->WebSocketOptions",
                "sendQueueSize(int)->WebSocketOptions", "pingInterval(long,TimeUnit)->WebSocketOptions",
                "idleTimeout(long,TimeUnit)->WebSocketOptions"), signatures(WebSocketOptions.class));
        DefaultWebSocketOptions o = options(t);
        assertEquals(Collections.singletonList("https://a.example"), o.origins());
        assertEquals(4096, o.maxMessageSize());
        assertEquals(8192, o.sendQueueSize());
        assertEquals(2000L, o.pingIntervalMillis());
        assertEquals(600000L, o.idleTimeoutMillis());
    }

    // SDS-AC-3; IR-WS-001 AC-6
    @Test
    public void sdsAc3_IR_WS_001_AC6_aThrowingConfigurerKeepsEveryEarlierSetting() {
        TestApp t = TestApp.create();
        t.app().webSocket(o -> o.maxMessageSize(2048));
        IllegalStateException boom = new IllegalStateException("boom");
        try {
            t.app().webSocket(o -> {
                o.maxMessageSize(10).idleTimeout(5, TimeUnit.SECONDS);
                throw boom;
            });
            fail("configurer exception swallowed");
        } catch (IllegalStateException e) {
            assertSame(boom, e);
        }
        assertEquals(2048, options(t).maxMessageSize());
        assertEquals(300000L, options(t).idleTimeoutMillis());
    }

    // SDS-AC-5; IR-WS-001 AC-7
    @Test
    public void sdsAc5_IR_WS_001_AC7_defaultsAndAllowedOriginsReplacesAndResets() {
        TestApp t = TestApp.create();
        DefaultWebSocketOptions d = options(t);
        assertEquals(Collections.<String>emptyList(), d.origins());
        assertEquals(1048576, d.maxMessageSize());
        assertEquals(1048576, d.sendQueueSize());
        assertEquals(30000L, d.pingIntervalMillis());
        assertEquals(300000L, d.idleTimeoutMillis());

        t.app().webSocket(o -> o.allowedOrigins("https://a.example"));
        t.app().webSocket(o -> o.allowedOrigins("https://b.example"));
        assertEquals(Collections.singletonList("https://b.example"), options(t).origins());
        t.app().webSocket(o -> o.allowedOrigins("https://c.example", "https://a.example"));
        assertEquals(Arrays.asList("https://c.example", "https://a.example"), options(t).origins());
        t.app().webSocket(o -> o.allowedOrigins());
        assertEquals(Collections.<String>emptyList(), options(t).origins());
    }

    // SDS-AC-5; IR-WS-001 AC-7, AC-8
    @Test
    public void sdsAc5_IR_WS_001_AC7_AC8_aNullOriginKeepsThePreviousListAndTheArrayIsCopied() {
        TestApp t = TestApp.create();
        String[] origins = {"https://a.example"};
        t.app().webSocket(o -> o.allowedOrigins(origins));
        origins[0] = "https://evil.example";
        assertEquals(Collections.singletonList("https://a.example"), options(t).origins());
        DefaultWebSocketOptions o = options(t);
        assertNpe(() -> o.allowedOrigins("https://b.example", null));
        assertNpe(() -> o.allowedOrigins((String[]) null));
        assertEquals(Collections.singletonList("https://a.example"), o.origins());
        try {
            o.origins().add("x");
            fail("origins() is modifiable");
        } catch (UnsupportedOperationException expected) {
            // expected
        }
    }

    // IR-WS-001 AC-8
    @Test
    public void IR_WS_001_AC8_sizesAreBoundedWithTheSettingNameInTheMessage() {
        DefaultWebSocketOptions o = options(TestApp.create());
        assertIae("maxMessageSize must be in 1..16777216: 0", () -> o.maxMessageSize(0));
        assertIae("maxMessageSize must be in 1..16777216: -1", () -> o.maxMessageSize(-1));
        assertIae("maxMessageSize must be in 1..16777216: 16777217", () -> o.maxMessageSize(16777217));
        assertIae("sendQueueSize must be in 1..67108864: 0", () -> o.sendQueueSize(0));
        assertIae("sendQueueSize must be in 1..67108864: 67108865", () -> o.sendQueueSize(67108865));
        o.maxMessageSize(1).maxMessageSize(16777216).sendQueueSize(1).sendQueueSize(67108864);
        assertEquals(16777216, o.maxMessageSize());
        assertEquals(67108864, o.sendQueueSize());
    }

    // SDS-AC-4; IR-WS-001 AC-8
    @Test
    public void sdsAc4_IR_WS_001_AC8_durationsAreBoundedWithTheCallersTimeAndUnitInTheMessage() {
        DefaultWebSocketOptions o = options(TestApp.create());
        assertIae("pingInterval must be in 1 s..1 h: 0 SECONDS", () -> o.pingInterval(0, TimeUnit.SECONDS));
        assertIae("pingInterval must be in 1 s..1 h: 999 MILLISECONDS", () -> o.pingInterval(999, TimeUnit.MILLISECONDS));
        assertIae("pingInterval must be in 1 s..1 h: 2 HOURS", () -> o.pingInterval(2, TimeUnit.HOURS));
        assertIae("pingInterval must be in 1 s..1 h: " + Long.MAX_VALUE + " DAYS",
                () -> o.pingInterval(Long.MAX_VALUE, TimeUnit.DAYS));
        assertIae("pingInterval must be in 1 s..1 h: -1 SECONDS", () -> o.pingInterval(-1, TimeUnit.SECONDS));
        assertIae("idleTimeout must be in 1 s..24 h: 0 SECONDS", () -> o.idleTimeout(0, TimeUnit.SECONDS));
        assertIae("idleTimeout must be in 1 s..24 h: 25 HOURS", () -> o.idleTimeout(25, TimeUnit.HOURS));
        assertIae("idleTimeout must be in 1 s..24 h: " + Long.MAX_VALUE + " DAYS",
                () -> o.idleTimeout(Long.MAX_VALUE, TimeUnit.DAYS));
        assertIae("idleTimeout must be in 1 s..24 h: 86400001 MILLISECONDS",
                () -> o.idleTimeout(86400001, TimeUnit.MILLISECONDS));
        assertIae("pingInterval must be in 1 s..1 h: 3600000000001 NANOSECONDS",
                () -> o.pingInterval(3600000000001L, TimeUnit.NANOSECONDS));
        assertNpe(() -> o.pingInterval(1, null));
        assertNpe(() -> o.idleTimeout(1, null));
        o.pingInterval(1, TimeUnit.SECONDS);
        assertEquals(1000L, o.pingIntervalMillis());
        o.pingInterval(1, TimeUnit.HOURS);
        assertEquals(3600000L, o.pingIntervalMillis());
        o.idleTimeout(1000, TimeUnit.MILLISECONDS);
        assertEquals(1000L, o.idleTimeoutMillis());
        o.idleTimeout(24, TimeUnit.HOURS);
        assertEquals(86400000L, o.idleTimeoutMillis());
    }

    // IR-WS-001 AC-9
    @Test
    public void IR_WS_001_AC9_noHandshakeOrPongTimeoutSetter() {
        for (Class<?> type : new Class<?>[] {WebSocketOptions.class, RestApp.class}) {
            for (java.lang.reflect.Method m : type.getMethods()) {
                String name = m.getName().toLowerCase(Locale.ROOT);
                assertFalse(type.getSimpleName() + "." + m.getName(), name.contains("handshake"));
                assertFalse(type.getSimpleName() + "." + m.getName(), name.contains("pong"));
            }
        }
    }

    // SDS-AC-9 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-6.sds.md; FR-WS-008 AC-3, FR-WS-008 AC-7: no
    // pong-timeout and no handshake-timeout setting on either options interface
    @Test
    public void sdsAc9_FR_WS_008_AC3_AC7_noPongOrHandshakeMethodOnWebSocketOptionsOrServerOptions() {
        int checked = 0;
        for (Class<?> type : new Class<?>[] {WebSocketOptions.class, ServerOptions.class}) {
            for (java.lang.reflect.Method m : type.getMethods()) {
                String name = m.getName().toLowerCase(Locale.ROOT);
                assertFalse(type.getSimpleName() + "." + m.getName(), name.contains("pong"));
                assertFalse(type.getSimpleName() + "." + m.getName(), name.contains("handshake"));
                checked++;
            }
        }
        assertTrue(checked > 0);
    }

    /** Hand-written session that records close calls; the other methods are never called here. */
    private static final class RecordingSession implements WebSocketSession {
        private final List<String> calls;

        RecordingSession(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public String id() {
            return "s";
        }

        @Override
        public void sendText(String text) {
            calls.add("sendText");
        }

        @Override
        public void sendBinary(byte[] data) {
            calls.add("sendBinary");
        }

        @Override
        public void sendJson(@Nullable Object value) {
            calls.add("sendJson");
        }

        @Override
        public void ping() {
            calls.add("ping");
        }

        @Override
        public void close(int code, String reason) {
            calls.add("close(" + code + "," + reason + ")");
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public void onText(TextListener listener) {
            calls.add("onText");
        }

        @Override
        public void onBinary(BinaryListener listener) {
            calls.add("onBinary");
        }

        @Override
        public <T> void onJson(Class<T> type, JsonListener<T> listener) {
            calls.add("onJson");
        }

        @Override
        public void onClose(CloseListener listener) {
            calls.add("onClose");
        }

        @Override
        public void onError(ErrorListener listener) {
            calls.add("onError");
        }

        @Override
        public void attribute(String key, @Nullable Object value) {
            calls.add("attribute");
        }

        @Override
        public <T> @Nullable T attribute(String key, Class<T> type) {
            return null;
        }
    }
}
