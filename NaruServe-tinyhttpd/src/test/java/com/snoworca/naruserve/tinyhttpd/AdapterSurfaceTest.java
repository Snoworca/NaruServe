package com.snoworca.naruserve.tinyhttpd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.HttpException;
import com.snoworca.naruserve.spi.HttpAdapter;
import com.snoworca.naruserve.spi.HttpExchange;
import com.snoworca.naruserve.spi.StreamDrain;
import com.snoworca.naruserve.spi.WebSocketSettings;
import com.snoworca.naruserve.testing.NoLeftoverThreads;
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
import org.junit.Rule;
import org.junit.Test;

/** Module surface. SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-5-5.sds.md. */
public class AdapterSurfaceTest {

    // I-015: no naruserve-* thread outlives the test
    @Rule
    public final NoLeftoverThreads noLeftoverThreads = new NoLeftoverThreads();

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

    // FR-ENGINE-011 AC-2, AC-3; SDS-AC-9 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-8.sds.md: the feature
    // hooks share the abort check and single-start claim. IR-HTTP-009 AC-3 requires
    // callbacks outside IO/lifecycle locks; method modifiers are not the concurrency contract.
    @Test(timeout = 20000)
    public void FR_ENGINE_011_AC2_featureHooksShareTheCommitLockAbortCheckAndSingleStart() throws Exception {
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

    // FR-APP-023 AC-1/6 supersedes FR-ENGINE-011 AC-3's historical late-start rejection.
    @Test(timeout = 20000)
    public void FR_APP_023_lateEventStreamAfterHandlerReturnIsActuallyDrained() throws Exception {
        AtomicReference<HttpExchange> exchange = new AtomicReference<>();
        CountDownLatch handled = new CountDownLatch(1), drained = new CountDownLatch(1);
        adapter.bind(RawTargetSocketTest.Http.settings().build(), ex -> {
            exchange.set(ex);
            handled.countDown();
        });
        try (Socket socket = new Socket("127.0.0.1", adapter.boundPort())) {
            socket.setSoTimeout(10000);
            socket.getOutputStream().write(RawTargetSocketTest.Http.get("/late").getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
            assertTrue(handled.await(5, TimeUnit.SECONDS));
            HttpExchange ex = exchange.get();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!((TinyHttpdExchange) ex).handlerReturned() && System.nanoTime() < deadline) Thread.sleep(5);
            assertTrue(((TinyHttpdExchange) ex).handlerReturned());
            ex.eventStream(200, Collections.emptyList(), out -> {
                out.write("data: late\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                drained.countDown();
                ex.finish();
            });
            String reply = RawTargetSocketTest.Http.readAll(socket.getInputStream());
            assertEquals(200, RawTargetSocketTest.Http.status(reply));
            assertTrue(reply, reply.contains("data: late\n\n"));
            assertTrue(drained.await(5, TimeUnit.SECONDS));
        }
    }

    // IR-HTTP-009 AC-3/5; FR-APP-022 AC-3: commit is an atomic claim, actual writes
    // retain the IO monitor, and a competing claim cannot emit another response.
    @Test(timeout = 20000)
    public void concurrentCommitHasOneWinnerAndWritesWaitForTheIoOwner() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        java.util.concurrent.atomic.AtomicInteger winners = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger rejected = new java.util.concurrent.atomic.AtomicInteger();
        CountDownLatch writesAttempted = new CountDownLatch(1), written = new CountDownLatch(1);
        adapter.bind(RawTargetSocketTest.Http.settings().build(), ex -> {
            CountDownLatch go = new CountDownLatch(1);
            AtomicReference<java.io.OutputStream> body = new AtomicReference<>();
            Runnable claim = () -> {
                try {
                    go.await();
                    body.set(ex.commit(200, Collections.singletonList(new SimpleEntry<>("Content-Length", "2")), 2));
                    winners.incrementAndGet();
                } catch (IllegalStateException expected) {
                    rejected.incrementAndGet();
                } catch (Throwable unexpected) { failure.compareAndSet(null, unexpected); }
            };
            Thread one = new Thread(claim), two = new Thread(claim);
            one.start(); two.start(); go.countDown();
            try {
                one.join(5000); two.join(5000);
                assertFalse(one.isAlive()); assertFalse(two.isAlive());
                assertEquals(1, winners.get()); assertEquals(1, rejected.get());
                Thread writer = new Thread(() -> {
                    writesAttempted.countDown();
                    try { body.get().write(new byte[] {'o', 'k'}); }
                    catch (Throwable unexpected) { failure.compareAndSet(null, unexpected); }
                    finally { written.countDown(); }
                });
                synchronized (ex) {
                    writer.start();
                    assertTrue(writesAttempted.await(5, TimeUnit.SECONDS));
                    assertFalse("body writer bypassed current IO owner", written.await(100, TimeUnit.MILLISECONDS));
                }
                assertTrue(written.await(5, TimeUnit.SECONDS));
                writer.join(5000);
                ex.finish();
            } catch (Throwable unexpected) { failure.compareAndSet(null, unexpected); ex.abort(); }
        });
        String reply = RawTargetSocketTest.Http.send(adapter.boundPort(), RawTargetSocketTest.Http.get("/race"));
        assertEquals(reply, 200, RawTargetSocketTest.Http.status(reply));
        assertEquals("ok", RawTargetSocketTest.Http.body(reply));
        assertEquals(1, winners.get()); assertEquals(1, rejected.get());
        assertEquals(null, failure.get());
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

    // SDS-AC-1 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-11.sds.md; FR-SSE-003 AC-6: the start checks,
    // the slot and the start mark share one synchronized (exchange) and nothing else holds it: the lock is free
    // within 100 ms while the drain waits, the open stream holds one slot until run() released it, and after the
    // start a commit or a second eventStream throws IllegalStateException without taking a slot
    @Test(timeout = 30000)
    public void sdsAc1_FR_SSE_003_AC6_eventStreamHoldsTheLockOnlyForTheChecksTheSlotAndTheStart() throws Exception {
        AtomicReference<HttpExchange> exchange = new AtomicReference<>();
        AtomicReference<String> after = new AtomicReference<>();
        CountDownLatch draining = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<Map.Entry<String, String>> none = Collections.<Map.Entry<String, String>>emptyList();
        adapter.bind(RawTargetSocketTest.Http.settings().build(), ex -> {
            exchange.set(ex);
            AtomicReference<Boolean> lockedInAttach = new AtomicReference<>();
            try {
                ex.eventStream(200, none, new StreamDrain() {
                    @Override
                    public void attach(java.io.OutputStream out) {
                        lockedInAttach.set(Thread.holdsLock(ex)); // the headers, flush and attach run without it
                    }

                    @Override
                    public void drain(java.io.OutputStream out) throws IOException {
                        draining.countDown();
                        try {
                            release.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        ex.finish();
                    }
                });
            } catch (IOException e) {
                ex.abort();
                return;
            }
            StringBuilder b = new StringBuilder("attach locked " + lockedInAttach.get() + ",");
            try {
                ex.commit(200, none, 0);
                b.append("committed,");
            } catch (IllegalStateException | IOException e) {
                b.append(e.getClass().getSimpleName()).append(',');
            }
            try {
                ex.eventStream(200, none, out -> { });
                b.append("started,");
            } catch (IllegalStateException | IOException e) {
                b.append(e.getClass().getSimpleName()).append(',');
            }
            b.append(((TinyHttpdExchange) ex).slots().open());
            after.set(b.toString());
        });
        try (Socket s = new Socket("127.0.0.1", adapter.boundPort())) {
            try {
                s.getOutputStream().write(RawTargetSocketTest.Http.get("/sse").getBytes(StandardCharsets.ISO_8859_1));
                s.getOutputStream().flush();
                assertTrue(draining.await(5, TimeUnit.SECONDS));
                assertTrue("synchronized (exchange) while the drain waits", lockFree(exchange.get()));
                assertEquals("attach locked false,IllegalStateException,IllegalStateException,1", after.get());
            } finally {
                release.countDown();
            }
            TinyHttpdEventStream.Slots slots = ((TinyHttpdExchange) exchange.get()).slots();
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (slots.open() != 0 && System.nanoTime() < end) {
                Thread.sleep(5);
            }
            assertEquals("slot released after the stream ended", 0, slots.open());
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
