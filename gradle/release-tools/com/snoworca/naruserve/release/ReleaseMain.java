package com.snoworca.naruserve.release;

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
import java.util.List;
import java.util.Locale;
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
     * {@code gate <report> <exceptions>} prints the release decision and exits 1 when it blocks.
     */
    // @req FR-REL-002
    // @req FR-REL-003
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
                    DependencyList.runtime(Arrays.asList(read(new File(args[3])).split("\r?\n"))),
                    log.isFile() ? read(log) : "", args[6], scanDate);
            write(new File(args[4]), report);
            System.out.println(report.substring(0, report.indexOf("\n## Dependencies")).trim());
        } else if (command.equals("gate") && args.length == 3) {
            ReleaseGate.Decision decision = ReleaseGate.decide(read(new File(args[1])), read(new File(args[2])));
            System.out.println(decision.message);
            if (!decision.pass) {
                System.exit(1);
            }
        } else {
            System.err.println("usage: scan-report <exit> <csv> <graph> <out> <log> <scanner> | gate <report> <exceptions>");
            System.exit(2);
        }
    }

    /** One finding per data row of a Dependency-Check CSV report; CVSS v3, else v4, else v2, else NaN. */
    // @req FR-REL-002
    public static List<Finding> findingsFromCsv(String csv) {
        List<List<String>> rows = parseCsv(csv);
        List<Finding> findings = new ArrayList<>();
        if (rows.isEmpty()) {
            return findings;
        }
        List<String> header = rows.get(0);
        for (List<String> row : rows.subList(1, rows.size())) {
            Matcher purl = PURL.matcher(cell(header, row, "Identifiers"));
            // Without a purl the jar name stays, which ReleaseGate cannot match and so blocks on.
            String dependency = purl.find() ? purl.group(1) + ":" + purl.group(2) + ":" + decode(purl.group(3))
                    : cell(header, row, "DependencyName");
            double cvss = Double.NaN;
            for (String column : new String[] {"CVSSv3_BaseScore", "CVSSv4_BaseScore", "CVSSv2_Score"}) {
                String score = cell(header, row, column);
                if (Double.isNaN(cvss) && !score.isEmpty()) {
                    try {
                        cvss = Double.parseDouble(score);
                    } catch (NumberFormatException ignored) {
                        // a non-numeric score counts as no score
                    }
                }
            }
            for (String column : new String[] {"CVSSv3_BaseSeverity", "CVSSv4_BaseSeverity", "CVSSv2_Severity"}) {
                if (Double.isNaN(cvss)) {
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
        return rows.size() > 1 ? cell(rows.get(0), rows.get(1), "ScanDate") : null;
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
        for (int i = 0; i < csv.length(); i++) {
            char c = csv.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < csv.length() && csv.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                row.add(field.toString());
                field.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < csv.length() && csv.charAt(i + 1) == '\n') {
                    i++;
                }
                endRow(rows, row, field);
                row = new ArrayList<>();
            } else {
                field.append(c);
            }
        }
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
