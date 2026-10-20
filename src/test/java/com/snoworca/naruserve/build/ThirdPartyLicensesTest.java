package com.snoworca.naruserve.build;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.release.LicenseCheck.License;
import com.snoworca.naruserve.release.ReleaseMain;
import com.snoworca.naruserve.release.ThirdPartyLicenses;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import org.junit.Test;

/** THIRD-PARTY-LICENSES.md: the writer over fixtures, and the committed file against the resolved graph. */
public class ThirdPartyLicensesTest {

    static final String PROVIDED_HEADING = ThirdPartyLicenses.PROVIDED_HEADING;

    private static String read(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    static String committed() throws IOException {
        String root = System.getProperty("naruserve.rootDir");
        assertNotNull("naruserve.rootDir system property", root);
        return read(new File(root, "THIRD-PARTY-LICENSES.md"));
    }

    static List<String> graph() throws IOException {
        return ReleaseMain.graphLines(new File(System.getProperty("naruserve.depReport")));
    }

    private static SortedMap<String, License> shipped() {
        SortedMap<String, License> shipped = new TreeMap<>();
        shipped.put("com.google.code.gson:gson:2.14.0",
                new License("Apache-2.0", "https://www.apache.org/licenses/LICENSE-2.0.txt"));
        shipped.put("com.snoworca.tinyhttpd:tinyhttpd-core:0.1.0-SNAPSHOT",
                new License("BSD-3-Clause", "TinyHttpd/LICENSE"));
        return shipped;
    }

    // SDS-AC-1; FR-REL-004 AC-1
    @Test
    public void fr_REL_004_AC1_renderWritesOneRowPerShippedDependencyAndTheProvidedSection() {
        String md = ThirdPartyLicenses.render(shipped(), Collections.singletonList(
                "javax.servlet:javax.servlet-api:3.1.0 | CDDL + GPLv2 with classpath exception | https://x"));
        int provided = md.indexOf(PROVIDED_HEADING);
        assertTrue(md, provided > 0);
        String top = md.substring(0, provided);
        assertTrue(top, top.contains("| com.google.code.gson:gson:2.14.0 | Apache-2.0 | "
                + "https://www.apache.org/licenses/LICENSE-2.0.txt |"));
        assertTrue(top, top.contains("| com.snoworca.tinyhttpd:tinyhttpd-core:0.1.0-SNAPSHOT | BSD-3-Clause | "
                + "TinyHttpd/LICENSE |"));
        assertTrue(md.substring(provided), md.substring(provided).contains(
                "| javax.servlet:javax.servlet-api:3.1.0 | CDDL + GPLv2 with classpath exception | https://x |"));
        assertTrue("rows are diffable against the graph", ThirdPartyLicenses.diff(Arrays.asList(
                ":NaruServe|runtimeClasspath|com.google.code.gson:gson:2.14.0",
                ":NaruServe|runtimeClasspath|com.snoworca.tinyhttpd:tinyhttpd-core:0.1.0-SNAPSHOT",
                ":NaruServe-servlet|compileClasspath|javax.servlet:javax.servlet-api:3.1.0"), md).isEmpty());
    }

    // SDS-AC-1; FR-REL-004 AC-1
    @Test
    public void fr_REL_004_AC1_committedFileNamesTheShippedDependenciesWithLicenseAndUrl() throws IOException {
        String md = committed();
        int provided = md.indexOf(PROVIDED_HEADING);
        assertTrue("THIRD-PARTY-LICENSES.md has the provided section", provided > 0);
        String top = md.substring(0, provided);
        for (String dep : new String[] {"com.google.code.gson:gson:2.14.0",
                "com.google.errorprone:error_prone_annotations:", "org.jetbrains.kotlin:kotlin-stdlib:2.2.0",
                "org.jetbrains:annotations:", "org.slf4j:slf4j-api:1.7.36",
                "com.snoworca.tinyhttpd:tinyhttpd-core:0.1.0-SNAPSHOT",
                "com.snoworca.tinyhttpd:tinyhttpd-websocket:0.1.0-SNAPSHOT"}) {
            String row = null;
            for (String line : top.split("\n")) {
                if (line.startsWith("| " + dep)) {
                    row = line;
                }
            }
            assertNotNull("row for " + dep, row);
            String[] cells = row.split("\\s*\\|\\s*");
            assertEquals(row, 4, cells.length);
            assertTrue(row, !cells[2].isEmpty() && !cells[3].isEmpty());
        }
        assertTrue(md.substring(provided), md.substring(provided).contains("| javax.servlet:javax.servlet-api:3.1.0 |"));
    }

    // SDS-AC-3; FR-REL-004 AC-3
    @Test
    public void fr_REL_004_AC3_committedFileListsExactlyTheRuntimeDependenciesOfTheGraph() throws IOException {
        assertEquals("rerun ./gradlew -Prelease releaseLicenses and commit THIRD-PARTY-LICENSES.md",
                Collections.<String>emptyList(), ThirdPartyLicenses.diff(graph(), committed()));
    }

    // SDS-AC-3; FR-REL-004 AC-3
    @Test
    public void fr_REL_004_AC3_missingAndExtraRowsAreNamed() throws IOException {
        String md = committed();
        List<String> lines = new ArrayList<>(Arrays.asList(md.split("\n", -1)));
        lines.removeIf(l -> l.startsWith("| org.slf4j:slf4j-api:1.7.36 "));
        int gson = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith("| com.google.code.gson:gson:2.14.0 ")) {
                gson = i;
            }
        }
        assertTrue("gson row", gson >= 0);
        lines.add(gson + 1, "| com.example:extra:1.0 | MIT | https://x |");
        List<String> messages = ThirdPartyLicenses.diff(graph(), String.join("\n", lines));
        assertEquals(messages.toString(), 2, messages.size());
        assertTrue(messages.toString(), messages.stream().anyMatch(m -> m.contains("org.slf4j:slf4j-api:1.7.36")
                && m.contains("missing")));
        assertTrue(messages.toString(), messages.stream().anyMatch(m -> m.contains("com.example:extra:1.0")
                && m.contains("extra")));
    }

    // SDS-AC-3; FR-REL-004 AC-3 — the provided section cannot go stale either
    @Test
    public void fr_REL_004_AC3_staleProvidedRowIsNamed() throws IOException {
        String md = committed().replace("| javax.servlet:javax.servlet-api:3.1.0 |", "| javax.servlet:javax.servlet-api:3.0.1 |");
        List<String> messages = ThirdPartyLicenses.diff(graph(), md);
        assertEquals(messages.toString(), 2, messages.size());
        assertTrue(messages.toString(), messages.stream().anyMatch(m -> m.contains("missing")
                && m.contains("javax.servlet:javax.servlet-api:3.1.0") && m.contains("provided")));
        assertTrue(messages.toString(), messages.stream().anyMatch(m -> m.contains("extra")
                && m.contains("javax.servlet:javax.servlet-api:3.0.1")));
    }
}
