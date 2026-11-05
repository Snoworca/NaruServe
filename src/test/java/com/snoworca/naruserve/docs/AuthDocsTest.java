package com.snoworca.naruserve.docs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;

/** The auth guide docs/api/auth.md and its English mirror, found by fixed sentences. */
public class AuthDocsTest {

    private static String read(String path) throws IOException {
        String root = System.getProperty("naruserve.rootDir");
        assertNotNull("naruserve.rootDir system property", root);
        File f = new File(root, path);
        assertTrue(f.getPath(), f.isFile());
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    private static void contains(String file, String text, String... needles) {
        for (String n : needles) {
            assertTrue(file + " lacks: " + n, text.contains(n));
        }
    }

    static final String KO_HASH = "`passwordHash`는 `transient`라서 기본 modifier 설정의 Gson(`new GsonJsonCodec()`)만 "
            + "이 값을 빼고 씁니다. `excludeFieldsWithModifiers(STATIC)`로 만든 Gson이나 다른 `JsonCodec`을 쓰면 앱이 "
            + "`passwordHash`를 직접 지워야 합니다.";
    static final String EN_HASH = "`passwordHash` is `transient`, so only a Gson with the default modifier settings "
            + "(`new GsonJsonCodec()`) leaves it out. With a Gson built with `excludeFieldsWithModifiers(STATIC)` or "
            + "another `JsonCodec`, the app must remove `passwordHash` itself.";

    // DR-IAM-001 component: the AuthUser class Javadoc and both docs state the default-Gson-only limit of the
    // transient hash; the requirement's acceptance also names the auth.userStore() Javadoc, which comes with Auth
    @Test
    public void transientHashLimitIsDocumented() throws IOException {
        contains("auth.md", read("docs/api/auth.md"), KO_HASH);
        contains("en/auth.md", read("docs/api/en/auth.md"), EN_HASH);
        String source = read("NaruServe-api/src/main/java/com/snoworca/naruserve/utils/auth/user/AuthUser.java");
        int end = source.indexOf("public final class AuthUser");
        int start = source.lastIndexOf("/**", end);
        assertTrue(start >= 0 && end > start);
        String javadoc = source.substring(start, end).replaceAll("\\s*\\n\\s*\\*\\s*", " ");
        contains("AuthUser.java", javadoc, "only a Gson with the default modifier settings",
                "excludeFieldsWithModifiers(STATIC)", "the app must remove passwordHash itself");
    }

    // REL-IAM-004 AC-6: both docs state that API 21 has no directory fsync and ver may regress after power loss
    @Test
    public void REL_IAM_004_AC6_directoryFsyncLimitIsDocumented() throws IOException {
        contains("auth.md", read("docs/api/auth.md"),
                "API 21에는 디렉터리 fsync가 없어서 이름 바꾸기의 내구성은 파일 시스템에 달려 있고, 전원이 꺼지면 "
                        + "`ver`(토큰 버전)가 한 단계 되돌아갈 수 있습니다.");
        contains("en/auth.md", read("docs/api/en/auth.md"),
                "API 21 has no directory fsync, so rename durability depends on the file system and `ver` (the token "
                        + "version) may regress after power loss.");
    }

    // IR-AUTH-003 component: the decided error-code answers are recorded with their status and producing answer
    @Test
    public void decidedErrorCodesAreListed() throws IOException {
        for (String page : new String[] {"docs/api/auth.md", "docs/api/en/auth.md"}) {
            contains(page, read(page), "`invalid_credentials` | 401", "`duplicate_user` | 409", "`write_conflict` | 409",
                    "`group_cycle` | 409", "`group_too_large` | 409", "`invalid_setup_token` | 401",
                    "`setup_not_local` | 403", "`insufficient_scope` | 403", "`security_write_conflict` | 503",
                    "`SERVICE_UNAVAILABLE` | 503");
        }
        contains("auth.md", read("docs/api/auth.md"), "**현재 비밀번호가 틀린 경우도 이 코드입니다**",
                "`auth.requireRole`의 거부, 거짓인 `auth.check` 규칙, 자기 자신에 대한 부여 규칙 거부도 이 코드입니다");
        contains("en/auth.md", read("docs/api/en/auth.md"), "**A wrong current password uses this code too**",
                "The refusal of `auth.requireRole`, a false `auth.check` rule and the self-rule refusal use this code too");
    }

    // DR-IAM-001 AC-7: the AuthUser and auth.userStore() Javadoc and both docs state the default-Gson-only limit
    @Test
    public void DR_IAM_001_AC7_transientHashLimitInJavadocAndDocs() throws IOException {
        contains("auth.md", read("docs/api/auth.md"), KO_HASH);
        contains("en/auth.md", read("docs/api/en/auth.md"), EN_HASH);
        for (String[] where : new String[][] {
            {"NaruServe-api/src/main/java/com/snoworca/naruserve/utils/auth/user/AuthUser.java", "public final class AuthUser"},
            {"NaruServe-api/src/main/java/com/snoworca/naruserve/utils/auth/Auth.java", "public UserStore userStore()"}}) {
            String source = read(where[0]);
            int end = source.indexOf(where[1]);
            int start = source.lastIndexOf("/**", end);
            assertTrue(where[1], start >= 0 && end > start);
            String javadoc = source.substring(start, end).replaceAll("\\s*\\n\\s*\\*\\s*", " ");
            contains(where[0], javadoc, "Gson with the default modifier settings",
                    "excludeFieldsWithModifiers(STATIC)", "the app must remove passwordHash itself");
        }
    }

    // CON-AUTH-005 AC-1: the auth page and its English mirror exist and both indexes link them
    @Test
    public void CON_AUTH_005_AC1_pagesAndIndexLinks() throws IOException {
        contains("auth.md", read("docs/api/auth.md"), "[English](en/auth.md)");
        contains("en/auth.md", read("docs/api/en/auth.md"), "[한국어](../auth.md)");
        contains("README.md", read("docs/api/README.md"), "](auth.md)");
        contains("en/README.md", read("docs/api/en/README.md"), "](auth.md)");
    }

    /** Every public top-level and public nested type declared under utils (internal excluded), as Outer.Inner. */
    static java.util.List<String> publicUtilsTypes() throws IOException {
        java.util.List<String> out = new java.util.ArrayList<>();
        File root = new File(System.getProperty("naruserve.rootDir"),
                "NaruServe-api/src/main/java/com/snoworca/naruserve/utils");
        collect(root, out);
        assertTrue(out.toString(), out.size() > 30);
        return out;
    }

    private static void collect(File dir, java.util.List<String> out) throws IOException {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                if (!f.getName().equals("internal")) {
                    collect(f, out);
                }
            } else if (f.getName().endsWith(".java") && !f.getName().equals("package-info.java")) {
                String src = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                String outer = f.getName().substring(0, f.getName().length() - 5);
                java.util.regex.Matcher top = java.util.regex.Pattern.compile(
                        "(?m)^public (final |abstract )*(class|interface|enum) " + outer + "\\b").matcher(src);
                if (!top.find()) {
                    continue;
                }
                out.add(outer);
                java.util.regex.Matcher nested = java.util.regex.Pattern.compile(
                        "(?m)^    public (static )?(final |abstract )*(static )?(class|interface|enum) (\\w+)").matcher(src);
                while (nested.find()) {
                    out.add(outer + "." + nested.group(5));
                }
            }
        }
    }

    // FR-IAM-003 AC-7: both auth pages warn that a login attribute such as email must not be self-editable through
    // allowedAttributes unless the app normalizes it and enforces its uniqueness
    @Test
    public void FR_IAM_003_AC7_loginAttributeWarningIsDocumented() throws IOException {
        contains("auth.md", read("docs/api/auth.md"), "`email` 같은 속성으로 로그인시키는 앱은 그 속성을 직접 정규화하고 "
                + "유일성을 보장하지 않는 한 `allowedAttributes`에 넣으면 안 됩니다. auth는 둘 다 하지 않습니다.");
        contains("en/auth.md", read("docs/api/en/auth.md"), "An app that logs users in by an attribute such as `email` "
                + "must not list that attribute in `allowedAttributes` unless the app itself normalizes it and enforces "
                + "its uniqueness, because auth applies neither.");
    }

    // REL-IAM-001 AC-6: both auth pages state that two admins writing the same user concurrently is last-writer-wins
    @Test
    public void REL_IAM_001_AC6_lastWriterWinsIsDocumented() throws IOException {
        contains("auth.md", read("docs/api/auth.md"), "두 관리자가 같은 사용자를 동시에 쓰면 마지막에 쓴 쪽이 "
                + "이깁니다(last-writer-wins)");
        contains("en/auth.md", read("docs/api/en/auth.md"), "Two admins writing the same user at the same time is "
                + "last-writer-wins");
    }

    // SEC-FLOW-009 AC-8: both auth pages state that a Path or Domain change through .cookies() needs the app to
    // expire the old cookies during refreshTtl
    @Test
    public void SEC_FLOW_009_AC8_cookieAttributeChangeIsDocumented() throws IOException {
        contains("auth.md", read("docs/api/auth.md"), "`.cookies()`로 Path나 Domain을 바꾸면 앱이 `refreshTtl` 동안 옛 "
                + "Path와 Domain에 `Max-Age=0`을 보내야 합니다. auth는 옛 쿠키를 알아내거나 지울 수 없기 때문입니다.");
        contains("en/auth.md", read("docs/api/en/auth.md"), "Changing Path or Domain through `.cookies()` requires the "
                + "app to send `Max-Age=0` for the old Path and Domain during `refreshTtl`, because auth cannot detect "
                + "or clear the old cookies.");
    }

    // CON-AUTH-005 AC-2: every public type of utils and utils.auth.* is named on both auth pages
    @Test
    public void CON_AUTH_005_AC2_everyPublicTypeIsNamed() throws IOException {
        String ko = read("docs/api/auth.md");
        String en = read("docs/api/en/auth.md");
        java.util.List<String> types = publicUtilsTypes();
        java.util.List<String> missing = unnamedTypes(types, ko, en);
        assertTrue(missing.toString(), missing.isEmpty());
        // the type list holds the real public names (outer and nested) and no internal type
        assertTrue(types.toString(), types.contains("Auth") && types.contains("Auth.Builder")
                && types.contains("AuthUser") && types.contains("Lockout.After"));
        assertFalse(types.toString(), types.contains("MiniJson") || types.contains("FailureFloor"));
        // the negative control: a type absent from the pages is reported by the same check, in both languages
        assertFalse(ko.contains("`NoSuchAuthType`"));
        java.util.List<String> control = new java.util.ArrayList<>(types);
        control.add("NoSuchAuthType");
        assertEquals(java.util.Arrays.asList("ko NoSuchAuthType", "en NoSuchAuthType"), unnamedTypes(control, ko, en));
    }

    /** The {@code types} neither page names as {@code `T`}, {@code `T.} or {@code `T(}, as "ko T" / "en T". */
    private static java.util.List<String> unnamedTypes(java.util.List<String> types, String ko, String en) {
        java.util.List<String> missing = new java.util.ArrayList<>();
        for (String type : types) {
            if (!ko.contains("`" + type + "`") && !ko.contains("`" + type + ".") && !ko.contains("`" + type + "(")) {
                missing.add("ko " + type);
            }
            if (!en.contains("`" + type + "`") && !en.contains("`" + type + ".") && !en.contains("`" + type + "(")) {
                missing.add("en " + type);
            }
        }
        return missing;
    }

    // SEC-FLOW-001 AC-8: the mandatory proxy step, the header names and both residual-risk statements, in both languages
    @Test
    public void SEC_FLOW_001_AC8_proxyStepAndResidualRisks() throws IOException {
        contains("auth.md", read("docs/api/auth.md"),
                "**리버스 프록시 설정은 필수 배포 단계입니다.**", "`X-Forwarded-For`와 `X-Forwarded-Proto`",
                "proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;",
                "proxy_set_header X-Forwarded-Proto $scheme;",
                "Core는 `Forwarded` 헤더를 읽지 않습니다. 그래서 `Forwarded`만 보내는 프록시의 요청은 로컬이 아니게 되지만"
                        + "(`req.isSecure()`는 여전히 거짓) 모든 클라이언트가 프록시 주소를 IP로 갖습니다.",
                "같은 호스트의 프록시가 이 헤더를 하나도 붙이지 않으면 모든 요청이 로컬로 보여, 평문 외부 요청도 보안 연결 "
                        + "검사를 통과하고 모든 클라이언트가 프록시 주소를 IP로 공유해 로그인 IP 한도가 모든 사용자에게 한 "
                        + "키로 걸립니다");
        contains("en/auth.md", read("docs/api/en/auth.md"),
                "**The reverse-proxy configuration is a mandatory deployment step.**",
                "`X-Forwarded-For` and `X-Forwarded-Proto`",
                "proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;",
                "proxy_set_header X-Forwarded-Proto $scheme;",
                "Core does not read the `Forwarded` header. A proxy that sends only `Forwarded` therefore makes its "
                        + "requests non-local, but `req.isSecure()` stays false and every client has the proxy's address "
                        + "as its IP.",
                "a same-host proxy that adds none of these headers makes every request look local, so plain-text "
                        + "external requests pass the secure-transport check and every client shares the proxy's address "
                        + "as its IP, so the login IP limit applies to all users as one key");
    }

    // SEC-AUTH-002 component: fromEnv is documented and the withheld note is gone
    @Test
    public void fromEnvIsDocumented() throws IOException {
        String ko = read("docs/api/auth.md");
        String en = read("docs/api/en/auth.md");
        contains("auth.md", ko, "`AuthMode.fromEnv()`는 환경 변수 `NARU_ENV`가 `dev`");
        contains("en/auth.md", en, "`AuthMode.fromEnv()` is `DEV` when the environment variable `NARU_ENV` is `dev`");
        assertFalse(ko.contains("아직 없습니다. \"NaruServe-api의 어떤 클래스도"));
        assertFalse(en.contains("is not there yet: its conflict"));
    }

    /** The `## ` sections of a page after its introduction, keyed by heading, in page order. */
    private static java.util.Map<String, String> sections(String text) {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        String[] parts = text.split("\n## ", -1);
        for (int i = 1; i < parts.length; i++) {
            int nl = parts[i].indexOf('\n');
            out.put(nl < 0 ? parts[i] : parts[i].substring(0, nl), parts[i]);
        }
        return out;
    }

    /** Korean sections exempt from the API-entry check: their heading names no public API. Heading -> reason. */
    static final java.util.Map<String, String> KO_PROSE_SECTIONS = new java.util.LinkedHashMap<>();
    /** The English mirror of {@link #KO_PROSE_SECTIONS}. */
    static final java.util.Map<String, String> EN_PROSE_SECTIONS = new java.util.LinkedHashMap<>();

    static {
        KO_PROSE_SECTIONS.put("보안 연결 규칙", "the PROD connection rule and the proxy deployment step, no API of its own");
        KO_PROSE_SECTIONS.put("시작 점검(doctor)", "the list of start checks, which have no API");
        KO_PROSE_SECTIONS.put("저장 파일의 내구성", "the internal AtomicFiles write protocol, no public API");
        KO_PROSE_SECTIONS.put("관리 쓰기의 권한 부여 규칙", "the grant rule applied by the admin endpoints, no API");
        KO_PROSE_SECTIONS.put("API 키 사용 시각", "background lastUsedAt behaviour of ApiKeyStore, documented with it");
        EN_PROSE_SECTIONS.put("The secure-transport rule", "the PROD connection rule and the proxy deployment step, no API of its own");
        EN_PROSE_SECTIONS.put("Startup checks (doctor)", "the list of start checks, which have no API");
        EN_PROSE_SECTIONS.put("Durability of the store files", "the internal AtomicFiles write protocol, no public API");
        EN_PROSE_SECTIONS.put("The grant rule of admin writes", "the grant rule applied by the admin endpoints, no API");
        EN_PROSE_SECTIONS.put("API key usage time", "background lastUsedAt behaviour of ApiKeyStore, documented with it");
    }

    private static java.util.List<String> apiEntryGaps(String page, String text, java.util.Map<String, String> exempt,
            String... expressPhrases) {
        java.util.List<String> gaps = new java.util.ArrayList<>();
        java.util.Map<String, String> secs = sections(text);
        for (String heading : exempt.keySet()) {
            if (!secs.containsKey(heading)) {
                gaps.add(page + ": stale exemption " + heading);
            } else if (heading.contains("`")) {
                gaps.add(page + ": exempt heading names an API " + heading);
            }
        }
        for (java.util.Map.Entry<String, String> s : secs.entrySet()) {
            if (exempt.containsKey(s.getKey())) {
                continue;
            }
            boolean express = false;
            for (String phrase : expressPhrases) {
                express |= s.getValue().contains(phrase);
            }
            if (!express) {
                gaps.add(page + " \"" + s.getKey() + "\": no Express mapping line");
            }
            if (!s.getValue().contains("```java\n")) {
                gaps.add(page + " \"" + s.getKey() + "\": no java block");
            }
            if (!s.getValue().contains("```kotlin\n")) {
                gaps.add(page + " \"" + s.getKey() + "\": no kotlin block");
            }
        }
        return gaps;
    }

    // CON-AUTH-005 AC-3: every API entry (each `## ` section outside the small prose list) of both auth pages has an
    // Express mapping line and both a Java and a Kotlin example block
    @Test
    public void CON_AUTH_005_AC3_everyApiEntryHasExpressJavaAndKotlin() throws IOException {
        String ko = read("docs/api/auth.md");
        String en = read("docs/api/en/auth.md");
        assertTrue(sections(ko).size() > 40);
        org.junit.Assert.assertEquals("both pages have the same sections", sections(ko).size(), sections(en).size());
        org.junit.Assert.assertEquals(KO_PROSE_SECTIONS.size(), EN_PROSE_SECTIONS.size());
        java.util.List<String> gaps = apiEntryGaps("auth.md", ko, KO_PROSE_SECTIONS, "Express 대응");
        gaps.addAll(apiEntryGaps("en/auth.md", en, EN_PROSE_SECTIONS, "Express equivalent", "Express counterpart"));
        assertTrue(String.join("\n", gaps), gaps.isEmpty());
        // the negative control: a section without the three parts is reported
        assertFalse(apiEntryGaps("t.md", "# t\n\n## `Foo`\n\ntext\n", new java.util.HashMap<>(), "Express 대응").isEmpty());
        // the AC's two named mappings
        contains("auth.md", ko, "`auth.require(scope...)`", "`express-jwt-authz(['posts:write'])`",
                "Express 대응: 마지막에 두는 오류 핸들러 `(err, req, res, next) => res.status(...).json(...)`");
        contains("en/auth.md", en, "`auth.require(scope...)`", "`express-jwt-authz(['posts:write'])`",
                "Express equivalent: the error handler placed last, `(err, req, res, next) => res.status(...).json(...)`");
    }

    private static void collectJava(File dir, java.util.List<File> out) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                collectJava(f, out);
            } else if (f.getName().endsWith(".java")) {
                out.add(f);
            }
        }
    }

    /** Lines of {@code text} holding an undecided-name marker, as {@code file:line: text}. */
    static java.util.List<String> undecidedMarks(String file, String text) {
        java.util.List<String> hits = new java.util.ArrayList<>();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String lower = lines[i].toLowerCase(java.util.Locale.ROOT);
            if (lines[i].contains("(미확정)") || lines[i].contains("미확정") || lower.contains("name not final")
                    || lower.contains("not final")) {
                hits.add(file + ":" + (i + 1) + ": " + lines[i].trim());
            }
        }
        return hits;
    }

    // CON-AUTH-005 AC-5: no "(미확정)", "미확정", "name not final" or "not final" on the auth pages or in the
    // utils.auth Javadoc and sources; every proposed name of 00 was replaced by the implemented name
    @Test
    public void CON_AUTH_005_AC5_noUndecidedNameMarkers() throws IOException {
        java.util.List<String> hits = new java.util.ArrayList<>();
        for (String page : new String[] {"docs/api/auth.md", "docs/api/en/auth.md"}) {
            hits.addAll(undecidedMarks(page, read(page)));
        }
        java.util.List<File> sources = new java.util.ArrayList<>();
        collectJava(new File(System.getProperty("naruserve.rootDir"),
                "NaruServe-api/src/main/java/com/snoworca/naruserve/utils/auth"), sources);
        assertTrue("utils.auth sources found", sources.size() > 50);
        for (File f : sources) {
            hits.addAll(undecidedMarks(f.getName(),
                    new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)));
        }
        assertTrue(String.join("\n", hits), hits.isEmpty());
        // the negative control: each marker form is found
        assertFalse(undecidedMarks("t", "이름 (미확정)").isEmpty());
        assertFalse(undecidedMarks("t", "the Name Not Final yet").isEmpty());
    }

    // CON-AUTH-005 AC-6: both pages state the install order, the DEV relaxation table, the proxy settings with the
    // nginx lines, the partial detection of undeclared multi-instance use (R35), the create-only-when-missing rule of
    // .user (R41) and the minimal principal of .statelessAccess(true) (R36)
    @Test
    public void CON_AUTH_005_AC6_operationalStatementsInBothLanguages() throws IOException {
        contains("auth.md", read("docs/api/auth.md"),
                "설치 순서는 **`cors` → `auth.install(app)` → 라우트 → 맨 끝 `app.onError(auth.errors())`**입니다.",
                "| DEV가 완화하는 것(전부) | PROD |",
                "| 비밀키가 없으면 임시 키(재시작하면 모든 토큰 무효, WARN) | `build()`가 `AuthConfigException` |",
                "| 보안 연결 검사(평문·비로컬 요청도 처리, 로컬이 아닌 첫 요청에서 WARN 한 번) | 403 `insecure_transport` |",
                "| 짧거나 예제인 키, 해시 비용 실패를 WARN으로 | `build()` 거부 / doctor FAIL |",
                "프록시가 `X-Forwarded-For`와 `X-Forwarded-Proto`를 붙이고, 앱은 `app.trustProxyHops(1)` 또는 "
                        + "`app.trustProxyCidrs(\"10.0.0.0/8\")`로 프록시를 신뢰합니다.",
                "proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;",
                "proxy_set_header X-Forwarded-Proto $scheme;",
                "선언하지 않은 다중 노드 운영은 일부만 탐지됩니다. 탐지 수단은 둘입니다. `SINGLE_INSTANCE`에서 프록시 헤더가 붙은 첫 요청에 남기는 위의 WARN"
                        + " 한 번, 그리고 이 노드의 인메모리 refresh 저장소에 기록이 없는 refresh 토큰이 올 때 401 `invalid_refresh_token`과 함께 남"
                        + "기는 WARN 한 번(재시작 전에 발급되었거나 다른 노드가 발급한 토큰, 토큰 값은 남기지 않음)입니다. 완전한 탐지는 불가능합니다.",
                "그 이름의 계정이 **없을 때만** 만들고",
                "**`.user`는 이미 있는 계정의 비밀번호를 절대 바꾸지 않습니다.**",
                "| `.statelessAccess(true)` | 가드 없는 라우트는 store를 읽지 않은 최소 주체(`getId()`만, `getUsername()`은 "
                        + "빈 문자열)를 봅니다. 앱은 `getId()`만 씁니다 | — |");
        contains("en/auth.md", read("docs/api/en/auth.md"),
                "The install order is **`cors` → `auth.install(app)` → routes → `app.onError(auth.errors())` last**.",
                "| What DEV relaxes (the whole list) | PROD |",
                "| A missing secret becomes a temporary key (a restart invalidates all tokens; WARN) | `build()` throws "
                        + "`AuthConfigException` |",
                "| The secure-transport check (plain-text non-local requests are served; one WARN on the first non-local "
                        + "request) | 403 `insecure_transport` |",
                "| Short or example keys and hash-cost failures become WARNs | `build()` refuses / doctor FAIL |",
                "The proxy adds `X-Forwarded-For` and `X-Forwarded-Proto`, and the app trusts it with "
                        + "`app.trustProxyHops(1)` or `app.trustProxyCidrs(\"10.0.0.0/8\")`.",
                "proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;",
                "proxy_set_header X-Forwarded-Proto $scheme;",
                "Undeclared multi-instance deployment is detected only partly, by two signals: the WARN abo"
                        + "ve on the first request carrying a proxy header under `SINGLE_INSTANCE`, and one WARN, wit"
                        + "h the 401 `invalid_refresh_token`, when a refresh token arrives whose record this node's i"
                        + "n-memory refresh store does not hold (issued before a restart or by another node; the toke"
                        + "n is not logged). Full detection is impossible.",
                "the account is created **only when no account of that name exists**",
                "**`.user` never changes the password of an existing account**",
                "| `.statelessAccess(true)` | Unguarded routes see a minimal principal without a store read (`getId()` "
                        + "only; `getUsername()` is empty). Apps use only `getId()` there | — |");
    }

    // FR-AUTH-010 AC-6: both auth pages show app.use(auth) as the way to attach auth with install(app) as its equivalent,
    // and both app pages document Installable under use(mw)
    @Test
    public void FR_AUTH_010_AC6_useAuthIsTheDocumentedWayToAttach() throws IOException {
        contains("auth.md", read("docs/api/auth.md"),
                "app.use(auth);                                        // auth.install(app)과 같음",
                "**`app.use(auth)`는 `auth.install(app)`과 같습니다.**");
        contains("en/auth.md", read("docs/api/en/auth.md"),
                "app.use(auth);                                        // same as auth.install(app)",
                "**`app.use(auth)` is the same as `auth.install(app)`.**");
        contains("app.md", read("docs/api/app.md"),
                "| `use(middleware)` | 모든 요청에 실행. 미들웨어가 `Installable`(예: auth)이면",
                "`Installable`은 메서드 하나 `void install(RestApp app)`의 인터페이스입니다.");
        contains("en/app.md", read("docs/api/en/app.md"),
                "| `use(middleware)` | Every request. A middleware that is an `Installable` (such as auth)",
                "`Installable` is an interface with one method, `void install(RestApp app)`.");
    }

    // FR-AUTH-003 AC-6 (docs half): the profile table of both pages lists the policy defaults of BASELINE, STANDARD and
    // STRICT, token lifetimes and the new-password minimum included, and names STANDARD as the default profile
    @Test
    public void FR_AUTH_003_AC6_profileTableListsEachProfilesDefaults() throws IOException {
        contains("auth.md", read("docs/api/auth.md"),
                "| AuthProfile | 바꾸는 정책 기본값 |",
                "| `BASELINE` | 토큰 수명 access 15분 / refresh 14일. 새 비밀번호 최소 8자(최대 128자) |",
                "| `STANDARD` | 토큰 수명 access 15분 / refresh 1일. 새 비밀번호 최소 8자(최대 128자). `.profile(...)`을 "
                        + "부르지 않을 때의 프로필 |",
                "| `STRICT` | 토큰 수명 access 5분 / refresh 12시간. 새 비밀번호 최소 15자(최대 128자). doctor가 hop 모드 "
                        + "프록시 신뢰를 WARN합니다 |");
        contains("en/auth.md", read("docs/api/en/auth.md"),
                "| AuthProfile | Policy defaults it sets |",
                "| `BASELINE` | Token lifetimes: access 15 minutes / refresh 14 days. New passwords: at least 8 characters "
                        + "(at most 128) |",
                "| `STANDARD` | Token lifetimes: access 15 minutes / refresh 1 day. New passwords: at least 8 characters "
                        + "(at most 128). The profile used when `.profile(...)` is not called |",
                "| `STRICT` | Token lifetimes: access 5 minutes / refresh 12 hours. New passwords: at least 15 characters "
                        + "(at most 128). The doctor warns about hop-mode proxy trust |");
    }
}
