package com.snoworca.naruserve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.testing.SettingsCapturingAdapter;
import com.snoworca.naruserve.testing.StubJsonCodec;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.junit.Test;

/**
 * FR-DX-012 (RestApp.start) and FR-DX-013 (ServerHandle.url and shutdown) on the engine-free surface. The
 * NaruServe-api test class path has no AppFactory provider, so the behavior over a real engine is in the
 * bootstrap module's StartBootstrapTest.
 */
public class StartApiTest {

    private static RestApp app(SettingsCapturingAdapter adapter) {
        return RestApp.builder().httpAdapter(adapter).jsonCodec(new StubJsonCodec())
                .logger(new com.snoworca.naruserve.testing.RecordingLogger()).build();
    }

    // FR-DX-012 AC-1
    @Test
    public void FR_DX_012_AC1_startIsTheOnlyEntryAndTheRejectedNamesAreAbsent() throws Exception {
        Method start = RestApp.class.getDeclaredMethod("start", int.class, Configurer.class);
        assertTrue(Modifier.isPublic(start.getModifiers()) && Modifier.isStatic(start.getModifiers()));
        assertEquals(ServerHandle.class, start.getReturnType());
        int named = 0;
        for (Method m : RestApp.class.getDeclaredMethods()) {
            if (m.getName().equals("start")) {
                named++;
            }
        }
        assertEquals("exactly one start method, with a port", 1, named);
        for (Class<?> type : new Class<?>[] {RestApp.class, ServerHandle.class}) {
            for (Method m : type.getMethods()) {
                for (String banned : new String[] {"run", "startAsync", "await", "close"}) {
                    assertFalse(type.getSimpleName() + "." + banned, m.getName().equals(banned));
                }
            }
        }
        assertFalse(AutoCloseable.class.isAssignableFrom(ServerHandle.class));
    }

    // FR-DX-012 AC-1: no environment variable is an input of the core
    @Test
    public void FR_DX_012_AC1_noMainClassOfTheApiCallsGetenv() throws Exception {
        List<String> hits = new ArrayList<>();
        File location = codeSource();
        if (location.isDirectory()) {
            scanDir(location, location, hits);
        } else {
            try (JarFile jar = new JarFile(location)) {
                for (Enumeration<JarEntry> e = jar.entries(); e.hasMoreElements(); ) {
                    JarEntry entry = e.nextElement();
                    if (entry.getName().endsWith(".class")) {
                        try (InputStream in = jar.getInputStream(entry)) {
                            if (contains(readAll(in), "getenv")) {
                                hits.add(entry.getName());
                            }
                        }
                    }
                }
            }
        }
        assertEquals(hits.toString(), 0, hits.size());
    }

    private static File codeSource() throws URISyntaxException {
        return new File(RestApp.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    private static void scanDir(File root, File dir, List<String> hits) throws IOException {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                scanDir(root, f, hits);
            } else if (f.getName().endsWith(".class")) {
                try (InputStream in = new java.io.FileInputStream(f)) {
                    if (contains(readAll(in), "getenv")) {
                        hits.add(f.getPath());
                    }
                }
            }
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static boolean contains(byte[] bytes, String needle) {
        return new String(bytes, StandardCharsets.ISO_8859_1).contains(needle);
    }

    // FR-DX-012 AC-2: a null configuration lambda fails before create() looks for a provider (there is none here)
    @Test
    public void FR_DX_012_AC2_nullRoutesThrowNullPointerBeforeCreate() {
        try {
            RestApp.start(0, null);
            fail("null routes accepted");
        } catch (NullPointerException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("routes"));
        }
    }

    // FR-DX-012 AC-7
    @Test
    public void FR_DX_012_AC7_withoutAProviderTheCreateErrorPassesThroughAndRoutesNeverRun() {
        AtomicInteger calls = new AtomicInteger();
        try {
            RestApp.start(0, app -> calls.incrementAndGet());
            fail("start worked without a provider");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("com.snoworca.naruserve:NaruServe"));
        }
        assertEquals(0, calls.get());
    }

    // FR-DX-013 AC-1
    @Test
    public void FR_DX_013_AC1_noPositionOfTheHandleIsNullable() {
        for (Method m : ServerHandle.class.getDeclaredMethods()) {
            assertEquals(m.getName(), 0, m.getAnnotations().length);
            for (java.lang.annotation.Annotation[] ps : m.getParameterAnnotations()) {
                assertEquals(m.getName(), 0, ps.length);
            }
            assertTrue(m.getName(), m.getAnnotatedReturnType().getAnnotations().length == 0);
        }
    }

    private static ServerHandle fixed(String address, int port) {
        return new ServerHandle() {
            @Override
            public int port() {
                return port;
            }

            @Override
            public String address() {
                return address;
            }

            @Override
            public AppState state() {
                return AppState.RUNNING;
            }

            @Override
            public void shutdown(long timeout, TimeUnit unit) {
            }
        };
    }

    // FR-DX-013 AC-2
    @Test
    public void FR_DX_013_AC2_urlFormsOfTheBoundAddress() {
        assertEquals("http://127.0.0.1:8080", fixed("127.0.0.1", 8080).url());
        assertEquals("http://localhost:3000", fixed("0.0.0.0", 3000).url());
        assertEquals("http://localhost:3000", fixed("::", 3000).url());
        assertEquals("http://localhost:3000", fixed("0:0:0:0:0:0:0:0", 3000).url());
        assertEquals("http://localhost:3000", fixed("[::]", 3000).url());
        assertEquals("http://192.168.0.7:9", fixed("192.168.0.7", 9).url());
        assertEquals("http://myphone.local:80", fixed("myphone.local", 80).url());
        assertEquals("http://[::1]:8080", fixed("::1", 8080).url());
        assertEquals("http://[fe80::1]:8080", fixed("fe80::1", 8080).url());
        assertEquals("http://[::1]:8080", fixed("[::1]", 8080).url());
    }

    // FR-DX-013 AC-2: the handle of listen reports the bound port, also after listen(0)
    @Test
    public void FR_DX_013_AC2_handleOfListenUsesTheBoundPortAndTheBindAddress() {
        SettingsCapturingAdapter loopback = new SettingsCapturingAdapter();
        RestApp first = app(loopback);
        ServerHandle h1 = first.listen(0);
        assertEquals("http://127.0.0.1:8080", h1.url());
        first.shutdown(1, TimeUnit.SECONDS);

        RestApp second = app(new SettingsCapturingAdapter()).server(o -> o.bindAddress("0.0.0.0"));
        ServerHandle h2 = second.listen(0);
        assertEquals("http://localhost:8080", h2.url());
        second.shutdown(1, TimeUnit.SECONDS);
    }

    // FR-DX-013 AC-3
    @Test
    public void FR_DX_013_AC3_handleShutdownFollowsTheAppShutdownContract() throws Exception {
        RestApp app = app(new SettingsCapturingAdapter());
        AtomicInteger stops = new AtomicInteger();
        app.onStop(ctx -> stops.incrementAndGet());
        ServerHandle h = app.listen(0);
        assertEquals(AppState.RUNNING, h.state());

        try {
            h.shutdown(-1, TimeUnit.SECONDS);
            fail("negative timeout accepted");
        } catch (IllegalArgumentException expected) {
            // as RestApp.shutdown
        }
        try {
            h.shutdown(1, null);
            fail("null unit accepted");
        } catch (NullPointerException expected) {
            // as RestApp.shutdown
        }
        assertEquals(AppState.RUNNING, h.state());

        h.shutdown(5, TimeUnit.SECONDS);
        assertEquals(AppState.TERMINATED, h.state());
        assertEquals(AppState.TERMINATED, app.state());
        assertEquals(1, stops.get());

        h.shutdown(5, TimeUnit.SECONDS);
        app.shutdown(1, TimeUnit.SECONDS);
        assertEquals(1, stops.get());
    }

    // FR-DX-013 AC-3: several threads
    @Test
    public void FR_DX_013_AC3_handleShutdownFromSeveralThreadsRunsOnStopOnce() throws Exception {
        RestApp app = app(new SettingsCapturingAdapter());
        AtomicInteger stops = new AtomicInteger();
        app.onStop(ctx -> stops.incrementAndGet());
        ServerHandle h = app.listen(0);
        CountDownLatch go = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Thread t = new Thread(() -> {
                try {
                    go.await();
                    h.shutdown(5, TimeUnit.SECONDS);
                } catch (Throwable e) {
                    failure.set(e);
                }
            });
            threads.add(t);
            t.start();
        }
        go.countDown();
        for (Thread t : threads) {
            t.join(10000);
        }
        assertEquals(null, failure.get());
        assertEquals(AppState.TERMINATED, h.state());
        assertEquals(1, stops.get());
    }
}
