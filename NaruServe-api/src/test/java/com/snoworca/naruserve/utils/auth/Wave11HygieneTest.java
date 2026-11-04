package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.ErrorMiddleware;
import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.utils.auth.apikey.InMemoryApiKeyStore;
import com.snoworca.naruserve.utils.auth.apikey.IssuedApiKey;
import com.snoworca.naruserve.utils.auth.audit.AuditEvent;
import com.snoworca.naruserve.utils.auth.password.Pbkdf2PasswordHasher;
import com.snoworca.naruserve.utils.auth.rbac.RolePolicy;
import com.snoworca.naruserve.utils.auth.user.AuthUser;
import com.snoworca.naruserve.utils.auth.user.InMemoryUserStore;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.junit.After;
import org.junit.Test;

/**
 * The cross-flow marker test of sensitive-value hygiene (SEC-AUTH-008 AC-1): every flow runs with known marker values
 * for each listed secret, and no captured log line, logged exception, exception thrown to the app, audit event,
 * {@code auth.describe()} output or {@code toString()} of the builder or the Auth holds a marker, apart from the one
 * startup log line of the one-time setup token or the DEV temporary admin password.
 */
public class Wave11HygieneTest {

    /** A 40-character signing secret (the test asserts the length). */
    static final String SECRET = "mkSECRET-7Qp2Lx9Vr4Tz8Wb1Nc6Ys3Hd5Gf0JaK";
    static final String PW = "mkPW-alice-Horse-Battery-42";
    static final String PW_WRONG = "mkPW-wrong-guess-9876";
    static final String PW_SETUP = "mkPW-setup-root-5555";
    static final String FORGED_TOKEN = "mkAT-forged-0f1e2d3c4b5a69788796a5b4";
    static final String FORGED_KEY = "mkKEY-forged-77aa88bb99cc";
    static final String CK_AT = "mkCK-at-1a2b3c4d5e6f7a8b9c";
    static final String CK_RT = "mkCK-rt-9c8b7a6f5e4d3c2b1a";
    static final String CK_CSRF = "mkCK-csrf-5e5e5e6f6f6f7a7a";
    static final String CK_DEVICE = "mkCK-device-4d4d3c3c2b2b1a1a";
    static final String HOST = "app.test";
    static final String SAME = "https://app.test";

    private final List<RestApp> apps = new CopyOnWriteArrayList<>();

    @After
    public void stop() {
        for (RestApp a : apps) {
            a.shutdown(5, TimeUnit.SECONDS);
        }
    }

    /** One run: an app with auth over the marker secret, capturing every channel the criterion names. */
    final class Run {
        final TokenTransport transport;
        final AuthMode mode;
        final RecordingLogger log = new RecordingLogger();
        final List<AuditEvent> audit = new CopyOnWriteArrayList<>();
        final List<Throwable> thrown = new CopyOnWriteArrayList<>();
        final List<String> configMessages = new CopyOnWriteArrayList<>();
        final InMemoryUserStore store = new InMemoryUserStore();
        final AtomicLong clock = new AtomicLong(W8.T0);
        final Set<String> markers = new LinkedHashSet<>();
        final InMemoryHttpAdapter adapter = new InMemoryHttpAdapter("127.0.0.1", true);
        Auth.Builder builder;
        Auth auth;
        RestApp app;
        int startupLines;
        String alice;

        Run(TokenTransport transport, AuthMode mode) {
            this.transport = transport;
            this.mode = mode;
            for (String m : new String[] {SECRET, PW, PW_WRONG, PW_SETUP, FORGED_TOKEN, FORGED_KEY, CK_AT, CK_RT,
                CK_CSRF, CK_DEVICE}) {
                markers.add(m);
            }
        }

        Auth.Builder base() {
            return Auth.builder(SECRET).mode(mode).transport(transport).hashIterations(1000).logger(log)
                    .timeSource(clock::get).audit(audit::add).apiKeys(new InMemoryApiKeyStore()).users(store)
                    .roles(RolePolicy.builder().role("reader", "posts:read").build());
        }

        Run start(boolean recovery, @Nullable String seedName) throws Exception {
            if (seedName == null) { // the DEV seed run starts over an empty store
                alice = store.create(AuthUser.builder().username("alice")
                        .passwordHash(new Pbkdf2PasswordHasher(1000).hash(PW.toCharArray())).roles("reader").build())
                        .getId();
            }
            builder = base();
            if (recovery) {
                builder.recovery(true);
            }
            if (seedName != null) {
                builder.user(seedName, null, "admin");
            }
            app = RestApp.builder().httpAdapter(adapter).jsonCodec(new AdminFixture.MiniJsonCodec()).logger(log).build();
            apps.add(app);
            auth = builder.build();
            auth.install(app);
            app.get("/api/x", auth.require("posts:read"), (req, res) -> res.send("x:" + auth.user(req).getId()));
            app.post("/api/x", auth.require("posts:read"), (req, res) -> res.status(201).send("posted"));
            app.use((ErrorMiddleware) (err, req, res, next) -> {
                thrown.add(err);
                next.error(err);
            });
            app.onError(auth.errors());
            app.listen(0);
            startupLines = log.lines.size();
            // the configuration errors the app can be thrown: a short marker key and a refused option with the key
            for (Auth.Builder refused : new Auth.Builder[] {Auth.builder("mkSHORT-key-77"),
                Auth.builder(SECRET).accessTtl(5)}) {
                try {
                    refused.build();
                    fail("built");
                } catch (AuthConfigException e) {
                    configMessages.add(String.valueOf(e.getMessage()));
                    thrown.add(e);
                }
            }
            markers.add("mkSHORT-key-77");
            return this;
        }

        boolean cookies() {
            return transport != TokenTransport.HEADER;
        }

        ClientResponse send(String method, String path, @Nullable String json, String... headers) {
            ClientRequest r = json == null ? ClientRequest.of(method, path) : ClientRequest.json(method, path, json);
            if (cookies()) {
                r.header("Host", HOST).header("Origin", SAME);
            }
            for (int i = 0; i < headers.length; i += 2) {
                r.header(headers[i], headers[i + 1]);
            }
            ClientResponse out = adapter.send(r);
            for (Map.Entry<String, String> h : out.headers()) {
                if (h.getKey().equalsIgnoreCase("Set-Cookie")) {
                    for (String n : new String[] {"naru_at", "naru_rt", "naru_csrf", "naru_device"}) {
                        String v = W8.cookieValue(out, n);
                        if (v != null && !v.isEmpty()) {
                            markers.add(v);
                        }
                    }
                }
            }
            Map<String, Object> body;
            try {
                body = W8.object(out);
            } catch (RuntimeException notJson) {
                body = java.util.Collections.emptyMap();
            }
            for (String k : new String[] {"accessToken", "refreshToken", "deviceToken", "csrfToken", "key"}) {
                Object v = body.get(k);
                if (v instanceof String && ((String) v).length() > 8) {
                    markers.add((String) v);
                }
            }
            return out;
        }

        /** Every text the criterion lists, each labelled with its channel. */
        List<String[]> texts() {
            List<String[]> out = new ArrayList<>();
            synchronized (log.lines) {
                for (int i = 0; i < log.lines.size(); i++) {
                    out.add(new String[] {(i < startupLines ? "startup log #" : "log #") + i, log.lines.get(i)});
                }
            }
            for (Throwable t : log.errors) {
                out.add(new String[] {"logged exception", stack(t)});
            }
            for (Throwable t : thrown) {
                out.add(new String[] {"exception thrown to the app", stack(t)});
            }
            for (String m : configMessages) {
                out.add(new String[] {"AuthConfigException", m});
            }
            for (AuditEvent e : audit) {
                out.add(new String[] {"audit event", e.toString() + " " + e.getDetails() + " " + e.getReason() + " "
                        + e.getActorId() + " " + e.getTargetId() + " " + e.getAction() + " " + e.getSourceIp()});
            }
            out.add(new String[] {"describe()", auth.describe()});
            out.add(new String[] {"Auth.toString()", auth.toString()});
            out.add(new String[] {"Builder.toString()", builder.toString()});
            return out;
        }

        /** Asserts the criterion; {@code carvedOut} values may appear only in exactly one startup log line. */
        void assertClean(String... carvedOut) {
            Set<String> carved = new LinkedHashSet<>();
            for (String c : carvedOut) {
                carved.add(c);
            }
            List<String[]> texts = texts();
            for (String m : markers) {
                if (carved.contains(m)) {
                    continue;
                }
                for (String[] t : texts) {
                    assertTrue(transport + "/" + mode + ": a marker in " + t[0] + ": " + t[1],
                            !t[1].contains(m));
                }
            }
            for (String c : carved) {
                int startup = 0;
                for (String[] t : texts) {
                    if (t[1].contains(c)) {
                        assertTrue(transport + "/" + mode + ": the one-time value outside the startup log, in " + t[0]
                                + ": " + t[1], t[0].startsWith("startup log #"));
                        startup++;
                    }
                }
                assertEquals(transport + "/" + mode + ": startup lines holding the one-time value", 1, startup);
            }
        }
    }

    private static String stack(Throwable t) {
        StringWriter w = new StringWriter();
        t.printStackTrace(new PrintWriter(w));
        StringBuilder b = new StringBuilder(w.toString());
        for (Throwable c = t; c != null; c = c.getCause()) {
            b.append(' ').append(c.toString());
        }
        return b.toString();
    }

    private static String setupBody(String token) {
        return "{\"token\":\"" + token + "\",\"username\":\"root\",\"password\":\"" + PW_SETUP + "\"}";
    }

    /** The flows of one run in HEADER, COOKIE or BOTH; returns the one-time setup token it drove. */
    private String drive(Run r) throws Exception {
        String setup = r.auth.pendingSetupToken();
        assertNotNull("recovery opened setup", setup);
        r.markers.add(setup);
        ClientResponse s = r.send("POST", "/auth/setup", setupBody(setup));
        assertEquals(s.text(), 201, s.status());
        // a failed login, then a login (its device token, cookie or body, is captured)
        assertEquals(401, r.send("POST", "/auth/login", W8.login("alice", PW_WRONG)).status());
        ClientResponse login = r.send("POST", "/auth/login", W8.login("alice", PW));
        assertEquals(login.text(), 200, login.status());
        IssuedApiKey key = r.auth.apiKeys().issue(r.alice, "ci", null, "posts:read");
        r.markers.add(key.getPlainKey());
        r.markers.add(key.getPlainKey().substring(("nk_" + key.getKey().getPrefix() + "_").length()));
        if (!r.cookies()) {
            Map<String, Object> b = W8.object(login);
            String at = (String) b.get("accessToken");
            String rt = (String) b.get("refreshToken");
            String device = (String) b.get("deviceToken");
            assertEquals(200, r.send("GET", "/api/x", null, "Authorization", "Bearer " + at).status());
            assertEquals(401, r.send("GET", "/api/x", null, "Authorization", "Bearer " + FORGED_TOKEN).status());
            assertEquals(200, r.send("GET", "/api/x", null, "Authorization", "ApiKey " + key.getPlainKey()).status());
            assertEquals(401, r.send("GET", "/api/x", null, "Authorization", "ApiKey " + FORGED_KEY).status());
            ClientResponse refreshed = r.send("POST", "/auth/refresh", "{\"refreshToken\":\"" + rt + "\"}");
            assertEquals(refreshed.text(), 200, refreshed.status());
            assertEquals(401, r.send("POST", "/auth/refresh", "{\"refreshToken\":\"" + rt + "\"}").status());
            assertEquals(200, r.send("POST", "/auth/login", W8.login("alice", PW), "Naru-Device", device).status());
            assertEquals(401, r.send("POST", "/auth/login", W8.login("alice", PW_WRONG), "Naru-Device", CK_DEVICE)
                    .status());
            String at2 = (String) W8.object(refreshed).get("accessToken");
            assertEquals(204, r.send("POST", "/auth/logout", null, "Authorization", "Bearer " + at2).status());
            return setup;
        }
        String at = W8.cookieValue(login, "naru_at");
        String rt = W8.cookieValue(login, "naru_rt");
        String device = W8.cookieValue(login, "naru_device");
        String csrf = (String) W8.object(login).get("csrfToken");
        assertNotNull(login.text(), at);
        assertNotNull(csrf);
        String jar = "naru_at=" + at + "; naru_rt=" + rt + "; naru_csrf=" + csrf;
        // the protected request is a write carrying X-CSRF-Token
        ClientResponse write = r.send("POST", "/api/x", null, "Cookie", jar, "X-CSRF-Token", csrf);
        assertEquals(write.text(), 201, write.status());
        assertEquals(200, r.send("GET", "/api/x", null, "Authorization", "ApiKey " + key.getPlainKey()).status());
        assertEquals(401, r.send("GET", "/api/x", null, "Authorization", "ApiKey " + FORGED_KEY).status());
        ClientResponse refreshed = r.send("POST", "/auth/refresh", null, "Cookie", jar, "X-CSRF-Token", csrf);
        assertEquals(refreshed.text(), 200, refreshed.status());
        String at2 = W8.cookieValue(refreshed, "naru_at");
        String rt2 = W8.cookieValue(refreshed, "naru_rt");
        String csrf2 = (String) W8.object(refreshed).get("csrfToken");
        assertEquals(200, r.send("POST", "/auth/login", W8.login("alice", PW), "Cookie", "naru_device=" + device)
                .status());
        assertEquals(204, r.send("POST", "/auth/logout", null, "Cookie", "naru_at=" + at2 + "; naru_rt=" + rt2
                + "; naru_csrf=" + csrf2, "X-CSRF-Token", csrf2).status());
        // the same flows with marker values as the four request cookies
        String marked = "naru_at=" + CK_AT + "; naru_rt=" + CK_RT + "; naru_csrf=" + CK_CSRF + "; naru_device="
                + CK_DEVICE;
        assertEquals(401, r.send("POST", "/api/x", null, "Cookie", marked, "X-CSRF-Token", CK_CSRF).status());
        assertEquals(401, r.send("GET", "/api/x", null, "Cookie", marked).status());
        assertEquals(401, r.send("POST", "/auth/refresh", null, "Cookie", marked, "X-CSRF-Token", CK_CSRF).status());
        assertEquals(200, r.send("POST", "/auth/login", W8.login("alice", PW), "Cookie", marked).status());
        assertEquals(401, r.send("POST", "/auth/login", W8.login("alice", PW_WRONG), "Cookie", marked).status());
        ClientResponse out = r.send("POST", "/auth/logout", null, "Cookie", marked, "X-CSRF-Token", CK_CSRF);
        assertTrue(out.text(), out.status() == 204 || out.status() == 401 || out.status() == 403);
        return setup;
    }

    // SEC-AUTH-008 AC-1: HEADER and COOKIE runs (PROD, .recovery(true), local requests) drive setup with the one-time
    // token, login, a failed login, a protected request (a CSRF-carrying write in COOKIE), an API key request, refresh,
    // logout and the doctor run at start, with markers for every listed secret (a 40-character signing secret
    // included), the cookies auth sets added to the markers and the flows repeated with marker request cookies; a BOTH
    // run whose login takes the cookie-delivery branch; and a DEV run with .user("admin", null, "admin") over an empty
    // store. No log line, logged or thrown exception (AuthConfigException included), audit event, describe() or
    // toString() of the builder or the Auth holds a marker, apart from the one startup line of the setup token or the
    // DEV temporary password, which appears in exactly that one startup line
    // SEC-AUTH-001 AC-7: the secret never appears in the AuthConfigException message, toString() of the builder or
    // Auth, or any log line (the marker test above)
    // FR-AUTH-009 AC-5: no AuditEvent carries a token, cookie value, API key secret, password or Authorization value
    // (the COOKIE and BOTH runs put the cookie values on the code path)
    @Test(timeout = 120000)
    public void SEC_AUTH_008_AC1_SEC_AUTH_001_AC7_FR_AUTH_009_AC5_noSecretInAnyOutput() throws Exception {
        assertEquals(40, SECRET.length());
        for (TokenTransport t : new TokenTransport[] {TokenTransport.HEADER, TokenTransport.COOKIE,
            TokenTransport.BOTH}) {
            Run r = new Run(t, AuthMode.PROD).start(true, null);
            String setup = drive(r);
            assertTrue(t + ": the audit sink saw the flows' events", !r.audit.isEmpty());
            r.assertClean(setup);
        }
        // the BOTH login answered through cookies
        Run both = new Run(TokenTransport.BOTH, AuthMode.PROD).start(true, null);
        ClientResponse login = both.send("POST", "/auth/login", W8.login("alice", PW));
        assertNotNull("the BOTH login took the cookie-delivery branch", W8.cookieValue(login, "naru_at"));
        both.markers.add(both.auth.pendingSetupToken());
        both.assertClean(both.auth.pendingSetupToken());

        // DEV, .user("admin", null, "admin"), an empty store
        Run dev = new Run(TokenTransport.HEADER, AuthMode.DEV);
        dev.start(false, "admin");
        String temporary = null;
        for (String l : dev.log.lines) {
            int i = l.indexOf("temporary password ");
            if (i >= 0) {
                temporary = l.substring(i + 19, l.indexOf(" (shown once"));
            }
        }
        assertNotNull(dev.log.lines.toString(), temporary);
        dev.markers.add(temporary);
        ClientResponse admin = dev.send("POST", "/auth/login", W8.login("admin", temporary));
        assertEquals(admin.text(), 200, admin.status());
        assertEquals(401, dev.send("POST", "/auth/login", W8.login("admin", PW_WRONG)).status());
        String at = (String) W8.object(admin).get("accessToken");
        assertEquals(200, dev.send("GET", "/auth/admin/users", null, "Authorization", "Bearer " + at).status());
        dev.assertClean(temporary);

        // negative controls: a marker in a later log line, or the setup token repeated after start, fails the check
        Run leak = new Run(TokenTransport.HEADER, AuthMode.PROD).start(true, null);
        String token = leak.auth.pendingSetupToken();
        leak.markers.add(token);
        leak.assertClean(token);
        leak.log.warn("a careless line with " + PW);
        failsCheck(leak, token);
        leak.log.lines.remove(leak.log.lines.size() - 1);
        leak.log.info("the token again: " + token);
        failsCheck(leak, token);
    }

    private static void failsCheck(Run r, String carved) {
        try {
            r.assertClean(carved);
        } catch (AssertionError expected) {
            return;
        }
        fail("the check passed a leak");
    }
}
