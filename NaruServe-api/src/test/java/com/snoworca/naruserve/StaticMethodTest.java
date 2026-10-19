package com.snoworca.naruserve;

import static com.snoworca.naruserve.StaticTraversalTest.write;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.TestApp;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Method handling on static paths. SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-6-6.sds.md. */
public class StaticMethodTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    private File root() throws IOException {
        File root = tmp.getRoot();
        write(new File(root, "index.html"), "INDEX");
        write(new File(root, "assets/app.js"), "APP");
        write(new File(root, "docs/index.html"), "DOCS");
        write(new File(root, "login"), "LOGINFILE");
        write(new File(root, ".env"), "ENV");
        return root;
    }

    // SDS-AC-8; FR-STATIC-002 AC-1; FR-STATIC-002 AC-2
    @Test
    public void sdsAc8_FR_STATIC_002_AC1_nonGetOnServedPathIs405AllowGetHead() throws IOException {
        TestApp t = TestApp.create();
        t.app().staticFiles("/", root());
        for (String[] c : new String[][] {{"POST", "/assets/app.js"}, {"PUT", "/assets/app.js"},
                {"DELETE", "/docs/"}, {"OPTIONS", "/assets/app.js"}, {"PATCH", "/docs"}}) {
            ClientResponse r = t.send(c[0], c[1]);
            assertEquals(c[0] + c[1], 405, r.status());
            assertEquals(c[0] + c[1], "GET, HEAD", r.header("Allow"));
            assertFalse(r.text().contains("APP"));
            assertFalse(r.text().contains("DOCS"));
        }
    }

    // SDS-AC-8; FR-STATIC-002 AC-5 (T27)
    @Test
    public void sdsAc8_FR_STATIC_002_AC5_spaContentDoesNotCountFor405() throws IOException {
        TestApp t = TestApp.create();
        t.app().staticFiles("/", root(), o -> o.spaFallback(true));
        for (String path : new String[] {"/missing", "/api/typo", "/.env"}) {
            ClientResponse r = t.send("POST", path, "Accept", "text/html");
            assertEquals(path, 404, r.status());
            assertNull(path, r.header("Allow"));
        }
    }

    // SDS-AC-3; FR-STATIC-002 AC-5
    @Test
    public void sdsAc3_FR_STATIC_002_AC5_staticPathCheckRunsBeforeMethodDecision() throws IOException {
        TestApp t = TestApp.create();
        t.app().staticFiles("/", root());
        for (String m : new String[] {"GET", "HEAD", "POST", "PUT", "DELETE", "OPTIONS"}) {
            assertEquals(m, 400, t.send(m, "/%252e%252e/secret.txt").status());
            assertEquals(m, 400, t.send(m, "/a%252Fb").status());
        }
    }

    // SDS-AC-3; FR-STATIC-002 AC-5
    @Test
    public void sdsAc3_FR_STATIC_002_AC5_explicitRouteStillWins() throws IOException {
        TestApp t = TestApp.create();
        t.app().staticFiles("/", root());
        t.app().post("/:x", (req, res) -> res.send("ROUTE " + req.param("x")));
        ClientResponse r = t.send("POST", "/a%252Fb");
        assertEquals(200, r.status());
        assertEquals("ROUTE a%2Fb", r.text());
    }

    // SDS-AC-9; FR-STATIC-002 AC-3
    @Test
    public void sdsAc9_FR_STATIC_002_AC3_getAfterRouteMethodMismatchFallsThroughPutKeepsRouteAllow()
            throws IOException {
        TestApp t = TestApp.create();
        t.app().post("/login", (req, res) -> res.send("POSTED"));
        t.app().staticFiles("/", root());
        assertEquals("LOGINFILE", t.send("GET", "/login").text());
        ClientResponse head = t.send("HEAD", "/login");
        assertEquals(200, head.status());
        assertEquals(0, head.body().length);
        ClientResponse put = t.send("PUT", "/login");
        assertEquals(405, put.status());
        assertEquals("POST, OPTIONS", put.header("Allow")); // the route's own set, GET and HEAD not merged
    }

    // SDS-AC-9; FR-STATIC-002 AC-3
    @Test
    public void sdsAc9_FR_STATIC_002_AC3_spaAfterMismatchAndRoute405WhenNoMountServes() throws IOException {
        File spaRoot = new File(tmp.getRoot(), "spa");
        write(new File(spaRoot, "index.html"), "SPA");
        TestApp spa = TestApp.create();
        spa.app().post("/login", (req, res) -> res.send("POSTED"));
        spa.app().staticFiles("/", spaRoot, o -> o.spaFallback(true));
        assertEquals("SPA", spa.send("GET", "/login", "Accept", "text/html").text());

        TestApp none = TestApp.create();
        none.app().post("/login", (req, res) -> res.send("POSTED"));
        none.app().staticFiles("/", spaRoot);
        ClientResponse r = none.send("GET", "/login");
        assertEquals(405, r.status());
        assertEquals("POST, OPTIONS", r.header("Allow"));
    }

    // FR-STATIC-002 AC-4
    @Test
    public void FR_STATIC_002_AC4_webSocketRoutePathIsNeverServedStatically() throws IOException {
        File wsRoot = new File(tmp.getRoot(), "ws");
        write(new File(wsRoot, "index.html"), "SPA");
        write(new File(wsRoot, "chat"), "CHATFILE");
        TestApp t = TestApp.create();
        t.app().staticFiles("/", wsRoot, o -> o.spaFallback(true));
        t.webSocketRoute("/chat");
        ClientResponse r = t.send("GET", "/chat", "Accept", "text/html");
        assertEquals(426, r.status());
        assertFalse(r.text().contains("CHATFILE"));
        assertFalse(r.text().contains("SPA"));
    }

    // FR-STATIC-002 AC-2
    @Test
    public void FR_STATIC_002_AC2_unknownOrDisabledMethodIs501BeforeStatic() throws IOException {
        TestApp t = TestApp.create();
        t.app().staticFiles("/", root());
        assertEquals(501, t.send("BREW", "/assets/app.js").status());
        assertEquals(501, t.send("TRACE", "/assets/app.js").status());
    }

    // FR-STATIC-002 AC-5; FR-STATIC-006 AC-2: a refused symbolic link is not "served by GET", so no 405 and no 301
    @Test
    public void FR_STATIC_002_AC5_refusedSymlinksGet404NotAllowOr301() throws IOException {
        File base = tmp.getRoot();
        File root = new File(base, "root");
        File outside = new File(base, "outside");
        write(new File(root, "real.txt"), "REAL");
        write(new File(root, "dir/index.html"), "DIRINDEX");
        write(new File(outside, "secret.txt"), "OUTSIDE");
        write(new File(outside, "odir/index.html"), "OUTSIDE");
        Files.createSymbolicLink(new File(root, "inlink.txt").toPath(), new File(root, "real.txt").toPath());
        Files.createSymbolicLink(new File(root, "outlink.txt").toPath(), new File(outside, "secret.txt").toPath());
        Files.createSymbolicLink(new File(root, "indir").toPath(), new File(root, "dir").toPath());
        Files.createSymbolicLink(new File(root, "outdir").toPath(), new File(outside, "odir").toPath());
        TestApp t = TestApp.create();
        t.app().staticFiles("/", root);
        assertEquals(405, t.send("POST", "/real.txt").status()); // control: a served file
        for (String path : new String[] {"/inlink.txt", "/outlink.txt", "/indir", "/indir/", "/outdir",
                "/outdir/"}) {
            ClientResponse post = t.send("POST", path);
            assertEquals("POST " + path, 404, post.status());
            assertNull("POST " + path, post.header("Allow"));
            ClientResponse get = t.send("GET", path);
            assertEquals("GET " + path, 404, get.status());
            assertNull("GET " + path, get.header("Location"));
        }

        TestApp allowed = TestApp.create();
        allowed.app().staticFiles("/", root, o -> o.allowSymlinks(true));
        assertEquals(405, allowed.send("POST", "/inlink.txt").status());
        assertEquals(301, allowed.send("GET", "/indir").status());
        for (String path : new String[] {"/outlink.txt", "/outdir", "/outdir/"}) {
            assertEquals("POST " + path, 404, allowed.send("POST", path).status());
            assertEquals("GET " + path, 404, allowed.send("GET", path).status());
        }
    }
}
