package com.snoworca.naruserve.docs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * The READMEs after the 0.12.0 publication (OPS-REL-017). OPS-REL-012 was the same step for 0.11.0; its checks moved
 * to 0.12.0 here, with 0.11.0 kept as the previous release before 0.10.0.
 */
public class ReleasedReadmeDocsTest {

    /** The published release the READMEs describe. */
    static final String VERSION = ReadmeDocsTest.PUBLISHED_VERSION;

    /** The release before {@link #VERSION}. */
    static final String PREVIOUS = ReadmeDocsTest.PREVIOUS_VERSION;

    /** The release before {@link #PREVIOUS}. */
    static final String OLDER = "0.10.0";

    static final String GITHUB_RELEASE = "https://github.com/Snoworca/NaruServe/releases/tag/v" + VERSION;

    static final String CENTRAL_RECORD = ReadmeDocsTest.PUBLISHED_RECORD;

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

    /** The release documents of {@code version}: GitHub release, notes (Korean for README.ko.md), test results, record. */
    private static String[] releaseDocs(String f, String version) {
        return new String[] {"https://github.com/Snoworca/NaruServe/releases/tag/v" + version,
            "docs/release/" + version + (ko(f) ? "-release-notes.ko.md" : "-release-notes.md"),
            "docs/release/" + version + "-test-results.md", "docs/release/verification/central-" + version + ".md"};
    }

    /** Wording that treats 0.12.0 as unpublished or 0.11.0 as the latest release (OPS-REL-017 AC-2). */
    static final String[] STALE = {"has not been released yet", "is not released yet", "not yet on Maven Central",
        "Until " + VERSION + " is published", "until it reaches Maven Central", "once " + VERSION + " reaches Maven Central",
        VERSION + " among them", "release is not available", "Check release availability", "If " + VERSION + " is unavailable",
        "latest release on Maven Central is " + PREVIOUS, PREVIOUS + ", the release on Maven Central",
        "the released " + PREVIOUS, "아직 릴리스되지 않았습니다", VERSION + "은 아직 릴리스되지 않았", "아직 Maven Central에 없습니다",
        "올라가기 전", "올라간 뒤", "배포되기 전", "릴리스 가용성", "받을 수 없다면", "릴리스를 찾지 못하면",
        VERSION + "처럼 아직 릴리스되지 않은", "최신 릴리스는 " + PREVIOUS, "Maven Central의 릴리스인 " + PREVIOUS,
        "릴리스된 " + PREVIOUS};

    /** Whether {@code line} names {@link #PREVIOUS} as the latest or current release. */
    private static boolean namesPreviousAsLatest(String line) {
        return line.contains(PREVIOUS) && (line.contains("latest") || line.contains("최신") || line.contains(PREVIOUS + " (current)")
                || line.contains(PREVIOUS + "(현재)"));
    }

    // OPS-REL-017 AC-1: the Install section links the GitHub release v0.12.0 and its publication record and says that
    // 0.12.0 is on Maven Central; the release documents list the 0.12.0 notes, test results, GitHub release and record,
    // then 0.11.0 as the previous release before 0.10.0
    @Test
    public void OPS_REL_017_AC1_readmesLinkTheReleaseAndItsRecords() throws IOException {
        for (String f : READMES) {
            String doc = read(f);
            String install = section(f, doc, ko(f) ? "## 설치" : "## Install");
            has(f + " Install", install, "](" + GITHUB_RELEASE + ")");
            has(f + " Install", install, "](" + CENTRAL_RECORD + ")");
            has(f + " Install", install,
                    ko(f) ? "NaruServe " + VERSION + "은 Maven Central에 게시되어 있" : "NaruServe " + VERSION + " is on Maven Central");
            String releases = section(f, doc, ko(f) ? "## 릴리스" : "## Releases");
            int last = -1;
            for (String version : new String[] {VERSION, PREVIOUS, OLDER}) {
                String[] links = releaseDocs(f, version);
                for (int i = 0; i < links.length; i++) {
                    if (version.equals(OLDER) && i == 0) {
                        continue; // 0.10.0 has no GitHub release
                    }
                    has(f + " releases", releases, "](" + links[i] + ")");
                    if (!links[i].startsWith("https:")) {
                        assertTrue(links[i] + " exists", new File(ReadmeDocsTest.root(), links[i]).isFile());
                    }
                }
                int at = releases.indexOf("](" + links[1] + ")");
                assertTrue(f + ": the " + version + " notes come after the newer release", at > last);
                last = at;
            }
        }
    }

    // OPS-REL-017 AC-2: no sentence treats 0.12.0 as unpublished or names 0.11.0 as the latest release; the source build
    // is for changes that are not released yet and warns that a local 0.12.0 shadows the Maven Central 0.12.0
    @Test
    public void OPS_REL_017_AC2_noStaleAvailabilityAndTheSourceBuildIsForUnreleasedChanges() throws IOException {
        for (String f : READMES) {
            String doc = read(f);
            for (String phrase : STALE) {
                assertFalse(f + " still says: " + phrase, doc.contains(phrase));
            }
            for (String line : doc.split("\n")) {
                assertFalse(f + " names " + PREVIOUS + " as the latest release: " + line, namesPreviousAsLatest(line));
            }
            String source = section(f, doc, ko(f) ? "### 소스 체크아웃 빌드" : "### Build from a source checkout");
            has(f + " source section", source, ko(f) ? "아직 릴리스되지 않은 변경" : "changes that are not released yet");
            has(f + " source section", source,
                    ko(f) ? "Maven Central의 " + VERSION + "을 가립니다" : "shadows the Maven Central " + VERSION);
            has(f + " source section", source, "mavenLocal()");
        }
    }

    // OPS-REL-017 AC-3: the unresolved-coordinate row of 0.12.0 points to mavenCentral(), not to a local publication
    @Test
    public void OPS_REL_017_AC3_couldNotFindRowPointsToMavenCentral() throws IOException {
        for (String f : READMES) {
            String row = null;
            for (String l : read(f).split("\n")) {
                if (l.startsWith("| `Could not find com.snoworca.naruserve:...:" + VERSION + "` |")) {
                    assertTrue(f + ": one Could not find row", row == null);
                    row = l;
                }
            }
            assertTrue(f + " has the Could not find row of " + VERSION, row != null);
            has(f + " row", row, "mavenCentral()");
            assertFalse(f + " row: " + row, row.contains("mavenLocal()"));
            assertFalse(f + " row: " + row, row.contains("publish") || row.contains("게시"));
            assertFalse(f + " row: " + row, row.contains("released") || row.contains("릴리스"));
        }
    }

    // OPS-REL-017 AC-4: the API indexes send a consumer to the Maven Central coordinates and keep the source build for
    // unreleased changes only; no version banner of docs/api or docs/api/en says that 0.12.0 is unreleased
    @Test
    public void OPS_REL_017_AC4_apiIndexesPointToMavenCentralAndNoBannerCallsTheReleaseUnreleased() throws IOException {
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
        String[] prefixes = {"> **버전 범위:**", "> **Version scope:**", "> **문서 버전:**", "> **Documentation version:**",
            "> **문서 상태:**", "> **Documentation status:**"};
        int banners = 0;
        for (String dir : new String[] {"docs/api", "docs/api/en"}) {
            File[] pages = new File(ReadmeDocsTest.root(), dir).listFiles((d, n) -> n.endsWith(".md"));
            assertTrue(dir, pages != null);
            for (File page : pages) {
                String path = dir + "/" + page.getName();
                for (String l : read(path).split("\n")) {
                    for (String prefix : prefixes) {
                        if (!l.startsWith(prefix)) {
                            continue;
                        }
                        banners++;
                        for (String phrase : STALE) {
                            assertFalse(path + " banner still says: " + phrase + ": " + l, l.contains(phrase));
                        }
                        assertFalse(path + " banner names " + PREVIOUS + " as the latest release: " + l,
                                namesPreviousAsLatest(l));
                    }
                }
            }
        }
        assertTrue("version banners found: " + banners, banners >= 10);
    }

    // OPS-REL-017 AC-5: both READMEs share one heading outline, and the 0.12.0 version note is a blockquote of its own
    @Test
    public void OPS_REL_017_AC5_readmesShareOneOutline() throws IOException {
        assertEquals(headingLevels(read("README.md")), headingLevels(read("README.ko.md")));
        for (String f : READMES) {
            String[] lines = read(f).split("\n", -1);
            int note = -1;
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].startsWith("> ") && lines[i].contains(VERSION)) {
                    note = i;
                    break;
                }
            }
            assertTrue(f + " has a " + VERSION + " version note", note >= 0);
            assertTrue(f + ": the version note is followed by a blank line",
                    note + 1 < lines.length && lines[note + 1].trim().isEmpty());
            assertTrue(f + ": the version note is preceded by a blank line", note > 0 && lines[note - 1].trim().isEmpty());
        }
    }
}
