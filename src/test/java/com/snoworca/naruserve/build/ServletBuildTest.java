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

    // CON-SVL-001 AC-4 records the historical wave-8 untouched-source checkpoint.
    // CON-APP-010 AC-5; IR-HTTP-009 AC-1; FR-APP-023 AC-8; FR-ENGINE-012:
    // version that checkpoint with precise additive defaults and retained SPI/Servlet/Tiny isolation.
    @Test
    public void originalSpiDeclarationsAndIsolationSurviveOptionalLifetimeHooks() throws Exception {
        String spi = "NaruServe-api/src/main/java/com/snoworca/naruserve/spi";
        Set<String> originalFiles = new TreeSet<>(Arrays.asList(
                BuildLayoutTest.git(ROOT, "ls-tree", "--name-only", "f7da71d", spi + "/").trim().split("\\n")));
        Set<String> currentFiles = new TreeSet<>();
        for (File file : BuildLayoutTest.walk(new File(ROOT, spi), ".java")) {
            String relative = ROOT.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/');
            currentFiles.add(relative);
            String baseline = BuildLayoutTest.git(ROOT, "show", "f7da71d:" + relative);
            String current = BuildLayoutTest.read(file);
            assertEquals("all original SPI declarations/signatures retained: " + relative,
                    originalSpiDeclarations(baseline, file.getName()), originalSpiDeclarations(current, file.getName()));
            assertFalse(relative, current.contains("javax.servlet") || current.contains("jakarta.servlet"));
            assertFalse(relative, current.contains("com.snoworca.tinyhttpd") || current.contains("com.snoworca.naruserve.tinyhttpd"));
        }
        assertEquals("no additional SPI source is silently admitted", originalFiles, currentFiles);
        assertTrue(com.snoworca.naruserve.spi.StreamDrain.class.getMethod("abort").isDefault());
        java.lang.reflect.Method onAbort = com.snoworca.naruserve.spi.HttpExchange.class.getMethod("onAbort", Runnable.class);
        assertTrue(onAbort.isDefault());
        assertEquals(void.class, onAbort.getReturnType());
        java.lang.reflect.Method retain = com.snoworca.naruserve.spi.HttpExchange.class.getMethod("retainRequest");
        assertTrue(retain.isDefault());
        assertEquals(Runnable.class, retain.getReturnType());
        assertEquals("", BuildLayoutTest.git(ROOT, "status", "--porcelain", "--", spi));
        BuildLayoutTest.assertTinyHttpdClean();
    }

    // Preserve every original declaration, including generic types, throws clauses and existing
    // default bodies. Remove ONLY the three explicitly allowed optional callback declarations.
    private static String originalSpiDeclarations(String source) {
        return originalSpiDeclarations(source, "HttpExchange.java");
    }

    private static String originalSpiDeclarations(String source, String fileName) {
        String stripped = source.replaceAll("(?s)/\\*.*?\\*/|//[^\\r\\n]*", "");
        if (fileName.equals("HttpExchange.java")) {
            stripped = removeOptionalDefault(stripped, "default\\s+void\\s+onAbort\\s*\\(\\s*Runnable\\s+listener\\s*\\)");
            stripped = removeOptionalDefault(stripped, "default\\s+Runnable\\s+retainRequest\\s*\\(\\s*\\)");
        }
        return fileName.equals("StreamDrain.java") ? originalDrainDeclarations(stripped)
                : stripped.replaceAll("\\s+", "");
    }

    private static String removeOptionalDefault(String source, String signature) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(signature + "\\s*\\{").matcher(source);
        if (!matcher.find()) return source;
        int start = matcher.start(), end = matcher.end(), depth = 1;
        char quote = 0;
        boolean escaped = false;
        while (end < source.length() && depth > 0) {
            char c = source.charAt(end++);
            if (quote != 0) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == quote) quote = 0;
            } else if (c == '"' || c == '\'') quote = c;
            else if (c == '{') depth++;
            else if (c == '}') depth--;
        }
        assertEquals("well-formed optional default body", 0, depth);
        return source.substring(0, start) + source.substring(end);
    }

    // IR-HTTP-009 AC-1; FR-APP-023 AC-8; CON-SVL-001 AC-4:
    // the exception cannot hide a changed abstract signature, mandatory hook or unrelated default.
    @Test
    public void optionalLifetimeHooksRejectUnrelatedOrMandatorySpiChanges() {
        String original = "interface HttpExchange { OutputStream commit(int status) throws IOException; void finish() throws IOException; }";
        String permitted = original.substring(0, original.lastIndexOf('}'))
                + "default void onAbort(Runnable listener) { java.util.Objects.requireNonNull(listener); } "
                + "default Runnable retainRequest() { return () -> { }; } }";
        assertEquals(originalSpiDeclarations(original), originalSpiDeclarations(permitted));
        for (String wrongOwner : new String[] {"HttpAdapter.java", "StreamDrain.java", "ExchangeHandler.java"}) {
            assertFalse("hooks cannot migrate to " + wrongOwner,
                    originalSpiDeclarations(original, wrongOwner).equals(originalSpiDeclarations(permitted, wrongOwner)));
        }
        for (String changed : new String[] {
                permitted.replace("OutputStream commit", "InputStream commit"),
                permitted.replace("commit(int status)", "commit(long status)"),
                permitted.replace("void finish() throws IOException", "void finish()"),
                permitted.replace("default void onAbort(Runnable listener) { java.util.Objects.requireNonNull(listener); }", "void onAbort(Runnable listener);"),
                permitted.replace("default Runnable retainRequest() { return () -> { }; }", "Runnable retainRequest();"),
                permitted.replace("default Runnable retainRequest()", "default Object retainRequest()"),
                permitted.replace("Runnable listener", "String listener"),
                permitted.replace("default Runnable retainRequest()", "default Runnable retainRequest(int extra)"),
                permitted.replace("void finish()", "default void extra() { } void finish()")}) {
            assertFalse(changed, originalSpiDeclarations(original).equals(originalSpiDeclarations(changed)));
        }
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
