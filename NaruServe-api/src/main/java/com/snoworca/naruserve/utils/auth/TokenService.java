package com.snoworca.naruserve.utils.auth;

import com.snoworca.naruserve.Logger;
import com.snoworca.naruserve.utils.TimeSource;
import com.snoworca.naruserve.utils.auth.internal.EventCalls;
import com.snoworca.naruserve.utils.auth.internal.Hs256TokenCodec;
import com.snoworca.naruserve.utils.auth.internal.AccessTokens;
import com.snoworca.naruserve.utils.auth.internal.ConstantTime;
import com.snoworca.naruserve.utils.auth.internal.RefreshTokens;
import com.snoworca.naruserve.utils.auth.jwt.JwtClaims;
import com.snoworca.naruserve.utils.auth.jwt.JwtException;
import com.snoworca.naruserve.utils.auth.jwt.JwtKeys;
import com.snoworca.naruserve.utils.auth.spi.RefreshRecord;
import com.snoworca.naruserve.utils.auth.spi.RefreshTokenStore;
import com.snoworca.naruserve.utils.auth.spi.TokenCodec;
import com.snoworca.naruserve.utils.auth.user.AuthUser;
import com.snoworca.naruserve.utils.auth.user.UserStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Issues and rotates the tokens of one Auth, {@code auth.tokens()}. {@link #issue(AuthUser)} starts a new refresh
 * family (login, registration, setup, password change); {@link #refresh(String)} checks the refresh token, the user's
 * current token version and enabled flag and the stored record, marks the presented record used (an atomic
 * compare-and-set of the {@link RefreshTokenStore}) and returns a new pair in the same family. A refresh token that is
 * used again outside the reuse grace window ({@code .reuseGrace(millis)}, 0 by default) revokes its whole family and
 * raises {@link AuthEvents#onRefreshTokenReused} (reuse detection, RFC 9700 4.14), answering exactly like any other
 * invalid refresh token. Every token goes through the configured {@code TokenCodec}. Access tokens carry exactly {@code sub},
 * {@code ver}, {@code fam}, {@code iat}, {@code exp} and {@code jti} under {@code typ} {@code at+jwt}; refresh tokens
 * the same claims under {@code typ} {@code rt+jwt}; each is signed with its own key derived from the key set (no scope
 * or role claim: scopes are read per request). Store failures throw {@link AuthUnavailableException} (503); a refresh
 * token that cannot be used throws {@code AuthRejection(401, "invalid_refresh_token")} and issues nothing. Express
 * counterpart: the {@code jwt.sign} calls of a passport-jwt login route plus a refresh-token table.
 */
// @req FR-TOKEN-001
// @req SEC-TOKEN-005
public final class TokenService {

    private final UserStore users;
    private final RefreshTokenStore store;
    private final AccessTokens access;
    private final RefreshTokens refresh;
    private final TimeSource time;
    private final long accessTtlMillis;
    private final long refreshTtlMillis;
    private final long reuseGraceMillis;
    private final List<AuthEvents> events;
    private volatile @Nullable Logger logger;
    private final java.util.concurrent.atomic.AtomicBoolean unknownFamilyWarned =
            new java.util.concurrent.atomic.AtomicBoolean();

    TokenService(UserStore users, RefreshTokenStore store, JwtKeys keys, TimeSource time, long accessTtlMillis,
            long refreshTtlMillis) {
        this(users, store, new Hs256TokenCodec(keys, time), time, accessTtlMillis, refreshTtlMillis, 0L,
                Collections.<AuthEvents>emptyList(), null);
    }

    // @req IR-TOKEN-002
    // @req SEC-TOKEN-006
    TokenService(UserStore users, RefreshTokenStore store, TokenCodec codec, TimeSource time, long accessTtlMillis,
            long refreshTtlMillis, long reuseGraceMillis, List<AuthEvents> events, @Nullable Logger logger) {
        this.users = users;
        this.store = store;
        this.access = new AccessTokens(codec, time, logger);
        this.refresh = new RefreshTokens(codec, time);
        this.time = time;
        this.accessTtlMillis = accessTtlMillis;
        this.refreshTtlMillis = refreshTtlMillis;
        this.reuseGraceMillis = reuseGraceMillis;
        this.events = events;
        this.logger = logger;
    }

    /** Replaces the logger of after-event failures (auth swaps in the app's logger at install). */
    void logger(Logger logger) {
        this.logger = logger;
    }

    /** The reuse grace window in milliseconds; 0 makes every second use of a refresh token a reuse. */
    long reuseGraceMillis() {
        return reuseGraceMillis;
    }

    /** The access token lifetime in milliseconds. */
    public long getAccessTtl() {
        return accessTtlMillis;
    }

    /** The refresh token lifetime in milliseconds. */
    public long getRefreshTtl() {
        return refreshTtlMillis;
    }

    /**
     * A new pair for {@code user} (a stored user with an id) in a new refresh family; the refresh record is saved
     * before the pair is returned. Each call starts another family, so two logins never share one.
     */
    // @req FR-TOKEN-001
    public TokenPair issue(AuthUser user) {
        Objects.requireNonNull(user, "user");
        String id = user.getId();
        if (id == null) {
            throw new IllegalArgumentException("the user has no id; issue tokens for a stored user");
        }
        return pair(id, user.getTokenVersion(), AccessTokens.random());
    }

    private TokenPair pair(String userId, long ver, String familyId) {
        String jti = AccessTokens.random();
        String rt = refresh.issue(userId, ver, familyId, jti, refreshTtlMillis);
        long now = time.nowMillis();
        final RefreshRecord record = RefreshRecord.builder().tokenId(jti).familyId(familyId).userId(userId)
                .tokenHash(hash(rt)).createdAt(now).expiresAt(now + refreshTtlMillis).build();
        call(() -> {
            store.save(record);
            return Boolean.TRUE;
        });
        String at = access.issue(userId, ver, familyId, accessTtlMillis);
        return new TokenPair(at, accessTtlMillis / 1000L, rt, refreshTtlMillis / 1000L, userId, familyId);
    }

    /**
     * An access token alone for {@code user} at its current version, in a new synthetic family with no refresh record
     * (the test kit's {@code AuthTesting.tokenFor}); it verifies like any other access token.
     */
    // @req IR-AUTH-005
    String accessOnly(AuthUser user) {
        String id = user.getId();
        if (id == null) {
            throw new IllegalArgumentException("the user has no id; issue tokens for a stored user");
        }
        return access.issue(id, user.getTokenVersion(), AccessTokens.random(), accessTtlMillis);
    }

    /**
     * A new pair in the family of {@code refreshToken}: the token must verify as a refresh token, its record must be
     * found in the store unused and unrevoked with the same hash, and the user must exist, be enabled and still be at
     * the token's version; the record is marked used before the new one is saved. A record that was already used
     * revokes the family; a family revoked while the new record was being saved (a concurrent reuse or logout) is
     * revoked again, new record included, and the refresh is refused. Any refusal is {@code AuthRejection(401,
     * "invalid_refresh_token")}.
     */
    // @req FR-TOKEN-001
    public TokenPair refresh(String refreshToken) {
        Objects.requireNonNull(refreshToken, "refreshToken");
        final JwtClaims c;
        try {
            c = refresh.verify(refreshToken);
        } catch (JwtException e) {
            if (isAccessToken(refreshToken)) { // OPS-AUTH-022 AC-6
                throw new AuthRejection(AuthErrorCode.INVALID_REFRESH_TOKEN.getStatus(),
                        AuthErrorCode.INVALID_REFRESH_TOKEN.getCode(), "the refresh token is not valid: this is an "
                        + "access token; send the refreshToken of the login or refresh answer");
            }
            throw invalid();
        }
        final String jti = c.getTokenId();
        final String sub = c.getSubject();
        final String fam = c.getString("fam");
        final Long ver = c.getLong("ver");
        if (jti == null || sub == null || fam == null || ver == null) {
            throw invalid();
        }
        RefreshRecord r = call(() -> store.find(jti));
        if (r == null) {
            unknownFamily();
        }
        if (r == null || r.isRevoked() || !r.getUserId().equals(sub) || !r.getFamilyId().equals(fam)
                || !ConstantTime.equals(hash(refreshToken).getBytes(StandardCharsets.US_ASCII),
                        r.getTokenHash().getBytes(StandardCharsets.US_ASCII))) {
            throw invalid();
        }
        final long now = time.nowMillis();
        Long usedAt = r.getUsedAt();
        if (usedAt != null) {
            if (reuseGraceMillis > 0 && now - usedAt < reuseGraceMillis) {
                throw invalid(); // a repeat inside the grace window: refused, nothing revoked
            }
            reused(sub, fam);
            throw invalid();
        }
        AuthUser u = call(() -> users.findById(sub));
        if (u == null || !u.isEnabled() || u.getTokenVersion() != ver) {
            throw invalid();
        }
        boolean first = call(() -> store.markUsed(jti, now));
        if (!first) {
            if (reuseGraceMillis > 0) {
                throw invalid(); // a concurrent use won the record: inside the grace window by definition
            }
            reused(sub, fam);
            throw invalid();
        }
        TokenPair next = pair(sub, ver, fam);
        // A concurrent reuse or logout that revoked the family between markUsed and save revoked only the records
        // that existed then; the new record must not outlive it.
        RefreshRecord after = call(() -> store.find(jti));
        if (after == null || after.isRevoked()) {
            revoke(fam);
            throw invalid();
        }
        return next;
    }

    /**
     * R35 (2): a verified refresh token whose record this node's in-memory store does not hold was issued before a
     * restart or by another node (an undeclared multi-instance deployment); the first one logs one WARN, without the
     * token. A shared or file store holds every record of the deployment, so it is not reported.
     */
    // @req FR-AUTH-003
    private void unknownFamily() {
        Logger log = logger;
        if (store instanceof InMemoryRefreshTokenStore && log != null && unknownFamilyWarned.compareAndSet(false, true)) {
            log.warn("auth: a refresh token whose record this node's in-memory refresh store does not hold was "
                    + "refused; it was issued before a restart or by another node: if several nodes serve this app, "
                    + "declare .deployment(MULTI_INSTANCE) with shared stores (logged once)");
        }
    }

    /**
     * R45: the identity a logout may act on, read from {@code refreshToken} without consuming it: the claims when the
     * token verifies and its record is present, unused, unrevoked and of the same user, family and hash; null
     * otherwise. It never calls markUsed or revokeFamily and raises no event, so a used or revoked token gives no
     * identity and never counts as reuse. A store failure throws {@link AuthUnavailableException}.
     */
    // @req SEC-TOKEN-006
    @Nullable JwtClaims logoutIdentity(String refreshToken) {
        final JwtClaims c;
        try {
            c = refresh.verify(refreshToken);
        } catch (JwtException e) {
            return null;
        }
        final String jti = c.getTokenId();
        String sub = c.getSubject();
        String fam = c.getString("fam");
        if (jti == null || sub == null || fam == null) {
            return null;
        }
        RefreshRecord r = call(() -> store.find(jti));
        if (r == null || r.isRevoked() || r.getUsedAt() != null || !r.getUserId().equals(sub)
                || !r.getFamilyId().equals(fam)
                || !ConstantTime.equals(hash(refreshToken).getBytes(StandardCharsets.US_ASCII),
                        r.getTokenHash().getBytes(StandardCharsets.US_ASCII))) {
            return null;
        }
        return c;
    }

    /**
     * SEC-FLOW-008: the claims of a cookie refresh token checked before its CSRF check, without consuming it: the token
     * verifies and its record is present, unrevoked and of the same user, family and hash. A used record still passes
     * here, so the rotation that follows the CSRF check is the one that detects reuse (a forged cross-site request
     * cannot trigger it). Null otherwise; a store failure throws {@link AuthUnavailableException}.
     */
    // @req SEC-FLOW-008
    @Nullable JwtClaims refreshIdentity(String refreshToken) {
        final JwtClaims c;
        try {
            c = refresh.verify(refreshToken);
        } catch (JwtException e) {
            return null;
        }
        final String jti = c.getTokenId();
        String sub = c.getSubject();
        String fam = c.getString("fam");
        if (jti == null || sub == null || fam == null) {
            return null;
        }
        RefreshRecord r = call(() -> store.find(jti));
        if (r == null || r.isRevoked() || !r.getUserId().equals(sub) || !r.getFamilyId().equals(fam)
                || !ConstantTime.equals(hash(refreshToken).getBytes(StandardCharsets.US_ASCII),
                        r.getTokenHash().getBytes(StandardCharsets.US_ASCII))) {
            return null;
        }
        return c;
    }

    /** Reuse outside the grace window: the family is revoked, then onRefreshTokenReused runs once. */
    // @req SEC-TOKEN-006
    private void reused(final String userId, final String fam) {
        revoke(fam);
        final AuthEventContext ctx = new AuthEventContext(null, null, null, time.nowMillis());
        Logger l = logger;
        for (final AuthEvents e : events) {
            if (l == null) {
                try {
                    e.onRefreshTokenReused(userId, fam, ctx);
                } catch (Exception ignored) {
                    // no logger: an after-event failure leaves the answer unchanged
                }
            } else {
                EventCalls.after(l, "onRefreshTokenReused", () -> e.onRefreshTokenReused(userId, fam, ctx));
            }
        }
    }

    /**
     * The claims of a valid access token (signature with the access key, expiry, {@code typ} at+jwt); otherwise
     * {@code AuthRejection(401, "invalid_token")}. It does not read the user store: the version and enabled checks are
     * the middleware's.
     */
    // @req FR-TOKEN-001
    public JwtClaims verifyAccess(String accessToken) {
        return access.claims(Objects.requireNonNull(accessToken, "accessToken"));
    }

    /** OPS-AUTH-026 AC-3: lets the access check name a device token. */
    void deviceTokens(com.snoworca.naruserve.utils.auth.internal.DeviceTokens d) {
        access.deviceTokens(d);
    }

    private void revoke(final String fam) {
        call(() -> store.revokeFamily(fam));
    }

    private boolean isAccessToken(String token) {
        try {
            access.claims(token);
            return true;
        } catch (AuthRejection notAccess) {
            return false;
        }
    }

    private static AuthRejection invalid() {
        return new AuthRejection(AuthErrorCode.INVALID_REFRESH_TOKEN.getStatus(),
                AuthErrorCode.INVALID_REFRESH_TOKEN.getCode(), "the refresh token is not valid");
    }

    /** The SHA-256 of a token as 64 lower-case hex digits; the store keeps this, never the token. */
    static String hash(String token) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
            StringBuilder b = new StringBuilder(64);
            for (byte x : d) {
                b.append(Character.forDigit((x >> 4) & 0xf, 16)).append(Character.forDigit(x & 0xf, 16));
            }
            return b.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** One store call. */
    private interface StoreCall<T> {
        T run() throws Exception;
    }

    private static <T> T call(StoreCall<T> c) {
        try {
            return c.run();
        } catch (AuthRejection | AuthUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new AuthUnavailableException("the token store failed", e);
        }
    }

    @Override
    public String toString() {
        return "TokenService{accessTtl=" + accessTtlMillis + "ms, refreshTtl=" + refreshTtlMillis + "ms}";
    }
}
