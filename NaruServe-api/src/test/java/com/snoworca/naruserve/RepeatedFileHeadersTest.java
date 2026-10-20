package com.snoworca.naruserve;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.TestApp;
import java.io.File;
import java.io.IOException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Repeated conditional and Range fields keep the same contract on all file response entry points. */
public class RepeatedFileHeadersTest {
    @Rule public final TemporaryFolder tmp = new TemporaryFolder();
    private static final String[] PATHS = {"/static/a.txt", "/file", "/download"};

    private TestApp app() throws IOException {
        File file = StaticConditionalTest.bytes(new File(tmp.getRoot(), "a.txt"), 1234);
        TestApp t = TestApp.create();
        t.app().staticFiles("/static", tmp.getRoot(), o -> o.cache("*.txt", CachePolicy.noCache()));
        t.app().get("/file", (req, res) -> res.file(file));
        t.app().get("/download", (req, res) -> res.download(file, "a.txt"));
        return t;
    }

    // FR-STATIC-009 AC-2: all repeated If-None-Match fields form one list; weak tags and wildcard match.
    @Test public void FR_STATIC_009_AC2_laterMatchingFieldIs304ForGetAndHeadOnEveryEntryPoint() throws IOException {
        TestApp t = app();
        for (String path : PATHS) {
            for (String method : new String[] {"GET", "HEAD"}) {
                for (String later : new String[] {StaticConditionalTest.ETAG, "W/" + StaticConditionalTest.ETAG, "*"}) {
                    ClientResponse r = t.send(method, path, "If-None-Match", "\"miss\"", "if-none-match", later,
                            "If-Modified-Since", "Mon, 13 Nov 2023 00:00:00 GMT", "Range", "bytes=0-9");
                    assertEquals(method + " " + path + " " + later, 304, r.status());
                    assertEquals(0, r.body().length);
                    assertEquals(StaticConditionalTest.ETAG, r.header("ETag"));
                    assertNull(r.header("Content-Range"));
                    if (path.startsWith("/static")) assertEquals("no-cache", r.header("Cache-Control"));
                }
                ClientResponse miss = t.send(method, path, "If-None-Match", "\"miss1\"",
                        "If-None-Match", "\"miss2\"", "If-Modified-Since", StaticConditionalTest.LAST_MODIFIED);
                assertEquals(200, miss.status());
                assertEquals(method.equals("HEAD") ? 0 : 1234, miss.body().length);
            }
        }
    }

    // FR-STATIC-010 AC-3: repeated Range fields use the full 200 fallback, including an unsatisfiable first field.
    // FR-STATIC-009 AC-4: HEAD remains bodyless with the full GET headers.
    @Test public void FR_STATIC_010_AC3_repeatedRangesReturnFullRepresentationOnEveryEntryPoint() throws IOException {
        TestApp t = app();
        for (String path : PATHS) {
            ClientResponse full = t.send("GET", path);
            assertEquals(206, t.send("GET", path, "Range", "bytes=0-9").status());
            for (String first : new String[] {"bytes=0-9", "bytes=9999-", "bytes=abc"}) {
                for (String method : new String[] {"GET", "HEAD"}) {
                    ClientResponse r = t.send(method, path, "Range", first, "rAnGe", "bytes=20-29");
                    assertEquals(method + " " + path + " " + first, 200, r.status());
                    assertNull(r.header("Content-Range"));
                    assertEquals("1234", r.header("Content-Length"));
                    assertArrayEquals(method.equals("HEAD") ? new byte[0] : full.body(), r.body());
                    assertEquals(full.header("ETag"), r.header("ETag"));
                }
            }
        }
    }
}
