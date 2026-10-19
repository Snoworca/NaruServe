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

    // SDS-AC-1; FR-ENGINE-001 AC-1
    @Test
    public void sdsAc1_FR_ENGINE_001_AC1_noEngineTypeInAnyPublicSignature() throws Exception {
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

    // SDS-AC-2; FR-ENGINE-001 AC-2
    @Test(timeout = 20000)
    public void sdsAc2_FR_ENGINE_001_AC2_featureHooksThrowNamingTheirWave() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        adapter.bind(RawTargetSocketTest.Http.settings().build(), RawTargetSocketTest.Http.ok(ex -> {
            StringBuilder sb = new StringBuilder();
            try {
                ex.sendFile(200, Collections.emptyList(), new File("missing"), 0, 0);
            } catch (UnsupportedOperationException e) {
                sb.append(e.getMessage()).append('|');
            }
            try {
                ex.eventStream(200, Collections.emptyList(), out -> { });
            } catch (UnsupportedOperationException e) {
                sb.append(e.getMessage()).append('|');
            }
            try {
                ex.upgrade(Collections.emptyList(), (in, out) -> { });
            } catch (UnsupportedOperationException e) {
                sb.append(e.getMessage());
            }
            seen.set(sb.toString());
        }));
        assertEquals(200, RawTargetSocketTest.Http.status(
                RawTargetSocketTest.Http.send(adapter.boundPort(), RawTargetSocketTest.Http.get("/f"))));
        String[] parts = seen.get().split("\\|", -1);
        assertEquals(seen.get(), 3, parts.length);
        assertTrue(parts[0], parts[0].contains("wave 6"));
        assertTrue(parts[1], parts[1].contains("wave 7"));
        assertTrue(parts[2], parts[2].contains("wave 7"));
        // The hooks live in their own class, not in the exchange bridge.
        assertTrue(classText(new File(TinyHttpdAdapter.class.getResource("TinyHttpdExchange.class").toURI()))
                .contains("com/snoworca/naruserve/tinyhttpd/TinyHttpdFeatures"));
    }

    // SDS-AC-3; FR-ENGINE-001 AC-3
    @Test
    public void sdsAc3_FR_ENGINE_001_AC3_noClassReferencesTlsConfig() throws Exception {
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
}
