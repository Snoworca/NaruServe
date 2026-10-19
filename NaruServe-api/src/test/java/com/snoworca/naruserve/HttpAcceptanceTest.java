package com.snoworca.naruserve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.routing.RoutingConfigException;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.TestApp;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/**
 * object.md T02, T03, T05 and T06 through the in-memory harness, limited to the parts answered without a handler
 * call. The handler-reaching parts of T01, T03 and T05 (automatic HEAD) need DefaultRequest and DefaultResponse
 * from wave-3 parts 4 and 5.
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
}
