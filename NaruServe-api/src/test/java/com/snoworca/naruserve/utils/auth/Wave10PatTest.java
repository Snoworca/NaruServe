package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.AsyncRouteHandler;
import com.snoworca.naruserve.Registration;
import com.snoworca.naruserve.RegistrationKind;
import com.snoworca.naruserve.spi.ExchangeHandler;
import com.snoworca.naruserve.spi.HttpAdapter;
import com.snoworca.naruserve.spi.HttpExchange;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.utils.auth.apikey.ApiKeyPrefixCollisionException;
import com.snoworca.naruserve.utils.auth.apikey.IssuedApiKey;
import com.snoworca.naruserve.utils.auth.apikey.StoredApiKey;
import com.snoworca.naruserve.utils.auth.audit.AuditEvent;
import com.snoworca.naruserve.utils.auth.internal.FailureFloor;
import com.snoworca.naruserve.utils.auth.internal.HashSlots;
import com.snoworca.naruserve.utils.auth.internal.Passwords;
import com.snoworca.naruserve.utils.auth.password.HashCost;
import com.snoworca.naruserve.utils.auth.password.Pbkdf2PasswordHasher;
import com.snoworca.naruserve.utils.auth.rbac.RolePolicy;
import com.snoworca.naruserve.utils.auth.user.AuthUser;
import com.snoworca.naruserve.utils.auth.user.ForwardingUserStore;
import com.snoworca.naruserve.utils.auth.user.UserStore;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;
import org.junit.After;
import org.junit.Test;

/**
 * Personal access tokens ({@code POST/GET/DELETE /auth/me/apikeys}, FR-APIKEY-003), the admin key endpoints and their
 * check order (FR-APIKEY-004), the issuance limits and body checks (FR-APIKEY-005), the off-request stale-key deletion per owner (REL-APIKEY-002) and the issuance ACs that waited for
 * the PAT and admin endpoints (SEC-APIKEY-009, SEC-APIKEY-010), over the in-memory adapter.
 */
public class Wave10PatTest {

    static final String ROLES_NOTE = "reader = posts:read, writer = posts:write, keyadmin = auth-apikeys:write";

    final W8 f = new W8();
    private final List<W8> others = new ArrayList<>();
    final Wave9KeyAuthTest.Keys keys = new Wave9KeyAuthTest.Keys();
    final Wave9KeyAuthTest.Held held = new Wave9KeyAuthTest.Held();
    final List<AuditEvent> audit = new CopyOnWriteArrayList<>();
    String u1;
    String root;

    @After
    public void stop() {
        f.stop();
        for (W8 o : others) {
            o.stop();
        }
    }

    static RolePolicy roles() {
        return RolePolicy.builder().role("reader", "posts:read").role("writer", "posts:write")
                .role("keyadmin", "auth-apikeys:write", "auth-apikeys:read").role("editor", "posts:*").build();
    }

    void start(UnaryOperator<Auth.Builder> config) throws Exception {
        u1 = f.user("u1", W8.PW, "reader");
        root = f.user("root", W8.PW, "admin");
        f.start(b -> config.apply(b.apiKeys(keys).audit(audit::add).roles(roles())));
        f.auth.apiKeyOwners().deletions(held);
    }

    String bearer(String id) throws Exception {
        return "Bearer " + f.auth.accessToken(id, f.stored(id).getTokenVersion(), 900_000L);
    }

    static String body(@Nullable String password, String... scopes) {
        StringBuilder b = new StringBuilder("{\"name\":\"ci\",\"scopes\":[");
        for (int i = 0; i < scopes.length; i++) {
            b.append(i == 0 ? "" : ",").append('"').append(scopes[i]).append('"');
        }
        b.append(']');
        if (password != null) {
            b.append(",\"password\":\"").append(password).append('"');
        }
        return b.append('}').toString();
    }

    static String admin(String ownerId, @Nullable String password, String... scopes) {
        String b = body(password, scopes);
        return "{\"ownerUserId\":\"" + ownerId + "\"," + b.substring(1);
    }

    ClientResponse pat(String id, String json) throws Exception {
        return f.post("/auth/me/apikeys", json, "Authorization", bearer(id));
    }

    ClientResponse adminIssue(String actor, String json) throws Exception {
        return f.post("/auth/admin/apikeys", json, "Authorization", bearer(actor));
    }

    ClientResponse send(String method, String path, String auth) {
        return f.adapter.send(ClientRequest.of(method, path).header("Authorization", auth));
    }

    @SuppressWarnings("unchecked")
    static List<Object> array(ClientResponse r) {
        Object o = com.snoworca.naruserve.utils.auth.internal.MiniJson.parse(r.text(),
                com.snoworca.naruserve.utils.auth.internal.MiniJson.Limits.file());
        return o instanceof List ? (List<Object>) o : new ArrayList<Object>();
    }

    List<StoredApiKey> rows(String owner) {
        return keys.d.listByOwner(owner);
    }

    static void assertBadRequest(String what, ClientResponse r) {
        assertEquals(what + ": " + r.text(), 400, r.status());
        assertEquals(what, "BAD_REQUEST", W8.code(r));
    }

    // ---------------------------------------------------------------------------------------------- FR-APIKEY-003

    // FR-APIKEY-003 AC-1: POST /auth/me/apikeys with a correct password, name ci and scopes ["posts:read"] -> 201 with
    // the plaintext key; GET /auth/me/apikeys then lists it with status active and no secret or hash field
    @Test
    public void FR_APIKEY_003_AC1_issueThenList() throws Exception {
        start(b -> b);
        ClientResponse r = pat(u1, body(W8.PW, "posts:read"));
        assertEquals(r.text(), 201, r.status());
        Map<String, Object> issued = W8.object(r);
        String plain = (String) issued.get("key");
        assertTrue(plain, plain.startsWith("nk_"));
        assertEquals("ci", issued.get("name"));
        assertEquals("active", issued.get("status"));
        assertEquals("no-store", r.header("Cache-Control"));
        assertEquals(200, f.get("/api/x", "Authorization", "ApiKey " + plain).status());
        ClientResponse list = f.get("/auth/me/apikeys", "Authorization", bearer(u1));
        assertEquals(200, list.status());
        List<Object> items = array(list);
        assertEquals(1, items.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> k = (Map<String, Object>) items.get(0);
        assertEquals(issued.get("id"), k.get("id"));
        assertEquals("active", k.get("status"));
        for (String secret : new String[] {"key", "secret", "secretHash", "hash", "plainKey"}) {
            assertFalse(secret, k.containsKey(secret));
        }
        assertFalse(list.text(), list.text().contains(plain.substring(12)));
    }

    // FR-APIKEY-003 AC-2: a wrong password -> the same 401 as a wrong current password on /auth/password, counted in
    // the per-user limit (15 minutes, 5 attempts) shared with /auth/password; the 6th wrong attempt -> 429
    // too_many_attempts with Retry-After
    @Test
    public void FR_APIKEY_003_AC2_wrongPasswordSharesTheLimit() throws Exception {
        start(b -> b);
        ClientResponse viaPassword = f.post("/auth/password", Wave9PasswordTest.change("not the password",
                "a brand new passphrase"), "Authorization", bearer(u1));
        assertEquals(401, viaPassword.status());
        for (int i = 0; i < 4; i++) {
            ClientResponse r = pat(u1, body("not the password", "posts:read"));
            assertEquals(r.text(), viaPassword.status(), r.status());
            assertEquals(W8.code(viaPassword), W8.code(r));
            assertEquals(viaPassword.header("WWW-Authenticate"), r.header("WWW-Authenticate"));
        }
        ClientResponse sixth = pat(u1, body("not the password", "posts:read"));
        assertEquals(sixth.text(), 429, sixth.status());
        assertEquals("too_many_attempts", W8.code(sixth));
        assertNotNull(sixth.header("Retry-After"));
        assertEquals("even the correct password waits for the window", 429, pat(u1, body(W8.PW, "posts:read")).status());
        assertTrue(rows(u1).isEmpty());
        f.clock.addAndGet(15 * 60_000L + 1_000L);
        assertEquals(201, pat(u1, body(W8.PW, "posts:read")).status());
    }

    // FR-APIKEY-003 AC-3: a caller whose passwordHash is null (SSO) gets the same 401 as a wrong password
    @Test
    public void FR_APIKEY_003_AC3_aNullHashIsAWrongPassword() throws Exception {
        start(b -> b);
        String sso = f.user("sso", null, "reader");
        ClientResponse wrong = pat(u1, body("not the password", "posts:read"));
        ClientResponse r = pat(sso, body(W8.PW, "posts:read"));
        assertEquals(r.text(), wrong.status(), r.status());
        assertEquals(W8.code(wrong), W8.code(r));
        assertEquals("invalid_credentials", W8.code(r));
        assertTrue(rows(sso).isEmpty());
    }

    // FR-APIKEY-003 AC-4: a user holding only posts:read requesting posts:write -> 403 insufficient_scope
    @Test
    public void FR_APIKEY_003_AC4_scopesMustBeHeld() throws Exception {
        Wave8AccountTest.Counting counting = new Wave8AccountTest.Counting();
        start(b -> b.passwordHasher(counting));
        int work = counting.verifies.get() + counting.hashes.get();
        ClientResponse r = pat(u1, body(W8.PW, "posts:write"));
        assertEquals(r.text(), 403, r.status());
        assertEquals("insufficient_scope", W8.code(r));
        assertTrue(rows(u1).isEmpty());
        assertEquals("no password hash for a refused scope", work, counting.verifies.get() + counting.hashes.get());
    }

    // FR-APIKEY-003 AC-5: DELETE /auth/me/apikeys/:id with another user's key id or an unknown id -> 404 NOT_FOUND;
    // with an own key id the key is deleted without a password
    @Test
    public void FR_APIKEY_003_AC5_deleteOnlyOwnKeys() throws Exception {
        start(b -> b);
        String other = f.user("other", W8.PW, "reader");
        IssuedApiKey theirs = f.auth.apiKeys().issue(other, "theirs", null, "posts:read");
        String mine = (String) W8.object(pat(u1, body(W8.PW, "posts:read"))).get("id");
        for (String id : new String[] {theirs.getKey().getId(), "no-such-key"}) {
            ClientResponse r = send("DELETE", "/auth/me/apikeys/" + id, bearer(u1));
            assertEquals(r.text(), 404, r.status());
            assertEquals("NOT_FOUND", W8.code(r));
        }
        assertNotNull(keys.d.findById(theirs.getKey().getId()));
        assertEquals(204, send("DELETE", "/auth/me/apikeys/" + mine, bearer(u1)).status());
        assertNull(keys.d.findById(mine));
    }

    // FR-APIKEY-003 AC-6: "password": null or no password -> 400 BAD_REQUEST
    @Test
    public void FR_APIKEY_003_AC6_passwordIsRequired() throws Exception {
        start(b -> b);
        assertBadRequest("no password", pat(u1, body(null, "posts:read")));
        assertBadRequest("null password", pat(u1, "{\"name\":\"ci\",\"scopes\":[\"posts:read\"],\"password\":null}"));
        assertTrue(rows(u1).isEmpty());
    }

    // FR-APIKEY-003 AC-7: an expired JWT on POST /auth/me/apikeys -> 401 invalid_token, the handler does not run, and
    // with keys on the challenge is Bearer error="invalid_token", ApiKey
    @Test
    public void FR_APIKEY_003_AC7_expiredTokenIsInvalidToken() throws Exception {
        Wave8AccountTest.Counting counting = new Wave8AccountTest.Counting();
        start(b -> b.passwordHasher(counting));
        int work = counting.verifies.get();
        ClientResponse r = f.post("/auth/me/apikeys", body(W8.PW, "posts:read"), "Authorization",
                "Bearer " + f.expiredAccess(u1));
        assertEquals(r.text(), 401, r.status());
        assertEquals("invalid_token", W8.code(r));
        assertEquals("Bearer error=\"invalid_token\", ApiKey", r.header("WWW-Authenticate"));
        assertTrue(rows(u1).isEmpty());
        assertEquals(work, counting.verifies.get());
    }

    // FR-APIKEY-003 AC-8: on POST /auth/me/apikeys and POST /auth/admin/apikeys a wrong password and a caller whose
    // passwordHash is null each take one hash slot and run exactly one target-cost PBKDF2, and the 401 is scheduled
    // at max(1.25T, elapsed) plus 0-50 ms jitter (HashCost.calibrate(250 ms), T = 250 ms, a measurement stub, a fast
    // fake hasher and an injected scheduler: 312.5 ms plus jitter, never earlier); a correct password is not delayed
    // FR-APIKEY-004 AC-10: the admin issuance's wrong-password and null-hash 401s run one target-cost PBKDF2 in a hash
    // slot and are held to the same floor
    @Test
    public void FR_APIKEY_003_AC8_FR_APIKEY_004_AC10_constantWorkAndTheFloor() throws Exception {
        Wave8AccountTest.Counting counting = new Wave8AccountTest.Counting();
        start(b -> b.passwordHasher(counting));
        String ssoAdmin = f.user("ssoadmin", null, "admin");
        String ssoUser = f.user("ssouser", null, "reader");
        Passwords timing = new Passwords(new Pbkdf2PasswordHasher(1000), HashCost.calibrate(250), new HashSlots(1),
                iterations -> 1_000_000L);
        assertEquals(250_000_000L, timing.timing().timingNanos());
        Wave7LoginTest.RecordingScheduler sched = new Wave7LoginTest.RecordingScheduler();
        final AtomicLong nanos = new AtomicLong(5_000_000_000L);
        final long[] jitter = {0L};
        f.auth.failureFloor(new FailureFloor(timing.timing(), sched, nanos::get, () -> jitter[0]));
        f.auth.passwords().prepare();
        try {
            String[][] cases = {{"pat", u1, "not the password"}, {"pat", ssoUser, W8.PW},
                {"admin", root, "not the password"}, {"admin", ssoAdmin, W8.PW}};
            long[] jitters = {0L, 50_000_000L, 37_000_000L, 12_000_000L};
            for (int i = 0; i < cases.length; i++) {
                String[] c = cases[i];
                jitter[0] = jitters[i];
                sched.delaysNanos.clear();
                int verifies = counting.verifies.get();
                int hashes = counting.hashes.get();
                long slots = f.auth.hashSlots().requested();
                ClientResponse r = c[0].equals("pat") ? pat(c[1], body(c[2], "posts:read"))
                        : adminIssue(c[1], admin(u1, c[2], "posts:read"));
                assertEquals(c[1] + " " + r.text(), 401, r.status());
                assertEquals("invalid_credentials", W8.code(r));
                assertEquals(c[0] + ": one slot", slots + 1, f.auth.hashSlots().requested());
                assertEquals(c[0] + ": one target-cost PBKDF2 verify", verifies + 1, counting.verifies.get());
                assertEquals(c[0] + ": no hash of a new password", hashes, counting.hashes.get());
                assertEquals(c[0] + ": scheduled at the floor", Arrays.asList(312_500_000L + jitters[i]),
                        sched.delaysNanos);
            }
            sched.delaysNanos.clear();
            assertEquals(201, pat(u1, body(W8.PW, "posts:read")).status());
            assertEquals(201, adminIssue(root, admin(u1, W8.PW, "posts:read")).status());
            assertTrue("a correct password is not delayed", sched.delaysNanos.isEmpty());
        } finally {
            sched.shutdownNow();
            timing.slots().shutdown();
        }
    }

    /** A hasher recording the stored string of every verify. */
    static final class Recording implements com.snoworca.naruserve.utils.auth.password.PasswordHasher {
        final Pbkdf2PasswordHasher inner = new Pbkdf2PasswordHasher(1000);
        final List<String> verified = new CopyOnWriteArrayList<>();

        @Override
        public boolean accepts(String stored) {
            return inner.accepts(stored);
        }

        @Override
        public String hash(char[] password) {
            return inner.hash(password);
        }

        @Override
        public boolean verify(char[] password, String stored) {
            verified.add(stored);
            return inner.verify(password, stored);
        }

        @Override
        public boolean needsRehash(String stored) {
            return inner.needsRehash(stored);
        }
    }

    // FR-APIKEY-003 AC-8: the one verify of a wrong password and of a null-hash caller (the dummy) runs at the target
    // iteration count, on both issuers
    @Test
    public void FR_APIKEY_003_AC8_theOneVerifyRunsAtTheTargetCount() throws Exception {
        Recording hasher = new Recording();
        start(b -> b.passwordHasher(hasher));
        String ssoAdmin = f.user("ssoadmin", null, "admin");
        String ssoUser = f.user("ssouser", null, "reader");
        f.auth.passwords().prepare();
        String[][] cases = {{"pat", u1, "not the password"}, {"pat", ssoUser, W8.PW},
            {"admin", root, "not the password"}, {"admin", ssoAdmin, W8.PW}};
        for (String[] c : cases) {
            hasher.verified.clear();
            ClientResponse r = c[0].equals("pat") ? pat(c[1], body(c[2], "posts:read"))
                    : adminIssue(c[1], admin(u1, c[2], "posts:read"));
            assertEquals(r.text(), 401, r.status());
            assertEquals(c[0], 1, hasher.verified.size());
            assertTrue(hasher.verified.get(0), hasher.verified.get(0).startsWith(Pbkdf2PasswordHasher.PREFIX + "1000$"));
        }
    }

    // FR-APIKEY-003 AC-9: a PAT issued by u1 is stored with issuedByUserId u1 (owner and issuer u1) and the issue
    // audit event names u1 as the issuer
    @Test
    public void FR_APIKEY_003_AC9_theIssuerIsRecorded() throws Exception {
        start(b -> b);
        audit.clear();
        String id = (String) W8.object(pat(u1, body(W8.PW, "posts:read"))).get("id");
        StoredApiKey row = keys.d.findById(id);
        assertEquals(u1, row.getOwnerUserId());
        assertEquals(u1, row.getIssuedByUserId());
        AuditEvent issued = null;
        for (AuditEvent e : audit) {
            if (AuditEvent.API_KEY_ISSUED.equals(e.getType())) {
                issued = e;
            }
        }
        assertNotNull(audit.toString(), issued);
        assertEquals(u1, issued.getActorId());
        assertEquals(u1, issued.getTargetId());
        assertEquals(id, issued.getDetails().get("apiKeyId"));
        assertEquals(u1, issued.getDetails().get("issuedByUserId"));
    }

    // FR-APIKEY-003 AC-10: COOKIE, and BOTH with no Authorization header, a valid naru_at of family F and a correct
    // password: POST /auth/me/apikeys and POST /auth/admin/apikeys without X-CSRF-Token (same-origin Origin) -> 403
    // csrf_failed; with F's X-CSRF-Token and naru_csrf but Origin https://evil.example -> 403 csrf_failed; no key is
    // inserted and no password hash runs
    @Test
    public void FR_APIKEY_003_AC10_csrfAndOriginOnTheKeyIssuers() throws Exception {
        for (TokenTransport t : new TokenTransport[] {TokenTransport.COOKIE, TokenTransport.BOTH}) {
            W8 w = new W8();
            others.add(w);
            Wave9KeyAuthTest.Keys ks = new Wave9KeyAuthTest.Keys();
            Wave8AccountTest.Counting counting = new Wave8AccountTest.Counting();
            String alice = w.user("alice", W8.PW, "admin");
            w.start(b -> b.transport(t).apiKeys(ks).passwordHasher(counting));
            ClientResponse login = w.post("/auth/login", W8.login("alice", W8.PW), "Host", W8.HOST, "Origin", W8.SAME);
            String at = W8.cookieValue(login, "naru_at");
            String csrf = (String) W8.object(login).get("csrfToken");
            int work = counting.verifies.get() + counting.hashes.get();
            for (String path : new String[] {"/auth/me/apikeys", "/auth/admin/apikeys"}) {
                String json = path.contains("admin") ? admin(alice, W8.PW, "posts:read") : body(W8.PW, "posts:read");
                ClientResponse noCsrf = w.post(path, json, "Host", W8.HOST, "Origin", W8.SAME, "Cookie",
                        "naru_at=" + at + "; naru_csrf=" + csrf);
                ClientResponse evil = w.post(path, json, "Host", W8.HOST, "Origin", W8.EVIL, "Cookie",
                        "naru_at=" + at + "; naru_csrf=" + csrf, "X-CSRF-Token", csrf);
                for (ClientResponse r : new ClientResponse[] {noCsrf, evil}) {
                    assertEquals(t + " " + path + " " + r.text(), 403, r.status());
                    assertEquals("csrf_failed", W8.code(r));
                }
            }
            assertTrue(ks.d.listByOwner(alice).isEmpty());
            assertEquals("no password hash", work, counting.verifies.get() + counting.hashes.get());
            ClientResponse ok = w.post("/auth/me/apikeys", body(W8.PW, "posts:read"), "Host", W8.HOST, "Origin",
                    W8.SAME, "Cookie", "naru_at=" + at + "; naru_csrf=" + csrf, "X-CSRF-Token", csrf);
            assertEquals(t + " " + ok.text(), 201, ok.status());
        }
    }

    // ---------------------------------------------------------------------------------------------- FR-APIKEY-005

    // FR-APIKEY-005 AC-1: an owner with 20 live keys: PAT issuance -> 409 api_key_limit; app issue throws
    // AuthRejection(409, "api_key_limit"); admin issuance -> 409 api_key_limit
    @Test
    public void FR_APIKEY_005_AC1_theLimitOnEveryPath() throws Exception {
        start(b -> b);
        for (int i = 0; i < 20; i++) {
            f.auth.apiKeys().issue(u1, "k" + i, null, "posts:read");
        }
        ClientResponse r = pat(u1, body(W8.PW, "posts:read"));
        assertEquals(r.text(), 409, r.status());
        assertEquals("api_key_limit", W8.code(r));
        try {
            f.auth.apiKeys().issue(u1, "app", null, "posts:read");
            fail("issued");
        } catch (AuthRejection e) {
            assertEquals(409, e.getStatus());
            assertEquals("api_key_limit", e.getCode());
        }
        ClientResponse a = adminIssue(root, admin(u1, W8.PW, "posts:read"));
        assertEquals(a.text(), 409, a.status());
        assertEquals("api_key_limit", W8.code(a));
        assertEquals(20, rows(u1).size());
    }

    // FR-APIKEY-005 AC-2: with the off-request stale-key deletion held, an owner with 5 expired keys and 15 keys made
    // stale by ?all=true (0 live keys): PAT issuance -> 201, and the list shows those keys as expired and stale
    @Test
    public void FR_APIKEY_005_AC2_deadKeysDoNotCount() throws Exception {
        start(b -> b);
        for (int i = 0; i < 15; i++) {
            f.auth.apiKeys().issue(u1, "old" + i, null, "posts:read");
        }
        assertEquals(204, f.post("/auth/logout?all=true", null, "Authorization", bearer(u1)).status());
        for (int i = 0; i < 5; i++) {
            f.auth.apiKeys().issue(u1, "short" + i, f.clock.get() + 1_000L, "posts:read");
        }
        f.clock.addAndGet(2_000L);
        assertEquals(20, rows(u1).size());
        ClientResponse r = pat(u1, body(W8.PW, "posts:read"));
        assertEquals(r.text(), 201, r.status());
        int expired = 0;
        int stale = 0;
        int active = 0;
        for (Object o : array(f.get("/auth/me/apikeys", "Authorization", bearer(u1)))) {
            String s = (String) ((Map<?, ?>) o).get("status");
            expired += "expired".equals(s) ? 1 : 0;
            stale += "stale".equals(s) ? 1 : 0;
            active += "active".equals(s) ? 1 : 0;
        }
        assertEquals(5, expired);
        assertEquals(15, stale);
        assertEquals(1, active);
    }

    // FR-APIKEY-005 AC-3: PAT or admin issuance with expiresInDays 400 (above the default 365), 0, 1.5 or the string
    // "30" -> 400 BAD_REQUEST
    @Test
    public void FR_APIKEY_005_AC3_expiresInDaysMustBeAnIntegerInRange() throws Exception {
        start(b -> b);
        for (String days : new String[] {"400", "0", "1.5", "\"30\"", "-1", "true"}) {
            String extra = ",\"expiresInDays\":" + days + "}";
            String p = body(W8.PW, "posts:read");
            String a = admin(u1, W8.PW, "posts:read");
            assertBadRequest("PAT " + days, pat(u1, p.substring(0, p.length() - 1) + extra));
            assertBadRequest("admin " + days, adminIssue(root, a.substring(0, a.length() - 1) + extra));
        }
        assertTrue(rows(u1).isEmpty());
        String p = body(W8.PW, "posts:read");
        assertEquals(201, pat(u1, p.substring(0, p.length() - 1) + ",\"expiresInDays\":365}").status());
    }

    // FR-APIKEY-005 AC-4: PAT issuance without expiresInDays stores expiresAt = issuance time + 365 days in
    // milliseconds (31536000000 ms)
    @Test
    public void FR_APIKEY_005_AC4_theDefaultLifetime() throws Exception {
        start(b -> b);
        String id = (String) W8.object(pat(u1, body(W8.PW, "posts:read"))).get("id");
        StoredApiKey row = keys.d.findById(id);
        assertEquals(W8.T0, row.getCreatedAt());
        assertEquals(Long.valueOf(row.getCreatedAt() + 31_536_000_000L), row.getExpiresAt());
    }

    // FR-APIKEY-005 AC-5: PAT issuance with empty scopes, missing name, "name": null or a non-string name -> 400 with
    // body code BAD_REQUEST through Core HttpException(400), not AuthRejection
    @Test
    public void FR_APIKEY_005_AC5_bodyChecks() throws Exception {
        start(b -> b);
        String[] bodies = {"{\"name\":\"ci\",\"scopes\":[],\"password\":\"" + W8.PW + "\"}",
            "{\"scopes\":[\"posts:read\"],\"password\":\"" + W8.PW + "\"}",
            "{\"name\":null,\"scopes\":[\"posts:read\"],\"password\":\"" + W8.PW + "\"}",
            "{\"name\":7,\"scopes\":[\"posts:read\"],\"password\":\"" + W8.PW + "\"}",
            "{\"name\":\"ci\",\"scopes\":\"posts:read\",\"password\":\"" + W8.PW + "\"}",
            "{\"name\":\"ci\",\"scopes\":[7],\"password\":\"" + W8.PW + "\"}"};
        for (String b : bodies) {
            ClientResponse r = pat(u1, b);
            assertBadRequest(b, r);
            assertTrue(r.text(), r.text().contains("\"BAD_REQUEST\""));
        }
        assertTrue(rows(u1).isEmpty());
    }

    // FR-APIKEY-005 AC-6: with .maxKeysPerUser(2) and .maxApiKeyDays(30) a third live key -> 409 api_key_limit and
    // expiresInDays 31 -> 400 BAD_REQUEST
    @Test
    public void FR_APIKEY_005_AC6_configuredLimits() throws Exception {
        start(b -> b.maxKeysPerUser(2).maxApiKeyDays(30));
        String p = body(W8.PW, "posts:read");
        assertBadRequest("31 days", pat(u1, p.substring(0, p.length() - 1) + ",\"expiresInDays\":31}"));
        String id = (String) W8.object(pat(u1, p)).get("id");
        assertEquals(Long.valueOf(W8.T0 + 30L * 86_400_000L), keys.d.findById(id).getExpiresAt());
        assertEquals(201, pat(u1, p.substring(0, p.length() - 1) + ",\"expiresInDays\":30}").status());
        ClientResponse third = pat(u1, p);
        assertEquals(third.text(), 409, third.status());
        assertEquals("api_key_limit", W8.code(third));
    }

    /** A user store counting the reads of each id. */
    static final class Reads extends ForwardingUserStore {
        final Map<String, AtomicInteger> byId = new java.util.concurrent.ConcurrentHashMap<>();

        Reads(UserStore d) {
            super(d);
        }

        @Override
        public @Nullable AuthUser findById(String id) throws Exception {
            AtomicInteger n = byId.get(id);
            if (n == null) {
                byId.putIfAbsent(id, new AtomicInteger());
                n = byId.get(id);
            }
            n.incrementAndGet();
            return super.findById(id);
        }

        int of(String id) {
            AtomicInteger n = byId.get(id);
            return n == null ? 0 : n.get();
        }
    }

    // FR-APIKEY-005 AC-7: scopes breaking the R46 grammar, ["Posts:Read"] and ["po*"], from a caller holding * with the
    // correct password: PAT -> 400 BAD_REQUEST (not 403) and no key; admin -> the same 400, also when ownerUserId names
    // no user (not 404), with no owner read and no password hash
    @Test
    public void FR_APIKEY_005_AC7_grammarBeforeOwnerAndPassword() throws Exception {
        Reads reads = new Reads(f.store);
        f.users = reads;
        Wave8AccountTest.Counting counting = new Wave8AccountTest.Counting();
        start(b -> b.passwordHasher(counting));
        int work = counting.verifies.get() + counting.hashes.get();
        for (String bad : new String[] {"Posts:Read", "po*"}) {
            assertBadRequest("PAT " + bad, pat(root, body(W8.PW, bad)));
            assertBadRequest("admin " + bad, adminIssue(root, admin(u1, W8.PW, bad)));
            assertBadRequest("admin unknown owner " + bad, adminIssue(root, admin("ghost", W8.PW, bad)));
        }
        assertEquals("no owner read", 0, reads.of("ghost"));
        assertEquals("no password hash", work, counting.verifies.get() + counting.hashes.get());
        assertTrue(rows(root).isEmpty());
        assertTrue(rows(u1).isEmpty());
    }

    // FR-APIKEY-005 AC-5: the PAT body errors are thrown as a Core HttpException(400), not as an AuthRejection (the body
    // check both issuers run; the 400 BAD_REQUEST answers over HTTP are asserted by the _bodyChecks test)
    // FR-APIKEY-005 AC-7: so are the R46 grammar refusals, on both issuers
    @Test
    @SuppressWarnings("unchecked")
    public void FR_APIKEY_005_AC5_AC7_refusalsAreCoreHttpExceptions() throws Exception {
        start(b -> b);
        KeyIssuance issuance = new KeyIssuance(f.auth);
        String pw = "\"password\":\"" + W8.PW + "\"}";
        for (String json : new String[] {"{\"name\":\"ci\",\"scopes\":[]," + pw, "{\"scopes\":[\"posts:read\"]," + pw,
            "{\"name\":null,\"scopes\":[\"posts:read\"]," + pw, "{\"name\":7,\"scopes\":[\"posts:read\"]," + pw,
            body(W8.PW, "Posts:Read"), body(W8.PW, "po*"), admin("ghost", W8.PW, "Posts:Read"),
            admin("ghost", W8.PW, "po*")}) {
            Map<String, Object> parsed = (Map<String, Object>) com.snoworca.naruserve.utils.auth.internal.MiniJson
                    .parse(json, com.snoworca.naruserve.utils.auth.internal.MiniJson.Limits.file());
            try {
                issuance.spec(parsed, parsed.containsKey("ownerUserId"));
                fail("accepted " + json);
            } catch (com.snoworca.naruserve.HttpException e) {
                assertFalse(json, e instanceof AuthRejection);
                assertEquals(json, 400, e.status());
            }
        }
    }

    // ---------------------------------------------------------------------------------------------- SEC-APIKEY-009

    // SEC-APIKEY-009 AC-1: opt-in off: PAT and admin issuance with scope auth-users:write -> 400 BAD_REQUEST; the app
    // API auth.apiKeys().issue(owner, "n", null, "auth-users:write") throws IllegalArgumentException
    @Test
    public void SEC_APIKEY_009_AC1_adminScopesNeedTheOptIn() throws Exception {
        start(b -> b);
        assertBadRequest("PAT", pat(root, body(W8.PW, "auth-users:write")));
        assertBadRequest("admin", adminIssue(root, admin(root, W8.PW, "auth-users:write")));
        try {
            f.auth.apiKeys().issue(root, "n", null, "auth-users:write");
            fail("issued");
        } catch (IllegalArgumentException expected) {
            assertTrue(rows(root).isEmpty());
        }
    }

    // SEC-APIKEY-009 AC-6: an admin holding only auth-apikeys:write issuing a key with scope * -> 403
    // insufficient_scope (the new key's scopes must be covered by the actor's scopes)
    @Test
    public void SEC_APIKEY_009_AC6_newScopesMustBeCovered() throws Exception {
        start(b -> b);
        String keyAdmin = f.user("keyadmin", W8.PW, "keyadmin");
        String bot = f.user("bot", W8.PW);
        ClientResponse r = adminIssue(keyAdmin, admin(bot, W8.PW, "*"));
        assertEquals(r.text(), 403, r.status());
        assertEquals("insufficient_scope", W8.code(r));
        assertTrue(rows(bot).isEmpty());
    }

    // ---------------------------------------------------------------------------------------------- SEC-APIKEY-010

    /** A W8 node over the same user and key stores, with its own principal cache. */
    W8 nodeB() throws Exception {
        W8 b = new W8();
        b.users = f.store;
        b.clock.set(f.clock.get());
        others.add(b);
        b.start(x -> x.apiKeys(keys).roles(roles()));
        b.auth.apiKeyOwners().deletions(held);
        return b;
    }

    private void setVer(String id, long ver) throws Exception {
        f.store.update(f.stored(id).toBuilder().tokenVersion(ver).build());
    }

    // SEC-APIKEY-010 AC-1: node B's principal cache holds ver 5 while the store holds ver 6; a PAT issued on node B
    // gets ownerVer 6 and authenticates on its first use
    @Test
    public void SEC_APIKEY_010_AC1_aPatTakesTheStoreVer() throws Exception {
        start(b -> b);
        setVer(u1, 5);
        W8 b = nodeB();
        String token5 = "Bearer " + b.auth.accessToken(u1, 5, 900_000L);
        assertEquals(200, b.get("/api/x", "Authorization", token5).status()); // node B caches ver 5
        f.store.bumpTokenVersion(u1);
        assertEquals(6, f.stored(u1).getTokenVersion());
        ClientResponse r = b.post("/auth/me/apikeys", body(W8.PW, "posts:read"), "Authorization", token5);
        assertEquals(r.text(), 201, r.status());
        String id = (String) W8.object(r).get("id");
        assertEquals(6, keys.d.findById(id).getOwnerVer());
        assertEquals(200, b.get("/api/x", "Authorization", "ApiKey " + W8.object(r).get("key")).status());
    }

    /** A counting PBKDF2 hasher whose verify of {@link #PAUSE_PW} reports that it started and then waits. */
    static final class Pausing implements com.snoworca.naruserve.utils.auth.password.PasswordHasher {
        static final String PAUSE_PW = "pause me here please";
        final Pbkdf2PasswordHasher inner = new Pbkdf2PasswordHasher(1000);
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public boolean accepts(String stored) {
            return inner.accepts(stored);
        }

        @Override
        public String hash(char[] password) {
            return inner.hash(password);
        }

        @Override
        public boolean verify(char[] password, String stored) {
            if (new String(password).equals(PAUSE_PW)) {
                started.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return inner.verify(password, stored);
        }

        @Override
        public boolean needsRehash(String stored) {
            return inner.needsRehash(stored);
        }
    }

    // SEC-APIKEY-010 AC-2: with the off-request stale-key deletion held, a PAT issuance whose password check read ver 5
    // followed by ?all=true on another node before the insert stores a key with ownerVer 5 that gets 401
    // invalid_api_key and is listed as stale
    // SEC-APIKEY-010 AC-6: SINGLE_INSTANCE, one Auth: (a) a PAT issuance paused by a test PasswordHasher hook after its
    // password check read the owner at ver 5; the owner's POST /auth/logout?all=true completes (ver 6); resumed, it
    // calls deleteFutureByOwner(owner, 6) inside the owner lock, stores ownerVer 5, the key gets 401 invalid_api_key
    // and is listed as stale
    @Test(timeout = 30000)
    public void SEC_APIKEY_010_AC2_AC6a_aPatBehindALogoutOfAllDevices() throws Exception {
        for (int way = 0; way < 2; way++) {
            W8 w = new W8();
            others.add(w);
            Wave9KeyAuthTest.Keys ks = new Wave9KeyAuthTest.Keys();
            Pausing hasher = new Pausing();
            String owner = w.user("owner", Pausing.PAUSE_PW, "reader");
            w.store.update(w.stored(owner).toBuilder().tokenVersion(5).build());
            w.start(b -> b.apiKeys(ks).passwordHasher(hasher));
            w.auth.apiKeyOwners().deletions(held);
            W8 other = w;
            if (way == 1) {
                other = new W8();
                other.users = w.store;
                others.add(other);
                other.start(b -> b.apiKeys(ks));
                other.auth.apiKeyOwners().deletions(held);
            }
            String token5 = "Bearer " + w.auth.accessToken(owner, 5, 900_000L);
            Future<ClientResponse> issuing = w.adapter.submit(ClientRequest.json("POST", "/auth/me/apikeys",
                    body(Pausing.PAUSE_PW, "posts:read")).header("Authorization", token5));
            assertTrue("the password check started", hasher.started.await(10, TimeUnit.SECONDS));
            ClientResponse logout = other.post("/auth/logout?all=true", null, "Authorization",
                    "Bearer " + other.auth.accessToken(owner, 5, 900_000L));
            assertEquals(204, logout.status());
            assertEquals(6, w.stored(owner).getTokenVersion());
            ks.deletes.clear();
            final List<String> futureInLock = new CopyOnWriteArrayList<>();
            ks.futureCheck = id -> futureInLock.add("deleteFutureByOwner lock=" + ApiKeyOwners.heldByCurrentThread(id));
            hasher.release.countDown();
            ClientResponse r = issuing.get(10, TimeUnit.SECONDS);
            assertEquals("way " + way + " " + r.text(), 201, r.status());
            String id = (String) W8.object(r).get("id");
            assertEquals(5, ks.d.findById(id).getOwnerVer());
            assertTrue(ks.deletes.toString(), ks.deletes.contains("deleteFutureByOwner " + owner + " 6"));
            assertEquals(Arrays.asList("deleteFutureByOwner lock=true"), futureInLock);
            w.clock.addAndGet(5_001L); // past userCacheTtl: the issuing node sees ver 6 in every way
            ClientResponse use = w.get("/api/x", "Authorization", "ApiKey " + W8.object(r).get("key"));
            assertEquals(401, use.status());
            assertEquals("invalid_api_key", W8.code(use));
            String status = null;
            for (Object o : array(w.get("/auth/me/apikeys", "Authorization",
                    "Bearer " + w.auth.accessToken(owner, 6, 900_000L)))) {
                if (id.equals(((Map<?, ?>) o).get("id"))) {
                    status = (String) ((Map<?, ?>) o).get("status");
                }
            }
            assertEquals("stale", status);
            held.queued.clear();
        }
    }

    // SEC-APIKEY-010 AC-6: (b) an admin issuance for owner S paused after its R31 re-read read S enabled at ver 5; A4
    // then disables S (ver 6); resumed, it stores the key with ownerVer 5; after A4 re-enables S (no ver change), a
    // request with that key gets 401 invalid_api_key
    @Test(timeout = 30000)
    public void SEC_APIKEY_010_AC6b_anAdminIssuanceBehindADisable() throws Exception {
        Pausing hasher = new Pausing();
        String admin = f.user("ops", Pausing.PAUSE_PW, "admin");
        start(b -> b.passwordHasher(hasher));
        String s = f.user("svc", null, "reader");
        setVer(s, 5);
        Future<ClientResponse> issuing = f.adapter.submit(ClientRequest.json("POST", "/auth/admin/apikeys",
                admin(s, Pausing.PAUSE_PW, "posts:read")).header("Authorization", bearer(admin)));
        assertTrue(hasher.started.await(10, TimeUnit.SECONDS));
        ClientResponse disable = f.adapter.send(ClientRequest.json("PATCH", "/auth/admin/users/" + s,
                "{\"enabled\":false}").header("Authorization", bearer(root)));
        assertEquals(disable.text(), 200, disable.status());
        assertEquals(6, f.stored(s).getTokenVersion());
        hasher.release.countDown();
        ClientResponse r = issuing.get(10, TimeUnit.SECONDS);
        assertEquals(r.text(), 201, r.status());
        String id = (String) W8.object(r).get("id");
        assertEquals(5, keys.d.findById(id).getOwnerVer());
        assertEquals(admin, keys.d.findById(id).getIssuedByUserId());
        ClientResponse enable = f.adapter.send(ClientRequest.json("PATCH", "/auth/admin/users/" + s,
                "{\"enabled\":true}").header("Authorization", bearer(root)));
        assertEquals(200, enable.status());
        assertEquals("re-enabling does not raise ver", 6, f.stored(s).getTokenVersion());
        ClientResponse use = f.get("/api/x", "Authorization", "ApiKey " + W8.object(r).get("key"));
        assertEquals(401, use.status());
        assertEquals("invalid_api_key", W8.code(use));
    }

    /** A key store whose limited insert always reports a prefix collision. */
    static final class Colliding extends Wave9KeyAuthTest.Keys {
        final AtomicInteger inserts = new AtomicInteger();

        @Override
        public boolean insertIfUnderLimit(StoredApiKey key, int maxLiveKeys, long nowMillis) {
            inserts.incrementAndGet();
            throw new ApiKeyPrefixCollisionException("prefix " + key.getPrefix() + " exists");
        }
    }

    // SEC-APIKEY-010 AC-4: a store throwing ApiKeyPrefixCollisionException on the first attempt and on all 3 retries:
    // the endpoint answers 503 SERVICE_UNAVAILABLE and the app API throws AuthUnavailableException
    @Test
    public void SEC_APIKEY_010_AC4_collisionsExhaustTheRetries() throws Exception {
        Colliding colliding = new Colliding();
        u1 = f.user("u1", W8.PW, "reader");
        root = f.user("root", W8.PW, "admin");
        f.start(b -> b.apiKeys(colliding).roles(roles()));
        for (ClientResponse r : new ClientResponse[] {pat(u1, body(W8.PW, "posts:read")),
            adminIssue(root, admin(u1, W8.PW, "posts:read"))}) {
            assertEquals(r.text(), 503, r.status());
            assertEquals("SERVICE_UNAVAILABLE", W8.code(r));
        }
        assertEquals("1 + 3 attempts per issuance", 8, colliding.inserts.get());
        try {
            f.auth.apiKeys().issue(u1, "app", null, "posts:read");
            fail("issued");
        } catch (AuthUnavailableException expected) {
            assertEquals(12, colliding.inserts.get());
        }
    }

    // ---------------------------------------------------------------------------------------------- REL-APIKEY-002

    // REL-APIKEY-002 AC-1: with the deletion executor held, SINGLE_INSTANCE, 100 concurrent requests with stale keys of
    // one owner (observed vers 5 and 6) produce exactly one pending deleteStaleByOwner for that owner, called with
    // ver 6
    @Test(timeout = 30000)
    public void REL_APIKEY_002_AC1_oneDeletionPerOwnerWithTheLargestVer() throws Exception {
        start(b -> b.apiKeyFailureLimit(1000, 900_000L));
        setVer(u1, 4);
        String stale = f.auth.apiKeys().issue(u1, "old", null, "posts:read").getPlainKey(); // ownerVer 4
        setVer(u1, 5);
        // the principal cache holds ver 5; the store moves on to 6 behind it (the clock stands still, so the cache
        // does not expire)
        assertEquals(200, f.get("/api/x", "Authorization", "Bearer " + f.auth.accessToken(u1, 5, 900_000L)).status());
        setVer(u1, 6);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            final String header = "ApiKey " + stale;
            // 50 concurrent requests whose cached principal holds ver 5
            assertAll401(pool, header, 50);
            assertEquals("the requests at ver 5 coalesce into one pending deletion", 1, held.queued.size());
            assertEquals(Long.valueOf(5), f.auth.apiKeyOwners().pendingStale(u1));
            // and 50 that read ver 6 from the store, while that deletion is still pending
            f.auth.userRevoked(u1);
            assertAll401(pool, header, 50);
        } finally {
            pool.shutdownNow();
        }
        assertEquals("one pending deletion for the owner", 1, held.queued.size());
        assertEquals(Long.valueOf(6), f.auth.apiKeyOwners().pendingStale(u1));
        // REL-APIKEY-002 AC-1 out of order: a ver-5 observation landing after the ver-6 one (a request that read its
        // principal before the ver-6 batch) neither lowers the pending ver nor schedules a second deletion
        f.auth.apiKeyOwners().scheduleStale(u1, 5);
        assertEquals("the largest observed ver wins", Long.valueOf(6), f.auth.apiKeyOwners().pendingStale(u1));
        assertEquals("still one pending deletion for the owner", 1, held.queued.size());
        keys.deletes.clear();
        held.runAll();
        assertEquals(Arrays.asList("deleteStaleByOwner " + u1 + " 6"), keys.deletes);
        assertNull(f.auth.apiKeyOwners().pendingStale(u1));
    }

    // REL-APIKEY-002 AC-2: a stale-key request arriving after the pending deletion for its owner has started schedules
    // a new deletion
    @Test(timeout = 30000)
    public void REL_APIKEY_002_AC2_aRequestAfterTheStartSchedulesAgain() throws Exception {
        start(b -> b);
        String stale = f.auth.apiKeys().issue(u1, "old", null, "posts:read").getPlainKey();
        f.store.bumpTokenVersion(u1);
        f.auth.userRevoked(u1);
        final CountDownLatch inside = new CountDownLatch(1);
        final CountDownLatch go = new CountDownLatch(1);
        keys.deleteStaleHook = owner -> {
            inside.countDown();
            try {
                go.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        assertEquals(401, f.get("/api/x", "Authorization", "ApiKey " + stale).status());
        assertEquals(1, held.queued.size());
        final Runnable job = held.queued.remove(0);
        Thread runner = new Thread(job);
        runner.start();
        assertTrue(inside.await(10, TimeUnit.SECONDS));
        assertNull("the owner left the pending set when the deletion started", f.auth.apiKeyOwners().pendingStale(u1));
        assertEquals(401, f.get("/api/x", "Authorization", "ApiKey " + stale).status());
        assertEquals("a new deletion is scheduled", 1, held.queued.size());
        go.countDown();
        runner.join(10_000);
    }

    // REL-APIKEY-002 AC-3: node A raises ver 5 -> 6 and issues K6 (ownerVer 6); node B with cached ver 5 meets K4
    // (ownerVer 4) and calls deleteStaleByOwner(u, 5): K4 is deleted and K6 remains
    @Test
    public void REL_APIKEY_002_AC3_anOlderVerDeletesOnlyOlderKeys() throws Exception {
        start(b -> b);
        setVer(u1, 4);
        IssuedApiKey k4 = f.auth.apiKeys().issue(u1, "k4", null, "posts:read");
        setVer(u1, 5);
        W8 b = nodeB();
        assertEquals(200, b.get("/api/x", "Authorization", "Bearer " + b.auth.accessToken(u1, 5, 900_000L)).status());
        f.store.bumpTokenVersion(u1); // node A: 5 -> 6
        IssuedApiKey k6 = f.auth.apiKeys().issue(u1, "k6", null, "posts:read");
        assertEquals(6, k6.getKey().getOwnerVer());
        keys.deletes.clear();
        assertEquals(401, b.get("/api/x", "Authorization", "ApiKey " + k4.getPlainKey()).status());
        held.runAll();
        assertEquals(Arrays.asList("deleteStaleByOwner " + u1 + " 5"), keys.deletes);
        assertNull(keys.d.findById(k4.getKey().getId()));
        assertNotNull(keys.d.findById(k6.getKey().getId()));
    }

    // REL-APIKEY-002 AC-4: the request that met the stale key gets its 401 without waiting for the deletion, which
    // runs on another thread
    @Test(timeout = 30000)
    public void REL_APIKEY_002_AC4_theRequestDoesNotWait() throws Exception {
        start(b -> b);
        String stale = f.auth.apiKeys().issue(u1, "old", null, "posts:read").getPlainKey();
        f.store.bumpTokenVersion(u1);
        f.auth.userRevoked(u1);
        final List<String> threads = new CopyOnWriteArrayList<>();
        final CountDownLatch go = new CountDownLatch(1);
        keys.deleteStaleHook = owner -> {
            threads.add(Thread.currentThread().getName());
            try {
                go.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        f.auth.apiKeyOwners().deletions(job -> new Thread(job, "deleter").start());
        ClientResponse r = f.get("/api/x", "Authorization", "ApiKey " + stale);
        assertEquals(401, r.status());
        assertEquals("invalid_api_key", W8.code(r));
        go.countDown();
        for (int i = 0; i < 500 && threads.isEmpty(); i++) {
            Thread.sleep(10);
        }
        assertEquals(Arrays.asList("deleter"), threads);
    }

    // REL-APIKEY-002 AC-5: an owner at ver 5 holding keys of ownerVer 5 calls POST /auth/logout?all=true (5 -> 6) with
    // no key request in between: deleteStaleByOwner(u, 6) is called exactly once, off the request path, after the
    // ver++; with the deletion executor held the 204 has already arrived and the call is still pending
    @Test
    public void REL_APIKEY_002_AC5_aLogoutOfAllDevicesSchedulesTheDeletion() throws Exception {
        start(b -> b);
        setVer(u1, 5);
        f.auth.apiKeys().issue(u1, "a", null, "posts:read");
        f.auth.apiKeys().issue(u1, "b", null, "posts:read");
        keys.deletes.clear();
        ClientResponse r = f.post("/auth/logout?all=true", null, "Authorization", bearer(u1));
        assertEquals(204, r.status());
        assertEquals(6, f.stored(u1).getTokenVersion());
        assertFalse("pending, not on the request path", keys.deletes.toString().contains("deleteStaleByOwner"));
        assertEquals(1, held.queued.size());
        assertEquals(Long.valueOf(6), f.auth.apiKeyOwners().pendingStale(u1));
        held.runAll();
        List<String> stale = new ArrayList<>();
        for (String d : keys.deletes) {
            if (d.startsWith("deleteStaleByOwner")) {
                stale.add(d);
            }
        }
        assertEquals(Arrays.asList("deleteStaleByOwner " + u1 + " 6"), stale);
        assertTrue(rows(u1).isEmpty());
    }

    // ---------------------------------------------------------------------------------------------- FR-APIKEY-004

    private ClientResponse list(String actor, String query) throws Exception {
        return f.get("/auth/admin/apikeys" + query, "Authorization", bearer(actor));
    }

    // FR-APIKEY-004 AC-1: GET /auth/admin/apikeys without ownerUserId -> 400 BAD_REQUEST; ?ownerUserId=u1 -> u1's keys
    // with status, also when u1 is disabled; an unknown ownerUserId -> 404 NOT_FOUND
    @Test
    public void FR_APIKEY_004_AC1_theListNeedsAnOwner() throws Exception {
        start(b -> b);
        IssuedApiKey k = f.auth.apiKeys().issue(u1, "ci", null, "posts:read");
        assertBadRequest("no owner", list(root, ""));
        ClientResponse r = list(root, "?ownerUserId=" + u1);
        assertEquals(r.text(), 200, r.status());
        List<Object> items = array(r);
        assertEquals(1, items.size());
        assertEquals(k.getKey().getId(), ((Map<?, ?>) items.get(0)).get("id"));
        assertEquals("active", ((Map<?, ?>) items.get(0)).get("status"));
        assertTrue(r.text(), !r.text().contains("secretHash"));
        f.store.setEnabled(u1, false);
        ClientResponse disabled = list(root, "?ownerUserId=" + u1);
        assertEquals(200, disabled.status());
        assertEquals(1, array(disabled).size());
        ClientResponse unknown = list(root, "?ownerUserId=ghost");
        assertEquals(404, unknown.status());
        assertEquals("NOT_FOUND", W8.code(unknown));
    }

    // FR-APIKEY-004 AC-1 (disabled owner): the keys of a disabled owner are listed with a status too
    @Test
    public void FR_APIKEY_004_AC1_aDisabledOwnersKeysKeepAStatus() throws Exception {
        start(b -> b);
        IssuedApiKey k = f.auth.apiKeys().issue(u1, "ci", null, "posts:read");
        f.store.setEnabled(u1, false);
        List<Object> items = array(list(root, "?ownerUserId=" + u1));
        assertEquals(1, items.size());
        Map<?, ?> item = (Map<?, ?>) items.get(0);
        assertEquals(k.getKey().getId(), item.get("id"));
        assertTrue(String.valueOf(item), Arrays.asList("active", "expired", "stale").contains(item.get("status")));
    }

    // FR-APIKEY-004 AC-2: POST with an unknown ownerUserId -> 404 NOT_FOUND; with a disabled owner that passes the grant
    // rule -> 400 BAD_REQUEST; with a disabled owner protected by target protection -> 403 insufficient_scope; none of
    // the three runs the password hash or counts toward the per-user limit
    @Test
    public void FR_APIKEY_004_AC2_ownerChecksBeforeThePassword() throws Exception {
        Wave8AccountTest.Counting counting = new Wave8AccountTest.Counting();
        start(b -> b.passwordHasher(counting));
        String delegated = f.user("dee", W8.PW, "keyadmin", "reader");
        String plain = f.user("plain", W8.PW, "reader");
        String boss = f.user("boss", W8.PW, "admin");
        f.store.setEnabled(plain, false);
        f.store.setEnabled(boss, false);
        int work = counting.verifies.get() + counting.hashes.get();
        for (int i = 0; i < 2; i++) { // twice each: a counted attempt would reach the limit of 5 below
            ClientResponse unknown = adminIssue(delegated, admin("ghost", W8.PW, "posts:read"));
            assertEquals(unknown.text(), 404, unknown.status());
            assertEquals("NOT_FOUND", W8.code(unknown));
            assertBadRequest("disabled owner", adminIssue(delegated, admin(plain, W8.PW, "posts:read")));
            ClientResponse guarded = adminIssue(delegated, admin(boss, W8.PW, "posts:read"));
            assertEquals(guarded.text(), 403, guarded.status());
            assertEquals("insufficient_scope", W8.code(guarded));
        }
        assertEquals("no password hash", work, counting.verifies.get() + counting.hashes.get());
        assertTrue(rows(plain).isEmpty());
        assertTrue(rows(boss).isEmpty());
        for (int i = 0; i < 5; i++) {
            assertEquals("attempt " + (i + 1) + " still counts from zero", 401,
                    adminIssue(delegated, admin(u1, "not the password", "posts:read")).status());
        }
        assertEquals(429, adminIssue(delegated, admin(u1, "not the password", "posts:read")).status());
    }

    // FR-APIKEY-004 AC-3: POST with empty scopes and an unknown ownerUserId -> 400 BAD_REQUEST (body check first);
    // "ownerUserId": null, no ownerUserId, "ownerUserId": 7, no name and "name": 7 each -> 400 before any owner read or
    // password check
    @Test
    public void FR_APIKEY_004_AC3_theBodyIsCheckedFirst() throws Exception {
        Reads reads = new Reads(f.store);
        f.users = reads;
        Wave8AccountTest.Counting counting = new Wave8AccountTest.Counting();
        start(b -> b.passwordHasher(counting));
        int work = counting.verifies.get() + counting.hashes.get();
        String pw = ",\"password\":\"" + W8.PW + "\"}";
        String[] bodies = {"{\"ownerUserId\":\"ghost\",\"name\":\"ci\",\"scopes\":[]" + pw,
            "{\"ownerUserId\":null,\"name\":\"ci\",\"scopes\":[\"posts:read\"]" + pw,
            "{\"name\":\"ci\",\"scopes\":[\"posts:read\"]" + pw,
            "{\"ownerUserId\":7,\"name\":\"ci\",\"scopes\":[\"posts:read\"]" + pw,
            "{\"ownerUserId\":\"ghost\",\"scopes\":[\"posts:read\"]" + pw,
            "{\"ownerUserId\":\"ghost\",\"name\":7,\"scopes\":[\"posts:read\"]" + pw};
        for (String b : bodies) {
            assertBadRequest(b, adminIssue(root, b));
        }
        assertEquals("no owner read", 0, reads.of("ghost"));
        assertEquals("no password check", work, counting.verifies.get() + counting.hashes.get());
    }

    // FR-APIKEY-004 AC-4: with a stolen access token, no password and separately "password": null each -> 400 before
    // any owner read or password hash, neither counting toward the per-user limit; a wrong password -> 401 and counts
    @Test
    public void FR_APIKEY_004_AC4_theCallersPasswordIsRequired() throws Exception {
        Reads reads = new Reads(f.store);
        f.users = reads;
        Wave8AccountTest.Counting counting = new Wave8AccountTest.Counting();
        start(b -> b.passwordHasher(counting));
        String target = f.user("target", W8.PW, "reader");
        int work = counting.verifies.get() + counting.hashes.get();
        int targetReads = reads.of(target);
        for (int i = 0; i < 5; i++) {
            assertBadRequest("no password", adminIssue(root, admin(target, null, "posts:read")));
            assertBadRequest("null password", adminIssue(root, "{\"ownerUserId\":\"" + target + "\",\"name\":\"ci\","
                    + "\"scopes\":[\"posts:read\"],\"password\":null}"));
        }
        assertEquals("no owner read", targetReads, reads.of(target));
        assertEquals("no password hash", work, counting.verifies.get() + counting.hashes.get());
        for (int i = 0; i < 5; i++) {
            ClientResponse wrong = adminIssue(root, admin(target, "not the password", "posts:read"));
            assertEquals(wrong.text(), 401, wrong.status());
            assertEquals("invalid_credentials", W8.code(wrong));
        }
        ClientResponse sixth = adminIssue(root, admin(target, "not the password", "posts:read"));
        assertEquals("the wrong passwords counted, the 400s did not", 429, sixth.status());
        assertEquals("too_many_attempts", W8.code(sixth));
    }

    // FR-APIKEY-004 AC-5: admin A issues a key for service account S: the stored key's issuedByUserId is A and the issue
    // audit event names A; an ownerUserId equal to the caller is allowed with the same password check
    @Test
    public void FR_APIKEY_004_AC5_theAdminIsTheIssuer() throws Exception {
        start(b -> b);
        String svc = f.auth.users().create("ci-bot", null, u -> u.toBuilder().serviceAccount(true).build()).getId();
        audit.clear();
        ClientResponse r = adminIssue(root, admin(svc, W8.PW, "posts:read"));
        assertEquals(r.text(), 201, r.status());
        Map<String, Object> body = W8.object(r);
        assertTrue(((String) body.get("key")).startsWith("nk_"));
        StoredApiKey row = keys.d.findById((String) body.get("id"));
        assertEquals(svc, row.getOwnerUserId());
        assertEquals(root, row.getIssuedByUserId());
        assertEquals(root, body.get("issuedByUserId"));
        AuditEvent issued = null;
        for (AuditEvent e : audit) {
            if (AuditEvent.API_KEY_ISSUED.equals(e.getType())) {
                issued = e;
            }
        }
        assertNotNull(issued);
        assertEquals(root, issued.getActorId());
        assertEquals(svc, issued.getTargetId());
        ClientResponse self = adminIssue(root, admin(root, W8.PW, "posts:read"));
        assertEquals(self.text(), 201, self.status());
        assertEquals(root, keys.d.findById((String) W8.object(self).get("id")).getOwnerUserId());
        ClientResponse selfWrong = adminIssue(root, admin(root, "not the password", "posts:read"));
        assertEquals("the same password check", 401, selfWrong.status());
    }

    // FR-APIKEY-004 AC-6: a delegated admin deleting the admin's key with DELETE /auth/admin/apikeys/:id -> 403
    // insufficient_scope; an unknown key id -> 404 NOT_FOUND
    @Test
    public void FR_APIKEY_004_AC6_revokingABiggerUsersKey() throws Exception {
        start(b -> b);
        String delegated = f.user("dee", W8.PW, "keyadmin", "reader");
        IssuedApiKey adminKey = f.auth.apiKeys().issue(root, "root", null, "posts:read");
        ClientResponse r = send("DELETE", "/auth/admin/apikeys/" + adminKey.getKey().getId(), bearer(delegated));
        assertEquals(r.text(), 403, r.status());
        assertEquals("insufficient_scope", W8.code(r));
        assertNotNull(keys.d.findById(adminKey.getKey().getId()));
        ClientResponse unknown = send("DELETE", "/auth/admin/apikeys/no-such-key", bearer(delegated));
        assertEquals(404, unknown.status());
        assertEquals("NOT_FOUND", W8.code(unknown));
    }

    // FR-APIKEY-004 AC-7: a caller whose passwordHash is null gets the same 401 as a wrong password on POST
    // /auth/admin/apikeys
    @Test
    public void FR_APIKEY_004_AC7_aNullHashCaller() throws Exception {
        start(b -> b);
        String sso = f.user("ssoadmin", null, "admin");
        ClientResponse wrong = adminIssue(root, admin(u1, "not the password", "posts:read"));
        ClientResponse r = adminIssue(sso, admin(u1, W8.PW, "posts:read"));
        assertEquals(wrong.status(), r.status());
        assertEquals(W8.code(wrong), W8.code(r));
        assertEquals("invalid_credentials", W8.code(r));
        assertTrue(rows(u1).isEmpty());
    }

    // FR-APIKEY-004 AC-8: a delegated admin holding auth-apikeys:write and posts:* issuing a posts:read key for an
    // enabled owner holding * -> 403 insufficient_scope (target protection although the new key scope is covered); no
    // key is inserted and no password hash runs
    @Test
    public void FR_APIKEY_004_AC8_targetProtectionOnTheOwner() throws Exception {
        Wave8AccountTest.Counting counting = new Wave8AccountTest.Counting();
        start(b -> b.passwordHasher(counting));
        String delegated = f.user("dee", W8.PW, "keyadmin", "editor");
        int work = counting.verifies.get() + counting.hashes.get();
        ClientResponse r = adminIssue(delegated, admin(root, W8.PW, "posts:read"));
        assertEquals(r.text(), 403, r.status());
        assertEquals("insufficient_scope", W8.code(r));
        assertTrue(rows(root).isEmpty());
        assertEquals(work, counting.verifies.get() + counting.hashes.get());
        ClientResponse ok = adminIssue(delegated, admin(u1, W8.PW, "posts:read"));
        assertEquals("the same caller for an owner it covers", 201, ok.status());
    }

    // ---------------------------------------------------------------------------------------------- IR-FLOW-002 AC-5

    // IR-FLOW-002 AC-5: with .apiKeys(store) POST /me/apikeys is registered with methodAsync (with login, password,
    // register and setup), so a request waiting for a hash slot holds no worker thread: with every hash slot taken a
    // pending issuance leaves the worker pool free for another request
    // FR-APIKEY-004 AC-9: POST /auth/admin/apikeys is registered with methodAsync and its password check uses the
    // shared hash slot: with all hash slots taken a pending request holds no worker thread
    // REL-TOKEN-001 AC-5: while all slots are busy a non-auth route on the same app still answers without waiting,
    // because the hashing endpoints (login among them) are registered with methodAsync
    @Test(timeout = 30000)
    public void IR_FLOW_002_AC5_FR_APIKEY_004_AC9_theIssuersAreAsync() throws Exception {
        // every exchange of a hashing endpoint whose handle() returned while the slots were still held: the adapter
        // thread was given back before the answer (a synchronous handler returns only once it has answered)
        final AtomicBoolean slotsHeld = new AtomicBoolean(true);
        final AtomicInteger returnedEarly = new AtomicInteger();
        f.engine = a -> new HandleObserving(a, ex -> {
            if (slotsHeld.get() && "POST".equals(ex.methodToken()) && ex.rawPath().startsWith("/auth/")) {
                returnedEarly.incrementAndGet();
            }
        });
        start(b -> b.register(true));
        for (String p : new String[] {"/auth/login", "/auth/password", "/auth/register", "/auth/setup",
            "/auth/me/apikeys", "/auth/admin/apikeys"}) {
            boolean async = false;
            for (Registration r : f.app.registrations()) {
                if (r.getKind() == RegistrationKind.ROUTE && p.equals(r.getPath()) && "POST".equals(String.valueOf(r.getMethod()))) {
                    async = r.getTarget() instanceof AsyncRouteHandler;
                }
            }
            assertTrue(p + " is async", async);
        }
        HashSlots slots = f.auth.hashSlots();
        final CountDownLatch hold = new CountDownLatch(1);
        final CountDownLatch busy = new CountDownLatch(slots.slots());
        for (int i = 0; i < slots.slots(); i++) {
            slots.submit(() -> {
                busy.countDown();
                hold.await();
                return null;
            }, (v, e) -> { });
        }
        assertTrue(busy.await(10, TimeUnit.SECONDS));
        long base = slots.requested();
        List<Future<ClientResponse>> pending = new ArrayList<>();
        // one issuance per caller (a caller's confirmations run one at a time)
        pending.add(f.adapter.submit(ClientRequest.json("POST", "/auth/me/apikeys", body(W8.PW, "posts:read"))
                .header("Authorization", bearer(u1))));
        pending.add(f.adapter.submit(ClientRequest.json("POST", "/auth/admin/apikeys", admin(u1, W8.PW, "posts:read"))
                .header("Authorization", bearer(root))));
        Future<ClientResponse> login = f.adapter.submit(ClientRequest.json("POST", "/auth/login",
                W8.login("u1", W8.PW)));
        awaitRequested(slots, base + 3); // all three wait for a slot now
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (returnedEarly.get() < 3 && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertEquals("the three waiting requests gave their adapter thread back before the answer", 3,
                returnedEarly.get());
        ClientResponse other = f.get("/api/x", "Authorization", bearer(u1));
        assertEquals("a worker is free while the issuances wait for a slot", 200, other.status());
        for (Future<ClientResponse> p : pending) {
            assertFalse(p.isDone());
        }
        assertFalse("the login waits for a slot too", login.isDone());
        slotsHeld.set(false);
        hold.countDown();
        assertEquals(200, login.get(20, TimeUnit.SECONDS).status());
        for (Future<ClientResponse> p : pending) {
            assertEquals(201, p.get(20, TimeUnit.SECONDS).status());
        }
    }

    // ---------------------------------------------------------------------------------------------- review fixes

    // FR-APIKEY-003 AC-2: the per-user limit shared with /auth/password cannot be outrun in parallel: while one
    // confirmation of u1 waits in its hash slot, another PAT attempt and a POST /auth/password of u1 get 429
    // too_many_attempts with Retry-After and run no verify; the hold is released on every answer (401, 409, 429)
    // SEC-FLOW-015 AC-6: the PAT confirmation and POST /auth/password share one per-user counter and one hold
    @Test(timeout = 30000)
    public void FR_APIKEY_003_AC2_SEC_FLOW_015_AC6_oneConfirmationPerUserAtATime() throws Exception {
        Pausing hasher = new Pausing();
        start(b -> b.passwordHasher(hasher).maxKeysPerUser(1));
        Future<ClientResponse> first = f.adapter.submit(ClientRequest.json("POST", "/auth/me/apikeys",
                body(Pausing.PAUSE_PW, "posts:read")).header("Authorization", bearer(u1)));
        assertTrue(hasher.started.await(10, TimeUnit.SECONDS));
        assertEquals(1, f.auth.confirmationHolds().size());
        ClientResponse second = pat(u1, body("another guess", "posts:read"));
        ClientResponse viaPassword = f.post("/auth/password", Wave9PasswordTest.change("a third guess",
                "a brand new passphrase"), "Authorization", bearer(u1));
        for (ClientResponse r : new ClientResponse[] {second, viaPassword}) {
            assertEquals(r.text(), 429, r.status());
            assertEquals("too_many_attempts", W8.code(r));
            assertEquals("1", r.header("Retry-After"));
        }
        assertEquals("another user is not held", 201, pat(root, body(W8.PW, "posts:read")).status());
        hasher.release.countDown();
        ClientResponse r = first.get(10, TimeUnit.SECONDS);
        assertEquals(r.text(), 401, r.status());
        assertReleased();
        assertEquals(201, pat(u1, body(W8.PW, "posts:read")).status());
        assertReleased();
        assertEquals("at the key limit", 409, pat(u1, body(W8.PW, "posts:read")).status());
        assertReleased();
        for (int i = 0; i < 4; i++) {
            assertEquals(401, pat(u1, body("not the password", "posts:read")).status());
        }
        assertEquals("one shared counter: 1 + 4 failures", 429, f.post("/auth/password",
                Wave9PasswordTest.change("not the password", "a brand new passphrase"), "Authorization", bearer(u1))
                .status());
        assertReleased();
    }

    /** The holds are released once the answer settled (it settles right after the response is written). */
    /** Sends {@code n} concurrent {@code GET /api/x} with {@code authorization}; each must answer 401. */
    private void assertAll401(ExecutorService pool, final String authorization, int n) throws Exception {
        List<Future<Integer>> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(pool.submit(() -> f.get("/api/x", "Authorization", authorization).status()));
        }
        for (Future<Integer> x : out) {
            assertEquals(Integer.valueOf(401), x.get(10, TimeUnit.SECONDS));
        }
    }

    /** Waits (bounded) until {@code slots} has been asked for a slot {@code n} times in all. */
    static void awaitRequested(HashSlots slots, long n) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (slots.requested() < n && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(n, slots.requested());
    }

    /** An engine that tells {@code returned} about every exchange whose {@code handle()} call has returned. */
    static final class HandleObserving implements HttpAdapter {
        private final HttpAdapter engine;
        private final Consumer<HttpExchange> returned;

        HandleObserving(HttpAdapter engine, Consumer<HttpExchange> returned) {
            this.engine = engine;
            this.returned = returned;
        }

        @Override
        public void bind(String host, int port, ExchangeHandler handler) throws IOException {
            engine.bind(host, port, ex -> {
                try {
                    handler.handle(ex);
                } finally {
                    returned.accept(ex);
                }
            });
        }

        @Override
        public int boundPort() {
            return engine.boundPort();
        }

        @Override
        public void stop(long timeout, TimeUnit unit) {
            engine.stop(timeout, unit);
        }

        @Override
        public void release() {
            engine.release();
        }
    }

    private void assertReleased() throws InterruptedException {
        for (int i = 0; i < 500 && f.auth.confirmationHolds().size() > 0; i++) {
            Thread.sleep(10);
        }
        assertEquals(0, f.auth.confirmationHolds().size());
    }

    // FR-APIKEY-004 AC-6 (with FR-AUTH-009 AC-2): DELETE /auth/me/apikeys/:id and DELETE /auth/admin/apikeys/:id each
    // emit api_key_revoked naming the actor, the owner as target and the deleted key as revokedApiKeyId; an API-key
    // actor's revoke also carries the actor's own key id
    @Test
    public void FR_APIKEY_004_AC6_revokesThroughTheEndpointsAreAudited() throws Exception {
        start(b -> b.allowAdminScopesOnApiKeys(true));
        IssuedApiKey own = f.auth.apiKeys().issue(u1, "own", null, "posts:read");
        IssuedApiKey other = f.auth.apiKeys().issue(u1, "other", null, "posts:read");
        IssuedApiKey third = f.auth.apiKeys().issue(u1, "third", null, "posts:read");
        IssuedApiKey actorKey = f.auth.apiKeys().issue(root, "ops", null, "auth-apikeys:*", "posts:read");
        audit.clear();
        assertEquals(204, send("DELETE", "/auth/me/apikeys/" + own.getKey().getId(), bearer(u1)).status());
        assertEquals(204, send("DELETE", "/auth/admin/apikeys/" + other.getKey().getId(), bearer(root)).status());
        assertEquals(204, send("DELETE", "/auth/admin/apikeys/" + third.getKey().getId(),
                "ApiKey " + actorKey.getPlainKey()).status());
        List<AuditEvent> revoked = new ArrayList<>();
        for (AuditEvent e : audit) {
            if (AuditEvent.API_KEY_REVOKED.equals(e.getType())) {
                revoked.add(e);
            }
        }
        assertEquals(audit.toString(), 3, revoked.size());
        assertEquals(u1, revoked.get(0).getActorId());
        assertEquals(u1, revoked.get(0).getTargetId());
        assertEquals(own.getKey().getId(), revoked.get(0).getDetails().get("revokedApiKeyId"));
        assertEquals(root, revoked.get(1).getActorId());
        assertEquals(u1, revoked.get(1).getTargetId());
        assertEquals(other.getKey().getId(), revoked.get(1).getDetails().get("revokedApiKeyId"));
        assertEquals(u1, revoked.get(2).getTargetId());
        assertEquals(third.getKey().getId(), revoked.get(2).getDetails().get("revokedApiKeyId"));
        assertEquals(actorKey.getKey().getId(), revoked.get(2).getDetails().get("apiKeyId"));
    }

    // FR-AUTH-009 AC-2: the app calls carry the key id too, and a revoke names the owner as target; a key with no
    // recorded issuer (auth.apiKeys().issue) has no issuedByUserId on its issue event or on any revoke event
    @Test
    public void FR_AUTH_009_AC2_issueAndRevokeEventsCarryTheKeyId() throws Exception {
        start(b -> b);
        IssuedApiKey viaCall = f.auth.apiKeys().issue(u1, "call", null, "posts:read");
        IssuedApiKey viaEndpoint = f.auth.apiKeys().issue(u1, "endpoint", null, "posts:read");
        assertTrue(f.auth.apiKeys().revoke(viaCall.getKey().getId()));
        assertEquals(204, send("DELETE", "/auth/me/apikeys/" + viaEndpoint.getKey().getId(), bearer(u1)).status());
        List<AuditEvent> issued = new ArrayList<>();
        List<AuditEvent> revoked = new ArrayList<>();
        for (AuditEvent e : audit) {
            if (AuditEvent.API_KEY_ISSUED.equals(e.getType())) {
                issued.add(e);
            } else if (AuditEvent.API_KEY_REVOKED.equals(e.getType())) {
                revoked.add(e);
            }
        }
        assertEquals(audit.toString(), 2, issued.size());
        assertEquals(viaCall.getKey().getId(), issued.get(0).getDetails().get("apiKeyId"));
        assertFalse(issued.get(0).getDetails().containsKey("issuedByUserId"));
        assertEquals(audit.toString(), 2, revoked.size());
        assertEquals(viaCall.getKey().getId(), revoked.get(0).getDetails().get("revokedApiKeyId"));
        assertEquals(viaEndpoint.getKey().getId(), revoked.get(1).getDetails().get("revokedApiKeyId"));
        for (AuditEvent e : revoked) {
            assertEquals(u1, e.getTargetId());
            assertFalse(e.getDetails().containsKey("issuedByUserId"));
        }
    }

    // FR-AUTH-009 AC-2, AC-3: auth.apiKeys() takes no request, so its event has no request context even inside a route
    @Test
    public void FR_AUTH_009_AC2_AC3_facadeEventInsideARouteHasNoRequestContext() throws Exception {
        f.attach = (a, app) -> {
            a.install(app);
            app.get("/facade/:id", (req, res) -> res.send(String.valueOf(a.apiKeys().revoke(req.param("id")))));
        };
        start(b -> b);
        IssuedApiKey actor = f.auth.apiKeys().issue(u1, "actor", null, "posts:read");
        IssuedApiKey victim = f.auth.apiKeys().issue(u1, "victim", null, "posts:read");
        audit.clear();
        ClientResponse r = f.get("/facade/" + victim.getKey().getId(), "Authorization", "ApiKey " + actor.getPlainKey());
        assertEquals(r.text(), 200, r.status());
        assertEquals("true", r.text());
        List<AuditEvent> revoked = new ArrayList<>();
        for (AuditEvent e : audit) {
            if (AuditEvent.API_KEY_REVOKED.equals(e.getType())) {
                revoked.add(e);
            }
        }
        assertEquals(audit.toString(), 1, revoked.size());
        AuditEvent event = revoked.get(0);
        assertEquals(victim.getKey().getId(), event.getDetails().get("revokedApiKeyId"));
        assertEquals(u1, event.getTargetId());
        assertEquals(null, event.getActorId());
        assertEquals(null, event.getRequestId());
        assertEquals(null, event.getSourceIp());
        assertFalse(event.getDetails().containsKey("apiKeyId"));
    }

    // FR-AUTH-009 AC-2 (00 R47 (3), (6)): a key with a recorded issuer carries it on the revoke event too, on all three
    // revoke paths: DELETE /auth/me/apikeys/:id, DELETE /auth/admin/apikeys/:id and auth.apiKeys().revoke
    @Test
    public void FR_AUTH_009_AC2_revokeEventsCarryTheRecordedIssuer() throws Exception {
        start(b -> b);
        String viaMe = (String) W8.object(pat(u1, body(W8.PW, "posts:read"))).get("id");
        String viaAdmin = (String) W8.object(pat(u1, body(W8.PW, "posts:read"))).get("id");
        String viaCall = (String) W8.object(pat(u1, body(W8.PW, "posts:read"))).get("id");
        audit.clear();
        assertEquals(204, send("DELETE", "/auth/me/apikeys/" + viaMe, bearer(u1)).status());
        assertEquals(204, send("DELETE", "/auth/admin/apikeys/" + viaAdmin, bearer(root)).status());
        assertTrue(f.auth.apiKeys().revoke(viaCall));
        List<AuditEvent> revoked = new ArrayList<>();
        for (AuditEvent e : audit) {
            if (AuditEvent.API_KEY_REVOKED.equals(e.getType())) {
                revoked.add(e);
            }
        }
        assertEquals(audit.toString(), 3, revoked.size());
        String[] ids = {viaMe, viaAdmin, viaCall};
        for (int i = 0; i < ids.length; i++) {
            assertEquals(ids[i], revoked.get(i).getDetails().get("revokedApiKeyId"));
            assertEquals(ids[i], u1, revoked.get(i).getTargetId());
            assertEquals(ids[i], u1, revoked.get(i).getDetails().get("issuedByUserId"));
        }
    }
}
