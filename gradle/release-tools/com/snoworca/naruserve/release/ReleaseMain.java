package com.snoworca.naruserve.release;

import com.snoworca.naruserve.common.CvssScores;

import com.snoworca.naruserve.release.LicenseCheck.License;
import com.snoworca.naruserve.release.ScanReport.Finding;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Entry point of the release tools, run by the JavaExec tasks of gradle/release.gradle. */
public final class ReleaseMain {

    private static final Pattern PURL = Pattern.compile("pkg:maven/([^/\\s,]+)/([^@\\s,]+)@([^\\s,?#]+)");

    private ReleaseMain() {
    }

    /**
     * {@code scan-report <exit> <child csv> <graph report> <out> <child log> <scanner>} writes the scan report;
     * {@code gate <report> <exceptions>} prints the release decision and exits 1 when it blocks;
     * {@code licenses <graph report> <pom dir> <out> [<group:name>=<license>|<url> ...]} writes
     * THIRD-PARTY-LICENSES.md, or exits 1 naming each shipped dependency whose license is GPL-family or unknown.
     */
    // @req FR-REL-002
    // @req FR-REL-003
    // @req FR-REL-004
    public static void main(String[] args) throws IOException {
        String command = args.length > 0 ? args[0] : "";
        if (command.equals("scan-report") && args.length == 7) {
            File csv = new File(args[2]);
            List<Finding> findings = null;
            String scanDate = "unknown";
            if (csv.isFile()) {
                String text = read(csv);
                findings = findingsFromCsv(text);
                String date = scanDateFromCsv(text);
                scanDate = date != null ? date : iso(csv.lastModified());
            }
            File log = new File(args[5]);
            String report = ScanReport.fromChild(Integer.parseInt(args[1]), findings,
                    DependencyList.runtime(graphLines(new File(args[3]))),
                    log.isFile() ? read(log) : "", args[6], scanDate);
            write(new File(args[4]), report);
            System.out.println(report.substring(0, report.indexOf("\n## Dependencies")).trim());
        } else if (command.equals("gate") && args.length == 3) {
            ReleaseGate.Decision decision = ReleaseGate.decide(read(new File(args[1])), read(new File(args[2])));
            System.out.println(decision.message);
            if (!decision.pass) {
                System.exit(1);
            }
        } else if (command.equals("licenses") && args.length >= 4) {
            licenses(args);
        } else {
            System.err.println("usage: scan-report <exit> <csv> <graph> <out> <log> <scanner> | gate <report> <exceptions>"
                    + " | licenses <graph> <pom dir> <out> [<group:name>=<license>|<url> ...]");
            System.exit(2);
        }
    }

    /** The {@code licenses} command: shipped rows must pass {@link LicenseCheck#verdict}; provided rows are listed. */
    // @req FR-REL-004
    private static void licenses(String[] args) throws IOException {
        List<String> graph = graphLines(new File(args[1]));
        File pomDir = new File(args[2]);
        Map<String, License> overrides = new HashMap<>();
        for (String o : Arrays.asList(args).subList(4, args.length)) {
            int eq = o.indexOf('=');
            int bar = eq < 0 ? -1 : o.indexOf('|', eq);
            if (bar < 0) {
                throw new IllegalArgumentException("override must be <group:name>=<license>|<url>: " + o);
            }
            overrides.put(o.substring(0, eq), new License(o.substring(eq + 1, bar), o.substring(bar + 1)));
        }
        SortedSet<String> shipped = DependencyList.runtime(graph);
        SortedMap<String, License> rows = new TreeMap<>();
        List<String> failures = new ArrayList<>();
        for (String dep : shipped) {
            License override = overrides.get(DependencyList.module(dep));
            List<License> found = override != null ? Arrays.asList(override)
                    : LicenseCheck.licensesOf(pomChain(pomDir, dep));
            LicenseCheck.Verdict verdict = LicenseCheck.verdict(found);
            if (override == null && !new File(pomDir, dep.replace(':', '/') + "/0.pom").isFile()) {
                failures.add(dep + ": POM not resolved (offline, or no repository serves it)");
            } else if (!verdict.pass) {
                failures.add(dep + ": " + verdict.message);
            }
            rows.put(dep, joined(found));
        }
        List<String> provided = new ArrayList<>();
        for (String dep : ThirdPartyLicenses.provided(graph)) {
            License l = joined(LicenseCheck.licensesOf(pomChain(pomDir, dep)));
            provided.add(dep + " | " + (l.name.isEmpty() ? "unknown" : l.name) + " | "
                    + (l.url.isEmpty() ? "-" : l.url));
        }
        if (!failures.isEmpty()) {
            for (String f : failures) {
                System.err.println("releaseLicenses: " + f);
            }
            System.exit(1);
        }
        write(new File(args[3]), ThirdPartyLicenses.render(rows, provided));
        System.out.println("THIRD-PARTY-LICENSES.md: " + rows.size() + " shipped, " + provided.size() + " provided");
    }

    /** The POMs releaseLicenses stored as {@code <pom dir>/<group>/<name>/<version>/<n>.pom}, child first. */
    private static List<String> pomChain(File pomDir, String dep) throws IOException {
        File dir = new File(pomDir, dep.replace(':', '/'));
        List<String> chain = new ArrayList<>();
        for (int i = 0; new File(dir, i + ".pom").isFile(); i++) {
            chain.add(read(new File(dir, i + ".pom")));
        }
        return chain;
    }

    /** A dual license as one row: names joined with {@code OR}, URLs with a space. */
    private static License joined(List<License> licenses) {
        StringBuilder names = new StringBuilder();
        StringBuilder urls = new StringBuilder();
        for (License l : licenses) {
            names.append(names.length() > 0 ? " OR " : "").append(l.name);
            if (!l.url.isEmpty()) {
                urls.append(urls.length() > 0 ? " " : "").append(l.url);
            }
        }
        return new License(names.toString(), urls.toString());
    }

    /**
     * One finding per data row of a Dependency-Check CSV report; CVSS v3, else v4, else v2, else NaN. Null, so the
     * report is pending, when the file has no header row or the header lacks the CVE or the Identifiers column.
     */
    // @req FR-REL-002
    public static List<Finding> findingsFromCsv(String csv) {
        List<List<String>> rows = parseCsv(csv);
        if (rows == null || rows.isEmpty() || !rows.get(0).contains("CVE") || !rows.get(0).contains("Identifiers")
                || new HashSet<String>(rows.get(0)).size() != rows.get(0).size()) {
            return null; // an empty or truncated report is not a scan without findings
        }
        List<Finding> findings = new ArrayList<>();
        List<String> header = rows.get(0);
        for (List<String> row : rows.subList(1, rows.size())) {
            if (row.size() != header.size() || cell(header, row, "CVE").isEmpty()) return null;
            Matcher purl = PURL.matcher(cell(header, row, "Identifiers"));
            // Without a purl the jar name stays, which ReleaseGate cannot match and so blocks on.
            String dependency = purl.find() ? purl.group(1) + ":" + purl.group(2) + ":" + decode(purl.group(3))
                    : cell(header, row, "DependencyName");
            if (dependency.isEmpty()) return null;
            double cvss = Double.NaN;
            boolean invalidScore = false;
            for (String column : new String[] {"CVSSv3_BaseScore", "CVSSv4_BaseScore", "CVSSv2_Score"}) {
                String score = cell(header, row, column);
                if (Double.isNaN(cvss) && !score.isEmpty()) {
                    try {
                        cvss = Double.parseDouble(score);
                        if (!CvssScores.isValid(cvss)) {
                            invalidScore = true;
                            cvss = Double.NaN;
                            break;
                        }
                    } catch (NumberFormatException ignored) {
                        // a non-numeric score counts as no score
                    }
                }
            }
            for (String column : new String[] {"CVSSv3_BaseSeverity", "CVSSv4_BaseSeverity", "CVSSv2_Severity"}) {
                if (!invalidScore && Double.isNaN(cvss)) {
                    cvss = bandFloor(cell(header, row, column));
                }
            }
            findings.add(new Finding(dependency, cell(header, row, "CVE"), cvss));
        }
        return findings;
    }

    /** The ScanDate of the first data row, or null when the report has no rows. */
    public static String scanDateFromCsv(String csv) {
        List<List<String>> rows = parseCsv(csv);
        return rows != null && rows.size() > 1 ? cell(rows.get(0), rows.get(1), "ScanDate") : null;
    }

    /** The lowest score of a named severity band, so a severity without a score still lands in its band. */
    private static double bandFloor(String severity) {
        switch (severity.toUpperCase(Locale.ROOT)) {
            case "CRITICAL": return 9.0;
            case "HIGH": return 7.0;
            case "MEDIUM": case "MODERATE": return 4.0;
            case "LOW": return 0.1;
            default: return Double.NaN;
        }
    }

    private static String decode(String purlPart) {
        try {
            return URLDecoder.decode(purlPart.replace("+", "%2B"), "UTF-8");
        } catch (UnsupportedEncodingException | IllegalArgumentException e) {
            return purlPart;
        }
    }

    private static String cell(List<String> header, List<String> row, String column) {
        int i = header.indexOf(column);
        return i >= 0 && i < row.size() ? row.get(i).trim() : "";
    }

    /** RFC 4180 records: quoted fields may hold commas, doubled quotes and line breaks; blank lines are skipped. */
    static List<List<String>> parseCsv(String csv) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean closed = false;
        for (int i = 0; i < csv.length(); i++) {
            char c = csv.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < csv.length() && csv.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                    closed = true;
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                if (closed || field.length() != 0) return null;
                quoted = true;
            } else if (c == ',') {
                closed = false;
                row.add(field.toString());
                field.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < csv.length() && csv.charAt(i + 1) == '\n') {
                    i++;
                }
                endRow(rows, row, field);
                row = new ArrayList<>();
                closed = false;
            } else {
                if (closed) return null;
                field.append(c);
            }
        }
        if (quoted) return null;
        endRow(rows, row, field);
        return rows;
    }

    private static void endRow(List<List<String>> rows, List<String> row, StringBuilder field) {
        row.add(field.toString());
        field.setLength(0);
        if (row.size() > 1 || !row.get(0).isEmpty()) {
            rows.add(row);
        }
    }

    private static String iso(long millis) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(millis));
    }

    /** The lines of a dependency graph report. */
    // @req FR-REL-002
    public static List<String> graphLines(File report) throws IOException {
        return Arrays.asList(read(report).split("\r?\n"));
    }

    private static String read(File file) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[8192];
            for (int n = in.read(buf); n > 0; n = in.read(buf)) {
                out.write(buf, 0, n);
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void write(File file, String text) throws IOException {
        File dir = file.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }
}
