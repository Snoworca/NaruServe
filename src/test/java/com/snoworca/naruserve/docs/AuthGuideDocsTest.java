package com.snoworca.naruserve.docs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/** The auth guide is task-oriented, explains its terms and carries a test recipe (OPS-AUTH-007). */
public class AuthGuideDocsTest {

    private static final String KO = "docs/api/auth.md";
    private static final String EN = "docs/api/en/auth.md";

    private static String read(String path) throws IOException {
        return ReadmeDocsTest.read(path);
    }

    private static boolean en(String file) {
        return file.contains("/en/");
    }

    private static void has(String file, String text, String needle) {
        assertTrue(file + " lacks: " + needle, text.contains(needle));
    }

    /** The text with every fenced block blanked out, so code lines are never read as headings or prose. */
    private static String prose(String md) {
        StringBuilder out = new StringBuilder();
        boolean fenced = false;
        for (String line : md.split("\n", -1)) {
            if (line.trim().startsWith("```")) {
                fenced = !fenced;
                out.append('\n');
            } else {
                out.append(fenced ? "" : line).append('\n');
            }
        }
        return out.toString();
    }

    /** The GitHub anchor of a heading text: lower case, punctuation dropped, spaces to hyphens, letters kept. */
    static String anchor(String heading) {
        String lower = heading.trim().toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (c == ' ') {
                out.append('-');
            } else if (Character.isLetterOrDigit(c) || c == '-' || c == '_') {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** The anchors of every heading outside fenced blocks. */
    static Set<String> anchors(String md) {
        Set<String> out = new HashSet<>();
        for (String line : prose(md).split("\n")) {
            Matcher m = Pattern.compile("^#{1,6} (.+)$").matcher(line);
            if (m.matches()) {
                out.add(anchor(m.group(1)));
            }
        }
        return out;
    }

    /** The data rows (header and separator skipped) of the first table in {@code text}. */
    static List<String> tableRows(String text) {
        List<String> rows = new ArrayList<>();
        boolean inTable = false;
        int seen = 0;
        for (String line : text.split("\n")) {
            if (line.startsWith("|")) {
                inTable = true;
                if (++seen > 2) {
                    rows.add(line);
                }
            } else if (inTable) {
                break;
            }
        }
        return rows;
    }

    /** The paragraph (blank-line separated) of {@code text} holding {@code needle}, or null. */
    private static String paragraphWith(String text, String needle) {
        for (String p : text.split("\n\n")) {
            if (p.contains(needle)) {
                return p;
            }
        }
        return null;
    }

    // OPS-AUTH-007 AC-1: right after the status note both guides have a task guide table of at least 10 rows, one for
    // each common task, whose links all resolve to headings of the same file
    @Test
    public void OPS_AUTH_007_AC1_taskGuideLinksResolve() throws IOException {
        for (String f : new String[] {KO, EN}) {
            String s = read(f);
            String note = en(f) ? "> **Status:**" : "> **문서 상태:**";
            int start = s.indexOf(note);
            assertTrue(f + " has its status note", start >= 0);
            String afterNote = s.substring(s.indexOf('\n', start) + 1);
            String lead = en(f) ? "**Task guide:**" : "**할 일별 안내:**";
            assertTrue(f + ": the task guide follows the status note", afterNote.trim().startsWith(lead));
            String intro = afterNote.substring(0, afterNote.indexOf("\n## "));
            List<String> rows = tableRows(intro);
            assertTrue(f + " task guide rows: " + rows.size(), rows.size() >= 10);
            Set<String> anchors = anchors(s);
            Pattern link = Pattern.compile("\\]\\(#([^)]+)\\)");
            StringBuilder all = new StringBuilder();
            for (String row : rows) {
                Matcher m = link.matcher(row);
                assertTrue(f + ": a task row without a link: " + row, m.find());
                m.reset();
                while (m.find()) {
                    assertTrue(f + ": the link #" + m.group(1) + " names no heading", anchors.contains(m.group(1)));
                }
                all.append(row.split("\\|")[1].toLowerCase(Locale.ROOT)).append('\n');
            }
            String[] tasks = en(f)
                    ? new String[] {"log in", "protect a route", "roles and scopes", "create users from code",
                            "react to user changes", "groups", "own database", "prod files and keys", "lan or a device",
                            "tests"}
                    : new String[] {"로그인", "경로 보호", "역할과 스코프", "코드에서 사용자 만들기", "사용자 변경에 반응",
                            "그룹", "자체 데이터베이스", "prod 파일과 키", "lan이나 기기", "테스트"};
            for (String task : tasks) {
                assertTrue(f + " task guide lacks the task: " + task, all.toString().contains(task));
            }
        }
        // the negative control: the anchor rules of GitHub, and a link to no heading is not resolved
        assertEquals("계정-엔드포인트-authroutes", anchor("계정 엔드포인트: auth.routes()"));
        assertEquals("the-first-account-user", anchor("The first account: .user(...)"));
        assertFalse(anchors("## A b\n```\n## Not a heading\n```\n").contains("not-a-heading"));
    }

    /** The method names declared by AuthEvents.java, in source order. */
    private static List<String> authEventsMethods() throws IOException {
        String source = read("NaruServe-api/src/main/java/com/snoworca/naruserve/utils/auth/AuthEvents.java");
        List<String> names = new ArrayList<>();
        Matcher m = Pattern.compile("default void (\\w+)\\(").matcher(source);
        while (m.find()) {
            names.add(m.group(1));
        }
        return names;
    }

    // OPS-AUTH-007 AC-2: the AuthEvents section lists every AuthEvents method with its Java signature, when it fires
    // and whether it can refuse, and a Java and a Kotlin example keep an app table in sync on create, update, delete
    // and login
    @Test
    public void OPS_AUTH_007_AC2_eventsTableListsEveryMethod() throws IOException {
        List<String> methods = authEventsMethods();
        assertEquals("AuthEvents methods: " + methods, 12, methods.size());
        for (String f : new String[] {KO, EN}) {
            String section = ReleaseNotesDocsTest.section(read(f), "## AuthEvents");
            int tableAt = section.indexOf(en(f) ? "| Method and Java signature |" : "| 메서드와 Java 시그니처 |");
            assertTrue(f + " has the events table", tableAt >= 0);
            List<String> rows = tableRows(section.substring(tableAt));
            assertEquals(f + " one row per AuthEvents method", methods.size(), rows.size());
            for (String name : methods) {
                String row = null;
                for (String r : rows) {
                    if (r.startsWith("| `" + name + "(")) {
                        row = r;
                    }
                }
                assertTrue(f + ": no events row for " + name, row != null);
                String[] cells = row.split("\\|");
                assertEquals(f + ": three cells in " + row, 4, cells.length);
                assertTrue(f + ": the signature of " + name + ": " + row, cells[1].contains("AuthEventContext ctx)"));
                assertFalse(f + ": no 'when' for " + name, cells[2].trim().isEmpty());
                boolean refuses = name.startsWith("before");
                String yes = en(f) ? "Yes" : "예";
                String no = en(f) ? "No" : "아니요";
                assertTrue(f + ": the refuse cell of " + name + ": " + row,
                        cells[3].trim().startsWith(refuses ? yes : no));
            }
            for (String lang : new String[] {"java", "kotlin"}) {
                boolean synced = false;
                for (String block : ReadmeDocsTest.blocks(section, lang)) {
                    synced |= block.contains("onUserCreated") && block.contains("onUserUpdated")
                            && block.contains("onUserDeleted") && block.contains("onLogin")
                            && block.contains(".events(");
                }
                assertTrue(f + ": no " + lang + " block keeps an app table in sync", synced);
            }
        }
    }

    // OPS-AUTH-007 AC-3: the group section says every direct store write (membership, parent edge, role) reaches a
    // signed-in user when the cached principal expires, and shows .userCacheTtl(0) for tests
    // FR-IAM-016 AC-6: both pages say a grantRole made on the store after start reaches cached principals on expiry
    @Test
    public void OPS_AUTH_007_AC3_groupWritesReachSignedInUsers() throws IOException {
        for (String f : new String[] {KO, EN}) {
            String section = ReleaseNotesDocsTest.section(read(f),
                    en(f) ? "## Groups: GroupStore and GroupLimits" : "## 그룹: GroupStore와 GroupLimits");
            String sentence = en(f) ? "when that user's cached principal expires" : "그 사용자의 캐시된 주체가 만료될 때";
            String p = paragraphWith(section, sentence);
            assertTrue(f + " group section lacks: " + sentence, p != null);
            for (String write : new String[] {"addMember", "removeMember", "addParent", "removeParent", "grantRole",
                    "revokeRole"}) {
                has(f + " cache paragraph", p, "`" + write + "`");
            }
            has(f + " cache paragraph", p, "userCacheTtl");
            has(f + " cache paragraph", p, ".userCacheTtl(0)");
        }
    }

    // OPS-AUTH-007 AC-4: no stale "not in yet" sentence and no internal requirement code; a glossary of at least ten
    // terms; the admin labels A1-A6 and G1-G9 are explained where first used
    @Test
    public void OPS_AUTH_007_AC4_noStaleTextAndAGlossary() throws IOException {
        Pattern forbidden = Pattern.compile(
                "\\bC4\\b|\\bR33\\b|\\bR46\\b|\\bR8\\b|00 R5|is not in yet|nothing queues a rehash yet|아직 들어가지");
        for (String f : new String[] {KO, EN}) {
            String s = read(f);
            Matcher m = forbidden.matcher(s);
            assertFalse(f + " still holds: " + (m.find() ? m.group() : ""), forbidden.matcher(s).find());
            String glossary = ReleaseNotesDocsTest.section(s, en(f) ? "## Glossary" : "## 용어");
            List<String> rows = tableRows(glossary);
            assertTrue(f + " glossary rows: " + rows.size(), rows.size() >= 10);
            StringBuilder terms = new StringBuilder();
            for (String row : rows) {
                String term = row.split("\\|")[1].trim();
                assertTrue(f + ": a glossary term is not bold: " + row, term.startsWith("**") && term.endsWith("**"));
                terms.append(term.toLowerCase(Locale.ROOT)).append('\n');
            }
            for (String t : new String[] {"principal", "scope", "role", "grant", "family", "ver", "kid", "doctor",
                    "dev", "prod", "dag"}) {
                assertTrue(f + " glossary lacks the term " + t, terms.toString().contains(t));
            }
            Matcher label = Pattern.compile("\\b[AG][1-9]\\b").matcher(prose(s));
            assertTrue(f + " uses the admin labels", label.find());
            String first = prose(s).substring(0, label.end());
            String firstParagraph = first.substring(first.lastIndexOf("\n\n") + 2);
            String rest = prose(s).substring(label.end());
            firstParagraph += rest.substring(0, Math.max(0, rest.indexOf("\n\n")));
            has(f + " first admin-label paragraph", firstParagraph, "A1–A6");
            has(f + " first admin-label paragraph", firstParagraph, "G1–G9");
        }
        assertTrue("the negative control", forbidden.matcher("judged by R33").find());
        assertFalse("R330 is not R33", forbidden.matcher("R330").find());
    }

    // OPS-AUTH-007 AC-5: a JUnit test recipe starts the app on port 0, logs in over HTTP, calls a protected route and
    // shuts down, with .userCacheTtl(0), in Java and Kotlin
    @Test
    public void OPS_AUTH_007_AC5_testRecipe() throws IOException {
        for (String f : new String[] {KO, EN}) {
            String kit = ReleaseNotesDocsTest.section(read(f),
                    en(f) ? "## Test kit: AuthTesting" : "## 테스트 킷: AuthTesting");
            String heading = en(f) ? "### HTTP test recipe" : "### HTTP 테스트 레시피";
            int at = kit.indexOf("\n" + heading + "\n");
            assertTrue(f + " has " + heading, at >= 0);
            String recipe = kit.substring(at);
            for (String lang : new String[] {"java", "kotlin"}) {
                List<String> blocks = ReadmeDocsTest.blocks(recipe, lang);
                assertEquals(f + " " + lang + " recipe blocks", 1, blocks.size());
                String b = blocks.get(0);
                for (String part : new String[] {"import org.junit.Test", "@Test", "AuthMode.DEV", ".userCacheTtl(0)",
                        ".user(\"alice\", \"", "app.use(auth)", "listen(0)", "HttpURLConnection", "/auth/login",
                        "Bearer ", "shutdown("}) {
                    has(f + " " + lang + " recipe", b, part);
                }
            }
        }
    }

    // OPS-AUTH-007 AC-6: the secure-transport section quotes the Core LAN listen WARN, says it appears whenever the
    // bind address is not loopback, also with auth installed, and what to check
    @Test
    public void OPS_AUTH_007_AC6_coreLanWarnExplained() throws IOException {
        for (String f : new String[] {KO, EN}) {
            String section = ReleaseNotesDocsTest.section(read(f),
                    en(f) ? "## The secure-transport rule" : "## 보안 연결 규칙");
            String warn = "`listening on <address>, reachable beyond this device: the app must add its own "
                    + "authentication`";
            String p = paragraphWith(section, warn);
            assertTrue(f + " secure-transport section lacks the Core WARN " + warn, p != null);
            for (String part : en(f)
                    ? new String[] {"not loopback", "even when auth is installed", "app.use(auth)",
                            "insecure_transport"}
                    : new String[] {"루프백이 아니면", "auth를 설치해도", "app.use(auth)", "insecure_transport"}) {
                has(f + " Core WARN paragraph", p, part);
            }
        }
        // the quoted text is the one Core logs
        String core = read("NaruServe-api/src/main/java/com/snoworca/naruserve/internal/DefaultRestApp.java");
        has("DefaultRestApp.java", core, "\", reachable beyond this device:\"");
        has("DefaultRestApp.java", core, "\" the app must add its own authentication\"");
    }

    // OPS-AUTH-007 AC-7: each examples README shows the runAuth command and curl lines, and each example build
    // registers runAuth for the AuthExample main class
    @Test
    public void OPS_AUTH_007_AC7_examplesReadmesAndBuildsRunAuth() throws IOException {
        for (String f : new String[] {"examples/README.md", "examples/README.ko.md"}) {
            String s = read(f);
            has(f, s, "./gradlew -p examples/java -PtinyHttpdFromMaven=true runAuth");
            has(f, s, "./gradlew -p examples/kotlin -PtinyHttpdFromMaven=true runAuth");
            has(f, s, "curl -X POST http://127.0.0.1:3000/auth/login");
            has(f, s, "Authorization: Bearer");
        }
        has("examples/java/build.gradle", read("examples/java/build.gradle"), "mainClass = 'example.AuthExample'");
        has("examples/kotlin/build.gradle", read("examples/kotlin/build.gradle"), "mainClass = 'example.AuthExample'");
        for (String f : new String[] {"examples/java/build.gradle", "examples/kotlin/build.gradle"}) {
            String s = read(f);
            has(f, s, "tasks.register('runAuth', JavaExec)");
            has(f, s, "classpath = sourceSets.main.runtimeClasspath");
        }
    }
}
