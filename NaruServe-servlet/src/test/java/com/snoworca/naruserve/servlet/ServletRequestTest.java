package com.snoworca.naruserve.servlet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.Cookie;
import com.snoworca.naruserve.RestApp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;

/** Request mapping on Jetty: the raw-path 404, the forbidden getters, headers, cookies and the remote values. */
public class ServletRequestTest {

    @Rule
    public final ContainerRule container = new ContainerRule();

    private static void routes(RestApp app) {
        app.get("/users", (req, res) -> res.send("users"));
        app.get("/h", (req, res) -> {
            Cookie s = req.cookie("s");
            res.send(String.join(",", req.headers("X-A")) + "|" + req.header("X-A") + "|"
                    + (s == null ? "none" : s.getValue()) + "|" + req.header("Cookie") + "|" + req.remoteAddress()
                    + "|" + req.isSecure());
        });
    }

    private static void assertNoForbiddenGetter(List<String> calls) {
        synchronized (calls) {
            assertFalse(calls.isEmpty());
            for (String name : calls) {
                assertFalse(name, name.equals("getServletPath") || name.equals("getPathInfo")
                        || name.startsWith("getParameter"));
            }
        }
    }

    // SDS-AC-2; FR-SVL-002 AC-2, CON-SVL-004 AC-1
    @Test
    public void sdsAc2_FR_SVL_002_AC2_encodedPrefixMissIs404WithStandardBodyAndNoForbiddenGetter()
            throws Exception {
        List<String> calls = Collections.synchronizedList(new ArrayList<String>());
        container.filter(RecordingRequest.filter(calls));
        SharedApp.onJetty(container, "/api/*", ServletRequestTest::routes);

        SharedApp.Reply miss = SharedApp.http(container.port(), "GET", "/%61pi/users", null);
        assertEquals(404, miss.status);
        assertTrue(miss.text(), miss.text().contains("\"code\":\"NOT_FOUND\""));
        assertTrue(miss.text(), miss.text().matches(".*\"requestId\":\"[^\"]+\".*"));
        assertTrue(String.valueOf(miss.header("Content-Type")), miss.header("Content-Type").startsWith("application/json"));
        assertTrue("the servlet was reached through the recording request", calls.contains("getRequestURI"));
        assertNoForbiddenGetter(calls);

        calls.clear();
        assertEquals("users", SharedApp.http(container.port(), "GET", "/api/users?a=1", null).text());
        assertNoForbiddenGetter(calls);
    }

    // SDS-AC-3; FR-SVL-002 AC-3
    @Test
    public void sdsAc3_FR_SVL_002_AC3_repeatedHeadersCookieAndRemoteValuesReachTheCore() throws Exception {
        SharedApp.onJetty(container, "/*", ServletRequestTest::routes);
        SharedApp.Reply r = SharedApp.http(container.port(), "GET", "/h", null, "X-A", "1", "X-B", "z", "X-A", "2",
                "Cookie", "s=abc; t=%20x");
        assertEquals(200, r.status);
        assertEquals("1,2|1|abc|s=abc; t=%20x|127.0.0.1|false", r.text());
    }

    // SDS-AC-3; FR-SVL-002 AC-3 — trustProxy acts in the core over the container's values
    @Test
    public void sdsAc3_FR_SVL_002_AC3_trustProxyTakesEffectInTheCore() throws Exception {
        SharedApp.onJetty(container, "/*", app -> {
            routes(app);
            app.trustProxy(true);
        });
        SharedApp.Reply r = SharedApp.http(container.port(), "GET", "/h", null, "X-Forwarded-For", "10.1.2.3",
                "X-Forwarded-Proto", "https");
        assertTrue(r.text(), r.text().endsWith("|10.1.2.3|true"));
    }
}
