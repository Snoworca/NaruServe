package com.snoworca.naruserve.tinyhttpd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.HttpException;
import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.tinyhttpd.RawTargetSocketTest.Http;
import com.snoworca.tinyhttpd.ServerConfig;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Test;

/** Request limits on the TinyHttpd builder. SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-5-5.sds.md. */
public class LimitsSocketTest {

    private final List<TinyHttpdAdapter> adapters = new ArrayList<>();
    private final List<RestApp> apps = new ArrayList<>();
    private final RecordingLogger log = new RecordingLogger();

    @After
    public void stop() {
        for (RestApp a : apps) {
            a.shutdown(2, TimeUnit.SECONDS);
        }
        for (TinyHttpdAdapter a : adapters) {
            Http.stopQuietly(a);
        }
    }

    private TinyHttpdAdapter bound(int maxRequestLineLength) throws Exception {
        TinyHttpdAdapter a = new TinyHttpdAdapter();
        adapters.add(a);
        a.bind(Http.settings().maxRequestLineLength(maxRequestLineLength).build(), Http.ok(ex -> { }));
        return a;
    }

    private RestApp app(TinyHttpdAdapter adapter) {
        RestApp a = Http.app(adapter, log);
        a.get("/h", (req, res) -> res.send("hi"));
        apps.add(a);
        return a;
    }

    /** {@code count} header lines of {@code size} bytes each, name and value together. */
    private static String[] padHeaders(int count, int size) {
        String[] out = new String[count];
        for (int i = 0; i < count; i++) {
            String name = "X-Pad-" + i + ": ";
            char[] v = new char[size - name.length()];
            Arrays.fill(v, 'a');
            out[i] = name + new String(v);
        }
        return out;
    }

    /** A request line of exactly {@code length} bytes: method, target and version without CRLF. */
    private static String requestLineOf(int length) {
        String prefix = "GET /h?q=";
        String suffix = " HTTP/1.1";
        char[] t = new char[length - prefix.length() - suffix.length()];
        Arrays.fill(t, 'a');
        return prefix + new String(t) + suffix + "\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n";
    }

    private int accessLines(String status) {
        int n = 0;
        for (String line : log.info()) {
            if (line.contains(" " + status + " ")) {
                n++;
            }
        }
        return n;
    }

    // SDS-AC-8; FR-ENGINE-003 AC-2
    @Test(timeout = 30000)
    public void sdsAc8_FR_ENGINE_003_AC2_requestLineLimitSizesTheInputBuffer() throws Exception {
        int[][] cases = {{1, 8192}, {8190, 8192}, {8191, 8193}, {65534, 65536}};
        for (int[] c : cases) {
            TinyHttpdAdapter a = bound(c[0]);
            assertTrue(a.boundPort() > 0);
            assertEquals("maxRequestLineBytes for " + c[0], c[0], a.config().maxRequestLineBytes);
            assertEquals("inputBufferBytes for " + c[0], c[1], a.config().inputBufferBytes);
            Http.stopQuietly(a);
        }
    }

    // SDS-AC-8; FR-ENGINE-003 AC-1
    @Test(timeout = 30000)
    public void sdsAc8_FR_ENGINE_003_AC1_defaultLimitsAnswer431And414() throws Exception {
        int port = app(new TinyHttpdAdapter()).listen(0).port();
        assertEquals(431, Http.status(Http.send(port, Http.get("/h", padHeaders(4, 5000)))));
        assertEquals(200, Http.status(Http.send(port, requestLineOf(8190))));
        assertEquals(414, Http.status(Http.send(port, requestLineOf(8191))));
    }

    // OPS-ENGINE-002 AC-2, AC-6: with the defaults one header line may hold inputBufferBytes - 2 = 8190 bytes
    @Test(timeout = 30000)
    public void OPS_ENGINE_002_AC2_AC6_defaultHeaderLineLimitIs8190Bytes() throws Exception {
        int port = app(new TinyHttpdAdapter()).listen(0).port();
        assertEquals(200, Http.status(Http.send(port, Http.get("/h", padHeaders(1, 8190)))));
        assertEquals(431, Http.status(Http.send(port, Http.get("/h", padHeaders(1, 8191)))));
    }

    // SDS-AC-9; FR-ENGINE-003 AC-7
    @Test(timeout = 30000)
    public void sdsAc9_FR_ENGINE_003_AC7_bodyLimitsStayWithTheCoreAndUnreadBodiesAreDrained() throws Exception {
        ServerConfig defaults = TinyHttpdAdapter.configure(Http.settings().build(), (q, r) -> { }).build().getConfig();
        assertEquals(-1L, defaults.getMaxRequestBodyBytes());
        assertEquals(65536L, defaults.maxDrainBytes);

        TinyHttpdAdapter adapter = new TinyHttpdAdapter();
        RestApp a = app(adapter);
        AtomicInteger onError = new AtomicInteger();
        a.jsonLimit(10);
        a.post("/j", (req, res) -> res.send(String.valueOf(req.body(Map.class))));
        a.post("/ignore", (req, res) -> res.send("ignored"));
        a.onError((err, req, res) -> {
            onError.incrementAndGet();
            res.status(err instanceof HttpException ? ((HttpException) err).status() : 500).send("too large");
        });
        int port = a.listen(0).port();
        assertEquals(-1L, adapter.config().getMaxRequestBodyBytes());
        assertEquals(65536L, adapter.config().maxDrainBytes);

        char[] body = new char[100];
        Arrays.fill(body, ' ');
        String big = "POST /j HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\nContent-Length: 100\r\n"
                + "Connection: close\r\n\r\n" + new String(body);
        assertEquals(413, Http.status(Http.send(port, big)));
        assertEquals(1, onError.get());
        assertEquals(1, accessLines("413"));

        char[] unread = new char[1000];
        Arrays.fill(unread, 'x');
        String pipelined = "POST /ignore HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: 1000\r\n\r\n" + new String(unread)
                + Http.get("/h");
        String both = Http.send(port, pipelined);
        assertEquals(both, 2, both.split("HTTP/1\\.1 200 ", -1).length - 1);
        assertTrue(both, both.endsWith("hi"));
    }

    // SDS-AC-10; FR-ENGINE-003 AC-8
    @Test(timeout = 30000)
    public void sdsAc10_FR_ENGINE_003_AC8_nonDefaultLimitsAndTimeoutsReadBackUnchanged() throws Exception {
        ServerConfig defaults = TinyHttpdAdapter.configure(Http.settings().build(), (q, r) -> { }).build().getConfig();
        assertEquals(16384, defaults.maxHeaderBytes);
        assertEquals(100, defaults.maxHeaderCount);
        assertEquals(8190, defaults.maxRequestLineBytes);
        assertEquals(8192, defaults.inputBufferBytes);
        assertEquals(10000, defaults.getHeaderTimeout());
        assertEquals(30000, defaults.getBodyReadTimeout());
        assertEquals(30000, defaults.getWriteTimeout());
        assertEquals(240, defaults.getMinRequestBodyRate());

        TinyHttpdAdapter adapter = new TinyHttpdAdapter();
        RestApp a = app(adapter);
        a.server(o -> o.maxHeaderSize(32768).maxHeaderCount(50).maxRequestLineLength(9000)
                .headerTimeout(7, TimeUnit.SECONDS).bodyReadTimeout(9, TimeUnit.SECONDS)
                .writeTimeout(11, TimeUnit.SECONDS).minBodyRate(100));
        int port = a.listen(0).port();
        ServerConfig c = adapter.config();
        assertEquals(32768, c.maxHeaderBytes);
        assertEquals(50, c.maxHeaderCount);
        assertEquals(9000, c.maxRequestLineBytes);
        assertEquals(9002, c.inputBufferBytes);
        assertEquals(7000, c.getHeaderTimeout());
        assertEquals(9000, c.getBodyReadTimeout());
        assertEquals(11000, c.getWriteTimeout());
        assertEquals(100, c.getMinRequestBodyRate());

        assertEquals(200, Http.status(Http.send(port, Http.get("/h", padHeaders(4, 5000)))));
    }

    /** An app whose POST /b reads the JSON body (whitespace, so the fixture codec is never reached). */
    private RestApp bodyApp(TinyHttpdAdapter adapter) {
        RestApp a = app(adapter);
        a.post("/b", (req, res) -> {
            req.body(Object.class);
            res.send("read");
        });
        return a;
    }

    /** Sends the headers of a POST /b declaring {@code length} body bytes, then {@code first} body bytes. */
    private static Socket openBody(int port, int length, String first) throws Exception {
        Socket s = new Socket();
        s.connect(new InetSocketAddress("127.0.0.1", port), 5000);
        s.setSoTimeout(15000);
        OutputStream out = s.getOutputStream();
        out.write(("POST /b HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\nContent-Length: "
                + length + "\r\n\r\n" + first).getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
        return s;
    }

    // FR-ENGINE-003 AC-3, IR-ENGINE-001 AC-4
    @Test(timeout = 30000)
    public void FR_ENGINE_003_AC3_headerLineLimitFollowsTheRequestLineLimitNotTheHeaderTotal() throws Exception {
        int port = app(new TinyHttpdAdapter()).listen(0).port();
        assertEquals(431, Http.status(Http.send(port, Http.get("/h", padHeaders(1, 8191)))));

        TinyHttpdAdapter bigTotal = new TinyHttpdAdapter();
        RestApp total = app(bigTotal);
        total.server(o -> o.maxHeaderSize(65536));
        int totalPort = total.listen(0).port();
        assertEquals(65536, bigTotal.config().maxHeaderBytes);
        assertEquals(8192, bigTotal.config().inputBufferBytes);
        assertEquals(431, Http.status(Http.send(totalPort, Http.get("/h", padHeaders(1, 8191)))));

        TinyHttpdAdapter longLine = new TinyHttpdAdapter();
        RestApp line = app(longLine);
        line.server(o -> o.maxRequestLineLength(10000));
        int linePort = line.listen(0).port();
        assertEquals(16384, longLine.config().maxHeaderBytes);
        assertEquals(10002, longLine.config().inputBufferBytes);
        assertEquals(200, Http.status(Http.send(linePort, Http.get("/h", padHeaders(1, 10000)))));
        assertEquals(431, Http.status(Http.send(linePort, Http.get("/h", padHeaders(1, 10001)))));
    }

    // FR-ENGINE-003 AC-4
    @Test(timeout = 30000)
    public void FR_ENGINE_003_AC4_headerCountHeaderTimeoutAndBodyReadTimeout() throws Exception {
        TinyHttpdAdapter adapter = new TinyHttpdAdapter();
        RestApp a = bodyApp(adapter);
        a.server(o -> o.headerTimeout(300, TimeUnit.MILLISECONDS).bodyReadTimeout(1, TimeUnit.SECONDS));
        int port = a.listen(0).port();

        // Http.get adds Host and Connection: 98 pads make 100 fields, 99 make 101.
        assertEquals(200, Http.status(Http.send(port, Http.get("/h", padHeaders(98, 20)))));
        assertEquals(431, Http.status(Http.send(port, Http.get("/h", padHeaders(99, 20)))));

        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            s.setSoTimeout(10000);
            s.getOutputStream().write("GET /h HTTP/1.1\r\nHost: 127.0.0.1\r\n".getBytes(StandardCharsets.ISO_8859_1));
            s.getOutputStream().flush();
            assertEquals(408, Http.status(Http.readAll(s.getInputStream())));
        }

        long start = System.nanoTime();
        try (Socket s = openBody(port, 10, "  ")) {
            String response = Http.readAll(s.getInputStream());
            long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertEquals(response, 408, Http.status(response));
            assertEquals(response, "close", Http.header(response, "Connection"));
            assertTrue("took " + tookMs, tookMs >= 900 && tookMs < 4500);
        }
    }

    // FR-ENGINE-003 AC-5
    @Test(timeout = 30000)
    public void FR_ENGINE_003_AC5_dripFedBodyIsAnswered408AfterTheGraceAndClosed() throws Exception {
        TinyHttpdAdapter adapter = new TinyHttpdAdapter();
        int port = bodyApp(adapter).listen(0).port();
        assertEquals(240, adapter.config().getMinRequestBodyRate());

        long start = System.nanoTime();
        try (Socket s = openBody(port, 1000, "")) {
            Thread drip = new Thread(() -> {
                try {
                    OutputStream out = s.getOutputStream();
                    for (int i = 0; i < 1000; i++) {
                        out.write(' ');
                        out.flush();
                        Thread.sleep(100);
                    }
                } catch (Exception closed) {
                    // the server closed the connection
                }
            });
            drip.setDaemon(true);
            drip.start();
            // readAll returns only at end of stream, so returning shows the server closed the connection.
            String response = Http.readAll(s.getInputStream());
            long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertEquals(response, 408, Http.status(response));
            assertEquals(response, "close", Http.header(response, "Connection"));
            assertTrue("took " + tookMs, tookMs >= 4500 && tookMs < 20000);
        }
    }

    // FR-ENGINE-003 AC-6
    @Test(timeout = 30000)
    public void FR_ENGINE_003_AC6_contentLengthWithChunkedIsRejectedBeforeDispatch() throws Exception {
        TinyHttpdAdapter adapter = new TinyHttpdAdapter();
        RestApp a = app(adapter);
        AtomicInteger handler = new AtomicInteger();
        List<Throwable> onError = Collections.synchronizedList(new ArrayList<Throwable>());
        a.post("/c", (req, res) -> {
            handler.incrementAndGet();
            res.send("reached");
        });
        a.onError((err, req, res) -> {
            onError.add(err);
            res.status(500).send("recorded");
        });
        int port = a.listen(0).port();
        int infoBefore = log.info().size();

        String r = Http.send(port, "POST /c HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: 5\r\n"
                + "Transfer-Encoding: chunked\r\nConnection: close\r\n\r\n5\r\nhello\r\n0\r\n\r\n");
        assertEquals(r, 400, Http.status(r));

        // The one dispatched request after the rejection adds the only access-log line.
        assertEquals(200, Http.status(Http.send(port, Http.get("/h"))));
        long deadline = System.currentTimeMillis() + 5000;
        while (log.info().size() < infoBefore + 1 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        List<String> lines = log.info();
        assertEquals(lines.toString(), infoBefore + 1, lines.size());
        assertTrue(lines.get(infoBefore), lines.get(infoBefore).startsWith("GET /h 200"));
        assertEquals(0, handler.get());
        assertEquals(Collections.emptyList(), onError);
    }
}
