package com.snoworca.naruserve.servlet;

import static org.junit.Assert.*;

import com.snoworca.naruserve.HttpException;
import com.snoworca.naruserve.Method;
import com.snoworca.naruserve.RestApp;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;

/** Actual Jetty socket evidence for HTTP CORS, standard errors and the independent Servlet upgrade policy. */
public class CorsServletTest {
    private static final String ORIGIN = "https://app.example";
    @Rule public final ContainerRule container = new ContainerRule();
    private @Nullable RestApp app;
    private final AtomicInteger authenticated = new AtomicInteger();

    private void start(boolean assertInvalidConfiguration) throws Exception {
        app = SharedApp.onJetty(container, "/*", candidate -> {
            if (assertInvalidConfiguration) {
                assertThrows(IllegalArgumentException.class,
                        () -> candidate.cors(o -> o.allowedOrigins("*").credentials(true)));
            }
            candidate.use((req, res, next) -> {
                res.header("Vary", "Accept-Encoding");
                next.run();
            });
            candidate.cors(o -> o.allowedOrigins(ORIGIN).credentials(true)
                    .allowedMethods(Method.GET, Method.POST).allowedHeaders("Content-Type", "X-Token").maxAge(300));
            candidate.use("/auth", (req, res, next) -> {
                authenticated.incrementAndGet();
                throw new HttpException(401, "authentication required");
            });
            candidate.get("/json", (req, res) -> res.json("ok"));
            for (int status : new int[]{400, 401, 500}) {
                candidate.get("/error/" + status, (req, res) -> {
                    res.header("X-Application-Leak", "drop");
                    if (status == 500) throw new IllegalStateException("boom");
                    throw new HttpException(status, "invalid request");
                });
            }
            candidate.webSocket(o -> o.allowedOrigins(ORIGIN));
            candidate.ws("/ws", (req, ws) -> fail("Servlet adapter cannot upgrade"));
        });
    }

    @After public void stop() {
        if (app != null) app.shutdown(1, TimeUnit.SECONDS);
    }

    private SharedApp.Reply request(String method, String path, String... headers) throws Exception {
        return SharedApp.http(container.port(), method, path, null, headers);
    }

    private static Set<String> vary(SharedApp.Reply response) {
        Set<String> values = new HashSet<>();
        for (String line : response.headers("Vary")) {
            for (String part : line.split(",")) values.add(part.trim().toLowerCase(java.util.Locale.ROOT));
        }
        return values;
    }

    private static void assertJson(SharedApp.Reply response) {
        String contentType = response.header("Content-Type");
        assertNotNull(contentType);
        assertTrue(contentType, contentType.replace(" ", "").equalsIgnoreCase("application/json;charset=UTF-8"));
    }

    // FR-DX-010 AC-1: allowed origins get appropriate headers and Vary.
    @Test public void allowedJsonResponseHasCredentialsAndMergedCacheDimensions() throws Exception {
        start(false);
        SharedApp.Reply response = request("GET", "/json", "Origin", ORIGIN);
        assertEquals(200, response.status);
        assertEquals("\"ok\"", response.text());
        assertJson(response);
        assertEquals(ORIGIN, response.header("Access-Control-Allow-Origin"));
        assertEquals("true", response.header("Access-Control-Allow-Credentials"));
        assertEquals(new HashSet<>(Arrays.asList("origin", "accept-encoding")), vary(response));
    }

    // FR-DX-010 AC-2: denied origins are not granted access.
    @Test public void deniedOriginCannotReadNormalOrErrorResponses() throws Exception {
        start(false);
        for (String path : new String[]{"/json", "/error/400", "/error/401", "/error/500"}) {
            SharedApp.Reply response = request("GET", path, "Origin", "https://evil.example");
            assertNull(response.header("Access-Control-Allow-Origin"));
            assertNull(response.header("Access-Control-Allow-Credentials"));
            assertTrue(vary(response).contains("origin"));
            assertJson(response);
        }
    }

    // FR-DX-010 AC-3: credentials cannot combine with wildcard origin.
    @Test public void invalidWildcardCredentialsPolicyDoesNotRegisterAnyPartialGrant() throws Exception {
        start(true);
        assertEquals(ORIGIN, request("GET", "/json", "Origin", ORIGIN).header("Access-Control-Allow-Origin"));
        SharedApp.Reply denied = request("GET", "/json", "Origin", "https://evil.example");
        assertNull(denied.header("Access-Control-Allow-Origin"));
        assertNull(denied.header("Access-Control-Allow-Credentials"));
    }

    // FR-DX-010 AC-4: preflight terminates before downstream authentication.
    @Test public void preflightApprovesOrRejectsBeforeAuthentication() throws Exception {
        start(false);
        SharedApp.Reply allowed = request("OPTIONS", "/auth/resource", "Origin", ORIGIN,
                "Access-Control-Request-Method", "POST", "Access-Control-Request-Headers", "content-type, x-token");
        assertEquals(204, allowed.status);
        assertEquals(0, allowed.body.length);
        assertEquals(ORIGIN, allowed.header("Access-Control-Allow-Origin"));
        assertEquals("true", allowed.header("Access-Control-Allow-Credentials"));
        assertEquals("GET, POST", allowed.header("Access-Control-Allow-Methods"));
        assertEquals("content-type, x-token", allowed.header("Access-Control-Allow-Headers"));
        assertEquals("300", allowed.header("Access-Control-Max-Age"));
        assertTrue(vary(allowed).containsAll(Arrays.asList("origin", "access-control-request-method",
                "access-control-request-headers", "accept-encoding")));
        for (String[] headers : new String[][] {
            {"Origin", ORIGIN, "Access-Control-Request-Method", "DELETE"},
            {"Origin", ORIGIN, "Access-Control-Request-Method", "POST", "Access-Control-Request-Headers", "X-Denied"},
            {"Origin", "https://evil.example", "Access-Control-Request-Method", "POST"}
        }) {
            SharedApp.Reply denied = request("OPTIONS", "/auth/resource", headers);
            assertEquals(403, denied.status);
            assertEquals(0, denied.body.length);
            assertNull(denied.header("Access-Control-Allow-Origin"));
        }
        assertEquals(0, authenticated.get());
        SharedApp.Reply unauthenticated = request("GET", "/auth/resource", "Origin", ORIGIN);
        assertEquals(401, unauthenticated.status);
        assertEquals(1, authenticated.get());
        assertEquals(ORIGIN, unauthenticated.header("Access-Control-Allow-Origin"));
        assertJson(unauthenticated);
    }

    // FR-DX-010 AC-5: WebSocket Origin checks remain separate.
    @Test public void allowedWebSocketOriginStillGetsUnsupported501WithoutHttpCorsGrant() throws Exception {
        start(false);
        SharedApp.Reply response = request("GET", "/ws", "Origin", ORIGIN,
                "Upgrade", "websocket", "Connection", "Upgrade", "Sec-WebSocket-Version", "13",
                "Sec-WebSocket-Key", "dGhlIHNhbXBsZSBub25jZQ==");
        assertEquals(response.text(), 501, response.status);
        assertTrue(response.text(), response.isStandardError("[A-Z_]+"));
        assertJson(response);
        assertNull(response.header("Access-Control-Allow-Origin"));
        assertNull(response.header("Access-Control-Allow-Credentials"));
    }

    // FR-DX-010 AC-6: absent Origin varies safely; allowed400/401/500 preserve only framework CORS metadata.
    @Test public void errorsKeepFrameworkGrantAndCredentialsButDropArbitraryHeaders() throws Exception {
        start(false);
        SharedApp.Reply absentOrigin = request("GET", "/json");
        assertEquals(200, absentOrigin.status);
        assertEquals("\"ok\"", absentOrigin.text());
        assertTrue(vary(absentOrigin).contains("origin"));
        assertNull(absentOrigin.header("Access-Control-Allow-Origin"));
        for (int status : new int[]{400, 401, 500}) {
            SharedApp.Reply error = request("GET", "/error/" + status, "Origin", ORIGIN);
            assertEquals(status, error.status);
            assertTrue(error.text(), error.isStandardError(status == 401 ? "HTTP_401" : status == 400 ? "BAD_REQUEST" : "INTERNAL_ERROR"));
            assertJson(error);
            assertEquals(ORIGIN, error.header("Access-Control-Allow-Origin"));
            assertEquals("true", error.header("Access-Control-Allow-Credentials"));
            assertEquals(new HashSet<>(Arrays.asList("origin")), vary(error));
            assertNull(error.header("X-Application-Leak"));
        }
    }
}
