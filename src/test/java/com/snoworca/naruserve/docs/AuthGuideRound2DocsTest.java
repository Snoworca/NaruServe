package com.snoworca.naruserve.docs;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/** The second junior round of the auth guide (OPS-AUTH-009) and the behaviour changes it documents (OPS-AUTH-008). */
public class AuthGuideRound2DocsTest {

    private static final String KO = "docs/api/auth.md";
    private static final String EN = "docs/api/en/auth.md";
    private static final String[] GUIDES = {KO, EN};

    private static String read(String path) throws IOException {
        return ReadmeDocsTest.read(path);
    }

    private static boolean en(String f) {
        return f.contains("/en/");
    }

    private static void has(String where, String text, String needle) {
        assertTrue(where + " lacks: " + needle, text.contains(needle));
    }

    /** The one line of {@code text} that starts with {@code prefix} (a table row or a paragraph). */
    private static String line(String f, String text, String prefix) {
        String found = null;
        for (String l : text.split("\n")) {
            if (l.startsWith(prefix)) {
                assertTrue(f + ": more than one line starts with " + prefix, found == null);
                found = l;
            }
        }
        assertTrue(f + ": no line starts with " + prefix, found != null);
        return found;
    }

    private static String section(String f, String doc, String enHeading, String koHeading) {
        return ReleaseNotesDocsTest.section(doc, en(f) ? enHeading : koHeading);
    }

    /** The one block of {@code lang} in {@code text} that holds {@code marker}. */
    private static String block(String f, String text, String lang, String marker) {
        String found = null;
        for (String b : ReadmeDocsTest.blocks(text, lang)) {
            if (b.contains(marker)) {
                assertTrue(f + ": two " + lang + " blocks hold " + marker, found == null);
                found = b;
            }
        }
        assertTrue(f + ": no " + lang + " block holds " + marker, found != null);
        return found;
    }

    // OPS-AUTH-009 AC-1: the group example's code names userCacheTtl and its 5-second default
    // FR-IAM-016 AC-6: both pages show granting a role to a group with the built-in store (the grantRole Java block)
    @Test
    public void OPS_AUTH_009_AC1_groupExampleNamesTheCacheTtl() throws IOException {
        for (String f : GUIDES) {
            String groups = section(f, read(f), "## Groups: GroupStore and GroupLimits", "## 그룹: GroupStore와 GroupLimits");
            String java = block(f, groups, "java", "groups.grantRole(\"company\", \"editor\")");
            has(f + " group example", java, "userCacheTtl");
            has(f + " group example", java, ".userCacheTtl(0)");
            has(f + " group example", java, en(f) ? "5 s by default" : "기본 5초");
        }
    }

    // OPS-AUTH-009 AC-2: the logout row says the access token stays valid until it expires and that ?all=true ends it
    @Test
    public void OPS_AUTH_009_AC2_logoutRowSaysTheAccessTokenLives() throws IOException {
        for (String f : GUIDES) {
            String row = line(f, read(f), "| `POST /auth/logout[?all=true]` |");
            has(f + " logout row", row, en(f) ? "the access token already issued stays valid until it expires"
                    : "이미 받은 access 토큰은 만료될 때까지 유효");
            has(f + " logout row", row, en(f) ? "to end it now, send `?all=true`" : "지금 끊으려면 `?all=true`");
        }
    }

    private static final Pattern PUBLIC_TYPE =
            Pattern.compile("(?m)^public (?:final |abstract )*(?:class|interface|enum|@interface) (\\w+)");

    /** Every top-level public type of the auth packages (internal excluded), keyed by its package row name. */
    private static Map<String, List<String>> publicAuthTypes() throws IOException {
        File base = new File(ReadmeDocsTest.root(), "NaruServe-api/src/main/java/com/snoworca/naruserve/utils/auth");
        assertTrue(base + " exists", base.isDirectory());
        Map<String, List<String>> out = new LinkedHashMap<>();
        List<File> dirs = new ArrayList<>();
        dirs.add(base);
        for (File d : base.listFiles()) {
            if (d.isDirectory() && !d.getName().equals("internal")) {
                dirs.add(d);
            }
        }
        for (File d : dirs) {
            String pkg = d.equals(base) ? "utils.auth" : "utils.auth." + d.getName();
            List<String> names = new ArrayList<>();
            for (File j : d.listFiles()) {
                if (!j.getName().endsWith(".java") || j.getName().equals("package-info.java")) {
                    continue;
                }
                Matcher m = PUBLIC_TYPE.matcher(new String(Files.readAllBytes(j.toPath()), StandardCharsets.UTF_8));
                if (m.find()) {
                    names.add(m.group(1));
                }
            }
            out.put(pkg, names);
        }
        return out;
    }

    // OPS-AUTH-009 AC-3: the package table lists every public auth type in its real package, FileLoginThrottle and
    // FileDeviceTrust included, and the guide describes those two file stores
    @Test
    public void OPS_AUTH_009_AC3_packageTableIsComplete() throws IOException {
        Map<String, List<String>> types = publicAuthTypes();
        assertTrue(types.get("utils.auth.spi").contains("Authenticator"));
        for (String f : GUIDES) {
            String s = read(f);
            for (Map.Entry<String, List<String>> e : types.entrySet()) {
                String row = line(f, s, "| `" + e.getKey() + "` |");
                for (String name : e.getValue()) {
                    has(f + " package row " + e.getKey(), row, "`" + name + "`");
                }
            }
            String core = line(f, s, "| `utils.auth` |");
            has(f + " package row utils.auth", core, "`FileLoginThrottle`");
            has(f + " package row utils.auth", core, "`FileDeviceTrust`");
            String files = line(f, s, en(f) ? "**File defaults**" : "**파일 기본값**");
            has(f + " file defaults", files, "`FileLoginThrottle`");
            has(f + " file defaults", files, "`FileDeviceTrust`");
            has(f + " file defaults", files, "package-private");
            has(f + " file defaults", files, en(f) ? "no public constructor" : "공개 생성자가 없");
            has(f + " file defaults", files, en(f) ? "survive a restart" : "재시작한 뒤에도");
        }
    }

    // OPS-AUTH-009 AC-4: the FileUserStore constructors are said to throw IOException, and the AuthFileKey example
    // declares throws Exception on its enclosing method
    @Test
    public void OPS_AUTH_009_AC4_fileUserStoreThrowsIoException() throws IOException {
        for (String f : GUIDES) {
            String s = read(f);
            has(f + " FileUserStore item", line(f, s, "- `FileUserStore(File)`"), "`IOException`");
            has(f + " AuthFileKey table", line(f, s, "| `new FileUserStore(file, key)`"), "`IOException`");
            String sec = section(f, s, "## Encrypting file secrets: AuthFileKey", "## 파일 비밀 값 암호화: AuthFileKey");
            String java = block(f, sec, "java", "new FileUserStore(new File(dataDir, \"users.json\"), key)");
            int thr = java.indexOf(") throws Exception {");
            assertTrue(f + " AuthFileKey example declares throws Exception:\n" + java, thr >= 0);
            assertTrue(f + " throws Exception encloses the store code", thr < java.indexOf("AuthFileKey.fromEnv()"));
            assertTrue(f + " AuthFileKey example is a class:\n" + java, java.trim().startsWith("class ")
                    || java.contains("\nclass ") || java.contains("public class "));
        }
    }

    // OPS-AUTH-009 AC-5: the doctor list names the undeclared role and undeclared scope WARNs
    @Test
    public void OPS_AUTH_009_AC5_doctorListNamesUndeclaredWarns() throws IOException {
        for (String f : GUIDES) {
            String doctor = section(f, read(f), "## Startup checks (doctor)", "## 시작 점검(doctor)");
            has(f + " doctor", doctor, "`undeclared role`");
            has(f + " doctor", doctor, "`undeclared scope`");
        }
    }

    // OPS-AUTH-009 AC-6: auth.users() refuses a bad argument with IllegalArgumentException (a policy-breaking
    // password, say), naming only that type
    @Test
    public void OPS_AUTH_009_AC6_facadeNamesIllegalArgumentException() throws IOException {
        for (String f : GUIDES) {
            String s = read(f);
            String p = line(f, s, en(f) ? "`auth.users()` returns `AuthUsers`" : "`auth.users()`는 `UserStore`가 아니라");
            has(f + " facade", p, "`IllegalArgumentException`");
            has(f + " facade", p, en(f) ? "a password that breaks the `PasswordPolicy`" : "`PasswordPolicy`를 어긴 비밀번호");
            has(f + " facade", p, en(f) ? "internal subclass" : "내부 하위 클래스");
            assertFalse(f + " names the internal subclass", s.contains("AuthArgumentException"));
        }
    }

    // OPS-AUTH-009 AC-7: the Auth.builder(String) row says a bare null is a Java compile error and Kotlin accepts it
    @Test
    public void OPS_AUTH_009_AC7_builderRowExplainsNull() throws IOException {
        for (String f : GUIDES) {
            String row = line(f, read(f), "| `Auth.builder(@Nullable String secret)` |");
            has(f + " builder row", row, en(f) ? "a compile error" : "컴파일 오류");
            has(f + " builder row", row, "Kotlin");
            has(f + " builder row", row, "`Auth.builder(null)`");
        }
    }

    // OPS-AUTH-009 AC-8: near the quick start, default deny answers an unknown path with 401 unauthenticated before
    // any 404, so a wrong URL looks like a missing login
    @Test
    public void OPS_AUTH_009_AC8_unknownPathIs401() throws IOException {
        for (String f : GUIDES) {
            String start = section(f, read(f), "## Quick start", "## 빠른 시작");
            String l = line(f, start, en(f) ? "> **A wrong URL is 401 too:**" : "> **잘못된 URL도 401입니다:**");
            has(f + " unknown path", l, "`POST /login`");
            has(f + " unknown path", l, "`GET /auth/login`");
            has(f + " unknown path", l, "401 `unauthenticated`");
            has(f + " unknown path", l, "404");
        }
    }

    private static final String PATH_USE_MESSAGE = "app.use(\"/api\", mw): Auth installs itself on the whole app; call "
            + "app.use(mw) without a path (to protect only a prefix with auth, build it with .protectOnly(\"/api\"))";

    // FR-AUTH-010 AC-7: a path use of auth throws IllegalArgumentException at registration; the 500 answer is only
    // for an auth attached but never installed; app.md says a path use of an Installable throws
    @Test
    public void FR_AUTH_010_AC7_pathUseThrows() throws IOException {
        for (String f : GUIDES) {
            String p = line(f, read(f), en(f) ? "**`app.use(auth)` is the same as `auth.install(app)`.**"
                    : "**`app.use(auth)`는 `auth.install(app)`과 같습니다.**");
            has(f + " install paragraph", p, "`IllegalArgumentException`");
            has(f + " install paragraph", p, PATH_USE_MESSAGE);
            has(f + " install paragraph", p, "`router.use(auth)`");
            has(f + " install paragraph", p, "500 `INTERNAL_ERROR`");
            assertFalse(f + " still lists app.use(\"/api\", auth) as a 500 case", p.contains("A prefix `app.use(\"/api\", auth)`")
                    || p.contains("경로를 준 `app.use(\"/api\", auth)`, `router.use(auth)`"));
        }
        for (String f : new String[] {"docs/api/express-migration.md", "docs/api/en/express-migration.md"}) {
            String p = line(f, read(f), "app.use(auth);");
            assertFalse(f + " still says a path prefix answers every request with 500: " + p,
                    p.contains("500"));
            has(f + " migration comment", p, "IllegalArgumentException");
        }
        for (String f : new String[] {"docs/api/app.md", "docs/api/en/app.md"}) {
            String p = line(f, read(f), "`Installable`");
            has(f + " Installable note", p, "`IllegalArgumentException`");
            assertFalse(f + " still says a path use adds it as middleware",
                    p.contains("A prefix `use(prefix, x)` and a router's `use(x)` still add it as middleware")
                            || p.contains("경로를 준 `use(prefix, x)`와 라우터의 `use(x)`는 그대로 미들웨어로 더합니다"));
        }
    }

    // OPS-AUTH-008 AC-1, AC-2, AC-6: the doctor scope WARN, the second-install message and the CORS FAIL are quoted
    @Test
    public void OPS_AUTH_008_AC1_AC2_AC6_messagesAreQuoted() throws IOException {
        for (String f : GUIDES) {
            String s = read(f);
            String doctor = section(f, s, "## Startup checks (doctor)", "## 시작 점검(doctor)");
            has(f + " doctor", doctor, "scope '<s>' is required by auth.require(...) but no RolePolicy role grants it");
            String install = section(f, s, "## Install order", "## install 순서");
            has(f + " install order", install, "auth is already installed on this app; call app.use(auth) or "
                    + "auth.install(app) once, and build one Auth per app");
            has(f + " install order", install, "already installed on another app");
            has(f + " install order", install, "found CORS registered after it");
        }
    }

    // OPS-AUTH-008 AC-3: the unauthenticated row quotes the Bearer hint and the plain message
    @Test
    public void OPS_AUTH_008_AC3_unauthenticatedRowQuotesBothMessages() throws IOException {
        for (String f : GUIDES) {
            String row = line(f, read(f), "| `unauthenticated` | 401 |");
            has(f + " unauthenticated row", row,
                    "authentication is required; send the access token as Authorization: Bearer <token>");
            has(f + " unauthenticated row", row, "`authentication is required`");
            has(f + " unauthenticated row", row, "`Token abc`");
        }
    }

    // OPS-AUTH-008 AC-4: auth.user(req) on a public path is logged once, by Core's request <id> failed ERROR
    @Test
    public void OPS_AUTH_008_AC4_wrongStateIsLoggedOnce() throws IOException {
        for (String f : GUIDES) {
            String row = line(f, read(f), "| `auth.errors()` |");
            has(f + " auth.errors() row", row, "`request <id> failed`");
        }
    }

    // OPS-AUTH-008 AC-5: the admin A2 and A4 rows quote the empty-password 400
    @Test
    public void OPS_AUTH_008_AC5_adminRowsQuoteEmptyPassword() throws IOException {
        for (String f : GUIDES) {
            String s = read(f);
            for (String a : new String[] {"| A2 |", "| A4 |"}) {
                String row = line(f, s, a);
                has(f + " " + a, row, "400 `BAD_REQUEST`");
                has(f + " " + a, row, "password must be a non-empty JSON string");
            }
        }
    }
}
