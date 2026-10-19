package com.snoworca.naruserve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.spi.ExchangeHandler;
import com.snoworca.naruserve.spi.HttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.StubJsonCodec;
import com.snoworca.naruserve.testing.TestApp;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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

    /** Records "LEVEL message" for every call. */
    static final class RecordingLogger implements Logger {
        final List<String> lines = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void trace(String message) {
            lines.add("TRACE " + message);
        }

        @Override
        public void debug(String message) {
            lines.add("DEBUG " + message);
        }

        @Override
        public void info(String message) {
            lines.add("INFO " + message);
        }

        @Override
        public void warn(String message) {
            lines.add("WARN " + message);
        }

        @Override
        public void error(String message) {
            lines.add("ERROR " + message);
        }

        @Override
        public void error(String message, Throwable error) {
            lines.add("ERROR " + message);
        }

        /** The INFO lines, without the level prefix. */
        List<String> info() {
            List<String> out = new ArrayList<>();
            synchronized (lines) {
                for (String l : lines) {
                    if (l.startsWith("INFO ")) {
                        out.add(l.substring(5));
                    }
                }
            }
            return out;
        }
    }

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

    // SDS-AC-4; FR-APP-015 AC-1 (T38)
    @Test
    public void sdsAc4_FR_APP_015_AC1_defaultLineForGetUsers7() {
        t.app().get("/users/:id", (req, res) -> res.send("hello"));
        assertEquals(200, send("GET", "/users/7").status());
        String line = onlyLine();
        assertTrue(line, line.matches("GET /users/7 200 \\d+ms in=0 out=5 rid=[0-9a-f]{16}"));
    }

    // SDS-AC-6; FR-APP-015 AC-3
    @Test
    public void sdsAc6_FR_APP_015_AC3_abortedAfterCommitLogsTheCommittedStatusAndAborted() {
        t.app().get("/s", (req, res) -> {
            res.status(202).stream(new ByteArrayInputStream(new byte[10]), "text/plain");
            throw new IOException("after stream");
        });
        send("GET", "/s");
        String line = onlyLine();
        assertTrue(line, line.matches("GET /s 202 aborted \\d+ms in=0 out=0 rid=[0-9a-f]{16}"));
    }

    // SDS-AC-6; FR-APP-015 AC-3
    @Test
    public void sdsAc6_FR_APP_015_AC3_everyAutomaticAnswerAndRejectionWritesExactlyOneLine() {
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

    // SDS-AC-6; FR-APP-015 AC-3
    @Test
    public void sdsAc6_FR_APP_015_AC3_lifecycleGate503WritesExactlyOneLine() {
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

    // SDS-AC-7; FR-APP-015 AC-4
    @Test
    public void sdsAc7_FR_APP_015_AC4_streamCountsCopiedBytesAndHeadCountsZero() {
        t.app().get("/big", (req, res) -> res.stream(new ByteArrayInputStream(new byte[20000]), "text/plain"));
        t.app().get("/users/:id", (req, res) -> res.send("hello"));
        assertEquals(20000, send("GET", "/big").body().length);
        String line = onlyLine();
        assertTrue(line, line.contains(" in=0 out=20000 "));
        send("HEAD", "/users/7");
        line = onlyLine();
        assertTrue(line, line.startsWith("HEAD /users/7 200 ") && line.contains(" in=0 out=0 "));
    }

    // SDS-AC-9; FR-APP-015 AC-6
    @Test
    public void sdsAc9_FR_APP_015_AC6_excludedRawPathWritesNoLine() {
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

    // SDS-AC-9; FR-APP-015 AC-6
    @Test
    public void sdsAc9_FR_APP_015_AC6_disabledWritesNoLine() {
        t.app().accessLog(o -> o.enabled(false));
        t.app().get("/a", (req, res) -> res.send("a"));
        send("GET", "/a");
        send("GET", "/missing");
        send("FOO", "/a");
        assertEquals(Collections.emptyList(), log.info());
    }

    // SDS-AC-10; FR-APP-015 AC-7
    @Test
    public void sdsAc10_FR_APP_015_AC7_lineRidEqualsTheErrorBodyRequestId() {
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
