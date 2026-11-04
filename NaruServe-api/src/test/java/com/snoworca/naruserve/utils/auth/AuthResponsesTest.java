package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.HttpException;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.testing.TestApp;
import com.snoworca.naruserve.utils.auth.spi.AuthResponses;
import com.snoworca.naruserve.utils.auth.user.AuthUser;
import com.snoworca.naruserve.utils.auth.user.DuplicateUserException;
import com.snoworca.naruserve.utils.auth.user.ForwardingUserStore;
import com.snoworca.naruserve.utils.auth.user.InMemoryUserStore;
import java.sql.SQLException;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.junit.Test;

/** Every auth response is JSON: the middleware, the guards and the auth routers (FR-AUTH-006). */
public class AuthResponsesTest {

    private static final Pattern NO_TRACE = Pattern.compile("(?s).*(\\tat |Exception|\\.java:).*");

    private static void noLeak(ClientResponse r) {
        assertFalse(r.text(), NO_TRACE.matcher(r.text()).matches());
        assertFalse(r.text(), r.text().contains("SECRET-XYZ"));
    }

    // FR-AUTH-006 AC-1: no credentials -> 401 JSON unauthenticated with the request's own requestId; next not called
    @Test
    public void FR_AUTH_006_AC1_unauthenticatedBodyCarriesTheRequestId() {
        TestApp t = TestApp.create();
        AuthTestSupport.builder().build().install(t.app());
        final String[] seen = new String[1];
        t.app().use("/", (req, res, next) -> {
            seen[0] = "next";
            next.run();
        });
        ClientResponse r = t.send("GET", "/api/x");
        assertEquals(401, r.status());
        assertTrue(r.header("Content-Type"), r.header("Content-Type").startsWith("application/json"));
        String body = r.text();
        assertTrue(body, body.matches("\\{\"error\":\\{\"code\":\"unauthenticated\",\"message\":\"[^\"]+\","
                + "\"requestId\":\"[0-9a-f]{16}\"}}"));
        assertEquals(null, seen[0]);
        // the id is the request's own: the access log of a second app instance would show the same value
        TestApp echo = TestApp.create();
        final String[] id = new String[1];
        echo.app().use((req, res, next) -> {
            id[0] = req.requestId();
            next.run();
        });
        AuthTestSupport.builder().build().install(echo.app());
        ClientResponse e = echo.send("GET", "/api/x");
        assertTrue(e.text(), e.text().contains("\"requestId\":\"" + id[0] + "\""));
    }

    /** A store whose reads fail with a message that must not leak. */
    static final class FailingStore extends ForwardingUserStore {
        volatile boolean failing;

        FailingStore() {
            super(new InMemoryUserStore());
        }

        @Override
        public @Nullable AuthUser findById(String id) throws Exception {
            if (failing) {
                throw new SQLException("SECRET-XYZ connection refused");
            }
            return super.findById(id);
        }
    }

    // FR-AUTH-006 AC-2: a store failure during the global principal load is 503 JSON, also inside a guard
    @Test
    public void FR_AUTH_006_AC2_storeFailuresAre503Json() throws Exception {
        FailingStore store = new FailingStore();
        AuthUser alice = store.create(AuthUser.builder().username("alice").roles("admin").build());
        TestApp t = TestApp.create();
        AuthTestSupport.builder().users(store).build().install(t.app());
        t.app().get("/x", (req, res) -> res.send("ok"));
        store.failing = true;
        ClientResponse r = t.send("GET", "/x", AuthTestSupport.HEADER, alice.getId() + ":0");
        assertEquals(503, r.status());
        assertEquals("SERVICE_UNAVAILABLE", AuthTestSupport.code(r));
        assertTrue(r.header("Content-Type").startsWith("application/json"));
        noLeak(r);
        store.failing = false;
        TestApp g = TestApp.create();
        Auth stateless = AuthTestSupport.builder().users(store).statelessAccess(true).build();
        stateless.install(g.app());
        g.app().get("/y", stateless.require("posts:read"), (req, res) -> res.send("ok"));
        store.failing = true;
        ClientResponse guarded = g.send("GET", "/y", AuthTestSupport.HEADER, alice.getId() + ":0");
        assertEquals(503, guarded.status());
        assertEquals("SERVICE_UNAVAILABLE", AuthTestSupport.code(guarded));
        noLeak(guarded);
    }

    private static TestApp routerApp(InMemoryHttpAdapter adapter, Auth auth, RecordingLogger log) {
        TestApp t = TestApp.create(adapter);
        t.app().logger(log);
        t.app().jsonCodec(new AuthTestSupport.TestJsonCodec());
        auth.routes().post("/json", (req, res) -> res.json(req.body(Map.class)));
        auth.routes().post("/reject", (req, res) -> {
            throw new AuthRejection(422, "domain_not_allowed");
        });
        auth.routes().post("/dup", (req, res) -> {
            throw new DuplicateUserException("SECRET-XYZ alice exists");
        });
        auth.routes().post("/unavailable", (req, res) -> {
            throw new AuthUnavailableException(AuthErrorCode.SECURITY_WRITE_CONFLICT.getCode(), "SECRET-XYZ", null);
        });
        auth.routes().post("/sql", (req, res) -> {
            throw new SQLException("SECRET-XYZ");
        });
        auth.routes().post("/boom", (req, res) -> {
            throw new IllegalStateException("SECRET-XYZ");
        });
        auth.routes().post("/retry", (req, res) -> {
            throw new AuthRejection(429, "too_many_attempts", null, 30L);
        });
        auth.routes().post("/big", (req, res) -> {
            throw new HttpException(413, "SECRET-XYZ too large");
        });
        auth.install(t.app());
        t.start();
        return t;
    }

    // FR-AUTH-006 component (router error mapping): the auth router ErrorMiddleware answers each failure kind as JSON (the
    // POST /auth/password security_write_conflict case comes with that endpoint)
    @Test
    public void FR_AUTH_006_AC3_routerErrorMiddlewareMapsEveryFailure() {
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        RecordingLogger log = new RecordingLogger();
        Auth auth = AuthTestSupport.builder().permitPrefix("/auth").logger(log).build();
        routerApp(adapter, auth, log);
        ClientResponse bad = adapter.send(ClientRequest.json("POST", "/auth/json", "{not json"));
        assertEquals(400, bad.status());
        assertEquals("BAD_REQUEST", AuthTestSupport.code(bad));
        ClientResponse type = adapter.send(ClientRequest.of("POST", "/auth/json").header("Content-Type", "text/plain")
                .body("x".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals(415, type.status());
        assertEquals("UNSUPPORTED_MEDIA_TYPE", AuthTestSupport.code(type));
        ClientResponse big = AuthTestSupport.post(adapter, "/auth/big", "{}");
        assertEquals(413, big.status());
        assertEquals("PAYLOAD_TOO_LARGE", AuthTestSupport.code(big));
        ClientResponse rejected = AuthTestSupport.post(adapter, "/auth/reject", "{}");
        assertEquals(422, rejected.status());
        assertEquals("domain_not_allowed", AuthTestSupport.code(rejected));
        ClientResponse retry = AuthTestSupport.post(adapter, "/auth/retry", "{}");
        assertEquals(429, retry.status());
        assertEquals("30", retry.header("Retry-After"));
        ClientResponse dup = AuthTestSupport.post(adapter, "/auth/dup", "{}");
        assertEquals(409, dup.status());
        assertEquals("duplicate_user", AuthTestSupport.code(dup));
        ClientResponse conflict = AuthTestSupport.post(adapter, "/auth/unavailable", "{}");
        assertEquals(503, conflict.status());
        assertEquals("security_write_conflict", AuthTestSupport.code(conflict));
        ClientResponse sql = AuthTestSupport.post(adapter, "/auth/sql", "{}");
        assertEquals(503, sql.status());
        assertEquals("SERVICE_UNAVAILABLE", AuthTestSupport.code(sql));
        ClientResponse other = AuthTestSupport.post(adapter, "/auth/boom", "{}");
        assertEquals(500, other.status());
        assertEquals("INTERNAL_ERROR", AuthTestSupport.code(other));
        for (ClientResponse r : new ClientResponse[] {bad, type, big, rejected, retry, dup, conflict, sql, other}) {
            assertTrue(r.text(), r.header("Content-Type").startsWith("application/json"));
            noLeak(r);
        }
        // the unavailable, store and other cases are logged
        assertTrue(log.lines.toString(), RecordingLogger.countMatching(log.lines, ".*answering 503.*") >= 2);
        assertTrue(log.lines.toString(), RecordingLogger.countMatching(log.lines, ".*answering 500.*") >= 1);
    }

    // FR-AUTH-006 AC-4: no auth error body carries a stack trace, an exception class name or the exception message
    @Test
    public void FR_AUTH_006_AC4_noExceptionTextInBodies() {
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        RecordingLogger log = new RecordingLogger();
        Auth auth = AuthTestSupport.builder().permitPrefix("/auth").logger(log).build();
        routerApp(adapter, auth, log);
        for (String path : new String[] {"/auth/dup", "/auth/unavailable", "/auth/sql", "/auth/boom", "/auth/big"}) {
            ClientResponse r = AuthTestSupport.post(adapter, path, "{}");
            noLeak(r);
            assertFalse(path, r.text().contains("IllegalStateException") || r.text().contains("SQLException"));
        }
    }

    // FR-AUTH-006 AC-5: a throwing AuthResponses is logged and the default JSON body goes out with the same status
    @Test
    public void FR_AUTH_006_AC5_throwingResponsesFallBack() {
        RecordingLogger log = new RecordingLogger();
        TestApp t = TestApp.create();
        AuthTestSupport.builder(new AuthTestSupport.TestCredentials(), log).responses((req, res, status, code, msg) -> {
            throw new IllegalStateException("renderer broke");
        }).build().install(t.app());
        ClientResponse r = t.send("GET", "/x");
        assertEquals(401, r.status());
        assertEquals("unauthenticated", AuthTestSupport.code(r));
        assertTrue(r.text().startsWith("{\"error\":"));
        assertEquals(1, RecordingLogger.countMatching(log.lines, ".*AuthResponses implementation failed.*"));
    }

    // FR-AUTH-006 AC-6: an app AuthResponses reshapes the body, not the status
    @Test
    public void FR_AUTH_006_AC6_appResponsesChangeTheShapeOnly() {
        TestApp t = TestApp.create();
        AuthResponses custom = (req, res, status, code, msg) ->
                res.type("application/json").send("{\"err\":\"" + code + "\"}");
        AuthTestSupport.builder().responses(custom).build().install(t.app());
        ClientResponse r = t.send("GET", "/x");
        assertEquals(401, r.status());
        assertEquals("{\"err\":\"unauthenticated\"}", r.text());
        assertTrue(AuthResponses.class.isInterface());
        assertEquals("com.snoworca.naruserve.utils.auth.spi", AuthResponses.class.getPackage().getName());
    }

    // FR-AUTH-006 AC-7: the default responses render every auth-owned code without throwing
    @Test
    public void FR_AUTH_006_AC7_defaultRendersEveryCode() {
        final Auth auth = AuthTestSupport.builder().permit("/c").build();
        TestApp t = TestApp.create();
        auth.install(t.app());
        t.app().get("/c", (req, res) -> {
            AuthErrorCode c = AuthErrorCode.valueOf(req.query("code"));
            auth.respond(req, res, c.getStatus(), c.getCode(), null, null);
        });
        for (AuthErrorCode c : AuthErrorCode.values()) {
            ClientResponse r = t.send("GET", "/c?code=" + c.name());
            assertEquals(c.name(), c.getStatus(), r.status());
            assertEquals(c.name(), c.getCode(), AuthTestSupport.code(r));
            assertTrue(r.header("Content-Type").startsWith("application/json"));
        }
    }
}
