package com.snoworca.naruserve.tinyhttpd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.spi.HttpAdapter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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

    // SDS-AC-1; FR-ENGINE-010 AC-1
    @Test
    public void sdsAc1_FR_ENGINE_010_AC1_noEngineTypeInAnyPublicSignature() throws Exception {
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
        for (int i = 0; i < 3; i++) {
            try {
                if (i == 0) {
                    ex.sendFile(200, Collections.emptyList(), new File("missing"), 0, 0);
                } else if (i == 1) {
                    ex.eventStream(200, Collections.emptyList(), out -> { });
                } else {
                    ex.upgrade(Collections.emptyList(), (in, out) -> { });
                }
                sb.append("none,");
            } catch (Exception e) {
                sb.append(e.getClass().getSimpleName()).append(',');
            }
        }
        return sb.toString();
    }

    // FR-ENGINE-010 AC-2: the feature hooks share commit()'s lock, abort check and single-start rule, so later waves
    // fill TinyHttpdFeatures without racing an abort or starting the response twice
    @Test(timeout = 20000)
    public void FR_ENGINE_010_AC2_featureHooksShareTheCommitLockAbortCheckAndSingleStart() throws Exception {
        for (String name : new String[] {"commit", "sendFile", "eventStream", "upgrade"}) {
            for (Method m : TinyHttpdExchange.class.getDeclaredMethods()) {
                if (m.getName().equals(name)) {
                    assertTrue(name, Modifier.isSynchronized(m.getModifiers()));
                }
            }
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
        assertEquals("IllegalStateException,IllegalStateException,IllegalStateException,", afterCommit.get());
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
        assertEquals("IOException,IOException,IOException,", afterAbort.get());
    }

    // FR-ENGINE-010 AC-2: from wave 6 sendFile is a buffered copy that throws no UnsupportedOperationException; the
    // event-stream and upgrade hooks still throw naming wave 7
    @Test(timeout = 20000)
    public void FR_ENGINE_010_AC2_sendFileIsFilledAndTheOtherHooksThrowNamingWave7() throws Exception {
        File empty = File.createTempFile("naru-empty", ".bin");
        empty.deleteOnExit();
        AtomicReference<String> seen = new AtomicReference<>();
        adapter.bind(RawTargetSocketTest.Http.settings().build(), ex -> {
            StringBuilder sb = new StringBuilder();
            try {
                ex.eventStream(200, Collections.emptyList(), out -> { });
            } catch (UnsupportedOperationException | IOException e) {
                sb.append(e.getMessage()).append('|');
            }
            try {
                ex.upgrade(Collections.emptyList(), (in, out) -> { });
            } catch (UnsupportedOperationException | IOException e) {
                sb.append(e.getMessage()).append('|');
            }
            try {
                ex.sendFile(200, Collections.<java.util.Map.Entry<String, String>>singletonList(
                        new java.util.AbstractMap.SimpleEntry<>("Content-Length", "0")), empty, 0, 0);
                sb.append("sent");
                ex.finish();
            } catch (RuntimeException | IOException e) {
                sb.append(e);
                ex.abort();
            }
            seen.set(sb.toString());
        });
        assertEquals(200, RawTargetSocketTest.Http.status(
                RawTargetSocketTest.Http.send(adapter.boundPort(), RawTargetSocketTest.Http.get("/f"))));
        String[] parts = seen.get().split("\\|", -1);
        assertEquals(seen.get(), 3, parts.length);
        assertTrue(parts[0], parts[0].contains("wave 7"));
        assertTrue(parts[1], parts[1].contains("wave 7"));
        assertEquals("sent", parts[2]);
        // The hooks live in their own class, not in the exchange bridge.
        assertTrue(classText(new File(TinyHttpdAdapter.class.getResource("TinyHttpdExchange.class").toURI()))
                .contains("com/snoworca/naruserve/tinyhttpd/TinyHttpdFeatures"));
    }

    // SDS-AC-3; FR-ENGINE-010 AC-3
    @Test
    public void sdsAc3_FR_ENGINE_010_AC3_noClassReferencesTlsConfig() throws Exception {
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
