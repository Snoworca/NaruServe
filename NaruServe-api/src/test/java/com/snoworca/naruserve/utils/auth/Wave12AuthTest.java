package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.utils.auth.user.AuthUser;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.After;
import org.junit.Test;

/**
 * The event contexts outside user changes and the register-only creation policy (FR-AUTH-008), how auth answers its
 * 400, 415, 404 and 503 errors (IR-AUTH-003), and the release checks of the auth module: the Java 8 test run
 * (CON-AUTH-007) and the jar size record (CON-AUTH-001). Over the real endpoints of the in-memory adapter.
 */
public class Wave12AuthTest {

    private final W8 f = new W8();

    @After
    public void stop() {
        f.stop();
    }

    /** Records the origin of every non-user-change callback and refuses REGISTER creations. */
    static final class Origins implements AuthEvents {
        final List<String> seen = new CopyOnWriteArrayList<>();

        private void note(String name, AuthEventContext ctx) {
            seen.add(name + "=" + ctx.getOrigin());
        }

        @Override
        public void beforeLogin(AuthUser user, AuthEventContext ctx) {
            note("beforeLogin", ctx);
        }

        @Override
        public void onLogin(AuthUser user, AuthEventContext ctx) {
            note("onLogin", ctx);
        }

        @Override
        public void onTokenRefreshed(AuthUser user, AuthEventContext ctx) {
            note("onTokenRefreshed", ctx);
        }

        @Override
        public void onRefreshTokenReused(String userId, String familyId, AuthEventContext ctx) {
            note("onRefreshTokenReused", ctx);
        }

        @Override
        public void onLogout(String userId, boolean allDevices, AuthEventContext ctx) {
            note("onLogout", ctx);
        }

        @Override
        public void beforeUserCreate(AuthUser candidate, AuthEventContext ctx) {
            if (ctx.getOrigin() == UserOrigin.REGISTER) {
                throw new AuthRejection(403, "registration_closed", "sign-up is closed");
            }
        }
    }

    // FR-AUTH-008 AC-5 (AuthEvents has no group-change callback): the origin is
    // null in beforeLogin, onLogin, onTokenRefreshed, onRefreshTokenReused and onLogout; a beforeUserCreate policy
    // testing origin == REGISTER blocks register and lets the admin create (A2) the same user
    @Test
    public void originIsNullOutsideUserChanges() throws Exception {
        Origins events = new Origins();
        String root = f.user("root", W8.PW, "admin");
        f.user("alice", W8.PW, "reader");
        f.start(b -> b.events(events).register(true));
        Map<String, Object> login = f.loginBody("alice", W8.PW);
        String rt = (String) login.get("refreshToken");
        assertEquals(200, f.post("/auth/refresh", "{\"refreshToken\":\"" + rt + "\"}").status());
        f.clock.addAndGet(120_000L); // past the reuse grace: the second use is a reuse
        f.post("/auth/refresh", "{\"refreshToken\":\"" + rt + "\"}");
        Map<String, Object> again = f.loginBody("alice", W8.PW);
        assertEquals(204, f.post("/auth/logout", null, "Authorization", "Bearer " + again.get("accessToken")).status());
        for (String name : new String[] {"beforeLogin", "onLogin", "onTokenRefreshed", "onRefreshTokenReused",
            "onLogout"}) {
            assertTrue(events.seen.toString(), events.seen.contains(name + "=null"));
        }
        for (String s : events.seen) {
            assertTrue(s, s.endsWith("=null"));
        }
        ClientResponse reg = f.post("/auth/register", W8.login("carol", "another long password"));
        assertEquals(reg.text(), 403, reg.status());
        assertEquals("registration_closed", W8.code(reg));
        assertNull(f.store.findByUsername("carol"));
        ClientResponse a2 = f.post("/auth/admin/users", "{\"username\":\"carol\",\"password\":\"another long "
                + "password\",\"roles\":[\"reader\"]}", "Authorization", "Bearer " + f.auth.accessToken(root,
                f.stored(root).getTokenVersion(), 60_000L));
        assertEquals(a2.text(), 201, a2.status());
        assertNotNull(f.store.findByUsername("carol"));
    }

    // IR-AUTH-003 AC-4: input validation is a Core HttpException 400
    // BAD_REQUEST rendered by the auth router's error middleware (the app error handler never sees it); a non-JSON auth POST body is 415
    // UNSUPPORTED_MEDIA_TYPE in the JSON error body, answered by the auth middleware before any handler or error
    // handler runs; auth 404s are NOT_FOUND (the 503 SERVICE_UNAVAILABLE answers are tested in Wave12ApiKeyTest)
    @Test
    public void errorAnswersAndWhoRaisesThem() throws Exception {
        final List<Throwable> handled = new CopyOnWriteArrayList<>();
        f.user("alice", W8.PW, "reader");
        f.errors = h -> (err, req, res) -> {
            handled.add(err);
            h.handle(err, req, res);
        };
        f.start(b -> b);
        ClientResponse bad = f.post("/auth/login", "{\"username\":7,\"password\":\"x\"}");
        assertEquals(bad.text(), 400, bad.status());
        assertEquals("BAD_REQUEST", W8.code(bad));
        assertTrue("the auth router's own error middleware renders the Core HttpException", handled.isEmpty());
        ClientRequest text = ClientRequest.of("POST", "/auth/login").header("Content-Type", "text/plain")
                .header("Content-Length", "5").body("hello".getBytes(StandardCharsets.UTF_8));
        ClientResponse unsupported = f.adapter.send(text);
        assertEquals(415, unsupported.status());
        assertEquals("UNSUPPORTED_MEDIA_TYPE", W8.code(unsupported));
        assertNotNull(W8.object(unsupported).get("error"));
        assertTrue("answered before any error handler", handled.isEmpty());
        String root = f.user("root", W8.PW, "admin");
        ClientResponse missing = f.get("/auth/admin/users/no-such-user", "Authorization", "Bearer "
                + f.auth.accessToken(root, 0, 60_000L));
        assertEquals(404, missing.status());
        assertEquals("NOT_FOUND", W8.code(missing));
    }

    // CON-AUTH-007 AC-3 (the per-run anchor only; the evidence is the two recorded build runs, Java 17 ./gradlew build
    // and the actual Java 8 run under -I gradle/java8-tests.gradle, which refuses a home that is not Java 8): the auth
    // facade works on whichever runtime Gradle forked for the tests
    @Test
    public void CON_AUTH_007_AC3_theAuthSuiteRunsOnTheForkedRuntime() throws Exception {
        String spec = System.getProperty("java.specification.version");
        assertTrue(spec, spec.equals("1.8") || Integer.parseInt(spec.startsWith("1.") ? spec.substring(2) : spec) >= 17);
        File script = new File("../gradle/java8-tests.gradle");
        String text = new String(Files.readAllBytes(script.toPath()), StandardCharsets.UTF_8);
        assertTrue(text, text.contains("naruserve.java8.home") && text.contains("must identify a Java 8 JDK"));
        f.user("alice", W8.PW, "reader");
        f.start(b -> b);
        String at = (String) f.loginBody("alice", W8.PW).get("accessToken");
        assertEquals(200, f.get("/api/x", "Authorization", "Bearer " + at).status());
    }

    // CON-AUTH-001 AC-5: the completed-work record of 0.11.0 states the measured NaruServe-api jar size before (0.10.0)
    // and after the auth module, in bytes
    @Test
    public void CON_AUTH_001_AC5_jarSizeIsRecorded() throws Exception {
        String log = new String(Files.readAllBytes(new File("../docs/spec/91.completed-work-log.md").toPath()),
                StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("NaruServe-api jar size: (\\d+) bytes before the auth module \\(0\\.10\\.0\\), "
                + "(\\d+) bytes after").matcher(log);
        assertTrue("no jar size record in the completed-work log", m.find());
        long before = Long.parseLong(m.group(1));
        long after = Long.parseLong(m.group(2));
        assertTrue(before + " -> " + after, before > 0 && after > before);
    }
}
