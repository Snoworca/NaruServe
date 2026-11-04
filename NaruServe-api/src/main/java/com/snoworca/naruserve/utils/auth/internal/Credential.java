package com.snoworca.naruserve.utils.auth.internal;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** A verified credential: whose it is, the token version it carries, and for an API key its id and scopes. */
// @req IR-AUTH-004
public final class Credential {

    private final String userId;
    private final long tokenVersion;
    private final @Nullable String apiKeyId;
    private final @Nullable Set<String> keyScopes;
    private final @Nullable String familyId;
    private final long expiresAt;

    /** A login credential. */
    // @req IR-AUTH-004
    public Credential(String userId, long tokenVersion) {
        this(userId, tokenVersion, null, null);
    }

    /** A credential; an API key carries its id and its scopes. */
    // @req IR-AUTH-004
    public Credential(String userId, long tokenVersion, @Nullable String apiKeyId, @Nullable Set<String> keyScopes) {
        this(userId, tokenVersion, apiKeyId, keyScopes, Long.MAX_VALUE);
    }

    /** An API key credential with the key's expiry in epoch milliseconds ({@link Long#MAX_VALUE}: none). */
    // @req SEC-APIKEY-011
    public Credential(String userId, long tokenVersion, @Nullable String apiKeyId, @Nullable Set<String> keyScopes,
            long expiresAt) {
        this.userId = userId;
        this.tokenVersion = tokenVersion;
        this.apiKeyId = apiKeyId;
        this.keyScopes = keyScopes == null ? null : Collections.unmodifiableSet(new LinkedHashSet<>(keyScopes));
        this.familyId = null;
        this.expiresAt = expiresAt;
    }

    /** A verified access token: its user, token version, refresh family and expiry in epoch milliseconds. */
    // @req SEC-TOKEN-005
    // @req FR-FLOW-008
    public Credential(String userId, long tokenVersion, @Nullable String familyId, long expiresAt) {
        this.userId = userId;
        this.tokenVersion = tokenVersion;
        this.apiKeyId = null;
        this.keyScopes = null;
        this.familyId = familyId;
        this.expiresAt = expiresAt;
    }

    /** The user id. */
    public String getUserId() {
        return userId;
    }

    /** The token version the credential was issued for. */
    public long getTokenVersion() {
        return tokenVersion;
    }

    /** The API key id, or null for a login token. */
    public @Nullable String getApiKeyId() {
        return apiKeyId;
    }

    /** The API key's scopes, or null for a login token. */
    public @Nullable Set<String> getKeyScopes() {
        return keyScopes;
    }

    /** The refresh family the access token was issued with ({@code fam}), or null for another credential. */
    public @Nullable String getFamilyId() {
        return familyId;
    }

    /** The expiry in epoch milliseconds; {@link Long#MAX_VALUE} for a credential without one. */
    public long getExpiresAt() {
        return expiresAt;
    }
}
