package com.snoworca.naruserve.docs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** The READMEs after the 0.11.0 publication (OPS-REL-012). */
public class ReleasedReadmeDocsTest {

    static final String GITHUB_RELEASE = "https://github.com/Snoworca/NaruServe/releases/tag/v0.11.0";

    static final String CENTRAL_RECORD = "docs/release/verification/central-0.11.0.md";

    private static final String[] READMES = {"README.md", "README.ko.md"};

    private static boolean ko(String f) {
        return f.endsWith(".ko.md");
    }

    private static String read(String path) throws IOException {
        return ReadmeDocsTest.read(path);
    }

    private static void has(String where, String text, String needle) {
        assertTrue(where + " lacks: " + needle, text.contains(needle));
    }

    /** The text from {@code heading} (a whole line) up to the next heading of the same or a higher level. */
    private static String section(String f, String doc, String heading) {
        int start = doc.indexOf("\n" + heading + "\n");
        assertTrue(f + " has '" + heading + "'", start >= 0);
        int end = doc.indexOf("\n## ", start + 1);
        return doc.substring(start, end < 0 ? doc.length() : end);
    }

    /** The level-2 and level-3 heading markers of {@code doc} outside fenced blocks, in file order. */
    private static List<String> headingLevels(String doc) {
        List<String> out = new ArrayList<>();
        boolean fenced = false;
        for (String line : doc.split("\n")) {
            if (line.trim().startsWith("```")) {
                fenced = !fenced;
            } else if (!fenced && (line.startsWith("## ") || line.startsWith("### "))) {
                out.add(line.substring(0, line.indexOf(' ')));
            }
        }
        return out;
    }

    // OPS-REL-012 AC-1: the Install section links the GitHub release and the publication record; the release
    // documents list the 0.11.0 notes, test results and GitHub release and keep 0.10.0 as the previous release
    @Test
    public void OPS_REL_012_AC1_readmesLinkTheReleaseAndItsRecords() throws IOException {
        for (String f : READMES) {
            String doc = read(f);
            String install = section(f, doc, ko(f) ? "## 설치" : "## Install");
            has(f + " Install", install, "](" + GITHUB_RELEASE + ")");
            has(f + " Install", install, "](" + CENTRAL_RECORD + ")");
            String releases = section(f, doc, ko(f) ? "## 릴리스" : "## Releases");
            String notes = ko(f) ? "docs/release/0.11.0-release-notes.ko.md" : "docs/release/0.11.0-release-notes.md";
            String previous = ko(f) ? "docs/release/0.10.0-release-notes.ko.md" : "docs/release/0.10.0-release-notes.md";
            for (String link : new String[] {GITHUB_RELEASE, notes, "docs/release/0.11.0-test-results.md",
                    CENTRAL_RECORD, previous, "docs/release/0.10.0-test-results.md"}) {
                has(f + " releases", releases, "](" + link + ")");
                if (!link.startsWith("https:")) {
                    assertTrue(link + " exists", new File(ReadmeDocsTest.root(), link).isFile());
                }
            }
            assertTrue(f + ": 0.11.0 is listed before 0.10.0", releases.indexOf(notes) < releases.indexOf(previous));
        }
    }

    // OPS-REL-012 AC-2: no sentence treats 0.11.0 as unpublished; the source build is for unreleased changes and
    // warns that a local 0.11.0 shadows the Central one
    @Test
    public void OPS_REL_012_AC2_noStaleAvailabilityAndTheSourceBuildIsForUnreleasedChanges() throws IOException {
        String[] stale = {"not yet on Maven Central", "Until 0.11.0 is published", "release is not available",
                "Check release availability", "If 0.11.0 is unavailable", "아직 Maven Central에 없습니다", "배포되기 전",
                "릴리스 가용성", "받을 수 없다면", "릴리스를 찾지 못하면"};
        for (String f : READMES) {
            String doc = read(f);
            for (String phrase : stale) {
                assertFalse(f + " still says: " + phrase, doc.contains(phrase));
            }
            String source = section(f, doc, ko(f) ? "### 소스 체크아웃 빌드" : "### Build from a source checkout");
            has(f + " source section", source, ko(f) ? "아직 릴리스되지 않은 변경" : "changes that are not released yet");
            has(f + " source section", source,
                    ko(f) ? "Maven Central의 0.11.0을 가립니다" : "shadows the Maven Central 0.11.0");
            has(f + " source section", source, "mavenLocal()");
        }
    }

    // OPS-REL-012 AC-3: the unresolved-coordinate row points to mavenCentral(), not to a local publication
    @Test
    public void OPS_REL_012_AC3_couldNotFindRowPointsToMavenCentral() throws IOException {
        for (String f : READMES) {
            String row = null;
            for (String l : read(f).split("\n")) {
                if (l.startsWith("| `Could not find com.snoworca.naruserve:...:0.11.0` |")) {
                    assertTrue(f + ": one Could not find row", row == null);
                    row = l;
                }
            }
            assertTrue(f + " has the Could not find row", row != null);
            has(f + " row", row, "mavenCentral()");
            assertFalse(f + " row: " + row, row.contains("mavenLocal()"));
            assertFalse(f + " row: " + row, row.contains("publish") || row.contains("게시"));
        }
    }

    // OPS-REL-012 AC-4: the API indexes send a consumer to the Maven Central coordinates and keep the source build
    // for unreleased changes only
    @Test
    public void OPS_REL_012_AC4_apiIndexesPointToMavenCentral() throws IOException {
        String[][] cases = {
            {"docs/api/README.md", "](../../README.ko.md#설치)", "Maven Central", "아직 릴리스되지 않은 변경"},
            {"docs/api/en/README.md", "](../../../README.md#install)", "Maven Central", "changes that are not released yet"}};
        String[] stale = {"Maven Central에서 버전이 조회되지 않으면", "저장소 가용성", "does not resolve from Maven Central",
                "unavailable", "source-build fallback"};
        for (String[] c : cases) {
            String doc = read(c[0]);
            for (int i = 1; i < c.length; i++) {
                has(c[0], doc, c[i]);
            }
            for (String phrase : stale) {
                assertFalse(c[0] + " still says: " + phrase, doc.contains(phrase));
            }
        }
    }

    // OPS-REL-012 AC-5: both READMEs share one heading outline, and the version note is a blockquote of its own
    @Test
    public void OPS_REL_012_AC5_readmesShareOneOutline() throws IOException {
        assertEquals(headingLevels(read("README.md")), headingLevels(read("README.ko.md")));
        for (String f : READMES) {
            String[] lines = read(f).split("\n", -1);
            int note = -1;
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].startsWith("> ") && lines[i].contains("0.11.0")) {
                    note = i;
                    break;
                }
            }
            assertTrue(f + " has a 0.11.0 version note", note >= 0);
            assertTrue(f + ": the version note is followed by a blank line",
                    note + 1 < lines.length && lines[note + 1].trim().isEmpty());
            assertTrue(f + ": the version note is preceded by a blank line", note > 0 && lines[note - 1].trim().isEmpty());
        }
    }
}
