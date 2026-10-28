package com.snoworca.naruserve.docs;

import static com.snoworca.naruserve.docs.ReadmeDocsTest.read;
import static com.snoworca.naruserve.docs.ReleaseNotesDocsTest.section;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/** docs/release/test-results.md: the T01-T50 result table of object.md 17.2, its evidence links and runs. */
public final class ReleaseResultsDocsTest {

    static final String RESULTS = "docs/release/test-results.md";

    /** The four status literals of OPS-REL-003. */
    static final List<String> STATUS_LITERALS = Arrays.asList("automated", "manual-pending", "verified",
            "out-of-v1-scope");

    static final String OUT_OF_SCOPE_REASON = "user decision 2026-10-20";

    /** Rows {test, part}, in table order, that carry out-of-v1-scope, and no other row does. */
    static final List<String> OUT_OF_SCOPE_ROWS = Arrays.asList("T10 -", "T12 -", "T26 device", "T47 -",
            "T49 device");

    /** The parts of the tests with more than one row; every other test has the part "-". */
    static final Map<String, List<String>> PARTS = new HashMap<>();

    static {
        PARTS.put("T26", Arrays.asList("jvm", "device"));
        PARTS.put("T49", Arrays.asList("jvm", "device"));
        PARTS.put("T50", Arrays.asList("sbom", "vulnerability", "license"));
    }

    private static final Pattern ROW = Pattern.compile("^\\| (T\\d{2}) \\| ([\\w-]+) \\| ([^|]+?) \\| ([^|]+?) \\|$");

    /** The ids of the table under "### 17.2" of object.md, in file order. */
    static List<String> objectIds() throws IOException {
        String object = read("object.md");
        int start = object.indexOf("\n### 17.2");
        assertTrue("object.md has '### 17.2'", start >= 0);
        int end = object.indexOf("\n## ", start + 1);
        List<String> ids = new ArrayList<>();
        Matcher m = Pattern.compile("(?m)^\\| (T\\d{2}) \\|").matcher(object.substring(start, end < 0 ? object.length() : end));
        while (m.find()) {
            ids.add(m.group(1));
        }
        return ids;
    }

    /** The result rows of the document, each [test, part, status, evidence]. */
    static List<String[]> rows(String doc) {
        List<String[]> rows = new ArrayList<>();
        for (String line : doc.split("\n")) {
            Matcher m = ROW.matcher(line.trim());
            if (m.matches()) {
                rows.add(new String[] {m.group(1), m.group(2), m.group(3).trim(), m.group(4).trim()});
            }
        }
        return rows;
    }

    // SDS-AC-6; OPS-REL-003 AC-1
    @Test
    public void ops_REL_003_AC1_fiftyFourRowsWhoseIdsEqualTheObjectTable() throws IOException {
        List<String> ids = objectIds();
        assertEquals("T01 to T50 in object.md 17.2", 50, ids.size());
        List<String> expected = new ArrayList<>();
        for (String id : ids) {
            List<String> parts = PARTS.containsKey(id) ? PARTS.get(id) : Arrays.asList("-");
            for (String part : parts) {
                expected.add(id + " " + part);
            }
        }
        List<String> actual = new ArrayList<>();
        for (String[] row : rows(read(RESULTS))) {
            actual.add(row[0] + " " + row[1]);
        }
        assertEquals(54, expected.size());
        assertEquals(expected, actual);
    }

    // SDS-AC-7; OPS-REL-003 AC-2
    @Test
    public void ops_REL_003_AC2_eachRowHasOneLiteralAndOnlyTheFiveAndroidRowsAreOutOfScope() throws IOException {
        List<String> outOfScope = new ArrayList<>();
        for (String[] row : rows(read(RESULTS))) {
            assertTrue(row[0] + " " + row[1] + " status is a literal: " + row[2], STATUS_LITERALS.contains(row[2]));
            if ("out-of-v1-scope".equals(row[2])) {
                outOfScope.add(row[0] + " " + row[1]);
                assertEquals(row[0] + " reason", OUT_OF_SCOPE_REASON, row[3]);
            }
        }
        assertEquals(OUT_OF_SCOPE_ROWS, outOfScope);
    }

    /** Whether {@code source} declares the method {@code method}, in Java or as a Kotlin (backtick) name. */
    static boolean declares(String source, String method) {
        return Pattern.compile("void " + Pattern.quote(method) + "\\(").matcher(source).find()
                || source.contains("fun `" + method + "`(") || source.contains("fun " + method + "(");
    }

    // SDS-AC-8; OPS-REL-003 AC-3
    @Test
    public void ops_REL_003_AC3_everyEvidenceLinkResolves() throws IOException {
        String doc = read(RESULTS);
        Map<String, String[]> runs = ReleaseRecordsDocsTest.runs();
        String root = System.getProperty("naruserve.rootDir");
        for (String[] row : rows(doc)) {
            String id = row[0] + " " + row[1];
            String evidence = row[3];
            if ("automated".equals(row[2])) {
                for (String link : evidence.split(", ")) {
                    int hash = link.indexOf('#');
                    assertTrue(id + " evidence is path#method: " + link, hash > 0);
                    // OPS-REL-008 AC-2: this unchanged0.9 record refers to its actual source snapshot.
                    try { ReleaseEvidenceSnapshots.method(new File(root), RESULTS, ReleaseEvidenceSnapshots.PIN, link); }
                    catch (Exception failure) { throw new AssertionError(id + " historical evidence", failure); }
                }
            } else if ("manual-pending".equals(row[2])) {
                Matcher m = Pattern.compile("^\\[[^\\]]+\\]\\(#(checklist-\\d+)\\)$").matcher(evidence);
                assertTrue(id + " evidence is a checklist link: " + evidence, m.matches());
                assertTrue(id + " checklist anchor exists", doc.contains("<a id=\"" + m.group(1) + "\"></a>"));
            } else if ("verified".equals(row[2])) {
                Matcher m = Pattern.compile("^\\[[^\\]]+\\]\\(#run-([\\w-]+)\\)$").matcher(evidence);
                assertTrue(id + " evidence is a run link: " + evidence, m.matches());
                assertTrue(id + " run anchor exists", doc.contains("<a id=\"run-" + m.group(1) + "\"></a>"));
                assertNotNull(id + " links a recorded run line", runs.get(m.group(1)));
            }
        }
    }

    // SDS-AC-8; OPS-REL-003 AC-3
    @Test
    public void ops_REL_003_AC3_t50RowsMatchTheirRecords() throws IOException {
        Map<String, String[]> runs = ReleaseRecordsDocsTest.runs();
        String scan = ReleaseRecordsDocsTest.scanStatus();
        Map<String, String[]> t50 = new HashMap<>();
        for (String[] row : rows(read(RESULTS))) {
            if ("T50".equals(row[0])) {
                t50.put(row[1], row);
            }
        }
        for (String[] part : new String[][] {{"sbom", "sbom"}, {"license", "licenses"}}) {
            String[] run = runs.get(part[1]);
            boolean released = run != null && run[0].contains("-Prelease");
            String[] row = t50.get(part[0]);
            assertEquals("T50 " + part[0], released ? "verified" : "manual-pending", row[2]);
            if (released) {
                assertTrue(row[3], row[3].endsWith("(#run-" + part[1] + ")"));
            }
        }
        String[] vulnerability = t50.get("vulnerability");
        assertEquals("pending".equals(scan) ? "manual-pending" : "verified", vulnerability[2]);
        if ("completed".equals(scan)) {
            assertTrue(vulnerability[3], vulnerability[3].endsWith("(#run-vulnerability-scan)"));
        }
    }

    // SDS-AC-9; OPS-REL-003 AC-4
    @Test
    public void ops_REL_003_AC4_postV1LowEndRowOutsideTheTableAndAFullSuiteLine() throws IOException {
        String doc = read(RESULTS);
        String post = section(doc, "## Post-v1 items");
        assertTrue("the post-v1 section holds no result row", rows(post).isEmpty());
        assertTrue(post, post.contains("D-347") && post.toLowerCase(java.util.Locale.ROOT).contains("low-end android"));
        assertTrue("the I-020 sentence",
                doc.contains("No requirement is `verified` on the strength of a `manual-pending` line."));
        String[] full = ReleaseRecordsDocsTest.runs().get("full-suite");
        assertNotNull("a '- run full-suite:' line", full);
        assertTrue(full[0], full[0].startsWith("./gradlew build"));
        assertTrue("full-suite result names the commit: " + full[2],
                Pattern.compile("commit=[0-9a-f]{7,40}").matcher(full[2]).find());
        Matcher tests = Pattern.compile("tests=(\\d+)").matcher(full[2]);
        assertTrue("full-suite result names the test count: " + full[2], tests.find());
        // OPS-REL-003 AC-4: the record cannot go stale; 1561 is the count after the run-final terminal review,
        // and the suite only grows.
        assertTrue(full[2], Integer.parseInt(tests.group(1)) >= 1561);
        assertFalse("a working-tree reference cannot be reproduced: " + full[2], full[2].contains("working tree"));
        assertTrue(full[2], full[2].contains("exit=0"));
    }

    // SDS-AC-10; OPS-REL-003 AC-5
    @Test
    public void ops_REL_003_AC5_scopeStatementAndApi21Basis() throws IOException {
        String doc = read(RESULTS);
        String scope = WaveFiveDocsTest.lineWith(doc, "not part of v1");
        assertTrue(scope, scope.contains("Android sample") && scope.contains("API 21 device or emulator"));
        String basis = WaveFiveDocsTest.lineWith(doc, "android-api-level-21");
        assertTrue(basis, basis.contains("--release 8") && basis.contains("animal-sniffer"));
        assertFalse("no prose status in place of the literal", doc.contains("out of v1 scope (user decision)"));
    }
}
