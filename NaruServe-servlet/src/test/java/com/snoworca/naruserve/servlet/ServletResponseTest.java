package com.snoworca.naruserve.servlet;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.RestApp;
import java.io.ByteArrayInputStream;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/** Response writing on Jetty: status, headers and body pass through; bodyless answers carry no bytes. */
public class ServletResponseTest {

    /** Larger than Jetty's 32 KiB output buffer, so the unknown-length body is framed chunked. */
    private static final byte[] BIG = SharedApp.pattern(200000);

    @Rule
    public final ContainerRule container = new ContainerRule();

    private static void routes(RestApp app) {
        app.get("/created", (req, res) -> res.status(201).header("X-A", "1").send("ok"));
        app.get("/stream", (req, res) -> res.stream(new ByteArrayInputStream(BIG), "application/octet-stream"));
        app.get("/boom", (req, res) -> {
            throw new IllegalStateException("boom");
        });
        app.get("/empty", (req, res) -> res.status(204).end());
        app.get("/same", (req, res) -> res.status(304).end());
    }

    @Before
    public void start() throws Exception {
        SharedApp.onJetty(container, "/*", ServletResponseTest::routes);
    }

    private SharedApp.Reply send(String method, String target) throws Exception {
        return SharedApp.http(container.port(), method, target, null);
    }

    // SDS-AC-7; FR-SVL-003 AC-1
    @Test
    public void sdsAc7_FR_SVL_003_AC1_statusHeaderAndBodyPassWithTheCoreContentLength() throws Exception {
        SharedApp.Reply r = send("GET", "/created");
        assertEquals(201, r.status);
        assertEquals("1", r.header("X-A"));
        assertEquals("ok", r.text());
        assertEquals("2", r.header("Content-Length"));
    }

    // SDS-AC-7; FR-SVL-003 AC-1
    @Test
    public void sdsAc7_FR_SVL_003_AC1_unknownLengthStreamArrivesIntactAndChunked() throws Exception {
        SharedApp.Reply r = SharedApp.keepAliveGet(container.port(), "/stream");
        assertEquals(200, r.status);
        assertEquals("chunked", r.header("Transfer-Encoding"));
        assertNull(r.header("Content-Length"));
        assertArrayEquals(BIG, r.body);
    }

    // SDS-AC-7; FR-SVL-003 AC-1 — no container error page for the core's 404 and 500
    @Test
    public void sdsAc7_FR_SVL_003_AC1_core404And500KeepTheStandardJsonBody() throws Exception {
        for (String[] c : new String[][] {{"/nope", "404", "NOT_FOUND"}, {"/boom", "500", "INTERNAL_ERROR"}}) {
            SharedApp.Reply r = send("GET", c[0]);
            assertEquals(Integer.parseInt(c[1]), r.status);
            assertTrue(r.text(), r.text().startsWith("{\"error\":{\"code\":\"" + c[2] + "\""));
            assertFalse(r.text(), r.text().toLowerCase().contains("<html"));
            assertEquals(Integer.toString(r.body.length), r.header("Content-Length"));
        }
    }

    // SDS-AC-8; FR-SVL-003 AC-2
    @Test
    public void sdsAc8_FR_SVL_003_AC2_headKeepsTheGetLengthAndSendsNoBody() throws Exception {
        SharedApp.Reply r = send("HEAD", "/created");
        assertEquals(201, r.status);
        assertEquals("2", r.header("Content-Length"));
        assertEquals(0, r.body.length);
    }

    // SDS-AC-8; FR-SVL-003 AC-2
    @Test
    public void sdsAc8_FR_SVL_003_AC2_204And304CarryNoBody() throws Exception {
        assertEquals(204, send("GET", "/empty").status);
        assertEquals(0, send("GET", "/empty").body.length);
        assertEquals(304, send("GET", "/same").status);
        assertEquals(0, send("GET", "/same").body.length);
    }
}
