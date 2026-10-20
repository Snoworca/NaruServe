package com.snoworca.naruserve.docs;

import static com.snoworca.naruserve.docs.WaveFiveDocsTest.doc;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
// @req FR-SVL-006
// @req OPS-SVL-001
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

    // Wave 8 part 8 (docs/sds/2026-10-19.naruserve.naruserve-v1-wave-8-8.sds.md): the final servlet.md.

    static final String MARKER = "(미확정)";
    static final String JAKARTA_SENTENCE = "v1에는 `jakarta.servlet` 모듈이 없으며, Servlet 5 이상(`jakarta.servlet`)"
            + " 컨테이너는 지원하지 않습니다.";
    static final String EXPRESS_SENTENCE = "Express에는 서블릿 컨테이너에 앱을 끼워 넣는 기능이 없고, 가장 가까운 것은 호스트 서버에"
            + " 앱을 마운트하는 것입니다.";
    static final String DEVIATION_PHRASE = "\"timeout 때 강제로 끊는다\"는 SSE 약속은 Servlet 어댑터에서 컨테이너의 쓰기 제한"
            + " 시간까지만 지켜집니다";
    static final String SSE_POINTER = "- Servlet 어댑터(`NaruServe-servlet`)에서는 이 강제 종료가 컨테이너의 쓰기 제한 시간에"
            + " 묶입니다. [Servlet 모듈](servlet.md#종료)";
    /** SHA-256 of docs/api/sse.md as wave 7 left it (UTF-8 bytes). */
    static final String WAVE7_SSE_SHA256 = "6941670375a472139ae110a283f2030baaa20606ee05a44b06dd5586cc6d9921";

    /** The text from {@code heading} to the next heading of the same or a higher level. */
    private static String section(String doc, String heading) {
        int start = doc.indexOf("\n" + heading + "\n");
        assertTrue("servlet.md has no section " + heading, start >= 0);
        int end = doc.indexOf("\n## ", start + heading.length() + 2);
        return end < 0 ? doc.substring(start) : doc.substring(start, end);
    }

    private static void has(String where, String text, String... parts) {
        for (String p : parts) {
            assertTrue(where + " lacks: " + p, text.contains(p));
        }
    }

    // OPS-SVL-001 AC-1 SDS-AC-6: no marker; the jakarta sentence says no jakarta.servlet module exists in v1
    @Test
    public void noMarkerAndJakartaSentence() throws IOException {
        String doc = doc("servlet.md");
        assertFalse("servlet.md keeps a marker", doc.contains(MARKER));
        has("servlet.md", doc, JAKARTA_SENTENCE);
        assertFalse("the jakarta module is no promise", doc.contains("별도 모듈로 나눌 예정"));
    }

    // OPS-SVL-001 AC-2 SDS-AC-7: class names, init-parameters with defaults, discovery, examples and Express sentence
    @Test
    public void registrationSection() throws IOException {
        String reg = section(doc("servlet.md"), "## 등록");
        has("## 등록", reg, "`ServletConfigurer`", "`NaruServeInitializer`",
                "| `naruserve.configurer` | 없음 |", "| `naruserve.urlPattern` | `/*` |",
                "| `naruserve.shutdownTimeoutSeconds` | `10` |", "1~300",
                "`META-INF/services/javax.servlet.ServletContainerInitializer`", "`asyncSupported=true`",
                "<context-param>", "<param-name>naruserve.configurer</param-name>",
                "```java", "implements ServletConfigurer", "```kotlin", ": ServletConfigurer", EXPRESS_SENTENCE);
    }

    // OPS-SVL-001 AC-3 SDS-AC-8: one keyword for each fact of the comparison table and the sections
    @Test
    public void sectionKeywords() throws IOException {
        has("servlet.md", doc("servlet.md"),
                "`ServerHandle.port()`는 항상 `0`",                       // port owned by the container
                "`ServerHandle.address()`는 컨테이너의 주소가 아닙니다",     // address is not the container's
                "`ServletContextListener`가 `app.shutdown(timeout, SECONDS)`", // destroy callback runs shutdown
                "원본 `getRequestURI()`에서 컨텍스트 경로와 url-pattern 접두사를 뺀 나머지", // path rule
                "남는 경로가 비면 `/`", "`;name=value` 경로 파라미터도 경로에 남습니다",
                "Servlet `Part` API",                                     // multipart by the core parser
                WEBSOCKET_PHRASE,                                         // WebSocket 501
                "`AsyncContext.setTimeout(0)`", "`naruserve-servlet-sse-<n>`", "`maxSseStreams`(기본 128)",
                "`asyncSupported=true`",                                  // SSE under AsyncContext
                DEVIATION_PHRASE,                                         // the cut deviation
                HEADER_ORDER_PHRASE,                                      // container header order
                "| `OPTIONS *` |",                                        // the OPTIONS * status
                "리플렉션", "`Builder.jsonCodec(...)`",                    // the Gson lookup
                "Jetty 9.4에서만 확인했고 다른 컨테이너는 확인하지 않았습니다", // only Jetty 9.4
                THREAD_TOTAL_PHRASE);                                     // thread-total exception
    }

    // FR-SVL-006 AC-3 SDS-AC-3: the deviation sentence in servlet.md and its pointer line in sse.md
    @Test
    public void cutAtTimeoutDeviation() throws IOException {
        has("servlet.md", section(doc("servlet.md"), "## 종료"), DEVIATION_PHRASE);
        assertTrue("sse.md has no pointer line", Arrays.asList(doc("sse.md").split("\n")).contains(SSE_POINTER));
    }

    // OPS-SVL-001 AC-5 SDS-AC-10: sse.md is its wave 7 text plus the pointer line; servlet.md has a kotlin fence
    @Test
    public void sseDocOnlyGainsPointer() throws Exception {
        String sse = doc("sse.md");
        String withoutPointer = sse.replace(SSE_POINTER + "\n", "");
        assertEquals("exactly one pointer line", sse.length() - SSE_POINTER.length() - 1, withoutPointer.length());
        byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                .digest(withoutPointer.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : hash) {
            hex.append(String.format("%02x", b));
        }
        assertEquals("sse.md changed beyond the pointer line", WAVE7_SSE_SHA256, hex.toString());
        int kotlin = 0;
        for (String line : doc("servlet.md").split("\n")) {
            kotlin += line.trim().equals("```kotlin") ? 1 : 0;
        }
        assertEquals("servlet.md kotlin fences", 1, kotlin);
    }
}
