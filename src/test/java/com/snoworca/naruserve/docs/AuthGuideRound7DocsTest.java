package com.snoworca.naruserve.docs;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import org.junit.Test;

/** The seventh junior round of the guides (OPS-AUTH-019). */
public class AuthGuideRound7DocsTest {

    private static final String[] GUIDES = {"docs/api/auth.md", "docs/api/en/auth.md"};

    private static boolean en(String f) {
        return f.contains("/en/");
    }

    private static void has(String where, String text, String needle) {
        assertTrue(where + " lacks: " + needle, text.contains(needle));
    }

    private static String line(String f, String text, String prefix) {
        for (String l : text.split("\n")) {
            if (l.startsWith(prefix)) {
                return l;
            }
        }
        throw new AssertionError(f + ": no line starts with " + prefix);
    }

    // OPS-AUTH-019 AC-1: a policy refusal from auth.users() reaches the client with its message
    @Test
    public void OPS_AUTH_019_AC1_usersRefusalCarriesThePolicyMessage() throws IOException {
        for (String f : GUIDES) {
            String p = line(f, ReadmeDocsTest.read(f), en(f) ? "`auth.users()` returns `AuthUsers`" : "`auth.users()`는 `UserStore`가 아니라");
            has(f, p, en(f) ? "with its message" : "그 메시지와 함께");
            has(f, p, en(f) ? "the other argument refusals" : "그 밖의 인자 거부");
        }
    }

    // OPS-AUTH-019 AC-2: the recipe names a Java 8 way to send PATCH
    @Test
    public void OPS_AUTH_019_AC2_recipeNamesJava8Patch() throws IOException {
        for (String f : GUIDES) {
            String recipe = ReleaseNotesDocsTest.section(ReadmeDocsTest.read(f),
                    en(f) ? "### HTTP test recipe" : "### HTTP 테스트 레시피");
            has(f, recipe, en(f) ? "needs Java 11" : "Java 11 이상이 필요");
            has(f, recipe, "OkHttp");
            has(f, recipe, en(f) ? "Java 8" : "Java 8");
        }
    }

    // OPS-AUTH-019 AC-3: a bodiless login is 415
    @Test
    public void OPS_AUTH_019_AC3_bodilessLoginIs415() throws IOException {
        for (String f : GUIDES) {
            has(f, ReadmeDocsTest.read(f), en(f) ? "`POST /auth/login` without a body is 415"
                    : "본문 없는 `POST /auth/login`은 415");
        }
    }

    // OPS-AUTH-019 AC-4: the admin A2 row says an undeclared role is WARNed
    @Test
    public void OPS_AUTH_019_AC4_a2RowNamesTheUndeclaredRoleWarn() throws IOException {
        for (String f : GUIDES) {
            has(f + " A2 row", line(f, ReadmeDocsTest.read(f), "| A2 |"), en(f) ? "does not declare logs a WARN"
                    : "선언하지 않은 역할을 주면 WARN");
        }
    }
}
