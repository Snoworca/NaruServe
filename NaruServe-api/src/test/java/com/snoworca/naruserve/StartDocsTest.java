package com.snoworca.naruserve;

import static com.snoworca.naruserve.HttpDocsTest.doc;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** OPS-DX-003: the docs of RestApp.start, ServerHandle.url and shutdown, and the Quick start of both READMEs. */
// @req OPS-REL-008 AC-1 AC-2 AC-3
public class StartDocsTest {

    private static String root(String path) throws IOException {
        File f = new File(System.getProperty("user.dir"), "../" + path);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[8192];
            for (int n = in.read(buf); n > 0; n = in.read(buf)) {
                out.write(buf, 0, n);
            }
        }
        return new String(out.toByteArray(), "UTF-8");
    }

    private static String section(String doc, String heading) {
        int i = doc.indexOf("\n" + heading + "\n");
        assertTrue("no section " + heading, i >= 0);
        int j = doc.indexOf("\n## ", i + 1);
        return j < 0 ? doc.substring(i) : doc.substring(i, j);
    }

    private static List<String> blocks(String text, String lang) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = null;
        for (String line : text.split("\n", -1)) {
            if (cur == null && line.trim().equals("```" + lang)) {
                cur = new StringBuilder();
            } else if (cur != null && line.trim().startsWith("```")) {
                out.add(cur.toString());
                cur = null;
            } else if (cur != null) {
                cur.append(line).append('\n');
            }
        }
        return out;
    }

    // OPS-DX-003 AC-1
    @Test
    public void OPS_DX_003_AC1_appMdHasTheStartSectionWithTheComparisonTable() throws IOException {
        String start = section(doc("app.md"), "## start");
        for (String part : new String[] {"`start(int port, Configurer<RestApp> routes)`", "`RestApp.create()`", "`listen(port)`",
                "시작 로그", "JVM 종료 훅", "app.listen(3000, () => ...)", "`url()`", "`handle.shutdown(...)`",
                "| | `start(port, routes)` | `create()` + `listen(port)` |", "| 시작 로그 |", "| JVM 종료 훅 |",
                "| 설정하는 곳 |", "| 블로킹 |", "| 기본 바인딩 |", "`127.0.0.1`",
                "app.server(o -> o.bindAddress(\"0.0.0.0\"))", "메인 스레드에서 부르지 마세요", "Android에서는 훅을 등록하지 않고"}) {
            assertTrue("app.md start section holds " + part, start.contains(part));
        }
        assertEquals("one java and one kotlin block", 1, blocks(start, "java").size());
        assertEquals(1, blocks(start, "kotlin").size());
        assertTrue(blocks(start, "java").get(0).contains("RestApp.start(3000, app ->"));
        assertTrue(blocks(start, "kotlin").get(0).contains("RestApp.start(3000) { app ->"));
        assertFalse("no marker left", doc("app.md").contains("(미확정)"));
        String handle = doc("app.md");
        assertTrue(handle.contains("`url()`은 열어 볼 주소"));
        assertTrue(handle.contains("`shutdown(timeout, unit)`은 `app.shutdown`과 같은 규칙으로"));
    }

    // OPS-DX-003 AC-2
    @Test
    public void OPS_DX_003_AC2_lifecycleExpressDefaultsAndKotlinNameStartWithoutChangingTheBind() throws IOException {
        String lifecycle = doc("lifecycle.md");
        assertTrue(lifecycle.contains("NEW ──listen/start──> STARTING"));
        assertTrue(lifecycle.contains("`RestApp.start(...)`도 `listen()`을 포함하므로 **메인 스레드에서 부르지 마세요.**"));
        String express = doc("express-migration.md");
        // OPS-REL-008 AC-1 AC-3: start entry point and unchanged loopback live in current comparison sections.
        assertTrue(express.contains("`RestApp.start(port, routes)`"));
        assertTrue(express.contains("기본 127.0.0.1"));
        String binding = section(express, "### 4. 바인딩과 검증 기본값은 유지합니다");
        assertTrue(binding.contains("127.0.0.1") && binding.contains("0.0.0.0"));
        assertTrue(binding.contains("인증") && binding.contains("보안 경고"));
        assertTrue(doc("app.md").contains("`start(3000, ...)`는 `listen`처럼 이 기기에서만 받습니다"));
        String defaults = doc("defaults.md");
        assertTrue(defaults.contains("`RestApp.start(...)`도 같은 `127.0.0.1`에 바인딩합니다."));
        assertTrue(doc("kotlin.md").contains("`RestApp.start(3000) { app -> ... }`는 Java 정적 메서드"));
    }

    // OPS-DX-003 AC-4
    @Test
    public void OPS_DX_003_AC4_bothQuickStartsCallStartAndNameTheLoopbackBind() throws IOException {
        String readme = root("README.md");
        String api = doc("README.md");
        for (String text : new String[] {readme, api}) {
            assertTrue(text.contains("RestApp.start(3000, app -> app.get(\"/hello\", (req, res) -> res.send(\"Hello World\")));"));
            assertTrue(text.contains("RestApp.start(3000) { app -> app.get(\"/hello\") { _, res -> res.send(\"Hello World\") } }"));
            assertTrue(text.contains("public static void main(String[] args)"));
            assertTrue(text.contains("import com.snoworca.naruserve.RestApp;"));
            assertTrue(text.contains("fun main() {"));
            assertTrue(text.contains("127.0.0.1"));
        }
        assertTrue(readme.contains("listens on `127.0.0.1` only"));
        assertTrue(api.contains("`127.0.0.1`에서만 받고"));
    }
}
