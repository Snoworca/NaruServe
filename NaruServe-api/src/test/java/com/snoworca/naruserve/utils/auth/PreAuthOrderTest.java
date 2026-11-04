package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.Middleware;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.testing.TestApp;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** Path middleware before install and CORS ordering (SEC-AUTH-004). */
public class PreAuthOrderTest {

    private static final Middleware PASS = (req, res, next) -> next.run();

    private static String startFailure(TestApp t) {
        try {
            t.start();
        } catch (RuntimeException e) {
            return String.valueOf(e.getMessage());
        }
        return null;
    }

    private static List<String> listing(RecordingLogger log) {
        List<String> out = new ArrayList<>();
        for (String l : log.info()) {
            if (l.startsWith("auth doctor: pre-auth middleware ")) {
                out.add(l.substring("auth doctor: pre-auth middleware ".length()));
            }
        }
        return out;
    }

    // SEC-AUTH-004 AC-1: use("/hook", mw) before install FAILs start in PROD and DEV; .allowPreAuthMiddleware("/hook")
    // starts and the doctor lists /hook
    @Test
    public void SEC_AUTH_004_AC1_pathMiddlewareBeforeInstall() {
        for (AuthMode mode : AuthMode.values()) {
            TestApp t = TestApp.create();
            t.app().use("/hook", PASS);
            AuthTestSupport.builder().mode(mode).build().install(t.app());
            String failure = startFailure(t);
            assertTrue(mode + ": " + failure, failure != null && failure.contains("pre-auth middleware")
                    && failure.contains("/hook") && failure.contains(".allowPreAuthMiddleware(\"/hook\")"));
        }
        RecordingLogger log = new RecordingLogger();
        TestApp t = TestApp.create();
        t.app().use("/hook", PASS);
        AuthTestSupport.builder(new AuthTestSupport.TestCredentials(), log).allowPreAuthMiddleware("/hook").build()
                .install(t.app());
        assertEquals(null, startFailure(t));
        assertEquals("[[/hook]]", listing(log).toString());
        // a whitelist names exact paths: another path still FAILs
        TestApp other = TestApp.create();
        other.app().use("/hook2", PASS);
        AuthTestSupport.builder().allowPreAuthMiddleware("/hook").build().install(other.app());
        String failure = startFailure(other);
        assertTrue(failure, failure != null && failure.contains("/hook2"));
    }

    // SEC-AUTH-004 AC-2: use("", mw) and use("/", mw) before install are allowed and listed
    @Test
    public void SEC_AUTH_004_AC2_pathlessMiddlewareIsListedNotFailed() {
        RecordingLogger log = new RecordingLogger();
        TestApp t = TestApp.create();
        t.app().use("", PASS);
        t.app().use("/", PASS);
        t.app().use(PASS);
        AuthTestSupport.builder(new AuthTestSupport.TestCredentials(), log).build().install(t.app());
        assertEquals(null, startFailure(t));
        List<String> l = listing(log);
        assertEquals(1, l.size());
        assertTrue(l.get(0), l.get(0).contains("/") && l.get(0).contains("(all paths)"));
    }

    // SEC-AUTH-004 AC-3: cors after install FAILs start with "cors must be registered before auth.install", DEV too
    @Test
    public void SEC_AUTH_004_AC3_corsAfterInstallFails() {
        for (AuthMode mode : AuthMode.values()) {
            TestApp t = TestApp.create();
            AuthTestSupport.builder().mode(mode).build().install(t.app());
            t.app().cors(c -> c.allowedOrigins("https://app.example.com"));
            String failure = startFailure(t);
            assertTrue(mode + ": " + failure, failure != null
                    && failure.contains("cors must be registered before auth.install"));
        }
    }

    private static InMemoryHttpAdapter corsApp() {
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter("203.0.113.9", true);
        TestApp t = TestApp.create(adapter);
        t.app().cors(c -> c.allowedOrigins("https://app.example.com"));
        AuthTestSupport.builder().build().install(t.app());
        t.app().get("/admin/x", (req, res) -> res.send("x"));
        t.app().onError((err, req, res) -> res.status(500).send("x"));
        t.start();
        return adapter;
    }

    // SEC-AUTH-004 AC-4: cors before install answers the preflight OPTIONS /admin/x without a token
    @Test
    public void SEC_AUTH_004_AC4_corsAnswersPreflightWithoutAToken() {
        ClientResponse r = corsApp().send(ClientRequest.of("OPTIONS", "/admin/x")
                .header("Origin", "https://app.example.com").header("Access-Control-Request-Method", "GET"));
        assertTrue(r.status() + " " + r.text(), r.status() == 204 || r.status() == 200);
        assertEquals("https://app.example.com", r.header("Access-Control-Allow-Origin"));
    }

    // SEC-AUTH-004 AC-5: an OPTIONS with Access-Control-Request-Method but no Origin is authenticated like any request
    @Test
    public void SEC_AUTH_004_AC5_originlessOptionsIsAuthenticated() {
        ClientResponse r = corsApp().send(ClientRequest.of("OPTIONS", "/admin/x")
                .header("Access-Control-Request-Method", "GET"));
        assertEquals(r.text(), 401, r.status());
        assertEquals("unauthenticated", AuthTestSupport.code(r));
    }
}
