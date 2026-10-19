package com.snoworca.naruserve.tinyhttpd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.tinyhttpd.RawTargetSocketTest.Http;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Errors TinyHttpd answers itself. SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-5-6.sds.md. */
public class TransportRejectionTest {

    private static final Pattern REQUEST_ID = Pattern.compile("\"requestId\":\"([^\"]+)\"");

    private final RecordingLogger log = new RecordingLogger();
    private final List<Throwable> onError = Collections.synchronizedList(new ArrayList<Throwable>());
    private RestApp app;
    private int port;

    @Before
    public void start() {
        app = Http.app(new TinyHttpdAdapter(), log);
        app.server(o -> o.headerTimeout(300, TimeUnit.MILLISECONDS));
        app.onError((e, req, res) -> {
            onError.add(e);
            res.status(500).send("recorded");
        });
        app.get("/ok", (req, res) -> res.send("ok"));
        port = app.listen(0).port();
    }

    @After
    public void stop() {
        app.shutdown(2, TimeUnit.SECONDS);
    }

    /** The INFO lines once there are at least {@code n}: the access line may land just after the response. */
    private List<String> awaitInfo(int n) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (log.info().size() < n && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        return log.info();
    }

    private static String requestId(String response) {
        Matcher m = REQUEST_ID.matcher(Http.body(response));
        assertTrue(response, m.find());
        return m.group(1);
    }

    private void assertPlain(int status, String response) {
        assertEquals(response, status, Http.status(response));
        assertEquals(response, "text/plain; charset=UTF-8", Http.header(response, "Content-Type"));
        assertEquals(response, status + " ", Http.body(response).substring(0, 4));
    }

    // SDS-AC-3; FR-ENGINE-005 AC-3
    @Test(timeout = 20000)
    public void sdsAc3_FR_ENGINE_005_AC3_hostRejectionWritesTheStandardJsonBodyWithFreshIds() throws Exception {
        String first = Http.send(port, "GET /ok HTTP/1.1\r\nHost: evil.example\r\nConnection: close\r\n\r\n");
        String second = Http.send(port, "GET /ok HTTP/1.1\r\nHost: evil.example\r\nConnection: close\r\n\r\n");
        for (String r : new String[] {first, second}) {
            assertEquals(r, 421, Http.status(r));
            assertEquals(r, "application/json; charset=UTF-8", Http.header(r, "Content-Type"));
            assertTrue(r, Http.body(r).startsWith("{\"error\":{\"code\":\"HTTP_421\",\"message\":\"Misdirected Request\""));
        }
        assertNotEquals(requestId(first), requestId(second));
    }

    // SDS-AC-4; FR-ENGINE-005 AC-4
    @Test(timeout = 30000)
    public void sdsAc4_FR_ENGINE_005_AC4_preDispatchRejectionsStayPlainAndUnrecorded() throws Exception {
        int infoBefore = log.info().size();
        char[] longPath = new char[9000];
        java.util.Arrays.fill(longPath, 'a');
        char[] bigHeader = new char[20000];
        java.util.Arrays.fill(bigHeader, 'b');

        assertPlain(400, Http.send(port, "NOT A REQUEST LINE\r\n\r\n"));
        assertPlain(414, Http.send(port, Http.get("/" + new String(longPath))));
        assertPlain(431, Http.send(port, Http.get("/ok", "X-Big: " + new String(bigHeader))));
        assertPlain(505, Http.send(port, "GET /ok HTTP/2.0\r\nHost: 127.0.0.1\r\n\r\n"));
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            s.setSoTimeout(10000);
            OutputStream out = s.getOutputStream();
            // An unfinished header section: the 300 ms header timeout answers it.
            out.write("GET /ok HTTP/1.1\r\nHost: 127.0.0.1\r\n".getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            assertPlain(408, Http.readAll(s.getInputStream()));
        }

        // The access log is live: the one dispatched request after the rejections adds the only line.
        assertEquals(200, Http.status(Http.send(port, Http.get("/ok"))));
        List<String> lines = awaitInfo(infoBefore + 1);
        assertEquals(lines.toString(), infoBefore + 1, lines.size());
        assertTrue(lines.get(infoBefore), lines.get(infoBefore).startsWith("GET /ok 200"));
        assertEquals(Collections.emptyList(), onError);
    }

    // SDS-AC-5; FR-ENGINE-005 AC-5
    @Test(timeout = 20000)
    public void sdsAc5_FR_ENGINE_005_AC5_originFormConnectIsDispatchedAuthorityFormIsNot() throws Exception {
        int infoBefore = log.info().size();
        String dispatched = Http.send(port, "CONNECT / HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n");
        assertEquals(dispatched, 501, Http.status(dispatched));
        assertEquals(dispatched, "application/json; charset=UTF-8", Http.header(dispatched, "Content-Type"));
        String transport = Http.send(port, "CONNECT example.com:443 HTTP/1.1\r\nHost: example.com:443\r\n\r\n");
        assertPlain(501, transport);
        assertEquals(200, Http.status(Http.send(port, Http.get("/ok"))));

        List<String> lines = awaitInfo(infoBefore + 2);
        assertEquals(lines.toString(), infoBefore + 2, lines.size());
        assertTrue(lines.get(infoBefore), lines.get(infoBefore).startsWith("CONNECT / 501"));
        assertTrue(lines.get(infoBefore + 1), lines.get(infoBefore + 1).startsWith("GET /ok 200"));
        assertEquals(Collections.emptyList(), onError);
    }

    // FR-ENGINE-005: an empty rendered error body (the plain bind overload's renderer) is not labelled JSON
    @Test(timeout = 20000)
    public void FR_ENGINE_005_emptyErrorBodyCarriesNoJsonContentType() throws Exception {
        TinyHttpdAdapter bare = new TinyHttpdAdapter();
        try {
            bare.bind(Http.settings().errorRenderer(status -> "").allowedHosts("myphone.local").build(),
                    ex -> ex.abort());
            String r = Http.send(bare.boundPort(), "GET / HTTP/1.1\r\nHost: evil.example\r\nConnection: close\r\n\r\n");
            assertEquals(r, 421, Http.status(r));
            assertEquals(r, "0", Http.header(r, "Content-Length"));
            assertEquals(r, null, Http.header(r, "Content-Type"));
        } finally {
            Http.stopQuietly(bare);
        }
    }
}
