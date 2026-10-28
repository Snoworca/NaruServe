package com.snoworca.naruserve.tinyhttpd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.Method;
import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.routing.RouterPolicy;
import com.snoworca.naruserve.testing.TestSupport;
import java.util.ArrayList;
import java.util.List;

/**
 * One case body run on both adapters: subclasses supply a fresh unstarted app and send one request to it. Each case
 * asserts the expected result and appends what it saw to {@link #transcript}, so the two runs compare line by line.
 * SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-5-7.sds.md.
 */
// @req FR-ENGINE-007
abstract class BothAdaptersCases {

    /** One response: status, the headers a case asks for (case-insensitive) and the UTF-8 body. */
    interface Reply {
        int status();

        String header(String name);

        String body();
    }

    final List<String> transcript = new ArrayList<>();

    /** A new app that has not listened yet. */
    abstract RestApp newApp();

    /** Starts {@code app} on first use and sends one request; {@code headerPairs} is name, value, .... */
    abstract Reply send(RestApp app, String method, String target, String... headerPairs) throws Exception;

    /** Stops every app this run started. */
    abstract void close();

    private void record(String label, Reply r, String... headers) {
        StringBuilder sb = new StringBuilder(label).append(" -> ").append(r.status());
        for (String h : headers) {
            sb.append(" ").append(h).append('=').append(r.header(h));
        }
        // FR-APP-024 AC-3; CON-APP-010 AC-5: validate HTML/security headers per adapter;
        // compare the error class/message, since Java call frames include adapter-specific stacks.
        transcript.add(sb.append(" body=").append(r.status() >= 400
                ? CrossAdapterTest.htmlError(r.status(), r::header, r.body(), "com.snoworca.naruserve.HttpException")
                : TestSupport.maskRequestId(r.body()))
                .toString());
    }

    /** T07: duplicate query and header values, UTF-8 parameters and polluted inputs. */
    // @req FR-ENGINE-007
    void t07Cases() throws Exception {
        RestApp app = newApp();
        app.get("/p/:x", (req, res) -> res.send("param=" + req.param("x")
                + "|a=" + req.query("a") + "|as=" + req.queries("a")
                + "|c=" + req.query("c") + "|cs=" + req.queries("c")
                + "|q=" + req.query("q") + "|han=" + req.query("한")
                + "|tag=" + req.header("X-TAG") + "|tags=" + req.headers("x-Tag")
                + "|none=" + req.header("None") + "|nones=" + req.headers("None")));

        Reply ok = send(app, "GET", "/p/%ED%95%9C?a=1&a=2&b=3&q=a+b%20c&%ED%95%9C=%EA%B8%80",
                "X-Tag", "a", "x-tag", "b");
        record("t07 duplicates and UTF-8", ok, "Content-Type");
        assertEquals(200, ok.status());
        assertEquals("text/plain; charset=utf-8", ok.header("Content-Type").toLowerCase(java.util.Locale.ROOT));
        assertEquals("param=한|a=1|as=[1, 2]|c=null|cs=[]|q=a b c|han=글|tag=a|tags=[a, b]|none=null|nones=[]",
                ok.body());

        for (String polluted : new String[] {"?x=%zz", "?x=%E0%A4", "?x=%C3%28"}) {
            Reply r = send(app, "GET", "/p/v" + polluted);
            record("t07 polluted query " + polluted, r);
            assertEquals(polluted, 400, r.status());
            CrossAdapterTest.htmlError(r.status(), r::header, r.body(), "com.snoworca.naruserve.HttpException");
        }
    }

    /** D-052 rejections and OPTIONS *, compared by status (TinyHttpd may answer its own plain-text 400). */
    // @req FR-ENGINE-007
    void pathRejectionCases() throws Exception {
        RestApp app = newApp();
        app.route(Method.any(), "/", (req, res) -> res.send("root")).route(Method.any(), "/:x", (req, res) -> res.send("x"))
                .route(Method.any(), "/:x/:y", (req, res) -> res.send("xy"));
        // FR-HTTP-027 AC-2; CON-APP-010 AC-2: malformed captures fail; encoded
        // slash/NUL/dot are data. The independent legacy Router test retains its old guards.
        String[] targets = {"/%zz", "/%E0%A4", "/%C3%28", "/a%2Fb", "/a%2fb", "/a%00b",
            "/.", "/..", "/a/./b", "/a/../b", "/%2e", "/%2e%2e", "/a/%2E/b", "/a/%2E%2E/b"};
        int[] statuses = {400, 400, 400, 200, 200, 200, 200, 200, 404, 404, 200, 200, 404, 404};
        for (int i = 0; i < targets.length; i++) {
            Reply r = send(app, "GET", targets[i]);
            transcript.add("common " + targets[i] + " -> " + r.status());
            assertEquals(targets[i], statuses[i], r.status());
            if (statuses[i] == 200) assertEquals("x", r.body());
        }
        Reply star = send(app, "OPTIONS", "*");
        record("OPTIONS *", star, "Allow");
        assertEquals(204, star.status());
        assertEquals("GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS", star.header("Allow"));
    }

    /** /a//b under mergeSlashes(true) and /a%20b to /:x reach handlers; strict /a//b is 404. */
    // @req FR-ENGINE-007
    void handlerReachingCases() throws Exception {
        RestApp merged = newApp();
        merged.routerPolicy(RouterPolicy.builder().mergeSlashes(true).build());
        merged.get("/a/b", (req, res) -> res.send("raw=" + req.rawPath() + "|path=" + req.path()));
        merged.get("/:x", (req, res) -> res.send("raw=" + req.rawPath() + "|path=" + req.path()
                + "|x=" + req.param("x")));
        Reply slashes = send(merged, "GET", "/a//b");
        record("merged /a//b", slashes);
        assertEquals(200, slashes.status());
        assertEquals("raw=/a//b|path=/a//b", slashes.body());
        Reply space = send(merged, "GET", "/a%20b");
        record("merged /a%20b", space);
        assertEquals(200, space.status());
        assertEquals("raw=/a%20b|path=/a%20b|x=a b", space.body());

        RestApp strict = newApp();
        strict.get("/a/b", (req, res) -> res.send("reached"));
        Reply r = send(strict, "GET", "/a//b");
        transcript.add("strict /a//b -> " + r.status());
        assertEquals(404, r.status());
    }

    /** app.trustProxy on and off: remoteAddress() and isSecure() for the same forwarded headers. */
    // @req FR-ENGINE-008
    void proxyTrustCases() throws Exception {
        String[][] headerSets = {
            {},
            {"X-Forwarded-For", "203.0.113.7, 10.0.0.1", "X-Forwarded-Proto", "https"},
            {"X-Forwarded-Proto", "http"},
            {"Forwarded", "for=198.51.100.1;proto=https"},
        };
        String[][] expected = {
            {"127.0.0.1 false", "127.0.0.1 false", "127.0.0.1 false", "127.0.0.1 false"},
            {"127.0.0.1 false", "203.0.113.7 true", "127.0.0.1 false", "127.0.0.1 false"},
        };
        for (int trust = 0; trust < 2; trust++) {
            RestApp app = newApp();
            app.trustProxy(trust == 1);
            app.get("/who", (req, res) -> res.send(req.remoteAddress() + " " + req.isSecure()));
            for (int i = 0; i < headerSets.length; i++) {
                Reply r = send(app, "GET", "/who", headerSets[i]);
                record("trustProxy=" + (trust == 1) + " " + String.join(",", headerSets[i]), r);
                assertEquals(200, r.status());
                assertEquals(expected[trust][i], r.body());
            }
        }
    }
}
