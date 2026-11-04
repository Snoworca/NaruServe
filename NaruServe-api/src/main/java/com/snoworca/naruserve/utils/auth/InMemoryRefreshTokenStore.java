package com.snoworca.naruserve.utils.auth;

import com.snoworca.naruserve.utils.auth.spi.RefreshRecord;
import com.snoworca.naruserve.utils.auth.spi.RefreshTokenStore;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Refresh-token records in memory, lost on restart (every user then logs in again): the default beside an
 * {@code InMemoryUserStore} and for tests. Thread-safe; {@link #markUsed} is atomic. Not shared between nodes, so
 * {@code Deployment.MULTI_INSTANCE} refuses it.
 */
// @req FR-TOKEN-002
public final class InMemoryRefreshTokenStore implements RefreshTokenStore {

    private final Map<String, RefreshRecord> records = new LinkedHashMap<>();

    /** An empty store. */
    // @req FR-TOKEN-002
    public InMemoryRefreshTokenStore() {
    }

    @Override
    public synchronized void save(RefreshRecord record) {
        Objects.requireNonNull(record, "record");
        if (records.containsKey(record.getTokenId())) {
            throw new IllegalArgumentException("token id " + record.getTokenId() + " is stored already");
        }
        records.put(record.getTokenId(), record);
    }

    @Override
    public synchronized @Nullable RefreshRecord find(String tokenId) {
        return records.get(tokenId);
    }

    // @req FR-TOKEN-002
    @Override
    public synchronized boolean markUsed(String tokenId, long nowMillis) {
        RefreshRecord r = records.get(tokenId);
        if (r == null || r.getUsedAt() != null || r.isRevoked()) {
            return false;
        }
        records.put(tokenId, r.toBuilder().usedAt(nowMillis).build());
        return true;
    }

    @Override
    public synchronized int revokeFamily(String familyId) {
        int n = 0;
        for (Map.Entry<String, RefreshRecord> e : records.entrySet()) {
            RefreshRecord r = e.getValue();
            if (r.getFamilyId().equals(familyId) && !r.isRevoked()) {
                e.setValue(r.toBuilder().revoked(true).build());
                n++;
            }
        }
        return n;
    }

    // @req FR-TOKEN-002
    @Override
    public synchronized int purgeExpired(long nowMillis) {
        int n = 0;
        for (Iterator<RefreshRecord> it = records.values().iterator(); it.hasNext(); ) {
            if (it.next().getExpiresAt() <= nowMillis) {
                it.remove();
                n++;
            }
        }
        return n;
    }

    @Override
    public synchronized String toString() {
        return getClass().getSimpleName() + "{records=" + records.size() + "}";
    }
}
