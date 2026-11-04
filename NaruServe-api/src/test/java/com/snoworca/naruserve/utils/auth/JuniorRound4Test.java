package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.Registration;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.testing.TestApp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Test;

/** The mistakes of the fourth junior study are answered as JSON with the fix (OPS-AUTH-012). */
public class JuniorRound4Test {

    private final List<W8> fixtures = new ArrayList<>();
    private final List<AdminFixture> admins = new ArrayList<>();

    @After
    public void stop() {
        for (W8 f : fixtures) {
            f.stop();
        }
        AdminFixture.stopAll(admins);
    }

    private W8 fixture() {
        W8 f = new W8();
        fixtures.add(f);
        return f;
    }

    private static String message(ClientResponse r) {
        return String.valueOf(((Map<?, ?>) W8.object(r).get("error")).get("message"));
    }

    // OPS-AUTH-012 AC-1: without an auth error layer after the routes, auth exceptions of app handlers are auth JSON;
    // with auth.errors() last, start appends nothing
    @Test
    public void OPS_AUTH_012_AC1_missingErrorLayerStillAnswersJson() {
        Auth auth = AuthTestSupport.builder(new AuthTestSupport.TestCredentials(), new RecordingLogger())
                .permit("/boom").permit("/state").build();
        TestApp t = TestApp.create();
        auth.install(t.app());
        t.app().get("/boom", (req, res) -> {
            throw new AuthRejection(422, "domain_not_allowed", "no");
        });
        t.app().get("/state", (req, res) -> res.send(auth.user(req).getId()));
        t.start();
        ClientResponse boom = t.send("GET", "/boom");
        assertEquals(boom.text(), 422, boom.status());
        assertEquals("domain_not_allowed", W8.code(boom));
        assertFalse(boom.text(), boom.text().contains("<html") || boom.text().contains("at com."));
        ClientResponse state = t.send("GET", "/state");
        assertEquals(state.text(), 500, state.status());
        assertEquals("INTERNAL_ERROR", W8.code(state));
        assertFalse(state.text(), state.text().contains("<html"));

        Auth placed = AuthTestSupport.builder(new AuthTestSupport.TestCredentials(), new RecordingLogger()).build();
        TestApp p = TestApp.create();
        placed.install(p.app());
        p.app().get("/x", (req, res) -> res.send("x"));
        p.app().onError(placed.errors());
        int before = p.app().registrations().size();
        p.start();
        List<Registration> after = p.app().registrations();
        for (Registration r : after.subList(before, after.size())) { // only the OPS-AUTH-033 layers are appended
            assertFalse(after.toString(), r.getTarget() == placed.errorMiddleware());
        }
    }

    // OPS-AUTH-012 AC-2: a refresh without a refresh token says where the token goes
    @Test
    public void OPS_AUTH_012_AC2_refreshWithoutTokenNamesTheField() {
        W8 f = fixture();
        f.start(b -> b);
        for (String body : new String[] {"{}", "{\"token\":\"abc\"}", null}) {
            ClientResponse r = f.post("/auth/refresh", body);
            assertEquals(body + ": " + r.text(), 401, r.status());
            assertEquals("invalid_refresh_token", W8.code(r));
            assertTrue(r.text(), message(r).contains("\"refreshToken\""));
        }
        W8 cookie = fixture();
        cookie.start(b -> b.transport(TokenTransport.COOKIE));
        ClientResponse c = cookie.post("/auth/refresh", null);
        assertEquals(c.text(), 401, c.status());
        assertTrue(c.text(), message(c).contains("naru_rt"));
        ClientResponse bodyInCookieMode = cookie.post("/auth/refresh", "{\"refreshToken\":\"abc\"}");
        assertEquals(bodyInCookieMode.text(), 400, bodyInCookieMode.status());
        assertTrue(bodyInCookieMode.text(), message(bodyInCookieMode).contains("COOKIE mode"));
    }

    // OPS-AUTH-012 AC-3: a body that is not JSON, or not an object, is named in the 400, on account and admin routes
    @Test
    public void OPS_AUTH_012_AC3_badJsonBodyIsNamed() throws Exception {
        W8 f = fixture();
        f.start(b -> b);
        ClientResponse broken = f.post("/auth/login", "{\"username\":");
        assertEquals(broken.text(), 400, broken.status());
        assertEquals("BAD_REQUEST", W8.code(broken));
        assertEquals("the request body must be a JSON object", message(broken)); // OPS-AUTH-014 AC-1
        ClientResponse array = f.post("/auth/login", "[1]");
        assertEquals(array.text(), 400, array.status());
        assertEquals("the request body must be a JSON object", message(array));

        AdminFixture a = new AdminFixture();
        admins.add(a);
        a.start();
        String root = a.user("root", "admin");
        ClientResponse admin = a.call("POST", "/auth/admin/users", root, "{\"username\":");
        assertEquals(admin.text(), 400, admin.status());
        assertEquals("the request body must be a JSON object", message(admin));
    }

    // OPS-AUTH-012 AC-4: a disabled sign-up answers JSON 404 naming .register(true), unless the app serves the path
    @Test
    public void OPS_AUTH_012_AC4_disabledRegisterNamesTheOption() {
        W8 off = fixture();
        off.start(b -> b);
        ClientResponse r = off.post("/auth/register", W8.login("zed", W8.PW));
        assertEquals(r.text(), 404, r.status());
        assertEquals("NOT_FOUND", W8.code(r));
        assertTrue(r.text(), message(r).contains(".register(true)"));

        W8 own = fixture();
        own.routes = w -> w.app.post("/auth/register", (req, res) -> res.status(202).send("own sign-up"));
        own.start(b -> b);
        ClientResponse mine = own.post("/auth/register", W8.login("zed", W8.PW));
        assertEquals(mine.text(), 202, mine.status());
        assertEquals("own sign-up", mine.text());

        W8 on = fixture();
        on.start(b -> b.register(true));
        ClientResponse created = on.post("/auth/register", W8.login("zed", W8.PW));
        assertEquals(created.text(), 201, created.status());
    }
}
