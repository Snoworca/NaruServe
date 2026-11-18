package com.snoworca.naruserve.docs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/**
 * The guides of docs/api and docs/api/en (README.md excluded) read the same in both languages and match the
 * published 0.11.0 release (OPS-DX-007).
 */
// @req OPS-DX-007
public class GuideConsistencyDocsTest {

    /** The guide pages of docs/api, README.md excluded, by file name. */
    static List<String> pages() {
        String[] names = new File(ReadmeDocsTest.root(), "docs/api").list((d, n) -> n.endsWith(".md"));
        assertTrue("docs/api lists its guides", names != null);
        List<String> out = new ArrayList<>();
        for (String n : names) {
            if (!n.equals("README.md")) {
                out.add(n);
            }
        }
        java.util.Collections.sort(out);
        return out;
    }

    private static String ko(String page) throws IOException {
        return ReadmeDocsTest.read("docs/api/" + page);
    }

    private static String en(String page) throws IOException {
        return ReadmeDocsTest.read("docs/api/en/" + page);
    }

    /** The prose lines of a page: fenced code blocks dropped, inline code replaced by a placeholder. */
    static List<String> prose(String text) {
        List<String> out = new ArrayList<>();
        boolean fence = false;
        for (String line : text.split("\n", -1)) {
            if (line.startsWith("```")) {
                fence = !fence;
                out.add("");
                continue;
            }
            out.add(fence ? "" : line.replaceAll("`[^`]*`", "``").replaceAll("\\]\\([^)]*\\)", "]()"));
        }
        return out;
    }

    private static String line(String where, String text, String needle) {
        for (String l : text.split("\n")) {
            if (l.contains(needle)) {
                return l;
            }
        }
        throw new AssertionError(where + ": no line holds " + needle);
    }

    private static void has(String where, String text, String... needles) {
        for (String n : needles) {
            assertTrue(where + " lacks: " + n, text.contains(n));
        }
    }

    /** Wording that describes 0.11.0 as an unpublished checkout or points at an availability fallback. */
    static final List<Pattern> STALE = Arrays.asList(
            Pattern.compile("체크아웃"), Pattern.compile("(?i)\\bcheckout\\b"),
            Pattern.compile("Maven 배포 완료를 뜻하지 않습니다"), Pattern.compile("not a Maven publication announcement"),
            Pattern.compile("가용성"), Pattern.compile("(?i)availability"), Pattern.compile("(?i)when that version is unavailable"),
            Pattern.compile("소스/아티팩트"), Pattern.compile("source/artifact"), Pattern.compile("배포 좌표의 공개 여부"));

    // OPS-DX-007 AC-1: no guide describes 0.11.0 as an unpublished target checkout
    @Test
    public void OPS_DX_007_AC1_noGuideCallsTheReleaseAnUnpublishedCheckout() throws IOException {
        List<String> found = new ArrayList<>();
        for (String page : pages()) {
            for (String[] doc : new String[][] {{page, ko(page)}, {"en/" + page, en(page)}}) {
                String[] lines = doc[1].split("\n", -1);
                for (int i = 0; i < lines.length; i++) {
                    for (Pattern p : STALE) {
                        if (p.matcher(lines[i]).find()) {
                            found.add(doc[0] + ":" + (i + 1) + " " + p.pattern());
                        }
                    }
                }
            }
        }
        assertEquals(String.join("\n", found), new ArrayList<String>(), found);
    }

    // OPS-DX-007 AC-1: the LAN warning of app.md points at the auth guide instead of hand-made middleware
    @Test
    public void OPS_DX_007_AC1_lanWarningLinksTheAuthGuide() throws IOException {
        has("app.md", line("app.md", ko("app.md"), "LAN에 열면"), "(auth.md)");
        assertFalse("app.md still says to add authentication by hand", ko("app.md").contains("인증은 미들웨어로 직접 붙이세요"));
        has("en/app.md", line("en/app.md", en("app.md"), "Non-loopback binding logs a warning"), "(auth.md)");
        assertFalse("en/app.md still says to add authentication through middleware",
                en("app.md").contains("Add authentication through [middleware]"));
    }

    /** A number glued to the Korean or Latin word before it, such as {@code preflight204} or {@code 한도1 MiB}. */
    static final Pattern GLUED = Pattern.compile("[가-힣][0-9]|(?<![A-Za-z0-9_./-])[A-Za-z]{3,}[0-9]{2,}(?![A-Za-z0-9])");

    /** Latin words that legitimately end in digits (algorithm and standard names). */
    static final Pattern GLUED_OK = Pattern.compile("(?i)(sha|hs|rs|es|ps|utf|base|iso|ipv|http|rfc|slf|pbkdf|argon|api|jdk|log|aes|gcm|crc|java)[0-9]+");

    // OPS-DX-007 AC-2: Korean prose keeps a space between a word and the number after it
    @Test
    public void OPS_DX_007_AC2_koreanProseHasNoGluedNumbers() throws IOException {
        List<String> found = new ArrayList<>();
        for (String page : pages()) {
            List<String> lines = prose(ko(page));
            for (int i = 0; i < lines.size(); i++) {
                Matcher m = GLUED.matcher(lines.get(i));
                while (m.find()) {
                    if (!GLUED_OK.matcher(m.group()).matches()) {
                        found.add(page + ":" + (i + 1) + " " + m.group());
                    }
                }
            }
        }
        assertEquals(String.join("\n", found), new ArrayList<String>(), found);
    }

    // OPS-DX-011 AC-2; OPS-DX-007 AC-3; OPS-DX-010 AC-12: every guide pair starts with the same navigation line shape,
    // for the 18 pairs of 0.11.0 and proxy.md
    @Test
    public void OPS_DX_007_AC3_everyGuideHasTheSameNavigationLine() throws IOException {
        List<String> pages = pages();
        assertEquals("19 guide pairs besides README.md (the 18 of 0.11.0 and proxy.md)", 19, pages.size());
        assertTrue("proxy.md is a guide pair", pages.contains("proxy.md"));
        for (String page : pages) {
            assertEquals(page, "[English](en/" + page + ") | [문서 목록](README.md)", ko(page).split("\n")[2]);
            assertEquals("en/" + page, "[한국어](../" + page + ") | [API index](README.md)", en(page).split("\n")[2]);
        }
    }

    /** The guide pages of 0.11.0, README.md excluded; the pages a later release adds are listed by their own test. */
    static final List<String> PAGES_0_11_0 = Arrays.asList("app.md", "auth.md", "cookie.md", "defaults.md",
            "express-migration.md", "json.md", "kotlin.md", "lifecycle.md", "logging.md", "middleware.md",
            "multipart.md", "request.md", "response.md", "routing.md", "servlet.md", "sse.md", "static.md",
            "websocket.md");

    // OPS-DX-011 AC-2: the guide pairs are the 18 of 0.11.0 and proxy.md, each page has its English counterpart,
    // and every pair starts with the same navigation line shape
    @Test
    public void OPS_DX_011_AC2_theNineteenGuidePairsAreThoseOf0110AndProxy() throws IOException {
        List<String> expected = new ArrayList<>(PAGES_0_11_0);
        expected.add("proxy.md");
        java.util.Collections.sort(expected);
        assertEquals("guide pages of docs/api", expected, pages());
        String[] english = new File(ReadmeDocsTest.root(), "docs/api/en").list((d, n) -> n.endsWith(".md") && !n.equals("README.md"));
        assertTrue("docs/api/en lists its guides", english != null);
        List<String> en = new ArrayList<>(Arrays.asList(english));
        java.util.Collections.sort(en);
        assertEquals("guide pages of docs/api/en", expected, en);
        for (String page : expected) {
            assertEquals(page, "[English](en/" + page + ") | [문서 목록](README.md)", ko(page).split("\n")[2]);
            assertEquals("en/" + page, "[한국어](../" + page + ") | [API index](README.md)", en(page).split("\n")[2]);
        }
    }

    /** The page entries of the top-level {@code nav} list of a MkDocs configuration, in order (titles dropped). */
    static List<String> nav(String config) throws IOException {
        String text = ReadmeDocsTest.read(config);
        int start = text.indexOf("\nnav:\n");
        assertTrue(config + " has a top-level nav", start >= 0);
        List<String> out = new ArrayList<>();
        for (String l : text.substring(start + "\nnav:\n".length()).split("\n")) {
            if (!l.startsWith("  - ")) {
                break;
            }
            String entry = l.substring(4).trim();
            int colon = entry.indexOf(": ");
            out.add(colon >= 0 ? entry.substring(colon + 2).trim() : entry);
        }
        return out;
    }

    // OPS-DX-011 AC-1: mkdocs.yml and mkdocs.en.yml list every guide page, proxy.md included, in their nav, before the
    // external Javadoc entry, so the strict site builds have no page left out of the navigation
    @Test
    public void OPS_DX_011_AC1_bothSiteNavigationsListEveryGuidePageIncludingProxy() throws IOException {
        for (String config : new String[] {"mkdocs.yml", "mkdocs.en.yml"}) {
            List<String> entries = nav(config);
            assertEquals(config + " starts its nav with README.md", "README.md", entries.get(0));
            assertTrue(config + " ends its nav with the Javadoc link", entries.get(entries.size() - 1).startsWith("https://javadoc.io/"));
            for (String page : pages()) {
                assertTrue(config + " nav lists " + page, entries.contains(page));
            }
            assertTrue(config + " nav lists proxy.md", entries.contains("proxy.md"));
            assertEquals(config + " lists each guide once", new java.util.TreeSet<>(entries).size(), entries.size());
        }
    }

    // OPS-DX-007 AC-4: the defaults size table lists the header-count row in both languages
    @Test
    public void OPS_DX_007_AC4_defaultsSizeTableHasTheHeaderCountRow() throws IOException {
        has("defaults.md", line("defaults.md", ko("defaults.md"), "| 헤더 개수 |"), "| 100 |", "10000", "`431`", "`maxHeaderCount`");
        has("en/defaults.md", line("en/defaults.md", en("defaults.md"), "| Header count |"), "| 100 |", "10000", "`431`",
                "`maxHeaderCount`");
    }

    // OPS-DX-007 AC-4: express-migration maps the Express auth packages to Auth and app.use(auth)
    @Test
    public void OPS_DX_007_AC4_expressMigrationMapsAuthPackages() throws IOException {
        has("express-migration.md", line("express-migration.md", ko("express-migration.md"), "| `passport`"),
                "`Auth.builder(secret)", "`app.use(auth)`", "(auth.md)");
        has("en/express-migration.md", line("en/express-migration.md", en("express-migration.md"), "| `passport`"),
                "`Auth.builder(secret)", "`app.use(auth)`", "(auth.md)");
    }

    // OPS-DX-007 AC-4: response.md lists redirect and sendStatus with their Express counterparts
    @Test
    public void OPS_DX_007_AC4_responseTableListsRedirectAndSendStatus() throws IOException {
        for (String[] doc : new String[][] {{"response.md", ko("response.md")}, {"en/response.md", en("response.md")}}) {
            has(doc[0], line(doc[0], doc[1], "| `redirect([status,] url)` |"), "`res.redirect(");
            has(doc[0], line(doc[0], doc[1], "| `sendStatus(code)` |"), "`res.sendStatus(code)`");
        }
    }

    // OPS-DX-007 AC-4: the Korean websocket.md states the TinyHttpd 426 headers in Korean
    @Test
    public void OPS_DX_007_AC4_websocket426HeadersInEachLanguage() throws IOException {
        has("websocket.md", ko("websocket.md"), WaveSevenDocsTest.TINYHTTPD_426_PHRASE);
        assertFalse("websocket.md holds an English sentence", ko("websocket.md").contains("over the TinyHttpd adapter"));
        has("en/websocket.md", en("websocket.md"), WaveSevenDocsTest.TINYHTTPD_426_PHRASE_EN);
    }

    // OPS-DX-007 AC-4: servlet.md names the 451-thread design total without an internal decision id
    @Test
    public void OPS_DX_007_AC4_servletThreadTotalHasNoDecisionId() throws IOException {
        for (String page : pages()) {
            assertFalse(page + " names an internal decision id", Pattern.compile("\\bD-[0-9]{3}\\b").matcher(ko(page)).find());
            assertFalse("en/" + page + " names an internal decision id",
                    Pattern.compile("\\bD-[0-9]{3}\\b").matcher(en(page)).find());
        }
        has("servlet.md", ko("servlet.md"), "설계값 451");
        has("en/servlet.md", en("servlet.md"), "design 451");
    }

    // OPS-DX-007 AC-6: auth.md states the transport lifetimes and error messages as the code does
    @Test
    public void OPS_DX_007_AC6_authGuideMatchesTheCodeAndTheRelease() throws IOException {
        String k = ko("auth.md");
        String e = en("auth.md");
        has("auth.md", k, "수명이 고정됩니다(access 5분, refresh 24시간)", "토큰 수명을 고정합니다",
                "메시지는 4xx `HttpException`(`AuthRejection` 포함)의 것만 보이고",
                "Express 대응: `app.set('trust proxy', 1)` 뒤에 `req.secure`");
        has("en/auth.md", e, "get fixed lifetimes (access 5 minutes, refresh 24 hours)", "it fixes the token lifetimes",
                "Only a 4xx `HttpException` (`AuthRejection` included) shows its message",
                "Express counterpart: `app.set('trust proxy', 1)` plus a middleware that refuses when `req.secure`");
        for (String stale : new String[] {"수명이 짧아집니다", "강제로 줄입니다", "던진 예외의 메시지가 들어가지 않습니다", "첫 공개",
                "다음 버전", "앞으로 들어갈"}) {
            assertFalse("auth.md still says " + stale, k.contains(stale));
        }
        for (String stale : new String[] {"live shorter", "forces short token lifetimes", "the message of the thrown exception",
                "first public release", "before the first release", "every file store will use"}) {
            assertFalse("en/auth.md still says " + stale, e.contains(stale));
        }
    }
}
