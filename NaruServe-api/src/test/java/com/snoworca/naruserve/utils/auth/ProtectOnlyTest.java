package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.Router;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryWebSocketChannel;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.testing.TestApp;
import com.snoworca.naruserve.utils.auth.user.AuthUser;
import com.snoworca.naruserve.utils.auth.user.InMemoryUserStore;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.Test;

/** The protectOnly range and the always-protected upgrades (SEC-AUTH-006) through the real auth middleware. */
public class ProtectOnlyTest {

    private InMemoryHttpAdapter adapter;
    private RecordingLogger logger;
    private Auth auth;
    private String aliceId;

    private Auth.Builder builder() throws Exception {
        InMemoryUserStore s = new InMemoryUserStore();
        aliceId = s.create(AuthUser.builder().username("alice").passwordHash("x").build()).getId();
        logger = new RecordingLogger();
        return Auth.builder(AuthTestSupport.SECRET).hashIterations(1000).logger(logger).users(s);
    }

    private TestApp app(Auth.Builder b) {
        adapter = new InMemoryHttpAdapter();
        TestApp t = TestApp.create(adapter);
        auth = b.build();
        auth.install(t.app());
        return t;
    }

    private ClientResponse get(String path, String... headers) {
        ClientRequest r = ClientRequest.of("GET", path);
        for (int i = 0; i < headers.length; i += 2) {
            r.header(headers[i], headers[i + 1]);
        }
        return adapter.send(r);
    }

    private List<String> warns() {
        List<String> out = new ArrayList<>();
        for (String l : logger.lines) {
            if (l.startsWith("WARN ")) {
                out.add(l);
            }
        }
        return out;
    }

    // SEC-AUTH-006 AC-2: case, repeated slashes, percent escapes, dot segments and an invalid escape are all judged
    // protected under protectOnly("/api")
    @Test
    public void SEC_AUTH_006_AC2_widerNormalizationClosesTheRange() throws Exception {
        TestApp t = app(builder().protectOnly("/api"));
        t.app().get("/*rest", (req, res) -> res.send("reached " + req.path()));
        t.start();
        assertEquals(200, get("/public/page").status());
        for (String p : new String[] {"/API/users", "//api/users", "/%61pi/users", "/api/../x", "/api/%2e%2e/x",
            "/x/./y", "/x/%zz/y", "/x/%4"}) {
            ClientResponse r = get(p);
            assertEquals(p + " -> " + r.text(), 401, r.status());
            assertEquals(p, "unauthenticated", AuthTestSupport.code(r));
        }
    }

    /** A protectOnly prefix written with %XX escapes is judged decoded, and one that cannot be normalized is refused. */
    @Test
    public void escapedPrefixesAreNormalizedOrRefused() throws Exception {
        TestApp t = app(builder().protectOnly("/%61pi"));
        t.app().get("/*rest", (req, res) -> res.send("reached"));
        t.start();
        assertEquals(401, get("/api/users").status());
        assertEquals(401, get("/%61pi/users").status());
        assertEquals(200, get("/public").status());
        for (String bad : new String[] {"/a/../b", "/x%zz"}) {
            try {
                builder().protectOnly(bad).build();
                fail("accepted .protectOnly(" + bad + ")");
            } catch (AuthConfigException e) {
                assertTrue(e.getMessage(), e.getMessage().contains(".protectOnly"));
            }
        }
    }

    // SEC-AUTH-006 AC-3: with protectOnly a regex or array path before start fails the start with a doctor FAIL;
    // after start a regex route on the app, on a mounted router, and a router mount holding one throw
    // IllegalStateException and are not served
    @Test
    public void SEC_AUTH_006_AC3_unjudgeablePathsAreRefused() throws Exception {
        TestApp before = app(builder().protectOnly("/api"));
        before.app().get(Pattern.compile("^/x"), (req, res) -> res.send("x"));
        try {
            before.start();
            fail("a regex route under protectOnly started");
        } catch (RuntimeException e) {
            String m = e.getMessage() + (e.getCause() == null ? "" : e.getCause().getMessage());
            assertTrue(m, m.contains("FAIL protectOnly range"));
        }
        TestApp arrays = app(builder().protectOnly("/api"));
        Router ar = Router.create();
        ar.get(new Object[] {"/a", "/b"}, (req, res) -> res.send("ab"));
        arrays.app().use("/r", ar);
        try {
            arrays.start();
            fail("an array route under protectOnly started");
        } catch (RuntimeException e) {
            String m = e.getMessage() + (e.getCause() == null ? "" : e.getCause().getMessage());
            assertTrue(m, m.contains("FAIL protectOnly range"));
        }

        TestApp t = app(builder().protectOnly("/api"));
        Router r = Router.create();
        t.app().use("/api", r);
        t.start();
        String token = auth.accessToken(aliceId, 0, 900_000L);
        try {
            t.app().get(Pattern.compile("^/x"), (req, res) -> res.send("x"));
            fail("app.get(Pattern) after start was accepted");
        } catch (IllegalStateException expected) {
            // vetoed by the registration watcher
        }
        assertEquals(404, get("/x", "Authorization", "Bearer " + token).status());
        try {
            r.get(Pattern.compile("^/x"), (req, res) -> res.send("rx"));
            fail("a regex route on a mounted router after start was accepted");
        } catch (IllegalStateException expected) {
            // vetoed
        }
        assertEquals(404, get("/api/x", "Authorization", "Bearer " + token).status());
        Router r2 = Router.create();
        r2.get(Pattern.compile("^/x"), (req, res) -> res.send("r2x"));
        try {
            t.app().use("/api2", r2);
            fail("mounting a router holding a regex route after start was accepted");
        } catch (IllegalStateException expected) {
            // vetoed
        }
        assertEquals(404, get("/api2/x", "Authorization", "Bearer " + token).status());
    }

    // SEC-AUTH-006 AC-3 (M2-F09): a regex registration made while auth's onStart runs (after the doctor, before the
    // range is closed) is refused like one after start; it neither slips past the doctor nor gets served
    @Test(timeout = 30000)
    public void SEC_AUTH_006_AC3_registrationRacingOnStartIsRefused() throws Exception {
        java.util.concurrent.CountDownLatch inSeed = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        InMemoryUserStore base = new InMemoryUserStore();
        com.snoworca.naruserve.utils.auth.user.UserStore blocking =
                new com.snoworca.naruserve.utils.auth.user.ForwardingUserStore(base) {
                    @Override
                    public AuthUser create(AuthUser user) throws Exception {
                        if ("seeded".equals(user.getUsername())) {
                            inSeed.countDown();
                            assertTrue("never released", release.await(20, TimeUnit.SECONDS));
                        }
                        return super.create(user);
                    }
                };
        logger = new RecordingLogger();
        TestApp t = app(Auth.builder(AuthTestSupport.SECRET).hashIterations(1000).logger(logger).users(blocking)
                .protectOnly("/api").user("seeded", "correct horse battery"));
        java.util.concurrent.atomic.AtomicReference<Throwable> startError =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread starter = new Thread(() -> {
            try {
                t.start();
            } catch (Throwable e) {
                startError.set(e);
            }
        }, "racing-starter");
        starter.setDaemon(true);
        starter.start();
        assertTrue("the seed window was not reached", inSeed.await(20, TimeUnit.SECONDS));
        String outcome;
        try {
            t.app().get(Pattern.compile("^/raced$"), (req, res) -> res.send("raced"));
            outcome = "registered";
        } catch (IllegalStateException e) {
            outcome = "refused";
        } finally {
            release.countDown();
        }
        starter.join(20_000);
        assertFalse("start did not finish", starter.isAlive());
        assertEquals("a Pattern registration during auth's onStart escaped protectOnly", "refused", outcome);
        if (startError.get() == null) {
            assertEquals(404, get("/raced").status());
        }
        t.app().shutdown(5, TimeUnit.SECONDS);
    }

    // SEC-AUTH-006 AC-4: the doctor WARNs for routes and WebSocket routes outside the range; describe() and the
    // startup log show the protected range including the mount path
    @Test
    public void SEC_AUTH_006_AC4_rangeIsReportedAndOutsideRoutesWarned() throws Exception {
        TestApp t = app(builder().protectOnly("/api"));
        t.app().get("/api/users", (req, res) -> res.send("in"));
        t.app().get("/blog", (req, res) -> res.send("out"));
        t.app().ws("/chat", (req, ws) -> { });
        t.start();
        assertTrue(auth.describe(), auth.describe().contains("protected: /api, /auth"));
        boolean logged = false;
        for (String l : logger.lines) {
            logged |= l.startsWith("INFO ") && l.contains("protected: /api, /auth");
        }
        assertTrue(logger.lines.toString(), logged);
        String w = warns().toString();
        assertTrue(w, w.contains("GET /blog") && w.contains("outside the protected range"));
        assertTrue(w, w.contains("WEBSOCKET /chat"));
        assertFalse(w, w.contains("GET /api/users"));

        TestApp t2 = app(builder().protectOnly("/app").mountPath("/api/auth"));
        t2.start();
        boolean logged2 = false;
        for (String l : logger.lines) {
            logged2 |= l.startsWith("INFO ") && l.contains("protected: /app, /api/auth");
        }
        assertTrue(logger.lines.toString(), logged2);
        assertTrue(auth.describe(), auth.describe().contains("protected: /app, /api/auth"));
        assertFalse(auth.describe(), auth.describe().contains("/auth,"));
    }

    // SEC-AUTH-006 AC-5: an upgrade signalled on a second Connection header line, or an h2c upgrade, is protected
    // even on a public path
    @Test
    public void SEC_AUTH_006_AC5_upgradesOnPublicPathsAreProtected() throws Exception {
        TestApp t = app(builder().protectOnly("/api"));
        t.app().get("/page", (req, res) -> res.send("page"));
        t.start();
        assertEquals(200, get("/page").status());
        assertEquals(200, get("/page", "Connection", "keep-alive").status());
        ClientResponse split = get("/page", "Connection", "keep-alive", "Connection", "Upgrade",
                "Upgrade", "websocket");
        assertEquals(401, split.status());
        ClientResponse h2c = get("/page", "Connection", "Upgrade, HTTP2-Settings", "Upgrade", "h2c",
                "HTTP2-Settings", "AAMAAABkAARAAAAAAAIAAAAA");
        assertEquals(401, h2c.status());
        assertEquals("unauthenticated", AuthTestSupport.code(h2c));
    }

    // SEC-AUTH-006 AC-6: a WebSocket upgrade outside the range gets 401 before 101; .permitWebSocket("/chat") opens it
    @Test(timeout = 20000)
    public void SEC_AUTH_006_AC6_websocketUpgradesNeedPermitWebSocket() throws Exception {
        TestApp t = app(builder().protectOnly("/api"));
        final boolean[] opened = new boolean[1]; // read after the handshake joined
        t.app().ws("/chat", (req, ws) -> opened[0] = true);
        t.start();
        ClientResponse r = adapter.send(ClientRequest.upgrade("/chat"));
        assertEquals(401, r.status());
        assertFalse(r.upgradeHandedOver());
        assertFalse(opened[0]);

        TestApp open = app(builder().protectOnly("/api").permitWebSocket("/chat"));
        open.app().ws("/chat", (req, ws) -> opened[0] = true);
        open.app().ws("/other", (req, ws) -> opened[0] = true);
        open.start();
        Future<ClientResponse> f = adapter.submit(ClientRequest.upgrade("/chat"));
        InMemoryWebSocketChannel ch = adapter.nextChannel(5, TimeUnit.SECONDS);
        ch.clientClose(1000, "");
        assertEquals(101, f.get(5, TimeUnit.SECONDS).status());
        assertTrue(opened[0]);
        opened[0] = false;
        assertEquals(401, adapter.send(ClientRequest.upgrade("/other")).status());
        assertFalse(opened[0]);
        // compared with the decoded WebSocket req.path(): /%63hat is /chat, /%63hat2 decodes to another path
        Future<ClientResponse> encoded = adapter.submit(ClientRequest.upgrade("/%63hat"));
        InMemoryWebSocketChannel ch2 = adapter.nextChannel(5, TimeUnit.SECONDS);
        ch2.clientClose(1000, "");
        assertEquals(101, encoded.get(5, TimeUnit.SECONDS).status());
        assertTrue(opened[0]);
        opened[0] = false;
        assertEquals(401, adapter.send(ClientRequest.upgrade("/%6Fther")).status());
        assertFalse(opened[0]);
        // a permitWebSocket path does not open plain requests or other upgrades to it
        TestApp plain = app(builder().permitWebSocket("/chat"));
        plain.app().get("/chat", (req, res) -> res.send("plain"));
        plain.start();
        assertEquals(401, get("/chat").status());
        assertEquals(401, get("/chat", "Connection", "Upgrade", "Upgrade", "h2c").status());
    }

    // SEC-AUTH-006 AC-8: without protectOnly the watcher vetoes nothing: regex and array paths before and after
    // listen are accepted, start has no FAIL, and a valid token reaches each of them
    @Test
    public void SEC_AUTH_006_AC8_withoutProtectOnlyNothingIsVetoed() throws Exception {
        TestApp t = app(builder());
        t.app().get(Pattern.compile("^/x$"), (req, res) -> res.send("x"));
        Router r = Router.create();
        r.get(new Object[] {"/a", "/b"}, (req, res) -> res.send("ab"));
        t.app().use("/r", r);
        t.start();
        t.app().get(Pattern.compile("^/x2$"), (req, res) -> res.send("x2"));
        r.get(Pattern.compile("^/y$"), (req, res) -> res.send("y"));
        Router r2 = Router.create();
        r2.get(Pattern.compile("^/z$"), (req, res) -> res.send("z"));
        t.app().use("/r2", r2);
        String token = auth.accessToken(aliceId, 0, 900_000L);
        for (String p : new String[] {"/x", "/r/a", "/r/b", "/x2", "/r/y", "/r2/z"}) {
            assertEquals(p, 200, get(p, "Authorization", "Bearer " + token).status());
            assertEquals(p, 401, get(p).status());
        }
        for (String l : logger.lines) {
            assertFalse(l, l.contains("FAIL"));
        }
    }
}
