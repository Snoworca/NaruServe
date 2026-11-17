package com.snoworca.naruserve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.internal.DefaultRestApp;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.StubJsonCodec;
import com.snoworca.naruserve.testing.TestApp;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.NullUnmarked;
import org.junit.Test;

/** The app-wide raw body limit {@code app.rawBodyLimit(bytes)} that {@code req.bodyStream()} reads under. */
public class RawBodyLimitTest {

    private static final long MIB = 1048576L;

    private static DefaultRestApp newApp() {
        return (DefaultRestApp) RestApp.builder().httpAdapter(new InMemoryHttpAdapter())
                .jsonCodec(new StubJsonCodec()).build();
    }

    /**
     * Registers {@code POST /raw}, which drains {@code req.bodyStream()} and answers {@code "eof <n>"}, or
     * {@code "tooLarge <n> <limit>"} when a read throws {@link BodyTooLargeException} after {@code n} bytes.
     */
    private static void drainRoute(RestApp app) {
        app.post("/raw", (req, res) -> {
            long n = 0;
            byte[] buf = new byte[8192];
            try (InputStream in = req.bodyStream()) {
                int r;
                while ((r = in.read(buf)) != -1) {
                    n += r;
                }
                res.send("eof " + n);
            } catch (BodyTooLargeException e) {
                res.send("tooLarge " + n + " " + e.limit());
            } catch (IOException e) {
                res.send("io " + n + " " + e);
            }
        });
    }

    /** As {@link #drainRoute} on {@code POST /byte}, draining with the single-byte {@code read()}. */
    private static void drainByteRoute(RestApp app) {
        app.post("/byte", (req, res) -> {
            long n = 0;
            try (InputStream in = req.bodyStream()) {
                while (in.read() != -1) {
                    n++;
                }
                res.send("eof " + n);
            } catch (BodyTooLargeException e) {
                res.send("tooLarge " + n + " " + e.limit());
            } catch (IOException e) {
                res.send("io " + n + " " + e);
            }
        });
    }

    /** POSTs {@code size} bytes to {@code /raw} without a Content-Length header. */
    private static ClientResponse postWithoutLength(TestApp t, InMemoryHttpAdapter a, int size) {
        return postWithoutLength(t, a, "/raw", size);
    }

    /** POSTs {@code size} bytes to {@code path} without a Content-Length header. */
    private static ClientResponse postWithoutLength(TestApp t, InMemoryHttpAdapter a, String path, int size) {
        t.start();
        byte[] body = new byte[size];
        Arrays.fill(body, (byte) 'x');
        return a.send(ClientRequest.of("POST", path).body(body));
    }

    // IR-APP-009 AC-1: RestApp declares the public abstract instance method RestApp rawBodyLimit(long)
    @Test
    public void IR_APP_009_AC1_restAppDeclaresRawBodyLimitReturningRestApp() throws Exception {
        java.lang.reflect.Method m = RestApp.class.getDeclaredMethod("rawBodyLimit", long.class);
        assertEquals(RestApp.class, m.getReturnType());
        assertTrue(Modifier.isPublic(m.getModifiers()));
        assertTrue(!Modifier.isStatic(m.getModifiers()));
    }

    // IR-APP-009 AC-1: rawBodyLimit returns the receiver
    @Test
    public void IR_APP_009_AC1_rawBodyLimitReturnsTheReceiver() {
        RestApp app = newApp();
        assertSame(app, app.rawBodyLimit(2048L));
    }

    // IR-APP-009 AC-2: with no rawBodyLimit call, bodyStream() reads a 1048576-byte body without Content-Length to
    // its end
    @Test
    public void IR_APP_009_AC2_defaultLimitReadsOneMebibyteWithoutContentLengthToTheEnd() {
        InMemoryHttpAdapter a = new InMemoryHttpAdapter();
        TestApp t = TestApp.create(a);
        drainRoute(t.app());
        ClientResponse r = postWithoutLength(t, a, (int) MIB);
        assertEquals(r.text(), 200, r.status());
        assertEquals("eof " + MIB, r.text());
    }

    // IR-APP-009 AC-2: with no rawBodyLimit call, the read that would return byte 1048577 throws
    // BodyTooLargeException with limit() 1048576, after exactly 1048576 bytes were handed out
    @Test
    public void IR_APP_009_AC2_defaultLimitThrowsBodyTooLargeOnTheReadOfByte1048577() {
        InMemoryHttpAdapter a = new InMemoryHttpAdapter();
        TestApp t = TestApp.create(a);
        drainRoute(t.app());
        ClientResponse r = postWithoutLength(t, a, (int) MIB + 1);
        assertEquals(r.text(), 200, r.status());
        assertEquals("tooLarge " + MIB + " " + MIB, r.text());
    }

    // IR-APP-009 AC-1 (requirement statement: the value rawBodyLimit records is the limit of req.bodyStream())
    @Test
    public void IR_APP_009_AC1_configuredLimitIsTheLimitOfBodyStream() {
        InMemoryHttpAdapter a = new InMemoryHttpAdapter();
        TestApp t = TestApp.create(a);
        t.app().rawBodyLimit(10L);
        drainRoute(t.app());
        drainByteRoute(t.app());
        assertEquals("eof 10", postWithoutLength(t, a, 10).text());
        assertEquals("tooLarge 10 10", postWithoutLength(t, a, 11).text());
        // the single-byte read() enforces the same limit as the array read
        assertEquals("eof 10", postWithoutLength(t, a, "/byte", 10).text());
        assertEquals("tooLarge 10 10", postWithoutLength(t, a, "/byte", 11).text());
    }

    // IR-APP-009 AC-2: with no rawBodyLimit call, the single-byte read() that would return byte 1048577 throws
    // BodyTooLargeException with limit() 1048576, after exactly 1048576 bytes were handed out
    @Test
    public void IR_APP_009_AC2_defaultLimitSingleByteReadThrowsOnByte1048577() {
        InMemoryHttpAdapter a = new InMemoryHttpAdapter();
        TestApp t = TestApp.create(a);
        drainByteRoute(t.app());
        assertEquals("eof " + MIB, postWithoutLength(t, a, "/byte", (int) MIB).text());
        assertEquals("tooLarge " + MIB + " " + MIB, postWithoutLength(t, a, "/byte", (int) MIB + 1).text());
    }

    // IR-APP-009 AC-3: 0, -1 and 1073741825 throw IllegalArgumentException and leave the limit unchanged;
    // 1 and 1073741824 are accepted
    @Test
    public void IR_APP_009_AC3_rangeIsOneToOneGibibyte() {
        DefaultRestApp app = newApp();
        assertEquals(MIB, app.rawBodyLimit());
        for (long bad : new long[] {0, -1, 1073741825L}) {
            try {
                app.rawBodyLimit(bad);
                fail("rawBodyLimit(" + bad + ") must throw");
            } catch (IllegalArgumentException e) {
                assertTrue(e.getMessage(), e.getMessage().contains("rawBodyLimit"));
                assertTrue(e.getMessage(), e.getMessage().contains("1..1073741824"));
            }
        }
        assertEquals(MIB, app.rawBodyLimit());
        assertSame(app, app.rawBodyLimit(1));
        assertEquals(1L, app.rawBodyLimit());
        assertSame(app, app.rawBodyLimit(1073741824L));
        assertEquals(1073741824L, app.rawBodyLimit());
    }

    // IR-APP-009 AC-4: outside NEW rawBodyLimit throws IllegalStateException before it records anything, so even an
    // out-of-range value is refused for the state and the limit stays as it was
    @Test
    public void IR_APP_009_AC4_rawBodyLimitOutsideNewThrowsIllegalStateAndChangesNothing() {
        DefaultRestApp app = newApp();
        app.rawBodyLimit(4096L);
        app.listen(0);
        try {
            for (long value : new long[] {5L, 0L, 1073741825L}) {
                try {
                    app.rawBodyLimit(value);
                    fail("rawBodyLimit(" + value + ") in RUNNING must throw");
                } catch (IllegalStateException e) {
                    assertTrue(e.getMessage(), e.getMessage().contains("rawBodyLimit"));
                    assertTrue(e.getMessage(), e.getMessage().contains("RUNNING"));
                }
                assertEquals(4096L, app.rawBodyLimit());
            }
        } finally {
            app.shutdown(5, TimeUnit.SECONDS);
        }
        assertEquals(AppState.TERMINATED, app.state());
        try {
            app.rawBodyLimit(5L);
            fail("rawBodyLimit(5) in TERMINATED must throw");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("rawBodyLimit"));
            assertTrue(e.getMessage(), e.getMessage().contains("TERMINATED"));
        }
        assertEquals(4096L, app.rawBodyLimit());
    }

    // IR-APP-009 AC-6: rawBodyLimit stays inside the package @NullMarked scope with no nullness annotation, so Kotlin
    // sees RestApp and not the platform type RestApp! (Kotlin would also accept RestApp! in a typed declaration, so
    // RawBodyLimitKotlinTest alone cannot tell the two apart)
    @Test
    public void IR_APP_009_AC6_rawBodyLimitCarriesNoNullnessEscape() throws Exception {
        java.lang.reflect.Method m = RestApp.class.getMethod("rawBodyLimit", long.class);
        assertNotNull(RestApp.class.getPackage().getAnnotation(NullMarked.class));
        assertFalse(RestApp.class.isAnnotationPresent(NullUnmarked.class));
        assertFalse(m.isAnnotationPresent(NullUnmarked.class));
        assertEquals(0, m.getAnnotatedReturnType().getAnnotations().length);
        assertEquals(0, m.getAnnotatedParameterTypes()[0].getAnnotations().length);
        for (java.lang.annotation.Annotation a : m.getAnnotations()) {
            String simple = a.annotationType().getSimpleName();
            assertFalse(simple, simple.equals("Nullable") || simple.equals("NonNull") || simple.equals("NullUnmarked"));
        }
    }
}
