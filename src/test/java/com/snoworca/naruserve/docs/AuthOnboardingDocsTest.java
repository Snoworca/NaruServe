package com.snoworca.naruserve.docs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.List;
import org.junit.Test;

/** A junior finds auth, runs the quick start and is not misled by other guides (OPS-AUTH-005, OPS-AUTH-004 AC-3). */
public class AuthOnboardingDocsTest {

    private static String read(String path) throws IOException {
        return ReadmeDocsTest.read(path);
    }

    private static void has(String file, String text, String needle) {
        assertTrue(file + " lacks: " + needle, text.contains(needle));
    }

    // OPS-AUTH-005 AC-6: the OPS_AUTH_005_AC1 to AC5 tests of this class are the docs test that asserts AC-1 to AC-5
    // OPS-AUTH-005 AC-1: README.md and README.ko.md introduce auth, link the guide, show app.use(auth) and say where a
    // build with auth comes from before 0.11.0 is published
    @Test
    public void OPS_AUTH_005_AC1_readmeIntroducesAuth() throws IOException {
        for (String f : new String[] {"README.md", "README.ko.md"}) {
            String s = read(f);
            has(f, s, f.equals("README.md") ? "The auth module" : "인증 모듈");
            has(f, s, "app.use(auth)");
            has(f, s, "auth.md)");
            has(f, s, "0.11.0");
            has(f, s, f.equals("README.md") ? "#build-from-a-source-checkout" : "#소스-체크아웃-빌드");
        }
    }

    // OPS-AUTH-005 AC-2: the auth rows of the API indexes state the real status
    @Test
    public void OPS_AUTH_005_AC2_apiIndexStatesTheRealStatus() throws IOException {
        for (String f : new String[] {"docs/api/README.md", "docs/api/en/README.md"}) {
            String row = null;
            for (String l : read(f).split("\n")) {
                if (l.startsWith("|") && l.contains("(auth.md)")) {
                    row = l;
                }
            }
            assertTrue(f + " has no auth row", row != null);
            assertFalse(f + ": " + row, row.contains("JDBC"));
            assertFalse(f + ": " + row, row.contains("In progress") || row.contains("만드는 중") || row.contains("진행"));
            has(f, row, "/auth/login");
            has(f, row, "UserStore");
            has(f, row, "SPI");
            has(f, row, "auth.require");
            has(f, row, f.contains("/en/") ? "groups and roles" : "그룹과 역할");
        }
    }

    // OPS-AUTH-005 AC-3: the quick start is one complete Java program (imports, DEV, a .user, app.use(auth),
    // app.onError(auth.errors())) followed by curl lines; the Kotlin quick start has the same parts
    @Test
    public void OPS_AUTH_005_AC3_quickStartIsComplete() throws IOException {
        for (String f : new String[] {"docs/api/auth.md", "docs/api/en/auth.md"}) {
            String start = ReleaseNotesDocsTest.section(read(f), f.contains("/en/") ? "## Quick start" : "## 빠른 시작");
            List<String> java = ReadmeDocsTest.blocks(start, "java");
            assertEquals(f + " Java quick start blocks", 1, java.size());
            String j = java.get(0);
            for (String part : new String[] {"import com.snoworca.naruserve.RestApp;",
                    "import com.snoworca.naruserve.utils.auth.Auth;", "public static void main(", "AuthMode.DEV",
                    ".user(\"alice\", \"", "app.use(auth);", "app.onError(auth.errors());"}) {
                has(f + " Java quick start", j, part);
            }
            List<String> kotlin = ReadmeDocsTest.blocks(start, "kotlin");
            assertEquals(f + " Kotlin quick start blocks", 1, kotlin.size());
            for (String part : new String[] {"import com.snoworca.naruserve.utils.auth.Auth", "fun main(",
                    "AuthMode.DEV", ".user(\"alice\", \"", "app.use(auth)", "app.onError(auth.errors())"}) {
                has(f + " Kotlin quick start", kotlin.get(0), part);
            }
            List<String> curl = ReadmeDocsTest.blocks(start, "bash");
            assertEquals(f + " curl block", 1, curl.size());
            has(f + " curl", curl.get(0), "curl -X POST http://localhost:8080/auth/login");
            has(f + " curl", curl.get(0), "Authorization: Bearer");
        }
    }

    // OPS-AUTH-005 AC-4: defaults.md no longer advises trustProxy(true) behind a TLS proxy
    @Test
    public void OPS_AUTH_005_AC4_defaultsAdviseHopTrust() throws IOException {
        for (String f : new String[] {"docs/api/defaults.md", "docs/api/en/defaults.md"}) {
            String tls = null;
            for (String l : read(f).split("\n")) {
                if (l.startsWith("- **TLS")) {
                    tls = l;
                }
            }
            assertTrue(f + " has no TLS advice", tls != null);
            assertFalse(f + " still advises trustProxy(true): " + tls,
                    tls.contains("enable `app.trustProxy(true)`") || tls.contains("`app.trustProxy(true)`를 켜세요"));
            has(f, tls, "app.trustProxyHops(1)");
        }
    }

    // OPS-AUTH-005 AC-5: express-migration.md shows app.use(auth) with .protectOnly("/api")
    @Test
    public void OPS_AUTH_005_AC5_migrationGuideInstallsAuth() throws IOException {
        for (String f : new String[] {"docs/api/express-migration.md", "docs/api/en/express-migration.md"}) {
            String s = read(f);
            assertFalse(f, s.contains("app.use(\"/api\", auth)"));
            has(f, s, ".protectOnly(\"/api\")");
            has(f, s, "app.use(auth);");
        }
    }

    // OPS-AUTH-004 AC-3: the LAN/device paragraph sits with the secure-transport rule and quotes the WARN
    @Test
    public void OPS_AUTH_004_AC3_lanParagraphQuotesTheWarn() throws IOException {
        for (String f : new String[] {"docs/api/auth.md", "docs/api/en/auth.md"}) {
            String s = read(f);
            has(f, s, "auth: POST /auth/login was refused with 403 insecure_transport");
            has(f, s, ".allowInsecureTransport(\"");
            has(f, s, "trustProxyHops(1)");
        }
    }

    // OPS-AUTH-005 AC-3: the quick-start .user password meets the default policy (8 to 128 characters)
    @Test
    public void OPS_AUTH_005_AC3_quickStartPasswordMeetsThePolicy() throws IOException {
        java.util.regex.Pattern user = java.util.regex.Pattern.compile("\\.user\\(\"alice\", \"([^\"]+)\"");
        for (String f : new String[] {"docs/api/auth.md", "docs/api/en/auth.md"}) {
            String start = ReleaseNotesDocsTest.section(read(f), f.contains("/en/") ? "## Quick start" : "## 빠른 시작");
            for (String lang : new String[] {"java", "kotlin"}) {
                java.util.regex.Matcher m = user.matcher(ReadmeDocsTest.blocks(start, lang).get(0));
                assertTrue(f + " " + lang + " has the .user account", m.find());
                assertTrue(f + " " + lang + ": " + m.group(1), m.group(1).length() >= 8 && m.group(1).length() <= 128);
            }
        }
    }

    // OPS-AUTH-004 AC-3: the paragraph sits in the secure-transport section itself, not elsewhere in the guide
    @Test
    public void OPS_AUTH_004_AC3_lanParagraphIsInTheSecureTransportSection() throws IOException {
        for (String f : new String[] {"docs/api/auth.md", "docs/api/en/auth.md"}) {
            String s = ReleaseNotesDocsTest.section(read(f),
                    f.contains("/en/") ? "## The secure-transport rule" : "## 보안 연결 규칙");
            has(f, s, "auth: POST /auth/login was refused with 403 insecure_transport");
            has(f, s, ".allowInsecureTransport(");
            has(f, s, "trustProxyHops(1)");
        }
    }
}
