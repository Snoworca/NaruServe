package com.snoworca.naruserve.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.Method;
import com.snoworca.naruserve.Middleware;
import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.RouteHandler;
import com.snoworca.naruserve.routing.RouterPolicy;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.TestApp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/**
 * Global, prefix and route middleware order through the harness. SDS-AC-n cites
 * docs/sds/2026-10-19.naruserve.naruserve-v1-wave-4-4.sds.md.
 */
public class MiddlewarePipelineTest {

    private final List<String> calls = Collections.synchronizedList(new ArrayList<>());

    private Middleware mw(String name) {
        return (req, res, next) -> {
            calls.add(name);
            next.run();
        };
    }

    private RouteHandler h(String name) {
        return (req, res) -> {
            calls.add(name);
            res.send(name);
        };
    }

    // SDS-AC-1; FR-APP-003 AC-1, T35
    @Test
    public void sdsAc1_FR_APP_003_AC1_globalsThenPrefixesThenRouteMiddlewareThenHandler() {
        TestApp t = TestApp.create();
        RestApp app = t.app();
        app.use(mw("g1"));
        app.use("/api", mw("p1"));
        app.use(mw("g2"));
        app.use("/api", mw("p2"));
        app.route("/api/users").use(mw("r1")).use(mw("r2")).get(h("h"));
        app.get("/b", mw("r3"), h("h2"));
        assertEquals("h", t.send("GET", "/api/users").text());
        assertEquals(Arrays.asList("g1", "g2", "p1", "p2", "r1", "r2", "h"), calls);
        calls.clear();
        assertEquals("h2", t.send("GET", "/b").text());
        assertEquals(Arrays.asList("g1", "g2", "r3", "h2"), calls);
    }

    // SDS-AC-2; FR-APP-003 AC-1
    @Test
    public void sdsAc2_FR_APP_003_AC1_routeBuilderUseAppliesInOrderToLaterVerbsOnly() {
        TestApp t = TestApp.create();
        t.app().route("/api/users").get(h("get")).use(mw("r1")).use(mw("r2")).post(h("post"));
        t.send("GET", "/api/users");
        assertEquals(Collections.singletonList("get"), calls);
        calls.clear();
        t.send("POST", "/api/users");
        assertEquals(Arrays.asList("r1", "r2", "post"), calls);
    }

    // SDS-AC-4; FR-APP-003 AC-3
    @Test
    public void sdsAc4_FR_APP_003_AC3_prefixFollowsTheCaseInsensitivePolicy() {
        TestApp t = TestApp.create();
        t.app().routerPolicy(RouterPolicy.builder().caseInsensitive(true).build());
        t.app().use("/api", mw("p"));
        t.app().get("/api/users", h("h"));
        assertEquals(200, t.send("GET", "/API/users").status());
        assertEquals(Arrays.asList("p", "h"), calls);
    }

    // SDS-AC-4; FR-APP-003 AC-3
    @Test
    public void sdsAc4_FR_APP_003_AC3_prefixFollowsTheMergeSlashesPolicy() {
        TestApp t = TestApp.create();
        t.app().routerPolicy(RouterPolicy.builder().mergeSlashes(true).build());
        t.app().use("/api", mw("p"));
        t.app().get("/api/users", h("h"));
        assertEquals(200, t.send("GET", "//api//users").status());
        assertEquals(Arrays.asList("p", "h"), calls);
    }

    // SDS-AC-5; FR-APP-003 AC-4
    @Test
    public void sdsAc5_FR_APP_003_AC4_prefixMiddlewareAndHandlerSeeTheFullPath() {
        TestApp t = TestApp.create();
        t.app().use("/api", (req, res, next) -> {
            calls.add("p " + req.path());
            next.run();
        });
        t.app().get("/api/:name", (req, res) -> {
            calls.add("h " + req.path());
            res.send("ok");
        });
        t.send("GET", "/api/users");
        t.send("GET", "/api/a%20b");
        assertEquals(Arrays.asList("p /api/users", "h /api/users", "p /api/a b", "h /api/a b"), calls);
    }

    // SDS-AC-6; FR-APP-003 AC-5
    @Test
    public void sdsAc6_FR_APP_003_AC5_noMatchRunsGlobalsAndPrefixThenThe404() {
        TestApp t = TestApp.create();
        t.app().use(mw("g"));
        t.app().use("/api", mw("p"));
        t.app().use("/other", mw("o"));
        t.app().get("/api/users", mw("r"), h("h"));
        t.app().get("/boom/now", (req, res) -> {
            throw new IllegalStateException("boom");
        });
        t.app().onError((error, req, res) -> calls.add("onError"));
        ClientResponse r = t.send("GET", "/api/missing");
        assertEquals(404, r.status());
        assertTrue(r.text(), r.text().startsWith("{\"error\":{\"code\":\"NOT_FOUND\""));
        assertEquals(Arrays.asList("g", "p"), calls);
        // Control (FR-APP-005 AC-7): the recording onError is wired, so the empty call lists above are meaningful.
        calls.clear();
        assertEquals(500, t.send("GET", "/boom/now").status());
        assertTrue(calls.toString(), calls.contains("onError"));
    }

    // SDS-AC-6; FR-APP-003 AC-5
    @Test
    public void sdsAc6_FR_APP_003_AC5_fallbackRequestCarriesTheParsedMethodAndNoParams() {
        List<Object> seen = new ArrayList<>();
        TestApp t = TestApp.create();
        t.app().use((req, res, next) -> {
            seen.add(req.method());
            seen.add(req.path());
            try {
                req.param("x");
            } catch (IllegalArgumentException e) {
                seen.add("no param");
            }
            next.run();
        });
        t.app().get("/:x/y", h("h"));
        assertEquals(404, t.send("DELETE", "/nothing").status());
        assertEquals(Arrays.asList(Method.DELETE, "/nothing", "no param"), seen);
    }

    // SDS-AC-7; FR-APP-003 AC-7, FR-HTTP-021 AC-4
    @Test
    public void sdsAc7_FR_APP_003_AC7_FR_HTTP_021_AC4_preRequestRejectionsRunNoMiddlewareHandlerOrOnError() {
        TestApp t = TestApp.create();
        t.app().use(mw("g"));
        t.app().use("/", mw("p"));
        t.app().onError((error, req, res) -> calls.add("onError"));
        t.app().route(Method.any(), "/", h("h")).route(Method.any(), "/:x", h("h"));
        t.app().get("/boom/now", (req, res) -> {
            throw new IllegalStateException("boom");
        });
        assertEquals(501, t.send("FOO", "/x").status());
        assertEquals(400, t.send("GET", "abc").status());
        assertEquals(400, t.send("GET", "/%2e%2e").status());
        assertEquals(204, t.send("OPTIONS", "*").status());
        assertEquals(new ArrayList<String>(), calls);
        // Control (FR-APP-005 AC-7): the recording onError is wired, so the empty call lists above are meaningful.
        calls.clear();
        assertEquals(500, t.send("GET", "/boom/now").status());
        assertTrue(calls.toString(), calls.contains("onError"));
    }

    // SDS-AC-8; FR-APP-003 AC-8, FR-APP-004 AC-6
    @Test
    public void sdsAc8_FR_APP_003_AC8_everyStepRunsOnTheAdapterThread() {
        List<Thread> threads = Collections.synchronizedList(new ArrayList<>());
        Middleware record = (req, res, next) -> {
            threads.add(Thread.currentThread());
            next.run();
        };
        TestApp t = TestApp.create();
        t.app().use(record);
        t.app().use("/a", record);
        t.app().get("/a", record, (req, res) -> {
            threads.add(Thread.currentThread());
            res.send("ok");
        });
        t.send("GET", "/a");
        assertEquals(4, threads.size());
        for (Thread th : threads) {
            // InMemoryHttpAdapter.send runs the exchange on the caller thread.
            assertSame(Thread.currentThread(), th);
        }
    }

    // SDS-AC-17; FR-HTTP-022 AC-8, FR-APP-003 AC-6, FR-HTTP-022 AC-4
    @Test
    public void sdsAc17_FR_HTTP_022_AC8_globalRunsOnceForEveryFallbackAnswerButNotTheUnknownToken() {
        TestApp t = TestApp.create().webSocketRoute("/ws");
        t.app().use(mw("g"));
        t.app().onError((error, req, res) -> calls.add("onError"));
        t.app().get("/r", h("h")).post("/r", h("h"));
        t.app().get("/boom/now", (req, res) -> {
            throw new IllegalStateException("boom");
        });
        String[][] cases = {{"GET", "/missing", "404"}, {"DELETE", "/r", "405"}, {"GET", "/ws", "426"},
            {"OPTIONS", "/r", "204"}, {"TRACE", "/x", "501"}, {"CONNECT", "/", "501"}};
        for (String[] c : cases) {
            calls.clear();
            assertEquals(c[0] + " " + c[1], Integer.parseInt(c[2]), t.send(c[0], c[1]).status());
            assertEquals(c[0] + " " + c[1], Collections.singletonList("g"), calls);
        }
        calls.clear();
        assertEquals(501, t.send("BREW", "/r").status());
        assertEquals(new ArrayList<String>(), calls);
        // Control (FR-APP-005 AC-7): the recording onError is wired, so the empty call lists above are meaningful.
        calls.clear();
        assertEquals(500, t.send("GET", "/boom/now").status());
        assertTrue(calls.toString(), calls.contains("onError"));
    }

    // SDS-AC-17; FR-HTTP-022 AC-4
    @Test
    public void sdsAc17_FR_HTTP_022_AC4_disabledTraceRequestSeesTheTraceMethod() {
        List<Method> seen = new ArrayList<>();
        TestApp t = TestApp.create();
        t.app().use((req, res, next) -> {
            seen.add(req.method());
            next.run();
        });
        ClientResponse r = t.send("TRACE", "/x");
        assertEquals(501, r.status());
        assertTrue(r.text(), r.text().startsWith("{\"error\":{\"code\":\"NOT_IMPLEMENTED\""));
        assertEquals(Collections.singletonList(Method.TRACE), seen);
    }
}
