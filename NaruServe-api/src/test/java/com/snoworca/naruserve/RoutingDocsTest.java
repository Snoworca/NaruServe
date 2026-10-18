package com.snoworca.naruserve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class RoutingDocsTest {

    private static final String MARKER = "(미확정)";

    private static String doc() throws IOException {
        // Module tests run in NaruServe-api; the docs live at the repository root.
        File f = new File(System.getProperty("user.dir"), "../docs/api/routing.md");
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
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            int hashes = 0;
            while (hashes < line.length() && line.charAt(hashes) == '#') {
                hashes++;
            }
            if (hashes > 0 && hashes <= level && line.length() > hashes && line.charAt(hashes) == ' ') {
                break;
            }
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    // SDS-AC-9; OPS-ROUTE-001 AC-2
    @Test
    public void sdsAc9_OPS_ROUTE_001_AC2_accessorsNamedWithJavaKotlinAndExpressNote() throws IOException {
        String s = section(doc(), "### MethodSet 조회");
        for (String name : new String[] {"contains(Method)", "iterator()", "size()"}) {
            assertTrue("accessor " + name, s.contains("`" + name + "`"));
        }
        assertTrue("java fence", s.contains("```java"));
        assertTrue("kotlin fence", s.contains("```kotlin"));
        assertTrue("Express note", s.contains("Express에는 대응하는 API가 없습니다"));
    }

    // SDS-AC-10; OPS-ROUTE-001 AC-3
    @Test
    public void sdsAc10_OPS_ROUTE_001_AC3_wave1MarkersKeptAndNoneAdded() throws IOException {
        List<String> markerLines = new ArrayList<>();
        for (String line : doc().split("\n")) {
            if (line.contains(MARKER)) {
                markerLines.add(line);
            }
        }
        assertEquals(markerLines.toString(), 4, markerLines.size());
        assertTrue(markerLines.get(0), markerLines.get(0).contains("`use(middleware)`"));
        assertTrue(markerLines.get(1), markerLines.get(1).contains("`TRACE`, `CONNECT`"));
        assertTrue(markerLines.get(2), markerLines.get(2).contains("`//` 처리, 끝 슬래시, 대소문자"));
        assertTrue(markerLines.get(3), markerLines.get(3).contains("`req.method()`"));
    }

    // SDS-AC-10; OPS-ROUTE-001 AC-1
    @Test
    public void sdsAc10_OPS_ROUTE_001_AC1_routingRulesSectionPresent() throws IOException {
        String s = section(doc(), "## 라우팅 규칙 (wave 2 결정)");
        String[] required = {
            "엄격", "TRACE", "CONNECT", "Allow", "HEAD", "OPTIONS", "WebSocket", "426",
            "DELETE /users/me", "/a/:", "/a/:id/:id", "/a//b", "`get`", "Connection", "Upgrade",
            "%2F", "%00", "..", "%252e", "%2e"
        };
        for (String r : required) {
            assertTrue("routing rules mention " + r, s.contains(r));
        }
    }
}
