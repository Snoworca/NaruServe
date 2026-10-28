package com.snoworca.naruserve.docs;

import static com.snoworca.naruserve.docs.WaveFiveDocsTest.contains;
import static com.snoworca.naruserve.docs.WaveFiveDocsTest.doc;
import static com.snoworca.naruserve.docs.WaveFiveDocsTest.lineWith;
import static com.snoworca.naruserve.docs.WaveFiveDocsTest.linesWith;
import static com.snoworca.naruserve.docs.WaveSevenDocsTest.h2Section;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/**
 * Wave 8 marker cleanup of docs/api (OPS-REL-002) and the TLS post-v1 line (OPS-REL-006). SDS-AC-n cites
 * docs/sds/2026-10-19.naruserve.naruserve-v1-wave-8-12.sds.md.
 */
// @req OPS-REL-008 AC-1 AC-2 AC-3
public final class ApiMarkersDocsTest {

    private static final String MARKER = "(미확정)";

    /** The only rows of docs/api that keep the marker, all in express-migration.md (D-380 exception). */
    static final List<String> KEPT_PREFIXES = Collections.unmodifiableList(
            Collections.<String>emptyList());

    /** The two TLS reasons: epic #20 has no sub-issue for it; object.md §14.3 and §20.3 need separate tests. */
    static final List<String> TLS_REASON_TOKENS = Collections.unmodifiableList(Arrays.asList("#20", "§14.3", "§20.3"));

    static final String UNDECIDED_HEADING = "## Express에 있지만 아직 정해지지 않은 기능";
    static final String LOW_END_HEADING = "## 저사양 Android 튜닝(수동 후속 작업)";
    static final String BANNER_PHRASE = "이 문서는 v1 구현을 설명합니다";
    // Same text as HttpDocsTest.REDIRECT_POST_V1_PHRASE (another module); change both together.
    static final String REDIRECT_POST_V1_PHRASE = "`res.redirect`는 v1에 포함되지 않습니다";
    static final String REDIRECT_SNIPPET = "`res.status(302).header(\"Location\", url).end()`";

    /** One key per post-v1 entry of release-notes.md; each entry holds its key and a reason ("because"). */
    static final List<String> POST_V1_KEYS = Collections.unmodifiableList(Arrays.asList("`res.render`", "D-347", "TLS/WSS", "`suspend`",
            "`jakarta.servlet`", "WebSocket on the Servlet adapter"));

    private static File root() {
        return ReadmeDocsTest.root();
    }

    private static String releaseNotes() throws IOException {
        return doc("../release/release-notes.md");
    }

    /** The list entries of "## Post-v1 items", each with its indented continuation lines joined by a space. */
    private static List<String> postV1Entries() throws IOException {
        List<String> entries = new ArrayList<>();
        for (String l : h2Section(releaseNotes(), "## Post-v1 items").split("\n")) {
            if (l.startsWith("- ")) {
                entries.add(l);
            } else if (l.startsWith("  ") && !entries.isEmpty()) {
                int last = entries.size() - 1;
                entries.set(last, entries.get(last) + " " + l.trim());
            }
        }
        return entries;
    }

    // SDS-AC-1; OPS-REL-002 AC-1: (미확정) stands on exactly the three express-migration.md rows
    @Test
    public void exactlyThreeMarkerLines() throws IOException {
        File[] files = new File(root(), "docs/api").listFiles();
        assertNotNull(files);
        Arrays.sort(files);
        List<String> found = new ArrayList<>();
        for (File f : files) {
            if (f.getName().endsWith(".md")) {
                for (String l : linesWith(doc(f.getName()), MARKER)) {
                    found.add(f.getName() + ": " + l);
                }
            }
        }
        assertEquals(found.toString(), KEPT_PREFIXES.size(), found.size());
        for (int i = 0; i < KEPT_PREFIXES.size(); i++) {
            assertTrue(found.toString(), found.get(i).startsWith("express-migration.md: " + KEPT_PREFIXES.get(i)));
        }
    }

    // OPS-REL-008 AC-1 AC-2: both banners identify the current profile and migration boundary.
    @Test
    public void sdsAc2_OPS_REL_002_AC2_apiReadmeBanner() throws IOException {
        String readme = doc("README.md");
        assertFalse(readme, readme.contains("아직 구현되지 않았습니다"));
        assertFalse(readme, readme.contains("구현 목표 계약"));
        assertFalse(readme, readme.contains("으로 표시했습니다"));
        String banner = lineWith(readme, "> **문서 상태:**");
        contains("README.md", banner, "0.10.0", "공통 HTTP", "Java Pattern", "(express-migration.md)");
        assertFalse(banner, banner.contains(MARKER));
        String english = ReadmeDocsTest.read("docs/api/en/README.md");
        String englishBanner = lineWith(english, "> **Documentation status:**");
        contains("en/README.md", englishBanner, "0.10.0", "common HTTP", "Java Pattern", "(express-migration.md)");
        assertFalse(englishBanner, englishBanner.contains(MARKER));
    }

    // SDS-AC-3; OPS-REL-002 AC-3: response.md states res.redirect as a post-v1 item; res.type/res.render rows and the
    // sentence under the undecided heading are v1 exclusions with reasons; the heading stays byte-identical
    @Test
    public void sdsAc3_OPS_REL_002_AC3_responseRedirectLineAndExpressExclusions() throws IOException {
        String response = doc("response.md");
        assertTrue(response.contains("res.redirect"));
        assertFalse(response.contains(REDIRECT_POST_V1_PHRASE));
        String migration = doc("express-migration.md");
        assertFalse(migration.contains(MARKER));
        // OPS-REL-008 AC-1 AC-2 AC-3: updated migration table plus the owning guides preserve all APIs.
        contains("express-migration.md", migration, "Router.create", "app.cors", "`redirect`", "`type`");
        contains("middleware.md", doc("middleware.md"), "group(prefix, configurer)");
        contains("response.md", response, "res.redirect", "res.type");

    }

    // SDS-AC-4; OPS-REL-002 AC-4: the low-end Android paragraph is a post-v1 item, the old statement is gone
    @Test
    public void sdsAc4_OPS_REL_002_AC4_lowEndSection() throws IOException {
        String d = doc("defaults.md");
        String low = h2Section(d, LOW_END_HEADING);
        contains("defaults.md", low, "`maxSseStreams`", "`maxUpgradedConnections`", "`sendQueueSize`",
                "수동 후속 작업", "자동", "v1 이후 항목", "v1 범위 밖");
        assertFalse(low, low.contains("릴리스 테스트의 수동 후속 작업이며"));
        assertFalse(low, low.contains("실제 기기에서 연결을 열어 보며"));
    }

    // SDS-AC-5; OPS-REL-002 AC-5: release-notes.md lists every post-v1 item with its reason
    @Test
    public void sdsAc5_OPS_REL_002_AC5_postV1Items() throws IOException {
        List<String> entries = postV1Entries();
        assertEquals(entries.toString(), POST_V1_KEYS.size(), entries.size());
        for (int i = 0; i < POST_V1_KEYS.size(); i++) {
            contains("release-notes.md post-v1 entry", entries.get(i), POST_V1_KEYS.get(i), "because");
        }
    }

    // SDS-AC-6; OPS-REL-002 AC-6: no test source cites the superseded requirement
    @Test
    public void noTestCitesOps007() throws IOException {
        // Built from parts so this file does not match itself.
        String dashed = "OPS-HTTP-" + "007";
        String underscored = "OPS_HTTP_" + "007";
        List<String> hits = new ArrayList<>();
        List<File> roots = new ArrayList<>(Arrays.asList(new File(root(), "src/test"),
                new File(root(), "NaruServe-api/src/test")));
        File[] modules = root().listFiles();
        assertNotNull(modules);
        for (File m : modules) {
            File test = new File(m, "src/test");
            if (m.getName().startsWith("NaruServe") && test.isDirectory() && !roots.contains(test)) {
                roots.add(test);
            }
        }
        for (File start : roots) {
            assertTrue(start + " exists", start.isDirectory());
            scan(start, dashed, underscored, hits);
        }
        assertEquals(Collections.<String>emptyList(), hits);
    }

    private static void scan(File f, String dashed, String underscored, List<String> hits) throws IOException {
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                scan(c, dashed, underscored, hits);
            }
            return;
        }
        if (f.getName().endsWith(".java") || f.getName().endsWith(".kt")) {
            String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            if (text.contains(dashed) || text.contains(underscored)) {
                hits.add(f.getPath());
            }
        }
    }

    // SDS-AC-7, SDS-AC-9; OPS-REL-006 AC-1, AC-3: the TLS line keeps TLS 리스너 and v1, gives both reasons, the
    // reverse proxy and app.trustProxy(true), and cites neither Q-3 nor device runs
    @Test
    public void sdsAc7_OPS_REL_006_AC1_tlsLine() throws IOException {
        String tls = lineWith(doc("defaults.md"), "- **TLS**");
        contains("defaults.md", tls, "TLS 리스너", "v1", "v1 이후 항목", "리버스 프록시", "`app.trustProxy(true)`",
                "API 21", "인증서");
        for (String token : TLS_REASON_TOKENS) {
            contains("defaults.md", tls, token);
        }
        assertFalse(tls, tls.contains("Q-3"));
        assertFalse(tls, tls.contains("기기마다"));
    }

    // SDS-AC-8; OPS-REL-006 AC-2: both documents hold both TLS reasons and neither cites Q-3
    @Test
    public void tlsReasonsInBothDocuments() throws IOException {
        String tlsEntry = null;
        for (String e : postV1Entries()) {
            if (e.contains("TLS/WSS")) {
                tlsEntry = e;
            }
        }
        assertNotNull("release-notes.md has a TLS/WSS post-v1 entry", tlsEntry);
        contains("release-notes.md", tlsEntry, "API 21", "certificate", "TinyHttpd");
        String[][] docs = {{"defaults.md", lineWith(doc("defaults.md"), "- **TLS**")},
            {"release-notes.md", tlsEntry}};
        for (String[] d : docs) {
            for (String token : TLS_REASON_TOKENS) {
                contains(d[0], d[1], token);
            }
        }
        assertFalse(doc("defaults.md").contains("Q-3"));
        assertFalse(releaseNotes().contains("Q-3"));
    }
}
