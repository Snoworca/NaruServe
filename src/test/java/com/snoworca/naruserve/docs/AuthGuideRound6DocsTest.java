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
        }
    }

    // OPS-AUTH-017 AC-3: the DEV list includes the .user password policy, and so does the quick-start comment
    @Test
    public void OPS_AUTH_017_AC3_devListIsComplete() throws IOException {
        for (String f : GUIDES) {
            String doc = read(f);
            has(f, doc, en(f) ? "| A `.user(...)` password that breaks the password policy" : "| 비밀번호 정책을 어긴 `.user(...)` 비밀번호");
            for (String l : doc.split("\n")) {
                if (l.contains(".user(\"alice\", \"alice-password-1\", \"editor\")") && l.contains("//")) {
                    assertTrue(f + ": " + l, l.contains("PROD") && l.contains("DEV"));
                }
            }
            assertFalse(f, doc.contains(en(f) ? "Relaxes exactly three things" : "정해진 세 가지만"));
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
        }
    }

    // OPS-AUTH-017 AC-7: the recipe names the login limit in tests
    @Test
    public void OPS_AUTH_017_AC7_recipeNamesTheLoginLimit() throws IOException {
        for (String f : GUIDES) {
            String recipe = ReleaseNotesDocsTest.section(read(f), en(f) ? "### HTTP test recipe" : "### HTTP 테스트 레시피");
            has(f, recipe, ".loginFailureLimits(");
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
        }
    }
}
