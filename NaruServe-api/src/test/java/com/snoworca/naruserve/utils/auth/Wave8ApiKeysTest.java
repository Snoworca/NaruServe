package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.Method;
import com.snoworca.naruserve.Registration;
import com.snoworca.naruserve.RegistrationKind;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.utils.auth.apikey.ApiKey;
import com.snoworca.naruserve.utils.auth.apikey.ApiKeyPrefixCollisionException;
import com.snoworca.naruserve.utils.auth.apikey.ApiKeyStatus;
import com.snoworca.naruserve.utils.auth.apikey.ApiKeyStore;
import com.snoworca.naruserve.utils.auth.apikey.InMemoryApiKeyStore;
import com.snoworca.naruserve.utils.auth.apikey.IssuedApiKey;
import com.snoworca.naruserve.utils.auth.apikey.StoredApiKey;
import com.snoworca.naruserve.utils.auth.testing.AuthTesting;
import com.snoworca.naruserve.utils.auth.user.AuthUser;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Test;

/**
 * API keys: the in-memory store, the builder options, the app facade auth.apiKeys(), AuthTesting.apiKeyFor, the
 * Authorization-scheme presentation and the WWW-Authenticate shapes, over the in-memory adapter.
 */
public class Wave8ApiKeysTest {

    private final W8 f = new W8();

    @After
    public void stop() {
        f.stop();
    }

    /** An InMemoryApiKeyStore counting findByPrefix and updateLastUsed; updateLastUsed can block or throw. */
    static class Counting implements ApiKeyStore {
        final InMemoryApiKeyStore d = new InMemoryApiKeyStore();
        final AtomicInteger lookups = new AtomicInteger();
        final AtomicInteger lastUsed = new AtomicInteger();
        volatile boolean throwOnLastUsed;
        volatile CountDownLatch blockLastUsed;

        @Override
        public StoredApiKey findByPrefix(String prefix) {
            lookups.incrementAndGet();
            return d.findByPrefix(prefix);
        }

        @Override
        public StoredApiKey findById(String keyId) {
            return d.findById(keyId);
        }

        @Override
        public List<StoredApiKey> listByOwner(String ownerUserId) {
            return d.listByOwner(ownerUserId);
        }

        @Override
        public boolean insertIfUnderLimit(StoredApiKey key, int maxLiveKeys, long nowMillis) {
            return d.insertIfUnderLimit(key, maxLiveKeys, nowMillis);
        }

        @Override
        public boolean deleteByIdAndOwner(String keyId, String ownerUserId) {
            return d.deleteByIdAndOwner(keyId, ownerUserId);
        }

        @Override
        public boolean deleteById(String keyId) {
            return d.deleteById(keyId);
        }

        @Override
        public int deleteAllByOwner(String ownerUserId) {
            return d.deleteAllByOwner(ownerUserId);
        }

        @Override
        public int deleteStaleByOwner(String ownerUserId, long currentOwnerVer) {
            return d.deleteStaleByOwner(ownerUserId, currentOwnerVer);
        }

        @Override
        public int deleteExpired(long nowMillis) {
            return d.deleteExpired(nowMillis);
        }

        @Override
        public void updateLastUsed(String keyId, long lastUsedAtMillis) throws Exception {
            lastUsed.incrementAndGet();
            CountDownLatch b = blockLastUsed;
            if (b != null) {
                b.await(10, TimeUnit.SECONDS);
            }
            if (throwOnLastUsed) {
                throw new java.sql.SQLException("lastUsedAt column locked");
            }
            d.updateLastUsed(keyId, lastUsedAtMillis);
        }
    }

    private final Counting keys = new Counting();
    private String owner;

    private void start(java.util.function.UnaryOperator<Auth.Builder> config) throws Exception {
        owner = f.user("bot", W8.PW, "reader", "writer");
        f.start(b -> config.apply(b.apiKeys(keys)));
    }

    private String key(String... scopes) {
        return AuthTesting.apiKeyFor(f.auth, owner, scopes);
    }

    private ClientResponse get(String auth) {
        return f.get("/api/x", "Authorization", auth);
    }

    private static final String SIX = "GET /auth/me/apikeys, POST /auth/me/apikeys, DELETE /auth/me/apikeys/:id, "
            + "GET /auth/admin/apikeys, POST /auth/admin/apikeys, DELETE /auth/admin/apikeys/:id";

    private static Set<String> keyRoutes(W8 w) {
        Set<String> out = new java.util.TreeSet<>();
        for (Registration r : w.app.registrations()) {
            if (r.getKind() == RegistrationKind.ROUTE && r.getPath() != null && r.getPath().contains("/apikeys")) {
                out.add(r.getMethod() + " " + r.getPath());
            }
        }
        return out;
    }

    // ---------------------------------------------------------------------------------------------- FR-APIKEY-007

    // FR-APIKEY-007 AC-1: AuthTesting.verifyApiKeyStore(new InMemoryApiKeyStore()) completes
    // FR-APIKEY-007 AC-2: a second key with an existing prefix throws ApiKeyPrefixCollisionException; aBcd1234 and
    // Abcd1234 are two rows
    @Test
    public void FR_APIKEY_007_AC1_AC2_theInMemoryStoreKeepsTheContract() throws Exception {
        AuthTesting.verifyApiKeyStore(new InMemoryApiKeyStore());
        InMemoryApiKeyStore s = new InMemoryApiKeyStore();
        assertTrue(s.insertIfUnderLimit(row("k1", "aBcd1234"), 20, W8.T0));
        assertTrue(s.insertIfUnderLimit(row("k2", "Abcd1234"), 20, W8.T0));
        try {
            s.insertIfUnderLimit(row("k3", "aBcd1234"), 20, W8.T0);
            fail("the prefix collided");
        } catch (ApiKeyPrefixCollisionException expected) {
            assertNull(s.findById("k3"));
        }
        assertEquals("k1", s.findByPrefix("aBcd1234").getId());
        assertEquals("k2", s.findByPrefix("Abcd1234").getId());
    }

    private static StoredApiKey row(String id, String prefix) {
        return StoredApiKey.builder().id(id).prefix(prefix).secretHash(
                "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08").ownerUserId("u1").ownerVer(0L)
                .name("n").scopes(Collections.singleton("posts:read")).createdAt(W8.T0).build();
    }

    // FR-APIKEY-007 AC-3: MULTI_INSTANCE with .apiKeys(new InMemoryApiKeyStore()) fails build()
    @Test
    public void FR_APIKEY_007_AC3_multiInstanceRefusesTheMemoryStore() {
        try {
            f.builder().deployment(Deployment.MULTI_INSTANCE).apiKeys(new InMemoryApiKeyStore()).build();
            fail("built");
        } catch (AuthConfigException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("InMemoryApiKeyStore (API key store)"));
        }
    }

    // FR-APIKEY-007 AC-4: SINGLE_INSTANCE, 8 threads issuing through one Auth for an owner at limit-1 -> exactly one
    // new key (auth's per-owner lock makes count-then-insert safe)
    @Test
    public void FR_APIKEY_007_AC4_eightThreadsAtTheLimitMakeOneKey() throws Exception {
        start(b -> b.maxKeysPerUser(5));
        for (int i = 0; i < 4; i++) {
            key("posts:read");
        }
        assertEquals(1, race(8, () -> f.auth.apiKeys().issue(owner, "race", null, "posts:read")));
        assertEquals(5, keys.listByOwner(owner).size());
        f.stop();
        // the same over a count-then-insert store: only auth's per-owner lock keeps the count right
        W8 w = new W8();
        try {
            final String id = w.user("bot", W8.PW, "reader");
            final NonAtomic slow = new NonAtomic();
            w.start(b -> b.apiKeys(slow).maxKeysPerUser(5));
            for (int i = 0; i < 4; i++) {
                w.auth.apiKeys().issue(id, "k" + i, null, "posts:read");
            }
            assertEquals(1, race(8, () -> w.auth.apiKeys().issue(id, "race", null, "posts:read")));
            assertEquals(5, slow.listByOwner(id).size());
        } finally {
            w.stop();
        }
    }

    /** Runs {@code n} copies of {@code issue} at once; how many returned without AuthRejection(409). */
    private static int race(int n, final Callable<IssuedApiKey> issue) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        final CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            results.add(pool.submit(() -> {
                go.await();
                try {
                    issue.call();
                    return true;
                } catch (AuthRejection limit) {
                    assertEquals(409, limit.getStatus());
                    assertEquals("api_key_limit", limit.getCode());
                    return false;
                }
            }));
        }
        go.countDown();
        int ok = 0;
        for (Future<Boolean> r : results) {
            ok += r.get(30, TimeUnit.SECONDS) ? 1 : 0;
        }
        pool.shutdown();
        return ok;
    }

    // ---------------------------------------------------------------------------------------------- FR-APIKEY-001

    // FR-APIKEY-001 AC-1: without .apiKeys(store), only Authorization: ApiKey nk_... on a protected path -> 401
    // unauthenticated, and no store lookup happens
    // FR-APIKEY-001 AC-2: without .apiKeys(store) none of the six key endpoints is registered
    // IR-TOKEN-001 AC-5: without .apiKeys(store) the key authenticator is not installed and the header is no
    // credential; with it, key-scheme requests go to the key path
    @Test
    public void FR_APIKEY_001_AC1_AC2_keysAreOffByDefault() throws Exception {
        String id = f.user("bot", W8.PW, "reader");
        f.start(b -> b);
        Auth withKeys = f.builder().apiKeys(keys).build();
        String plain = withKeys.apiKeys().issue(id, "n", null, "posts:read").getPlainKey();
        int lookups = keys.lookups.get();
        ClientResponse r = get("ApiKey " + plain);
        assertEquals(401, r.status());
        assertEquals("unauthenticated", W8.code(r));
        assertEquals(lookups, keys.lookups.get());
        assertEquals(Collections.emptySet(), keyRoutes(f));
    }

    // FR-APIKEY-001 AC-3: with .apiKeys(new InMemoryApiKeyStore()) the six key endpoints are registered
    // IR-TOKEN-001 AC-5 (on): a key-scheme request is routed to the API key path
    @Test
    public void FR_APIKEY_001_AC3_sixEndpointsWithKeys() throws Exception {
        f.start(b -> b.apiKeys(new InMemoryApiKeyStore()));
        assertEquals(new java.util.TreeSet<>(Arrays.asList(SIX.split(", "))), keyRoutes(f));
        ClientResponse r = get("ApiKey nk_AAAAAAAA_BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB");
        assertEquals("invalid_api_key", W8.code(r));
    }

    // FR-APIKEY-001 AC-4: with .apiKeys(store) and no other key option the effective values are 20, 365, false,
    // ApiKey and 100 per 900000 ms
    // FR-APIKEY-001 AC-5: the facade without .apiKeys(store) throws AuthStateException (IR-APIKEY-002 AC-6)
    @Test
    public void FR_APIKEY_001_AC4_AC5_defaults() throws Exception {
        Auth a = f.builder().apiKeys(new InMemoryApiKeyStore()).build();
        assertEquals(20, a.maxKeysPerUser());
        assertEquals(365, a.maxApiKeyDays());
        assertFalse(a.allowAdminScopesOnApiKeys());
        assertEquals("ApiKey", a.apiKeyScheme());
        assertEquals(100, a.apiKeyFailureMax());
        assertEquals(900_000L, a.apiKeyFailureWindowMillis());
        String d = a.describe();
        assertTrue(d, d.contains("scheme ApiKey, at most 20 live keys per user, at most 365 days, admin scopes "
                + "refused, failure limit 100 per 900000 ms"));
        Auth custom = f.builder().apiKeys(new InMemoryApiKeyStore()).maxKeysPerUser(3).maxApiKeyDays(30)
                .allowAdminScopesOnApiKeys(true).apiKeyFailureLimit(10, 60_000L).build();
        assertEquals(3, custom.maxKeysPerUser());
        assertEquals(30, custom.maxApiKeyDays());
        assertTrue(custom.allowAdminScopesOnApiKeys());
        assertEquals(10, custom.apiKeyFailureMax());
        assertEquals(60_000L, custom.apiKeyFailureWindowMillis());
        for (Auth.Builder bad : Arrays.asList(f.builder().maxKeysPerUser(0), f.builder().maxApiKeyDays(0),
                f.builder().apiKeyFailureLimit(0, 60_000L))) {
            try {
                bad.build();
                fail("built");
            } catch (AuthConfigException expected) {
                // refused
            }
        }
        Auth off = f.builder().build();
        for (Runnable call : Arrays.<Runnable>asList(() -> off.apiKeys().issue("1", "n", null, "posts:read"),
                () -> off.apiKeys().list("1"), () -> off.apiKeys().revoke("k"))) {
            try {
                call.run();
                fail("ran without keys");
            } catch (AuthStateException expected) {
                // IR-APIKEY-002 AC-6
            }
        }
    }

    // ---------------------------------------------------------------------------------------------- CON-APIKEY-001

    // CON-APIKEY-001 AC-1: Bearer, basic, DPoP and negotiate are refused by build()
    // CON-APIKEY-001 AC-2: "" and "a k" are refused
    // CON-APIKEY-001 AC-3: "a,k" is refused
    @Test
    public void CON_APIKEY_001_AC1_AC3_schemeRules() {
        for (String bad : new String[] {"Bearer", "basic", "DPoP", "negotiate", "", "a k", "a,k"}) {
            try {
                f.builder().apiKeys(new InMemoryApiKeyStore()).apiKeyScheme(bad).build();
                fail("accepted " + bad);
            } catch (AuthConfigException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("apiKeyScheme"));
            }
        }
    }

    // CON-APIKEY-001 AC-4: .apiKeyScheme("ak") builds and only ak (any case) is the key scheme; ApiKey is not
    // FR-APIKEY-010 AC-2: with .apiKeyScheme("ak") the apiKeyFor key works with Authorization: ak <key>
    // SEC-APIKEY-001 AC-2: AK nk_... is a key; ApiKey nk_... is no credential (401 unauthenticated) and the 401
    // challenge names Bearer and ak
    // SEC-APIKEY-001 AC-3: ak.nk_... is read as the scheme ak.nk_..., no credential
    // IR-FLOW-001 AC-4 (scheme part): an expired JWT under .apiKeyScheme("ak") -> Bearer error="invalid_token", ak
    @Test
    public void CON_APIKEY_001_AC4_customSchemeIsTheOnlyKeyScheme() throws Exception {
        start(b -> b.apiKeyScheme("ak"));
        String k = key("posts:read");
        assertEquals(200, get("ak " + k).status());
        assertEquals(200, get("AK " + k).status());
        ClientResponse old = get("ApiKey " + k);
        assertEquals(401, old.status());
        assertEquals("unauthenticated", W8.code(old));
        assertEquals("Bearer, ak", old.header("WWW-Authenticate"));
        ClientResponse dotted = get("ak." + k);
        assertEquals(401, dotted.status());
        assertEquals("unauthenticated", W8.code(dotted));
        ClientResponse expired = get("Bearer " + f.expiredAccess(owner));
        assertEquals("Bearer error=\"invalid_token\", ak", expired.header("WWW-Authenticate"));
    }

    // ---------------------------------------------------------------------------------------------- FR-APIKEY-010

    // FR-APIKEY-010 AC-1: Authorization: ApiKey + apiKeyFor(auth, "u1", "posts:read") to a posts:read route -> 200;
    // the stored key has expiresAt null and issuedByUserId null
    // FR-APIKEY-010 AC-3: an unknown owner -> IllegalArgumentException; without .apiKeys(store) -> AuthStateException
    // FR-APIKEY-010 AC-5: the 21st apiKeyFor for one owner with .maxKeysPerUser(20) -> AuthRejection(409,
    // "api_key_limit")
    @Test
    public void FR_APIKEY_010_AC1_AC3_AC5_apiKeyForIssuesARealKey() throws Exception {
        start(b -> b.maxKeysPerUser(20));
        String k = key("posts:read");
        ClientResponse r = get("ApiKey " + k);
        assertEquals(r.text(), 200, r.status());
        assertEquals("x:" + owner, r.text());
        StoredApiKey stored = keys.listByOwner(owner).get(0);
        assertNull(stored.getExpiresAt());
        assertNull(stored.getIssuedByUserId());
        try {
            AuthTesting.apiKeyFor(f.auth, "u-404", "posts:read");
            fail("unknown owner");
        } catch (IllegalArgumentException expected) {
            // refused
        }
        try {
            AuthTesting.apiKeyFor(f.builder().build(), owner, "posts:read");
            fail("no key store");
        } catch (AuthStateException expected) {
            // refused
        }
        for (int i = 1; i < 20; i++) {
            key("posts:read");
        }
        try {
            key("posts:read");
            fail("the 21st key was issued");
        } catch (AuthRejection limit) {
            assertEquals(409, limit.getStatus());
            assertEquals("api_key_limit", limit.getCode());
        }
    }

    // FR-APIKEY-010 AC-4: apiKeyFor works with an app-provided ApiKeyStore implementation that is not
    // InMemoryApiKeyStore
    @Test
    public void FR_APIKEY_010_AC4_apiKeyForOverAnAppProvidedStore() throws Exception {
        AppStores.Keys keys = new AppStores().apiKeys();
        assertFalse(((Object) keys) instanceof com.snoworca.naruserve.utils.auth.apikey.InMemoryApiKeyStore);
        String id = f.user("bot", W8.PW, "reader");
        f.start(b -> b.apiKeys(keys));
        String k = AuthTesting.apiKeyFor(f.auth, id, "posts:read");
        assertEquals(200, get("ApiKey " + k).status());
        assertEquals(1, keys.listByOwner(id).size());
    }

    // ---------------------------------------------------------------------------------------------- IR-APIKEY-002

    // IR-APIKEY-002 AC-1: issue(botId, "ci-bot", null, "posts:read") returns the plaintext key; the stored key has
    // issuedByUserId null and expiresAt null
    // IR-APIKEY-002 AC-4: list of an unknown owner -> IllegalArgumentException; list(owner) returns ApiKey values with
    // a status and no secret hash
    // IR-APIKEY-002 AC-5: revoke("k-404") false without an exception; an existing key -> true and deleted
    @Test
    public void IR_APIKEY_002_AC1_AC4_AC5_issueListRevoke() throws Exception {
        start(b -> b);
        IssuedApiKey issued = f.auth.apiKeys().issue(owner, "ci-bot", null, "posts:read");
        assertTrue(issued.getPlainKey().startsWith("nk_" + issued.getKey().getPrefix() + "_"));
        StoredApiKey row = keys.findById(issued.getKey().getId());
        assertNull(row.getIssuedByUserId());
        assertNull(row.getExpiresAt());
        assertEquals("ci-bot", row.getName());
        assertEquals(200, get("ApiKey " + issued.getPlainKey()).status());
        try {
            f.auth.apiKeys().list("u-404");
            fail("listed an unknown owner");
        } catch (IllegalArgumentException expected) {
            // refused
        }
        List<ApiKey> listed = f.auth.apiKeys().list(owner);
        assertEquals(1, listed.size());
        assertEquals(ApiKeyStatus.ACTIVE, listed.get(0).getStatus());
        for (java.lang.reflect.Method m : ApiKey.class.getMethods()) {
            assertFalse(m.getName(), m.getName().toLowerCase(java.util.Locale.ROOT).contains("secret")
                    || m.getName().toLowerCase(java.util.Locale.ROOT).contains("hash") && !m.getName().equals("hashCode"));
        }
        assertFalse(f.auth.apiKeys().revoke("k-404"));
        assertTrue(f.auth.apiKeys().revoke(issued.getKey().getId()));
        assertNull(keys.findById(issued.getKey().getId()));
        assertEquals(401, get("ApiKey " + issued.getPlainKey()).status());
    }

    // IR-APIKEY-002 AC-2: an unknown owner, a disabled owner, scopes violating R46 and an expiresAt at or before now
    // -> IllegalArgumentException
    @Test
    public void IR_APIKEY_002_AC2_invalidIssuance() throws Exception {
        start(b -> b);
        String off = f.user("off", W8.PW);
        f.store.setEnabled(off, false);
        List<Runnable> bad = Arrays.<Runnable>asList(
                () -> f.auth.apiKeys().issue("u-404", "n", null, "posts:read"),
                () -> f.auth.apiKeys().issue(off, "n", null, "posts:read"),
                () -> f.auth.apiKeys().issue(owner, "n", null, "posts"),
                () -> f.auth.apiKeys().issue(owner, "n", null, "Posts:Read"),
                () -> f.auth.apiKeys().issue(owner, "n", null, "auth-users:write"),
                () -> f.auth.apiKeys().issue(owner, "n", f.clock.get(), "posts:read"),
                () -> f.auth.apiKeys().issue(owner, "n", f.clock.get() - 1, "posts:read"));
        for (int i = 0; i < bad.size(); i++) {
            try {
                bad.get(i).run();
                fail("issued case " + i);
            } catch (IllegalArgumentException expected) {
                // refused
            }
        }
        assertTrue(keys.listByOwner(owner).isEmpty());
        IssuedApiKey later = f.auth.apiKeys().issue(owner, "n", f.clock.get() + 60_000L, "posts:read");
        assertEquals(Long.valueOf(f.clock.get() + 60_000L), later.getKey().getExpiresAt());
    }

    // IR-APIKEY-002 AC-3: issue when the owner already has 20 live keys -> AuthRejection 409 api_key_limit
    // IR-APIKEY-002 AC-7: auth.errors() renders that AuthRejection thrown from an app route as 409 JSON api_key_limit
    @Test
    public void IR_APIKEY_002_AC3_AC7_limitAndItsJson() throws Exception {
        start(b -> b);
        for (int i = 0; i < 20; i++) {
            f.auth.apiKeys().issue(owner, "k" + i, null, "posts:read");
        }
        try {
            f.auth.apiKeys().issue(owner, "k21", null, "posts:read");
            fail("issued over the limit");
        } catch (AuthRejection limit) {
            assertEquals(409, limit.getStatus());
            assertEquals("api_key_limit", limit.getCode());
        }
        f.stop();
        W8 w = new W8();
        try {
            final String id = w.user("bot", W8.PW, "reader");
            final InMemoryApiKeyStore store = new InMemoryApiKeyStore();
            w.routes = x -> x.app.post("/issue", (req, res) -> res.send(x.auth.apiKeys().issue(id, "n", null,
                    "posts:read").getKey().getId()));
            w.start(b -> b.apiKeys(store).maxKeysPerUser(1).permit(Method.POST, "/issue"));
            assertEquals(200, w.post("/issue", null).status());
            ClientResponse second = w.post("/issue", null);
            assertEquals(409, second.status());
            assertEquals("api_key_limit", W8.code(second));
        } finally {
            w.stop();
        }
    }

    // IR-APIKEY-002 AC-8: with .allowAdminScopesOnApiKeys(true), issue(adminId, "n", null, "auth-users:write") from an
    // app route whose principal holds only posts:read succeeds (no actor check); the AuthApiKeys Javadoc says the
    // facade performs no actor checks and that delegated-admin screens must check the actor themselves
    @Test
    public void IR_APIKEY_002_AC8_noActorChecks() throws Exception {
        final String admin = f.user("root", W8.PW, "admin");
        f.user("reader", W8.PW, "reader");
        f.routes = x -> x.app.post("/delegate", x.auth.require("posts:read"), (req, res) -> res.send(
                x.auth.apiKeys().issue(admin, "n", null, "auth-users:write").getKey().getId()));
        f.start(b -> b.apiKeys(keys).allowAdminScopesOnApiKeys(true));
        String token = (String) f.loginBody("reader", W8.PW).get("accessToken");
        ClientResponse r = f.post("/delegate", null, "Authorization", "Bearer " + token);
        assertEquals(r.text(), 200, r.status());
        assertEquals(Collections.singleton("auth-users:write"), keys.findById(r.text()).getScopes());
        String doc = javadoc("apikey/AuthApiKeys.java", "public interface AuthApiKeys");
        assertTrue(doc, doc.contains("performs no actor checks"));
        assertTrue(doc, doc.contains("must check the acting user itself"));
    }

    static String javadoc(String path, String declaration) throws Exception {
        File src = new File(System.getProperty("user.dir"), "src/main/java/com/snoworca/naruserve/utils/auth/" + path);
        String text;
        try (InputStream in = new FileInputStream(src)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0;) {
                out.write(buf, 0, n);
            }
            text = new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
        int end = text.indexOf(declaration);
        int start = text.lastIndexOf("/**", end);
        return text.substring(start, end).replaceAll("\\s*\\*\\s*", " ").replaceAll("<[^>]+>", "");
    }

    // ---------------------------------------------------------------------------------------------- SEC-APIKEY-010

    /** A store that throws ApiKeyPrefixCollisionException on the first {@code collisions} inserts. */
    static final class Colliding extends Counting {
        final AtomicInteger collisions;
        final List<String> prefixes = new CopyOnWriteArrayList<>();

        Colliding(int collisions) {
            this.collisions = new AtomicInteger(collisions);
        }

        @Override
        public synchronized boolean insertIfUnderLimit(StoredApiKey key, int maxLiveKeys, long nowMillis) {
            prefixes.add(key.getPrefix());
            if (collisions.getAndDecrement() > 0) {
                throw new ApiKeyPrefixCollisionException("prefix exists");
            }
            return super.insertIfUnderLimit(key, maxLiveKeys, nowMillis);
        }
    }

    // SEC-APIKEY-010 AC-3: a store colliding on the first attempt and accepting the second: issuance succeeds with a
    // prefix other than the first attempt's
    @Test
    public void SEC_APIKEY_010_AC3_collisionRetriesWithANewPrefix() throws Exception {
        String id = f.user("bot", W8.PW, "reader");
        Colliding store = new Colliding(1);
        f.start(b -> b.apiKeys(store));
        IssuedApiKey k = f.auth.apiKeys().issue(id, "n", null, "posts:read");
        assertEquals(2, store.prefixes.size());
        assertNotEquals(store.prefixes.get(0), store.prefixes.get(1));
        assertEquals(store.prefixes.get(1), k.getKey().getPrefix());
    }

    // SEC-APIKEY-010, the app-API half of acceptance 4 (the endpoint half waits for the issuance endpoints): colliding on the first attempt and all 3 retries -> AuthUnavailableException
    @Test
    public void SEC_APIKEY_010_AC4_collisionsExhaustTheRetries() throws Exception {
        String id = f.user("bot", W8.PW, "reader");
        Colliding store = new Colliding(4);
        f.start(b -> b.apiKeys(store));
        try {
            f.auth.apiKeys().issue(id, "n", null, "posts:read");
            fail("issued");
        } catch (AuthUnavailableException expected) {
            assertEquals(503, expected.getStatus());
        }
        assertEquals(4, store.prefixes.size());
        assertTrue(store.listByOwner(id).isEmpty());
    }

    /** A count-then-insert store with a pause between the count and the insert (not atomic). */
    static final class NonAtomic extends Counting {
        @Override
        public boolean insertIfUnderLimit(StoredApiKey key, int maxLiveKeys, long nowMillis) {
            int live = listByOwner(key.getOwnerUserId()).size();
            try {
                Thread.sleep(30);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (live >= maxLiveKeys) {
                return false;
            }
            return d.insertIfUnderLimit(key, Integer.MAX_VALUE, nowMillis);
        }
    }

    // SEC-APIKEY-010 AC-5: two concurrent issuances for an owner with 19 live keys through one Auth over a non-atomic
    // store: exactly one succeeds, the other gets 409 api_key_limit
    // SEC-APIKEY-010, the app-path analogue of acceptance 1 (which is about a PAT): the new key's ownerVer is the owner row the issuance read from the store
    @Test
    public void SEC_APIKEY_010_AC5_theOwnerLockSerializesIssuance() throws Exception {
        final String id = f.user("bot", W8.PW, "reader");
        final NonAtomic store = new NonAtomic();
        f.start(b -> b.apiKeys(store));
        for (int i = 0; i < 19; i++) {
            f.auth.apiKeys().issue(id, "k" + i, null, "posts:read");
        }
        assertEquals(1, race(2, () -> f.auth.apiKeys().issue(id, "race", null, "posts:read")));
        assertEquals(20, store.listByOwner(id).size());
        f.store.bumpTokenVersion(id);
        f.auth.apiKeys().revoke(store.listByOwner(id).get(0).getId());
        IssuedApiKey fresh = f.auth.apiKeys().issue(id, "after", null, "posts:read");
        assertEquals(1L, fresh.getKey().getOwnerVer());
    }

    // ---------------------------------------------------------------------------------------------- SEC-APIKEY-001

    // SEC-APIKEY-001 AC-1: ApiKey nk_... is a key, Bearer <JWT> a JWT, Bearer nk_... a bad JWT (401 invalid_token)
    // SEC-APIKEY-001 AC-7: the scheme followed by an auth-param shape (ApiKey a=b) -> 401 invalid_api_key
    @Test
    public void SEC_APIKEY_001_AC1_AC7_schemeDecides() throws Exception {
        start(b -> b);
        String k = key("posts:read");
        assertEquals(200, get("ApiKey " + k).status());
        String jwt = (String) f.loginBody("bot", W8.PW).get("accessToken");
        assertEquals(200, get("Bearer " + jwt).status());
        ClientResponse shaped = get("Bearer " + k);
        assertEquals(401, shaped.status());
        assertEquals("invalid_token", W8.code(shaped));
        ClientResponse param = get("ApiKey a=b");
        assertEquals(401, param.status());
        assertEquals("invalid_api_key", W8.code(param));
    }

    // SEC-APIKEY-001 AC-4: in HEADER, COOKIE and BOTH, ?api_key=nk_... or another cookie holding nk_... -> 401
    // unauthenticated and no lookup
    // SEC-APIKEY-001 AC-5: COOKIE or BOTH, ApiKey with a cookie access token on a protected path -> 400; on a public
    // path anonymous, no 400
    // SEC-APIKEY-001 AC-6: COOKIE or BOTH, ApiKey plus a naru_rt cookie authenticates with the key
    @Test
    public void SEC_APIKEY_001_AC4_AC6_onlyTheHeaderCarriesAKey() throws Exception {
        for (TokenTransport t : TokenTransport.values()) {
            W8 w = new W8();
            Counting store = new Counting();
            try {
                final String id = w.user("bot", W8.PW, "reader");
                w.start(b -> b.apiKeys(store).transport(t).permit("/health"));
                String k = AuthTesting.apiKeyFor(w.auth, id, "posts:read");
                int lookups = store.lookups.get();
                ClientResponse q = w.get("/api/x?api_key=" + k);
                assertEquals(t.toString(), 401, q.status());
                assertEquals("unauthenticated", W8.code(q));
                ClientResponse c = w.get("/api/x", "Cookie", "api_key=" + k);
                assertEquals("unauthenticated", W8.code(c));
                assertEquals(t.toString(), lookups, store.lookups.get());
                if (t == TokenTransport.HEADER) {
                    continue;
                }
                String at = w.auth.accessToken(id, 0, 60_000L);
                ClientResponse two = w.get("/api/x", "Authorization", "ApiKey " + k, "Cookie", "naru_at=" + at);
                assertEquals(t.toString(), 400, two.status());
                assertEquals("invalid_request", W8.code(two));
                ClientResponse pub = w.get("/health", "Authorization", "ApiKey " + k, "Cookie", "naru_at=" + at);
                assertEquals(200, pub.status());
                ClientResponse rt = w.get("/api/x", "Authorization", "ApiKey " + k, "Cookie", "naru_rt=a.b.c");
                assertEquals(t.toString(), 200, rt.status());
            } finally {
                w.stop();
            }
        }
    }

    // SEC-APIKEY-001 AC-5: COOKIE or BOTH, ApiKey with a cookie access token on a public path proceeds anonymously: no
    // principal is attached, though the same key alone on the same path attaches one
    @Test
    public void SEC_APIKEY_001_AC5_thePublicPathStaysAnonymous() throws Exception {
        for (TokenTransport t : new TokenTransport[] {TokenTransport.COOKIE, TokenTransport.BOTH}) {
            W8 w = new W8();
            try {
                final String id = w.user("bot", W8.PW, "reader");
                w.start(b -> b.apiKeys(new Counting()).transport(t).permit("/health"));
                String k = AuthTesting.apiKeyFor(w.auth, id, "posts:read");
                assertEquals(t.toString(), "user", w.get("/health", "Authorization", "ApiKey " + k).text());
                String at = w.auth.accessToken(id, 0, 60_000L);
                ClientResponse pub = w.get("/health", "Authorization", "ApiKey " + k, "Cookie", "naru_at=" + at);
                assertEquals(t.toString(), 200, pub.status());
                assertEquals(t.toString(), "anon", pub.text());
            } finally {
                w.stop();
            }
        }
    }

    // ---------------------------------------------------------------------------------------------- SEC-APIKEY-002

    // SEC-APIKEY-002 AC-3: two Authorization headers on POST /auth/login -> 400 with body code invalid_request, with
    // and without a .permit of that endpoint
    @Test
    public void SEC_APIKEY_002_AC3_theLoginAnswerIsInvalidRequest() throws Exception {
        for (int way = 0; way < 2; way++) {
            final boolean permitted = way == 1;
            W8 w = new W8();
            try {
                w.user("bot", W8.PW, "reader");
                w.start(b -> permitted ? b.apiKeys(new Counting()).permit(Method.POST, "/auth/login")
                        : b.apiKeys(new Counting()));
                ClientResponse r = w.adapter.send(ClientRequest.json("POST", "/auth/login", W8.login("bot", W8.PW))
                        .header("Authorization", "Bearer a.b.c")
                        .header("Authorization", "ApiKey nk_AAAAAAAA_BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB"));
                assertEquals(r.text(), 400, r.status());
                assertEquals("invalid_request", W8.code(r));
            } finally {
                w.stop();
            }
        }
    }

    // SEC-APIKEY-002 AC-1: Bearer and ApiKey as two headers on a protected path -> 400 invalid_request
    // SEC-APIKEY-002 AC-2: two Authorization headers on the public GET /health -> 200 anonymous
    // SEC-APIKEY-002 AC-3: two Authorization headers on POST /auth/login -> 400, regardless of .permit
    // SEC-APIKEY-002 AC-4: one merged header "Bearer eyJ..., ApiKey nk_..." -> 401 invalid_token
    // SEC-APIKEY-002 AC-5: one merged header "ApiKey nk_..., Bearer eyJ..." -> 401 invalid_api_key
    @Test
    public void SEC_APIKEY_002_AC1_AC5_multipleAndMergedHeaders() throws Exception {
        start(b -> b.permit("/health").permit(Method.POST, "/auth/login"));
        String k = key("posts:read");
        String jwt = (String) f.loginBody("bot", W8.PW).get("accessToken");
        ClientResponse two = f.adapter.send(ClientRequest.of("GET", "/api/x").header("Authorization", "Bearer " + jwt)
                .header("Authorization", "ApiKey " + k));
        assertEquals(400, two.status());
        assertEquals("invalid_request", W8.code(two));
        ClientResponse pub = f.adapter.send(ClientRequest.of("GET", "/health").header("Authorization", "Bearer " + jwt)
                .header("Authorization", "ApiKey " + k));
        assertEquals(200, pub.status());
        assertEquals("anon", pub.text());
        ClientResponse login = f.adapter.send(ClientRequest.json("POST", "/auth/login", W8.login("bot", W8.PW))
                .header("Authorization", "Bearer " + jwt).header("Authorization", "ApiKey " + k));
        assertEquals(400, login.status());
        ClientResponse bearerFirst = get("Bearer " + jwt + ", ApiKey " + k);
        assertEquals(401, bearerFirst.status());
        assertEquals("invalid_token", W8.code(bearerFirst));
        ClientResponse keyFirst = get("ApiKey " + k + ", Bearer " + jwt);
        assertEquals(401, keyFirst.status());
        assertEquals("invalid_api_key", W8.code(keyFirst));
    }

    // SEC-APIKEY-002 AC-6: PROD plain text non-local, two Authorization headers one of them ApiKey -> 403
    // insecure_transport before the headers are read (SEC-FLOW-001 AC-2 with keys on)
    @Test
    public void SEC_APIKEY_002_AC6_transportFirst() throws Exception {
        f.start("203.0.113.9", false, b -> b.apiKeys(keys));
        ClientResponse r = f.adapter.send(ClientRequest.of("GET", "/api/x").header("Authorization", "Bearer a.b.c")
                .header("Authorization", "ApiKey nk_AAAAAAAA_BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB"));
        assertEquals(403, r.status());
        assertEquals("insecure_transport", W8.code(r));
        assertEquals(0, keys.lookups.get());
    }

    // ---------------------------------------------------------------------------------------------- IR-FLOW-001

    // IR-FLOW-001 AC-1: keys off, no credential -> 401 unauthenticated with WWW-Authenticate: Bearer
    // IR-FLOW-001 AC-2: keys off, an expired Bearer -> 401 invalid_token with Bearer error="invalid_token"
    // IR-FLOW-001 AC-7: a JWT request refused by a scope guard -> 403 with Bearer error="insufficient_scope"
    @Test
    public void IR_FLOW_001_AC1_AC2_AC7_challengesWithoutKeys() throws Exception {
        String id = f.user("plain", W8.PW);
        f.start(b -> b);
        ClientResponse none = f.get("/api/x");
        assertEquals(401, none.status());
        assertEquals("unauthenticated", W8.code(none));
        assertEquals("Bearer", none.header("WWW-Authenticate"));
        ClientResponse expired = get("Bearer " + f.expiredAccess(id));
        assertEquals("invalid_token", W8.code(expired));
        assertEquals("Bearer error=\"invalid_token\"", expired.header("WWW-Authenticate"));
        ClientResponse scope = get("Bearer " + f.auth.accessToken(id, 0, 60_000L));
        assertEquals(403, scope.status());
        assertEquals("insufficient_scope", W8.code(scope));
        assertEquals("Bearer error=\"insufficient_scope\"", scope.header("WWW-Authenticate"));
    }

    // IR-FLOW-001 AC-3: keys on (default scheme), no credential -> one header Bearer, ApiKey; a malformed or a wrong key
    // -> the same header with body code invalid_api_key
    // IR-FLOW-001 AC-4: keys on, an expired JWT -> Bearer error="invalid_token", ApiKey
    // IR-FLOW-001 AC-5: no response puts error= on the key challenge, which appears only on 401
    // IR-FLOW-001 AC-6: a key request refused with 403 insufficient_scope or 403 api_key_not_allowed -> no
    // WWW-Authenticate header
    @Test
    public void IR_FLOW_001_AC3_AC6_challengesWithKeys() throws Exception {
        f.routes = x -> x.app.post("/role", x.auth.requireRole("reader"), (req, res) -> res.send("role"));
        start(b -> b);
        List<ClientResponse> all = new ArrayList<>();
        ClientResponse none = f.get("/api/x");
        all.add(none);
        assertEquals("Bearer, ApiKey", none.header("WWW-Authenticate"));
        for (String bad : new String[] {"ApiKey nk_short", "ApiKey nk_AAAAAAAA_BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB"}) {
            ClientResponse r = get(bad);
            all.add(r);
            assertEquals("invalid_api_key", W8.code(r));
            assertEquals("Bearer, ApiKey", r.header("WWW-Authenticate"));
        }
        ClientResponse expired = get("Bearer " + f.expiredAccess(owner));
        all.add(expired);
        assertEquals("Bearer error=\"invalid_token\", ApiKey", expired.header("WWW-Authenticate"));
        String writer = key("posts:write");
        ClientResponse scope = get("ApiKey " + writer);
        all.add(scope);
        assertEquals(403, scope.status());
        assertEquals("insufficient_scope", W8.code(scope));
        assertNull(scope.header("WWW-Authenticate"));
        ClientResponse role = f.post("/role", null, "Authorization", "ApiKey " + key("posts:read"));
        all.add(role);
        assertEquals(403, role.status());
        assertEquals("api_key_not_allowed", W8.code(role));
        assertNull(role.header("WWW-Authenticate"));
        ClientResponse login = f.post("/auth/login", W8.login("bot", W8.PW), "Authorization", "ApiKey " + writer);
        all.add(login);
        assertEquals(403, login.status());
        assertNull(login.header("WWW-Authenticate"));
        for (ClientResponse r : all) {
            String h = r.header("WWW-Authenticate");
            if (h == null) {
                continue;
            }
            assertEquals(401, r.status());
            int comma = h.indexOf(", ApiKey");
            assertTrue(h, comma >= 0 && h.substring(comma).equals(", ApiKey"));
            int headers = 0;
            for (java.util.Map.Entry<String, String> e : r.headers()) {
                headers += e.getKey().equalsIgnoreCase("WWW-Authenticate") ? 1 : 0;
            }
            assertEquals(1, headers);
        }
    }

    // ---------------------------------------------------------------------------------------------- carried ACs

    // PERF-APIKEY-001 AC-1: 60 key requests within 59 seconds cause exactly one lastUsedAt update
    // PERF-APIKEY-001 AC-2: a request 60 seconds after the last update causes a second one
    // PERF-APIKEY-001 AC-3: an update that throws leaves the response 200 and writes a log entry
    // PERF-APIKEY-001 AC-4: an update blocking for seconds does not delay the response
    @Test
    public void PERF_APIKEY_001_AC1_AC4_lastUsedAtThroughKeyRequests() throws Exception {
        start(b -> b);
        String k = key("posts:read");
        for (int i = 0; i < 60; i++) {
            assertEquals(200, get("ApiKey " + k).status());
            f.clock.addAndGet(983L);
        }
        Wave8LoginTest.waitFor(() -> keys.lastUsed.get() == 1);
        Thread.sleep(50);
        assertEquals(1, keys.lastUsed.get());
        f.clock.addAndGet(60_000L);
        assertEquals(200, get("ApiKey " + k).status());
        Wave8LoginTest.waitFor(() -> keys.lastUsed.get() == 2);
        keys.throwOnLastUsed = true;
        f.clock.addAndGet(60_000L);
        assertEquals(200, get("ApiKey " + k).status());
        Wave8LoginTest.waitFor(() -> logged("lastUsedAt"));
        keys.throwOnLastUsed = false;
        keys.blockLastUsed = new CountDownLatch(1);
        f.clock.addAndGet(60_000L);
        long t0 = System.nanoTime();
        assertEquals(200, get("ApiKey " + k).status());
        assertTrue(System.nanoTime() - t0 < TimeUnit.SECONDS.toNanos(4));
        keys.blockLastUsed.countDown();
    }

    // PERF-APIKEY-001 AC-2: through key requests, 59.999 s after the last update causes none and 60 s causes the second
    @Test
    public void PERF_APIKEY_001_AC2_theSecondUpdateComesAtSixtySeconds() throws Exception {
        start(b -> b);
        String k = key("posts:read");
        assertEquals(200, get("ApiKey " + k).status());
        Wave8LoginTest.waitFor(() -> keys.lastUsed.get() == 1);
        f.clock.addAndGet(59_999L);
        assertEquals(200, get("ApiKey " + k).status());
        Thread.sleep(50);
        assertEquals(1, keys.lastUsed.get());
        f.clock.addAndGet(1L);
        assertEquals(200, get("ApiKey " + k).status());
        Wave8LoginTest.waitFor(() -> keys.lastUsed.get() == 2);
    }

    // PERF-APIKEY-001 AC-4: the response is complete while the update it caused has started and is still blocked
    @Test
    public void PERF_APIKEY_001_AC4_theResponseIsSentWhileTheUpdateIsBlocked() throws Exception {
        start(b -> b);
        String k = key("posts:read");
        CountDownLatch blocked = new CountDownLatch(1);
        keys.blockLastUsed = blocked;
        try {
            assertEquals(200, get("ApiKey " + k).status());
            Wave8LoginTest.waitFor(() -> keys.lastUsed.get() == 1);
            assertEquals("the update has not been released", 1L, blocked.getCount());
        } finally {
            blocked.countDown();
        }
    }

    private boolean logged(String word) {
        synchronized (f.logger.lines) {
            for (String l : f.logger.lines) {
                if (l.contains(word)) {
                    return true;
                }
            }
        }
        return false;
    }

    // SEC-FLOW-006 AC-6: Bearer writes (HEADER, BOTH) and API-key writes pass without any X-CSRF-Token
    @Test
    public void SEC_FLOW_006_AC6_bearerAndKeyWritesNeedNoCsrf() throws Exception {
        for (TokenTransport t : new TokenTransport[] {TokenTransport.HEADER, TokenTransport.BOTH,
                TokenTransport.COOKIE}) {
            W8 w = new W8();
            try {
                String id = w.user("bot", W8.PW, "reader");
                w.start(b -> b.apiKeys(new InMemoryApiKeyStore()).transport(t));
                String k = AuthTesting.apiKeyFor(w.auth, id, "posts:read");
                assertEquals(t.toString(), 201, w.post("/api/x", "{}", "Authorization", "ApiKey " + k).status());
                if (t != TokenTransport.COOKIE) {
                    String at = w.auth.accessToken(id, 0, 60_000L);
                    assertEquals(t.toString(), 201, w.post("/api/x", "{}", "Authorization", "Bearer " + at).status());
                }
            } finally {
                w.stop();
            }
        }
    }

    // FR-FLOW-009 AC-4: a locked service account keeps authenticating with its API key
    @Test
    public void FR_FLOW_009_AC4_lockoutDoesNotStopKeys() throws Exception {
        String svc = f.store.create(AuthUser.builder().username("svc").serviceAccount(true).roles("reader").build())
                .getId();
        f.start(b -> b.apiKeys(keys).lockout(Lockout.after(1).forMinutes(15)));
        f.auth.lockouts().recordFailure(svc);
        assertTrue(f.auth.lockouts().isLocked(svc));
        String k = f.auth.apiKeys().issue(svc, "svc", null, "posts:read").getPlainKey();
        assertEquals(200, get("ApiKey " + k).status());
    }

    // SEC-IAM-003 AC-3 (API key row): after one ver++ a key issued before it -> 401 invalid_api_key and it is listed
    // as stale while the off-request deletion has not removed it
    @Test
    public void SEC_IAM_003_AC3_keyIssuedBeforeTheBumpIsStale() throws Exception {
        start(b -> b);
        f.auth.apiKeyOwners().deletions(job -> { }); // the off-request stale-key deletion is held (the AC's setup)
        String k = key("posts:read");
        assertEquals(200, get("ApiKey " + k).status());
        String at = (String) f.loginBody("bot", W8.PW).get("accessToken");
        assertEquals(204, f.post("/auth/logout?all=true", null, "Authorization", "Bearer " + at).status());
        ClientResponse r = get("ApiKey " + k);
        assertEquals(401, r.status());
        assertEquals("invalid_api_key", W8.code(r));
        assertEquals(ApiKeyStatus.STALE, f.auth.apiKeys().list(owner).get(0).getStatus());
        // the stale key is still the owner's one listed key: the held deletion has not removed it
        Set<String> seen = new HashSet<>();
        for (com.snoworca.naruserve.utils.auth.apikey.ApiKey listed : f.auth.apiKeys().list(owner)) {
            seen.add(listed.getPrefix());
        }
        assertEquals(1, seen.size());
        assertTrue(seen + " holds the prefix of " + k, seen.contains(k.substring(3, 11)));
    }

    // review regression (IR-APIKEY-002 exception set): a store's own IllegalArgumentException is a store failure, 503
    @Test
    public void storeIllegalArgumentIsUnavailable() throws Exception {
        final String id = f.user("bot", W8.PW, "reader");
        f.start(b -> b.apiKeys(new Counting() {
            @Override
            public List<StoredApiKey> listByOwner(String ownerUserId) {
                throw new IllegalArgumentException("driver rejected the bind value");
            }
        }));
        try {
            f.auth.apiKeys().list(id);
            fail("listed");
        } catch (AuthUnavailableException expected) {
            assertEquals(503, expected.getStatus());
        }
    }

    // IR-FLOW-001 AC-2: keys off, an expired Bearer on a protected path is a 401 (the challenge test checks its code and
    // header). AC-4: with .apiKeyScheme("ak") that response is a 401 too, with the ak challenge.
    @Test
    public void IR_FLOW_001_AC2_AC4_anExpiredJwtIsA401() throws Exception {
        String id = f.user("plain", W8.PW);
        f.start(b -> b);
        ClientResponse off = get("Bearer " + f.expiredAccess(id));
        assertEquals(off.text(), 401, off.status());
        assertEquals("invalid_token", W8.code(off));
        assertEquals("Bearer error=\"invalid_token\"", off.header("WWW-Authenticate"));
        W8 ak = new W8();
        try {
            String bot = ak.user("bot", W8.PW, "reader");
            ak.start(b -> b.apiKeys(new InMemoryApiKeyStore()).apiKeyScheme("ak"));
            ClientResponse on = ak.get("/api/x", "Authorization", "Bearer " + ak.expiredAccess(bot));
            assertEquals(on.text(), 401, on.status());
            assertEquals("invalid_token", W8.code(on));
            assertEquals("Bearer error=\"invalid_token\", ak", on.header("WWW-Authenticate"));
        } finally {
            ak.stop();
        }
    }
}
