package com.snoworca.naruserve;

import static com.snoworca.naruserve.StaticTraversalTest.write;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.TestApp;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** CachePolicy values, res.cache, options.cache and defaultCache (T32). SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-6-8.sds.md. */
public class StaticCacheTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    // FR-STATIC-008 AC-1
    @Test
    public void FR_STATIC_008_AC1_policyHeaderValues() {
        assertEquals("no-store", CachePolicy.noStore().getHeaderValue());
        assertEquals("no-cache", CachePolicy.noCache().getHeaderValue());
        assertEquals("private, max-age=600", CachePolicy.privateFor(10, TimeUnit.MINUTES).getHeaderValue());
        CachePolicy year = CachePolicy.publicFor(365, TimeUnit.DAYS);
        assertEquals("public, max-age=31536000, immutable", year.immutable().getHeaderValue());
        assertEquals("public, max-age=31536000", year.getHeaderValue());
        assertEquals("public, max-age=3600", CachePolicy.publicFor(1, TimeUnit.HOURS).getHeaderValue());
    }

    // FR-STATIC-008 AC-2
    @Test
    public void FR_STATIC_008_AC2_lastResCacheBeforeCommitWins() {
        TestApp t = TestApp.create();
        t.app().get("/one", (req, res) -> res.cache(CachePolicy.noStore()).send("x"));
        t.app().get("/two", (req, res) -> res.cache(CachePolicy.noStore()).cache(CachePolicy.noCache()).send("x"));
        assertEquals("no-store", t.send("GET", "/one").header("Cache-Control"));
        assertEquals("no-cache", t.send("GET", "/two").header("Cache-Control"));
    }

    // FR-STATIC-008 AC-3
    @Test
    public void FR_STATIC_008_AC3_patternsOnARootMount() throws IOException {
        File root = tmp.getRoot();
        write(new File(root, "index.html"), "I");
        write(new File(root, "docs/a.html"), "A");
        write(new File(root, "assets/app.js"), "J");
        TestApp t = TestApp.create();
        t.app().staticFiles("/", root, o -> o.cache("**/*.html", CachePolicy.noCache())
                .cache("/assets/**", CachePolicy.publicFor(365, TimeUnit.DAYS).immutable()));
        assertEquals("no-cache", t.send("GET", "/index.html").header("Cache-Control"));
        assertEquals("no-cache", t.send("GET", "/").header("Cache-Control"));
        assertEquals("no-cache", t.send("GET", "/docs/a.html").header("Cache-Control"));
        assertEquals("public, max-age=31536000, immutable", t.send("GET", "/assets/app.js").header("Cache-Control"));
    }

    // SDS-AC-3; FR-STATIC-008 AC-4
    @Test
    public void sdsAc3_FR_STATIC_008_AC4_patternsAreMountRelative() throws IOException {
        File root = tmp.getRoot();
        write(new File(root, "assets/x.js"), "X");
        write(new File(root, "a/b.css"), "C");
        write(new File(root, "y.js"), "Y");
        TestApp t = TestApp.create();
        t.app().staticFiles("/static", root, o -> o.cache("/assets/**", CachePolicy.noStore())
                .cache("**/*.js", CachePolicy.noCache()).cache("*.css", CachePolicy.privateFor(1, TimeUnit.SECONDS)));
        assertEquals("no-store", t.send("GET", "/static/assets/x.js").header("Cache-Control"));
        assertEquals("no-cache", t.send("GET", "/static/y.js").header("Cache-Control"));
        assertEquals("private, max-age=1", t.send("GET", "/static/a/b.css").header("Cache-Control"));
    }

    private TestApp matrix(CachePolicy defaultCache, boolean middleware) throws IOException {
        File root = new File(tmp.getRoot(), middleware ? "m" : "r");
        write(new File(root, "plain.txt"), "P");
        write(new File(root, "pat.css"), "C");
        TestApp t = TestApp.create();
        t.app().defaultCache(defaultCache);
        if (middleware) {
            t.app().use((req, res, next) -> {
                res.header("Cache-Control", "private");
                next.run();
            });
        }
        t.app().get("/json", (req, res) -> res.json(1));
        t.app().get("/store", (req, res) -> res.cache(CachePolicy.noStore()).send("s"));
        t.app().get("/header", (req, res) -> res.header("Cache-Control", "max-age=5").send("h"));
        t.app().staticFiles("/", root, o -> o.cache("*.css", CachePolicy.publicFor(1, TimeUnit.HOURS)));
        return t;
    }

    // SDS-AC-4; FR-STATIC-008 AC-5
    @Test
    public void sdsAc4_FR_STATIC_008_AC5_defaultCacheFillsOnlyAnEmptyCacheControl() throws IOException {
        TestApp off = matrix(null, false);
        assertNull(off.send("GET", "/json").header("Cache-Control"));
        assertNull(off.send("GET", "/plain.txt").header("Cache-Control"));
        assertNull(off.send("GET", "/missing").header("Cache-Control"));

        TestApp on = matrix(CachePolicy.noCache(), false);
        assertEquals("no-cache", on.send("GET", "/json").header("Cache-Control"));
        assertEquals("no-cache", on.send("GET", "/plain.txt").header("Cache-Control"));
        ClientResponse missing = on.send("GET", "/missing");
        assertEquals(404, missing.status());
        assertEquals("no-cache", missing.header("Cache-Control"));
        String etag = on.send("GET", "/plain.txt").header("ETag");
        ClientResponse notModified = on.send("GET", "/plain.txt", "If-None-Match", etag);
        assertEquals(304, notModified.status());
        assertEquals("no-cache", notModified.header("Cache-Control"));
        assertEquals("no-store", on.send("GET", "/store").header("Cache-Control"));
        assertEquals("max-age=5", on.send("GET", "/header").header("Cache-Control"));
        assertEquals("public, max-age=3600", on.send("GET", "/pat.css").header("Cache-Control"));

        TestApp mw = matrix(CachePolicy.noCache(), true);
        assertEquals("private", mw.send("GET", "/plain.txt").header("Cache-Control"));
    }
}
