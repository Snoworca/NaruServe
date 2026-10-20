package com.snoworca.naruserve.servlet;

import static com.snoworca.naruserve.testing.TestSupport.headers;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.TestSupport;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.Rule;
import org.junit.Test;

/** sendFile chunking, and res.file / res.download with Range and 416 equal to the in-memory adapter. */
public class ServletSendFileTest {

    @Rule
    public final ContainerRule container = new ContainerRule();

    // SDS-AC-10; FR-SVL-003 AC-4
    @Test
    public void sdsAc10_FR_SVL_003_AC4_sendFileCopiesExactlyLengthInChunksOfAtMost64KiB() throws Exception {
        byte[] data = SharedApp.pattern(300000);
        File f = File.createTempFile("naruserve-sendfile", ".bin");
        f.deleteOnExit();
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(data);
        }
        ServletAbortTest.FakeResponse res = new ServletAbortTest.FakeResponse();
        ServletExchange ex = new ServletExchange(ServletAbortTest.request(), res.proxy, "/");
        ex.sendFile(206, headers("Content-Length", "250000"), f, 1234, 250000);

        assertArrayEquals(Arrays.copyOfRange(data, 1234, 1234 + 250000), res.body.toByteArray());
        assertTrue(res.writes.toString(), res.writes.size() >= 4);
        for (int n : res.writes) {
            assertTrue(res.writes.toString(), n > 0 && n <= 65536);
        }
        assertEquals("setStatus[206]", res.calls.get(0));
        assertEquals("setHeader[Content-Length, 250000]", res.calls.get(1));
    }

    // SDS-AC-10; FR-SVL-003 AC-4
    @Test
    public void sdsAc10_FR_SVL_003_AC4_abortStopsSendFileBeforeTheNextChunk() throws Exception {
        File f = File.createTempFile("naruserve-sendfile", ".bin");
        f.deleteOnExit();
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(SharedApp.pattern(200000));
        }
        ServletAbortTest.FakeResponse res = new ServletAbortTest.FakeResponse();
        ServletExchange ex = new ServletExchange(ServletAbortTest.request(), res.proxy, "/");
        ex.abort();
        try {
            ex.sendFile(200, headers(), f, 0, 200000);
            throw new AssertionError("sendFile after abort did not throw");
        } catch (java.io.IOException expected) {
            assertEquals(0, res.body.size());
        }
    }

    // SDS-AC-10; FR-SVL-003 AC-4 (review S2-002): the range is checked before the abort and single-start rules, as in
    // TinyHttpdExchange, and a bad range starts nothing
    @Test
    public void sdsAc10_FR_SVL_003_AC4_badRangeWinsOverAbortAndSingleStart() throws Exception {
        File f = File.createTempFile("naruserve-sendfile", ".bin");
        f.deleteOnExit();
        ServletAbortTest.FakeResponse res = new ServletAbortTest.FakeResponse();
        ServletExchange started = new ServletExchange(ServletAbortTest.request(), res.proxy, "/");
        started.commit(200, headers(), 0);
        ServletExchange aborted = new ServletExchange(ServletAbortTest.request(), res.proxy, "/");
        aborted.abort();
        for (ServletExchange ex : new ServletExchange[] {started, aborted}) {
            try {
                ex.sendFile(200, headers(), f, 1, 5);
                throw new AssertionError("a bad range did not throw");
            } catch (IllegalArgumentException expected) {
                // the range first
            }
        }
        ServletExchange fresh = new ServletExchange(ServletAbortTest.request(), res.proxy, "/");
        try {
            fresh.sendFile(200, headers(), f, 1, 5);
            throw new AssertionError("a bad range did not throw");
        } catch (IllegalArgumentException expected) {
            fresh.commit(200, headers(), 0); // still unstarted
        }
    }

    // SDS-AC-10; FR-SVL-003 AC-4
    @Test
    public void sdsAc10_FR_SVL_003_AC4_servletExchangeReferencesNoJavaNioFile() throws Exception {
        try (InputStream in = ServletExchange.class.getResourceAsStream("ServletExchange.class")) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf)) > 0; ) {
                b.write(buf, 0, n);
            }
            assertFalse(new String(b.toByteArray(), StandardCharsets.ISO_8859_1).contains("java/nio/file"));
        }
    }

    // SDS-AC-10; FR-SVL-003 AC-4
    @Test
    public void sdsAc10_FR_SVL_003_AC4_fileAndDownloadWithRangeAnd416EqualInMemory() throws Exception {
        File file = SharedApp.tempFile();
        SharedApp.onJetty(container, "/*", app -> SharedApp.define(app, file));
        InMemoryHttpAdapter memory = new InMemoryHttpAdapter();
        SharedApp.listen(memory, app -> SharedApp.define(app, file));
        String[][] cases = {
            {"/file"}, {"/file", "Range", "bytes=10-99"}, {"/file", "Range", "bytes=99990-"},
            {"/file", "Range", "bytes=999999-"}, {"/download"}, {"/download", "Range", "bytes=0-0"}};
        boolean saw206 = false;
        boolean saw416 = false;
        for (String[] c : cases) {
            String[] h = Arrays.copyOfRange(c, 1, c.length);
            SharedApp.Reply j = SharedApp.http(container.port(), "GET", c[0], null, h);
            SharedApp.Reply m = SharedApp.inMemory(memory, "GET", c[0], null, h);
            String what = Arrays.toString(c);
            assertEquals(what, m.status, j.status);
            assertEquals(what, m.header("Content-Range"), j.header("Content-Range"));
            assertEquals(what, m.header("Content-Disposition"), j.header("Content-Disposition"));
            if (j.status == 416) {
                // The standard error body carries a fresh request id per response.
                assertEquals(what, TestSupport.maskRequestId(m.text()), TestSupport.maskRequestId(j.text()));
            } else {
                assertArrayEquals(what, m.body, j.body);
            }
            saw206 |= j.status == 206;
            saw416 |= j.status == 416;
        }
        assertTrue(saw206 && saw416);
    }
}
