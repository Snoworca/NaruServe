package com.snoworca.naruserve.docs;

import static com.snoworca.naruserve.docs.ReadmeDocsTest.read;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/** docs/release/release-notes.md: Android scope, recorded deviations, API 21 checks and the platform matrix. */
public final class ReleaseNotesDocsTest {

    static final String NOTES = "docs/release/release-notes.md";

    /** The statuses a platform-matrix row may carry: the four OPS-REL-003 literals plus pending. */
    static final List<String> MATRIX_STATUSES = Arrays.asList("automated", "verified", "pending", "manual-pending",
            "out-of-v1-scope");

    /** Every phrase of the Android scope statement (OPS-REL-007 AC-1). */
    static final List<String> SCOPE_PHRASES = Arrays.asList("Android sample", "AGP", "R8", "StrictMode", "Logcat",
            "API 21 device or emulator", "2026-10-20", "issue #17", "--release 8", "animal-sniffer", "android.*",
            "D-385");

    /** Deviation name to the requirement that records it (OPS-REL-007 AC-3). */
    static final Map<String, String> DEVIATIONS = new LinkedHashMap<>();

    static {
        DEVIATIONS.put("D-408", "FR-WS-004");
        DEVIATIONS.put("D-416", "PERF-APP-001");
        DEVIATIONS.put("D-406", "FR-SSE-002");
        DEVIATIONS.put("D-323", "FR-WS-008");
        DEVIATIONS.put("D-340", "FR-WS-010");
        DEVIATIONS.put("D-106", "IR-HTTP-007");
        DEVIATIONS.put("Servlet SSE blocked-write bound", "FR-SVL-006");
        DEVIATIONS.put("D-380", "OPS-REL-002");
    }

    static final List<String> ANDROID_ITEMS = Arrays.asList("D-364", "D-365", "D-366", "D-367", "D-368", "D-369",
            "D-370", "D-371", "D-372", "D-373", "D-374", "D-412");

    /** The seven API 21 modules of CLAUDE.md, checked by animal-sniffer. */
    static final List<String> API21_MODULES = Arrays.asList("NaruServe-api", "NaruServe", "NaruServe-tinyhttpd",
            "NaruServe-gson", "NaruServe-kotlin", "NaruServe-logcat", "NaruServe-filelog");

    private static final Pattern DEVIATION = Pattern.compile(
            "^- (.+?): frozen: (.+?); deviation: (.+?); recorded in ([A-Z][A-Z0-9-]*[0-9])$");

    static String section(String doc, String heading) {
        int start = doc.indexOf("\n" + heading + "\n");
        assertTrue("has '" + heading + "'", start >= 0);
        int end = doc.indexOf("\n## ", start + 1);
        return doc.substring(start, end < 0 ? doc.length() : end);
    }

    /** Data rows of the "## Platform matrix" table, each [target, status, notes]; a missing cell is "". */
    static List<String[]> matrix(String doc) {
        int start = doc.indexOf("\n## Platform matrix\n");
        List<String[]> rows = new ArrayList<>();
        if (start < 0) {
            return rows;
        }
        int end = doc.indexOf("\n## ", start + 1);
        for (String line : doc.substring(start, end < 0 ? doc.length() : end).split("\n")) {
            String t = line.trim();
            if (!t.startsWith("|") || t.startsWith("| target |") || t.matches("\\|[\\s|:-]+\\|")) {
                continue;
            }
            String[] cells = t.substring(1, t.endsWith("|") ? t.length() - 1 : t.length()).split("\\|", -1);
            String[] row = new String[3];
            for (int i = 0; i < 3; i++) {
                row[i] = i < cells.length ? cells[i].trim() : "";
            }
            rows.add(row);
        }
        return rows;
    }

    /** The matrix rule violations of {@code doc}; empty when the matrix is valid (OPS-REL-004 AC-3). */
    static List<String> matrixRules(String doc) {
        List<String> problems = new ArrayList<>();
        List<String[]> rows = matrix(doc);
        if (rows.size() != 5) {
            problems.add("the matrix has " + rows.size() + " rows, not the five of object.md 17.1");
        }
        for (String[] row : rows) {
            if (!MATRIX_STATUSES.contains(row[1])) {
                problems.add(row[0] + ": no status literal (" + row[1] + ")");
            }
            if (row[0].contains("Java 8") && "verified".equals(row[1])
                    && !row[2].contains("(test-results.md#run-java8)")) {
                problems.add(row[0] + ": verified without a recorded Java 8 run reference");
            }
        }
        for (String check : new String[] {"--release 8", "android-api-level-21"}) {
            if (!doc.contains(check)) {
                problems.add("the compile-time check " + check + " is not named");
            }
        }
        return problems;
    }

    private static String[] row(String doc, String target) {
        for (String[] row : matrix(doc)) {
            if (row[0].contains(target)) {
                return row;
            }
        }
        throw new AssertionError("no matrix row for " + target);
    }

    // SDS-AC-1; OPS-REL-007 AC-1
    @Test
    public void ops_REL_007_AC1_threeDocumentsStateTheAndroidScopeDecision() throws IOException {
        for (String path : new String[] {NOTES, ReleaseResultsDocsTest.RESULTS, "README.md"}) {
            String doc = read(path);
            for (String phrase : SCOPE_PHRASES) {
                assertTrue(path + " states " + phrase, doc.contains(phrase));
            }
            String line = WaveFiveDocsTest.lineWith(doc, "issue #17");
            assertTrue(path + ": " + line, line.contains("closed") && line.contains("2026-10-20"));
        }
        assertTrue(section(read(NOTES), "## Android scope").contains("issue #17"));
    }

    // SDS-AC-2; OPS-REL-007 AC-2
    @Test
    public void ops_REL_007_AC2_serverServiceNoteAndTheSkipEntry() throws IOException {
        String lifecycle = read("docs/api/lifecycle.md");
        int block = lifecycle.indexOf("```java\npublic class ServerService extends Service {");
        assertTrue("lifecycle.md holds the ServerService example", block > 0);
        String before = lifecycle.substring(0, block).trim();
        String note = before.substring(before.lastIndexOf('\n') + 1);
        assertTrue("the line above the example is the note: " + note,
                note.contains("ServerService") && note.contains("v1") && note.contains("컴파일하지 않"));
        DocsExamplesCompileTest.Skip skip = null;
        for (DocsExamplesCompileTest.Skip s : DocsExamplesCompileTest.SKIPS) {
            if (s.file.equals("lifecycle.md") && s.block.equals("public class ServerService extends Service {")) {
                skip = s;
            }
        }
        assertNotNull("DocsExamplesCompileTest.SKIPS keeps the ServerService entry", skip);
        assertEquals("absent type `android.app.Service`", skip.reason);
    }

    // SDS-AC-3; OPS-REL-007 AC-3
    @Test
    public void ops_REL_007_AC3_eightDeviationEntriesResolveToSrsHeadings() throws IOException {
        String headings = srsHeadings();
        Map<String, String> found = new LinkedHashMap<>();
        for (String line : section(read(NOTES), "## Recorded deviations").split("\n")) {
            Matcher m = DEVIATION.matcher(line.trim());
            if (m.matches()) {
                assertTrue("one entry per deviation: " + m.group(1), found.put(m.group(1), m.group(4)) == null);
                assertFalse(m.group(1) + " frozen wording", m.group(2).trim().isEmpty());
                assertFalse(m.group(1) + " deviation", m.group(3).trim().isEmpty());
                assertTrue(m.group(4) + " is a '### <id> —' heading of docs/spec",
                        headings.contains("\n### " + m.group(4) + " —"));
            }
        }
        assertEquals(DEVIATIONS, found);
    }

    private static String srsHeadings() throws IOException {
        File[] srs = new File(System.getProperty("naruserve.rootDir"), "docs/spec")
                .listFiles((d, n) -> n.endsWith(".srs.md"));
        assertNotNull(srs);
        StringBuilder all = new StringBuilder("\n");
        for (File f : srs) {
            all.append(read("docs/spec/" + f.getName())).append('\n');
        }
        return all.toString();
    }

    // SDS-AC-4; OPS-REL-007 AC-4
    @Test
    public void ops_REL_007_AC4_androidItemsAreOutOfScopeAndNeverManualPending() throws IOException {
        String notes = read(NOTES);
        String scope = section(notes, "## Android scope");
        for (String id : ANDROID_ITEMS) {
            Matcher m = Pattern.compile("(?m)^- " + id + ": .*$").matcher(scope);
            assertTrue("'- " + id + ": ...' line", m.find());
            assertTrue(m.group(), m.group().endsWith("out-of-v1-scope"));
        }
        for (String line : notes.split("\n")) {
            for (String id : ANDROID_ITEMS) {
                assertFalse(line, line.contains(id) && line.contains("manual-pending"));
            }
        }
        assertFalse(scope.contains("manual-pending"));
    }

    // SDS-AC-5; OPS-REL-007 AC-5
    @Test
    public void ops_REL_007_AC5_api21ChecksRecordCommandCommitAndResult() throws IOException {
        String checks = section(read(NOTES), "## API 21 checks");
        for (String part : new String[] {"command=./gradlew build", "animalsnifferMain", "checkClassReferences",
                "android-api-level-21", "result=", "android.*"}) {
            assertTrue("API 21 checks hold " + part, checks.contains(part));
        }
        assertTrue("the commit is named", Pattern.compile("commit=[0-9a-f]{7,40}").matcher(checks).find());
        for (String module : API21_MODULES) {
            for (String task : new String[] {"animalsnifferMain", "checkClassReferences"}) {
                assertTrue("API 21 checks name :" + module + ":" + task, checks.contains(":" + module + ":" + task));
            }
        }
        String logcat = WaveFiveDocsTest.lineWith(checks, "- check logcat:");
        assertTrue(logcat, logcat.contains(":NaruServe-logcat:checkClassReferences") && logcat.contains("android.*")
                && logcat.contains("result="));
    }

    // SDS-AC-11; OPS-REL-004 AC-1
    @Test
    public void ops_REL_004_AC1_fiveRowsAndTheJava8RowPending() throws IOException {
        String notes = read(NOTES);
        assertEquals(new ArrayList<String>(), matrixRules(notes));
        String[] java8 = row(notes, "Java 8");
        if ("verified".equals(java8[1])) {
            assertNotNull("a verified Java 8 row needs a recorded 'run java8' line",
                    ReleaseRecordsDocsTest.runs().get("java8"));
        }
        if ("verified".equals(java8[1])) {
            assertTrue("Java 8 verification requires successful runtime evidence", java8RunVerified(
                    ReleaseRecordsDocsTest.runs().get("java8")));
        } else {
            assertEquals("pending", java8[1]);
            assertTrue(java8[2], java8[2].contains("no Java 8 runtime"));
        }
        for (String part : new String[] {"--release 8", "android-api-level-21", "JDK 17"}) {
            assertTrue("Java 8 row names " + part + ": " + java8[2], java8[2].contains(part));
        }
    }

    private static final String JAVA8_EVIDENCE = "docs/release/verification/java8-suite.json";

    private static boolean java8RunVerified(String[] run) {
        return java8RunVerified(run, new File(System.getProperty("naruserve.rootDir"), JAVA8_EVIDENCE));
    }

    private static boolean java8RunVerified(String[] run, File evidence) {
        if (run == null || run.length != 3 || !evidence.isFile()
                || !run[0].matches("(?:^|.*\\s)\\./gradlew\\s+.*")
                || !run[0].matches(".*(?:^|\\s)(?:build|test|:[\\w:-]+:test)(?:\\s|$).*")
                || !run[0].matches(".*(?:^|\\s)-I\\s+gradle/java8-tests\\.gradle(?:\\s|$).*")
                || !run[0].matches(".*(?:^|\\s)-Dnaruserve\\.java8\\.home=\\S+(?:\\s|$).*")
                || !run[1].matches("\\d{4}-\\d{2}-\\d{2}")) return false;
        Map<String, String> result = new LinkedHashMap<>();
        for (String part : run[2].split(";", -1)) {
            int equals = part.indexOf('=');
            if (equals < 1 || result.put(part.substring(0, equals).trim(), part.substring(equals + 1).trim()) != null) {
                return false;
            }
        }
        String runtime = result.get("runtime");
        String tests = result.get("tests");
        if (!"0".equals(result.get("exit")) || !JAVA8_EVIDENCE.equals(result.get("evidence"))
                || runtime == null || !runtime.matches("1\\.8\\.[^; ]+")
                || tests == null || !tests.matches("[1-9][0-9]*")) return false;
        try {
            String text = new String(java.nio.file.Files.readAllBytes(evidence.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            JsonObject report = JsonParser.parseString(text).getAsJsonObject();
            for (String name : Arrays.asList("command", "date", "runtime")) {
                if (!report.has(name) || !report.get(name).isJsonPrimitive()
                        || !report.getAsJsonPrimitive(name).isString()) return false;
            }
            for (String name : Arrays.asList("exit", "tests", "failures", "errors", "skipped")) {
                if (!report.has(name) || !report.get(name).isJsonPrimitive()
                        || !report.getAsJsonPrimitive(name).isNumber()
                        || !report.get(name).getAsString().matches("[0-9]+")) return false;
            }
            return run[0].equals(report.get("command").getAsString())
                    && run[1].equals(report.get("date").getAsString())
                    && runtime.equals(report.get("runtime").getAsString())
                    && tests.equals(report.get("tests").getAsString())
                    && "0".equals(report.get("exit").getAsString())
                    && "0".equals(report.get("failures").getAsString())
                    && "0".equals(report.get("errors").getAsString())
                    && "0".equals(report.get("skipped").getAsString());
        } catch (IOException | RuntimeException invalidEvidence) {
            return false;
        }
    }

    // OPS-REL-004 AC-1; OPS-REL-004 AC-3: reject textual success claims without actual Java 8 suite evidence.
    @Test
    public void ops_REL_004_AC1_verifiedRuntimeRecordRejectsMissingFailedAndWrongRuntime() throws IOException {
        File evidence = File.createTempFile("java8-evidence-", ".json");
        try {
            String command = "./gradlew build --rerun-tasks -I gradle/java8-tests.gradle -Dnaruserve.java8.home=/opt/jdk8";
            String result = "exit=0; tests=1643; runtime=1.8.0_504; evidence=docs/release/verification/java8-suite.json";
            String[] run = {command, "2026-10-21", result};
            String valid = "{\"command\":\"" + command + "\",\"date\":\"2026-10-21\",\"runtime\":\"1.8.0_504\","
                    + "\"exit\":0,\"tests\":1643,\"failures\":0,\"errors\":0,\"skipped\":0}";
            java.nio.file.Files.write(evidence.toPath(), valid.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertTrue(java8RunVerified(run, evidence));
            assertFalse(java8RunVerified(null, evidence));
            for (String badCommand : new String[] {"./gradlew help", "./gradlew build",
                    "./gradlew build -I gradle/java8-tests.gradle",
                    "./gradlew build -Dnaruserve.java8.home=/opt/jdk8",
                    "./gradlew help -I gradle/java8-tests.gradle -Dnaruserve.java8.home=/opt/jdk8"}) {
                assertFalse(badCommand, java8RunVerified(new String[] {badCommand, run[1], result}, evidence));
            }
            for (String badResult : new String[] {result.replace("exit=0", "exit=1"),
                    result.replace("tests=1643", "tests=0"), result.replace("runtime=1.8.0_504", "runtime=17"),
                    result.substring(0, result.indexOf("; evidence=")),
                    result.replace("java8-suite.json", "missing.json")}) {
                assertFalse(badResult, java8RunVerified(new String[] {command, run[1], badResult}, evidence));
            }
            assertFalse(java8RunVerified(new String[] {command, "2026-10-22", result}, evidence));
            for (String invalid : new String[] {"not JSON", "{}", valid.replace("1.8.0_504", "17.0.20"),
                    valid.replace("\"exit\":0", "\"exit\":1"), valid.replace("\"tests\":1643", "\"tests\":0"),
                    valid.replace("\"tests\":1643", "\"tests\":1642"), valid.replace("\"failures\":0", "\"failures\":1"),
                    valid.replace("\"errors\":0", "\"errors\":1"), valid.replace("\"skipped\":0", "\"skipped\":1"),
                    valid.replace("\"failures\":0,", ""), valid.replace("\"command\":", "\"wrong\":"),
                    valid.replace("2026-10-21", "2026-10-22")}) {
                java.nio.file.Files.write(evidence.toPath(), invalid.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                assertFalse(invalid, java8RunVerified(run, evidence));
            }
        } finally { evidence.delete(); }
        assertFalse(java8RunVerified(new String[] {"./gradlew build", "2026-10-21", "exit=0"}, evidence));
    }

    // SDS-AC-12; OPS-REL-004 AC-2
    @Test
    public void ops_REL_004_AC2_androidRowsOutOfScopeAndTheServletRowAutomated() throws IOException {
        String notes = read(NOTES);
        for (String target : new String[] {"Android 5.0", "Latest Android", "Kotlin Android"}) {
            String[] row = row(notes, target);
            assertEquals(target, "out-of-v1-scope", row[1]);
            assertTrue(target + ": " + row[2], row[2].contains("user decision 2026-10-20"));
        }
        String[] servlet = row(notes, "Servlet");
        assertEquals("automated", servlet[1]);
        for (String part : new String[] {"Jetty 9.4", "Servlet 3.1", "HTTP", "multipart", "async", "SSE",
                "WebSocket not supported", "other containers not tested"}) {
            assertTrue("Servlet row names " + part + ": " + servlet[2], servlet[2].contains(part));
        }
    }

    // SDS-AC-13; OPS-REL-004 AC-3
    @Test
    public void ops_REL_004_AC3_matrixRulesRejectTheNegativeFixtures() {
        String head = "x --release 8 android-api-level-21\n\n## Platform matrix\n\n| target | status | notes |\n|---|---|---|\n";
        String rest = "| Android 5.0 | out-of-v1-scope | a |\n| Latest Android | out-of-v1-scope | b |\n"
                + "| Kotlin Android | out-of-v1-scope | c |\n| Servlet | automated | d |\n";
        assertEquals(new ArrayList<String>(), matrixRules(head + "| JVM Java 8 | pending | n |\n" + rest));
        assertFalse("missing status", matrixRules(head + "| JVM Java 8 |  | n |\n" + rest).isEmpty());
        assertFalse("other status word", matrixRules(head + "| JVM Java 8 | passed | n |\n" + rest).isEmpty());
        assertFalse("verified without a run",
                matrixRules(head + "| JVM Java 8 | verified | bytecode is Java 8 |\n" + rest).isEmpty());
        assertEquals(new ArrayList<String>(),
                matrixRules(head + "| JVM Java 8 | verified | [run](test-results.md#run-java8) |\n" + rest));
        assertFalse("a run link that does not reach test-results.md",
                matrixRules(head + "| JVM Java 8 | verified | [run](#run-java8) |\n" + rest).isEmpty());
        assertFalse("compile-time check unnamed", matrixRules(
                head.replace("android-api-level-21", "") + "| JVM Java 8 | pending | n |\n" + rest).isEmpty());
        assertFalse("four rows", matrixRules(head + rest).isEmpty());
    }
}
