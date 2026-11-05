package com.snoworca.naruserve.docs;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.regex.Pattern;
import org.junit.Test;

/** The fourth junior round of the guides (OPS-AUTH-013). */
public class AuthGuideRound4DocsTest {

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

    /** The one line of {@code text} that starts with {@code prefix}. */
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

    /** The one fenced {@code lang} block of {@code text} holding {@code marker}. */
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

    // OPS-AUTH-013 AC-1: the recipe's call helper writes a body only for a non-null json, so a GET stays a GET
    @Test
    public void OPS_AUTH_013_AC1_recipeHelperKeepsGetAGet() throws IOException {
        for (String f : GUIDES) {
            String recipe = ReleaseNotesDocsTest.section(read(f), en(f) ? "### HTTP test recipe" : "### HTTP 테스트 레시피");
            String java = block(f, recipe, "java", "private String call(");
            String kotlin = block(f, recipe, "kotlin", "private fun call(");
            for (String[] b : new String[][] {{"java", java, "if (json != null) {"}, {"kotlin", kotlin, "if (json != null) {"}}) {
                int guard = b[1].indexOf(b[2]);
                assertTrue(f + " " + b[0] + " helper has no " + b[2], guard >= 0);
                int output = b[1].indexOf(b[0].equals("java") ? "setDoOutput(true)" : "doOutput = true");
                assertTrue(f + " " + b[0] + ": doOutput is set outside the json guard", output > guard);
            }
            has(f + " recipe", recipe, en(f) ? "a GET passes `null`" : "GET은 `null`을 넘깁니다");
        }
    }

    // OPS-AUTH-013 AC-2: Logger's package is shown and it is not listed as an SPI type
    @Test
    public void OPS_AUTH_013_AC2_loggerPackageIsShown() throws IOException {
        for (String f : new String[] {"docs/api/logging.md", "docs/api/en/logging.md"}) {
            has(f, read(f), "import com.snoworca.naruserve.Logger;"); // the block itself declares the interface
        }
        for (String f : new String[] {"docs/api/README.md", "docs/api/en/README.md"}) {
            String row = line(f, read(f), "| `NaruServe-api` |");
            assertFalse(f + ": Logger listed as SPI: " + row, Pattern.compile("SPI[^|]*`Logger`").matcher(row).find());
        }
    }

    // OPS-AUTH-013 AC-3: when the default throttle and device-trust files are written
    @Test
    public void OPS_AUTH_013_AC3_fileDefaultsSayWhenFilesAppear() throws IOException {
        for (String f : GUIDES) {
            String p = line(f, read(f), en(f) ? "**File defaults**" : "**파일 기본값**");
            for (String needle : en(f) ? new String[] {"every 60 seconds", "at stop", "only when"}
                    : new String[] {"60초마다", "멈출 때", "바뀐 것이 있을 때만"}) {
                has(f + " file defaults", p, needle);
            }
        }
    }

    // OPS-AUTH-013 AC-4: the READMEs say plainly where 0.11.0 is and which module holds auth
    @Test
    public void OPS_AUTH_013_AC4_readmeStatesAvailability() throws IOException {
        for (String f : new String[] {"README.md", "README.ko.md"}) {
            String doc = read(f);
            has(f, doc, f.endsWith(".ko.md") ? "0.11.0은 아직 Maven Central에 없습니다" : "0.11.0 is not yet on Maven Central");
            has(f, doc, "publishToMavenLocal");
            has(f + " naruserve-api row", line(f, doc, "| `NaruServe-api` |"), "utils.auth");
        }
    }

    // OPS-AUTH-013 AC-5: the error section describes the OPS-AUTH-012 answers
    @Test
    public void OPS_AUTH_013_AC5_errorSectionDescribesTheNewAnswers() throws IOException {
        for (String f : GUIDES) {
            String errors = ReleaseNotesDocsTest.section(read(f),
                    en(f) ? "## Error responses and AuthResponses" : "## 오류 응답과 AuthResponses");
            for (String needle : new String[] {"auth.errorMiddleware()", "sign-up is off", "no refresh token",
                    "the request body must be a JSON object"}) {
                has(f + " error section", errors, needle);
            }
            has(f + " error section", errors, en(f) ? "appends" : "덧붙");
            has(f + " error section", errors, en(f) ? "added after start" : "기동 뒤에 더한");
            assertFalse(f + ": the register 404 is still described as HTML", errors.contains("Cannot POST /auth/register"));
        }
    }

    // OPS-AUTH-013 AC-2: the import is shown before the Logger interface
    @Test
    public void OPS_AUTH_013_AC2_importComesBeforeTheInterface() throws IOException {
        for (String f : new String[] {"docs/api/logging.md", "docs/api/en/logging.md"}) {
            String doc = read(f);
            int shown = doc.indexOf("import com.snoworca.naruserve.Logger;");
            assertTrue(f, shown >= 0 && shown < doc.indexOf("public interface Logger"));
        }
    }

    // OPS-AUTH-013 AC-3: the device-trust file appears when its state first changes
    @Test
    public void OPS_AUTH_013_AC3_deviceTrustFileAppearsAtItsFirstChange() throws IOException {
        for (String f : GUIDES) {
            String p = line(f, read(f), en(f) ? "**File defaults**" : "**파일 기본값**");
            has(f + " file defaults", p, en(f) ? "the device-trust file at its first invalidation" : "신뢰 기기 파일은 첫 무효 표시");
        }
    }

    // OPS-AUTH-013 AC-4: publishToMavenLocal comes with mavenLocal() for a consumer
    @Test
    public void OPS_AUTH_013_AC4_readmeNamesMavenLocal() throws IOException {
        for (String f : new String[] {"README.md", "README.ko.md"}) {
            has(f, read(f), "mavenLocal()");
        }
    }
}
