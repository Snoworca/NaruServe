package com.snoworca.naruserve.utils.auth.apikey;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * API key rows, implemented by the app over its own table or by a shipped store. Every store call is synchronous
 * and may throw; auth turns a failure of a call the response depends on into 503 JSON and only logs a failure off the
 * request path. Arguments are never null.
 *
 * <p>Contract:
 * <ul>
 *   <li>The prefix has a case-sensitive unique index; {@link #insertIfUnderLimit} throws
 *   {@link ApiKeyPrefixCollisionException} for an existing prefix.</li>
 *   <li>A live key is one that is unexpired at {@code nowMillis} and whose ownerVer is at least the inserted key's
 *   ownerVer; {@link #insertIfUnderLimit} inserts only while the owner has fewer than {@code maxLiveKeys} of
 *   them.</li>
 *   <li>Under {@code Deployment.MULTI_INSTANCE} the limit and prefix check must be atomic across nodes, declared by
 *   {@link #supportsAtomicLimitInsert()}.</li>
 *   <li>{@link #listByOwner} returns dead keys too; auth attaches the status.</li>
 * </ul>
 *
 * <p>After the first public release this interface gains default methods only.
 */
// @req IR-APIKEY-004
public interface ApiKeyStore {

    /** The key with this prefix (compared byte for byte), or null. */
    @Nullable StoredApiKey findByPrefix(String prefix) throws Exception;

    /** The key with this id, or null. */
    @Nullable StoredApiKey findById(String keyId) throws Exception;

    /** Every key of the owner, dead keys included; empty when there is none. */
    List<StoredApiKey> listByOwner(String ownerUserId) throws Exception;

    /**
     * Inserts {@code key} when its owner has fewer than {@code maxLiveKeys} live keys at {@code nowMillis} (unexpired,
     * ownerVer at least {@code key.getOwnerVer()}) and returns true; otherwise inserts nothing and returns false.
     * Throws {@link ApiKeyPrefixCollisionException} when the prefix exists.
     */
    boolean insertIfUnderLimit(StoredApiKey key, int maxLiveKeys, long nowMillis) throws Exception;

    /** Deletes the key when it belongs to {@code ownerUserId}; false when it is missing or someone else's. */
    boolean deleteByIdAndOwner(String keyId, String ownerUserId) throws Exception;

    /** Deletes the key; false when it is missing. */
    boolean deleteById(String keyId) throws Exception;

    /** Deletes every key of the owner and returns how many; idempotent. */
    int deleteAllByOwner(String ownerUserId) throws Exception;

    /** Deletes the owner's keys whose ownerVer is lower than {@code currentOwnerVer}; returns how many. */
    int deleteStaleByOwner(String ownerUserId, long currentOwnerVer) throws Exception;

    /**
     * Deletes the owner's keys whose ownerVer is higher than {@code currentOwnerVer} and returns how many; on a healthy
     * store (ver only grows) this is always 0. The default reads {@link #listByOwner} and deletes each such key with
     * {@link #deleteById}; a store with SQL overrides it with one statement.
     */
    // @req IR-APIKEY-004
    default int deleteFutureByOwner(String ownerUserId, long currentOwnerVer) throws Exception {
        int deleted = 0;
        for (StoredApiKey k : listByOwner(ownerUserId)) {
            if (k.getOwnerVer() > currentOwnerVer && deleteById(k.getId())) {
                deleted++;
            }
        }
        return deleted;
    }

    /** Deletes the keys whose expiresAt is not null and at most {@code nowMillis}; returns how many. */
    int deleteExpired(long nowMillis) throws Exception;

    /** Records the last authentication time of a key, best effort and off the request path; the default does nothing. */
    // @req IR-APIKEY-004
    default void updateLastUsed(String keyId, long lastUsedAtMillis) throws Exception {
    }

    /** True when {@link #insertIfUnderLimit} is atomic across nodes; the default is false. */
    // @req IR-APIKEY-004
    default boolean supportsAtomicLimitInsert() {
        return false;
    }

    /**
     * The number of keys (dead ones included) naming a scope whose resource starts with {@code auth-}; a key whose only
     * scope is {@code *} is not counted. The default is -1: the store cannot count.
     */
    // @req IR-APIKEY-004
    default int countKeysWithAdminScopes() throws Exception {
        return -1;
    }
}
