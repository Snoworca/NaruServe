package com.snoworca.naruserve.build;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.release.DependencyList;
import com.snoworca.naruserve.release.ReleaseMain;
import com.snoworca.naruserve.release.ScanReport;
import com.snoworca.naruserve.release.ScanReport.Cause;
import com.snoworca.naruserve.release.ScanReport.Finding;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import org.junit.Test;

/** The vulnerability report writer of gradle/release-tools, driven by fixtures and stub exit codes, no network. */
public class ScanReportTest {

    static final List<String> GRAPH = Arrays.asList(
            ":NaruServe-api|compileClasspath|org.jspecify:jspecify:1.0.0",
            ":NaruServe-api|runtimeClasspath|com.snoworca.naruserve:NaruServe-api:0.1.0-SNAPSHOT",
            ":NaruServe-api|testRuntimeClasspath|junit:junit:4.13.2",
            ":NaruServe-servlet|compileClasspath|javax.servlet:javax.servlet-api:3.1.0",
            ":NaruServe-servlet|testRuntimeClasspath|org.eclipse.jetty:jetty-server:9.4.58.v20250814",
            ":NaruServe-tinyhttpd|runtimeClasspath|com.snoworca.tinyhttpd:tinyhttpd-websocket:0.1.0-SNAPSHOT",
            ":NaruServe-tinyhttpd|runtimeClasspath|com.snoworca.tinyhttpd:tinyhttpd-core:0.1.0-SNAPSHOT",
            ":NaruServe-gson|runtimeClasspath|com.google.code.gson:gson:2.14.0",
            ":NaruServe|runtimeClasspath|com.google.code.gson:gson:2.14.0",
            ":NaruServe|runtimeClasspath|com.snoworca.tinyhttpd:tinyhttpd-core:0.1.0-SNAPSHOT",
            ":NaruServe-slf4j|runtimeClasspath|org.slf4j:slf4j-api:1.7.36");

    static final List<String> EXPECTED = Arrays.asList("com.google.code.gson:gson:2.14.0",
            "com.snoworca.tinyhttpd:tinyhttpd-core:0.1.0-SNAPSHOT",
            "com.snoworca.tinyhttpd:tinyhttpd-websocket:0.1.0-SNAPSHOT", "org.slf4j:slf4j-api:1.7.36");

    /** The non-empty lines after "## Dependencies". */
    static List<String> dependencySection(String report) {
        int start = report.indexOf("\n## Dependencies\n");
        assertTrue("report has a '## Dependencies' section:\n" + report, start >= 0);
        List<String> deps = new ArrayList<>();
        for (String line : report.substring(start + "\n## Dependencies\n".length()).split("\n")) {
            if (line.startsWith("## ")) {
                break;
            }
            if (!line.trim().isEmpty()) {
                deps.add(line.trim());
            }
        }
        return deps;
    }

    static boolean hasLine(String report, String line) {
        return Arrays.asList(report.split("\n")).contains(line);
    }

    // SDS-AC-1; FR-REL-002 AC-1
    @Test
    public void fr_REL_002_AC1_eachFailureCauseWritesPendingWithReasonAndSortedRuntimeList() {
        SortedSet<String> deps = DependencyList.runtime(GRAPH);
        assertEquals(EXPECTED, new ArrayList<>(deps));
        for (Cause cause : Cause.values()) {
            String report = ScanReport.pending(cause, deps);
            assertTrue(report, hasLine(report, "Status: pending"));
            assertTrue(report, hasLine(report, "Reason: " + cause.name()));
            assertEquals(EXPECTED, dependencySection(report));
        }
        assertEquals(Arrays.asList("NO_NETWORK", "MISSING_API_KEY", "RATE_LIMIT", "TOOL_ERROR"),
                Arrays.asList(Arrays.stream(Cause.values()).map(Enum::name).toArray(String[]::new)));
    }

    // SDS-AC-1; FR-REL-002 AC-1
    @Test
    public void fr_REL_002_AC1_realGraphReportGivesTheShippedRuntimeDependencies() throws IOException {
        File report = new File(BuildLayoutTest.property("naruserve.depReport"));
        List<String> lines = Arrays.asList(BuildLayoutTest.read(report).split("\r?\n"));
        SortedSet<String> deps = DependencyList.runtime(lines);
        for (String expected : new String[] {"com.google.code.gson:gson:2.14.0",
                "com.google.errorprone:error_prone_annotations:2.48.0", "org.jetbrains.kotlin:kotlin-stdlib:2.2.0",
                "org.jetbrains:annotations:13.0", "org.slf4j:slf4j-api:1.7.36",
                "com.snoworca.tinyhttpd:tinyhttpd-core:0.1.0-SNAPSHOT"}) {
            assertTrue(expected + " in " + deps, deps.contains(expected));
        }
        for (String dep : deps) {
            assertFalse(dep, dep.startsWith("com.snoworca.naruserve:"));
            assertFalse(dep, dep.startsWith("javax.servlet:") || dep.startsWith("org.eclipse.jetty:")
                    || dep.startsWith("junit:") || dep.startsWith("org.jspecify:"));
        }
        assertEquals(new TreeSet<>(deps), deps);
    }

    // SDS-AC-4; FR-REL-002 AC-4
    @Test
    public void fr_REL_002_AC4_compileOnlyAndTestRowsAreLeftOutAndTinyHttpdKept() {
        SortedSet<String> deps = DependencyList.runtime(GRAPH);
        for (String left : new String[] {"javax.servlet:javax.servlet-api:3.1.0", "org.jspecify:jspecify:1.0.0",
                "junit:junit:4.13.2", "org.eclipse.jetty:jetty-server:9.4.58.v20250814",
                "com.snoworca.naruserve:NaruServe-api:0.1.0-SNAPSHOT"}) {
            assertFalse(left, deps.contains(left));
        }
        assertTrue(deps.contains("com.snoworca.tinyhttpd:tinyhttpd-core:0.1.0-SNAPSHOT"));
        assertTrue(deps.contains("com.snoworca.tinyhttpd:tinyhttpd-websocket:0.1.0-SNAPSHOT"));
    }

    // SDS-AC-2; FR-REL-002 AC-2
    @Test
    public void fr_REL_002_AC2_finishedScanWritesCompletedWithScannerTimestampAndFindingsTable() {
        SortedSet<String> deps = DependencyList.runtime(GRAPH);
        String report = ScanReport.completed("OWASP Dependency-Check 13.0.0", "2026-10-20T01:02:03Z",
                Collections.singletonList(new Finding("com.google.code.gson:gson:2.14.0", "CVE-2099-0001", 7.5)),
                deps);
        assertTrue(report, hasLine(report, "Status: completed"));
        assertTrue(report, hasLine(report, "Scanner: OWASP Dependency-Check 13.0.0"));
        assertTrue(report, hasLine(report, "Data timestamp: 2026-10-20T01:02:03Z"));
        assertTrue(report, hasLine(report, "| dependency | identifier | CVSS | severity |"));
        assertTrue(report, hasLine(report, "| com.google.code.gson:gson:2.14.0 | CVE-2099-0001 | 7.5 | High |"));
        assertFalse(report, report.contains("no findings"));
        assertEquals(EXPECTED, dependencySection(report));
    }

    // SDS-AC-2; FR-REL-002 AC-2
    @Test
    public void fr_REL_002_AC2_finishedScanWithoutFindingsWritesNoFindings() {
        String report = ScanReport.completed("OWASP Dependency-Check 13.0.0", "2026-10-20T01:02:03Z",
                Collections.<Finding>emptyList(), DependencyList.runtime(GRAPH));
        assertTrue(report, hasLine(report, "Status: completed"));
        assertTrue(report, hasLine(report, "no findings"));
        assertFalse(report, report.contains("| dependency | identifier |"));
    }

    // SDS-AC-5; FR-REL-002 AC-5
    @Test
    public void fr_REL_002_AC5_nonZeroExitOrMissingFindingsGivesPending() {
        SortedSet<String> deps = DependencyList.runtime(GRAPH);
        List<Finding> none = Collections.emptyList();
        String exit1 = ScanReport.fromChild(1, none, deps, "BUILD FAILED");
        assertTrue(exit1, hasLine(exit1, "Status: pending"));
        assertTrue(exit1, hasLine(exit1, "Reason: TOOL_ERROR"));
        String noReport = ScanReport.fromChild(0, null, deps, "BUILD SUCCESSFUL");
        assertTrue(noReport, hasLine(noReport, "Status: pending"));
        assertEquals(EXPECTED, dependencySection(noReport));
        String done = ScanReport.fromChild(0, none, deps, "BUILD SUCCESSFUL");
        assertTrue(done, hasLine(done, "Status: completed"));
    }

    // SDS-AC-1, SDS-AC-5; FR-REL-002 AC-1, FR-REL-002 AC-5
    @Test
    public void fr_REL_002_AC5_failureCauseIsClassifiedFromTheChildLog() {
        SortedSet<String> deps = DependencyList.runtime(GRAPH);
        String[][] cases = {
            {"java.net.UnknownHostException: services.nvd.nist.gov", "NO_NETWORK"},
            {"NVD API key is required for the update", "MISSING_API_KEY"},
            {"NVD returned 403 Forbidden", "MISSING_API_KEY"},
            {"NVD returned HTTP 429 Too Many Requests", "RATE_LIMIT"},
            {"Analysis failed.", "TOOL_ERROR"},
            {"NvdApiException: Invalid API Key, length of 0 too short", "MISSING_API_KEY"},
            // the scanner's standard key warning and stack-frame line numbers name no cause
            {"An NVD API Key was not provided - it is highly recommended to use one\n"
                    + "\tat org.owasp.dependencycheck.gradle.tasks.AbstractAnalyze.analyze(AbstractAnalyze.groovy:429)\n"
                    + "\tat Foo.bar(Foo.java:403)\n> Analysis failed.", "TOOL_ERROR"},
        };
        for (String[] c : cases) {
            String report = ScanReport.fromChild(1, null, deps, c[0]);
            assertTrue(c[0] + "\n" + report, hasLine(report, "Reason: " + c[1]));
        }
    }

    // SDS-AC-2, SDS-AC-5; FR-REL-002 AC-2, FR-REL-002 AC-5
    @Test
    public void fr_REL_002_AC5_childCsvReportIsReadIntoFindings() {
        String csv = "\"Project\",\"ScanDate\",\"DependencyName\",\"Identifiers\",\"CVE\",\"Description\","
                + "\"CVSSv2_Score\",\"CVSSv3_BaseScore\",\"CVSSv4_BaseScore\"\n"
                + "\"NaruServe\",\"2026-10-20T01:02:03Z\",\"gson-2.14.0.jar\","
                + "\"pkg:maven/com.google.code.gson/gson@2.14.0\",\"CVE-2099-0001\",\"a, \"\"quoted\"\"\nline\","
                + "\"5.0\",\"9.1\",\"\"\n";
        List<Finding> findings = ReleaseMain.findingsFromCsv(csv);
        assertEquals(1, findings.size());
        assertEquals("com.google.code.gson:gson:2.14.0", findings.get(0).dependency);
        assertEquals("CVE-2099-0001", findings.get(0).identifier);
        assertEquals(9.1, findings.get(0).cvss, 0.0001);
        assertEquals("Critical", findings.get(0).severity());
        assertEquals("2026-10-20T01:02:03Z", ReleaseMain.scanDateFromCsv(csv));
        assertTrue(ReleaseMain.findingsFromCsv(csv.substring(0, csv.indexOf('\n') + 1)).isEmpty());
    }

    // SDS-AC-2, SDS-AC-5; FR-REL-002 AC-2, FR-REL-002 AC-5
    @Test
    public void fr_REL_002_AC5_csvRowsWithoutScoreOrPurlKeepTheirSeverityAndJarName() {
        String csv = "\"Project\",\"ScanDate\",\"DependencyName\",\"Identifiers\",\"CVE\",\"CVSSv2_Severity\","
                + "\"CVSSv2_Score\",\"CVSSv3_BaseSeverity\",\"CVSSv3_BaseScore\",\"CVSSv4_BaseSeverity\","
                + "\"CVSSv4_BaseScore\"\n"
                + "\"p\",\"d\",\"a.jar\",\"pkg:maven/g/a@1.0%2Bb\",\"CVE-1\",\"\",\"\",\"HIGH\",\"\",\"\",\"\"\n"
                + "\"p\",\"d\",\"tinyhttpd-core.jar\",\"cpe:2.3:a:nanohttpd:nanohttpd\",\"CVE-2\",\"\",\"\",\"\","
                + "\"9.8\",\"\",\"\"\n"
                + "\"p\",\"d\",\"b.jar\",\"pkg:maven/g/b@2\",\"CVE-3\",\"\",\"\",\"\",\"\",\"\",\"\"\n";
        List<Finding> findings = ReleaseMain.findingsFromCsv(csv);
        assertEquals("g:a:1.0+b", findings.get(0).dependency);
        assertEquals("High", findings.get(0).severity());
        assertEquals("tinyhttpd-core.jar", findings.get(1).dependency);
        assertEquals("Critical", findings.get(1).severity());
        assertTrue(Double.isNaN(findings.get(2).cvss));
    }
}
