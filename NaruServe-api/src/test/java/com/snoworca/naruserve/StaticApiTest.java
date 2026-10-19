package com.snoworca.naruserve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.internal.DefaultRestApp;
import com.snoworca.naruserve.internal.StaticMount;
import com.snoworca.naruserve.testing.SettingsCapturingAdapter;
import com.snoworca.naruserve.testing.StubJsonCodec;
import com.snoworca.naruserve.testing.TestApp;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/** app.staticFiles(...) and StaticOptions. SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-6-5.sds.md. */
public class StaticApiTest {

    private static DefaultRestApp app() {
        return (DefaultRestApp) RestApp.builder().httpAdapter(new SettingsCapturingAdapter())
                .jsonCodec(new StubJsonCodec()).build();
    }

    private static List<String> prefixes(DefaultRestApp app) {
        List<String> out = new ArrayList<>();
        for (StaticMount m : app.staticMounts()) {
            out.add(m.prefix());
        }
        return out;
    }

    // SDS-AC-1; IR-STATIC-001 AC-1
    @Test
    public void sdsAc1_IR_STATIC_001_AC1_trailingSlashIsStrippedAndDuplicatePrefixesAreBothKept() {
        DefaultRestApp app = app();
        File a = new File("a");
        File b = new File("b");
        assertSame(app, app.staticFiles("/assets", a));
        assertSame(app, app.staticFiles("/assets/", b, o -> o.index("main.html")));
        app.staticFiles("/", a);
        assertEquals(Arrays.asList("/assets", "/assets", "/"), prefixes(app));
        assertSame(a, app.staticMounts().get(0).dir());
        assertSame(b, app.staticMounts().get(1).dir());
    }

    // IR-STATIC-001 AC-1
    @Test
    public void IR_STATIC_001_AC1_badPrefixAndNullArgumentsAreRefused() {
        DefaultRestApp app = app();
        try {
            app.staticFiles("assets", new File("a"));
            fail("prefix without '/' accepted");
        } catch (IllegalArgumentException expected) {
            // refused
        }
        List<Runnable> nulls = Arrays.<Runnable>asList(
                () -> app.staticFiles(StaticApiTest.<String>nullOf(), new File("a")),
                () -> app.staticFiles("/a", StaticApiTest.<File>nullOf()),
                () -> app.staticFiles("/a", new File("a"), StaticApiTest.<Configurer<StaticOptions>>nullOf()));
        for (Runnable r : nulls) {
            try {
                r.run();
                fail("null accepted");
            } catch (NullPointerException expected) {
                // refused
            }
        }
        assertEquals(0, app.staticMounts().size());
    }

    @SuppressWarnings({"unchecked", "TypeParameterUnusedInFormals"})
    private static <T> T nullOf() {
        return (T) (Object) null;
    }

    // SDS-AC-2; IR-STATIC-001 AC-1
    @Test
    public void sdsAc2_IR_STATIC_001_AC1_missingDirRegistersAndServesNothing() {
        TestApp t = TestApp.create();
        File missing = new File("build/no-such-static-dir-" + System.nanoTime());
        t.app().staticFiles("/", missing);
        assertEquals(404, t.send("GET", "/index.html").status());
    }

    // SDS-AC-3; IR-STATIC-001 AC-2
    @Test
    public void sdsAc3_IR_STATIC_001_AC2_badIndexAndSpaExcludeArgumentsAreRefused() {
        List<Configurer<StaticOptions>> bad = Arrays.<Configurer<StaticOptions>>asList(
                o -> o.index(""), o -> o.index("a/b"), o -> o.spaExclude("admin"));
        for (Configurer<StaticOptions> c : bad) {
            DefaultRestApp app = app();
            try {
                app.staticFiles("/", new File("a"), c);
                fail("bad option accepted");
            } catch (IllegalArgumentException expected) {
                assertEquals(0, app.staticMounts().size());
            }
        }
    }

    // IR-STATIC-001 AC-2
    @Test
    public void IR_STATIC_001_AC2_everyOptionSetterReturnsTheSameOptions() {
        DefaultRestApp app = app();
        List<Object> seen = new ArrayList<>();
        app.staticFiles("/", new File("a"), o -> {
            seen.add(o);
            seen.add(o.index("main.html"));
            seen.add(o.directoryListing(true));
            seen.add(o.spaFallback(true));
            seen.add(o.spaExclude("/admin", "/x"));
            seen.add(o.cache("*.js", CachePolicy.noCache()));
            seen.add(o.mime(".webp", "image/webp"));
            seen.add(o.allowSymlinks(true));
        });
        assertEquals(8, seen.size());
        for (Object o : seen) {
            assertSame(seen.get(0), o);
        }
    }

    // IR-STATIC-001 AC-3; IR-STATIC-001 AC-4
    @Test
    public void IR_STATIC_001_AC3_AC4_resolverIsAcceptedNowhereAndNoSignatureNamesNioFile() {
        for (Class<?> type : new Class<?>[] {RestApp.class, StaticOptions.class, Response.class}) {
            for (java.lang.reflect.Method m : type.getMethods()) {
                for (Class<?> p : m.getParameterTypes()) {
                    assertFalse(type.getSimpleName() + "." + m.getName(), p == ResourceResolver.class);
                }
            }
        }
        for (Class<?> type : new Class<?>[] {StaticOptions.class, ResourceResolver.class, CachePolicy.class,
                MimeTypes.class}) {
            for (java.lang.reflect.Method m : type.getMethods()) {
                List<Class<?>> named = new ArrayList<>(Arrays.asList(m.getParameterTypes()));
                named.add(m.getReturnType());
                named.addAll(Arrays.asList(m.getExceptionTypes()));
                for (Class<?> c : named) {
                    assertFalse(type.getSimpleName() + "." + m.getName(), c.getName().startsWith("java.nio.file."));
                }
            }
        }
        for (java.lang.reflect.Method m : ResourceResolver.class.getDeclaredMethods()) {
            List<Class<?>> named = new ArrayList<>(Arrays.asList(m.getParameterTypes()));
            named.add(m.getReturnType());
            named.addAll(Arrays.asList(m.getExceptionTypes()));
            for (Class<?> c : named) {
                String n = c.getName();
                assertTrue(m.getName() + " names " + n,
                        c.isPrimitive() || n.startsWith("java.lang.") || n.startsWith("java.io."));
            }
        }
    }
}
