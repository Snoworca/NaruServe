package com.snoworca.naruserve.docs;

import static com.snoworca.naruserve.docs.WaveFiveDocsTest.doc;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.snoworca.naruserve.build.BuildLayoutTest;
import com.snoworca.naruserve.build.ScanReportTest;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/**
 * The recorded release runs under "## Recorded runs" of docs/release/test-results.md: one line
 * {@code - run <name>: command=...; date=...; result=...} per name of {@link #RUN_NAMES}.
 */
public final class ReleaseRecordsDocsTest {

    static final List<String> RUN_NAMES = Arrays.asList("full-suite", "sbom", "offline-build", "publish-local",
            "licenses", "vulnerability-scan");

    static final Pattern RUN_LINE = Pattern.compile(
            "^- run ([\\w-]+): command=(.+?); date=(\\d{4}-\\d{2}-\\d{2}); result=(.+)$");

    /** The nine published modules, in the order of naruModules. */
    static final List<String> MODULES = BuildLayoutTest.MODULES;

    /** The published modules of this build: the nine and, when an Android SDK resolved, NaruServe-android. */
    static List<String> publishedModules() {
        List<String> modules = new java.util.ArrayList<>(MODULES);
        if (BuildLayoutTest.androidIncluded()) {
            modules.add(BuildLayoutTest.ANDROID_MODULE);
        }
        return modules;
    }

    /**
     * Run lines of the "## Recorded runs" section by name, each [command, date, result].
     * shortcut: skipped while test-results.md is absent; ReleaseResultsDocsTest (OPS-REL-003) requires the file.
     */
    static Map<String, String[]> runs() throws IOException {
        String root = System.getProperty("naruserve.rootDir");
        assertNotNull("naruserve.rootDir system property", root);
        assumeTrue("docs/release/test-results.md is written by the OPS-REL-003 fragment",
                new File(root, "docs/release/test-results.md").isFile());
        String doc = doc("../release/test-results.md");
        int start = doc.indexOf("\n## Recorded runs");
        assertTrue("test-results.md has a '## Recorded runs' section", start >= 0);
        int end = doc.indexOf("\n## ", start + 1);
        Map<String, String[]> runs = new LinkedHashMap<>();
        for (String line : doc.substring(start, end < 0 ? doc.length() : end).split("\n")) {
            Matcher m = RUN_LINE.matcher(line.trim());
            if (m.matches()) {
                assertTrue("one line per run: " + m.group(1), runs.put(m.group(1),
                        new String[] {m.group(2).trim(), m.group(3), m.group(4).trim()}) == null);
            }
        }
        return runs;
    }

    private static String[] run(String name) throws IOException {
        String[] run = runs().get(name);
        assertNotNull("recorded run line for " + name, run);
        return run;
    }

    // SDS-AC-2, SDS-AC-4; FR-REL-001 AC-2, FR-REL-001 AC-4
    @Test
    public void fr_REL_001_AC2_AC4_everyReleaseRunHasOneLineWithCommandDateAndResult() throws IOException {
        for (String name : RUN_NAMES) {
            String[] run = run(name);
            assertTrue(name + " command runs the wrapper: " + run[0], run[0].contains("./gradlew"));
            assertFalse(name + " result", run[2].isEmpty());
        }
    }

    // SDS-AC-2; FR-REL-001 AC-2
    @Test
    public void fr_REL_001_AC2_sbomRunListsNineModuleFilesWithComponentCounts() throws IOException {
        String[] sbom = run("sbom");
        assertTrue(sbom[0], sbom[0].contains("-Prelease") && sbom[0].contains("releaseSbom"));
        for (String module : publishedModules()) {
            Matcher m = Pattern.compile("(?<![\\w-])" + Pattern.quote(module + ".cdx.json") + "=(\\d+)")
                    .matcher(sbom[2]);
            assertTrue("sbom result names " + module + ".cdx.json=<component count>: " + sbom[2], m.find());
        }
    }

    /** The {@code Status:} word of docs/release/vulnerability-scan.md, {@code pending} or {@code completed}. */
    static String scanStatus() throws IOException {
        Matcher m = Pattern.compile("(?m)^Status: (pending|completed)$").matcher(doc("../release/vulnerability-scan.md"));
        assertTrue("vulnerability-scan.md has a 'Status: pending' or 'Status: completed' line", m.find());
        return m.group(1);
    }

    // SDS-AC-3; FR-REL-002 AC-3
    @Test
    public void fr_REL_002_AC3_vulnerabilityScanRecordHasStatusAndReasonWhenPending() throws IOException {
        String report = doc("../release/vulnerability-scan.md");
        if ("pending".equals(scanStatus())) {
            assertTrue(report, Pattern.compile("(?m)^Reason: (NO_NETWORK|MISSING_API_KEY|RATE_LIMIT|TOOL_ERROR)$")
                    .matcher(report).find());
        }
        List<String> recorded = ScanReportTest.dependencySection(report);
        File graph = new File(System.getProperty("naruserve.depReport"));
        List<String> graphLines = com.snoworca.naruserve.release.ReleaseMain.graphLines(graph);
        assertEquals("the recorded dependency list is the current runtime list; rerun releaseScan and copy the report",
                new java.util.ArrayList<>(com.snoworca.naruserve.release.DependencyList.runtime(graphLines)), recorded);
    }

    // SDS-AC-3; FR-REL-002 AC-3
    @Test
    public void fr_REL_002_AC3_t50VulnerabilityLineMatchesTheScanStatus() throws IOException {
        String root = System.getProperty("naruserve.rootDir");
        assumeTrue("docs/release/test-results.md is written by the OPS-REL-003 fragment",
                new File(root, "docs/release/test-results.md").isFile());
        String expected = "pending".equals(scanStatus()) ? "manual-pending" : "verified";
        String[] row = null;
        for (String[] r : ReleaseResultsDocsTest.rows(doc("../release/test-results.md"))) {
            if ("T50".equals(r[0]) && "vulnerability".equals(r[1])) {
                assertTrue("one T50 vulnerability row", row == null);
                row = r;
                assertTrue(r[0] + " " + r[1] + " carries " + expected + ": " + r[2], expected.equals(r[2]));
            }
        }
        assertNotNull("test-results.md has the '| T50 | vulnerability |' row", row);
    }

    // SDS-AC-7; FR-REL-003 AC-2
    @Test
    public void fr_REL_003_AC2_releaseNotesListThePendingScan() throws IOException {
        String root = System.getProperty("naruserve.rootDir");
        assumeTrue("docs/release/release-notes.md is written by the OPS-REL-007 fragment",
                new File(root, "docs/release/release-notes.md").isFile());
        boolean pending = "pending".equals(scanStatus());
        boolean listed = false;
        boolean completed = false;
        for (String line : doc("../release/release-notes.md").split("\n")) {
            String lower = line.toLowerCase(java.util.Locale.ROOT);
            listed |= lower.contains("vulnerability scan") && lower.contains("pending");
            completed |= lower.contains("vulnerability scan") && lower.contains("completed");
        }
        if (pending) {
            assertTrue("release-notes.md has a line naming the vulnerability scan as pending", listed);
        } else {
            assertFalse("completed scan must not remain a pending open item", listed);
            assertTrue("release-notes.md records the completed vulnerability scan", completed);
        }
    }

    // SDS-AC-8; FR-REL-003 AC-3
    @Test
    public void fr_REL_003_AC3_exceptionsFileHasHeaderAndNoExceptionsOrRows() throws IOException {
        String doc = doc("../release/vulnerability-exceptions.md");
        assertTrue(doc, Pattern.compile("(?m)^\\| id \\| dependency \\| reason \\| approver \\| date \\|$")
                .matcher(doc).find());
        boolean none = Pattern.compile("(?m)^no exceptions$").matcher(doc).find();
        int rows = 0;
        for (String line : doc.split("\n")) {
            String[] cells = line.trim().split("\\s*\\|\\s*", -1);
            if (cells.length == 7 && !"id".equals(cells[1]) && !cells[1].startsWith("-")) {
                rows++;
            }
        }
        assertTrue("either 'no exceptions' or data rows, not both", none != (rows > 0));
    }

    // SDS-AC-4 (wave-8-11); FR-REL-004 AC-4
    @Test
    public void fr_REL_004_AC4_licensesRunLineRecordsTheReleaseLicensesCommandAndResult() throws IOException {
        String[] licenses = run("licenses");
        assertTrue(licenses[0], licenses[0].contains("-Prelease") && licenses[0].contains("releaseLicenses"));
        // ReleaseMain licenses prints "THIRD-PARTY-LICENSES.md: <n> shipped, <m> provided" only on success.
        assertTrue("licenses result is the success line: " + licenses[2],
                Pattern.compile("\\d+ shipped, \\d+ provided").matcher(licenses[2]).find());
    }

    // SDS-AC-5 (wave-8-11); FR-REL-005 AC-1
    @Test
    public void fr_REL_005_AC1_publishLocalRunLineNamesTheNineMavenLocalDirectories() throws IOException {
        String[] publish = run("publish-local");
        assertTrue(publish[0], publish[0].contains("publishToMavenLocal"));
        assertFalse("publishToMavenLocal runs without -Prelease: " + publish[0], publish[0].contains("-Prelease"));
        for (String module : publishedModules()) {
            assertTrue("publish-local result names com/snoworca/naruserve/" + module + "/0.1.0-SNAPSHOT/: " + publish[2],
                    Pattern.compile("(?<![\\w-])" + Pattern.quote("com/snoworca/naruserve/" + module + "/0.1.0-SNAPSHOT/"))
                            .matcher(publish[2]).find());
        }
    }

    // SDS-AC-4; FR-REL-001 AC-4
    @Test
    public void fr_REL_001_AC4_offlineBuildRunExitedZero() throws IOException {
        String[] offline = run("offline-build");
        assertTrue(offline[0], offline[0].contains("build") && offline[0].contains("--offline"));
        assertTrue(offline[2], offline[2].contains("exit=0"));
    }
}
