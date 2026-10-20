package com.snoworca.naruserve.release;

import java.util.List;
import java.util.Locale;
import java.util.SortedSet;
import java.util.regex.Pattern;

/** Writes build/reports/naruserve/vulnerability-scan.md: {@code completed} with findings, or {@code pending}. */
public final class ScanReport {

    /** Why a scan could not finish; written as the {@code Reason:} token. */
    public enum Cause { NO_NETWORK, MISSING_API_KEY, RATE_LIMIT, TOOL_ERROR }

    /** One vulnerability of one dependency; {@code cvss} is NaN when the scanner gave no score. */
    public static final class Finding {
        public final String dependency;
        public final String identifier;
        public final double cvss;

        public Finding(String dependency, String identifier, double cvss) {
            this.dependency = dependency;
            this.identifier = identifier;
            this.cvss = cvss;
        }

        /** CVSS v3 base score bands: Critical 9.0 and above, High 7.0 to 8.9. */
        public String severity() {
            return severityOf(cvss);
        }
    }

    static final String TITLE = "# Vulnerability scan\n\n";
    static final String TABLE_HEADER = "| dependency | identifier | CVSS | severity |";

    private static final Pattern NO_NETWORK = Pattern.compile(
            "unknownhost|no route to host|network is unreachable|connection refused|connect timed out");
    // Bare status numbers would match stack-frame line numbers (Foo.groovy:429), and every keyless run prints
    // "An NVD API Key was not provided", so only failure wording counts.
    private static final Pattern API_KEY = Pattern.compile(
            "invalid api ?key|api ?key is required|missing api ?key|403 forbidden|(status|http|code)[ :=]*403\\b");
    private static final Pattern RATE_LIMIT = Pattern.compile(
            "429 too many|too many requests|rate limit|(status|http|code)[ :=]*429\\b");

    private ScanReport() {
    }

    // @req FR-REL-003
    static String severityOf(double cvss) {
        if (Double.isNaN(cvss)) {
            return "unscored";
        }
        return cvss >= 9.0 ? "Critical" : cvss >= 7.0 ? "High" : cvss >= 4.0 ? "Medium" : cvss > 0 ? "Low" : "None";
    }

    // @req FR-REL-002
    public static String pending(Cause cause, SortedSet<String> deps) {
        return TITLE + "Status: pending\nReason: " + cause.name() + "\n" + dependencies(deps);
    }

    // @req FR-REL-002
    public static String completed(String scannerVersion, String dataTimestamp, List<Finding> findings,
            SortedSet<String> deps) {
        StringBuilder out = new StringBuilder(TITLE).append("Status: completed\nScanner: ").append(scannerVersion)
                .append("\nData timestamp: ").append(dataTimestamp).append("\n\n## Findings\n\n");
        if (findings.isEmpty()) {
            out.append("no findings\n");
        } else {
            out.append(TABLE_HEADER).append("\n|---|---|---|---|\n");
            for (Finding f : findings) {
                String score = Double.isNaN(f.cvss) ? "n/a" : String.format(Locale.ROOT, "%.1f", f.cvss);
                out.append("| ").append(f.dependency).append(" | ").append(f.identifier).append(" | ").append(score)
                        .append(" | ").append(f.severity()).append(" |\n");
            }
        }
        return out.append(dependencies(deps)).toString();
    }

    /** The pending decision with an unnamed scanner; see the six-argument form. */
    // @req FR-REL-002
    public static String fromChild(int exit, List<Finding> findings, SortedSet<String> deps, String log) {
        return fromChild(exit, findings, deps, log, "OWASP Dependency-Check", "unknown");
    }

    /**
     * The report of the child scan: {@code pending} when it exited non-zero or left no findings ({@code findings}
     * null), the cause read from its log, otherwise {@code completed}.
     */
    // @req FR-REL-002
    public static String fromChild(int exit, List<Finding> findings, SortedSet<String> deps, String log,
            String scannerVersion, String dataTimestamp) {
        if (exit != 0 || findings == null) {
            return pending(classify(log), deps);
        }
        return completed(scannerVersion, dataTimestamp, findings, deps);
    }

    // @req FR-REL-002
    static Cause classify(String log) {
        String lower = log.toLowerCase(Locale.ROOT);
        if (NO_NETWORK.matcher(lower).find()) {
            return Cause.NO_NETWORK;
        }
        if (API_KEY.matcher(lower).find()) {
            return Cause.MISSING_API_KEY;
        }
        if (RATE_LIMIT.matcher(lower).find()) {
            return Cause.RATE_LIMIT;
        }
        return Cause.TOOL_ERROR;
    }

    private static String dependencies(SortedSet<String> deps) {
        StringBuilder out = new StringBuilder("\n## Dependencies\n\n");
        for (String dep : deps) {
            out.append(dep).append('\n');
        }
        return out.toString();
    }
}
