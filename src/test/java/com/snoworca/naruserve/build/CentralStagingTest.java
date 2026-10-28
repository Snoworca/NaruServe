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

    // OPS-REL-009 AC-1: assert the new publication, including every generated internal edge.
    private static final String VERSION = "0.10.0";

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
