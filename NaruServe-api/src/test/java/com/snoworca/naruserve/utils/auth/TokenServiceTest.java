package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.utils.auth.internal.AccessTokens;
import com.snoworca.naruserve.utils.auth.internal.Base64Url;
import com.snoworca.naruserve.utils.auth.internal.HmacSigner;
import com.snoworca.naruserve.utils.auth.internal.MiniJson;
import com.snoworca.naruserve.utils.auth.internal.RefreshTokens;
import com.snoworca.naruserve.utils.auth.internal.TokenKeys;
import com.snoworca.naruserve.utils.auth.jwt.Jwt;
import com.snoworca.naruserve.utils.auth.jwt.JwtClaims;
import com.snoworca.naruserve.utils.auth.jwt.JwtException;
import com.snoworca.naruserve.utils.auth.jwt.JwtKeys;
import com.snoworca.naruserve.utils.auth.spi.RefreshRecord;
import com.snoworca.naruserve.utils.auth.user.AuthUser;
import com.snoworca.naruserve.utils.auth.user.ForwardingUserStore;
import com.snoworca.naruserve.utils.auth.user.InMemoryUserStore;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.After;
import org.junit.Test;

/**
 * The token format with typ separation and purpose keys (SEC-TOKEN-005), TokenService issue and rotation
 * (FR-TOKEN-001), the self-service password change through {@code POST /auth/password} (REL-IAM-002) and what one
 * ver++ revokes (SEC-IAM-003), with real HS256 tokens over the auth key set.
 */
public class TokenServiceTest {

    private static final long T0 = 1_761_700_900_000L;
    private final AtomicLong clock = new AtomicLong(T0);
    private final InMemoryUserStore users = new InMemoryUserStore();
    private final Saves refresh = new Saves();

    /** The in-memory refresh store, counting saved records. */
    private static final class Saves implements com.snoworca.naruserve.utils.auth.spi.RefreshTokenStore {
        final InMemoryRefreshTokenStore d = new InMemoryRefreshTokenStore();
        int saved;

        @Override
        public synchronized void save(RefreshRecord record) {
            saved++;
            d.save(record);
        }

        @Override
        public RefreshRecord find(String tokenId) {
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

        synchronized int size() {
            return saved;
        }
    }

    /** The apps {@link #serve} started, shut down after each test. */
    private final List<RestApp> apps = new ArrayList<>();

    @After
    public void stopApps() {
        for (RestApp a : apps) {
            a.shutdown(5, TimeUnit.SECONDS);
        }
    }

    /** Installs {@code auth} into a new listening app over an in-memory adapter; returns the adapter. */
    private InMemoryHttpAdapter serve(Auth auth) {
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter("127.0.0.1", true);
        RestApp app = RestApp.builder().httpAdapter(adapter).jsonCodec(new AdminFixture.MiniJsonCodec())
                .logger(new RecordingLogger()).build();
        apps.add(app);
        auth.install(app);
        app.onError(auth.errors());
        app.listen(0);
        return adapter;
    }

    /** {@code POST /auth/password} with {@code accessToken} as the Bearer credential. */
    private static ClientResponse changePassword(InMemoryHttpAdapter adapter, String accessToken, String current,
            String next) {
        return adapter.send(ClientRequest.json("POST", "/auth/password", "{\"currentPassword\":\"" + current
                + "\",\"newPassword\":\"" + next + "\"}").header("Authorization", "Bearer " + accessToken));
    }

    /** The access token of a 200 password change answer (header transport: in the JSON body). */
    private static String accessToken(ClientResponse r) {
        assertEquals(r.text(), 200, r.status());
        Object o = MiniJson.parse(r.text(), MiniJson.Limits.file());
        assertTrue(r.text(), o instanceof Map);
        Object t = ((Map<?, ?>) o).get("accessToken");
        assertTrue(r.text(), t instanceof String);
        return (String) t;
    }

    private Auth.Builder builder() {
        return Auth.builder(AuthTestSupport.SECRET).hashIterations(1000).logger(new RecordingLogger()).users(users)
                .refreshTokens(refresh).timeSource(clock::get);
    }

    private AuthUser user(String name) throws Exception {
        return users.create(AuthUser.builder().username(name).passwordHash(new com.snoworca.naruserve.utils.auth
                .internal.Passwords(1000).hash(("pw-" + name).toCharArray())).roles("reader").build());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> part(String token, int i) {
        String p = token.split("\\.")[i];
        return (Map<String, Object>) MiniJson.parse(new String(Base64Url.decode(p), StandardCharsets.UTF_8),
                MiniJson.Limits.claims());
    }

    // SEC-TOKEN-005 AC-1: the access header has exactly alg HS256, typ at+jwt and kid; the payload exactly sub, ver,
    // fam, iat, exp and jti, no scope or roles
    @Test
    public void SEC_TOKEN_005_AC1_accessTokenShape() throws Exception {
        Auth auth = builder().build();
        TokenPair p = auth.tokens().issue(user("alice"));
        Map<String, Object> h = part(p.getAccessToken(), 0);
        assertEquals(new HashSet<>(Arrays.asList("alg", "typ", "kid")), h.keySet());
        assertEquals("HS256", h.get("alg"));
        assertEquals("at+jwt", h.get("typ"));
        assertEquals(auth.keySet().getSigningKid(), h.get("kid"));
        assertEquals(new HashSet<>(Arrays.asList("sub", "ver", "fam", "iat", "exp", "jti")),
                part(p.getAccessToken(), 1).keySet());
    }

    // SEC-TOKEN-005 AC-2: the refresh header has typ rt+jwt and the payload sub, ver, fam, jti, iat and exp
    @Test
    public void SEC_TOKEN_005_AC2_refreshTokenShape() throws Exception {
        TokenPair p = builder().build().tokens().issue(user("alice"));
        assertEquals("rt+jwt", part(p.getRefreshToken(), 0).get("typ"));
        assertEquals(new HashSet<>(Arrays.asList("sub", "ver", "fam", "iat", "exp", "jti")),
                part(p.getRefreshToken(), 1).keySet());
    }

    // SEC-TOKEN-005 AC-3: a refresh token is refused as an access token and an access token as a refresh token, by
    // typ and by the different derived keys
    @Test
    public void SEC_TOKEN_005_AC3_noCrossPurposeVerification() throws Exception {
        Auth auth = builder().build();
        AuthUser alice = user("alice");
        TokenPair p = auth.tokens().issue(alice);
        try {
            auth.tokens().verifyAccess(p.getRefreshToken());
            fail("a refresh token verified as an access token");
        } catch (AuthRejection e) {
            assertEquals("invalid_token", e.getCode());
        }
        try {
            auth.tokens().refresh(p.getAccessToken());
            fail("an access token was accepted as a refresh token");
        } catch (AuthRejection e) {
            assertEquals("invalid_refresh_token", e.getCode());
        }
        JwtKeys accessKeys = TokenKeys.purpose(auth.keySet(), TokenKeys.ACCESS);
        JwtKeys refreshKeys = TokenKeys.purpose(auth.keySet(), TokenKeys.REFRESH);
        try { // the type check alone off: the keys still differ
            Jwt.builder(accessKeys).timeSource(clock::get).build().verify(p.getRefreshToken());
            fail("the refresh token verified with the access key");
        } catch (JwtException e) {
            assertEquals(JwtException.Reason.BAD_SIGNATURE, e.getReason());
        }
        try {
            Jwt.builder(refreshKeys).timeSource(clock::get).build().verify(p.getAccessToken());
            fail("the access token verified with the refresh key");
        } catch (JwtException e) {
            assertEquals(JwtException.Reason.BAD_SIGNATURE, e.getReason());
        }
        // over HTTP the refresh token is no access credential
        com.snoworca.naruserve.testing.InMemoryHttpAdapter adapter =
                new com.snoworca.naruserve.testing.InMemoryHttpAdapter("203.0.113.9", true);
        com.snoworca.naruserve.testing.TestApp t = com.snoworca.naruserve.testing.TestApp.create(adapter);
        auth.install(t.app());
        t.app().get("/api/x", (req, res) -> res.send("x"));
        assertEquals(200, t.send("GET", "/api/x", "Authorization", "Bearer " + p.getAccessToken()).status());
        ClientResponse r = t.send("GET", "/api/x", "Authorization", "Bearer " + p.getRefreshToken());
        assertEquals(401, r.status());
        assertEquals("invalid_token", AuthTestSupport.code(r));
    }

    // SEC-TOKEN-005 AC-4: a token re-signed with the access-derived key but typ rt+jwt fails refresh verification
    @Test
    public void SEC_TOKEN_005_AC4_refreshKeyDiffersFromAccessKey() throws Exception {
        Auth auth = builder().build();
        AuthUser alice = user("alice");
        TokenPair p = auth.tokens().issue(alice);
        JwtClaims c = new RefreshTokens(auth.keySet(), clock::get).verify(p.getRefreshToken());
        String forged = Jwt.builder(TokenKeys.purpose(auth.keySet(), TokenKeys.ACCESS)).timeSource(clock::get)
                .build().sign(JwtClaims.builder().type(RefreshTokens.TYPE).subject(c.getSubject())
                        .issuedAt(clock.get()).expiresAt(clock.get() + 60_000L).tokenId(c.getTokenId())
                        .claim("ver", 0L).claim("fam", c.getString("fam")).build());
        assertEquals("rt+jwt", part(forged, 0).get("typ"));
        try {
            new RefreshTokens(auth.keySet(), clock::get).verify(forged);
            fail("a token signed with the access key verified as a refresh token");
        } catch (JwtException e) {
            assertEquals(JwtException.Reason.BAD_SIGNATURE, e.getReason());
        }
        try {
            auth.tokens().refresh(forged);
            fail("refresh accepted it");
        } catch (AuthRejection e) {
            assertEquals("invalid_refresh_token", e.getCode());
        }
    }

    // SEC-TOKEN-005 AC-3 (the typ half): a token signed with the right key but the other purpose's typ is refused
    // both ways
    @Test
    public void SEC_TOKEN_005_AC3_typAloneSeparatesPurposes() throws Exception {
        Auth auth = builder().build();
        AuthUser alice = user("alice");
        long now = clock.get();
        String accessKeyRefreshTyp = Jwt.builder(TokenKeys.purpose(auth.keySet(), TokenKeys.ACCESS))
                .timeSource(clock::get).build().sign(JwtClaims.builder().type(RefreshTokens.TYPE)
                        .subject(alice.getId()).issuedAt(now).expiresAt(now + 60_000L).tokenId("j1").claim("ver", 0L)
                        .claim("fam", "f1").build());
        try {
            auth.tokens().verifyAccess(accessKeyRefreshTyp);
            fail("a typ rt+jwt token verified as an access token");
        } catch (AuthRejection e) {
            assertEquals("invalid_token", e.getCode());
        }
        String refreshKeyAccessTyp = Jwt.builder(TokenKeys.purpose(auth.keySet(), TokenKeys.REFRESH))
                .timeSource(clock::get).build().sign(JwtClaims.builder().type(AccessTokens.TYPE)
                        .subject(alice.getId()).issuedAt(now).expiresAt(now + 60_000L).tokenId("j2").claim("ver", 0L)
                        .claim("fam", "f2").build());
        try {
            new RefreshTokens(auth.keySet(), clock::get).verify(refreshKeyAccessTyp);
            fail("a typ at+jwt token verified as a refresh token");
        } catch (JwtException e) {
            assertEquals(JwtException.Reason.WRONG_TYPE, e.getReason());
        }
        try {
            auth.tokens().refresh(refreshKeyAccessTyp);
            fail("refresh accepted a typ at+jwt token");
        } catch (AuthRejection e) {
            assertEquals("invalid_refresh_token", e.getCode());
        }
    }

    // SEC-TOKEN-005 AC-5: the device key is HMAC(secret, "naru-device") and the fingerprint key HMAC(secret,
    // "naru-device-fp") for the header kid, both different from the access and refresh keys
    @Test
    public void SEC_TOKEN_005_AC5_deviceKeysDerivedPerKid() throws Exception {
        byte[] s1 = "first-secret-0123456789-abcdefghijklmnop".getBytes(StandardCharsets.US_ASCII);
        byte[] s2 = "second-secret-0123456789-abcdefghijklmno".getBytes(StandardCharsets.US_ASCII);
        JwtKeys raw = JwtKeys.builder().signingKey("k2", s2).verificationKey("k1", s1).build();
        for (String kid : new String[] {"k1", "k2"}) {
            byte[] secret = kid.equals("k1") ? s1 : s2;
            byte[] device = TokenKeys.keyFor(raw, kid, TokenKeys.DEVICE);
            byte[] fp = TokenKeys.keyFor(raw, kid, TokenKeys.DEVICE_FINGERPRINT);
            assertArrayEquals(new HmacSigner(secret).sign("naru-device".getBytes(StandardCharsets.US_ASCII)), device);
            assertArrayEquals(new HmacSigner(secret).sign("naru-device-fp".getBytes(StandardCharsets.US_ASCII)), fp);
            byte[] access = TokenKeys.purpose(raw, TokenKeys.ACCESS).verificationKey(kid);
            byte[] refreshKey = TokenKeys.purpose(raw, TokenKeys.REFRESH).verificationKey(kid);
            Set<String> distinct = new HashSet<>();
            for (byte[] k : new byte[][] {device, fp, access, refreshKey, secret}) {
                distinct.add(Base64Url.encode(k));
            }
            assertEquals(5, distinct.size());
        }
        assertNull(TokenKeys.keyFor(raw, "k9", TokenKeys.DEVICE));
    }

    // SEC-TOKEN-005 AC-6: the access token's fam is the family of the refresh token issued with it; 1,000 jti values
    // are pairwise distinct
    @Test
    public void SEC_TOKEN_005_AC6_famSharedAndJtiDistinct() throws Exception {
        Auth auth = builder().build();
        AuthUser alice = user("alice");
        Set<String> jtis = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            TokenPair p = auth.tokens().issue(alice);
            Map<String, Object> a = part(p.getAccessToken(), 1);
            Map<String, Object> r = part(p.getRefreshToken(), 1);
            assertEquals(a.get("fam"), r.get("fam"));
            RefreshRecord rec = refresh.find((String) r.get("jti"));
            assertNotNull(rec);
            assertEquals(a.get("fam"), rec.getFamilyId());
            jtis.add((String) a.get("jti"));
            jtis.add((String) r.get("jti"));
        }
        assertEquals(1000, jtis.size());
    }

    // FR-TOKEN-001 AC-1: issue(user) shares one new fam between both tokens and saves the refresh record (jti, family,
    // user id, expiry)
    @Test
    public void FR_TOKEN_001_AC1_issueSavesTheRecord() throws Exception {
        Auth auth = builder().build();
        AuthUser alice = user("alice");
        TokenPair p = auth.tokens().issue(alice);
        Map<String, Object> r = part(p.getRefreshToken(), 1);
        RefreshRecord rec = refresh.find((String) r.get("jti"));
        assertNotNull(rec);
        assertEquals(r.get("fam"), rec.getFamilyId());
        assertEquals(part(p.getAccessToken(), 1).get("fam"), rec.getFamilyId());
        assertEquals(alice.getId(), rec.getUserId());
        assertEquals(((Number) r.get("exp")).longValue() * 1000L, rec.getExpiresAt());
        assertNull(rec.getUsedAt());
        assertFalse(rec.getTokenHash().contains(p.getRefreshToken()));
    }

    // FR-TOKEN-001 AC-2: the pair exposes the access token, its lifetime in seconds, the refresh token, its lifetime
    // and the type Bearer
    @Test
    public void FR_TOKEN_001_AC2_pairShape() throws Exception {
        TokenPair p = builder().accessTtl(900_000L).refreshTtl(86_400_000L).build().tokens().issue(user("alice"));
        assertNotNull(p.getAccessToken());
        assertNotNull(p.getRefreshToken());
        assertEquals(900L, p.getExpiresIn());
        assertEquals(86_400L, p.getRefreshExpiresIn());
        assertEquals("Bearer", p.getTokenType());
        assertFalse(p.toString().contains(p.getAccessToken()));
    }

    // FR-TOKEN-001 AC-3: refresh(rt1) of family F returns tokens of family F, marks rt1 used and saves the new jti in F
    @Test
    public void FR_TOKEN_001_AC3_rotationKeepsTheFamily() throws Exception {
        Auth auth = builder().build();
        TokenPair p1 = auth.tokens().issue(user("alice"));
        String jti1 = (String) part(p1.getRefreshToken(), 1).get("jti");
        String fam = (String) part(p1.getRefreshToken(), 1).get("fam");
        clock.addAndGet(1000);
        TokenPair p2 = auth.tokens().refresh(p1.getRefreshToken());
        assertEquals(fam, part(p2.getAccessToken(), 1).get("fam"));
        assertEquals(fam, part(p2.getRefreshToken(), 1).get("fam"));
        assertNotNull(refresh.find(jti1).getUsedAt());
        RefreshRecord next = refresh.find((String) part(p2.getRefreshToken(), 1).get("jti"));
        assertNotNull(next);
        assertEquals(fam, next.getFamilyId());
        // a second use of rt1 is reuse: refused and the family is revoked
        try {
            auth.tokens().refresh(p1.getRefreshToken());
            fail("a used refresh token was accepted");
        } catch (AuthRejection e) {
            assertEquals("invalid_refresh_token", e.getCode());
        }
        assertTrue(next.getFamilyId().equals(fam) && refresh.find(next.getTokenId()).isRevoked());
    }

    // FR-TOKEN-001 AC-4 (and the access and refresh rows of SEC-IAM-003): after ver++, after a disable or after a delete the
    // refresh fails as invalid_refresh_token and issues nothing; the old access token is 401 too
    @Test
    public void FR_TOKEN_001_AC4_refreshChecksTheUser() throws Exception {
        Auth auth = builder().build();
        com.snoworca.naruserve.testing.InMemoryHttpAdapter adapter =
                new com.snoworca.naruserve.testing.InMemoryHttpAdapter("203.0.113.9", true);
        com.snoworca.naruserve.testing.TestApp t = com.snoworca.naruserve.testing.TestApp.create(adapter);
        auth.install(t.app());
        t.app().get("/api/x", (req, res) -> res.send("x"));
        for (int mode = 0; mode < 3; mode++) {
            AuthUser u = user("user" + mode);
            TokenPair p = auth.tokens().issue(u);
            if (mode == 0) {
                users.bumpTokenVersion(u.getId());
            } else if (mode == 1) {
                users.setEnabled(u.getId(), false);
            } else {
                users.delete(u.getId());
            }
            int before = refresh.size();
            try {
                auth.tokens().refresh(p.getRefreshToken());
                fail("mode " + mode + " issued tokens");
            } catch (AuthRejection e) {
                assertEquals(401, e.getStatus());
                assertEquals("invalid_refresh_token", e.getCode());
            }
            assertEquals(before, refresh.size());
            auth.userRevoked(u.getId()); // only for the access check below: refresh reads the store itself
            assertEquals(401, t.send("GET", "/api/x", "Authorization", "Bearer " + p.getAccessToken()).status());
        }
    }

    // FR-TOKEN-001 AC-5: two issues (two logins) get different fam values; a password change returns a pair in a
    // family other than the one the request came from
    @Test
    public void FR_TOKEN_001_AC5_newFamilies() throws Exception {
        Auth auth = builder().build();
        AuthUser alice = user("alice");
        TokenPair a = auth.tokens().issue(alice);
        TokenPair b = auth.tokens().issue(alice);
        assertFalse(part(a.getAccessToken(), 1).get("fam").equals(part(b.getAccessToken(), 1).get("fam")));
        String changed = accessToken(changePassword(serve(auth), a.getAccessToken(), "pw-alice", "a-new-password-1"));
        assertFalse(part(a.getAccessToken(), 1).get("fam").equals(part(changed, 1).get("fam")));
        assertFalse(part(b.getAccessToken(), 1).get("fam").equals(part(changed, 1).get("fam")));
        assertEquals(1L, ((Number) part(changed, 1).get("ver")).longValue());
    }

    // FR-TOKEN-001 AC-6: explicit 15 minutes / 14 days give exp - iat of 900 s and 1,209,600 s; unset, the lifetimes
    // are the profile's (STANDARD 15 min / 1 day, BASELINE 15 min / 14 days, STRICT 5 min / 12 h)
    @Test
    public void FR_TOKEN_001_AC6_lifetimes() throws Exception {
        AuthUser alice = user("alice");
        TokenPair p = builder().accessTtl(15 * 60_000L).refreshTtl(14 * 86_400_000L).build().tokens().issue(alice);
        assertEquals(900L, span(p.getAccessToken()));
        assertEquals(1_209_600L, span(p.getRefreshToken()));
        long[][] expected = {{900L, 86_400L}, {900L, 1_209_600L}, {300L, 43_200L}};
        AuthProfile[] profiles = {AuthProfile.STANDARD, AuthProfile.BASELINE, AuthProfile.STRICT};
        for (int i = 0; i < profiles.length; i++) {
            TokenPair q = builder().profile(profiles[i]).build().tokens().issue(alice);
            assertEquals(profiles[i].name(), expected[i][0], span(q.getAccessToken()));
            assertEquals(profiles[i].name(), expected[i][1], span(q.getRefreshToken()));
        }
        assertEquals(900L, span(builder().build().tokens().issue(alice).getAccessToken()));
    }

    private static long span(String token) {
        Map<String, Object> c = part(token, 1);
        return ((Number) c.get("exp")).longValue() - ((Number) c.get("iat")).longValue();
    }

    // FR-TOKEN-001 AC-7: a refresh token whose family is not in this node's in-memory store is invalid
    @Test
    public void FR_TOKEN_001_AC7_unknownFamilyIsInvalid() throws Exception {
        AuthUser alice = user("alice");
        TokenPair p = builder().build().tokens().issue(alice);
        Auth other = Auth.builder(AuthTestSupport.SECRET).hashIterations(1000).logger(new RecordingLogger())
                .users(users).refreshTokens(new InMemoryRefreshTokenStore()).timeSource(clock::get).build();
        try {
            other.tokens().refresh(p.getRefreshToken());
            fail("a token of another node's store was accepted");
        } catch (AuthRejection e) {
            assertEquals("invalid_refresh_token", e.getCode());
        }
    }

    // REL-IAM-002 AC-1: changePassword on a null hash with expectedOldHash null stores the hash and ver + 1
    @Test
    public void REL_IAM_002_AC1_nullHashReset() throws Exception {
        AuthUser sso = users.create(AuthUser.builder().username("sso").build());
        assertTrue(users.changePassword(sso.getId(), null, 0L, "pbkdf2-sha256$1000$abc$def"));
        assertEquals("pbkdf2-sha256$1000$abc$def", users.findById(sso.getId()).getPasswordHash());
        assertEquals(1L, users.findById(sso.getId()).getTokenVersion());
        assertFalse(users.changePassword(sso.getId(), "pbkdf2-sha256$1000$abc$def", 0L, "x$y"));
    }

    /** A store whose first changePassword loses to a rehash that lands just before it. */
    private static final class RacingRehash extends ForwardingUserStore {
        final InMemoryUserStore base;
        final String rehash;
        int changeCalls;

        RacingRehash(InMemoryUserStore base, String rehash) {
            super(base);
            this.base = base;
            this.rehash = rehash;
        }

        @Override
        public boolean changePassword(String id, String expectedOldHash, long expectedVer, String newHash)
                throws Exception {
            if (changeCalls++ == 0) {
                base.updatePasswordHash(id, expectedOldHash, rehash); // the rehash wins the race
            }
            return super.changePassword(id, expectedOldHash, expectedVer, newHash);
        }
    }

    // REL-IAM-002 AC-3 / AC-5: a self-service change racing a rehash: the first write fails, the re-read hash still
    // verifies the current password, one retry succeeds; ver is +1 exactly and the hash is the new one
    @Test
    public void REL_IAM_002_AC3_AC5_retryAfterRehash() throws Exception {
        com.snoworca.naruserve.utils.auth.internal.Passwords pw =
                new com.snoworca.naruserve.utils.auth.internal.Passwords(1000);
        AuthUser alice = users.create(AuthUser.builder().username("alice").passwordHash(pw.hash("cur-pw".toCharArray()))
                .build());
        RacingRehash store = new RacingRehash(users, pw.hash("cur-pw".toCharArray()));
        Auth auth = Auth.builder(AuthTestSupport.SECRET).hashIterations(1000).logger(new RecordingLogger())
                .users(store).refreshTokens(refresh).timeSource(clock::get).build();
        ClientResponse r = changePassword(serve(auth), auth.tokens().issue(alice).getAccessToken(), "cur-pw",
                "next-password-1");
        assertNotNull(accessToken(r));
        assertEquals(2, store.changeCalls);
        AuthUser after = users.findById(alice.getId());
        assertEquals(1L, after.getTokenVersion());
        assertTrue(pw.verify("next-password-1".toCharArray(), after.getPasswordHash()));
    }

    // REL-IAM-002 AC-4 (self-service half) / AC-6: racing an admin reset, the re-read hash does not verify the
    // current password: 409 write_conflict without a second write, and no token pair
    @Test
    public void REL_IAM_002_AC4_AC6_selfServiceAgainstAdminReset() throws Exception {
        final com.snoworca.naruserve.utils.auth.internal.Passwords pw =
                new com.snoworca.naruserve.utils.auth.internal.Passwords(1000);
        final AuthUser alice = users.create(AuthUser.builder().username("alice")
                .passwordHash(pw.hash("cur-pw".toCharArray())).build());
        final int[] calls = new int[1];
        final String adminHash = pw.hash("admin-set-pw".toCharArray());
        ForwardingUserStore store = new ForwardingUserStore(users) {
            @Override
            public boolean changePassword(String id, String expectedOldHash, long expectedVer, String newHash)
                    throws Exception {
                calls[0]++;
                AuthUser cur = users.findById(id);
                // the admin reset landed first: a new hash and ver + 1, as changePassword writes them
                users.update(cur.toBuilder().passwordHash(adminHash).tokenVersion(cur.getTokenVersion() + 1).build());
                return false;
            }
        };
        Auth auth = Auth.builder(AuthTestSupport.SECRET).hashIterations(1000).logger(new RecordingLogger())
                .users(store).refreshTokens(refresh).timeSource(clock::get).build();
        InMemoryHttpAdapter adapter = serve(auth);
        String token = auth.tokens().issue(alice).getAccessToken();
        int records = refresh.size();
        ClientResponse lost = changePassword(adapter, token, "cur-pw", "next-pw-1");
        assertEquals("a lost race returned tokens: " + lost.text(), 409, lost.status());
        assertEquals("write_conflict", AuthTestSupport.code(lost));
        assertFalse(lost.text(), lost.text().contains("accessToken"));
        assertEquals(1, calls[0]);
        assertEquals(records, refresh.size());
        // a disabled or bumped session is 401 before any write: the bumped one (ver 0 after the reset's ver + 1) and
        // a disabled one at the current ver, with the password the reset set
        ClientResponse bumped = changePassword(adapter, token, "admin-set-pw", "x-pw-12345");
        assertEquals(bumped.text(), 401, bumped.status());
        String current = auth.tokens().issue(users.findById(alice.getId())).getAccessToken();
        users.setEnabled(alice.getId(), false);
        ClientResponse disabled = changePassword(adapter, current, "admin-set-pw", "x-pw-12345");
        assertEquals("a disabled user changed its password: " + disabled.text(), 401, disabled.status());
        assertEquals(1, calls[0]);
    }

    /** Distinct values of a set, for readable failures. */
    static Set<String> set(String... v) {
        return new LinkedHashSet<>(Arrays.asList(v));
    }
}
