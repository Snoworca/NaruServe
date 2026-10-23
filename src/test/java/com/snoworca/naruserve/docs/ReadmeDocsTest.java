package com.snoworca.naruserve.docs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/** The root README.md and the TinyHttpd instruction of README.md and docs/release/release-notes.md. */
public final class ReadmeDocsTest {

    static final String NOT_PUBLISHED_PHRASE = "TinyHttpd is not published by this repository";

    static final String FORBIDDEN_PHRASE = "publish it to mavenLocal";

    static final String INCLUDE_BUILD_SNIPPET = "includeBuild('/path/to/TinyHttpd')";

    static final String VERIFIED_COMMIT = "b759e2d";

    private static final String MARKER = "(미확정)";

    static File root() {
        String root = System.getProperty("naruserve.rootDir");
        assertNotNull("naruserve.rootDir system property", root);
        return new File(root);
    }

    static String read(String path) throws IOException {
        File f = new File(root(), path);
        assertTrue(path + " exists", f.isFile());
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    /** Every fenced block opened by {@code ```<lang>}, in file order. */
    static List<String> blocks(String md, String lang) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = null;
        for (String line : md.split("\n", -1)) {
            if (cur == null) {
                if (line.trim().equals("```" + lang)) {
                    cur = new StringBuilder();
                }
            } else if (line.trim().startsWith("```")) {
                out.add(cur.toString());
                cur = null;
            } else {
                cur.append(line).append('\n');
            }
        }
        return out;
    }

    /** The text of the "5분 시작" section of docs/api/README.md. */
    private static String apiQuickStart() throws IOException {
        String api = read("docs/api/README.md");
        int start = api.indexOf("\n## 5분 시작");
        assertTrue("docs/api/README.md has '## 5분 시작'", start >= 0);
        int end = api.indexOf("\n## ", start + 1);
        return api.substring(start, end < 0 ? api.length() : end);
    }

    // SDS-AC-9; OPS-REL-001 AC-1
    @Test
    public void ops_REL_001_AC1_readmeListsTheNineCoordinatesOfTheApiModuleTableAndTheInstallLines()
            throws IOException {
        Set<String> table = new TreeSet<>();
        Matcher row = Pattern.compile("(?m)^\\| `[\\w-]+` \\| `(com\\.snoworca\\.naruserve:[\\w-]+)` \\|")
                .matcher(read("docs/api/README.md"));
        while (row.find()) {
            table.add(row.group(1) + ":0.1.0-SNAPSHOT");
        }
        assertEquals("module table rows of docs/api/README.md", 9, table.size());
        String readme = read("README.md");
        Set<String> listed = new TreeSet<>();
        Matcher coordinate = Pattern.compile("com\\.snoworca\\.naruserve:[\\w-]+:0\\.1\\.0-SNAPSHOT").matcher(readme);
        while (coordinate.find()) {
            listed.add(coordinate.group());
        }
        assertEquals(table, listed);
        for (String part : new String[] {"publishToMavenLocal", "mavenLocal()",
                "implementation 'com.snoworca.naruserve:NaruServe:0.1.0-SNAPSHOT'"}) {
            assertTrue("README.md holds " + part, readme.contains(part));
        }
    }

    // SDS-AC-10; OPS-REL-001 AC-2
    @Test
    public void ops_REL_001_AC2_quickStartBlocksEqualTheApiReadmeBlocks() throws IOException {
        String readme = read("README.md");
        String api = apiQuickStart();
        for (String lang : new String[] {"java", "kotlin"}) {
            List<String> apiBlocks = blocks(api, lang);
            List<String> readmeBlocks = blocks(readme, lang);
            assertEquals("one ```" + lang + " block under '5분 시작'", 1, apiBlocks.size());
            assertEquals("one ```" + lang + " block in README.md", 1, readmeBlocks.size());
            assertEquals(lang + " quick start", apiBlocks.get(0), readmeBlocks.get(0));
        }
        assertTrue("quick start contains an executable entrypoint", readme.contains("public static void main(String[] args)"));
        assertTrue("quick start contains imports", readme.contains("import com.snoworca.naruserve.RestApp;"));
        assertFalse("quick start no longer needs a placeholder service", readme.contains("userService"));
    }

    // SDS-AC-11; OPS-REL-001 AC-3
    @Test
    public void ops_REL_001_AC3_readmeStatesTheAndroidScopeAndApi21BasisAndLinksTheDocuments() throws IOException {
        String readme = read("README.md");
        String scope = WaveFiveDocsTest.lineWith(readme, "not part of v1");
        assertTrue(scope, scope.contains("Android sample") && scope.contains("API 21 device or emulator verification"));
        String basis = WaveFiveDocsTest.lineWith(readme, "android-api-level-21");
        assertTrue(basis, basis.contains("--release 8") && basis.contains("animal-sniffer"));
        for (String link : new String[] {"docs/api/README.md", "docs/release/release-notes.md",
                "docs/release/test-results.md", "THIRD-PARTY-LICENSES.md"}) {
            assertTrue("README.md links " + link, readme.contains("](" + link + ")"));
        }
        for (String present : new String[] {"docs/api/README.md", "docs/release/release-notes.md",
                "THIRD-PARTY-LICENSES.md"}) {
            assertTrue(present + " exists", new File(root(), present).isFile());
        }
        // shortcut: reported as skipped while test-results.md is absent; the OPS-REL-003 fragment writes it.
        assumeTrue("docs/release/test-results.md is written by the OPS-REL-003 fragment",
                new File(root(), "docs/release/test-results.md").isFile());
    }

    // SDS-AC-12; OPS-REL-001 AC-4
    @Test
    public void ops_REL_001_AC4_readmeHasNoMarkerAndTheTinyHttpdSection() throws IOException {
        String readme = read("README.md");
        assertFalse(readme.contains(MARKER));
        assertTrue("README.md has a TinyHttpd section", Pattern.compile("(?m)^## .*TinyHttpd").matcher(readme).find());
        assertTrue(readme.contains(NOT_PUBLISHED_PHRASE));
    }

    // SDS-AC-13; OPS-REL-005 AC-1; OPS-DX-001 AC-5
    @Test
    public void ops_REL_005_AC1_readmeAndReleaseNotesHoldTheTinyHttpdInstruction() throws IOException {
        for (String path : new String[] {"README.md", "docs/release/release-notes.md"}) {
            String doc = read(path);
            for (String part : new String[] {NOT_PUBLISHED_PHRASE, INCLUDE_BUILD_SNIPPET,
                    "com.snoworca:tinyhttpd-core:3.0.0",
                    "com.snoworca:tinyhttpd-websocket:3.0.0", VERIFIED_COMMIT}) {
                assertTrue(path + " holds " + part, doc.contains(part));
            }
        }
    }

    // SDS-AC-14; OPS-REL-005 AC-2
    @Test
    public void ops_REL_005_AC2_releaseNotesRecordTheConsumerRun() throws IOException {
        String notes = read("docs/release/release-notes.md");
        int start = notes.indexOf("\n## TinyHttpd for consumers");
        assertTrue("release-notes.md has '## TinyHttpd for consumers'", start >= 0);
        int end = notes.indexOf("\n## ", start + 1);
        String section = notes.substring(start, end < 0 ? notes.length() : end);
        Matcher run = Pattern.compile("(?m)^- consumer run: command=(.+?); date=(\\d{4}-\\d{2}-\\d{2}); "
                + "with includeBuild=(.+?); without includeBuild=(.+)$").matcher(section);
        assertTrue("one '- consumer run: command=...; date=...; with includeBuild=...; without includeBuild=...' line",
                run.find());
        assertTrue(run.group(1), run.group(1).contains("gradlew"));
        assertTrue(run.group(3), run.group(3).startsWith("resolved") && run.group(3).contains("tinyhttpd-core"));
        assertTrue(run.group(4), run.group(4).startsWith("failed") && run.group(4).contains("tinyhttpd-core"));
        assertTrue("the consumer depends on NaruServe-tinyhttpd from mavenLocal()",
                section.contains("com.snoworca.naruserve:NaruServe-tinyhttpd:0.1.0-SNAPSHOT")
                        && section.contains("mavenLocal()"));
    }

    // SDS-AC-15; OPS-REL-005 AC-3
    @Test
    public void ops_REL_005_AC3_noDocumentSaysToPublishTinyHttpdToMavenLocal() throws IOException {
        List<String> paths = new ArrayList<>(Arrays.asList("README.md", "docs/release/release-notes.md"));
        File[] api = new File(root(), "docs/api").listFiles((d, n) -> n.endsWith(".md"));
        assertNotNull(api);
        for (File f : api) {
            paths.add("docs/api/" + f.getName());
        }
        for (String path : paths) {
            assertFalse(path + " says '" + FORBIDDEN_PHRASE + "'",
                    read(path).toLowerCase(Locale.ROOT).contains(FORBIDDEN_PHRASE.toLowerCase(Locale.ROOT)));
        }
    }
}
