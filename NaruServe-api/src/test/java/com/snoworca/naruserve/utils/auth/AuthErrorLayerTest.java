package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.ErrorMiddleware;
import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.Router;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.testing.TestApp;
import com.snoworca.naruserve.utils.auth.user.DuplicateUserException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/** auth.errors() and auth.errorMiddleware() (IR-AUTH-002) and the exceptions they answer. */
public class AuthErrorLayerTest {

    private static final String CRED = AuthTestSupport.HEADER;

    /** How the error layer is attached in one run of the six cases. */
    private enum Layer { ON_ERROR, MIDDLEWARE_BEFORE_LISTEN, MIDDLEWARE_AFTER_LISTEN }

    private RecordingLogger log;

    private Auth auth(RecordingLogger logger) {
        log = logger;
        return AuthTestSupport.builder(new AuthTestSupport.TestCredentials(), logger).permitPrefix("/pub")
                .users(new CarriedAcceptanceTest.DroppingStore()).build();
    }

    /** The six app-handler failures of AC-1, each on its own path. */
    private static void sixRoutes(RestApp app, final Auth auth, String prefix) {
        app.get(prefix + "/dup", (req, res) -> {
            throw new DuplicateUserException("the username is taken");
        });
        app.get(prefix + "/down", (req, res) -> {
            throw new AuthUnavailableException("store down");
        });
        app.get(prefix + "/conflict", (req, res) -> {
            auth.userStore().setEnabled("1", false); // its update() silently does not apply
            res.send("not reached");
        });
        app.get(prefix + "/arg", (req, res) -> {
            auth.users().create("svc", (char[]) null, (com.snoworca.naruserve.utils.auth.user.UserMutator) null); // no password and not a service account: the auth IAE subclass
            res.send("not reached");
        });
        app.get(prefix + "/reject", (req, res) -> {
            throw new AuthRejection(422, "domain_not_allowed");
        });
        app.get("/pub" + prefix, (req, res) -> res.send(auth.user(req).getId())); // public: no principal
    }

    private static void sixAnswers(TestApp t, String prefix) {
        String[] h = {CRED, "1:0"};
        ClientResponse dup = t.send("GET", prefix + "/dup", h);
        assertEquals(dup.text(), 409, dup.status());
        assertEquals("duplicate_user", AuthTestSupport.code(dup));
        ClientResponse down = t.send("GET", prefix + "/down", h);
        assertEquals(503, down.status());
        assertEquals("SERVICE_UNAVAILABLE", AuthTestSupport.code(down));
        ClientResponse conflict = t.send("GET", prefix + "/conflict", h);
        assertEquals(conflict.text(), 503, conflict.status());
        assertEquals("security_write_conflict", AuthTestSupport.code(conflict));
        ClientResponse arg = t.send("GET", prefix + "/arg", h);
        assertEquals(arg.text(), 400, arg.status());
        assertEquals("BAD_REQUEST", AuthTestSupport.code(arg));
        ClientResponse reject = t.send("GET", prefix + "/reject", h);
        assertEquals(422, reject.status());
        assertEquals("domain_not_allowed", AuthTestSupport.code(reject));
        ClientResponse state = t.send("GET", "/pub" + prefix);
        assertEquals(state.text(), 500, state.status());
        assertEquals("INTERNAL_ERROR", AuthTestSupport.code(state));
        assertFalse(state.text(), state.text().contains("found no principal"));
        assertTrue(state.header("Content-Type").startsWith("application/json"));
        for (ClientResponse r : new ClientResponse[] {dup, down, conflict, arg, reject, state}) {
            assertTrue(r.text(), r.text().startsWith("{\"error\":{\"code\":"));
            assertFalse(r.text(), r.text().contains("Exception"));
        }
    }

    private TestApp sixCaseApp(Layer layer) {
        final Auth auth = auth(new RecordingLogger());
        TestApp t = TestApp.create();
        auth.install(t.app());
        t.app().get("/pubcheck", (req, res) -> res.send("ok")); // keeps a route before the layer in every layout
        if (layer == Layer.MIDDLEWARE_AFTER_LISTEN) {
            t.start();
            sixRoutes(t.app(), auth, "/late");
            t.app().use(auth.errorMiddleware());
            return t;
        }
        sixRoutes(t.app(), auth, "/late");
        if (layer == Layer.ON_ERROR) {
            t.app().onError(auth.errors());
        } else {
            t.app().use(auth.errorMiddleware());
        }
        return t;
    }

    // IR-AUTH-002 AC-1: the six exceptions through onError(errors()), use(errorMiddleware()) before listen, and a route
    // batch added after listen followed by use(errorMiddleware())
    // IR-AUTH-004 AC-1: auth.user(req) without a principal throws AuthStateException, which auth.errors() turns into
    // 500 JSON without the exception message
    // FR-IAM-001 AC-1: DuplicateUserException from an app route after app.onError(auth.errors()) -> 409 Core JSON shape
    @Test
    public void IR_AUTH_002_AC1_IR_AUTH_004_AC1_FR_IAM_001_AC1_sixExceptionsThroughEveryLayer() {
        for (Layer layer : Layer.values()) {
            sixAnswers(sixCaseApp(layer).start(), "/late");
        }
    }

    // IR-AUTH-002 AC-2: retryAfterSeconds 30 -> Retry-After: 30 through errors(), errorMiddleware() and the auth router
    // IR-AUTH-003 AC-2: a null-message rejection gets a non-empty default message; retryAfterSeconds is Retry-After
    @Test
    public void IR_AUTH_002_AC2_IR_AUTH_003_AC2_retryAfterThroughEveryLayer() {
        Auth auth = auth(new RecordingLogger());
        TestApp t = TestApp.create();
        auth.routes().get("/slow", (req, res) -> {
            throw new AuthRejection(429, "too_many_attempts", null, 30L);
        });
        auth.install(t.app());
        t.app().get("/a", (req, res) -> {
            throw new AuthRejection(429, "too_many_attempts", null, 30L);
        });
        t.app().get("/b", (req, res) -> {
            throw new AuthRejection(422, "domain_not_allowed");
        });
        t.app().onError(auth.errors());
        t.start();
        Router late = Router.create();
        late.get("/c", (req, res) -> {
            throw new AuthRejection(429, "too_many_attempts", null, 30L);
        });
        t.app().use(late);
        t.app().use(auth.errorMiddleware());
        for (String path : new String[] {"/a", "/c", "/auth/slow"}) {
            ClientResponse r = t.send("GET", path, CRED, "1:0");
            assertEquals(path + " " + r.text(), 429, r.status());
            assertEquals(path, "30", r.header("Retry-After"));
            assertEquals("too_many_attempts", AuthTestSupport.code(r));
            assertFalse(r.text(), r.text().contains("\"message\":\"\""));
        }
        ClientResponse plain = t.send("GET", "/b", CRED, "1:0");
        assertNull(plain.header("Retry-After"));
        assertTrue(plain.text(), plain.text().contains("\"message\":\"Unprocessable Content\""));
    }

    // IR-AUTH-002 AC-3: other exceptions pass to the next error layer unchanged, through errors() and errorMiddleware(),
    // before listen and for a route batch added after listen
    @Test
    public void IR_AUTH_002_AC3_otherExceptionsPassUnchanged() {
        final RuntimeException own = new RuntimeException("app failure");
        final IllegalArgumentException plain = new IllegalArgumentException("not from auth");
        for (Layer layer : Layer.values()) {
            final AtomicReference<Throwable> seen = new AtomicReference<>();
            final Auth auth = auth(new RecordingLogger());
            TestApp t = TestApp.create();
            auth.install(t.app());
            if (layer == Layer.MIDDLEWARE_AFTER_LISTEN) {
                t.start();
            }
            t.app().get("/own", (req, res) -> {
                throw own;
            });
            t.app().get("/iae", (req, res) -> {
                throw plain;
            });
            if (layer == Layer.ON_ERROR) {
                t.app().onError(auth.errors());
                t.app().onError((err, req, res) -> {
                    seen.set(err);
                    res.status(599).send("next");
                });
            } else {
                t.app().use(auth.errorMiddleware());
                t.app().use((ErrorMiddleware) (err, req, res, next) -> {
                    seen.set(err);
                    res.status(599).send("next");
                });
            }
            for (Object[] c : new Object[][] {{"/own", own}, {"/iae", plain}}) {
                seen.set(null);
                ClientResponse r = t.send("GET", (String) c[0], CRED, "1:0");
                assertEquals(layer + " " + c[0], 599, r.status());
                assertEquals("next", r.text());
                assertSame(c[1], seen.get());
            }
        }
    }

    // IR-AUTH-002 AC-4: the same instance per Auth; use(errorMiddleware()) is accepted after start, onError is not
    @Test
    public void IR_AUTH_002_AC4_sameInstancesAndLateRegistration() {
        Auth auth = auth(new RecordingLogger());
        assertSame(auth.errors(), auth.errors());
        assertSame(auth.errorMiddleware(), auth.errorMiddleware());
        TestApp t = TestApp.create();
        auth.install(t.app());
        t.start();
        t.app().use(auth.errorMiddleware());
        try {
            t.app().onError(auth.errors());
            fail("Core accepted onError after start");
        } catch (IllegalStateException expected) {
            // Core: onError is configured before listen
        }
    }

    private static List<String> layerWarns(RecordingLogger log) {
        List<String> out = new ArrayList<>();
        for (String l : new ArrayList<>(log.lines)) {
            if (l.startsWith("WARN ") && l.contains("after the last auth error layer")) {
                out.add(l);
            }
        }
        return out;
    }

    private static List<String> doctorLayerWarns(RecordingLogger log) {
        List<String> out = new ArrayList<>();
        for (String l : layerWarns(log)) {
            if (l.contains("auth doctor WARN errors placement")) {
                out.add(l);
            }
        }
        return out;
    }

    // IR-AUTH-002 AC-5: the request-time WARN: none on the first request (start appended the layer), after a later batch
    // without a layer one, none
    // once errorMiddleware() follows, at most once a minute, and routes added to a mounted router count at the mount
    @Test
    public void IR_AUTH_002_AC5_requestTimeWarn() {
        final AtomicLong now = new AtomicLong(1_000_000L);
        RecordingLogger logA = new RecordingLogger();
        Auth a = AuthTestSupport.builder(new AuthTestSupport.TestCredentials(), logA).timeSource(now::get).build();
        TestApp ta = TestApp.create();
        a.install(ta.app());
        ta.app().get("/x", (req, res) -> res.send("x"));
        ta.start();
        assertEquals(1, doctorLayerWarns(logA).size());
        ta.send("GET", "/x", CRED, "1:0");
        // OPS-AUTH-012 AC-1: start appended auth.errorMiddleware(), so the first request finds the layer: doctor only
        assertEquals(logA.lines.toString(), 1, layerWarns(logA).size());
        ta.send("GET", "/x", CRED, "1:0");
        assertEquals(1, layerWarns(logA).size());

        RecordingLogger logB = new RecordingLogger();
        Auth b = AuthTestSupport.builder(new AuthTestSupport.TestCredentials(), logB).timeSource(now::get).build();
        TestApp tb = TestApp.create();
        b.install(tb.app());
        tb.app().get("/x", (req, res) -> res.send("x"));
        tb.app().onError(b.errors());
        tb.start();
        tb.send("GET", "/x", CRED, "1:0");
        assertEquals(logB.lines.toString(), 0, layerWarns(logB).size());
        tb.app().get("/later", (req, res) -> res.send("later"));
        tb.send("GET", "/x", CRED, "1:0");
        assertEquals(1, layerWarns(logB).size());
        tb.app().use(b.errorMiddleware());
        now.addAndGet(120_000L);
        tb.send("GET", "/x", CRED, "1:0");
        assertEquals(1, layerWarns(logB).size());
        // a repeated mistake within a minute stays quiet; after a minute it WARNs again
        tb.app().get("/again", (req, res) -> res.send("again"));
        tb.send("GET", "/x", CRED, "1:0");
        assertEquals(2, layerWarns(logB).size());
        tb.app().get("/again2", (req, res) -> res.send("again"));
        now.addAndGet(10_000L);
        tb.send("GET", "/x", CRED, "1:0");
        assertEquals(2, layerWarns(logB).size());
        tb.app().get("/again3", (req, res) -> res.send("again"));
        now.addAndGet(60_000L);
        tb.send("GET", "/x", CRED, "1:0");
        assertEquals(3, layerWarns(logB).size());
        // a mistake suppressed within the minute is reported after it, without a further registration
        tb.app().get("/again4", (req, res) -> res.send("again"));
        now.addAndGet(5_000L);
        tb.send("GET", "/x", CRED, "1:0");
        assertEquals(3, layerWarns(logB).size());
        now.addAndGet(60_000L);
        tb.send("GET", "/x", CRED, "1:0");
        assertEquals(4, layerWarns(logB).size());
        tb.send("GET", "/x", CRED, "1:0");
        assertEquals(4, layerWarns(logB).size());

        // a route added later to a router mounted before the layer counts at the mount: no WARN
        RecordingLogger logC = new RecordingLogger();
        Auth c = AuthTestSupport.builder(new AuthTestSupport.TestCredentials(), logC).timeSource(now::get)
                .statelessAccess(true).build();
        TestApp tc = TestApp.create();
        c.install(tc.app());
        Router api = Router.create();
        api.get("/one", (req, res) -> res.send("one"));
        tc.app().use("/api", api);
        tc.app().onError(c.errors());
        tc.start();
        api.get("/two", (req, res) -> res.send("two"));
        ClientResponse two = tc.send("GET", "/api/two", CRED, "1:0");
        assertEquals(200, two.status());
        assertEquals(logC.lines.toString(), 0, layerWarns(logC).size());
    }

    // IR-AUTH-002 AC-6: doctor WARNs unless the very errors()/errorMiddleware() object follows the last ROUTE; an app's
    // own onError there still WARNs; onError(auth.errors()) is recognised through Core's wrapper
    @Test
    public void IR_AUTH_002_AC6_doctorPlacementWarn() {
        RecordingLogger own = new RecordingLogger();
        Auth a = AuthTestSupport.builder(new AuthTestSupport.TestCredentials(), own).build();
        TestApp ta = TestApp.create();
        a.install(ta.app());
        ta.app().get("/x", (req, res) -> res.send("x"));
        ta.app().onError((err, req, res) -> res.status(500).send("mine"));
        ta.start();
        assertEquals(1, doctorLayerWarns(own).size());

        for (boolean middleware : new boolean[] {false, true}) {
            RecordingLogger log = new RecordingLogger();
            Auth b = AuthTestSupport.builder(new AuthTestSupport.TestCredentials(), log).build();
            TestApp tb = TestApp.create();
            b.install(tb.app());
            tb.app().get("/x", (req, res) -> res.send("x"));
            if (middleware) {
                tb.app().use(b.errorMiddleware());
            } else {
                tb.app().onError(b.errors());
            }
            tb.start();
            assertEquals(log.lines.toString(), 0, doctorLayerWarns(log).size());
        }

        RecordingLogger other = new RecordingLogger();
        Auth c = AuthTestSupport.builder(new AuthTestSupport.TestCredentials(), other).build();
        Auth stranger = AuthTestSupport.builder().build();
        TestApp tc = TestApp.create();
        c.install(tc.app());
        tc.app().get("/x", (req, res) -> res.send("x"));
        tc.app().onError(stranger.errors()); // another Auth's handler is not this auth's layer
        tc.start();
        assertEquals(1, doctorLayerWarns(other).size());

        // an error middleware on a path covers only that path, not a route outside it
        RecordingLogger scoped = new RecordingLogger();
        Auth d = AuthTestSupport.builder(new AuthTestSupport.TestCredentials(), scoped).build();
        TestApp td = TestApp.create();
        d.install(td.app());
        td.app().get("/x", (req, res) -> res.send("x"));
        td.app().use("/api", d.errorMiddleware());
        td.start();
        assertEquals(1, doctorLayerWarns(scoped).size());
    }
}
