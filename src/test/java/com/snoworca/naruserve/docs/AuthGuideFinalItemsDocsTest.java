package com.snoworca.naruserve.docs;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import org.junit.Test;

/** The guide side of the five items fixed after the twentieth junior study (OPS-AUTH-033 AC-5, AC-6). */
public class AuthGuideFinalItemsDocsTest {

    private static final String[] GUIDES = {"docs/api/auth.md", "docs/api/en/auth.md"};
    private static final String[] APP = {"docs/api/app.md", "docs/api/en/app.md"};

    private static boolean en(String f) {
        return f.contains("/en/");
    }

    private static void has(String where, String text, String needle) {
        assertTrue(where + " lacks: " + needle, text.contains(needle));
    }

    private static void lacks(String where, String text, String needle) {
        assertFalse(where + " still says: " + needle, text.contains(needle));
    }

    private static String line(String f, String text, String prefix) {
        for (String l : text.split("\n")) {
            if (l.startsWith(prefix)) {
                return l;
            }
        }
        throw new AssertionError(f + ": no line starts with " + prefix);
    }

    // OPS-AUTH-033 AC-5: the guides describe the four answers and drop the old statements
    @Test
    public void OPS_AUTH_033_AC5_guidesDescribeTheNewAnswers() throws IOException {
        for (String f : GUIDES) {
            String g = ReadmeDocsTest.read(f);
            has(f, g, "app.use(\"/\", auth)");
            has(f, g, "`no auth endpoint serves <method> <path>`");
            has(f, g, "this endpoint takes GET, PATCH, DELETE /auth/admin/users/:id");
            has(f, g, "`page must be a 32-bit integer`");
            has(f, g, "`unknown field <name>; PATCH /auth/me reads only {\"attributes\":{...}}`");
            lacks(f, g, "Cannot GET /auth/logout");
            lacks(f, g, en(f) ? "or `password` are ignored" : "같은 다른 필드는 무시합니다");
        }
        for (String f : APP) {
            String a = ReadmeDocsTest.read(f);
            has(f, a, en(f) ? "`use(\"/\", x)` installs it like `use(x)`" : "`use(\"/\", x)`는 `use(x)`처럼 install하고");
        }
    }

    // OPS-AUTH-033 AC-5: the guides describe the PROD final error layer and the 405, and promise no Core HTML for them
    @Test
    public void OPS_AUTH_033_AC5_guidesDescribeTheFinalErrorLayer() throws IOException {
        for (String f : GUIDES) {
            String g = ReadmeDocsTest.read(f);
            has(f, g, en(f) ? "In PROD auth also appends, at start and after every layer registered by then"
                    : "PROD에서는 기동할 때 그때까지 등록된 모든 층 뒤에");
            has(f, g, en(f) ? "in PROD so is the answer to every exception thrown by an app route registered before start"
                    : "PROD에서는 기동 전에 등록한 앱 라우트가 던진 예외의 응답도 모두 JSON입니다");
            has(f, g, "`METHOD_NOT_ALLOWED`");
            lacks(f, g, "HTML 404");
        }
    }

    // OPS-AUTH-033 AC-6: both rows say the roles are the user's own, in words and not only by naming getRoles()
    @Test
    public void OPS_AUTH_033_AC6_rowsSayOwnRolesInWords() throws IOException {
        for (String f : GUIDES) {
            String g = ReadmeDocsTest.read(f);
            has(f + " /auth/me row", line(f, g, "| `GET /auth/me` |"),
                    en(f) ? "`roles` is the user's own roles only" : "`roles`는 사용자 자신의 역할뿐");
            has(f + " A3 row", line(f, g, "| A3 |"),
                    en(f) ? "`roles` is the user's own roles" : "`roles`는 사용자 자신의 역할이고");
        }
    }

    // OPS-AUTH-033 AC-6: the GET /auth/me and A3 rows say roles are the user's own roles only
    @Test
    public void OPS_AUTH_033_AC6_meAndA3RowsNameOwnRoles() throws IOException {
        for (String f : GUIDES) {
            String g = ReadmeDocsTest.read(f);
            has(f + " /auth/me row", line(f, g, "| `GET /auth/me` |"), "auth.user(req).getRoles()");
            has(f + " A3 row", line(f, g, "| A3 |"), "auth.user(req).getRoles()");
        }
    }
}
