package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.utils.auth.apikey.InMemoryApiKeyStore;
import com.snoworca.naruserve.utils.auth.audit.AuditEvent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;
import org.junit.After;
import org.junit.Test;

/**
 * The credential each transport mode reads (SEC-FLOW-004), the JSON-only bodies of the auth endpoints (SEC-FLOW-005)
 * and the auth cookie names, attributes and clearing (SEC-FLOW-009), over the real login, refresh, logout, register
 * and password endpoints of the in-memory adapter with real HS256 tokens; nothing is a mock.
 */
public class Wave12TransportTest {

    private final W8 f = new W8();
    private final List<W8> others = new ArrayList<>();
    private String alice;

    @After
    public void stop() {
        f.stop();
        for (W8 o : others) {
            o.stop();
        }
    }

    private W8 start(W8 w, TokenTransport t, UnaryOperator<Auth.Builder> config) throws Exception {
        String id = w.user("alice", W8.PW, "reader");
        if (w == f) {
            alice = id;
        }
        w.routes = x -> x.app.post("/api/posts", x.auth.require("posts:read"), (req, res) -> res.status(201)
                .send("posted"));
        return w.start(b -> config.apply(b.transport(t)));
    }

    private W8 other() {
        W8 w = new W8();
        others.add(w);
        return w;
    }

    /** A same-origin login of alice; the response. */
    private static ClientResponse cookieLogin(W8 w) {
        ClientResponse r = w.post("/auth/login", W8.login("alice", W8.PW), "Host", W8.HOST, "Origin", W8.SAME);
        assertEquals(r.text(), 200, r.status());
        return r;
    }

    /** {@code [naru_at, naru_rt, csrfToken]} of a cookie login. */
    private static String[] cookies(W8 w) {
        ClientResponse r = cookieLogin(w);
        return new String[] {W8.cookieValue(r, "naru_at"), W8.cookieValue(r, "naru_rt"),
            (String) W8.object(r).get("csrfToken")};
    }

    private static ClientRequest raw(String method, String path, @Nullable String contentType, String body,
            String... headers) {
        ClientRequest r = ClientRequest.of(method, path);
        if (contentType != null) {
            r.header("Content-Type", contentType);
        }
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        r.header("Content-Length", String.valueOf(b.length));
        if (b.length > 0) {
            r.body(b);
        }
        for (int i = 0; i < headers.length; i += 2) {
            r.header(headers[i], headers[i + 1]);
        }
        return r;
    }

    // ---------------------------------------------------------------------------------------------- SEC-FLOW-004

    // SEC-FLOW-004 AC-4: COOKIE with API keys enabled, protected GET /api/x with only a valid API-key scheme header ->
    // authenticated by the key (200)
    @Test
    public void SEC_FLOW_004_AC4_cookieModeAcceptsTheKeyHeader() throws Exception {
        start(f, TokenTransport.COOKIE, b -> b.apiKeys(new InMemoryApiKeyStore()));
        String key = f.auth.apiKeys().issue(alice, "ci", null, "posts:read").getPlainKey();
        ClientResponse r = f.get("/api/x", "Authorization", "ApiKey " + key);
        assertEquals(r.text(), 200, r.status());
        assertEquals("x:" + alice, r.text());
    }

    // SEC-FLOW-004 AC-5: BOTH, a valid Bearer and a valid naru_at -> 400 invalid_request; COOKIE or BOTH with
    // .apiKeys(store), an API-key header and a valid naru_at -> 400 invalid_request; without .apiKeys(store) the same
    // request is authenticated by naru_at (200), the API-key header being no credential
    @Test
    public void SEC_FLOW_004_AC5_twoCredentialsAre400() throws Exception {
        start(f, TokenTransport.BOTH, b -> b.apiKeys(new InMemoryApiKeyStore()));
        String at = f.auth.accessToken(alice, 0, 60_000L);
        ClientResponse both = f.get("/api/x", "Authorization", "Bearer " + at, "Cookie", "naru_at=" + at);
        assertEquals(400, both.status());
        assertEquals("invalid_request", W8.code(both));
        String key = f.auth.apiKeys().issue(alice, "ci", null, "posts:read").getPlainKey();
        ClientResponse keyAndCookie = f.get("/api/x", "Authorization", "ApiKey " + key, "Cookie", "naru_at=" + at);
        assertEquals(400, keyAndCookie.status());
        assertEquals("invalid_request", W8.code(keyAndCookie));
        for (TokenTransport t : new TokenTransport[] {TokenTransport.COOKIE, TokenTransport.BOTH}) {
            W8 keyed = other();
            start(keyed, t, b -> b.apiKeys(new InMemoryApiKeyStore()));
            String id = keyed.store.findByUsername("alice").getId();
            String k = keyed.auth.apiKeys().issue(id, "ci", null, "posts:read").getPlainKey();
            String a = keyed.auth.accessToken(id, 0, 60_000L);
            ClientResponse r = keyed.get("/api/x", "Authorization", "ApiKey " + k, "Cookie", "naru_at=" + a);
            assertEquals(t + ": " + r.text(), 400, r.status());
            assertEquals("invalid_request", W8.code(r));
            W8 plain = other();
            start(plain, t, b -> b);
            String pid = plain.store.findByUsername("alice").getId();
            String pa = plain.auth.accessToken(pid, 0, 60_000L);
            ClientResponse byCookie = plain.get("/api/x", "Authorization", "ApiKey nk_AAAAAAAA_whatever", "Cookie",
                    "naru_at=" + pa);
            assertEquals(t + ": " + byCookie.text(), 200, byCookie.status());
            assertEquals("x:" + pid, byCookie.text());
        }
    }

    // SEC-FLOW-004 AC-7: COOKIE, two naru_at cookies: POST /api/posts -> 400; GET /health with .permit("/health") ->
    // anonymous 200; POST /auth/password -> 400; POST /auth/refresh with one valid naru_rt, its X-CSRF-Token and a
    // same-origin Origin -> judged by the refresh cookie (200); POST /auth/login same-origin with valid credentials ->
    // 200; POST /auth/logout treats them as no access cookie (SEC-FLOW-010 AC-8)
    @Test
    public void SEC_FLOW_004_AC7_twoAccessCookies() throws Exception {
        start(f, TokenTransport.COOKIE, b -> b.permit("/health"));
        String[] s = cookies(f);
        String[] t = cookies(f);
        String two = "naru_at=" + s[0] + "; naru_at=" + t[0];
        ClientResponse posts = f.post("/api/posts", null, "Host", W8.HOST, "Origin", W8.SAME, "Cookie", two,
                "X-CSRF-Token", s[2]);
        assertEquals(posts.text(), 400, posts.status());
        assertEquals("invalid_request", W8.code(posts));
        ClientResponse health = f.get("/health", "Cookie", two);
        assertEquals(200, health.status());
        assertEquals("anon", health.text());
        ClientResponse pw = f.post("/auth/password", "{\"currentPassword\":\"" + W8.PW
                + "\",\"newPassword\":\"another long password\"}", "Host", W8.HOST, "Origin", W8.SAME, "Cookie", two,
                "X-CSRF-Token", s[2]);
        assertEquals(pw.text(), 400, pw.status());
        assertEquals("invalid_request", W8.code(pw));
        ClientResponse refresh = f.post("/auth/refresh", null, "Host", W8.HOST, "Origin", W8.SAME, "Cookie",
                two + "; naru_rt=" + s[1] + "; naru_csrf=" + s[2], "X-CSRF-Token", s[2]);
        assertEquals(refresh.text(), 200, refresh.status());
        ClientResponse login = f.post("/auth/login", W8.login("alice", W8.PW), "Host", W8.HOST, "Origin", W8.SAME,
                "Cookie", two);
        assertEquals(login.text(), 200, login.status());
        ClientResponse logout = f.post("/auth/logout?all=true", null, "Host", W8.HOST, "Origin", W8.SAME, "Cookie",
                two);
        assertEquals("no access cookie: no identity for ?all=true", 401, logout.status());
    }

    // ---------------------------------------------------------------------------------------------- SEC-FLOW-005

    private static void unsupported(ClientResponse r) {
        assertEquals(r.text(), 415, r.status());
        assertEquals("UNSUPPORTED_MEDIA_TYPE", W8.code(r));
        assertTrue("no cookie is set", W8.setCookies(r).isEmpty());
    }

    // SEC-FLOW-005 AC-1 (the real login, not a stand-in handler): any mode, a cross-origin form POST /auth/login with
    // valid credentials -> 415, not 403; the login never ran (no hash) and no Set-Cookie is sent
    @Test
    public void SEC_FLOW_005_AC1_theRealLoginRefusesACrossOriginForm() throws Exception {
        for (TokenTransport t : TokenTransport.values()) {
            W8 w = other();
            start(w, t, b -> b);
            long slots = w.auth.hashSlots().requested();
            unsupported(w.adapter.send(raw("POST", "/auth/login", "application/x-www-form-urlencoded",
                    "username=alice&password=correct", "Host", W8.HOST, "Origin", W8.EVIL)));
            assertEquals(t.toString(), slots, w.auth.hashSlots().requested());
        }
    }

    // SEC-FLOW-005 AC-4 (the real endpoints): POST /auth/logout with Content-Length 5 and text/plain -> 415; POST
    // /auth/login with Transfer-Encoding: chunked and text/plain -> 415
    @Test
    public void SEC_FLOW_005_AC4_theRealEndpointsCheckLengthAndChunked() throws Exception {
        start(f, TokenTransport.HEADER, b -> b);
        unsupported(f.adapter.send(raw("POST", "/auth/logout", "text/plain", "hello")));
        unsupported(f.adapter.send(ClientRequest.of("POST", "/auth/login").header("Content-Type", "text/plain")
                .header("Transfer-Encoding", "chunked").body("hello".getBytes(StandardCharsets.UTF_8))));
    }

    // SEC-FLOW-005 AC-2: HEADER or BOTH, POST /auth/refresh with text/plain and a body holding a refresh token -> 415;
    // the token is not used (a later JSON refresh with it succeeds) and no cookie is set
    @Test
    public void SEC_FLOW_005_AC2_textRefreshIs415AndUnused() throws Exception {
        for (TokenTransport t : new TokenTransport[] {TokenTransport.HEADER, TokenTransport.BOTH}) {
            W8 w = other();
            start(w, t, b -> b);
            String rt = (String) w.loginBody("alice", W8.PW).get("refreshToken");
            String body = "{\"refreshToken\":\"" + rt + "\"}";
            unsupported(w.adapter.send(raw("POST", "/auth/refresh", "text/plain", body)));
            ClientResponse ok = w.post("/auth/refresh", body);
            assertEquals(t + ": " + ok.text(), 200, ok.status());
        }
    }

    // SEC-FLOW-005 AC-3: POST /auth/register (.register(true)) with multipart/form-data -> 415 and no user is created
    @Test
    public void SEC_FLOW_005_AC3_multipartRegisterIs415() throws Exception {
        start(f, TokenTransport.HEADER, b -> b.register(true));
        String body = "--x\r\nContent-Disposition: form-data; name=\"username\"\r\n\r\nmallory\r\n--x\r\n"
                + "Content-Disposition: form-data; name=\"password\"\r\n\r\nanother long password\r\n--x--\r\n";
        unsupported(f.adapter.send(raw("POST", "/auth/register", "multipart/form-data; boundary=x", body)));
        assertNull(f.store.findByUsername("mallory"));
    }

    // SEC-FLOW-005 AC-5: COOKIE, POST /auth/refresh with no body (Content-Length 0), no Content-Type, a valid naru_rt,
    // its X-CSRF-Token and a same-origin Origin -> 200, not 415
    @Test
    public void SEC_FLOW_005_AC5_bodilessCookieRefreshIs200() throws Exception {
        start(f, TokenTransport.COOKIE, b -> b);
        String[] s = cookies(f);
        ClientResponse r = f.adapter.send(raw("POST", "/auth/refresh", null, "", "Host", W8.HOST, "Origin", W8.SAME,
                "Cookie", "naru_rt=" + s[1] + "; naru_csrf=" + s[2], "X-CSRF-Token", s[2]));
        assertEquals(r.text(), 200, r.status());
        assertNotNull(W8.setCookie(r, "naru_at"));
    }

    // SEC-FLOW-005 AC-6: POST /auth/logout with no body and no Content-Type -> 204, not 415
    @Test
    public void SEC_FLOW_005_AC6_bodilessLogoutIs204() throws Exception {
        start(f, TokenTransport.HEADER, b -> b);
        String at = (String) f.loginBody("alice", W8.PW).get("accessToken");
        ClientResponse r = f.adapter.send(raw("POST", "/auth/logout", null, "", "Authorization", "Bearer " + at));
        assertEquals(r.text(), 204, r.status());
        ClientResponse anonymous = f.adapter.send(ClientRequest.of("POST", "/auth/logout"));
        assertEquals(anonymous.text(), 204, anonymous.status());
    }

    // SEC-FLOW-005 AC-7: POST /auth/login with application/json and a body that is not valid JSON -> 400 JSON error
    // body {"error":{"code","message","requestId"}} with no stack trace
    @Test
    @SuppressWarnings("unchecked")
    public void SEC_FLOW_005_AC7_invalidJsonLoginIs400() throws Exception {
        f.codec = new StrictCodec();
        start(f, TokenTransport.HEADER, b -> b);
        ClientResponse r = f.adapter.send(raw("POST", "/auth/login", "application/json", "{\"username\":\"alice\","));
        assertEquals(r.text(), 400, r.status());
        Map<String, Object> error = (Map<String, Object>) W8.object(r).get("error");
        assertNotNull(r.text(), error);
        assertEquals(new java.util.TreeSet<>(java.util.Arrays.asList("code", "message", "requestId")),
                new java.util.TreeSet<>(error.keySet()));
        assertFalse(r.text(), r.text().contains("at com.") || r.text().contains("Exception"));
    }

    /**
     * The auth MiniJson codec under the JsonCodec contract: malformed input is an IOException (as the Gson codec
     * reports it), which Core answers 400.
     */
    static final class StrictCodec implements com.snoworca.naruserve.spi.JsonCodec {
        private final AdminFixture.MiniJsonCodec inner = new AdminFixture.MiniJsonCodec();

        @Override
        public <T> @Nullable T decode(java.io.Reader reader, java.lang.reflect.Type type) throws java.io.IOException {
            try {
                return inner.decode(reader, type);
            } catch (com.snoworca.naruserve.utils.auth.internal.MiniJson.JsonFormatException e) {
                throw new java.io.IOException("malformed JSON", e);
            }
        }

        @Override
        public void encode(@Nullable Object value, java.io.Writer writer) throws java.io.IOException {
            inner.encode(value, writer);
        }
    }

    // ---------------------------------------------------------------------------------------------- SEC-FLOW-009

    // SEC-FLOW-009 AC-1: COOKIE, default mount, a same-origin login -> naru_rt Set-Cookie has Path=/auth; with
    // .mountPath("/api/auth") it has Path=/api/auth
    @Test
    public void SEC_FLOW_009_AC1_refreshCookiePathFollowsTheMount() throws Exception {
        start(f, TokenTransport.COOKIE, b -> b);
        assertTrue(W8.setCookie(cookieLogin(f), "naru_rt").contains("; Path=/auth;"));
        W8 mounted = other();
        start(mounted, TokenTransport.COOKIE, b -> b.mountPath("/api/auth"));
        ClientResponse r = mounted.post("/api/auth/login", W8.login("alice", W8.PW), "Host", W8.HOST, "Origin",
                W8.SAME);
        assertEquals(r.text(), 200, r.status());
        String rt = W8.setCookie(r, "naru_rt");
        assertTrue(rt, rt.contains("; Path=/api/auth;") || rt.endsWith("; Path=/api/auth"));
    }

    // SEC-FLOW-009 AC-3: COOKIE, two naru_rt cookies (different Path) on POST /auth/refresh -> the same 401 as a
    // refresh without any refresh token; neither value is used
    @Test
    public void SEC_FLOW_009_AC3_twoRefreshCookiesAreNone() throws Exception {
        start(f, TokenTransport.COOKIE, b -> b);
        String[] s = cookies(f);
        String[] t = cookies(f);
        ClientResponse none = f.post("/auth/refresh", null, "Host", W8.HOST, "Origin", W8.SAME, "Cookie",
                "naru_csrf=" + s[2], "X-CSRF-Token", s[2]);
        ClientResponse two = f.post("/auth/refresh", null, "Host", W8.HOST, "Origin", W8.SAME, "Cookie",
                "naru_rt=" + s[1] + "; naru_rt=" + t[1] + "; naru_csrf=" + s[2], "X-CSRF-Token", s[2]);
        assertEquals(401, none.status());
        assertEquals(none.status(), two.status());
        assertEquals(W8.code(none), W8.code(two));
        for (String[] session : new String[][] {s, t}) {
            ClientResponse one = f.post("/auth/refresh", null, "Host", W8.HOST, "Origin", W8.SAME, "Cookie",
                    "naru_rt=" + session[1] + "; naru_csrf=" + session[2], "X-CSRF-Token", session[2]);
            assertEquals("unused, so it still rotates once: " + one.text(), 200, one.status());
        }
    }

    // SEC-FLOW-009 AC-4: COOKIE or BOTH, a same-origin login sets naru_at HttpOnly SameSite=Strict Path=/, naru_rt
    // HttpOnly SameSite=Strict, naru_csrf not HttpOnly SameSite=Strict Path=/; Secure exactly when req.isSecure();
    // Max-Age of naru_at = accessTtl and of naru_rt and naru_csrf = refreshTtl
    @Test
    public void SEC_FLOW_009_AC4_cookieDefaults() throws Exception {
        for (TokenTransport t : new TokenTransport[] {TokenTransport.COOKIE, TokenTransport.BOTH}) {
            for (boolean secure : new boolean[] {true, false}) {
                W8 w = other();
                w.user("alice", W8.PW, "reader");
                w.start("127.0.0.1", secure, b -> {
                    Auth.Builder x = b.transport(t);
                    return secure ? x : x.allowInsecureTransport("plain-text test");
                });
                ClientResponse r = w.post("/auth/login", W8.login("alice", W8.PW), "Host", W8.HOST, "Origin",
                        secure ? W8.SAME : "http://" + W8.HOST);
                assertEquals(r.text(), 200, r.status());
                long access = (secure ? Auth.lifetimes(AuthProfile.STANDARD)[0] : Auth.INSECURE_ACCESS_TTL_MILLIS) / 1000;
                long refresh = (secure ? Auth.lifetimes(AuthProfile.STANDARD)[1] : Auth.INSECURE_REFRESH_TTL_MILLIS)
                        / 1000;
                String at = W8.setCookie(r, "naru_at");
                String rt = W8.setCookie(r, "naru_rt");
                String csrf = W8.setCookie(r, "naru_csrf");
                String where = t + (secure ? " https" : " http") + ": ";
                assertTrue(where + at, at.contains("HttpOnly") && at.contains("SameSite=Strict")
                        && at.contains("Path=/;") && at.contains("Max-Age=" + access));
                assertTrue(where + rt, rt.contains("HttpOnly") && rt.contains("SameSite=Strict")
                        && rt.contains("Max-Age=" + refresh));
                assertTrue(where + csrf, !csrf.contains("HttpOnly") && csrf.contains("SameSite=Strict")
                        && csrf.contains("Path=/;") && csrf.contains("Max-Age=" + refresh));
                for (String c : new String[] {at, rt, csrf}) {
                    assertEquals(where + c, secure, c.contains("; Secure"));
                }
            }
        }
    }

    // SEC-FLOW-013 AC-10: COOKIE or BOTH (cookie delivery), a same-origin login sets naru_device HttpOnly,
    // SameSite=Strict, Path = mount + /login, Secure exactly when req.isSecure() and Max-Age 7776000; a password change
    // with cookie delivery sets it with the same attributes
    @Test
    public void SEC_FLOW_013_AC10_deviceCookieOnLoginAndPasswordChange() throws Exception {
        for (TokenTransport t : new TokenTransport[] {TokenTransport.COOKIE, TokenTransport.BOTH}) {
            for (String mount : new String[] {"/auth", "/api/auth"}) {
                for (boolean secure : new boolean[] {true, false}) {
                    W8 w = other();
                    w.user("alice", W8.PW, "reader");
                    w.start("127.0.0.1", secure, b -> {
                        Auth.Builder x = b.transport(t).mountPath(mount);
                        return secure ? x : x.allowInsecureTransport("plain-text test");
                    });
                    String origin = secure ? W8.SAME : "http://" + W8.HOST;
                    String where = t + " " + mount + (secure ? " https: " : " http: ");
                    ClientResponse login = w.post(mount + "/login", W8.login("alice", W8.PW), "Host", W8.HOST,
                            "Origin", origin);
                    assertEquals(where + login.text(), 200, login.status());
                    String set = W8.setCookie(login, "naru_device");
                    assertNotNull(where, set);
                    assertTrue(where + set, set.contains("HttpOnly") && set.contains("SameSite=Strict")
                            && set.contains("Path=" + mount + "/login") && set.contains("Max-Age=7776000"));
                    assertEquals(where + set, secure, set.contains("; Secure"));
                    String csrf = (String) W8.object(login).get("csrfToken");
                    ClientResponse change = w.post(mount + "/password",
                            Wave9PasswordTest.change(W8.PW, "a brand new passphrase"), "Host", W8.HOST, "Origin", origin,
                            "Cookie", "naru_at=" + W8.cookieValue(login, "naru_at") + "; naru_csrf=" + csrf,
                            "X-CSRF-Token", csrf);
                    assertEquals(where + change.text(), 200, change.status());
                    String again = W8.setCookie(change, "naru_device");
                    assertNotNull(where, again);
                    assertEquals(where, set.substring(set.indexOf(';')), again.substring(again.indexOf(';')));
                }
            }
        }
    }

    // SEC-FLOW-009 AC-5: COOKIE with .cookies(c -> c.domain("example.com")): login, then a same-origin POST
    // /auth/logout with the login's csrfToken -> the 204 clears naru_at, naru_rt and naru_csrf with Max-Age=0 and the
    // Path and Domain attributes the login used
    @Test
    public void SEC_FLOW_009_AC5_logoutClearsWithTheLoginAttributes() throws Exception {
        start(f, TokenTransport.COOKIE, b -> b.cookies(c -> c.domain("example.com")));
        ClientResponse login = cookieLogin(f);
        String csrf = (String) W8.object(login).get("csrfToken");
        String rt = W8.cookieValue(login, "naru_rt");
        String at = W8.cookieValue(login, "naru_at");
        ClientResponse out = f.post("/auth/logout", null, "Host", W8.HOST, "Origin", W8.SAME, "Cookie", "naru_at="
                + at + "; naru_rt=" + rt + "; naru_csrf=" + csrf, "X-CSRF-Token", csrf);
        assertEquals(out.text(), 204, out.status());
        for (String n : new String[] {"naru_at", "naru_rt", "naru_csrf"}) {
            String set = W8.setCookie(login, n);
            String cleared = W8.setCookie(out, n);
            assertNotNull(n + " cleared", cleared);
            assertTrue(cleared, cleared.contains("Max-Age=0"));
            assertEquals(n, attribute(set, "Path"), attribute(cleared, "Path"));
            assertEquals(n, "example.com", attribute(set, "Domain"));
            assertEquals(n, attribute(set, "Domain"), attribute(cleared, "Domain"));
        }
    }

    /** The value of {@code name} in a Set-Cookie header, or null. */
    private static @Nullable String attribute(String setCookie, String name) {
        for (String part : setCookie.split(";")) {
            String p = part.trim();
            if (p.regionMatches(true, 0, name + "=", 0, name.length() + 1)) {
                return p.substring(name.length() + 1);
            }
        }
        return null;
    }

    // SEC-FLOW-009 AC-7: a same-origin COOKIE login, refresh and logout (each with a matching X-CSRF-Token) under a
    // capturing log handler and an audit sink: no log line, exception message or audit event holds the value of
    // naru_at, naru_rt, naru_csrf or naru_device
    @Test
    public void SEC_FLOW_009_AC7_cookieValuesStayOutOfLogsAndAudit() throws Exception {
        final List<AuditEvent> audit = new CopyOnWriteArrayList<>();
        start(f, TokenTransport.COOKIE, b -> b.audit(audit::add));
        ClientResponse login = cookieLogin(f);
        List<String> values = new ArrayList<>();
        for (String n : new String[] {"naru_at", "naru_rt", "naru_csrf", "naru_device"}) {
            String v = W8.cookieValue(login, n);
            assertNotNull(n, v);
            values.add(v);
        }
        String csrf = values.get(2);
        ClientResponse refresh = f.post("/auth/refresh", null, "Host", W8.HOST, "Origin", W8.SAME, "Cookie",
                "naru_rt=" + values.get(1) + "; naru_csrf=" + csrf, "X-CSRF-Token", csrf);
        assertEquals(refresh.text(), 200, refresh.status());
        String at2 = W8.cookieValue(refresh, "naru_at");
        String rt2 = W8.cookieValue(refresh, "naru_rt");
        String csrf2 = W8.cookieValue(refresh, "naru_csrf");
        values.add(at2);
        values.add(rt2);
        values.add(csrf2);
        // a replayed (already rotated) refresh cookie: the reuse path logs and audits too
        f.post("/auth/refresh", null, "Host", W8.HOST, "Origin", W8.SAME, "Cookie", "naru_rt=" + values.get(1)
                + "; naru_csrf=" + csrf, "X-CSRF-Token", csrf);
        ClientResponse out = f.post("/auth/logout", null, "Host", W8.HOST, "Origin", W8.SAME, "Cookie", "naru_at="
                + at2 + "; naru_rt=" + rt2 + "; naru_csrf=" + csrf2, "X-CSRF-Token", csrf2);
        assertEquals(out.text(), 204, out.status());
        // login, refresh and logout emit no audit event of their own; whatever reached the sink is checked below
        List<String> seen = new ArrayList<>(f.logger.lines);
        for (AuditEvent e : audit) {
            seen.add(e.toString() + " " + e.getDetails() + " " + e.getReason());
        }
        for (Throwable e : new ArrayList<>(f.logger.errors)) {
            for (Throwable c = e; c != null; c = c.getCause()) {
                seen.add(String.valueOf(c.getMessage()));
            }
        }
        for (String line : seen) {
            for (String v : values) {
                if (v != null) {
                    assertFalse("a cookie value leaked: " + line, line.contains(v));
                }
            }
        }
    }
}
