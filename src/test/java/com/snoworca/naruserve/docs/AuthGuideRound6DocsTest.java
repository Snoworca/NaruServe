package com.snoworca.naruserve.docs;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.regex.Pattern;
import org.junit.Test;

/** The sixth junior round of the guides (OPS-AUTH-017). */
public class AuthGuideRound6DocsTest {

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

    private static String java(String f, String doc, String marker) {
        for (String b : ReadmeDocsTest.blocks(doc, "java")) {
            if (b.contains(marker)) {
                return b;
            }
        }
        throw new AssertionError(f + ": no java block holds " + marker);
    }

    // OPS-AUTH-017 AC-1: the access-token leeway
    @Test
    public void OPS_AUTH_017_AC1_accessTokenLeewayIsStated() throws IOException {
        for (String f : GUIDES) {
            String doc = read(f);
            has(f, doc, "Jwt.DEFAULT_LEEWAY_MILLIS");
            has(f, doc, en(f) ? "60-second clock leeway" : "60초의 시계 오차 허용");
            has(f, doc, en(f) ? "not configurable on `Auth`" : "`Auth`에서 바꿀 수 없음");
            has(f, doc, en(f) ? "past `accessTtl` plus 60 seconds" : "`accessTtl`에 60초를 더한 시각 뒤로");
        }
    }

    // OPS-AUTH-017 AC-2: an empty array reads as an empty object
    @Test
    public void OPS_AUTH_017_AC2_emptyArrayIsExplained() throws IOException {
        for (String f : GUIDES) {
            String errors = ReleaseNotesDocsTest.section(read(f),
                    en(f) ? "## Error responses and AuthResponses" : "## 오류 응답과 AuthResponses");
            has(f, errors, "`[]`");
            has(f, errors, "Gson");
            has(f, errors, en(f) ? "read as an empty object by the Gson codec" : "Gson 코덱이 빈 객체로 읽으므로");
            has(f, errors, en(f) ? "missing-field answer" : "필드 누락 응답");
        }
    }

    // OPS-AUTH-017 AC-3: the DEV list includes the .user password policy, and so does the quick-start comment
    @Test
    public void OPS_AUTH_017_AC3_devListIsComplete() throws IOException {
        for (String f : GUIDES) {
            String doc = read(f);
            has(f, doc, en(f) ? "| A `.user(...)` password that breaks the password policy" : "| 비밀번호 정책을 어긴 `.user(...)` 비밀번호");
            has(f, doc, en(f) ? "becomes a WARN and the account is created | `build()` throws `AuthConfigException` |"
                    : "WARN으로 하고 계정을 만듦 | `build()`가 `AuthConfigException` |");
            for (String l : doc.split("\n")) {
                if (l.contains(".user(\"alice\", \"alice-password-1\", \"editor\")") && l.contains("//")) {
                    assertTrue(f + ": " + l, l.contains("PROD") && l.contains("DEV"));
                }
            }
            assertFalse(f, doc.contains(en(f) ? "Relaxes exactly three things" : "정해진 세 가지만"));
        }
    }

    // SEC-AUTH-002 AC-6: the AuthMode Javadoc and both guides state the one closed list of eight DEV relaxations
    @Test
    public void SEC_AUTH_002_AC6_javadocAndGuidesStateTheSameDevList() throws IOException {
        String source = read("NaruServe-api/src/main/java/com/snoworca/naruserve/utils/auth/AuthMode.java");
        String javadoc = source.substring(0, source.indexOf("public enum AuthMode")).replaceAll("\\s*\\n\\s*\\*\\s*", " ");
        assertFalse(javadoc, javadoc.contains("exactly three"));
        for (String item : new String[] {"exactly eight things", "a temporary key for a missing secret",
            "the secure-transport check", "the hash-cost and key-strength failures", "a {@code .user(...)} password that breaks",
            "a password-less admin {@code .user(...)}", "a file store without a file key", "legacy {@code trustProxy(true)}",
            "no final auth error layer", "Authorization is never relaxed"}) {
            has("AuthMode", javadoc, item);
        }
        String[][] rows = {
            {"비밀키가 없으면 임시 키", "A missing secret becomes a temporary key"},
            {"보안 연결 검사(", "The secure-transport check ("},
            {"짧거나 예제인 키, 해시 비용 실패를 WARN으로", "Short or example keys and hash-cost failures become WARNs"},
            {"비밀번호 정책을 어긴 `.user(...)` 비밀번호", "A `.user(...)` password that breaks the password policy"},
            {"비밀번호 없는 관리자 `.user(...)`", "A password-less admin `.user(...)`"},
            {"파일 키 없는 파일 저장소", "A file store without a file key"},
            {"옛 `trustProxy(true)`", "Legacy `trustProxy(true)`"},
            {"마지막 오류 층이 없음(앞선 층이", "No final error layer (an app exception no earlier layer"}};
        for (String f : GUIDES) {
            String doc = read(f);
            int at = doc.indexOf(en(f) ? "| What DEV relaxes (the whole list) | PROD |" : "| DEV가 완화하는 것(전부) | PROD |");
            assertTrue(f, at >= 0);
            String[] lines = doc.substring(at).split("\n");
            int body = 0;
            while (lines[2 + body].startsWith("|")) {
                has(f, lines[2 + body], rows[body][en(f) ? 1 : 0]);
                // the Korean rows say "층" for a layer, never "레이어"
                assertFalse(f + ": mixed layer term in " + lines[2 + body], !en(f) && lines[2 + body].contains("레이어"));
                body++;
            }
            assertTrue(f + " has " + body + " DEV rows", body == rows.length);
            String row = null;
            for (String l : doc.split("\n")) {
                if (l.startsWith("| `AuthMode.DEV` |")) {
                    row = l;
                }
            }
            has(f, row, en(f) ? "Relaxes exactly eight things" : "정해진 여덟 가지만");
            assertFalse(f, doc.contains(en(f) ? "exactly four things" : "정해진 네 가지만"));
        }
    }

    // OPS-AUTH-017 AC-4: the Kotlin test-kit fragment is a class
    @Test
    public void OPS_AUTH_017_AC4_kotlinTestKitIsAClass() throws IOException {
        for (String f : GUIDES) {
            String block = null;
            for (String b : ReadmeDocsTest.blocks(read(f), "kotlin")) {
                if (b.contains("MyUserStore(ds)")) {
                    block = b;
                }
            }
            assertTrue(f, block != null && Pattern.compile("(?m)^class \\w+ \\{").matcher(block).find());
        }
    }

    // OPS-AUTH-017 AC-5: how an auth.users() refusal reaches the client
    @Test
    public void OPS_AUTH_017_AC5_usersRefusalIsExplained() throws IOException {
        for (String f : GUIDES) {
            String p = null;
            for (String l : read(f).split("\n")) {
                if (l.startsWith(en(f) ? "`auth.users()` returns `AuthUsers`" : "`auth.users()`는 `UserStore`가 아니라")) {
                    p = l;
                }
            }
            assertTrue(f, p != null);
            for (String needle : new String[] {"auth.errors()", "BAD_REQUEST", "catch"}) {
                has(f + " auth.users() paragraph", p, needle);
            }
        }
    }

    // OPS-AUTH-017 AC-6: where the never-installed ERROR goes
    @Test
    public void OPS_AUTH_017_AC6_neverInstalledLogIsNamed() throws IOException {
        for (String f : GUIDES) {
            has(f, read(f), en(f) ? "not to `app.logger()`" : "`app.logger()`가 아니라");
            has(f, read(f), en(f) ? "goes to the auth logger (`.logger(...)`, the console logger by default)"
                    : "auth 로거(`.logger(...)`, 기본은 콘솔 로거)로 갑니다");
        }
    }

    // OPS-AUTH-017 AC-7: the recipe names the login limit in tests
    @Test
    public void OPS_AUTH_017_AC7_recipeNamesTheLoginLimit() throws IOException {
        for (String f : GUIDES) {
            String recipe = ReleaseNotesDocsTest.section(read(f), en(f) ? "### HTTP test recipe" : "### HTTP 테스트 레시피");
            has(f, recipe, ".loginFailureLimits(");
            has(f, recipe, en(f) ? "Five failed logins of one name from one IP within 15 minutes answer 429"
                    : "한 이름이 한 IP에서 15분 안에 로그인에 다섯 번 실패하면");
            has(f, recipe, en(f) ? "a fresh app per test" : "테스트마다 새 앱");
            has(f, recipe, "429");
        }
    }

    // OPS-AUTH-017 AC-8: the fragments that show imports show all of them
    @Test
    public void OPS_AUTH_017_AC8_importListsAreComplete() throws IOException {
        String[][] cases = {
            {"JwtKeys keys = JwtKeys.builder()", "com.snoworca.naruserve.utils.auth.Auth;",
                "com.snoworca.naruserve.utils.auth.AuthFileKey;", "com.snoworca.naruserve.utils.auth.KeySources;",
                "com.snoworca.naruserve.utils.auth.jwt.JwtKeys;", "java.io.File;"},
            {"AuditSinks.logger(new ConsoleLogger())", "com.snoworca.naruserve.utils.auth.Auth;",
                "com.snoworca.naruserve.utils.auth.audit.AuditSinks;"},
            {"new SecureRandom().nextBytes(raw)", "java.util.Base64;", "java.io.File;",
                "com.snoworca.naruserve.utils.auth.Auth;", "com.snoworca.naruserve.utils.auth.AuthFileKey;",
                "com.snoworca.naruserve.utils.auth.AuthMode;", "com.snoworca.naruserve.utils.auth.user.FileUserStore;"}};
        for (String f : GUIDES) {
            String doc = read(f);
            for (String[] c : cases) {
                String b = java(f, doc, c[0]);
                for (int i = 1; i < c.length; i++) {
                    has(f + " block with " + c[0], b, "import " + c[i]);
                }
            }
        }
    }

    // OPS-AUTH-017 AC-9: Kotlin fragments that use an undeclared auth say where it comes from
    @Test
    public void OPS_AUTH_017_AC9_kotlinAuthIsNamed() throws IOException {
        for (String f : GUIDES) {
            for (String b : ReadmeDocsTest.blocks(read(f), "kotlin")) {
                boolean uses = Pattern.compile("\\bauth\\.").matcher(b).find();
                boolean declares = Pattern.compile("val auth\\b|fun \\w+\\(auth|auth: Auth").matcher(b).find();
                if (uses && !declares) {
                    assertTrue(f + ": auth unexplained in\n" + b, b.contains("// `auth`") || b.contains("`auth`는")
                            || b.contains("`auth` as above"));
                }
            }
        }
    }

    // OPS-AUTH-017 AC-10: the default binding typo
    @Test
    public void OPS_AUTH_017_AC10_defaultBindingTypo() throws IOException {
        for (String f : new String[] {"docs/api/express-migration.md", "docs/api/en/express-migration.md"}) {
            String doc = read(f);
            assertFalse(f, doc.contains("is127.0.0.1") || doc.contains("은127.0.0.1"));
            has(f, doc, en(f) ? "Default binding is 127.0.0.1" : "기본은 127.0.0.1");
        }
    }
}
