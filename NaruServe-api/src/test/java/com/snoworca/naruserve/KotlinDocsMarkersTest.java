package com.snoworca.naruserve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.Test;

/** Every ```kotlin block of docs/api has exactly one marked Kotlin test, and every marker names a block. */
public final class KotlinDocsMarkersTest {

    /** The Kotlin test source roots that may hold docs-example markers; a missing one holds none. */
    private static final String[] KOTLIN_ROOTS = {
        "NaruServe-api/src/test/kotlin", "NaruServe-kotlin/src/test/kotlin", "NaruServe-servlet/src/test/kotlin",
    };

    private static File root() {
        return new File(System.getProperty("user.dir")).getParentFile();
    }

    private static String read(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    private static Map<String, String> docs() throws IOException {
        Map<String, String> docs = new TreeMap<>();
        for (String prefix : new String[] {"", "en/"}) {
            File[] files = new File(root(), "docs/api/" + prefix).listFiles((d, n) -> n.endsWith(".md"));
            assertTrue("guide directory " + prefix, files != null);
            for (File f : files) docs.put(prefix + f.getName(), read(f));
        }
        return docs;
    }

    private static void collect(File dir, String prefix, Map<String, String> out) throws IOException {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File f : children) {
            if (f.isDirectory()) {
                collect(f, prefix + f.getName() + "/", out);
            } else if (f.getName().endsWith(".kt")) {
                out.put(prefix + f.getName(), read(f));
            }
        }
    }

    private static Map<String, String> kotlinSources() throws IOException {
        Map<String, String> sources = new TreeMap<>();
        for (String r : KOTLIN_ROOTS) {
            collect(new File(root(), r), r + "/", sources);
        }
        return sources;
    }

    private static Map<String, String> one(String key, String value) {
        Map<String, String> m = new TreeMap<>();
        m.put(key, value);
        return m;
    }

    private static final String DOC = "# T\n\n```kotlin\nval a = 1\n```\n\ntext\n\n```kotlin\nval b =   2\n\nval c = 3\n```\n";

    private static final String SOURCE = "class T {\n"
            + "    // docs-example: t.md#1\n"
            + "    fun a() {\n"
            + "        val a = 1\n"
            + "    }\n"
            + "    // docs-example: t.md#2\n"
            + "    fun b() {\n"
            + "        val b = 2\n"
            + "        val c = 3\n"
            + "    }\n"
            + "}\n";

    // OPS-REL-008 AC-3; FR-KT-004 AC-1 AC-4: actual English guides join the marker ledger.
    @Test
    public void OPS_REL_008_AC3_bothLanguageKotlinDocsAreDiscovered() throws IOException {
        Map<String, String> actual = docs();
        for (String prefix : new String[] {"", "en/"}) {
            File[] files = new File(root(), "docs/api/" + prefix).listFiles((dir, name) -> name.endsWith(".md"));
            assertTrue("guide directory " + prefix, files != null && files.length > 0);
            for (File file : files) assertEquals(prefix + file.getName(), read(file), actual.get(prefix + file.getName()));
        }
    }

    // OPS-REL-008 AC-3: an identical English executable block shares the existing
    // compiled/running Korean ordinal marker; translated comments never require duplicated tests.
    @Test
    public void OPS_REL_008_AC3_englishPairReusesExecutedKoreanOrdinal() {
        Map<String, String> paired = one("t.md", DOC.replace("val a = 1", "// 설명\nval a = 1"));
        paired.put("en/t.md", DOC.replace("val a = 1", "// explanation\nval a = 1"));
        assertEquals(Collections.emptyList(), KotlinDocsMarkers.failures(paired, one("T.kt", SOURCE)));
    }

    // OPS-REL-008 AC-3: literals and execution order, not just fence counts, must match.
    @Test
    public void OPS_REL_008_AC3_changedEnglishCodeIsNotCoveredByKoreanRuntimeMarker() {
        Map<String, String> paired = one("t.md", DOC);
        paired.put("en/t.md", DOC.replace("val b =   2", "val b = 99"));
        List<String> failures = KotlinDocsMarkers.failures(paired, one("T.kt", SOURCE));
        assertEquals(failures.toString(), 1, failures.size());
        assertTrue(failures.get(0), failures.get(0).startsWith("en/t.md#2"));
        assertTrue("name the executable parity failure: " + failures, failures.get(0).contains("executable"));
        paired.put("en/t.md", DOC.replace("val b =   2\n\nval c = 3", "val c = 3\nval b = 2"));
        assertTrue("statement reordering changes executable parity",
                KotlinDocsMarkers.failures(paired, one("T.kt", SOURCE)).stream().anyMatch(f -> f.contains("executable")));
        assertFalse("Kotlin newline can change return semantics",
                com.snoworca.naruserve.testing.common.ExampleCodeTokens.kotlinTokens("return value")
                        .equals(com.snoworca.naruserve.testing.common.ExampleCodeTokens.kotlinTokens("return\nvalue")));
        assertFalse("Kotlin raw-string content stays exact",
                com.snoworca.naruserve.testing.common.ExampleCodeTokens.kotlinTokens("val x = \"\"\"a  b\"\"\"")
                        .equals(com.snoworca.naruserve.testing.common.ExampleCodeTokens.kotlinTokens("val x = \"\"\"a b\"\"\"")));
    }

    // OPS-REL-008 AC-3; FR-KT-004 AC-2: even existing marker checks retain literal content.
    @Test
    public void OPS_REL_008_AC3_literalSpacesInsideKotlinCodeCannotBeNormalizedAway() {
        String document = "```kotlin\nval value = \"hello  world\"\n```\n";
        String runtime = "// docs-example: t.md#1\nval value = \"hello world\"\n";
        List<String> failures = KotlinDocsMarkers.failures(one("t.md", document), one("T.kt", runtime));
        assertEquals(failures.toString(), 1, failures.size());
        assertTrue(failures.get(0), failures.get(0).startsWith("t.md#1"));
    }

    // OPS-REL-008 AC-3: an extra English fence cannot silently reuse a different ordinal.
    @Test
    public void OPS_REL_008_AC3_unpairedEnglishFenceHasNoRuntimeCoverage() {
        Map<String, String> paired = one("t.md", DOC);
        paired.put("en/t.md", DOC + "```kotlin\nval extra = 4\n```\n");
        List<String> failures = KotlinDocsMarkers.failures(paired, one("T.kt", SOURCE));
        assertEquals(failures.toString(), 1, failures.size());
        assertTrue(failures.get(0), failures.get(0).startsWith("en/t.md#3"));
    }

    // SDS-AC-1; FR-KT-004 AC-1: real docs and the three Kotlin source dirs give no failure
    @Test
    public void sdsAc1_FR_KT_004_AC_1_real_docs_and_three_source_dirs_give_no_failure() throws IOException {
        Map<String, String> sources = kotlinSources();
        assertTrue("no Kotlin sources read", sources.size() > 10);
        List<String> failures = KotlinDocsMarkers.failures(docs(), sources);
        assertEquals(String.join("\n", failures), Collections.<String>emptyList(), failures);
    }

    @Test
    public void sdsAc1_FR_KT_004_AC_1_a_matching_fixture_gives_no_failure() {
        assertEquals(Collections.<String>emptyList(), KotlinDocsMarkers.failures(one("t.md", DOC), one("T.kt", SOURCE)));
    }

    // SDS-AC-1; FR-KT-004 AC-1: a block without a marker is named
    @Test
    public void sdsAc1_FR_KT_004_AC_1_a_block_without_a_marker_is_named() {
        String source = SOURCE.replace("    // docs-example: t.md#2\n", "");
        List<String> failures = KotlinDocsMarkers.failures(one("t.md", DOC), one("T.kt", source));
        assertEquals(failures.toString(), 1, failures.size());
        assertTrue(failures.get(0), failures.get(0).startsWith("t.md#2"));
    }

    // SDS-AC-4; FR-KT-004 AC-4: an inserted block without its marker is named
    @Test
    public void sdsAc4_FR_KT_004_AC_4_an_inserted_block_without_its_marker_is_named() {
        // A block inserted before the second shifts it to #3; the marker of #2 now meets the new block's lines.
        String doc = DOC.replace("text\n", "text\n\n```kotlin\nval inserted = 0\n```\n");
        List<String> failures = KotlinDocsMarkers.failures(one("t.md", doc), one("T.kt", SOURCE));
        assertTrue(failures.toString(), failures.stream().anyMatch(f -> f.startsWith("t.md#3")));
        assertTrue(failures.toString(), failures.stream().anyMatch(f -> f.startsWith("t.md#2")));
    }

    // SDS-AC-4; FR-KT-004 AC-4: a stale marker is named
    @Test
    public void sdsAc4_FR_KT_004_AC_4_a_stale_marker_is_named() {
        String source = SOURCE + "// docs-example: t.md#3\nfun c() {}\n// docs-example: gone.md#1\nfun d() {}\n";
        List<String> failures = KotlinDocsMarkers.failures(one("t.md", DOC), one("T.kt", source));
        List<String> named = new ArrayList<>();
        for (String f : failures) {
            named.add(f.substring(0, f.indexOf(':')));
        }
        assertEquals(failures.toString(), java.util.Arrays.asList("t.md#3", "gone.md#1"), named);
    }

    @Test
    public void sdsAc1_FR_KT_004_AC_1_a_marker_found_twice_is_named() {
        Map<String, String> sources = one("T.kt", SOURCE);
        sources.put("U.kt", "// docs-example: t.md#1\nval a = 1\n");
        List<String> failures = KotlinDocsMarkers.failures(one("t.md", DOC), sources);
        assertEquals(failures.toString(), 1, failures.size());
        assertTrue(failures.get(0), failures.get(0).startsWith("t.md#1"));
    }

    @Test
    public void sdsAc2_FR_KT_004_AC_2_a_missing_block_line_fails_unless_adapted() {
        String missing = SOURCE.replace("        val c = 3\n", "");
        List<String> failures = KotlinDocsMarkers.failures(one("t.md", DOC), one("T.kt", missing));
        assertEquals(failures.toString(), 1, failures.size());
        assertTrue(failures.get(0), failures.get(0).startsWith("t.md#2"));
        assertTrue(failures.get(0), failures.get(0).contains("val c = 3"));

        String adapted = missing.replace("    fun b() {\n", "    // adapted: c is left out\n    fun b() {\n");
        assertEquals(Collections.<String>emptyList(), KotlinDocsMarkers.failures(one("t.md", DOC), one("T.kt", adapted)));
    }

    @Test
    public void sdsAc2_FR_KT_004_AC_2_a_line_after_the_next_marker_is_outside_the_extent() {
        // val a = 1 sits only in #2's extent, so #1's extent lacks it.
        String source = "// docs-example: t.md#1\nfun a() {}\n// docs-example: t.md#2\nval a = 1\nval b = 2\nval c = 3\n";
        List<String> failures = KotlinDocsMarkers.failures(one("t.md", DOC), one("T.kt", source));
        assertEquals(failures.toString(), 1, failures.size());
        assertTrue(failures.get(0), failures.get(0).startsWith("t.md#1"));
    }
}
