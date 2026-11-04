package com.snoworca.naruserve.utils.auth;

/**
 * An access token and a refresh token issued together, as {@link TokenService#issue} and
 * {@link TokenService#refresh} return them. The JSON field names follow the OAuth 2.0 token response in camel case:
 * {@code accessToken}, {@code expiresIn} (seconds), {@code refreshToken}, {@code refreshExpiresIn} (seconds) and
 * {@code tokenType} ({@code "Bearer"}). Express counterpart: the object a passport-jwt login route sends back.
 */
// @req FR-TOKEN-001
public final class TokenPair {

    private final String accessToken;
    private final long expiresIn;
    private final String refreshToken;
    private final long refreshExpiresIn;
    private final String tokenType = "Bearer";

    /** The user and refresh family of the pair, for the CSRF token of cookie delivery; not part of the JSON. */
    private final transient String userId;
    private final transient String familyId;

    TokenPair(String accessToken, long expiresIn, String refreshToken, long refreshExpiresIn) {
        this(accessToken, expiresIn, refreshToken, refreshExpiresIn, "", "");
    }

    TokenPair(String accessToken, long expiresIn, String refreshToken, long refreshExpiresIn, String userId,
            String familyId) {
        this.accessToken = accessToken;
        this.expiresIn = expiresIn;
        this.refreshToken = refreshToken;
        this.refreshExpiresIn = refreshExpiresIn;
        this.userId = userId;
        this.familyId = familyId;
    }

    String userId() {
        return userId;
    }

    String familyId() {
        return familyId;
    }

    /** The access token, sent as {@code Authorization: Bearer <token>}. */
    public String getAccessToken() {
        return accessToken;
    }

    /** The access token lifetime in seconds. */
    public long getExpiresIn() {
        return expiresIn;
    }

    /** The refresh token, exchanged once at the refresh endpoint for a new pair. */
    public String getRefreshToken() {
        return refreshToken;
    }

    /** The refresh token lifetime in seconds. */
    public long getRefreshExpiresIn() {
        return refreshExpiresIn;
    }

    /** Always {@code "Bearer"}. */
    public String getTokenType() {
        return tokenType;
    }

    /** No token appears in the text. */
    @Override
    public String toString() {
        return "TokenPair{tokenType=Bearer, expiresIn=" + expiresIn + ", refreshExpiresIn=" + refreshExpiresIn + "}";
    }
}
