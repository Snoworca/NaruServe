package com.snoworca.naruserve.build;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.Test;

/**
 * The local staging repository the outer build published before this test (centralStaging), read through
 * naruserve.centralStaging: the file set Maven Central needs for every module, without a nested build.
 */
public class CentralStagingTest {

    // OPS-REL-013 AC-1 (was OPS-REL-010 AC-1 for 0.11.0, OPS-REL-009 AC-1 for 0.10.0): assert the new publication,
    // including every generated internal edge.
    private static final String VERSION = "0.12.0";

    private static File moduleDir(String artifactId) {
        String root = System.getProperty("naruserve.centralStaging");
        assertNotNull("naruserve.centralStaging system property", root);
        return new File(root, "com/snoworca/naruserve/" + artifactId + "/" + VERSION);
    }

    private static File staged(String artifactId, String suffix) {
        return new File(moduleDir(artifactId), artifactId + "-" + VERSION + suffix);
    }

    private static List<String> entries(File jar) throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipFile zip = new ZipFile(jar)) {
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                names.add(e.nextElement().getName());
            }
        }
        return names;
    }

    // FR-REL-005 AC-6, FR-BUILD-001 AC-4
    @Test
    public void fr_REL_005_AC6_everyModuleHasTheFiveCentralFilesWithChecksums() {
        for (String id : PublishedPomTest.artifactIds()) {
            String main = id.equals("naruserve-android") ? ".aar" : ".jar";
            for (String suffix : new String[] {main, "-sources.jar", "-javadoc.jar", ".pom", ".module"}) {
                File f = staged(id, suffix);
                assertTrue("staged " + f, f.isFile());
                assertTrue("md5 of " + f, new File(f.getPath() + ".md5").isFile());
                assertTrue("sha1 of " + f, new File(f.getPath() + ".sha1").isFile());
            }
            for (String name : moduleDir(id).list()) {
                assertFalse("no test-fixtures artifact: " + name, name.contains("test-fixtures"));
            }
        }
    }

    // OPS-REL-013 AC-1: build.gradle declares the release version 0.12.0 once, and the jar of every JAR module, the
    // proxy included, is named with it under build/libs and in the staging repository
    @Test
    public void OPS_REL_013_AC1_buildGradleDeclaresTheReleaseVersionAndEveryJarCarriesIt() throws IOException {
        String root = System.getProperty("naruserve.rootDir");
        assertNotNull("naruserve.rootDir system property", root);
        String build = new String(java.nio.file.Files.readAllBytes(new File(root, "build.gradle").toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
        java.util.regex.Matcher version = java.util.regex.Pattern.compile("(?m)^\\s*version\\s*=\\s*'([^']+)'")
                .matcher(build);
        assertTrue("build.gradle declares a version", version.find());
        assertEquals("build.gradle version", VERSION, version.group(1));
        assertFalse("build.gradle declares one version", version.find());
        for (String module : BuildLayoutTest.MODULES) {
            File jar = new File(root, module + "/build/libs/" + module + "-" + VERSION + ".jar");
            assertTrue("jar named with the release version: " + jar, jar.isFile());
        }
        assertTrue(staged("naruserve-proxy", ".jar").isFile());
        assertTrue(staged("naruserve-proxy", ".pom").isFile());
    }

    // OPS-REL-010 AC-1, OPS-REL-013 AC-1: the staged module metadata names the release version, and both examples
    // depend on it
    @Test
    public void OPS_REL_010_AC1_moduleMetadataAndExamplesUseTheReleaseVersion() throws IOException {
        for (String id : PublishedPomTest.artifactIds()) {
            String module = new String(java.nio.file.Files.readAllBytes(staged(id, ".module").toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(id + " module metadata", module.contains("\"module\": \"" + id + "\",\n    \"version\": \""
                    + VERSION + "\""));
        }
        String root = System.getProperty("naruserve.rootDir");
        assertNotNull("naruserve.rootDir system property", root);
        String javaExample = new String(java.nio.file.Files.readAllBytes(new File(root, "examples/java/build.gradle").toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(javaExample, javaExample.contains("'com.snoworca.naruserve:naruserve:" + VERSION + "'"));
        String kotlin = new String(java.nio.file.Files.readAllBytes(new File(root, "examples/kotlin/build.gradle").toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(kotlin, kotlin.contains("'com.snoworca.naruserve:naruserve:" + VERSION + "'"));
        assertTrue(kotlin, kotlin.contains("'com.snoworca.naruserve:naruserve-kotlin:" + VERSION + "'"));
    }

    // FR-REL-005 AC-6
    @Test
    public void fr_REL_005_AC6_sourcesAndJavadocJarsHoldTheirContent() throws IOException {
        assertTrue(entries(staged("naruserve-api", "-sources.jar")).contains("com/snoworca/naruserve/RestApp.java"));
        assertTrue(entries(staged("naruserve-api", "-javadoc.jar")).contains("index.html"));
        boolean kotlinSource = false;
        for (String name : entries(staged("naruserve-kotlin", "-sources.jar"))) {
            kotlinSource |= name.endsWith(".kt");
        }
        assertTrue("naruserve-kotlin sources hold a .kt file", kotlinSource);
    }

    // FR-REL-006 AC-3: naruserve-proxy is staged with the jar, sources, javadoc, POM and module, each checksummed
    @Test
    public void fr_REL_006_AC3_proxyIsStagedWithTheFiveFilesAndTheirChecksums() throws IOException {
        File dir = moduleDir("naruserve-proxy");
        assertTrue("staged " + dir, dir.isDirectory());
        for (String suffix : new String[] {".jar", "-sources.jar", "-javadoc.jar", ".pom", ".module"}) {
            File f = staged("naruserve-proxy", suffix);
            assertTrue("staged " + f, f.isFile());
            assertTrue("md5 of " + f, new File(f.getPath() + ".md5").isFile());
            assertTrue("sha1 of " + f, new File(f.getPath() + ".sha1").isFile());
        }
        for (String name : dir.list()) {
            assertFalse("no test-fixtures artifact: " + name, name.contains("test-fixtures"));
        }
        assertTrue(entries(staged("naruserve-proxy", "-sources.jar"))
                .contains("com/snoworca/naruserve/proxy/package-info.java"));
    }

    // FR-REL-005 AC-6: only this build's artifactIds, no stale upper-case or SNAPSHOT directory
    @Test
    public void fr_REL_005_AC6_stagingHoldsOnlyThePublishedArtifactIds() {
        File group = moduleDir("naruserve").getParentFile().getParentFile();
        Set<String> dirs = new TreeSet<>();
        for (File d : group.listFiles(File::isDirectory)) {
            dirs.add(d.getName());
        }
        assertEquals(PublishedPomTest.artifactIds(), dirs);
    }
}
