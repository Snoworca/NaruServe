package com.snoworca.naruserve;

import static com.snoworca.naruserve.HttpDocsTest.doc;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** Routing semantics in docs/api/routing.md with no marker left (OPS-ROUTE-003). */
// @req OPS-REL-008 AC-1 AC-2 AC-3
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
        // OPS-REL-008 AC-1 AC-3: compact accessor reference plus executable examples preserve this extension.
        for (String name : new String[] {"contains", "iterator", "size"}) assertTrue("accessor " + name, s.contains("`" + name + "`"));
        assertTrue(s.contains("불변") && s.contains("remove()"));
        assertTrue("java fence", s.contains("```java"));
        assertTrue("kotlin fence", s.contains("```kotlin"));
        assertTrue("finite-set/all-method boundary", doc("routing.md").contains("Express `app.all`의 모든 토큰을 뜻하지 않습니다"));
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
        // OPS-REL-008 AC-1 AC-3; CON-APP-010 AC-1 AC-2: common HTTP rules and retained strict WS are explicit.
        String d = doc("routing.md");
        for (String value : new String[] {"/*parts", "{/:id}", "RoutingConfigException", "등록 순서", "Route 식별자",
                "Java Pattern", "Matcher.find()", "originalFlags", "mergeParams",
                "HEAD", "OPTIONS", "Allow", "200", "404", "%2F", "%ZZ"})
            assertTrue("common routing fact " + value, d.contains(value));
        assertTrue(doc("request.md").contains("paramOrNull") && doc("request.md").contains("paramValues"));
        String strict = section(d, "## 기존 라우터와 WebSocket");
        for (String value : new String[] {"엄격", "경로", "Origin", "고정 경로 우선", "충돌", "426", "204", "501", "슬래시/NUL", "경로 이탈"})
            assertTrue("retained strict/WS fact " + value, strict.contains(value));
        assertTrue(section(d, "## 자동 응답").contains("등록"));
    }
}
