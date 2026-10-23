package com.snoworca.naruserve.build;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

/**
 * Inspects the root build's files and the outputs of the module builds that run before this test.
 */
public class BuildLayoutTest {

    public static final List<String> MODULES = Arrays.asList("NaruServe-api", "NaruServe-servlet", "NaruServe-tinyhttpd",
            "NaruServe-gson", "NaruServe", "NaruServe-kotlin", "NaruServe-slf4j", "NaruServe-filelog",
            "NaruServe-logcat");

    private static final File ROOT = new File(property("naruserve.rootDir"));

    static String property(String name) {
        String value = System.getProperty(name);
        assertNotNull("system property " + name + " is set by the root build", value);
        return value;
    }

    static String read(File file) throws IOException {
        return read(file, StandardCharsets.UTF_8);
    }

    static String read(File file, Charset charset) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            return new String(drain(in), charset);
        }
    }

    private static byte[] drain(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    static List<File> walk(File dir, String suffix) {
        List<File> found = new ArrayList<>();
        File[] children = dir.listFiles();
        if (children == null) {
            return found;
        }
        Arrays.sort(children);
        for (File child : children) {
            if (child.isDirectory()) {
                found.addAll(walk(child, suffix));
            } else if (child.getName().endsWith(suffix)) {
                found.add(child);
            }
        }
        return found;
    }

    // SDS-AC-1; CON-BUILD-002 AC-1, CON-BUILD-002 AC-2, CON-BUILD-002 AC-3
    @Test
    public void sdsAc1_CON_BUILD_002_AC1_AC2_AC3_settingsIncludesNineModulesAndRootName() throws IOException {
        String settings = read(new File(ROOT, "settings.gradle"));
        Set<String> included = new TreeSet<>();
        Matcher m = Pattern.compile("include\\s*\\(?\\s*['\"]:([^'\"]+)['\"]").matcher(settings);
        while (m.find()) {
            included.add(m.group(1));
        }
        assertEquals(new TreeSet<>(MODULES), included);
        assertTrue("rootProject.name is NaruServe-build",
                Pattern.compile("rootProject\\.name\\s*=\\s*['\"]NaruServe-build['\"]").matcher(settings).find());
        for (String module : MODULES) {
            if (module.startsWith("NaruServe-")) {
                continue;
            }
            assertEquals("the only module without a suffix is the bootstrap NaruServe", "NaruServe", module);
        }
    }

    // SDS-AC-2; CON-BUILD-005 AC-1
    @Test
    public void sdsAc2_CON_BUILD_005_AC1_mainSourcesUseModulePackages() throws IOException {
        Map<String, String> packages = new LinkedHashMap<>();
        packages.put("NaruServe-api", "com.snoworca.naruserve");
        packages.put("NaruServe-servlet", "com.snoworca.naruserve.servlet");
        packages.put("NaruServe-tinyhttpd", "com.snoworca.naruserve.tinyhttpd");
        packages.put("NaruServe-gson", "com.snoworca.naruserve.gson");
        packages.put("NaruServe", "com.snoworca.naruserve.bootstrap");
        packages.put("NaruServe-kotlin", "com.snoworca.naruserve.kotlin");
        packages.put("NaruServe-slf4j", "com.snoworca.naruserve.slf4j");
        packages.put("NaruServe-filelog", "com.snoworca.naruserve.filelog");
        packages.put("NaruServe-logcat", "com.snoworca.naruserve.logcat");
        assertEquals(new HashSet<>(MODULES), packages.keySet());

        Pattern packageLine = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)");
        int checked = 0;
        for (Map.Entry<String, String> e : packages.entrySet()) {
            File main = new File(ROOT, e.getKey() + "/src/main");
            List<File> sources = walk(main, ".java");
            sources.addAll(walk(main, ".kt"));
            for (File source : sources) {
                Matcher m = packageLine.matcher(read(source));
                assertTrue(source + " declares a package", m.find());
                String pkg = m.group(1);
                assertTrue(source + " package " + pkg + " is under " + e.getValue(),
                        pkg.equals(e.getValue()) || pkg.startsWith(e.getValue() + "."));
                checked++;
            }
        }
        assertTrue("main sources were found", checked >= 7);
        assertTrue("Maven group is com.snoworca.naruserve",
                read(new File(ROOT, "build.gradle")).contains("group = 'com.snoworca.naruserve'"));
    }

    private static int majorVersion(File classFile) throws IOException {
        try (DataInputStream in = new DataInputStream(new FileInputStream(classFile))) {
            assertEquals(classFile + " is a class file", 0xCAFEBABE, in.readInt());
            in.readUnsignedShort();
            return in.readUnsignedShort();
        }
    }

    // SDS-AC-6; CON-BUILD-004 AC-1, CON-BUILD-004 AC-2
    @Test
    public void sdsAc6_CON_BUILD_004_AC1_AC2_allMainAndTestClassesAreMajor52() throws IOException {
        int checked = 0;
        Set<String> seenOutputs = new TreeSet<>();
        for (String module : MODULES) {
            File[] languages = new File(ROOT, module + "/build/classes").listFiles();
            if (languages == null) {
                continue;
            }
            for (File language : languages) {
                for (String sourceSet : new String[] {"main", "test"}) {
                    for (File classFile : walk(new File(language, sourceSet), ".class")) {
                        assertEquals(classFile.getPath(), 52, majorVersion(classFile));
                        seenOutputs.add(module + "/" + language.getName() + "/" + sourceSet);
                        checked++;
                    }
                }
            }
        }
        assertTrue("found classes: " + seenOutputs, checked > 0);
        assertTrue(seenOutputs.toString(), seenOutputs.containsAll(Arrays.asList(
                "NaruServe-api/java/test", "NaruServe-api/kotlin/test",
                "NaruServe-kotlin/kotlin/main", "NaruServe-kotlin/kotlin/test")));
    }

    /** Lines of the checkDependencyRules report: {@code :module|configuration|group:name:version}. */
    private static List<String[]> dependencyReport() throws IOException {
        List<String[]> rows = new ArrayList<>();
        for (String line : read(new File(property("naruserve.depReport"))).split("\n")) {
            if (!line.trim().isEmpty()) {
                rows.add(line.trim().split("\\|"));
            }
        }
        return rows;
    }

    // SDS-AC-7; CON-BUILD-011 AC-1, CON-BUILD-006 AC-2
    @Test
    public void sdsAc7_CON_BUILD_011_AC1_jspecifyCompileOnlyEverywhereAndNeverAtRuntime() throws IOException {
        Set<String> runtimeListed = new TreeSet<>();
        Set<String> jspecifyCompile = new TreeSet<>();
        for (String[] row : dependencyReport()) {
            String module = row[0].substring(1);
            if (row[1].equals("runtimeClasspath")) {
                runtimeListed.add(module);
                assertFalse("jspecify on " + row[1] + " of " + module, row[2].startsWith("org.jspecify:"));
            }
            if (row[1].equals("compileClasspath") && row[2].equals("org.jspecify:jspecify:1.0.0")) {
                jspecifyCompile.add(module);
            }
        }
        assertEquals(new TreeSet<>(MODULES), runtimeListed);
        assertEquals(new TreeSet<>(MODULES), jspecifyCompile);
    }

    // CON-BUILD-006 AC-3
    @Test
    public void con_BUILD_006_AC3_apiRuntimeClasspathHoldsNoArtifact() throws IOException {
        for (String[] row : dependencyReport()) {
            if (row[0].equals(":NaruServe-api") && row[1].equals("runtimeClasspath")) {
                assertTrue("only the api project itself: " + row[2],
                        row[2].startsWith("com.snoworca.naruserve:NaruServe-api:"));
            }
        }
    }

    /** Resolved {@code group:name} of one module's configuration, without the module itself. */
    static Set<String> components(String module, String configuration) throws IOException {
        Set<String> found = new TreeSet<>();
        for (String[] row : dependencyReport()) {
            if (row[0].equals(":" + module) && row[1].equals(configuration)) {
                String[] gav = row[2].split(":");
                String ga = gav[0] + ":" + gav[1];
                if (!ga.equals("com.snoworca.naruserve:" + module)) {
                    found.add(row[2].startsWith("com.snoworca.naruserve:") ? ga : row[2]);
                }
            }
        }
        return found;
    }

    private static Set<String> set(String... items) {
        return new TreeSet<>(Arrays.asList(items));
    }

    // CON-BUILD-006 AC-1, CON-BUILD-006 AC-4, CON-BUILD-006 AC-6, CON-BUILD-006 AC-7, CON-BUILD-009 AC-1
    @Test
    public void con_BUILD_006_AC1_AC4_AC6_AC7_runtimeClasspathsHoldExactlyTheDeclaredMainDependencies()
            throws IOException {
        String api = "com.snoworca.naruserve:NaruServe-api";
        String core = "com.snoworca:tinyhttpd-core:3.0.0";
        String websocket = "com.snoworca:tinyhttpd-websocket:3.0.0";
        String gson = "com.google.code.gson:gson:2.14.0";
        // Gson 2.14.0 itself brings error_prone_annotations; AC-4 limits declarations, not transitive edges.
        String gsonTransitive = "com.google.errorprone:error_prone_annotations:2.48.0";
        assertEquals(set(), components("NaruServe-api", "runtimeClasspath"));
        assertEquals(set(api), components("NaruServe-servlet", "runtimeClasspath"));
        assertEquals(set(api, core, websocket), components("NaruServe-tinyhttpd", "runtimeClasspath"));
        assertEquals(set(api, gson, gsonTransitive), components("NaruServe-gson", "runtimeClasspath"));
        assertEquals(set(api, "com.snoworca.naruserve:NaruServe-tinyhttpd", "com.snoworca.naruserve:NaruServe-gson",
                "com.snoworca.naruserve:NaruServe-logcat", core, websocket, gson, gsonTransitive),
                components("NaruServe", "runtimeClasspath"));
        assertEquals(set(api, "org.jetbrains.kotlin:kotlin-stdlib:2.2.0", "org.jetbrains:annotations:13.0"),
                components("NaruServe-kotlin", "runtimeClasspath"));
        assertEquals(set(api, "org.slf4j:slf4j-api:1.7.36"), components("NaruServe-slf4j", "runtimeClasspath"));
        assertEquals(set(api), components("NaruServe-filelog", "runtimeClasspath"));
        assertEquals(set(api), components("NaruServe-logcat", "runtimeClasspath"));
    }

    // CON-BUILD-006 AC-5, CON-BUILD-006 AC-9, CON-BUILD-009 AC-4
    @Test
    public void con_BUILD_006_AC5_AC9_compileOnlyServletApisAndGsonExposedToBootstrap() throws IOException {
        Set<String> servletCompile = components("NaruServe-servlet", "compileClasspath");
        assertTrue(servletCompile.toString(), servletCompile.containsAll(set(
                "javax.servlet:javax.servlet-api:3.1.0", "javax.websocket:javax.websocket-api:1.1")));
        Set<String> bootstrapCompile = components("NaruServe", "compileClasspath");
        assertTrue(bootstrapCompile.toString(), bootstrapCompile.contains("com.google.code.gson:gson:2.14.0"));
        for (String c : bootstrapCompile) {
            assertFalse("TinyHttpd is implementation-scope: " + c, c.startsWith("com.snoworca:tinyhttpd-"));
        }
    }

    // CON-BUILD-006 AC-8
    @Test
    public void con_BUILD_006_AC8_apiHasTestFixtures() {
        assertTrue(new File(ROOT, "NaruServe-api/build/libs/NaruServe-api-0.1.0-SNAPSHOT-test-fixtures.jar").isFile());
    }

    // CON-BUILD-007 AC-1, CON-BUILD-007 AC-2
    @Test
    public void con_BUILD_007_AC1_AC2_kotlinStdlibOnlyInKotlinModule() throws IOException {
        assertTrue(read(new File(ROOT, "gradle.properties")).contains("kotlin.stdlib.default.dependency=false"));
        for (String module : MODULES) {
            for (String configuration : new String[] {"compileClasspath", "runtimeClasspath"}) {
                boolean stdlib = components(module, configuration).contains("org.jetbrains.kotlin:kotlin-stdlib:2.2.0");
                assertEquals(module + " " + configuration, module.equals("NaruServe-kotlin"), stdlib);
            }
        }
    }

    // OPS-BUILD-002 AC-1, OPS-BUILD-002 AC-5
    @Test
    public void ops_BUILD_002_AC1_AC5_junitAndKotlinTestStdlibInEveryModule() throws IOException {
        for (String module : MODULES) {
            Set<String> testCompile = components(module, "testCompileClasspath");
            assertTrue(module + " " + testCompile, testCompile.contains("junit:junit:4.13.2"));
            assertTrue(module + " " + testCompile, testCompile.contains("org.jetbrains.kotlin:kotlin-stdlib:2.2.0"));
        }
    }

    // SDS-AC-5, SDS-AC-6; CON-BUILD-017 AC-1, FR-REL-001 AC-5 (re-pointed from CON-BUILD-003 AC-1, body unchanged)
    @Test
    public void con_BUILD_017_AC1_rootBuildFileHoldsPluginsCompileDependenciesAndChecks() throws IOException {
        String build = read(new File(ROOT, "build.gradle"));
        for (String part : new String[] {"'java-library'", "'org.jetbrains.kotlin.jvm'", "'ru.vyarus.animalsniffer'",
                "options.release = 8", "checkClassReferences", "checkDependencyRules", "configureDependencies"}) {
            assertTrue("root build.gradle holds " + part, build.contains(part));
        }
    }

    // SDS-AC-12; CON-KT-001 AC-4 — the coroutine ban is its own task, the wave 1 prefix lists stay as pinned
    @Test
    public void con_KT_001_AC4_checkNoCoroutinesLeavesTheClassReferencePrefixesUnchanged() throws IOException {
        String build = read(new File(ROOT, "build.gradle"));
        assertTrue(build.contains("prefixes += ['android/', 'jakarta/servlet/', 'com/google/gson/', "
                + "'com/snoworca/tinyhttpd/', 'kotlin/']"));
        assertTrue(build.contains("def prefixes = path in androidModules ? ['javax/servlet/'] : []"));
        assertTrue(build.contains("tasks.register('checkNoCoroutines')"));
        assertTrue(build.contains("tasks.named('check') { dependsOn checkNoCoroutines }"));
        int prefixes = build.indexOf("def classRefPrefixes");
        String prefixFunction = build.substring(prefixes, build.indexOf("\n}\n", prefixes));
        assertFalse(prefixFunction, prefixFunction.contains("kotlinx/"));
    }

    // OPS-BUILD-003 AC-5
    @Test
    public void ops_BUILD_003_AC5_servletDocKeepsJakartaMarker() throws IOException {
        assertTrue(read(new File(ROOT, "docs/api/servlet.md")).contains("jakarta"));
    }

    // SDS-AC-7; CON-BUILD-001 AC-1
    @Test
    public void con_BUILD_001_AC1_gradleWrapperIsTheOnlyBuildTool() throws IOException {
        assertTrue(read(new File(ROOT, "gradle/wrapper/gradle-wrapper.properties"))
                .contains("gradle-8.14.3-bin.zip"));
        for (String name : new String[] {"pom.xml", "build.xml", "mvnw", "mvnw.cmd", ".mvn", "build.sbt",
                "BUILD.bazel", "WORKSPACE"}) {
            assertFalse(name + " must not exist", new File(ROOT, name).exists());
        }
    }

    // SDS-AC-5, SDS-AC-7; CON-BUILD-017 AC-2, CON-BUILD-014 AC-1, FR-REL-001 AC-5 (re-pointed from CON-BUILD-003 AC-2, body unchanged)
    @Test
    public void con_BUILD_017_AC2_noModuleBuildFile() {
        for (String module : MODULES) {
            for (String name : new String[] {"build.gradle", "build.gradle.kts", "pom.xml"}) {
                assertFalse(module + "/" + name, new File(ROOT, module + "/" + name).exists());
            }
        }
    }

    // SDS-AC-5, SDS-AC-6; CON-BUILD-017 AC-1, FR-REL-001 AC-5
    @Test
    public void con_BUILD_017_AC1_onlyReferenceToAnotherScriptIsTheGuardedReleaseApply() throws IOException {
        String build = read(new File(ROOT, "build.gradle"));
        Matcher applies = Pattern.compile("apply\\s+from\\s*:").matcher(build);
        int count = 0;
        while (applies.find()) {
            count++;
        }
        assertEquals("one apply from: in build.gradle", 1, count);
        assertTrue("the apply from: is guarded by project.hasProperty('release')",
                ReleaseScriptTextTest.GUARDED_APPLY.matcher(build).find());
        assertFalse("no buildscript block in build.gradle", build.contains("buildscript"));
        assertTrue("the release script sits outside the module directories",
                new File(ROOT, "gradle/release.gradle").isFile());
    }

    // SDS-AC-8; CON-BUILD-017 AC-3
    @Test
    public void con_BUILD_017_AC3_claudeMdStatesReleaseScriptException() throws IOException {
        String exception = null;
        for (String line : read(new File(ROOT, "CLAUDE.md")).split("\n")) {
            if (line.contains("`gradle/release.gradle`") && line.contains("`-Prelease`")) {
                exception = line;
            }
        }
        assertNotNull("CLAUDE.md names gradle/release.gradle and -Prelease on one line", exception);
        assertTrue(exception, exception.contains("build.gradle"));
    }

    // SDS-AC-19; CON-BUILD-010 AC-1
    @Test
    public void sdsAc19_CON_BUILD_010_AC1_tinyHttpdCheckoutHasNoChanges() throws Exception {
        assertTinyHttpdClean();
    }

    // CON-BUILD-010 AC-2 (automatable half: no TinyHttpd sources or patches live in this repository)
    @Test
    public void con_BUILD_010_AC2_repoHoldsNoTinyHttpdSourcesOrPatches() throws IOException {
        List<String> offenders = new ArrayList<>();
        scanForTinyHttpdCopies(ROOT, true, offenders);
        assertTrue("TinyHttpd copies/patches in repo: " + offenders, offenders.isEmpty());
    }

    private static void scanForTinyHttpdCopies(File dir, boolean top, List<String> offenders) throws IOException {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        Pattern pkg = Pattern.compile("^\\s*package\\s+com\\.snoworca\\.tinyhttpd\\b", Pattern.MULTILINE);
        for (File child : children) {
            String name = child.getName();
            if (child.isDirectory()) {
                boolean skipped = name.equals("build") || name.equals(".gradle") || name.equals(".git")
                        || (top && (name.equals(".kiwi") || name.equals("docs") || name.equals(".claude")));
                if (!skipped) {
                    scanForTinyHttpdCopies(child, false, offenders);
                }
            } else if (name.endsWith(".patch") || name.endsWith(".diff")) {
                if (read(child).toLowerCase().contains("tinyhttpd")) {
                    offenders.add(child.getPath());
                }
            } else if ((name.endsWith(".java") || name.endsWith(".kt")) && pkg.matcher(read(child)).find()) {
                offenders.add(child.getPath());
            }
        }
    }

    /** Also called after BuildChecksTest's nested builds, since test class order is not fixed. */
    static void assertTinyHttpdClean() throws Exception {
        assertEquals("", git(new File(property("naruserve.tinyHttpdDir")), "status", "--porcelain"));
    }

    /** Runs git in dir and returns its output; the exit status must be zero. --no-optional-locks keeps status from rewriting the index. */
    static String git(File dir, String... args) throws Exception {
        List<String> command = new ArrayList<>(Arrays.asList("git", "--no-optional-locks", "-C", dir.getPath()));
        command.addAll(Arrays.asList(args));
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        String out;
        try (InputStream in = p.getInputStream()) {
            out = new String(drain(in), StandardCharsets.UTF_8);
        }
        assertEquals(out, 0, p.waitFor());
        return out;
    }

    // SDS-AC-20; OPS-BUILD-003 AC-1
    @Test
    public void sdsAc20_OPS_BUILD_003_AC1_readmeNamesTinyHttpdCommit() throws IOException {
        String readme = read(new File(ROOT, "docs/api/README.md"));
        assertTrue(readme.contains("c000eb1"));
        assertFalse("the previous verified commit is gone", readme.contains("46b973e"));
    }

    // OPS-BUILD-003 AC-1; OPS-BUILD-001 AC-1: settings.gradle, CLAUDE.md and docs/api name one verified TinyHttpd commit
    @Test
    public void ops_BUILD_003_AC1_settingsGradleAndClaudeMdNameTheSameVerifiedCommit() throws IOException {
        assertTrue(read(new File(ROOT, "settings.gradle")).contains("def verifiedTinyHttpd = 'c000eb1'"));
        String claude = read(new File(ROOT, "CLAUDE.md"));
        assertTrue(claude.contains("c000eb1"));
        assertFalse("the previous verified commit is gone", claude.contains("46b973e"));
        assertFalse("settings.gradle keeps no previous commit", read(new File(ROOT, "settings.gradle")).contains("46b973e"));
    }

    // SDS-AC-20; OPS-BUILD-003 AC-2
    @Test
    public void ops_BUILD_003_AC2_apiDocsNameFinalModulesWithoutUndecidedMarker() throws IOException {
        Map<String, List<String>> required = new LinkedHashMap<>();
        required.put("README.md", MODULES);
        required.put("app.md", Arrays.asList("com.snoworca.naruserve:NaruServe:"));
        required.put("json.md", Arrays.asList("NaruServe-gson", "com.snoworca.naruserve:NaruServe-gson:",
                "com.google.code.gson:gson:2.14.0"));
        required.put("kotlin.md", Arrays.asList("NaruServe-kotlin", "com.snoworca.naruserve:NaruServe-kotlin:"));
        required.put("logging.md", Arrays.asList("NaruServe-slf4j", "NaruServe-filelog", "NaruServe-logcat"));
        for (Map.Entry<String, List<String>> e : required.entrySet()) {
            String doc = read(new File(ROOT, "docs/api/" + e.getKey()));
            for (String name : e.getValue()) {
                assertTrue(e.getKey() + " names " + name, doc.contains(name));
            }
            for (String line : doc.split("\n")) {
                assertFalse(e.getKey() + " has a module (미확정) marker: " + line,
                        line.contains("(미확정)") && (line.contains("모듈") || line.contains("좌표")));
            }
        }
    }

    // SDS-AC-20; OPS-BUILD-003 AC-3, OPS-BUILD-003 AC-4
    @Test
    public void ops_BUILD_003_AC3_AC4_claudeMdModuleLineAndNoRestKotlin() throws IOException {
        String claude = read(new File(ROOT, "CLAUDE.md"));
        String moduleLine = null;
        for (String line : claude.split("\n")) {
            if (line.startsWith("- 모듈:")) {
                moduleLine = line;
            }
        }
        assertNotNull("CLAUDE.md has a '- 모듈:' line", moduleLine);
        for (String module : MODULES) {
            assertTrue("module line names " + module, moduleLine.contains("`" + module + "`"));
        }
        assertTrue(moduleLine.contains("API 21"));
        assertTrue(moduleLine.contains("JVM 전용"));
        assertFalse(claude.contains("rest-kotlin"));
        assertFalse(read(new File(ROOT, "docs/api/kotlin.md")).contains("rest-kotlin"));
    }

    // CON-HTTP-001 AC-2, CON-HTTP-001 AC-3
    @Test
    public void CON_HTTP_001_AC2_AC3_apiMainHoldingSpiAndDispatcherIsClassRefCheckedAndSniffedAtApi21()
            throws IOException {
        File main = new File(ROOT, "NaruServe-api/build/classes/java/main/com/snoworca/naruserve");
        for (String c : new String[] {"spi/HttpAdapter.class", "spi/HttpExchange.class", "spi/ExchangeHandler.class",
            "internal/Dispatcher.class"}) {
            assertTrue(c + " is compiled from the NaruServe-api main source set", new File(main, c).isFile());
        }
        String build = read(new File(ROOT, "build.gradle"));
        // AC-2: :NaruServe-api's class-reference check names every forbidden prefix and runs under check.
        assertTrue(build.contains("prefixes += ['android/', 'jakarta/servlet/', 'com/google/gson/', "
                + "'com/snoworca/tinyhttpd/', 'kotlin/']"));
        assertTrue(build.contains("def prefixes = path in androidModules ? ['javax/servlet/'] : []"));
        assertTrue(build.contains("p.tasks.named('check') { dependsOn checkClassReferences }"));
        // AC-3: NaruServe-api is an Android module, sniffed over its main source set with the API 21 signature.
        assertTrue(build.contains("def androidModules = naruModules - [':NaruServe-servlet', ':NaruServe-slf4j']"));
        assertTrue(build.contains("':NaruServe-api'"));
        Matcher sniff = Pattern.compile("configure\\(androidModules\\.collect \\{ project\\(it\\) \\}\\) \\{"
                + "\\s*apply plugin: 'ru\\.vyarus\\.animalsniffer'"
                + "\\s*animalsniffer \\{\\s*sourceSets = \\[sourceSets\\.main\\]\\s*}\\s*dependencies \\{\\s*"
                + "signature 'net\\.sf\\.androidscents\\.signature:android-api-level-21:[^']+@signature'").matcher(build);
        assertTrue("animal-sniffer API 21 over sourceSets.main of every Android module", sniff.find());
    }
}
