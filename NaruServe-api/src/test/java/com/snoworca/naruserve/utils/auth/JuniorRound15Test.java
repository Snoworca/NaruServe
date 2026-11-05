package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.testing.TestApp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Test;

/** The code findings of the fifteenth junior study are answered with the fix (OPS-AUTH-027). */
public class JuniorRound15Test {

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

    // OPS-AUTH-027 AC-1: a key naming a scope its owner lacks is warned at issuance, and its 403 names the owner rule
    @Test
    public void OPS_AUTH_027_AC1_keyBeyondOwnerScopesIsNamed() {
        W8 f = fixture();
        f.start(b -> b.apiKeys(new AdminFixture.ListKeys()));
        String bot = f.auth.users().create("ci-bot", null, u -> u.toBuilder().serviceAccount(true).build()).getId();
        String key = f.auth.apiKeys().issue(bot, "ci", null, "posts:read").getPlainKey();
        List<String> warns = RecordingLogger.matching(f.logger.lines, "(?s)WARN .*does not hold.*posts:read.*");
        assertEquals(f.logger.lines.toString(), 1, warns.size());
        ClientResponse r = f.get("/api/x", "Authorization", "ApiKey " + key);
        assertEquals(r.text(), 403, r.status());
        assertTrue(message(r), message(r).endsWith("; an API key acts only with the scopes its owner also holds"));
    }

    // OPS-AUTH-027 AC-2: an unknown field on POST /auth/me/apikeys is refused
    @Test
    public void OPS_AUTH_027_AC2_unknownKeyFieldIsRefused() throws Exception {
        W8 f = fixture();
        f.start(b -> b.apiKeys(new AdminFixture.ListKeys()));
        f.user("bob", W8.PW, "reader");
        String t = String.valueOf(f.loginBody("bob", W8.PW).get("accessToken"));
        ClientResponse r = f.adapter.send(ClientRequest.json("POST", "/auth/me/apikeys", "{\"name\":\"ci\",\"scopes\":"
                + "[\"posts:read\"],\"password\":\"" + W8.PW + "\",\"ownerUserId\":\"3\"}").header("Authorization", "Bearer " + t));
        assertEquals(r.text(), 400, r.status());
        assertTrue(message(r), message(r).startsWith("unknown field ownerUserId; the fields are name, scopes, "));
    }

    // OPS-AUTH-027 AC-3: a proxied PROD request without X-Forwarded-Proto names the header, once
    @Test
    public void OPS_AUTH_027_AC3_proxyWithoutForwardedProtoIsNamed() {
        W8 f = fixture();
        f.start("10.0.0.5", false, b -> b);
        for (int i = 0; i < 2; i++) {
            assertEquals(403, f.post("/auth/login", W8.login("bob", W8.PW), "X-Forwarded-For", "203.0.113.7").status());
        }
        List<String> warns = RecordingLogger.matching(f.logger.lines, "(?s)WARN .*X-Forwarded-Proto.*");
        assertEquals(f.logger.lines.toString(), 1, warns.size());
        assertTrue(warns.get(0), warns.get(0).contains("proxy_set_header X-Forwarded-Proto $scheme"));
    }

    // OPS-AUTH-027 AC-4: auth.users().create with a null password is a shown argument refusal
    @Test
    public void OPS_AUTH_027_AC4_nullPasswordCreateIsNamed() {
        W8 f = fixture();
        f.routes = w -> w.app.get("/mk", (req, res) -> res.send(w.auth.users().create("np", (char[]) null).getId()));
        f.start(b -> b.permit("/mk"));
        ClientResponse r = f.get("/mk");
        assertEquals(r.text(), 400, r.status());
        assertTrue(message(r), message(r).startsWith("a password is required"));
    }

    // OPS-AUTH-027 AC-5: the never-installed ERROR says that routes without a guard are public
    @Test
    public void OPS_AUTH_027_AC5_neverInstalledSaysUnguardedArePublic() {
        RecordingLogger log = new RecordingLogger();
        Auth auth = Auth.builder(AuthTestSupport.SECRET).logger(log).build();
        TestApp t = TestApp.create();
        t.app().get("/x", auth.require("posts:read"), (req, res) -> res.send("x"));
        t.start();
        t.send("GET", "/x");
        List<String> errors = RecordingLogger.matching(log.lines, "(?s)ERROR .*never installed.*");
        assertEquals(log.lines.toString(), 1, errors.size());
        assertTrue(errors.get(0), errors.get(0).contains("every route without a guard is public"));
    }

    // OPS-AUTH-027 AC-6: a blank role name in requireRole is refused
    @Test
    public void OPS_AUTH_027_AC6_blankRoleIsRefused() {
        Auth auth = Auth.builder(AuthTestSupport.SECRET).logger(new RecordingLogger()).build();
        try {
            auth.requireRole(" ");
            fail("accepted");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("blank"));
        }
    }

    // OPS-AUTH-027 AC-1: the 403 of an API-key request is insufficient_scope
    @Test
    public void OPS_AUTH_027_AC1_keyBeyondOwnerScopesIsInsufficientScope() {
        W8 f = fixture();
        f.start(b -> b.apiKeys(new AdminFixture.ListKeys()));
        String bot = f.auth.users().create("ci-bot", null, u -> u.toBuilder().serviceAccount(true).build()).getId();
        String key = f.auth.apiKeys().issue(bot, "ci", null, "posts:read").getPlainKey();
        ClientResponse r = f.get("/api/x", "Authorization", "ApiKey " + key);
        assertEquals(r.text(), 403, r.status());
        assertEquals("insufficient_scope", W8.code(r));
    }

    // OPS-AUTH-027 AC-2: an unknown field on POST /auth/admin/apikeys is refused too
    @Test
    public void OPS_AUTH_027_AC2_unknownAdminKeyFieldIsRefused() throws Exception {
        AdminFixture a = new AdminFixture();
        try {
            a.start(b -> b.apiKeys(new AdminFixture.ListKeys()));
            String root = a.user("root", "admin", "keyswriter");
            String u1 = a.user("u1", "reader");
            ClientResponse r = a.call("POST", "/auth/admin/apikeys", root, "{\"ownerUserId\":\"" + u1 + "\",\"name\":\"ci\","
                    + "\"scopes\":[\"posts:read\"],\"password\":\"pw-root\",\"owner\":\"x\"}");
            assertEquals(r.text(), 400, r.status());
            assertTrue(message(r), message(r).startsWith("unknown field owner; the fields are "));
        } finally {
            a.stop();
        }
    }

    // OPS-AUTH-027 AC-6: the refusal names the blank name it got
    @Test
    public void OPS_AUTH_027_AC6_blankRoleRefusalQuotesTheName() {
        Auth auth = Auth.builder(AuthTestSupport.SECRET).logger(new RecordingLogger()).build();
        try {
            auth.requireRole("reader", "  ");
            fail("accepted");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("blank role name \"  \""));
        }
    }
}
