package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.utils.auth.apikey.IssuedApiKey;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.After;
import org.junit.Test;

/**
 * POST /auth/refresh (FR-FLOW-005) with its cookie CSRF rule (SEC-FLOW-008), the delivery rule (FR-FLOW-002) and the
 * fixed decision order of the auth endpoints (FR-FLOW-001), over the in-memory adapter and the shipped stores.
 */
public class Wave9RefreshTest {

    /** A refresh store counting every find, over the recording one of wave 8. */
    static final class Finds implements com.snoworca.naruserve.utils.auth.spi.RefreshTokenStore {
        final Wave8LogoutTest.Recording d = new Wave8LogoutTest.Recording();
        final java.util.concurrent.atomic.AtomicInteger finds = new java.util.concurrent.atomic.AtomicInteger();

        @Override
        public void save(com.snoworca.naruserve.utils.auth.spi.RefreshRecord record) {
            d.save(record);
        }

        @Override
        public com.snoworca.naruserve.utils.auth.spi.@Nullable RefreshRecord find(String tokenId) throws Exception {
            finds.incrementAndGet();
            return d.find(tokenId);
        }

        @Override
        public boolean markUsed(String tokenId, long nowMillis) {
            return d.markUsed(tokenId, nowMillis);
        }

        @Override
        public int revokeFamily(String familyId) {
            return d.revokeFamily(familyId);
        }
    }

    private final W8 f = new W8();
    private final Wave8LogoutTest.Recording refresh = new Wave8LogoutTest.Recording();
    private final Wave8LogoutTest.Logouts events = new Wave8LogoutTest.Logouts();
    private String alice;

    @After
    public void stop() {
        f.stop();
    }

    private void start(TokenTransport t) throws Exception {
        start(t, b -> b);
    }

    private void start(TokenTransport t, java.util.function.UnaryOperator<Auth.Builder> more) throws Exception {
        alice = f.user("alice", W8.PW, "reader");
        f.start(b -> more.apply(b.transport(t).refreshTokens(refresh).events(events)));
    }

    /** A login of alice: [accessToken, refreshToken, csrfToken or null], by body or by cookies. */
    private String[] login() {
        ClientResponse r = f.post("/auth/login", W8.login("alice", W8.PW), "Host", W8.HOST, "Origin", W8.SAME);
        assertEquals(r.text(), 200, r.status());
        Map<String, Object> body = W8.object(r);
        if (body.containsKey("accessToken")) {
            return new String[] {(String) body.get("accessToken"), (String) body.get("refreshToken"), null};
        }
        return new String[] {W8.cookieValue(r, "naru_at"), W8.cookieValue(r, "naru_rt"), (String) body.get("csrfToken")};
    }

    private ClientResponse refreshCall(@Nullable String json, String... headers) {
        return f.post("/auth/refresh", json, headers);
    }

    private static String rtBody(String rt) {
        return "{\"refreshToken\":\"" + rt + "\"}";
    }

    /** A cookie refresh of {@code s} with its CSRF token from the request's own origin. */
    private ClientResponse cookieRefresh(String[] s, String... extra) {
        String[] h = new String[6 + extra.length];
        h[0] = "Host";
        h[1] = W8.HOST;
        h[2] = "Origin";
        h[3] = W8.SAME;
        h[4] = "Cookie";
        h[5] = "naru_rt=" + s[1] + "; naru_csrf=" + s[2];
        System.arraycopy(extra, 0, h, 6, extra.length);
        return refreshCall(null, h);
    }

    // ---------------------------------------------------------------------------------------------- FR-FLOW-005

    // FR-FLOW-005 AC-1: HEADER, a valid body refreshToken -> 200 with a new accessToken and refreshToken in the body;
    // the new access token's fam is the family of the presented refresh token
    @Test
    public void FR_FLOW_005_AC1_bodyRefreshRotatesInTheFamily() throws Exception {
        start(TokenTransport.HEADER);
        String[] s = login();
        ClientResponse r = refreshCall(rtBody(s[1]));
        assertEquals(r.text(), 200, r.status());
        Map<String, Object> body = W8.object(r);
        String at = (String) body.get("accessToken");
        String rt = (String) body.get("refreshToken");
        assertNotNull(at);
        assertNotNull(rt);
        assertNotEquals(s[1], rt);
        assertEquals(f.fam(s[0]), f.fam(at));
        assertTrue(W8.setCookies(r).isEmpty());
        assertEquals("no-store", r.header("Cache-Control"));
    }

    // FR-FLOW-005 AC-2: with the reuse grace window 0, a refresh token already rotated -> 401, and the newest refresh
    // token of that family then also fails with 401 (family revoked by reuse detection)
    @Test
    public void FR_FLOW_005_AC2_reuseRevokesTheFamily() throws Exception {
        start(TokenTransport.HEADER, b -> b.reuseGrace(0));
        String[] s = login();
        ClientResponse first = refreshCall(rtBody(s[1]));
        assertEquals(200, first.status());
        String newest = (String) W8.object(first).get("refreshToken");
        ClientResponse again = refreshCall(rtBody(s[1]));
        assertEquals(401, again.status());
        assertEquals("invalid_refresh_token", W8.code(again));
        ClientResponse after = refreshCall(rtBody(newest));
        assertEquals(401, after.status());
        assertEquals(1, events.reused.get());
    }

    // FR-FLOW-005 AC-3: COOKIE or BOTH with API keys enabled, a valid API key on POST /auth/refresh with a valid
    // naru_rt cookie issues no refresh token and does not read naru_rt; a wrong key likewise (401 invalid_api_key);
    // without .apiKeys(store) the key-scheme header is no credential and naru_rt decides
    // FR-FLOW-001 AC-6: with API keys on, the key procedure decides before the refresh cookie is read (naru_rt is
    // neither read nor rotated); without .apiKeys(store) the request is decided by naru_rt
    @Test
    public void FR_FLOW_005_AC3_FR_FLOW_001_AC6_keysDecideBeforeTheCookie() throws Exception {
        for (TokenTransport t : new TokenTransport[] {TokenTransport.COOKIE, TokenTransport.BOTH}) {
            W8 w = new W8();
            Finds finds = new Finds();
            Wave8LogoutTest.Recording store = finds.d;
            try {
                String id = w.user("alice", W8.PW, "reader");
                w.start(b -> b.transport(t).refreshTokens(finds).apiKeys(new com.snoworca.naruserve.utils.auth.apikey
                        .InMemoryApiKeyStore()));
                IssuedApiKey key = w.auth.apiKeys().issue(id, "ci", null, "posts:read");
                ClientResponse login = w.post("/auth/login", W8.login("alice", W8.PW), "Host", W8.HOST, "Origin",
                        W8.SAME);
                String rt = W8.cookieValue(login, "naru_rt");
                String csrf = (String) W8.object(login).get("csrfToken");
                int marks = store.marks.get();
                int read = finds.finds.get();
                ClientResponse valid = w.post("/auth/refresh", null, "Host", W8.HOST, "Origin", W8.SAME, "Cookie",
                        "naru_rt=" + rt + "; naru_csrf=" + csrf, "X-CSRF-Token", csrf, "Authorization",
                        "ApiKey " + key.getPlainKey());
                assertEquals(t + " " + valid.text(), 403, valid.status());
                assertEquals("api_key_not_allowed", W8.code(valid));
                ClientResponse wrong = w.post("/auth/refresh", null, "Host", W8.HOST, "Origin", W8.SAME, "Cookie",
                        "naru_rt=" + rt + "; naru_csrf=" + csrf, "X-CSRF-Token", csrf, "Authorization",
                        "ApiKey nk_ZZZZZZZZ_ZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZ");
                assertEquals(401, wrong.status());
                assertEquals("invalid_api_key", W8.code(wrong));
                assertEquals("naru_rt was not rotated", marks, store.marks.get());
                assertEquals("naru_rt was not even read", read, finds.finds.get());
                assertFalse(W8.plantsAuthCookie(valid));
                assertFalse(W8.plantsAuthCookie(wrong));
                // the cookie still refreshes: it was never consumed
                ClientResponse ok = w.post("/auth/refresh", null, "Host", W8.HOST, "Origin", W8.SAME, "Cookie",
                        "naru_rt=" + rt + "; naru_csrf=" + csrf, "X-CSRF-Token", csrf);
                assertEquals(ok.text(), 200, ok.status());
            } finally {
                w.stop();
            }
            W8 off = new W8();
            try {
                off.user("alice", W8.PW, "reader");
                off.start(b -> b.transport(t));
                ClientResponse login = off.post("/auth/login", W8.login("alice", W8.PW), "Host", W8.HOST, "Origin",
                        W8.SAME);
                String rt = W8.cookieValue(login, "naru_rt");
                String csrf = (String) W8.object(login).get("csrfToken");
                ClientResponse ok = off.post("/auth/refresh", null, "Host", W8.HOST, "Origin", W8.SAME, "Cookie",
                        "naru_rt=" + rt + "; naru_csrf=" + csrf, "X-CSRF-Token", csrf, "Authorization",
                        "ApiKey nk_ZZZZZZZZ_ZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZ");
                assertEquals(t + " " + ok.text(), 200, ok.status());
                assertNotNull(W8.cookieValue(ok, "naru_rt"));
            } finally {
                off.stop();
            }
        }
    }

    // FR-FLOW-005 AC-4: any mode, no body token and no naru_rt cookie -> 401 invalid_refresh_token
    @Test
    public void FR_FLOW_005_AC4_noRefreshTokenIs401() throws Exception {
        for (TokenTransport t : TokenTransport.values()) {
            W8 w = new W8();
            try {
                w.start(b -> b.transport(t));
                ClientResponse none = w.post("/auth/refresh", null, "Host", W8.HOST, "Origin", W8.SAME);
                assertEquals(t + " " + none.text(), 401, none.status());
                assertEquals("invalid_refresh_token", W8.code(none));
                ClientResponse empty = w.post("/auth/refresh", "{}", "Host", W8.HOST, "Origin", W8.SAME);
                assertEquals(401, empty.status());
                assertEquals("invalid_refresh_token", W8.code(empty));
            } finally {
                w.stop();
            }
        }
    }

    // FR-FLOW-005 AC-5: after POST /auth/logout revoked family F, a refresh with a token of F -> 401
    // SEC-FLOW-010 AC-7 (on the refresh endpoint): HEADER, logout with only Bearer at1 -> 204 and revokeFamily(F); a
    // following POST /auth/refresh with rt1 -> 401 while another device's family still refreshes
    @Test
    public void FR_FLOW_005_AC5_SEC_FLOW_010_AC7_logoutEndsTheFamily() throws Exception {
        start(TokenTransport.HEADER);
        String[] one = login();
        String[] other = login();
        assertEquals(204, f.post("/auth/logout", null, "Authorization", "Bearer " + one[0]).status());
        ClientResponse cut = refreshCall(rtBody(one[1]));
        assertEquals(401, cut.status());
        assertEquals("invalid_refresh_token", W8.code(cut));
        assertEquals(200, refreshCall(rtBody(other[1])).status());
    }

    // FR-FLOW-005 AC-6: BOTH, a body token of family F and a naru_rt of family G both present, no X-CSRF-Token and no
    // Origin -> the body token is used: 200 body delivery and no Set-Cookie, naru_rt neither verified nor rotated (G
    // still refreshes with its own CSRF token); an invalid body token -> 401 without trying naru_rt
    // FR-FLOW-002 AC-3: BOTH refresh: a body token -> body delivery; only naru_rt (matching X-CSRF-Token, same-origin
    // Origin) -> cookie delivery; both present -> body delivery
    @Test
    public void FR_FLOW_005_AC6_FR_FLOW_002_AC3_bodyFirstInBoth() throws Exception {
        start(TokenTransport.BOTH);
        ClientResponse bodyLogin = f.post("/auth/login", W8.login("alice", W8.PW));
        String fRt = (String) W8.object(bodyLogin).get("refreshToken");
        String[] g = login(); // same-origin: cookie delivery
        assertNotNull(g[2]);
        int marks = refresh.marks.get();
        ClientResponse both = refreshCall(rtBody(fRt), "Cookie", "naru_rt=" + g[1] + "; naru_csrf=" + g[2]);
        assertEquals(both.text(), 200, both.status());
        assertNotNull(W8.object(both).get("accessToken"));
        assertTrue(W8.setCookies(both).isEmpty());
        assertEquals("only the body token was rotated", marks + 1, refresh.marks.get());
        ClientResponse invalid = refreshCall(rtBody(fRt), "Cookie", "naru_rt=" + g[1] + "; naru_csrf=" + g[2]);
        assertEquals(401, invalid.status());
        assertEquals(marks + 1, refresh.marks.get());
        ClientResponse cookieOnly = cookieRefresh(g, "X-CSRF-Token", g[2]);
        assertEquals(cookieOnly.text(), 200, cookieOnly.status());
        assertNotNull(W8.cookieValue(cookieOnly, "naru_rt"));
        assertNotNull(W8.object(cookieOnly).get("csrfToken"));
        assertNull(W8.object(cookieOnly).get("accessToken"));
    }

    // FR-FLOW-005 AC-7: HEADER, POST /auth/refresh with only a valid naru_rt cookie -> the same 401 as no refresh token;
    // the cookie is not read and its family is not rotated
    @Test
    public void FR_FLOW_005_AC7_headerModeIgnoresTheCookie() throws Exception {
        start(TokenTransport.HEADER);
        String[] s = login();
        int marks = refresh.marks.get();
        ClientResponse r = refreshCall(null, "Cookie", "naru_rt=" + s[1]);
        ClientResponse none = refreshCall(null);
        assertEquals(401, r.status());
        assertEquals(none.status(), r.status());
        assertEquals(W8.code(none), W8.code(r));
        assertEquals(marks, refresh.marks.get());
        assertEquals(200, refreshCall(rtBody(s[1])).status());
    }

    // ---------------------------------------------------------------------------------------------- SEC-FLOW-008

    // SEC-FLOW-008 AC-1: COOKIE, expired naru_at, valid naru_rt, X-CSRF-Token and naru_csrf of its sub and fam,
    // same-origin Origin -> 200
    // SEC-FLOW-008 AC-2: the same without X-CSRF-Token -> 403 csrf_failed and the token is not marked used, so the same
    // naru_rt with a correct header then refreshes (200)
    // SEC-FLOW-008 AC-3: the outcome of AC-1 and AC-2 is the same with a valid, expired or absent naru_at
    // SEC-FLOW-003 AC-1 (COOKIE half): an expired naru_at next to a valid naru_rt with CSRF -> 200
    @Test
    public void SEC_FLOW_008_AC1_AC2_AC3_SEC_FLOW_003_AC1_cookieRefreshNeedsCsrf() throws Exception {
        start(TokenTransport.COOKIE);
        String expired = f.expiredAccess(alice);
        for (int i = 0; i < 3; i++) {
            String[] s = login();
            String at = i == 0 ? "naru_at=" + s[0] + "; " : i == 1 ? "naru_at=" + expired + "; " : "";
            String cookie = at + "naru_rt=" + s[1] + "; naru_csrf=" + s[2];
            int marks = refresh.marks.get();
            ClientResponse no = refreshCall(null, "Host", W8.HOST, "Origin", W8.SAME, "Cookie", cookie);
            assertEquals(i + " " + no.text(), 403, no.status());
            assertEquals("csrf_failed", W8.code(no));
            assertEquals(marks, refresh.marks.get());
            assertFalse(W8.plantsAuthCookie(no));
            ClientResponse yes = refreshCall(null, "Host", W8.HOST, "Origin", W8.SAME, "Cookie", cookie,
                    "X-CSRF-Token", s[2]);
            assertEquals(i + " " + yes.text(), 200, yes.status());
            assertNotNull(W8.cookieValue(yes, "naru_at"));
            assertNotNull(W8.cookieValue(yes, "naru_rt"));
            assertNotNull(W8.cookieValue(yes, "naru_csrf"));
            Map<String, Object> body = W8.object(yes);
            assertNotNull(body.get("csrfToken"));
            assertNull(body.get("accessToken"));
            assertNull(body.get("refreshToken"));
        }
    }

    // SEC-FLOW-008 AC-4 (refresh half): COOKIE, a same-origin JSON body {"refreshToken"} on POST /auth/refresh -> 400
    // BAD_REQUEST, the token is not used and no Set-Cookie gives an auth cookie a value
    // FR-FLOW-001 AC-4: secure COOKIE refresh with a JSON body refreshToken, no X-CSRF-Token and a cross-origin
    // Origin -> 400 BAD_REQUEST, not 403
    @Test
    public void SEC_FLOW_008_AC4_FR_FLOW_001_AC4_bodyTokenIs400InCookieMode() throws Exception {
        start(TokenTransport.COOKIE);
        String[] s = login();
        int marks = refresh.marks.get();
        ClientResponse same = refreshCall(rtBody(s[1]), "Host", W8.HOST, "Origin", W8.SAME, "Cookie",
                "naru_rt=" + s[1] + "; naru_csrf=" + s[2], "X-CSRF-Token", s[2]);
        assertEquals(400, same.status());
        assertEquals("BAD_REQUEST", W8.code(same));
        assertFalse(W8.plantsAuthCookie(same));
        ClientResponse cross = refreshCall(rtBody(s[1]), "Host", W8.HOST, "Origin", W8.EVIL);
        assertEquals(400, cross.status());
        assertEquals("BAD_REQUEST", W8.code(cross));
        assertEquals(marks, refresh.marks.get());
    }

    // SEC-FLOW-008 AC-5: HEADER or BOTH, JSON body refreshToken without X-CSRF-Token and without Origin -> 200 with
    // accessToken and refreshToken in the body and no Set-Cookie
    @Test
    public void SEC_FLOW_008_AC5_bodyTokensNeedNoCsrf() throws Exception {
        for (TokenTransport t : new TokenTransport[] {TokenTransport.HEADER, TokenTransport.BOTH}) {
            W8 w = new W8();
            try {
                w.user("alice", W8.PW, "reader");
                w.start(b -> b.transport(t));
                String rt = (String) w.loginBody("alice", W8.PW).get("refreshToken");
                ClientResponse r = w.post("/auth/refresh", rtBody(rt));
                assertEquals(t + " " + r.text(), 200, r.status());
                assertNotNull(W8.object(r).get("accessToken"));
                assertNotNull(W8.object(r).get("refreshToken"));
                assertTrue(W8.setCookies(r).isEmpty());
            } finally {
                w.stop();
            }
        }
    }

    // SEC-FLOW-008 AC-6: BOTH, no body token, a valid naru_rt with X-CSRF-Token and naru_csrf of its sub and fam but no
    // Origin and no Referer -> 403 csrf_failed; Origin https://evil.example -> 403 csrf_failed; the token is not used,
    // no auth cookie gets a value and no body tokens are sent
    // SEC-FLOW-008 AC-8: BOTH, no body token, a valid naru_rt, same-origin Origin, no X-CSRF-Token -> 403 and not
    // used; with X-CSRF-Token and naru_csrf of its sub and fam -> 200
    @Test
    public void SEC_FLOW_008_AC6_AC8_bothModeCookieRefreshNeedsOriginAndCsrf() throws Exception {
        start(TokenTransport.BOTH);
        String[] s = login();
        int marks = refresh.marks.get();
        String cookie = "naru_rt=" + s[1] + "; naru_csrf=" + s[2];
        ClientResponse noOrigin = refreshCall(null, "Host", W8.HOST, "Cookie", cookie, "X-CSRF-Token", s[2]);
        ClientResponse evil = refreshCall(null, "Host", W8.HOST, "Origin", W8.EVIL, "Cookie", cookie, "X-CSRF-Token",
                s[2]);
        ClientResponse noCsrf = refreshCall(null, "Host", W8.HOST, "Origin", W8.SAME, "Cookie", cookie);
        for (ClientResponse r : new ClientResponse[] {noOrigin, evil, noCsrf}) {
            assertEquals(r.text(), 403, r.status());
            assertEquals("csrf_failed", W8.code(r));
            assertFalse(W8.plantsAuthCookie(r));
            assertNull(W8.object(r).get("accessToken"));
        }
        assertEquals(marks, refresh.marks.get());
        ClientResponse ok = cookieRefresh(s, "X-CSRF-Token", s[2]);
        assertEquals(ok.text(), 200, ok.status());
    }

    // SEC-FLOW-008 AC-7: COOKIE, the AC-1 request with Origin https://evil.example -> 403 csrf_failed and the token is
    // not marked used, so the same naru_rt with a same-origin Origin then refreshes (200)
    @Test
    public void SEC_FLOW_008_AC7_foreignOriginIsRefused() throws Exception {
        start(TokenTransport.COOKIE);
        String[] s = login();
        int marks = refresh.marks.get();
        ClientResponse evil = refreshCall(null, "Host", W8.HOST, "Origin", W8.EVIL, "Cookie",
                "naru_rt=" + s[1] + "; naru_csrf=" + s[2], "X-CSRF-Token", s[2]);
        assertEquals(403, evil.status());
        assertEquals("csrf_failed", W8.code(evil));
        assertEquals(marks, refresh.marks.get());
        assertEquals(200, cookieRefresh(s, "X-CSRF-Token", s[2]).status());
    }

    // SEC-FLOW-008 AC-9: COOKIE, same-origin, no X-CSRF-Token, a naru_rt whose family logout revoked and, separately,
    // one whose exp passed -> 401 with the same status and code as with a correct CSRF token, not 403; no auth cookie
    // gets a value
    @Test
    public void SEC_FLOW_008_AC9_theTokenIsVerifiedBeforeCsrf() throws Exception {
        start(TokenTransport.COOKIE);
        String[] revoked = login();
        assertEquals(204, f.post("/auth/logout", null, "Host", W8.HOST, "Origin", W8.SAME, "Cookie",
                "naru_rt=" + revoked[1] + "; naru_csrf=" + revoked[2], "X-CSRF-Token", revoked[2]).status());
        String[] old = login();
        for (int round = 0; round < 2; round++) {
            if (round == 1) {
                f.clock.addAndGet(f.auth.tokens().getRefreshTtl() + 600_000L); // past exp and any leeway
            }
            String[] s = round == 0 ? revoked : old; // the revoked family first, while it has not expired
            String cookie = "naru_rt=" + s[1] + "; naru_csrf=" + s[2];
            ClientResponse no = refreshCall(null, "Host", W8.HOST, "Origin", W8.SAME, "Cookie", cookie);
            ClientResponse with = refreshCall(null, "Host", W8.HOST, "Origin", W8.SAME, "Cookie", cookie,
                    "X-CSRF-Token", s[2]);
            assertEquals(no.text(), 401, no.status());
            assertEquals(with.status(), no.status());
            assertEquals(W8.code(with), W8.code(no));
            assertEquals("invalid_refresh_token", W8.code(no));
            assertFalse(W8.plantsAuthCookie(no));
            assertFalse(W8.plantsAuthCookie(with));
        }
    }

    // SEC-FLOW-008 AC-10: COOKIE, a valid naru_rt of family F and two naru_csrf cookies (old family G first, then F):
    // X-CSRF-Token equal to the F value -> 200; equal only to the G value -> 403 and not used, so the F value then
    // refreshes (200)
    @Test
    public void SEC_FLOW_008_AC10_twoCsrfCookies() throws Exception {
        start(TokenTransport.COOKIE);
        String[] g = login();
        String[] s = login();
        String cookie = "naru_rt=" + s[1] + "; naru_csrf=" + g[2] + "; naru_csrf=" + s[2];
        int marks = refresh.marks.get();
        ClientResponse wrong = refreshCall(null, "Host", W8.HOST, "Origin", W8.SAME, "Cookie", cookie, "X-CSRF-Token",
                g[2]);
        assertEquals(403, wrong.status());
        assertEquals("csrf_failed", W8.code(wrong));
        assertEquals(marks, refresh.marks.get());
        ClientResponse right = refreshCall(null, "Host", W8.HOST, "Origin", W8.SAME, "Cookie", cookie, "X-CSRF-Token",
                s[2]);
        assertEquals(right.text(), 200, right.status());
    }

    // ---------------------------------------------------------------------------------------------- SEC-FLOW-003

    // SEC-FLOW-003 AC-1 (BOTH half): POST /auth/refresh with an expired Authorization: Bearer token and a valid naru_rt
    // cookie with matching X-CSRF-Token and a same-origin Origin -> 200; no 401 invalid_token
    @Test
    public void SEC_FLOW_003_AC1_expiredBearerIsIgnoredOnRefresh() throws Exception {
        start(TokenTransport.BOTH);
        String[] s = login();
        ClientResponse r = cookieRefresh(s, "X-CSRF-Token", s[2], "Authorization", "Bearer " + f.expiredAccess(alice));
        assertEquals(r.text(), 200, r.status());
        assertNull(r.header("WWW-Authenticate"));
        assertNotNull(W8.cookieValue(r, "naru_rt"));
    }

    // SEC-FLOW-003 AC-7 (b): with .mountPath("/api/auth"), HEADER over a local connection, POST /api/auth/refresh with
    // an expired Bearer token and a valid JSON body refreshToken -> 200, no 401 invalid_token
    @Test
    public void SEC_FLOW_003_AC7b_refreshUnderTheMountPath() throws Exception {
        start(TokenTransport.HEADER, b -> b.mountPath("/api/auth"));
        ClientResponse login = f.post("/api/auth/login", W8.login("alice", W8.PW));
        assertEquals(200, login.status());
        String rt = (String) W8.object(login).get("refreshToken");
        ClientResponse r = f.post("/api/auth/refresh", rtBody(rt), "Authorization", "Bearer " + f.expiredAccess(alice));
        assertEquals(r.text(), 200, r.status());
        assertNotNull(W8.object(r).get("accessToken"));
    }

    // ---------------------------------------------------------------------------------------------- SEC-FLOW-010

    // SEC-FLOW-010 AC-4 (on the refresh endpoint): the body holds rt1 of family F already marked used by a real
    // POST /auth/refresh rotation, plus a valid Bearer at2 of F -> logout 204 with no identity; F is not revoked and
    // onRefreshTokenReused is not fired, so the rotated token still refreshes
    @Test
    public void SEC_FLOW_010_AC4_usedTokenGivesLogoutNoIdentity() throws Exception {
        start(TokenTransport.HEADER);
        String[] s = login();
        ClientResponse rotated = refreshCall(rtBody(s[1]));
        assertEquals(200, rotated.status());
        String at2 = (String) W8.object(rotated).get("accessToken");
        String rt2 = (String) W8.object(rotated).get("refreshToken");
        ClientResponse out = f.post("/auth/logout", rtBody(s[1]), "Authorization", "Bearer " + at2);
        assertEquals(204, out.status());
        assertTrue(refresh.revoked.isEmpty());
        assertEquals(0, events.reused.get());
        assertEquals(200, refreshCall(rtBody(rt2)).status());
    }

    // FR-FLOW-003 AC-3 (on the refresh endpoint): COOKIE, a valid naru_rt with CSRF and a same-origin Origin,
    // logout?all=true -> 204 and ver + 1; every refresh token of that user then fails on POST /auth/refresh
    @Test
    public void FR_FLOW_003_AC3_allDevicesEndsEveryRefresh() throws Exception {
        start(TokenTransport.COOKIE);
        String[] a = login();
        String[] b = login();
        long v = f.stored(alice).getTokenVersion();
        assertEquals(204, f.post("/auth/logout?all=true", null, "Host", W8.HOST, "Origin", W8.SAME, "Cookie",
                "naru_rt=" + a[1] + "; naru_csrf=" + a[2], "X-CSRF-Token", a[2]).status());
        assertEquals(v + 1, f.stored(alice).getTokenVersion());
        for (String[] s : new String[][] {a, b}) {
            ClientResponse r = cookieRefresh(s, "X-CSRF-Token", s[2]);
            assertEquals(401, r.status());
            assertEquals("invalid_refresh_token", W8.code(r));
        }
    }

    // ---------------------------------------------------------------------------------------------- FR-FLOW-001

    // FR-FLOW-001 AC-1: PROD, a plain-text non-local POST /auth/refresh with two Authorization headers and a
    // text/plain body -> 403 insecure_transport
    @Test
    public void FR_FLOW_001_AC1_transportFirst() throws Exception {
        f.start("203.0.113.7", false, b -> b.mode(AuthMode.PROD).transport(TokenTransport.HEADER));
        ClientResponse r = f.adapter.send(ClientRequest.of("POST", "/auth/refresh").header("Authorization", "Bearer a")
                .header("Authorization", "Bearer b").header("Content-Type", "text/plain").header("Content-Length", "5")
                .body("hello".getBytes(StandardCharsets.UTF_8)));
        assertEquals(403, r.status());
        assertEquals("insecure_transport", W8.code(r));
    }

    // FR-FLOW-001 AC-2: secure POST /auth/login with two Authorization headers and a text/plain body -> 400
    // invalid_request, not 415
    @Test
    public void FR_FLOW_001_AC2_multipleCredentialsBeforeTheBodyFormat() throws Exception {
        start(TokenTransport.HEADER);
        ClientResponse r = f.adapter.send(ClientRequest.of("POST", "/auth/login").header("Authorization", "Bearer a")
                .header("Authorization", "Bearer b").header("Content-Type", "text/plain").header("Content-Length", "5")
                .body("hello".getBytes(StandardCharsets.UTF_8)));
        assertEquals(400, r.status());
        assertEquals("invalid_request", W8.code(r));
    }

    // FR-FLOW-001 AC-3: secure COOKIE POST /auth/refresh with a form body containing refreshToken -> 415
    // UNSUPPORTED_MEDIA_TYPE, not 400
    @Test
    public void FR_FLOW_001_AC3_bodyFormatBeforeTheCookieModeBodyRule() throws Exception {
        start(TokenTransport.COOKIE);
        String[] s = login();
        byte[] form = ("refreshToken=" + s[1]).getBytes(StandardCharsets.UTF_8);
        ClientResponse r = f.adapter.send(ClientRequest.of("POST", "/auth/refresh").header("Content-Type",
                "application/x-www-form-urlencoded").header("Content-Length", String.valueOf(form.length)).body(form));
        assertEquals(415, r.status());
        assertEquals("UNSUPPORTED_MEDIA_TYPE", W8.code(r));
    }

    // FR-FLOW-001 AC-5: secure BOTH POST /auth/login with an Origin outside trustedOrigins and valid credentials -> 200
    // body delivery and no Set-Cookie; no 403
    @Test
    public void FR_FLOW_001_AC5_bothModeLoginSelectsTheDelivery() throws Exception {
        start(TokenTransport.BOTH);
        ClientResponse r = f.post("/auth/login", W8.login("alice", W8.PW), "Host", W8.HOST, "Origin", W8.EVIL);
        assertEquals(200, r.status());
        assertNotNull(W8.object(r).get("accessToken"));
        assertTrue(W8.setCookies(r).isEmpty());
    }

    // FR-FLOW-001 (the earliest step decides; body format 415 before CSRF/Origin 403): secure COOKIE, a valid naru_at
    // and no X-CSRF-Token, POST /auth/password and POST /auth/admin/users with a text/plain body -> 415
    // UNSUPPORTED_MEDIA_TYPE, not 403 csrf_failed
    @Test
    public void FR_FLOW_001_bodyFormatBeforeCsrfOnPrincipalEndpoints() throws Exception {
        start(TokenTransport.COOKIE);
        String[] s = login();
        for (String path : new String[] {"/auth/password", "/auth/admin/users"}) {
            ClientResponse r = f.adapter.send(ClientRequest.of("POST", path).header("Host", W8.HOST)
                    .header("Origin", W8.SAME).header("Cookie", "naru_at=" + s[0])
                    .header("Content-Type", "text/plain").header("Content-Length", "5")
                    .body("hello".getBytes(StandardCharsets.UTF_8)));
            assertEquals(path + " " + r.text(), 415, r.status());
            assertEquals(path, "UNSUPPORTED_MEDIA_TYPE", W8.code(r));
        }
    }
}
