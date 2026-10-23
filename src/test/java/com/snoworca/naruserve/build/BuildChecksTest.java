package com.snoworca.naruserve.build;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Negative cases of the build checks, run through Gradle TestKit against one temp copy of the root build.
 * Each test adds files or appends to the copy's build.gradle; {@link #restore()} undoes that.
 */
public class BuildChecksTest {

    private static File root;
    private static File work;
    private static String pristineBuild;
    private final List<File> added = new ArrayList<>();

    @BeforeClass
    public static void copyBuild() throws IOException {
        root = new File(BuildLayoutTest.property("naruserve.rootDir"));
        work = new File(BuildLayoutTest.property("naruserve.testKitWorkDir"));
        deleteTree(work);
        assertTrue(work.mkdirs());
        for (String name : new String[] {"settings.gradle", "build.gradle", "gradle.properties"}) {
            copy(new File(root, name), new File(work, name));
        }
        for (String module : BuildLayoutTest.MODULES) {
            // Every included project gets a directory, even a module with no sources yet.
            assertTrue(new File(work, module).mkdirs());
            copy(new File(root, module + "/src"), new File(work, module + "/src"));
        }
        pristineBuild = BuildLayoutTest.read(new File(work, "build.gradle"));
    }

    // SDS-AC-19; CON-BUILD-010 AC-1 — rechecked after the nested builds, which also build TinyHttpd.
    @AfterClass
    public static void deleteCopyAndCheckTinyHttpd() throws Exception {
        deleteTree(work);
        BuildLayoutTest.assertTinyHttpdClean();
    }

    @After
    public void restore() throws IOException {
        write(new File(work, "build.gradle"), pristineBuild);
        for (File f : added) {
            assertTrue(f + " deleted", f.delete());
        }
        added.clear();
    }

    private static void copy(File from, File to) throws IOException {
        if (from.isDirectory()) {
            File[] children = from.listFiles();
            assertTrue(to.isDirectory() || to.mkdirs());
            if (children != null) {
                for (File child : children) {
                    copy(child, new File(to, child.getName()));
                }
            }
        } else if (from.isFile()) {
            write(to, BuildLayoutTest.read(from));
        }
    }

    private static void write(File file, String text) throws IOException {
        File parent = file.getParentFile();
        assertTrue(parent.isDirectory() || parent.mkdirs());
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteTree(child);
            }
        }
        if (file.exists()) {
            assertTrue(file + " deleted", file.delete());
        }
    }

    private void addSource(String path, String text) throws IOException {
        File file = new File(work, path);
        write(file, text);
        added.add(file);
    }

    private void appendBuild(String text) throws IOException {
        write(new File(work, "build.gradle"), pristineBuild + "\n" + text + "\n");
    }

    /** Runs only the given tasks in the temp copy with the Gradle installation running the root build. */
    private static GradleRunner runner(String... args) {
        List<String> arguments = new ArrayList<>(Arrays.asList(args));
        arguments.add("--console=plain");
        // Command-line flags beat ~/.gradle/gradle.properties, so a global configuration cache or build cache
        // cannot change nested results (checkDependencyRules reads Project objects at execution time).
        // shortcut: ~/.gradle/init.d scripts still run, since the TestKit dir is the outer user home (offline cache);
        // seed a dedicated TestKit dir from that cache if an init script ever breaks these builds.
        arguments.add("--no-configuration-cache");
        arguments.add("--no-build-cache");
        arguments.add("--no-configure-on-demand");
        // OPS-BUILD-002 AC-6: the test worker may be Java 8 while the build/compiler stays on JDK 17.
        arguments.add("-Dorg.gradle.java.home=" + System.getProperty(
                "naruserve.buildJavaHome", System.getProperty("java.home")));
        // The copied settings.gradle must resolve the same TinyHttpd checkout as the outer build (OPS-BUILD-001).
        boolean overridden = false;
        for (String a : args) {
            overridden |= a.startsWith("-PtinyHttpdDir=");
        }
        if (!overridden) {
            arguments.add("-PtinyHttpdDir=" + BuildLayoutTest.property("naruserve.tinyHttpdDir"));
        }
        if (Boolean.parseBoolean(BuildLayoutTest.property("naruserve.offline"))) {
            arguments.add("--offline");
        }
        return GradleRunner.create()
                .withGradleInstallation(new File(BuildLayoutTest.property("naruserve.gradleHome")))
                // The outer user home holds the dependency cache, so nested builds neither re-download nor need network.
                .withTestKitDir(new File(BuildLayoutTest.property("naruserve.gradleUserHome")))
                .withProjectDir(work)
                .withArguments(arguments);
    }

    // OPS-BUILD-002 AC-6
    @Test
    public void OPS_BUILD_002_AC6_nestedBuildUsesExplicitCompilerHomeAndCurrentJvmFallback() {
        String original = System.getProperty("naruserve.buildJavaHome");
        try {
            String selected = new File("/explicit/compiler-jdk17").getAbsolutePath();
            System.setProperty("naruserve.buildJavaHome", selected);
            assertTrue(runner("help").getArguments().contains("-Dorg.gradle.java.home=" + selected));
            System.clearProperty("naruserve.buildJavaHome");
            assertTrue(runner("help").getArguments().contains(
                    "-Dorg.gradle.java.home=" + System.getProperty("java.home")));
        } finally {
            if (original == null) System.clearProperty("naruserve.buildJavaHome");
            else System.setProperty("naruserve.buildJavaHome", original);
        }
    }

    // SDS-AC-3; OPS-BUILD-001 AC-1, OPS-BUILD-001 AC-2, CON-BUILD-009 AC-1
    @Test
    public void sdsAc3_OPS_BUILD_001_AC1_AC2_propertyOverridesTheSettingsDefault() throws IOException {
        String settings = BuildLayoutTest.read(new File(root, "settings.gradle"));
        assertTrue(settings.contains("providers.gradleProperty('tinyHttpdDir')"));
        assertTrue(settings.contains("'/Users/beom/work/git/TinyHttpd'"));
        assertFalse("gradle.properties only overrides the default",
                BuildLayoutTest.read(new File(root, "gradle.properties")).contains("tinyHttpdDir"));
        BuildResult result = runner("-PtinyHttpdDir=" + BuildLayoutTest.property("naruserve.tinyHttpdDir"),
                ":NaruServe-tinyhttpd:compileJava").build();
        assertTrue(result.getOutput(), result.getOutput().contains("BUILD SUCCESSFUL"));
    }

    // OPS-BUILD-001 AC-1, OPS-BUILD-001 AC-2, SDS-AC-19 — nested builds use the checkout the outer build resolved.
    @Test
    public void ops_BUILD_001_AC1_AC2_nestedBuildsUseTheOuterTinyHttpdDir() throws IOException {
        appendBuild("println 'nested tinyHttpdDir=' + gradle.ext.tinyHttpdDir.absolutePath");
        String out = runner("help").build().getOutput();
        assertTrue(out, out.contains("nested tinyHttpdDir=" + BuildLayoutTest.property("naruserve.tinyHttpdDir")));
    }

    // SDS-AC-4; OPS-BUILD-001 AC-3
    @Test
    public void sdsAc4_OPS_BUILD_001_AC3_missingCheckoutFailsWhileEvaluatingSettings() {
        String missing = new File(work, "no-such-tinyhttpd").getAbsolutePath();
        BuildResult result = runner("-PtinyHttpdDir=" + missing, "help").buildAndFail();
        String out = result.getOutput();
        assertTrue(out, out.contains("tinyHttpdDir: TinyHttpd checkout not found at " + missing));
        assertTrue(out, out.contains("settings.gradle"));
        assertFalse("no task ran: " + out, out.contains("> Task"));
    }

    // SDS-AC-5; CON-BUILD-004 AC-3
    @Test
    public void sdsAc5_CON_BUILD_004_AC3_kotlinAndJavaTargetsAreConsistent() {
        BuildResult result = runner(":NaruServe-api:compileTestKotlin", ":NaruServe-kotlin:compileKotlin",
                ":NaruServe-kotlin:compileTestKotlin").build();
        assertFalse(result.getOutput(), result.getOutput().contains("Inconsistent JVM-target"));
    }

    // SDS-AC-8; CON-BUILD-011 AC-2
    @Test
    public void sdsAc8_CON_BUILD_011_AC2_nullableReturnDoesNotAssignToString() throws IOException {
        addSource("NaruServe-api/src/test/kotlin/com/snoworca/naruserve/NullableAssign.kt",
                "package com.snoworca.naruserve\n\n"
                        + "fun nullableAssign() {\n"
                        + "    val s: String = NullabilityFixture.annotatedNullable()\n"
                        + "    println(s)\n"
                        + "}\n");
        BuildResult result = runner(":NaruServe-api:compileTestKotlin").buildAndFail();
        String out = result.getOutput();
        assertTrue(out, out.contains("NullableAssign.kt"));
        assertTrue(out, out.contains("String?"));
    }

    // SDS-AC-11; OPS-BUILD-002 AC-4
    @Test
    public void sdsAc11_OPS_BUILD_002_AC4_runnerUsesTheBuildGradleAndATempCopy() throws IOException {
        File gradleHome = new File(BuildLayoutTest.property("naruserve.gradleHome"));
        assertTrue(new File(gradleHome, "lib").isDirectory());
        assertNotEquals(root.getCanonicalFile(), work.getCanonicalFile());
        assertEquals(BuildLayoutTest.read(new File(root, "settings.gradle")),
                BuildLayoutTest.read(new File(work, "settings.gradle")));
        assertEquals(BuildLayoutTest.read(new File(root, "build.gradle")), pristineBuild);
        assertFalse("the copy holds no root test sources", new File(work, "src").exists());
    }

    // SDS-AC-10; CON-BUILD-013 AC-1, CON-BUILD-013 AC-2, CON-BUILD-013 AC-3, CON-BUILD-007 AC-3, CON-BUILD-012 AC-1, OPS-BUILD-002 AC-3
    @Test
    public void con_BUILD_013_buildRunsTheChecks() {
        String out = runner("-m", "build").build().getOutput();
        assertTrue(out, out.contains(":checkDependencyRules"));
        for (String module : Arrays.asList("NaruServe-api", "NaruServe-tinyhttpd", "NaruServe-gson", "NaruServe",
                "NaruServe-kotlin", "NaruServe-filelog", "NaruServe-logcat")) {
            assertTrue(module + "\n" + out, out.contains(":" + module + ":checkClassReferences"));
            assertTrue(module + "\n" + out, out.contains(":" + module + ":animalsnifferMain"));
        }
        for (String module : BuildLayoutTest.MODULES) {
            // OPS-BUILD-002 AC-5: the Kotlin plugin is applied to all nine modules.
            assertTrue(module + "\n" + out, out.contains(":" + module + ":compileTestKotlin"));
        }
        assertTrue(out, out.contains(":NaruServe-api:test"));
        assertTrue(out, out.contains(":NaruServe-kotlin:test"));
    }

    // SDS-AC-12; CON-BUILD-013 AC-1, CON-BUILD-015 AC-1
    @Test
    public void sdsAc12_CON_BUILD_013_AC1_apiMainClassReferencingKotlinFails() throws IOException {
        appendBuild("project(':NaruServe-api') { dependencies { compileOnly 'org.jetbrains.kotlin:kotlin-stdlib:2.2.0' } }");
        addSource("NaruServe-api/src/main/java/com/snoworca/naruserve/probe/UsesKotlinUnit.java",
                "package com.snoworca.naruserve.probe;\n"
                        + "public class UsesKotlinUnit { public Object unit() { return kotlin.Unit.INSTANCE; } }\n");
        addSource("NaruServe-api/src/main/java/com/snoworca/naruserve/kotlin/Marker.java",
                "package com.snoworca.naruserve.kotlin;\npublic class Marker { }\n");
        addSource("NaruServe-api/src/main/java/com/snoworca/naruserve/probe/UsesMarker.java",
                "package com.snoworca.naruserve.probe;\n"
                        + "public class UsesMarker { public com.snoworca.naruserve.kotlin.Marker m; }\n");
        String out = runner(":NaruServe-api:checkClassReferences").buildAndFail().getOutput();
        assertTrue(out, out.contains("com/snoworca/naruserve/probe/UsesKotlinUnit -> kotlin/Unit"));
        assertFalse(out, out.contains("-> com/snoworca/naruserve/kotlin/Marker"));
    }

    // SDS-AC-13; CON-BUILD-013 AC-3
    @Test
    public void sdsAc13_CON_BUILD_013_AC3_androidModuleReferencingServletFails() throws IOException {
        appendBuild("project(':NaruServe-gson') { dependencies { compileOnly 'javax.servlet:javax.servlet-api:3.1.0' } }");
        addSource("NaruServe-gson/src/main/java/com/snoworca/naruserve/gson/probe/UsesServlet.java",
                "package com.snoworca.naruserve.gson.probe;\n"
                        + "public class UsesServlet { public javax.servlet.Servlet servlet; }\n");
        String out = runner(":NaruServe-gson:checkClassReferences").buildAndFail().getOutput();
        assertTrue(out, out.contains("com/snoworca/naruserve/gson/probe/UsesServlet -> javax/servlet/Servlet"));
    }

    // SDS-AC-7; IR-SVL-001 AC-7, CON-SVL-002 AC-1 — a source stub stands in for the jakarta API, so no artifact is resolved
    @Test
    public void sdsAc7_IR_SVL_001_AC7_servletMainReferencingJakartaFails() throws IOException {
        assertFalse(BuildLayoutTest.read(new File(root, "settings.gradle")).contains("jakarta"));
        addSource("NaruServe-servlet/src/main/java/jakarta/servlet/Servlet.java",
                "package jakarta.servlet;\npublic interface Servlet { }\n");
        addSource("NaruServe-servlet/src/main/java/com/snoworca/naruserve/servlet/probe/UsesJakarta.java",
                "package com.snoworca.naruserve.servlet.probe;\n"
                        + "public class UsesJakarta { public jakarta.servlet.Servlet servlet; }\n");
        String out = runner(":NaruServe-servlet:checkClassReferences").buildAndFail().getOutput();
        assertTrue(out, out.contains("com/snoworca/naruserve/servlet/probe/UsesJakarta -> jakarta/servlet/Servlet"));
    }

    // SDS-AC-14; CON-BUILD-008 AC-1, CON-APP-008 AC-1
    @Test
    public void sdsAc14_CON_BUILD_008_AC1_logcatMainReferencingAndroidFails() throws IOException {
        addSource("NaruServe-logcat/src/main/java/android/util/Log.java",
                "package android.util;\n"
                        + "public final class Log { public static int i(String t, String m) { return 0; } }\n");
        addSource("NaruServe-logcat/src/main/java/com/snoworca/naruserve/logcat/probe/UsesLog.java",
                "package com.snoworca.naruserve.logcat.probe;\n"
                        + "public class UsesLog { public int log() { return android.util.Log.i(\"t\", \"m\"); } }\n");
        String out = runner(":NaruServe-logcat:checkClassReferences").buildAndFail().getOutput();
        assertTrue(out, out.contains("com/snoworca/naruserve/logcat/probe/UsesLog -> android/util/Log"));
    }

    // SDS-AC-15; CON-BUILD-007 AC-3
    @Test
    public void sdsAc15_CON_BUILD_007_AC3_kotlinStdlibOutsideKotlinModuleFails() throws IOException {
        appendBuild("project(':NaruServe-gson') { dependencies { implementation 'org.jetbrains.kotlin:kotlin-stdlib:2.2.0' } }");
        String out = runner(":checkDependencyRules").buildAndFail().getOutput();
        assertTrue(out, out.contains("kotlin-stdlib on runtimeClasspath of :NaruServe-gson"));
    }

    // SDS-AC-16; CON-BUILD-013 AC-2
    @Test
    public void sdsAc16_CON_BUILD_013_AC2_gsonOtherThan2140Fails() throws IOException {
        appendBuild("project(':NaruServe-slf4j') {\n"
                + "    dependencies { implementation 'com.google.code.gson:gson:2.10.1' }\n"
                + "    configurations.configureEach { resolutionStrategy.force 'com.google.code.gson:gson:2.10.1' }\n"
                + "}");
        String out = runner(":checkDependencyRules").buildAndFail().getOutput();
        assertTrue(out, out.contains("com.google.code.gson:gson:2.10.1 on runtimeClasspath of :NaruServe-slf4j"));
    }

    // SDS-AC-17; CON-BUILD-009 AC-2, CON-BUILD-009 AC-3, CON-BUILD-009 AC-4, CON-BUILD-016 AC-1
    @Test
    public void sdsAc17_CON_BUILD_009_AC2_AC3_tinyHttpdOutsideAdapterOrRouterFails() throws IOException {
        appendBuild("project(':NaruServe-gson') { dependencies { implementation 'com.snoworca:tinyhttpd-core' } }\n"
                + "project(':NaruServe-tinyhttpd') { dependencies { implementation 'com.snoworca:tinyhttpd-router' } }");
        String out = runner(":checkDependencyRules").buildAndFail().getOutput();
        assertTrue(out, out.contains("com.snoworca:tinyhttpd-core declared by :NaruServe-gson"));
        assertTrue(out, out.contains("com.snoworca:tinyhttpd-router on runtimeClasspath of :NaruServe-tinyhttpd"));
    }

    // SDS-AC-18; CON-BUILD-012 AC-1, CON-BUILD-012 AC-2
    @Test
    public void sdsAc18_CON_BUILD_012_AC1_AC2_animalSnifferOnAndroidModulesOnly() throws IOException {
        addSource("NaruServe-gson/src/main/java/com/snoworca/naruserve/gson/probe/UsesOptional.java",
                "package com.snoworca.naruserve.gson.probe;\n"
                        + "public class UsesOptional { public Object none() { return java.util.Optional.empty(); } }\n");
        BuildResult sniffed = runner(":NaruServe-gson:animalsnifferMain").buildAndFail();
        assertEquals(TaskOutcome.FAILED, sniffed.task(":NaruServe-gson:animalsnifferMain").getOutcome());
        String out = sniffed.getOutput();
        assertTrue(out, out.contains("java.util.Optional"));

        String check = runner("-m", "check").build().getOutput();
        assertFalse(check, check.contains(":NaruServe-servlet:animalsniffer"));
        assertFalse(check, check.contains(":NaruServe-slf4j:animalsniffer"));
        String missing = runner(":NaruServe-servlet:animalsnifferMain").buildAndFail().getOutput();
        assertTrue(missing, missing.contains("not found in project ':NaruServe-servlet'"));
    }

    // SDS-AC-5; FR-APP-016 AC-4
    @Test
    public void sdsAc5_FR_APP_016_AC4_logcatMainHasNoAndroidReferenceAndTheFakeLivesInTests() {
        assertTrue(new File(root, "NaruServe-logcat/src/test/java/android/util/Log.java").isFile());
        assertFalse(new File(root, "NaruServe-logcat/src/main/java/android").exists());
        BuildResult result = runner(":NaruServe-logcat:checkClassReferences").build();
        assertEquals(TaskOutcome.SUCCESS, result.task(":NaruServe-logcat:checkClassReferences").getOutcome());
    }

    // SDS-AC-14; CON-APP-001 AC-1, CON-APP-001 AC-3, CON-APP-008 AC-1
    @Test
    public void sdsAc14_CON_APP_001_AC1_AC3_apiMainClassReferencingSlf4jFails() throws IOException {
        for (String row : BuildLayoutTest.read(new File(BuildLayoutTest.property("naruserve.depReport"))).split("\n")) {
            assertFalse(row, row.startsWith(":NaruServe-api|") && row.contains("|org.slf4j:")
                    && (row.contains("|compileClasspath|") || row.contains("|runtimeClasspath|")));
        }
        appendBuild("project(':NaruServe-api') { dependencies { compileOnly 'org.slf4j:slf4j-api:1.7.36' } }");
        addSource("NaruServe-api/src/main/java/com/snoworca/naruserve/probe/UsesSlf4j.java",
                "package com.snoworca.naruserve.probe;\n"
                        + "public class UsesSlf4j { public org.slf4j.Logger logger; }\n");
        String out = runner(":NaruServe-api:checkClassReferences").buildAndFail().getOutput();
        assertTrue(out, out.contains("com/snoworca/naruserve/probe/UsesSlf4j -> org/slf4j/Logger"));
        assertTrue(BuildLayoutTest.read(new File(root, "build.gradle")).contains("'org/slf4j/'"));
    }

    // SDS-AC-15; CON-APP-001 AC-2, CON-APP-001 AC-3, FR-APP-018 AC-3
    @Test
    public void sdsAc15_CON_APP_001_AC2_FR_APP_018_AC3_loggerClassInTheApiJarFailsPlacement() throws IOException {
        String dry = runner("-m", "build").build().getOutput();
        assertTrue(dry, dry.contains(":checkLoggerPlacement"));
        addSource("NaruServe-api/src/main/java/com/snoworca/naruserve/slf4j/Stray.java",
                "package com.snoworca.naruserve.slf4j;\npublic class Stray { }\n");
        String out = runner(":checkLoggerPlacement").buildAndFail().getOutput();
        assertTrue(out, out.contains("checkLoggerPlacement: NaruServe-api jar holds com/snoworca/naruserve/slf4j/Stray.class"));
    }

    // FR-APP-017 AC-6 — :NaruServe-filelog passes the API 21 animal-sniffer check, and that check does bite there
    @Test
    public void fr_APP_017_AC6_filelogPassesAndIsGuardedByTheApi21AnimalSniffer() throws IOException {
        BuildResult clean = runner(":NaruServe-filelog:animalsnifferMain").build();
        assertEquals(TaskOutcome.SUCCESS, clean.task(":NaruServe-filelog:animalsnifferMain").getOutcome());
        addSource("NaruServe-filelog/src/main/java/com/snoworca/naruserve/filelog/probe/UsesOptional.java",
                "package com.snoworca.naruserve.filelog.probe;\n"
                        + "public class UsesOptional { public Object none() { return java.util.Optional.empty(); } }\n");
        BuildResult sniffed = runner(":NaruServe-filelog:animalsnifferMain").buildAndFail();
        assertEquals(TaskOutcome.FAILED, sniffed.task(":NaruServe-filelog:animalsnifferMain").getOutcome());
        assertTrue(sniffed.getOutput(), sniffed.getOutput().contains("java.util.Optional"));
    }

    // SDS-AC-9, SDS-AC-10, SDS-AC-11; CON-KT-001 AC-1, CON-KT-001 AC-2, CON-KT-001 AC-3
    @Test
    public void checkNoCoroutinesFailsOnClassAndOnArtifact() throws IOException {
        addSource("NaruServe-gson/src/main/java/kotlinx/coroutines/BuildersKt.java",
                "package kotlinx.coroutines;\n"
                        + "public final class BuildersKt { public static Object runBlocking(Object c) { return c; } }\n");
        addSource("NaruServe-gson/src/main/java/com/snoworca/naruserve/gson/probe/UsesCoroutines.java",
                "package com.snoworca.naruserve.gson.probe;\n"
                        + "public class UsesCoroutines { public Object run() { return kotlinx.coroutines.BuildersKt.runBlocking(null); } }\n");
        // A packaging-pom module has no jar, so the file repository needs nothing from the network.
        File repo = new File(work, "coroutines-repo");
        addSource("coroutines-repo/org/jetbrains/kotlinx/kotlinx-coroutines-core/1.8.1/kotlinx-coroutines-core-1.8.1.pom",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                        + "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n"
                        + "  <modelVersion>4.0.0</modelVersion>\n"
                        + "  <groupId>org.jetbrains.kotlinx</groupId>\n"
                        + "  <artifactId>kotlinx-coroutines-core</artifactId>\n"
                        + "  <version>1.8.1</version>\n"
                        + "  <packaging>pom</packaging>\n"
                        + "</project>\n");
        appendBuild("project(':NaruServe-slf4j') {\n"
                + "    repositories {\n"
                + "        exclusiveContent {\n"
                + "            forRepository { maven { url = uri('" + repo.toURI() + "') } }\n"
                // Only this module: the Kotlin compiler classpath needs the real kotlinx-coroutines-core-jvm.
                + "            filter { includeModule 'org.jetbrains.kotlinx', 'kotlinx-coroutines-core' }\n"
                + "        }\n"
                + "    }\n"
                + "    dependencies { implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1' }\n"
                + "}");
        String out = runner(":checkNoCoroutines").buildAndFail().getOutput();
        assertTrue(out, out.contains("com/snoworca/naruserve/gson/probe/UsesCoroutines -> kotlinx/coroutines/BuildersKt"));
        assertTrue(out, out.contains(":NaruServe-slf4j runtimeClasspath -> org.jetbrains.kotlinx:kotlinx-coroutines-core"));
        assertTrue(out, out.contains(":NaruServe-slf4j compileClasspath -> org.jetbrains.kotlinx:kotlinx-coroutines-core"));
    }

    // SDS-AC-12, SDS-AC-9; CON-KT-001 AC-4, CON-KT-001 AC-1
    @Test
    public void con_KT_001_AC4_pristineCopyPassesCheckNoCoroutinesAndBuildRunsIt() {
        BuildResult result = runner(":checkNoCoroutines").build();
        assertEquals(TaskOutcome.SUCCESS, result.task(":checkNoCoroutines").getOutcome());
        String dry = runner("-m", "build").build().getOutput();
        assertTrue(dry, dry.contains(":checkNoCoroutines"));
        assertTrue(dry, dry.contains(":checkDependencyRules"));
    }

    // OPS-HTTP-004 AC-1 — successor of OPS-HTTP-002 AC-1, same assertions plus useJUnit()
    @Test
    public void ops_HTTP_004_AC1_apiMainOnRootTestClasspathWithWave1ConventionsKept() throws Exception {
        for (String type : new String[] {"Request", "Response", "RestApp"}) {
            Class<?> c = Class.forName("com.snoworca.naruserve." + type);
            String location = c.getProtectionDomain().getCodeSource().getLocation().getPath();
            assertTrue(type + " from " + location, location.contains("NaruServe-api"));
        }
        String build = BuildLayoutTest.read(new File(root, "build.gradle"));
        for (String part : new String[] {"testImplementation project(':NaruServe-api')",
                "testImplementation 'junit:junit:4.13.2'", "testImplementation gradleTestKit()", "useJUnit()",
                "dependsOn checkDependencyRules", "dependsOn ':NaruServe-api:testFixturesJar'",
                "systemProperty 'naruserve.rootDir'", "systemProperty 'naruserve.testKitWorkDir'"}) {
            assertTrue("root build.gradle holds " + part, build.contains(part));
        }
    }

    // SDS-AC-6; FR-STATIC-006 AC-6 — wave-6-7 SDS: every test task turns the Java 8 canonical-path cache off
    @Test
    public void sdsAc6_FR_STATIC_006_AC6_everyTestTaskSetsCanonCachesOff() throws IOException {
        String build = BuildLayoutTest.read(new File(root, "build.gradle"));
        assertTrue(build.contains(
                "tasks.withType(Test).configureEach { systemProperty 'sun.io.useCanonCaches', 'false' }"));
        assertEquals("false", System.getProperty("sun.io.useCanonCaches"));
    }
}

