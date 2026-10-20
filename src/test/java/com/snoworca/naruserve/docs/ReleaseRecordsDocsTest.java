package com.snoworca.naruserve.docs;

import static com.snoworca.naruserve.docs.WaveFiveDocsTest.doc;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/**
 * The recorded release runs under "## Recorded runs" of docs/release/test-results.md: one line
 * {@code - run <name>: command=...; date=...; result=...} per name of {@link #RUN_NAMES}.
 */
public final class ReleaseRecordsDocsTest {

    static final List<String> RUN_NAMES = Arrays.asList("full-suite", "sbom", "offline-build", "publish-local",
            "licenses", "vulnerability-scan");

    static final Pattern RUN_LINE = Pattern.compile(
            "^- run ([\\w-]+): command=(.+?); date=(\\d{4}-\\d{2}-\\d{2}); result=(.+)$");

    /** The nine published modules, in the order of naruModules. */
    static final List<String> MODULES = Arrays.asList("NaruServe-api", "NaruServe-servlet", "NaruServe-tinyhttpd",
            "NaruServe-gson", "NaruServe", "NaruServe-kotlin", "NaruServe-slf4j", "NaruServe-filelog",
            "NaruServe-logcat");

    /**
     * Run lines of the "## Recorded runs" section by name, each [command, date, result].
     * shortcut: skipped while test-results.md is absent; ReleaseResultsDocsTest (OPS-REL-003) requires the file.
     */
    static Map<String, String[]> runs() throws IOException {
        String root = System.getProperty("naruserve.rootDir");
        assertNotNull("naruserve.rootDir system property", root);
        assumeTrue("docs/release/test-results.md is written by the OPS-REL-003 fragment",
                new File(root, "docs/release/test-results.md").isFile());
        String doc = doc("../release/test-results.md");
        int start = doc.indexOf("\n## Recorded runs");
        assertTrue("test-results.md has a '## Recorded runs' section", start >= 0);
        int end = doc.indexOf("\n## ", start + 1);
        Map<String, String[]> runs = new LinkedHashMap<>();
        for (String line : doc.substring(start, end < 0 ? doc.length() : end).split("\n")) {
            Matcher m = RUN_LINE.matcher(line.trim());
            if (m.matches()) {
                assertTrue("one line per run: " + m.group(1), runs.put(m.group(1),
                        new String[] {m.group(2).trim(), m.group(3), m.group(4).trim()}) == null);
            }
        }
        return runs;
    }

    private static String[] run(String name) throws IOException {
        String[] run = runs().get(name);
        assertNotNull("recorded run line for " + name, run);
        return run;
    }

    // SDS-AC-2, SDS-AC-4; FR-REL-001 AC-2, FR-REL-001 AC-4
    @Test
    public void fr_REL_001_AC2_AC4_everyReleaseRunHasOneLineWithCommandDateAndResult() throws IOException {
        for (String name : RUN_NAMES) {
            String[] run = run(name);
            assertTrue(name + " command runs the wrapper: " + run[0], run[0].contains("./gradlew"));
            assertFalse(name + " result", run[2].isEmpty());
        }
    }

    // SDS-AC-2; FR-REL-001 AC-2
    @Test
    public void fr_REL_001_AC2_sbomRunListsNineModuleFilesWithComponentCounts() throws IOException {
        String[] sbom = run("sbom");
        assertTrue(sbom[0], sbom[0].contains("-Prelease") && sbom[0].contains("releaseSbom"));
        for (String module : MODULES) {
            Matcher m = Pattern.compile("(?<![\\w-])" + Pattern.quote(module + ".cdx.json") + "=(\\d+)")
                    .matcher(sbom[2]);
            assertTrue("sbom result names " + module + ".cdx.json=<component count>: " + sbom[2], m.find());
        }
    }

    // SDS-AC-4; FR-REL-001 AC-4
    @Test
    public void fr_REL_001_AC4_offlineBuildRunExitedZero() throws IOException {
        String[] offline = run("offline-build");
        assertTrue(offline[0], offline[0].contains("build") && offline[0].contains("--offline"));
        assertTrue(offline[2], offline[2].contains("exit=0"));
    }
}
