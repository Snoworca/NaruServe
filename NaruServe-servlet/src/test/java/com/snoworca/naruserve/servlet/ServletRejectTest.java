package com.snoworca.naruserve.servlet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.testing.RecordingLogger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;

/** The adapter's own answers: fixed header values only, no request text in headers or log lines. */
public class ServletRejectTest {

    private static final String[] SECRETS = {"tok-SECRET-1", "sess-SECRET-2", "path-SECRET-3"};

    @Rule
    public final ContainerRule container = new ContainerRule();

    private SharedApp.Reply sendWithSecrets(String target) throws Exception {
        return SharedApp.http(container.port(), "GET", target, null, "Authorization", "Bearer tok-SECRET-1",
                "Cookie", "JSESSIONID=sess-SECRET-2");
    }

    private static void assertFixedHeaders(SharedApp.Reply r) {
        for (Map.Entry<String, String> h : r.headers) {
            for (String secret : SECRETS) {
                assertFalse(h + " carries " + secret, h.getKey().contains(secret) || h.getValue().contains(secret));
            }
            assertFalse(h.toString(), h.getValue().contains("\r") || h.getValue().contains("\n"));
        }
        assertEquals(1, r.headers("Content-Type").size());
        assertTrue(r.header("Content-Type"), r.header("Content-Type").toLowerCase().startsWith("application/json"));
        for (String secret : SECRETS) {
            assertFalse(r.text(), r.text().contains(secret));
        }
    }

    // SDS-AC-6; FR-SVL-002 AC-6 — unbound adapter: 503 with Connection: close
    @Test
    public void sdsAc6_FR_SVL_002_AC6_unboundAnswer503CarriesOnlyFixedHeaderValues() throws Exception {
        container.servlet(new NaruServeServlet(new ServletAdapter(), ""), "/*");
        SharedApp.Reply r = sendWithSecrets("/path-SECRET-3");
        assertEquals(503, r.status);
        assertEquals("close", r.header("Connection"));
        assertFixedHeaders(r);
    }

    // SDS-AC-6; FR-SVL-002 AC-6 — prefix miss: 404 from the settings' renderer, nothing logged with request text
    @Test
    public void sdsAc6_FR_SVL_002_AC6_prefixMiss404LogsNoTokenSessionOrPath() throws Exception {
        RecordingLogger logger = new RecordingLogger();
        ServletAdapter adapter = new ServletAdapter();
        RestApp app = RestApp.builder().httpAdapter(adapter).jsonCodec(SharedApp.CODEC).logger(logger).build();
        app.get("/x", (req, res) -> res.send("x"));
        app.listen(0);
        container.servlet(new NaruServeServlet(adapter, "/api"), "/api/*");
        SharedApp.Reply r = sendWithSecrets("/%61pi/path-SECRET-3");
        assertEquals(404, r.status);
        assertTrue(r.text(), r.text().contains("\"code\":\"NOT_FOUND\""));
        assertFixedHeaders(r);
        List<String> lines;
        synchronized (logger.lines) {
            lines = new ArrayList<>(logger.lines);
        }
        for (String line : lines) {
            for (String secret : SECRETS) {
                assertFalse(line, line.contains(secret));
            }
        }
    }

    // SDS-AC-6; FR-SVL-002 AC-6 — the fallback renderer when only bind(host, port, handler) ran
    @Test
    public void sdsAc6_FR_SVL_002_AC6_fallbackRendererIsPlainJsonWithoutRequestText() {
        ServletAdapter adapter = new ServletAdapter();
        adapter.bind("0.0.0.0", 0, ex -> {
        });
        String body = adapter.errorRenderer().render(404);
        assertTrue(body, body.startsWith("{\"error\":{") && body.contains("404") && body.contains("\"requestId\":\""));
        assertFalse(body, body.contains("\r") || body.contains("\n"));
    }
}
