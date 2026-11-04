package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.Registration;
import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.Router;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.RecordingLogger;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.After;
import org.junit.Test;

/** app.use(auth) is auth.install(app); an auth that was never installed fails loudly (FR-AUTH-010). */
public class UseAuthTest {

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

    private W8 started(boolean viaUse) {
        W8 f = new W8();
        fixtures.add(f);
        if (viaUse) {
            f.attach = (auth, app) -> app.use(auth);
        }
        f.start(b -> b.user("admin", W8.PW, "reader"));
        return f;
    }

    private static List<String> shape(RestApp app) {
        List<String> out = new ArrayList<>();
        for (Registration r : app.registrations()) {
            out.add(r.getKind() + " " + r.getPath() + (r.getMount() == null ? "" : " under " + r.getMount().getPath()));
        }
        return out;
    }

    // FR-AUTH-010 AC-2: app.use(auth) mounts the login endpoint, seeds the startup account and records the same
    // registrations as auth.install(app)
    @Test
    public void FR_AUTH_010_AC2_useInstallsAuth() throws Exception {
        W8 used = started(true);
        assertNotNull(used.store.findByUsername("admin"));
        ClientResponse login = used.post("/auth/login", W8.login("admin", W8.PW));
        assertEquals(login.text(), 200, login.status());
        assertNotNull(W8.object(login).get("accessToken"));
        W8 installed = started(false);
        assertEquals(shape(installed.app), shape(used.app));
    }

    // FR-AUTH-010 AC-3: app.use(auth) after a route throws the install ordering error
    @Test
    public void FR_AUTH_010_AC3_useAfterRouteThrowsLikeInstall() {
        RestApp app = RestApp.builder().httpAdapter(new InMemoryHttpAdapter()).jsonCodec(new AdminFixture.MiniJsonCodec()).build();
        apps.add(app);
        app.get("/x", (req, res) -> res.send("x"));
        Auth auth = AuthTestSupport.builder().build();
        try {
            app.use(auth);
            fail("use(auth) accepted a prior route");
        } catch (IllegalStateException e) {
            assertEquals("auth.install(app) must come before routes; found GET /x registered before it", e.getMessage());
        }
    }

    private interface Attach {
        void attach(RestApp app, Auth auth, AtomicBoolean reached);
    }

    private void failsLoudly(String name, Attach attach) {
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        RestApp app = RestApp.builder().httpAdapter(adapter).jsonCodec(new AdminFixture.MiniJsonCodec()).build();
        apps.add(app);
        RecordingLogger log = new RecordingLogger();
        Auth auth = AuthTestSupport.builder(new AuthTestSupport.TestCredentials(), log).build();
        AtomicBoolean reached = new AtomicBoolean();
        attach.attach(app, auth, reached);
        app.listen(0);
        for (int i = 0; i < 3; i++) {
            ClientResponse r = adapter.send(ClientRequest.of("GET", "/api/x"));
            assertEquals(name + ": " + r.text(), 500, r.status());
            assertEquals(name, "INTERNAL_ERROR", AuthTestSupport.code(r));
        }
        assertFalse(name + ": next ran", reached.get());
        List<String> errors = RecordingLogger.matching(log.lines, "(?s)ERROR .*");
        assertEquals(name + ": " + log.lines, 1, errors.size());
        assertTrue(errors.get(0), errors.get(0).contains("app.use(auth)"));
        assertTrue(errors.get(0), errors.get(0).contains("auth.install(app)"));
    }

    // FR-AUTH-010 AC-4: an auth never installed and attached through a router mounted under a prefix or a route middleware
    // answers 500 INTERNAL_ERROR, never calls next and logs one ERROR naming the fix
    @Test
    public void FR_AUTH_010_AC4_uninstalledAuthFailsLoudly() {
        failsLoudly("router", (app, auth, reached) -> {
            Router r = Router.create();
            r.use(auth);
            r.get("/x", (req, res) -> {
                reached.set(true);
                res.send("x");
            });
            app.use("/api", r);
        });
        failsLoudly("route", (app, auth, reached) -> app.get("/api/x", auth, (req, res) -> {
            reached.set(true);
            res.send("x");
        }));
    }
}
