package com.snoworca.naruserve;

import com.snoworca.naruserve.testing.RecordingExchange;
import static com.snoworca.naruserve.StaticConditionalTest.bytes;
import static com.snoworca.naruserve.StaticTraversalTest.write;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.internal.DefaultResponse;
import com.snoworca.naruserve.internal.Dispatcher;
import com.snoworca.naruserve.internal.ResponseState;
import com.snoworca.naruserve.routing.Router;
import com.snoworca.naruserve.routing.RouterPolicy;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.TestApp;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * res.file and res.download. Tests named sdsAc4_FR_STATIC_006 cite
 * docs/sds/2026-10-19.naruserve.naruserve-v1-wave-6-7.sds.md; the others cite
 * docs/sds/2026-10-19.naruserve.naruserve-v1-wave-6-8.sds.md.
 */
public class FileResponseTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    // SDS-AC-4; FR-STATIC-006 AC-5
    @Test
    public void sdsAc4_FR_STATIC_006_AC5_resFileOnLinkDirectoryOrMissingIs404StandardBody() throws IOException {
        File base = tmp.getRoot();
        File real = new File(base, "real.html");
        write(real, "REAL");
        File link = new File(base, "link.txt");
        Files.createSymbolicLink(link.toPath(), real.toPath());
        File dir = new File(base, "dir");
        assertTrue(dir.mkdir());
        File missing = new File(base, "missing.txt");
        TestApp t = TestApp.create();
        t.app().get("/real", (req, res) -> res.file(real));
        t.app().get("/link", (req, res) -> res.file(link));
        t.app().get("/dir", (req, res) -> res.file(dir));
        t.app().get("/missing", (req, res) -> res.file(missing));
        ClientResponse ok = t.send("GET", "/real");
        assertEquals(200, ok.status());
        assertEquals("REAL", ok.text());
        assertEquals("text/html; charset=UTF-8", ok.header("Content-Type"));
        for (String p : new String[] {"/link", "/dir", "/missing"}) {
            ClientResponse r = t.send("GET", p);
            assertEquals(p, 404, r.status());
            // CON-APP-010 AC-4 AC-5; FR-APP-024 AC-3: final rendering changes, file safety never does.
            JsonBodyAcceptanceTest.assertDefaultError(404, HttpException.class, r);
            assertFalse(p, r.text().contains("REAL"));
            assertNull(r.header("Content-Disposition"));
        }
    }

    // SDS-AC-11; FR-STATIC-011 AC-1
    @Test
    public void sdsAc11_FR_STATIC_011_AC1_resFileSendsTheMimeTypeAndActsLikeAStaticFile() throws IOException {
        File pdf = bytes(new File(tmp.getRoot(), "report.pdf"), 1000);
        TestApp t = TestApp.create();
        t.app().get("/r", (req, res) -> res.file(pdf));
        t.app().get("/d", (req, res) -> res.download(pdf, "report.pdf"));
        ClientResponse r = t.send("GET", "/r");
        assertEquals(200, r.status());
        assertEquals("application/pdf", r.header("Content-Type"));
        assertNull(r.header("Content-Disposition"));
        assertEquals(1000, r.body().length);
        for (String p : new String[] {"/r", "/d"}) {
            ClientResponse part = t.send("GET", p, "Range", "bytes=10-19");
            assertEquals(p, 206, part.status());
            assertEquals(p, "bytes 10-19/1000", part.header("Content-Range"));
            assertEquals(p, 10, part.body().length);
            ClientResponse nm = t.send("GET", p, "If-None-Match", r.header("ETag"));
            assertEquals(p, 304, nm.status());
            assertEquals(p, 0, nm.body().length);
        }
        assertEquals("application/pdf", t.send("GET", "/d").header("Content-Type"));
    }

    // FR-STATIC-011 AC-2: a failed download leaves no Content-Disposition for the onError answer
    @Test
    public void FR_STATIC_011_AC2_failedDownloadDropsDispositionBeforeOnError() {
        File missing = new File(tmp.getRoot(), "missing.pdf");
        TestApp t = TestApp.create();
        t.app().get("/d", (req, res) -> res.download(missing, "x.pdf"));
        // CON-APP-010 AC-4 AC-5; FR-APP-024 AC-1 AC-5: configured error body remains, at its ordered position.
        t.app().onError((error, req, res) -> res.status(((HttpException) error).status()).send("gone"));
        ClientResponse r = t.send("GET", "/d");
        assertEquals(404, r.status());
        assertEquals("gone", r.text());
        assertNull(r.header("Content-Disposition"));
    }

    // SDS-AC-5; FR-STATIC-009 AC-2
    @Test
    public void sdsAc5_FR_STATIC_009_AC2_otherMethodsIgnoreConditionalsAndRange() throws IOException {
        File f = bytes(new File(tmp.getRoot(), "a.bin"), 1000);
        TestApp t = TestApp.create();
        t.app().post("/p", (req, res) -> res.file(f));
        String etag = "\"3e8-18bcfe5687b\"";
        ClientResponse r = t.send("POST", "/p", "If-None-Match", etag, "Range", "bytes=0-9", "If-Range", etag,
                "If-Modified-Since", "Wed, 15 Nov 2023 00:00:00 GMT");
        assertEquals(200, r.status());
        assertEquals(1000, r.body().length);
        assertNull(r.header("Content-Range"));
    }

    // SDS-AC-10; FR-STATIC-011 AC-2
    @Test
    public void sdsAc10_FR_STATIC_011_AC2_downloadDispositionIsSanitised() throws IOException {
        File f = bytes(new File(tmp.getRoot(), "a.bin"), 3);
        TestApp t = TestApp.create();
        t.app().get("/plain", (req, res) -> res.download(f, "report.pdf"));
        t.app().get("/evil", (req, res) -> res.download(f, "a\r\nSet-Cookie: x=1\".pdf"));
        t.app().get("/ko", (req, res) -> res.download(f, "보고서.pdf"));
        assertEquals("attachment; filename=\"report.pdf\"", t.send("GET", "/plain").header("Content-Disposition"));
        ClientResponse evil = t.send("GET", "/evil");
        String v = evil.header("Content-Disposition");
        assertTrue(v, v.startsWith("attachment; filename=\""));
        String name = v.substring("attachment; filename=\"".length(), v.length() - 1);
        for (char c : name.toCharArray()) {
            assertTrue(v, c >= 0x20 && c != 0x7F && c != '"' && c != '\\');
        }
        assertFalse(v, v.contains("filename*"));
        for (Map.Entry<String, String> h : evil.headers()) {
            assertFalse(h.getKey(), h.getKey().equalsIgnoreCase("Set-Cookie"));
        }
        String ko = "attachment; filename=\"___.pdf\"; filename*=UTF-8''%EB%B3%B4%EA%B3%A0%EC%84%9C.pdf";
        assertEquals(ko, t.send("GET", "/ko").header("Content-Disposition"));
        assertEquals(ko, DefaultResponse.disposition("보고서.pdf"));
        assertEquals("attachment; filename=\"ab.txt\"", DefaultResponse.disposition("a\u0001\u0085b\\.txt"));
    }

    // SDS-AC-12; FR-STATIC-011 AC-4
    @Test
    public void sdsAc12_FR_STATIC_011_AC4_fileBodiesGoThroughCommitWithTheRangeLength() throws IOException {
        File f = bytes(new File(tmp.getRoot(), "a.bin"), 1000);
        Router<RouteHandler> router = new Router<>(RouterPolicy.strict());
        router.add(Method.GET, "/f", (req, res) -> res.file(f));
        router.add(Method.GET, "/d", (req, res) -> res.download(f, "a.bin"));
        router.freeze();
        for (String p : new String[] {"/f", "/d"}) {
            RecordingExchange full = new RecordingExchange("GET", p);
            new Dispatcher(router).handle(full);
            assertEquals(p, 200, full.status);
            assertEquals(p, 1000, full.contentLength);
            assertEquals(p, 1000, full.body.size());
            RecordingExchange part = new RecordingExchange("GET", p, "Range", "bytes=100-199");
            new Dispatcher(router).handle(part);
            assertEquals(p, 206, part.status);
            assertEquals(p, 100, part.contentLength);
            byte[] b = part.body.toByteArray();
            assertEquals(p, 100, b.length);
            assertEquals(p, (byte) 100, b[0]);
            assertEquals(p, (byte) 199, b[99]);
            assertEquals(p, 0, full.sendFiles.get() + part.sendFiles.get());
        }
        // The in-memory adapter reports -2 for a response started by sendFile instead of commit.
        TestApp t = TestApp.create();
        t.app().staticFiles("/", tmp.getRoot());
        assertEquals(1000, t.send("GET", "/a.bin").contentLength());
        assertEquals(100, t.send("GET", "/a.bin", "Range", "bytes=0-99").contentLength());
    }

    // SDS-AC-12; FR-STATIC-011 AC-4
    @Test
    public void sdsAc12_FR_STATIC_011_AC4_skipLoopsAndFailsWhenTheSourceIsShort() throws IOException {
        // skip() always answers 0, so the body has to read to reach the range start.
        InputStream stubborn = new ByteArrayInputStream(new byte[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9}) {
            @Override
            public synchronized long skip(long n) {
                return 0;
            }
        };
        RecordingExchange ok = new RecordingExchange("GET", "/x");
        DefaultResponse res = new DefaultResponse(ok, false);
        res.sendBody(206, stubborn, 4, 3);
        res.runTransfer();
        assertEquals(3, ok.contentLength);
        assertEquals("[4, 5, 6]", Arrays.toString(ok.body.toByteArray()));
        assertEquals(ResponseState.COMPLETED, res.state());

        RecordingExchange shortSkip = new RecordingExchange("GET", "/x");
        try {
            new DefaultResponse(shortSkip, false).sendBody(206, new ByteArrayInputStream(new byte[5]), 10, 3);
            fail("expected IOException");
        } catch (IOException expected) {
            assertEquals(0, shortSkip.commits.get());
        }

        RecordingExchange shortRead = new RecordingExchange("GET", "/x");
        DefaultResponse r3 = new DefaultResponse(shortRead, false);
        r3.sendBody(200, new ByteArrayInputStream(new byte[5]), 0, 30);
        r3.runTransfer();
        assertEquals(ResponseState.ABORTED, r3.state());
        assertEquals(0, shortRead.finishes.get());
    }
}
