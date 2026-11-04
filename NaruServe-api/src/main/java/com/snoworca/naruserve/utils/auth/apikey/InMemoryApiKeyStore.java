package com.snoworca.naruserve.utils.auth.apikey;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * API keys in memory, lost on restart: for a single instance and for tests, {@code .apiKeys(new InMemoryApiKeyStore())}.
 * Thread-safe (every method holds the store's monitor, so the limited insertion is atomic on this node). The prefix is
 * unique and compared case-sensitively ({@code aBcd1234} and {@code Abcd1234} are two keys). Not shared between nodes,
 * so {@code Deployment.MULTI_INSTANCE} refuses it. Express counterpart: a {@code Map} of hashed keys in a demo server.
 */
// @req FR-APIKEY-007
public final class InMemoryApiKeyStore implements ApiKeyStore {

    private final Map<String, StoredApiKey> byId = new LinkedHashMap<>();
    private final Map<String, String> idByPrefix = new LinkedHashMap<>();

    /** An empty store. */
    // @req FR-APIKEY-007
    public InMemoryApiKeyStore() {
    }

    @Override
    public synchronized @Nullable StoredApiKey findByPrefix(String prefix) {
        String id = idByPrefix.get(prefix);
        return id == null ? null : byId.get(id);
    }

    @Override
    public synchronized @Nullable StoredApiKey findById(String keyId) {
        return byId.get(keyId);
    }

    @Override
    public synchronized List<StoredApiKey> listByOwner(String ownerUserId) {
        List<StoredApiKey> out = new ArrayList<>();
        for (StoredApiKey k : byId.values()) {
            if (k.getOwnerUserId().equals(ownerUserId)) {
                out.add(k);
            }
        }
        return out;
    }

    // @req FR-APIKEY-007
    @Override
    public synchronized boolean insertIfUnderLimit(StoredApiKey key, int maxLiveKeys, long nowMillis) {
        if (idByPrefix.containsKey(key.getPrefix())) {
            throw new ApiKeyPrefixCollisionException("an API key with this prefix exists");
        }
        int live = 0;
        for (StoredApiKey k : byId.values()) {
            Long exp = k.getExpiresAt();
            if (k.getOwnerUserId().equals(key.getOwnerUserId()) && (exp == null || exp > nowMillis)
                    && k.getOwnerVer() >= key.getOwnerVer()) {
                live++;
            }
        }
        if (live >= maxLiveKeys) {
            return false;
        }
        byId.put(key.getId(), key);
        idByPrefix.put(key.getPrefix(), key.getId());
        return true;
    }

    @Override
    public synchronized boolean deleteByIdAndOwner(String keyId, String ownerUserId) {
        StoredApiKey k = byId.get(keyId);
        return k != null && k.getOwnerUserId().equals(ownerUserId) && deleteById(keyId);
    }

    @Override
    public synchronized boolean deleteById(String keyId) {
        StoredApiKey k = byId.remove(keyId);
        if (k == null) {
            return false;
        }
        idByPrefix.remove(k.getPrefix());
        return true;
    }

    @Override
    public synchronized int deleteAllByOwner(String ownerUserId) {
        return deleteWhere(ownerUserId, Long.MIN_VALUE, Long.MAX_VALUE);
    }

    @Override
    public synchronized int deleteStaleByOwner(String ownerUserId, long currentOwnerVer) {
        return deleteWhere(ownerUserId, Long.MIN_VALUE, currentOwnerVer - 1);
    }

    @Override
    public synchronized int deleteFutureByOwner(String ownerUserId, long currentOwnerVer) {
        return currentOwnerVer == Long.MAX_VALUE ? 0 : deleteWhere(ownerUserId, currentOwnerVer + 1, Long.MAX_VALUE);
    }

    /** Deletes the owner's keys whose ownerVer lies in {@code from..to}; how many. */
    private int deleteWhere(String ownerUserId, long from, long to) {
        int n = 0;
        for (Iterator<StoredApiKey> it = byId.values().iterator(); it.hasNext();) {
            StoredApiKey k = it.next();
            if (k.getOwnerUserId().equals(ownerUserId) && k.getOwnerVer() >= from && k.getOwnerVer() <= to) {
                it.remove();
                idByPrefix.remove(k.getPrefix());
                n++;
            }
        }
        return n;
    }

    @Override
    public synchronized int deleteExpired(long nowMillis) {
        int n = 0;
        for (Iterator<StoredApiKey> it = byId.values().iterator(); it.hasNext();) {
            StoredApiKey k = it.next();
            Long exp = k.getExpiresAt();
            if (exp != null && exp <= nowMillis) {
                it.remove();
                idByPrefix.remove(k.getPrefix());
                n++;
            }
        }
        return n;
    }

    @Override
    public synchronized void updateLastUsed(String keyId, long lastUsedAtMillis) {
        StoredApiKey k = byId.get(keyId);
        if (k != null) {
            byId.put(keyId, k.toBuilder().lastUsedAt(lastUsedAtMillis).build());
        }
    }

    @Override
    public synchronized int countKeysWithAdminScopes() {
        int n = 0;
        for (StoredApiKey k : byId.values()) {
            for (String s : k.getScopes()) {
                if (s.startsWith("auth-")) {
                    n++;
                    break;
                }
            }
        }
        return n;
    }

    @Override
    public String toString() {
        return "InMemoryApiKeyStore";
    }
}
