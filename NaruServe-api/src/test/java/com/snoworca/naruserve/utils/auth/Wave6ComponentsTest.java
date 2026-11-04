package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.utils.auth.internal.ApiKeyUsage;
import com.snoworca.naruserve.utils.auth.internal.Lockouts;
import com.snoworca.naruserve.utils.auth.internal.ThrottleKeys;
import com.snoworca.naruserve.utils.auth.user.AuthUser;
import com.snoworca.naruserve.utils.auth.user.UserMutator;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.After;
import org.junit.Test;

/**
 * The lastUsedAt bookkeeping of API keys (PERF-APIKEY-001), the Lockout state and its admin release (FR-FLOW-009),
 * the UserMutator rules of auth.users() (SEC-IAM-001) and the documented ver rules (SEC-IAM-003 AC-5).
 */
public class Wave6ComponentsTest {

    /** The fixtures this test made; each is stopped after the test (its app, auth scheduler and pools). */
    private final List<AdminFixture> fixtures = new ArrayList<>();

    /** A new fixture, stopped by {@link #stopFixtures}. */
    private AdminFixture fixture() {
        AdminFixture f = new AdminFixture();
        fixtures.add(f);
        return f;
    }

    @After
    public void stopFixtures() {
        AdminFixture.stopAll(fixtures);
    }

    /** A key store recording updateLastUsed calls, optionally blocking or failing them. */
    private static final class UsageKeys extends AdminFixture.ListKeys {
        final List<String> updates = new CopyOnWriteArrayList<>();
        volatile CountDownLatch block;
        volatile boolean fail;

        @Override
        public void updateLastUsed(String keyId, long lastUsedAtMillis) throws Exception {
            CountDownLatch b = block;
            if (b != null) {
                b.await(10, TimeUnit.SECONDS);
            }
            if (fail) {
                throw new java.sql.SQLException("keys table down");
            }
            updates.add(keyId + "@" + lastUsedAtMillis);
        }
    }

    private static final Executor DIRECT = Runnable::run;

    // PERF-APIKEY-001 component (its once-per-minute criterion counts key requests, which wait for API-key authentication): 60 authentications of one key within 59 seconds cause exactly one update
    @Test
    public void lastUsedOncePerMinute() {
        UsageKeys keys = new UsageKeys();
        AtomicLong clock = new AtomicLong(1_000_000L);
        ApiKeyUsage u = new ApiKeyUsage(keys, DIRECT, clock::get, new RecordingLogger());
        long t0 = clock.get();
        for (int i = 0; i < 60; i++) {
            clock.set(t0 + i * 1000L); // 0 .. 59 seconds
            u.used("k1");
        }
        assertEquals(1, keys.updates.size());
    }

    // PERF-APIKEY-001 component (its second-update criterion counts key requests, which wait for API-key authentication): an authentication 60 seconds after the last update causes a second update
    @Test
    public void lastUsedAgainAfterAMinute() {
        UsageKeys keys = new UsageKeys();
        AtomicLong clock = new AtomicLong(1_000_000L);
        ApiKeyUsage u = new ApiKeyUsage(keys, DIRECT, clock::get, new RecordingLogger());
        assertTrue(u.used("k1"));
        clock.addAndGet(59_999);
        assertFalse(u.used("k1"));
        clock.addAndGet(1);
        assertTrue(u.used("k1"));
        assertEquals(2, keys.updates.size());
        assertTrue(u.used("k2"));
        assertEquals(3, keys.updates.size());
    }

    // PERF-APIKEY-001 component (its request-thread criterion waits for API-key authentication): the caller does not wait for the update: a store blocking 5 seconds does not delay it
    @Test(timeout = 20000)
    public void lastUsedOffTheCallerThread() throws Exception {
        UsageKeys keys = new UsageKeys();
        keys.block = new CountDownLatch(1);
        // the instance auth wires for .apiKeys(store), on its background executor
        Auth auth = Auth.builder(AuthTestSupport.SECRET).hashIterations(1000).logger(new RecordingLogger())
                .apiKeys(keys).build();
        ApiKeyUsage u = auth.apiKeyUsage();
        long t0 = System.nanoTime();
        assertTrue(u.used("k1"));
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) < 1000);
        assertTrue(keys.updates.isEmpty());
        keys.block.countDown();
        for (int i = 0; i < 500 && keys.updates.isEmpty(); i++) {
            Thread.sleep(10);
        }
        assertEquals(1, keys.updates.size());
    }

    // PERF-APIKEY-001 component (its request-path criterion waits for API-key authentication): a failing update is
    // logged as a WARN and never thrown to the caller
    @Test
    public void lastUsedFailureIsLoggedNotThrown() {
        UsageKeys keys = new UsageKeys();
        keys.fail = true;
        RecordingLogger log = new RecordingLogger();
        ApiKeyUsage u = new ApiKeyUsage(keys, DIRECT, () -> 1L, log);
        assertTrue(u.used("k1"));
        assertTrue(log.lines.toString(), log.lines.toString().contains("lastUsedAt"));
        Auth withKeys = Auth.builder(AuthTestSupport.SECRET).hashIterations(1000).logger(new RecordingLogger())
                .apiKeys(keys).build();
        assertNotNull(withKeys.apiKeyUsage());
        assertNull(Auth.builder(AuthTestSupport.SECRET).hashIterations(1000).logger(new RecordingLogger()).build()
                .apiKeyUsage());
    }

    // FR-FLOW-009 component (the login-path ACs wait for POST /auth/login): Lockout.after(n).forMinutes(m) locks after n
    // failures for m minutes, a release forgets it, an ended lock counts again; invalid policies are refused
    @Test
    public void lockoutStateAndPolicy() {
        Lockout policy = Lockout.after(5).forMinutes(15);
        assertEquals(5, policy.getFailures());
        assertEquals(15 * 60_000L, policy.getDurationMillis());
        AtomicLong clock = new AtomicLong(1_000L);
        Lockouts l = new Lockouts(policy.getFailures(), policy.getDurationMillis(), clock::get);
        for (int i = 0; i < 4; i++) {
            assertFalse(l.recordFailure("alice"));
        }
        assertFalse(l.isLocked("alice"));
        assertTrue(l.recordFailure("alice"));
        assertTrue(l.isLocked("alice"));
        clock.addAndGet(15 * 60_000L - 1);
        assertTrue(l.isLocked("alice"));
        clock.addAndGet(1);
        assertFalse(l.isLocked("alice"));
        for (int i = 0; i < 5; i++) {
            l.recordFailure("bob");
        }
        assertTrue(l.release("bob"));
        assertFalse(l.isLocked("bob"));
        for (int bad : new int[] {0, -1}) {
            try {
                Lockout.after(bad);
                fail("after(" + bad + ") accepted");
            } catch (IllegalArgumentException expected) {
                // refused
            }
        }
        try {
            Lockout.after(3).forMinutes(0);
            fail("forMinutes(0) accepted");
        } catch (IllegalArgumentException expected) {
            // refused
        }
    }

    // FR-FLOW-009 release route (the login half of its release criterion waits for POST /auth/login): DELETE /users/:id/lockout by an
    // auth-users:write admin is 204, clears the lock and the user-only throttle key, keeps the IP and (user, IP) keys
    @Test
    public void lockoutReleaseRoute() throws Exception {
        AdminFixture f = fixture().start(b -> b.lockout(Lockout.after(5).forMinutes(15)));
        String root = f.user("root", "admin");
        String alice = f.user("alice", "reader");
        for (int i = 0; i < 5; i++) {
            f.auth.lockouts().recordFailure(alice);
        }
        String userKey = ThrottleKeys.loginUser("alice");
        String ipKey = ThrottleKeys.loginIp("198.51.100.7");
        String userIpKey = ThrottleKeys.loginUserIp("alice", "198.51.100.7");
        for (int i = 0; i < 10; i++) {
            for (String k : new String[] {userKey, ipKey, userIpKey}) {
                f.auth.loginThrottle().recordFailure(k, 10, 15 * 60_000L);
            }
        }
        assertTrue(f.auth.loginThrottle().retryAfter(userKey, 10, 15 * 60_000L) > 0);
        assertEquals(204, f.call("DELETE", "/auth/admin/users/" + alice + "/lockout", root, null).status());
        assertFalse(f.auth.lockouts().isLocked(alice));
        assertEquals(0, f.auth.loginThrottle().retryAfter(userKey, 10, 15 * 60_000L));
        assertTrue(f.auth.loginThrottle().retryAfter(ipKey, 10, 15 * 60_000L) > 0);
        assertTrue(f.auth.loginThrottle().retryAfter(userIpKey, 10, 15 * 60_000L) > 0);
        assertEquals(204, f.call("DELETE", "/auth/admin/users/" + alice + "/lockout", root, null).status());
        assertNull(fixture().start().auth.lockouts());
    }

    private static Auth facade(AdminFixture f) {
        return f.auth;
    }

    // SEC-IAM-001 AC-1 (Java half; the Kotlin lambda is AuthWave6KotlinTest): UserMutator is a @FunctionalInterface
    // and not java.util.function.Function
    @Test
    public void SEC_IAM_001_AC1_mutatorIsItsOwnSam() {
        assertNotNull(UserMutator.class.getAnnotation(FunctionalInterface.class));
        assertFalse(java.util.function.Function.class.isAssignableFrom(UserMutator.class));
        for (Class<?> i : UserMutator.class.getInterfaces()) {
            assertFalse(i.getName(), i.getName().startsWith("java.util.function"));
        }
    }

    // SEC-IAM-001 AC-2: update(id, u -> attribute) stores the attribute and keeps passwordHash, ver, enabled, roles
    @Test
    public void SEC_IAM_001_AC2_updateKeepsSecurityFields() throws Exception {
        AdminFixture f = fixture().start();
        String id = f.user("alice", "reader");
        f.base.bumpTokenVersion(id);
        AuthUser before = f.stored(id);
        facade(f).users().update(id, u -> u.toBuilder().attribute("nickname", "n").build());
        AuthUser after = f.stored(id);
        assertEquals("n", after.getAttribute("nickname"));
        assertEquals(before.getPasswordHash(), after.getPasswordHash());
        assertEquals(before.getTokenVersion(), after.getTokenVersion());
        assertEquals(before.isEnabled(), after.isEnabled());
        assertEquals(before.getRoles(), after.getRoles());
    }

    // SEC-IAM-001 AC-3: the mutator sees a null hash; returning a hash "x" throws IllegalArgumentException, no write
    @Test
    public void SEC_IAM_001_AC3_hashStrippedAndRefused() throws Exception {
        AdminFixture f = fixture().start();
        String id = f.user("alice", "reader");
        AuthUser before = f.stored(id);
        final String[] seen = {"unset"};
        try {
            facade(f).users().update(id, u -> {
                seen[0] = u.getPasswordHash();
                return u.toBuilder().passwordHash("x").attribute("a", "b").build();
            });
            fail("a mutator set a hash");
        } catch (IllegalArgumentException expected) {
            // refused
        }
        assertNull(seen[0]);
        assertEquals(before, f.stored(id));
    }

    // SEC-IAM-001 AC-4: changing ver, enabled, roles, id, username or the service-account flag throws, each alone;
    // the username message points to rename; through auth.errors() the answer is 400
    @Test
    public void SEC_IAM_001_AC4_securityFieldsRefused() throws Exception {
        AdminFixture f = fixture().start();
        final String id = f.user("alice", "reader");
        UserMutator[] bad = {u -> u.toBuilder().tokenVersion(9).build(), u -> u.toBuilder().enabled(false).build(),
                u -> u.toBuilder().roles("admin").build(), u -> u.toBuilder().id("other").build(),
                u -> u.toBuilder().username("mallory").build(), u -> u.toBuilder().serviceAccount(true).build()};
        for (int i = 0; i < bad.length; i++) {
            try {
                facade(f).users().update(id, bad[i]);
                fail("mutator " + i + " was accepted");
            } catch (IllegalArgumentException e) {
                if (i == 4) {
                    assertTrue(e.getMessage(), e.getMessage().contains("rename"));
                }
            }
        }
        final java.util.concurrent.atomic.AtomicReference<String> target = new java.util.concurrent.atomic
                .AtomicReference<>();
        AdminFixture g = fixture().start(b -> b, x -> x.app.get("/api/bad",
                (req, res) -> x.auth.users().update(target.get(), u -> u.toBuilder().enabled(false).build())));
        String gid = g.user("alice", "reader");
        target.set(gid);
        assertEquals(400, g.call("GET", "/api/bad", gid, null).status());
    }

    // SEC-IAM-001 AC-5: create's init setting enabled=false or a hash throws; an init setting attributes stores them
    // with the hash create computed, and beforeUserCreate sees them
    @Test
    public void SEC_IAM_001_AC5_createInitRules() throws Exception {
        final List<String> seen = new CopyOnWriteArrayList<>();
        AdminFixture f = fixture().start(b -> b.events(new AuthEvents() {
            @Override
            public void beforeUserCreate(AuthUser candidate, AuthEventContext ctx) {
                seen.add(candidate.getAttribute("team"));
            }
        }));
        for (UserMutator init : new UserMutator[] {u -> u.toBuilder().enabled(false).build(),
                u -> u.toBuilder().passwordHash("x").build()}) {
            try {
                f.auth.users().create("eve" + seen.size(), "a-password-1".toCharArray(), init, "reader");
                fail("init accepted");
            } catch (IllegalArgumentException expected) {
                // refused
            }
        }
        AuthUser made = f.auth.users().create("carl", "a-password-1".toCharArray(),
                u -> u.toBuilder().attribute("team", "blue").build(), "reader");
        AuthUser stored = f.stored(made.getId());
        assertEquals("blue", stored.getAttribute("team"));
        assertTrue(f.auth.passwords().verify("a-password-1".toCharArray(), stored.getPasswordHash()));
        assertEquals(java.util.Collections.singletonList("blue"), seen);
    }

    // SEC-IAM-001 AC-7: an init setting the service-account flag with a null password stores a service account with
    // a null hash; the same with a password throws (400) and writes nothing
    @Test
    public void SEC_IAM_001_AC7_serviceAccountInit() throws Exception {
        AdminFixture f = fixture().start();
        AuthUser sa = f.auth.users().create("ci-bot", null, u -> u.toBuilder().serviceAccount(true).build(), "reader");
        assertTrue(f.stored(sa.getId()).isServiceAccount());
        assertNull(f.stored(sa.getId()).getPasswordHash());
        try {
            f.auth.users().create("ci-bot2", "a-password-1".toCharArray(),
                    u -> u.toBuilder().serviceAccount(true).build(), "reader");
            fail("a service account got a password");
        } catch (IllegalArgumentException expected) {
            // refused
        }
        assertNull(f.base.findByUsername("ci-bot2"));
        // through auth.errors() the refusal is 400 BAD_REQUEST
        AdminFixture g = fixture().start(b -> b, x -> x.app.get("/api/sa", (req, res) -> x.auth.users()
                .create("ci-bot3", "a-password-1".toCharArray(), u -> u.toBuilder().serviceAccount(true).build())));
        String caller = g.user("caller", "reader");
        com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse r = g.call("GET", "/api/sa", caller, null);
        assertEquals(400, r.status());
        assertEquals("BAD_REQUEST", AuthTestSupport.code(r));
        assertNull(g.base.findByUsername("ci-bot3"));
    }

    // SEC-IAM-001 AC-8: on the app path attribute keys are opaque: an attribute roles = "x" with role editor, and an
    // attribute enabled = "false" leave the fields as they were
    @Test
    public void SEC_IAM_001_AC8_attributeKeysOpaque() throws Exception {
        AdminFixture f = fixture().start();
        AuthUser made = f.auth.users().create("dan", "a-password-1".toCharArray(),
                u -> u.toBuilder().attribute("roles", "x").build(), "editor");
        AuthUser stored = f.stored(made.getId());
        assertEquals(AdminFixture.set("editor"), stored.getRoles());
        assertEquals("x", stored.getAttribute("roles"));
        long ver = stored.getTokenVersion();
        f.auth.users().update(made.getId(), u -> u.toBuilder().attribute("enabled", "false").build());
        assertTrue(f.stored(made.getId()).isEnabled());
        assertEquals(ver, f.stored(made.getId()).getTokenVersion());
        assertEquals("false", f.stored(made.getId()).getAttribute("enabled"));
    }

    // SEC-IAM-003 AC-5: the javadoc and docs/api state that ver only increases and that API keys must be reissued
    // after a password change
    @Test
    public void SEC_IAM_003_AC5_documented() throws Exception {
        Method bump = com.snoworca.naruserve.utils.auth.user.UserStore.class.getMethod("bumpTokenVersion", String.class);
        assertNotNull(bump);
        String src = new String(Files.readAllBytes(new File(root(),
                "NaruServe-api/src/main/java/com/snoworca/naruserve/utils/auth/user/UserStore.java").toPath()),
                StandardCharsets.UTF_8);
        assertTrue(src.contains("ver only increases"));
        assertTrue(src.contains("API keys issued before a password change must be reissued"));
        for (String doc : new String[] {"docs/api/auth.md", "docs/api/en/auth.md"}) {
            String text = new String(Files.readAllBytes(new File(root(), doc).toPath()), StandardCharsets.UTF_8);
            assertTrue(doc, text.contains(doc.contains("/en/") ? "ver only increases" : "ver는 늘기만"));
            assertTrue(doc, text.contains(doc.contains("/en/") ? "Reissue API keys after a password change"
                    : "비밀번호를 바꾸면 API 키를 다시 발급"));
        }
    }

    private static File root() {
        File d = new File("").getAbsoluteFile();
        while (d != null && !new File(d, "settings.gradle").isFile()) {
            d = d.getParentFile();
        }
        return d;
    }
}
