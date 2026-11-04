package com.snoworca.naruserve.build;

import static org.junit.Assert.assertEquals;
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
import java.util.regex.Pattern;
import org.junit.Test;

/**
 * The cross-module consistency gate of the 0.11.0 Core changes (CON-AUTH-007) and the Kotlin-stdlib-free api module
 * (CON-IAM-001 AC-5). The owner tests run in the same ./gradlew build as this one; this gate checks that each owner
 * AC is still cited by an @Test of the module it names, and restates none of their checks.
 */
public class CrossModuleAuthGateTest {

    private static final File ROOT = new File(BuildLayoutTest.property("naruserve.rootDir"));

    /** Owner AC to the test source directories that must cite it. */
    private static Map<String, String[]> owners() {
        Map<String, String[]> m = new LinkedHashMap<>();
        m.put("SEC-HTTP-001 AC-2", new String[] {"NaruServe-api/src/test"});
        m.put("SEC-HTTP-001 AC-4", new String[] {"NaruServe-tinyhttpd/src/test"});
        m.put("SEC-SVL-001 AC-1", new String[] {"NaruServe-servlet/src/test"});
        m.put("IR-HTTP-010 AC-2", new String[] {"NaruServe-api/src/test"});
        m.put("IR-APP-008 AC-5", new String[] {"NaruServe-api/src/test"});
        m.put("IR-ENGINE-003 AC-5", new String[] {"NaruServe-api/src/test"});
        m.put("CON-ENGINE-001 AC-1", new String[] {"NaruServe-api/src/test", "NaruServe-tinyhttpd/src/test",
            "NaruServe-servlet/src/test"});
        return m;
    }

    private static void sources(File dir, List<File> out) {
        File[] kids = dir.listFiles();
        if (kids == null) {
            return;
        }
        for (File k : kids) {
            if (k.isDirectory()) {
                sources(k, out);
            } else if (k.getName().endsWith(".java") || k.getName().endsWith(".kt")) {
                out.add(k);
            }
        }
    }

    /**
     * True when a test of {@code dir} cites {@code ac}: the citation comment sits directly above an {@code @Test} method
     * (within four lines, no {@code @Ignore} between), or a Kotlin {@code @Test fun} name carries it.
     */
    private static boolean cited(String ac, File dir) throws IOException {
        List<File> files = new ArrayList<>();
        sources(dir, files);
        Pattern token = Pattern.compile("(?<![A-Z0-9-])" + Pattern.quote(ac) + "(?![0-9])");
        for (File f : files) {
            String[] lines = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (!token.matcher(lines[i]).find()) {
                    continue;
                }
                if (lines[i].contains("fun `") && i > 0 && lines[i - 1].trim().startsWith("@Test")) {
                    return true;
                }
                for (int j = i + 1; j < Math.min(lines.length, i + 5); j++) {
                    String t = lines[j].trim();
                    if (t.startsWith("@Ignore")) {
                        break;
                    }
                    if (t.startsWith("@Test")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // CON-AUTH-007 AC-1: every owner AC of the socketAddress, method-set and legacy trustProxy checks is still cited
    // by a test of its module, so this build runs it
    @Test
    public void CON_AUTH_007_AC1_ownerAcceptanceTestsStayInTheBuild() throws IOException {
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, String[]> e : owners().entrySet()) {
            for (String dir : e.getValue()) {
                if (!cited(e.getKey(), new File(ROOT, dir))) {
                    missing.add(e.getKey() + " in " + dir);
                }
            }
        }
        assertEquals(new ArrayList<String>(), missing);
    }

    // CON-AUTH-007 AC-4: NaruServe-kotlin adds no Kotlin-only auth API in 0.11.0 (no req.principal, no attr<T>())
    @Test
    public void CON_AUTH_007_AC4_noKotlinOnlyAuthApi() throws IOException {
        List<File> files = new ArrayList<>();
        sources(new File(ROOT, "NaruServe-kotlin/src/main"), files);
        assertFalse(files.isEmpty());
        for (File f : files) {
            String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            assertFalse(f.getName(), Pattern.compile("\\.principal\\b").matcher(text).find());
            assertFalse(f.getName(), Pattern.compile("fun\\s*<[^>]*>\\s*\\S*\\.attr\\s*\\(").matcher(text).find());
            assertFalse(f.getName(), text.contains("utils.auth"));
        }
    }

    // CON-IAM-001 AC-5: NaruServe-api keeps no main dependency, so no Kotlin stdlib either
    @Test
    public void CON_IAM_001_AC5_apiHasNoKotlinStdlibDependency() throws IOException {
        String build = new String(Files.readAllBytes(new File(ROOT, "build.gradle").toPath()), StandardCharsets.UTF_8);
        assertTrue(Pattern.compile("':NaruServe-api'\\s*:\\s*\\[:\\]").matcher(build).find());
    }
}
