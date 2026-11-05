package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.testing.TestApp;
import com.snoworca.naruserve.utils.auth.rbac.RolePolicy;
import com.snoworca.naruserve.utils.auth.rbac.RoleStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Test;

/** The mistakes of the second junior study fail with the fix (OPS-AUTH-008). */
public class JuniorRound2Test {

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

    private static List<String> scopeWarns(W8 f) {
        return RecordingLogger.matching(f.logger.lines, "(?s)WARN auth doctor WARN undeclared scope.*");
    }

    // OPS-AUTH-008 AC-1: a required scope no RolePolicy role grants gives one doctor WARN per scope; a granted scope,
    // by name or wildcard, and any scope under a custom RoleStore give none
    @Test
    public void OPS_AUTH_008_AC1_ungrantedRequiredScopesAreWarned() {
        W8 f = fixture();
        f.routes = w -> {
            w.app.get("/api/a", w.auth.require("post:write"), (req, res) -> res.send("a"));
            w.app.get("/api/b", w.auth.require("post:write"), (req, res) -> res.send("b"));
            w.app.get("/api/c", w.auth.require("posts:write"), (req, res) -> res.send("c"));
            w.app.get("/api/d", w.auth.require("docs:edit"), (req, res) -> res.send("d"));
        };
        f.start(b -> b.roles(RolePolicy.builder().role("reader", "posts:read").role("writer", "posts:write")
                .role("editor", "docs:*").build()));
        List<String> warns = scopeWarns(f);
        assertEquals(f.logger.lines.toString(), 1, warns.size());
        assertTrue(warns.get(0), warns.get(0).contains("'post:write'") && warns.get(0).contains("403")
                && warns.get(0).contains("typo"));

        W8 custom = fixture();
        RoleStore db = role -> Collections.<String>emptySet();
        custom.routes = w -> w.app.get("/api/a", w.auth.require("post:write"), (req, res) -> res.send("a"));
        custom.start(b -> b.roles(db));
        assertEquals(custom.logger.lines.toString(), 0, scopeWarns(custom).size());
    }

    // OPS-AUTH-008 AC-2: a second install says auth is already installed, not that it must come before routes
    @Test
    public void OPS_AUTH_008_AC2_secondInstallSaysAlreadyInstalled() {
        Auth auth = AuthTestSupport.builder().build();
        TestApp t = TestApp.create();
        auth.install(t.app());
        for (Runnable again : new Runnable[] {() -> auth.install(t.app()), () -> t.app().use(auth),
                () -> auth.install(TestApp.create().app())}) {
            try {
                again.run();
                fail("a second install was accepted");
            } catch (IllegalStateException e) {
                assertTrue(e.getMessage(), e.getMessage().contains("already installed"));
                assertFalse(e.getMessage(), e.getMessage().contains("must come before routes"));
            }
        }
    }

    // OPS-AUTH-008 AC-3: an Authorization header without the Bearer scheme gets a 401 that says to send Bearer; no
    // header keeps "authentication is required"
    @Test
    public void OPS_AUTH_008_AC3_nonBearerSchemeIsNamed() {
        W8 f = fixture();
        f.start(b -> b);
        for (String header : new String[] {"Token abc", "abc.def.ghi", "Basic YTpi"}) {
            ClientResponse r = f.get("/api/x", "Authorization", header);
            assertEquals(header + ": " + r.text(), 401, r.status());
            assertEquals("unauthenticated", W8.code(r));
            assertTrue(r.text(), message(r).contains("Authorization: Bearer"));
        }
        ClientResponse none = f.get("/api/x");
        assertEquals(401, none.status());
        assertEquals("authentication is required", message(none));
    }

    // OPS-AUTH-008 AC-4: auth.user(req) on a public path answers 500 and logs one ERROR line for the request
    @Test
    public void OPS_AUTH_008_AC4_wrongStateIsLoggedOnce() {
        W8 f = fixture();
        f.routes = w -> w.app.get("/pub", (req, res) -> res.send(w.auth.user(req).getId()));
        f.start(b -> b.permit("/pub"));
        int before = RecordingLogger.matching(f.logger.lines, "(?s)ERROR .*").size();
        ClientResponse r = f.get("/pub");
        assertEquals(r.text(), 500, r.status());
        assertEquals("INTERNAL_ERROR", W8.code(r));
        List<String> errors = RecordingLogger.matching(f.logger.lines, "(?s)ERROR .*");
        assertEquals(f.logger.lines.toString(), before + 1, errors.size());
        assertTrue(errors.toString(), errors.toString().contains("auth.userOrNull(req)"));
    }

    // OPS-AUTH-008 AC-5: admin create and update with an empty password answer 400 with the field message
    @Test
    public void OPS_AUTH_008_AC5_adminEmptyPasswordNamesTheField() throws Exception {
        AdminFixture a = new AdminFixture();
        admins.add(a);
        a.start();
        String root = a.user("root", "admin");
        ClientResponse create = a.call("POST", "/auth/admin/users", root,
                "{\"username\":\"bob\",\"password\":\"\",\"roles\":[\"reader\"]}");
        assertEquals(create.text(), 400, create.status());
        assertEquals("BAD_REQUEST", W8.code(create));
        assertEquals("password must be a non-empty JSON string", message(create));
        String bob = a.user("bob", "reader");
        ClientResponse patch = a.call("PATCH", "/auth/admin/users/" + bob, root, "{\"password\":\"\"}");
        assertEquals(patch.text(), 400, patch.status());
        assertEquals("password must be a non-empty JSON string", message(patch));
    }

    // OPS-AUTH-009 AC-8 (the documented runtime claim): default deny answers an unknown path with 401 before any 404
    @Test
    public void OPS_AUTH_009_AC8_unknownPathIs401BeforeAny404() {
        W8 f = fixture();
        f.start(b -> b);
        ClientResponse login = f.post("/login", W8.login("alice", W8.PW));
        assertEquals(login.text(), 401, login.status());
        assertEquals("unauthenticated", W8.code(login));
        ClientResponse getLogin = f.get("/auth/login");
        assertEquals(getLogin.text(), 401, getLogin.status());
        assertEquals("unauthenticated", W8.code(getLogin));
    }

    // OPS-AUTH-008 AC-6: the cors-after-auth FAIL prints no empty path
    @Test
    public void OPS_AUTH_008_AC6_corsFailHasNoEmptyPath() {
        TestApp t = TestApp.create();
        AuthTestSupport.builder().build().install(t.app());
        t.app().cors(c -> c.allowedOrigins("https://app.example.com"));
        try {
            t.start();
            fail("cors after auth started");
        } catch (RuntimeException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("found CORS registered after it"));
        }
    }
}
