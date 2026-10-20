package com.snoworca.naruserve.release;

import com.snoworca.naruserve.common.CvssScores;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The release block rule over vulnerability-scan.md and docs/release/vulnerability-exceptions.md. */
public final class ReleaseGate {

    public static final String PENDING_MESSAGE = "scan pending: release not cleared for T50 vulnerability part";

    private static final Pattern STATUS = Pattern.compile("(?m)^Status: (pending|completed)$");
    private static final Pattern COORDINATE = Pattern.compile("[^\\s:|]+:[^\\s:|]+:[^\\s:|]+");

    /** The verdict of {@link #decide}; {@code message} is printed either way. */
    public static final class Decision {
        public final boolean pass;
        public final String message;

        Decision(boolean pass, String message) {
            this.pass = pass;
            this.message = message;
        }
    }

    private ReleaseGate() {
    }

    /**
     * Fails on a High or Critical finding (CVSS 7.0 or higher) of a dependency in the report's dependency list that
     * has no complete exception row (id, dependency, reason, approver and date all non-empty); a pending report
     * passes with {@link #PENDING_MESSAGE}. An unscored finding, and a High one named by a jar instead of a
     * coordinate, block as well unless excepted, and so does any finding whose group and name match a shipped
     * dependency of another version; a completed report with an empty dependency list blocks.
     */
    // @req FR-REL-003
    public static Decision decide(String report, String exceptions) {
        Matcher status = STATUS.matcher(report);
        if (!status.find()) {
            return new Decision(false, "release blocked: the vulnerability report has no 'Status:' line");
        }
        String state = status.group(1);
        Matcher statusLines = Pattern.compile("(?m)^Status:.*$").matcher(report);
        int statusCount = 0;
        while (statusLines.find()) statusCount++;
        if (statusCount != 1) return new Decision(false, "release blocked: ambiguous vulnerability report status");
        if (state.equals("pending")) {
            return new Decision(true, PENDING_MESSAGE);
        }
        Set<String> deps = new HashSet<>();
        Set<String> shippedModules = new HashSet<>();
        for (String line : section(report, "## Dependencies")) {
            if (COORDINATE.matcher(line.trim()).matches()) {
                deps.add(line.trim());
                shippedModules.add(DependencyList.module(line.trim()));
            }
        }
        if (deps.isEmpty()) {
            // without the shipped list no finding can be told apart from a test-scope one: fail closed
            return new Decision(false, "release blocked: the completed vulnerability report lists no '## Dependencies'");
        }
        Set<String> excepted = new HashSet<>();
        for (List<String> row : rows(splitLines(exceptions))) {
            if (row.size() == 5 && !row.contains("") && !row.get(0).equals("id")) {
                excepted.add(row.get(0) + " " + row.get(1));
            }
        }
        if (!report.contains("\n## Findings\n")) {
            return new Decision(false, "release blocked: the completed vulnerability report has no '## Findings'");
        }
        List<String> blocked = new ArrayList<>();
        List<String> findingLines = section(report, "## Findings");
        boolean noFindings = false;
        for (String line : findingLines) {
            String text = line.trim();
            if (text.equals("no findings")) noFindings = true;
            else if (!text.isEmpty() && !(text.startsWith("|") && text.endsWith("|"))) {
                return new Decision(false, "release blocked: malformed vulnerability finding line");
            }
        }
        int findingCount = 0;
        for (List<String> row : rows(findingLines)) {
            if (row.get(0).equals("dependency")) {
                if (row.size() != 4 || !row.get(1).equals("identifier") || !row.get(2).equals("CVSS")
                        || !row.get(3).equals("severity")) {
                    return new Decision(false, "release blocked: malformed findings header");
                }
                continue;
            }
            if (row.size() != 4 || row.get(0).isEmpty() || row.get(1).isEmpty()) {
                return new Decision(false, "release blocked: malformed vulnerability finding");
            }
            findingCount++;
            String dependency = row.get(0);
            boolean coordinate = COORDINATE.matcher(dependency).matches();
            if (coordinate && !shippedModules.contains(DependencyList.module(dependency))) {
                continue; // not shipped: test scope or compileOnly
            }
            double cvss;
            try {
                cvss = Double.parseDouble(row.get(2));
            } catch (NumberFormatException unscored) {
                cvss = Double.NaN;
            }
            // Fail closed: an unscored finding blocks, and so does a High one whose dependency is a jar name, not a
            // coordinate that could be checked against the shipped list.
            if (!Double.isNaN(cvss) && !CvssScores.isValid(cvss)) {
                return new Decision(false, "release blocked: invalid CVSS score in vulnerability finding");
            }
            boolean blocking = Double.isNaN(cvss) || cvss >= 7.0;
            if (blocking && !excepted.contains(row.get(1) + " " + dependency)) {
                blocked.add("release blocked: " + ScanReport.severityOf(cvss) + " " + row.get(1) + " in " + dependency
                        + " (CVSS " + row.get(2) + ") has no complete exception in"
                        + " docs/release/vulnerability-exceptions.md");
            }
        }
        if ((findingCount == 0 && !noFindings) || (findingCount > 0 && noFindings)) {
            return new Decision(false, "release blocked: incomplete or contradictory findings section");
        }
        if (!blocked.isEmpty()) {
            return new Decision(false, String.join("\n", blocked));
        }
        return new Decision(true, "vulnerability scan cleared: no unexcepted High or Critical finding");
    }

    private static List<String> splitLines(String text) {
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\r?\n")) {
            lines.add(line);
        }
        return lines;
    }

    /** The lines after {@code heading} up to the next {@code ## } heading. */
    private static List<String> section(String text, String heading) {
        List<String> lines = new ArrayList<>();
        boolean in = false;
        for (String line : splitLines(text)) {
            if (line.startsWith("## ")) {
                in = line.trim().equals(heading);
            } else if (in) {
                lines.add(line);
            }
        }
        return lines;
    }

    /** The trimmed cells of each Markdown table row, separator rows left out. */
    private static List<List<String>> rows(List<String> lines) {
        List<List<String>> rows = new ArrayList<>();
        for (String line : lines) {
            String t = line.trim();
            if (!t.startsWith("|") || !t.endsWith("|") || t.length() < 2 || t.matches("\\|[\\s:|-]*\\|")) {
                continue;
            }
            List<String> cells = new ArrayList<>();
            for (String cell : t.substring(1, t.length() - 1).split("\\|", -1)) {
                cells.add(cell.trim());
            }
            rows.add(cells);
        }
        return rows;
    }
}
