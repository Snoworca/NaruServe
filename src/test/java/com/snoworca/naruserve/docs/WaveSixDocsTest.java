package com.snoworca.naruserve.docs;

import static com.snoworca.naruserve.docs.WaveFiveDocsTest.contains;
import static com.snoworca.naruserve.docs.WaveFiveDocsTest.dflt;
import static com.snoworca.naruserve.docs.WaveFiveDocsTest.doc;
import static com.snoworca.naruserve.docs.WaveFiveDocsTest.lineWith;
import static com.snoworca.naruserve.docs.WaveFiveDocsTest.linesWith;
import static com.snoworca.naruserve.docs.WaveFiveDocsTest.max;
import static com.snoworca.naruserve.docs.WaveFiveDocsTest.row;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.gson.GsonJsonCodec;
import com.snoworca.naruserve.internal.DefaultRestApp;
import com.snoworca.naruserve.internal.DefaultUploadFile;
import com.snoworca.naruserve.internal.MultipartSettings;
import com.snoworca.naruserve.testing.SettingsCapturingAdapter;
import java.io.File;
import java.io.IOException;
import java.util.List;
import org.junit.Test;

/** Wave 6 docs/api decisions: multipart and static pages marker-free, defaults.md rows read against the code. */
// @req OPS-MULTIPART-001
// @req OPS-STATIC-001
// @req OPS-REL-008 AC-1 AC-2 AC-3
public final class WaveSixDocsTest {

    private static final String MARKER = "(미확정)";

    private static void noMarker(String file) throws IOException {
        List<String> m = linesWith(doc(file), MARKER);
        assertTrue(file + ": " + m, m.isEmpty());
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
        contains("multipart.md", lineWith(m, "비어 있는 파일 입력"), "`filename=\"\"`", "`null`이 아니라", "빈 문자열",
                "`size()`가 0인 `UploadFile`", "`file == null || file.size() == 0`");
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
        // OPS-REL-008 AC-1 AC-3: the compact migration table delegates exact settings to owning references.
        contains("express-migration.md", e, "urlencoded()", "formLimit", "multipart", "staticFiles",
                "`file`, `download`, `stream`", "cache(pattern, policy)", "32 MiB", "64 MiB", "20개", "64 KiB");
        contains("request.md", doc("request.md"), "`express.urlencoded({ limit })`", "`app.formLimit(bytes)`");
        contains("multipart.md", doc("multipart.md"), "`app.multipart(c -> c.maxFileSize(...))`",
                "`tempDirectory`", "`java.io.tmpdir`", "`maxFileSize`", "`maxRequestSize`", "`maxParts`", "`memoryThreshold`");
        contains("response.md", doc("response.md"), "`file(File)`", "`download(File, filename)`", "`Content-Disposition`");
        contains("static.md", doc("static.md"), "`cache(pattern, policy)`", "`options.cache");
        for (String file : new String[] {"express-migration.md", "request.md", "multipart.md", "response.md", "static.md"}) noMarker(file);
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
        // OPS-REL-008 AC-1 AC-3; CON-APP-010 AC-4 AC-5: current ordered HTTP, retained WS reservation.
        contains("static.md", lineWith(s, "**등록한 위치**"), "정적 등록 앞에 인증", "전역 단계는 없습니다");
        contains("static.md", lineWith(s, "메서드가 다른 라우트"), "`GET`/`HEAD`", "`405`", "`404`");
        contains("static.md", lineWith(s, "유지되는 WebSocket 전용 경로"), "SPA fallback", "`426`");
        contains("static.md", lineWith(s, "`Allow: GET, HEAD`"), "`OPTIONS`", "`501`");
        contains("static.md", lineWith(s, "경로 검사("), "`400`", "`NUL`");
        contains("static.md", lineWith(s, "SPA 내용은 실제 쓰기 대상"), "`GET`/`HEAD`", "`404`", "심볼릭 링크");
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
