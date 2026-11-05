package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.HttpException;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.testing.TestApp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Test;

/** The five items the user approved with guide changes after the twentieth junior study (OPS-AUTH-033). */
public class JuniorFinalItemsTest {

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

    private static String token(W8 f) throws Exception {
        f.user("bob", W8.PW, "reader");
        return String.valueOf(f.loginBody("bob", W8.PW).get("accessToken"));
    }

    private static void appRoutes(W8 w) {
        w.app.get("/boom", (req, res) -> {
            throw new IllegalStateException("db password=hunter2 failed");
        });
        w.app.get("/page", (req, res) -> res.send(String.valueOf(req.queryInt("page", 1))));
    }

    // OPS-AUTH-033 AC-1: in PROD auth's error layer answers any other exception as JSON without a stack trace
    @Test
    public void OPS_AUTH_033_AC1_prodAnswersAppExceptionsAsJson() {
        for (boolean appended : new boolean[] {false, true}) {
            W8 f = fixture();
            f.routes = JuniorFinalItemsTest::appRoutes;
            if (appended) {
                f.errors = null; // no app.onError: auth appends its error layer at start
            }
            f.start(b -> b.permit("/boom").permit("/page"));
            ClientResponse boom = f.get("/boom");
            assertEquals(boom.text(), 500, boom.status());
            assertTrue(boom.header("Content-Type"), boom.header("Content-Type").startsWith("application/json"));
            assertEquals("INTERNAL_ERROR", W8.code(boom));
            assertFalse(boom.text(), boom.text().contains("hunter2") || boom.text().contains("at com."));
            ClientResponse page = f.get("/page?page=abc");
            assertEquals(page.text(), 400, page.status());
            assertEquals("page must be a 32-bit integer", message(page));
        }
    }

    // OPS-AUTH-033 AC-1: DEV keeps Core's page, whose stack trace helps while developing
    @Test
    public void OPS_AUTH_033_AC1_devKeepsCorePage() {
        W8 f = fixture();
        f.routes = JuniorFinalItemsTest::appRoutes;
        f.start(b -> b.mode(AuthMode.DEV).permit("/boom"));
        ClientResponse boom = f.get("/boom");
        assertEquals(500, boom.status());
        assertTrue(boom.header("Content-Type"), boom.header("Content-Type").startsWith("text/html"));
    }

    // OPS-AUTH-033 AC-2: a wrong method on an auth endpoint with a token is JSON 405 with Allow
    @Test
    public void OPS_AUTH_033_AC2_wrongMethodIs405() throws Exception {
        W8 f = fixture();
        f.start(b -> b);
        String t = token(f);
        ClientResponse r = f.get("/auth/logout", "Authorization", "Bearer " + t);
        assertEquals(r.text(), 405, r.status());
        assertEquals("METHOD_NOT_ALLOWED", W8.code(r));
        assertEquals("POST", r.header("Allow"));
        assertEquals("this endpoint takes POST /auth/logout", message(r));
    }

    // OPS-AUTH-033 AC-2: an admin endpoint with a parameter names its pattern and methods
    @Test
    public void OPS_AUTH_033_AC2_adminWrongMethodNamesPattern() throws Exception {
        AdminFixture a = new AdminFixture();
        admins.add(a);
        a.start();
        String root = a.user("root", "admin");
        String bob = a.user("bob");
        ClientResponse r = a.call("PUT", "/auth/admin/users/" + bob, root, "{}");
        assertEquals(r.text(), 405, r.status());
        assertTrue(r.header("Allow"), r.header("Allow").contains("PATCH"));
        assertTrue(message(r), message(r).startsWith("this endpoint takes ") && message(r).endsWith(" /auth/admin/users/:id"));
        ClientResponse roles = a.call("PUT", "/auth/admin/groups/eng/roles", root, "{}");
        assertEquals(roles.text(), 404, roles.status());
        assertTrue(message(roles), message(roles).contains("grantRole"));
    }

    // OPS-AUTH-033 AC-2: an unknown path under the mount is JSON 404; key routes with keys off say so
    @Test
    public void OPS_AUTH_033_AC2_unknownPathIsJson404() throws Exception {
        W8 f = fixture();
        f.start(b -> b);
        String t = token(f);
        ClientResponse r = f.get("/auth/nothing", "Authorization", "Bearer " + t);
        assertEquals(r.text(), 404, r.status());
        assertEquals("NOT_FOUND", W8.code(r));
        assertEquals("no auth endpoint serves GET /auth/nothing", message(r));
        ClientResponse keys = f.adapter.send(ClientRequest.json("POST", "/auth/me/apikeys", "{}")
                .header("Authorization", "Bearer " + t));
        assertEquals(keys.text(), 404, keys.status());
        assertTrue(message(keys), message(keys).contains("API keys are off") && message(keys).contains(".apiKeys("));
    }

    // OPS-AUTH-033 AC-3: PATCH /auth/me refuses a field outside attributes
    @Test
    public void OPS_AUTH_033_AC3_patchMeRefusesUnknownFields() throws Exception {
        W8 f = fixture();
        f.start(b -> b.allowedAttributes("displayName"));
        String t = token(f);
        ClientResponse r = f.adapter.send(ClientRequest.json("PATCH", "/auth/me", "{\"displayName\":\"Al\"}")
                .header("Authorization", "Bearer " + t));
        assertEquals(r.text(), 400, r.status());
        assertTrue(message(r), message(r).startsWith("unknown field displayName; PATCH /auth/me reads only {\"attributes\":"));
        ClientResponse pw = f.adapter.send(ClientRequest.json("PATCH", "/auth/me", "{\"password\":\"new-password-99\"}")
                .header("Authorization", "Bearer " + t));
        assertEquals(400, pw.status());
        assertTrue(message(pw), message(pw).contains("POST /auth/password"));
        ClientResponse ok = f.adapter.send(ClientRequest.json("PATCH", "/auth/me", "{\"attributes\":{\"displayName\":\"Al\"}}")
                .header("Authorization", "Bearer " + t));
        assertEquals(ok.text(), 200, ok.status());
    }

    // OPS-AUTH-033 AC-4: app.use("/", auth) installs auth like app.use(auth); app.use("", auth) stays plain middleware
    @Test
    public void OPS_AUTH_033_AC4_slashUseInstalls() {
        RecordingLogger log = new RecordingLogger();
        Auth auth = Auth.builder(AuthTestSupport.SECRET).logger(log).build();
        TestApp t = TestApp.create();
        t.app().use("/", auth);
        t.app().get("/me", auth.authenticated(), (req, res) -> res.send("me"));
        t.start();
        assertEquals(401, t.send("GET", "/me").status());
        ClientResponse login = t.send("POST", "/auth/login");
        assertTrue(login.text(), login.status() == 415 || login.status() == 400);
        assertTrue(log.lines.toString(), RecordingLogger.matching(log.lines, "(?s)ERROR .*never installed.*").isEmpty());
    }

    // OPS-AUTH-033 AC-1: the final layer comes after the app's error layers: an app handler answers first, and an error
    // middleware that passes the exception on still ends in the JSON answer
    @Test
    public void OPS_AUTH_033_AC1_appErrorLayersAnswerFirst() {
        W8 own = fixture();
        own.errors = null;
        own.routes = w -> {
            appRoutes(w);
            w.app.onError((err, req, res) -> res.status(418).send("mine"));
        };
        own.start(b -> b.permit("/boom"));
        ClientResponse mine = own.get("/boom");
        assertEquals(mine.text(), 418, mine.status());
        assertEquals("mine", mine.text());

        java.util.concurrent.atomic.AtomicInteger seen = new java.util.concurrent.atomic.AtomicInteger();
        W8 passing = fixture();
        passing.errors = null;
        passing.routes = w -> {
            appRoutes(w);
            w.app.use((com.snoworca.naruserve.ErrorMiddleware) (err, req, res, next) -> {
                seen.incrementAndGet();
                next.error(err);
            });
        };
        passing.start(b -> b.permit("/boom"));
        ClientResponse boom = passing.get("/boom");
        assertEquals(1, seen.get());
        assertEquals(boom.text(), 500, boom.status());
        assertEquals("INTERNAL_ERROR", W8.code(boom));
        assertFalse(boom.text(), boom.text().contains("IllegalStateException"));
    }

    // OPS-AUTH-033 AC-1: a Core HttpException keeps its status; below 500 its message, from 500 no message
    @Test
    public void OPS_AUTH_033_AC1_httpExceptionKeepsStatusAndHidesMessageFrom500() {
        W8 f = fixture();
        f.errors = null;
        f.routes = w -> {
            w.app.get("/teapot", (req, res) -> {
                throw new HttpException(418, "short and stout");
            });
            w.app.get("/down", (req, res) -> {
                throw new HttpException(503, "db password=hunter2 failed");
            });
        };
        f.start(b -> b.permit("/teapot").permit("/down"));
        ClientResponse teapot = f.get("/teapot");
        assertEquals(teapot.text(), 418, teapot.status());
        assertTrue(teapot.header("Content-Type"), teapot.header("Content-Type").startsWith("application/json"));
        assertEquals("short and stout", message(teapot));
        ClientResponse down = f.get("/down");
        assertEquals(down.text(), 503, down.status());
        assertTrue(down.header("Content-Type"), down.header("Content-Type").startsWith("application/json"));
        assertFalse(down.text(), down.text().contains("hunter2"));
    }

    // OPS-AUTH-033 AC-2: OPTIONS passes the final layer on: neither its 405 nor its 404 answers it
    @Test
    public void OPS_AUTH_033_AC2_optionsPassesOn() throws Exception {
        W8 f = fixture();
        f.start(b -> b);
        String t = token(f);
        for (String path : new String[] {"/auth/logout", "/auth/nothing"}) {
            ClientResponse r = f.adapter.send(ClientRequest.of("OPTIONS", path).header("Authorization", "Bearer " + t));
            assertFalse(r.text(), r.text().contains("this endpoint takes") || r.text().contains("no auth endpoint serves"));
        }
    }

    // OPS-AUTH-033 AC-3: username points at A4 and auth.users().rename, and a refused body changes nothing
    @Test
    public void OPS_AUTH_033_AC3_patchMeNamesRenameAndChangesNothing() throws Exception {
        W8 f = fixture();
        f.start(b -> b.allowedAttributes("displayName"));
        String t = token(f);
        String id = f.store.findByUsername("bob").getId();
        ClientResponse name = f.adapter.send(ClientRequest.json("PATCH", "/auth/me", "{\"username\":\"robert\"}")
                .header("Authorization", "Bearer " + t));
        assertEquals(name.text(), 400, name.status());
        assertTrue(message(name), message(name).contains("admin A4") && message(name).contains("auth.users().rename"));
        ClientResponse mixed = f.adapter.send(ClientRequest.json("PATCH", "/auth/me",
                "{\"attributes\":{\"displayName\":\"Al\"},\"nick\":\"x\"}").header("Authorization", "Bearer " + t));
        assertEquals(mixed.text(), 400, mixed.status());
        assertEquals("bob", f.stored(id).getUsername());
        assertTrue(f.stored(id).getAttributes().toString(), f.stored(id).getAttributes().isEmpty());
    }

    static {
        HttpException.class.getName();
    }
}
