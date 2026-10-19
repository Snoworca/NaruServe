package com.snoworca.naruserve;

import static com.snoworca.naruserve.StaticTraversalTest.write;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.TestApp;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

    private static String text(ClientResponse r) {
        return new String(r.body(), StandardCharsets.UTF_8);
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
            assertFalse(text(r).contains("APP"));
            assertFalse(text(r).contains("DOCS"));
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
        assertEquals("ROUTE a%2Fb", text(r));
    }

    // SDS-AC-9; FR-STATIC-002 AC-3
    @Test
    public void sdsAc9_FR_STATIC_002_AC3_getAfterRouteMethodMismatchFallsThroughPutKeepsRouteAllow()
            throws IOException {
        TestApp t = TestApp.create();
        t.app().post("/login", (req, res) -> res.send("POSTED"));
        t.app().staticFiles("/", root());
        assertEquals("LOGINFILE", text(t.send("GET", "/login")));
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
        assertEquals("SPA", text(spa.send("GET", "/login", "Accept", "text/html")));

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
        assertFalse(text(r).contains("CHATFILE"));
        assertFalse(text(r).contains("SPA"));
    }

    // FR-STATIC-002 AC-2
    @Test
    public void FR_STATIC_002_AC2_unknownOrDisabledMethodIs501BeforeStatic() throws IOException {
        TestApp t = TestApp.create();
        t.app().staticFiles("/", root());
        assertEquals(501, t.send("BREW", "/assets/app.js").status());
        assertEquals(501, t.send("TRACE", "/assets/app.js").status());
    }
}
