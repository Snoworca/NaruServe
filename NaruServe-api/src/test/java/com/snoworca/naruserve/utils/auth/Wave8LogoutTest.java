package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.utils.auth.spi.RefreshRecord;
import com.snoworca.naruserve.utils.auth.spi.RefreshTokenStore;
import com.snoworca.naruserve.utils.auth.testing.AuthTesting;
import com.snoworca.naruserve.utils.auth.user.AuthUser;
import com.snoworca.naruserve.utils.auth.user.ForwardingUserStore;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.After;
import org.junit.Test;

/**
 * POST /auth/logout: the identity decision table with per-source CSRF (SEC-FLOW-010) and the outcomes (FR-FLOW-003),
 * over a refresh store that records every revokeFamily and can fail its find.
 */
public class Wave8LogoutTest {

    /** The default in-memory refresh store, recording revocations and markUsed calls; find can be made to fail. */
    static final class Recording implements RefreshTokenStore {
        final InMemoryRefreshTokenStore d = new InMemoryRefreshTokenStore();
        final List<String> revoked = new CopyOnWriteArrayList<>();
        final AtomicInteger marks = new AtomicInteger();
        final AtomicBoolean failFind = new AtomicBoolean();

        @Override
        public void save(RefreshRecord record) {
            d.save(record);
        }

        @Override
        public @Nullable RefreshRecord find(String tokenId) throws Exception {
            if (failFind.get()) {
                throw new java.sql.SQLException("connection reset");
            }
            return d.find(tokenId);
        }

        @Override
        public boolean markUsed(String tokenId, long nowMillis) {
            marks.incrementAndGet();
            return d.markUsed(tokenId, nowMillis);
        }

        @Override
        public int revokeFamily(String familyId) {
            revoked.add(familyId);
            return d.revokeFamily(familyId);
        }
    }

    /** Records onLogout calls. */
    static final class Logouts implements AuthEvents {
        final List<String> calls = new CopyOnWriteArrayList<>();
        final AtomicInteger reused = new AtomicInteger();

        @Override
        public void onLogout(String userId, boolean allDevices, AuthEventContext ctx) {
            calls.add(userId + ":" + allDevices);
        }

        @Override
        public void onRefreshTokenReused(String userId, String familyId, AuthEventContext ctx) {
            reused.incrementAndGet();
        }
    }

    /** A store whose ver read (findById) can be made to fail. */
    static final class FlakyUsers extends ForwardingUserStore {
        final AtomicBoolean fail = new AtomicBoolean();

        FlakyUsers(com.snoworca.naruserve.utils.auth.user.UserStore d) {
            super(d);
        }

        @Override
        public AuthUser findById(String id) throws Exception {
            if (fail.get()) {
                throw new java.sql.SQLException("read timeout");
            }
            return delegate().findById(id);
        }

        @Override
        public boolean supportsAtomicFieldWrites() {
            return true;
        }
    }

    private final W8 f = new W8();
    private final Recording refresh = new Recording();
    private final Logouts events = new Logouts();
    private String alice;
    private String bob;

    @After
    public void stop() {
        f.stop();
    }

    private void start(TokenTransport t) throws Exception {
        alice = f.user("alice", W8.PW, "reader");
        bob = f.user("bob", W8.PW, "reader");
        f.start(b -> b.transport(t).refreshTokens(refresh).events(events));
    }

    /** A login of {@code name}: [accessToken, refreshToken, csrfToken or null], body or cookie delivery. */
    private String[] login(String name) {
        ClientResponse r = f.post("/auth/login", W8.login(name, W8.PW), "Host", W8.HOST, "Origin", W8.SAME);
        assertEquals(r.text(), 200, r.status());
        Map<String, Object> body = W8.object(r);
        if (body.containsKey("accessToken")) {
            return new String[] {(String) body.get("accessToken"), (String) body.get("refreshToken"), null};
        }
        return new String[] {W8.cookieValue(r, "naru_at"), W8.cookieValue(r, "naru_rt"), (String) body.get("csrfToken")};
    }

    private ClientResponse logout(String query, @Nullable String json, String... headers) {
        return f.post("/auth/logout" + query, json, headers);
    }

    private static String rtBody(String rt) {
        return "{\"refreshToken\":\"" + rt + "\"}";
    }

    private long ver(String id) throws Exception {
        return f.stored(id).getTokenVersion();
    }

    /** True when {@code rt} still refreshes (it is rotated by the check). */
    private boolean usable(String rt) {
        try {
            f.auth.tokens().refresh(rt);
            return true;
        } catch (AuthRejection r) {
            return false;
        }
    }

    private static void cleared(ClientResponse r) {
        for (String n : new String[] {"naru_at", "naru_rt", "naru_csrf"}) {
            String c = W8.setCookie(r, n);
            assertNotNull(n + " cleared", c);
            assertTrue(c, c.contains("Max-Age=0"));
        }
    }

    // ---------------------------------------------------------------------------------------------- SEC-FLOW-010 ①

    // SEC-FLOW-010 AC-1: BOTH, the same user's body refreshToken (family F) and naru_rt (family G), no CSRF, no Origin
    // -> 204, F and G both revoked
    @Test
    public void SEC_FLOW_010_AC1_bodyTokenAlsoRevokesTheSameUsersCookieFamily() throws Exception {
        start(TokenTransport.BOTH);
        String[] fBody = new String[] {null, (String) f.loginBody("alice", W8.PW).get("refreshToken")};
        String[] g = login("alice"); // cookie delivery: a naru_rt of family G
        ClientResponse r = logout("", rtBody(fBody[1]), "Cookie", "naru_rt=" + g[1]);
        assertEquals(r.text(), 204, r.status());
        assertEquals(2, refresh.revoked.size());
        assertFalse(usable(fBody[1]));
        assertFalse(usable(g[1]));
    }

    // SEC-FLOW-010 AC-2: BOTH, body token of family F and a naru_rt of another user's family H -> only F revoked
    @Test
    public void SEC_FLOW_010_AC2_anotherUsersCookieFamilyStays() throws Exception {
        start(TokenTransport.BOTH);
        String rt = (String) f.loginBody("alice", W8.PW).get("refreshToken");
        String[] h = login("bob");
        assertEquals(204, logout("", rtBody(rt), "Cookie", "naru_rt=" + h[1]).status());
        assertEquals(1, refresh.revoked.size());
        assertTrue(usable(h[1]));
    }

    // SEC-FLOW-010 AC-3: BOTH, an expired body token plus a valid naru_rt -> 204 with no identity, G stays usable;
    // with ?all=true -> 401 invalid_token. FR-FLOW-003 AC-8: that 401 leaves ver unchanged although the unconsulted
    // naru_rt is valid.
    @Test
    public void SEC_FLOW_010_AC3_expiredBodyTokenEndsTheSearch() throws Exception {
        start(TokenTransport.BOTH);
        String expired = (String) f.loginBody("alice", W8.PW).get("refreshToken");
        f.clock.addAndGet(f.auth.tokens().getRefreshTtl() + 120_000L); // past exp and the 60 s leeway
        String[] g = login("alice");
        long v = ver(alice);
        ClientResponse plain = logout("", rtBody(expired), "Cookie", "naru_rt=" + g[1]);
        assertEquals(204, plain.status());
        assertTrue(refresh.revoked.isEmpty());
        assertTrue(events.calls.isEmpty());
        ClientResponse all = logout("?all=true", rtBody(expired), "Cookie", "naru_rt=" + g[1]);
        assertEquals(401, all.status());
        assertEquals("invalid_token", W8.code(all));
        assertEquals(v, ver(alice));
        assertTrue(usable(g[1]));
    }

    // SEC-FLOW-010 AC-4: the body holds rt1 of family F already used by rotation, plus a valid Bearer at2 of F -> 204
    // with no identity; F is not revoked and onRefreshTokenReused is not fired
    @Test
    public void SEC_FLOW_010_AC4_usedRefreshTokenGivesNoIdentity() throws Exception {
        start(TokenTransport.HEADER);
        String rt1 = (String) f.loginBody("alice", W8.PW).get("refreshToken");
        TokenPair rotated = f.auth.tokens().refresh(rt1);
        int marks = refresh.marks.get();
        ClientResponse r = logout("", rtBody(rt1), "Authorization", "Bearer " + rotated.getAccessToken());
        assertEquals(204, r.status());
        assertTrue(refresh.revoked.isEmpty());
        assertEquals(0, events.reused.get());
        assertEquals("logout never marks a token used", marks, refresh.marks.get());
    }

    // ---------------------------------------------------------------------------------------------- SEC-FLOW-010 ②

    // SEC-FLOW-010 AC-5: COOKIE, valid naru_rt with matching X-CSRF-Token and a same-origin Origin -> its family
    // revoked; without X-CSRF-Token -> 403 and nothing revoked; ?all=true without it -> 403 and ver unchanged
    @Test
    public void SEC_FLOW_010_AC5_refreshCookieNeedsCsrf() throws Exception {
        start(TokenTransport.COOKIE);
        String[] s = login("alice");
        String cookie = "naru_rt=" + s[1] + "; naru_csrf=" + s[2];
        long v = ver(alice);
        ClientResponse noCsrf = logout("", null, "Host", W8.HOST, "Origin", W8.SAME, "Cookie", cookie);
        assertEquals(403, noCsrf.status());
        assertEquals("csrf_failed", W8.code(noCsrf));
        ClientResponse allNoCsrf = logout("?all=true", null, "Host", W8.HOST, "Origin", W8.SAME, "Cookie", cookie);
        assertEquals(403, allNoCsrf.status());
        assertEquals(v, ver(alice));
        assertTrue(refresh.revoked.isEmpty());
        ClientResponse ok = logout("", null, "Host", W8.HOST, "Origin", W8.SAME, "Cookie", cookie,
                "X-CSRF-Token", s[2]);
        assertEquals(ok.text(), 204, ok.status());
        assertEquals(1, refresh.revoked.size());
        assertEquals(f.fam(s[0]), refresh.revoked.get(0));
    }

    // ---------------------------------------------------------------------------------------------- SEC-FLOW-010 ③

    // SEC-FLOW-010 AC-6: COOKIE, no naru_rt, valid naru_at of family F with F's X-CSRF-Token and a same-origin Origin
    // -> 204 and revokeFamily(F); without X-CSRF-Token -> 403 and F not revoked
    @Test
    public void SEC_FLOW_010_AC6_accessCookieNeedsCsrf() throws Exception {
        start(TokenTransport.COOKIE);
        String[] s = login("alice");
        String cookie = "naru_at=" + s[0] + "; naru_csrf=" + s[2];
        assertEquals(403, logout("", null, "Host", W8.HOST, "Origin", W8.SAME, "Cookie", cookie).status());
        assertTrue(refresh.revoked.isEmpty());
        assertEquals(204, logout("", null, "Host", W8.HOST, "Origin", W8.SAME, "Cookie", cookie, "X-CSRF-Token",
                s[2]).status());
        assertEquals(java.util.Collections.singletonList(f.fam(s[0])), refresh.revoked);
    }

    // SEC-FLOW-010 AC-7: HEADER, logout with only Bearer at1 -> 204 and revokeFamily(F); rt1 then no longer refreshes
    // while another device's family still does
    @Test
    public void SEC_FLOW_010_AC7_bearerLogoutCutsItsFamily() throws Exception {
        start(TokenTransport.HEADER);
        Map<String, Object> one = f.loginBody("alice", W8.PW);
        Map<String, Object> other = f.loginBody("alice", W8.PW);
        ClientResponse r = logout("", null, "Authorization", "Bearer " + one.get("accessToken"));
        assertEquals(204, r.status());
        assertEquals(java.util.Collections.singletonList(f.fam((String) one.get("accessToken"))), refresh.revoked);
        assertFalse(usable((String) one.get("refreshToken")));
        assertTrue(usable((String) other.get("refreshToken")));
    }

    // SEC-FLOW-010 AC-8: COOKIE, two naru_rt count as no refresh token (step ③ applies), two naru_at as no access
    // cookie; BOTH, a Bearer token and a naru_at together -> 400 invalid_request before the table
    @Test
    public void SEC_FLOW_010_AC8_duplicatesAndTwoCredentials() throws Exception {
        start(TokenTransport.COOKIE);
        String[] s = login("alice");
        String[] t = login("alice");
        ClientResponse twoRt = logout("", null, "Host", W8.HOST, "Origin", W8.SAME, "Cookie", "naru_rt=" + s[1]
                + "; naru_rt=" + t[1] + "; naru_at=" + s[0] + "; naru_csrf=" + s[2], "X-CSRF-Token", s[2]);
        assertEquals(204, twoRt.status());
        assertEquals("step ③ used the access cookie", java.util.Collections.singletonList(f.fam(s[0])),
                refresh.revoked);
        ClientResponse twoAt = logout("?all=true", null, "Host", W8.HOST, "Origin", W8.SAME, "Cookie", "naru_at="
                + s[0] + "; naru_at=" + t[0]);
        assertEquals("two naru_at are no access cookie: no token was read", 401, twoAt.status());
        assertEquals("unauthenticated", W8.code(twoAt));
        f.stop();
        W8 both = new W8();
        try {
            String id = both.user("alice", W8.PW);
            both.start(b -> b.transport(TokenTransport.BOTH));
            String at = both.auth.accessToken(id, 0, 60_000L);
            ClientResponse r = both.post("/auth/logout", null, "Authorization", "Bearer " + at, "Cookie",
                    "naru_at=" + at);
            assertEquals(400, r.status());
            assertEquals("invalid_request", W8.code(r));
        } finally {
            both.stop();
        }
    }

    // SEC-FLOW-010 AC-9: COOKIE, an invalid (revoked) naru_rt of family G plus a valid naru_at of family F with F's
    // CSRF -> 204 with no identity, F not revoked; ?all=true -> 401 invalid_token and ver unchanged
    @Test
    public void SEC_FLOW_010_AC9_invalidRefreshCookieEndsTheSearch() throws Exception {
        start(TokenTransport.COOKIE);
        String[] g = login("alice");
        String[] s = login("alice");
        f.auth.refreshTokens().revokeFamily(f.fam(g[0]));
        refresh.revoked.clear();
        long v = ver(alice);
        String cookie = "naru_rt=" + g[1] + "; naru_at=" + s[0] + "; naru_csrf=" + s[2];
        ClientResponse r = logout("", null, "Host", W8.HOST, "Origin", W8.SAME, "Cookie", cookie, "X-CSRF-Token", s[2]);
        assertEquals(204, r.status());
        assertTrue(refresh.revoked.isEmpty());
        ClientResponse all = logout("?all=true", null, "Host", W8.HOST, "Origin", W8.SAME, "Cookie", cookie,
                "X-CSRF-Token", s[2]);
        assertEquals(401, all.status());
        assertEquals("invalid_token", W8.code(all));
        assertEquals(v, ver(alice));
    }

    // SEC-FLOW-010 AC-10: HEADER, only a valid naru_rt cookie -> 204 with no identity, not revoked; ?all=true -> 401
    // unauthenticated and ver unchanged
    @Test
    public void SEC_FLOW_010_AC10_headerModeDoesNotReadTheRefreshCookie() throws Exception {
        start(TokenTransport.HEADER);
        String rt = (String) f.loginBody("alice", W8.PW).get("refreshToken");
        long v = ver(alice);
        assertEquals(204, logout("", null, "Cookie", "naru_rt=" + rt).status());
        assertTrue(refresh.revoked.isEmpty());
        ClientResponse all = logout("?all=true", null, "Cookie", "naru_rt=" + rt);
        assertEquals(401, all.status());
        assertEquals("unauthenticated", W8.code(all));
        assertEquals(v, ver(alice));
    }

    // SEC-FLOW-010 AC-11: COOKIE, valid naru_rt with matching X-CSRF-Token but Origin https://evil.example -> 403 and
    // nothing revoked (?all=true too, ver unchanged); ③ naru_at with F's CSRF and the same Origin -> 403
    @Test
    public void SEC_FLOW_010_AC11_crossOriginIsRefusedForCookieIdentities() throws Exception {
        start(TokenTransport.COOKIE);
        String[] s = login("alice");
        long v = ver(alice);
        String rtCookie = "naru_rt=" + s[1] + "; naru_csrf=" + s[2];
        for (String q : new String[] {"", "?all=true"}) {
            ClientResponse r = logout(q, null, "Host", W8.HOST, "Origin", W8.EVIL, "Cookie", rtCookie, "X-CSRF-Token",
                    s[2]);
            assertEquals(q, 403, r.status());
            assertEquals("csrf_failed", W8.code(r));
        }
        ClientResponse at = logout("", null, "Host", W8.HOST, "Origin", W8.EVIL, "Cookie", "naru_at=" + s[0]
                + "; naru_csrf=" + s[2], "X-CSRF-Token", s[2]);
        assertEquals(403, at.status());
        assertTrue(refresh.revoked.isEmpty());
        assertEquals(v, ver(alice));
    }

    // SEC-FLOW-010 AC-12: BOTH, no body token and no Authorization: ② naru_rt without X-CSRF-Token -> 403; ② with it
    // but a cross-origin Origin -> 403; ③ naru_at with F's CSRF and a cross-origin Origin -> 403; ③ same-origin -> 204
    @Test
    public void SEC_FLOW_010_AC12_bothModeCookieIdentities() throws Exception {
        start(TokenTransport.BOTH);
        String[] s = login("alice");
        String rt = "naru_rt=" + s[1] + "; naru_csrf=" + s[2];
        assertEquals(403, logout("", null, "Host", W8.HOST, "Origin", W8.SAME, "Cookie", rt).status());
        assertEquals(403, logout("", null, "Host", W8.HOST, "Origin", W8.EVIL, "Cookie", rt, "X-CSRF-Token", s[2])
                .status());
        String at = "naru_at=" + s[0] + "; naru_csrf=" + s[2];
        assertEquals(403, logout("", null, "Host", W8.HOST, "Origin", W8.EVIL, "Cookie", at, "X-CSRF-Token", s[2])
                .status());
        assertTrue(refresh.revoked.isEmpty());
        assertEquals(204, logout("", null, "Host", W8.HOST, "Origin", W8.SAME, "Cookie", at, "X-CSRF-Token", s[2])
                .status());
        assertEquals(java.util.Collections.singletonList(f.fam(s[0])), refresh.revoked);
    }

    // SEC-FLOW-010 AC-13: a stale ver: login gives at1/rt1, a second login's at2 logs out all devices (ver+1); then a
    // body rt1 -> 204 with no identity (no revoke, no onLogout) and with ?all=true -> 401 invalid_token, ver unchanged;
    // the same two requests with Bearer at1 give the same outcomes
    @Test
    public void SEC_FLOW_010_AC13_staleVerIsInvalid() throws Exception {
        start(TokenTransport.HEADER);
        Map<String, Object> one = f.loginBody("alice", W8.PW);
        Map<String, Object> two = f.loginBody("alice", W8.PW);
        long v = ver(alice);
        assertEquals(204, logout("?all=true", null, "Authorization", "Bearer " + two.get("accessToken")).status());
        assertEquals(v + 1, ver(alice));
        events.calls.clear();
        refresh.revoked.clear();
        String[][] requests = {{"body"}, {"bearer"}};
        for (String[] kind : requests) {
            String json = kind[0].equals("body") ? rtBody((String) one.get("refreshToken")) : null;
            String[] h = kind[0].equals("body") ? new String[0]
                    : new String[] {"Authorization", "Bearer " + one.get("accessToken")};
            assertEquals(kind[0], 204, logout("", json, h).status());
            ClientResponse all = logout("?all=true", json, h);
            assertEquals(kind[0], 401, all.status());
            assertEquals("Bearer error=\"invalid_token\"", all.header("WWW-Authenticate"));
        }
        assertTrue(refresh.revoked.isEmpty());
        assertTrue(events.calls.isEmpty());
        assertEquals(v + 1, ver(alice));
    }

    // SEC-FLOW-010 AC-14: no identity means no CSRF check: COOKIE, same-origin, no X-CSRF-Token: (a) only a revoked
    // naru_rt, and only an expired naru_rt -> 204 clearing the cookies (not 403); ?all=true -> 401 invalid_token, ver
    // unchanged; (b) only an expired naru_at -> 204, ?all=true -> 401 invalid_token
    @Test
    public void SEC_FLOW_010_AC14_noIdentityNeedsNoCsrf() throws Exception {
        start(TokenTransport.COOKIE);
        String[] revoked = login("alice");
        f.auth.refreshTokens().revokeFamily(f.fam(revoked[0]));
        String[] expiring = login("alice");
        long v = ver(alice);
        f.clock.addAndGet(f.auth.tokens().getRefreshTtl() + 120_000L); // past exp and the 60 s leeway
        for (String cookie : new String[] {"naru_rt=" + revoked[1], "naru_rt=" + expiring[1],
                "naru_at=" + expiring[0]}) {
            ClientResponse r = logout("", null, "Host", W8.HOST, "Origin", W8.SAME, "Cookie", cookie);
            assertEquals(cookie, 204, r.status());
            cleared(r);
            ClientResponse all = logout("?all=true", null, "Host", W8.HOST, "Origin", W8.SAME, "Cookie", cookie);
            assertEquals(cookie, 401, all.status());
            assertEquals("Bearer error=\"invalid_token\"", all.header("WWW-Authenticate"));
        }
        assertEquals(v, ver(alice));
    }

    // SEC-FLOW-010 AC-15: two naru_csrf cookies (old family G first, F second): ② naru_rt of F with X-CSRF-Token = F
    // value -> 204 and revokeFamily(F); = G value only -> 403; ③ the same with naru_at of F
    @Test
    public void SEC_FLOW_010_AC15_twoCsrfCookies() throws Exception {
        start(TokenTransport.COOKIE);
        for (String source : new String[] {"naru_rt", "naru_at"}) {
            String[] s = login("alice");
            String g = f.auth.csrfTokens().issue(alice, "old-family-g");
            String token = source.equals("naru_rt") ? s[1] : s[0];
            String cookie = source + "=" + token + "; naru_csrf=" + g + "; naru_csrf=" + s[2];
            refresh.revoked.clear();
            ClientResponse wrong = logout("", null, "Host", W8.HOST, "Origin", W8.SAME, "Cookie", cookie,
                    "X-CSRF-Token", g);
            assertEquals(source, 403, wrong.status());
            assertTrue(refresh.revoked.isEmpty());
            ClientResponse right = logout("", null, "Host", W8.HOST, "Origin", W8.SAME, "Cookie", cookie,
                    "X-CSRF-Token", s[2]);
            assertEquals(source, 204, right.status());
            assertEquals(java.util.Collections.singletonList(f.fam(s[0])), refresh.revoked);
        }
    }

    // ---------------------------------------------------------------------------------------------- FR-FLOW-003

    // FR-FLOW-003 AC-1: HEADER or BOTH, only an expired Bearer -> 204 (BOTH clears the cookies), no onLogout, ver
    // unchanged; ?all=true -> 401 invalid_token; COOKIE, the same ?all=true -> 401 unauthenticated
    @Test
    public void FR_FLOW_003_AC1_expiredBearer() throws Exception {
        for (TokenTransport t : new TokenTransport[] {TokenTransport.HEADER, TokenTransport.BOTH,
                TokenTransport.COOKIE}) {
            W8 w = new W8();
            Logouts ev = new Logouts();
            try {
                String id = w.user("alice", W8.PW);
                w.start(b -> b.transport(t).events(ev));
                String expired = w.expiredAccess(id);
                ClientResponse plain = w.post("/auth/logout", null, "Authorization", "Bearer " + expired);
                assertEquals(t.toString(), 204, plain.status());
                if (t == TokenTransport.BOTH) {
                    cleared(plain);
                }
                ClientResponse all = w.post("/auth/logout?all=true", null, "Authorization", "Bearer " + expired);
                assertEquals(t.toString(), 401, all.status());
                assertEquals(t.toString(), t == TokenTransport.COOKIE ? "unauthenticated" : "invalid_token",
                        W8.code(all));
                assertEquals(t == TokenTransport.COOKIE ? "Bearer" : "Bearer error=\"invalid_token\"",
                        all.header("WWW-Authenticate"));
                assertTrue(ev.calls.isEmpty());
                assertEquals(0, w.stored(id).getTokenVersion());
            } finally {
                w.stop();
            }
        }
    }

    // FR-FLOW-003 AC-2: no token at all, ?all=true -> 401 unauthenticated with WWW-Authenticate: Bearer (", ApiKey"
    // with API keys on)
    @Test
    public void FR_FLOW_003_AC2_noTokenAllDevices() throws Exception {
        start(TokenTransport.HEADER);
        ClientResponse r = logout("?all=true", null);
        assertEquals(401, r.status());
        assertEquals("unauthenticated", W8.code(r));
        assertEquals("Bearer", r.header("WWW-Authenticate"));
        f.stop();
        W8 keys = new W8();
        try {
            keys.start(b -> b.apiKeys(new com.snoworca.naruserve.utils.auth.apikey.InMemoryApiKeyStore()));
            assertEquals("Bearer, ApiKey", keys.post("/auth/logout?all=true", null).header("WWW-Authenticate"));
        } finally {
            keys.stop();
        }
    }

    // FR-FLOW-003 AC-3: COOKIE, valid naru_rt with CSRF and a same-origin Origin, ?all=true -> 204 and ver+1; every
    // refresh token of the user then fails. SEC-IAM-003 AC-1 (logout row): ver increases by exactly 1.
    @Test
    public void FR_FLOW_003_AC3_allDevicesRaisesVerOnce() throws Exception {
        start(TokenTransport.COOKIE);
        String[] s = login("alice");
        String[] other = login("alice");
        long v = ver(alice);
        ClientResponse r = logout("?all=true", null, "Host", W8.HOST, "Origin", W8.SAME, "Cookie", "naru_rt=" + s[1]
                + "; naru_csrf=" + s[2], "X-CSRF-Token", s[2]);
        assertEquals(r.text(), 204, r.status());
        assertEquals(v + 1, ver(alice));
        assertFalse(usable(s[1]));
        assertFalse(usable(other[1]));
        assertEquals(java.util.Collections.singletonList(alice + ":true"), events.calls);
    }

    // FR-FLOW-003 AC-4: a valid identity, plain logout -> revokeFamily once, ver unchanged, onLogout once, over the
    // default refresh store when none is configured
    @Test
    public void FR_FLOW_003_AC4_plainLogoutRevokesOnce() throws Exception {
        String id = f.user("alice", W8.PW);
        f.start(b -> b.events(events));
        Map<String, Object> s = f.loginBody("alice", W8.PW);
        assertEquals(204, logout("", null, "Authorization", "Bearer " + s.get("accessToken")).status());
        assertEquals(0, f.stored(id).getTokenVersion());
        assertEquals(java.util.Collections.singletonList(id + ":false"), events.calls);
        assertFalse(usable((String) s.get("refreshToken")));
        assertTrue(f.auth.refreshTokens() instanceof InMemoryRefreshTokenStore);
    }

    // FR-FLOW-003 AC-5: a valid refresh token with ?all=true while RefreshTokenStore.find throws -> 503; while the
    // UserStore ver read throws -> 503; no 401 either way
    @Test
    public void FR_FLOW_003_AC5_storeErrorsAre503() throws Exception {
        FlakyUsers flaky = new FlakyUsers(f.store);
        f.users = flaky;
        start(TokenTransport.HEADER);
        String rt = (String) f.loginBody("alice", W8.PW).get("refreshToken");
        refresh.failFind.set(true);
        ClientResponse a = logout("?all=true", rtBody(rt));
        assertEquals(a.text(), 503, a.status());
        assertEquals("SERVICE_UNAVAILABLE", W8.code(a));
        refresh.failFind.set(false);
        flaky.fail.set(true);
        ClientResponse b = logout("?all=true", rtBody(rt));
        flaky.fail.set(false);
        assertEquals(b.text(), 503, b.status());
        assertTrue(refresh.revoked.isEmpty());
    }

    // FR-FLOW-003 AC-6: every 204 in COOKIE or BOTH clears naru_at, naru_rt and naru_csrf with Max-Age=0, identity or
    // not, and a repeated logout answers 204 again
    @Test
    public void FR_FLOW_003_AC6_everyNoContentClearsTheCookies() throws Exception {
        start(TokenTransport.COOKIE);
        String[] s = login("alice");
        String cookie = "naru_at=" + s[0] + "; naru_csrf=" + s[2];
        for (int i = 0; i < 2; i++) {
            ClientResponse r = logout("", null, "Host", W8.HOST, "Origin", W8.SAME, "Cookie", cookie, "X-CSRF-Token",
                    s[2]);
            assertEquals(204, r.status());
            cleared(r);
        }
        ClientResponse anonymous = logout("", null, "Host", W8.HOST, "Origin", W8.SAME);
        assertEquals(204, anonymous.status());
        cleared(anonymous);
    }

    // FR-FLOW-003 AC-7: a token from AuthTesting.tokenFor(auth, "alice") as Bearer -> 204; no real family of alice
    // is revoked
    @Test
    public void FR_FLOW_003_AC7_testTokenLogout() throws Exception {
        f.start(b -> b.refreshTokens(refresh));
        f.store.create(AuthUser.builder().username("alice").passwordHash(
                new com.snoworca.naruserve.utils.auth.password.Pbkdf2PasswordHasher(1000).hash(W8.PW.toCharArray()))
                .build());
        String rt = (String) f.loginBody("alice", W8.PW).get("refreshToken");
        String test = AuthTesting.tokenFor(f.auth, "alice");
        assertEquals(204, logout("", null, "Authorization", "Bearer " + test).status());
        assertTrue(usable(rt));
    }

    // SEC-FLOW-008 AC-4 (logout part): COOKIE, a body refreshToken with a naru_rt -> 400 BAD_REQUEST
    @Test
    public void SEC_FLOW_008_AC4_cookieModeRefusesABodyTokenOnLogout() throws Exception {
        start(TokenTransport.COOKIE);
        String[] s = login("alice");
        ClientResponse r = logout("", rtBody(s[1]), "Host", W8.HOST, "Origin", W8.SAME, "Cookie", "naru_rt=" + s[1]);
        assertEquals(400, r.status());
        assertEquals("BAD_REQUEST", W8.code(r));
        assertTrue(refresh.revoked.isEmpty());
        try {
            assertTrue(usable(s[1]));
        } catch (AssertionError e) {
            fail("the cookie token stayed usable");
        }
    }
}
