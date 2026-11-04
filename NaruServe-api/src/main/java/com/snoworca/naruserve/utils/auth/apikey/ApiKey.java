package com.snoworca.naruserve.utils.auth.apikey;

import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * An API key as the app sees it: the metadata of a {@link StoredApiKey} without any secret or secret hash, plus the
 * {@link ApiKeyStatus} auth computed. Serialising a listing can therefore never leak a hash. Times are epoch
 * milliseconds. Kotlin reads it with property syntax ({@code key.expiresAt} is {@code Long?}).
 */
// @req DR-APIKEY-001
public final class ApiKey {

    private final String id;
    private final String prefix;
    private final String ownerUserId;
    private final long ownerVer;
    private final String name;
    private final Set<String> scopes;
    private final @Nullable Long expiresAt;
    private final long createdAt;
    private final @Nullable Long lastUsedAt;
    private final @Nullable String issuedByUserId;
    private final ApiKeyStatus status;

    private ApiKey(StoredApiKey k, ApiKeyStatus status) {
        this.id = k.getId();
        this.prefix = k.getPrefix();
        this.ownerUserId = k.getOwnerUserId();
        this.ownerVer = k.getOwnerVer();
        this.name = k.getName();
        this.scopes = k.getScopes();
        this.expiresAt = k.getExpiresAt();
        this.createdAt = k.getCreatedAt();
        this.lastUsedAt = k.getLastUsedAt();
        this.issuedByUserId = k.getIssuedByUserId();
        this.status = status;
    }

    /** The app-facing copy of {@code stored} with {@code status}; the secret hash is not copied. */
    // @req DR-APIKEY-001
    public static ApiKey of(StoredApiKey stored, ApiKeyStatus status) {
        return new ApiKey(Objects.requireNonNull(stored, "stored"), Objects.requireNonNull(status, "status"));
    }

    /** The key id. */
    public String getId() {
        return id;
    }

    /** The 8 prefix characters, the only part of a key that may be shown or logged. */
    public String getPrefix() {
        return prefix;
    }

    /** The owner's user id. */
    public String getOwnerUserId() {
        return ownerUserId;
    }

    /** The owner's {@code ver} when the key was issued. */
    public long getOwnerVer() {
        return ownerVer;
    }

    /** The key's name. */
    public String getName() {
        return name;
    }

    /** The key's scopes. */
    public Set<String> getScopes() {
        return scopes;
    }

    /** When the key expires, or null for no expiry. */
    public @Nullable Long getExpiresAt() {
        return expiresAt;
    }

    /** When the key was issued. */
    public long getCreatedAt() {
        return createdAt;
    }

    /** When the key last authenticated (best effort), or null. */
    public @Nullable Long getLastUsedAt() {
        return lastUsedAt;
    }

    /** The user who issued the key, or null. */
    public @Nullable String getIssuedByUserId() {
        return issuedByUserId;
    }

    /** The status auth computed for this listing. */
    public ApiKeyStatus getStatus() {
        return status;
    }

    @Override
    public String toString() {
        return "ApiKey{id=" + id + ", key=nk_" + prefix + "_***, owner=" + ownerUserId + ", name=" + name
                + ", scopes=" + scopes + ", status=" + status + "}";
    }
}
