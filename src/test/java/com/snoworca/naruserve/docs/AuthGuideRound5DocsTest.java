package com.snoworca.naruserve.docs;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** The fifth junior round of the guides (OPS-AUTH-015). */
public class AuthGuideRound5DocsTest {

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

    private static String section(String f, String enHeading, String koHeading) throws IOException {
        return ReleaseNotesDocsTest.section(read(f), en(f) ? enHeading : koHeading);
    }

    private static List<String> code(String text) {
        List<String> out = new ArrayList<>(ReadmeDocsTest.blocks(text, "java"));
        out.addAll(ReadmeDocsTest.blocks(text, "kotlin"));
        return out;
    }

    // OPS-AUTH-015 AC-1: the error section gives the observed GET /auth/logout answers and the one body message
    @Test
    public void OPS_AUTH_015_AC1_errorSectionMatchesTheAnswers() throws IOException {
        for (String f : GUIDES) {
            String errors = section(f, "## Error responses and AuthResponses", "## 오류 응답과 AuthResponses");
            for (String needle : new String[] {"GET /auth/logout", "this endpoint takes POST /auth/logout",
                    "405 `METHOD_NOT_ALLOWED`", "the request body must be a JSON object"}) { // AC-1 amended by OPS-AUTH-033
                has(f + " error section", errors, needle);
            }
            has(f + " error section", errors, "401 `unauthenticated`");
            has(f + " error section", errors, "authentication is required; this endpoint takes POST /auth/logout");
            has(f + " error section", errors, en(f) ? "default deny runs before routing" : "기본 거부가 라우팅보다 먼저");
            assertFalse(f, errors.contains("the request body is not valid JSON"));
            assertFalse(f, errors.contains("a JSON object is required"));
        }
    }

    // OPS-AUTH-015 AC-2: a surprising 403 points to the undeclared scope and role WARNs
    @Test
    public void OPS_AUTH_015_AC2_a403PointsToTheDoctorWarns() throws IOException {
        for (String f : GUIDES) {
            for (String s : new String[] {section(f, "## Quick start", "## 빠른 시작"),
                    section(f, "## Guards and the principal", "## 가드와 주체")}) {
                has(f, s, "undeclared scope");
                has(f, s, "undeclared role");
            }
        }
    }

    // OPS-AUTH-015 AC-3: Kotlin fragments say where their outside names come from
    @Test
    public void OPS_AUTH_015_AC3_kotlinFragmentsNameTheirOutsideNames() throws IOException {
        String[][] cases = {{"JwtKeys.of(\"k1\", key)", "key", "now"}, {"ApiKey.of(row", "row"},
            {"MyUserStore(ds)", "MyUserStore", "ds"}, {"issue(botId", "botId"}, {"in commonPasswords", "commonPasswords"},
            {"directory[name]", "store", "directory"}, {"File(dataDir", "dataDir"}};
        for (String f : GUIDES) {
            List<String> kotlin = ReadmeDocsTest.blocks(read(f), "kotlin");
            for (String[] c : cases) {
                String block = null;
                for (String b : kotlin) {
                    if (b.contains(c[0])) {
                        block = b;
                    }
                }
                assertTrue(f + ": no Kotlin block holds " + c[0], block != null);
                String comments = "";
                for (String l : block.split("\n")) {
                    if (l.trim().startsWith("//")) {
                        comments += l + "\n";
                    }
                }
                for (int i = 1; i < c.length; i++) {
                    has(f + " comment of the block with " + c[0], comments, "`" + c[i] + "`");
                }
                if (c[0].equals("MyUserStore(ds)")) {
                    has(f + " test-kit Kotlin block", block, "import org.junit.Test");
                }
            }
        }
    }

    // OPS-AUTH-015 AC-4: no checked charset exception in a guide block
    @Test
    public void OPS_AUTH_015_AC4_noGetBytesUtf8() throws IOException {
        for (String f : GUIDES) {
            for (String b : code(read(f))) {
                assertFalse(f + ":\n" + b, b.contains("getBytes(\"UTF-8\")"));
            }
        }
    }

    // OPS-AUTH-015 AC-5: the test kit shows a test-only PROD secret and file key
    @Test
    public void OPS_AUTH_015_AC5_testKitShowsAProdSecret() throws IOException {
        for (String f : GUIDES) {
            String kit = section(f, "## Test kit: AuthTesting", "## 테스트 킷: AuthTesting");
            has(f + " test kit", kit, "SecureRandom");
            has(f + " test kit", kit, "AuthFileKey.of(");
        }
    }

    // OPS-AUTH-015 AC-6: the availability is one sentence of the Install section
    @Test
    public void OPS_AUTH_015_AC6_availabilityIsOneSentence() throws IOException {
        String en = read("README.md");
        assertFalse(en.contains("> **Availability:**"));
        String line = null;
        for (String l : en.split("\n")) {
            if (l.contains("If 0.11.0 is unavailable")) {
                line = l;
            }
        }
        assertTrue(line != null && line.contains("0.11.0 is not yet on Maven Central") && !line.trim().endsWith(":"));
        String ko = read("README.ko.md");
        assertFalse(ko.contains("> **가용성:**"));
        line = null;
        for (String l : ko.split("\n")) {
            if (l.contains("0.11.0을 받을 수 없다면")) {
                line = l;
            }
        }
        assertTrue(line != null && line.contains("0.11.0은 아직 Maven Central에 없습니다"));
    }
}
