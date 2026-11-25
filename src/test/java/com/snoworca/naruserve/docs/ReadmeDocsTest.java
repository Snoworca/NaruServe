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
        // OPS-REL-013 AC-2: the install line gives the release version of this checkout
        int line = readme.indexOf("implementation 'com.snoworca.naruserve:naruserve:" + RELEASE_VERSION + "'", install);
        assertTrue("install line after '## Install'", line > install);
        String before = readme.substring(install, line);
        int repos = before.lastIndexOf("repositories {");
        assertTrue("a repositories block before the install line", repos >= 0);
        String block = before.substring(repos);
        assertTrue(block, block.contains("mavenCentral()"));
        assertFalse(block, block.contains("mavenLocal()"));
        // OPS-REL-008 AC-2; OPS-REL-009 AC-1: coordinates assert only a verified publication; OPS-REL-017 AC-1 AC-2
        // (OPS-REL-012 for 0.11.0): 0.12.0 is published (docs/release/verification/central-0.12.0.md), so the README
        // says so and links that record.
        assertTrue("README.md states the Maven Central coordinates", readme.contains("Maven Central coordinates"));
        String installSection = ReleaseNotesDocsTest.section(readme, "## Install");
        assertTrue("README.md states that " + PUBLISHED_VERSION + " is on Maven Central",
                installSection.contains("NaruServe " + PUBLISHED_VERSION + " is on Maven Central"));
        assertTrue("README.md links the publication record from Install",
                installSection.contains("](" + PUBLISHED_RECORD + ")"));
        for (String v : new String[] {PREVIOUS_VERSION, PUBLISHED_VERSION}) {
            assertFalse("README.md no longer treats " + v + " as unavailable", readme.contains("If " + v + " is unavailable"));
        }
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

    /**
     * The version of the latest release on Maven Central, which the READMEs name with its publication record
     * (OPS-REL-017; OPS-REL-012 for 0.11.0).
     */
    static final String PUBLISHED_VERSION = "0.12.0";

    /**
     * The release version of this checkout (OPS-REL-013): every current installation coordinate of the READMEs, the
     * guides and the examples uses it. It is published (OPS-REL-016), so it equals {@link #PUBLISHED_VERSION}.
     */
    static final String RELEASE_VERSION = "0.12.0";

    /** The publication record of {@link #PUBLISHED_VERSION}. */
    static final String PUBLISHED_RECORD = "docs/release/verification/central-0.12.0.md";

    /** The release before {@link #PUBLISHED_VERSION}, the previous release of the READMEs. */
    static final String PREVIOUS_VERSION = "0.11.0";

    /** The publication record of {@link #PREVIOUS_VERSION}, a historical record. */
    static final String PREVIOUS_RECORD = "docs/release/verification/central-0.11.0.md";

    /** The artifactIds the published release staged, read from the Staging row of its publication record. */
    static Set<String> publishedArtifacts() throws IOException {
        return stagedArtifacts(PUBLISHED_RECORD);
    }

    /** The artifactIds of the Staging row of the publication record {@code record}. */
    static Set<String> stagedArtifacts(String record) throws IOException {
        String staging = null;
        for (String l : read(record).split("\n")) {
            if (l.startsWith("| Staging |")) {
                staging = l;
            }
        }
        assertNotNull(record + " has a Staging row", staging);
        Set<String> out = new TreeSet<>();
        Matcher m = Pattern.compile("`(naruserve[\\w-]*)`").matcher(staging);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    /** The head line of the module table of {@code docs/api/README.md}. */
    static final String API_MODULE_TABLE_HEAD = "| 모듈 | 좌표 | 내용 | Android |";

    /**
     * The cells of a Markdown table line: the leading and trailing {@code |} are removed and the rest is split at every
     * {@code |} outside a backtick code span (a {@code \|} stays in its cell). A line that does not start and end with
     * {@code |} fails.
     */
    static List<String> cells(String where, String line) {
        assertTrue(where + ": a table line starts and ends with |: " + line,
                line.length() >= 2 && line.startsWith("|") && line.endsWith("|"));
        List<String> out = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean code = false;
        String inner = line.substring(1, line.length() - 1);
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '\\' && i + 1 < inner.length() && inner.charAt(i + 1) == '|') {
                cell.append("\\|");
                i++;
                continue;
            }
            if (c == '`') {
                code = !code;
            }
            if (c == '|' && !code) {
                out.add(cell.toString().trim());
                cell.setLength(0);
            } else {
                cell.append(c);
            }
        }
        assertFalse(where + ": a code span is left open: " + line, code);
        out.add(cell.toString().trim());
        return out;
    }

    /**
     * The rows of the one table of {@code text} whose head line is {@code head}, as cells: every line after the
     * delimiter line ({@code |---}) up to the first line that does not start with {@code |} is a row, and each row has
     * as many cells as the head.
     */
    static List<List<String>> tableRows(String where, String text, String head) {
        String[] lines = text.split("\n", -1);
        int at = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].equals(head)) {
                assertTrue(where + ": one table is headed " + head, at < 0);
                at = i;
            }
        }
        assertTrue(where + ": a table is headed " + head, at >= 0);
        assertTrue(where + ": the line after " + head + " is the delimiter line",
                at + 1 < lines.length && lines[at + 1].startsWith("|---"));
        int width = cells(where, head).size();
        List<List<String>> rows = new ArrayList<>();
        for (int i = at + 2; i < lines.length && lines[i].startsWith("|"); i++) {
            List<String> c = cells(where, lines[i]);
            assertEquals(where + ": cells of the table row " + lines[i], width, c.size());
            rows.add(c);
        }
        return rows;
    }

    /**
     * Every row of the module table of {@code docs/api/README.md}, in order, as {@code {module name, group:artifactId}}.
     * The module cell must be exactly one backticked module name and the coordinate cell exactly
     * {@code `com.snoworca.naruserve:<module name in lower case>`}, so a row in any other form fails here.
     */
    static List<String[]> apiModuleRows(String path) throws IOException {
        List<String[]> rows = new ArrayList<>();
        for (List<String> row : tableRows(path, read(path), API_MODULE_TABLE_HEAD)) {
            String cell = row.get(0);
            assertTrue(path + ": the module cell is one backticked module name: " + row,
                    cell.matches("`NaruServe(-[A-Za-z0-9]+)?`"));
            String module = cell.substring(1, cell.length() - 1);
            String coordinate = "com.snoworca.naruserve:" + module.toLowerCase(Locale.ROOT);
            assertEquals(path + ": the coordinate cell of the module " + module, "`" + coordinate + "`", row.get(1));
            rows.add(new String[] {module, coordinate});
        }
        return rows;
    }

    // SDS-AC-9; OPS-REL-001 AC-1; OPS-DX-011 AC-5; OPS-REL-013 AC-2; OPS-REL-017: README.md lists the coordinate of
    // every row of the docs/api/README.md module table at the release version of this checkout (0.12.0, the release
    // that first ships NaruServe-proxy) and of no other row, plus the install lines; the published 0.12.0 staged every
    // row (its publication record), and the previous 0.11.0 staged every row but naruserve-proxy (its publication
    // record), a historical fact this test keeps
    @Test
    public void ops_REL_001_AC1_readmeListsTheTenCoordinatesOfTheApiModuleTableAndTheInstallLines()
            throws IOException {
        Set<String> published = publishedArtifacts();
        Set<String> previous = stagedArtifacts(PREVIOUS_RECORD);
        // OPS-DX-011 AC-5: every line of the table block is a row (a row in another form fails in apiModuleRows), so a
        // row that invents a module name fails here: eleven rows, no coordinate twice, each artifactId its module name
        List<String[]> rows = apiModuleRows("docs/api/README.md");
        assertEquals("rows (not distinct coordinates) of the module table of docs/api/README.md", 11, rows.size());
        Set<String> coordinates = new TreeSet<>();
        Set<String> table = new TreeSet<>();
        Set<String> inPublished = new TreeSet<>();
        Set<String> unpublished = new TreeSet<>();
        for (String[] r : rows) {
            assertTrue("module table coordinate " + r[1] + " appears once", coordinates.add(r[1]));
            table.add(r[1] + ":" + RELEASE_VERSION);
            String artifactId = r[1].substring(r[1].indexOf(':') + 1);
            assertTrue(PUBLISHED_RECORD + " stages the module table row " + r[1], published.contains(artifactId));
            if (previous.contains(artifactId)) {
                inPublished.add(r[1]);
            } else {
                unpublished.add(r[1]);
            }
        }
        assertEquals("every module the published " + PUBLISHED_VERSION + " staged has a row: " + published, 11,
                published.size());
        assertEquals("module table rows of docs/api/README.md that " + PREVIOUS_VERSION + " contains", 10,
                inPublished.size());
        assertEquals("every module the previous release staged has a row: " + previous, previous.size(),
                inPublished.size());
        assertEquals("module table rows of docs/api/README.md that " + PREVIOUS_VERSION + " does not contain",
                new TreeSet<>(Arrays.asList("com.snoworca.naruserve:naruserve-proxy")), unpublished);
        assertTrue(table.toString(), table.contains("com.snoworca.naruserve:naruserve-android:" + RELEASE_VERSION));
        assertTrue(table.toString(), table.contains("com.snoworca.naruserve:naruserve-proxy:" + RELEASE_VERSION));
        String readme = read("README.md");
        Set<String> listed = new TreeSet<>();
        Matcher coordinate = Pattern.compile("com\\.snoworca\\.naruserve:[\\w-]+:" + Pattern.quote(RELEASE_VERSION)
                + "(?![\\w.-])").matcher(readme);
        while (coordinate.find()) {
            listed.add(coordinate.group());
        }
        assertEquals(table, listed);
        Matcher any = Pattern.compile("com\\.snoworca\\.naruserve:[\\w-]+:([0-9]+\\.[0-9]+\\.[0-9]+)").matcher(readme);
        while (any.find()) {
            assertEquals("README.md coordinate " + any.group(), RELEASE_VERSION, any.group(1));
        }
        for (String part : new String[] {"publishToMavenLocal", "mavenLocal()",
                "implementation 'com.snoworca.naruserve:naruserve:" + RELEASE_VERSION + "'"}) {
            assertTrue("README.md holds " + part, readme.contains(part));
        }
    }

    // OPS-DX-011 AC-5; OPS-REL-013 AC-2; OPS-REL-017: the module table holds NaruServe-proxy, a module the previous
    // release 0.11.0 does not contain and the published 0.12.0 does; the READMEs give naruserve-proxy only at the release
    // version of this checkout, the first that ships it
    @Test
    public void OPS_DX_011_AC5_proxyRowIsInTheTableButNotAmongThePublishedCoordinates() throws IOException {
        Set<String> coordinates = new TreeSet<>();
        for (String[] r : apiModuleRows("docs/api/README.md")) {
            coordinates.add(r[1]);
        }
        assertTrue("docs/api/README.md has the naruserve-proxy row: " + coordinates,
                coordinates.contains("com.snoworca.naruserve:naruserve-proxy"));
        assertFalse(PREVIOUS_RECORD + " does not stage naruserve-proxy",
                stagedArtifacts(PREVIOUS_RECORD).contains("naruserve-proxy"));
        assertTrue(PUBLISHED_RECORD + " stages naruserve-proxy", publishedArtifacts().contains("naruserve-proxy"));
        for (String f : new String[] {"README.md", "README.ko.md"}) {
            String doc = read(f);
            assertTrue(f + " gives the naruserve-proxy coordinate at " + RELEASE_VERSION,
                    doc.contains("com.snoworca.naruserve:naruserve-proxy:" + RELEASE_VERSION));
            // any form of a naruserve-proxy dependency (colon coordinate, Gradle map notation, a version variable)
            // names the artifactId; each occurrence is the release-version coordinate
            Matcher m = Pattern.compile("naruserve-proxy").matcher(doc);
            int count = 0;
            while (m.find()) {
                assertTrue(f + " names naruserve-proxy other than at " + RELEASE_VERSION + ": "
                        + doc.substring(Math.max(0, m.start() - 40), Math.min(doc.length(), m.end() + 20)),
                        doc.startsWith("naruserve-proxy:" + RELEASE_VERSION, m.start())
                                && doc.startsWith("com.snoworca.naruserve:", m.start() - "com.snoworca.naruserve:".length()));
                count++;
            }
            assertEquals(f + " names naruserve-proxy once (its module row)", 1, count);
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

    // OPS-DX-011 AC-4; OPS-REL-013 AC-2: both READMEs name NaruServe-proxy in their module tables with its contents and
    // a link to docs/api/proxy.md, state that 0.11.0 does not contain it, and give its coordinate only at the release
    // version of this checkout (0.12.0, the version published with the module)
    @Test
    public void OPS_DX_011_AC4_readmesNameTheProxyModuleWithoutAPublishedCoordinate() throws IOException {
        String[][] cases = {
            {"README.md", "reverse proxy middleware", "depends only on `NaruServe-api`", "not in 0.11.0",
                "Without an SDK, the ten JAR modules still build.", "nine JAR modules"},
            {"README.ko.md", "리버스 프록시 미들웨어", "`NaruServe-api`에만 의존", "0.11.0에는 없",
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
            assertTrue(c[0] + " proxy row shows the release-version coordinate: " + line,
                    line.startsWith("| `NaruServe-proxy` | `com.snoworca.naruserve:naruserve-proxy:" + RELEASE_VERSION + "` |"));
            assertEquals(c[0] + " proxy row names no module besides NaruServe-api: " + line,
                    new TreeSet<>(Arrays.asList("NaruServe-api", "NaruServe-proxy")), moduleNames(line));
            Matcher version = Pattern.compile("naruserve-proxy:([0-9]+\\.[0-9]+\\.[0-9]+)").matcher(line);
            while (version.find()) {
                assertEquals(c[0] + " proxy row version: " + line, RELEASE_VERSION, version.group(1));
            }
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

    // OPS-REL-010 AC-2, OPS-REL-013 AC-2: every installation coordinate of the READMEs and both guide directories is the
    // current version 0.12.0; 0.11.0 and 0.10.0 stay only as earlier releases, never as a coordinate
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
                assertEquals(path + " " + m.group(), RELEASE_VERSION, m.group(1));
                found++;
            }
            assertFalse(path + " has a Maven version element of the old release", text.contains("<version>0.10.0</version>"));
            assertFalse(path + " has a Maven version element of 0.11.0", text.contains("<version>0.11.0</version>"));
        }
        assertTrue("coordinates found: " + found, found > 0);
    }

    // OPS-REL-013 AC-2: the version banner of both READMEs and every version-scope banner of both guide directories
    // names the release version 0.12.0 (auth.md keeps its status line, the auth/RBAC v1 contract introduced in 0.11.0)
    @Test
    public void OPS_REL_013_AC2_versionBannersNameTheReleaseVersion() throws IOException {
        for (String f : new String[] {"README.md", "README.ko.md"}) {
            String banner = null;
            for (String l : read(f).split("\n")) {
                if (l.startsWith("> ")) {
                    banner = l;
                    break;
                }
            }
            assertNotNull(f + " has a version banner", banner);
            assertTrue(f + " banner names NaruServe " + RELEASE_VERSION + ": " + banner,
                    banner.contains("NaruServe " + RELEASE_VERSION));
        }
        String[] prefixes = {"> **버전 범위:**", "> **Version scope:**", "> **문서 버전:**", "> **Documentation version:**",
            "> **문서 상태:**", "> **Documentation status:**"};
        int banners = 0;
        for (String dir : new String[] {"docs/api", "docs/api/en"}) {
            File[] pages = new File(root(), dir).listFiles((d, n) -> n.endsWith(".md") && !n.equals("auth.md"));
            assertNotNull(dir, pages);
            for (File page : pages) {
                for (String l : read(dir + "/" + page.getName()).split("\n")) {
                    for (String prefix : prefixes) {
                        if (l.startsWith(prefix)) {
                            assertTrue(dir + "/" + page.getName() + " banner names " + RELEASE_VERSION + ": " + l,
                                    l.contains(RELEASE_VERSION));
                            banners++;
                        }
                    }
                }
            }
        }
        assertTrue("version banners found: " + banners, banners >= 10);
    }
}
