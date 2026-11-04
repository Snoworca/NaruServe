package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.AsyncRouteHandler;
import com.snoworca.naruserve.Registration;
import com.snoworca.naruserve.RegistrationKind;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.utils.auth.password.Pbkdf2PasswordHasher;
import com.snoworca.naruserve.utils.auth.user.AuthUser;
import com.snoworca.naruserve.utils.auth.user.ForwardingUserStore;
import com.snoworca.naruserve.utils.auth.user.InMemoryUserStore;
import com.snoworca.naruserve.utils.auth.user.UserPage;
import com.snoworca.naruserve.utils.auth.user.UserStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.After;
import org.junit.Test;

/**
 * POST /auth/password (SEC-FLOW-014): the self-service password change with its atomic hash swap and one retry
 * (REL-IAM-002), the per-user attempt limit, the events, the new pair, device token and CSRF token through the delivery
 * rule (FR-FLOW-002, SEC-FLOW-013), and the password policy before any hash (FR-TOKEN-004), over the in-memory adapter.
 */
public class Wave9PasswordTest {

    static final String NEW_PW = "a brand new passphrase";

    private final W8 f = new W8();
    private final Recorder events = new Recorder();
    private String alice;

    @After
    public void stop() {
        f.stop();
    }

    /** Records the user events with their origin. */
    static final class Recorder implements AuthEvents {
        final List<String> calls = new CopyOnWriteArrayList<>();
        final List<AuthUser> seen = new CopyOnWriteArrayList<>();
        volatile @Nullable AuthRejection refuse;

        @Override
        public void beforeUserUpdate(AuthUser before, AuthUser after, AuthEventContext ctx) {
            calls.add("beforeUserUpdate:" + ctx.getOrigin());
            seen.add(before);
            seen.add(after);
            AuthRejection r = refuse;
            if (r != null) {
                throw r;
            }
        }

        @Override
        public void onUserUpdated(AuthUser before, AuthUser after, Set<UserChange> changes, AuthEventContext ctx) {
            calls.add("onUserUpdated:" + ctx.getOrigin() + ":" + changes);
        }

        @Override
        public void onPasswordChanged(AuthUser user, AuthEventContext ctx) {
            calls.add("onPasswordChanged:" + ctx.getOrigin());
        }
    }

    private void start(TokenTransport t) throws Exception {
        start(t, b -> b);
    }

    private void start(TokenTransport t, java.util.function.UnaryOperator<Auth.Builder> more) throws Exception {
        alice = f.user("alice", W8.PW, "reader");
        f.start(b -> more.apply(b.transport(t).events(events)));
    }

    static String change(String current, String next) {
        return "{\"currentPassword\":\"" + current + "\",\"newPassword\":\"" + next + "\"}";
    }

    private ClientResponse bearerChange(String token, String json) {
        return f.post("/auth/password", json, "Authorization", "Bearer " + token);
    }

    private String hash(String pw) {
        return new Pbkdf2PasswordHasher(1000).hash(pw.toCharArray());
    }

    // ---------------------------------------------------------------------------------------------- SEC-FLOW-014

    // SEC-FLOW-014 AC-1: a valid Bearer, a correct currentPassword and a compliant newPassword -> 200 with a new pair
    // whose fam differs from the old family and a new deviceToken; the old access token is then rejected (ver + 1)
    // FR-FLOW-002 AC-4 (HEADER half): the same Bearer request -> accessToken, refreshToken and deviceToken in the body
    // and no Set-Cookie
    @Test
    public void SEC_FLOW_014_AC1_FR_FLOW_002_AC4_aNewPairInANewFamily() throws Exception {
        start(TokenTransport.HEADER);
        Map<String, Object> login = f.loginBody("alice", W8.PW);
        String at = (String) login.get("accessToken");
        long v = f.stored(alice).getTokenVersion();
        ClientResponse r = bearerChange(at, change(W8.PW, NEW_PW));
        assertEquals(r.text(), 200, r.status());
        Map<String, Object> body = W8.object(r);
        String at2 = (String) body.get("accessToken");
        assertNotNull(at2);
        assertNotNull(body.get("refreshToken"));
        assertNotNull(body.get("deviceToken"));
        assertNotEquals(login.get("deviceToken"), body.get("deviceToken"));
        assertNotEquals(f.fam(at), f.fam(at2));
        assertTrue(W8.setCookies(r).isEmpty());
        assertEquals("no-store", r.header("Cache-Control"));
        assertEquals(v + 1, f.stored(alice).getTokenVersion());
        assertTrue(f.auth.passwords().verify(NEW_PW.toCharArray(), f.stored(alice).getPasswordHash()));
        assertEquals(401, f.get("/api/x", "Authorization", "Bearer " + at).status());
        assertEquals(200, f.get("/api/x", "Authorization", "Bearer " + at2).status());
    }

    // SEC-FLOW-014 AC-2: a wrong current password -> 401 with the wrong-password code, the hash unchanged, and the
    // failure counts toward the SEC-FLOW-015 per-user limit (the sixth try within 15 minutes is 429)
    @Test
    public void SEC_FLOW_014_AC2_wrongCurrentPasswordCounts() throws Exception {
        start(TokenTransport.HEADER);
        String at = (String) f.loginBody("alice", W8.PW).get("accessToken");
        String before = f.stored(alice).getPasswordHash();
        for (int i = 0; i < DefaultAuthUsers.CONFIRM_MAX; i++) {
            ClientResponse r = bearerChange(at, change("not my password", NEW_PW));
            assertEquals(r.text(), 401, r.status());
            assertEquals("invalid_credentials", W8.code(r));
            assertNull(W8.object(r).get("accessToken"));
        }
        assertEquals(before, f.stored(alice).getPasswordHash());
        ClientResponse blocked = bearerChange(at, change(W8.PW, NEW_PW));
        assertEquals(blocked.text(), 429, blocked.status());
        assertEquals("too_many_attempts", W8.code(blocked));
        assertNotNull(blocked.header("Retry-After"));
        assertEquals(before, f.stored(alice).getPasswordHash());
    }

    // SEC-FLOW-014 AC-3: the token's ver differs from the re-read ver, or the user is disabled -> 401 and the password
    // is unchanged
    // REL-IAM-002 AC-2 (endpoint half): a re-read ver different from the token's -> 401 and no tokens; a disabled user
    // -> 401 without calling changePassword
    @Test
    public void SEC_FLOW_014_AC3_REL_IAM_002_AC2_aCutSessionCannotChangeThePassword() throws Exception {
        final AtomicInteger changes = new AtomicInteger();
        f.users = new ForwardingUserStore(f.store) {
            @Override
            public boolean changePassword(String id, @Nullable String expectedOldHash, long expectedVer,
                    String newHash) throws Exception {
                changes.incrementAndGet();
                return super.changePassword(id, expectedOldHash, expectedVer, newHash);
            }
        };
        start(TokenTransport.HEADER, b -> b.userCacheTtl(0));
        String at = (String) f.loginBody("alice", W8.PW).get("accessToken");
        String before = f.stored(alice).getPasswordHash();
        // the row moves on before the request (another node's ?all=true, a disable): the principal load refuses it
        f.store.bumpTokenVersion(alice);
        ClientResponse moved = bearerChange(at, change(W8.PW, NEW_PW));
        assertEquals(401, moved.status());
        assertNull(W8.object(moved).get("accessToken"));
        String at2 = (String) f.loginBody("alice", W8.PW).get("accessToken");
        f.store.setEnabled(alice, false);
        ClientResponse disabled = bearerChange(at2, change(W8.PW, NEW_PW));
        assertEquals(401, disabled.status());
        assertNull(W8.object(disabled).get("accessToken"));
        assertEquals(0, changes.get());
        assertEquals(before, f.stored(alice).getPasswordHash());
    }

    // SEC-FLOW-014 AC-3 / REL-IAM-002 AC-2 (re-read inside the endpoint): the principal passes the middleware, then the
    // row changes before the endpoint re-reads it -> 401 and changePassword is not called
    @Test
    public void SEC_FLOW_014_AC3_REL_IAM_002_AC2_theEndpointReReadsTheRow() throws Exception {
        final AtomicInteger changes = new AtomicInteger();
        final AtomicInteger reads = new AtomicInteger();
        final String[] id = new String[1];
        final boolean[] disable = new boolean[1];
        f.users = new ForwardingUserStore(f.store) {
            @Override
            public AuthUser findById(String uid) throws Exception {
                AuthUser u = super.findById(uid);
                if (u != null && uid.equals(id[0]) && reads.incrementAndGet() == 2) {
                    // moves on between the principal load and the endpoint re-read
                    if (disable[0]) {
                        f.store.setEnabled(uid, false);
                    } else {
                        f.store.bumpTokenVersion(uid);
                    }
                    return super.findById(uid);
                }
                return u;
            }

            @Override
            public boolean changePassword(String uid, @Nullable String expectedOldHash, long expectedVer,
                    String newHash) throws Exception {
                changes.incrementAndGet();
                return super.changePassword(uid, expectedOldHash, expectedVer, newHash);
            }
        };
        start(TokenTransport.HEADER, b -> b.userCacheTtl(0));
        String at = (String) f.loginBody("alice", W8.PW).get("accessToken");
        id[0] = alice;
        reads.set(0);
        ClientResponse r = bearerChange(at, change(W8.PW, NEW_PW));
        assertEquals(r.text(), 401, r.status());
        assertEquals(0, changes.get());
        assertNull(W8.object(r).get("accessToken"));
        // the re-read user is disabled: 401 without calling changePassword
        String at2 = (String) f.loginBody("alice", W8.PW).get("accessToken");
        disable[0] = true;
        reads.set(0);
        ClientResponse d = bearerChange(at2, change(W8.PW, NEW_PW));
        assertEquals(d.text(), 401, d.status());
        assertEquals(0, changes.get());
        assertNull(W8.object(d).get("accessToken"));
    }

    /** A store whose first changePassword loses a race to {@code race}, which runs just before it. */
    static final class Racing extends ForwardingUserStore {
        final AtomicInteger calls = new AtomicInteger();
        volatile @Nullable Race race;

        interface Race {
            void run(String id) throws Exception;
        }

        Racing(UserStore d) {
            super(d);
        }

        @Override
        public boolean changePassword(String id, @Nullable String expectedOldHash, long expectedVer, String newHash)
                throws Exception {
            Race r = race;
            if (calls.incrementAndGet() == 1 && r != null) {
                r.run(id);
            }
            return super.changePassword(id, expectedOldHash, expectedVer, newHash);
        }
    }

    // SEC-FLOW-014 AC-4: changePassword returns false because a concurrent rehash changed the hash and the current
    // password verifies the newly read hash -> one retry succeeds (200); when it does not verify (an admin reset came
    // first) -> 409 and no tokens
    // REL-IAM-002 AC-3 (endpoint): the rehash race is retried once with the new hash as expectation
    // REL-IAM-002 AC-4 (endpoint): the admin-reset race answers 409 without a second write
    @Test
    public void SEC_FLOW_014_AC4_oneRetryOnlyForARehash() throws Exception {
        final Racing racing = new Racing(f.store);
        f.users = racing;
        start(TokenTransport.HEADER);
        String at = (String) f.loginBody("alice", W8.PW).get("accessToken");
        racing.race = id -> {
            AuthUser u = f.store.findById(id);
            assertTrue(f.store.updatePasswordHash(id, u.getPasswordHash(), hash(W8.PW))); // a rehash: same password
        };
        ClientResponse ok = bearerChange(at, change(W8.PW, NEW_PW));
        assertEquals(ok.text(), 200, ok.status());
        assertEquals(2, racing.calls.get());
        assertTrue(f.auth.passwords().verify(NEW_PW.toCharArray(), f.stored(alice).getPasswordHash()));

        String at2 = (String) W8.object(ok).get("accessToken");
        racing.calls.set(0);
        racing.race = id -> {
            AuthUser u = f.store.findById(id);
            assertTrue(f.store.changePassword(id, u.getPasswordHash(), u.getTokenVersion(), hash("reset by admin!")));
        };
        ClientResponse conflict = bearerChange(at2, change(NEW_PW, "yet another passphrase"));
        assertEquals(conflict.text(), 409, conflict.status());
        assertEquals("write_conflict", W8.code(conflict));
        assertEquals(1, racing.calls.get());
        assertNull(W8.object(conflict).get("accessToken"));
        assertTrue(f.auth.passwords().verify("reset by admin!".toCharArray(), f.stored(alice).getPasswordHash()));
    }

    // SEC-FLOW-014 AC-5: COOKIE, naru_at of family F with F's X-CSRF-Token and a same-origin Origin -> 200 setting
    // naru_at, naru_rt, naru_csrf of the new family F2 and naru_device, body csrfToken only; a following POST with the
    // new csrfToken -> 201, with the old F csrf token -> 403 csrf_failed
    // SEC-FLOW-013 AC-10 (password half): the password change sets naru_device with the login attributes
    @Test
    public void SEC_FLOW_014_AC5_cookieDeliveryInANewFamily() throws Exception {
        start(TokenTransport.COOKIE);
        ClientResponse login = f.post("/auth/login", W8.login("alice", W8.PW), "Host", W8.HOST, "Origin", W8.SAME);
        String at = W8.cookieValue(login, "naru_at");
        String csrf = (String) W8.object(login).get("csrfToken");
        ClientResponse r = f.post("/auth/password", change(W8.PW, NEW_PW), "Host", W8.HOST, "Origin", W8.SAME,
                "Cookie", "naru_at=" + at + "; naru_csrf=" + csrf, "X-CSRF-Token", csrf);
        assertEquals(r.text(), 200, r.status());
        String at2 = W8.cookieValue(r, "naru_at");
        String csrf2 = (String) W8.object(r).get("csrfToken");
        assertNotNull(at2);
        assertNotNull(W8.cookieValue(r, "naru_rt"));
        assertNotNull(W8.cookieValue(r, "naru_csrf"));
        assertNotNull(W8.cookieValue(r, "naru_device"));
        String device = W8.setCookie(r, "naru_device");
        assertTrue(device, device.contains("HttpOnly") && device.contains("SameSite=Strict")
                && device.contains("Path=/auth/login") && device.contains("Max-Age=7776000"));
        Map<String, Object> body = W8.object(r);
        assertNull(body.get("accessToken"));
        assertNull(body.get("refreshToken"));
        assertNull(body.get("deviceToken"));
        assertNotEquals(f.fam(at), f.fam(at2));
        ClientResponse fresh = f.post("/api/x", "{}", "Host", W8.HOST, "Origin", W8.SAME, "Cookie",
                "naru_at=" + at2 + "; naru_csrf=" + csrf2, "X-CSRF-Token", csrf2);
        assertEquals(fresh.text(), 201, fresh.status());
        ClientResponse stale = f.post("/api/x", "{}", "Host", W8.HOST, "Origin", W8.SAME, "Cookie",
                "naru_at=" + at2 + "; naru_csrf=" + csrf, "X-CSRF-Token", csrf);
        assertEquals(403, stale.status());
        assertEquals("csrf_failed", W8.code(stale));
    }

    // FR-FLOW-002 AC-4 (BOTH): Authorization Bearer -> new accessToken, refreshToken and deviceToken in the body and no
    // Set-Cookie; naru_at with its family's X-CSRF-Token and a same-origin Origin -> naru_at, naru_rt, naru_csrf and
    // naru_device cookies with body csrfToken only
    @Test
    public void FR_FLOW_002_AC4_bothModeDeliversTheWayTheCredentialCame() throws Exception {
        start(TokenTransport.BOTH);
        String at = (String) W8.object(f.post("/auth/login", W8.login("alice", W8.PW))).get("accessToken");
        ClientResponse bearer = bearerChange(at, change(W8.PW, NEW_PW));
        assertEquals(bearer.text(), 200, bearer.status());
        assertNotNull(W8.object(bearer).get("accessToken"));
        assertNotNull(W8.object(bearer).get("refreshToken"));
        assertNotNull(W8.object(bearer).get("deviceToken"));
        assertTrue(W8.setCookies(bearer).isEmpty());
        ClientResponse login = f.post("/auth/login", W8.login("alice", NEW_PW), "Host", W8.HOST, "Origin", W8.SAME);
        String cat = W8.cookieValue(login, "naru_at");
        String csrf = (String) W8.object(login).get("csrfToken");
        ClientResponse cookie = f.post("/auth/password", change(NEW_PW, W8.PW + " again"), "Host", W8.HOST, "Origin",
                W8.SAME, "Cookie", "naru_at=" + cat + "; naru_csrf=" + csrf, "X-CSRF-Token", csrf);
        assertEquals(cookie.text(), 200, cookie.status());
        assertNotNull(W8.cookieValue(cookie, "naru_at"));
        assertNotNull(W8.cookieValue(cookie, "naru_rt"));
        assertNotNull(W8.cookieValue(cookie, "naru_csrf"));
        assertNotNull(W8.cookieValue(cookie, "naru_device"));
        assertNotNull(W8.object(cookie).get("csrfToken"));
        assertNull(W8.object(cookie).get("accessToken"));
        assertNull(W8.object(cookie).get("refreshToken"));
        assertNull(W8.object(cookie).get("deviceToken"));
    }

    // SEC-FLOW-014 AC-6: events fire as beforeUserUpdate (origin SELF), then onUserUpdated and onPasswordChanged
    @Test
    public void SEC_FLOW_014_AC6_selfEvents() throws Exception {
        start(TokenTransport.HEADER);
        String at = (String) f.loginBody("alice", W8.PW).get("accessToken");
        assertEquals(200, bearerChange(at, change(W8.PW, NEW_PW)).status());
        assertEquals("[beforeUserUpdate:SELF, onUserUpdated:SELF:[PASSWORD], onPasswordChanged:SELF]",
                events.calls.toString());
        for (AuthUser u : events.seen) {
            assertNull(u.getPasswordHash());
        }
    }

    // SEC-FLOW-014 AC-7: the route is registered with methodAsync and its hashing takes the shared hash slot
    @Test
    public void SEC_FLOW_014_AC7_asyncAndInTheHashSlot() throws Exception {
        start(TokenTransport.HEADER);
        int async = 0;
        for (Registration r : f.app.registrations()) {
            if (r.getKind() == RegistrationKind.ROUTE && "/auth/password".equals(r.getPath())
                    && "POST".equals(r.getMethod())) {
                assertTrue(r.getTarget() instanceof AsyncRouteHandler);
                async++;
            }
        }
        assertEquals(1, async);
        String at = (String) f.loginBody("alice", W8.PW).get("accessToken");
        long slots = f.auth.hashSlots().requested();
        assertEquals(200, bearerChange(at, change(W8.PW, NEW_PW)).status());
        assertTrue("the change took the shared slot", f.auth.hashSlots().requested() > slots);
    }

    // SEC-FLOW-014 AC-8: a beforeUserUpdate listener throwing AuthRejection(403, "password_change_locked") -> 403 JSON
    // password_change_locked; the listener saw origin SELF and neither hash nor plaintext; changePassword is not called,
    // hash and ver are unchanged (the old access token still works), no tokens, no onUserUpdated or onPasswordChanged
    @Test
    public void SEC_FLOW_014_AC8_aBeforeListenerCanRefuse() throws Exception {
        final Racing racing = new Racing(f.store);
        f.users = racing;
        start(TokenTransport.HEADER);
        String at = (String) f.loginBody("alice", W8.PW).get("accessToken");
        AuthUser before = f.stored(alice);
        events.refuse = new AuthRejection(403, "password_change_locked");
        ClientResponse r = bearerChange(at, change(W8.PW, NEW_PW));
        assertEquals(r.text(), 403, r.status());
        assertEquals("password_change_locked", W8.code(r));
        assertEquals("[beforeUserUpdate:SELF]", events.calls.toString());
        for (AuthUser u : events.seen) {
            assertNull(u.getPasswordHash());
            assertFalse(u.toString().contains(W8.PW));
            assertFalse(u.toString().contains(NEW_PW));
        }
        assertEquals(0, racing.calls.get());
        assertEquals(before.getPasswordHash(), f.stored(alice).getPasswordHash());
        assertEquals(before.getTokenVersion(), f.stored(alice).getTokenVersion());
        assertEquals(200, f.get("/api/x", "Authorization", "Bearer " + at).status());
        assertNull(W8.object(r).get("accessToken"));
    }

    // SEC-FLOW-014 AC-9: COOKIE, and BOTH without an Authorization header, a valid naru_at of family F and a correct
    // current password: without X-CSRF-Token -> 403 csrf_failed; with F's X-CSRF-Token but Origin
    // https://evil.example -> 403 csrf_failed; changePassword is not called, hash and ver unchanged, no Set-Cookie
    @Test
    public void SEC_FLOW_014_AC9_csrfAndOriginOnTheCookieSession() throws Exception {
        for (TokenTransport t : new TokenTransport[] {TokenTransport.COOKIE, TokenTransport.BOTH}) {
            W8 w = new W8();
            Racing racing = new Racing(w.store);
            w.users = racing;
            try {
                String id = w.user("alice", W8.PW, "reader");
                w.start(b -> b.transport(t));
                ClientResponse login = w.post("/auth/login", W8.login("alice", W8.PW), "Host", W8.HOST, "Origin",
                        W8.SAME);
                String at = W8.cookieValue(login, "naru_at");
                String csrf = (String) W8.object(login).get("csrfToken");
                AuthUser before = w.stored(id);
                ClientResponse noCsrf = w.post("/auth/password", change(W8.PW, NEW_PW), "Host", W8.HOST, "Origin",
                        W8.SAME, "Cookie", "naru_at=" + at + "; naru_csrf=" + csrf);
                ClientResponse evil = w.post("/auth/password", change(W8.PW, NEW_PW), "Host", W8.HOST, "Origin",
                        W8.EVIL, "Cookie", "naru_at=" + at + "; naru_csrf=" + csrf, "X-CSRF-Token", csrf);
                for (ClientResponse r : new ClientResponse[] {noCsrf, evil}) {
                    assertEquals(t + " " + r.text(), 403, r.status());
                    assertEquals("csrf_failed", W8.code(r));
                    assertTrue(W8.setCookies(r).isEmpty());
                }
                assertEquals(0, racing.calls.get());
                assertEquals(before.getPasswordHash(), w.stored(id).getPasswordHash());
                assertEquals(before.getTokenVersion(), w.stored(id).getTokenVersion());
            } finally {
                w.stop();
            }
        }
    }

    // ---------------------------------------------------------------------------------------------- carried

    // SEC-FLOW-013 AC-3: after a password change the old device token is no longer trusted (its ver is gone), and the
    // password-change response carries a new device token that is trusted for the next login
    @Test
    public void SEC_FLOW_013_AC3_aPasswordChangeReplacesTheDeviceToken() throws Exception {
        start(TokenTransport.HEADER);
        Map<String, Object> login = f.loginBody("alice", W8.PW);
        String oldDevice = (String) login.get("deviceToken");
        assertNotNull(f.auth.deviceTokens().read(oldDevice, "alice"));
        ClientResponse r = bearerChange((String) login.get("accessToken"), change(W8.PW, NEW_PW));
        assertEquals(200, r.status());
        String newDevice = (String) W8.object(r).get("deviceToken");
        assertNotNull(newDevice);
        AuthUser now = f.stored(alice);
        assertNotEquals(now.getTokenVersion(), f.auth.deviceTokens().read(oldDevice, "alice").getTokenVersion());
        assertEquals(now.getTokenVersion(), f.auth.deviceTokens().read(newDevice, "alice").getTokenVersion());
        assertEquals(alice, f.auth.deviceTokens().read(newDevice, "alice").getUserId());
        assertFalse(f.auth.deviceTrust().isInvalidated(f.auth.deviceTokens().read(newDevice, "alice")));
        // the trust decision of a login (SEC-FLOW-012 step 3): the old token is not trusted, the new one is
        PasswordLogin.Outcome viaOld = Wave7LoginTest.attempt(f.auth.passwordLogin(), "alice", NEW_PW, "10.9.0.1",
                oldDevice);
        assertEquals(PasswordLogin.Kind.SUCCESS, viaOld.kind);
        assertFalse(viaOld.trustedDevice);
        PasswordLogin.Outcome viaNew = Wave7LoginTest.attempt(f.auth.passwordLogin(), "alice", NEW_PW, "10.9.0.1",
                newDevice);
        assertTrue(viaNew.trustedDevice);
    }

    /** A store implementing only the five required methods; update() can silently not apply. */
    static final class FiveMethods implements UserStore {
        final InMemoryUserStore d = new InMemoryUserStore();
        final AtomicInteger updates = new AtomicInteger();
        volatile boolean dropUpdates;

        @Override
        public @Nullable AuthUser findByUsername(String username) {
            return d.findByUsername(username);
        }

        @Override
        public @Nullable AuthUser findById(String id) {
            return d.findById(id);
        }

        @Override
        public AuthUser create(AuthUser user) throws Exception {
            return d.create(user);
        }

        @Override
        public @Nullable AuthUser update(AuthUser user) throws Exception {
            if (dropUpdates) {
                updates.incrementAndGet();
                return d.findById(user.getId()); // reports success, writes nothing
            }
            return d.update(user);
        }

        @Override
        public boolean delete(String id) {
            return d.delete(id);
        }

        @Override
        public UserPage list(int offset, int limit, @Nullable String search) {
            return d.list(offset, limit, search);
        }
    }

    // REL-IAM-002 AC-7 (endpoint): a five-method store whose update() silently does not apply: the default
    // changePassword calls update once, the re-read shows the old hash and ver, POST /auth/password answers 503
    // security_write_conflict and no token pair
    @Test
    public void REL_IAM_002_AC7_anUnconfirmedWriteIs503() throws Exception {
        FiveMethods five = new FiveMethods();
        f.users = five;
        alice = five.d.create(AuthUser.builder().username("alice").passwordHash(hash(W8.PW)).roles("reader").build())
                .getId();
        f.start(b -> b);
        String at = (String) f.loginBody("alice", W8.PW).get("accessToken");
        AuthUser before = five.d.findById(alice);
        five.dropUpdates = true;
        ClientResponse r = bearerChange(at, change(W8.PW, NEW_PW));
        assertEquals(r.text(), 503, r.status());
        assertEquals("security_write_conflict", W8.code(r));
        assertEquals("the default changePassword calls update once, no retry", 1, five.updates.get());
        assertNull(W8.object(r).get("accessToken"));
        assertEquals(before.getPasswordHash(), five.d.findById(alice).getPasswordHash());
        assertEquals(before.getTokenVersion(), five.d.findById(alice).getTokenVersion());
    }

    // FR-TOKEN-004 AC-1, AC-2, AC-3 (endpoint): a new password shorter than the minimum, longer than 128 characters,
    // or empty is refused on POST /auth/password with zero hasher calls
    @Test
    public void FR_TOKEN_004_AC1_AC2_AC3_theEndpointChecksBeforeHashing() throws Exception {
        Wave8AccountTest.Counting counting = new Wave8AccountTest.Counting();
        start(TokenTransport.HEADER, b -> b.passwordHasher(counting));
        String at = (String) f.loginBody("alice", W8.PW).get("accessToken");
        StringBuilder longPw = new StringBuilder();
        for (int i = 0; i < 129; i++) {
            longPw.append('a');
        }
        List<String> answers = new ArrayList<>();
        for (String pw : new String[] {"short", longPw.toString(), ""}) {
            int work = counting.hashes.get() + counting.verifies.get();
            ClientResponse r = bearerChange(at, change(W8.PW, pw));
            answers.add(r.status() + " " + W8.code(r));
            assertEquals(work, counting.hashes.get() + counting.verifies.get());
        }
        assertEquals("[422 UNPROCESSABLE_ENTITY, 422 UNPROCESSABLE_ENTITY, 422 UNPROCESSABLE_ENTITY]",
                answers.toString());
        assertTrue(f.auth.passwords().verify(W8.PW.toCharArray(), f.stored(alice).getPasswordHash()));
    }

    // FR-TOKEN-004 AC-5 (endpoint): a throwing BreachedPasswordChecker -> the new password is not stored, zero hasher
    // calls, and the exception message is not in the response body
    @Test
    public void FR_TOKEN_004_AC5_aThrowingCheckerStoresNothing() throws Exception {
        Wave8AccountTest.Counting counting = new Wave8AccountTest.Counting();
        start(TokenTransport.HEADER, b -> b.passwordHasher(counting).breachedPasswordChecker(pw -> {
            throw new IllegalStateException("breach service secret detail");
        }));
        String at = (String) f.loginBody("alice", W8.PW).get("accessToken");
        int work = counting.hashes.get() + counting.verifies.get();
        String before = f.stored(alice).getPasswordHash();
        ClientResponse r = bearerChange(at, change(W8.PW, NEW_PW));
        assertEquals(503, r.status());
        assertFalse(r.text().contains("secret detail"));
        assertEquals(work, counting.hashes.get() + counting.verifies.get());
        assertEquals(before, f.stored(alice).getPasswordHash());
    }

    // SEC-FLOW-014 statement: a request without the current-password field -> 400 BAD_REQUEST, no hash
    @Test
    public void missingFieldsAre400() throws Exception {
        start(TokenTransport.HEADER);
        String at = (String) f.loginBody("alice", W8.PW).get("accessToken");
        ClientResponse r = bearerChange(at, "{\"newPassword\":\"" + NEW_PW + "\"}");
        assertEquals(400, r.status());
        assertEquals("BAD_REQUEST", W8.code(r));
        ClientResponse empty = bearerChange(at, change("", NEW_PW));
        assertEquals(400, empty.status());
    }
}
