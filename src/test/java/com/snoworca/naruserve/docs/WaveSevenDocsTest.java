package com.snoworca.naruserve.docs;

import static com.snoworca.naruserve.docs.WaveFiveDocsTest.contains;
import static com.snoworca.naruserve.docs.WaveFiveDocsTest.doc;
import static com.snoworca.naruserve.docs.WaveFiveDocsTest.lineWith;
import static com.snoworca.naruserve.docs.WaveFiveDocsTest.linesWith;
import static com.snoworca.naruserve.docs.WaveFiveDocsTest.row;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.WebSocketSession;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.Test;

/** Wave 7 docs/api statements, found by fixed phrases. SDS-AC-n cites the wave 7 SDS named on each case. */
// @req FR-WS-004
// @req OPS-WS-001
public final class WaveSevenDocsTest {

    private static final String MARKER = "(미확정)";

    /** The deferred TinyHttpd gap of the 426 answer (FR-WS-004 AC-3). */
    static final String TINYHTTPD_426_PHRASE =
            "over the TinyHttpd adapter the `426` answer carries `Upgrade: websocket` but not `Connection: Upgrade`";

    static final String HANDLER_PHRASE = "핸들러는 `101` 응답을 보낸 뒤 연결마다 한 번 실행됩니다";
    static final String ORIGIN_DEFAULT_PHRASE = "`Origin`이 없거나 같은 출처인 요청만 허용하고 나머지는 `403`으로 거부합니다";
    static final String ORIGIN_STAR_PHRASE = "`allowedOrigins(\"*\")`는 모든 `Origin`을 허용합니다";
    static final String OVERFLOW_PHRASE = "close `1008`(이유 `send queue overflow`)로 연결을 닫습니다";
    static final String ONJSON_PHRASE = "`onJson`의 JSON 파싱 실패는 `onError`로 전달되고 연결은 열린 채로 남습니다";
    static final String LATE_OPEN_PHRASE = "종료 대기가 끝난 뒤 열린 세션은 close 프레임 없이 끊습니다";
    static final String PONG_PHRASE = "Pong timeout은 Ping 주기와 같습니다";
    static final String PONG_GAP_PHRASE =
            "TinyHttpd의 `WebSocketListener`에 Pong 콜백이 없어 별도 Pong timeout은 지원하지 않습니다";
    static final String HANDSHAKE_PHRASE =
            "handshake는 `headerTimeout`과 `bodyReadTimeout`으로만 제한되고 전용 upgrade 기한은 지원하지 않습니다";

    static final String HEARTBEAT_PHRASE = "아무것도 쓰지 않은 채 heartbeat 주기가 지나면 `: ping` 주석을 보냅니다";
    static final String SSE_OVERFLOW_PHRASE =
            "다른 스레드의 `send`가 송신 큐를 넘치게 하면 스트림을 끝내고 `false`를 돌려줍니다";
    static final String NOTICE_PHRASE = "종료할 때 `event: shutdown`과 `data: shutdown`으로 된 종료 알림을 보냅니다";
    static final String LATE_ONCLOSE_PHRASE = "이미 끝난 뒤 등록한 `onClose`는 즉시 실행됩니다";
    static final String HEAD_SSE_PHRASE = "`HEAD` 요청에는 헤더만 보내고 본문 없이 이미 끝난 클라이언트를 돌려줍니다";
    static final String HANDLER_WRITE_PHRASE =
            "핸들러 스레드가 반환 전에 보낸 이벤트는 소켓에 바로 쓰며 송신 큐 한도를 받지 않습니다";
    static final String HANDLER_ORDER_PHRASE = "그 전에 다른 스레드가 큐에 넣은 이벤트를 먼저 씁니다";

    /** The sse.md decisions of OPS-SSE-001 AC-3, each found verbatim. */
    static final List<String> SSE_PHRASES = Collections.unmodifiableList(Arrays.asList(HEARTBEAT_PHRASE,
            SSE_OVERFLOW_PHRASE, NOTICE_PHRASE, LATE_ONCLOSE_PHRASE, HEAD_SSE_PHRASE, HANDLER_WRITE_PHRASE,
            HANDLER_ORDER_PHRASE));

    /** The SSE API sections of sse.md, each with an Express counterpart and Java and Kotlin examples. */
    static final List<String> SSE_SECTIONS = Collections.unmodifiableList(Arrays.asList(
            "## res.sse()", "## SseClient", "## SseEvent", "## 설정", "## 재접속과 Last-Event-ID"));

    static final String LOW_END_HEADING = "## 저사양 Android 튜닝(수동 후속 작업)";

    /** The WebSocket API sections of websocket.md, each with an Express counterpart and Java and Kotlin examples. */
    static final List<String> API_SECTIONS = Collections.unmodifiableList(Arrays.asList(
            "## 핸들러", "### 보내기", "### 받기", "### 연결 관리", "## 인증과 Origin", "## 설정"));

    /** The text from {@code heading} up to the next heading of any level, skipping fenced code. */
    static String section(String doc, String heading) {
        int start = doc.indexOf("\n" + heading + "\n");
        assertTrue("missing heading " + heading, start >= 0);
        String[] lines = doc.substring(start + 1).split("\n", -1);
        StringBuilder sb = new StringBuilder(lines[0]).append('\n');
        boolean fence = false;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.startsWith("```")) {
                fence = !fence;
            } else if (!fence && line.startsWith("#")) {
                break;
            }
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    /** The text from {@code heading} up to the next level-2 heading ({@code "## "}), skipping fenced code. */
    static String h2Section(String doc, String heading) {
        int start = doc.indexOf("\n" + heading + "\n");
        assertTrue("missing heading " + heading, start >= 0);
        String[] lines = doc.substring(start + 1).split("\n", -1);
        StringBuilder sb = new StringBuilder(lines[0]).append('\n');
        boolean fence = false;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.startsWith("```")) {
                fence = !fence;
            } else if (!fence && line.startsWith("## ")) {
                break;
            }
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private static List<String> markerLines(String doc, String... phrases) {
        List<String> out = new ArrayList<>();
        for (String p : phrases) {
            List<String> lines = linesWith(doc, p);
            assertFalse("no line holds " + p, lines.isEmpty());
            for (String l : lines) {
                if (l.contains(MARKER)) {
                    out.add(l);
                }
            }
        }
        return out;
    }

    // SDS-AC-12 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-3.sds.md; FR-WS-004 AC-3
    @Test
    public void sdsAc12_FR_WS_004_AC3_websocketMdStatesTheTinyHttpd426Gap() throws IOException {
        contains("websocket.md", doc("websocket.md"), TINYHTTPD_426_PHRASE, "`ExchangeResponse`",
                "deferred TinyHttpd limitation");
    }

    // SDS-AC-1 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-8.sds.md; OPS-WS-001 AC-1
    @Test
    public void sdsAc1_OPS_WS_001_AC1_noMarkerOnTheWebSocketLines() throws IOException {
        assertEquals(Collections.<String>emptyList(), linesWith(doc("websocket.md"), MARKER));
        assertEquals(Collections.<String>emptyList(), markerLines(doc("app.md"), "`ws`의 반환 타입", "`ws`에 넘기는 콜백"));
        assertEquals(Collections.<String>emptyList(), markerLines(doc("defaults.md"), "| WebSocket 메시지 |",
                "| WebSocket 송신 큐(연결당) |", "**WebSocket 인증**"));
    }

    // SDS-AC-1 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-8.sds.md; OPS-WS-001 AC-2
    @Test
    public void sdsAc1_OPS_WS_001_AC2_appMdNamesRestAppAndWebSocketHandlerAndEverySessionMethodIsListed()
            throws IOException {
        String app = doc("app.md");
        contains("app.md", lineWith(app, "`ws`의 반환 타입"), "`RestApp`");
        contains("app.md", lineWith(app, "`ws`에 넘기는 콜백"), "`WebSocketHandler`");
        contains("app.md", lineWith(app, "| `webSocket(options -> ...)`"), "websocket.md");
        String ws = doc("websocket.md");
        Set<String> methods = new TreeSet<>();
        for (Method m : WebSocketSession.class.getMethods()) {
            methods.add(m.getName());
        }
        assertTrue(methods.toString(), methods.containsAll(Arrays.asList("id", "sendText", "sendBinary", "sendJson",
                "ping", "close", "isOpen", "onText", "onBinary", "onJson", "onClose", "onError", "attribute")));
        for (String name : methods) {
            assertTrue("websocket.md lists " + name, ws.contains("| `" + name + "("));
        }
    }

    // SDS-AC-2 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-8.sds.md; OPS-WS-001 AC-3
    @Test
    public void sdsAc2_OPS_WS_001_AC3_websocketMdStatesEachDecisionByItsPhrase() throws IOException {
        contains("websocket.md", doc("websocket.md"), HANDLER_PHRASE, ORIGIN_DEFAULT_PHRASE, ORIGIN_STAR_PHRASE,
                OVERFLOW_PHRASE, ONJSON_PHRASE, LATE_OPEN_PHRASE, PONG_GAP_PHRASE, HANDSHAKE_PHRASE,
                TINYHTTPD_426_PHRASE);
    }

    /** The websocket.md settings row of {@code setting}, as cells. */
    private static String[] wsRow(String ws, String setting) {
        String line = lineWith(ws, "| `" + setting + "` |");
        String[] raw = line.split("\\|", -1);
        String[] cells = new String[raw.length - 2];
        for (int i = 1; i < raw.length - 1; i++) {
            cells[i - 1] = raw[i].trim();
        }
        return cells;
    }

    // SDS-AC-3 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-8.sds.md; OPS-WS-001 AC-4
    @Test
    public void sdsAc3_OPS_WS_001_AC4_limitsAreListedInBothFiles() throws IOException {
        String ws = doc("websocket.md");
        // Columns: setting, item, default, maximum, range, answer.
        String[][] expected = {
            {"maxMessageSize", "1 MiB", "16 MiB", "1009"},
            {"sendQueueSize", "1 MiB", "64 MiB", "1008"},
            {"pingInterval", "30초", "1시간", "1001"},
            {"idleTimeout", "5분", "24시간", "1001"},
        };
        for (String[] e : expected) {
            String[] cells = wsRow(ws, e[0]);
            assertEquals(Arrays.toString(cells), e[1], cells[2]);
            assertEquals(Arrays.toString(cells), e[2], cells[3]);
            assertTrue(Arrays.toString(cells), cells[4].startsWith("1"));
            assertTrue(Arrays.toString(cells), cells[5].contains(e[3]));
        }
        assertTrue(wsRow(ws, "pingInterval")[4].startsWith("1초"));
        assertTrue(wsRow(ws, "idleTimeout")[4].startsWith("1초"));
        String d = doc("defaults.md");
        String[] message = row(d, "WebSocket 메시지");
        assertEquals("1 MiB", message[1]);
        assertEquals("16 MiB", message[2]);
        assertEquals("연결 종료(1009)", message[3]);
        String[] queue = row(d, "WebSocket 송신 큐(연결당)");
        assertEquals("1 MiB", queue[1]);
        assertEquals("64 MiB", queue[2]);
        assertEquals("연결 종료(1008)", queue[3]);
        assertFalse(lineWith(d, "| WebSocket 송신 큐(연결당) |"), lineWith(d, "| WebSocket 송신 큐(연결당) |")
                .contains("예외"));
        String[] ping = row(d, "WebSocket Ping 주기");
        assertEquals("30초", ping[1]);
        assertEquals("1시간", ping[2]);
        assertTrue(Arrays.toString(ping), ping[4].contains("최소 1초"));
        String[] idle = row(d, "WebSocket idle timeout");
        assertEquals("5분", idle[1]);
        assertEquals("24시간", idle[2]);
        assertTrue(Arrays.toString(idle), idle[4].contains("최소 1초"));
        for (String f : new String[] {"websocket.md", "defaults.md"}) {
            contains(f, doc(f), PONG_PHRASE, HANDSHAKE_PHRASE);
        }
    }

    // SDS-AC-4 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-8.sds.md; OPS-WS-001 AC-5
    @Test
    public void sdsAc4_OPS_WS_001_AC5_eachApiSectionHasExpressJavaAndKotlin() throws IOException {
        String ws = doc("websocket.md");
        for (String heading : API_SECTIONS) {
            String s = section(ws, heading);
            assertTrue(heading + " names Express", s.contains("Express"));
            assertTrue(heading + " has a java block", s.contains("\n```java\n"));
            assertTrue(heading + " has a kotlin block", s.contains("\n```kotlin\n"));
        }
        String migration = doc("express-migration.md");
        contains("express-migration.md", lineWith(migration, "| `app.ws('/ws'"), "`app.ws(\"/ws\", (req, ws) -> ...)`");
        contains("express-migration.md", lineWith(migration, "| `ws.send(text)`"), "`ws.sendText(text)`");
        contains("express-migration.md", lineWith(migration, "| `ws.on('message'"), "`ws.onText(");
        contains("express-migration.md", lineWith(migration, "| `ws.on('close'"), "`ws.onClose(");
        contains("express-migration.md", lineWith(migration, "| `ws.close(code, reason)`"), "`ws.close(code, reason)`");
    }

    // SDS-AC-4 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-8.sds.md; OPS-WS-001 AC-5:
    // the section reader stops at the next heading of any level
    @Test
    public void sdsAc4_OPS_WS_001_AC5_sectionStopsAtTheNextHeading() {
        String d = "x\n## A\na\n```java\n# not a heading\n```\n### B\nb\n";
        assertEquals("## A\na\n```java\n# not a heading\n```\n", section(d, "## A"));
    }

    // Wave 7 part 13: SDS-AC-n below cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-13.sds.md.

    // SDS-AC-4; PERF-APP-001 AC-3, AC-4: defaults.md states the thread total and the stack of the request-path
    // threads, and the low-end Android section names the three settings as a manual follow-up
    @Test
    public void wave7s13SdsAc4_PERF_APP_001_AC3_AC4_defaultsStatesThreadsStackAndTheLowEndFollowUp()
            throws IOException {
        String d = doc("defaults.md");
        String[] total = row(d, "앱 하나의 스레드 합계");
        assertEquals("452", total[1]);
        String stack = lineWith(d, "| 요청 경로 스레드 stack |");
        contains("defaults.md", stack, "256 KiB", "449", "112.25 MiB", "API 21");
        String low = h2Section(d, LOW_END_HEADING);
        contains("defaults.md", low, "`maxSseStreams`", "`maxUpgradedConnections`", "`sendQueueSize`",
                "수동 후속 작업", "자동");
    }

    // SDS-AC-4: the level-2 section reader keeps ### subsections and fenced ## lines
    @Test
    public void wave7s13SdsAc4_h2SectionStopsOnlyAtTheNextLevel2Heading() {
        String d = "x\n## A\na\n### B\nb\n```java\n## not a heading\n```\n## C\nc\n";
        assertEquals("## A\na\n### B\nb\n```java\n## not a heading\n```\n", h2Section(d, "## A"));
    }

    // SDS-AC-5; OPS-SSE-001 AC-1: no marker on any sse.md line or on the defaults.md SSE send-queue row
    @Test
    public void wave7s13SdsAc5_OPS_SSE_001_AC1_noMarkerInSseMdOrTheSseRow() throws IOException {
        assertEquals(Collections.<String>emptyList(), linesWith(doc("sse.md"), MARKER));
        assertEquals(Collections.<String>emptyList(), markerLines(doc("defaults.md"), "| SSE 송신 큐(연결당) |",
                "| SSE heartbeat 주기 |"));
    }

    // SDS-AC-5; OPS-SSE-001 AC-2: the SseClient table lists every method with the send result, the settings section
    // the two options with their defaults and ranges, and defaults.md the send-queue and heartbeat rows
    @Test
    public void wave7s13SdsAc5_OPS_SSE_001_AC2_clientTableSettingsAndDefaultsRows() throws IOException {
        String sse = doc("sse.md");
        for (String m : new String[] {"send(event, data)", "send(data)", "send(SseEvent)", "close()", "isOpen()",
                "onClose(listener)"}) {
            assertTrue("sse.md lists " + m, sse.contains("| `" + m + "` |"));
        }
        Set<String> methods = new TreeSet<>();
        for (Method m : com.snoworca.naruserve.SseClient.class.getMethods()) {
            methods.add(m.getName());
        }
        for (String name : methods) {
            assertTrue("sse.md lists " + name, sse.contains("| `" + name + "("));
        }
        String client = h2Section(sse, "## SseClient");
        contains("sse.md", client, "`true`", "`false`");
        String settings = h2Section(sse, "## 설정");
        contains("sse.md", settings, "app.sse(o -> o.heartbeatInterval(", ".sendQueueSize(", "| `heartbeatInterval` |",
                "| `sendQueueSize` |", "15초", "1초~1시간", "256 KiB", "1바이트~16 MiB");
        String d = doc("defaults.md");
        String[] queue = row(d, "SSE 송신 큐(연결당)");
        assertEquals("256 KiB", queue[1]);
        assertEquals("16 MiB", queue[2]);
        assertEquals("스트림 종료", queue[3]);
        String[] heartbeat = row(d, "SSE heartbeat 주기");
        assertEquals("15초", heartbeat[1]);
        assertEquals("1시간", heartbeat[2]);
        assertTrue(Arrays.toString(heartbeat), heartbeat[4].contains("heartbeatInterval"));
    }

    // SDS-AC-5; OPS-SSE-001 AC-3: sse.md states each decision by its fixed phrase
    @Test
    public void wave7s13SdsAc5_OPS_SSE_001_AC3_sseMdStatesEachDecisionByItsPhrase() throws IOException {
        String sse = doc("sse.md");
        for (String p : SSE_PHRASES) {
            contains("sse.md", sse, p);
        }
    }

    // SDS-AC-6; OPS-SSE-001 AC-4: each SSE API section has an Express counterpart, a java and a kotlin block
    @Test
    public void wave7s13SdsAc6_OPS_SSE_001_AC4_eachSseSectionHasExpressJavaAndKotlin() throws IOException {
        String sse = doc("sse.md");
        for (String heading : SSE_SECTIONS) {
            String s = h2Section(sse, heading);
            assertTrue(heading + " names Express", s.contains("Express"));
            assertTrue(heading + " has a java block", s.contains("\n```java\n"));
            assertTrue(heading + " has a kotlin block", s.contains("\n```kotlin\n"));
        }
    }

    // SDS-AC-8; OPS-SSE-001 AC-5: the Kotlin progress example that SseDocsKotlinTest runs is the one in sse.md
    @Test
    public void wave7s13SdsAc8_OPS_SSE_001_AC5_sseMdHoldsTheKotlinProgressExample() throws IOException {
        contains("sse.md", h2Section(doc("sse.md"), "## res.sse()"), "```kotlin\napp.get(\"/api/events\") { req, res ->\n"
                + "    val c = res.sse()\n    c.send(\"connected\", \"ready\")\n    c.onClose { }\n}\n```");
    }
}
