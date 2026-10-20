package com.snoworca.naruserve.build;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

    // SDS-AC-8; CON-SVL-001 AC-1, CON-SVL-007 AC-1
    @Test
    public void sdsAc8_CON_SVL_001_AC1_servletClasspaths() throws IOException {
        Set<String> compile = BuildLayoutTest.components(SERVLET, "compileClasspath");
        assertEquals(new TreeSet<>(Arrays.asList("com.snoworca.naruserve:NaruServe-api",
                "javax.servlet:javax.servlet-api:3.1.0", "javax.websocket:javax.websocket-api:1.1",
                "org.jspecify:jspecify:1.0.0")), compile);
        assertEquals(new TreeSet<>(Arrays.asList("com.snoworca.naruserve:NaruServe-api")),
                BuildLayoutTest.components(SERVLET, "runtimeClasspath"));
    }

    // SDS-AC-9; CON-SVL-001 AC-2, CON-SVL-003 AC-1
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

    // SDS-AC-10; CON-SVL-001 AC-3
    @Test
    public void sdsAc10_CON_SVL_001_AC3_classReferencesAndGson() throws IOException {
        String build = BuildLayoutTest.read(new File(ROOT, "build.gradle"));
        assertTrue(build.contains("if (path == ':NaruServe-servlet') {"));
        assertFalse(BuildLayoutTest.read(new File(ROOT, "settings.gradle")).contains("jakarta"));
        List<File> servletClasses = BuildLayoutTest.walk(new File(ROOT, SERVLET + "/build/classes/java/main"), ".class");
        assertTrue("servlet main classes were built", servletClasses.size() >= 5);
        for (File f : servletClasses) {
            assertFalse(f.getPath(), BuildLayoutTest.read(f, StandardCharsets.ISO_8859_1).contains("jakarta/servlet/"));
        }
        for (String module : BuildLayoutTest.MODULES) {
            if (!module.equals(SERVLET) && !module.equals("NaruServe-slf4j")) {
                for (File f : BuildLayoutTest.walk(new File(ROOT, module + "/build/classes"), ".class")) {
                    if (f.getPath().contains(File.separator + "main" + File.separator)) {
                        assertFalse(f.getPath(), BuildLayoutTest.read(f, StandardCharsets.ISO_8859_1).contains("javax/servlet/"));
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

    // SDS-AC-11; CON-SVL-001 AC-4 — f7da71d closes wave 7 (after its lane merge and audit fixes); wave 8 starts there
    @Test
    public void sdsAc11_CON_SVL_001_AC4_spiAndTinyHttpdUntouched() throws Exception {
        String spi = "NaruServe-api/src/main/java/com/snoworca/naruserve/spi";
        String drain = spi + "/StreamDrain.java";
        assertEquals("", BuildLayoutTest.git(ROOT, "diff", "--stat", "f7da71d", "--", spi, ":(exclude)" + drain));
        String baseline = BuildLayoutTest.git(ROOT, "show", "f7da71d:" + drain);
        String current = BuildLayoutTest.read(new File(ROOT, drain));
        assertEquals("only the documented optional cancellation callback may be added",
                originalDrainDeclarations(baseline), originalDrainDeclarations(current));
        assertTrue(com.snoworca.naruserve.spi.StreamDrain.class.getMethod("abort").isDefault());
        assertEquals("", BuildLayoutTest.git(ROOT, "status", "--porcelain", "--", spi));
        BuildLayoutTest.assertTinyHttpdClean();
    }
    /** Removes comments, whitespace and only the explicitly permitted empty default cancellation callback. */
    private static String originalDrainDeclarations(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/|//[^\\r\\n]*", "")
                .replaceAll("default\\s+void\\s+abort\\s*\\(\\s*\\)\\s*\\{\\s*\\}", "")
                .replaceAll("\\s+", "");
    }

    // CON-SVL-001 AC-4; IR-HTTP-007 AC-3: the exception must not permit unrelated or mandatory SPI changes.
    @Test
    public void cancellationExceptionRejectsOtherSignatureAndMethodChanges() {
        String original = "interface StreamDrain { void drain(OutputStream out) throws IOException; }";
        String permitted = original.replace("}", "default void abort() { } }");
        assertEquals(originalDrainDeclarations(original), originalDrainDeclarations(permitted));
        for (String changed : new String[] {permitted.replace("void drain", "int drain"),
                permitted.replace("default void abort() { }", "void abort();"),
                permitted.replace("default void abort() { }", "default void abort() { throw new RuntimeException(); }"),
                permitted.replace("default void abort() { }", "default void abort() { } default void extra() { }")}) {
            assertFalse(changed, originalDrainDeclarations(original).equals(originalDrainDeclarations(changed)));
        }
    }

}
