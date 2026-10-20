package com.snoworca.naruserve.build;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.Test;

/** Build placement of NaruServe-servlet, read from the checkDependencyRules report and the module outputs. */
public class ServletBuildTest {

    private static final File ROOT = new File(BuildLayoutTest.property("naruserve.rootDir"));
    private static final String SERVLET = "NaruServe-servlet";
    private static final List<String> CONFIGURATIONS = Arrays.asList("compileClasspath", "runtimeClasspath",
            "testCompileClasspath", "testRuntimeClasspath");
    private static final String JETTY = "9.4.58.v20250814";

    private static boolean jettyOrServlet(String component) {
        return component.startsWith("org.eclipse.jetty") || component.startsWith("javax.servlet:")
                || component.startsWith("jakarta.servlet:");
    }

    // SDS-AC-8; CON-SVL-001 AC-1
    @Test
    public void sdsAc8_CON_SVL_001_AC1_servletClasspaths() throws IOException {
        Set<String> compile = BuildLayoutTest.components(SERVLET, "compileClasspath");
        assertEquals(new TreeSet<>(Arrays.asList("com.snoworca.naruserve:NaruServe-api",
                "javax.servlet:javax.servlet-api:3.1.0", "javax.websocket:javax.websocket-api:1.1",
                "org.jspecify:jspecify:1.0.0")), compile);
        assertEquals(new TreeSet<>(Arrays.asList("com.snoworca.naruserve:NaruServe-api")),
                BuildLayoutTest.components(SERVLET, "runtimeClasspath"));
    }

    // SDS-AC-9; CON-SVL-001 AC-2
    @Test
    public void sdsAc9_CON_SVL_001_AC2_testScopeOnlyDependencies() throws IOException {
        Set<String> testCompile = BuildLayoutTest.components(SERVLET, "testCompileClasspath");
        for (String jetty : new String[] {"jetty-servlet", "jetty-webapp", "jetty-annotations"}) {
            assertTrue(testCompile.toString(), testCompile.contains("org.eclipse.jetty:" + jetty + ":" + JETTY));
        }
        assertTrue(testCompile.contains("com.snoworca.naruserve:NaruServe-gson"));
        assertTrue(BuildLayoutTest.components(SERVLET, "testRuntimeClasspath")
                .contains("com.snoworca.naruserve:NaruServe-gson"));
        // The fixtures share the NaruServe-api component in the report, so the declaration is read instead.
        String build = BuildLayoutTest.read(new File(ROOT, "build.gradle"));
        int block = build.indexOf("project(':NaruServe-servlet') {");
        assertTrue("build.gradle has a :NaruServe-servlet block", block >= 0);
        String servletBlock = build.substring(block, build.indexOf("\n}\n", block));
        assertTrue(servletBlock, servletBlock.contains("testImplementation testFixtures(project(':NaruServe-api'))"));
        assertTrue(servletBlock, servletBlock.contains("testImplementation project(':NaruServe-gson')"));

        Set<String> holding = new TreeSet<>();
        for (String module : BuildLayoutTest.MODULES) {
            for (String cn : CONFIGURATIONS) {
                for (String c : BuildLayoutTest.components(module, cn)) {
                    if (c.startsWith("org.eclipse.jetty")) {
                        holding.add(module + "|" + cn);
                    }
                    if (jettyOrServlet(c)) {
                        assertEquals(c + " on " + cn + " of " + module, SERVLET, module);
                    }
                    if (c.equals("com.snoworca.naruserve:NaruServe-gson") && module.equals(SERVLET)) {
                        assertTrue(cn, cn.startsWith("test"));
                    }
                }
            }
        }
        assertEquals(new TreeSet<>(Arrays.asList(SERVLET + "|testCompileClasspath", SERVLET + "|testRuntimeClasspath")),
                holding);
    }

    private static String bytes(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.ISO_8859_1);
        }
    }

    private static List<File> classes(File dir) {
        List<File> found = new ArrayList<>();
        File[] children = dir.listFiles();
        if (children != null) {
            for (File c : children) {
                if (c.isDirectory()) {
                    found.addAll(classes(c));
                } else if (c.getName().endsWith(".class")) {
                    found.add(c);
                }
            }
        }
        return found;
    }

    // SDS-AC-10; CON-SVL-001 AC-3
    @Test
    public void sdsAc10_CON_SVL_001_AC3_classReferencesAndGson() throws IOException {
        String build = BuildLayoutTest.read(new File(ROOT, "build.gradle"));
        assertTrue(build.contains("if (path == ':NaruServe-servlet') {"));
        assertFalse(BuildLayoutTest.read(new File(ROOT, "settings.gradle")).contains("jakarta"));
        List<File> servletClasses = classes(new File(ROOT, SERVLET + "/build/classes/java/main"));
        assertTrue("servlet main classes were built", servletClasses.size() >= 5);
        for (File f : servletClasses) {
            assertFalse(f.getPath(), bytes(f).contains("jakarta/servlet/"));
        }
        for (String module : BuildLayoutTest.MODULES) {
            if (!module.equals(SERVLET) && !module.equals("NaruServe-slf4j")) {
                for (File f : classes(new File(ROOT, module + "/build/classes"))) {
                    if (f.getPath().contains(File.separator + "main" + File.separator)) {
                        assertFalse(f.getPath(), bytes(f).contains("javax/servlet/"));
                    }
                }
            }
            for (String cn : CONFIGURATIONS) {
                for (String c : BuildLayoutTest.components(module, cn)) {
                    assertFalse(c + " on " + cn + " of " + module, c.startsWith("jakarta."));
                    if (c.startsWith("com.google.code.gson:gson:")) {
                        assertEquals(cn + " of " + module, "com.google.code.gson:gson:2.14.0", c);
                    }
                }
            }
        }
        assertTrue(BuildLayoutTest.components(SERVLET, "testRuntimeClasspath")
                .contains("com.google.code.gson:gson:2.14.0"));
    }

    private static String git(String... args) throws Exception {
        List<String> command = new ArrayList<>(Arrays.asList("git", "--no-optional-locks", "-C", ROOT.getPath()));
        command.addAll(Arrays.asList(args));
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        String out;
        try (InputStream in = p.getInputStream()) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0) {
                buf.write(b, 0, n);
            }
            out = new String(buf.toByteArray(), StandardCharsets.UTF_8);
        }
        assertEquals(out, 0, p.waitFor());
        return out;
    }

    // SDS-AC-11; CON-SVL-001 AC-4 — f7da71d closes wave 7 (after its lane merge and audit fixes); wave 8 starts there
    @Test
    public void sdsAc11_CON_SVL_001_AC4_spiAndTinyHttpdUntouched() throws Exception {
        String spi = "NaruServe-api/src/main/java/com/snoworca/naruserve/spi";
        assertEquals("", git("diff", "--stat", "f7da71d", "--", spi));
        assertEquals("", git("status", "--porcelain", "--", spi));
        BuildLayoutTest.assertTinyHttpdClean();
    }
}
