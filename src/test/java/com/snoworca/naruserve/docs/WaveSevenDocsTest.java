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
}
