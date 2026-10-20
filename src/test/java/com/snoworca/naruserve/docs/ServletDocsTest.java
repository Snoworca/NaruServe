package com.snoworca.naruserve.docs;

import static com.snoworca.naruserve.docs.WaveFiveDocsTest.doc;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/** docs/api/servlet.md statements, found by fixed phrases; one method per documented fact. */
// @req FR-SVL-002
// @req FR-SVL-003
public final class ServletDocsTest {

    /** The raw request-line labels of FR-SVL-002 AC-5; ServletRawTargetTest keeps the same labels in its CASES. */
    static final List<String> RAW_CASES = Arrays.asList("GET /a%zz", "GET /a%2", "GET /a%C3%28", "GET /a%2Fb",
            "GET /a%00b", "GET /a<NUL>b", "GET /./a", "GET /../a", "GET /a/../b", "GET /%2e/a", "GET /%2e%2e/a",
            "GET /a/%2e%2e/b");

    static final String OPTIONS_STAR = "OPTIONS *";

    static final String HEADER_ORDER_PHRASE = "응답 헤더가 전송되는 순서는 컨테이너가 정합니다";

    private static int status(String doc, String label) {
        Matcher m = Pattern.compile("(?m)^\\| `" + Pattern.quote(label) + "` \\|.*?\\| (\\d{3}) \\|").matcher(doc);
        assertTrue("servlet.md has no observed-status row for " + label, m.find());
        return Integer.parseInt(m.group(1));
    }

    // SDS-AC-5; FR-SVL-002 AC-5
    @Test
    public void sdsAc5_FR_SVL_002_AC5_observedStatuses() throws IOException {
        String doc = doc("servlet.md");
        for (String label : RAW_CASES) {
            int s = status(doc, label);
            assertTrue(label + " -> " + s, s == 400 || s == 404);
        }
        status(doc, OPTIONS_STAR);
    }

    // SDS-AC-11; FR-SVL-003 AC-5
    @Test
    public void sdsAc11_FR_SVL_003_AC5_headerOrderSentence() throws IOException {
        String doc = doc("servlet.md");
        assertTrue(doc.contains(HEADER_ORDER_PHRASE));
    }
}
