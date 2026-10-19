package com.snoworca.naruserve;

import static com.snoworca.naruserve.StaticConditionalTest.bytes;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.TestApp;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Single byte ranges, 416, ignored ranges and If-Range (T31). SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-6-8.sds.md. */
public class StaticRangeTest {

    static final String ETAG = "\"3e8-18bcfe5687b\"";

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    private TestApp app() throws IOException {
        bytes(new File(tmp.getRoot(), "k.bin"), 1000);
        TestApp t = TestApp.create();
        t.app().staticFiles("/", tmp.getRoot(), o -> o.cache("*.bin", CachePolicy.publicFor(1, TimeUnit.HOURS)));
        return t;
    }

    private static void assertSlice(ClientResponse r, int from, int to) {
        assertEquals(206, r.status());
        assertEquals("bytes " + from + "-" + to + "/1000", r.header("Content-Range"));
        assertEquals(String.valueOf(to - from + 1), r.header("Content-Length"));
        byte[] b = r.body();
        assertEquals(to - from + 1, b.length);
        for (int i = 0; i < b.length; i++) {
            assertEquals((byte) (from + i), b[i]);
        }
    }

    // FR-STATIC-010 AC-1
    @Test
    public void FR_STATIC_010_AC1_singleRangesGive206AndClipTheEnd() throws IOException {
        TestApp t = app();
        assertSlice(t.send("GET", "/k.bin", "Range", "bytes=0-99"), 0, 99);
        assertSlice(t.send("GET", "/k.bin", "Range", "bytes=900-"), 900, 999);
        assertSlice(t.send("GET", "/k.bin", "Range", "bytes=-50"), 950, 999);
        assertSlice(t.send("GET", "/k.bin", "Range", "bytes=990-5000"), 990, 999);
    }

    // SDS-AC-8; FR-STATIC-010 AC-2
    @Test
    public void sdsAc8_FR_STATIC_010_AC2_unsatisfiableRangeIs416WithStarContentRange() throws IOException {
        TestApp t = app();
        for (String range : new String[] {"bytes=1000-", "bytes=2000-2100"}) {
            ClientResponse r = t.send("GET", "/k.bin", "Range", range);
            assertEquals(range, 416, r.status());
            assertEquals(range, "bytes */1000", r.header("Content-Range"));
            assertEquals(range, ETAG, r.header("ETag"));
            assertEquals(range, StaticConditionalTest.LAST_MODIFIED, r.header("Last-Modified"));
            String body = new String(r.body(), StandardCharsets.UTF_8);
            assertTrue(body, body.startsWith("{\"error\":{\"code\":\"HTTP_416\""));
        }
    }

    // FR-STATIC-010 AC-3
    @Test
    public void FR_STATIC_010_AC3_multipleOrMalformedRangesGiveTheFull200() throws IOException {
        TestApp t = app();
        for (String range : new String[] {"bytes=0-9,20-29", "bytes=abc", "items=0-9", "bytes=50-10"}) {
            ClientResponse r = t.send("GET", "/k.bin", "Range", range);
            assertEquals(range, 200, r.status());
            assertEquals(range, 1000, r.body().length);
            assertEquals(range, null, r.header("Content-Range"));
        }
    }

    // SDS-AC-7; FR-STATIC-010 AC-1
    @Test
    public void sdsAc7_FR_STATIC_010_AC1_headIgnoresRange() throws IOException {
        TestApp t = app();
        ClientResponse r = t.send("HEAD", "/k.bin", "Range", "bytes=0-99");
        assertEquals(200, r.status());
        assertEquals("1000", r.header("Content-Length"));
        assertEquals(null, r.header("Content-Range"));
        assertEquals(0, r.body().length);
    }

    // SDS-AC-9; FR-STATIC-010 AC-4
    @Test
    public void sdsAc9_FR_STATIC_010_AC4_ifRangeHoldsOnlyForTheCurrentStrongValidator() throws IOException {
        TestApp t = app();
        assertSlice(t.send("GET", "/k.bin", "Range", "bytes=0-99", "If-Range", ETAG), 0, 99);
        assertSlice(t.send("GET", "/k.bin", "Range", "bytes=0-99", "If-Range", StaticConditionalTest.LAST_MODIFIED),
                0, 99);
        for (String ir : new String[] {"\"other\"", "Mon, 13 Nov 2023 00:00:00 GMT", "W/" + ETAG,
            "Wed, 15 Nov 2023 00:00:00 GMT"}) {
            ClientResponse r = t.send("GET", "/k.bin", "Range", "bytes=0-99", "If-Range", ir);
            assertEquals(ir, 200, r.status());
            assertEquals(ir, 1000, r.body().length);
        }
    }

    // FR-STATIC-010 AC-5
    @Test
    public void FR_STATIC_010_AC5_rangeAndCacheCombined() throws IOException {
        TestApp t = app();
        ClientResponse full = t.send("GET", "/k.bin");
        ClientResponse part = t.send("GET", "/k.bin", "Range", "bytes=0-99");
        assertEquals(206, part.status());
        for (String h : new String[] {"ETag", "Last-Modified", "Cache-Control"}) {
            assertEquals(h, full.header(h), part.header(h));
        }
        assertEquals("public, max-age=3600", part.header("Cache-Control"));
        ClientResponse nm = t.send("GET", "/k.bin", "Range", "bytes=0-99", "If-None-Match", ETAG);
        assertEquals(304, nm.status());
        assertEquals(0, nm.body().length);
        assertSlice(t.send("GET", "/k.bin", "Range", "bytes=0-99", "If-Range", ETAG), 0, 99);
    }
}
