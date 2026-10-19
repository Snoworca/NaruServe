package com.snoworca.naruserve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/** Wave 4 decisions recorded in docs/api with the wave 5 marker set (OPS-APP-003, successor of OPS-APP-001). */
public class AppDocsTest {

    private static final String MARKER = "(미확정)";

    /** Per file, phrases decided in waves 4 and 5: each appears, and no line holding it carries the marker. */
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
        r.put("defaults.md", Arrays.asList("| 바인딩 주소", "| 프록시 헤더"));
        REMOVED = Collections.unmodifiableMap(r);

        Map<String, List<String>> k = new LinkedHashMap<>();
        k.put("app.md", Arrays.asList("`ws`의 반환 타입", "`ws`에 넘기는 콜백"));
        k.put("express-migration.md", Arrays.asList("| `express.Router()`", "| `cors()`", "| `res.redirect(url)`"));
        k.put("websocket.md", Arrays.asList("Ping 주기", "`101` 응답 전/후"));
        k.put("static.md", Arrays.asList("SPA fallback은", "`/docs/`로 301"));
        k.put("multipart.md", Arrays.asList("| `tempDirectory`"));
        k.put("sse.md", Arrays.asList("heartbeat"));
        KEPT = Collections.unmodifiableMap(k);
    }

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

    // SDS-AC-7; OPS-APP-003 AC-6
    @Test
    public void sdsAc7_OPS_APP_003_AC6_decidedMarkersAreGone() throws IOException {
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

    // SDS-AC-7; OPS-APP-003 AC-6
    @Test
    public void sdsAc7_OPS_APP_003_AC6_laterWaveMarkersAreKept() throws IOException {
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

    // SDS-AC-7; OPS-APP-003 AC-6
    @Test
    public void sdsAc7_OPS_APP_003_AC6_wave4PagesKeepNoMarker() throws IOException {
        for (String f : new String[] {"middleware.md", "lifecycle.md", "logging.md", "routing.md"}) {
            List<String> m = markerLines(doc(f));
            assertEquals(f + ": " + m, 0, m.size());
        }
    }

    // OPS-APP-003 AC-1
    @Test
    public void OPS_APP_003_AC1_builderFactoryServerHandleAndKeeperThread() throws IOException {
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

    // SDS-AC-7; OPS-APP-003 AC-6
    @Test
    public void sdsAc7_OPS_APP_003_AC6_wsMarkersSitOnTheirOwnLines() throws IOException {
        String app = doc("app.md");
        for (String l : markerLines(app)) {
            for (String decided : new String[] {"라우트 미들웨어", "onStart", "onStop", "onError", "daemon"}) {
                assertFalse(l, l.contains(decided));
            }
        }
        assertEquals(1, linesWith(app, "`ws`의 반환 타입").size());
        assertEquals(1, linesWith(app, "`ws`에 넘기는 콜백").size());
    }

    // OPS-APP-003 AC-2
    @Test
    public void OPS_APP_003_AC2_middlewareAndRoutingDecisions() throws IOException {
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

    // OPS-APP-003 AC-7
    @Test
    public void OPS_APP_003_AC7_decisionsCarryAReason() throws IOException {
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

    // OPS-APP-003 AC-3
    @Test
    public void OPS_APP_003_AC3_lifecycleStartGateAndPendingStop() throws IOException {
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

    // OPS-APP-003 AC-4
    @Test
    public void OPS_APP_003_AC4_loggingFormatDefaultsAndClose() throws IOException {
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

    // OPS-APP-003 AC-5
    @Test
    public void OPS_APP_003_AC5_expressMigrationRows() throws IOException {
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
