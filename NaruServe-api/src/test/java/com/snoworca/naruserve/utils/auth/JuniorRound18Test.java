package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.utils.auth.internal.AuthArgumentException;
import com.snoworca.naruserve.utils.auth.testing.AuthTesting;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Test;

/** The code findings of the eighteenth junior study are answered with the fix (OPS-AUTH-030). */
public class JuniorRound18Test {

    private final List<W8> fixtures = new ArrayList<>();

    @After
    public void stop() {
        for (W8 f : fixtures) {
            f.stop();
        }
    }

    private W8 fixture() {
        W8 f = new W8();
        fixtures.add(f);
        return f;
    }

    private static String message(ClientResponse r) {
        return String.valueOf(((Map<?, ?>) W8.object(r).get("error")).get("message"));
    }

    private static String token(W8 f) throws Exception {
        f.user("bob", W8.PW, "reader");
        return String.valueOf(f.loginBody("bob", W8.PW).get("accessToken"));
    }

    // OPS-AUTH-030 AC-1: a guard refusing an anonymous request on a permitted path says a guard refused
    @Test
    public void OPS_AUTH_030_AC1_guardOnPermittedPathIsNamed() {
        W8 f = fixture();
        f.routes = w -> w.app.get("/open", w.auth.require("posts:read"), (req, res) -> res.send("o"));
        f.start(b -> b.permit("/open"));
        String m = message(f.get("/open"));
        assertTrue(m, m.contains("guard") && m.contains(".permit"));
    }

    // OPS-AUTH-030 AC-2: a token cut by a version raise says why
    @Test
    public void OPS_AUTH_030_AC2_noLongerValidSaysWhy() throws Exception {
        W8 f = fixture();
        f.start(b -> b);
        String t = token(f);
        assertEquals(204, f.post("/auth/logout?all=true", null, "Authorization", "Bearer " + t).status());
        String m = message(f.get("/api/x", "Authorization", "Bearer " + t));
        assertTrue(m, m.startsWith("the token is no longer valid") && m.contains("logged out everywhere"));
    }

    // OPS-AUTH-030 AC-3: in DEV, a 401 for a GET of a literal route without a permit logs one WARN
    @Test
    public void OPS_AUTH_030_AC3_devProtectedRouteIsWarned() {
        W8 f = fixture();
        f.start(b -> b.mode(AuthMode.DEV));
        for (int i = 0; i < 2; i++) {
            assertEquals(401, f.get("/health").status());
        }
        List<String> warns = RecordingLogger.matching(f.logger.lines, "(?s)WARN .*GET /health.*\\.permit\\(\"/health\"\\).*");
        assertEquals(f.logger.lines.toString(), 1, warns.size());
    }

    // OPS-AUTH-030 AC-4: POST .../token is a login-like path
    @Test
    public void OPS_AUTH_030_AC4_tokenPathNamesLogin() {
        W8 f = fixture();
        f.start(b -> b);
        assertEquals("authentication is required; auth's login endpoint is POST /auth/login",
                message(f.post("/auth/token", "{}")));
    }

    // OPS-AUTH-030 AC-5: the lock of an account logs one WARN naming the release
    @Test
    public void OPS_AUTH_030_AC5_lockIsLogged() throws Exception {
        W8 f = fixture();
        f.start(b -> b.lockout(Lockout.after(2).forMinutes(15)));
        f.user("bob", W8.PW, "reader");
        for (int i = 0; i < 3; i++) {
            f.post("/auth/login", W8.login("bob", "wrong-password-x"));
        }
        List<String> warns = RecordingLogger.matching(f.logger.lines, "(?s)WARN .*locked.*DELETE /auth/admin/users/.*");
        assertEquals(f.logger.lines.toString(), 1, warns.size());
    }

    // OPS-AUTH-030 AC-6: AuthTesting.apiKeyFor with a username says to pass the id
    @Test
    public void OPS_AUTH_030_AC6_apiKeyForNamesTheId() throws Exception {
        W8 f = fixture();
        f.start(b -> b.apiKeys(new AdminFixture.ListKeys()));
        f.user("alice", W8.PW, "reader");
        try {
            AuthTesting.apiKeyFor(f.auth, "alice", "posts:read");
            fail("issued");
        } catch (AuthArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("not the username"));
        }
    }

    // OPS-AUTH-030 AC-7: ?all=TRUE says the value is lower case
    @Test
    public void OPS_AUTH_030_AC7_upperCaseAllIsNamed() throws Exception {
        W8 f = fixture();
        f.start(b -> b);
        String t = token(f);
        String m = message(f.post("/auth/logout?all=TRUE", null, "Authorization", "Bearer " + t));
        assertTrue(m, m.contains("lower-case") && m.contains("TRUE"));
    }

    // OPS-AUTH-030 AC-8: a personal key with an unheld scope says so in plain words
    @Test
    public void OPS_AUTH_030_AC8_personalKeyScopeIsPlain() throws Exception {
        W8 f = fixture();
        f.start(b -> b.apiKeys(new AdminFixture.ListKeys()));
        String t = token(f);
        ClientResponse r = f.adapter.send(ClientRequest.json("POST", "/auth/me/apikeys", "{\"name\":\"ci\",\"scopes\":"
                + "[\"billing:read\"],\"password\":\"" + W8.PW + "\"}").header("Authorization", "Bearer " + t));
        assertEquals(r.text(), 403, r.status());
        assertEquals("you cannot put billing:read on your own key because you do not hold it", message(r));
    }

    // OPS-AUTH-030 AC-1: all four guard kinds answer 401 on a permitted path and say .permit opens default deny only
    @Test
    public void OPS_AUTH_030_AC1_everyGuardKindOnPermittedPathIsNamed() {
        for (int kind = 0; kind < 4; kind++) {
            final int k = kind;
            W8 f = fixture();
            f.routes = w -> w.app.get("/open", k == 0 ? w.auth.require("posts:read") : k == 1 ? w.auth.requireRole("reader")
                    : k == 2 ? w.auth.check((p, req) -> true) : w.auth.authenticated(), (req, res) -> res.send("o"));
            f.start(b -> b.permit("/open"));
            ClientResponse r = f.get("/open");
            assertEquals(r.text(), 401, r.status());
            assertTrue(message(r), message(r).contains("a guard on this route")
                    && message(r).contains(".permit opens default deny only, not the guards"));
        }
    }

    // OPS-AUTH-030 AC-2: the answer is 401 invalid_token and names the password change and the disabled account too
    @Test
    public void OPS_AUTH_030_AC2_noLongerValidIs401AndNamesEveryCause() throws Exception {
        W8 f = fixture();
        f.start(b -> b);
        String t = token(f);
        assertEquals(204, f.post("/auth/logout?all=true", null, "Authorization", "Bearer " + t).status());
        ClientResponse r = f.get("/api/x", "Authorization", "Bearer " + t);
        assertEquals(r.text(), 401, r.status());
        assertEquals("invalid_token", W8.code(r));
        assertTrue(message(r), message(r).contains("changed the password") && message(r).contains("was disabled"));
    }

    // OPS-AUTH-030 AC-3: a HEAD of a literal route gets the WARN like a GET
    @Test
    public void OPS_AUTH_030_AC3_devHeadOfProtectedRouteIsWarned() {
        W8 f = fixture();
        f.start(b -> b.mode(AuthMode.DEV));
        assertEquals(401, f.adapter.send(ClientRequest.of("HEAD", "/health")).status());
        assertEquals(f.logger.lines.toString(), 1, RecordingLogger.matching(f.logger.lines,
                "(?s)WARN .*HEAD /health.*\\.permit\\(\"/health\"\\).*").size());
    }

    // OPS-AUTH-030 AC-5: the lock WARN names the user id, the lock end and the full release route
    @Test
    public void OPS_AUTH_030_AC5_lockWarnNamesIdEndAndRoute() throws Exception {
        W8 f = fixture();
        f.start(b -> b.lockout(Lockout.after(2).forMinutes(15)));
        String id = f.user("bob", W8.PW, "reader");
        for (int i = 0; i < 3; i++) {
            f.post("/auth/login", W8.login("bob", "wrong-password-x"));
        }
        List<String> warns = RecordingLogger.matching(f.logger.lines, "(?s)WARN .*locked out until .*");
        assertEquals(f.logger.lines.toString(), 1, warns.size());
        assertTrue(warns.get(0), warns.get(0).contains("user id " + id)
                && warns.get(0).contains("DELETE /auth/admin/users/" + id + "/lockout"));
    }

    // OPS-AUTH-030 AC-6: auth.apiKeys().issue itself says to pass the id
    @Test
    public void OPS_AUTH_030_AC6_issueNamesTheId() throws Exception {
        W8 f = fixture();
        f.start(b -> b.apiKeys(new AdminFixture.ListKeys()));
        f.user("alice", W8.PW, "reader");
        try {
            f.auth.apiKeys().issue("alice", "ci", null, "posts:read");
            fail("issued");
        } catch (AuthArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("not the username"));
        }
    }

    // OPS-AUTH-030 AC-7: a case variant of false is named like one of true
    @Test
    public void OPS_AUTH_030_AC7_mixedCaseFalseIsNamed() throws Exception {
        W8 f = fixture();
        f.start(b -> b);
        String t = token(f);
        String m = message(f.post("/auth/logout?all=False", null, "Authorization", "Bearer " + t));
        assertTrue(m, m.contains("lower-case true or false") && m.contains("False"));
    }

    // OPS-AUTH-030 AC-8: the personal key refusal has the code insufficient_scope
    @Test
    public void OPS_AUTH_030_AC8_personalKeyScopeIsInsufficientScope() throws Exception {
        W8 f = fixture();
        f.start(b -> b.apiKeys(new AdminFixture.ListKeys()));
        String t = token(f);
        ClientResponse r = f.adapter.send(ClientRequest.json("POST", "/auth/me/apikeys", "{\"name\":\"ci\",\"scopes\":"
                + "[\"billing:read\"],\"password\":\"" + W8.PW + "\"}").header("Authorization", "Bearer " + t));
        assertEquals("insufficient_scope", W8.code(r));
    }
}
