package com.snoworca.naruserve.docs;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.Test;

/** The third junior round of the guides (OPS-AUTH-011). */
public class AuthGuideRound3DocsTest {

    private static final String[] GUIDES = {"docs/api/auth.md", "docs/api/en/auth.md"};

    private static String read(String path) throws IOException {
        return ReadmeDocsTest.read(path);
    }

    private static boolean en(String f) {
        return f.contains("/en/");
    }

    private static void has(String where, String text, String needle) {
        assertTrue(where + " lacks: " + needle, text.contains(needle));
    }

    private static String section(String f, String doc, String enHeading, String koHeading) {
        return ReleaseNotesDocsTest.section(doc, en(f) ? enHeading : koHeading);
    }

    /** Every java and kotlin block of {@code text}. */
    private static List<String> code(String text) {
        List<String> out = new ArrayList<>(ReadmeDocsTest.blocks(text, "java"));
        out.addAll(ReadmeDocsTest.blocks(text, "kotlin"));
        return out;
    }

    /** The blocks of {@code text} holding {@code marker}; at least one. */
    private static List<String> holding(String f, String text, String marker) {
        List<String> out = new ArrayList<>();
        for (String b : code(text)) {
            if (b.contains(marker)) {
                out.add(b);
            }
        }
        assertFalse(f + ": no code block holds " + marker, out.isEmpty());
        return out;
    }

    // OPS-AUTH-011 AC-1: every guide block that opens a FileUserStore or a KeySources.fromFile builds in PROD
    @Test
    public void OPS_AUTH_011_AC1_fileStoreSnippetsPassAFileKey() throws IOException {
        for (String f : GUIDES) {
            String doc = read(f);
            for (String marker : new String[] {"FileUserStore(", "KeySources.fromFile("}) {
                for (String b : holding(f, doc, marker)) {
                    assertTrue(f + ": a block with " + marker + " has no AuthFileKey or DEV mode:\n" + b,
                            b.contains("AuthFileKey") || b.contains("AuthMode.DEV"));
                }
            }
        }
    }

    // OPS-AUTH-011 AC-2: under the status line, the JWT_SECRET assumption and how to try a fragment
    @Test
    public void OPS_AUTH_011_AC2_examplesAssumeJwtSecret() throws IOException {
        for (String f : GUIDES) {
            String doc = read(f);
            int status = doc.indexOf(en(f) ? "> **Status:**" : "> **문서 상태:**");
            int tasks = doc.indexOf(en(f) ? "**Task guide:**" : "**할 일별 안내:**");
            assertTrue(f, status >= 0 && tasks > status);
            String head = doc.substring(status, tasks);
            for (String needle : new String[] {"JWT_SECRET", "openssl rand -base64 32", "AuthMode.DEV", "PROD"}) {
                has(f + " head", head, needle);
            }
        }
    }

    // OPS-AUTH-011 AC-3: the middleware guides name their own check checkAuth and point to the auth module
    @Test
    public void OPS_AUTH_011_AC3_middlewareGuideHasNoBareAuth() throws IOException {
        for (String f : new String[] {"docs/api/middleware.md", "docs/api/en/middleware.md"}) {
            String doc = read(f);
            for (String b : code(doc)) {
                assertFalse(f + ": bare auth middleware in\n" + b, Pattern.compile("[(,]\\s*auth\\)").matcher(b).find());
            }
            has(f, doc, "checkAuth");
            has(f, doc, "(auth.md");
        }
    }

    // OPS-AUTH-011 AC-4: the defaults guides' authentication row links to the auth module
    @Test
    public void OPS_AUTH_011_AC4_defaultsPointToTheAuthModule() throws IOException {
        for (String f : new String[] {"docs/api/defaults.md", "docs/api/en/defaults.md"}) {
            String row = null;
            for (String l : read(f).split("\n")) {
                if (l.startsWith(en(f) ? "- **Authentication and authorization:**" : "- **인증과 권한**")) {
                    row = l;
                }
            }
            assertTrue(f + ": no authentication row", row != null);
            has(f + " authentication row", row, "(auth.md");
        }
    }

    // OPS-AUTH-011 AC-5: the quick start says what logout does to the access token
    @Test
    public void OPS_AUTH_011_AC5_quickStartStatesLogoutSemantics() throws IOException {
        for (String f : GUIDES) {
            String quick = section(f, read(f), "## Quick start", "## 빠른 시작");
            has(f + " quick start", quick, "?all=true");
            has(f + " quick start", quick, en(f) ? "15 minutes" : "15분");
            has(f + " quick start", quick, en(f) ? "logout" : "로그아웃");
        }
    }

    // OPS-AUTH-011 AC-6 (amended with OPS-AUTH-012 and OPS-AUTH-033): the error section names the JSON 405 of a wrong
    // method with a token and the one answer that is still not JSON
    @Test
    public void OPS_AUTH_011_AC6_errorSectionNamesTheNonJsonAnswers() throws IOException {
        for (String f : GUIDES) {
            String errors = section(f, read(f), "## Error responses and AuthResponses", "## 오류 응답과 AuthResponses");
            for (String needle : new String[] {"GET /auth/logout", "405 `METHOD_NOT_ALLOWED`", "`Allow`"}) {
                has(f + " error section", errors, needle);
            }
            has(f + " error section", errors, en(f) ? "stack trace" : "스택 트레이스");
        }
    }

    // OPS-AUTH-011 AC-7: the quick start says .permit is GET/HEAD and exact, and shows another method
    @Test
    public void OPS_AUTH_011_AC7_quickStartExplainsPermit() throws IOException {
        for (String f : GUIDES) {
            String quick = section(f, read(f), "## Quick start", "## 빠른 시작");
            has(f + " quick start", quick, ".permit(Method.POST, ");
            has(f + " quick start", quick, en(f) ? "exactly" : "정확히");
        }
    }

    // OPS-AUTH-011 AC-8: the group example adds a user it created, not a literal id
    @Test
    public void OPS_AUTH_011_AC8_groupExampleUsesACreatedUser() throws IOException {
        for (String f : GUIDES) {
            String groups = section(f, read(f), "## Groups: GroupStore and GroupLimits", "## 그룹: GroupStore와 GroupLimits");
            for (String b : holding(f, groups, "addMember(")) {
                assertFalse(f + ": literal user id in\n" + b,
                        Pattern.compile("addMember\\(\"[^\"]+\",\\s*\"[^\"]+\"\\)").matcher(b).find());
            }
        }
    }

    // OPS-AUTH-011 AC-9: the ConsoleLogger examples show its import
    @Test
    public void OPS_AUTH_011_AC9_consoleLoggerImportIsShown() throws IOException {
        for (String f : GUIDES) {
            String audit = section(f, read(f), "## Audit events: AuditEvent, AuditSink, AuditSinks",
                    "## 감사 이벤트: AuditEvent, AuditSink, AuditSinks");
            for (String b : holding(f, audit, "new ConsoleLogger(")) {
                has(f + " audit example", b, "import com.snoworca.naruserve.ConsoleLogger");
            }
        }
        for (String f : new String[] {"docs/api/logging.md", "docs/api/en/logging.md"}) {
            for (String b : holding(f, read(f), "ConsoleLogger.format(")) {
                has(f + " ConsoleLogger example", b, "import com.snoworca.naruserve.ConsoleLogger");
            }
        }
    }

    // OPS-AUTH-011 AC-10: the token-issue example finds a user it created
    @Test
    public void OPS_AUTH_011_AC10_tokenExampleHasNoUndefinedId() throws IOException {
        for (String f : GUIDES) {
            for (String b : holding(f, read(f), "tokens().issue(")) {
                assertFalse(f + ": undefined id in\n" + b, b.contains("findById(id)"));
            }
        }
    }

    // OPS-AUTH-011 AC-11: the HTTP test recipe says HttpURLConnection cannot PATCH and how to send one
    @Test
    public void OPS_AUTH_011_AC11_testRecipeNamesThePatchLimit() throws IOException {
        for (String f : GUIDES) {
            String recipe = section(f, read(f), "### HTTP test recipe", "### HTTP 테스트 레시피");
            has(f + " recipe", recipe, "PATCH");
            has(f + " recipe", recipe, "java.net.http.HttpClient");
        }
    }

    // OPS-AUTH-011 AC-12: role names and scopes are case-sensitive
    @Test
    public void OPS_AUTH_011_AC12_rolesAreCaseSensitive() throws IOException {
        for (String f : GUIDES) {
            String roles = section(f, read(f), "## Roles and scopes: RolePolicy, RoleStore, Scopes",
                    "## 역할과 스코프: RolePolicy, RoleStore, Scopes");
            has(f + " roles", roles, en(f) ? "case-sensitive" : "대소문자를 구분");
        }
    }
}
