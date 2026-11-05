package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.testing.TestApp;
import com.snoworca.naruserve.utils.auth.internal.Doctor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Test;

/** The code findings of the sixteenth junior study are answered with the fix (OPS-AUTH-028). */
public class JuniorRound16Test {

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

    private AdminFixture admin(java.util.function.UnaryOperator<Auth.Builder> config) throws Exception {
        AdminFixture a = new AdminFixture();
        admins.add(a);
        a.start(config);
        return a;
    }

    private static String message(ClientResponse r) {
        return String.valueOf(((Map<?, ?>) W8.object(r).get("error")).get("message"));
    }

    // OPS-AUTH-028 AC-1: the never-installed ERROR names app.use("/", auth) among the calls that do not install
    @Test
    public void OPS_AUTH_028_AC1_neverInstalledNamesSlashUse() {
        RecordingLogger log = new RecordingLogger();
        Auth auth = Auth.builder(AuthTestSupport.SECRET).logger(log).build();
        TestApp t = TestApp.create();
        t.app().use("", auth); // "/" installs since OPS-AUTH-033 AC-4 (this AC amended)
        t.app().get("/me", (req, res) -> res.send("me"));
        t.start();
        assertEquals(500, t.send("GET", "/me").status());
        List<String> errors = RecordingLogger.matching(log.lines, "(?s)ERROR .*never installed.*");
        assertEquals(log.lines.toString(), 1, errors.size());
        assertTrue(errors.get(0), errors.get(0).contains("app.use(\"\", auth)"));
    }

    // OPS-AUTH-028 AC-2: a logout body field auth does not read is refused
    @Test
    public void OPS_AUTH_028_AC2_unknownLogoutFieldIsRefused() throws Exception {
        W8 f = fixture();
        f.start(b -> b);
        f.user("bob", W8.PW, "reader");
        String rt = String.valueOf(f.loginBody("bob", W8.PW).get("refreshToken"));
        ClientResponse r = f.post("/auth/logout", "{\"refresh_token\":\"" + rt + "\"}");
        assertEquals(r.text(), 400, r.status());
        assertEquals("unknown field refresh_token; logout reads only refreshToken", message(r));
    }

    // OPS-AUTH-028 AC-3: an exact permit that matches no route while a slash or case variant does is a doctor WARN
    @Test
    public void OPS_AUTH_028_AC3_permitNearMissIsWarned() {
        Auth auth = Auth.builder(AuthTestSupport.SECRET).logger(new RecordingLogger()).permit("/health/").build();
        TestApp t = TestApp.create();
        auth.install(t.app());
        t.app().get("/health", (req, res) -> res.send("ok"));
        List<String> warns = new ArrayList<>();
        for (Doctor.Finding x : auth.doctor(t.app()).findings()) {
            if (x.getLevel() == Doctor.Level.WARN && x.getItem().equals("permit path")) {
                warns.add(x.getMessage());
            }
        }
        assertEquals(warns.toString(), 1, warns.size());
        assertTrue(warns.get(0), warns.get(0).contains(".permit(\"/health\")"));
    }

    // OPS-AUTH-028 AC-4: a malformed API key names the key format
    @Test
    public void OPS_AUTH_028_AC4_malformedKeyNamesTheFormat() {
        W8 f = fixture();
        f.start(b -> b.apiKeys(new AdminFixture.ListKeys()));
        String m = message(f.get("/api/x", "Authorization", "ApiKey nk_short"));
        assertTrue(m, m.contains("nk_<8-character prefix>_<32-character secret>"));
    }

    // OPS-AUTH-028 AC-5: a wrong confirmation password on key issuance says whose password it must be
    @Test
    public void OPS_AUTH_028_AC5_keyPasswordNamesTheCaller() throws Exception {
        AdminFixture a = admin(b -> b.apiKeys(new AdminFixture.ListKeys()));
        String root = a.user("root", "admin", "keyswriter");
        String u1 = a.user("u1", "reader");
        ClientResponse r = a.call("POST", "/auth/admin/apikeys", root, "{\"ownerUserId\":\"" + u1 + "\",\"name\":\"ci\","
                + "\"scopes\":[\"posts:read\"],\"password\":\"pw-u1\"}");
        assertEquals(r.text(), 401, r.status());
        assertTrue(message(r), message(r).startsWith("the password is wrong") && message(r).contains("caller"));
    }

    // OPS-AUTH-028 AC-6: an A4 rename to a taken name says the username is taken
    @Test
    public void OPS_AUTH_028_AC6_renameConflictIsNamed() throws Exception {
        AdminFixture a = admin(b -> b);
        String root = a.user("root", "admin");
        String bob = a.user("bob");
        a.user("carol");
        ClientResponse r = a.call("PATCH", "/auth/admin/users/" + bob, root, "{\"username\":\"carol\"}");
        assertEquals(r.text(), 409, r.status());
        assertEquals("duplicate_user", W8.code(r));
        assertEquals("the username is taken", message(r));
    }

    // OPS-AUTH-028 AC-1: the ERROR names router.use(auth) and route middleware as well
    @Test
    public void OPS_AUTH_028_AC1_neverInstalledNamesRouterAndRouteMiddleware() {
        RecordingLogger log = new RecordingLogger();
        Auth auth = Auth.builder(AuthTestSupport.SECRET).logger(log).build();
        TestApp t = TestApp.create();
        t.app().use("", auth);
        t.app().get("/me", (req, res) -> res.send("me"));
        t.start();
        t.send("GET", "/me");
        List<String> errors = RecordingLogger.matching(log.lines, "(?s)ERROR .*never installed.*");
        assertEquals(log.lines.toString(), 1, errors.size());
        assertTrue(errors.get(0), errors.get(0).contains("router.use(auth)") && errors.get(0).contains("route middleware"));
    }

    // OPS-AUTH-028 AC-3: a letter-case variant is warned like a slash variant; an exact match is not
    @Test
    public void OPS_AUTH_028_AC3_permitCaseNearMissIsWarned() {
        for (boolean nearMiss : new boolean[] {true, false}) {
            Auth auth = Auth.builder(AuthTestSupport.SECRET).logger(new RecordingLogger())
                    .permit(nearMiss ? "/Health" : "/health").build();
            TestApp t = TestApp.create();
            auth.install(t.app());
            t.app().get("/health", (req, res) -> res.send("ok"));
            List<String> warns = new ArrayList<>();
            for (Doctor.Finding x : auth.doctor(t.app()).findings()) {
                if (x.getLevel() == Doctor.Level.WARN && x.getItem().equals("permit path")) {
                    warns.add(x.getMessage());
                }
            }
            assertEquals(warns.toString(), nearMiss ? 1 : 0, warns.size());
            if (nearMiss) {
                assertTrue(warns.get(0), warns.get(0).contains(".permit(\"/health\")"));
            }
        }
    }

    // OPS-AUTH-028 AC-4: the malformed key answer is 401 invalid_api_key
    @Test
    public void OPS_AUTH_028_AC4_malformedKeyIs401InvalidApiKey() {
        W8 f = fixture();
        f.start(b -> b.apiKeys(new AdminFixture.ListKeys()));
        ClientResponse r = f.get("/api/x", "Authorization", "ApiKey nk_short");
        assertEquals(r.text(), 401, r.status());
        assertEquals("invalid_api_key", W8.code(r));
    }

    // OPS-AUTH-028 AC-5: the code is invalid_credentials on both issuance routes, and the personal route names the caller
    @Test
    public void OPS_AUTH_028_AC5_keyPasswordRefusalIsInvalidCredentials() throws Exception {
        AdminFixture a = admin(b -> b.apiKeys(new AdminFixture.ListKeys()));
        String root = a.user("root", "admin", "keyswriter");
        String u1 = a.user("u1", "reader");
        ClientResponse admin = a.call("POST", "/auth/admin/apikeys", root, "{\"ownerUserId\":\"" + u1 + "\",\"name\":\"ci\","
                + "\"scopes\":[\"posts:read\"],\"password\":\"pw-u1\"}");
        assertEquals(admin.text(), 401, admin.status());
        assertEquals("invalid_credentials", W8.code(admin));
        ClientResponse own = a.call("POST", "/auth/me/apikeys", u1, "{\"name\":\"ci\",\"scopes\":[\"posts:read\"],"
                + "\"password\":\"not-my-password\"}");
        assertEquals(own.text(), 401, own.status());
        assertEquals("invalid_credentials", W8.code(own));
        assertTrue(message(own), message(own).startsWith("the password is wrong") && message(own).contains("caller"));
    }

    // OPS-AUTH-028 AC-6: the rename conflict keeps the top-level `applied` list
    @Test
    public void OPS_AUTH_028_AC6_renameConflictKeepsApplied() throws Exception {
        AdminFixture a = admin(b -> b);
        String root = a.user("root", "admin");
        String bob = a.user("bob");
        a.user("carol");
        ClientResponse r = a.call("PATCH", "/auth/admin/users/" + bob, root, "{\"username\":\"carol\"}");
        assertEquals(r.text(), 409, r.status());
        assertTrue(r.text(), W8.object(r).get("applied") instanceof List);
    }
}
