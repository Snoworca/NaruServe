package com.snoworca.naruserve.utils.auth.spi;

import java.util.Objects;

/**
 * The verified content of one device token (00 R34 (2)): its random id, the user and token version it was issued
 * for, and its expiry. Auth hands a {@link DeviceTrust} only tokens whose signature, expiry and name fingerprint it
 * already checked; the token string itself and the login name never reach the trust store.
 */
// @req SEC-FLOW-013
public final class DeviceToken {

    private final String tokenId;
    private final String userId;
    private final long tokenVersion;
    private final long expiresAt;

    /** A device token: its random id, user id, token version ({@code ver}) and expiry in epoch milliseconds. */
    // @req SEC-FLOW-013
    public DeviceToken(String tokenId, String userId, long tokenVersion, long expiresAt) {
        this.tokenId = Objects.requireNonNull(tokenId, "tokenId");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.tokenVersion = tokenVersion;
        this.expiresAt = expiresAt;
    }

    /** The random id that names this token in a trust store. */
    public String getTokenId() {
        return tokenId;
    }

    /** The id of the user the token was issued to. */
    public String getUserId() {
        return userId;
    }

    /** The user's token version ({@code ver}) when the token was issued. */
    public long getTokenVersion() {
        return tokenVersion;
    }

    /** The expiry in epoch milliseconds. */
    public long getExpiresAt() {
        return expiresAt;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof DeviceToken && ((DeviceToken) o).tokenId.equals(tokenId);
    }

    @Override
    public int hashCode() {
        return tokenId.hashCode();
    }

    @Override
    public String toString() {
        return "DeviceToken{user=" + userId + ", ver=" + tokenVersion + "}";
    }
}
