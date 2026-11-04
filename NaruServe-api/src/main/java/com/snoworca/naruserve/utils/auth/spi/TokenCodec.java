package com.snoworca.naruserve.utils.auth.spi;

import com.snoworca.naruserve.utils.auth.jwt.JwtClaims;

/**
 * The format of auth's access and refresh tokens: auth builds the claims ({@code sub}, {@code ver}, {@code fam},
 * {@code iat}, {@code exp}, {@code jti} and the type {@link #ACCESS} or {@link #REFRESH}) and the codec turns them into
 * a token string and back. Every token auth issues and every token it reads goes through the configured codec, set with
 * {@code Auth.builder(..).tokenCodec(codec)}. The default is the built-in HS256 codec over the keys of the
 * {@link KeySource}: header {@code alg} HS256 with {@code kid}, {@code typ} {@code at+jwt} or {@code rt+jwt}, each type
 * signed with its own purpose-derived key. 0.11.0 ships no other codec (RS256, ES256, JWKS and introspection codecs
 * come later). Both methods may throw; a failing {@link #decode} answers the request like a bad signature (401
 * {@code invalid_token}, or {@code invalid_refresh_token} on refresh) and its message is only logged. After the first
 * public release this interface gains default methods only. Express counterpart: the {@code sign}/{@code verify} pair
 * handed to {@code express-jwt}.
 */
// @req IR-TOKEN-002
public interface TokenCodec {

    /** The type of an access token, {@code at+jwt}. */
    String ACCESS = "at+jwt";

    /** The type of a refresh token, {@code rt+jwt}. */
    String REFRESH = "rt+jwt";

    /** The token string of {@code claims}; {@code claims.getType()} is {@link #ACCESS} or {@link #REFRESH}. */
    String encode(JwtClaims claims) throws Exception;

    /**
     * The claims of {@code token} when it is valid (integrity, expiry) and of {@code expectedType}
     * ({@link #ACCESS} or {@link #REFRESH}); throws otherwise, so a refresh token never decodes as an access token
     * and an access token never decodes as a refresh token.
     */
    JwtClaims decode(String token, String expectedType) throws Exception;
}
