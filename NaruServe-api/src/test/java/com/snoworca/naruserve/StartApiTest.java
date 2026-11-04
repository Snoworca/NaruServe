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

    /**
     * The methods allowed to read the environment (FR-DX-012 AC-1): explicit auth calls the app makes, the
     * {@code NARU_AUTH_RECOVERY} switch {@code Auth.Builder.recoveryFromEnv()} included (SEC-AUTH-003), and the
     * {@code NARU_AUTH_FILE_KEY} read of {@code AuthFileKey.fromEnv()} (FR-IAM-015).
     */
    static final java.util.Set<String> ENV_READERS = java.util.Collections.unmodifiableSet(new java.util.TreeSet<>(
            java.util.Arrays.asList("com/snoworca/naruserve/utils/auth/AuthMode#fromEnv",
                    "com/snoworca/naruserve/utils/auth/KeySources#fromEnv",
                    "com/snoworca/naruserve/utils/auth/Auth$Builder#recoveryFromEnv",
                    "com/snoworca/naruserve/utils/auth/AuthFileKey#fromEnv")));

    // FR-DX-012 AC-1: no Core class reads the environment (0.10.0 check: no Core class file holds the bytes "getenv"
    // at all, so a reflective read or a getenv constant is caught too); inside utils.auth only the allowed explicit
    // calls do
    @Test
    public void FR_DX_012_AC1_noMainClassOfTheApiCallsGetenv() throws Exception {
        List<String> hits = new ArrayList<>();
        java.util.Set<String> readers = new java.util.TreeSet<>();
        File location = codeSource();
        if (location.isDirectory()) {
            scanDir(location, location, hits, readers);
        } else {
            try (JarFile jar = new JarFile(location)) {
                for (Enumeration<JarEntry> e = jar.entries(); e.hasMoreElements(); ) {
                    JarEntry entry = e.nextElement();
                    if (entry.getName().endsWith(".class")) {
                        try (InputStream in = jar.getInputStream(entry)) {
                            scan(readAll(in), hits, readers);
                        }
                    }
                }
            }
        }
        assertEquals(hits.toString(), 0, hits.size());
        assertEquals("the allowed readers are the only getenv callers", ENV_READERS, readers);
    }

    // FR-DX-012 AC-1: negative controls; a Core class or an unlisted auth method calling getenv is a hit
    @Test
    public void FR_DX_012_AC1_getenvOutsideTheAllowedMethodsIsCaught() throws Exception {
        List<String> hits = new ArrayList<>();
        java.util.Set<String> readers = new java.util.TreeSet<>();
        classify(readAll(StartApiTest.class.getResourceAsStream("StartApiTest$CoreEnvFixture.class")), hits, readers);
        assertEquals(1, hits.size());
        assertTrue(hits.get(0), hits.get(0).contains("CoreEnvFixture#read"));
        hits.clear();
        classify(readAll(com.snoworca.naruserve.utils.auth.EnvFixture.class.getResourceAsStream("EnvFixture.class")),
                hits, readers);
        assertEquals(hits.toString(), 1, hits.size());
        assertTrue(hits.get(0), hits.get(0).contains("utils/auth/EnvFixture#fromEnvLike"));
        hits.clear();
        classify(readAll(StartApiTest.class.getResourceAsStream("StartApiTest.class")), hits, readers);
        assertEquals("this test names getenv only as a string", 0, hits.size());
    }

    // FR-DX-012 AC-1: negative control of the 0.10.0 Core check; a Core class that reads getenv reflectively has no
    // getenv Methodref, so the method-level classify() misses it, but the scan of a main class catches it by name
    @Test
    public void FR_DX_012_AC1_reflectiveGetenvInCoreIsCaught() throws Exception {
        byte[] fixture = readAll(StartApiTest.class.getResourceAsStream("StartApiTest$ReflectiveCoreEnvFixture.class"));
        List<String> hits = new ArrayList<>();
        java.util.Set<String> readers = new java.util.TreeSet<>();
        classify(fixture, hits, readers);
        assertEquals("no getenv Methodref in the reflective fixture: " + hits, 0, hits.size());
        scan(fixture, hits, readers);
        assertEquals(hits.toString(), 1, hits.size());
        assertTrue(hits.get(0), hits.get(0).contains("StartApiTest$ReflectiveCoreEnvFixture"));
        assertTrue(readers.toString(), readers.isEmpty());
        hits.clear();
        scan(readAll(StartApiTest.class.getResourceAsStream("StartApiTest$CoreEnvFixture.class")), hits, readers);
        assertEquals("a direct call is one hit, not two: " + hits, 1, hits.size());
        assertTrue(hits.get(0), hits.get(0).contains("CoreEnvFixture#read"));
        hits.clear();
        scan(readAll(com.snoworca.naruserve.utils.auth.EnvFixture.class.getResourceAsStream("EnvFixture.class")),
                hits, readers);
        assertEquals("utils.auth keeps the method-level check: " + hits, 1, hits.size());
        assertTrue(hits.get(0), hits.get(0).contains("utils/auth/EnvFixture#fromEnvLike"));
    }

    /** A Core-package class reading the environment. */
    static final class CoreEnvFixture {
        static String read() {
            return System.getenv("HOME");
        }
    }

    /** A Core-package class reading the environment through reflection, with no getenv Methodref. */
    static final class ReflectiveCoreEnvFixture {
        static Object read() throws Exception {
            return System.class.getMethod("getenv", String.class).invoke(null, "HOME");
        }
    }

    /**
     * One main class file: the method-level {@link #classify}, and for a class outside utils.auth (Core) also the
     * 0.10.0 check, under which the bytes {@code getenv} anywhere in the class file are a hit (a reflective read or a
     * {@code getenv} constant included). A Core class that classify() already flagged is not counted twice.
     */
    static void scan(byte[] bytes, List<String> hits, java.util.Set<String> readers) throws IOException {
        int before = hits.size();
        String self = classify(bytes, hits, readers);
        if (!self.startsWith("com/snoworca/naruserve/utils/auth/") && hits.size() == before
                && contains(bytes, "getenv")) {
            hits.add(self + " holds the bytes getenv (a Core class)");
        }
    }

    private static boolean contains(byte[] bytes, String needle) {
        return new String(bytes, StandardCharsets.ISO_8859_1).contains(needle);
    }

    private static File codeSource() throws URISyntaxException {
        return new File(RestApp.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    private static void scanDir(File root, File dir, List<String> hits, java.util.Set<String> readers)
            throws IOException {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                scanDir(root, f, hits, readers);
            } else if (f.getName().endsWith(".class")) {
                try (InputStream in = new java.io.FileInputStream(f)) {
                    scan(readAll(in), hits, readers);
                }
            }
        }
    }

    /**
     * Finds the methods of one class file whose code calls {@code System.getenv} (an {@code invokestatic} of that
     * Methodref) or that hold it as a method handle. A caller outside utils.auth, or a utils.auth method not in
     * {@link #ENV_READERS}, is a hit; allowed callers are collected in {@code readers}. Returns the class's internal
     * name.
     */
    static String classify(byte[] bytes, List<String> hits, java.util.Set<String> readers) throws IOException {
        java.io.DataInputStream in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes));
        in.readInt();
        in.readUnsignedShort();
        in.readUnsignedShort();
        int count = in.readUnsignedShort();
        String[] utf8 = new String[count];
        int[] classIdx = new int[count];
        int[] refClass = new int[count];
        int[] refNat = new int[count];
        int[] natName = new int[count];
        int[] handleRef = new int[count];
        int[] tags = new int[count];
        for (int i = 1; i < count; i++) {
            int tag = in.readUnsignedByte();
            tags[i] = tag;
            switch (tag) {
                case 1: utf8[i] = in.readUTF(); break;
                case 7: classIdx[i] = in.readUnsignedShort(); break;
                case 9: case 10: case 11: refClass[i] = in.readUnsignedShort(); refNat[i] = in.readUnsignedShort(); break;
                case 12: natName[i] = in.readUnsignedShort(); in.readUnsignedShort(); break;
                case 15: in.readUnsignedByte(); handleRef[i] = in.readUnsignedShort(); break;
                case 8: case 16: case 19: case 20: in.skipBytes(2); break;
                case 3: case 4: case 17: case 18: in.skipBytes(4); break;
                case 5: case 6: in.skipBytes(8); i++; break;
                default: throw new IOException("unknown constant pool tag " + tag);
            }
        }
        java.util.Set<Integer> getenv = new java.util.HashSet<>();
        for (int i = 1; i < count; i++) {
            if ((tags[i] == 10 || tags[i] == 11) && "java/lang/System".equals(utf8[classIdx[refClass[i]]])
                    && "getenv".equals(utf8[natName[refNat[i]]])) {
                getenv.add(i);
            }
        }
        in.readUnsignedShort();
        String self = utf8[classIdx[in.readUnsignedShort()]];
        if (getenv.isEmpty()) {
            return self;
        }
        for (int i = 1; i < count; i++) {
            if (tags[i] == 15 && getenv.contains(handleRef[i])) {
                hits.add(self + " holds System.getenv as a method handle");
            }
        }
        in.readUnsignedShort();
        in.skipBytes(2 * in.readUnsignedShort());
        int fields = in.readUnsignedShort();
        for (int f = 0; f < fields; f++) {
            in.skipBytes(6);
            skipAttributes(in);
        }
        int methods = in.readUnsignedShort();
        for (int m = 0; m < methods; m++) {
            in.readUnsignedShort();
            String name = utf8[in.readUnsignedShort()];
            in.readUnsignedShort();
            int attrs = in.readUnsignedShort();
            boolean calls = false;
            for (int a = 0; a < attrs; a++) {
                String attr = utf8[in.readUnsignedShort()];
                byte[] body = new byte[in.readInt()];
                in.readFully(body);
                if (attr.equals("Code")) {
                    int codeLength = (body[4] & 0xff) << 24 | (body[5] & 0xff) << 16 | (body[6] & 0xff) << 8
                            | body[7] & 0xff;
                    for (int c = 8; c + 2 < 8 + codeLength; c++) {
                        if ((body[c] & 0xff) == 0xb8 && getenv.contains((body[c + 1] & 0xff) << 8 | body[c + 2] & 0xff)) {
                            calls = true;
                        }
                    }
                }
            }
            if (calls) {
                String where = self + "#" + name;
                if (self.startsWith("com/snoworca/naruserve/utils/auth/") && ENV_READERS.contains(where)) {
                    readers.add(where);
                } else {
                    hits.add(where);
                }
            }
        }
        return self;
    }

    private static void skipAttributes(java.io.DataInputStream in) throws IOException {
        int attrs = in.readUnsignedShort();
        for (int a = 0; a < attrs; a++) {
            in.readUnsignedShort();
            in.skipBytes(in.readInt());
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
            assertTrue(e.getMessage(), e.getMessage().contains("com.snoworca.naruserve:naruserve"));
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
