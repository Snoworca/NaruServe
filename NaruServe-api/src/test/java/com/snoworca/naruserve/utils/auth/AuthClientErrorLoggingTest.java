package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.HttpException;
import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.utils.auth.user.DuplicateUserException;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Test;

/** Auth 4xx answers leave no ERROR line; the 503 and 500 cases stay logged (OPS-AUTH-003). */
public class AuthClientErrorLoggingTest {

    private final W8 f = new W8();
    private final AdminFixture admin = new AdminFixture();
    private RestApp plain;

    @After
    public void stop() {
        f.stop();
        admin.stop();
        if (plain != null) {
            plain.shutdown(5, TimeUnit.SECONDS);
        }
    }

    // OPS-AUTH-003 AC-1: AuthRejection and DuplicateUserException are HttpExceptions carrying their 4xx status
    @Test
    public void OPS_AUTH_003_AC1_authClientErrorsAreHttpExceptions() {
        AuthRejection r = new AuthRejection(422, "domain_not_allowed", "no", 30L);
        assertTrue(r instanceof HttpException);
        assertTrue(r instanceof RuntimeException);
        assertEquals(422, r.status());
        assertEquals(422, r.getStatus());
        assertEquals("domain_not_allowed", r.getCode());
        assertEquals(Long.valueOf(30L), r.getRetryAfterSeconds());
        DuplicateUserException d = new DuplicateUserException("the username is taken");
        assertTrue(d instanceof HttpException);
        assertEquals(409, d.status());
    }

    private void noError(String what, ClientResponse r, int status, String code, RecordingLogger log) {
        assertEquals(what + ": " + r.text(), status, r.status());
        assertEquals(what, code, W8.code(r));
        assertEquals(what + ": " + log.lines, 0, log.count("ERROR"));
    }

    // OPS-AUTH-003 AC-2: refresh with an unknown token, a wrong current password, an auth-router AuthRejection, an app
    // AuthRejection under app.onError(auth.errors()) and a taken username answer their 4xx with zero ERROR lines
    @Test
    public void OPS_AUTH_003_AC2_clientErrorsAreNotLogged() throws Exception {
        f.user("alice", W8.PW, "reader");
        f.routes = w -> {
            w.auth.routes().post("/ops/reject", (req, res) -> {
                throw new AuthRejection(422, "domain_not_allowed");
            });
            w.app.post("/api/tenant", w.auth.require("posts:read"), (req, res) -> {
                throw new AuthRejection(422, "tenant_required");
            });
        };
        f.start(b -> b);
        String token = (String) f.loginBody("alice", W8.PW).get("accessToken");
        String bearer = "Bearer " + token;
        noError("refresh", f.post("/auth/refresh", "{\"refreshToken\":\"bogus.refresh.token\"}"), 401,
                "invalid_refresh_token", f.logger);
        ClientResponse pw = f.post("/auth/password",
                "{\"currentPassword\":\"wrong password!\",\"newPassword\":\"another good one\"}", "Authorization", bearer);
        assertEquals(pw.text(), 401, pw.status());
        assertEquals(f.logger.lines.toString(), 0, f.logger.count("ERROR"));
        noError("auth router", f.post("/auth/ops/reject", null, "Authorization", bearer), 422, "domain_not_allowed",
                f.logger);
        noError("app route", f.post("/api/tenant", null, "Authorization", bearer), 422, "tenant_required", f.logger);

        admin.start();
        String root = admin.user("root", "admin");
        String body = "{\"username\":\"bob\",\"password\":\"bob-password-1\",\"roles\":[\"reader\"]}";
        assertEquals(201, admin.call("POST", "/auth/admin/users", root, body).status());
        noError("duplicate", admin.call("POST", "/auth/admin/users", root, body), 409, "duplicate_user",
                admin.logger);
    }

    // OPS-AUTH-003 AC-3: without an auth error layer an app AuthRejection is Core's 422, not 500
    @Test
    public void OPS_AUTH_003_AC3_withoutErrorLayerCoreAnswersTheStatus() {
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        RecordingLogger log = new RecordingLogger();
        plain = RestApp.builder().httpAdapter(adapter).jsonCodec(new AdminFixture.MiniJsonCodec()).logger(log).build();
        plain.get("/t", (req, res) -> {
            throw new AuthRejection(422, "tenant_required");
        });
        plain.listen(0);
        ClientResponse r = adapter.send(ClientRequest.of("GET", "/t"));
        assertEquals(r.text(), 422, r.status());
        assertEquals(log.lines.toString(), 0, log.count("ERROR"));
    }

    // OPS-AUTH-003 AC-4: AuthUnavailableException, a store IOException and any other exception in an auth router are
    // still logged at ERROR
    @Test
    public void OPS_AUTH_003_AC4_serverFailuresStayLogged() throws Exception {
        f.user("alice", W8.PW, "reader");
        f.routes = w -> {
            w.auth.routes().post("/ops/down", (req, res) -> {
                throw new AuthUnavailableException("store down");
            });
            w.auth.routes().post("/ops/io", (req, res) -> {
                throw new java.io.IOException("disk gone");
            });
            w.auth.routes().post("/ops/bug", (req, res) -> {
                throw new IllegalStateException("a bug");
            });
        };
        f.start(b -> b);
        String bearer = "Bearer " + f.loginBody("alice", W8.PW).get("accessToken");
        String[][] cases = {{"/auth/ops/down", "503"}, {"/auth/ops/io", "503"}, {"/auth/ops/bug", "500"}};
        for (String[] c : cases) {
            int before = f.logger.count("ERROR");
            ClientResponse r = f.post(c[0], null, "Authorization", bearer);
            assertEquals(c[0], Integer.parseInt(c[1]), r.status());
            assertTrue(c[0] + ": " + f.logger.lines, f.logger.count("ERROR") > before);
        }
    }
}
