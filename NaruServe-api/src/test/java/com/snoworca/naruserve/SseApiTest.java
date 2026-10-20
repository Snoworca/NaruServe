package com.snoworca.naruserve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.internal.DefaultRestApp;
import com.snoworca.naruserve.internal.DefaultSseOptions;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.TestApp;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/** SseClient, SseEvent, SseOptions and app.sse. SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-9.sds.md. */
public class SseApiTest {

    /** "name(paramSimpleNames)->returnSimpleName" for every public non-synthetic method a type declares. */
    private static Set<String> publicSignatures(Class<?> type) {
        Set<String> out = new TreeSet<>();
        for (java.lang.reflect.Method m : type.getDeclaredMethods()) {
            if (m.isSynthetic() || !Modifier.isPublic(m.getModifiers())) {
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

    private static DefaultSseOptions options(TestApp t) {
        return ((DefaultRestApp) t.app()).sseOptions();
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

    // SDS-AC-2; IR-SSE-001 AC-3
    @Test
    public void sdsAc2_IR_SSE_001_AC3_sseEventHasNoPublicConstructorAndOnlyTheFourBuilderMethods() throws Exception {
        assertTrue(Modifier.isFinal(SseEvent.class.getModifiers()));
        assertEquals(0, SseEvent.class.getConstructors().length);
        assertEquals(setOf("event(String)->SseEvent", "id(String)->SseEvent", "data(String)->SseEvent",
                "retry(long)->SseEvent"), publicSignatures(SseEvent.class));
        assertTrue(Modifier.isStatic(SseEvent.class.getMethod("event", String.class).getModifiers()));
        java.lang.reflect.Method wire = SseEvent.class.getDeclaredMethod("wire");
        int mod = wire.getModifiers();
        assertFalse(Modifier.isPublic(mod) || Modifier.isProtected(mod) || Modifier.isPrivate(mod));
        SseEvent e = SseEvent.event("progress").id("100").data("x").retry(3000);
        assertNotNull(e);
        SseEvent same = SseEvent.event("progress");
        assertSame(same, same.id("1"));
        assertSame(same, same.data(null));
        assertSame(same, same.retry(0));
    }

    // SDS-AC-2; IR-SSE-001 AC-2
    @Test
    public void sdsAc2_IR_SSE_001_AC2_sseClientAndCloseListenerDeclareTheListedMethods() throws Exception {
        assertTrue(SseClient.class.isInterface());
        assertEquals(setOf("send(String,String)->boolean", "send(String)->boolean", "send(SseEvent)->boolean",
                "close()->void", "isOpen()->boolean", "onClose(CloseListener)->void"),
                publicSignatures(SseClient.class));
        Class<?> listener = SseClient.CloseListener.class;
        assertTrue(listener.isInterface());
        assertNotNull(listener.getAnnotation(FunctionalInterface.class));
        assertEquals(setOf("onClose()->void"), publicSignatures(listener));
        assertEquals(Collections.<Class<?>>singletonList(Exception.class),
                Arrays.asList(listener.getMethod("onClose").getExceptionTypes()));
    }

    // SDS-AC-3; IR-SSE-001 AC-4
    @Test
    public void sdsAc3_IR_SSE_001_AC4_sseAppliesTheConfigurerAndReturnsTheApp() throws Exception {
        TestApp t = TestApp.create();
        AtomicReference<SseOptions> seen = new AtomicReference<>();
        assertSame(t.app(), t.app().sse(o -> seen.set(o.heartbeatInterval(5, TimeUnit.SECONDS).sendQueueSize(1024))));
        assertSame(options(t), seen.get());
        assertEquals(5000L, options(t).heartbeatMillis());
        assertEquals(1024, options(t).sendQueueSize());
        assertEquals(RestApp.class, RestApp.class.getMethod("sse", Configurer.class).getReturnType());
        assertEquals(SseOptions.class,
                SseOptions.class.getMethod("heartbeatInterval", long.class, TimeUnit.class).getReturnType());
        assertEquals(SseOptions.class, SseOptions.class.getMethod("sendQueueSize", int.class).getReturnType());
    }

    // SDS-AC-3; IR-SSE-001 AC-4
    @Test
    public void sdsAc3_IR_SSE_001_AC4_sseAfterListenThrowsBeforeTheNullCheckAndRunsNoConfigurer() {
        TestApp t = TestApp.create();
        t.start();
        AtomicBoolean ran = new AtomicBoolean();
        try {
            t.app().sse(o -> ran.set(true));
            fail("sse accepted after listen()");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("sse"));
        }
        assertFalse(ran.get());
        try {
            t.app().sse(null);
            fail("sse(null) accepted after listen()");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("sse"));
        }
        assertNpe(() -> TestApp.create().app().sse(null));
    }

    // SDS-AC-3; IR-SSE-001 AC-4
    @Test
    public void sdsAc3_IR_SSE_001_AC4_aThrowingConfigurerKeepsBothEarlierValues() {
        TestApp t = TestApp.create();
        t.app().sse(o -> o.heartbeatInterval(2, TimeUnit.SECONDS).sendQueueSize(4096));
        DefaultSseOptions before = options(t);
        try {
            t.app().sse(o -> {
                o.heartbeatInterval(9, TimeUnit.SECONDS).sendQueueSize(8);
                throw new IllegalStateException("boom");
            });
            fail("the configurer's exception was swallowed");
        } catch (IllegalStateException e) {
            assertEquals("boom", e.getMessage());
        }
        assertSame(before, options(t));
        assertEquals(2000L, options(t).heartbeatMillis());
        assertEquals(4096, options(t).sendQueueSize());
    }

    // SDS-AC-4; IR-SSE-001 AC-6
    @Test
    public void sdsAc4_IR_SSE_001_AC6_heartbeatIntervalAcceptsOneSecondToOneHourOnly() {
        DefaultSseOptions o = new DefaultSseOptions();
        assertIae("heartbeatInterval must be in 1 s..1 h: 0 SECONDS", () -> o.heartbeatInterval(0, TimeUnit.SECONDS));
        assertIae("heartbeatInterval must be in 1 s..1 h: 999 MILLISECONDS",
                () -> o.heartbeatInterval(999, TimeUnit.MILLISECONDS));
        assertIae("heartbeatInterval must be in 1 s..1 h: -1 SECONDS", () -> o.heartbeatInterval(-1, TimeUnit.SECONDS));
        assertIae("heartbeatInterval must be in 1 s..1 h: 2 HOURS", () -> o.heartbeatInterval(2, TimeUnit.HOURS));
        assertIae("heartbeatInterval must be in 1 s..1 h: " + Long.MAX_VALUE + " DAYS",
                () -> o.heartbeatInterval(Long.MAX_VALUE, TimeUnit.DAYS));
        assertIae("heartbeatInterval must be in 1 s..1 h: 3600000001 MICROSECONDS",
                () -> o.heartbeatInterval(3600000001L, TimeUnit.MICROSECONDS));
        assertNpe(() -> o.heartbeatInterval(1, null));
        assertEquals(15000L, o.heartbeatMillis());
        assertSame(o, o.heartbeatInterval(1000, TimeUnit.MILLISECONDS));
        assertEquals(1000L, o.heartbeatMillis());
        o.heartbeatInterval(1, TimeUnit.HOURS);
        assertEquals(3600000L, o.heartbeatMillis());
    }

    // SDS-AC-4; IR-SSE-001 AC-6
    @Test
    public void sdsAc4_IR_SSE_001_AC6_sendQueueSizeAcceptsOneTo16MiBOnly() {
        DefaultSseOptions o = new DefaultSseOptions();
        assertIae("sendQueueSize must be in 1..16777216: 0", () -> o.sendQueueSize(0));
        assertIae("sendQueueSize must be in 1..16777216: -1", () -> o.sendQueueSize(-1));
        assertIae("sendQueueSize must be in 1..16777216: 16777217", () -> o.sendQueueSize(16777217));
        assertEquals(262144, o.sendQueueSize());
        assertSame(o, o.sendQueueSize(1));
        assertEquals(1, o.sendQueueSize());
        o.sendQueueSize(16777216);
        assertEquals(16777216, o.sendQueueSize());
    }

    // SDS-AC-11; FR-SSE-001 AC-6
    @Test
    public void sdsAc11_FR_SSE_001_AC6_crLfAndNulAreRefusedInNamesAndIdsAndNegativeRetryAndNullName() {
        for (String bad : new String[] {"a\rb", "a\nb", "a\u0000b"}) {
            assertIae("event must not contain CR, LF or NUL", () -> SseEvent.event(bad));
            assertIae("id must not contain CR, LF or NUL", () -> SseEvent.event("ok").id(bad));
        }
        assertIae("retry must not be negative: -1", () -> SseEvent.event("ok").retry(-1));
        assertNpe(() -> SseEvent.event(null));
    }

    // SDS-AC-11; FR-SSE-001 AC-6
    @Test
    public void sdsAc11_FR_SSE_001_AC6_sendWithABadNameThrowsOnTheCallerAndWritesNothing() {
        TestApp t = TestApp.create();
        AtomicReference<String> refused = new AtomicReference<>();
        t.app().get("/events", (req, res) -> {
            SseClient c = res.sse();
            try {
                c.send("bad\nname", "x");
                refused.set("accepted");
            } catch (IllegalArgumentException e) {
                refused.set(e.getMessage());
            }
            c.send("ok", "y");
            c.close();
        });
        ClientResponse r = t.send("GET", "/events");
        assertEquals("event must not contain CR, LF or NUL", refused.get());
        assertEquals("event: ok\ndata: y\n\n", new String(r.body(), StandardCharsets.UTF_8));
    }
}
