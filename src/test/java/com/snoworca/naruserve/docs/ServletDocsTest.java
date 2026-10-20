package com.snoworca.naruserve.docs;

import static com.snoworca.naruserve.docs.WaveFiveDocsTest.doc;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.ServerOptions;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/** docs/api/servlet.md statements, found by fixed phrases; one method per documented fact. */
// @req FR-SVL-002
// @req FR-SVL-003
// @req FR-SVL-001
// @req FR-SVL-008
// @req FR-SVL-007
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

    static final String THREAD_TOTAL_PHRASE = "내장 엔진의 스레드 총량 451개(D-416)는 Servlet 어댑터에 적용되지 않습니다";
    static final String THREAD_SOURCE_PHRASE = "컨테이너의 스레드에 SSE 스레드 최대 `maxSseStreams`개를 더한 것";
    static final String IGNORED_PREFIX = "- 무시하는 `ServerOptions` 설정:";
    static final String APPLIED_PREFIX = "- 적용하는 설정:";
    static final String LAN_PHRASE = "LAN 경고와 `ServerHandle.address()`는 설정한 `bindAddress` 값을 말할 뿐 컨테이너 커넥터의 주소가"
            + " 아닙니다";

    /** The ServerOptions settings FR-SVL-008 names as ignored on the Servlet adapter. */
    static final Set<String> IGNORED = new LinkedHashSet<>(Arrays.asList("bindAddress", "allowedHosts",
            "maxHeaderSize", "maxHeaderCount", "maxRequestLineLength", "headerTimeout", "bodyReadTimeout",
            "writeTimeout", "minBodyRate", "maxWorkers", "maxConnections", "maxUpgradedConnections",
            "maxConnectionsPerAddress"));

    /** The settings FR-SVL-008 names as applied; {@code app.} marks a RestApp method. */
    static final Set<String> APPLIED = new LinkedHashSet<>(Arrays.asList("jsonLimit", "formLimit",
            "app.multipart", "maxSseStreams", "app.sse", "trustProxy"));

    /** The backticked names of the one line of {@code doc} that starts with {@code prefix}. */
    private static Set<String> listed(String doc, String prefix) {
        Set<String> out = new LinkedHashSet<>();
        for (String line : doc.split("\n")) {
            if (line.startsWith(prefix)) {
                Matcher m = Pattern.compile("`([^`]+)`").matcher(line.substring(prefix.length()));
                while (m.find()) {
                    out.add(m.group(1));
                }
                return out;
            }
        }
        throw new AssertionError("servlet.md has no line starting with " + prefix);
    }

    private static boolean hasMethod(Class<?> c, String name) {
        for (Method m : c.getMethods()) {
            if (m.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    // SDS-AC-8; FR-SVL-001 AC-8
    @Test
    public void threadTotalDeviation() throws IOException {
        String doc = doc("servlet.md");
        assertTrue(doc.contains(THREAD_TOTAL_PHRASE));
        assertTrue(doc.contains(THREAD_SOURCE_PHRASE));
    }

    // SDS-AC-12; FR-SVL-008 AC-4
    @Test
    public void ignoredAndAppliedOptions() throws IOException {
        String doc = doc("servlet.md");
        Set<String> ignored = listed(doc, IGNORED_PREFIX);
        assertEquals(IGNORED, ignored);
        for (String name : ignored) {
            assertTrue(name + " on ServerOptions", hasMethod(ServerOptions.class, name));
        }
        Set<String> applied = listed(doc, APPLIED_PREFIX);
        assertEquals(APPLIED, applied);
        for (String name : applied) {
            String bare = name.startsWith("app.") ? name.substring(4) : name;
            assertTrue(name + " on ServerOptions or RestApp",
                    hasMethod(ServerOptions.class, bare) || hasMethod(RestApp.class, bare));
        }
    }

    // SDS-AC-13; FR-SVL-008 AC-5
    @Test
    public void lanWarningSentence() throws IOException {
        assertTrue(doc("servlet.md").contains(LAN_PHRASE));
    }

    static final String WEBSOCKET_PHRASE = "Servlet 어댑터는 v1에서 WebSocket을 지원하지 않습니다. `app.ws` 라우트는 이 어댑터에서"
            + " `501`을 답하며, WebSocket은 내장 엔진(`app.listen`)이 제공합니다";

    // SDS-AC-14; FR-SVL-007 AC-4
    @Test
    public void websocketNotSupportedSentence() throws IOException {
        assertTrue(doc("servlet.md").contains(WEBSOCKET_PHRASE));
    }
}
