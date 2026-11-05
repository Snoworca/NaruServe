package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.Configurer;
import com.snoworca.naruserve.Registration;
import com.snoworca.naruserve.RegistrationKind;
import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.Router;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.TestApp;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** auth.install(app): the ordering check and the wiring (FR-AUTH-005). */
public class AuthInstallTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private interface Prior {
        void register(RestApp app) throws Exception;
    }

    private void refusedAfter(Prior prior, String named) throws Exception {
        TestApp t = TestApp.create();
        prior.register(t.app());
        Auth auth = AuthTestSupport.builder().build();
        try {
            auth.install(t.app());
            fail("install accepted a prior " + named);
        } catch (IllegalStateException e) {
            assertEquals("auth.install(app) must come before routes; found " + named + " registered before it",
                    e.getMessage());
        }
    }

    // FR-AUTH-005 AC-1: a route, ws, staticFiles, router mount or group before install throws, naming it
    @Test
    public void FR_AUTH_005_AC1_routeFamilyBeforeInstallThrows() throws Exception {
        refusedAfter(app -> app.get("/x", (req, res) -> res.send("x")), "GET /x");
        refusedAfter(app -> app.ws("/chat", (req, ws) -> { }), "WEBSOCKET /chat");
        File dir = tmp.newFolder("www");
        refusedAfter(app -> app.staticFiles("/files", dir), "STATIC /files");
        refusedAfter(app -> app.use("/api", Router.create()), "ROUTER_MOUNT /api");
        Configurer<com.snoworca.naruserve.RouteGroup> group = g -> g.get("/a", (req, res) -> res.send("a"));
        refusedAfter(app -> app.group("/g", group), "GROUP /g");
    }

    // FR-AUTH-005 AC-2: cors, accessLog, use(Middleware), use(ErrorMiddleware) and param before install are allowed
    @Test
    public void FR_AUTH_005_AC2_pathlessMiddlewareBeforeInstallIsAllowed() {
        TestApp t = TestApp.create();
        RestApp app = t.app();
        app.cors(c -> c.allowedOrigins("https://app.example.com"));
        app.accessLog(o -> { });
        app.use((req, res, next) -> next.run());
        app.use((com.snoworca.naruserve.ErrorMiddleware) (err, req, res, next) -> next.error(err));
        app.param("id", (req, res, next, value, name) -> next.run());
        AuthTestSupport.builder().build().install(app);
    }

    // FR-AUTH-005 AC-3 (structure): one MIDDLEWARE record targeting the auth middleware, then the /auth mount with
    // its expanded records, then the /auth/admin mount with its expanded records
    @Test
    public void FR_AUTH_005_AC3_registrationOrderAfterInstall() {
        TestApp t = TestApp.create();
        Auth auth = AuthTestSupport.builder().build();
        auth.routes().post("/probe", (req, res) -> res.send("p"));
        auth.install(t.app());
        List<Registration> rs = t.app().registrations();
        List<String> shape = new ArrayList<>();
        for (Registration r : rs) {
            shape.add(r.getKind() + " " + r.getPath() + (r.getMount() == null ? "" : " under " + r.getMount().getPath()));
        }
        assertEquals(shape.toString(), RegistrationKind.MIDDLEWARE, rs.get(0).getKind());
        assertSame(auth, rs.get(0).getTarget());
        int middleware = 0;
        for (Registration r : rs) {
            middleware += r.getKind() == RegistrationKind.MIDDLEWARE ? 1 : 0;
        }
        assertEquals(1, middleware);
        // each mount holds the auth router itself, its records, and last the auth error middleware; the account mount
        // also holds the built-in account endpoints router after the app's own routes (FR-FLOW-004 AC-7: login is a
        // registered route; refresh, logout, password (FR-FLOW-005, SEC-FLOW-014) and the conditional setup with it,
        // register only with .register(true))
        assertEquals(shape.toString(), java.util.Arrays.asList(
                "MIDDLEWARE ",
                "ROUTER_MOUNT /auth",
                "ROUTER_MOUNT /auth under /auth",
                "ROUTE /auth/probe under /auth",
                "ROUTER_MOUNT /auth under /auth",
                "ROUTE /auth/login under /auth",
                "ROUTE /auth/refresh under /auth",
                "ROUTE /auth/logout under /auth",
                // GET and PATCH /me (IR-FLOW-002 AC-1, FR-FLOW-006)
                "ROUTE /auth/me under /auth",
                "ROUTE /auth/me under /auth",
                "ROUTE /auth/password under /auth",
                "ROUTE /auth/setup under /auth",
                "ERROR_MIDDLEWARE /auth under /auth",
                "ROUTER_MOUNT /auth/admin",
                "ROUTER_MOUNT /auth/admin under /auth/admin",
                // the 15 admin endpoints of IR-IAM-005 AC-1, in their registration order
                "ROUTE /auth/admin/users under /auth/admin",
                "ROUTE /auth/admin/users under /auth/admin",
                "ROUTE /auth/admin/users/:id under /auth/admin",
                "ROUTE /auth/admin/users/:id under /auth/admin",
                "ROUTE /auth/admin/users/:id under /auth/admin",
                "ROUTE /auth/admin/users/:id/groups under /auth/admin",
                "ROUTE /auth/admin/users/:id/lockout under /auth/admin",
                "ROUTE /auth/admin/groups under /auth/admin",
                "ROUTE /auth/admin/groups under /auth/admin",
                "ROUTE /auth/admin/groups/:id under /auth/admin",
                "ROUTE /auth/admin/groups/:id under /auth/admin",
                "ROUTE /auth/admin/groups/:id/members/:userId under /auth/admin",
                "ROUTE /auth/admin/groups/:id/members/:userId under /auth/admin",
                "ROUTE /auth/admin/groups/:id/parents/:parentId under /auth/admin",
                "ROUTE /auth/admin/groups/:id/parents/:parentId under /auth/admin",
                "ERROR_MIDDLEWARE /auth/admin under /auth/admin"), shape);
        assertSame(auth.routes(), rs.get(2).getTarget());
        // the admin router's record follows the eight records of the built-in account endpoints router
        assertSame(auth.adminRoutes(), rs.get(14).getTarget());
    }

    // FR-AUTH-005 AC-4: a WebSocket upgrade without a token, to a route registered after install, gets 401 JSON
    @Test(timeout = 20000)
    public void FR_AUTH_005_AC4_websocketUpgradeWithoutTokenGets401BeforeTheHandshake() {
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        TestApp t = TestApp.create(adapter);
        AuthTestSupport.builder().build().install(t.app());
        final boolean[] opened = new boolean[1];
        t.app().ws("/chat", (req, ws) -> opened[0] = true);
        t.start();
        ClientResponse r = adapter.send(ClientRequest.upgrade("/chat"));
        assertEquals(401, r.status());
        assertTrue(r.header("Content-Type"), r.header("Content-Type").startsWith("application/json"));
        assertEquals("unauthenticated", AuthTestSupport.code(r));
        assertFalse(r.upgradeHandedOver());
        assertFalse(opened[0]);
    }

    // FR-AUTH-005 AC-7: a second install on the same app throws saying it is already installed (OPS-AUTH-008 AC-2)
    @Test
    public void FR_AUTH_005_AC7_secondInstallThrows() {
        TestApp t = TestApp.create();
        Auth auth = AuthTestSupport.builder().build();
        auth.install(t.app());
        try {
            auth.install(t.app());
            fail("second install accepted");
        } catch (IllegalStateException e) {
            assertEquals("auth is already installed on this app; call app.use(auth) or auth.install(app) once, and "
                    + "build one Auth per app", e.getMessage());
        }
        try {
            AuthTestSupport.builder().build().install(t.app());
            fail("another Auth installed on the same app");
        } catch (IllegalStateException e) { // OPS-AUTH-024 AC-7 replaced the before-routes message for this case
            assertTrue(e.getMessage(), e.getMessage().contains("another Auth is already installed on this app"));
        }
    }

    // FR-AUTH-005 AC-7: the first Auth is found by what it is, so a second one with another mount path is named too
    @Test
    public void FR_AUTH_005_AC7_secondAuthWithAnotherMountPathIsNamed() {
        TestApp t = TestApp.create();
        AuthTestSupport.builder().build().install(t.app());
        try {
            AuthTestSupport.builder().mountPath("/api/auth").build().install(t.app());
            fail("another Auth installed on the same app");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("another Auth is already installed on this app"));
            assertTrue(e.getMessage(), e.getMessage().contains("mounted at /auth)"));
        }
    }

    // FR-AUTH-005 AC-7: an app route under the first Auth's mount path is an app route, so the order message stays
    @Test
    public void FR_AUTH_005_AC7_appRouteUnderTheFirstMountKeepsTheOrderMessage() {
        TestApp t = TestApp.create();
        AuthTestSupport.builder().build().install(t.app());
        t.app().get("/auth/extra", (req, res) -> res.send("x"));
        try {
            AuthTestSupport.builder().build().install(t.app());
            fail("installed after a route");
        } catch (IllegalStateException e) {
            assertEquals("auth.install(app) must come before routes; found GET /auth/extra registered before it",
                    e.getMessage());
        }
    }

    // FR-AUTH-005 AC-1: the app's own router at the mount path is that registration, not another Auth
    @Test
    public void FR_AUTH_005_AC1_appRouterAtTheMountPathIsNamedAsThatRegistration() throws Exception {
        refusedAfter(app -> app.use("/auth", Router.create()), "ROUTER_MOUNT /auth");
    }

    // FR-AUTH-005 component (mount path): .mountPath("/api/auth") mounts the account router there, and /auth/login is then
    // an ordinary protected path (the login endpoint itself comes with the login requirement)
    @Test
    public void mountPathMovesTheAccountRouter() {
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        TestApp t = TestApp.create(adapter);
        Auth auth = AuthTestSupport.builder().mountPath("/api/auth").build();
        auth.install(t.app());
        boolean mounted = false;
        for (Registration r : t.app().registrations()) {
            mounted |= r.getKind() == RegistrationKind.ROUTER_MOUNT && "/api/auth".equals(r.getPath())
                    && r.getTarget() == auth.routes();
        }
        assertTrue(mounted);
        t.start();
        ClientResponse r = AuthTestSupport.post(adapter, "/auth/login", "{\"username\":\"a\",\"password\":\"b\"}");
        assertEquals(401, r.status());
        assertEquals("unauthenticated", AuthTestSupport.code(r));
        assertNull(r.header("Set-Cookie"));
        assertNotNull(auth.routes());
    }

    // FR-AUTH-005 (Builder.logger default): without .logger(...), the components built with auth (audit trail, user
    // facade, ...) log to the app's logger from install on, not to the build-time console logger
    @Test
    public void withoutBuilderLoggerEveryComponentLogsToTheAppLoggerAfterInstall() {
        com.snoworca.naruserve.testing.RecordingLogger appLog = new com.snoworca.naruserve.testing.RecordingLogger();
        TestApp t = TestApp.create();
        t.app().logger(appLog);
        com.snoworca.naruserve.utils.auth.user.UserStore failing =
                new com.snoworca.naruserve.utils.auth.user.ForwardingUserStore(
                        new com.snoworca.naruserve.utils.auth.user.InMemoryUserStore()) {
                    @Override
                    public com.snoworca.naruserve.utils.auth.user.AuthUser findById(String id) {
                        throw new IllegalStateException("store down");
                    }
                };
        Auth auth = Auth.builder(AuthTestSupport.SECRET).credentials(new AuthTestSupport.TestCredentials())
                .hashIterations(1000).users(failing).audit(e -> {
                    throw new IllegalStateException("sink down");
                }).build();
        auth.install(t.app());
        auth.audit().emit(auth.audit().event("test_event", "test", null, null, null, false).build());
        try {
            auth.users().findById("u1");
            fail("a failing user store answered");
        } catch (AuthUnavailableException expected) {
            // logged, then 503
        }
        assertEquals(appLog.lines.toString(), 1, com.snoworca.naruserve.testing.RecordingLogger.countMatching(
                appLog.lines, "ERROR auth: the audit sink failed on a test_event event.*"));
        assertEquals(appLog.lines.toString(), 1, com.snoworca.naruserve.testing.RecordingLogger.countMatching(
                appLog.lines, "ERROR auth: the user store failed in findById.*"));
    }

    // REL-FLOW-001 (the stop snapshot keeps the counters): a login failure recorded by a hash or completion still
    // running when the app stops is in the throttle file after stop, because the hash slots and the background pool
    // stop (and are awaited) before the final snapshot is written
    @Test
    public void REL_FLOW_001_failureRecordedDuringStopIsInTheFinalSnapshot() throws Exception {
        File dir = tmp.newFolder();
        W8 f = new W8();
        f.users = new com.snoworca.naruserve.utils.auth.user.FileUserStore(new File(dir, "users.json"),
                AuthTestSupport.FILE_KEY);
        f.users.create(com.snoworca.naruserve.utils.auth.user.AuthUser.builder().username("alice")
                .passwordHash(new com.snoworca.naruserve.utils.auth.password.Pbkdf2PasswordHasher(1000)
                .hash(W8.PW.toCharArray())).roles("reader").build());
        java.util.concurrent.CountDownLatch hashing = new java.util.concurrent.CountDownLatch(1);
        f.start(b -> b.authenticator((name, password, ctx) -> {
            hashing.countDown();
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            while (!Thread.currentThread().isInterrupted() && System.nanoTime() < deadline) {
                java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L); // until stop interrupts the slot
            }
            Thread.interrupted();
            return null;
        }));
        Thread login = new Thread(() -> f.post("/auth/login", W8.login("alice", "wrong-password"), "Host", W8.HOST,
                "Origin", W8.SAME), "login-in-flight");
        login.setDaemon(true);
        login.start();
        assertTrue(hashing.await(10, java.util.concurrent.TimeUnit.SECONDS));
        f.app.shutdown(0, java.util.concurrent.TimeUnit.SECONDS);
        login.join(10_000);
        assertFalse("the login request never answered", login.isAlive());
        File file = new File(dir, "users-login-throttle.json");
        assertTrue("no snapshot was written at stop", file.exists());
        String text = new String(java.nio.file.Files.readAllBytes(file.toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(text, text.contains(FileLoginThrottle.hashed("login:u:alice")));
    }
}
