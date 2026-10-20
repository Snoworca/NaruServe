package com.snoworca.naruserve.tinyhttpd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.SseClient;
import com.snoworca.naruserve.internal.DefaultResponse;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.tinyhttpd.RawTargetSocketTest.Http;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Test;

/** res.sse() over TinyHttpd. SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-9.sds.md. */
public class SseSocketTest {

    private final RecordingLogger log = new RecordingLogger();
    private final TinyHttpdAdapter adapter = new TinyHttpdAdapter();
    private RestApp app;

    @After
    public void stop() {
        if (app != null) {
            app.shutdown(2, TimeUnit.SECONDS);
        }
        Http.stopQuietly(adapter);
    }

    private static int count(String response, String name) {
        int n = 0;
        for (String line : response.substring(0, response.indexOf("\r\n\r\n")).split("\r\n")) {
            int c = line.indexOf(':');
            if (c > 0 && line.substring(0, c).equalsIgnoreCase(name)) {
                n++;
            }
        }
        return n;
    }

    /** The payload of a chunked body: hex size line, that many bytes, CRLF, until the 0 chunk. */
    private static String dechunk(String body) {
        StringBuilder out = new StringBuilder();
        int at = 0;
        while (true) {
            int eol = body.indexOf("\r\n", at);
            String size = body.substring(at, eol);
            int semi = size.indexOf(';');
            int n = Integer.parseInt((semi < 0 ? size : size.substring(0, semi)).trim(), 16);
            if (n == 0) {
                return out.toString();
            }
            out.append(body, eol + 2, eol + 2 + n);
            at = eol + 2 + n + 2;
        }
    }

    // SDS-AC-6; FR-SSE-001 AC-1, AC-3 (T24)
    @Test(timeout = 20000)
    public void sdsAc6_FR_SSE_001_AC1_AC3_sseCommits200WithEachEventStreamHeaderOnceOverTinyHttpd() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        app = Http.app(adapter, log);
        app.get("/events", (req, res) -> {
            res.status(201).header("Content-Type", "text/plain").header("Cache-Control", "max-age=60");
            SseClient c = res.sse();
            seen.set(res.isCommitted() + " " + ((DefaultResponse) res).state());
            c.send("connected", "ready");
            c.close();
        });
        int port = app.listen(0).port();
        String response = Http.send(port, Http.get("/events"));
        assertEquals(200, Http.status(response));
        assertEquals("text/event-stream; charset=UTF-8", Http.header(response, "Content-Type"));
        assertEquals("no-cache, no-transform", Http.header(response, "Cache-Control"));
        assertEquals(1, count(response, "Content-Type"));
        assertEquals(1, count(response, "Cache-Control"));
        assertNull(Http.header(response, "Content-Length"));
        assertEquals("chunked", Http.header(response, "Transfer-Encoding"));
        assertEquals("event: connected\ndata: ready\n\n", dechunk(Http.body(response)));
        assertEquals("true STREAMING", seen.get());
    }
}
