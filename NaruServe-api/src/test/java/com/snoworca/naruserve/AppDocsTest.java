package com.snoworca.naruserve;

import static com.snoworca.naruserve.HttpDocsTest.doc;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * Wave 4 decisions recorded in docs/api with the wave 7 marker set (OPS-APP-005, successor of OPS-APP-004). The
 * wave7s13 cases cite SDS-AC-n of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-13.sds.md.
 */
public class AppDocsTest {

    private static final String MARKER = "(미확정)";

    /** The app.md shutdown steps of D-446, in order (OPS-APP-005 AC-8). */
    static final List<String> SHUTDOWN_STEPS = Collections.unmodifiableList(Arrays.asList(
            "새 요청과 WebSocket Upgrade는 모두 `503`과 `Connection: close`로 답합니다",
            "연결을 아직 받는 동안 최대 1초, 열린 SSE 스트림에는 종료 알림을 보내고 열린 WebSocket 세션에는 close `1001`을 보냅니다",
            "연결 받기를 멈추고 진행 중인 요청이 끝나기를 timeout까지 기다립니다",
            "timeout까지 열려 있는 연결은 강제로 끊습니다",
            "`onStop` 콜백을 실행합니다"));

    /** The lifecycle.md statements of OPS-APP-005 AC-9. */
    static final List<String> LIFECYCLE_PHRASES = Collections.unmodifiableList(Arrays.asList(
            "WebSocket·SSE 작별 인사는 연결을 아직 받는 동안 최대 1초 동안 진행됩니다",
            "object.md §11.4와 다릅니다",
            "`shutdown(timeout, unit)`은 timeout에 3초를 더한 시간 안에 반환합니다",
            "SSE `onClose` 리스너 안에서 부른 `app.shutdown`도 요청 핸들러에서 부른 것처럼 바로 반환합니다"));

    /** Per file, phrases decided in waves 4 to 7: each appears, and no line holding it carries the marker. */
    static final Map<String, List<String>> REMOVED;

    /** Per file, phrases owned by later waves: each appears, and every line holding it carries the marker. */
    static final Map<String, List<String>> KEPT;

    static {
        Map<String, List<String>> r = new LinkedHashMap<>();
        r.put("app.md", Arrays.asList("`RestApp.builder()`", "`AppFactory`", "라우트 미들웨어 오버로드",
                "`LifecycleCallback`", "`ErrorHandler`", "daemon", "`ServerHandle`은", "LAN"));
        r.put("README.md", Arrays.asList("`RestApp.builder()`"));
        r.put("middleware.md", Arrays.asList("`RouteBuilder.use(middleware)`", "응답도 `next.run()`도 없이",
                "`ErrorHandler`", "`HttpException`", "`UnsupportedMediaTypeException`", "체인", "`404`/`405` 본문"));
        r.put("routing.md", Arrays.asList("`use(middleware)`"));
        r.put("lifecycle.md", Arrays.asList("`registerExternal`", "`AppStartException`"));
        r.put("logging.md", Arrays.asList("rid=<id>"));
        r.put("express-migration.md", Arrays.asList("`auth, admin, h`", "`req.path()`", "`INTERNAL_ERROR`",
                "| 마지막 404 미들웨어", "| `app.set('trust proxy', ...)`", "0.0.0.0", "| `cookie-parser`"));
        r.put("cookie.md", Arrays.asList("| `getMaxAge()`", "`Expires`"));
        r.put("defaults.md", Arrays.asList("| 바인딩 주소", "| 프록시 헤더", "| 심볼릭 링크"));
        r.put("static.md", Arrays.asList("SPA fallback은", "`/docs/`로 301", "`cache(pattern, policy)`",
                "심볼릭 링크(루트 안을"));
        r.put("multipart.md", Arrays.asList("| `tempDirectory`", "무제한 설정 허용 여부"));
        // Settled by wave 7 (OPS-WS-001, OPS-SSE-001, OPS-ENGINE-002); moved here from KEPT.
        r.put("app.md", concat(r.get("app.md"), "`ws`의 반환 타입", "`ws`에 넘기는 콜백"));
        r.put("websocket.md", Arrays.asList("idle timeout", "Ping 주기", "handshake timeout", "`101` 응답 뒤"));
        r.put("sse.md", Arrays.asList("heartbeat", "연결별 송신 큐 기본값"));
        REMOVED = Collections.unmodifiableMap(r);

        Map<String, List<String>> k = new LinkedHashMap<>();
        k.put("express-migration.md", Arrays.asList("| `express.Router()`", "| `cors()`", "| `res.redirect(url)`"));
        KEPT = Collections.unmodifiableMap(k);
    }

    private static List<String> concat(List<String> head, String... tail) {
        List<String> out = new ArrayList<>(head);
        out.addAll(Arrays.asList(tail));
        return out;
    }

    private static List<String> linesWith(String doc, String phrase) {
        List<String> out = new ArrayList<>();
        for (String l : doc.split("\n")) {
            if (l.contains(phrase)) {
                out.add(l);
            }
        }
        return out;
    }

    private static List<String> markerLines(String doc) {
        return linesWith(doc, MARKER);
    }

    /** The paragraph or table row (one line) holding {@code phrase}; fails when absent. */
    private static String lineWith(String doc, String phrase) {
        List<String> found = linesWith(doc, phrase);
        assertFalse("no line holds " + phrase, found.isEmpty());
        return found.get(0);
    }

    // SDS-AC-5; OPS-APP-005 AC-6; CON-APP-009 AC-1
    @Test
    public void sdsAc5_OPS_APP_005_AC6_decidedMarkersAreGone() throws IOException {
        for (Map.Entry<String, List<String>> e : REMOVED.entrySet()) {
            String d = doc(e.getKey());
            for (String phrase : e.getValue()) {
                List<String> lines = linesWith(d, phrase);
                assertFalse(e.getKey() + " lacks " + phrase, lines.isEmpty());
                for (String l : lines) {
                    assertFalse(e.getKey() + ": " + l, l.contains(MARKER));
                }
            }
        }
    }

    // SDS-AC-5; OPS-APP-005 AC-6
    @Test
    public void sdsAc5_OPS_APP_005_AC6_laterWaveMarkersAreKept() throws IOException {
        for (Map.Entry<String, List<String>> e : KEPT.entrySet()) {
            String d = doc(e.getKey());
            for (String phrase : e.getValue()) {
                List<String> lines = linesWith(d, phrase);
                assertFalse(e.getKey() + " lacks " + phrase, lines.isEmpty());
                for (String l : lines) {
                    assertTrue(e.getKey() + ": " + l, l.contains(MARKER));
                }
            }
        }
    }

    // SDS-AC-5; OPS-APP-005 AC-6
    @Test
    public void sdsAc5_OPS_APP_005_AC6_wave4PagesKeepNoMarker() throws IOException {
        for (String f : new String[] {"middleware.md", "lifecycle.md", "logging.md", "routing.md"}) {
            List<String> m = markerLines(doc(f));
            assertEquals(f + ": " + m, 0, m.size());
        }
    }

    // OPS-APP-005 AC-1
    @Test
    public void OPS_APP_005_AC1_builderFactoryServerHandleAndKeeperThread() throws IOException {
        String app = doc("app.md");
        String builder = lineWith(app, "`RestApp.builder()`는");
        for (String m : new String[] {"`httpAdapter(HttpAdapter)`", "`jsonCodec(JsonCodec)`", "`logger(Logger)`",
                "`build()`", "`IllegalStateException`", "`ConsoleLogger`"}) {
            assertTrue(m + " in " + builder, builder.contains(m));
        }
        String factory = lineWith(app, "`com.snoworca.naruserve.spi.AppFactory`");
        assertTrue(factory, factory.contains("`ServiceLoader`"));
        assertTrue(factory, factory.contains("`IllegalStateException`"));
        assertTrue("create() message quoted", app.contains("No com.snoworca.naruserve.spi.AppFactory provider found."
                + " Add the com.snoworca.naruserve:NaruServe module to use RestApp.create(), or assemble the app with"
                + " RestApp.builder().httpAdapter(...).jsonCodec(...).build()"));
        String handle = lineWith(app, "`ServerHandle`은");
        for (String m : new String[] {"`port()`", "`address()`", "`state()`", "`127.0.0.1`"}) {
            assertTrue(m + " in " + handle, handle.contains(m));
        }
        String jvm = lineWith(app, "daemon");
        assertTrue(jvm, jvm.contains("`shutdown"));
        assertTrue(jvm, jvm.contains("`main`"));
        assertTrue(jvm, jvm.contains("keeper"));
        assertTrue(jvm, jvm.contains("TinyHttpd 자체의 스레드는 daemon"));
        assertFalse(jvm, jvm.contains("다음 단계에서"));
        String overload = lineWith(app, "라우트 미들웨어 오버로드");
        assertTrue(overload, overload.contains("`RestApp`"));
        String callbacks = lineWith(app, "`LifecycleCallback`");
        assertTrue(callbacks, callbacks.contains("`ErrorHandler`"));
        assertTrue(callbacks, callbacks.contains("throws Exception"));
        String readme = lineWith(doc("README.md"), "`RestApp.builder()`");
        assertTrue(readme, readme.contains("`httpAdapter`"));
        assertTrue(readme, readme.contains("`jsonCodec`"));
    }

    // SDS-AC-5; wave7s13 SDS-AC-11; OPS-APP-005 AC-6: each of the two ws phrases of app.md sits on exactly one line
    // without a marker
    @Test
    public void wave7s13SdsAc11_OPS_APP_005_AC6_wsPhrasesSitOnOneLineWithoutMarker() throws IOException {
        String app = doc("app.md");
        for (String l : markerLines(app)) {
            for (String decided : new String[] {"라우트 미들웨어", "onStart", "onStop", "onError", "daemon"}) {
                assertFalse(l, l.contains(decided));
            }
        }
        for (String phrase : new String[] {"`ws`의 반환 타입", "`ws`에 넘기는 콜백"}) {
            List<String> lines = linesWith(app, phrase);
            assertEquals(phrase + ": " + lines, 1, lines.size());
            assertFalse(lines.get(0), lines.get(0).contains(MARKER));
        }
    }

    // wave7s13 SDS-AC-11; OPS-APP-005 AC-6: the kept list holds only the three express-migration.md rows, and the
    // wave 7 phrases are in the removed list
    @Test
    public void wave7s13SdsAc11_OPS_APP_005_AC6_keptHoldsOnlyTheThreeExpressRows() {
        Map<String, List<String>> kept = new LinkedHashMap<>();
        kept.put("express-migration.md", Arrays.asList("| `express.Router()`", "| `cors()`", "| `res.redirect(url)`"));
        assertEquals(kept, KEPT);
        assertTrue(REMOVED.get("app.md").containsAll(Arrays.asList("`ws`의 반환 타입", "`ws`에 넘기는 콜백")));
        assertEquals(Arrays.asList("idle timeout", "Ping 주기", "handshake timeout", "`101` 응답 뒤"),
                REMOVED.get("websocket.md"));
        assertEquals(Arrays.asList("heartbeat", "연결별 송신 큐 기본값"), REMOVED.get("sse.md"));
    }

    // wave7s13 SDS-AC-9; OPS-APP-005 AC-8: app.md states the five shutdown steps in order, and the timeout sentence
    // names steps 2 to 4 and keeps that onStop, resource close and logger flush have no time limit
    @Test
    public void wave7s13SdsAc9_OPS_APP_005_AC8_shutdownStepsInOrderAndTheTimeoutSentence() throws IOException {
        String app = doc("app.md");
        int start = app.indexOf("\n## shutdown\n");
        assertTrue("no shutdown section", start >= 0);
        int end = app.indexOf("\n## ", start + 1);
        String section = end < 0 ? app.substring(start) : app.substring(start, end);
        int at = 0;
        for (String step : SHUTDOWN_STEPS) {
            int next = section.indexOf(step, at);
            assertTrue("step out of order or missing: " + step, next > at);
            at = next;
        }
        String timeout = lineWith(app, "timeout은 2~4단계");
        assertFalse(timeout, timeout.contains("2단계("));
        assertTrue(timeout, timeout.contains("최대 1초"));
        assertTrue(timeout, timeout.contains("`onStop`, 리소스 닫기, 로거 flush에는 시간 제한이 없"));
        assertFalse(app.contains("timeout은 2단계("));
    }

    // wave7s13 SDS-AC-10; OPS-APP-005 AC-9: lifecycle.md states the goodbye while accepting and its §11.4
    // deviation, the 3 s margin with its parts and the shutdown from an SSE onClose listener; logging.md documents
    // the WebSocket handshake and close lines and the SSE end line without a marker
    @Test
    public void wave7s13SdsAc10_OPS_APP_005_AC9_lifecycleStatementsAndLoggingLines() throws IOException {
        String lifecycle = doc("lifecycle.md");
        for (String p : LIFECYCLE_PHRASES) {
            assertTrue("lifecycle.md lacks " + p, lifecycle.contains(p));
        }
        String margin = lineWith(lifecycle, "timeout에 3초를 더한 시간");
        for (String part : new String[] {"1.5초", "1초", "0.5초", "시간 제한 없이"}) {
            assertTrue(part + " in " + margin, margin.contains(part));
        }
        String deviation = lineWith(lifecycle, "object.md §11.4와 다릅니다");
        assertTrue(deviation, deviation.contains("`503`"));
        String logging = doc("logging.md");
        for (String phrase : new String[] {"GET /ws 101", "WS <path>", "sse start="}) {
            List<String> lines = linesWith(logging, phrase);
            assertFalse("logging.md lacks " + phrase, lines.isEmpty());
            for (String l : lines) {
                assertFalse(l, l.contains(MARKER));
            }
        }
        assertTrue(logging.contains("`WS <path> <closeCode>[ <n>ms][ rid=<id>]`"));
        assertTrue(logging.contains(" sse start=<epochMillis> cause=<client|app|overflow|shutdown|error>"));
    }

    // OPS-APP-005 AC-2
    @Test
    public void OPS_APP_005_AC2_middlewareAndRoutingDecisions() throws IOException {
        String mw = doc("middleware.md");
        String use = lineWith(mw, "`RouteBuilder.use(middleware)`");
        assertTrue(use, use.contains(".use(") && use.contains("여러"));
        String finish = lineWith(mw, "응답도 `next.run()`도 없이");
        assertTrue(finish, finish.contains("경고"));
        assertTrue(finish, finish.contains("`end()`"));
        String http = lineWith(mw, "`HttpException`");
        assertTrue(http, http.contains("`status()`") && http.contains("`code()`"));
        assertTrue(lineWith(mw, "`UnsupportedMediaTypeException`").contains("415"));
        String chain = lineWith(mw, "체인");
        assertTrue(chain, chain.contains("등록 순서"));
        assertTrue(chain, chain.contains("응답을 시작"));
        for (String code : new String[] {"BAD_REQUEST", "NOT_FOUND", "METHOD_NOT_ALLOWED", "PAYLOAD_TOO_LARGE",
                "UNSUPPORTED_MEDIA_TYPE", "UNPROCESSABLE_ENTITY", "UPGRADE_REQUIRED", "INTERNAL_ERROR",
                "NOT_IMPLEMENTED", "SERVICE_UNAVAILABLE", "HTTP_<status>"}) {
            assertTrue("code " + code, mw.contains("`" + code + "`"));
        }
        assertTrue(lineWith(mw, "`404`/`405` 본문").contains("v1"));
        String stopping = lineWith(mw, "`STOPPING`");
        assertTrue(stopping, stopping.contains("`503`") && stopping.contains("`SERVICE_UNAVAILABLE`"));
        String queue = lineWith(mw, "대기 큐가 가득");
        assertTrue(queue, queue.contains("`503`") && queue.contains("TinyHttpd") && queue.contains("평문"));

        String routing = doc("routing.md");
        String routeUse = lineWith(routing, "`use(middleware)`");
        assertTrue(routeUse, routeUse.contains("여러"));
        String rStopping = lineWith(routing, "`STOPPING`");
        assertTrue(rStopping, rStopping.contains("`503`") && rStopping.contains("표준 오류 형식"));
        String rQueue = lineWith(routing, "대기 큐가 가득");
        assertTrue(rQueue, rQueue.contains("`503`") && rQueue.contains("TinyHttpd") && rQueue.contains("평문"));
    }

    // OPS-APP-005 AC-7; CON-APP-009 AC-1
    @Test
    public void OPS_APP_005_AC7_decisionsCarryAReason() throws IOException {
        String mw = doc("middleware.md");
        String use = lineWith(mw, "가변 인자 오버로드 대신 `RouteBuilder.use`");
        assertTrue(use, use.contains("이유"));
        String routing = lineWith(doc("routing.md"), "가변 인자 오버로드 대신 `RouteBuilder.use`");
        assertTrue(routing, routing.contains("이유"));
        String app = doc("app.md");
        assertTrue(lineWith(app, "`builder()`라는 이름").contains("이유"));
        assertTrue(lineWith(app, "`create()`가 `AppFactory`").contains("이유"));
        assertTrue(lineWith(app, "daemon").contains("이유"));
        assertTrue(lineWith(app, "`LifecycleCallback`").contains("이유"));
        assertTrue(lineWith(mw, "체인").contains("이유"));
        assertTrue(lineWith(mw, "`404`/`405` 본문").contains("이유"));
        assertTrue(lineWith(mw, "응답도 `next.run()`도 없이").contains("이유"));
        String lifecycle = doc("lifecycle.md");
        assertTrue(lineWith(lifecycle, "`registerExternal`").contains("이유"));
        assertTrue(lineWith(lifecycle, "`AppStartException`").contains("이유"));
        assertTrue(lineWith(lifecycle, "멈춤 요청은 그대로").contains("이유"));
        String logging = doc("logging.md");
        assertTrue(lineWith(logging, "rid=<id>").contains("이유"));
        assertTrue(lineWith(logging, "`context.getFilesDir()`").contains("이유"));
        assertTrue(lineWith(doc("express-migration.md"), "| `app.use('/api', mw)`").contains("이유"));
    }

    // OPS-APP-005 AC-3
    @Test
    public void OPS_APP_005_AC3_lifecycleStartGateAndPendingStop() throws IOException {
        String d = doc("lifecycle.md");
        assertTrue(lineWith(d, "`registerExternal`").contains("닫지 않"));
        assertTrue(d.contains("<T> void registerExternal(Class<T> type, T instance);"));
        String start = lineWith(d, "`AppStartException`");
        assertTrue(start, start.contains("checked"));
        String gate = lineWith(d, "| `STARTING`");
        assertTrue(gate, gate.contains("`RUNNING`") && gate.contains("기다"));
        assertTrue(gate, gate.contains("`503`") && gate.contains("`Connection: close`"));
        String pending = lineWith(d, "멈춤 요청은 그대로");
        assertTrue(pending, pending.contains("`STARTING`") && pending.contains("timeout"));
        assertTrue(pending, pending.contains("예외 없이 반환"));
        String nullAttr = lineWith(d, "`(Object) null`");
        assertTrue(nullAttr, nullAttr.contains("`NullPointerException`"));
    }

    // OPS-APP-005 AC-4
    @Test
    public void OPS_APP_005_AC4_loggingFormatDefaultsAndClose() throws IOException {
        String d = doc("logging.md");
        assertTrue(d.contains("`<method> <path> <status>[ aborted][ <n>ms][ in=<i> out=<o>][ rid=<id>]`"));
        String defaults = lineWith(d, "기본값:");
        for (String m : new String[] {"`enabled(true)`", "`level(LogLevel.INFO)`", "`includeRequestId(true)`",
                "`includeDuration(true)`", "`includeBytes(true)`"}) {
            assertTrue(m + " in " + defaults, defaults.contains(m));
        }
        assertTrue(lineWith(d, "`in`/`out`").contains("본문 바이트"));
        assertTrue(lineWith(d, "태그").contains("`NaruServe`"));
        assertTrue(lineWith(d, "`<timestamp> <LEVEL> <message>`").contains("`System.err`"));
        String file = lineWith(d, "`RotatingFileLogger.builder()`");
        for (String m : new String[] {"`./logs`", "5 MiB", "5개", "`naruserve.log`", "`naruserve.log.1`"}) {
            assertTrue(m + " in " + file, file.contains(m));
        }
        assertTrue(d.contains("`context.getFilesDir()`"));
        String close = lineWith(d, "`flush()`");
        assertTrue(close, close.contains("`close()`") && close.contains("`shutdown"));
        assertTrue(lineWith(d, "여러 앱이").contains("`close()`"));
    }

    // OPS-APP-005 AC-5
    @Test
    public void OPS_APP_005_AC5_expressMigrationRows() throws IOException {
        String d = doc("express-migration.md");
        String route = lineWith(d, "| `app.get('/x', auth, h)`");
        assertFalse(route, route.contains(MARKER));
        assertTrue(route, route.contains(".use(auth).use(admin).get(h)"));
        String prefix = lineWith(d, "| `app.use('/api', mw)`");
        assertFalse(prefix, prefix.contains(MARKER));
        assertTrue(prefix, prefix.contains("전체 경로"));
        String errors = lineWith(d, "`500`의 `code`");
        assertFalse(errors, errors.contains(MARKER));
        assertTrue(errors, errors.contains("`INTERNAL_ERROR`"));
        String notFound = lineWith(d, "| 마지막 404 미들웨어");
        assertTrue(notFound, notFound.contains("v1"));
    }
}
