package com.snoworca.naruserve.utils.auth.apikey;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import org.junit.Test;

/** The API key value types (DR-APIKEY-001), their secret hygiene (SEC-AUTH-008 AC-2) and the store SPI (IR-APIKEY-004). */
public class ApiKeyTypesTest {

    static final String HASH = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";
    static final String PREFIX = "Ab3dE5gH";
    static final String SECRET = "s3cretS3cretS3cretS3cretS3cret32";

    static StoredApiKey.Builder full() {
        return StoredApiKey.builder().id("k1").prefix(PREFIX).secretHash(HASH).ownerUserId("u1").ownerVer(4L)
                .name("ci-bot").scopes(new LinkedHashSet<>(Arrays.asList("posts:read", "posts:write")))
                .createdAt(1_000L);
    }

    /** One builder step that sets a required field. */
    private interface Step {
        void apply(StoredApiKey.Builder b);
    }

    // DR-APIKEY-001 AC-1: missing any required field throws IllegalStateException from build(); the optional fields
    // default to null
    @Test
    public void DR_APIKEY_001_AC1_requiredFieldsAndNullDefaults() {
        String[] names = {"id", "prefix", "secretHash", "ownerUserId", "ownerVer", "name", "scopes", "createdAt"};
        Step[] steps = {
            b -> b.id("k1"), b -> b.prefix(PREFIX), b -> b.secretHash(HASH), b -> b.ownerUserId("u1"),
            b -> b.ownerVer(4L), b -> b.name("ci-bot"), b -> b.scopes(Collections.singleton("posts:read")),
            b -> b.createdAt(1_000L)};
        for (int skip = 0; skip < steps.length; skip++) {
            StoredApiKey.Builder b = StoredApiKey.builder();
            for (int i = 0; i < steps.length; i++) {
                if (i != skip) {
                    steps[i].apply(b);
                }
            }
            try {
                b.build();
                fail("built without " + names[skip]);
            } catch (IllegalStateException e) {
                assertTrue(e.getMessage(), e.getMessage().contains(names[skip]));
            }
        }
        StoredApiKey k = full().build();
        assertNull(k.getExpiresAt());
        assertNull(k.getLastUsedAt());
        assertNull(k.getIssuedByUserId());
        try {
            full().secretHash(HASH.toUpperCase(Locale.ROOT)).build();
            fail("an upper-case secret hash was accepted");
        } catch (IllegalStateException expected) {
            // 64 lower-case hex characters
        }
    }

    // DR-APIKEY-001 AC-2: toBuilder() copies every field
    @Test
    public void DR_APIKEY_001_AC2_toBuilderCopiesEveryField() {
        StoredApiKey k = full().expiresAt(9_000L).lastUsedAt(5_000L).issuedByUserId("admin-1").build();
        StoredApiKey c = k.toBuilder().build();
        assertEquals(k.getId(), c.getId());
        assertEquals(k.getPrefix(), c.getPrefix());
        assertEquals(k.getSecretHash(), c.getSecretHash());
        assertEquals(k.getOwnerUserId(), c.getOwnerUserId());
        assertEquals(k.getOwnerVer(), c.getOwnerVer());
        assertEquals(k.getName(), c.getName());
        assertEquals(k.getScopes(), c.getScopes());
        assertEquals(k.getExpiresAt(), c.getExpiresAt());
        assertEquals(k.getCreatedAt(), c.getCreatedAt());
        assertEquals(k.getLastUsedAt(), c.getLastUsedAt());
        assertEquals(k.getIssuedByUserId(), c.getIssuedByUserId());
        assertEquals(k, c);
        assertEquals(k.hashCode(), c.hashCode());
        assertFalse(k.equals(k.toBuilder().name("other").build()));
    }

    // DR-APIKEY-001 AC-3 and SEC-AUTH-008 AC-2: IssuedApiKey.toString() shows nk_<prefix8>_*** and not the secret;
    // StoredApiKey.toString() does not contain the secret hash; a key is referenced only by its prefix
    @Test
    public void DR_APIKEY_001_AC3_SEC_AUTH_008_AC2_toStringHidesSecrets() {
        StoredApiKey stored = full().build();
        ApiKey key = ApiKey.of(stored, ApiKeyStatus.ACTIVE);
        String plain = "nk_" + PREFIX + "_" + SECRET;
        IssuedApiKey issued = new IssuedApiKey(key, plain);
        assertEquals(plain, issued.getPlainKey());
        assertSame(key, issued.getKey());
        String s = issued.toString();
        assertTrue(s, s.contains("nk_" + PREFIX + "_***"));
        assertFalse(s, s.contains(SECRET));
        assertFalse(stored.toString(), stored.toString().contains(HASH));
        assertTrue(stored.toString(), stored.toString().contains("nk_" + PREFIX));
        assertFalse(key.toString(), key.toString().contains(HASH));
        try {
            new IssuedApiKey(key, "nk_other123_" + SECRET);
            fail("a plain key of another prefix was accepted");
        } catch (IllegalArgumentException e) {
            assertFalse(e.getMessage(), e.getMessage().contains(SECRET));
        }
    }

    // DR-APIKEY-001 AC-4: ApiKey has no secret accessor; createdAt is long, expiresAt and lastUsedAt are Long and may
    // be null, issuedByUserId may be null
    @Test
    public void DR_APIKEY_001_AC4_apiKeyHasNoSecretAccessor() throws Exception {
        for (Method m : ApiKey.class.getMethods()) {
            String n = m.getName().toLowerCase(Locale.ROOT);
            assertFalse(m.toString(), n.contains("secret") || n.contains("hash") && !n.equals("hashcode"));
        }
        assertEquals(long.class, ApiKey.class.getMethod("getCreatedAt").getReturnType());
        assertEquals(Long.class, ApiKey.class.getMethod("getExpiresAt").getReturnType());
        assertEquals(Long.class, ApiKey.class.getMethod("getLastUsedAt").getReturnType());
        assertEquals(String.class, ApiKey.class.getMethod("getIssuedByUserId").getReturnType());
        assertEquals(ApiKeyStatus.class, ApiKey.class.getMethod("getStatus").getReturnType());
        ApiKey k = ApiKey.of(full().build(), ApiKeyStatus.STALE);
        assertNull(k.getExpiresAt());
        assertNull(k.getLastUsedAt());
        assertNull(k.getIssuedByUserId());
        assertEquals(1_000L, k.getCreatedAt());
        assertEquals(ApiKeyStatus.STALE, k.getStatus());
        assertEquals(4L, k.getOwnerVer());
        assertTrue(Modifier.isFinal(ApiKey.class.getModifiers()));
        assertTrue(Modifier.isFinal(StoredApiKey.class.getModifiers()));
    }

    /** A store implementing only the abstract methods, over a list. */
    static class MinimalStore implements ApiKeyStore {
        final List<StoredApiKey> rows = new ArrayList<>();
        final List<String> deletedById = new ArrayList<>();

        @Override
        public StoredApiKey findByPrefix(String prefix) {
            for (StoredApiKey k : rows) {
                if (k.getPrefix().equals(prefix)) {
                    return k;
                }
            }
            return null;
        }

        @Override
        public StoredApiKey findById(String keyId) {
            for (StoredApiKey k : rows) {
                if (k.getId().equals(keyId)) {
                    return k;
                }
            }
            return null;
        }

        @Override
        public List<StoredApiKey> listByOwner(String ownerUserId) {
            List<StoredApiKey> out = new ArrayList<>();
            for (StoredApiKey k : rows) {
                if (k.getOwnerUserId().equals(ownerUserId)) {
                    out.add(k);
                }
            }
            return out;
        }

        @Override
        public boolean insertIfUnderLimit(StoredApiKey key, int maxLiveKeys, long nowMillis) {
            rows.add(key);
            return true;
        }

        @Override
        public boolean deleteByIdAndOwner(String keyId, String ownerUserId) {
            StoredApiKey k = findById(keyId);
            return k != null && k.getOwnerUserId().equals(ownerUserId) && rows.remove(k);
        }

        @Override
        public boolean deleteById(String keyId) {
            deletedById.add(keyId);
            StoredApiKey k = findById(keyId);
            return k != null && rows.remove(k);
        }

        @Override
        public int deleteAllByOwner(String ownerUserId) {
            List<StoredApiKey> mine = listByOwner(ownerUserId);
            rows.removeAll(mine);
            return mine.size();
        }

        @Override
        public int deleteStaleByOwner(String ownerUserId, long currentOwnerVer) {
            return 0;
        }

        @Override
        public int deleteExpired(long nowMillis) {
            return 0;
        }
    }

    // IR-APIKEY-004 AC-1: the interface declares exactly the methods of the 00 §6.3 table, the four defaults included
    @Test
    public void IR_APIKEY_004_AC1_methodSetIsTheTable() {
        Set<String> all = new TreeSet<>();
        Set<String> defaults = new TreeSet<>();
        int declared = 0;
        for (Method m : ApiKeyStore.class.getDeclaredMethods()) {
            if (m.isSynthetic()) {
                continue;
            }
            declared++;
            all.add(m.getName());
            if (m.isDefault()) {
                defaults.add(m.getName());
            }
            // every store call is synchronous and may throw; only the declaration flag reads no store
            List<Class<?>> expected = m.getName().equals("supportsAtomicLimitInsert")
                    ? Collections.<Class<?>>emptyList() : Arrays.<Class<?>>asList(Exception.class);
            assertEquals(m.toString(), expected, Arrays.asList(m.getExceptionTypes()));
        }
        assertEquals(new TreeSet<>(Arrays.asList("findByPrefix", "findById", "listByOwner", "insertIfUnderLimit",
                "deleteByIdAndOwner", "deleteById", "deleteAllByOwner", "deleteStaleByOwner", "deleteFutureByOwner",
                "deleteExpired", "updateLastUsed", "supportsAtomicLimitInsert", "countKeysWithAdminScopes")), all);
        assertEquals(all.size(), declared);
        assertEquals(new TreeSet<>(Arrays.asList("deleteFutureByOwner", "updateLastUsed",
                "supportsAtomicLimitInsert", "countKeysWithAdminScopes")), defaults);
        assertTrue(RuntimeException.class.isAssignableFrom(ApiKeyPrefixCollisionException.class));
    }

    // IR-APIKEY-004 AC-3: the default deleteFutureByOwner(u, 5) deletes only the ownerVer 6 key via listByOwner and
    // deleteById and returns 1
    @Test
    public void IR_APIKEY_004_AC3_defaultDeleteFutureByOwner() throws Exception {
        MinimalStore s = new MinimalStore();
        s.rows.add(full().id("a").prefix("AAAAAAAA").ownerVer(4L).build());
        s.rows.add(full().id("b").prefix("BBBBBBBB").ownerVer(5L).build());
        s.rows.add(full().id("c").prefix("CCCCCCCC").ownerVer(6L).build());
        s.rows.add(full().id("d").prefix("DDDDDDDD").ownerUserId("u2").ownerVer(9L).build());
        assertEquals(1, s.deleteFutureByOwner("u1", 5L));
        assertEquals(Arrays.asList("c"), s.deletedById);
        assertNull(s.findById("c"));
        assertEquals(3, s.rows.size());
    }

    // IR-APIKEY-004 AC-4: a store with only the abstract methods reports false and -1, and its lastUsedAt update does
    // nothing
    @Test
    public void IR_APIKEY_004_AC4_minimalStoreDefaults() throws Exception {
        MinimalStore s = new MinimalStore();
        StoredApiKey k = full().build();
        s.rows.add(k);
        assertFalse(s.supportsAtomicLimitInsert());
        assertEquals(-1, s.countKeysWithAdminScopes());
        s.updateLastUsed("k1", 77_000L);
        assertSame(k, s.findById("k1"));
        assertNull(s.findById("k1").getLastUsedAt());
    }
}
