package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.testing.TestApp;
import com.snoworca.naruserve.utils.auth.internal.Hs256TokenCodec;
import com.snoworca.naruserve.utils.auth.jwt.Jwt;
import com.snoworca.naruserve.utils.auth.jwt.JwtClaims;
import com.snoworca.naruserve.utils.auth.jwt.JwtKeys;
import com.snoworca.naruserve.utils.auth.spi.RefreshRecord;
import com.snoworca.naruserve.utils.auth.spi.RefreshTokenStore;
import com.snoworca.naruserve.utils.auth.spi.TokenCodec;
import com.snoworca.naruserve.utils.auth.user.AuthUser;
import com.snoworca.naruserve.utils.auth.user.InMemoryUserStore;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

/**
 * Wave 7 token layer: the TokenCodec seam (IR-TOKEN-002), per-family refresh reuse detection (SEC-TOKEN-006) and the
 * forced short lifetimes of allowInsecureTransport (SEC-FLOW-002), with real tokens through auth.tokens().
 */
public class Wave7TokenTest {

    private static final long T0 = 1_761_700_900_000L;
    private final AtomicLong clock = new AtomicLong(T0);
    private final InMemoryUserStore users = new InMemoryUserStore();
    private final CountingStore store = new CountingStore();
    private final List<String> reused = new ArrayList<>();

    /** The in-memory refresh store, counting markUsed and revokeFamily, optionally failing revokeFamily. */
    static final class CountingStore implements RefreshTokenStore {
        final InMemoryRefreshTokenStore d = new InMemoryRefreshTokenStore();
        final AtomicInteger marks = new AtomicInteger();
        final List<String> revoked = new ArrayList<>();
        final List<RefreshRecord> saved = new ArrayList<>();
        volatile boolean failRevoke;
        /** Runs once just before the next save, standing in for a concurrent request between markUsed and save. */
        volatile Runnable beforeSave;

        @Override
        public synchronized void save(RefreshRecord record) {
            Runnable r = beforeSave;
            beforeSave = null;
            if (r != null) {
                r.run();
            }
            saved.add(record);
            d.save(record);
        }

        @Override
        public RefreshRecord find(String tokenId) {
            return d.find(tokenId);
        }

        @Override
        public boolean markUsed(String tokenId, long nowMillis) {
            marks.incrementAndGet();
            return d.markUsed(tokenId, nowMillis);
        }

        @Override
        public synchronized int revokeFamily(String familyId) throws Exception {
            if (failRevoke) {
                throw new java.sql.SQLException("the database is down");
            }
            revoked.add(familyId);
            return d.revokeFamily(familyId);
        }
    }

    private Auth.Builder builder() {
        return Auth.builder(AuthTestSupport.SECRET).hashIterations(1000).logger(new RecordingLogger()).users(users)
                .refreshTokens(store).timeSource(clock::get).events(new AuthEvents() {
                    @Override
                    public void onRefreshTokenReused(String userId, String familyId, AuthEventContext ctx) {
                        reused.add(userId + "/" + familyId);
                    }
                });
    }

    private AuthUser alice() throws Exception {
        AuthUser u = users.findByUsername("alice");
        return u != null ? u : users.create(AuthUser.builder().username("alice").passwordHash("x").build());
    }

    private static AuthRejection refusal(TokenService t, String token) {
        try {
            t.refresh(token);
        } catch (AuthRejection r) {
            return r;
        }
        throw new AssertionError("the refresh token was accepted");
    }

    private static String fam(Auth auth, TokenPair p) {
        String f = auth.tokens().verifyAccess(p.getAccessToken()).getString("fam");
        assertNotNull(f);
        return f;
    }

    // SEC-TOKEN-006 AC-1: with reuseGrace 0, rt1 rotated to rt2, rt1 again fails, revokeFamily(F) once, rt2 then fails
    @Test
    public void SEC_TOKEN_006_AC1_reuseRevokesTheFamily() throws Exception {
        Auth auth = builder().reuseGrace(0).build();
        TokenPair p1 = auth.tokens().issue(alice());
        String f = fam(auth, p1);
        clock.addAndGet(1000);
        TokenPair p2 = auth.tokens().refresh(p1.getRefreshToken());
        refusal(auth.tokens(), p1.getRefreshToken());
        assertEquals(Arrays.asList(f), store.revoked);
        refusal(auth.tokens(), p2.getRefreshToken());
    }

    // SEC-TOKEN-006 AC-2: the reuse raises onRefreshTokenReused once with the user id and family F, and its failure is
    // indistinguishable from an expired or a bad-signature refresh token
    @Test
    public void SEC_TOKEN_006_AC2_reuseEventAndIndistinguishableAnswer() throws Exception {
        Auth auth = builder().reuseGrace(0).build();
        AuthUser a = alice();
        TokenPair p1 = auth.tokens().issue(a);
        String f = fam(auth, p1);
        auth.tokens().refresh(p1.getRefreshToken());
        AuthRejection reuse = refusal(auth.tokens(), p1.getRefreshToken());
        assertEquals(Arrays.asList(a.getId() + "/" + f), reused);

        TokenPair other = auth.tokens().issue(a);
        String rt = other.getRefreshToken();
        String forged = rt.substring(0, rt.length() - 2) + (rt.endsWith("AA") ? "BB" : "AA");
        AuthRejection badSignature = refusal(auth.tokens(), forged);
        clock.addAndGet(auth.tokens().getRefreshTtl() + 120_000L);
        AuthRejection expired = refusal(auth.tokens(), rt);
        for (AuthRejection r : new AuthRejection[] {badSignature, expired}) {
            assertEquals(reuse.getStatus(), r.getStatus());
            assertEquals(reuse.getCode(), r.getCode());
            assertEquals(reuse.getMessage(), r.getMessage());
            assertEquals(reuse.getRetryAfterSeconds(), r.getRetryAfterSeconds());
        }
        assertEquals("only the reuse raised the event", 1, reused.size());
    }

    // SEC-TOKEN-006 AC-3: with reuseGrace 0, reuse in family F leaves family G of the same user able to refresh
    @Test
    public void SEC_TOKEN_006_AC3_otherFamilyKeepsRefreshing() throws Exception {
        Auth auth = builder().reuseGrace(0).build();
        TokenPair f = auth.tokens().issue(alice());
        TokenPair g = auth.tokens().issue(alice());
        auth.tokens().refresh(f.getRefreshToken());
        refusal(auth.tokens(), f.getRefreshToken());
        assertNotNull(auth.tokens().refresh(g.getRefreshToken()));
    }

    // SEC-TOKEN-006 AC-4: with reuseGrace 0, a second use 1 ms after the first already counts as reuse
    @Test
    public void SEC_TOKEN_006_AC4_oneMillisecondLaterIsReuse() throws Exception {
        Auth auth = builder().reuseGrace(0).build();
        TokenPair p = auth.tokens().issue(alice());
        auth.tokens().refresh(p.getRefreshToken());
        clock.addAndGet(1);
        refusal(auth.tokens(), p.getRefreshToken());
        assertEquals(1, store.revoked.size());
        assertEquals(1, reused.size());
    }

    // the default window is 0 as well, and a configured grace refuses a repeat without revoking (a settled open value)
    @Test
    public void reuseGraceDefaultIsZeroAndAWindowRefusesWithoutRevoking() throws Exception {
        assertEquals(0L, builder().build().tokens().reuseGraceMillis());
        Auth auth = builder().reuseGrace(5000).build();
        TokenPair p = auth.tokens().issue(alice());
        TokenPair next = auth.tokens().refresh(p.getRefreshToken());
        clock.addAndGet(4000);
        refusal(auth.tokens(), p.getRefreshToken());
        assertTrue(store.revoked.isEmpty());
        assertTrue(reused.isEmpty());
        assertNotNull(auth.tokens().refresh(next.getRefreshToken()));
        clock.addAndGet(2000);
        refusal(auth.tokens(), p.getRefreshToken()); // 6 s after the first use: outside the window
        assertEquals(1, store.revoked.size());
        try {
            builder().reuseGrace(-1).build();
            fail("a negative grace was accepted");
        } catch (AuthConfigException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(".reuseGrace(-1)"));
        }
    }

    // SEC-TOKEN-006 AC-5: a used or revoked refresh token gives no logout identity, without markUsed, revokeFamily or
    // onRefreshTokenReused
    @Test
    public void SEC_TOKEN_006_AC5_logoutIdentityNeverConsumes() throws Exception {
        Auth auth = builder().reuseGrace(0).build();
        TokenPair used = auth.tokens().issue(alice());
        TokenPair fresh = auth.tokens().refresh(used.getRefreshToken());
        TokenPair revoked = auth.tokens().issue(alice());
        store.d.revokeFamily(fam(auth, revoked));
        int marks = store.marks.get();
        assertNull(auth.tokens().logoutIdentity(used.getRefreshToken()));
        assertNull(auth.tokens().logoutIdentity(revoked.getRefreshToken()));
        assertNull(auth.tokens().logoutIdentity("not-a-token"));
        JwtClaims live = auth.tokens().logoutIdentity(fresh.getRefreshToken());
        assertNotNull(live);
        assertEquals(alice().getId(), live.getSubject());
        assertEquals(marks, store.marks.get());
        assertTrue(store.revoked.isEmpty());
        assertTrue(reused.isEmpty());
        assertNotNull("the live token is still unused", auth.tokens().refresh(fresh.getRefreshToken()));
    }

    // SEC-TOKEN-006 AC-6: a store error while handling the reuse is a 503 AuthUnavailableException, neither a refresh
    // nor an invalid-token answer
    @Test
    public void SEC_TOKEN_006_AC6_storeErrorDuringReuseIs503() throws Exception {
        Auth auth = builder().reuseGrace(0).build();
        TokenPair p = auth.tokens().issue(alice());
        auth.tokens().refresh(p.getRefreshToken());
        store.failRevoke = true;
        try {
            auth.tokens().refresh(p.getRefreshToken());
            fail("the reuse was answered without the store");
        } catch (AuthUnavailableException e) {
            assertEquals(503, e.getStatus());
        } catch (AuthRejection r) {
            fail("a store error became " + r.getCode());
        }
        assertTrue(reused.isEmpty());
    }

    // A concurrent reuse of the same refresh token that lands between the winner's markUsed and its save revokes the
    // family before the winner's new record exists; the winner must not walk away with a live refresh token
    // (SEC-TOKEN-006 AC-1 under a race)
    @Test
    public void concurrentReuseBetweenMarkUsedAndSaveRevokesTheWinnersNewRecord() throws Exception {
        final Auth auth = builder().reuseGrace(0).build();
        final TokenPair p = auth.tokens().issue(alice());
        String f = fam(auth, p);
        final List<String> loser = new ArrayList<>();
        store.beforeSave = () -> loser.add(refusal(auth.tokens(), p.getRefreshToken()).getCode());
        AuthRejection winner = refusal(auth.tokens(), p.getRefreshToken());
        assertEquals("invalid_refresh_token", winner.getCode());
        assertEquals(Arrays.asList("invalid_refresh_token"), loser);
        assertEquals(Arrays.asList(alice().getId() + "/" + f), reused);
        RefreshRecord fresh = store.saved.get(store.saved.size() - 1);
        assertTrue("the winner's new record is revoked", store.find(fresh.getTokenId()).isRevoked());
    }

    // A logout that revokes the family between a refresh's markUsed and its save leaves no live token either
    @Test
    public void familyRevokedBetweenMarkUsedAndSaveRefusesTheRefresh() throws Exception {
        final Auth auth = builder().reuseGrace(0).build();
        final TokenPair p = auth.tokens().issue(alice());
        final String f = fam(auth, p);
        store.beforeSave = () -> store.d.revokeFamily(f);
        AuthRejection r = refusal(auth.tokens(), p.getRefreshToken());
        assertEquals("invalid_refresh_token", r.getCode());
        RefreshRecord fresh = store.saved.get(store.saved.size() - 1);
        assertTrue("the new record is revoked", store.find(fresh.getTokenId()).isRevoked());
        assertTrue("a logout is not a reuse", reused.isEmpty());
    }

    // ---------------------------------------------------------------------------------------------- TokenCodec

    /** A codec that wraps the default one and counts calls; its decode can be told to throw. */
    static final class WrappingCodec implements TokenCodec {
        final TokenCodec inner;
        final AtomicInteger encodes = new AtomicInteger();
        final AtomicInteger decodes = new AtomicInteger();
        volatile boolean fail;

        WrappingCodec(TokenCodec inner) {
            this.inner = inner;
        }

        @Override
        public String encode(JwtClaims claims) throws Exception {
            encodes.incrementAndGet();
            return "x." + inner.encode(claims);
        }

        @Override
        public JwtClaims decode(String token, String expectedType) throws Exception {
            decodes.incrementAndGet();
            if (fail || !token.startsWith("x.")) {
                throw new IllegalStateException("codec refused: secret detail");
            }
            return inner.decode(token.substring(2), expectedType);
        }
    }

    // IR-TOKEN-002 AC-3: encode(claims) and decode(token, expectedType) with non-null parameters and returns through the
    // spi @NullMarked default (no @Nullable, no @NonNull), throwing Exception; the default codec keeps typ separation
    @Test
    public void IR_TOKEN_002_AC3_methodShapeAndDefaultTypeSeparation() throws Exception {
        Method encode = TokenCodec.class.getMethod("encode", JwtClaims.class);
        Method decode = TokenCodec.class.getMethod("decode", String.class, String.class);
        for (Method m : new Method[] {encode, decode}) {
            assertEquals(Arrays.asList(Exception.class), Arrays.asList(m.getExceptionTypes()));
            List<AnnotatedType> types = new ArrayList<>(Arrays.asList(m.getAnnotatedParameterTypes()));
            types.add(m.getAnnotatedReturnType());
            for (AnnotatedType t : types) {
                for (Annotation a : t.getAnnotations()) {
                    fail(m.getName() + " carries " + a);
                }
            }
            for (Annotation[] ps : m.getParameterAnnotations()) {
                assertEquals(m.getName(), 0, ps.length);
            }
            assertEquals(0, m.getAnnotations().length);
        }
        assertEquals(String.class, encode.getReturnType());
        assertEquals(JwtClaims.class, decode.getReturnType());
        assertNotNull(TokenCodec.class.getPackage().getAnnotation(org.jspecify.annotations.NullMarked.class));

        JwtKeys keys = JwtKeys.of(AuthTestSupport.SECRET.getBytes("UTF-8"));
        Hs256TokenCodec codec = new Hs256TokenCodec(keys, clock::get);
        JwtClaims access = JwtClaims.builder().type(TokenCodec.ACCESS).subject("u1").issuedAt(T0)
                .expiresAt(T0 + 60_000L).tokenId("j1").claim("ver", 0L).claim("fam", "f1").build();
        JwtClaims refresh = JwtClaims.builder().type(TokenCodec.REFRESH)
                .subject("u1").issuedAt(T0).expiresAt(T0 + 60_000L).tokenId("j2").claim("ver", 0L).claim("fam", "f1")
                .build();
        String at = codec.encode(access);
        String rt = codec.encode(refresh);
        assertEquals("u1", codec.decode(at, TokenCodec.ACCESS).getSubject());
        assertEquals("u1", codec.decode(rt, TokenCodec.REFRESH).getSubject());
        refused(codec, at, TokenCodec.REFRESH);
        refused(codec, rt, TokenCodec.ACCESS);
    }

    private static void refused(TokenCodec codec, String token, String type) {
        try {
            codec.decode(token, type);
            fail("decoded as " + type);
        } catch (Exception expected) {
            // typ separation
        }
    }

    // A configured codec issues every token and authenticates protected requests from its decode; a throwing decode
    // is the 401 invalid_token of a bad signature and its reason stays out of the response (the TokenService half of
    // the codec requirement, not a citation; the login half arrives with POST /auth/login)
    @Test
    public void customCodecIssuesAndAuthenticates() throws Exception {
        JwtKeys keys = JwtKeys.of(AuthTestSupport.SECRET.getBytes("UTF-8"));
        WrappingCodec codec = new WrappingCodec(new Hs256TokenCodec(keys, clock::get));
        Auth auth = builder().tokenCodec(codec).build();
        assertTrue(auth.tokenCodec() == codec);
        TokenPair p = auth.tokens().issue(alice());
        assertTrue(p.getAccessToken().startsWith("x.") && p.getRefreshToken().startsWith("x."));
        assertEquals(2, codec.encodes.get());
        assertNotNull(auth.tokens().refresh(p.getRefreshToken()));

        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        TestApp t = TestApp.create(adapter);
        auth.install(t.app());
        t.app().get("/api/me", (req, res) -> res.send("me:" + auth.user(req).getId()));
        t.start();
        try {
            ClientResponse ok = adapter.send(ClientRequest.of("GET", "/api/me")
                    .header("Authorization", "Bearer " + p.getAccessToken()));
            assertEquals(ok.text(), 200, ok.status());
            codec.fail = true;
            ClientResponse bad = adapter.send(ClientRequest.of("GET", "/api/me")
                    .header("Authorization", "Bearer " + p.getAccessToken()));
            codec.fail = false;
            String forged = forgedSignature(p.getAccessToken());
            assertNotEquals(p.getAccessToken(), forged);
            ClientResponse sig = adapter.send(ClientRequest.of("GET", "/api/me")
                    .header("Authorization", "Bearer " + forged));
            assertEquals(401, bad.status());
            assertEquals("invalid_token", AuthTestSupport.code(bad));
            assertEquals(sig.status(), bad.status());
            assertEquals(sig.text().replaceAll("\"requestId\":\"[^\"]*\"", ""),
                    bad.text().replaceAll("\"requestId\":\"[^\"]*\"", ""));
            assertFalse(bad.text().contains("secret detail"));
            // T2-F07: a token whose signature already ends in "AA" (about 1 issue in 1024) is forged too: the forged
            // signature differs from it and answers the same 401 invalid_token, so the check above cannot pass or
            // fail by chance
            String endsInAa = null;
            for (int i = 0; i < 50_000 && endsInAa == null; i++) {
                String at = auth.tokens().issue(alice()).getAccessToken();
                endsInAa = at.endsWith("AA") ? at : null;
            }
            assertNotNull("no access token ending in AA was issued", endsInAa);
            String forgedAa = forgedSignature(endsInAa);
            assertNotEquals(endsInAa, forgedAa);
            ClientResponse sigAa = adapter.send(ClientRequest.of("GET", "/api/me")
                    .header("Authorization", "Bearer " + forgedAa));
            assertEquals(sigAa.text(), bad.status(), sigAa.status());
            assertEquals(sigAa.text().replaceAll("\"requestId\":\"[^\"]*\"", ""),
                    bad.text().replaceAll("\"requestId\":\"[^\"]*\"", ""));
        } finally {
            t.app().shutdown(5, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    /** {@code token} with the last two signature characters replaced by other ones (T2-F07: never the same token). */
    private static String forgedSignature(String token) {
        return "x." + token.substring(2, token.length() - 2) + (token.endsWith("AA") ? "BB" : "AA");
    }

    // The default codec issues the SEC-TOKEN-005 format: HS256 with kid, typ at+jwt / rt+jwt over purpose keys (the
    // TokenService half only, not a citation; the login half arrives with POST /auth/login)
    @Test
    public void defaultCodecKeepsTheTokenFormat() throws Exception {
        Auth auth = builder().build();
        assertTrue(auth.tokenCodec() instanceof Hs256TokenCodec);
        TokenPair p = auth.tokens().issue(alice());
        Jwt plain = Jwt.builder(auth.keySet()).timeSource(clock::get).build();
        try {
            plain.verify(p.getAccessToken());
            fail("an access token verified with the raw key");
        } catch (com.snoworca.naruserve.utils.auth.jwt.JwtException expected) {
            // purpose-derived keys
        }
        assertEquals(TokenCodec.ACCESS, auth.tokens().verifyAccess(p.getAccessToken()).getType());
    }

    // ---------------------------------------------------------------------------------------------- insecure transport

    // SEC-FLOW-002 AC-2: under allowInsecureTransport, accessTtl 15 min and refreshTtl 14 days are overridden to an
    // access token with exp - iat = 300 s and a refresh token expiring 24 h after issue
    @Test
    public void SEC_FLOW_002_AC2_insecureTransportForcesShortLifetimes() throws Exception {
        Auth auth = builder().allowInsecureTransport("TLS adapter pending").accessTtl(15 * 60_000L)
                .refreshTtl(14L * 24 * 3_600_000L).build();
        TokenPair p = auth.tokens().issue(alice());
        JwtClaims at = auth.tokens().verifyAccess(p.getAccessToken());
        Long iat = at.getIssuedAt();
        assertNotNull(iat);
        assertEquals(300_000L, at.getExpiresAt() - iat);
        assertEquals(300L, p.getExpiresIn());
        assertEquals(24L * 3600L, p.getRefreshExpiresIn());
        assertEquals(1, store.saved.size());
        RefreshRecord r = store.saved.get(0);
        assertEquals(24L * 3_600_000L, r.getExpiresAt() - r.getCreatedAt());
        Auth secure = builder().accessTtl(15 * 60_000L).build();
        assertEquals(15 * 60_000L, secure.tokens().getAccessTtl());
    }
}
