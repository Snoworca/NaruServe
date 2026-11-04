package com.snoworca.naruserve.utils.auth.internal;

import com.snoworca.naruserve.utils.TimeSource;
import com.snoworca.naruserve.utils.auth.jwt.Jwt;
import com.snoworca.naruserve.utils.auth.jwt.JwtClaims;
import com.snoworca.naruserve.utils.auth.jwt.JwtException;
import com.snoworca.naruserve.utils.auth.jwt.JwtKeys;
import com.snoworca.naruserve.utils.auth.spi.TokenCodec;
import org.jspecify.annotations.Nullable;

/**
 * The default {@link TokenCodec}: the standalone HS256 {@link Jwt} with {@code kid}, access tokens signed with the
 * access-purpose keys and refresh tokens with the refresh-purpose keys of {@link TokenKeys} (00 R8), so neither type
 * ever verifies as the other.
 */
// @req IR-TOKEN-002
// @req SEC-TOKEN-005
public final class Hs256TokenCodec implements TokenCodec {

    private final Jwt access;
    private final Jwt refresh;

    /** The codec over the raw key set {@code keys} on {@code time}. */
    // @req IR-TOKEN-002
    public Hs256TokenCodec(JwtKeys keys, TimeSource time) {
        this.access = Jwt.builder(TokenKeys.purpose(keys, TokenKeys.ACCESS)).timeSource(time).build();
        this.refresh = Jwt.builder(TokenKeys.purpose(keys, TokenKeys.REFRESH)).timeSource(time).build();
    }

    // @req IR-TOKEN-002
    @Override
    public String encode(JwtClaims claims) {
        return jwt(claims.getType()).sign(claims);
    }

    // @req IR-TOKEN-002
    @Override
    public JwtClaims decode(String token, String expectedType) throws JwtException {
        return jwt(expectedType).verify(token, expectedType);
    }

    private Jwt jwt(@Nullable String type) {
        if (ACCESS.equals(type)) {
            return access;
        }
        if (REFRESH.equals(type)) {
            return refresh;
        }
        throw new IllegalArgumentException("the token type must be " + ACCESS + " or " + REFRESH + ", not " + type);
    }

    @Override
    public String toString() {
        return "Hs256TokenCodec";
    }
}
