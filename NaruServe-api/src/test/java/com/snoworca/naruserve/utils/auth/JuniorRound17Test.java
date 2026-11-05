package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.testing.TestApp;
import com.snoworca.naruserve.utils.auth.testing.AuthTesting;
import com.snoworca.naruserve.utils.auth.user.ForwardingUserStore;
import com.snoworca.naruserve.utils.auth.user.InMemoryUserStore;
import com.snoworca.naruserve.utils.auth.user.UserPage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Test;

/** The code findings of the seventeenth junior study are answered with the fix (OPS-AUTH-029). */
public class JuniorRound17Test {

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

    /** A store whose list() returns null. */
    private static final class NullList extends ForwardingUserStore {
        NullList(InMemoryUserStore d) {
            super(d);
        }

        @Override
        public UserPage list(int offset, int limit, @org.jspecify.annotations.Nullable String search) {
            return null;
        }
    }

    // OPS-AUTH-029 AC-1: the never-installed ERROR of plain middleware says it answers 500, not that routes are public
    @Test
    public void OPS_AUTH_029_AC1_middlewareCaseSays500() {
        RecordingLogger log = new RecordingLogger();
        Auth auth = Auth.builder(AuthTestSupport.SECRET).logger(log).build();
        TestApp t = TestApp.create();
        t.app().use("", auth); // plain middleware ("/" installs since OPS-AUTH-033 AC-4)
        t.app().get("/me", (req, res) -> res.send("me"));
        t.start();
        t.send("GET", "/me");
        List<String> errors = RecordingLogger.matching(log.lines, "(?s)ERROR .*never installed.*");
        assertEquals(log.lines.toString(), 1, errors.size());
        assertFalse(errors.get(0), errors.get(0).contains("is public"));
        assertTrue(errors.get(0), errors.get(0).contains("every request that reaches it answers 500"));
    }

    // OPS-AUTH-029 AC-2: a CORS preflight to an auth endpoint gets the CORS hint, not the method hint
    @Test
    public void OPS_AUTH_029_AC2_preflightToAuthEndpointNamesCors() {
        W8 f = fixture();
        f.start(b -> b);
        ClientResponse r = f.adapter.send(ClientRequest.of("OPTIONS", "/auth/login").header("Origin", W8.SAME)
                .header("Access-Control-Request-Method", "POST"));
        assertEquals(401, r.status());
        assertTrue(message(r), message(r).contains("CORS preflight") && !message(r).contains("takes POST"));
    }

    // OPS-AUTH-029 AC-3: A1 on a store whose list() returns null names UserStore.list and the full path
    @Test
    public void OPS_AUTH_029_AC3_nullListIsNamed() throws Exception {
        AdminFixture a = new AdminFixture(NullList::new);
        admins.add(a);
        a.start();
        String root = a.user("root", "admin");
        assertEquals(500, a.call("GET", "/auth/admin/users", root, null).status());
        List<String> errors = RecordingLogger.matching(a.logger.lines, "(?s)ERROR auth: .*UserStore.list returned null.*");
        assertEquals(a.logger.lines.toString(), 1, errors.size());
        assertTrue(errors.get(0), errors.get(0).contains("GET /auth/admin/users"));
    }

    // OPS-AUTH-029 AC-4: the test kit refuses a list() that returns null
    @Test
    public void OPS_AUTH_029_AC4_kitChecksList() {
        try {
            AuthTesting.verifyUserStore(new NullList(new InMemoryUserStore()));
            fail("passed");
        } catch (AssertionError e) {
            assertTrue(e.getMessage(), e.getMessage().contains("list") && e.getMessage().contains("null"));
        }
        AuthTesting.verifyUserStore(new ForwardingUserStore(new InMemoryUserStore()) { }); // a working list() passes
    }

    // OPS-AUTH-029 AC-5: auth.users().create with a null username is a shown refusal
    @Test
    public void OPS_AUTH_029_AC5_nullUsernameIsNamed() {
        W8 f = fixture();
        f.routes = w -> w.app.get("/mk", (req, res) -> res.send(w.auth.users().create(req.query("name"),
                "long-password-1".toCharArray()).getId()));
        f.start(b -> b.permit("/mk"));
        ClientResponse r = f.get("/mk");
        assertEquals(r.text(), 400, r.status());
        assertEquals("a username is required", message(r));
    }

    // OPS-AUTH-029 AC-6: the closed-setup 404 names the recovery start too
    @Test
    public void OPS_AUTH_029_AC6_closedSetupNamesRecovery() {
        W8 f = fixture();
        f.start(b -> b);
        String m = message(f.post("/auth/setup", "{\"token\":\"x\",\"username\":\"a\",\"password\":\"long-password-1\"}"));
        assertTrue(m, m.contains(".recovery(true)") && m.contains("NARU_AUTH_RECOVERY"));
    }

    // OPS-AUTH-029 AC-7: a false auth.check rule says that rule refused
    @Test
    public void OPS_AUTH_029_AC7_checkRefusalNamesTheRule() throws Exception {
        W8 f = fixture();
        f.routes = w -> w.app.get("/c", w.auth.check((p, req) -> false), (req, res) -> res.send("c"));
        f.start(b -> b);
        f.user("bob", W8.PW, "reader");
        String t = String.valueOf(f.loginBody("bob", W8.PW).get("accessToken"));
        String m = message(f.get("/c", "Authorization", "Bearer " + t));
        assertTrue(m, m.contains("auth.check rule"));
    }

    // OPS-AUTH-029 AC-4: a list() that throws UnsupportedOperationException passes the kit
    @Test
    public void OPS_AUTH_029_AC4_kitAcceptsUnsupportedList() {
        AuthTesting.verifyUserStore(new ForwardingUserStore(new InMemoryUserStore()) {
            @Override
            public UserPage list(int offset, int limit, @org.jspecify.annotations.Nullable String search) {
                throw new UnsupportedOperationException("this store does not page");
            }
        });
    }

    // OPS-AUTH-029 AC-7: the answer of a false auth.check rule is 403
    @Test
    public void OPS_AUTH_029_AC7_checkRefusalIs403() throws Exception {
        W8 f = fixture();
        f.routes = w -> w.app.get("/c", w.auth.check((p, req) -> false), (req, res) -> res.send("c"));
        f.start(b -> b);
        f.user("bob", W8.PW, "reader");
        String t = String.valueOf(f.loginBody("bob", W8.PW).get("accessToken"));
        ClientResponse r = f.get("/c", "Authorization", "Bearer " + t);
        assertEquals(r.text(), 403, r.status());
    }
}
