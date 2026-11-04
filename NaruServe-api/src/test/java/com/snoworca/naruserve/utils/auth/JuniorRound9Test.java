package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.testing.TestApp;
import com.snoworca.naruserve.utils.auth.internal.Doctor;
import com.snoworca.naruserve.utils.auth.rbac.RolePolicy;
import com.snoworca.naruserve.utils.auth.user.FileUserStore;
import com.snoworca.naruserve.utils.auth.user.ForwardingUserStore;
import com.snoworca.naruserve.utils.auth.user.UserPage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.After;
import org.junit.Test;

/** The code findings of the ninth junior study are answered with the fix (OPS-AUTH-021). */
public class JuniorRound9Test {

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

    private AdminFixture admin(AdminFixture a) throws Exception {
        admins.add(a);
        a.start();
        return a;
    }

    private static String message(ClientResponse r) {
        return String.valueOf(((Map<?, ?>) W8.object(r).get("error")).get("message"));
    }

    private static String token(W8 f) throws Exception {
        f.user("bob", W8.PW, "reader");
        return String.valueOf(f.loginBody("bob", W8.PW).get("accessToken"));
    }

    // OPS-AUTH-021 AC-1: an `all` value other than true or false, or an `all` body field, is 400 on logout
    @Test
    public void OPS_AUTH_021_AC1_logoutAllIsChecked() throws Exception {
        W8 f = fixture();
        f.start(b -> b);
        String t = token(f);
        for (String q : new String[] {"?all=1", "?all=yes", "?all"}) {
            ClientResponse r = f.post("/auth/logout" + q, null, "Authorization", "Bearer " + t);
            assertEquals(q + " " + r.text(), 400, r.status());
            assertTrue(r.text(), message(r).startsWith("all must be true or false"));
        }
        ClientResponse body = f.post("/auth/logout", "{\"all\":true}", "Authorization", "Bearer " + t);
        assertEquals(body.text(), 400, body.status());
        assertTrue(body.text(), message(body).contains("all is a query parameter: POST /auth/logout?all=true"));
        assertEquals(200, f.get("/api/x", "Authorization", "Bearer " + t).status()); // nothing was revoked
    }

    // OPS-AUTH-021 AC-2: an unknown A2 or A4 body field is 400 naming it and the known fields
    @Test
    public void OPS_AUTH_021_AC2_unknownAdminFieldIsRefused() throws Exception {
        AdminFixture a = admin(new AdminFixture());
        String root = a.user("root", "admin");
        ClientResponse c = a.call("POST", "/auth/admin/users", root,
                "{\"username\":\"bob\",\"password\":\"long-password-1\",\"role\":\"editor\"}");
        assertEquals(c.text(), 400, c.status());
        assertTrue(c.text(), message(c).startsWith("unknown field role; the fields are ") && message(c).contains("roles"));
        String bob = a.user("bob2");
        ClientResponse p = a.call("PATCH", "/auth/admin/users/" + bob, root, "{\"nickname\":\"v\"}");
        assertEquals(p.text(), 400, p.status());
        assertTrue(p.text(), message(p).startsWith("unknown field nickname; the fields are "));
    }

    // OPS-AUTH-021 AC-3: a route pattern in .permit or .permitPrefix is refused at build
    @Test
    public void OPS_AUTH_021_AC3_permitPatternIsRefused() {
        for (String p : new String[] {"/users/:id", "/public/*"}) {
            try {
                Auth.builder(AuthTestSupport.SECRET).permit(p).build();
                fail(p + " accepted");
            } catch (AuthConfigException e) {
                assertTrue(e.getMessage(), e.getMessage().contains("not a route pattern") && e.getMessage().contains(".permitPrefix"));
            }
        }
    }

    // OPS-AUTH-021 AC-4: a 401 for a login-like path that no route serves names the auth endpoint
    @Test
    public void OPS_AUTH_021_AC4_loginLikePathNamesTheEndpoint() {
        W8 f = fixture();
        f.start(b -> b);
        ClientResponse login = f.post("/login", W8.login("bob", W8.PW));
        assertEquals(401, login.status());
        assertEquals("authentication is required; auth's login endpoint is POST /auth/login", message(login));
        assertEquals("authentication is required; auth's register endpoint is POST /auth/register, and sign-up is off "
                + "(build with .register(true))", message(f.post("/auth/signup", "{}"))); // OPS-AUTH-023 AC-6 suffix
        assertEquals("authentication is required", message(f.post("/api/y", "{}")));
    }

    // OPS-AUTH-021 AC-5: install after listen names the order
    @Test
    public void OPS_AUTH_021_AC5_installAfterListenNamesTheOrder() {
        TestApp t = TestApp.create();
        t.start();
        try {
            Auth.builder(AuthTestSupport.SECRET).build().install(t.app());
            fail("installed on a running app");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().startsWith("auth.install(app) must run before app.listen("));
        }
    }

    // OPS-AUTH-021 AC-6: an admin 404 names the missing user or group
    @Test
    public void OPS_AUTH_021_AC6_admin404NamesTheMissingThing() throws Exception {
        AdminFixture a = admin(new AdminFixture());
        String root = a.user("root", "admin");
        ClientResponse u = a.call("GET", "/auth/admin/users/alice", root, null);
        assertEquals(404, u.status());
        assertEquals("no user with id alice; the admin endpoints take the user id, not the username", message(u));
        ClientResponse g = a.call("GET", "/auth/admin/groups/nope", root, null);
        assertEquals(404, g.status());
        assertEquals("no group nope", message(g));
        assertEquals(201, a.call("POST", "/auth/admin/groups", root, "{\"id\":\"eng\"}").status());
        assertEquals("no group nope", message(a.call("PUT", "/auth/admin/groups/eng/parents/nope", root, null)));
    }

    // OPS-AUTH-021 AC-7: the grant-rule 403 names the scope
    @Test
    public void OPS_AUTH_021_AC7_grantRefusalNamesTheScope() throws Exception {
        AdminFixture a = admin(new AdminFixture());
        String ua = a.user("ua", "userswriter");
        ClientResponse r = a.call("POST", "/auth/admin/users", ua,
                "{\"username\":\"boss\",\"password\":\"long-password-1\",\"roles\":[\"editor\"]}");
        assertEquals(r.text(), 403, r.status());
        assertEquals("the actor does not hold a scope it grants: posts:write", message(r));
    }

    // OPS-AUTH-021 AC-8: an expired, a malformed and an API-key Bearer value get separate messages
    @Test
    public void OPS_AUTH_021_AC8_tokenMessagesAreSeparate() throws Exception {
        W8 f = fixture();
        f.start(b -> b);
        String id = f.user("ann", W8.PW, "reader");
        assertTrue(message(f.get("/api/x", "Authorization", "Bearer " + f.expiredAccess(id))).startsWith(
                "the token has expired; renew it with the refresh endpoint"));
        String bad = message(f.get("/api/x", "Authorization", "Bearer abc.def.ghi"));
        assertTrue(bad, bad.startsWith("the token is not valid") && !bad.contains("expired"));
        String key = message(f.get("/api/x", "Authorization", "Bearer nk_aBcd1234_0123456789abcdef0123456789abcdef"));
        assertTrue(key, key.contains("API key") && key.contains("Authorization: ApiKey"));
    }

    // OPS-AUTH-021 AC-9: a credential in a place auth does not read is named in the 401
    @Test
    public void OPS_AUTH_021_AC9_credentialInTheWrongPlaceIsNamed() {
        W8 f = fixture();
        f.start(b -> b);
        assertTrue(message(f.get("/api/x?access_token=abc")).contains("?access_token"));
        assertTrue(message(f.get("/api/x", "X-API-Key", "nk_x")).contains("X-API-Key"));
        String cookie = message(f.get("/api/x", "Cookie", "naru_at=abc"));
        assertTrue(cookie, cookie.contains("naru_at") && cookie.contains("TokenTransport.HEADER"));
    }

    // OPS-AUTH-021 AC-10: a missing directory of the file stores is one FAIL saying so
    @Test
    public void OPS_AUTH_021_AC10_missingDirectoryIsOneFail() throws Exception {
        RecordingLogger log = new RecordingLogger();
        File dir = new File("/nonexistent-naru-junior-" + System.nanoTime());
        Auth auth = AuthTestSupport.builder(new AuthTestSupport.TestCredentials(), log)
                .users(new FileUserStore(new File(dir, "users.json"), AuthTestSupport.FILE_KEY)).build();
        TestApp t = TestApp.create();
        auth.install(t.app());
        List<String> fails = new ArrayList<>();
        for (Doctor.Finding x : auth.doctor(t.app()).findings()) {
            if (x.getItem().equals("file state")) {
                fails.add(x.getLevel() + " " + x.getMessage());
            }
        }
        assertEquals(fails.toString(), 1, fails.size());
        assertTrue(fails.get(0), fails.get(0).startsWith("FAIL ") && fails.get(0).contains("does not exist; create it"));
    }

    // OPS-AUTH-021 AC-11: A1 on a UserStore without list() is 501 naming list()
    @Test
    public void OPS_AUTH_021_AC11_listWithoutStoreSupportIs501() throws Exception {
        AdminFixture a = admin(new AdminFixture(base -> new ForwardingUserStore(base) {
            @Override
            public UserPage list(int offset, int limit, @org.jspecify.annotations.Nullable String search) {
                throw new UnsupportedOperationException("NoList does not implement UserStore.list");
            }
        }));
        String root = a.user("root", "admin");
        ClientResponse r = a.call("GET", "/auth/admin/users", root, null);
        assertEquals(r.text(), 501, r.status());
        assertEquals("NOT_IMPLEMENTED", W8.code(r));
        assertTrue(r.text(), message(r).contains("does not implement UserStore.list"));
    }

    // OPS-AUTH-021 AC-12: a plain naru.auth.secret is one violation, not also a null secret
    @Test
    public void OPS_AUTH_021_AC12_plainConfigSecretIsOneViolation() {
        Properties p = new Properties();
        p.setProperty("naru.auth.secret", "my-plain-secret");
        try {
            Auth.builder((String) null).config(AuthConfig.from(p)).build();
            fail("built");
        } catch (AuthConfigException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("must be a reference"));
            assertFalse(e.getMessage(), e.getMessage().contains("is null or empty"));
        }
    }

    // OPS-AUTH-021 AC-13: the first DEV login throttle 429 logs a WARN naming .loginFailureLimits
    @Test
    public void OPS_AUTH_021_AC13_devThrottleIsWarned() throws Exception {
        W8 f = fixture();
        f.start(b -> b.mode(AuthMode.DEV));
        f.user("bob", W8.PW, "reader");
        int last = 0;
        for (int i = 0; i < 8; i++) {
            last = f.post("/auth/login", W8.login("bob", "wrong-password-x")).status();
        }
        assertEquals(429, last);
        List<String> warns = RecordingLogger.matching(f.logger.lines, "(?s)WARN .*\\.loginFailureLimits\\(.*");
        assertEquals(f.logger.lines.toString(), 1, warns.size());
    }

    // OPS-AUTH-021 AC-14: calling .roles(...) twice logs a WARN at build
    @Test
    public void OPS_AUTH_021_AC14_secondRolesCallIsWarned() {
        RecordingLogger log = new RecordingLogger();
        Auth.builder(AuthTestSupport.SECRET).logger(log).roles(RolePolicy.builder().role("editor", "posts:write").build())
                .roles(RolePolicy.builder().role("viewer", "posts:read").build()).build();
        List<String> warns = RecordingLogger.matching(log.lines, "(?s)WARN .*\\.roles\\(.*replaces.*");
        assertEquals(log.lines.toString(), 1, warns.size());
    }
}
