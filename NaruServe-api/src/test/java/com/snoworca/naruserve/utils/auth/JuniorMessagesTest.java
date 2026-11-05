package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.utils.auth.rbac.RoleStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import org.junit.After;
import org.junit.Test;

/** Auth messages tell a junior what to change (OPS-AUTH-006). */
public class JuniorMessagesTest {

    private final List<W8> fixtures = new ArrayList<>();
    private final List<RestApp> apps = new ArrayList<>();

    @After
    public void stop() {
        for (W8 f : fixtures) {
            f.stop();
        }
        for (RestApp a : apps) {
            a.shutdown(5, TimeUnit.SECONDS);
        }
    }

    private W8 started(UnaryOperator<Auth.Builder> config) {
        W8 f = new W8();
        fixtures.add(f);
        f.start(config);
        return f;
    }

    private static String message(ClientResponse r) {
        Object err = W8.object(r).get("error");
        return String.valueOf(((java.util.Map<?, ?>) err).get("message"));
    }

    // OPS-AUTH-006 AC-1: a request-shape 400 of login, register and setup carries its field message
    @Test
    public void OPS_AUTH_006_AC1_bodyErrorsNameTheField() {
        W8 f = started(b -> b.register(true));
        ClientResponse wrongNames = f.post("/auth/login", "{\"user\":\"a\",\"pass\":\"b\"}");
        assertEquals(wrongNames.text(), 400, wrongNames.status());
        assertEquals("BAD_REQUEST", W8.code(wrongNames));
        assertEquals("username must be a JSON string", message(wrongNames));
        ClientResponse noPassword = f.post("/auth/login", "{\"username\":\"a\"}");
        assertEquals("password must be a non-empty JSON string", message(noPassword));
        ClientResponse register = f.post("/auth/register", "{\"name\":\"neo\",\"password\":\"long-enough-pw\"}");
        assertEquals(register.text(), 400, register.status());
        assertEquals("username must be a JSON string", message(register));
        for (String body : new String[] {"{\"username\":\"neo\"}", "{\"username\":\"neo\",\"password\":5}"}) {
            ClientResponse noPw = f.post("/auth/register", body);
            assertEquals(noPw.text(), 400, noPw.status());
            assertEquals("BAD_REQUEST", W8.code(noPw));
            assertEquals("password must be a non-empty JSON string", message(noPw));
        }
    }

    // OPS-AUTH-006 AC-2 (amended by OPS-AUTH-021 AC-8): an expired access token mentions expiry and refresh; a
    // garbage one names how to get a valid token without claiming expiry
    @Test
    public void OPS_AUTH_006_AC2_invalidTokenMentionsExpiryAndRefresh() throws Exception {
        W8 f = started(b -> b);
        ClientResponse r = f.get("/api/x", "Authorization", "Bearer not.a.token");
        assertEquals(r.text(), 401, r.status());
        assertEquals("invalid_token", W8.code(r));
        assertFalse(r.text(), message(r).contains("expired"));
        assertTrue(r.text(), message(r).contains("refresh"));
        String id = f.user("exp", W8.PW, "reader");
        ClientResponse e = f.get("/api/x", "Authorization", "Bearer " + f.expiredAccess(id));
        assertEquals(e.text(), 401, e.status());
        assertEquals("invalid_token", W8.code(e));
        assertTrue(e.text(), message(e).contains("expired"));
        assertTrue(e.text(), message(e).contains("refresh"));
    }

    // OPS-AUTH-006 AC-3: the restart-logout doctor WARN names FileRefreshTokenStore
    @Test
    public void OPS_AUTH_006_AC3_restartLogoutWarnNamesTheFileStore() {
        W8 f = new W8();
        fixtures.add(f);
        f.users = new com.snoworca.naruserve.utils.auth.user.ForwardingUserStore(f.store) { };
        f.start(b -> b);
        List<String> warns = RecordingLogger.matching(f.logger.lines, "(?s)WARN auth doctor WARN restart logout.*");
        assertEquals(f.logger.lines.toString(), 1, warns.size());
        assertTrue(warns.get(0), warns.get(0).contains("FileRefreshTokenStore"));
        // an app cannot construct the package-private file throttle and device trust, so the WARN must not offer them
        assertFalse(warns.get(0), warns.get(0).contains("FileLoginThrottle") || warns.get(0).contains("FileDeviceTrust"));
        assertTrue(warns.get(0), warns.get(0).contains("LoginThrottle") && warns.get(0).contains("DeviceTrust"));
    }

    private static List<String> roleWarns(W8 f) {
        return RecordingLogger.matching(f.logger.lines, "(?s)WARN auth doctor WARN undeclared role.*");
    }

    // OPS-AUTH-006 AC-4: an undeclared .user or requireRole role gives one doctor WARN per role; declared, built-in
    // admin and custom RoleStore roles give none
    @Test
    public void OPS_AUTH_006_AC4_undeclaredRolesAreWarned() {
        W8 f = new W8();
        fixtures.add(f);
        f.routes = w -> {
            w.app.get("/api/ed", w.auth.requireRole("editr"), (req, res) -> res.send("e"));
            w.app.get("/api/rd", w.auth.requireRole("reader"), (req, res) -> res.send("r"));
        };
        f.start(b -> b.user("seed", "long-enough-pw", "reviewer", "admin", "reader"));
        List<String> warns = roleWarns(f);
        assertEquals(f.logger.lines.toString(), 2, warns.size());
        String all = warns.toString();
        assertTrue(all, all.contains("'editr'") && all.contains("'reviewer'"));
        assertTrue(all, all.contains("403") && all.contains("typo"));
        assertFalse(all, all.contains("'reader'") || all.contains("'admin'"));

        W8 custom = new W8();
        fixtures.add(custom);
        RoleStore db = role -> Collections.<String>emptySet();
        custom.routes = w -> w.app.get("/api/ed", w.auth.requireRole("editr"), (req, res) -> res.send("e"));
        custom.start(b -> b.roles(db).user("seed", "long-enough-pw", "reviewer"));
        assertEquals(custom.logger.lines.toString(), 0, roleWarns(custom).size());
    }

    // OPS-AUTH-006 AC-5: the uninstalled-auth ERROR names the full path of a request through a Router mounted at /api
    @Test
    public void OPS_AUTH_006_AC5_uninstalledErrorNamesTheFullPath() {
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        RestApp app = RestApp.builder().httpAdapter(adapter).jsonCodec(new AdminFixture.MiniJsonCodec()).build();
        apps.add(app);
        RecordingLogger log = new RecordingLogger();
        Auth auth = AuthTestSupport.builder(new AuthTestSupport.TestCredentials(), log).build();
        com.snoworca.naruserve.Router api = com.snoworca.naruserve.Router.create();
        api.use(auth);
        api.get("/x", (req, res) -> res.send("x"));
        app.use("/api", api);
        app.listen(0);
        assertEquals(500, adapter.send(ClientRequest.of("GET", "/api/x")).status());
        List<String> errors = RecordingLogger.matching(log.lines, "(?s)ERROR .*");
        assertEquals(log.lines.toString(), 1, errors.size());
        assertTrue(errors.get(0), errors.get(0).contains("GET /api/x reached auth"));
    }

    // OPS-AUTH-006 AC-6: singular and plural of the distinct-byte message
    @Test
    public void OPS_AUTH_006_AC6_distinctByteMessageIsGrammatical() {
        try {
            Auth.builder("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa").build();
            fail("PROD built with one distinct byte");
        } catch (AuthConfigException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("1 distinct byte value;"));
        }
        try {
            Auth.builder("abcabcabcabcabcabcabcabcabcabcabcabcabc").build();
            fail("PROD built with three distinct bytes");
        } catch (AuthConfigException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("3 distinct byte values;"));
        }
    }

    // OPS-AUTH-006 AC-1: setup carries login's field messages
    @Test
    public void OPS_AUTH_006_AC1_setupBodyErrorsNameTheField() {
        W8 f = new W8();
        fixtures.add(f);
        f.start("127.0.0.1", false, b -> b.recovery(true).user("admin", null, "admin"));
        String token = f.auth.pendingSetupToken();
        ClientResponse noName = f.post("/auth/setup", "{\"token\":\"" + token + "\",\"password\":\"long-enough-pw\"}");
        assertEquals(noName.text(), 400, noName.status());
        assertEquals("BAD_REQUEST", W8.code(noName));
        assertEquals("username must be a JSON string", message(noName));
        ClientResponse noPassword = f.post("/auth/setup", "{\"token\":\"" + token + "\",\"username\":\"admin\"}");
        assertEquals(noPassword.text(), 400, noPassword.status());
        assertEquals("password must be a non-empty JSON string", message(noPassword));
    }

    // OPS-AUTH-006 AC-1: any other HttpException in the auth routers answers without its message
    @Test
    public void OPS_AUTH_006_AC1_otherHttpExceptionHidesItsMessage() throws Exception {
        W8 f = new W8();
        fixtures.add(f);
        f.user("alice", W8.PW, "reader");
        f.routes = w -> w.auth.routes().post("/ops/http", (req, res) -> {
            throw new com.snoworca.naruserve.HttpException(400, "HIDDEN-DETAIL");
        });
        f.start(b -> b);
        String bearer = "Bearer " + f.loginBody("alice", W8.PW).get("accessToken");
        ClientResponse r = f.post("/auth/ops/http", null, "Authorization", bearer);
        assertEquals(r.text(), 400, r.status());
        assertEquals("BAD_REQUEST", W8.code(r));
        assertFalse(r.text(), r.text().contains("HIDDEN-DETAIL"));
    }
}
