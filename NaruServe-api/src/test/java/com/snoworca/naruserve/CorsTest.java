package com.snoworca.naruserve;

import static org.junit.Assert.*;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.TestApp;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/** FR-DX-010 AC-1 through AC-5: CORS decisions through the real middleware pipeline. */
public class CorsTest {
    private static final String ORIGIN = "https://app.example";

    // FR-DX-010 AC-1, AC-2
    @Test public void exactOriginsGetCredentialsAndMergedVaryButOthersGetNoGrant() {
        TestApp t = TestApp.create();
        t.app().use((req, res, next) -> { res.header("Vary", "Accept-Encoding, origin"); next.run(); });
        t.app().cors(o -> o.allowedOrigins(ORIGIN).credentials(true));
        t.app().get("/x", (req, res) -> res.send("ok"));
        ClientResponse allowed = t.send("GET", "/x", "Origin", ORIGIN);
        assertEquals(ORIGIN, allowed.header("Access-Control-Allow-Origin"));
        assertEquals("true", allowed.header("Access-Control-Allow-Credentials"));
        assertEquals("Accept-Encoding, origin", allowed.header("Vary"));
        for (String denied : new String[]{"https://app.example.evil", "https://APP.example", "null", "https://evil.example"}) {
            ClientResponse response = t.send("GET", "/x", "Origin", denied);
            assertEquals("ok", response.text());
            assertNull(response.header("Access-Control-Allow-Origin"));
            assertNull(response.header("Access-Control-Allow-Credentials"));
        }
    }

    // FR-DX-010 AC-1, AC-4
    @Test public void preflightSucceedsBeforeAuthenticationAndChecksRequestedHeaders() {
        TestApp t = TestApp.create();
        t.app().cors(o -> o.allowedOrigins(ORIGIN).allowedMethods(Method.GET, Method.POST)
                .allowedHeaders("Content-Type", "X-Token").maxAge(600));
        t.app().use((req, res, next) -> res.status(401).end());
        ClientResponse response = t.send("OPTIONS", "/x", "Origin", ORIGIN,
                "Access-Control-Request-Method", "POST", "Access-Control-Request-Headers", "content-type, x-token");
        assertEquals(204, response.status());
        assertEquals(0, response.body().length);
        assertEquals(ORIGIN, response.header("Access-Control-Allow-Origin"));
        assertEquals("GET, POST", response.header("Access-Control-Allow-Methods"));
        assertEquals("content-type, x-token", response.header("Access-Control-Allow-Headers"));
        assertEquals("600", response.header("Access-Control-Max-Age"));
        assertEquals("Origin, Access-Control-Request-Method, Access-Control-Request-Headers", response.header("Vary"));
        assertEquals(401, t.send("GET", "/x", "Origin", ORIGIN).status());
    }

    // FR-DX-010 AC-2, AC-4
    @Test public void rejectedOrMalformedPreflightIs403WithNoAccessGrant() {
        TestApp t = TestApp.create();
        t.app().cors(o -> o.allowedOrigins(ORIGIN).allowedMethods(Method.POST).allowedHeaders("X-Allowed"));
        t.app().use((req, res, next) -> res.status(401).end());
        String[][] cases = {
            {"Origin", "https://evil.example", "Access-Control-Request-Method", "POST"},
            {"Origin", ORIGIN, "Access-Control-Request-Method", "DELETE"},
            {"Origin", ORIGIN, "Access-Control-Request-Method", "post"},
            {"Origin", ORIGIN, "Access-Control-Request-Method", "POST", "Access-Control-Request-Headers", "X-Denied"},
            {"Origin", ORIGIN, "Access-Control-Request-Method", "POST", "Access-Control-Request-Headers", "X-Allowed,"},
            {"Origin", ORIGIN, "Access-Control-Request-Method", "POST", "Access-Control-Request-Headers", "X Bad"},
            {"Origin", ORIGIN, "Origin", "https://evil.example", "Access-Control-Request-Method", "POST"},
            {"Origin", ORIGIN, "Access-Control-Request-Method", "POST", "Access-Control-Request-Method", "GET"}
        };
        for (String[] headers : cases) {
            ClientResponse response = t.send("OPTIONS", "/x", headers);
            assertEquals(403, response.status());
            assertNull(response.header("Access-Control-Allow-Origin"));
        }
    }

    // FR-DX-010 AC-1, AC-2, AC-5
    @Test public void noOriginAndOrdinaryOptionsStayUntouchedAndWildcardVaryIsPreserved() {
        TestApp t = TestApp.create();
        t.app().use((req, res, next) -> { res.header("Vary", "*"); next.run(); });
        t.app().cors(o -> o.allowedOrigins("*"));
        t.app().options("/x", (req, res) -> res.send("options"));
        t.app().get("/x", (req, res) -> res.send("get"));
        assertNull(t.send("GET", "/x").header("Access-Control-Allow-Origin"));
        assertEquals("options", t.send("OPTIONS", "/x", "Origin", ORIGIN).text());
        ClientResponse allowed = t.send("GET", "/x", "Origin", ORIGIN);
        assertEquals("*", allowed.header("Access-Control-Allow-Origin"));
        assertEquals("*", allowed.header("Vary"));
        assertNull(allowed.header("Access-Control-Allow-Credentials"));
    }

    // FR-DX-010 AC-3 — validation is atomic and retained options cannot change live policy.
    @Test public void invalidConfigurationRegistersNothingAndSuccessfulPolicyIsImmutable() {
        TestApp t = TestApp.create();
        assertThrows(IllegalArgumentException.class, () -> t.app().cors(o -> o.allowedOrigins("*").credentials(true)));
        assertThrows(IllegalArgumentException.class, () -> t.app().cors(o -> o.allowedOrigins(ORIGIN + "/path")));
        assertThrows(IllegalArgumentException.class, () -> t.app().cors(o -> o.allowedOrigins(ORIGIN).allowedHeaders("X\r\nBad")));
        assertThrows(IllegalArgumentException.class, () -> t.app().cors(o -> o.allowedOrigins(ORIGIN).maxAge(-1)));
        AtomicReference<CorsOptions> kept = new AtomicReference<>();
        t.app().cors(o -> { kept.set(o); o.allowedOrigins(ORIGIN); });
        kept.get().allowedOrigins("https://evil.example");
        t.app().get("/x", (req, res) -> res.send("ok"));
        assertEquals(ORIGIN, t.send("GET", "/x", "Origin", ORIGIN).header("Access-Control-Allow-Origin"));
        assertNull(t.send("GET", "/x", "Origin", "https://evil.example").header("Access-Control-Allow-Origin"));
        boolean[] called = {false};
        assertThrows(IllegalStateException.class, () -> t.app().cors(o -> called[0] = true));
        assertFalse(called[0]);
    }

    // FR-DX-010 AC-7 — an origin is scheme://host[:port] whatever its scheme: a listed custom scheme gets the same grant
    // as an http(s) origin, on preflight, on the request and on an error response.
    @Test public void listedCustomSchemeOriginsGetPreflightRequestAndErrorGrants() {
        String[] listed = {"capacitor://localhost", "ionic://localhost"};
        TestApp t = TestApp.create();
        t.app().cors(o -> o.allowedOrigins(listed).allowedMethods(Method.GET, Method.POST).allowedHeaders("Content-Type"));
        t.app().get("/x", (req, res) -> res.send("ok"));
        t.app().get("/boom", (req, res) -> { throw new IllegalStateException("boom"); });
        for (String origin : listed) {
            ClientResponse preflight = t.send("OPTIONS", "/x", "Origin", origin,
                    "Access-Control-Request-Method", "POST", "Access-Control-Request-Headers", "content-type");
            assertEquals(origin, 204, preflight.status());
            assertEquals(origin, preflight.header("Access-Control-Allow-Origin"));
            assertEquals(origin, t.send("GET", "/x", "Origin", origin).header("Access-Control-Allow-Origin"));
            ClientResponse failed = t.send("GET", "/boom", "Origin", origin);
            assertEquals(500, failed.status());
            assertEquals(origin, failed.header("Access-Control-Allow-Origin"));
        }
    }

    // FR-DX-010 AC-2, AC-7 — the list stays an exact match: a custom scheme nobody listed is still denied.
    @Test public void unlistedCustomSchemeOriginIsDenied() {
        TestApp t = TestApp.create();
        t.app().cors(o -> o.allowedOrigins("capacitor://localhost").allowedMethods(Method.POST));
        t.app().get("/x", (req, res) -> res.send("ok"));
        String[] preflight = {"Access-Control-Request-Method", "POST"};
        for (String other : new String[]{"ionic://localhost", "capacitor://other", "capacitor://localhost:8080"}) {
            ClientResponse denied = t.send("OPTIONS", "/x", "Origin", other, preflight[0], preflight[1]);
            assertEquals(other, 403, denied.status());
            assertNull(other, denied.header("Access-Control-Allow-Origin"));
            assertNull(other, t.send("GET", "/x", "Origin", other).header("Access-Control-Allow-Origin"));
        }
    }

    // FR-DX-010 AC-7 — * covers every origin of that form, which is what docs/api/middleware.md promises.
    @Test public void wildcardGrantsCustomSchemeOrigins() {
        TestApp t = TestApp.create();
        t.app().cors(o -> o.allowedOrigins("*").allowedMethods(Method.POST));
        t.app().get("/x", (req, res) -> res.send("ok"));
        for (String origin : new String[]{"capacitor://localhost", "ionic://localhost", "chrome-extension://abcdef"}) {
            ClientResponse preflight = t.send("OPTIONS", "/x", "Origin", origin, "Access-Control-Request-Method", "POST");
            assertEquals(origin, 204, preflight.status());
            assertEquals("*", preflight.header("Access-Control-Allow-Origin"));
            assertEquals("*", t.send("GET", "/x", "Origin", origin).header("Access-Control-Allow-Origin"));
        }
    }

    // FR-DX-010 AC-7 — a value that is not scheme://host[:port] is refused at configuration and gets no grant on a request.
    @Test public void malformedCustomSchemeValuesAreRefusedAndGetNoGrant() {
        String[] malformed = {"capacitor://localhost/", "capacitor://localhost/path", "capacitor://u@localhost",
            "capacitor://localhost?q=1", "capacitor://localhost#f", "capacitor:x", "capacitor:/x", "file://", "file:///a"};
        for (String value : malformed) {
            TestApp refused = TestApp.create();
            assertThrows(value, IllegalArgumentException.class, () -> refused.app().cors(o -> o.allowedOrigins(value)));
        }
        TestApp t = TestApp.create();
        t.app().cors(o -> o.allowedOrigins("*").allowedMethods(Method.POST));
        t.app().get("/x", (req, res) -> res.send("ok"));
        for (String value : malformed) {
            assertNull(value, t.send("GET", "/x", "Origin", value).header("Access-Control-Allow-Origin"));
            ClientResponse preflight = t.send("OPTIONS", "/x", "Origin", value, "Access-Control-Request-Method", "POST");
            assertEquals(value, 403, preflight.status());
            assertNull(value, preflight.header("Access-Control-Allow-Origin"));
        }
    }

    // FR-DX-010 AC-5 — HTTP CORS cannot authorize a WebSocket origin.
    @Test public void corsAllowlistDoesNotBypassWebSocketOriginCheck() {
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        TestApp t = TestApp.create(adapter);
        t.app().cors(o -> o.allowedOrigins(ORIGIN));
        t.app().ws("/ws", (req, ws) -> fail("foreign origin must not upgrade"));
        t.start();
        ClientResponse response = adapter.send(ClientRequest.upgrade("/ws", "Origin", ORIGIN, "Host", "localhost"));
        assertEquals(403, response.status());
        assertNull(response.header("Access-Control-Allow-Origin"));
    }
    // FR-DX-010 AC-5 — a separately allowed upgrade gets no HTTP CORS response headers.
    @Test(timeout = 15000) public void allowedWebSocketUpgradeKeepsItsSeparatePolicy() throws Exception {
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        TestApp t = TestApp.create(adapter);
        t.app().cors(o -> o.allowedOrigins(ORIGIN));
        t.app().webSocket(o -> o.allowedOrigins(ORIGIN));
        t.app().ws("/ws", (req, ws) -> { });
        t.start();
        ClientResponse response = WebSocketDispatchTest.accepted(adapter,
                ClientRequest.upgrade("/ws", "Origin", ORIGIN, "Host", "localhost"));
        assertEquals(101, response.status());
        assertNull(response.header("Access-Control-Allow-Origin"));
    }

    // FR-DX-010 AC-1 — responses without Origin still vary to protect shared caches.
    @Test public void absentOriginChangesNoRoutingOrGrantButStillVariesOnOrigin() { // FR-DX-010 AC-6
        TestApp t = TestApp.create();
        t.app().cors(o -> o.allowedOrigins(ORIGIN));
        t.app().get("/x", (req, res) -> res.send("ok"));
        ClientResponse response = t.send("GET", "/x");
        assertEquals("ok", response.text());
        assertEquals("Origin", response.header("Vary"));
        assertNull(response.header("Access-Control-Allow-Origin"));
    }

    // FR-DX-010 AC-1 AC-2 AC-6; CON-APP-010 AC-4: error helpers cannot replace the configured grant decision.
    @Test public void exceptionHeadersCannotOverrideFrameworkCorsGrantOrCreateADeniedGrant() {
        TestApp t=TestApp.create();t.app().cors(o->o.allowedOrigins(ORIGIN).credentials(true));
        t.app().get("/spoof",(req,res)->{throw new HttpException(400,"bad")
                .withHeader("Access-Control-Allow-Origin","*").withHeader("Access-Control-Allow-Credentials","false")
                .withHeader("Vary","X-Unrelated");});
        ClientResponse allowed=t.send("GET","/spoof","Origin",ORIGIN);
        assertEquals(400,allowed.status());assertEquals(ORIGIN,allowed.header("Access-Control-Allow-Origin"));
        assertEquals("true",allowed.header("Access-Control-Allow-Credentials"));assertEquals("Origin",allowed.header("Vary"));
        ClientResponse denied=t.send("GET","/spoof","Origin","https://evil.example");
        assertEquals(400,denied.status());assertNull(denied.header("Access-Control-Allow-Origin"));
        assertNull(denied.header("Access-Control-Allow-Credentials"));assertEquals("Origin",denied.header("Vary"));
    }

    // FR-DX-010 AC-1, AC-2 — only framework CORS metadata survives standard error reset.
    @Test public void standardErrorsPreserveCorsMetadataWithoutPreservingApplicationHeaders() { // FR-DX-010 AC-6
        TestApp t = TestApp.create();
        t.app().cors(o -> o.allowedOrigins(ORIGIN).credentials(true));
        t.app().get("/400", (req, res) -> {
            res.header("X-Leak", "drop");
            throw new HttpException(400, "bad");
        });
        t.app().get("/401", (req, res) -> { throw new HttpException(401, "unauthorized"); });
        t.app().get("/500", (req, res) -> { throw new IllegalStateException("boom"); });
        for (int code : new int[]{400, 401, 500}) {
            ClientResponse allowed = t.send("GET", "/" + code, "Origin", ORIGIN);
            assertEquals(code, allowed.status());
            assertEquals(ORIGIN, allowed.header("Access-Control-Allow-Origin"));
            assertEquals("true", allowed.header("Access-Control-Allow-Credentials"));
            assertEquals("Origin", allowed.header("Vary"));
            assertNull(allowed.header("X-Leak"));
            ClientResponse denied = t.send("GET", "/" + code, "Origin", "https://evil.example");
            assertEquals(code, denied.status());
            assertNull(denied.header("Access-Control-Allow-Origin"));
            assertNull(denied.header("Access-Control-Allow-Credentials"));
            assertEquals("Origin", denied.header("Vary"));
        }
    }

}
