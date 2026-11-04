package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.Method;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.utils.auth.apikey.InMemoryApiKeyStore;
import com.snoworca.naruserve.utils.auth.testing.AuthTesting;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.UnaryOperator;
import org.junit.After;
import org.junit.Test;

/**
 * The two credential endpoint classes (SEC-FLOW-003): anonymous-entry (login, refresh, logout, register, setup)
 * ignore invalid access tokens and never attach a principal; subject-required (password, the two key issuances) are
 * judged exactly like protected paths, whatever .permit says. Probe handlers on auth.routes() (an app route there
 * runs before the built-in one) show whether a request reached the handler and what principal it saw.
 */
public class Wave8EndpointClassesTest {

    private final W8 f = new W8();
    private final List<String> reached = new CopyOnWriteArrayList<>();
    private String alice;

    @After
    public void stop() {
        f.stop();
    }

    private void start(UnaryOperator<Auth.Builder> config) throws Exception {
        alice = f.user("alice", W8.PW, "reader");
        f.start(config);
    }

    /** Builds auth with probe routes at {@code probes} on auth.routes() (and auth.adminRoutes() for /admin/apikeys). */
    private void probe(String peer, boolean secure, UnaryOperator<Auth.Builder> config, final String... probes)
            throws Exception {
        alice = f.user("alice", W8.PW, "reader");
        f.routes = w -> {
            for (String p : probes) {
                final String path = p;
                if (path.equals("/admin/apikeys")) {
                    w.auth.adminRoutes().post("/apikeys", (req, res) -> {
                        reached.add(path + ":" + principal(req));
                        res.send("probe");
                    });
                } else {
                    w.auth.routes().post(path, (req, res) -> {
                        reached.add(path + ":" + principal(req));
                        res.send("probe");
                    });
                }
            }
        };
        f.start(peer, secure, config);
    }

    private String principal(com.snoworca.naruserve.Request req) {
        AuthPrincipal p = f.auth.userOrNull(req);
        return p == null ? "none" : p.getId();
    }

    // SEC-FLOW-003 AC-2: POST /auth/login with an expired Bearer and valid credentials -> 200, no 401 invalid_token
    @Test
    public void SEC_FLOW_003_AC2_expiredTokenOnLoginIsIgnored() throws Exception {
        start(b -> b);
        ClientResponse r = f.post("/auth/login", W8.login("alice", W8.PW), "Authorization",
                "Bearer " + f.expiredAccess(alice));
        assertEquals(r.text(), 200, r.status());
    }

    // SEC-FLOW-003 AC-3: login, register, setup and refresh carrying a valid access token -> the handler sees no
    // principal
    @Test
    public void SEC_FLOW_003_AC3_anonymousEntryAttachesNoPrincipal() throws Exception {
        probe("127.0.0.1", true, b -> b, "/login", "/register", "/setup", "/refresh");
        String at = f.auth.accessToken(alice, 0, 60_000L);
        for (String p : new String[] {"/login", "/register", "/setup", "/refresh"}) {
            assertEquals(p, 200, f.post("/auth" + p, "{}", "Authorization", "Bearer " + at).status());
        }
        assertEquals(Arrays.asList("/login:none", "/register:none", "/setup:none", "/refresh:none"), reached);
    }

    // SEC-FLOW-003 AC-4: POST /auth/password with an expired Bearer -> 401 invalid_token with Bearer
    // error="invalid_token", the handler does not run; with keys on, POST /auth/me/apikeys with the same token and POST
    // /auth/admin/apikeys with a bad-signature Bearer -> the same 401 with ", ApiKey" appended
    @Test
    public void SEC_FLOW_003_AC4_subjectRequiredIsProtected() throws Exception {
        probe("127.0.0.1", true, b -> b.apiKeys(new InMemoryApiKeyStore()), "/password", "/me/apikeys",
                "/admin/apikeys");
        String expired = f.expiredAccess(alice);
        String good = f.auth.accessToken(alice, 0, 60_000L);
        String forged = good.substring(0, good.length() - 2) + (good.endsWith("A") ? "BB" : "AA");
        String[][] cases = {{"/auth/password", expired}, {"/auth/me/apikeys", expired},
            {"/auth/admin/apikeys", forged}};
        for (String[] c : cases) {
            ClientResponse r = f.post(c[0], "{}", "Authorization", "Bearer " + c[1]);
            assertEquals(c[0], 401, r.status());
            assertEquals("invalid_token", W8.code(r));
            assertEquals("Bearer error=\"invalid_token\", ApiKey", r.header("WWW-Authenticate"));
        }
        assertTrue(reached.toString(), reached.isEmpty());
        f.stop();
        W8 plain = new W8();
        try {
            String id = plain.user("alice", W8.PW);
            plain.start(b -> b);
            ClientResponse r = plain.post("/auth/password", "{}", "Authorization", "Bearer " + plain.expiredAccess(id));
            assertEquals("Bearer error=\"invalid_token\"", r.header("WWW-Authenticate"));
        } finally {
            plain.stop();
        }
    }

    // SEC-FLOW-003 AC-5: on an anonymous-entry endpoint two Authorization headers -> 400; with keys on a valid key on
    // POST /auth/refresh -> 403 api_key_not_allowed and a wrong key -> 401 invalid_api_key; without keys the
    // key-scheme header is no credential and the request reaches the endpoint
    @Test
    public void SEC_FLOW_003_AC5_onlyMultipleCredentialsAndKeysAreJudged() throws Exception {
        probe("127.0.0.1", true, b -> b.apiKeys(new InMemoryApiKeyStore()), "/refresh");
        ClientResponse two = f.adapter.send(ClientRequest.json("POST", "/auth/refresh", "{}")
                .header("Authorization", "Bearer a.b.c").header("Authorization", "Bearer d.e.f"));
        assertEquals(400, two.status());
        assertEquals("invalid_request", W8.code(two));
        String k = AuthTesting.apiKeyFor(f.auth, alice, "posts:read");
        ClientResponse valid = f.post("/auth/refresh", "{}", "Authorization", "ApiKey " + k);
        assertEquals(403, valid.status());
        assertEquals("api_key_not_allowed", W8.code(valid));
        ClientResponse wrong = f.post("/auth/refresh", "{}", "Authorization",
                "ApiKey nk_AAAAAAAA_BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB");
        assertEquals(401, wrong.status());
        assertEquals("invalid_api_key", W8.code(wrong));
        assertTrue(reached.isEmpty());
        f.stop();
        W8 off = new W8();
        final List<String> hit = new CopyOnWriteArrayList<>();
        try {
            off.routes = w -> w.auth.routes().post("/refresh", (req, res) -> {
                hit.add("refresh");
                res.send("decided by the refresh token");
            });
            off.start(b -> b);
            ClientResponse r = off.post("/auth/refresh", "{}", "Authorization", "ApiKey " + k);
            assertEquals(200, r.status());
            assertEquals(Arrays.asList("refresh"), hit);
        } finally {
            off.stop();
        }
    }

    // SEC-FLOW-003 AC-6: with .permitPrefix("/auth"), POST /auth/password with an expired Bearer -> 401 invalid_token
    // and POST /auth/login with two Authorization headers -> 400; HEADER, keys on, body {}: (a) permitPrefix: POST
    // /auth/me/apikeys with an expired Bearer -> 401 invalid_token (Bearer error="invalid_token", ApiKey) and with no
    // credential -> 401 unauthenticated (Bearer, ApiKey); (b) .permit(POST, "/auth/admin/apikeys"): the same two for
    // POST /auth/admin/apikeys; no handler runs and no key is issued
    @Test
    public void SEC_FLOW_003_AC6_permitDoesNotReclassify() throws Exception {
        final InMemoryApiKeyStore store = new InMemoryApiKeyStore();
        probe("127.0.0.1", true, b -> b.apiKeys(store).permitPrefix("/auth").permit(Method.POST,
                "/auth/admin/apikeys"), "/password", "/me/apikeys", "/admin/apikeys");
        String expired = f.expiredAccess(alice);
        ClientResponse pw = f.post("/auth/password", "{}", "Authorization", "Bearer " + expired);
        assertEquals(401, pw.status());
        assertEquals("invalid_token", W8.code(pw));
        ClientResponse login = f.adapter.send(ClientRequest.json("POST", "/auth/login", W8.login("alice", W8.PW))
                .header("Authorization", "Bearer a.b.c").header("Authorization", "Bearer d.e.f"));
        assertEquals(400, login.status());
        for (String path : new String[] {"/auth/me/apikeys", "/auth/admin/apikeys"}) {
            ClientResponse e = f.post(path, "{}", "Authorization", "Bearer " + expired);
            assertEquals(path, 401, e.status());
            assertEquals("invalid_token", W8.code(e));
            assertEquals("Bearer error=\"invalid_token\", ApiKey", e.header("WWW-Authenticate"));
            ClientResponse n = f.post(path, "{}");
            assertEquals(path, 401, n.status());
            assertEquals("unauthenticated", W8.code(n));
            assertEquals("Bearer, ApiKey", n.header("WWW-Authenticate"));
        }
        assertTrue(reached.toString(), reached.isEmpty());
        assertTrue(store.listByOwner(alice).isEmpty());
    }

    // SEC-FLOW-003 AC-7 (a): with .mountPath("/api/auth") and PROD .permitPrefix("/api/auth"), a plain-text non-local
    // POST /api/auth/login -> 403 insecure_transport, the login handler does not run; (c) HEADER, local, POST
    // /api/auth/password with an expired Bearer -> 401 invalid_token with Bearer error="invalid_token"
    @Test
    public void SEC_FLOW_003_AC7_classesFollowTheMountPath() throws Exception {
        probe("203.0.113.9", false, b -> b.mountPath("/api/auth").permitPrefix("/api/auth"), "/login");
        ClientResponse r = f.post("/api/auth/login", W8.login("alice", W8.PW));
        assertEquals(403, r.status());
        assertEquals("insecure_transport", W8.code(r));
        assertTrue(reached.isEmpty());
        f.stop();
        W8 local = new W8();
        try {
            String id = local.user("alice", W8.PW);
            local.start("127.0.0.1", false, b -> b.mountPath("/api/auth"));
            ClientResponse pw = local.post("/api/auth/password", "{}", "Authorization", "Bearer "
                    + local.expiredAccess(id));
            assertEquals(401, pw.status());
            assertEquals("Bearer error=\"invalid_token\"", pw.header("WWW-Authenticate"));
            assertEquals(200, local.post("/api/auth/login", W8.login("alice", W8.PW), "Authorization", "Bearer "
                    + local.expiredAccess(id)).status());
        } finally {
            local.stop();
        }
    }

    // SEC-FLOW-003 AC-8: COOKIE, keys off, no Authorization: (a) POST /auth/password with a well-formed body,
    // same-origin Origin, CSRF built from the token's sub and fam, and only an expired naru_at -> 401 invalid_token
    // with Bearer error="invalid_token", no handler; (b) a naru_at with an altered signature -> the same; (c) the
    // expired naru_at on GET /api/x -> 401 invalid_token, not unauthenticated
    @Test
    public void SEC_FLOW_003_AC8_accessCookieOnSubjectRequired() throws Exception {
        probe("127.0.0.1", true, b -> b.transport(TokenTransport.COOKIE), "/password");
        String expired = f.expiredAccess(alice);
        String good = f.auth.accessToken(alice, 0, 60_000L);
        String forged = good.substring(0, good.length() - 2) + (good.endsWith("A") ? "BB" : "AA");
        for (String at : new String[] {expired, forged}) {
            String csrf = f.auth.csrfTokens().issue(alice, "any-family");
            ClientResponse r = f.post("/auth/password", "{\"currentPassword\":\"x\",\"newPassword\":\"y\"}",
                    "Host", W8.HOST, "Origin", W8.SAME, "Cookie", "naru_at=" + at + "; naru_csrf=" + csrf,
                    "X-CSRF-Token", csrf);
            assertEquals(401, r.status());
            assertEquals("invalid_token", W8.code(r));
            assertEquals("Bearer error=\"invalid_token\"", r.header("WWW-Authenticate"));
        }
        ClientResponse get = f.get("/api/x", "Cookie", "naru_at=" + expired);
        assertEquals(401, get.status());
        assertEquals("invalid_token", W8.code(get));
        assertEquals("Bearer error=\"invalid_token\"", get.header("WWW-Authenticate"));
        assertTrue(reached.isEmpty());
    }

    // SEC-FLOW-003 AC-9: HEADER, keys off, no credential: POST /auth/password {} -> 401 unauthenticated with
    // WWW-Authenticate: Bearer, not 400, no handler and no hash; the same under .permitPrefix("/auth"); with keys on,
    // POST /auth/me/apikeys and /auth/admin/apikeys {} -> 401 unauthenticated with Bearer, ApiKey, no key issued
    @Test
    public void SEC_FLOW_003_AC9_noCredentialOnSubjectRequired() throws Exception {
        for (boolean permitted : new boolean[] {false, true}) {
            W8 w = new W8();
            final List<String> hit = new CopyOnWriteArrayList<>();
            try {
                w.routes = x -> x.auth.routes().post("/password", (req, res) -> {
                    hit.add("password");
                    res.send("x");
                });
                w.start(b -> permitted ? b.permitPrefix("/auth") : b);
                long slots = w.auth.hashSlots().requested();
                ClientResponse r = w.post("/auth/password", "{}");
                assertEquals(401, r.status());
                assertEquals("unauthenticated", W8.code(r));
                assertEquals("Bearer", r.header("WWW-Authenticate"));
                assertTrue(hit.isEmpty());
                assertEquals(slots, w.auth.hashSlots().requested());
            } finally {
                w.stop();
            }
        }
        final InMemoryApiKeyStore store = new InMemoryApiKeyStore();
        probe("127.0.0.1", true, b -> b.apiKeys(store), "/me/apikeys", "/admin/apikeys");
        for (String path : new String[] {"/auth/me/apikeys", "/auth/admin/apikeys"}) {
            ClientResponse r = f.post(path, "{}");
            assertEquals(401, r.status());
            assertEquals("unauthenticated", W8.code(r));
            assertEquals("Bearer, ApiKey", r.header("WWW-Authenticate"));
        }
        assertTrue(reached.isEmpty());
        assertTrue(store.listByOwner(alice).isEmpty());
        assertFalse(f.auth.toString().isEmpty());
    }
}
