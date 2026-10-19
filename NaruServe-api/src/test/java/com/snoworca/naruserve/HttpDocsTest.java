package com.snoworca.naruserve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** Wave 3 decisions recorded in docs/api (OPS-HTTP-003); routing.md keeps no marker, checked by RoutingDocsTest. */
public class HttpDocsTest {

    private static final String MARKER = "(미확정)";

    private static String doc(String name) throws IOException {
        // Module tests run in NaruServe-api; the docs live at the repository root.
        File f = new File(System.getProperty("user.dir"), "../docs/api/" + name);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[8192];
            for (int n = in.read(buf); n > 0; n = in.read(buf)) {
                out.write(buf, 0, n);
            }
        }
        return new String(out.toByteArray(), "UTF-8");
    }

    /** Text from the heading line up to the next heading of the same or a higher level. */
    private static String section(String doc, String heading) {
        int start = doc.indexOf("\n" + heading + "\n");
        assertTrue("missing heading " + heading, start >= 0);
        int level = heading.indexOf(' ');
        String[] lines = doc.substring(start + 1).split("\n");
        StringBuilder sb = new StringBuilder(lines[0]).append('\n');
        boolean fence = false;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.startsWith("```")) {
                fence = !fence;
            }
            int hashes = 0;
            while (!fence && hashes < line.length() && line.charAt(hashes) == '#') {
                hashes++;
            }
            if (hashes > 0 && hashes <= level && line.length() > hashes && line.charAt(hashes) == ' ') {
                break;
            }
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    /** The single line starting with {@code prefix}. */
    private static String line(String doc, String prefix) {
        String found = null;
        for (String l : doc.split("\n")) {
            if (l.startsWith(prefix)) {
                assertTrue("two lines start with " + prefix, found == null);
                found = l;
            }
        }
        assertNotNull("no line starts with " + prefix, found);
        return found;
    }

    private static List<String> markerLines(String doc) {
        List<String> out = new ArrayList<>();
        for (String l : doc.split("\n")) {
            if (l.contains(MARKER)) {
                out.add(l);
            }
        }
        return out;
    }

    /** Bodies of the fenced blocks opened by {@code info} in {@code text}. */
    private static List<String> blocks(String text, String info) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = null;
        for (String l : text.split("\n")) {
            if (cur == null && l.equals("```" + info)) {
                cur = new StringBuilder();
            } else if (cur != null && l.startsWith("```")) {
                out.add(cur.toString());
                cur = null;
            } else if (cur != null) {
                cur.append(l).append('\n');
            }
        }
        return out;
    }

    // SDS-AC-9; OPS-HTTP-003 AC-1
    @Test
    public void sdsAc9_OPS_HTTP_003_AC1_rawPathRowIsRawPathWithoutQuery() throws IOException {
        String row = line(doc("request.md"), "| `rawPath()`");
        assertFalse(row, row.contains(MARKER));
        assertTrue(row, row.contains("`req.path`"));
        assertTrue(row, row.contains("디코딩 전"));
        assertTrue(row, row.contains("쿼리 문자열 제외"));
        assertTrue(row, row.contains("req.query(...)"));
        assertFalse(row, row.contains("originalUrl"));
    }

    // SDS-AC-9; OPS-HTTP-003 AC-2
    @Test
    public void sdsAc9_OPS_HTTP_003_AC2_attributeTypeMismatchAndNullRemoval() throws IOException {
        String s = section(doc("request.md"), "## 요청 속성");
        assertTrue(s, s.contains("`ClassCastException`"));
        for (String l : s.split("\n")) {
            if (l.contains("ClassCastException")) {
                assertFalse(l, l.contains(MARKER));
            }
        }
        assertTrue("typed null variable", s.contains("타입이 있는 `null` 변수"));
        assertTrue("literal null picks the read overload", s.contains("`null` 리터럴"));
        assertTrue("read overload named", s.contains("`attribute(key, Class<T> type)`"));
        boolean example = false;
        for (String b : blocks(s, "java")) {
            example |= b.contains("= null;") && b.contains("req.attribute(\"user\", ");
        }
        assertTrue("java removal example", example);
        assertFalse("kotlin block", blocks(s, "kotlin").isEmpty());
    }

    // SDS-AC-9; OPS-HTTP-003 AC-8
    @Test
    public void sdsAc9_OPS_HTTP_003_AC8_requestKeepsOtherIssuesMarkers() throws IOException {
        List<String> m = markerLines(doc("request.md"));
        assertEquals(m.toString(), 3, m.size());
        assertTrue(m.get(0), m.get(0).contains("프록시"));
        assertTrue(m.get(1), m.get(1).contains("`req.jsonTree()`/`req.jsonMap()`"));
        assertTrue(m.get(2), m.get(2).startsWith("| `parts()`"));
    }

    // SDS-AC-9; OPS-HTTP-003 AC-3
    @Test
    public void sdsAc9_OPS_HTTP_003_AC3_octetStreamAndStreamClosing() throws IOException {
        String d = doc("response.md");
        String note = line(d, "`send(byte[])`에 `Content-Type`이 없으면");
        assertFalse(note, note.contains(MARKER));
        assertTrue(note, note.contains("`application/octet-stream`"));
        assertTrue(note, note.contains("`stream(InputStream, contentType)`"));
        assertTrue(note, note.contains("성공·실패·`abort`"));
        assertTrue(note, note.contains("닫습니다"));
        String s = section(d, "## 스트림");
        List<String> java = blocks(s, "java");
        assertEquals(1, java.size());
        assertTrue(java.get(0), java.get(0).contains("res.stream("));
        assertFalse("no try-with-resources", java.get(0).contains("try ("));
        List<String> kotlin = blocks(s, "kotlin");
        assertEquals(1, kotlin.size());
        assertTrue(kotlin.get(0), kotlin.get(0).contains("res.stream("));
        assertFalse("no use {}", kotlin.get(0).contains(".use"));
    }

    // SDS-AC-9; OPS-HTTP-003 AC-8
    @Test
    public void sdsAc9_OPS_HTTP_003_AC8_responseKeepsFileAndRedirectMarkers() throws IOException {
        List<String> m = markerLines(doc("response.md"));
        assertEquals(m.toString(), 2, m.size());
        assertTrue(m.get(0), m.get(0).startsWith("| `file(File)`"));
        assertTrue(m.get(1), m.get(1).contains("`res.redirect`"));
    }

    // SDS-AC-9; OPS-HTTP-003 AC-4
    @Test
    public void sdsAc9_OPS_HTTP_003_AC4_routerPolicyDocumented() throws IOException {
        String d = doc("routing.md");
        String s = section(d, "## 라우터 정책");
        boolean java = false;
        for (String b : blocks(s, "java")) {
            java |= b.contains("app.routerPolicy(RouterPolicy.builder()") && b.contains(".build());");
        }
        assertTrue("java example", java);
        assertFalse("kotlin example", blocks(s, "kotlin").isEmpty());
        for (String sw : new String[] {"mergeSlashes", "ignoreTrailingSlash", "caseInsensitive", "enableTrace",
                "enableConnect"}) {
            assertTrue("switch " + sw, s.contains("`" + sw + "(boolean)`"));
        }
        assertTrue(s.contains("`NEW`"));
        assertTrue(s.contains("`listen()`"));
        assertTrue(s.contains("`IllegalStateException`"));
        String rules = section(d, "## 라우팅 규칙 (wave 2 결정)");
        assertFalse(rules, rules.contains("정하지 않았"));
        assertTrue(rules, rules.contains("`app.routerPolicy(RouterPolicy)`"));
        String head = line(d, "자동 HEAD 처리 때 `req.method()`");
        assertTrue(head, head.contains("`HEAD`를 돌려줍니다"));
    }

    // SDS-AC-9; OPS-HTTP-003 AC-7
    @Test
    public void sdsAc9_OPS_HTTP_003_AC7_expressRawPathAndRawTextMapping() throws IOException {
        String d = doc("express-migration.md");
        String original = line(d, "| `req.originalUrl`");
        assertFalse(original, original.contains(MARKER));
        assertTrue(original, original.contains("`req.rawPath()`"));
        assertTrue(original, original.contains("`req.query(...)`"));
        String raw = line(d, "| `req.path` (디코딩 전)");
        assertFalse(raw, raw.contains(MARKER));
        assertTrue(raw, raw.contains("| `req.rawPath()` |"));
        String rawText = line(d, "| `express.raw()`, `express.text()`");
        assertFalse(rawText, rawText.contains(MARKER));
        assertTrue(rawText, rawText.contains("v1에는 원본·텍스트 본문을 읽는 API가 없습니다"));
        int statements = 0;
        for (String l : d.split("\n")) {
            if (l.contains("express.raw()")) {
                assertFalse(l, l.contains(MARKER));
                statements++;
            }
        }
        assertEquals("raw/text stated once", 1, statements);
        String undecided = section(d, "## Express에 있지만 아직 정해지지 않은 기능");
        assertFalse(undecided, undecided.contains("express.raw"));
    }

    // SDS-AC-9; OPS-HTTP-003 AC-8
    @Test
    public void sdsAc9_OPS_HTTP_003_AC8_expressKeepsOtherIssuesMarkers() throws IOException {
        String d = doc("express-migration.md");
        for (String prefix : new String[] {"| `app.set('trust proxy', ...)`", "| `express.Router()`",
                "| `res.redirect(url)`", "| `cors()`"}) {
            assertTrue(prefix, line(d, prefix).contains(MARKER));
        }
    }

    // SDS-AC-9; OPS-HTTP-003 AC-5
    @Test
    public void sdsAc9_OPS_HTTP_003_AC5_appListsRouterPolicy() throws IOException {
        String d = doc("app.md");
        String row = line(d, "| `routerPolicy(RouterPolicy)`");
        assertFalse(row, row.contains(MARKER));
        assertTrue(row, row.contains("(routing.md#라우터-정책)"));
        String s = section(d, "### 라우터 정책");
        assertFalse(s, s.contains(MARKER));
        List<String> java = blocks(s, "java");
        List<String> kotlin = blocks(s, "kotlin");
        assertEquals(1, java.size());
        assertEquals(1, kotlin.size());
        assertTrue(java.get(0), java.get(0).contains("app.routerPolicy("));
        assertTrue(kotlin.get(0), kotlin.get(0).contains("app.routerPolicy("));
    }

    // SDS-AC-9; OPS-HTTP-003 AC-6
    @Test
    public void sdsAc9_OPS_HTTP_003_AC6_defaultsNamesTraceConnectPolicy() throws IOException {
        String row = line(doc("defaults.md"), "| `TRACE`, `CONNECT` 메서드");
        assertFalse(row, row.contains(MARKER));
        assertTrue(row, row.contains("`app.routerPolicy(...)`"));
        assertTrue(row, row.contains("`enableTrace`"));
        assertTrue(row, row.contains("`enableConnect`"));
    }

    // SDS-AC-9; OPS-HTTP-003 AC-8
    @Test
    public void sdsAc9_OPS_HTTP_003_AC8_routingKeepsNoMarker() throws IOException {
        List<String> m = markerLines(doc("routing.md"));
        assertTrue(m.toString(), m.isEmpty());
    }
}
