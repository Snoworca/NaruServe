package com.snoworca.naruserve.utils.auth.testing;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.utils.auth.apikey.ApiKeyPrefixCollisionException;
import com.snoworca.naruserve.utils.auth.apikey.ApiKeyStore;
import com.snoworca.naruserve.utils.auth.apikey.StoredApiKey;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.Test;

/**
 * AuthTesting.verifyApiKeyStore (FR-APIKEY-009): a correct in-memory store passes, and each deliberately broken
 * variant fails with an AssertionError naming the broken method.
 */
public class VerifyApiKeyStoreTest {

    /** What a variant gets wrong. */
    enum Bug {
        NONE, DROPS_ISSUED_BY, CASE_INSENSITIVE_PREFIX, OVERWRITES_PREFIX, COUNTS_STALE, COUNTS_EXPIRED,
        DELETE_IGNORES_OWNER, DELETE_ALL_KEEPS_ONE, EXPIRED_STRICT, STALE_NOT_EQUAL, FUTURE_GREATER_EQUAL,
        NOT_ATOMIC, THROWS_ON_INSERT, ABSENT_PREFIX_RETURNS_A_ROW, FILLS_NULL_EXPIRES_AT, FILLS_NULL_LAST_USED,
        FILLS_NULL_ISSUED_BY
    }

    /** One storage shared by any number of {@link MemStore} instances. */
    static final class Rows {
        final Map<String, StoredApiKey> byId = new LinkedHashMap<>();
    }

    /** A reference in-memory store over {@link Rows}, broken as {@link Bug} says. */
    static final class MemStore implements ApiKeyStore {
        private final Rows rows;
        private final Bug bug;

        MemStore(Rows rows, Bug bug) {
            this.rows = rows;
            this.bug = bug;
        }

        private boolean samePrefix(String a, String b) {
            return bug == Bug.CASE_INSENSITIVE_PREFIX ? a.toLowerCase(Locale.ROOT).equals(b.toLowerCase(Locale.ROOT))
                    : a.equals(b);
        }

        @Override
        public StoredApiKey findByPrefix(String prefix) {
            synchronized (rows) {
                for (StoredApiKey k : rows.byId.values()) {
                    if (samePrefix(k.getPrefix(), prefix)) {
                        return k;
                    }
                }
                if (bug == Bug.ABSENT_PREFIX_RETURNS_A_ROW && !rows.byId.isEmpty()) {
                    return rows.byId.values().iterator().next();
                }
                return null;
            }
        }

        /** The row this variant really stores for {@code key}. */
        private StoredApiKey stored(StoredApiKey key) {
            if (bug == Bug.DROPS_ISSUED_BY) {
                return key.toBuilder().issuedByUserId(null).build();
            }
            if (bug == Bug.FILLS_NULL_EXPIRES_AT && key.getExpiresAt() == null) {
                return key.toBuilder().expiresAt(Long.MAX_VALUE).build();
            }
            if (bug == Bug.FILLS_NULL_LAST_USED && key.getLastUsedAt() == null) {
                return key.toBuilder().lastUsedAt(key.getCreatedAt()).build();
            }
            if (bug == Bug.FILLS_NULL_ISSUED_BY && key.getIssuedByUserId() == null) {
                return key.toBuilder().issuedByUserId(key.getOwnerUserId()).build();
            }
            return key;
        }

        @Override
        public StoredApiKey findById(String keyId) {
            synchronized (rows) {
                return rows.byId.get(keyId);
            }
        }

        @Override
        public List<StoredApiKey> listByOwner(String ownerUserId) {
            synchronized (rows) {
                List<StoredApiKey> out = new ArrayList<>();
                for (StoredApiKey k : rows.byId.values()) {
                    if (k.getOwnerUserId().equals(ownerUserId)) {
                        out.add(k);
                    }
                }
                return out;
            }
        }

        private int live(StoredApiKey key, long now) {
            int n = 0;
            for (StoredApiKey k : rows.byId.values()) {
                boolean expired = k.getExpiresAt() != null && k.getExpiresAt() <= now;
                boolean stale = k.getOwnerVer() < key.getOwnerVer();
                if (k.getOwnerUserId().equals(key.getOwnerUserId())
                        && (!expired || bug == Bug.COUNTS_EXPIRED) && (!stale || bug == Bug.COUNTS_STALE)) {
                    n++;
                }
            }
            return n;
        }

        @Override
        public boolean insertIfUnderLimit(StoredApiKey key, int maxLiveKeys, long nowMillis) throws Exception {
            if (bug == Bug.THROWS_ON_INSERT) {
                throw new SQLException("deadlock found");
            }
            if (bug == Bug.NOT_ATOMIC) {
                int n;
                synchronized (rows) {
                    for (StoredApiKey k : rows.byId.values()) {
                        if (k.getPrefix().equals(key.getPrefix())) {
                            throw new ApiKeyPrefixCollisionException("prefix exists");
                        }
                    }
                    n = live(key, nowMillis);
                }
                Thread.sleep(15);
                if (n >= maxLiveKeys) {
                    return false;
                }
                synchronized (rows) {
                    rows.byId.put(key.getId(), key);
                }
                return true;
            }
            synchronized (rows) {
                if (bug != Bug.OVERWRITES_PREFIX) {
                    for (StoredApiKey k : rows.byId.values()) {
                        if (samePrefix(k.getPrefix(), key.getPrefix())) {
                            throw new ApiKeyPrefixCollisionException("prefix exists");
                        }
                    }
                }
                if (live(key, nowMillis) >= maxLiveKeys) {
                    return false;
                }
                rows.byId.put(key.getId(), stored(key));
                return true;
            }
        }

        @Override
        public boolean deleteByIdAndOwner(String keyId, String ownerUserId) {
            synchronized (rows) {
                StoredApiKey k = rows.byId.get(keyId);
                if (k == null || !k.getOwnerUserId().equals(ownerUserId) && bug != Bug.DELETE_IGNORES_OWNER) {
                    return false;
                }
                rows.byId.remove(keyId);
                return true;
            }
        }

        @Override
        public boolean deleteById(String keyId) {
            synchronized (rows) {
                return rows.byId.remove(keyId) != null;
            }
        }

        @Override
        public int deleteAllByOwner(String ownerUserId) {
            synchronized (rows) {
                int n = 0;
                boolean kept = false;
                for (Iterator<StoredApiKey> it = rows.byId.values().iterator(); it.hasNext(); ) {
                    StoredApiKey k = it.next();
                    if (k.getOwnerUserId().equals(ownerUserId)) {
                        if (bug == Bug.DELETE_ALL_KEEPS_ONE && !kept) {
                            kept = true;
                            continue;
                        }
                        it.remove();
                        n++;
                    }
                }
                return n;
            }
        }

        private int deleteWhere(String owner, long v, int sign) {
            synchronized (rows) {
                int n = 0;
                for (Iterator<StoredApiKey> it = rows.byId.values().iterator(); it.hasNext(); ) {
                    StoredApiKey k = it.next();
                    long kv = k.getOwnerVer();
                    boolean hit;
                    if (sign < 0) {
                        hit = bug == Bug.STALE_NOT_EQUAL ? kv != v : kv < v;
                    } else {
                        hit = bug == Bug.FUTURE_GREATER_EQUAL ? kv >= v : kv > v;
                    }
                    if (k.getOwnerUserId().equals(owner) && hit) {
                        it.remove();
                        n++;
                    }
                }
                return n;
            }
        }

        @Override
        public int deleteStaleByOwner(String ownerUserId, long currentOwnerVer) {
            return deleteWhere(ownerUserId, currentOwnerVer, -1);
        }

        @Override
        public int deleteFutureByOwner(String ownerUserId, long currentOwnerVer) {
            return deleteWhere(ownerUserId, currentOwnerVer, 1);
        }

        @Override
        public int deleteExpired(long nowMillis) {
            synchronized (rows) {
                int n = 0;
                for (Iterator<StoredApiKey> it = rows.byId.values().iterator(); it.hasNext(); ) {
                    Long e = it.next().getExpiresAt();
                    if (e != null && (bug == Bug.EXPIRED_STRICT ? e < nowMillis : e <= nowMillis)) {
                        it.remove();
                        n++;
                    }
                }
                return n;
            }
        }
    }

    private static MemStore store(Bug bug) {
        return new MemStore(new Rows(), bug);
    }

    private static void failsNaming(Bug bug, String method) {
        try {
            AuthTesting.verifyApiKeyStore(store(bug));
        } catch (AssertionError e) {
            assertTrue(bug + ": message must name " + method + ": " + e.getMessage(),
                    String.valueOf(e.getMessage()).contains(method));
            return;
        }
        fail(bug + ": verifyApiKeyStore passed a broken store");
    }

    /** Both instances share one storage; the single-store checks pass for both bugs, only the race can fail. */
    private static void concurrentFailsNaming(Bug bugA, Bug bugB, String method) {
        Rows rows = new Rows();
        try {
            AuthTesting.verifyApiKeyStore(new MemStore(rows, bugA), new MemStore(rows, bugB));
        } catch (AssertionError e) {
            assertTrue(bugA + "/" + bugB + ": message must name " + method + ": " + e.getMessage(),
                    String.valueOf(e.getMessage()).contains(method));
            return;
        }
        fail(bugA + "/" + bugB + ": verifyApiKeyStore(a, b) passed a broken pair");
    }

    // a correct store passes both overloads, repeatedly on the same storage
    @Test
    public void correctStorePasses() {
        Rows rows = new Rows();
        for (int i = 0; i < 3; i++) {
            AuthTesting.verifyApiKeyStore(new MemStore(rows, Bug.NONE));
            AuthTesting.verifyApiKeyStore(new MemStore(rows, Bug.NONE), new MemStore(rows, Bug.NONE));
        }
    }

    // FR-APIKEY-009 AC-1: field round trip with null expiresAt, lastUsedAt and issuedByUserId, absent prefix null,
    // existing prefix ApiKeyPrefixCollisionException, aB... and Ab... separate rows
    @Test
    public void FR_APIKEY_009_AC1_singleStoreLookupRules() {
        failsNaming(Bug.DROPS_ISSUED_BY, "issuedByUserId");
        failsNaming(Bug.OVERWRITES_PREFIX, "ApiKeyPrefixCollisionException");
        failsNaming(Bug.CASE_INSENSITIVE_PREFIX, "findByPrefix");
    }

    // FR-APIKEY-009 AC-1: a store that does not keep null expiresAt, lastUsedAt or issuedByUserId as null, or that answers
    // an absent prefix with a row, fails with the broken rule named
    @Test
    public void FR_APIKEY_009_AC1_nullFieldsAndAnAbsentPrefix() {
        failsNaming(Bug.FILLS_NULL_EXPIRES_AT, "round-trip expiresAt");
        failsNaming(Bug.FILLS_NULL_LAST_USED, "round-trip lastUsedAt");
        failsNaming(Bug.FILLS_NULL_ISSUED_BY, "round-trip issuedByUserId");
        failsNaming(Bug.ABSENT_PREFIX_RETURNS_A_ROW, "findByPrefix of an absent prefix must return null");
    }

    // FR-APIKEY-009 AC-2: limit counting excludes expired and lower-ownerVer keys, deleteByIdAndOwner with another
    // owner is false, deleteAllByOwner removes all, deleteExpired removes expired keys
    @Test
    public void FR_APIKEY_009_AC2_singleStoreLimitAndDeleteRules() {
        failsNaming(Bug.COUNTS_STALE, "insertIfUnderLimit");
        failsNaming(Bug.COUNTS_EXPIRED, "insertIfUnderLimit");
        failsNaming(Bug.DELETE_IGNORES_OWNER, "deleteByIdAndOwner");
        failsNaming(Bug.DELETE_ALL_KEEPS_ONE, "deleteAllByOwner");
        failsNaming(Bug.EXPIRED_STRICT, "deleteExpired");
    }

    // FR-APIKEY-009 AC-3: ownerVer 4, 5, 6: deleteStaleByOwner(u, 5) deletes only the 4 key and returns 1;
    // deleteFutureByOwner(u, 5) on a fresh set deletes only the 6 key and returns 1
    @Test
    public void FR_APIKEY_009_AC3_staleAndFutureDeletes() {
        failsNaming(Bug.STALE_NOT_EQUAL, "deleteStaleByOwner");
        failsNaming(Bug.FUTURE_GREATER_EQUAL, "deleteFutureByOwner");
    }

    // FR-APIKEY-009 AC-4: 8 threads split over a and b insert at limit-1: exactly one true, the rest false, no
    // exception, live count equal to the limit
    @Test
    public void FR_APIKEY_009_AC4_concurrentLimitInsert() {
        concurrentFailsNaming(Bug.NOT_ATOMIC, Bug.NOT_ATOMIC, "insertIfUnderLimit");
        concurrentFailsNaming(Bug.NONE, Bug.THROWS_ON_INSERT, "insertIfUnderLimit");
        Rows rows = new Rows();
        AuthTesting.verifyApiKeyStore(new MemStore(rows, Bug.NONE), new MemStore(rows, Bug.NONE));
    }

    // FR-APIKEY-009 AC-5: a store using ownerVer != v in deleteStaleByOwner fails with a message naming
    // deleteStaleByOwner
    @Test
    public void FR_APIKEY_009_AC5_brokenStaleDeleteNamed() {
        failsNaming(Bug.STALE_NOT_EQUAL, "deleteStaleByOwner");
    }

    // FR-APIKEY-009 AC-6: verifyApiKeyStore(a, b) with a and b two instances of an app-provided test ApiKeyStore over
    // one shared backing storage with an atomic insertIfUnderLimit passes deterministically in 20 repeated runs (no
    // JDBC driver)
    @Test(timeout = 120000)
    public void FR_APIKEY_009_AC6_twoAppStoreInstancesPassTwentyTimes() {
        for (int run = 0; run < 20; run++) {
            com.snoworca.naruserve.utils.auth.AppStores db = new com.snoworca.naruserve.utils.auth.AppStores();
            ApiKeyStore a = db.apiKeys();
            ApiKeyStore b = db.apiKeys();
            assertTrue(a != b && a.supportsAtomicLimitInsert() && b.supportsAtomicLimitInsert());
            AuthTesting.verifyApiKeyStore(a, b);
        }
    }
}
