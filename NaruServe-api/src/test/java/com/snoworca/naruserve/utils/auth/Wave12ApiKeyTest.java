package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.utils.auth.apikey.ApiKeyStore;
import com.snoworca.naruserve.utils.auth.apikey.InMemoryApiKeyStore;
import com.snoworca.naruserve.utils.auth.apikey.StoredApiKey;
import com.snoworca.naruserve.utils.auth.internal.ApiKeyFormat;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The ApiKeyStore contract over the shipped store and an app-provided one (IR-APIKEY-004 AC-2, AC-5), the 503 wrapping
 * of store failures on the request path and the paths that leave the response alone (IR-APIKEY-004 AC-6) and the
 * secret hygiene of key failure logs (DR-APIKEY-001 AC-5). Over the in-memory adapter; nothing is a mock.
 */
public class Wave12ApiKeyTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final long NOW = 1_761_700_900_000L;
    private static final String HASH = ApiKeyFormat.secretHash("S3cretS3cretS3cretS3cretS3cretAB");

    private final W8 f = new W8();
    private final Wave9KeyAuthTest.Held held = new Wave9KeyAuthTest.Held();

    @After
    public void stop() {
        f.stop();
    }

    private static StoredApiKey key(String id, String owner, long ver, @Nullable Long expiresAt, String... scopes) {
        String prefix = ("P" + id + "xxxxxxx").substring(0, 8);
        return StoredApiKey.builder().id(id).prefix(prefix).secretHash(HASH).ownerUserId(owner).ownerVer(ver)
                .name("n-" + id).scopes(scopes.length == 0 ? Collections.singletonList("posts:read")
                        : Arrays.asList(scopes)).expiresAt(expiresAt).createdAt(NOW - 1000).build();
    }

    private List<ApiKeyStore> stores() throws Exception {
        return Arrays.<ApiKeyStore>asList(new InMemoryApiKeyStore(), new AppStores().apiKeys());
    }

    // ---------------------------------------------------------------------------------------------- IR-APIKEY-004

    // IR-APIKEY-004 AC-2: insertIfUnderLimit counts as live only keys unexpired at nowMillis whose ownerVer is at least
    // the inserted key's ownerVer: with 19 such keys and limit 20 it inserts and returns true, with 20 it returns false
    @Test
    public void IR_APIKEY_004_AC2_liveCountForTheLimit() throws Exception {
        for (ApiKeyStore s : stores()) {
            String where = s.getClass().getSimpleName();
            int n = 0;
            for (int i = 0; i < 19; i++) {
                // live: unexpired (no expiry or a later one), ownerVer 5 or above
                Long exp = i % 3 == 0 ? null : NOW + 60_000L;
                assertTrue(where, s.insertIfUnderLimit(key("L" + (n++), "u1", i % 2 == 0 ? 5 : 6, exp), 100, NOW));
            }
            // dead: expired at NOW, or a lower ownerVer (stale)
            assertTrue(s.insertIfUnderLimit(key("D" + (n++), "u1", 5, NOW), 100, NOW - 1));
            assertTrue(s.insertIfUnderLimit(key("D" + (n++), "u1", 5, NOW - 5), 100, NOW - 10));
            assertTrue(s.insertIfUnderLimit(key("D" + (n++), "u1", 4, null), 100, NOW));
            assertTrue(s.insertIfUnderLimit(key("D" + (n++), "u1", 3, NOW + 60_000L), 100, NOW));
            // another owner's keys never count
            assertTrue(s.insertIfUnderLimit(key("O" + (n++), "u2", 5, null), 100, NOW));
            assertTrue(where + ": 19 live -> insert", s.insertIfUnderLimit(key("N" + (n++), "u1", 5, null), 20, NOW));
            assertFalse(where + ": 20 live -> refused", s.insertIfUnderLimit(key("N" + (n++), "u1", 5, null), 20, NOW));
            assertNull(where, s.findById("N" + (n - 1)));
        }
    }

    // IR-APIKEY-004 AC-5: countKeysWithAdminScopes() counts keys naming an auth- resource scope (dead keys included)
    // and not keys whose only scope is *
    @Test
    public void IR_APIKEY_004_AC5_adminScopeCount() throws Exception {
        for (ApiKeyStore s : stores()) {
            String where = s.getClass().getSimpleName();
            s.insertIfUnderLimit(key("a1", "u1", 5, null, "auth-users:read"), 100, NOW);
            s.insertIfUnderLimit(key("a2", "u1", 5, null, "posts:read", "auth-apikeys:write"), 100, NOW);
            s.insertIfUnderLimit(key("a3", "u2", 1, NOW - 10, "auth-groups:*"), 100, NOW - 20); // dead: counted
            s.insertIfUnderLimit(key("s1", "u1", 5, null, "*"), 100, NOW);
            s.insertIfUnderLimit(key("p1", "u1", 5, null, "posts:*"), 100, NOW);
            s.insertIfUnderLimit(key("p2", "u2", 5, null, "authors:read"), 100, NOW);
            assertEquals(where, 3, s.countKeysWithAdminScopes());
        }
    }

    /** An ApiKeyStore over the in-memory store whose named methods throw a store error. */
    static final class Flaky implements ApiKeyStore {
        final InMemoryApiKeyStore d = new InMemoryApiKeyStore();
        final Set<String> failing = new CopyOnWriteArraySet<>();
        /** Every call that threw because its method was failing, in call order. */
        final List<String> failed = new java.util.concurrent.CopyOnWriteArrayList<>();

        private void check(String method) throws SQLException {
            if (failing.contains(method)) {
                failed.add(method);
                throw new SQLException("key store down in " + method);
            }
        }

        @Override
        public @Nullable StoredApiKey findByPrefix(String prefix) throws Exception {
            check("findByPrefix");
            return d.findByPrefix(prefix);
        }

        @Override
        public @Nullable StoredApiKey findById(String keyId) throws Exception {
            check("findById");
            return d.findById(keyId);
        }

        @Override
        public List<StoredApiKey> listByOwner(String ownerUserId) throws Exception {
            check("listByOwner");
            return d.listByOwner(ownerUserId);
        }

        @Override
        public boolean insertIfUnderLimit(StoredApiKey key, int maxLiveKeys, long nowMillis) throws Exception {
            check("insertIfUnderLimit");
            return d.insertIfUnderLimit(key, maxLiveKeys, nowMillis);
        }

        @Override
        public boolean deleteByIdAndOwner(String keyId, String ownerUserId) throws Exception {
            check("deleteByIdAndOwner");
            return d.deleteByIdAndOwner(keyId, ownerUserId);
        }

        @Override
        public boolean deleteById(String keyId) throws Exception {
            check("deleteById");
            return d.deleteById(keyId);
        }

        @Override
        public int deleteAllByOwner(String ownerUserId) throws Exception {
            check("deleteAllByOwner");
            return d.deleteAllByOwner(ownerUserId);
        }

        @Override
        public int deleteStaleByOwner(String ownerUserId, long currentOwnerVer) throws Exception {
            check("deleteStaleByOwner");
            return d.deleteStaleByOwner(ownerUserId, currentOwnerVer);
        }

        @Override
        public int deleteFutureByOwner(String ownerUserId, long currentOwnerVer) throws Exception {
            check("deleteFutureByOwner");
            return d.deleteFutureByOwner(ownerUserId, currentOwnerVer);
        }

        @Override
        public int deleteExpired(long nowMillis) throws Exception {
            check("deleteExpired");
            return d.deleteExpired(nowMillis);
        }

        @Override
        public void updateLastUsed(String keyId, long lastUsedAtMillis) throws Exception {
            check("updateLastUsed");
            d.updateLastUsed(keyId, lastUsedAtMillis);
        }

        @Override
        public boolean supportsAtomicLimitInsert() {
            return true;
        }

        @Override
        public int countKeysWithAdminScopes() throws Exception {
            return d.countKeysWithAdminScopes();
        }
    }

    private final Flaky keys = new Flaky();
    private String u1;
    private String root;

    private void start() throws Exception {
        u1 = f.user("u1", W8.PW, "reader");
        root = f.user("root", W8.PW, "admin");
        f.start(b -> b.apiKeys(keys).roles(Wave10PatTest.roles()));
        f.auth.apiKeyOwners().deletions(held);
    }

    private String bearer(String id) throws Exception {
        return "Bearer " + f.auth.accessToken(id, f.stored(id).getTokenVersion(), 900_000L);
    }

    private ClientResponse send(String method, String path, String authorization) {
        return f.adapter.send(ClientRequest.of(method, path).header("Authorization", authorization));
    }

    private String issue(String owner) {
        return f.auth.apiKeys().issue(owner, "ci", null, "posts:read").getPlainKey();
    }

    private String idOf(String plain) throws Exception {
        return keys.d.findByPrefix(plain.substring(3, 11)).getId();
    }

    private static void unavailable(String what, ClientResponse r) {
        assertEquals(what + ": " + r.text(), 503, r.status());
        assertEquals(what, "SERVICE_UNAVAILABLE", W8.code(r));
    }

    /** Fails {@code method} for one call of {@code action}, then lets it work again. */
    private ClientResponse failing(String method, java.util.concurrent.Callable<ClientResponse> action)
            throws Exception {
        keys.failing.add(method);
        try {
            return action.call();
        } finally {
            keys.failing.remove(method);
        }
    }

    // IR-APIKEY-004 AC-6: on a protected request a store exception from a call the response depends on (findByPrefix,
    // findById, listByOwner, insertIfUnderLimit, deleteByIdAndOwner, deleteById on the admin DELETE, deleteAllByOwner
    // in a user deletion, deleteFutureByOwner before an issuance, pending-marker re-delete included) -> 503 JSON
    // SERVICE_UNAVAILABLE; the lastUsedAt update and the off-request deletions (deleteStaleByOwner, invariant
    // deleteById, deleteExpired) leave the response unchanged; deleteFutureByOwner failing before an auth-triggered
    // ver++ is no 503: ERROR log, the ver++ goes on, a pending marker
    @Test
    public void IR_APIKEY_004_AC6_storeFailuresOnAndOffTheRequestPath() throws Exception {
        start();
        String plain = issue(u1);
        String id = idOf(plain);
        String u1Bearer = bearer(u1);
        String rootBearer = bearer(root);
        unavailable("findByPrefix", failing("findByPrefix", () -> f.get("/api/x", "Authorization", "ApiKey " + plain)));
        unavailable("findById", failing("findById", () -> send("DELETE", "/auth/admin/apikeys/" + id, rootBearer)));
        unavailable("deleteById", failing("deleteById", () -> send("DELETE", "/auth/admin/apikeys/" + id,
                rootBearer)));
        unavailable("listByOwner", failing("listByOwner", () -> f.get("/auth/me/apikeys", "Authorization", u1Bearer)));
        unavailable("insertIfUnderLimit", failing("insertIfUnderLimit", () -> f.post("/auth/me/apikeys",
                Wave10PatTest.body(W8.PW, "posts:read"), "Authorization", u1Bearer)));
        unavailable("deleteFutureByOwner", failing("deleteFutureByOwner", () -> f.post("/auth/me/apikeys",
                Wave10PatTest.body(W8.PW, "posts:read"), "Authorization", u1Bearer)));
        f.auth.apiKeyOwners().mark(u1, f.stored(u1).getTokenVersion());
        unavailable("pending-marker re-delete", failing("deleteFutureByOwner", () -> f.post("/auth/me/apikeys",
                Wave10PatTest.body(W8.PW, "posts:read"), "Authorization", u1Bearer)));
        assertNotNull("the marker stays", f.auth.apiKeyOwners().marker(u1));
        ClientResponse cleared = f.post("/auth/me/apikeys", Wave10PatTest.body(W8.PW, "posts:read"), "Authorization",
                u1Bearer);
        assertEquals("the re-delete works again: " + cleared.text(), 201, cleared.status());
        assertNull("the marker is gone", f.auth.apiKeyOwners().marker(u1));
        int u1Keys = keys.d.listByOwner(u1).size();
        unavailable("deleteByIdAndOwner", failing("deleteByIdAndOwner", () -> send("DELETE", "/auth/me/apikeys/" + id,
                u1Bearer)));
        String victim = f.user("victim", W8.PW, "reader");
        issue(victim);
        unavailable("deleteAllByOwner", failing("deleteAllByOwner", () -> send("DELETE", "/auth/admin/users/"
                + victim, rootBearer)));
        assertEquals("no deletion went through", u1Keys, keys.d.listByOwner(u1).size());
        assertEquals(1, keys.d.listByOwner(victim).size());
        assertNotNull(f.store.findById(victim));

        // off the request path: the response is the one without the failure
        int lastUsedWarns = com.snoworca.naruserve.testing.RecordingLogger.countMatching(f.logger.lines,
                "WARN .*lastUsedAt.*");
        keys.failing.add("updateLastUsed");
        ClientResponse used;
        try {
            used = f.get("/api/x", "Authorization", "ApiKey " + plain);
            // the lastUsedAt update runs on the background executor: keep it failing until it has failed and logged
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (com.snoworca.naruserve.testing.RecordingLogger.countMatching(f.logger.lines,
                    "WARN .*lastUsedAt.*") == lastUsedWarns && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
        } finally {
            keys.failing.remove("updateLastUsed");
        }
        assertEquals("a failed lastUsedAt update", 200, used.status());
        assertTrue("the lastUsedAt update was reached and failed: " + keys.failed,
                keys.failed.contains("updateLastUsed"));
        assertEquals(f.logger.lines.toString(), lastUsedWarns + 1, com.snoworca.naruserve.testing.RecordingLogger
                .countMatching(f.logger.lines, "WARN .*lastUsedAt.*"));
        StoredApiKey stale = key("st", u1, f.stored(u1).getTokenVersion() - 1, null);
        String stalePlain = plant(stale);
        StoredApiKey future = key("fu", u1, f.stored(u1).getTokenVersion() + 1, null);
        String futurePlain = plant(future);
        keys.failing.addAll(Arrays.asList("deleteStaleByOwner", "deleteById", "deleteExpired"));
        keys.failed.clear();
        try {
            ClientResponse s = f.get("/api/x", "Authorization", "ApiKey " + stalePlain);
            ClientResponse v = f.get("/api/x", "Authorization", "ApiKey " + futurePlain);
            assertTrue("nothing failed on the request path: " + keys.failed, keys.failed.isEmpty());
            held.runAll(); // the scheduled deletions run and fail
            f.auth.cleanupRun();
            assertTrue("every off-request deletion was reached and failed: " + keys.failed,
                    keys.failed.containsAll(Arrays.asList("deleteStaleByOwner", "deleteById", "deleteExpired")));
            assertTrue(f.logger.lines.toString(), com.snoworca.naruserve.testing.RecordingLogger.countMatching(
                    f.logger.lines, "ERROR .*cleanup of expired API keys failed.*") >= 1);
            assertEquals("stale key", 401, s.status());
            assertEquals("invalid_api_key", W8.code(s));
            assertEquals("invariant violation", 401, v.status());
            assertEquals("invalid_api_key", W8.code(v));
        } finally {
            keys.failing.clear();
        }
        // deleteFutureByOwner failing before an auth-triggered ver++: logout of every device still answers 204
        long before = f.stored(u1).getTokenVersion();
        assertNull(f.auth.apiKeyOwners().marker(u1));
        ClientResponse out = failing("deleteFutureByOwner", () -> f.post("/auth/logout?all=true", null,
                "Authorization", bearer(u1)));
        assertEquals(out.text(), 204, out.status());
        assertEquals("the ver++ went on", before + 1, f.stored(u1).getTokenVersion());
        assertEquals("a pending marker at the old ver", Long.valueOf(before), f.auth.apiKeyOwners().marker(u1));
        assertTrue(f.logger.lines.toString(), com.snoworca.naruserve.testing.RecordingLogger.countMatching(
                f.logger.lines, "ERROR .*future-key deletion before a ver\\+\\+.*") >= 1);
    }

    /** Stores {@code row} with a known secret; its plaintext key. */
    private String plant(StoredApiKey row) throws Exception {
        String secret = "S3cretS3cretS3cretS3cretS3cretAB";
        assertTrue(keys.d.insertIfUnderLimit(row, 100, NOW - 1000));
        return "nk_" + row.getPrefix() + "_" + secret;
    }

    // ---------------------------------------------------------------------------------------------- DR-APIKEY-001

    // DR-APIKEY-001 AC-5: a key authentication failure log line holds the key prefix at most, never the secret or the
    // Authorization header value (log capture over the failures that log: invariant violation, store failure, failed
    // lastUsedAt update, failed off-request deletion)
    @Test
    public void DR_APIKEY_001_AC5_failureLogsHoldNoSecret() throws Exception {
        start();
        List<String> plains = new ArrayList<>();
        String plain = issue(u1);
        plains.add(plain);
        String futurePlain = plant(key("fx", u1, f.stored(u1).getTokenVersion() + 1, null));
        plains.add(futurePlain);
        String stalePlain = plant(key("sx", u1, f.stored(u1).getTokenVersion() - 1, null));
        plains.add(stalePlain);
        keys.failing.addAll(Arrays.asList("deleteById", "deleteStaleByOwner"));
        assertEquals(401, f.get("/api/x", "Authorization", "ApiKey " + futurePlain).status());
        assertEquals(401, f.get("/api/x", "Authorization", "ApiKey " + stalePlain).status());
        assertEquals(401, f.get("/api/x", "Authorization", "ApiKey " + Wave9KeyAuthTest.wrongSecret(plain)).status());
        held.runAll();
        keys.failing.clear();
        keys.failing.add("updateLastUsed");
        assertEquals(200, f.get("/api/x", "Authorization", "ApiKey " + plain).status());
        // the lastUsedAt update runs on the background executor: keep it failing until its WARN is logged
        long deadline = System.currentTimeMillis() + 10_000L;
        while (com.snoworca.naruserve.testing.RecordingLogger.countMatching(f.logger.lines, ".*lastUsedAt.*") == 0
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        keys.failing.clear();
        keys.failing.add("findByPrefix");
        unavailable("lookup", f.get("/api/x", "Authorization", "ApiKey " + plain));
        keys.failing.clear();
        List<String> seen = new ArrayList<>(f.logger.lines);
        assertTrue(seen.toString(), com.snoworca.naruserve.testing.RecordingLogger.countMatching(seen,
                "ERROR .*ownerVer.*") >= 1);
        assertTrue(seen.toString(), com.snoworca.naruserve.testing.RecordingLogger.countMatching(seen,
                ".*lastUsedAt.*") >= 1);
        for (Throwable e : new ArrayList<>(f.logger.errors)) {
            for (Throwable c = e; c != null; c = c.getCause()) {
                seen.add(String.valueOf(c.getMessage()) + " " + c);
            }
        }
        for (String line : seen) {
            for (String p : plains) {
                String secret = p.substring(12);
                assertFalse("the secret leaked: " + line, line.contains(secret));
                assertFalse("the key leaked: " + line, line.contains(p));
                assertFalse("the header leaked: " + line, line.contains("ApiKey " + p));
                assertFalse("the wrong secret leaked: " + line,
                        line.contains(Wave9KeyAuthTest.wrongSecret(p).substring(12)));
            }
        }
    }
}
