package com.snoworca.naruserve;

import static com.snoworca.naruserve.StaticTraversalTest.write;
import static org.junit.Assert.assertEquals;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.TestApp;
import java.io.File;
import java.io.IOException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** SPA fallback conditions and exclusions. SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-6-6.sds.md. */
public class StaticSpaTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    private TestApp app(Configurer<StaticOptions> options) throws IOException {
        File root = tmp.getRoot();
        write(new File(root, "index.html"), "SPA");
        write(new File(root, "real.txt"), "REAL");
        TestApp t = TestApp.create();
        t.app().get("/routed", (req, res) -> res.send("ROUTE"));
        t.app().staticFiles("/", root, options);
        return t;
    }

    // SDS-AC-6; FR-STATIC-004 AC-1; FR-STATIC-004 AC-2
    @Test
    public void sdsAc6_FR_STATIC_004_AC1_AC2_acceptMustNameTextHtml() throws IOException {
        TestApp t = app(o -> o.spaFallback(true));
        ClientResponse r = t.send("GET", "/app/users/7", "Accept", "text/html,application/xhtml+xml");
        assertEquals(200, r.status());
        assertEquals("SPA", r.text());
        assertEquals("SPA", t.send("GET", "/app/users/7", "Accept", "TEXT/HTML").text());
        assertEquals(404, t.send("GET", "/app/users/7", "Accept", "*/*").status());
        assertEquals(404, t.send("GET", "/app/users/7", "Accept", "application/json").status());
        assertEquals(404, t.send("GET", "/app/users/7").status());
        assertEquals("REAL", t.send("GET", "/real.txt", "Accept", "text/html").text());
        assertEquals("ROUTE", t.send("GET", "/routed", "Accept", "text/html").text());
        ClientResponse head = t.send("HEAD", "/app/x", "Accept", "text/html");
        assertEquals(200, head.status());
        assertEquals(0, head.body().length);
    }

    // SDS-AC-6; FR-STATIC-004 AC-1
    @Test
    public void sdsAc6_FR_STATIC_004_AC1_spaOffMeans404() throws IOException {
        TestApp t = app(o -> o.spaFallback(false));
        assertEquals(404, t.send("GET", "/app/users/7", "Accept", "text/html").status());
    }

    // SDS-AC-7; FR-STATIC-004 AC-3
    @Test
    public void sdsAc7_FR_STATIC_004_AC3_apiAndWsExcludedOnSegmentBoundaries() throws IOException {
        TestApp t = app(o -> o.spaFallback(true));
        for (String path : new String[] {"/api/x", "/api", "/ws/chat", "/ws"}) {
            assertEquals(path, 404, t.send("GET", path, "Accept", "text/html").status());
        }
        assertEquals("SPA", t.send("GET", "/apix", "Accept", "text/html").text());
    }

    // SDS-AC-7; FR-STATIC-004 AC-4
    @Test
    public void sdsAc7_FR_STATIC_004_AC4_spaExcludeAddsToDefaults() throws IOException {
        TestApp t = app(o -> o.spaFallback(true).spaExclude("/admin/"));
        assertEquals(404, t.send("GET", "/admin/x", "Accept", "text/html").status());
        assertEquals(404, t.send("GET", "/admin", "Accept", "text/html").status());
        assertEquals(404, t.send("GET", "/api/x", "Accept", "text/html").status());
        assertEquals("SPA", t.send("GET", "/adminx", "Accept", "text/html").text());
    }
}
