package com.snoworca.naruserve.docs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.gson.Gson;
import com.snoworca.naruserve.Configurer;
import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.ServerOptions;
import com.snoworca.naruserve.gson.GsonJsonCodec;
import com.snoworca.naruserve.spi.ExchangeHandler;
import com.snoworca.naruserve.spi.HttpExchange;
import com.snoworca.naruserve.spi.ServerSettings;
import com.snoworca.naruserve.spi.StreamDrain;
import com.snoworca.naruserve.spi.UpgradeHandler;
import com.snoworca.naruserve.testing.SettingsCapturingAdapter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/** Wave 5 docs/api decisions: defaults.md against the code, settled markers gone, module coordinates. */
public final class WaveFiveDocsTest {

    private static final String MARKER = "(미확정)";

    static String doc(String name) throws IOException {
        String root = System.getProperty("naruserve.rootDir");
        assertNotNull("naruserve.rootDir system property", root);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = new FileInputStream(new File(root, "docs/api/" + name))) {
            byte[] buf = new byte[8192];
            for (int n = in.read(buf); n > 0; n = in.read(buf)) {
                out.write(buf, 0, n);
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    static List<String> linesWith(String doc, String phrase) {
        List<String> out = new ArrayList<>();
        for (String l : doc.split("\n")) {
            if (l.contains(phrase)) {
                out.add(l);
            }
        }
        return out;
    }

    static String lineWith(String doc, String phrase) {
        List<String> found = linesWith(doc, phrase);
        assertFalse("no line holds " + phrase, found.isEmpty());
        return found.get(0);
    }

    /** Every line holding {@code phrase} exists and lacks the marker. */
    private static void settled(String file, String phrase) throws IOException {
        List<String> lines = linesWith(doc(file), phrase);
        assertFalse(file + " lacks " + phrase, lines.isEmpty());
        for (String l : lines) {
            assertFalse(file + ": " + l, l.contains(MARKER));
        }
    }

    /** Every line holding {@code phrase} exists and keeps the marker of a later wave. */
    private static void kept(String file, String phrase) throws IOException {
        List<String> lines = linesWith(doc(file), phrase);
        assertFalse(file + " lacks " + phrase, lines.isEmpty());
        for (String l : lines) {
            assertTrue(file + ": " + l, l.contains(MARKER));
        }
    }

    static void contains(String where, String text, String... parts) {
        for (String p : parts) {
            assertTrue(p + " in " + where + ": " + text, text.contains(p));
        }
    }

    // --- defaults.md table parsing ---

    private static final Pattern VALUE = Pattern.compile("([0-9]+)\\s*(KiB|MiB|GiB|B/s|B|초|시간)?");

    /** A cell such as {@code 16 KiB}, {@code 8190 B}, {@code 10초} or {@code 1시간} in bytes, milliseconds or units. */
    static long value(String cell) {
        Matcher m = VALUE.matcher(cell.trim());
        assertTrue("not a value: " + cell, m.matches());
        long n = Long.parseLong(m.group(1));
        String unit = m.group(2) == null ? "" : m.group(2);
        switch (unit) {
            case "KiB":
                return n * 1024L;
            case "MiB":
                return n * 1024L * 1024L;
            case "GiB":
                return n * 1024L * 1024L * 1024L;
            case "초":
                return n * 1000L;
            case "시간":
                return n * 3600000L;
            default:
                return n;
        }
    }

    /** The cells of the defaults.md table row whose first cell is {@code label}. */
    static String[] row(String defaults, String label) {
        String[] cells = cells(lineWith(defaults, "| " + label + " |"));
        assertEquals(label, cells[0]);
        return cells;
    }

    /** The trimmed cells between the outer pipes of one markdown table line. */
    static String[] cells(String line) {
        String[] raw = line.split("\\|", -1);
        String[] cells = new String[raw.length - 2];
        for (int i = 1; i < raw.length - 1; i++) {
            cells[i - 1] = raw[i].trim();
        }
        return cells;
    }

    static long dflt(String defaults, String label) {
        return value(row(defaults, label)[1]);
    }

    static long max(String defaults, String label) {
        return value(row(defaults, label)[2]);
    }

    /** A POST /j exchange with a JSON body and a declared Content-Length; records the committed status. */
    private static final class JsonExchange implements HttpExchange {
        final byte[] body;
        int status = -1;

        JsonExchange(byte[] body) {
            this.body = body;
        }

        @Override
        public String methodToken() {
            return "POST";
        }

        @Override
        public String rawPath() {
            return "/j";
        }

        @Override
        public String rawQuery() {
            return null;
        }

        @Override
        public String httpVersion() {
            return "HTTP/1.1";
        }

        @Override
        public List<Map.Entry<String, String>> headers() {
            return Arrays.<Map.Entry<String, String>>asList(
                    new AbstractMap.SimpleImmutableEntry<>("Content-Type", "application/json"),
                    new AbstractMap.SimpleImmutableEntry<>("Content-Length", String.valueOf(body.length)));
        }

        @Override
        public InputStream requestBody() {
            return new ByteArrayInputStream(body);
        }

        @Override
        public String remoteAddress() {
            return "127.0.0.1";
        }

        @Override
        public boolean isSecure() {
            return false;
        }

        @Override
        public OutputStream commit(int s, List<Map.Entry<String, String>> h, long contentLength) {
            status = s;
            return new ByteArrayOutputStream();
        }

        @Override
        public void finish() {
        }

        @Override
        public void abort() {
        }

        @Override
        public void sendFile(int s, List<Map.Entry<String, String>> h, File file, long offset, long length) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void upgrade(List<Map.Entry<String, String>> h, UpgradeHandler u) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void eventStream(int s, List<Map.Entry<String, String>> h, StreamDrain drain) {
            throw new UnsupportedOperationException();
        }
    }

    private static RestApp app(SettingsCapturingAdapter adapter) {
        return RestApp.builder().httpAdapter(adapter).jsonCodec(new GsonJsonCodec()).build();
    }

    /** {@code set} accepts {@code max} and rejects {@code max + 1} with IllegalArgumentException. */
    private static void bounded(String name, Configurer<ServerOptions> atMax, Configurer<ServerOptions> aboveMax)
            throws Exception {
        app(new SettingsCapturingAdapter()).server(atMax);
        try {
            app(new SettingsCapturingAdapter()).server(aboveMax);
            fail(name + " accepted a value above its documented maximum");
        } catch (IllegalArgumentException expected) {
            // documented maximum + 1 is out of range
        }
    }

    /** JSON {@code null} padded with spaces to {@code length} bytes. */
    private static byte[] nullBody(int length) {
        byte[] b = new byte[length];
        Arrays.fill(b, (byte) ' ');
        System.arraycopy("null".getBytes(StandardCharsets.US_ASCII), 0, b, 0, 4);
        return b;
    }

    private static String nested(int depth) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            sb.append('[');
        }
        for (int i = 0; i < depth; i++) {
            sb.append(']');
        }
        return sb.toString();
    }

    private static boolean decodes(GsonJsonCodec codec, int depth) {
        try {
            codec.decode(new StringReader(nested(depth)), Object.class);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    // SDS-AC-1; OPS-ENGINE-002 AC-6
    @Test
    public void defaultsMatchCode() throws Exception {
        String d = doc("defaults.md");
        SettingsCapturingAdapter adapter = new SettingsCapturingAdapter();
        RestApp app = app(adapter);
        app.post("/j", (req, res) -> {
            assertNull(req.body(Object.class));
            res.status(204).end();
        });
        app.listen(0);
        try {
            ServerSettings s = adapter.settings;
            assertNotNull("listen() bound through bind(ServerSettings, ExchangeHandler)", s);
            assertEquals(dflt(d, "HTTP 헤더 전체"), s.maxHeaderSize());
            assertEquals(dflt(d, "헤더 개수"), s.maxHeaderCount());
            assertEquals(dflt(d, "요청 줄 길이"), s.maxRequestLineLength());
            // The TinyHttpd adapter sizes inputBufferBytes as max(8192, maxRequestLineLength + 2); one line is that - 2.
            assertEquals(dflt(d, "헤더 한 줄"), Math.max(8192, s.maxRequestLineLength() + 2) - 2);
            assertEquals(dflt(d, "헤더 수신 시간"), s.headerTimeoutMillis());
            assertEquals(dflt(d, "본문 읽기 timeout"), s.bodyReadTimeoutMillis());
            assertEquals(dflt(d, "쓰기 timeout"), s.writeTimeoutMillis());
            assertEquals(dflt(d, "최소 본문 전송 속도"), s.minBodyRate());
            assertEquals(dflt(d, "HTTP 작업 스레드"), s.maxWorkers());
            assertEquals(dflt(d, "동시 SSE 스트림"), s.maxSseStreams());
            assertEquals(dflt(d, "열린 연결"), s.maxConnections());
            assertEquals(dflt(d, "대기 큐"), s.maxConnections() - s.maxWorkers() - s.maxSseStreams());
            assertEquals(dflt(d, "업그레이드 연결"), s.maxUpgradedConnections());
            assertEquals(dflt(d, "주소 하나의 연결"), s.maxConnectionsPerAddress());
            assertTrue(lineWith(d, "| 바인딩 주소 |").contains("`" + s.bindAddress() + "`"));

            int limit = (int) dflt(d, "JSON 요청 본문");
            JsonExchange atLimit = new JsonExchange(nullBody(limit));
            adapter.handler.handle(atLimit);
            assertEquals("a body of exactly the documented JSON limit is read", 204, atLimit.status);
            JsonExchange over = new JsonExchange(nullBody(limit + 1));
            adapter.handler.handle(over);
            assertEquals("one byte over the documented JSON limit", 413, over.status);
        } finally {
            app.shutdown(1, TimeUnit.SECONDS);
        }

        long jsonMax = max(d, "JSON 요청 본문");
        app(new SettingsCapturingAdapter()).jsonLimit(jsonMax);
        try {
            app(new SettingsCapturingAdapter()).jsonLimit(jsonMax + 1);
            fail("jsonLimit accepted a value above its documented maximum");
        } catch (IllegalArgumentException expected) {
            assertEquals("jsonLimit must be in 1..1073741824 bytes: " + (jsonMax + 1), expected.getMessage());
        }

        int depth = (int) dflt(d, "JSON 중첩 깊이");
        Gson gson = GsonJsonCodec.defaultGson();
        for (GsonJsonCodec c : new GsonJsonCodec[] {new GsonJsonCodec(), new GsonJsonCodec(gson)}) {
            assertTrue(decodes(c, depth));
            assertFalse(decodes(c, depth + 1));
        }
        int depthMax = (int) max(d, "JSON 중첩 깊이");
        assertTrue(decodes(new GsonJsonCodec(gson, depthMax), depthMax));
        try {
            new GsonJsonCodec(gson, depthMax + 1);
            fail("GsonJsonCodec accepted a depth above its documented maximum");
        } catch (IllegalArgumentException expected) {
            // documented maximum + 1 is out of range
        }

        final int header = (int) max(d, "HTTP 헤더 전체");
        bounded("maxHeaderSize", o -> o.maxHeaderSize(header), o -> o.maxHeaderSize(header + 1));
        final int count = (int) max(d, "헤더 개수");
        bounded("maxHeaderCount", o -> o.maxHeaderCount(count), o -> o.maxHeaderCount(count + 1));
        final int line = (int) max(d, "요청 줄 길이");
        bounded("maxRequestLineLength", o -> o.maxRequestLineLength(line),
                o -> o.maxRequestLineLength(line + 1));
        final long ht = max(d, "헤더 수신 시간");
        bounded("headerTimeout", o -> o.headerTimeout(ht, TimeUnit.MILLISECONDS),
                o -> o.headerTimeout(ht + 1, TimeUnit.MILLISECONDS));
        final long bt = max(d, "본문 읽기 timeout");
        bounded("bodyReadTimeout", o -> o.bodyReadTimeout(bt, TimeUnit.MILLISECONDS),
                o -> o.bodyReadTimeout(bt + 1, TimeUnit.MILLISECONDS));
        final long wt = max(d, "쓰기 timeout");
        bounded("writeTimeout", o -> o.writeTimeout(wt, TimeUnit.MILLISECONDS),
                o -> o.writeTimeout(wt + 1, TimeUnit.MILLISECONDS));
        final int rate = (int) max(d, "최소 본문 전송 속도");
        bounded("minBodyRate", o -> o.minBodyRate(rate), o -> o.minBodyRate(rate + 1));
        final int workers = (int) max(d, "HTTP 작업 스레드");
        bounded("maxWorkers", o -> o.maxWorkers(workers), o -> o.maxWorkers(workers + 1));
        final int sse = (int) max(d, "동시 SSE 스트림");
        bounded("maxSseStreams", o -> o.maxSseStreams(sse), o -> o.maxSseStreams(sse + 1));
        final int conns = (int) max(d, "열린 연결");
        bounded("maxConnections", o -> o.maxConnections(conns), o -> o.maxConnections(conns + 1));
        final int up = (int) max(d, "업그레이드 연결");
        bounded("maxUpgradedConnections", o -> o.maxUpgradedConnections(up),
                o -> o.maxUpgradedConnections(up + 1));
        final int per = (int) max(d, "주소 하나의 연결");
        bounded("maxConnectionsPerAddress", o -> o.maxConnectionsPerAddress(per),
                o -> o.maxConnectionsPerAddress(per + 1));
    }

    // OPS-ENGINE-002 AC-2 — the documented maxConnections floor is checked by listen()
    @Test
    public void maxConnectionsBelowWorkersPlusSseFailsListen() throws IOException {
        assertTrue(lineWith(doc("defaults.md"), "| 열린 연결 |").contains("`IllegalStateException`"));
        RestApp app = app(new SettingsCapturingAdapter());
        app.server(o -> o.maxConnections(100));
        try {
            app.listen(0);
            fail("listen() accepted maxConnections below maxWorkers + maxSseStreams");
        } catch (IllegalStateException expected) {
            // documented in defaults.md and app.md
        }
    }

    // SDS-AC-1; OPS-ENGINE-002 AC-6
    @Test
    public void valueCellsParse() {
        assertEquals(16384, value("16 KiB"));
        assertEquals(1073741824L, value("1 GiB"));
        assertEquals(8190, value("8190 B"));
        assertEquals(10000, value("10초"));
        assertEquals(3600000, value("1시간"));
        assertEquals(240, value("240 B/s"));
        assertEquals(64, value("64"));
    }

    // SDS-AC-10 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-8.sds.md; OPS-ENGINE-002 AC-7
    @Test
    public void settledMarkerLinesAreGoneAndLaterMarkersKept() throws IOException {
        for (String label : new String[] {"| JSON 요청 본문 |", "| JSON 중첩 깊이 |", "| HTTP 헤더 전체 |",
                "| 헤더 한 줄 |", "| 헤더 개수 |", "| 요청 줄 길이 |", "| 헤더 수신 시간 |", "| 본문 읽기 timeout |",
                "| 쓰기 timeout |", "| 최소 본문 전송 속도 |", "| HTTP 작업 스레드 |", "| 동시 SSE 스트림 |",
                "| 열린 연결 |", "| 대기 큐 |", "| 업그레이드 연결 |", "| 주소 하나의 연결 |", "| 바인딩 주소 |",
                "| 프록시 헤더", "무제한", "`headerTimeout`", "`maxSseStreams`", "`maxUpgradedConnections`"}) {
            settled("defaults.md", label);
        }
        String d = doc("defaults.md");
        assertTrue(linesWith(d, "최종 확정 전").isEmpty());
        String limits = lineWith(d, "`headerTimeout`, `bodyReadTimeout`");
        contains("defaults.md", limits, "`writeTimeout`", "`maxHeaderCount`", "`maxSseStreams`",
                "`maxUpgradedConnections`", "대기 큐");
        settled("sse.md", "`maxSseStreams`");
        settled("websocket.md", "`maxUpgradedConnections`");
        settled("websocket.md", "Ping 주기");
        assertFalse(lineWith(doc("websocket.md"), "Ping 주기").contains("최대 연결 수"));
        settled("express-migration.md", "| `app.set('trust proxy', ...)`");
        settled("express-migration.md", "`bindAddress(\"0.0.0.0\")`");
        String undecided = doc("express-migration.md")
                .substring(doc("express-migration.md").indexOf("## Express에 있지만 아직 정해지지 않은 기능"));
        assertFalse(undecided, undecided.contains("trust proxy"));
        assertFalse(undecided, undecided.contains("LAN"));
        // Wave 7 settles the WebSocket and SSE rows; the wave 8 markers stay.
        settled("defaults.md", "| WebSocket 메시지 |");
        settled("defaults.md", "| SSE 송신 큐(연결당) |");
        settled("sse.md", "heartbeat");
        settled("express-migration.md", "| `express.Router()`");
        settled("express-migration.md", "| `cors()`");
    }

    // SDS-AC-9 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-8-12.sds.md; OPS-REL-006 AC-3: TLS 리스너 line
    // SDS-AC-2; OPS-ENGINE-002 AC-2, AC-3
    @Test
    public void defaultsStatesNoUnlimitedBindProxyRejectionsAndTls() throws IOException {
        String d = doc("defaults.md");
        contains("defaults.md", lineWith(d, "무제한"), "`IllegalArgumentException`");
        contains("defaults.md", row(d, "HTTP 헤더 전체")[3], "`431`");
        contains("defaults.md", row(d, "헤더 한 줄")[4], "`inputBufferBytes - 2`", "`maxHeaderSize`");
        contains("defaults.md", row(d, "요청 줄 길이")[3], "`414`");
        contains("defaults.md", row(d, "최소 본문 전송 속도")[3], "`408`");
        String bind = lineWith(d, "| 바인딩 주소 |");
        contains("defaults.md", bind, "`127.0.0.1`", "`bindAddress(\"0.0.0.0\")`", "경고", "인증");
        contains("defaults.md", lineWith(d, "| 프록시 헤더"), "`app.trustProxy(true)`");
        String rejections = lineWith(d, "`onError`");
        contains("defaults.md", rejections, "`400`", "`408`", "`414`", "`417`", "`431`", "`501`", "`505`", "`503`",
                "평문", "접근 로그", "오류 JSON");
        contains("defaults.md", lineWith(d, "`421`"), "표준 오류");
        contains("defaults.md", lineWith(d, "TLS 리스너"), "v1");
    }

    // SDS-AC-3; OPS-ENGINE-002 AC-5
    @Test
    public void moduleCoordinates() throws IOException {
        Pattern bootstrap = Pattern.compile("com\\.snoworca\\.naruserve:naruserve(?![-\\w])");
        for (String f : new String[] {"README.md", "app.md", "json.md"}) {
            String d = doc(f);
            assertTrue(f + " names com.snoworca.naruserve:naruserve", bootstrap.matcher(d).find());
            assertTrue(f + " names com.snoworca.naruserve:naruserve-gson",
                    d.contains("com.snoworca.naruserve:naruserve-gson"));
        }
    }

    // OPS-ENGINE-002 AC-1, AC-4
    @Test
    public void appAndRequestNameServerTrustProxyAndKeeper() throws IOException {
        String app = doc("app.md");
        for (String setter : new String[] {"bindAddress", "allowedHosts", "maxHeaderSize", "maxHeaderCount",
                "maxRequestLineLength", "headerTimeout", "bodyReadTimeout", "writeTimeout", "minBodyRate",
                "maxWorkers", "maxSseStreams", "maxConnections", "maxUpgradedConnections",
                "maxConnectionsPerAddress"}) {
            assertTrue("app.md documents " + setter, app.contains("`" + setter + "("));
        }
        assertTrue(app.contains("options.bindAddress(\"0.0.0.0\").allowedHosts(\"myphone.local\")"));
        settled("app.md", "`app.trustProxy(true)`");
        settled("app.md", "LAN");
        contains("app.md", lineWith(app, "keeper"), "`main`", "daemon", "TinyHttpd");
        assertTrue(linesWith(app, "서버 스레드는 daemon이 아니며").isEmpty());
        settled("request.md", "`app.trustProxy(true)`");
    }

    // OPS-JSON-001 AC-1, AC-2
    @Test
    public void jsonAndRequestNameLimitsAndNullBody() throws IOException {
        String json = doc("json.md");
        settled("json.md", "`app.jsonLimit(bytes)`");
        settled("json.md", "`new GsonJsonCodec(gson, maxDepth)`");
        contains("json.md", lineWith(json, "`app.jsonLimit(bytes)`"), "1 MiB", "1 GiB");
        contains("json.md", lineWith(json, "`new GsonJsonCodec(gson, maxDepth)`"), "64", "255");
        contains("json.md", lineWith(json, "뒤에 다른 데이터"), "`400`", "빈 본문");
        String request = doc("request.md");
        contains("request.md", lineWith(request, "JSON `null`"), "`null`");
        settled("request.md", "`jsonTree()`");
        contains("request.md", lineWith(request, "`jsonTree()`"), "`req.body(Map.class)`", "이유");
        contains("request.md", lineWith(request, "| `Content-Type`이 JSON이 아님"), "`415`");
    }

    // SDS-AC-6; OPS-COOKIE-001 AC-1, AC-2, AC-3, AC-4, AC-5
    @Test
    public void cookieDocsHaveNoMarker() throws IOException {
        String c = doc("cookie.md");
        assertTrue(linesWith(c, MARKER).toString(), linesWith(c, MARKER).isEmpty());
        contains("cookie.md", lineWith(c, "| `getMaxAge()`"), "`-1`", "음수", "`0`", "초");
        contains("cookie.md", lineWith(c, "`Expires`"), "`Max-Age`", "이유");
        contains("cookie.md", lineWith(c, "서명 쿠키"), "HMAC");
        contains("cookie.md", lineWith(c, "인코딩하지도"), "`URLEncoder`", "`res.cookie`");
        contains("cookie.md", lineWith(c, "건너뜁니다"), "`=`");
        contains("cookie.md", lineWith(c, "`res.addCookie`가"), "`IllegalArgumentException`");
        String row = lineWith(doc("express-migration.md"), "| `cookie-parser`");
        assertFalse(row, row.contains(MARKER));
        contains("express-migration.md", row, "서명 쿠키", "인코딩", "v1");
    }
}
