package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.Registration;
import com.snoworca.naruserve.RegistrationKind;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.utils.auth.apikey.IssuedApiKey;
import com.snoworca.naruserve.utils.auth.user.AuthUser;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;
import org.junit.After;
import org.junit.Test;

/**
 * The account routes of {@code auth.routes()} (IR-FLOW-002), {@code GET} and {@code PATCH /auth/me} (FR-FLOW-006) and
 * the endpoints and writes refused to API-key actors (SEC-APIKEY-008), over the in-memory adapter.
 */
public class Wave10AccountTest {

    /** Records every user and session callback with its origin, and whether any received row carried a hash. */
    static final class Recorder implements AuthEvents {
        final List<String> calls = new CopyOnWriteArrayList<>();
        final List<String> hashes = new CopyOnWriteArrayList<>();
        volatile @Nullable AuthRejection refuseUpdate;

        private void seen(String what, @Nullable AuthUser u, @Nullable AuthEventContext ctx) {
            calls.add(what + ":" + (ctx == null ? null : ctx.getOrigin()));
            if (u != null && u.getPasswordHash() != null) {
                hashes.add(what);
            }
        }

        @Override
        public void beforeLogin(AuthUser user, AuthEventContext ctx) {
            seen("beforeLogin", user, ctx);
        }

        @Override
        public void onLogin(AuthUser user, AuthEventContext ctx) {
            seen("onLogin", user, ctx);
        }

        @Override
        public void beforeUserCreate(AuthUser candidate, AuthEventContext ctx) {
            seen("beforeUserCreate", candidate, ctx);
        }

        @Override
        public void onUserCreated(AuthUser user, AuthEventContext ctx) {
            seen("onUserCreated", user, ctx);
        }

        @Override
        public void beforeUserUpdate(AuthUser before, AuthUser after, AuthEventContext ctx) {
            seen("beforeUserUpdate", before, ctx);
            seen("beforeUserUpdate.after", after, null);
            calls.remove(calls.size() - 1);
            AuthRejection r = refuseUpdate;
            if (r != null) {
                throw r;
            }
        }

        @Override
        public void onUserUpdated(AuthUser before, AuthUser after, Set<UserChange> changes, AuthEventContext ctx) {
            Set<String> names = new TreeSet<>();
            for (UserChange c : changes) {
                names.add(c.name());
            }
            seen("onUserUpdated" + names, after, ctx);
            if (before.getPasswordHash() != null) {
                hashes.add("onUserUpdated.before");
            }
        }

        @Override
        public void onPasswordChanged(AuthUser user, AuthEventContext ctx) {
            seen("onPasswordChanged", user, ctx);
        }

        @Override
        public void beforeUserDelete(AuthUser user, AuthEventContext ctx) {
            seen("beforeUserDelete", user, ctx);
        }

        @Override
        public void onUserDeleted(AuthUser user, AuthEventContext ctx) {
            seen("onUserDeleted", user, ctx);
        }

        @Override
        public void onTokenRefreshed(AuthUser user, AuthEventContext ctx) {
            seen("onTokenRefreshed", user, ctx);
        }

        @Override
        public void onLogout(String userId, boolean allDevices, AuthEventContext ctx) {
            calls.add("onLogout:" + ctx.getOrigin());
        }
    }

    private final W8 f = new W8();
    private final List<W8> others = new ArrayList<>();
    private final Wave9KeyAuthTest.Keys keys = new Wave9KeyAuthTest.Keys();
    private final Recorder events = new Recorder();
    private String alice;
    private String root;

    @After
    public void stop() {
        f.stop();
        for (W8 o : others) {
            o.stop();
        }
    }

    private void start(UnaryOperator<Auth.Builder> config) throws Exception {
        alice = f.user("alice", W8.PW, "reader");
        root = f.user("root", W8.PW, "admin");
        f.start(b -> config.apply(b.events(events).roles(Wave10PatTest.roles())));
    }

    private String bearer(String id) throws Exception {
        return "Bearer " + f.auth.accessToken(id, f.stored(id).getTokenVersion(), 900_000L);
    }

    private ClientResponse send(String method, String path, @Nullable String json, String... headers) {
        ClientRequest r = json == null ? ClientRequest.of(method, path) : ClientRequest.json(method, path, json);
        for (int i = 0; i < headers.length; i += 2) {
            r.header(headers[i], headers[i + 1]);
        }
        return f.adapter.send(r);
    }

    private static Set<String> accountRoutes(W8 w) {
        Set<String> out = new TreeSet<>();
        for (Registration r : w.app.registrations()) {
            String p = r.getPath();
            if (r.getKind() == RegistrationKind.ROUTE && p != null && p.startsWith("/auth/")
                    && !p.startsWith("/auth/admin") && !p.contains("apikeys")) {
                out.add(r.getMethod() + " " + p);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------------------------------------- IR-FLOW-002

    // IR-FLOW-002 AC-1: the auth router's registrations with API keys off and .register(true) are exactly the eight
    // method/path pairs of the table (POST /setup whatever the setup state), with their classes and success statuses:
    // login 200, refresh 200, logout 204 (anonymous entry), GET /me 200 and PATCH /me 200 (authenticated), password 200
    // (subject required), register 201; with the default .register(false) the same pairs except POST /register
    @Test
    public void IR_FLOW_002_AC1_theAccountEndpointTable() throws Exception {
        start(b -> b.register(true).allowedAttributes("displayName"));
        assertEquals(new TreeSet<>(Arrays.asList("POST /auth/login", "POST /auth/refresh", "POST /auth/logout",
                "GET /auth/me", "PATCH /auth/me", "POST /auth/password", "POST /auth/register", "POST /auth/setup")),
                accountRoutes(f));
        String expired = "Bearer " + f.expiredAccess(alice);
        // anonymous entry: an expired token is ignored
        ClientResponse login = f.post("/auth/login", W8.login("alice", W8.PW), "Authorization", expired);
        assertEquals(200, login.status());
        String rt = (String) W8.object(login).get("refreshToken");
        ClientResponse refresh = f.post("/auth/refresh", "{\"refreshToken\":\"" + rt + "\"}");
        assertEquals(200, refresh.status());
        assertEquals(201, f.post("/auth/register", W8.login("neo", W8.PW)).status());
        // authenticated
        assertEquals(200, f.get("/auth/me", "Authorization", bearer(alice)).status());
        assertEquals(200, send("PATCH", "/auth/me", "{\"attributes\":{\"displayName\":\"Al\"}}", "Authorization",
                bearer(alice)).status());
        // subject required: an expired token is 401 invalid_token
        ClientResponse pw = f.post("/auth/password", Wave9PasswordTest.change(W8.PW, "a brand new passphrase"),
                "Authorization", expired);
        assertEquals(401, pw.status());
        assertEquals("invalid_token", W8.code(pw));
        assertEquals(200, f.post("/auth/password", Wave9PasswordTest.change(W8.PW, "a brand new passphrase"),
                "Authorization", bearer(alice)).status());
        assertEquals(204, f.post("/auth/logout", null, "Authorization", bearer(alice)).status());
        ClientResponse setup = f.post("/auth/setup", "{\"token\":\"x\",\"username\":\"a\",\"password\":\"b\"}");
        assertEquals("setup is closed: 404", 404, setup.status());

        W8 off = new W8();
        others.add(off);
        off.start(b -> b);
        assertEquals(new TreeSet<>(Arrays.asList("POST /auth/login", "POST /auth/refresh", "POST /auth/logout",
                "GET /auth/me", "PATCH /auth/me", "POST /auth/password", "POST /auth/setup")), accountRoutes(off));
    }

    // IR-FLOW-002 AC-2: with .apiKeys(store) the router also serves GET /me/apikeys, POST /me/apikeys and
    // DELETE /me/apikeys/:id; without it these three paths are not registered
    @Test
    public void IR_FLOW_002_AC2_theKeyRoutesFollowApiKeys() throws Exception {
        start(b -> b.apiKeys(keys));
        Set<String> on = new TreeSet<>();
        for (Registration r : f.app.registrations()) {
            if (r.getKind() == RegistrationKind.ROUTE && String.valueOf(r.getPath()).startsWith("/auth/me/")) {
                on.add(r.getMethod() + " " + r.getPath());
            }
        }
        assertEquals(new TreeSet<>(Arrays.asList("GET /auth/me/apikeys", "POST /auth/me/apikeys",
                "DELETE /auth/me/apikeys/:id")), on);
        W8 off = new W8();
        others.add(off);
        String bob = off.user("bob", W8.PW, "reader");
        off.start(b -> b);
        for (Registration r : off.app.registrations()) {
            assertFalse(String.valueOf(r.getPath()), String.valueOf(r.getPath()).contains("apikeys"));
        }
        ClientResponse r = off.get("/auth/me/apikeys", "Authorization",
                "Bearer " + off.auth.accessToken(bob, 0, 900_000L));
        assertEquals("not an auth route", 404, r.status());
    }

    // IR-FLOW-002 AC-3: no route exists for /signup, /password/forgot, /password/reset, /verify-email/request or
    // /verify-email; requests to them do not reach an auth handler
    @Test
    public void IR_FLOW_002_AC3_noV2Routes() throws Exception {
        start(b -> b.register(true));
        for (String p : new String[] {"/auth/signup", "/auth/password/forgot", "/auth/password/reset",
            "/auth/verify-email/request", "/auth/verify-email"}) {
            for (Registration r : f.app.registrations()) {
                assertFalse(p, p.equals(r.getPath()));
            }
            ClientResponse r = f.post(p, W8.login("neo", W8.PW), "Authorization", bearer(alice));
            assertEquals(p + " " + r.text(), 404, r.status());
        }
        assertNull("no user was created by a signup look-alike", f.store.findByUsername("neo"));
    }

    // IR-FLOW-002 AC-4: GET /auth/me and PATCH /auth/me without any credential -> 401 unauthenticated
    @Test
    public void IR_FLOW_002_AC4_meNeedsACredential() throws Exception {
        start(b -> b.allowedAttributes("displayName"));
        ClientResponse get = f.get("/auth/me");
        ClientResponse patch = send("PATCH", "/auth/me", "{\"attributes\":{\"displayName\":\"x\"}}");
        for (ClientResponse r : new ClientResponse[] {get, patch}) {
            assertEquals(401, r.status());
            assertEquals("unauthenticated", W8.code(r));
        }
        assertFalse(f.stored(alice).getAttributes().containsKey("displayName"));
    }

    // ---------------------------------------------------------------------------------------------- FR-FLOW-006

    // FR-FLOW-006 AC-1: a valid JWT, GET /auth/me -> 200 with the user's id and username; no passwordHash field and no
    // token version field
    @Test
    public void FR_FLOW_006_AC1_meIsThePublicProfile() throws Exception {
        start(b -> b);
        f.store.bumpTokenVersion(alice);
        ClientResponse r = f.get("/auth/me", "Authorization", bearer(alice));
        assertEquals(r.text(), 200, r.status());
        Map<String, Object> me = W8.object(r);
        assertEquals(alice, me.get("id"));
        assertEquals("alice", me.get("username"));
        for (String hidden : new String[] {"passwordHash", "tokenVersion", "ver"}) {
            assertFalse(hidden, me.containsKey(hidden));
        }
        assertFalse(r.text().contains("pbkdf2"));
    }

    // FR-FLOW-006 AC-2: with .allowedAttributes("displayName"), PATCH /auth/me {"attributes":{"displayName":"Al"}} ->
    // 200, the stored attribute is "Al", and beforeUserUpdate then onUserUpdated fire once each with origin SELF
    @Test
    public void FR_FLOW_006_AC2_anAllowedAttribute() throws Exception {
        start(b -> b.allowedAttributes("displayName"));
        events.calls.clear();
        ClientResponse r = send("PATCH", "/auth/me", "{\"attributes\":{\"displayName\":\"Al\"}}", "Authorization",
                bearer(alice));
        assertEquals(r.text(), 200, r.status());
        assertEquals("Al", f.stored(alice).getAttributes().get("displayName"));
        assertEquals("Al", ((Map<?, ?>) W8.object(r).get("attributes")).get("displayName"));
        assertEquals(Arrays.asList("beforeUserUpdate:SELF", "onUserUpdated[ATTRIBUTES]:SELF"), events.calls);
        assertTrue(events.hashes.toString(), events.hashes.isEmpty());
        // a null value removes the attribute
        assertEquals(200, send("PATCH", "/auth/me", "{\"attributes\":{\"displayName\":null}}", "Authorization",
                bearer(alice)).status());
        assertFalse(f.stored(alice).getAttributes().containsKey("displayName"));
    }

    // FR-FLOW-006 AC-3: PATCH /auth/me with an attribute not in the allowed list (email when only displayName is
    // allowed) -> 400 BAD_REQUEST from a Core HttpException; the stored user is unchanged and onUserUpdated does not fire
    @Test
    public void FR_FLOW_006_AC3_anAttributeNotAllowed() throws Exception {
        start(b -> b.allowedAttributes("displayName"));
        AuthUser before = f.stored(alice);
        events.calls.clear();
        ClientResponse r = send("PATCH", "/auth/me", "{\"attributes\":{\"displayName\":\"Al\",\"email\":\"a@x.example\"}}",
                "Authorization", bearer(alice));
        assertEquals(r.text(), 400, r.status());
        assertEquals("BAD_REQUEST", W8.code(r));
        assertEquals(before.getAttributes(), f.stored(alice).getAttributes());
        assertTrue(events.calls.toString(), events.calls.isEmpty());
        W8 none = new W8();
        others.add(none);
        String bob = none.user("bob", W8.PW);
        none.start(b -> b);
        ClientResponse nothingAllowed = none.adapter.send(ClientRequest.json("PATCH", "/auth/me",
                "{\"attributes\":{\"displayName\":\"Bo\"}}").header("Authorization",
                "Bearer " + none.auth.accessToken(bob, 0, 900_000L)));
        assertEquals("without .allowedAttributes nothing may change", 400, nothingAllowed.status());
    }

    // FR-FLOW-006 AC-4: a beforeUserUpdate listener throwing new AuthRejection(422, "name_policy") -> 422 JSON with
    // code name_policy and no change
    @Test
    public void FR_FLOW_006_AC4_aListenerRefuses() throws Exception {
        start(b -> b.allowedAttributes("displayName"));
        events.refuseUpdate = new AuthRejection(422, "name_policy", "not that name");
        ClientResponse r = send("PATCH", "/auth/me", "{\"attributes\":{\"displayName\":\"Al\"}}", "Authorization",
                bearer(alice));
        assertEquals(422, r.status());
        assertEquals("name_policy", W8.code(r));
        assertTrue(r.header("Content-Type").startsWith("application/json"));
        assertFalse(f.stored(alice).getAttributes().containsKey("displayName"));
    }

    // FR-FLOW-006 AC-5: with API keys on, PATCH /auth/me refuses key actors and GET /auth/me serves them
    // SEC-APIKEY-008 AC-2: a valid key on POST /auth/me/apikeys, GET /auth/me/apikeys, DELETE /auth/me/apikeys/k1 and
    // PATCH /auth/me each gets 403 api_key_not_allowed; GET /auth/me gets 200
    @Test
    public void FR_FLOW_006_AC5_SEC_APIKEY_008_AC2_keysOnTheMeRoutes() throws Exception {
        start(b -> b.apiKeys(keys).allowedAttributes("displayName"));
        IssuedApiKey mine = f.auth.apiKeys().issue(alice, "ci", null, "posts:read");
        String k = "ApiKey " + mine.getPlainKey();
        ClientResponse[] refused = {
            f.post("/auth/me/apikeys", Wave10PatTest.body(W8.PW, "posts:read"), "Authorization", k),
            f.get("/auth/me/apikeys", "Authorization", k),
            send("DELETE", "/auth/me/apikeys/" + mine.getKey().getId(), null, "Authorization", k),
            send("PATCH", "/auth/me", "{\"attributes\":{\"displayName\":\"Al\"}}", "Authorization", k)};
        for (ClientResponse r : refused) {
            assertEquals(r.text(), 403, r.status());
            assertEquals("api_key_not_allowed", W8.code(r));
            assertNull(r.header("WWW-Authenticate"));
        }
        assertNotNull(keys.d.findById(mine.getKey().getId()));
        assertFalse(f.stored(alice).getAttributes().containsKey("displayName"));
        assertEquals(1, keys.d.listByOwner(alice).size());
        ClientResponse me = f.get("/auth/me", "Authorization", k);
        assertEquals(200, me.status());
        assertEquals(alice, W8.object(me).get("id"));
    }

    // FR-FLOW-006 AC-6: PATCH /auth/me carrying a username field does not change the username
    @Test
    public void FR_FLOW_006_AC6_noRenameThroughMe() throws Exception {
        start(b -> b.allowedAttributes("displayName"));
        ClientResponse r = send("PATCH", "/auth/me", "{\"username\":\"mallory\",\"roles\":[\"admin\"],"
                + "\"attributes\":{\"displayName\":\"Al\"}}", "Authorization", bearer(alice));
        assertEquals(r.text(), 400, r.status()); // AC-6 amended by OPS-AUTH-033 AC-3: refused, never ignored
        assertTrue(r.text(), r.text().contains("rename"));
        assertEquals("alice", f.stored(alice).getUsername());
        assertEquals(new TreeSet<>(Arrays.asList("reader")), new TreeSet<>(f.stored(alice).getRoles()));
        assertNull(f.store.findByUsername("mallory"));
    }

    // ---------------------------------------------------------------------------------------------- SEC-APIKEY-008

    private static boolean hasValuedCookie(ClientResponse r) {
        return W8.plantsAuthCookie(r);
    }

    // SEC-APIKEY-008 AC-1: a valid key on POST /auth/refresh is refused by the endpoint restriction and no refresh
    // token is issued, in every transport mode (the key is judged before a refresh cookie is read)
    @Test
    public void SEC_APIKEY_008_AC1_noRefreshWithAKey() throws Exception {
        for (TokenTransport t : TokenTransport.values()) {
            W8 w = new W8();
            others.add(w);
            Wave9KeyAuthTest.Keys ks = new Wave9KeyAuthTest.Keys();
            String u = w.user("u", W8.PW, "reader");
            w.start(b -> b.transport(t).apiKeys(ks));
            ClientResponse login = w.post("/auth/login", W8.login("u", W8.PW), "Host", W8.HOST, "Origin", W8.SAME);
            String rt = t == TokenTransport.HEADER ? (String) W8.object(login).get("refreshToken")
                    : W8.cookieValue(login, "naru_rt");
            assertNotNull(t + " refresh token", rt);
            String k = "ApiKey " + w.auth.apiKeys().issue(u, "ci", null, "posts:read").getPlainKey();
            ClientResponse r = t == TokenTransport.HEADER
                    ? w.post("/auth/refresh", "{\"refreshToken\":\"" + rt + "\"}", "Authorization", k)
                    : w.post("/auth/refresh", null, "Authorization", k, "Host", W8.HOST, "Origin", W8.SAME,
                            "Cookie", "naru_rt=" + rt);
            assertEquals(t + " " + r.text(), 403, r.status());
            assertEquals("api_key_not_allowed", W8.code(r));
            assertNull(W8.object(r).get("refreshToken"));
            assertFalse(hasValuedCookie(r));
            ClientResponse still = t == TokenTransport.HEADER
                    ? w.post("/auth/refresh", "{\"refreshToken\":\"" + rt + "\"}")
                    : w.post("/auth/refresh", null, "Host", W8.HOST, "Origin", W8.SAME, "Cookie", "naru_rt=" + rt
                            + "; naru_csrf=" + W8.object(login).get("csrfToken"), "X-CSRF-Token",
                            (String) W8.object(login).get("csrfToken"));
            assertEquals(t + ": the refresh token was not used " + still.text(), 200, still.status());
        }
    }

    /** The Authorization value of a new key of {@code owner} with {@code scopes}. */
    private String keyOf(String owner, String... scopes) {
        return "ApiKey " + f.auth.apiKeys().issue(owner, "k", null, scopes).getPlainKey();
    }

    // SEC-APIKEY-008 AC-3: with .allowAdminScopesOnApiKeys(true) and a key holding auth-users:* and auth-apikeys:*
    // whose owner holds * through admin: GET /auth/admin/users -> 200; GET /auth/admin/apikeys?ownerUserId=u1 -> 200
    // with u1's keys and status; POST /auth/admin/apikeys -> 403 api_key_not_allowed; DELETE k2 of user X who holds
    // no scopes -> allowed (deleted, not 403); the same on a key whose owner holds * or posts:read (not covered by the
    // key's scopes) -> 403 insufficient_scope and the key stays
    @Test
    public void SEC_APIKEY_008_AC3_theOptInKeyOnTheKeyRoutes() throws Exception {
        start(b -> b.apiKeys(keys).allowAdminScopesOnApiKeys(true));
        String k = keyOf(root, "auth-users:*", "auth-apikeys:*");
        String x = f.user("x", W8.PW);
        String star = f.user("star", W8.PW, "admin");
        IssuedApiKey aliceKey = f.auth.apiKeys().issue(alice, "a", null, "posts:read");
        IssuedApiKey k2 = f.auth.apiKeys().issue(x, "k2", null, "posts:read");
        IssuedApiKey starKey = f.auth.apiKeys().issue(star, "s", null, "posts:read");
        assertEquals(200, f.get("/auth/admin/users", "Authorization", k).status());
        ClientResponse list = f.get("/auth/admin/apikeys?ownerUserId=" + alice, "Authorization", k);
        assertEquals(list.text(), 200, list.status());
        List<Object> items = Wave10PatTest.array(list);
        assertEquals(1, items.size());
        assertEquals("active", ((Map<?, ?>) items.get(0)).get("status"));
        ClientResponse post = f.post("/auth/admin/apikeys", Wave10PatTest.admin(x, W8.PW, "posts:read"),
                "Authorization", k);
        assertEquals(403, post.status());
        assertEquals("api_key_not_allowed", W8.code(post));
        ClientResponse del = send("DELETE", "/auth/admin/apikeys/" + k2.getKey().getId(), null, "Authorization", k);
        assertEquals(del.text(), 204, del.status());
        assertNull(keys.d.findById(k2.getKey().getId()));
        for (IssuedApiKey protectedKey : new IssuedApiKey[] {starKey, aliceKey}) {
            ClientResponse r = send("DELETE", "/auth/admin/apikeys/" + protectedKey.getKey().getId(), null,
                    "Authorization", k);
            assertEquals(r.text(), 403, r.status());
            assertEquals("insufficient_scope", W8.code(r));
            assertNotNull(keys.d.findById(protectedKey.getKey().getId()));
        }
    }

    private ClientResponse a4(String target, String json, String auth) {
        return send("PATCH", "/auth/admin/users/" + target, json, "Authorization", auth);
    }

    private ClientResponse a2(String json, String auth) {
        return f.post("/auth/admin/users", json, "Authorization", auth);
    }

    // SEC-APIKEY-008 AC-4: an opt-in key: A4 setting password on the key owner or on another user X -> 403
    // api_key_not_allowed; A4 enabling the key owner -> 403 api_key_not_allowed
    // SEC-APIKEY-008 AC-5: an opt-in key: A2 with password -> 403; A2 with neither password nor service-account flag
    // -> 403; A2 with only the service-account flag -> 201
    // SEC-APIKEY-008 AC-6: an opt-in key: A2 with the service-account flag and "password": null -> 201; A4 with
    // "password": null and other fields is not a password write and the other fields are decided by the grant rule
    @Test
    public void SEC_APIKEY_008_AC4_AC5_AC6_theOptInKeyOnA2AndA4() throws Exception {
        start(b -> b.apiKeys(keys).allowAdminScopesOnApiKeys(true));
        String owner = f.user("ops", W8.PW, "admin");
        String k = keyOf(owner, "auth-users:*");
        String x = f.user("x", W8.PW);
        String hashOwner = f.stored(owner).getPasswordHash();
        String hashX = f.stored(x).getPasswordHash();
        for (String target : new String[] {owner, x}) {
            ClientResponse r = a4(target, "{\"password\":\"a new password for it\"}", k);
            assertEquals(r.text(), 403, r.status());
            assertEquals("api_key_not_allowed", W8.code(r));
        }
        ClientResponse enable = a4(owner, "{\"enabled\":true}", k);
        assertEquals(403, enable.status());
        assertEquals("api_key_not_allowed", W8.code(enable));
        assertEquals(hashOwner, f.stored(owner).getPasswordHash());
        assertEquals(hashX, f.stored(x).getPasswordHash());
        ClientResponse withPw = a2("{\"username\":\"svc1\",\"serviceAccount\":true,\"password\":\"pw for a bot\"}", k);
        ClientResponse plain = a2("{\"username\":\"svc2\"}", k);
        for (ClientResponse r : new ClientResponse[] {withPw, plain}) {
            assertEquals(r.text(), 403, r.status());
            assertEquals("api_key_not_allowed", W8.code(r));
        }
        assertNull(f.store.findByUsername("svc1"));
        assertNull(f.store.findByUsername("svc2"));
        assertEquals(201, a2("{\"username\":\"svc3\",\"serviceAccount\":true}", k).status());
        ClientResponse nullPw = a2("{\"username\":\"svc4\",\"serviceAccount\":true,\"password\":null}", k);
        assertEquals(nullPw.text(), 201, nullPw.status());
        ClientResponse other = a4(x, "{\"password\":null,\"attributes\":{\"team\":\"blue\"}}", k);
        assertEquals(other.text(), 200, other.status());
        assertEquals("blue", f.stored(x).getAttributes().get("team"));
        assertEquals(hashX, f.stored(x).getPasswordHash());
        ClientResponse star = a4(root, "{\"password\":null,\"attributes\":{\"team\":\"red\"}}", k);
        assertEquals("the grant rule decides the other fields: root holds *", 403, star.status());
        assertEquals("insufficient_scope", W8.code(star));
    }

    // SEC-APIKEY-008 AC-7: a key whose scopes do not cover auth-users:write (posts:read, opt-in off) sending A2 with
    // the service-account flag and a non-null password -> 403 api_key_not_allowed, before 400 service_account_no_password
    // and the guard's 403 insufficient_scope; the same for an opt-in key holding auth-users:write
    @Test
    public void SEC_APIKEY_008_AC7_thePasswordBanComesFirst() throws Exception {
        String json = "{\"username\":\"svc\",\"serviceAccount\":true,\"password\":\"a password for a bot\"}";
        start(b -> b.apiKeys(keys));
        ClientResponse low = a2(json, keyOf(root, "posts:read"));
        assertEquals(low.text(), 403, low.status());
        assertEquals("api_key_not_allowed", W8.code(low));
        W8 on = new W8();
        others.add(on);
        Wave9KeyAuthTest.Keys ks = new Wave9KeyAuthTest.Keys();
        String admin = on.user("root", W8.PW, "admin");
        on.start(b -> b.apiKeys(ks).allowAdminScopesOnApiKeys(true));
        String k = "ApiKey " + on.auth.apiKeys().issue(admin, "k", null, "auth-users:write").getPlainKey();
        ClientResponse r = on.post("/auth/admin/users", json, "Authorization", k);
        assertEquals(r.text(), 403, r.status());
        assertEquals("api_key_not_allowed", W8.code(r));
        assertNull(f.store.findByUsername("svc"));
        assertNull(on.store.findByUsername("svc"));
    }

    // SEC-APIKEY-008 AC-8: .apiKeys(store), opt-in off, a local connection and a valid key K of an enabled owner u:
    // POST /auth/password (correct current password), POST /auth/login (valid credentials), POST /auth/logout and
    // ?all=true, POST /auth/register (.register(true)) and POST /auth/setup (open, with the logged token) each get 403
    // api_key_not_allowed with no WWW-Authenticate; changePassword is not called, hash and ver unchanged, no token,
    // refresh token or valued Set-Cookie, no user created, the setup token unused and no family revoked
    @Test
    public void SEC_APIKEY_008_AC8_noCredentialEndpointWithAKey() throws Exception {
        start(b -> b.apiKeys(keys).register(true).recovery(true));
        String setupToken = f.auth.pendingSetupToken();
        assertNotNull(setupToken);
        String rt = (String) f.loginBody("alice", W8.PW).get("refreshToken");
        String k = keyOf(alice, "posts:read");
        AuthUser before = f.stored(alice);
        for (int i = 0; i < 500 && !events.calls.contains("onLogin:null"); i++) {
            Thread.sleep(10); // onLogin of the login above runs after its response is written
        }
        events.calls.clear();
        ClientResponse[] all = {
            f.post("/auth/password", Wave9PasswordTest.change(W8.PW, "a brand new passphrase"), "Authorization", k),
            f.post("/auth/login", W8.login("alice", W8.PW), "Authorization", k),
            f.post("/auth/logout", null, "Authorization", k),
            f.post("/auth/logout?all=true", null, "Authorization", k),
            f.post("/auth/register", W8.login("neo", W8.PW), "Authorization", k),
            f.post("/auth/setup", "{\"token\":\"" + setupToken + "\",\"username\":\"alice\",\"password\":\"a "
                    + "recovered passphrase\"}", "Authorization", k)};
        for (ClientResponse r : all) {
            assertEquals(r.text(), 403, r.status());
            assertEquals("api_key_not_allowed", W8.code(r));
            assertNull(r.header("WWW-Authenticate"));
            assertFalse(hasValuedCookie(r));
            assertNull(W8.object(r).get("accessToken"));
            assertNull(W8.object(r).get("refreshToken"));
        }
        AuthUser after = f.stored(alice);
        assertEquals(before.getPasswordHash(), after.getPasswordHash());
        assertEquals(before.getTokenVersion(), after.getTokenVersion());
        assertNull(f.store.findByUsername("neo"));
        assertEquals("the setup token stays unused", setupToken, f.auth.pendingSetupToken());
        assertTrue(events.calls.toString(), events.calls.isEmpty());
        assertEquals("no family was revoked", 200, f.post("/auth/refresh", "{\"refreshToken\":\"" + rt + "\"}")
                .status());
    }

    // SEC-APIKEY-008 AC-9: opt-in off, a valid key K whose scopes are posts:read (owner holds * through admin), each with
    // a well-formed JSON body -> 403 api_key_not_allowed, not insufficient_scope, no WWW-Authenticate: A4 {"password"}
    // for another user X; A2 {"username","password"}; A2 {"username"} without the service-account flag; A4
    // {"enabled": true} for K's owner; no user row is created or changed and no password hash runs
    @Test
    public void SEC_APIKEY_008_AC9_bodyBansBeforeTheGuard() throws Exception {
        Wave8AccountTest.Counting counting = new Wave8AccountTest.Counting();
        start(b -> b.apiKeys(keys).passwordHasher(counting));
        String k = keyOf(root, "posts:read");
        String x = f.user("x", W8.PW);
        AuthUser xBefore = f.stored(x);
        AuthUser rootBefore = f.stored(root);
        int work = counting.hashes.get() + counting.verifies.get();
        ClientResponse[] all = {a4(x, "{\"password\":\"a new password for x\"}", k),
            a2("{\"username\":\"made\",\"password\":\"a password for made\"}", k), a2("{\"username\":\"made2\"}", k),
            a4(root, "{\"enabled\":true}", k)};
        for (ClientResponse r : all) {
            assertEquals(r.text(), 403, r.status());
            assertEquals("api_key_not_allowed", W8.code(r));
            assertNull(r.header("WWW-Authenticate"));
        }
        assertNull(f.store.findByUsername("made"));
        assertNull(f.store.findByUsername("made2"));
        assertEquals(xBefore.getPasswordHash(), f.stored(x).getPasswordHash());
        assertEquals(rootBefore.getTokenVersion(), f.stored(root).getTokenVersion());
        assertEquals(work, counting.hashes.get() + counting.verifies.get());
    }

    /** The MiniJson codec reporting a malformed body as an IOException, as the shipped Gson codec does. */
    static final class StrictCodec implements com.snoworca.naruserve.spi.JsonCodec {
        private final AdminFixture.MiniJsonCodec d = new AdminFixture.MiniJsonCodec();

        @Override
        public <T> T decode(java.io.Reader reader, java.lang.reflect.Type type) throws java.io.IOException {
            try {
                return d.decode(reader, type);
            } catch (IllegalArgumentException | IllegalStateException malformed) {
                throw new java.io.IOException(malformed.getMessage(), malformed);
            }
        }

        @Override
        public void encode(@Nullable Object value, java.io.Writer writer) throws java.io.IOException {
            d.encode(value, writer);
        }
    }

    private ClientResponse raw(W8 w, String path, String contentType, byte[] body, String key) {
        ClientRequest r = ClientRequest.of("POST", path).header("Authorization", key).header("Content-Type", contentType)
                .header("Content-Length", String.valueOf(body.length)).body(body);
        return w.adapter.send(r);
    }

    // SEC-APIKEY-008 AC-10: a non-JSON A2 body from a key actor: (a) opt-in off and a posts:read key, POST
    // /auth/admin/users with Content-Type text/plain and a non-empty body -> 403 insufficient_scope, not 415, with no
    // WWW-Authenticate; (b) opt-in on and an auth-users:write key, the same request -> 415 UNSUPPORTED_MEDIA_TYPE; no
    // user is created and no password hash runs in either case
    // SEC-APIKEY-008 AC-11: an unreadable JSON A2 body from a key actor applies no body-level ban: (a) the posts:read
    // key with a truncated body, [], an empty body with Content-Length 0 and a body over the JSON limit -> 403
    // insufficient_scope (not 400 or 413); (b) the auth-users:write opt-in key -> 400 BAD_REQUEST for the first three
    // and 413 for the oversized one; no user is created and no password hash runs
    @Test
    public void SEC_APIKEY_008_AC10_AC11_unreadableBodiesGoToTheGuard() throws Exception {
        Wave8AccountTest.Counting counting = new Wave8AccountTest.Counting();
        for (int way = 0; way < 2; way++) {
            final boolean optIn = way == 1;
            W8 w = new W8();
            others.add(w);
            Wave9KeyAuthTest.Keys ks = new Wave9KeyAuthTest.Keys();
            String admin = w.user("root", W8.PW, "admin");
            w.codec = new StrictCodec();
            w.routes = x -> x.app.jsonLimit(256);
            w.start(b -> b.apiKeys(ks).allowAdminScopesOnApiKeys(optIn).passwordHasher(counting));
            String k = "ApiKey " + w.auth.apiKeys().issue(admin, "k", null, optIn ? "auth-users:write" : "posts:read")
                    .getPlainKey();
            int work = counting.hashes.get() + counting.verifies.get();
            ClientResponse text = raw(w, "/auth/admin/users", "text/plain",
                    "username=made".getBytes(StandardCharsets.UTF_8), k);
            StringBuilder big = new StringBuilder("{\"username\":\"");
            while (big.length() < 400) {
                big.append('a');
            }
            big.append("\"}");
            String json = "application/json";
            ClientResponse truncated = raw(w, "/auth/admin/users", json, "{\"username\":".getBytes(StandardCharsets.UTF_8), k);
            ClientResponse array = raw(w, "/auth/admin/users", json, "[]".getBytes(StandardCharsets.UTF_8), k);
            ClientResponse empty = raw(w, "/auth/admin/users", json, new byte[0], k);
            ClientResponse over = raw(w, "/auth/admin/users", json, big.toString().getBytes(StandardCharsets.UTF_8), k);
            if (!optIn) {
                for (ClientResponse r : new ClientResponse[] {text, truncated, array, empty, over}) {
                    assertEquals(r.text(), 403, r.status());
                    assertEquals("insufficient_scope", W8.code(r));
                    assertNull(r.header("WWW-Authenticate"));
                }
            } else {
                assertEquals(text.text(), 415, text.status());
                assertEquals("UNSUPPORTED_MEDIA_TYPE", W8.code(text));
                for (ClientResponse r : new ClientResponse[] {truncated, array, empty}) {
                    assertEquals(r.text(), 400, r.status());
                    assertEquals("BAD_REQUEST", W8.code(r));
                }
                assertEquals(over.text(), 413, over.status());
            }
            assertNull(w.store.findByUsername("made"));
            assertEquals(work, counting.hashes.get() + counting.verifies.get());
        }
    }
}
