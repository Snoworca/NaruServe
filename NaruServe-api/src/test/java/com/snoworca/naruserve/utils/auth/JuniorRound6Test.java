package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Test;

/** The mistakes of the sixth junior study are answered with the fix (OPS-AUTH-016). */
public class JuniorRound6Test {

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

    // OPS-AUTH-016 AC-1: the 403 of a scope or role guard names what the route requires; other guards keep the message
    @Test
    public void OPS_AUTH_016_AC1_a403NamesWhatTheRouteRequires() throws Exception {
        W8 f = fixture();
        f.user("bob", W8.PW, "reader");
        f.routes = w -> {
            w.app.get("/api/w", w.auth.require("posts:write", "posts:read"), (req, res) -> res.send("w"));
            w.app.get("/api/r", w.auth.requireRole("writer"), (req, res) -> res.send("r"));
        };
        f.start(b -> b);
        String token = String.valueOf(f.loginBody("bob", W8.PW).get("accessToken"));
        ClientResponse scope = f.get("/api/w", "Authorization", "Bearer " + token);
        assertEquals(scope.text(), 403, scope.status());
        assertEquals("insufficient_scope", W8.code(scope));
        assertEquals("the principal lacks a required scope or role; this route requires the scope posts:write "
                + "posts:read", message(scope));
        ClientResponse role = f.get("/api/r", "Authorization", "Bearer " + token);
        assertEquals(role.text(), 403, role.status());
        assertEquals("the principal lacks a required scope or role; this route requires one of the roles writer",
                message(role));
    }

    // OPS-AUTH-016 AC-2: the password endpoint names a missing currentPassword
    @Test
    public void OPS_AUTH_016_AC2_passwordEndpointNamesTheField() throws Exception {
        W8 f = fixture();
        f.user("bob", W8.PW, "reader");
        f.start(b -> b);
        String token = String.valueOf(f.loginBody("bob", W8.PW).get("accessToken"));
        ClientResponse r = f.post("/auth/password", "{\"oldPassword\":\"x\",\"newPassword\":\"another-pass-1\"}",
                "Authorization", "Bearer " + token);
        assertEquals(r.text(), 400, r.status());
        assertEquals("currentPassword must be a non-empty JSON string", message(r));
    }

    // OPS-AUTH-016 AC-3: a bodiless POST to an auth endpoint that reads a body gets the auth 415 message
    @Test
    public void OPS_AUTH_016_AC3_bodilessLoginGetsTheAuth415() {
        W8 f = fixture();
        f.start(b -> b);
        ClientResponse r = f.post("/auth/login", null);
        assertEquals(r.text(), 415, r.status());
        assertEquals("auth endpoints accept only application/json bodies", message(r));
    }
}
