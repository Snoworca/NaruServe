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

    static final String VERIFIED_COMMIT = "e73cd5d";

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

    // OPS-REL-001 AC-5: the published modules install from Maven Central; mavenLocal is for a source checkout only
    @Test
    public void ops_REL_001_AC5_readmeInstallsFromMavenCentral() throws IOException {
        String readme = read("README.md");
        int install = readme.indexOf("\n## Install");
        assertTrue("README.md has '## Install'", install >= 0);
        int line = readme.indexOf("implementation 'com.snoworca.naruserve:naruserve:0.11.0'", install);
        assertTrue("install line after '## Install'", line > install);
        String before = readme.substring(install, line);
        int repos = before.lastIndexOf("repositories {");
        assertTrue("a repositories block before the install line", repos >= 0);
        String block = before.substring(repos);
        assertTrue(block, block.contains("mavenCentral()"));
        assertFalse(block, block.contains("mavenLocal()"));
        // OPS-REL-008 AC-2; OPS-REL-009 AC-1: coordinates assert only a verified publication; OPS-REL-012 AC-1 AC-2:
        // 0.11.0 is published (docs/release/verification/central-0.11.0.md), so the README says so and links that record.
        assertTrue("README.md states the Maven Central coordinates", readme.contains("Maven Central coordinates"));
        String installSection = ReleaseNotesDocsTest.section(readme, "## Install");
        assertTrue("README.md states that 0.11.0 is on Maven Central",
                installSection.contains("NaruServe 0.11.0 is on Maven Central"));
        assertTrue("README.md links the publication record from Install",
                installSection.contains("](docs/release/verification/central-0.11.0.md)"));
        assertFalse("README.md no longer treats 0.11.0 as unavailable", readme.contains("If 0.11.0 is unavailable"));
        int source = readme.indexOf("### Build from a source checkout");
        assertTrue("README.md has '### Build from a source checkout'", source >= 0);
        assertTrue("publishToMavenLocal only under the source-checkout heading",
                readme.indexOf("publishToMavenLocal") > source);
        assertTrue("mavenLocal() only under the source-checkout heading", readme.indexOf("mavenLocal()") > source);
        for (String f : new String[] {"README.md", "docs/api/README.md"}) {
            String d = read(f);
            assertFalse(f, d.contains("published to the local Maven repository only"));
            assertFalse(f, d.contains("not published to Maven Central"));
        }
    }

    /** The version of the published release the READMEs install. */
    static final String PUBLISHED_VERSION = "0.11.0";

    /** The publication record of {@link #PUBLISHED_VERSION}. */
    static final String PUBLISHED_RECORD = "docs/release/verification/central-0.11.0.md";

    /** The artifactIds the published release staged, read from the Staging row of its publication record. */
    static Set<String> publishedArtifacts() throws IOException {
        String staging = null;
        for (String l : read(PUBLISHED_RECORD).split("\n")) {
            if (l.startsWith("| Staging |")) {
                staging = l;
            }
        }
        assertNotNull(PUBLISHED_RECORD + " has a Staging row", staging);
        Set<String> out = new TreeSet<>();
        Matcher m = Pattern.compile("`(naruserve[\\w-]*)`").matcher(staging);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    /** The coordinates ({@code group:artifactId}) of the module table rows of {@code docs/api/README.md}. */
    static Set<String> apiModuleTable(String path) throws IOException {
        Set<String> table = new TreeSet<>();
        Matcher row = Pattern.compile("(?m)^\\| `[\\w-]+` \\| `(com\\.snoworca\\.naruserve:[\\w-]+)` \\|")
                .matcher(read(path));
        while (row.find()) {
            table.add(row.group(1));
        }
        return table;
    }

    // SDS-AC-9; OPS-REL-001 AC-1; OPS-DX-011 AC-5: README.md lists the coordinates of the module table rows the published
    // release contains (the nine JAR modules and NaruServe-android of 0.11.0) and of no other row, plus the install lines
    @Test
    public void ops_REL_001_AC1_readmeListsTheTenCoordinatesOfTheApiModuleTableAndTheInstallLines()
            throws IOException {
        Set<String> published = publishedArtifacts();
        Set<String> table = new TreeSet<>();
        Set<String> unpublished = new TreeSet<>();
        for (String c : apiModuleTable("docs/api/README.md")) {
            if (published.contains(c.substring(c.indexOf(':') + 1))) {
                table.add(c + ":" + PUBLISHED_VERSION);
            } else {
                unpublished.add(c);
            }
        }
        assertEquals("module table rows of docs/api/README.md that " + PUBLISHED_VERSION + " contains", 10, table.size());
        assertEquals("every module the release staged has a row: " + published, published.size(), table.size());
        assertTrue(table.toString(), table.contains("com.snoworca.naruserve:naruserve-android:0.11.0"));
        String readme = read("README.md");
        Set<String> listed = new TreeSet<>();
        Matcher coordinate = Pattern.compile("com\\.snoworca\\.naruserve:[\\w-]+:0\\.11\\.0(?![\\w.-])").matcher(readme);
        while (coordinate.find()) {
            listed.add(coordinate.group());
        }
        assertEquals(table, listed);
        for (String c : unpublished) {
            assertFalse("README.md shows a versioned coordinate of the unpublished " + c,
                    Pattern.compile(Pattern.quote(c) + ":[0-9]").matcher(readme).find());
        }
        for (String part : new String[] {"publishToMavenLocal", "mavenLocal()",
                "implementation 'com.snoworca.naruserve:naruserve:0.11.0'"}) {
            assertTrue("README.md holds " + part, readme.contains(part));
        }
    }

    // OPS-DX-011 AC-5: the module table holds NaruServe-proxy, a module the published release does not contain, so the
    // README coordinates are the published rows only and neither README gives naruserve-proxy a version
    @Test
    public void OPS_DX_011_AC5_proxyRowIsInTheTableButNotAmongThePublishedCoordinates() throws IOException {
        assertTrue("docs/api/README.md has the naruserve-proxy row",
                apiModuleTable("docs/api/README.md").contains("com.snoworca.naruserve:naruserve-proxy"));
        assertFalse(PUBLISHED_RECORD + " does not stage naruserve-proxy", publishedArtifacts().contains("naruserve-proxy"));
        for (String f : new String[] {"README.md", "README.ko.md"}) {
            // the READMEs install published artifacts only; any form of a naruserve-proxy dependency (colon
            // coordinate, Gradle map notation, a version variable) names the artifactId, so none may appear
            assertFalse(f + " names the unpublished artifact naruserve-proxy", read(f).contains("naruserve-proxy"));
        }
    }

    /** The backticked module names ({@code NaruServe} or {@code NaruServe-x}) of a line. */
    static Set<String> moduleNames(String line) {
        Set<String> out = new TreeSet<>();
        Matcher m = Pattern.compile("`(NaruServe(?:-[\\w]+)?)`").matcher(line);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    /** The one line of {@code text} that starts with {@code prefix}. */
    static String row(String where, String text, String prefix) {
        String found = null;
        for (String l : text.split("\n")) {
            if (l.startsWith(prefix)) {
                assertTrue(where + ": one line starts with " + prefix, found == null);
                found = l;
            }
        }
        assertNotNull(where + ": a line starts with " + prefix, found);
        return found;
    }

    // OPS-DX-011 AC-3: both API indexes have a NaruServe-proxy module row (coordinate, NaruServe-api only, API 21,
    // proxy.md link) and count ten modules built without an Android SDK
    @Test
    public void OPS_DX_011_AC3_apiIndexesHaveTheProxyRowAndCountTenModulesWithoutAnSdk() throws IOException {
        String[][] cases = {
            {"docs/api/README.md", "`NaruServe-api`에만 의존", "지원(API 21+)", "SDK가 없으면 경고만 내고 나머지 열 모듈을 빌드합니다",
                "아홉 모듈"},
            {"docs/api/en/README.md", "depends only on `NaruServe-api`", "Supported (API 21+)",
                "Without an SDK, it warns and builds the other ten modules", "nine modules"}};
        for (String[] c : cases) {
            String doc = read(c[0]);
            String line = row(c[0], doc, "| `NaruServe-proxy` | `com.snoworca.naruserve:naruserve-proxy` |");
            for (String part : new String[] {c[1], "](proxy.md)"}) {
                assertTrue(c[0] + " proxy row lacks " + part + ": " + line, line.contains(part));
            }
            assertTrue(c[0] + " proxy row ends with " + c[2] + ": " + line, line.endsWith("| " + c[2] + " |"));
            assertEquals(c[0] + " proxy row names no module besides NaruServe-api: " + line,
                    new TreeSet<>(Arrays.asList("NaruServe-api", "NaruServe-proxy")), moduleNames(line));
            assertTrue(c[0] + " counts ten modules built without an Android SDK", doc.contains(c[3]));
            assertFalse(c[0] + " still counts nine modules", doc.contains(c[4]));
        }
    }

    // OPS-DX-011 AC-4: both READMEs name NaruServe-proxy in their module tables with its contents and a link to
    // docs/api/proxy.md, state that 0.11.0 does not contain it and show no Maven Central coordinate for it
    @Test
    public void OPS_DX_011_AC4_readmesNameTheProxyModuleWithoutAPublishedCoordinate() throws IOException {
        String[][] cases = {
            {"README.md", "reverse proxy middleware", "depends only on `NaruServe-api`", "| Not in 0.11.0;",
                "Without an SDK, the ten JAR modules still build.", "nine JAR modules"},
            {"README.ko.md", "리버스 프록시 미들웨어", "`NaruServe-api`에만 의존", "| 0.11.0에는 없음.",
                "SDK가 없어도 열 JAR 모듈을 빌드합니다.", "아홉 JAR 모듈"}};
        for (String[] c : cases) {
            String doc = read(c[0]);
            // the source build counts NaruServe-proxy among the JAR modules built without an Android SDK
            assertTrue(c[0] + " counts ten JAR modules built without an Android SDK", doc.contains(c[4]));
            assertFalse(c[0] + " still counts nine JAR modules", doc.contains(c[5]));
            String line = row(c[0], doc, "| `NaruServe-proxy` |");
            for (String part : new String[] {c[1], c[2], c[3], "](docs/api/proxy.md)"}) {
                assertTrue(c[0] + " proxy row lacks " + part + ": " + line, line.contains(part));
            }
            assertFalse(c[0] + " proxy row shows a coordinate: " + line, line.contains("com.snoworca.naruserve:"));
            assertEquals(c[0] + " proxy row names no module besides NaruServe-api: " + line,
                    new TreeSet<>(Arrays.asList("NaruServe-api", "NaruServe-proxy")), moduleNames(line));
            assertFalse(c[0] + " proxy row gives a version to the artifact: " + line,
                    Pattern.compile("naruserve-proxy:[0-9]").matcher(line).find());
        }
    }

    // SDS-AC-10; OPS-REL-001 AC-2
    @Test
    public void ops_REL_001_AC2_quickStartBlocksEqualTheApiReadmeBlocks() throws IOException {
        String readme = read("README.md");
        String api = apiQuickStart();
        for (String lang : new String[] {"java", "kotlin"}) {
            List<String> apiBlocks = blocks(api, lang);
            List<String> readmeBlocks = blocks(ReleaseNotesDocsTest.section(readme, "## Quick start"), lang);
            assertEquals("one ```" + lang + " block under '5분 시작'", 1, apiBlocks.size());
            assertEquals("one ```" + lang + " block under README.md Quick start", 1, readmeBlocks.size());
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

    // OPS-REL-008 AC-1 AC-2; CON-BUILD-018 AC-1: current instructions supersede the historical engine pin.
    @Test
    public void ops_REL_005_AC1_readmeAndReleaseNotesHoldTheTinyHttpdInstruction() throws IOException {
        for (String path : new String[] {"README.md", "README.ko.md", "docs/release/release-notes.md"}) {
            String doc = read(path);
            assertTrue(path, doc.contains(path.equals("README.ko.md")
                    ? "이 저장소는 TinyHttpd를 게시하지 않습니다" : NOT_PUBLISHED_PHRASE));
            for (String part : new String[] {INCLUDE_BUILD_SNIPPET,
                    "com.snoworca:tinyhttpd-core:3.1.1",
                    "com.snoworca:tinyhttpd-websocket:3.1.1", "-PtinyHttpdFromMaven=true", VERIFIED_COMMIT}) {
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
        assertTrue(run.group(4), run.group(4).startsWith("resolved") && run.group(4).contains("tinyhttpd-core"));
        assertTrue("the consumer depends on NaruServe-tinyhttpd from mavenLocal()",
                section.contains("com.snoworca.naruserve:NaruServe-tinyhttpd:0.1.0-SNAPSHOT")
                        && section.contains("mavenLocal()"));
    }

    // OPS-REL-008 AC-1 AC-2; FR-ENGINE-012: current RAW policy boundary, not an obsolete 3.0 API gap.
    @Test
    public void ops_REL_008_AC1_documentsExplainPublished311RawPolicyBoundary() throws IOException {
        for (String path : new String[] {"README.md", "README.ko.md", "docs/release/release-notes.md"}) {
            String doc = read(path);
            assertTrue(path, doc.contains("3.1.1") && doc.contains("RequestTargetPolicy.RAW")
                    && doc.contains("3.1.0") && doc.contains("Host"));
        }
        List<String> paths = new ArrayList<>(Arrays.asList("README.md", "README.ko.md", "docs/release/release-notes.md"));
        File[] api = new File(root(), "docs/api").listFiles((d, n) -> n.endsWith(".md"));
        assertNotNull(api);
        for (File f : api) {
            paths.add("docs/api/" + f.getName());
        }
        File[] english = new File(root(), "docs/api/en").listFiles((d, n) -> n.endsWith(".md"));
        assertNotNull(english);
        for (File f : english) paths.add("docs/api/en/" + f.getName());
        for (String path : paths) {
            assertFalse(path + " says '" + FORBIDDEN_PHRASE + "'",
                    read(path).toLowerCase(Locale.ROOT).contains(FORBIDDEN_PHRASE.toLowerCase(Locale.ROOT)));
        }
    }

    // OPS-REL-010 AC-2: every installation coordinate of the READMEs and both guide directories is the current version;
    // 0.10.0 stays only as the previous release, never as a coordinate
    @Test
    public void OPS_REL_010_AC2_everyCurrentCoordinateIsTheReleaseVersion() throws IOException {
        List<String> paths = new ArrayList<>(Arrays.asList("README.md", "README.ko.md"));
        for (String dir : new String[] {"docs/api", "docs/api/en"}) {
            File[] pages = new File(root(), dir).listFiles((d, n) -> n.endsWith(".md"));
            assertNotNull(dir, pages);
            for (File page : pages) {
                paths.add(dir + "/" + page.getName());
            }
        }
        Pattern coordinate = Pattern.compile("com\\.snoworca\\.naruserve:[a-z-]+:([0-9][^'\"\\s)`]*)");
        int found = 0;
        for (String path : paths) {
            String text = read(path);
            Matcher m = coordinate.matcher(text);
            while (m.find()) {
                assertEquals(path + " " + m.group(), "0.11.0", m.group(1));
                found++;
            }
            assertFalse(path + " has a Maven version element of the old release", text.contains("<version>0.10.0</version>"));
        }
        assertTrue("coordinates found: " + found, found > 0);
    }
}
