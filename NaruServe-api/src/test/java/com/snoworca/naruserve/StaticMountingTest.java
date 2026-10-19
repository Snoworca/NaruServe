package com.snoworca.naruserve;

import static com.snoworca.naruserve.StaticTraversalTest.write;
import static org.junit.Assert.assertEquals;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.TestApp;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Mount mapping, several mounts and the trailing-slash 301. SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-6-6.sds.md. */
public class StaticMountingTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    private static String text(ClientResponse r) {
        return new String(r.body(), StandardCharsets.UTF_8);
    }

    // FR-STATIC-001 AC-1; FR-STATIC-001 AC-2; FR-STATIC-001 AC-4
    @Test
    public void FR_STATIC_001_AC1_AC2_AC4_filesIndexesAndRoutesFirst() throws IOException {
        File root = tmp.getRoot();
        write(new File(root, "index.html"), "INDEX");
        write(new File(root, "assets/app.js"), "APP");
        write(new File(root, "docs/index.html"), "DOCS");
        write(new File(root, "docs/main.html"), "MAIN");
        TestApp t = TestApp.create();
        t.app().get("/index.html", (req, res) -> res.send("ROUTE"));
        t.app().staticFiles("/", root);
        assertEquals("INDEX", text(t.send("GET", "/")));
        ClientResponse app = t.send("GET", "/assets/app.js");
        assertEquals(200, app.status());
        assertEquals("APP", text(app));
        assertEquals("DOCS", text(t.send("GET", "/docs/")));
        assertEquals("ROUTE", text(t.send("GET", "/index.html")));
        assertEquals(404, t.send("GET", "/nothing").status());
        assertEquals(404, t.send("GET", "/assets/app.js/").status());

        TestApp main = TestApp.create();
        main.app().staticFiles("/", root, o -> o.index("main.html"));
        assertEquals("MAIN", text(main.send("GET", "/docs/")));
    }

    // SDS-AC-4; FR-STATIC-001 AC-6
    @Test
    public void sdsAc4_FR_STATIC_001_AC6_directoryWithoutSlashIs301WithRawLocation() throws IOException {
        File root = tmp.getRoot();
        write(new File(root, "docs/index.html"), "DOCS");
        write(new File(root, "my docs/index.html"), "MY");
        TestApp t = TestApp.create();
        t.app().staticFiles("/", root);
        ClientResponse r = t.send("GET", "/docs?x=1");
        assertEquals(301, r.status());
        assertEquals("/docs/?x=1", r.header("Location"));
        ClientResponse enc = t.send("HEAD", "/my%20docs");
        assertEquals(301, enc.status());
        assertEquals("/my%20docs/", enc.header("Location"));
        assertEquals(404, t.send("GET", "//assets").status());
    }

    // SDS-AC-4; FR-STATIC-001 AC-6
    @Test
    public void sdsAc4_FR_STATIC_001_AC6_mountPrefixItselfRedirects() throws IOException {
        File root = tmp.getRoot();
        write(new File(root, "index.html"), "INDEX");
        TestApp t = TestApp.create();
        t.app().staticFiles("/docs", root);
        ClientResponse r = t.send("GET", "/docs");
        assertEquals(301, r.status());
        assertEquals("/docs/", r.header("Location"));
        assertEquals("INDEX", text(t.send("GET", "/docs/")));
        assertEquals(404, t.send("GET", "/docsx/index.html").status());
    }

    // SDS-AC-5; FR-STATIC-001 AC-3
    @Test
    public void sdsAc5_FR_STATIC_001_AC3_mountsInOrderThenSpaOnce() throws IOException {
        File a = new File(tmp.getRoot(), "a");
        File b = new File(tmp.getRoot(), "b");
        write(new File(a, "a.txt"), "A");
        write(new File(a, "index.html"), "SPA-A");
        write(new File(b, "x.css"), "X");
        write(new File(b, "index.html"), "SPA-B");
        TestApp t = TestApp.create();
        t.app().staticFiles("/", a, o -> o.spaFallback(true));
        t.app().staticFiles("/static", b, o -> o.spaFallback(true));
        assertEquals("X", text(t.send("GET", "/static/x.css", "Accept", "text/html")));
        assertEquals("A", text(t.send("GET", "/a.txt")));
        ClientResponse spa = t.send("GET", "/static/missing.css", "Accept", "text/html");
        assertEquals(200, spa.status());
        assertEquals("SPA-A", text(spa));
    }

    // FR-STATIC-001 AC-5
    @Test
    public void FR_STATIC_001_AC5_globalMiddlewareRunsOnStaticAndCanEndIt() throws IOException {
        File root = tmp.getRoot();
        write(new File(root, "assets/app.js"), "APP");
        TestApp t = TestApp.create();
        t.app().use((req, res, next) -> {
            res.header("X-Mw", "1");
            if (req.query("stop") != null) {
                res.send("STOPPED");
                return;
            }
            next.run();
        });
        t.app().staticFiles("/", root);
        ClientResponse r = t.send("GET", "/assets/app.js");
        assertEquals("APP", text(r));
        assertEquals("1", r.header("X-Mw"));
        assertEquals("STOPPED", text(t.send("GET", "/assets/app.js?stop=1")));
    }
}
