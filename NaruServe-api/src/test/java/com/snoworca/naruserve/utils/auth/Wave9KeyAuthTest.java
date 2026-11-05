package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.utils.auth.apikey.ApiKeyStatus;
import com.snoworca.naruserve.utils.auth.apikey.IssuedApiKey;
import com.snoworca.naruserve.utils.auth.apikey.StoredApiKey;
import com.snoworca.naruserve.utils.auth.audit.AuditEvent;
import com.snoworca.naruserve.utils.auth.internal.ApiKeyFormat;
import com.snoworca.naruserve.utils.auth.rbac.RolePolicy;
import com.snoworca.naruserve.utils.auth.spi.ForwardingLoginThrottle;
import com.snoworca.naruserve.utils.auth.user.AuthUser;
import com.snoworca.naruserve.utils.auth.user.ForwardingUserStore;
import com.snoworca.naruserve.utils.auth.user.UserStore;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.junit.After;
import org.junit.Test;

/**
 * API key authentication on request paths: the verification order (SEC-APIKEY-004), the key-only per-IP failure
 * counter (SEC-APIKEY-005), stale keys and the ownerVer invariant (SEC-APIKEY-006), the key principal and its effective
 * scopes (SEC-APIKEY-007), no key-row cache (REL-APIKEY-001), expiry and its cleanup (FR-APIKEY-002), the configured
 * challenge name (IR-APIKEY-005) and the listing status (IR-APIKEY-003), over the in-memory adapter.
 */
public class Wave9KeyAuthTest {

    /** The counting key store of wave 8 that also records deletions and can fail or match prefixes ignoring case. */
    static class Keys extends Wave8ApiKeysTest.Counting {
        final List<String> deletes = new CopyOnWriteArrayList<>();
        final AtomicInteger expiredCalls = new AtomicInteger();
        final AtomicLong expiredAt = new AtomicLong();
        volatile boolean failLookup;
        volatile boolean failExpired;
        volatile boolean ignoreCase;
        volatile int failFuture;
        volatile java.util.function.Consumer<String> insertCheck = id -> { };
        volatile java.util.function.Consumer<String> futureCheck = id -> { };
        volatile java.util.function.Consumer<String> deleteCheck = id -> { };
        volatile java.util.function.Consumer<String> deleteStaleHook = id -> { };

        @Override
        public StoredApiKey findByPrefix(String prefix) {
            if (failLookup) {
                lookups.incrementAndGet();
                throw new IllegalStateException("key store down");
            }
            if (ignoreCase) {
                lookups.incrementAndGet();
                for (StoredApiKey k : rowsOf()) {
                    if (k.getPrefix().equalsIgnoreCase(prefix)) {
                        return k;
                    }
                }
                return null;
            }
            return super.findByPrefix(prefix);
        }

        List<StoredApiKey> rowsOf() {
            List<StoredApiKey> all = new ArrayList<>();
            for (String o : owners) {
                all.addAll(d.listByOwner(o));
            }
            return all;
        }

        final List<String> owners = new CopyOnWriteArrayList<>();

        @Override
        public boolean insertIfUnderLimit(StoredApiKey key, int maxLiveKeys, long nowMillis) {
            insertCheck.accept(key.getOwnerUserId());
            if (!owners.contains(key.getOwnerUserId())) {
                owners.add(key.getOwnerUserId());
            }
            return super.insertIfUnderLimit(key, maxLiveKeys, nowMillis);
        }

        @Override
        public boolean deleteById(String keyId) {
            deletes.add("deleteById " + keyId);
            return super.deleteById(keyId);
        }

        @Override
        public int deleteStaleByOwner(String ownerUserId, long currentOwnerVer) {
            deleteStaleHook.accept(ownerUserId);
            deletes.add("deleteStaleByOwner " + ownerUserId + " " + currentOwnerVer);
            return super.deleteStaleByOwner(ownerUserId, currentOwnerVer);
        }

        @Override
        public int deleteFutureByOwner(String ownerUserId, long currentOwnerVer) throws Exception {
            deletes.add("deleteFutureByOwner " + ownerUserId + " " + currentOwnerVer);
            futureCheck.accept(ownerUserId);
            if (failFuture > 0) {
                failFuture--;
                throw new java.sql.SQLException("delete timed out");
            }
            return d.deleteFutureByOwner(ownerUserId, currentOwnerVer);
        }

        @Override
        public int deleteAllByOwner(String ownerUserId) {
            deleteCheck.accept(ownerUserId);
            return super.deleteAllByOwner(ownerUserId);
        }

        @Override
        public int deleteExpired(long nowMillis) {
            expiredCalls.incrementAndGet();
            expiredAt.set(nowMillis);
            if (failExpired) {
                throw new IllegalStateException("cleanup failed");
            }
            return super.deleteExpired(nowMillis);
        }
    }

    /** An executor that runs nothing until {@link #runAll()}. */
    static final class Held implements Executor {
        final List<Runnable> queued = new CopyOnWriteArrayList<>();

        @Override
        public void execute(Runnable command) {
            queued.add(command);
        }

        void runAll() {
            List<Runnable> now = new ArrayList<>(queued);
            queued.clear();
            for (Runnable r : now) {
                r.run();
            }
        }
    }

    private final W8 f = new W8();
    private final List<W8> others = new ArrayList<>();
    private final Keys keys = new Keys();
    private final Held held = new Held();
    private final List<AuditEvent> audit = new CopyOnWriteArrayList<>();
    private String owner;

    @After
    public void stop() {
        f.stop();
        for (W8 o : others) {
            o.stop();
        }
    }

    private void start(java.util.function.UnaryOperator<Auth.Builder> config) throws Exception {
        owner = f.user("bot", W8.PW, "reader", "writer");
        f.start(b -> config.apply(b.apiKeys(keys).audit(audit::add)));
        f.auth.apiKeyOwners().deletions(held);
    }

    /** A second node over the same user and key stores, with its own principal cache and clock. */
    private W8 nodeB(java.util.function.UnaryOperator<Auth.Builder> config) throws Exception {
        return nodeB(config, w -> { });
    }

    private W8 nodeB(java.util.function.UnaryOperator<Auth.Builder> config, java.util.function.Consumer<W8> routes)
            throws Exception {
        W8 b = new W8();
        b.users = f.store;
        b.routes = routes;
        b.clock.set(f.clock.get());
        others.add(b);
        b.start(x -> config.apply(x.apiKeys(keys)));
        b.auth.apiKeyOwners().deletions(held);
        return b;
    }

    private String key(String... scopes) {
        return f.auth.apiKeys().issue(owner, "ci", null, scopes).getPlainKey();
    }

    private static ClientResponse withKey(W8 w, String path, String key) {
        return w.get(path, "Authorization", "ApiKey " + key);
    }

    private ClientResponse withKey(String path, String key) {
        return withKey(f, path, key);
    }

    /** The same key with another secret. */
    static String wrongSecret(String plain) {
        return plain.substring(0, 12) + (plain.charAt(12) == 'A' ? "B" : "A") + plain.substring(13);
    }

    /** A key whose prefix no row holds. */
    static String unknownPrefix(String plain) {
        return "nk_ZZZZZZZZ" + plain.substring(11);
    }

    private ClientResponse fromIp(String ip, String path, String key) {
        return f.get(path, "Authorization", "ApiKey " + key, "X-Forwarded-For", ip);
    }

    // ---------------------------------------------------------------------------------------------- SEC-APIKEY-004

    // SEC-APIKEY-004 AC-1: an unknown prefix, a known prefix with a wrong secret and an expired key each get the same
    // 401 with body code invalid_api_key
    @Test
    public void SEC_APIKEY_004_AC1_everyFailureIsTheSame401() throws Exception {
        start(b -> b);
        String k = key("posts:read");
        String expired = f.auth.apiKeys().issue(owner, "old", f.clock.get() + 1000L, "posts:read").getPlainKey();
        f.clock.addAndGet(2000L);
        List<String> answers = new ArrayList<>();
        for (String bad : new String[] {unknownPrefix(k), wrongSecret(k), expired}) {
            ClientResponse r = withKey("/api/x", bad);
            answers.add(r.status() + " " + W8.code(r));
        }
        assertEquals("[401 invalid_api_key, 401 invalid_api_key, 401 invalid_api_key]", answers.toString());
        assertEquals(200, withKey("/api/x", k).status());
    }

    // SEC-APIKEY-004 AC-2: a wrong key on POST /auth/refresh -> 401 invalid_api_key; a valid key -> 403
    // api_key_not_allowed (endpoint restriction after key authentication)
    @Test
    public void SEC_APIKEY_004_AC2_authenticationBeforeTheEndpointRestriction() throws Exception {
        start(b -> b);
        String k = key("posts:read");
        ClientResponse wrong = f.post("/auth/refresh", null, "Authorization", "ApiKey " + wrongSecret(k));
        assertEquals(401, wrong.status());
        assertEquals("invalid_api_key", W8.code(wrong));
        ClientResponse valid = f.post("/auth/refresh", null, "Authorization", "ApiKey " + k);
        assertEquals(403, valid.status());
        assertEquals("api_key_not_allowed", W8.code(valid));
    }

    // SEC-APIKEY-004 AC-3: stored prefix aBcd1234, request prefix AbCD1234, a store that returns the aBcd1234 row for
    // that lookup -> 401 invalid_api_key (byte-wise prefix re-compare)
    @Test
    public void SEC_APIKEY_004_AC3_prefixIsReComparedBytewise() throws Exception {
        start(b -> b);
        String secret = "S3cretS3cretS3cretS3cretS3cretAB";
        keys.ignoreCase = true;
        keys.insertIfUnderLimit(StoredApiKey.builder().id("k-case").prefix("aBcd1234")
                .secretHash(ApiKeyFormat.secretHash(secret)).ownerUserId(owner)
                .ownerVer(f.stored(owner).getTokenVersion()).name("n").scopes(Collections.singleton("posts:read"))
                .createdAt(W8.T0).build(), 20, W8.T0);
        assertEquals(200, withKey("/api/x", "nk_aBcd1234_" + secret).status());
        ClientResponse other = withKey("/api/x", "nk_AbCD1234_" + secret);
        assertEquals(401, other.status());
        assertEquals("invalid_api_key", W8.code(other));
    }

    /** A user store counting findById per id. */
    static final class OwnerReads extends ForwardingUserStore {
        final AtomicInteger reads = new AtomicInteger();
        volatile boolean fail;
        volatile long rollback;

        OwnerReads(UserStore d) {
            super(d);
        }

        @Override
        public @Nullable AuthUser findById(String id) throws Exception {
            reads.incrementAndGet();
            if (fail) {
                throw new java.sql.SQLException("user store down");
            }
            AuthUser u = super.findById(id);
            if (u != null && rollback > 0) {
                return u.toBuilder().tokenVersion(u.getTokenVersion() - rollback).build();
            }
            return u;
        }
    }

    // SEC-APIKEY-004 AC-4: the owner is not read from the store or the principal cache when the secret does not match
    @Test
    public void SEC_APIKEY_004_AC4_noOwnerReadForAWrongSecret() throws Exception {
        OwnerReads reads = new OwnerReads(f.store);
        f.users = reads;
        start(b -> b.userCacheTtl(0));
        String k = key("posts:read");
        int before = reads.reads.get();
        assertEquals(401, withKey("/api/x", wrongSecret(k)).status());
        assertEquals(401, withKey("/api/x", unknownPrefix(k)).status());
        assertEquals(before, reads.reads.get());
        assertEquals(200, withKey("/api/x", k).status());
        assertTrue(reads.reads.get() > before);
    }

    // SEC-APIKEY-004 AC-5: a findByPrefix exception on a protected path -> 503 SERVICE_UNAVAILABLE, not 401; on a public
    // path the request proceeds anonymously, and the error log is limited to once a minute
    @Test
    public void SEC_APIKEY_004_AC5_lookupFailure() throws Exception {
        start(b -> b.permit("/health"));
        String k = key("posts:read");
        keys.failLookup = true;
        ClientResponse prot = withKey("/api/x", k);
        assertEquals(prot.text(), 503, prot.status());
        assertEquals("SERVICE_UNAVAILABLE", W8.code(prot));
        int errors = f.logger.count("ERROR");
        for (int i = 0; i < 3; i++) {
            ClientResponse pub = withKey("/health", k);
            assertEquals(200, pub.status());
            assertEquals("anon", pub.text());
        }
        assertEquals("one ERROR for three public failures within a minute", errors + 1, f.logger.count("ERROR"));
    }

    // SEC-APIKEY-004 AC-6: an exception while re-reading the owner in step 8 (ownerVer above the cached ver) on a
    // protected path -> 503 SERVICE_UNAVAILABLE; on a public path anonymous, the error log once a minute
    @Test
    public void SEC_APIKEY_004_AC6_ownerReReadFailure() throws Exception {
        OwnerReads reads = new OwnerReads(f.store);
        f.users = reads;
        start(b -> b.permit("/health"));
        String early = key("posts:read");
        assertEquals(200, withKey("/api/x", early).status()); // caches the owner at its ver
        f.store.bumpTokenVersion(owner); // another node raised ver and issued a key for it
        String later = key("posts:read");
        reads.fail = true;
        ClientResponse prot = withKey("/api/x", later);
        assertEquals(prot.text(), 503, prot.status());
        assertEquals("SERVICE_UNAVAILABLE", W8.code(prot));
        int errors = f.logger.count("ERROR");
        for (int i = 0; i < 3; i++) {
            assertEquals("anon", withKey("/health", later).text());
        }
        assertEquals(errors + 1, f.logger.count("ERROR"));
    }

    // SEC-APIKEY-004 AC-7: in PROD a plain-text non-local key request to a protected path -> 403 insecure_transport
    // before the Authorization header is read (no key lookup)
    @Test
    public void SEC_APIKEY_004_AC7_transportBeforeTheKey() throws Exception {
        owner = f.user("bot", W8.PW, "reader");
        f.start("203.0.113.7", false, b -> b.mode(AuthMode.PROD).apiKeys(keys));
        String k = f.auth.apiKeys().issue(owner, "ci", null, "posts:read").getPlainKey();
        int lookups = keys.lookups.get();
        ClientResponse r = withKey("/api/x", k);
        assertEquals(403, r.status());
        assertEquals("insecure_transport", W8.code(r));
        assertEquals(lookups, keys.lookups.get());
    }

    // SEC-APIKEY-004 AC-8: with every hash slot taken a valid key authenticates without waiting for a slot, and a
    // wrong-key 401 is not delayed to the 1.25T login floor (the floor is never started)
    @Test(timeout = 20000)
    public void SEC_APIKEY_004_AC8_noSlotAndNoFloor() throws Exception {
        start(b -> b.hashConcurrency(1));
        String k = key("posts:read");
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch taken = new CountDownLatch(1);
        f.auth.hashSlots().submit(new Callable<String>() {
            @Override
            public String call() throws Exception {
                taken.countDown();
                release.await(15, TimeUnit.SECONDS);
                return "x";
            }
        }, (v, e) -> { });
        assertTrue(taken.await(5, TimeUnit.SECONDS));
        try {
            long requested = f.auth.hashSlots().requested();
            long t0 = System.nanoTime();
            assertEquals(200, withKey("/api/x", k).status());
            assertEquals(401, withKey("/api/x", wrongSecret(k)).status());
            assertEquals("no hash slot was asked for", requested, f.auth.hashSlots().requested());
            // a sanity bound under the 5-second slot wait cap; the slot count above is the proof
            assertTrue("no slot wait", System.nanoTime() - t0 < TimeUnit.SECONDS.toNanos(5));
            assertNull("the failure floor was never used", f.auth.failureFloorIfStarted());
        } finally {
            release.countDown();
        }
    }

    // SEC-APIKEY-004 AC-9: an unknown prefix runs exactly one constant-time comparison against a dummy hash before its
    // 401, the same count as a known prefix with a wrong secret
    @Test
    public void SEC_APIKEY_004_AC9_oneComparisonEitherWay() throws Exception {
        start(b -> b);
        String k = key("posts:read");
        final AtomicInteger compares = new AtomicInteger();
        f.auth.keyCredentials().compareHook(compares::incrementAndGet);
        assertEquals(401, withKey("/api/x", unknownPrefix(k)).status());
        assertEquals(1, compares.get());
        assertEquals(401, withKey("/api/x", wrongSecret(k)).status());
        assertEquals(2, compares.get());
    }

    // SEC-APIKEY-004 AC-10: step 10 before step 11: opt-in off, local, a valid key K with scopes posts:read whose
    // owner holds * through admin, POST /auth/admin/apikeys with a well-formed body -> 403 api_key_not_allowed, not
    // 403 insufficient_scope; no WWW-Authenticate and no key is issued
    @Test
    public void SEC_APIKEY_004_AC10_endpointRestrictionBeforeTheScopeGuard() throws Exception {
        owner = f.user("root", W8.PW, "admin");
        f.start(b -> b.apiKeys(keys).roles(RolePolicy.builtin()));
        String k = f.auth.apiKeys().issue(owner, "ci", null, "posts:read").getPlainKey();
        int before = keys.d.listByOwner(owner).size();
        ClientResponse r = f.post("/auth/admin/apikeys", "{\"ownerUserId\":\"" + owner + "\",\"name\":\"n\","
                + "\"scopes\":[\"posts:read\"]}", "Authorization", "ApiKey " + k);
        assertEquals(r.text(), 403, r.status());
        assertEquals("api_key_not_allowed", W8.code(r));
        assertNull(r.header("WWW-Authenticate"));
        assertEquals(before, keys.d.listByOwner(owner).size());
    }

    // ---------------------------------------------------------------------------------------------- SEC-APIKEY-005

    private void startProxied(java.util.function.UnaryOperator<Auth.Builder> config) throws Exception {
        f.proxied = true;
        start(config);
    }

    // SEC-APIKEY-005 AC-1: 101 wrong-key requests from one IP within 15 minutes to a protected path: 1-100 get 401,
    // 101 gets 429 too_many_attempts with Retry-After and no findByPrefix call
    // SEC-APIKEY-005 AC-3: from an IP over the limit, Authorization: ApiKey abc on a protected path -> 429 (the limit
    // check precedes parsing)
    @Test
    public void SEC_APIKEY_005_AC1_AC3_theHundredFirstIs429() throws Exception {
        startProxied(b -> b);
        String bad = wrongSecret(key("posts:read"));
        for (int i = 1; i <= 100; i++) {
            ClientResponse r = fromIp("198.51.100.1", "/api/x", bad);
            assertEquals("request " + i, 401, r.status());
            assertEquals("invalid_api_key", W8.code(r));
        }
        int lookups = keys.lookups.get();
        ClientResponse r = fromIp("198.51.100.1", "/api/x", bad);
        assertEquals(429, r.status());
        assertEquals("too_many_attempts", W8.code(r));
        assertNotNull(r.header("Retry-After"));
        assertEquals(lookups, keys.lookups.get());
        ClientResponse junk = f.get("/api/x", "Authorization", "ApiKey abc", "X-Forwarded-For", "198.51.100.1");
        assertEquals(429, junk.status());
        assertEquals(200, fromIp("198.51.100.2", "/api/x", key("posts:read")).status());
    }

    // SEC-APIKEY-005 AC-2: 200 requests from one IP with an expired key whose secret matches: 1-100 get 401, from 101
    // on 429 without lookup
    @Test
    public void SEC_APIKEY_005_AC2_expiredKeysCountToo() throws Exception {
        startProxied(b -> b);
        String expired = f.auth.apiKeys().issue(owner, "old", f.clock.get() + 1000L, "posts:read").getPlainKey();
        f.clock.addAndGet(2000L);
        for (int i = 1; i <= 200; i++) {
            int lookups = keys.lookups.get();
            ClientResponse r = fromIp("198.51.100.3", "/api/x", expired);
            if (i <= 100) {
                assertEquals("request " + i, 401, r.status());
            } else {
                assertEquals("request " + i, 429, r.status());
                assertEquals(lookups, keys.lookups.get());
            }
        }
    }

    // SEC-APIKEY-005 AC-4: 50 wrong keys then a valid key from the same IP: the valid key authenticates and the counter
    // is not reset (the next 50 wrong keys reach the limit)
    @Test
    public void SEC_APIKEY_005_AC4_successDoesNotReset() throws Exception {
        startProxied(b -> b);
        String good = key("posts:read");
        String bad = wrongSecret(good);
        for (int i = 0; i < 50; i++) {
            assertEquals(401, fromIp("198.51.100.4", "/api/x", bad).status());
        }
        assertEquals(200, fromIp("198.51.100.4", "/api/x", good).status());
        for (int i = 0; i < 50; i++) {
            assertEquals(401, fromIp("198.51.100.4", "/api/x", bad).status());
        }
        assertEquals(429, fromIp("198.51.100.4", "/api/x", bad).status());
        assertEquals(429, fromIp("198.51.100.4", "/api/x", good).status());
    }

    // SEC-APIKEY-005 AC-5: 20 login failures from one IP that make the login IP key return 429 do not block key
    // authentication from that IP; 100 key failures do not consume the login IP limit
    @Test
    public void SEC_APIKEY_005_AC5_independentOfLoginThrottling() throws Exception {
        startProxied(b -> b);
        String good = key("posts:read");
        for (int i = 0; i < 20; i++) {
            f.post("/auth/login", W8.login("user" + (i % 5), "wrong password!"), "X-Forwarded-For", "198.51.100.5");
        }
        ClientResponse login = f.post("/auth/login", W8.login("someone", "wrong password!"), "X-Forwarded-For",
                "198.51.100.5");
        assertEquals(429, login.status());
        assertEquals(200, fromIp("198.51.100.5", "/api/x", good).status());
        for (int i = 0; i < 100; i++) {
            assertEquals(401, fromIp("198.51.100.6", "/api/x", wrongSecret(good)).status());
        }
        ClientResponse ok = f.post("/auth/login", W8.login("bot", W8.PW), "X-Forwarded-For", "198.51.100.6");
        assertEquals(ok.text(), 200, ok.status());
    }

    // SEC-APIKEY-005 AC-6: an IP over the key limit sending a key to a public path gets an anonymous response with no
    // 429 and no lookup
    // SEC-APIKEY-005 AC-10: on the public path GET /health a wrong key gets the anonymous 200 and is counted; after 100
    // such requests from one IP, request 101 to a protected path gets 429 without findByPrefix
    @Test
    public void SEC_APIKEY_005_AC6_AC10_publicPaths() throws Exception {
        startProxied(b -> b.permit("/health"));
        String good = key("posts:read");
        for (int i = 0; i < 100; i++) {
            ClientResponse r = fromIp("198.51.100.7", "/health", wrongSecret(good));
            assertEquals(200, r.status());
            assertEquals("anon", r.text());
        }
        int lookups = keys.lookups.get();
        ClientResponse prot = fromIp("198.51.100.7", "/api/x", good);
        assertEquals(429, prot.status());
        assertEquals(lookups, keys.lookups.get());
        ClientResponse pub = fromIp("198.51.100.7", "/health", good);
        assertEquals(200, pub.status());
        assertEquals("anon", pub.text());
        assertEquals(lookups, keys.lookups.get());
    }

    /** A throttle whose API key counter calls fail. */
    static final class KeyCounterDown extends ForwardingLoginThrottle {
        volatile boolean down;

        KeyCounterDown() {
            super(InMemoryLoginThrottle.builder().build());
        }

        @Override
        public long retryAfter(String key, int max, long windowMillis) throws Exception {
            if (down && key.startsWith("apikey:")) {
                throw new java.sql.SQLException("throttle down");
            }
            return super.retryAfter(key, max, windowMillis);
        }
    }

    // SEC-APIKEY-005 AC-7: a LoginThrottle exception during a key request -> 503 JSON on a protected path; on a public
    // path anonymous without key lookup, the error log once a minute
    @Test
    public void SEC_APIKEY_005_AC7_throttleFailure() throws Exception {
        KeyCounterDown throttle = new KeyCounterDown();
        start(b -> b.permit("/health").loginThrottle(throttle));
        String good = key("posts:read");
        throttle.down = true;
        int lookups = keys.lookups.get();
        ClientResponse prot = withKey("/api/x", good);
        assertEquals(503, prot.status());
        assertEquals("SERVICE_UNAVAILABLE", W8.code(prot));
        int errors = f.logger.count("ERROR");
        for (int i = 0; i < 3; i++) {
            assertEquals("anon", withKey("/health", good).text());
        }
        assertEquals(lookups, keys.lookups.get());
        assertEquals(errors + 1, f.logger.count("ERROR"));
    }

    // SEC-APIKEY-005 AC-8: login throttling and hard lockout of a service account name do not stop its key
    @Test
    public void SEC_APIKEY_005_AC8_lockoutOfAServiceAccountNameDoesNotStopItsKey() throws Exception {
        f.start(b -> b.apiKeys(keys).lockout(Lockout.after(3).forMinutes(15)));
        AuthUser svc = f.auth.users().create("svc", null, u -> u.toBuilder().serviceAccount(true).build(), "reader");
        String k = f.auth.apiKeys().issue(svc.getId(), "ci", null, "posts:read").getPlainKey();
        for (int i = 0; i < 12; i++) {
            f.post("/auth/login", W8.login("svc", "guessing " + i));
        }
        assertEquals(429, f.post("/auth/login", W8.login("svc", "guessing")).status());
        assertEquals(200, withKey("/api/x", k).status());
    }

    // SEC-APIKEY-005 AC-9: an expired key retried after deleteExpired removed it gets 401 invalid_api_key (unknown
    // prefix) and is counted exactly as before the cleanup
    @Test
    public void SEC_APIKEY_005_AC9_cleanupDoesNotChangeTheCount() throws Exception {
        startProxied(b -> b);
        String expired = f.auth.apiKeys().issue(owner, "old", f.clock.get() + 1000L, "posts:read").getPlainKey();
        f.clock.addAndGet(2000L);
        for (int i = 0; i < 50; i++) {
            assertEquals(401, fromIp("198.51.100.8", "/api/x", expired).status());
        }
        f.auth.cleanupRun();
        assertNull(keys.d.findByPrefix(expired.substring(3, 11)));
        for (int i = 0; i < 50; i++) {
            ClientResponse r = fromIp("198.51.100.8", "/api/x", expired);
            assertEquals(401, r.status());
            assertEquals("invalid_api_key", W8.code(r));
        }
        assertEquals(429, fromIp("198.51.100.8", "/api/x", expired).status());
    }

    // SEC-APIKEY-005 AC-11: docs/api states that the key-only IP limit also blocks valid keys from the same IP (NAT or
    // proxy) as a residual risk
    @Test
    public void SEC_APIKEY_005_AC11_docsStateTheSharedIpRisk() throws Exception {
        String ko = read("docs/api/auth.md");
        String en = read("docs/api/en/auth.md");
        assertTrue(ko.contains("같은 IP") && ko.contains("유효한 키도 함께 막힙니다"));
        assertTrue(en.contains("also blocks valid keys sent from the same IP"));
    }

    static String read(String repoPath) throws Exception {
        File f = new File(repoPath);
        if (!f.exists()) {
            f = new File("../" + repoPath);
        }
        return new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------------------------------------- SEC-APIKEY-006

    // SEC-APIKEY-006 AC-1: cached ver 6, key ownerVer 5 -> 401 invalid_api_key, no owner re-read, and
    // deleteStaleByOwner(u, 6) is scheduled off the request path
    @Test
    public void SEC_APIKEY_006_AC1_aStaleKeySchedulesItsDeletion() throws Exception {
        OwnerReads reads = new OwnerReads(f.store);
        f.users = reads;
        start(b -> b);
        String old = key("posts:read");
        f.store.bumpTokenVersion(owner);
        String fresh = key("posts:read");
        assertEquals(200, withKey("/api/x", fresh).status()); // the cache holds the new ver
        long ver = f.stored(owner).getTokenVersion();
        keys.deletes.clear(); // the issuances ran their own future-key deletion
        int r0 = reads.reads.get();
        ClientResponse r = withKey("/api/x", old);
        assertEquals(401, r.status());
        assertEquals("invalid_api_key", W8.code(r));
        assertEquals("no owner re-read", r0, reads.reads.get());
        assertTrue("not on the request path", keys.deletes.isEmpty());
        assertEquals(1, held.queued.size());
        held.runAll();
        assertEquals(Collections.singletonList("deleteStaleByOwner " + owner + " " + ver), keys.deletes);
        assertEquals(200, withKey("/api/x", fresh).status());
    }

    // SEC-APIKEY-006 AC-2: node A raises ver and issues K6 (ownerVer 6); node B with the old cached ver receives K6:
    // B re-reads the owner, authenticates, refreshes its cache and does not delete K6
    @Test
    public void SEC_APIKEY_006_AC2_aNewerKeyReReadsTheOwnerOnce() throws Exception {
        start(b -> b);
        W8 b = nodeB(x -> x);
        String k5 = key("posts:read");
        assertEquals(200, withKey(b, "/api/x", k5).status()); // B caches the owner
        f.store.bumpTokenVersion(owner);
        String k6 = key("posts:read");
        assertEquals(200, withKey(b, "/api/x", k6).status());
        assertEquals(f.stored(owner).getTokenVersion(), b.auth.principalCache().get(owner).getTokenVersion());
        held.runAll();
        assertFalse(keys.deletes.toString(), keys.deletes.toString().contains("deleteById"));
        assertEquals(200, withKey(b, "/api/x", k6).status());
    }

    // SEC-APIKEY-006 AC-3: SINGLE_INSTANCE, a store whose ver went back by one, a key with the newer ownerVer -> 401,
    // counted in the key IP counter, one ERROR within a minute for repeated requests, and deleteById off the request
    // SEC-APIKEY-006 AC-6: until deleted the invariant-violating key is listed with status stale
    @Test
    public void SEC_APIKEY_006_AC3_AC6_invariantViolationUnderSingleInstance() throws Exception {
        OwnerReads reads = new OwnerReads(f.store);
        f.users = reads;
        startProxied(b -> b);
        IssuedApiKey issued = f.auth.apiKeys().issue(owner, "ci", null, "posts:read");
        String k = issued.getPlainKey();
        keys.deletes.clear(); // the issuance ran its own future-key deletion
        reads.rollback = 1; // the file store lost its last ver++
        int errors = f.logger.count("ERROR");
        for (int i = 0; i < 3; i++) {
            ClientResponse r = fromIp("198.51.100.9", "/api/x", k);
            assertEquals(401, r.status());
            assertEquals("invalid_api_key", W8.code(r));
        }
        assertEquals("one ERROR a minute", errors + 1, f.logger.count("ERROR"));
        assertTrue(keys.deletes.isEmpty());
        assertEquals(ApiKeyStatus.STALE, f.auth.apiKeys().list(owner).get(0).getStatus());
        held.runAll();
        assertEquals(Collections.singletonList("deleteById " + issued.getKey().getId()), keys.deletes);
        for (int i = 0; i < 97; i++) {
            assertEquals(401, fromIp("198.51.100.9", "/api/x", k).status());
        }
        assertEquals("the three violations were counted", 429, fromIp("198.51.100.9", "/api/x", k).status());
    }

    // SEC-APIKEY-006 AC-5: a disabled owner's key with a matching secret -> 401 invalid_api_key
    @Test
    public void SEC_APIKEY_006_AC5_disabledOwner() throws Exception {
        start(b -> b.userCacheTtl(0));
        String k = key("posts:read");
        f.store.setEnabled(owner, false);
        ClientResponse r = withKey("/api/x", k);
        assertEquals(401, r.status());
        assertEquals("invalid_api_key", W8.code(r));
    }

    // ---------------------------------------------------------------------------------------------- SEC-APIKEY-007

    // SEC-APIKEY-007 AC-1: key scopes posts:*, owner demoted to posts:read: a posts:read route passes, a posts:write
    // route gets 403 insufficient_scope
    // SEC-APIKEY-007 AC-5: 403 answers to key requests carry no WWW-Authenticate header
    @Test
    public void SEC_APIKEY_007_AC1_AC5_effectiveScopesAreTheIntersection() throws Exception {
        f.routes = w -> w.app.get("/api/w", w.auth.require("posts:write"), (req, res) -> res.send("w"));
        start(b -> b);
        String k = key("posts:*");
        assertEquals(200, withKey("/api/w", k).status());
        f.store.setRoles(owner, Collections.singleton("reader"));
        f.auth.userChanged(owner);
        assertEquals(200, withKey("/api/x", k).status());
        ClientResponse w = withKey("/api/w", k);
        assertEquals(403, w.status());
        assertEquals("insufficient_scope", W8.code(w));
        assertNull(w.header("WWW-Authenticate"));
        String narrow = key("posts:read");
        f.store.setRoles(owner, new java.util.LinkedHashSet<>(java.util.Arrays.asList("reader", "writer")));
        f.auth.userChanged(owner);
        ClientResponse n = withKey("/api/w", narrow);
        assertEquals(403, n.status());
        assertNull(n.header("WWW-Authenticate"));
    }

    // SEC-APIKEY-007 AC-2: auth.user(req) on a key request returns the owner and getApiKeyId() the key id
    // SEC-APIKEY-007 AC-3: for a JWT request getApiKeyId() is null
    @Test
    public void SEC_APIKEY_007_AC2_AC3_thePrincipalIsTheOwner() throws Exception {
        f.routes = w -> w.app.get("/api/who", w.auth.authenticated(), (req, res) -> {
            AuthPrincipal p = w.auth.user(req);
            res.send(p.getId() + "|" + p.getUsername() + "|" + p.getApiKeyId());
        });
        start(b -> b);
        IssuedApiKey issued = f.auth.apiKeys().issue(owner, "ci", null, "posts:read");
        ClientResponse r = withKey("/api/who", issued.getPlainKey());
        assertEquals(owner + "|bot|" + issued.getKey().getId(), r.text());
        String at = (String) f.loginBody("bot", W8.PW).get("accessToken");
        assertEquals(owner + "|bot|null", f.get("/api/who", "Authorization", "Bearer " + at).text());
    }

    // SEC-APIKEY-007 AC-4: an audit event produced by a key-authenticated admin action carries the key id
    @Test
    public void SEC_APIKEY_007_AC4_auditCarriesTheKeyId() throws Exception {
        owner = f.user("root", W8.PW, "admin");
        String target = f.user("target", W8.PW, "reader");
        f.start(b -> b.apiKeys(keys).audit(audit::add).allowAdminScopesOnApiKeys(true)
                .roles(RolePolicy.builder().role("reader", "posts:read").build()));
        IssuedApiKey issued = f.auth.apiKeys().issue(owner, "ci", null, "auth-users:write");
        ClientResponse r = f.adapter.send(ClientRequest.json("PATCH", "/auth/admin/users/" + target,
                "{\"roles\":[\"admin\"]}").header("Authorization", "ApiKey " + issued.getPlainKey()));
        assertEquals(r.text(), 403, r.status());
        AuditEvent e = null;
        for (AuditEvent x : audit) {
            if (AuditEvent.GRANT_DENIED.equals(x.getType()) && target.equals(x.getTargetId())) {
                e = x;
            }
        }
        assertNotNull("the denied grant is audited", e);
        assertEquals(owner, e.getActorId());
        assertEquals(issued.getKey().getId(), e.getDetails().get("apiKeyId"));
    }

    // SEC-APIKEY-007 AC-6: after the owner is demoted on node A, the key's effective scopes shrink on node A at once
    // and on another node within 5 seconds
    // SEC-IAM-003 AC-4: a demoted user (role removed, ver unchanged) loses the removed scopes on the acting node at once
    // and on other nodes within userCacheTtl (5 s); a key of that user loses the scope by "key covers and owner covers"
    @Test
    public void SEC_APIKEY_007_AC6_SEC_IAM_003_AC4_demotionReachesEveryNode() throws Exception {
        f.user("root", W8.PW, "admin");
        f.routes = w -> w.app.get("/api/w", w.auth.require("posts:write"), (req, res) -> res.send("w"));
        start(b -> b.roles(RolePolicy.builder().role("reader", "posts:read").role("writer", "posts:write").build()));
        W8 b = nodeB(x -> x.roles(RolePolicy.builder().role("reader", "posts:read").role("writer", "posts:write")
                .build()), w -> w.app.get("/api/w", w.auth.require("posts:write"), (req, res) -> res.send("w")));
        String k = key("posts:*");
        String at = (String) f.loginBody("bot", W8.PW).get("accessToken");
        assertEquals(200, withKey("/api/w", k).status());
        assertEquals(200, withKey(b, "/api/x", k).status());
        assertEquals(200, withKey(b, "/api/w", k).status());
        assertEquals(200, b.get("/api/w", "Authorization", "Bearer " + at).status());
        long ver = f.stored(owner).getTokenVersion();
        String rootToken = (String) f.loginBody("root", W8.PW).get("accessToken");
        ClientResponse demote = f.adapter.send(ClientRequest.json("PATCH", "/auth/admin/users/" + owner,
                "{\"roles\":[\"reader\"]}").header("Authorization", "Bearer " + rootToken));
        assertEquals(demote.text(), 200, demote.status());
        assertEquals(ver, f.stored(owner).getTokenVersion());
        assertEquals(403, withKey("/api/w", k).status());
        assertEquals(403, f.get("/api/w", "Authorization", "Bearer " + at).status());
        assertEquals("node B within its cache window", 200, withKey(b, "/api/w", k).status());
        b.clock.addAndGet(5000L);
        assertEquals(403, withKey(b, "/api/w", k).status());
        assertEquals(403, b.get("/api/w", "Authorization", "Bearer " + at).status());
        assertEquals(200, withKey(b, "/api/x", k).status());
    }

    // ---------------------------------------------------------------------------------------------- REL-APIKEY-001

    // REL-APIKEY-001 AC-1: nodes A and B share one store; after DELETE /auth/me/apikeys/:id on node A the next request
    // with that key to node B gets 401 invalid_api_key
    // REL-APIKEY-001 AC-2: after auth.apiKeys().revoke(keyId) returns true the next request gets 401 with no wait
    // REL-APIKEY-001 AC-3: two consecutive requests with the same valid key each call findByPrefix once
    @Test
    public void REL_APIKEY_001_AC1_AC2_AC3_keyRowsAreNeverCached() throws Exception {
        start(b -> b);
        W8 b = nodeB(x -> x);
        IssuedApiKey one = f.auth.apiKeys().issue(owner, "one", null, "posts:read");
        IssuedApiKey two = f.auth.apiKeys().issue(owner, "two", null, "posts:read");
        int lookups = keys.lookups.get();
        assertEquals(200, withKey(b, "/api/x", one.getPlainKey()).status());
        assertEquals(200, withKey(b, "/api/x", one.getPlainKey()).status());
        assertEquals(lookups + 2, keys.lookups.get());
        String at = (String) f.loginBody("bot", W8.PW).get("accessToken");
        ClientResponse del = f.adapter.send(ClientRequest.of("DELETE", "/auth/me/apikeys/" + one.getKey().getId())
                .header("Authorization", "Bearer " + at));
        assertEquals(204, del.status());
        ClientResponse gone = withKey(b, "/api/x", one.getPlainKey());
        assertEquals(401, gone.status());
        assertEquals("invalid_api_key", W8.code(gone));
        assertEquals(200, withKey("/api/x", two.getPlainKey()).status());
        assertTrue(f.auth.apiKeys().revoke(two.getKey().getId()));
        assertEquals(401, withKey("/api/x", two.getPlainKey()).status());
        assertEquals(401, withKey(b, "/api/x", two.getPlainKey()).status());
    }

    // REL-APIKEY-001 AC-4: after the owner is disabled on node A, a key request to node A gets 401 at once and one to
    // node B no later than 5 seconds after the disable
    @Test
    public void REL_APIKEY_001_AC4_disableReachesTheOtherNodeInTheCacheWindow() throws Exception {
        f.user("root", W8.PW, "admin");
        start(b -> b);
        W8 b = nodeB(x -> x);
        String k = key("posts:read");
        assertEquals(200, withKey("/api/x", k).status());
        assertEquals(200, withKey(b, "/api/x", k).status());
        String rootToken = (String) f.loginBody("root", W8.PW).get("accessToken");
        ClientResponse off = f.adapter.send(ClientRequest.json("PATCH", "/auth/admin/users/" + owner,
                "{\"enabled\":false}").header("Authorization", "Bearer " + rootToken));
        assertEquals(off.text(), 200, off.status());
        assertEquals(401, withKey("/api/x", k).status());
        b.clock.addAndGet(5000L);
        ClientResponse late = withKey(b, "/api/x", k);
        assertEquals(401, late.status());
        assertEquals("invalid_api_key", W8.code(late));
    }

    // REL-APIKEY-001 AC-2: the 401 right after revoke returned true carries the body code invalid_api_key
    @Test
    public void REL_APIKEY_001_AC2_aRevokedKeyIsInvalidAtOnce() throws Exception {
        start(b -> b);
        IssuedApiKey k = f.auth.apiKeys().issue(owner, "k", null, "posts:read");
        assertEquals(200, withKey("/api/x", k.getPlainKey()).status());
        assertTrue(f.auth.apiKeys().revoke(k.getKey().getId()));
        ClientResponse r = withKey("/api/x", k.getPlainKey());
        assertEquals(401, r.status());
        assertEquals("invalid_api_key", W8.code(r));
    }

    // REL-APIKEY-001 AC-4 (acting node): the 401 on node A right after the disable carries the code invalid_api_key
    @Test
    public void REL_APIKEY_001_AC4_theActingNodeAnswersInvalidApiKey() throws Exception {
        f.user("root", W8.PW, "admin");
        start(b -> b);
        String k = key("posts:read");
        assertEquals(200, withKey("/api/x", k).status());
        String rootToken = (String) f.loginBody("root", W8.PW).get("accessToken");
        ClientResponse off = f.adapter.send(ClientRequest.json("PATCH", "/auth/admin/users/" + owner,
                "{\"enabled\":false}").header("Authorization", "Bearer " + rootToken));
        assertEquals(off.text(), 200, off.status());
        ClientResponse r = withKey("/api/x", k);
        assertEquals(401, r.status());
        assertEquals("invalid_api_key", W8.code(r));
    }

    // ---------------------------------------------------------------------------------------------- FR-APIKEY-002

    // FR-APIKEY-002 AC-1: nowMillis() equal to expiresAt -> 401 invalid_api_key
    // FR-APIKEY-002 AC-2: nowMillis() equal to expiresAt - 1 -> the key authenticates
    // FR-APIKEY-002 AC-3: a key issued with expiresAt null authenticates at Long.MAX_VALUE - 1
    @Test
    public void FR_APIKEY_002_AC1_AC2_AC3_expiryWithoutLeeway() throws Exception {
        start(b -> b);
        long exp = f.clock.get() + 60_000L;
        String k = f.auth.apiKeys().issue(owner, "e", exp, "posts:read").getPlainKey();
        String forever = key("posts:read");
        f.clock.set(exp - 1);
        assertEquals(200, withKey("/api/x", k).status());
        f.clock.set(exp);
        ClientResponse r = withKey("/api/x", k);
        assertEquals(401, r.status());
        assertEquals("invalid_api_key", W8.code(r));
        f.clock.set(Long.MAX_VALUE - 1);
        assertEquals(200, withKey("/api/x", forever).status());
    }

    // FR-APIKEY-002 AC-4: deleteExpired(now) removes keys whose non-null expiresAt is <= now and keeps null ones
    // FR-APIKEY-002 AC-5: with .apiKeys(store) one run of the 10-minute cleanup that calls purgeExpired also calls
    // deleteExpired once with that nowMillis: the expired key K is gone and the non-expiring N remains; without
    // .apiKeys(store) no ApiKeyStore call; after onStop no further call; a deleteExpired exception changes no answer
    @Test
    public void FR_APIKEY_002_AC4_AC5_cleanupDeletesExpiredKeys() throws Exception {
        start(b -> b);
        long exp = f.clock.get() + 1000L;
        IssuedApiKey k = f.auth.apiKeys().issue(owner, "k", exp, "posts:read");
        IssuedApiKey n = f.auth.apiKeys().issue(owner, "n", null, "posts:read");
        IssuedApiKey edge = f.auth.apiKeys().issue(owner, "edge", exp + 5000L, "posts:read");
        f.clock.set(exp + 5000L);
        f.auth.cleanupRun();
        assertEquals(1, keys.expiredCalls.get());
        assertEquals(exp + 5000L, keys.expiredAt.get());
        assertNull(keys.d.findByPrefix(k.getKey().getPrefix()));
        assertNull("expiresAt equal to now is removed", keys.d.findByPrefix(edge.getKey().getPrefix()));
        assertNotNull(keys.d.findByPrefix(n.getKey().getPrefix()));
        assertEquals(200, withKey("/api/x", n.getPlainKey()).status());
        // an exception of the off-request deleteExpired (the IR-APIKEY-004 rule) is logged and changes no answer
        keys.failExpired = true;
        f.auth.cleanupRun();
        assertEquals(200, withKey("/api/x", n.getPlainKey()).status());
        keys.failExpired = false;
        assertTrue(f.auth.cleanupScheduled());
        f.stop();
        assertFalse("the cleanup scheduler stopped in onStop", f.auth.cleanupScheduled());
        int calls = keys.expiredCalls.get();
        W8 none = new W8();
        others.add(none);
        none.start(b -> b);
        assertNull("without .apiKeys(store) the cleanup has no key store to call", none.auth.apiKeyStore());
        none.auth.cleanupRun();
        // the stopped node made no call while the other one started and ran its cleanup
        assertEquals(calls, keys.expiredCalls.get());
        // after onStop no further call, against a scheduler seen ticking: a 20 ms cleanup calls deleteExpired on its
        // own until onStop, and not once in the 25 intervals after it
        Keys ticked = new Keys();
        W8 ticking = new W8();
        others.add(ticking);
        ticking.start(b -> b.apiKeys(ticked).cleanupInterval(20L));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (ticked.expiredCalls.get() < 2 && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertTrue("the scheduler calls deleteExpired: " + ticked.expiredCalls, ticked.expiredCalls.get() >= 2);
        ticking.stop(); // onStop waits for a run in progress to end
        assertFalse(ticking.auth.cleanupScheduled());
        int stopped = ticked.expiredCalls.get();
        long quiet = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500); // 25 intervals; a late call ends it
        while (ticked.expiredCalls.get() == stopped && System.nanoTime() < quiet) {
            Thread.sleep(5);
        }
        assertEquals("no deleteExpired after onStop", stopped, ticked.expiredCalls.get());
    }

    // ---------------------------------------------------------------------------------------------- SEC-APIKEY-003

    // SEC-APIKEY-003 AC-1: a key from auth.apiKeys().issue matches nk_[A-Za-z0-9]{8}_[A-Za-z0-9]{32}
    // SEC-APIKEY-003 AC-2: the stored row holds the 8 prefix characters and the lower-case hex SHA-256 of the 32 secret
    // characters; no store field holds the plaintext secret
    // SEC-APIKEY-003 AC-3: ApiKey from list(ownerUserId) and from the list endpoint carries no secret and no hash
    // SEC-APIKEY-003 AC-4: on a protected path with a non-exhausted IP, Authorization: ApiKey abc -> 401
    // invalid_api_key, counted in the key-only IP counter
    // SEC-APIKEY-003 AC-5: a 7-character prefix and a secret containing '-' both get 401 invalid_api_key
    @Test
    public void SEC_APIKEY_003_AC1_AC5_formatAndStorage() throws Exception {
        startProxied(b -> b);
        IssuedApiKey issued = f.auth.apiKeys().issue(owner, "ci", null, "posts:read");
        String plain = issued.getPlainKey();
        assertTrue(plain, plain.matches("nk_[A-Za-z0-9]{8}_[A-Za-z0-9]{32}"));
        StoredApiKey row = keys.d.findById(issued.getKey().getId());
        assertEquals(plain.substring(3, 11), row.getPrefix());
        assertEquals(ApiKeyFormat.secretHash(plain.substring(12)), row.getSecretHash());
        assertTrue(row.getSecretHash().matches("[0-9a-f]{64}"));
        assertFalse(row.toString().contains(plain.substring(12)));
        assertFalse(f.auth.apiKeys().list(owner).get(0).toString().contains(row.getSecretHash()));
        String at = (String) f.loginBody("bot", W8.PW).get("accessToken");
        String listed = f.get("/auth/me/apikeys", "Authorization", "Bearer " + at).text();
        assertFalse(listed.contains(row.getSecretHash()));
        assertFalse(listed.contains(plain.substring(12)));
        assertFalse(listed.contains("secret"));
        for (String bad : new String[] {"abc", "nk_ab12cd3_" + plain.substring(12),
            plain.substring(0, 12) + "-" + plain.substring(13)}) {
            ClientResponse r = f.get("/api/x", "Authorization", "ApiKey " + bad, "X-Forwarded-For", "198.51.100.12");
            assertEquals(bad, 401, r.status());
            assertEquals("invalid_api_key", W8.code(r));
        }
        for (int i = 0; i < 97; i++) {
            fromIp("198.51.100.12", "/api/x", "abc");
        }
        assertEquals("the malformed keys were counted", 429, fromIp("198.51.100.12", "/api/x", plain).status());
    }

    // ---------------------------------------------------------------------------------------------- IR-APIKEY-005

    // IR-APIKEY-005 AC-1: with .apiKeyScheme("ak") no credential gets 401 with WWW-Authenticate: Bearer, ak, and a wrong
    // key as Authorization: ak nk_... gets 401 invalid_api_key with the same challenge (no attribute on either)
    @Test
    public void IR_APIKEY_005_AC1_theConfiguredSchemeNamesTheChallenge() throws Exception {
        start(b -> b.apiKeyScheme("ak"));
        String k = key("posts:read");
        ClientResponse none = f.get("/api/x");
        assertEquals(401, none.status());
        assertEquals("Bearer, ak", none.header("WWW-Authenticate"));
        ClientResponse wrong = f.get("/api/x", "Authorization", "ak " + wrongSecret(k));
        assertEquals(401, wrong.status());
        assertEquals("invalid_api_key", W8.code(wrong));
        assertEquals("Bearer, ak", wrong.header("WWW-Authenticate"));
        assertEquals(200, f.get("/api/x", "Authorization", "ak " + k).status());
    }

    // ---------------------------------------------------------------------------------------------- IR-APIKEY-003

    // IR-APIKEY-003 AC-1: node B cache ver 5, store ver 6, keys of ownerVer 6 and 5: GET /auth/me/apikeys on node B
    // shows the ownerVer 6 key active and the ownerVer 5 key stale
    // IR-APIKEY-003 AC-3: the list JSON has "status":"active" in lower case whatever the app codec does with enums
    @Test
    public void IR_APIKEY_003_AC1_AC3_listingsReadTheStore() throws Exception {
        start(b -> b);
        W8 b = nodeB(x -> x);
        String k5 = key("posts:read");
        assertEquals(200, withKey(b, "/api/x", k5).status()); // B caches the owner at the old ver
        f.store.bumpTokenVersion(owner);
        String k6 = key("posts:read");
        assertTrue(k6.length() > 0);
        long oldVer = f.stored(owner).getTokenVersion() - 1;
        // a token at the old ver, which node B still accepts from its cached old-ver entry: the cache stays old
        String at5 = b.auth.accessToken(owner, oldVer, 900_000L);
        ClientResponse list = b.get("/auth/me/apikeys", "Authorization", "Bearer " + at5);
        assertEquals("node B's cache still holds the old ver", oldVer,
                b.auth.principalCache().get(owner).getTokenVersion());
        assertEquals(list.text(), 200, list.status());
        String text = list.text();
        assertTrue(text, text.contains("\"status\":\"active\""));
        assertTrue(text, text.contains("\"status\":\"stale\""));
        assertFalse(text.contains("ACTIVE"));
    }

    // IR-APIKEY-003 AC-1: node B cache ver 5, store ver 6: the ownerVer 6 key itself is active and the ownerVer 5 key
    // itself is stale (matched by key id, not only both words somewhere in the body)
    @Test
    public void IR_APIKEY_003_AC1_eachKeyGetsItsOwnStatus() throws Exception {
        start(b -> b);
        W8 b = nodeB(x -> x);
        for (int i = 0; i < 5; i++) {
            f.store.bumpTokenVersion(owner);
        }
        IssuedApiKey k5 = f.auth.apiKeys().issue(owner, "five", null, "posts:read");
        assertEquals(200, withKey(b, "/api/x", k5.getPlainKey()).status()); // B caches the owner at ver 5
        f.store.bumpTokenVersion(owner);
        IssuedApiKey k6 = f.auth.apiKeys().issue(owner, "six", null, "posts:read");
        assertEquals(5L, k5.getKey().getOwnerVer());
        assertEquals(6L, k6.getKey().getOwnerVer());
        ClientResponse list = b.get("/auth/me/apikeys", "Authorization", "Bearer " + b.auth.accessToken(owner, 5L,
                900_000L));
        assertEquals(list.text(), 200, list.status());
        assertEquals("node B's cache still holds ver 5", 5L, b.auth.principalCache().get(owner).getTokenVersion());
        java.util.Map<Object, Object> status = new java.util.HashMap<>();
        for (Object o : Wave10PatTest.array(list)) {
            status.put(((java.util.Map<?, ?>) o).get("id"), ((java.util.Map<?, ?>) o).get("status"));
        }
        assertEquals("active", status.get(k6.getKey().getId()));
        assertEquals("stale", status.get(k5.getKey().getId()));
    }

    // IR-APIKEY-003 AC-2: a key whose ownerVer exceeds the store ver is shown as "status":"stale"
    @Test
    public void IR_APIKEY_003_AC2_aFutureKeyIsStale() throws Exception {
        OwnerReads reads = new OwnerReads(f.store);
        f.users = reads;
        start(b -> b);
        key("posts:read");
        reads.rollback = 1;
        String at = f.auth.accessToken(owner, reads.findById(owner).getTokenVersion(), 900_000L);
        ClientResponse list = f.get("/auth/me/apikeys", "Authorization", "Bearer " + at);
        assertEquals(list.text(), 200, list.status());
        assertTrue(list.text(), list.text().contains("\"status\":\"stale\""));
    }

    // IR-APIKEY-003 AC-6: the ApiKeyStatus Javadoc says values may be added later and that under MULTI_INSTANCE a stale
    // key whose ownerVer exceeds the read ver still counts toward maxKeysPerUser; docs/api says the same
    @Test
    public void IR_APIKEY_003_AC6_statusDocs() throws Exception {
        String src = read("NaruServe-api/src/main/java/com/snoworca/naruserve/utils/auth/apikey/ApiKeyStatus.java");
        assertTrue(src.contains("Values may be added in later versions"));
        assertTrue(src.contains("still counts toward {@code maxKeysPerUser}"));
        assertTrue(read("docs/api/en/auth.md").contains("still counts toward `maxKeysPerUser`"));
        assertTrue(read("docs/api/auth.md").contains("`maxKeysPerUser`에 계속 셉니다"));
    }

    // SEC-APIKEY-004 statement: the key path never leaves the anonymous request with a principal on an over-limit IP
    @Test
    public void overLimitPublicRequestStaysAnonymous() throws Exception {
        startProxied(b -> b.permit("/health"));
        String good = key("posts:read");
        for (int i = 0; i < 100; i++) {
            fromIp("198.51.100.10", "/api/x", wrongSecret(good));
        }
        assertEquals("anon", fromIp("198.51.100.10", "/health", good).text());
        assertEquals("user", fromIp("198.51.100.11", "/health", good).text());
        assertEquals("too_many_attempts", W8.code(fromIp("198.51.100.10", "/api/x", good)));
    }
}
