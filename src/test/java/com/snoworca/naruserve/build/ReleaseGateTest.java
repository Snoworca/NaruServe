package com.snoworca.naruserve.build;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.release.DependencyList;
import com.snoworca.naruserve.release.ReleaseGate;
import com.snoworca.naruserve.release.ReleaseGate.Decision;
import com.snoworca.naruserve.release.ScanReport;
import com.snoworca.naruserve.release.ScanReport.Cause;
import com.snoworca.naruserve.release.ScanReport.Finding;
import java.util.Collections;
import org.junit.Test;

/** The release block rule of gradle/release-tools over fixture reports and exception tables. */
public class ReleaseGateTest {

    private static final String GSON = "com.google.code.gson:gson:2.14.0";
    private static final String HEADER = "# Vulnerability exceptions\n\n| id | dependency | reason | approver | date |\n"
            + "|---|---|---|---|---|\n";
    private static final String NO_EXCEPTIONS = HEADER + "no exceptions\n";

    private static String report(String dependency, double cvss) {
        return ScanReport.completed("OWASP Dependency-Check 13.0.0", "2026-10-20T01:02:03Z",
                Collections.singletonList(new Finding(dependency, "CVE-2099-0001", cvss)),
                DependencyList.runtime(ScanReportTest.GRAPH));
    }

    private static String exception(String reason, String approver) {
        return HEADER + "| CVE-2099-0001 | " + GSON + " | " + reason + " | " + approver + " | 2026-10-20 |\n";
    }

    // SDS-AC-6; FR-REL-003 AC-1
    @Test
    public void fr_REL_003_AC1_highFindingWithoutExceptionFailsNamingIdAndDependency() {
        Decision d = ReleaseGate.decide(report(GSON, 7.5), NO_EXCEPTIONS);
        assertFalse(d.message, d.pass);
        assertTrue(d.message, d.message.contains("CVE-2099-0001"));
        assertTrue(d.message, d.message.contains(GSON));
    }

    // SDS-AC-6; FR-REL-003 AC-1
    @Test
    public void fr_REL_003_AC1_highFindingWithCompleteExceptionPasses() {
        Decision d = ReleaseGate.decide(report(GSON, 7.5), exception("not reachable from NaruServe", "beom"));
        assertTrue(d.message, d.pass);
    }

    // SDS-AC-6; FR-REL-003 AC-1
    @Test
    public void fr_REL_003_AC1_mediumFindingPasses() {
        Decision d = ReleaseGate.decide(report(GSON, 6.9), NO_EXCEPTIONS);
        assertTrue(d.message, d.pass);
    }

    // SDS-AC-6; FR-REL-003 AC-1
    @Test
    public void fr_REL_003_AC1_criticalFindingFails() {
        Decision d = ReleaseGate.decide(report(GSON, 9.0), NO_EXCEPTIONS);
        assertFalse(d.message, d.pass);
        assertTrue(d.message, d.message.contains("CVE-2099-0001") && d.message.contains(GSON));
    }

    // SDS-AC-6; FR-REL-003 AC-1
    @Test
    public void fr_REL_003_AC1_exceptionWithoutReasonOrApproverIsIgnored() {
        assertFalse(ReleaseGate.decide(report(GSON, 7.5), exception("", "beom")).pass);
        assertFalse(ReleaseGate.decide(report(GSON, 7.5), exception("not reachable", "")).pass);
        assertFalse(ReleaseGate.decide(report(GSON, 9.8), exception(" ", "beom")).pass);
    }

    // SDS-AC-6; FR-REL-003 AC-1
    @Test
    public void fr_REL_003_AC1_exceptionForAnotherDependencyDoesNotClearTheFinding() {
        String other = exception("not reachable", "beom").replace(GSON, "org.slf4j:slf4j-api:1.7.36");
        assertFalse(ReleaseGate.decide(report(GSON, 7.5), other).pass);
    }

    // SDS-AC-7; FR-REL-003 AC-2
    @Test
    public void fr_REL_003_AC2_pendingReportPassesAndPrintsPending() {
        String pending = ScanReport.pending(Cause.NO_NETWORK, DependencyList.runtime(ScanReportTest.GRAPH));
        Decision d = ReleaseGate.decide(pending, NO_EXCEPTIONS);
        assertTrue(d.pass);
        assertEquals("scan pending: release not cleared for T50 vulnerability part", d.message);
    }

    // SDS-AC-7; FR-REL-003 AC-2
    @Test
    public void fr_REL_003_AC2_reportWithoutStatusFails() {
        assertFalse(ReleaseGate.decide("# Vulnerability scan\n", NO_EXCEPTIONS).pass);
    }

    // SDS-AC-6; FR-REL-003 AC-1
    @Test
    public void fr_REL_003_AC1_findingThatCannotBeCheckedFailsClosed() {
        Decision jarName = ReleaseGate.decide(report("tinyhttpd-core.jar", 9.8), NO_EXCEPTIONS);
        assertFalse(jarName.message, jarName.pass);
        assertTrue(jarName.message, jarName.message.contains("tinyhttpd-core.jar"));
        Decision unscored = ReleaseGate.decide(report(GSON, Double.NaN), NO_EXCEPTIONS);
        assertFalse(unscored.message, unscored.pass);
        assertTrue(ReleaseGate.decide(report(GSON, Double.NaN), exception("triaged as Low", "beom")).pass);
        String noFindings = report(GSON, 7.5).replace("## Findings", "## Other");
        assertFalse(ReleaseGate.decide(noFindings, NO_EXCEPTIONS).pass);
    }

    // SDS-AC-9; FR-REL-003 AC-4
    @Test
    public void fr_REL_003_AC4_highFindingOfTestScopeArtifactPasses() {
        Decision d = ReleaseGate.decide(report("junit:junit:4.13.2", 8.8), NO_EXCEPTIONS);
        assertTrue(d.message, d.pass);
    }

    // FR-REL-003 AC-1, AC-4: the gate fails closed (review G4-001). A completed report without shipped dependencies
    // cannot tell shipped from test scope, so it blocks; a finding whose coordinate differs from a shipped one only
    // in the version (a normalised or related purl) blocks instead of being taken for a test-scope artifact.
    @Test
    public void fr_REL_003_AC1_AC4_unknownShipStatusFailsClosed() {
        String noDeps = ScanReport.completed("OWASP Dependency-Check 13.0.0", "2026-10-20T01:02:03Z",
                Collections.singletonList(new Finding(GSON, "CVE-2099-0001", 7.5)),
                new java.util.TreeSet<String>());
        Decision empty = ReleaseGate.decide(noDeps, NO_EXCEPTIONS);
        assertFalse(empty.message, empty.pass);
        assertTrue(empty.message, empty.message.contains("Dependencies"));
        Decision normalised = ReleaseGate.decide(report("com.google.code.gson:gson:2.14", 7.5), NO_EXCEPTIONS);
        assertFalse(normalised.message, normalised.pass);
        assertTrue(normalised.message, normalised.message.contains("com.google.code.gson:gson:2.14"));
    }
}
