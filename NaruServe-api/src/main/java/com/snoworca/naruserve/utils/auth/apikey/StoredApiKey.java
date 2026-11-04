package com.snoworca.naruserve.utils.auth.apikey;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * One API key row as an {@link ApiKeyStore} holds it, the only type carrying the secret hash. A custom store builds
 * rows with {@link #builder()} and changes them with {@link #toBuilder()}, as {@code AuthUser} does; fields added in
 * later versions arrive as builder methods only. Times are epoch milliseconds. {@link #toString()} shows the key as
 * {@code nk_<prefix>} and never the secret hash.
 */
// @req DR-APIKEY-001
public final class StoredApiKey {

    private final String id;
    private final String prefix;
    private final String secretHash;
    private final String ownerUserId;
    private final long ownerVer;
    private final String name;
    private final Set<String> scopes;
    private final @Nullable Long expiresAt;
    private final long createdAt;
    private final @Nullable Long lastUsedAt;
    private final @Nullable String issuedByUserId;

    private StoredApiKey(Builder b, String id, String prefix, String secretHash, String ownerUserId, long ownerVer,
            String name, Set<String> scopes, long createdAt) {
        this.id = id;
        this.prefix = prefix;
        this.secretHash = secretHash;
        this.ownerUserId = ownerUserId;
        this.ownerVer = ownerVer;
        this.name = name;
        this.scopes = scopes;
        this.expiresAt = b.expiresAt;
        this.createdAt = createdAt;
        this.lastUsedAt = b.lastUsedAt;
        this.issuedByUserId = b.issuedByUserId;
    }

    /** An empty builder; {@link Builder#build()} needs every required field. */
    // @req DR-APIKEY-001
    public static Builder builder() {
        return new Builder();
    }

    /** A builder holding every field of this row. */
    // @req DR-APIKEY-001
    public Builder toBuilder() {
        return new Builder().id(id).prefix(prefix).secretHash(secretHash).ownerUserId(ownerUserId).ownerVer(ownerVer)
                .name(name).scopes(scopes).expiresAt(expiresAt).createdAt(createdAt).lastUsedAt(lastUsedAt)
                .issuedByUserId(issuedByUserId);
    }

    /** The key id. */
    public String getId() {
        return id;
    }

    /** The 8 prefix characters ({@code nk_<prefix>_...}), the case-sensitive unique lookup key. */
    public String getPrefix() {
        return prefix;
    }

    /** The lower-case hex SHA-256 of the secret, 64 characters; never logged or returned to the app. */
    public String getSecretHash() {
        return secretHash;
    }

    /** The owner's user id. */
    public String getOwnerUserId() {
        return ownerUserId;
    }

    /** The owner's token version ({@code ver}) when the key was issued. */
    public long getOwnerVer() {
        return ownerVer;
    }

    /** The key's name. */
    public String getName() {
        return name;
    }

    /** The scopes the key may use, unmodifiable. */
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

    /** The user who issued the key (the owner for a personal key, an admin otherwise), or null. */
    public @Nullable String getIssuedByUserId() {
        return issuedByUserId;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (!(o instanceof StoredApiKey)) {
            return false;
        }
        StoredApiKey k = (StoredApiKey) o;
        return id.equals(k.id) && prefix.equals(k.prefix) && secretHash.equals(k.secretHash)
                && ownerUserId.equals(k.ownerUserId) && ownerVer == k.ownerVer && name.equals(k.name)
                && scopes.equals(k.scopes) && Objects.equals(expiresAt, k.expiresAt) && createdAt == k.createdAt
                && Objects.equals(lastUsedAt, k.lastUsedAt) && Objects.equals(issuedByUserId, k.issuedByUserId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, prefix, ownerUserId, ownerVer, name, scopes, expiresAt, createdAt);
    }

    @Override
    public String toString() {
        return "StoredApiKey{id=" + id + ", key=nk_" + prefix + "_***, owner=" + ownerUserId + ", ownerVer="
                + ownerVer + ", name=" + name + ", scopes=" + scopes + ", expiresAt=" + expiresAt + "}";
    }

    /** Collects the fields of a {@link StoredApiKey}. */
    public static final class Builder {
        private @Nullable String id;
        private @Nullable String prefix;
        private @Nullable String secretHash;
        private @Nullable String ownerUserId;
        private @Nullable Long ownerVer;
        private @Nullable String name;
        private @Nullable Set<String> scopes;
        private @Nullable Long expiresAt;
        private @Nullable Long createdAt;
        private @Nullable Long lastUsedAt;
        private @Nullable String issuedByUserId;

        private Builder() {
        }

        /** The key id; required. */
        public Builder id(String id) {
            this.id = Objects.requireNonNull(id, "id");
            return this;
        }

        /** The 8 prefix characters; required. */
        public Builder prefix(String prefix) {
            this.prefix = Objects.requireNonNull(prefix, "prefix");
            return this;
        }

        /** The lower-case hex SHA-256 of the secret (64 characters); required. */
        public Builder secretHash(String secretHash) {
            this.secretHash = Objects.requireNonNull(secretHash, "secretHash");
            return this;
        }

        /** The owner's user id; required. */
        public Builder ownerUserId(String ownerUserId) {
            this.ownerUserId = Objects.requireNonNull(ownerUserId, "ownerUserId");
            return this;
        }

        /** The owner's {@code ver} at issuance; required. */
        public Builder ownerVer(long ownerVer) {
            this.ownerVer = ownerVer;
            return this;
        }

        /** The key's name; required. */
        public Builder name(String name) {
            this.name = Objects.requireNonNull(name, "name");
            return this;
        }

        /** The key's scopes; required (may be empty). */
        public Builder scopes(Collection<String> scopes) {
            List<String> copy = new ArrayList<>();
            for (String s : scopes) {
                copy.add(Objects.requireNonNull(s, "scope"));
            }
            this.scopes = Collections.unmodifiableSet(new LinkedHashSet<>(copy));
            return this;
        }

        /** The expiry, or null for none. */
        public Builder expiresAt(@Nullable Long expiresAt) {
            this.expiresAt = expiresAt;
            return this;
        }

        /** The issuance time; required. */
        public Builder createdAt(long createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        /** The last authentication time, or null. */
        public Builder lastUsedAt(@Nullable Long lastUsedAt) {
            this.lastUsedAt = lastUsedAt;
            return this;
        }

        /** The issuing user, or null. */
        public Builder issuedByUserId(@Nullable String issuedByUserId) {
            this.issuedByUserId = issuedByUserId;
            return this;
        }

        /** The row; {@link IllegalStateException} naming the first missing required field or a malformed hash. */
        // @req DR-APIKEY-001
        public StoredApiKey build() {
            String i = require(id, "id");
            String p = require(prefix, "prefix");
            String h = require(secretHash, "secretHash");
            String o = require(ownerUserId, "ownerUserId");
            long v = require(ownerVer, "ownerVer");
            String n = require(name, "name");
            Set<String> s = require(scopes, "scopes");
            long c = require(createdAt, "createdAt");
            if (h.length() != 64) {
                throw new IllegalStateException("secretHash is 64 lower-case hex characters");
            }
            for (int k = 0; k < h.length(); k++) {
                char ch = h.charAt(k);
                if (!(ch >= '0' && ch <= '9' || ch >= 'a' && ch <= 'f')) {
                    throw new IllegalStateException("secretHash is 64 lower-case hex characters");
                }
            }
            return new StoredApiKey(this, i, p, h, o, v, n, s, c);
        }

        private static <T> T require(@Nullable T value, String field) {
            if (value == null) {
                throw new IllegalStateException("a StoredApiKey needs " + field);
            }
            return value;
        }
    }
}
