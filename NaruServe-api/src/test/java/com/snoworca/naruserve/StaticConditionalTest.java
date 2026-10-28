package com.snoworca.naruserve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.TestApp;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Validators, conditional 304, HEAD, nosniff and the directory listing (T30). SDS-AC-n cites
 * docs/sds/2026-10-19.naruserve.naruserve-v1-wave-6-8.sds.md.
 */
public class StaticConditionalTest {

    static final long MODIFIED = 1700000000123L;
    static final String ETAG = "\"4d2-18bcfe5687b\"";
    static final String LAST_MODIFIED = "Tue, 14 Nov 2023 22:13:20 GMT";

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    /** A file of {@code size} bytes 0, 1, 2, ... (mod 256) modified at {@link #MODIFIED}. */
    static File bytes(File f, int size) throws IOException {
        f.getParentFile().mkdirs();
        byte[] b = new byte[size];
        for (int i = 0; i < size; i++) {
            b[i] = (byte) i;
        }
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(b);
        }
        assertTrue(f.setLastModified(MODIFIED));
        return f;
    }

    private TestApp app() throws IOException {
        bytes(new File(tmp.getRoot(), "a.txt"), 1234);
        TestApp t = TestApp.create();
        t.app().staticFiles("/", tmp.getRoot(), o -> o.cache("*.txt", CachePolicy.noCache()));
        return t;
    }

    // FR-STATIC-009 AC-1
    @Test
    public void FR_STATIC_009_AC1_staticFileAndDownloadCarryEtagLastModifiedAndAcceptRanges() throws IOException {
        TestApp t = app();
        File f = new File(tmp.getRoot(), "a.txt");
        t.app().get("/file", (req, res) -> res.file(f));
        t.app().get("/download", (req, res) -> res.download(f, "a.txt"));
        for (String p : new String[] {"/a.txt", "/file", "/download"}) {
            ClientResponse r = t.send("GET", p);
            assertEquals(p, 200, r.status());
            assertEquals(p, ETAG, r.header("ETag"));
            assertEquals(p, LAST_MODIFIED, r.header("Last-Modified"));
            assertEquals(p, "bytes", r.header("Accept-Ranges"));
            assertEquals(p, 1234, r.body().length);
        }
    }

    // FR-STATIC-009 AC-2
    @Test
    public void FR_STATIC_009_AC2_matchingIfNoneMatchIs304AndWinsOverIfModifiedSince() throws IOException {
        TestApp t = app();
        String older = "Mon, 13 Nov 2023 00:00:00 GMT";
        for (String inm : new String[] {ETAG, "\"zz\", " + ETAG, "W/" + ETAG, "*"}) {
            ClientResponse r = t.send("GET", "/a.txt", "If-None-Match", inm, "If-Modified-Since", older);
            assertEquals(inm, 304, r.status());
            assertEquals(inm, 0, r.body().length);
            assertEquals(inm, ETAG, r.header("ETag"));
            assertEquals(inm, "no-cache", r.header("Cache-Control"));
        }
        ClientResponse miss = t.send("GET", "/a.txt", "If-None-Match", "\"other\"",
                "If-Modified-Since", LAST_MODIFIED);
        assertEquals(200, miss.status());
        assertEquals(1234, miss.body().length);
    }

    // SDS-AC-6; FR-STATIC-009 AC-3
    @Test
    public void sdsAc6_FR_STATIC_009_AC3_ifModifiedSinceComparesWholeSeconds() throws IOException {
        TestApp t = app();
        String[] notModified = {LAST_MODIFIED, "Wed, 15 Nov 2023 00:00:00 GMT", "Tuesday, 14-Nov-23 22:13:20 GMT",
            "Tue Nov 14 22:13:20 2023"};
        for (String d : notModified) {
            ClientResponse r = t.send("GET", "/a.txt", "If-Modified-Since", d);
            assertEquals(d, 304, r.status());
            assertEquals(d, 0, r.body().length);
        }
        for (String d : new String[] {"Tue, 14 Nov 2023 22:13:19 GMT", "garbage", ""}) {
            ClientResponse r = t.send("GET", "/a.txt", "If-Modified-Since", d);
            assertEquals(d, 200, r.status());
            assertEquals(d, 1234, r.body().length);
        }
    }

    // FR-STATIC-009 AC-4
    @Test
    public void FR_STATIC_009_AC4_headCarriesTheGetHeadersAndNoBody() throws IOException {
        TestApp t = app();
        ClientResponse get = t.send("GET", "/a.txt");
        ClientResponse head = t.send("HEAD", "/a.txt");
        assertEquals(200, head.status());
        assertEquals(0, head.body().length);
        for (String h : new String[] {"Content-Type", "Content-Length", "ETag", "Last-Modified", "Accept-Ranges",
            "Cache-Control", "X-Content-Type-Options"}) {
            assertEquals(h, get.header(h), head.header(h));
        }
        assertEquals("1234", head.header("Content-Length"));
    }

    // SDS-AC-2; FR-STATIC-003 AC-3
    @Test
    public void sdsAc2_FR_STATIC_003_AC3_nosniffOnEveryStaticOutcome() throws IOException {
        TestApp t = app();
        assertTrue(new File(tmp.getRoot(), "list").mkdir());
        t.app().staticFiles("/l", new File(tmp.getRoot(), "list"), o -> o.directoryListing(true));
        ClientResponse[] all = {
            t.send("GET", "/a.txt"),
            t.send("GET", "/a.txt", "Range", "bytes=0-9"),
            t.send("GET", "/a.txt", "If-None-Match", ETAG),
            t.send("GET", "/a.txt", "Range", "bytes=5000-"),
            t.send("HEAD", "/a.txt"),
            t.send("GET", "/l/"),
        };
        int[] status = {200, 206, 304, 416, 200, 200};
        for (int i = 0; i < all.length; i++) {
            assertEquals(i + "", status[i], all[i].status());
            assertEquals(i + "", "nosniff", all[i].header("X-Content-Type-Options"));
        }
    }

    // FR-STATIC-003 AC-1
    @Test
    public void FR_STATIC_003_AC1_directoryWithoutIndexIs404WhileListingIsOff() throws IOException {
        assertTrue(new File(tmp.getRoot(), "empty").mkdir());
        TestApp t = TestApp.create();
        t.app().staticFiles("/", tmp.getRoot());
        ClientResponse r = t.send("GET", "/empty/");
        assertEquals(404, r.status());
        // CON-APP-010 AC-4; FR-APP-024 AC-3: a static miss reaches the new common HTML finalhandler.
        assertTrue(r.text(),r.text().contains("<pre>Cannot GET /empty/</pre>"));
        assertEquals("text/html; charset=utf-8",r.header("Content-Type"));
    }

    // FR-STATIC-003 AC-2
    @Test
    public void FR_STATIC_003_AC2_listingIsEscapedSortedAndRefusesLinkedDirectories() throws IOException {
        File files = new File(tmp.getRoot(), "files");
        for (String n : new String[] {"b.txt", "a b.txt", "<x>&.txt", ".hidden"}) {
            bytes(new File(files, n), 1);
        }
        Files.createSymbolicLink(new File(files, "link.txt").toPath(), new File(files, "b.txt").toPath());
        File real = new File(tmp.getRoot(), "real");
        assertTrue(real.mkdir());
        Files.createSymbolicLink(new File(tmp.getRoot(), "linked").toPath(), real.toPath());
        TestApp t = TestApp.create();
        t.app().staticFiles("/", tmp.getRoot(), o -> o.directoryListing(true));
        ClientResponse r = t.send("GET", "/files/");
        assertEquals(200, r.status());
        assertEquals("text/html; charset=UTF-8", r.header("Content-Type"));
        String html = new String(r.body(), StandardCharsets.UTF_8);
        int x = html.indexOf("href=\"%3Cx%3E%26.txt\">&lt;x&gt;&amp;.txt<");
        int ab = html.indexOf("href=\"a%20b.txt\"");
        int b = html.indexOf("href=\"b.txt\"");
        assertTrue(html, x > 0 && ab > x && b > ab);
        assertTrue(html, !html.contains(".hidden") && !html.contains("link.txt"));
        assertEquals(400, t.send("GET", "/files/..%2F").status());
        assertEquals(404, t.send("GET", "/linked/").status());
        assertNull(t.send("GET", "/files/").header("Content-Disposition"));
    }
}
