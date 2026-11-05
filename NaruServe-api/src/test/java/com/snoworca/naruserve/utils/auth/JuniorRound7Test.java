package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.testing.TestApp;
import com.snoworca.naruserve.utils.auth.user.UserMutator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Test;

/** The mistakes of the seventh junior study are answered with the fix (OPS-AUTH-018). */
public class JuniorRound7Test {

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

    private AdminFixture admin() throws Exception {
        AdminFixture a = new AdminFixture();
        admins.add(a);
        a.start();
        return a;
    }

    private static String message(ClientResponse r) {
        return String.valueOf(((Map<?, ?>) W8.object(r).get("error")).get("message"));
    }

    // OPS-AUTH-018 AC-1: a policy refusal of auth.users() from a handler keeps its message; other refusals do not
    @Test
    public void OPS_AUTH_018_AC1_policyRefusalFromAHandlerIsNamed() {
        W8 f = fixture();
        f.routes = w -> {
            w.app.get("/mk", (req, res) -> res.send(w.auth.users().create("zed", "short".toCharArray()).getId()));
            UserMutator none = null;
            w.app.get("/mk2", (req, res) -> res.send(w.auth.users().create("zed2", null, none).getId()));
        };
        f.start(b -> b.permit("/mk").permit("/mk2"));
        ClientResponse r = f.get("/mk");
        assertEquals(r.text(), 400, r.status());
        assertEquals("BAD_REQUEST", W8.code(r));
        assertEquals("the password must have at least 8 characters", message(r));
        ClientResponse other = f.get("/mk2");
        assertEquals(other.text(), 400, other.status());
        assertEquals("Bad Request", message(other));
    }

    // OPS-AUTH-018 AC-2: PATCH /auth/me with a disallowed attribute names it and .allowedAttributes
    @Test
    public void OPS_AUTH_018_AC2_disallowedAttributeIsNamed() throws Exception {
        W8 f = fixture();
        f.user("bob", W8.PW, "reader");
        f.start(b -> b);
        String token = String.valueOf(f.loginBody("bob", W8.PW).get("accessToken"));
        ClientResponse r = f.adapter.send(ClientRequest.json("PATCH", "/auth/me", "{\"attributes\":{\"nick\":\"B\"}}")
                .header("Authorization", "Bearer " + token));
        assertEquals(r.text(), 400, r.status());
        assertTrue(r.text(), message(r).contains("nick") && message(r).contains(".allowedAttributes(\"nick\")"));
    }

    // OPS-AUTH-018 AC-3: the admin request-shape refusals name the field or rule
    @Test
    public void OPS_AUTH_018_AC3_adminShapeRefusalsAreNamed() throws Exception {
        AdminFixture a = admin();
        String root = a.user("root", "admin");
        ClientResponse roles = a.call("POST", "/auth/admin/users", root,
                "{\"username\":\"bob\",\"password\":\"bob-password-1\",\"roles\":\"editor\"}");
        assertEquals(roles.text(), 400, roles.status());
        assertEquals("roles must be an array of strings", message(roles));
        ClientResponse group = a.call("POST", "/auth/admin/groups", root, "{\"id\":\"Bad Id\"}");
        assertEquals(group.text(), 400, group.status());
        assertTrue(group.text(), message(group).contains("[a-z0-9._-]{1,64}"));
    }

    // OPS-AUTH-018 AC-4: an admin 403 names the scope the endpoint requires
    @Test
    public void OPS_AUTH_018_AC4_admin403NamesTheScope() throws Exception {
        AdminFixture a = admin();
        String bob = a.user("bob", "reader");
        ClientResponse r = a.call("GET", "/auth/admin/users", bob, null);
        assertEquals(r.text(), 403, r.status());
        assertEquals("insufficient_scope", W8.code(r));
        assertTrue(r.text(), message(r).endsWith("; this route requires the scope auth-users:read"));
    }

    // OPS-AUTH-018 AC-5: the after-start WARN names app.use(auth.errorMiddleware()), not app.onError
    @Test
    public void OPS_AUTH_018_AC5_afterStartWarnNamesErrorMiddleware() {
        RecordingLogger log = new RecordingLogger();
        Auth auth = AuthTestSupport.builder(new AuthTestSupport.TestCredentials(), log).build();
        TestApp t = TestApp.create();
        auth.install(t.app());
        t.app().get("/x", (req, res) -> res.send("x"));
        t.app().onError(auth.errors());
        t.start();
        t.app().get("/later", (req, res) -> res.send("later"));
        t.send("GET", "/x", AuthTestSupport.HEADER, "1:0");
        List<String> warns = RecordingLogger.matching(log.lines, "(?s)WARN auth: .*after the last auth error layer.*");
        assertEquals(log.lines.toString(), 1, warns.size());
        assertTrue(warns.get(0), warns.get(0).contains("app.use(auth.errorMiddleware())"));
        assertTrue(warns.get(0), warns.get(0).contains("Core refuses app.onError after start"));
        assertFalse(warns.get(0), warns.get(0).contains("add app.onError(auth.errors())"));
    }

    // OPS-AUTH-018 AC-6: a missing PROD secret names the builder argument, with JWT_SECRET as the example
    @Test
    public void OPS_AUTH_018_AC6_missingSecretNamesTheBuilderArgument() {
        try {
            Auth.builder((String) null).build();
            fail("PROD built without a secret");
        } catch (AuthConfigException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("the secret passed to Auth.builder(...) is null or empty"));
            assertTrue(e.getMessage(), e.getMessage().contains("JWT_SECRET"));
        }
    }

    // OPS-AUTH-018 AC-7: an admin assignment of an undeclared role logs one WARN per role name
    @Test
    public void OPS_AUTH_018_AC7_undeclaredAdminRoleIsWarned() throws Exception {
        AdminFixture a = admin();
        String root = a.user("root", "admin");
        for (String name : new String[] {"bob", "carol"}) {
            ClientResponse r = a.call("POST", "/auth/admin/users", root,
                    "{\"username\":\"" + name + "\",\"password\":\"long-password-1\",\"roles\":[\"Editr\"]}");
            assertEquals(r.text(), 201, r.status());
        }
        List<String> warns = RecordingLogger.matching(a.logger.lines, "(?s)WARN .*'Editr'.*");
        assertEquals(a.logger.lines.toString(), 1, warns.size());
        assertTrue(warns.get(0), warns.get(0).contains("RolePolicy.builder().role(\"Editr\""));
    }

    // OPS-AUTH-018 AC-1: a username refusal of auth.users() from a handler keeps its message too
    @Test
    public void OPS_AUTH_018_AC1_usernameRefusalFromAHandlerIsNamed() {
        W8 f = fixture();
        f.routes = w -> w.app.get("/mk", (req, res) ->
                res.send(w.auth.users().create("", "long-password-1".toCharArray()).getId()));
        f.start(b -> b.permit("/mk"));
        ClientResponse r = f.get("/mk");
        assertEquals(r.text(), 400, r.status());
        assertEquals("BAD_REQUEST", W8.code(r));
        assertTrue(r.text(), message(r).contains("username"));
    }

    // OPS-AUTH-018 AC-3: an invalid username and bad attributes answer with their message, on both routers
    @Test
    public void OPS_AUTH_018_AC3_usernameAndAttributeRefusalsAreNamed() throws Exception {
        AdminFixture a = admin();
        String root = a.user("root", "admin");
        ClientResponse name = a.call("POST", "/auth/admin/users", root,
                "{\"username\":\"\",\"password\":\"long-password-1\"}");
        assertEquals(name.text(), 400, name.status());
        assertTrue(name.text(), message(name).startsWith("invalid username: "));
        ClientResponse attrs = a.call("POST", "/auth/admin/users", root,
                "{\"username\":\"dan\",\"password\":\"long-password-1\",\"attributes\":\"x\"}");
        assertEquals(attrs.text(), 400, attrs.status());
        assertTrue(attrs.text(), message(attrs).contains("attributes"));

        W8 f = fixture();
        f.user("bob", W8.PW, "reader");
        f.start(b -> b.register(true));
        ClientResponse register = f.post("/auth/register", W8.login("", "long-password-1"));
        assertEquals(register.text(), 400, register.status());
        assertTrue(register.text(), message(register).startsWith("invalid username: "));
        String token = String.valueOf(f.loginBody("bob", W8.PW).get("accessToken"));
        ClientResponse me = f.adapter.send(ClientRequest.json("PATCH", "/auth/me", "{\"attributes\":\"x\"}")
                .header("Authorization", "Bearer " + token));
        assertEquals(me.text(), 400, me.status());
        assertEquals("attributes must be a JSON object of strings", message(me));
    }

    // OPS-AUTH-018 AC-7: an admin update assigning an undeclared role logs one WARN per role name too
    @Test
    public void OPS_AUTH_018_AC7_undeclaredRoleOnAdminUpdateIsWarned() throws Exception {
        AdminFixture a = admin();
        String root = a.user("root", "admin");
        for (String name : new String[] {"bob", "carol"}) {
            String id = a.user(name, "reader");
            ClientResponse r = a.call("PATCH", "/auth/admin/users/" + id, root, "{\"roles\":[\"Editr2\"]}");
            assertEquals(r.text(), 200, r.status());
        }
        List<String> warns = RecordingLogger.matching(a.logger.lines, "(?s)WARN .*'Editr2'.*");
        assertEquals(a.logger.lines.toString(), 1, warns.size());
        assertTrue(warns.get(0), warns.get(0).contains("RolePolicy.builder().role(\"Editr2\""));
    }
}
