package com.snoworca.naruserve.tinyhttpd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.HttpException;
import com.snoworca.naruserve.spi.HttpAdapter;
import com.snoworca.naruserve.spi.HttpExchange;
import com.snoworca.naruserve.spi.WebSocketSettings;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.Socket;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Test;

/** Module surface. SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-5-5.sds.md. */
public class AdapterSurfaceTest {

    private static final String ENGINE = "com.snoworca.tinyhttpd.";

    private final TinyHttpdAdapter adapter = new TinyHttpdAdapter();

    @After
    public void stop() {
        RawTargetSocketTest.Http.stopQuietly(adapter);
    }

    /** Every main class file of the module: the package directory beside TinyHttpdAdapter.class. */
    static List<File> mainClassFiles() throws URISyntaxException {
        File dir = new File(TinyHttpdAdapter.class.getResource("TinyHttpdAdapter.class").toURI()).getParentFile();
        List<File> out = new ArrayList<>();
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.getName().endsWith(".class")) {
                    out.add(f);
                }
            }
        }
        Collections.sort(out);
        return out;
    }

    /** A class file's bytes as ISO-8859-1 text, so constant-pool names read as substrings. */
    static String classText(File f) throws IOException {
        try (InputStream in = new java.io.FileInputStream(f)) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            for (int n; (n = in.read(b)) >= 0; ) {
                buf.write(b, 0, n);
            }
            return new String(buf.toByteArray(), StandardCharsets.ISO_8859_1);
        }
    }

    private static boolean exposed(int modifiers) {
        return Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers);
    }

    // SDS-AC-1; FR-ENGINE-011 AC-1
    @Test
    public void sdsAc1_FR_ENGINE_011_AC1_noEngineTypeInAnyPublicSignature() throws Exception {
        int mod = TinyHttpdAdapter.class.getModifiers();
        assertTrue(Modifier.isPublic(mod) && Modifier.isFinal(mod));
        assertTrue(HttpAdapter.class.isAssignableFrom(TinyHttpdAdapter.class));
        assertTrue(Modifier.isPublic(TinyHttpdAdapter.class.getConstructor().getModifiers()));

        List<String> leaks = new ArrayList<>();
        int publicClasses = 0;
        for (File f : mainClassFiles()) {
            String name = "com.snoworca.naruserve.tinyhttpd." + f.getName().replace(".class", "");
            Class<?> c = Class.forName(name, false, TinyHttpdAdapter.class.getClassLoader());
            if (!Modifier.isPublic(c.getModifiers())) {
                continue;
            }
            publicClasses++;
            List<String> sigs = new ArrayList<>();
            sigs.add(String.valueOf(c.getGenericSuperclass()));
            for (java.lang.reflect.Type t : c.getGenericInterfaces()) {
                sigs.add(t.getTypeName());
            }
            for (Constructor<?> k : c.getDeclaredConstructors()) {
                if (exposed(k.getModifiers())) {
                    sigs.add(k.toGenericString());
                }
            }
            for (Method m : c.getDeclaredMethods()) {
                if (exposed(m.getModifiers())) {
                    sigs.add(m.toGenericString());
                }
            }
            for (Field fd : c.getDeclaredFields()) {
                if (exposed(fd.getModifiers())) {
                    sigs.add(fd.toGenericString());
                }
            }
            for (String s : sigs) {
                if (s.contains(ENGINE)) {
                    leaks.add(name + ": " + s);
                }
            }
        }
        assertTrue("TinyHttpdAdapter must be scanned", publicClasses >= 1);
        assertEquals(Collections.emptyList(), leaks);
    }

    /** Calls every feature hook on {@code ex}, recording the simple name of what each one threw. */
    private static String featureFailures(com.snoworca.naruserve.spi.HttpExchange ex) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            try {
                if (i == 0) {
                    ex.sendFile(200, Collections.emptyList(), new File("missing"), 0, 0);
                } else if (i == 1) {
                    ex.eventStream(200, Collections.emptyList(), out -> { });
                } else if (i == 2) {
                    ex.upgrade(Collections.emptyList(), (in, out) -> { });
                } else {
                    ex.webSocket(new WebSocketSettings(4096, 15000), new TinyHttpdWebSocketTest.Recording());
                }
                sb.append("none,");
            } catch (Exception e) {
                sb.append(e.getClass().getSimpleName()).append(',');
            }
        }
        return sb.toString();
    }

    /** Whether every declared method of TinyHttpdExchange named {@code name} is synchronized; fails when none. */
    private static boolean synchronizedMethod(String name) {
        Boolean all = null;
        for (Method m : TinyHttpdExchange.class.getDeclaredMethods()) {
            if (m.getName().equals(name) && !m.isSynthetic()) {
                all = (all == null || all) && Modifier.isSynchronized(m.getModifiers());
            }
        }
        if (all == null) {
            throw new AssertionError("TinyHttpdExchange declares no " + name);
        }
        return all;
    }

    // FR-ENGINE-011 AC-2, AC-3; SDS-AC-9 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-8.sds.md: the feature
    // hooks share commit()'s abort check and single-start rule; commit, sendFile and upgrade hold the lock, while
    // eventStream and webSocket take it only for the checks, so their loops run outside it
    @Test(timeout = 20000)
    public void FR_ENGINE_011_AC2_featureHooksShareTheCommitLockAbortCheckAndSingleStart() throws Exception {
        for (String name : new String[] {"commit", "sendFile", "upgrade"}) {
            assertTrue(name, synchronizedMethod(name));
        }
        for (String name : new String[] {"eventStream", "webSocket"}) {
            assertFalse(name, synchronizedMethod(name));
        }
        AtomicReference<String> afterCommit = new AtomicReference<>();
        adapter.bind(RawTargetSocketTest.Http.settings().build(), ex -> {
            try {
                java.io.OutputStream out = ex.commit(200, Collections.emptyList(), 0);
                afterCommit.set(featureFailures(ex));
                out.flush();
                ex.finish();
            } catch (IOException e) {
                afterCommit.set(String.valueOf(e));
                ex.abort();
            }
        });
        assertEquals(200, RawTargetSocketTest.Http.status(
                RawTargetSocketTest.Http.send(adapter.boundPort(), RawTargetSocketTest.Http.get("/c"))));
        assertEquals("IllegalStateException,IllegalStateException,IllegalStateException,IllegalStateException,",
                afterCommit.get());
        RawTargetSocketTest.Http.stopQuietly(adapter);

        TinyHttpdAdapter second = new TinyHttpdAdapter();
        AtomicReference<String> afterAbort = new AtomicReference<>();
        try {
            second.bind(RawTargetSocketTest.Http.settings().build(), ex -> {
                ex.abort();
                afterAbort.set(featureFailures(ex));
            });
            RawTargetSocketTest.Http.send(second.boundPort(), RawTargetSocketTest.Http.get("/a"));
        } finally {
            RawTargetSocketTest.Http.stopQuietly(second);
        }
        assertEquals("IOException,IOException,IOException,IOException,", afterAbort.get());
    }

    private static final List<Map.Entry<String, String>> NO_BODY =
            Collections.<Map.Entry<String, String>>singletonList(new SimpleEntry<>("Content-Length", "0"));

    private int get(String path) throws IOException {
        return RawTargetSocketTest.Http.status(
                RawTargetSocketTest.Http.send(adapter.boundPort(), RawTargetSocketTest.Http.get(path)));
    }

    // FR-ENGINE-011 AC-2; SDS-AC-7 and SDS-AC-8 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-8.sds.md:
    // sendFile, eventStream and webSocket are filled on a real exchange; raw upgrade names webSocket and no wave and
    // leaves the response unstarted, so the error path still commits a 500
    @Test(timeout = 20000)
    public void FR_ENGINE_011_AC2_eventStreamAndWebSocketAreFilledAndRawUpgradeNamesNoWave() throws Exception {
        File empty = File.createTempFile("naru-empty", ".bin");
        empty.deleteOnExit();
        Map<String, String> seen = new ConcurrentHashMap<>();
        adapter.bind(RawTargetSocketTest.Http.settings().build(), ex -> {
            String path = ex.rawPath();
            try {
                if (path.equals("/sse")) {
                    ex.eventStream(200, Collections.<Map.Entry<String, String>>singletonList(
                            new SimpleEntry<>("Content-Type", "text/event-stream")), out -> {
                                out.write("data: x\n\n".getBytes(StandardCharsets.UTF_8));
                                out.flush();
                                ex.finish();
                            });
                    seen.put(path, "started");
                } else if (path.equals("/ws")) {
                    try {
                        ex.webSocket(new WebSocketSettings(4096, 15000), new TinyHttpdWebSocketTest.Recording());
                        seen.put(path, "upgraded");
                    } catch (HttpException e) {
                        seen.put(path, "refused " + e.status()); // a plain GET is no upgrade request
                        ex.commit(e.status(), NO_BODY, 0);
                        ex.finish();
                    }
                } else if (path.equals("/f")) {
                    ex.sendFile(200, NO_BODY, empty, 0, 0);
                    seen.put(path, "sent");
                    ex.finish();
                } else {
                    try {
                        ex.upgrade(Collections.<Map.Entry<String, String>>emptyList(), (in, out) -> { });
                        seen.put(path, "upgraded");
                    } catch (UnsupportedOperationException e) {
                        seen.put(path, String.valueOf(e.getMessage()));
                        ex.commit(500, NO_BODY, 0);
                        ex.finish();
                    }
                }
            } catch (RuntimeException | IOException e) {
                seen.put(path, String.valueOf(e));
                ex.abort();
            }
        });
        String sse = RawTargetSocketTest.Http.send(adapter.boundPort(), RawTargetSocketTest.Http.get("/sse"));
        assertEquals(sse, 200, RawTargetSocketTest.Http.status(sse));
        assertEquals(sse, "chunked", RawTargetSocketTest.Http.header(sse, "Transfer-Encoding"));
        assertEquals(sse, null, RawTargetSocketTest.Http.header(sse, "Content-Length"));
        assertTrue(sse, sse.contains("data: x"));
        assertEquals("started", seen.get("/sse"));
        assertEquals(400, get("/ws"));
        assertEquals("refused 400", seen.get("/ws"));
        assertEquals(200, get("/f"));
        assertEquals("sent", seen.get("/f"));
        assertEquals(500, get("/u"));
        String message = seen.get("/u");
        assertEquals("raw protocol upgrades are not supported by the TinyHttpd adapter; WebSocket goes through "
                + "webSocket", message);
        assertFalse(message, message.toLowerCase(Locale.ROOT).contains("wave"));
        // The hooks live in their own class, not in the exchange bridge, and none of them names wave 7 any more.
        assertTrue(classText(new File(TinyHttpdAdapter.class.getResource("TinyHttpdExchange.class").toURI()))
                .contains("com/snoworca/naruserve/tinyhttpd/TinyHttpdFeatures"));
        File source = new File(moduleRoot(),
                "NaruServe-tinyhttpd/src/main/java/com/snoworca/naruserve/tinyhttpd/TinyHttpdFeatures.java");
        String text = new String(java.nio.file.Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);
        assertFalse(text.contains("UnsupportedOperationException(\"protocol upgrades"));
        assertFalse(text.contains("wave 7"));
    }

    /** The checkout root: the test JVM runs in the module directory or, from an IDE, in the root. */
    private static File moduleRoot() {
        File dir = new File(System.getProperty("user.dir")).getAbsoluteFile();
        return new File(dir, "settings.gradle").isFile() ? dir : dir.getParentFile();
    }

    /** Whether a second thread, once running, enters {@code synchronized (lock)} within 100 ms. */
    private static boolean lockFree(Object lock) throws InterruptedException {
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        Thread t = new Thread(() -> {
            running.countDown();
            synchronized (lock) {
                entered.countDown();
            }
        });
        t.setDaemon(true);
        t.start();
        assertTrue(running.await(5, TimeUnit.SECONDS)); // thread start-up is not part of the 100 ms
        return entered.await(100, TimeUnit.MILLISECONDS);
    }

    // FR-ENGINE-011 AC-3: the drain runs on the worker after the handler returned, so an eventStream started on
    // another thread after that throws instead of leaving a stream nobody drains
    @Test(timeout = 20000)
    public void FR_ENGINE_011_AC3_eventStreamAfterTheHandlerReturnedThrows() throws Exception {
        AtomicReference<HttpExchange> exchange = new AtomicReference<>();
        CountDownLatch handled = new CountDownLatch(1);
        adapter.bind(RawTargetSocketTest.Http.settings().build(), ex -> {
            exchange.set(ex);
            handled.countDown();
        });
        try (Socket s = new Socket("127.0.0.1", adapter.boundPort())) {
            s.setSoTimeout(10000);
            s.getOutputStream().write(RawTargetSocketTest.Http.get("/late").getBytes(StandardCharsets.ISO_8859_1));
            s.getOutputStream().flush();
            assertTrue(handled.await(5, TimeUnit.SECONDS));
            HttpExchange ex = exchange.get();
            // run() marks the handler returned right after handle; wait until a drain could no longer be taken.
            String failure = null;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (failure == null && System.nanoTime() < deadline) {
                synchronized (ex) {
                    if (((TinyHttpdExchange) ex).handlerReturned()) {
                        try {
                            ex.eventStream(200, Collections.<Map.Entry<String, String>>emptyList(), out -> { });
                            failure = "none";
                        } catch (IllegalStateException e) {
                            failure = e.getMessage();
                        }
                    }
                }
                if (failure == null) {
                    Thread.sleep(5);
                }
            }
            ex.abort();
            assertEquals("eventStream must be called before the handler returns", failure);
        }
    }

    // FR-ENGINE-011 AC-3; SDS-AC-9 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-8.sds.md: a test thread enters
    // synchronized (exchange) within 100 ms while the WebSocket session waits idle and while the drain waits
    @Test(timeout = 30000)
    public void FR_ENGINE_011_AC3_theExchangeLockIsFreeWhileTheReadLoopAndTheDrainWait() throws Exception {
        AtomicReference<HttpExchange> exchange = new AtomicReference<>();
        CountDownLatch draining = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TinyHttpdWebSocketTest.Recording endpoint = new TinyHttpdWebSocketTest.Recording();
        adapter.bind(RawTargetSocketTest.Http.settings().build(), ex -> {
            exchange.set(ex);
            try {
                if (ex.rawPath().equals("/ws")) {
                    ex.webSocket(new WebSocketSettings(4096, 15000), endpoint);
                    ex.finish();
                } else {
                    ex.eventStream(200, Collections.<Map.Entry<String, String>>emptyList(), out -> {
                        draining.countDown();
                        try {
                            release.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        ex.finish();
                    });
                }
            } catch (RuntimeException | IOException e) {
                ex.abort();
            }
        });
        try (RawWebSocketClient c = RawWebSocketClient.open(adapter.boundPort(), "/ws")) {
            assertEquals(101, c.status());
            assertEquals("open", endpoint.events.poll(5, TimeUnit.SECONDS));
            assertTrue("synchronized (exchange) during an idle session", lockFree(exchange.get()));
        }
        try (Socket s = new Socket("127.0.0.1", adapter.boundPort())) {
            try {
                s.getOutputStream().write(RawTargetSocketTest.Http.get("/sse").getBytes(StandardCharsets.ISO_8859_1));
                s.getOutputStream().flush();
                assertTrue(draining.await(5, TimeUnit.SECONDS));
                assertTrue("synchronized (exchange) while the drain waits", lockFree(exchange.get()));
            } finally {
                release.countDown();
            }
        }
    }

    // SDS-AC-3; FR-ENGINE-011 AC-5
    @Test
    public void sdsAc3_FR_ENGINE_011_AC5_noClassReferencesTlsConfig() throws Exception {
        List<String> hits = new ArrayList<>();
        List<File> files = mainClassFiles();
        assertFalse(files.isEmpty());
        for (File f : files) {
            String text = classText(f);
            // Any tls(TlsConfig) or listen(..., TlsConfig) call names the TlsConfig descriptor in the constant pool.
            if (text.contains("com/snoworca/tinyhttpd/TlsConfig")) {
                hits.add(f.getName());
            }
        }
        assertEquals(Collections.emptyList(), hits);
    }

    private static void javaSources(File dir, List<File> out) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                javaSources(f, out);
            } else if (f.getName().endsWith(".java")) {
                out.add(f);
            }
        }
    }

    // SDS-AC-10 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-1.sds.md; IR-WS-002 AC-7
    @Test
    public void sdsAc10_IR_WS_002_AC7_noMainSourceOfApiOrTinyHttpdHandlesFrameOpcodesOrMasking() throws Exception {
        File dir = new File(System.getProperty("user.dir")).getAbsoluteFile();
        // The test JVM runs in the module directory or, from an IDE, in the checkout root.
        File root = new File(dir, "settings.gradle").isFile() ? dir : dir.getParentFile();
        java.util.regex.Pattern codec = java.util.regex.Pattern.compile("opcode|masking",
                java.util.regex.Pattern.CASE_INSENSITIVE);
        assertTrue("the scan bites", codec.matcher("int OPCODE_TEXT = 1; byte[] maskingKey;").find());
        List<String> hits = new ArrayList<>();
        for (String tree : new String[] {"NaruServe-api/src/main", "NaruServe-tinyhttpd/src/main"}) {
            List<File> files = new ArrayList<>();
            javaSources(new File(root, tree), files);
            assertFalse(tree + " has no sources", files.isEmpty());
            for (File f : files) {
                String text = new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                if (codec.matcher(text).find()) {
                    hits.add(tree + ": " + f.getName());
                }
            }
        }
        assertEquals(Collections.emptyList(), hits);
    }
}
