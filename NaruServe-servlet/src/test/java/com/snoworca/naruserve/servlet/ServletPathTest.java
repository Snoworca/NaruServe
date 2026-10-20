package com.snoworca.naruserve.servlet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.snoworca.naruserve.RestApp;
import org.junit.Rule;
import org.junit.Test;

/** The path remainder: raw request URI minus context path minus url-pattern prefix, undecoded. */
public class ServletPathTest {

    @Rule
    public final ContainerRule container = new ContainerRule();

    private static void routes(RestApp app) {
        app.get("/users/:id", (req, res) -> res.send(req.rawPath() + "|" + req.param("id") + "|" + req.query("x")));
        app.get("/", (req, res) -> res.send("root"));
    }

    /** Jetty with context path /app serving a new app at {@code pattern}; returns the port. */
    private int start(String pattern) throws Exception {
        ServletAdapter adapter = new ServletAdapter();
        SharedApp.listen(adapter, ServletPathTest::routes);
        container.servlet(new NaruServeServlet(adapter, InitParams.prefix(pattern)), pattern, "/app");
        return container.port();
    }

    // SDS-AC-1; FR-SVL-002 AC-1
    @Test
    public void sdsAc1_FR_SVL_002_AC1_remainderStripsContextAndPrefixAtASegmentBoundary() {
        assertEquals("/users/%41;v=1", ServletPath.remainder("/app/api/users/%41;v=1", "/app", "/api"));
        assertEquals("/", ServletPath.remainder("/app/api", "/app", "/api"));
        assertEquals("/", ServletPath.remainder("/app/api/", "/app", "/api"));
        assertEquals("/users/%41;v=1", ServletPath.remainder("/app/users/%41;v=1", "/app", ""));
        assertEquals("/", ServletPath.remainder("/app", "/app", ""));
        assertEquals("/x", ServletPath.remainder("/x", "", ""));
        assertNull(ServletPath.remainder("/app/apix/users", "/app", "/api"));
        assertNull(ServletPath.remainder("/app/%61pi/users", "/app", "/api"));
        assertNull(ServletPath.remainder("/other/api/users", "/app", "/api"));
    }

    // SDS-AC-1, SDS-AC-5; FR-SVL-002 AC-1, AC-5 — no decoding, no normalising
    @Test
    public void sdsAc1_FR_SVL_002_AC1_remainderNeverDecodesOrNormalises() {
        assertEquals("/a%2Fb", ServletPath.remainder("/a%2Fb", "", ""));
        assertEquals("/a/../b", ServletPath.remainder("/a/../b", "", ""));
        assertEquals("/a/%2e/b", ServletPath.remainder("/api/a/%2e/b", "", "/api"));
        assertEquals("//x", ServletPath.remainder("//x", "", ""));
    }

    // SDS-AC-1; FR-SVL-002 AC-1
    @Test
    public void sdsAc1_FR_SVL_002_AC1_prefixPatternReachesTheRouteWithTheRawPath() throws Exception {
        int port = start("/api/*");
        SharedApp.Reply r = SharedApp.http(port, "GET", "/app/api/users/%41;v=1?x=%20", null);
        assertEquals(200, r.status);
        assertEquals("/users/%41;v=1|A;v=1| ", r.text());
        assertEquals("root", SharedApp.http(port, "GET", "/app/api", null).text());
    }

    // SDS-AC-1; FR-SVL-002 AC-1
    @Test
    public void sdsAc1_FR_SVL_002_AC1_slashStarAndSlashStripNothingAfterTheContextPath() throws Exception {
        for (String pattern : new String[] {"/*", "/"}) {
            int port = start(pattern);
            SharedApp.Reply r = SharedApp.http(port, "GET", "/app/users/%41;v=1?x=%20", null);
            assertEquals(pattern, 200, r.status);
            assertEquals(pattern, "/users/%41;v=1|A;v=1| ", r.text());
            assertEquals(pattern, "root", SharedApp.http(port, "GET", "/app/", null).text());
        }
    }
}
