package com.snoworca.naruserve.docs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.gson.GsonJsonCodec;
import com.snoworca.naruserve.internal.DefaultRestApp;
import com.snoworca.naruserve.internal.DefaultUploadFile;
import com.snoworca.naruserve.internal.MultipartSettings;
import com.snoworca.naruserve.testing.SettingsCapturingAdapter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** Wave 6 docs/api decisions: multipart and static pages marker-free, defaults.md rows read against the code. */
// @req OPS-MULTIPART-001
// @req OPS-STATIC-001
public final class WaveSixDocsTest {

    private static final String MARKER = "(미확정)";

    private static String doc(String name) throws IOException {
        String root = System.getProperty("naruserve.rootDir");
        assertNotNull("naruserve.rootDir system property", root);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = new FileInputStream(new File(root, "docs/api/" + name))) {
            byte[] buf = new byte[8192];
            for (int n = in.read(buf); n > 0; n = in.read(buf)) {
                out.write(buf, 0, n);
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static List<String> linesWith(String doc, String phrase) {
        List<String> out = new ArrayList<>();
        for (String l : doc.split("\n")) {
            if (l.contains(phrase)) {
                out.add(l);
            }
        }
        return out;
    }

    private static String lineWith(String doc, String phrase) {
        List<String> found = linesWith(doc, phrase);
        assertFalse("no line holds " + phrase, found.isEmpty());
        return found.get(0);
    }

    private static void contains(String where, String text, String... parts) {
        for (String p : parts) {
            assertTrue(p + " in " + where + ": " + text, text.contains(p));
        }
    }

    private static void noMarker(String file) throws IOException {
        List<String> m = linesWith(doc(file), MARKER);
        assertTrue(file + ": " + m, m.isEmpty());
    }

    /** The cells of the defaults.md table row whose first cell is {@code label}. */
    private static String[] row(String defaults, String label) {
        String line = lineWith(defaults, "| " + label + " |");
        String[] raw = line.split("\\|", -1);
        String[] cells = new String[raw.length - 2];
        for (int i = 1; i < raw.length - 1; i++) {
            cells[i - 1] = raw[i].trim();
        }
        assertEquals(label, cells[0]);
        return cells;
    }

    private static long dflt(String d, String label) {
        return WaveFiveDocsTest.value(row(d, label)[1]);
    }

    private static long max(String d, String label) {
        return WaveFiveDocsTest.value(row(d, label)[2]);
    }

    private interface Setter {
        void set(long value);
    }

    /** {@code setter} accepts {@code max} and rejects {@code max + 1} with IllegalArgumentException. */
    private static void bounded(String name, long max, Setter setter) {
        setter.set(max);
        try {
            setter.set(max + 1);
            fail(name + " accepted a value above its documented maximum " + max);
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains(name));
        }
    }

    private static RestApp app() {
        return RestApp.builder().httpAdapter(new SettingsCapturingAdapter()).jsonCodec(new GsonJsonCodec()).build();
    }

    // SDS-AC-1; SDS-AC-2; OPS-MULTIPART-001 AC-3
    @Test
    public void defaultsMatchCode() throws IOException {
        String d = doc("defaults.md");
        // WaveFiveDocsTest reads the first line holding 무제한; it stays the opening paragraph.
        String unlimited = lineWith(d, "무제한");
        assertTrue(unlimited, unlimited.startsWith("NaruServe는") && unlimited.contains("`IllegalArgumentException`"));

        MultipartSettings s = new MultipartSettings();
        assertEquals(dflt(d, "업로드 파일 하나"), s.maxFileSize());
        assertEquals(dflt(d, "업로드 요청 전체"), s.maxRequestSize());
        assertEquals(dflt(d, "업로드 파트 수"), s.maxParts());
        assertEquals(dflt(d, "업로드 메모리 임계값"), s.memoryThreshold());
        assertEquals(dflt(d, "파트 헤더 한 줄"), MultipartSettings.MAX_HEADER_LINE);
        assertEquals(dflt(d, "파트 헤더 전체"), MultipartSettings.MAX_HEADER_TOTAL);
        assertEquals(dflt(d, "`UploadFile.bytes()`"), DefaultUploadFile.BYTES_LIMIT);
        contains("defaults.md", row(d, "파트 헤더 한 줄")[3], "`413`");
        contains("defaults.md", row(d, "파트 헤더 전체")[3], "`413`");
        contains("defaults.md", row(d, "`UploadFile.bytes()`")[3], "`IllegalStateException`");
        assertEquals(dflt(d, "폼 본문"), ((DefaultRestApp) app()).formLimit());
        contains("defaults.md", row(d, "폼 본문")[4], "`app.formLimit(bytes)`", "multipart 텍스트 필드");

        String temp = lineWith(d, "업로드 임시 폴더의 기본값은");
        contains("defaults.md", temp, "`java.io.tmpdir`", "Android", "`c.tempDirectory(dir)`");
        assertEquals(new File(System.getProperty("java.io.tmpdir")), s.tempDirectory());

        bounded("maxFileSize", max(d, "업로드 파일 하나"), v -> new MultipartSettings().maxFileSize(v));
        bounded("maxRequestSize", max(d, "업로드 요청 전체"), v -> new MultipartSettings().maxRequestSize(v));
        bounded("maxParts", max(d, "업로드 파트 수"), v -> new MultipartSettings().maxParts((int) v));
        bounded("memoryThreshold", max(d, "업로드 메모리 임계값"),
                v -> new MultipartSettings().memoryThreshold((int) v));
        bounded("formLimit", max(d, "폼 본문"), v -> app().formLimit(v));

        for (String label : new String[] {"| 폼 본문 |", "| 업로드 파일 하나 |", "| 업로드 요청 전체 |",
                "| 업로드 파트 수 |", "| 업로드 메모리 임계값 |", "| 파트 헤더 한 줄 |", "| 파트 헤더 전체 |",
                "| `UploadFile.bytes()` |", "업로드 임시 폴더", "| 심볼릭 링크 |", "| `Cache-Control` |",
                "| 정적 폴더 index 파일 |", "| 정적 폴더 목록 |", "| SPA fallback |"}) {
            assertFalse(label, lineWith(d, label).contains(MARKER));
        }
    }

    // OPS-STATIC-001 AC-5 — the static security rows of defaults.md
    @Test
    public void defaultsStaticRows() throws IOException {
        String d = doc("defaults.md");
        contains("defaults.md", lineWith(d, "| 심볼릭 링크 |"), "차단", "`options.allowSymlinks(true)`");
        contains("defaults.md", lineWith(d, "| `Cache-Control` |"), "붙이지 않음", "`app.defaultCache(policy)`");
        contains("defaults.md", lineWith(d, "| 정적 폴더 index 파일 |"), "`index.html`");
        contains("defaults.md", lineWith(d, "| 정적 폴더 목록 |"), "꺼짐");
        contains("defaults.md", lineWith(d, "| SPA fallback |"), "꺼짐", "`/api/`", "`/ws/`");
    }

    // OPS-MULTIPART-001 AC-1
    @Test
    public void multipartDecisions() throws IOException {
        noMarker("multipart.md");
        String m = doc("multipart.md");
        contains("multipart.md", lineWith(m, "| `tempDirectory`"), "`java.io.tmpdir`", "Android");
        contains("multipart.md", lineWith(m, "무제한 설정 허용 여부"), "허용하지 않습니다", "`IllegalArgumentException`",
                "이유");
        contains("multipart.md", lineWith(m, "| `maxFileSize`"), "4294967296");
        contains("multipart.md", lineWith(m, "| `maxRequestSize`"), "4294967296");
        contains("multipart.md", lineWith(m, "| `maxParts`"), "10000");
        contains("multipart.md", lineWith(m, "| `memoryThreshold`"), "16777216");
        contains("multipart.md", lineWith(m, "텍스트 필드(`filename`이 없는 파트)"), "메모리", "`app.formLimit(bytes)`",
                "1 MiB", "`413`", "이유");
        contains("multipart.md", lineWith(m, "| `bytes()`"), "8 MiB", "`IllegalStateException`");
        contains("multipart.md", lineWith(m, "`req.parts()`는"), "`List<UploadFile>`", "텍스트 필드");
        contains("multipart.md", lineWith(m, "임시 파일은 요청이 끝날 때"), "`save()`", "`413`", "끊었을 때", "`shutdown`");
        String block = m.substring(m.indexOf("public interface UploadFile"));
        block = block.substring(0, block.indexOf("```"));
        contains("multipart.md", block, "@Nullable String filename();", "@Nullable String contentType();",
                "void close();");
        assertFalse(block, block.contains("close() throws"));
    }

    // OPS-MULTIPART-001 AC-2
    @Test
    public void requestFormDecisions() throws IOException {
        String r = doc("request.md");
        for (String accessor : new String[] {"| `form(name)`", "| `file(name)`", "| `files(name)`", "| `parts()`"}) {
            assertFalse(accessor, lineWith(r, accessor).contains(MARKER));
        }
        contains("request.md", lineWith(r, "| `parts()`"), "`List<UploadFile>`", "텍스트 필드도 포함");
        contains("request.md", lineWith(r, "| `app.formLimit(bytes)`"), "1 MiB", "`express.urlencoded({ limit })`",
                "multipart 텍스트 필드");
    }

    // OPS-MULTIPART-001 AC-4; OPS-STATIC-001 AC-5
    @Test
    public void expressMigrationRows() throws IOException {
        String e = doc("express-migration.md");
        for (String[] r : new String[][] {
            {"| `express.urlencoded({ limit })`", "`app.formLimit(bytes)`", "묶었습니다"},
            {"| `multer` |", "`app.multipart(config -> ...)`", "이유"},
            {"| `multer({ limits", "`app.multipart(c ->", "하려는 것입니다"},
            {"| `multer({ dest })`", "`java.io.tmpdir`", "골랐습니다"},
            {"| `express.static('public')`", "`app.staticFiles(", "`serve-static`"},
            {"| `res.sendFile(path)`", "`res.file(file)`", "`Content-Disposition`"},
            {"| `res.download(path, name)`", "`res.download(file, name)`"},
            {"`setHeaders`", "`options.cache(pattern, policy)`", "이유"}}) {
            String line = lineWith(e, r[0]);
            assertFalse(line, line.contains(MARKER));
            for (int i = 1; i < r.length; i++) {
                contains("express-migration.md", line, r[i]);
            }
        }
    }

    // OPS-STATIC-001 AC-1, AC-3
    @Test
    public void staticDecisions() throws IOException {
        noMarker("static.md");
        String s = doc("static.md");
        contains("static.md", lineWith(s, "SPA fallback은"), "`/api/`", "`/ws/`", "`options.spaExclude(", "세그먼트 경계");
        contains("static.md", lineWith(s, "`/docs/`로 301"), "쿼리", "`404`", "빈 세그먼트");
        contains("static.md", lineWith(s, "| `cache(pattern, policy)`"), "마운트 접두사", "`**/`", "`*`", "`**`",
                "먼저 등록한 패턴");
        contains("static.md", lineWith(s, "심볼릭 링크(루트 안을"), "`allowSymlinks(true)`", "정규 경로");
        contains("static.md", lineWith(s, "`app.defaultCache(policy)`는"), "`null`", "`res.cache`", "`res.header`",
                "`options.cache`", "프레임워크");
        contains("static.md", lineWith(s, "**명시 라우트가 먼저**"), "명시 라우트 다음");
        contains("static.md", lineWith(s, "메서드가 다른 라우트"), "`GET`/`HEAD`", "`405`", "`Allow`");
        contains("static.md", lineWith(s, "WebSocket 라우트의 경로"), "SPA fallback");
        contains("static.md", lineWith(s, "`Allow: GET, HEAD`"), "`OPTIONS`", "`501`");
        contains("static.md", lineWith(s, "경로 검사("), "`400`", "`NUL`");
        contains("static.md", lineWith(s, "그 밖의 경로"), "SPA fallback", "`404`");
    }

    // SDS-AC-4; OPS-STATIC-001 AC-2
    @Test
    public void toctouAndCanonCacheShareOneParagraph() throws IOException {
        String p = lineWith(doc("static.md"), "TOCTOU");
        contains("static.md", p, "`java.io`", "재검사", "-Dsun.io.useCanonCaches=false", "30초", "Android");
    }

    // OPS-STATIC-001 AC-4
    @Test
    public void responseFileDecisions() throws IOException {
        String r = doc("response.md");
        contains("response.md", lineWith(r, "| `file(File)`"), "`Content-Disposition`을 보내지 않습니다");
        contains("response.md", lineWith(r, "| `cache(policy)`"), "`Cache-Control`");
        contains("response.md", lineWith(r, "| `download(File, filename)`"), "attachment");
        contains("response.md", lineWith(r, "`app.defaultCache(policy)`로"), "`null`");
        contains("response.md", lineWith(r, "경로 이탈 검사는 `staticFiles`만"), "`file()`");
        contains("response.md", lineWith(r, "`res.file`/`res.download`는 넘긴 파일 자체가"), "심볼릭 링크", "일반 파일",
                "`404`");
    }
}
