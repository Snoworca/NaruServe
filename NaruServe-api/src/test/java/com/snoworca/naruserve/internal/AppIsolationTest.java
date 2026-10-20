package com.snoworca.naruserve.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.ApplicationContext;
import com.snoworca.naruserve.Logger;
import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.StubJsonCodec;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.junit.Test;

/** Per-app isolation. SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-4-3.sds.md. */
public class AppIsolationTest {

    static final class RecordingLogger implements Logger {
        final List<String> lines = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void trace(String message) {
            lines.add(message);
        }

        @Override
        public void debug(String message) {
            lines.add(message);
        }

        @Override
        public void info(String message) {
            lines.add(message);
        }

        @Override
        public void warn(String message) {
            lines.add(message);
        }

        @Override
        public void error(String message) {
            lines.add(message);
        }

        @Override
        public void error(String message, Throwable error) {
            lines.add(message);
        }
    }

    static final class Svc {
        final String name;

        Svc(String name) {
            this.name = name;
        }
    }

    /** One app with its own adapter, route, middleware, logger, onStart, service and attribute. */
    static final class Built {
        final InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        final RecordingLogger logger = new RecordingLogger();
        final List<String> trail = Collections.synchronizedList(new ArrayList<>());
        final AtomicReference<ApplicationContext> ctx = new AtomicReference<>();
        final RestApp app;

        Built(String name) {
            app = RestApp.builder().httpAdapter(adapter).jsonCodec(new StubJsonCodec()).logger(logger).build();
            app.accessLog(o -> o.enabled(false)); // access lines (FR-APP-019) would mask the onStop-error checks
            // Route middleware: the global pipeline lands with the FR-APP-003 SDS.
            app.get("/" + name, (req, res, next) -> {
                trail.add("mw-" + name);
                next.run();
            }, (req, res) -> res.send(name));
            app.onStart(c -> {
                trail.add("start-" + name);
                c.register(Svc.class, new Svc(name));
                c.attribute("owner", name);
                ctx.set(c);
            });
            app.onStop(c -> {
                throw new IllegalStateException("stop-" + name);
            });
        }

        int status(String path) {
            return adapter.send(ClientRequest.of("GET", path)).status();
        }

        String body(String path) {
            return new String(adapter.send(ClientRequest.of("GET", path)).body(), StandardCharsets.UTF_8);
        }
    }

    // SDS-AC-1; FR-APP-002 AC-1; CON-APP-006 AC-1
    @Test
    public void sdsAc1_FR_APP_002_AC1_twoAppsInOneJvmKeepRoutesMiddlewareLoggerServicesAndAttributesApart() {
        Built a = new Built("a");
        Built b = new Built("b");
        a.app.listen(0);
        b.app.listen(0);

        assertEquals("a", a.body("/a"));
        assertEquals(404, a.status("/b"));
        assertEquals("b", b.body("/b"));
        assertEquals(404, b.status("/a"));
        for (String s : a.trail) {
            assertTrue(s, s.endsWith("-a"));
        }
        for (String s : b.trail) {
            assertTrue(s, s.endsWith("-b"));
        }
        assertTrue(a.trail.contains("start-a"));
        assertTrue(b.trail.contains("start-b"));
        assertTrue(a.trail.contains("mw-a"));
        assertTrue(b.trail.contains("mw-b"));

        assertSame(a.logger, a.app.logger());
        assertSame(b.logger, b.app.logger());
        assertSame(a.logger, a.ctx.get().logger());
        assertSame(b.logger, b.ctx.get().logger());
        assertEquals("a", a.ctx.get().get(Svc.class).name);
        assertEquals("b", b.ctx.get().get(Svc.class).name);
        assertEquals("a", a.ctx.get().attribute("owner", String.class));
        assertEquals("b", b.ctx.get().attribute("owner", String.class));

        a.app.shutdown(1, TimeUnit.SECONDS);
        // The failing onStop of a is logged only by a's logger; b is still serving.
        assertFalse(a.logger.lines.isEmpty());
        assertTrue(b.logger.lines.isEmpty());
        assertEquals("b", b.body("/b"));
        b.app.shutdown(1, TimeUnit.SECONDS);
        assertFalse(b.logger.lines.isEmpty());
    }

    /** A closeable service; the owning app closes it at shutdown. */
    static final class Pool implements AutoCloseable {
        final java.util.concurrent.atomic.AtomicInteger closes = new java.util.concurrent.atomic.AtomicInteger();

        @Override
        public void close() {
            closes.incrementAndGet();
        }
    }

    // CON-APP-006 AC-1: the same service key in two apps gives each its own instance, and a shutdown closes only its own
    @Test
    public void con_APP_006_AC1_sameServiceKeyInTwoAppsClosesOnlyTheOwnedResourceOfTheStoppedApp() {
        Pool[] pools = {new Pool(), new Pool()};
        Built[] apps = {new Built("a"), new Built("b")};
        for (int i = 0; i < 2; i++) {
            final Pool pool = pools[i];
            apps[i].app.onStart(c -> c.register(Pool.class, pool));
            apps[i].app.listen(0);
        }
        assertSame(pools[0], apps[0].ctx.get().get(Pool.class));
        assertSame(pools[1], apps[1].ctx.get().get(Pool.class));
        apps[0].app.shutdown(1, TimeUnit.SECONDS);
        assertEquals(1, pools[0].closes.get());
        assertEquals(0, pools[1].closes.get());
        assertEquals("b", apps[1].body("/b"));
        apps[1].app.shutdown(1, TimeUnit.SECONDS);
        assertEquals(1, pools[0].closes.get());
        assertEquals(1, pools[1].closes.get());
    }

    /** Binary names of the classes compiled into {@code pkg} (nested classes included). */
    private static List<String> classNames(String pkg) throws Exception {
        File location = new File(RestApp.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        String dir = pkg.replace('.', '/');
        List<String> out = new ArrayList<>();
        if (location.isDirectory()) {
            String[] names = new File(location, dir).list();
            if (names != null) {
                for (String n : names) {
                    if (n.endsWith(".class") && !n.equals("package-info.class")) {
                        out.add(pkg + "." + n.substring(0, n.length() - 6));
                    }
                }
            }
        } else {
            try (JarFile jar = new JarFile(location)) {
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    String n = entries.nextElement().getName();
                    if (n.startsWith(dir + "/") && n.endsWith(".class") && n.indexOf('/', dir.length() + 1) < 0
                            && !n.endsWith("package-info.class")) {
                        out.add(n.substring(0, n.length() - 6).replace('/', '.'));
                    }
                }
            }
        }
        return out;
    }

    // SDS-AC-2; FR-APP-002 AC-2; CON-APP-006 AC-1
    @Test
    public void sdsAc2_FR_APP_002_AC2_noMutableStaticStateInTheApiAndInternalPackages() throws Exception {
        List<String> offenders = new ArrayList<>();
        int scanned = 0;
        for (String pkg : new String[] {"com.snoworca.naruserve", "com.snoworca.naruserve.internal"}) {
            for (String name : classNames(pkg)) {
                Class<?> c = Class.forName(name, false, RestApp.class.getClassLoader());
                scanned++;
                for (Field f : c.getDeclaredFields()) {
                    int mods = f.getModifiers();
                    if (!Modifier.isStatic(mods) || f.isSynthetic() || f.isEnumConstant()) {
                        continue;
                    }
                    Class<?> t = f.getType();
                    if (!Modifier.isFinal(mods)) {
                        offenders.add(name + "." + f.getName() + " is not final");
                    } else if (Collection.class.isAssignableFrom(t) || Map.class.isAssignableFrom(t)) {
                        offenders.add(name + "." + f.getName() + " is a mutable collection");
                    } else if (t.isArray() && !Modifier.isPrivate(mods)) {
                        offenders.add(name + "." + f.getName() + " is a non-private array");
                    }
                }
            }
        }
        assertTrue("scanned " + scanned, scanned >= 30);
        assertEquals(new ArrayList<String>(), offenders);
        // The lifecycle state lives in each instance.
        assertNull(staticField(AppLifecycle.class));
        assertNull(staticField(DefaultRestApp.class));
    }

    // SDS-AC-3 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-5-3.sds.md; IR-COOKIE-001 AC-6
    @Test
    public void sdsAc3_IR_COOKIE_001_AC6_cookieFactoryHookHoldsOnlyAPrivateStaticFinalMethod() {
        List<String> fields = new ArrayList<>();
        for (Field f : CookieFactoryHook.class.getDeclaredFields()) {
            if (f.isSynthetic()) {
                continue;
            }
            int mods = f.getModifiers();
            assertTrue(f.getName(), Modifier.isPrivate(mods) && Modifier.isStatic(mods) && Modifier.isFinal(mods));
            assertEquals(java.lang.reflect.Method.class, f.getType());
            fields.add(f.getName());
        }
        assertEquals(1, fields.size());
    }

    private static Field staticField(Class<?> c) {
        for (Field f : c.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) && !f.isSynthetic()) {
                return f;
            }
        }
        return null;
    }
}
