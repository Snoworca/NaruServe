package com.snoworca.naruserve.utils.auth.internal;

import com.snoworca.naruserve.utils.TimeSource;
import com.snoworca.naruserve.utils.auth.jwt.JwtClaims;
import com.snoworca.naruserve.utils.auth.jwt.JwtException;
import com.snoworca.naruserve.utils.auth.jwt.JwtKeys;
import com.snoworca.naruserve.utils.auth.spi.TokenCodec;

/**
 * The refresh tokens of one Auth (00 R2, R8): header {@code typ} {@code rt+jwt}, claims {@code sub}, {@code ver},
 * {@code fam}, {@code jti}, {@code iat} and {@code exp}, signed with the refresh-purpose keys of {@link TokenKeys}, so
 * an access token never verifies here and a refresh token never verifies as an access token.
 */
// @req SEC-TOKEN-005
public final class RefreshTokens {

    /** The header {@code typ} of a refresh token. */
    public static final String TYPE = "rt+jwt";

    private final TokenCodec codec;
    private final TimeSource time;

    /** Refresh tokens over the refresh keys derived from {@code keys} (the default HS256 codec). */
    // @req SEC-TOKEN-005
    public RefreshTokens(JwtKeys keys, TimeSource time) {
        this(new Hs256TokenCodec(keys, time), time);
    }

    /** Refresh tokens encoded and decoded by {@code codec}. */
    // @req IR-TOKEN-002
    public RefreshTokens(TokenCodec codec, TimeSource time) {
        this.codec = codec;
        this.time = time;
    }

    /** A refresh token of {@code userId} at {@code ver} in family {@code familyId} with id {@code tokenId}. */
    // @req SEC-TOKEN-005
    public String issue(String userId, long ver, String familyId, String tokenId, long ttlMillis) {
        long now = time.nowMillis();
        JwtClaims claims = JwtClaims.builder().type(TYPE).subject(userId).issuedAt(now).expiresAt(now + ttlMillis)
                .tokenId(tokenId).claim("ver", ver).claim("fam", familyId).build();
        try {
            return codec.encode(claims);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("the token codec failed to encode a refresh token", e);
        }
    }

    /**
     * The claims of a refresh token that verifies (signature, expiry and {@code typ} rt+jwt), else the failure; a
     * codec failure other than a {@link JwtException} is reported as a malformed token.
     */
    // @req SEC-TOKEN-005
    // @req IR-TOKEN-002
    public JwtClaims verify(String token) throws JwtException {
        JwtClaims c;
        try {
            c = codec.decode(token, TYPE);
        } catch (JwtException e) {
            throw e;
        } catch (Exception e) {
            throw new JwtException(JwtException.Reason.MALFORMED, "the token codec refused the refresh token");
        }
        if (!TYPE.equals(c.getType())) {
            throw new JwtException(JwtException.Reason.MALFORMED, "the token codec ignored the expected type");
        }
        if (c.getSubject() == null || c.getLong("ver") == null || c.getString("fam") == null
                || c.getTokenId() == null) {
            throw new JwtException(JwtException.Reason.MALFORMED, "the refresh token lacks sub, ver, fam or jti");
        }
        return c;
    }

    @Override
    public String toString() {
        return "RefreshTokens{" + TYPE + "}";
    }
}
