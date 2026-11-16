package com.snoworca.naruserve;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.TestApp;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/** res.relay keeps the app's headers, frames from its length argument and never takes the app's defaultCache. */
public class ResponseRelayTest {

    private static final byte[] FIVE = "hello".getBytes(StandardCharsets.US_ASCII);

    private static ByteArrayInputStream five() {
        return new ByteArrayInputStream(FIVE);
    }

    // FR-HTTP-036 AC-1: no Content-Type set, relay(in, 5) sends no Content-Type, Content-Length 5 and the 5 bytes
    @Test
    public void FR_HTTP_036_AC1_relaySetsNoContentTypeAndFramesFromTheLength() {
        TestApp t = TestApp.create();
        t.app().get("/r", (req, res) -> res.relay(five(), 5));
        ClientResponse r = t.send("GET", "/r");
        assertEquals(200, r.status());
        assertNull(r.header("Content-Type"));
        assertEquals("5", r.header("Content-Length"));
        assertArrayEquals(FIVE, r.body());
    }

    // FR-HTTP-036 AC-2: an app-set Content-Type is sent exactly as set, with no charset added
    @Test
    public void FR_HTTP_036_AC2_appContentTypeIsSentUnchanged() {
        TestApp t = TestApp.create();
        t.app().get("/r", (req, res) -> {
            res.header("Content-Type", "application/x-custom");
            res.relay(five(), 5);
        });
        ClientResponse r = t.send("GET", "/r");
        assertEquals("application/x-custom", r.header("Content-Type"));
        assertEquals(1, count(r, "Content-Type"));
        assertArrayEquals(FIVE, r.body());
    }

    // FR-HTTP-036 AC-3: app-set Content-Length and Transfer-Encoding are dropped; framing comes from contentLength
    @Test
    public void FR_HTTP_036_AC3_appFramingHeadersAreDropped() {
        TestApp t = TestApp.create();
        t.app().get("/r", (req, res) -> {
            res.header("Content-Length", "99");
            res.header("Transfer-Encoding", "chunked");
            res.relay(five(), 5);
        });
        ClientResponse r = t.send("GET", "/r");
        assertEquals("5", r.header("Content-Length"));
        assertEquals(1, count(r, "Content-Length"));
        assertNull(r.header("Transfer-Encoding"));
        assertArrayEquals(FIVE, r.body());
    }

    // FR-HTTP-036 AC-4: header, append and addCookie headers arrive as set, plus only Content-Length from the core
    @Test
    public void FR_HTTP_036_AC4_committedHeaderListIsAppHeadersPlusContentLength() {
        TestApp t = TestApp.create();
        t.app().get("/r", (req, res) -> {
            res.header("X-Upstream", "a");
            res.append("Vary", "Accept");
            res.append("Vary", "Origin");
            res.addCookie(new Cookie("sid", "42"));
            res.relay(five(), 5);
        });
        List<Map.Entry<String, String>> expected = new ArrayList<>();
        expected.add(new SimpleImmutableEntry<>("X-Upstream", "a"));
        expected.add(new SimpleImmutableEntry<>("Vary", "Accept"));
        expected.add(new SimpleImmutableEntry<>("Vary", "Origin"));
        expected.add(new SimpleImmutableEntry<>("Set-Cookie", "sid=42"));
        expected.add(new SimpleImmutableEntry<>("Content-Length", "5"));
        ClientResponse r = t.send("GET", "/r");
        assertEquals(expected, r.headers());
        assertArrayEquals(FIVE, r.body());
    }

    // FR-HTTP-036 AC-5: with defaultCache(noCache()) a relayed response carries no Cache-Control
    @Test
    public void FR_HTTP_036_AC5_relayNeverReceivesDefaultCache() {
        TestApp t = TestApp.create();
        t.app().defaultCache(CachePolicy.noCache());
        t.app().get("/r", (req, res) -> res.relay(five(), 5));
        t.app().get("/send", (req, res) -> res.send("x"));
        ClientResponse r = t.send("GET", "/r");
        assertNull(r.header("Cache-Control"));
        assertArrayEquals(FIVE, r.body());
        assertEquals("no-cache", t.send("GET", "/send").header("Cache-Control"));
    }

    // FR-HTTP-036 AC-5: a Cache-Control the app set through res.header or res.cache before relay is sent as set
    @Test
    public void FR_HTTP_036_AC5_appCacheControlBeforeRelayIsKept() {
        TestApp t = TestApp.create();
        t.app().defaultCache(CachePolicy.noCache());
        t.app().get("/header", (req, res) -> {
            res.header("Cache-Control", "max-age=9");
            res.relay(five(), 5);
        });
        t.app().get("/cache", (req, res) -> {
            res.cache(CachePolicy.noStore());
            res.relay(five(), 5);
        });
        ClientResponse h = t.send("GET", "/header");
        assertEquals("max-age=9", h.header("Cache-Control"));
        assertEquals(1, count(h, "Cache-Control"));
        ClientResponse c = t.send("GET", "/cache");
        assertEquals("no-store", c.header("Cache-Control"));
        assertEquals(1, count(c, "Cache-Control"));
    }

    // FR-HTTP-036 AC-5: a HEAD request answered by relay takes no defaultCache either
    @Test
    public void FR_HTTP_036_AC5_bodylessRelayTakesNoDefaultCache() {
        TestApp t = TestApp.create();
        t.app().defaultCache(CachePolicy.noCache());
        t.app().get("/r", (req, res) -> res.relay(five(), 5));
        t.app().get("/gone", (req, res) -> {
            res.status(304);
            res.relay(five(), 5);
        });
        ClientResponse head = t.send("HEAD", "/r");
        assertEquals(200, head.status());
        assertNull(head.header("Cache-Control"));
        ClientResponse notModified = t.send("GET", "/gone");
        assertEquals(304, notModified.status());
        assertNull(notModified.header("Cache-Control"));
    }

    private static int count(ClientResponse r, String name) {
        int n = 0;
        for (Map.Entry<String, String> h : r.headers()) {
            if (h.getKey().equalsIgnoreCase(name)) {
                n++;
            }
        }
        return n;
    }
}
