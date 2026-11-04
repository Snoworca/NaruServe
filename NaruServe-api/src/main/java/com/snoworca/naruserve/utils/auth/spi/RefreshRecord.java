package com.snoworca.naruserve.utils.auth.spi;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One refresh token as a {@link RefreshTokenStore} keeps it: its id ({@code jti}), its family (every token rotated
 * from one login), the user, the hash of the token, and when it was issued, expires, was used (rotated) and whether
 * its family was revoked. Times are epoch milliseconds. The token itself is never stored.
 */
// @req FR-TOKEN-002
public final class RefreshRecord {

    private final String tokenId;
    private final String familyId;
    private final String userId;
    private final String tokenHash;
    private final long createdAt;
    private final long expiresAt;
    private final @Nullable Long usedAt;
    private final boolean revoked;

    private RefreshRecord(Builder b, String tokenId, String familyId, String userId, String tokenHash, long createdAt,
            long expiresAt) {
        this.tokenId = tokenId;
        this.familyId = familyId;
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.usedAt = b.usedAt;
        this.revoked = b.revoked;
    }

    /** An empty builder; {@link Builder#build()} needs every field except usedAt and revoked. */
    // @req FR-TOKEN-002
    public static Builder builder() {
        return new Builder();
    }

    /** A builder holding every field of this record. */
    public Builder toBuilder() {
        return new Builder().tokenId(tokenId).familyId(familyId).userId(userId).tokenHash(tokenHash)
                .createdAt(createdAt).expiresAt(expiresAt).usedAt(usedAt).revoked(revoked);
    }

    /** The token id ({@code jti}). */
    public String getTokenId() {
        return tokenId;
    }

    /** The family id: one per login, kept by every rotation. */
    public String getFamilyId() {
        return familyId;
    }

    /** The user id. */
    public String getUserId() {
        return userId;
    }

    /** The lower-case hex SHA-256 of the token. */
    public String getTokenHash() {
        return tokenHash;
    }

    /** When the token was issued. */
    public long getCreatedAt() {
        return createdAt;
    }

    /** When the token expires. */
    public long getExpiresAt() {
        return expiresAt;
    }

    /** When the token was used (rotated), or null while unused. */
    public @Nullable Long getUsedAt() {
        return usedAt;
    }

    /** True once the token's family was revoked. */
    public boolean isRevoked() {
        return revoked;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (!(o instanceof RefreshRecord)) {
            return false;
        }
        RefreshRecord r = (RefreshRecord) o;
        return tokenId.equals(r.tokenId) && familyId.equals(r.familyId) && userId.equals(r.userId)
                && tokenHash.equals(r.tokenHash) && createdAt == r.createdAt && expiresAt == r.expiresAt
                && Objects.equals(usedAt, r.usedAt) && revoked == r.revoked;
    }

    @Override
    public int hashCode() {
        return Objects.hash(tokenId, familyId, userId, expiresAt);
    }

    @Override
    public String toString() {
        return "RefreshRecord{jti=" + tokenId + ", family=" + familyId + ", user=" + userId + ", expiresAt="
                + expiresAt + ", usedAt=" + usedAt + ", revoked=" + revoked + "}";
    }

    /** Collects the fields of a {@link RefreshRecord}. */
    public static final class Builder {
        private @Nullable String tokenId;
        private @Nullable String familyId;
        private @Nullable String userId;
        private @Nullable String tokenHash;
        private @Nullable Long createdAt;
        private @Nullable Long expiresAt;
        private @Nullable Long usedAt;
        private boolean revoked;

        private Builder() {
        }

        /** The token id; required. */
        public Builder tokenId(String tokenId) {
            this.tokenId = Objects.requireNonNull(tokenId, "tokenId");
            return this;
        }

        /** The family id; required. */
        public Builder familyId(String familyId) {
            this.familyId = Objects.requireNonNull(familyId, "familyId");
            return this;
        }

        /** The user id; required. */
        public Builder userId(String userId) {
            this.userId = Objects.requireNonNull(userId, "userId");
            return this;
        }

        /** The token hash; required. */
        public Builder tokenHash(String tokenHash) {
            this.tokenHash = Objects.requireNonNull(tokenHash, "tokenHash");
            return this;
        }

        /** The issuance time; required. */
        public Builder createdAt(long createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        /** The expiry; required. */
        public Builder expiresAt(long expiresAt) {
            this.expiresAt = expiresAt;
            return this;
        }

        /** When the token was used, or null. */
        public Builder usedAt(@Nullable Long usedAt) {
            this.usedAt = usedAt;
            return this;
        }

        /** Whether the family was revoked; false when not called. */
        public Builder revoked(boolean revoked) {
            this.revoked = revoked;
            return this;
        }

        /** The record; {@link IllegalStateException} naming the first missing required field. */
        public RefreshRecord build() {
            return new RefreshRecord(this, require(tokenId, "tokenId"), require(familyId, "familyId"),
                    require(userId, "userId"), require(tokenHash, "tokenHash"), require(createdAt, "createdAt"),
                    require(expiresAt, "expiresAt"));
        }

        private static <T> T require(@Nullable T value, String field) {
            if (value == null) {
                throw new IllegalStateException("a RefreshRecord needs " + field);
            }
            return value;
        }
    }
}
