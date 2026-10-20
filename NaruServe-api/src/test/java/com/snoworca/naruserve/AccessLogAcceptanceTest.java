package com.snoworca.naruserve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.spi.ExchangeHandler;
import com.snoworca.naruserve.spi.HttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.InMemoryWebSocketChannel;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.testing.StubJsonCodec;
import com.snoworca.naruserve.testing.TestApp;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/** T38/T39 access log. SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-4-8.sds.md. */
public class AccessLogAcceptanceTest {

    private static final Pattern RID = Pattern.compile("rid=([0-9a-f]{16})$");
    private static final Pattern BODY_RID = Pattern.compile("\"requestId\":\"([0-9a-f]{16})\"");

    private final RecordingLogger log = new RecordingLogger();
    private final InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
    private final TestApp t = TestApp.create(adapter);

    {
        t.app().logger(log);
    }

    private TestApp app() {
        return t;
    }

    private String onlyLine() {
        List<String> info = log.info();
        assertEquals(info.toString(), 1, info.size());
        log.lines.clear();
        return info.get(0);
    }

    private ClientResponse send(String method, String target, String... headers) {
        return app().send(method, target, headers);
    }

    // SDS-AC-4; FR-APP-019 AC-1 (T38)
    @Test
    public void sdsAc4_FR_APP_019_AC1_defaultLineForGetUsers7() {
        t.app().get("/users/:id", (req, res) -> res.send("hello"));
        assertEquals(200, send("GET", "/users/7").status());
        String line = onlyLine();
        assertTrue(line, line.matches("GET /users/7 200 \\d+ms in=0 out=5 rid=[0-9a-f]{16}"));
    }

    // SDS-AC-6; FR-APP-019 AC-3
    @Test
    public void sdsAc6_FR_APP_019_AC3_abortedAfterCommitLogsTheCommittedStatusAndAborted() {
        t.app().get("/s", (req, res) -> {
            res.status(202).stream(new ByteArrayInputStream(new byte[10]), "text/plain");
            throw new IOException("after stream");
        });
        send("GET", "/s");
        String line = onlyLine();
        assertTrue(line, line.matches("GET /s 202 aborted \\d+ms in=0 out=0 rid=[0-9a-f]{16}"));
    }

    // SDS-AC-6; FR-APP-019 AC-3
    @Test
    public void sdsAc6_FR_APP_019_AC3_everyAutomaticAnswerAndRejectionWritesExactlyOneLine() {
        t.app().get("/a", (req, res) -> res.send("a"));
        t.app().get("/boom", (req, res) -> {
            throw new IllegalStateException("boom");
        });
        String[][] cases = {
            {"GET", "/missing", "404"},
            {"POST", "/a", "405"},
            {"GET", "/boom", "500"},
            {"FOO", "/a", "501"},
            {"GET", "abc", "400"},
            {"GET", "/%2e%2e", "400"},
            {"OPTIONS", "*", "204"},
        };
        for (String[] c : cases) {
            assertEquals(Integer.parseInt(c[2]), send(c[0], c[1]).status());
            String line = onlyLine();
            assertTrue(line, line.startsWith(c[0] + " " + c[1] + " " + c[2] + " "));
        }
    }

    /** Keeps the in-memory adapter bound through shutdown, so a request after TERMINATED reaches the gate. */
    static final class StayingAdapter implements HttpAdapter {
        final InMemoryHttpAdapter delegate = new InMemoryHttpAdapter();

        @Override
        public void bind(String host, int port, ExchangeHandler handler) {
            delegate.bind(host, port, handler);
        }

        @Override
        public int boundPort() {
            return delegate.boundPort();
        }

        @Override
        public void stop(long timeout, TimeUnit unit) {
        }

        @Override
        public void release() {
        }
    }

    // SDS-AC-6; FR-APP-019 AC-3
    @Test
    public void sdsAc6_FR_APP_019_AC3_lifecycleGate503WritesExactlyOneLine() {
        StayingAdapter adapter = new StayingAdapter();
        RestApp app = RestApp.builder().httpAdapter(adapter).jsonCodec(new StubJsonCodec()).logger(log).build();
        app.get("/x", (req, res) -> res.send("ok"));
        app.listen(0);
        app.shutdown(1, TimeUnit.SECONDS);
        assertEquals(AppState.TERMINATED, app.state());
        log.lines.clear();
        ClientResponse r = adapter.delegate.send(ClientRequest.of("GET", "/x"));
        assertEquals(503, r.status());
        String line = onlyLine();
        assertTrue(line, line.matches("GET /x 503 \\d+ms in=0 out=\\d+ rid=[0-9a-f]{16}"));
        assertEquals(rid(r), lineRid(line));
    }

    // SDS-AC-7; FR-APP-019 AC-4
    @Test
    public void sdsAc7_FR_APP_019_AC4_streamCountsCopiedBytesAndHeadCountsZero() {
        t.app().get("/big", (req, res) -> res.stream(new ByteArrayInputStream(new byte[20000]), "text/plain"));
        t.app().get("/users/:id", (req, res) -> res.send("hello"));
        assertEquals(20000, send("GET", "/big").body().length);
        String line = onlyLine();
        assertTrue(line, line.contains(" in=0 out=20000 "));
        send("HEAD", "/users/7");
        line = onlyLine();
        assertTrue(line, line.startsWith("HEAD /users/7 200 ") && line.contains(" in=0 out=0 "));
    }

    // SDS-AC-9; FR-APP-019 AC-6
    @Test
    public void sdsAc9_FR_APP_019_AC6_excludedRawPathWritesNoLine() {
        t.app().accessLog(o -> o.exclude("/health"));
        t.app().get("/health", (req, res) -> res.send("ok"));
        t.app().get("/health/x", (req, res) -> res.send("ok"));
        t.app().get("/healthz", (req, res) -> res.send("ok"));
        send("GET", "/health");
        send("GET", "/health?x=1");
        assertEquals(Collections.emptyList(), log.info());
        send("GET", "/health/x");
        assertTrue(onlyLine().startsWith("GET /health/x 200"));
        send("GET", "/healthz");
        assertTrue(onlyLine().startsWith("GET /healthz 200"));
    }

    // SDS-AC-9; FR-APP-019 AC-6
    @Test
    public void sdsAc9_FR_APP_019_AC6_disabledWritesNoLine() {
        t.app().accessLog(o -> o.enabled(false));
        t.app().get("/a", (req, res) -> res.send("a"));
        send("GET", "/a");
        send("GET", "/missing");
        send("FOO", "/a");
        assertEquals(Collections.emptyList(), log.info());
    }

    // SDS-AC-10; FR-APP-019 AC-7
    @Test
    public void sdsAc10_FR_APP_019_AC7_lineRidEqualsTheErrorBodyRequestId() {
        ClientResponse r = send("GET", "/missing");
        assertEquals(404, r.status());
        assertEquals(rid(r), lineRid(onlyLine()));
        ClientResponse rejected = send("FOO", "/missing");
        assertEquals(501, rejected.status());
        assertEquals(rid(rejected), lineRid(onlyLine()));
    }

    // SDS-AC-11; SEC-APP-001 AC-1 (T39)
    @Test
    public void sdsAc11_SEC_APP_001_AC1_noQueryHeaderOrBodySecretInTheLine() {
        t.app().post("/login", (req, res) -> res.send("token-xyz"));
        app().start();
        ClientRequest request = ClientRequest.of("POST", "/login?token=qzv&password=pwz")
                .header("Authorization", "Bearer s3cr3t").header("Cookie", "sid=c00k1e")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .body("password=pwq2".getBytes(StandardCharsets.UTF_8));
        ClientResponse r = adapter.send(request);
        assertEquals(200, r.status());
        String line = onlyLine();
        assertTrue(line, line.startsWith("POST /login 200 "));
        for (String secret : new String[] {"qzv", "pwz", "s3cr3t", "c00k1e", "pwq2", "token-xyz", "?"}) {
            assertFalse(secret + " in " + line, line.contains(secret));
        }
    }

    // SDS-AC-13; SEC-APP-001 AC-3
    @Test
    public void sdsAc13_SEC_APP_001_AC3_headerValuesAreAbsentFromTheLine() {
        t.app().get("/a", (req, res) -> res.header("X-Out", "outv4lue").send("a"));
        send("GET", "/a", "X-Custom", "hdrv4lue", "User-Agent", "agentv4lue", "Host", "hostv4lue");
        String line = onlyLine();
        for (String value : new String[] {"hdrv4lue", "agentv4lue", "hostv4lue", "outv4lue", "text/plain"}) {
            assertFalse(value + " in " + line, line.contains(value));
        }
    }

    /** Waits up to 5 s until an INFO line matches {@code regex} and returns it. */
    private String awaitLine(String regex) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < end) {
            for (String l : log.info()) {
                if (l.matches(regex)) {
                    return l;
                }
            }
            Thread.sleep(5);
        }
        throw new AssertionError("no line matching " + regex + " in " + log.info());
    }

    /** Waits up to 5 s until {@code n} INFO lines match {@code regex} and returns the last of them. */
    private String awaitNth(String regex, int n) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < end) {
            String last = null;
            int seen = 0;
            for (String l : log.info()) {
                if (l.matches(regex)) {
                    seen++;
                    last = l;
                }
            }
            if (seen >= n) {
                return last;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("fewer than " + n + " lines matching " + regex + " in " + log.info());
    }

    private int count(String regex) {
        int n = 0;
        for (String l : log.info()) {
            if (l.matches(regex)) {
                n++;
            }
        }
        return n;
    }

    // SDS-AC-1 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-12.sds.md; FR-APP-019 AC-8: the handshake line
    // is written in onOpen, read here before the client sends its first frame, and onDone adds no line (T38)
    @Test(timeout = 20000)
    public void wave7SdsAc1_FR_APP_019_AC8_handshakeLineIsWrittenBeforeTheFirstClientFrame() throws Exception {
        java.util.concurrent.BlockingQueue<String> texts = new java.util.concurrent.LinkedBlockingQueue<>();
        t.app().ws("/ws", (req, ws) -> ws.onText(texts::add));
        app().start();
        java.util.concurrent.Future<ClientResponse> f = adapter.submit(
                ClientRequest.of("GET", "/ws").header("Connection", "Upgrade").header("Upgrade", "websocket"));
        InMemoryWebSocketChannel ch = adapter.nextChannel(5, TimeUnit.SECONDS);
        String open = awaitLine("GET /ws 101 \\d+ms in=0 out=0 rid=[0-9a-f]{16}");
        assertEquals(log.info().toString(), 1, log.info().size());
        ch.clientText("first");
        assertEquals("first", texts.poll(5, TimeUnit.SECONDS));
        ch.clientClose(1000, "");
        assertEquals(101, f.get(5, TimeUnit.SECONDS).status());
        String close = awaitLine("WS /ws 1000 \\d+ms rid=[0-9a-f]{16}");
        assertEquals(lineRid(open), lineRid(close));
        Thread.sleep(50); // a line from onDone would follow the close line at once
        assertEquals(log.info().toString(), 2, log.info().size());
    }

    // SDS-AC-3 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-12.sds.md; FR-APP-019 AC-9: one close line per
    // session with the code onClose reports: 1000 for ws.close(), the client's code, 1008 on overflow, 1006 for an
    // abort without a close frame and 1001 at shutdown; each with the rid of its handshake line (T38)
    @Test(timeout = 30000)
    public void wave7SdsAc3_FR_APP_019_AC9_closeLineCarriesTheReportedCodeAndTheHandshakeRidOnce() throws Exception {
        char[] big = new char[2000];
        java.util.Arrays.fill(big, 'x');
        t.app().webSocket(o -> o.sendQueueSize(1000));
        t.app().ws("/ws", (req, ws) -> ws.onText(text -> {
            if ("close".equals(text)) {
                ws.close();
            } else if ("big".equals(text)) {
                ws.sendText(new String(big));
            }
        }));
        app().start();
        String[] codes = {"1000", "4001", "1008", "1006"};
        int sessions = 0;
        for (String code : codes) {
            java.util.concurrent.Future<ClientResponse> f = adapter.submit(
                    ClientRequest.of("GET", "/ws").header("Connection", "Upgrade").header("Upgrade", "websocket"));
            InMemoryWebSocketChannel ch = adapter.nextChannel(5, TimeUnit.SECONDS);
            String open = awaitNth("GET /ws 101 .*", ++sessions);
            if ("1000".equals(code)) {
                ch.clientText("close");
            } else if ("4001".equals(code)) {
                ch.clientClose(4001, "client");
            } else if ("1008".equals(code)) {
                ch.clientText("big");
            } else {
                ch.abort();
            }
            f.get(5, TimeUnit.SECONDS);
            String close = awaitLine("WS /ws " + code + " \\d+ms rid=" + lineRid(open));
            assertEquals(close, 1, count("WS /ws " + code + " .*"));
        }
        java.util.concurrent.Future<ClientResponse> last = adapter.submit(
                ClientRequest.of("GET", "/ws").header("Connection", "Upgrade").header("Upgrade", "websocket"));
        adapter.nextChannel(5, TimeUnit.SECONDS);
        String open = awaitNth("GET /ws 101 .*", ++sessions);
        t.app().shutdown(2, TimeUnit.SECONDS);
        last.get(5, TimeUnit.SECONDS);
        awaitLine("WS /ws 1001 \\d+ms rid=" + lineRid(open));
        assertEquals(log.info().toString(), 5, count("GET /ws 101 .*"));
        assertEquals(log.info().toString(), 5, count("WS /ws .*"));
        assertEquals(log.info().toString(), 10, log.info().size());
    }

    /** /events opens a stream; the test thread then sends, so every event goes through the drain. */
    private final java.util.concurrent.BlockingQueue<SseClient> streams =
            new java.util.concurrent.LinkedBlockingQueue<>();

    private SseClient openStream() throws InterruptedException {
        adapter.submit(ClientRequest.of("GET", "/events"));
        SseClient c = streams.poll(5, TimeUnit.SECONDS);
        assertTrue("no stream opened", c != null);
        return c;
    }

    /** Waits until a write to {@code es} began, which a hold then keeps in progress. */
    private static void awaitWriter(com.snoworca.naruserve.testing.InMemoryEventStream es)
            throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (es.writerThreads().isEmpty() && System.nanoTime() < end) {
            Thread.sleep(1);
        }
        assertFalse("no write began", es.writerThreads().isEmpty());
    }

    private static final String SSE_LINE = "GET /events 200 \\d+ms in=0 out=%d rid=[0-9a-f]{16} sse start=\\d+ cause=%s";

    // SDS-AC-4 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-12.sds.md; FR-APP-019 AC-10: no line at stream
    // start; one line at its end with out, start and the first recorded cause: client for a drain IOException, error
    // for a RuntimeException, app and overflow before a later IOException; HEAD keeps the ordinary line (T38)
    @Test(timeout = 30000)
    public void wave7SdsAc4_FR_APP_019_AC10_sseLineAtStreamEndWithTheFirstRecordedCause() throws Exception {
        t.app().sse(o -> o.sendQueueSize(1024));
        t.app().get("/events", (req, res) -> streams.add(res.sse()));
        app().start();
        long before = System.currentTimeMillis();

        SseClient client = openStream();
        com.snoworca.naruserve.testing.InMemoryEventStream es = adapter.nextEventStream(5, TimeUnit.SECONDS);
        assertEquals("no line when the stream starts", Collections.emptyList(), log.info());
        es.failWrite(3, new IOException("peer gone"));
        assertTrue(client.send("1"));
        assertTrue(client.send("2"));
        client.send("3");
        String line = awaitLine(String.format(SSE_LINE, "data: 1\n\ndata: 2\n\n".length(), "client"));
        long start = Long.parseLong(line.replaceAll(".* sse start=(\\d+) .*", "$1"));
        assertTrue(line, start >= before && start <= System.currentTimeMillis());
        assertFalse(line, line.contains(" aborted"));

        SseClient error = openStream();
        adapter.nextEventStream(5, TimeUnit.SECONDS).failWrite(1, new IllegalStateException("broken"));
        error.send("1");
        awaitLine(String.format(SSE_LINE, 0, "error"));

        SseClient app = openStream();
        com.snoworca.naruserve.testing.InMemoryEventStream appStream = adapter.nextEventStream(5, TimeUnit.SECONDS);
        appStream.failWrite(1, new IOException("after close"));
        java.util.concurrent.CountDownLatch held = appStream.hold();
        assertTrue(app.send("1"));
        awaitWriter(appStream);
        app.close();
        held.countDown();
        awaitLine(String.format(SSE_LINE, 0, "app"));

        SseClient overflow = openStream();
        com.snoworca.naruserve.testing.InMemoryEventStream ovStream = adapter.nextEventStream(5, TimeUnit.SECONDS);
        ovStream.failWrite(1, new IOException("after overflow"));
        java.util.concurrent.CountDownLatch ovHeld = ovStream.hold();
        assertTrue(overflow.send("1"));
        awaitWriter(ovStream); // the drain holds the first event in its write
        char[] big = new char[2000];
        java.util.Arrays.fill(big, 'x');
        assertFalse(overflow.send(new String(big)));
        ovHeld.countDown();
        awaitLine(String.format(SSE_LINE, 0, "overflow"));
        assertEquals(log.info().toString(), 4, log.info().size());

        log.lines.clear();
        assertEquals(200, send("HEAD", "/events").status());
        String head = onlyLine();
        assertTrue(head, head.matches("HEAD /events 200 \\d+ms in=0 out=0 rid=[0-9a-f]{16}"));
    }

    private static String rid(ClientResponse r) {
        Matcher m = BODY_RID.matcher(new String(r.body(), StandardCharsets.UTF_8));
        assertTrue(m.find());
        return m.group(1);
    }

    private static String lineRid(String line) {
        Matcher m = RID.matcher(line);
        assertTrue(line, m.find());
        return m.group(1);
    }
}
