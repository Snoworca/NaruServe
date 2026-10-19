package com.snoworca.naruserve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.routing.RoutingConfigException;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.TestApp;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/**
 * object.md T01, T02, T03, T05 and T06 through the in-memory harness. SDS-AC-n cites
 * docs/sds/2026-10-19.naruserve.naruserve-v1-wave-3-3.sds.md; SDS-3-AC-n marks the handler-reaching cases added
 * once DefaultResponse landed.
 */
public class HttpAcceptanceTest {

    private final AtomicInteger calls = new AtomicInteger();
    private final RouteHandler recording = (req, res) -> calls.incrementAndGet();

    private static void assertEmpty(int status, ClientResponse r) {
        assertEquals(status, r.status());
        assertEquals("0", r.header("Content-Length"));
        assertEquals(0, r.body().length);
    }

    // SDS-AC-14; T02, FR-HTTP-003 AC-7
    @Test
    public void sdsAc14_T02_FR_HTTP_003_AC7_anyExceptDeleteAnswersDelete405() {
        TestApp t = TestApp.create();
        t.app().route(Method.any().except(Method.DELETE), "/t", recording);
        ClientResponse r = t.send("DELETE", "/t");
        assertEmpty(405, r);
        assertEquals("GET, HEAD, POST, PUT, PATCH, OPTIONS", r.header("Allow"));
        assertEquals(0, calls.get());
    }

    // SDS-AC-11; T03, IR-HTTP-006 AC-3
    @Test
    public void sdsAc11_T03_IR_HTTP_006_AC3_duplicateBuilderAndAppGetFailsTheHarnessFreeze() {
        TestApp t = TestApp.create();
        t.app().get("/x", recording);
        t.app().route("/x").get(recording);
        try {
            t.start();
            fail("duplicate GET /x accepted");
        } catch (RoutingConfigException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("GET /x"));
        }
    }

    // SDS-AC-14; T05, FR-HTTP-003 AC-3
    @Test
    public void sdsAc14_T05_FR_HTTP_003_AC3_automaticOptionsListsAllowWithoutBody() {
        TestApp t = TestApp.create();
        t.app().get("/r", recording).post("/r", recording);
        ClientResponse r = t.send("OPTIONS", "/r");
        assertEquals(204, r.status());
        assertEquals("GET, HEAD, POST, OPTIONS", r.header("Allow"));
        assertNull(r.header("Content-Length"));
        assertEquals(0, r.body().length);
        assertEquals(0, calls.get());
    }

    // SDS-AC-14; T06, FR-HTTP-003 AC-1, FR-HTTP-003 AC-2, FR-HTTP-003 AC-4
    @Test
    public void sdsAc14_T06_FR_HTTP_003_AC1_AC2_AC4_noPathNoMethodUnknownMethod() {
        TestApp t = TestApp.create();
        t.app().post("/p", recording);
        assertEmpty(404, t.send("GET", "/nothing"));
        ClientResponse mismatch = t.send("GET", "/p");
        assertEmpty(405, mismatch);
        assertEquals("POST, OPTIONS", mismatch.header("Allow"));
        assertEmpty(501, t.send("FOO", "/p"));
        assertEquals(0, calls.get());
    }

    // SDS-AC-14; T06, FR-HTTP-003 AC-5, FR-HTTP-003 AC-6
    @Test
    public void sdsAc14_T06_FR_HTTP_003_AC5_AC6_webSocketOnlyHookRouteGives426() {
        TestApp t = TestApp.create().webSocketRoute("/ws");
        for (ClientResponse r : new ClientResponse[] {t.send("GET", "/ws"),
            t.send("GET", "/ws", "Connection", "Upgrade", "Upgrade", "websocket")}) {
            assertEmpty(426, r);
            assertEquals("websocket", r.header("Upgrade"));
            assertEquals("Upgrade", r.header("Connection"));
        }
    }

    // SDS-AC-14; FR-HTTP-005 AC-1, FR-HTTP-005 AC-2, FR-HTTP-005 AC-3, FR-HTTP-005 AC-4
    @Test
    public void sdsAc14_FR_HTTP_005_AC1_AC2_AC3_AC4_asteriskAndNonSlashTargets() {
        TestApp t = TestApp.create();
        t.app().route(Method.any(), "/", recording).route(Method.any(), "/:x", recording);
        ClientResponse star = t.send("OPTIONS", "*");
        assertEquals(204, star.status());
        assertEquals("GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS", star.header("Allow"));
        assertEquals(0, star.body().length);
        assertEmpty(400, t.send("GET", "abc"));
        assertEmpty(400, t.send("GET", "*"));
        assertEmpty(400, t.send("GET", "/%2e%2e"));
        assertEquals(0, calls.get());
    }

    private static String text(ClientResponse r) {
        return new String(r.body(), StandardCharsets.UTF_8);
    }

    // SDS-3-AC-14; FR-HTTP-002 AC-1, FR-HTTP-002 AC-5, FR-HTTP-002 AC-6
    @Test
    public void sds3Ac14_FR_HTTP_002_AC1_AC5_matchedRequestReachesTheHandlerOnce() {
        List<Object> seen = Collections.synchronizedList(new ArrayList<>());
        TestApp t = TestApp.create();
        t.app().get("/users/:id", (req, res) -> {
            calls.incrementAndGet();
            seen.add(req.method());
            seen.add(req.path());
            res.status(201).header("X-Id", req.param("id")).send("user " + req.param("id"));
        });
        ClientResponse r = t.send("GET", "/users/7");
        assertEquals(1, calls.get());
        assertEquals(201, r.status());
        assertEquals("7", r.header("X-Id"));
        assertEquals("user 7", text(r));
        assertNotNull(seen.get(0));
        assertEquals(Arrays.asList(Method.GET, "/users/7"), seen);
    }

    // SDS-3-AC-11; T01, FR-HTTP-007 AC-1, FR-HTTP-007 AC-2, FR-HTTP-007 AC-4
    @Test
    public void sds3Ac11_T01_FR_HTTP_007_AC1_AC2_AC4_oneHandlerForAMethodSetSeesTheActualMethod() {
        List<Method> methods = Collections.synchronizedList(new ArrayList<>());
        TestApp t = TestApp.create();
        t.app().route(Method.get().post().delete(), "/users/:id", (req, res) -> {
            methods.add(req.method());
            res.send(req.method().name());
        });
        assertEquals("GET", text(t.send("GET", "/users/1")));
        assertEquals("POST", text(t.send("POST", "/users/1")));
        assertEquals("DELETE", text(t.send("DELETE", "/users/1")));
        ClientResponse head = t.send("HEAD", "/users/1");
        assertEquals(200, head.status());
        assertEquals(0, head.body().length);
        assertEquals("4", head.header("Content-Length"));
        assertEmpty(405, t.send("PUT", "/users/1"));
        assertEquals(Arrays.asList(Method.GET, Method.POST, Method.DELETE, Method.HEAD), methods);
    }

    // SDS-3-AC-11; FR-HTTP-007 AC-3
    @Test
    public void sds3Ac11_FR_HTTP_007_AC3_patchRouteSeesPatch() {
        List<Method> methods = new ArrayList<>();
        TestApp t = TestApp.create();
        t.app().route(Method.PATCH, "/p", (req, res) -> methods.add(req.method()));
        assertEmpty(200, t.send("PATCH", "/p"));
        assertEquals(Collections.singletonList(Method.PATCH), methods);
    }

    // SDS-3-AC-7; T05, FR-HTTP-004 AC-1, FR-HTTP-004 AC-2
    @Test
    public void sds3Ac7_T05_FR_HTTP_004_AC1_AC2_automaticHeadUsesTheGetHandler() {
        List<Method> methods = new ArrayList<>();
        TestApp t = TestApp.create();
        t.app().get("/h", (req, res) -> {
            methods.add(req.method());
            res.status(200).header("X-A", "1").send("hello");
        });
        ClientResponse r = t.send("HEAD", "/h");
        assertEquals(200, r.status());
        assertEquals("1", r.header("X-A"));
        assertEquals(0, r.body().length);
        assertEquals(Collections.singletonList(Method.HEAD), methods);
    }

    // SDS-3-AC-14; FR-HTTP-001 AC-4, FR-HTTP-009 AC-1
    @Test
    public void sds3Ac14_FR_HTTP_001_AC4_rawPathAndQueryReachTheRequestUndecoded() {
        List<Object> seen = new ArrayList<>();
        TestApp t = TestApp.create();
        t.app().get("/:p", (req, res) -> {
            seen.add(req.rawPath());
            seen.add(req.path());
            seen.add(req.query("q"));
        });
        t.send("GET", "/a%20b?q=%20");
        assertEquals(Arrays.asList("/a%20b", "/a b", " "), seen);
    }

    // FR-HTTP-009 AC-1, FR-HTTP-009 AC-2, FR-HTTP-009 AC-3
    @Test
    public void FR_HTTP_009_AC1_AC2_AC3_decodedParamInsideTheHandler() {
        List<Object> seen = new ArrayList<>();
        TestApp t = TestApp.create();
        t.app().get("/files/:name", (req, res) -> {
            seen.add(req.path());
            seen.add(req.rawPath());
            seen.add(req.param("name"));
        });
        assertEquals(200, t.send("GET", "/files/a%20b?x=1").status());
        assertEquals(Arrays.asList("/files/a b", "/files/a%20b", "a b"), seen);
    }

    // FR-HTTP-012 AC-2
    @Test
    public void FR_HTTP_012_AC2_attributesAreIsolatedBetweenConcurrentRequests() throws Exception {
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        TestApp t = TestApp.create(adapter);
        CountDownLatch firstStored = new CountDownLatch(1);
        CountDownLatch secondDone = new CountDownLatch(1);
        t.app().get("/a", (req, res) -> {
            req.attribute("user", "first");
            firstStored.countDown();
            if (!secondDone.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("second request did not finish");
            }
            res.send(String.valueOf(req.attribute("user", String.class)));
        });
        t.app().get("/b", (req, res) -> {
            if (!firstStored.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("first request did not store");
            }
            res.send(String.valueOf(req.attribute("user", String.class)));
            secondDone.countDown();
        });
        t.start();
        try {
            Future<ClientResponse> a = adapter.submit(ClientRequest.of("GET", "/a"));
            Future<ClientResponse> b = adapter.submit(ClientRequest.of("GET", "/b"));
            assertEquals("null", text(b.get(10, TimeUnit.SECONDS)));
            assertEquals("first", text(a.get(10, TimeUnit.SECONDS)));
        } finally {
            adapter.release();
        }
    }

    // SDS-AC-12 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-4-3.sds.md; FR-APP-009 AC-7
    @Test
    public void sdsAc12_FR_APP_009_AC7_harnessRunsTheAppThroughListenSoHandlersSeeRunning() {
        TestApp t = TestApp.create();
        List<AppState> seen = new ArrayList<>();
        t.app().get("/s", (req, res) -> {
            seen.add(t.app().state());
            res.send("s");
        });
        assertEquals(AppState.NEW, t.app().state());
        ClientResponse r = t.send("GET", "/s");
        assertEquals(200, r.status());
        assertEquals(Arrays.asList(AppState.RUNNING), seen);
    }
}
