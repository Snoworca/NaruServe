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
    /** The Android library (AAR) module, included only when an Android SDK resolves (FR-BUILD-001). */
    public static final String ANDROID_MODULE = "NaruServe-android";

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

    /** Whether the root build included NaruServe-android, so its outputs exist (FR-BUILD-001 AC-1). */
    public static boolean androidIncluded() {
        return Boolean.parseBoolean(property("naruserve.androidIncluded"));
    }

    // SDS-AC-1; CON-BUILD-002 AC-1, CON-BUILD-002 AC-2, CON-BUILD-002 AC-3, FR-BUILD-001 AC-1
    @Test
    public void sdsAc1_CON_BUILD_002_AC1_AC2_AC3_settingsIncludesTheNineModulesTheAndroidModuleAndRootName()
            throws IOException {
        String settings = read(new File(ROOT, "settings.gradle"));
        Set<String> included = new TreeSet<>();
        Matcher m = Pattern.compile("include\\s*\\(?\\s*['\"]:([^'\"]+)['\"]").matcher(settings);
        while (m.find()) {
            included.add(m.group(1));
        }
        Set<String> expected = new TreeSet<>(MODULES);
        expected.add(ANDROID_MODULE);
        assertEquals(expected, included);
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
        String core = "com.snoworca:tinyhttpd-core:3.1.0";
        String websocket = "com.snoworca:tinyhttpd-websocket:3.1.0";
        String gson = "com.google.code.gson:gson:2.14.0";
        // Gson 2.14.0 itself brings error_prone_annotations; AC-4 limits declarations, not transitive edges.
        String gsonTransitive = "com.google.errorprone:error_prone_annotations:2.48.0";
        assertEquals(set(), components("NaruServe-api", "runtimeClasspath"));
        assertEquals(set(api), components("NaruServe-servlet", "runtimeClasspath"));
        assertEquals(set(api, core, websocket), components("NaruServe-tinyhttpd", "runtimeClasspath"));
        assertEquals(set(api, gson, gsonTransitive), components("NaruServe-gson", "runtimeClasspath"));
        String tinyhttpd = "com.snoworca.naruserve:NaruServe-tinyhttpd";
        String gsonModule = "com.snoworca.naruserve:NaruServe-gson";
        // AC-7: the bootstrap stays general for any JDK, so no NaruServe-logcat.
        assertEquals(set(api, tinyhttpd, gsonModule, core, websocket, gson, gsonTransitive),
                components("NaruServe", "runtimeClasspath"));
        assertEquals(set(api, "com.snoworca.naruserve:NaruServe", tinyhttpd, gsonModule, core, websocket, gson,
                gsonTransitive, "org.jetbrains.kotlin:kotlin-stdlib:2.2.0", "org.jetbrains:annotations:13.0"),
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
        // AC-9: NaruServe exposes NaruServe-gson as api, so a caller of NaruServe (here NaruServe-kotlin) compiles
        // against GsonJsonCodec and Gson, while TinyHttpd stays out of every compile classpath but its own.
        for (String module : new String[] {"NaruServe", "NaruServe-kotlin"}) {
            Set<String> compile = components(module, "compileClasspath");
            assertTrue(module + " " + compile, compile.contains("com.google.code.gson:gson:2.14.0"));
            assertTrue(module + " " + compile, compile.contains("com.snoworca.naruserve:NaruServe-gson"));
            assertFalse(module + " " + compile, compile.contains("com.snoworca.naruserve:NaruServe-logcat"));
            for (String c : compile) {
                assertFalse(module + ": TinyHttpd is implementation-scope: " + c, c.startsWith("com.snoworca:tinyhttpd-"));
            }
        }
        // The bootstrap compiles against its adapter module; a caller of NaruServe does not.
        Set<String> kotlinCompile = components("NaruServe-kotlin", "compileClasspath");
        assertTrue(kotlinCompile.toString(), kotlinCompile.contains("com.snoworca.naruserve:NaruServe"));
        assertFalse(kotlinCompile.toString(), kotlinCompile.contains("com.snoworca.naruserve:NaruServe-tinyhttpd"));
    }

    // FR-BUILD-001 AC-4, CON-BUILD-006 AC-1, CON-BUILD-006 AC-7: NaruServe-android adds the Logcat logger to NaruServe
    @Test
    public void fr_BUILD_001_AC4_androidReleaseClasspathsHoldNaruServeAndTheLogcatLogger() throws IOException {
        org.junit.Assume.assumeTrue("no Android SDK resolved, NaruServe-android is not built", androidIncluded());
        String api = "com.snoworca.naruserve:NaruServe-api";
        Set<String> runtime = components(ANDROID_MODULE, "releaseRuntimeClasspath");
        assertEquals(set(api, "com.snoworca.naruserve:NaruServe", "com.snoworca.naruserve:NaruServe-tinyhttpd",
                "com.snoworca.naruserve:NaruServe-gson", "com.snoworca.naruserve:NaruServe-logcat",
                "com.snoworca:tinyhttpd-core:3.1.0", "com.snoworca:tinyhttpd-websocket:3.1.0",
                "com.google.code.gson:gson:2.14.0", "com.google.errorprone:error_prone_annotations:2.48.0"), runtime);
        Set<String> compile = components(ANDROID_MODULE, "releaseCompileClasspath");
        assertTrue(compile.toString(), compile.contains("com.snoworca.naruserve:NaruServe-logcat"));
        assertTrue(compile.toString(), compile.contains("com.google.code.gson:gson:2.14.0"));
        String build = read(new File(ROOT, "build.gradle"));
        assertTrue("NaruServe-android declares only api NaruServe and NaruServe-logcat",
                build.contains("':NaruServe-android'  : [api: [':NaruServe', ':NaruServe-logcat']]"));
        // AC-7: among the published modules only NaruServe-android declares the Logcat logger module.
        for (String module : MODULES) {
            if (!module.equals("NaruServe-logcat")) {
                assertFalse(module + " runtime holds NaruServe-logcat", components(module, "runtimeClasspath")
                        .contains("com.snoworca.naruserve:NaruServe-logcat"));
            }
        }
    }

    // FR-BUILD-001 AC-1: settings.gradle includes NaruServe-android only when an Android SDK directory resolves
    @Test
    public void fr_BUILD_001_AC1_settingsResolvesTheSdkFromLocalPropertiesThenAndroidHomeThenSdkRoot() throws IOException {
        String settings = read(new File(ROOT, "settings.gradle"));
        int local = settings.indexOf("'sdk.dir'");
        int home = settings.indexOf("'ANDROID_HOME'");
        int root = settings.indexOf("'ANDROID_SDK_ROOT'");
        assertTrue(settings, local >= 0 && home > local && root > home);
        assertTrue(settings, settings.contains("local.properties"));
        Matcher include = Pattern.compile("if \\(androidSdk[^)]*\\) \\{\\s*include ':NaruServe-android'").matcher(settings);
        assertTrue("the include is conditional on the resolved SDK", include.find());
        assertTrue("the skip warning names the module and the three sources",
                settings.contains("NaruServe-android is not built: no Android SDK found in local.properties sdk.dir, "
                        + "ANDROID_HOME or ANDROID_SDK_ROOT"));
    }

    // FR-BUILD-001 AC-2: the root build.gradle configures the Android library; no module build file, no Kotlin, no sources
    @Test
    public void fr_BUILD_001_AC2_rootBuildConfiguresTheAndroidLibraryWithoutSourcesOrKotlin() throws IOException {
        String build = read(new File(ROOT, "build.gradle"));
        for (String part : new String[] {"id 'com.android.library' version '8.13.2' apply false",
                "apply plugin: 'com.android.library'", "namespace = 'com.snoworca.naruserve.android'",
                "compileSdk = 36", "minSdk = 21", "sourceCompatibility = JavaVersion.VERSION_1_8",
                "targetCompatibility = JavaVersion.VERSION_1_8"}) {
            assertTrue("root build.gradle holds " + part, build.contains(part));
        }
        assertFalse("NaruServe-android is not a Kotlin/JVM module", build.contains("':NaruServe-android', "));
        for (String name : new String[] {"build.gradle", "build.gradle.kts"}) {
            assertFalse(ANDROID_MODULE + "/" + name, new File(ROOT, ANDROID_MODULE + "/" + name).exists());
        }
        for (String dir : new String[] {"java", "kotlin"}) {
            assertFalse(ANDROID_MODULE + "/src/main/" + dir, new File(ROOT, ANDROID_MODULE + "/src/main/" + dir).exists());
        }
        assertTrue(new File(ROOT, ANDROID_MODULE + "/src/main/AndroidManifest.xml").isFile());
    }

    // FR-BUILD-001 AC-3: the release AAR's manifest: package, minSdkVersion 21 and the INTERNET permission only
    @Test
    public void fr_BUILD_001_AC3_releaseAarManifestHoldsMinSdk21AndOnlyTheInternetPermission() throws Exception {
        org.junit.Assume.assumeTrue("no Android SDK resolved, NaruServe-android is not built", androidIncluded());
        File aar = new File(property("naruserve.androidAar"));
        assertTrue("release AAR " + aar, aar.isFile());
        org.w3c.dom.Element manifest;
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(aar)) {
            java.util.zip.ZipEntry entry = zip.getEntry("AndroidManifest.xml");
            assertNotNull("AndroidManifest.xml in " + aar, entry);
            javax.xml.parsers.DocumentBuilderFactory f = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            try (InputStream in = zip.getInputStream(entry)) {
                manifest = f.newDocumentBuilder().parse(in).getDocumentElement();
            }
        }
        String ns = "http://schemas.android.com/apk/res/android";
        assertEquals("com.snoworca.naruserve.android", manifest.getAttribute("package"));
        org.w3c.dom.NodeList sdk = manifest.getElementsByTagName("uses-sdk");
        assertEquals(1, sdk.getLength());
        assertEquals("21", ((org.w3c.dom.Element) sdk.item(0)).getAttributeNS(ns, "minSdkVersion"));
        org.w3c.dom.NodeList permissions = manifest.getElementsByTagName("uses-permission");
        assertEquals("exactly one uses-permission", 1, permissions.getLength());
        assertEquals("android.permission.INTERNET", ((org.w3c.dom.Element) permissions.item(0)).getAttributeNS(ns, "name"));
    }

    // CON-BUILD-006 AC-8
    @Test
    public void con_BUILD_006_AC8_apiHasTestFixtures() {
        assertTrue(new File(ROOT, "NaruServe-api/build/libs/NaruServe-api-0.9.0-test-fixtures.jar").isFile());
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
        if (Boolean.parseBoolean(property("naruserve.tinyHttpdFromMaven"))) {
            return; // OPS-BUILD-001 AC-4: no source checkout participates in Maven mode.
        }
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
        assertTrue(readme.contains("165f365"));
        assertFalse("the previous verified commit is gone", readme.contains("46b973e"));
    }

    // OPS-BUILD-003 AC-1; OPS-BUILD-001 AC-1: settings.gradle, CLAUDE.md and docs/api name one verified TinyHttpd commit
    @Test
    public void ops_BUILD_003_AC1_settingsGradleAndClaudeMdNameTheSameVerifiedCommit() throws IOException {
        assertTrue(read(new File(ROOT, "settings.gradle")).contains("def verifiedTinyHttpd = '165f365'"));
        String claude = read(new File(ROOT, "CLAUDE.md"));
        assertTrue(claude.contains("165f365"));
        assertFalse("the previous verified commit is gone", claude.contains("46b973e"));
        assertFalse("settings.gradle keeps no previous commit", read(new File(ROOT, "settings.gradle")).contains("46b973e"));
    }

    // SDS-AC-20; OPS-BUILD-003 AC-2
    @Test
    public void ops_BUILD_003_AC2_apiDocsNameFinalModulesWithoutUndecidedMarker() throws IOException {
        Map<String, List<String>> required = new LinkedHashMap<>();
        List<String> readme = new ArrayList<>(MODULES);
        readme.add(ANDROID_MODULE);
        required.put("README.md", readme);
        required.put("app.md", Arrays.asList("com.snoworca.naruserve:naruserve:"));
        required.put("json.md", Arrays.asList("NaruServe-gson", "com.snoworca.naruserve:naruserve-gson:",
                "com.google.code.gson:gson:2.14.0"));
        required.put("kotlin.md", Arrays.asList("NaruServe-kotlin", "com.snoworca.naruserve:naruserve-kotlin:"));
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

    // OPS-BUILD-003 AC-6: docs/api states the android-module layout and drops the "add NaruServe-gson separately" advice
    @Test
    public void ops_BUILD_003_AC6_apiDocsStateTheDefaultKotlinAndAndroidModuleSets() throws IOException {
        String readme = read(new File(ROOT, "docs/api/README.md"));
        assertTrue(readme, readme.contains("| `NaruServe-android` | `com.snoworca.naruserve:naruserve-android` |"));
        assertTrue(readme, readme.contains("`NaruServe` 하나로 `GsonJsonCodec`과 Gson도 컴파일 시점에 함께 들어옵니다"));
        assertTrue(readme, readme.contains("`NaruServe-kotlin`은 `NaruServe`를 포함합니다"));
        assertTrue(readme, readme.contains("Android 앱은 `NaruServe-android`를 넣습니다"));
        assertTrue(readme, readme.contains("`android.permission.INTERNET`"));
        assertFalse(readme, readme.contains("그 모듈(NaruServe-gson 등)을 따로 넣으세요"));
        String json = read(new File(ROOT, "docs/api/json.md"));
        assertFalse(json, json.contains("이 모듈을 실행 시점에만 함께 가져옵니다"));
        assertTrue(json, json.contains("`NaruServe`를 넣었다면 따로 추가할 것이 없습니다"));
    }

    // FR-BUILD-001 AC-6: the Android app keeps one copy of the identical TinyHttpd notices both TinyHttpd JARs carry
    @Test
    public void fr_BUILD_001_AC6_docsShowThePickFirstsOfTheTinyHttpdNotices() throws IOException {
        String line = "pickFirsts += ['META-INF/LICENSE-tinyhttpd.txt', 'META-INF/LICENSE-nanohttpd.txt', "
                + "'META-INF/NOTICE-tinyhttpd.txt']";
        for (String doc : new String[] {"docs/api/README.md", "README.md"}) {
            String text = read(new File(ROOT, doc));
            assertTrue(doc + " shows " + line, text.contains(line));
            int at = text.indexOf(line);
            String before = text.substring(Math.max(0, at - 400), at);
            assertTrue(doc + ": the line sits under packaging { resources {", before.contains("packaging {")
                    && before.contains("resources {"));
        }
        assertTrue(read(new File(ROOT, "docs/api/README.md")).contains(
                "TinyHttpd 3.1.0의 core와 websocket JAR에 같은 경로, 같은 내용의 라이선스 고지가 들어 있어"));
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
        // AC-3: the Android library module, built only when an Android SDK resolves (FR-BUILD-001).
        assertTrue("module line names " + ANDROID_MODULE, moduleLine.contains("`" + ANDROID_MODULE + "`"));
        assertTrue(moduleLine, moduleLine.contains("AAR") && moduleLine.contains("Android SDK"));
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
