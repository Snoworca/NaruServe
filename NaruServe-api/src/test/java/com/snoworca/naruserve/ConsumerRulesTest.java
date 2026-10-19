package com.snoworca.naruserve;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import org.junit.Test;

public class ConsumerRulesTest {

    private static String rulesFromBuiltJar() throws Exception {
        String path = System.getProperty("naruserve.apiJar");
        assertNotNull("system property naruserve.apiJar", path);
        File jar = new File(path);
        assertTrue(jar + " exists", jar.isFile());
        try (JarFile f = new JarFile(jar)) {
            ZipEntry e = f.getEntry("META-INF/proguard/naruserve-api.pro");
            assertNotNull("META-INF/proguard/naruserve-api.pro in " + jar, e);
            try (InputStream in = f.getInputStream(e)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        }
    }

    // SDS-AC-2; IR-JSON-001 AC-3
    @Test
    public void sdsAc2_IR_JSON_001_AC3_builtJarShipsTheTypeRefAndCookieKeepRules() throws Exception {
        List<String> lines = Arrays.asList(rulesFromBuiltJar().split("\\r?\\n"));
        for (String rule : new String[] {"-keepattributes Signature,InnerClasses,EnclosingMethod",
                "-keep,allowobfuscation,allowshrinking class com.snoworca.naruserve.TypeRef",
                "-keep,allowobfuscation,allowshrinking class * extends com.snoworca.naruserve.TypeRef"}) {
            assertTrue(rule, lines.contains(rule));
        }
        boolean cookie = false;
        for (String l : lines) {
            cookie |= l.startsWith("-keepclassmembers class com.snoworca.naruserve.Cookie")
                    && l.contains("unvalidated(");
        }
        assertTrue("-keepclassmembers line for Cookie.unvalidated", cookie);
    }
}
