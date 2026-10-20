package com.snoworca.naruserve;

import static com.snoworca.naruserve.HttpDocsTest.doc;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** Routing semantics in docs/api/routing.md with no marker left (OPS-ROUTE-003). */
public class RoutingDocsTest {

    private static final String MARKER = "(미확정)";

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

    // SDS-AC-10; OPS-ROUTE-003 AC-2
    @Test
    public void sdsAc10_OPS_ROUTE_003_AC2_accessorsNamedWithJavaKotlinAndExpressNote() throws IOException {
        String s = section(doc("routing.md"), "### MethodSet 조회");
        for (String name : new String[] {"contains(Method)", "iterator()", "size()"}) {
            assertTrue("accessor " + name, s.contains("`" + name + "`"));
        }
        assertTrue("java fence", s.contains("```java"));
        assertTrue("kotlin fence", s.contains("```kotlin"));
        assertTrue("Express note", s.contains("Express에는 대응하는 API가 없습니다"));
    }

    // SDS-AC-10; OPS-ROUTE-003 AC-3
    @Test
    public void noMarkerRemains() throws IOException {
        List<String> markerLines = new ArrayList<>();
        for (String line : doc("routing.md").split("\n")) {
            if (line.contains(MARKER)) {
                markerLines.add(line);
            }
        }
        assertEquals(markerLines.toString(), 0, markerLines.size());
    }

    // SDS-AC-10; OPS-ROUTE-003 AC-1
    @Test
    public void sdsAc10_OPS_ROUTE_003_AC1_routingRulesSectionPresent() throws IOException {
        String s = section(doc("routing.md"), "## 라우팅 규칙 (wave 2 결정)");
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
