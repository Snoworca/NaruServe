package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.testing.TestApp;
import com.snoworca.naruserve.utils.auth.group.FileGroupStore;
import com.snoworca.naruserve.utils.auth.internal.MiniJson;
import com.snoworca.naruserve.utils.auth.password.Pbkdf2PasswordHasher;
import com.snoworca.naruserve.utils.auth.spi.ForwardingLoginThrottle;
import com.snoworca.naruserve.utils.auth.spi.LoginThrottle;
import com.snoworca.naruserve.utils.auth.user.AuthUser;
import com.snoworca.naruserve.utils.auth.user.FileUserStore;
import com.snoworca.naruserve.utils.auth.user.ForwardingUserStore;
import com.snoworca.naruserve.utils.auth.user.InMemoryUserStore;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.UnaryOperator;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The password-login limits over the real login endpoint (SEC-FLOW-011), the counter storage that follows the user
 * store (REL-FLOW-001: the snapshot files beside a FileUserStore, counting shared through app-provided stores), the built-in throttles'
 * limit arguments (IR-FLOW-003 AC-2), the key path's throttle calls (IR-APIKEY-001 AC-1) and the format version of
 * every auth file (REL-IAM-004 AC-7). Everything runs over the in-memory adapter with the shipped stores; nothing is a
 * mock.
 */
public class Wave12ThrottleTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final W8 f = new W8();
    private final List<W8> others = new ArrayList<>();

    @After
    public void stop() {
        f.stop();
        for (W8 o : others) {
            o.stop();
        }
    }

    /** Starts {@code w} behind one trusted proxy hop, so {@code X-Forwarded-For} is the client IP. */
    private static W8 proxied(W8 w, UnaryOperator<Auth.Builder> config) {
        w.proxied = true;
        return w.start(config);
    }

    private static ClientResponse login(W8 w, String name, String pw, String ip, String... more) {
        String[] h = new String[more.length + 2];
        h[0] = "X-Forwarded-For";
        h[1] = ip;
        System.arraycopy(more, 0, h, 2, more.length);
        return w.post("/auth/login", W8.login(name, pw), h);
    }

    private ClientResponse login(String name, String pw, String ip, String... more) {
        return login(f, name, pw, ip, more);
    }

    /** A HEADER login's device token (the body's deviceToken). */
    private static String device(W8 w, String name, String ip) {
        ClientResponse r = login(w, name, W8.PW, ip);
        assertEquals(r.text(), 200, r.status());
        Object d = W8.object(r).get("deviceToken");
        assertNotNull("a device token was issued", d);
        return (String) d;
    }

    /** Asserts the 429 {@code too_many_attempts} answer with a positive whole-second Retry-After; its header names. */
    private static TreeSet<String> assertThrottled(ClientResponse r) {
        assertEquals(r.text(), 429, r.status());
        assertEquals("too_many_attempts", W8.code(r));
        String retry = r.header("Retry-After");
        assertNotNull("Retry-After", retry);
        assertTrue(retry, Long.parseLong(retry) > 0);
        TreeSet<String> names = new TreeSet<>();
        for (Entry<String, String> e : r.headers()) {
            names.add(e.getKey().toLowerCase(java.util.Locale.ROOT));
        }
        return names;
    }

    // ---------------------------------------------------------------------------------------------- SEC-FLOW-011

    // SEC-FLOW-011 AC-1: 5 failed logins for alice from IP A within 15 minutes -> the 6th attempt from A, even with the
    // correct password, -> 429 too_many_attempts with a positive Retry-After
    // SEC-FLOW-011 AC-2: the same sequence for the unknown name nobody -> 429 at the same attempt number with the same
    // status, code and header set
    @Test
    public void SEC_FLOW_011_AC1_AC2_userIpLimitTreatsUnknownNamesAlike() throws Exception {
        f.user("alice", W8.PW, "reader");
        proxied(f, b -> b);
        for (int i = 0; i < 5; i++) {
            assertEquals(401, login("alice", "wrong password " + i, "203.0.113.1").status());
            f.clock.addAndGet(60_000L);
        }
        TreeSet<String> known = assertThrottled(login("alice", W8.PW, "203.0.113.1"));
        for (int i = 0; i < 5; i++) {
            assertEquals(401, login("nobody", "wrong password " + i, "203.0.113.2").status());
            f.clock.addAndGet(60_000L);
        }
        TreeSet<String> unknown = assertThrottled(login("nobody", W8.PW, "203.0.113.2"));
        assertEquals("the same header set", known, unknown);
    }

    // SEC-FLOW-011 AC-3: 20 failures from IP A over different names (at most 4 per name) -> the next attempt from A
    // without a trusted device -> 429; a valid trusted-device token of a real user from A is not limited by the IP key
    @Test
    public void SEC_FLOW_011_AC3_ipLimitExemptsTrustedDevices() throws Exception {
        f.user("bob", W8.PW, "reader");
        proxied(f, b -> b);
        String d = device(f, "bob", "198.51.100.200");
        for (int i = 0; i < 20; i++) {
            assertEquals(401, login("name" + (i % 5), "wrong password", "203.0.113.3").status());
        }
        assertThrottled(login("someone", "wrong password", "203.0.113.3"));
        assertThrottled(login("bob", W8.PW, "203.0.113.3"));
        ClientResponse trusted = login("bob", W8.PW, "203.0.113.3", "Naru-Device", d);
        assertEquals(trusted.text(), 200, trusted.status());
    }

    // SEC-FLOW-011 AC-4: 10 failures for admin from 10 distinct IPs within 15 minutes -> the 11th attempt from a new IP
    // without a trusted device -> 429; admin's trusted device from that IP is not limited by the user-only key
    @Test
    public void SEC_FLOW_011_AC4_userOnlyLimitSparesTheTrustedDevice() throws Exception {
        f.user("admin", W8.PW, "reader");
        proxied(f, b -> b);
        String d = device(f, "admin", "192.0.2.200");
        for (int i = 0; i < 10; i++) {
            assertEquals(401, login("admin", "guess " + i, "198.51.100." + (i + 1)).status());
        }
        assertThrottled(login("admin", W8.PW, "198.51.100.50"));
        ClientResponse trusted = login("admin", W8.PW, "198.51.100.50", "Naru-Device", d);
        assertEquals(trusted.text(), 200, trusted.status());
    }

    // SEC-FLOW-011 AC-5: a 429 is written without sleeping: the login path holds no Thread.sleep; with the test clock
    // never advanced (nothing waits on the TimeSource) and a 625 ms failure floor (HashCost.calibrate(500)), the 429
    // arrives at once by the wall clock while every failure waits for the floor
    @Test
    public void SEC_FLOW_011_AC5_throttledAnswerIsNotDelayed() throws Exception {
        File src = new File("src/main/java/com/snoworca/naruserve/utils/auth");
        for (String name : new String[] {"AccountEndpoints.java", "PasswordLogin.java", "internal/LoginLimits.java",
            "InMemoryLoginThrottle.java", "FileLoginThrottle.java", "internal/FailureFloor.java"}) {
            String text = new String(Files.readAllBytes(new File(src, name).toPath()), StandardCharsets.UTF_8);
            assertFalse(name + " sleeps", text.contains("Thread.sleep"));
            assertFalse(name + " sleeps", text.contains("TimeUnit.") && text.contains(".sleep("));
            assertFalse(name + " parks", text.contains("LockSupport"));
        }
        f.user("alice", W8.PW, "reader");
        f.user("bob", W8.PW, "reader");
        proxied(f, b -> b.hashCost(com.snoworca.naruserve.utils.auth.password.HashCost.calibrate(500)));
        long fastest = Long.MAX_VALUE;
        for (int i = 0; i < 5; i++) {
            long t0 = System.nanoTime();
            assertEquals(401, login("alice", "wrong password", "203.0.113.5").status());
            fastest = Math.min(fastest, System.nanoTime() - t0);
        }
        assertTrue("a failure waits for the 625 ms floor: " + fastest, fastest >= 600_000_000L);
        long t0 = System.nanoTime();
        assertThrottled(login("alice", W8.PW, "203.0.113.5"));
        long took = System.nanoTime() - t0;
        // a sanity bound under the 625 ms floor; the deterministic check is the recording floor below
        assertTrue("the 429 is not held to the floor: " + took + " ns", took < 600_000_000L);
        // the same floor over a recording scheduler: a failure schedules its answer, the 429 schedules nothing
        Wave7LoginTest.RecordingScheduler sched = new Wave7LoginTest.RecordingScheduler();
        try {
            f.auth.failureFloor(new com.snoworca.naruserve.utils.auth.internal.FailureFloor(
                    f.auth.passwords().timing(), sched));
            assertThrottled(login("alice", W8.PW, "203.0.113.5"));
            assertTrue("the 429 is written without a scheduled delay: " + sched.delaysNanos,
                    sched.delaysNanos.isEmpty());
            assertEquals(401, login("bob", "wrong password", "203.0.113.6").status());
            assertEquals("a failure is still answered through the floor", 1, sched.delaysNanos.size());
        } finally {
            sched.shutdownNow();
        }
    }

    // SEC-FLOW-011 AC-6: the IP part of the key is req.remoteAddress() after proxy trust, and the IPv6 addresses of one
    // /64 share a key: failures from 2001:db8::1 and 2001:db8::ffff count together
    @Test
    public void SEC_FLOW_011_AC6_ipv6SlashSixtyFourSharesAKey() throws Exception {
        f.user("alice", W8.PW, "reader");
        proxied(f, b -> b);
        for (int i = 0; i < 5; i++) {
            assertEquals(401, login("alice", "wrong", i % 2 == 0 ? "2001:db8::1" : "2001:db8::ffff").status());
        }
        assertThrottled(login("alice", W8.PW, "2001:db8::1"));
        assertThrottled(login("alice", W8.PW, "2001:db8::abcd"));
        assertEquals("another /64 is another key", 200, login("alice", W8.PW, "2001:db8:0:1::1").status());
        // without proxy trust the forwarded address is not the key: the in-memory peer is
        W8 direct = new W8();
        others.add(direct);
        direct.user("carol", W8.PW, "reader");
        direct.start("10.9.9.9", true, b -> b);
        for (int i = 0; i < 5; i++) {
            assertEquals(401, login(direct, "carol", "wrong", "198.51.100." + i).status());
        }
        assertThrottled(login(direct, "carol", W8.PW, "198.51.100.99"));
    }

    // SEC-FLOW-011 AC-7: builder-configured limits replace the defaults: with the (user, IP) limit set to 3
    // (.loginFailureLimits(3, 20, 10, 900_000)) the 4th attempt from the same IP -> 429
    @Test
    public void SEC_FLOW_011_AC7_configuredLimitsReplaceTheDefaults() throws Exception {
        f.user("alice", W8.PW, "reader");
        proxied(f, b -> b.loginFailureLimits(3, 20, 10, 900_000L));
        for (int i = 0; i < 3; i++) {
            assertEquals(401, login("alice", "wrong", "203.0.113.7").status());
        }
        assertThrottled(login("alice", W8.PW, "203.0.113.7"));
        // the default (5) lets the 4th attempt through
        W8 def = new W8();
        others.add(def);
        def.user("alice", W8.PW, "reader");
        proxied(def, b -> b);
        for (int i = 0; i < 3; i++) {
            assertEquals(401, login(def, "alice", "wrong", "203.0.113.7").status());
        }
        assertEquals(200, login(def, "alice", W8.PW, "203.0.113.7").status());
        // the other two limits and the window are replaced too: an IP limit of 2 within a 10-second window
        W8 ip = new W8();
        others.add(ip);
        proxied(ip, b -> b.loginFailureLimits(5, 2, 10, 10_000L));
        assertEquals(401, login(ip, "x1", "wrong", "203.0.113.8").status());
        assertEquals(401, login(ip, "x2", "wrong", "203.0.113.8").status());
        assertThrottled(login(ip, "x3", "wrong", "203.0.113.8"));
        ip.clock.addAndGet(10_000L);
        assertEquals("the window passed", 401, login(ip, "x3", "wrong", "203.0.113.8").status());
        // a limit below 1 or a window under a second is refused at build()
        try {
            Auth.builder(AuthTestSupport.SECRET).loginFailureLimits(0, 20, 10, 900_000L).build();
            fail("a zero limit was accepted");
        } catch (AuthConfigException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(".loginFailureLimits(0, 20, 10, 900000)"));
        }
    }

    // SEC-FLOW-011 AC-8: login throttling applies only to password login: failures for a service account's name do not
    // block API-key authentication of that account
    @Test
    public void SEC_FLOW_011_AC8_loginFailuresDoNotBlockTheServiceAccountKey() throws Exception {
        proxied(f, b -> b.apiKeys(new com.snoworca.naruserve.utils.auth.apikey.InMemoryApiKeyStore()));
        String svc = f.store.create(AuthUser.builder().username("ci-bot").serviceAccount(true).roles("reader")
                .build()).getId();
        String key = f.auth.apiKeys().issue(svc, "ci", null, "posts:read").getPlainKey();
        for (int i = 0; i < 12; i++) {
            login("ci-bot", "guess " + i, "203.0.113.9");
            login("ci-bot", "guess " + i, "198.51.100." + i);
        }
        assertThrottled(login("ci-bot", "guess", "203.0.113.9"));
        ClientResponse r = f.get("/api/x", "Authorization", "ApiKey " + key, "X-Forwarded-For", "203.0.113.9");
        assertEquals(r.text(), 200, r.status());
        assertEquals("x:" + svc, r.text());
    }

    // SEC-FLOW-011 AC-9: the keys use the normalized login name: alice, Alice, ALICE, fullwidth ALICE and aLiCe share
    // one (user, IP) counter, and admin/ADMIN from 10 IPs share one user-only counter
    @Test
    public void SEC_FLOW_011_AC9_keysUseTheNormalizedName() throws Exception {
        f.user("alice", W8.PW, "reader");
        f.user("admin", W8.PW, "reader");
        proxied(f, b -> b);
        for (String spelling : new String[] {"alice", "Alice", "ALICE", "ＡＬＩＣＥ", "aLiCe"}) {
            assertEquals(spelling, 401, login(spelling, "wrong", "203.0.113.10").status());
        }
        assertThrottled(login("alice", W8.PW, "203.0.113.10"));
        for (int i = 0; i < 10; i++) {
            assertEquals(401, login(i % 2 == 0 ? "admin" : "ADMIN", "wrong", "192.0.2." + (i + 1)).status());
        }
        assertThrottled(login("admin", W8.PW, "192.0.2.77"));
    }

    // ---------------------------------------------------------------------------------------------- REL-FLOW-001

    /** A FileUserStore deployment in a fresh directory, keyed as PROD requires (FR-IAM-015 AC-3). */
    private FileUserStore fileUsers(File dir) throws Exception {
        return fileUsers(dir, AuthTestSupport.FILE_KEY);
    }

    /** {@link #fileUsers(File)} with {@code key}; null writes the hashes plain (a DEV deployment). */
    private FileUserStore fileUsers(File dir, @org.jspecify.annotations.Nullable AuthFileKey key) throws Exception {
        FileUserStore store = new FileUserStore(new File(dir, "users.json"), key);
        store.create(AuthUser.builder().username("alice")
                .passwordHash(new Pbkdf2PasswordHasher(1000).hash(W8.PW.toCharArray())).roles("reader").build());
        return store;
    }

    // REL-FLOW-001 AC-3: FileUserStore deployment: 10 failed logins within 60 seconds cause no throttle file write per
    // failure; one snapshot is written at the next 60-second tick
    @Test
    public void REL_FLOW_001_AC3_throttleFileIsASnapshotNotPerFailure() throws Exception {
        File dir = tmp.newFolder();
        f.users = fileUsers(dir);
        proxied(f, b -> b);
        FileLoginThrottle throttle = (FileLoginThrottle) f.auth.loginThrottle();
        File file = new File(dir, "users-login-throttle.json");
        assertEquals(file.getAbsoluteFile(), throttle.getFile().getAbsoluteFile());
        for (int i = 0; i < 10; i++) {
            assertEquals(401, login("alice", "wrong", "198.51.100." + i).status());
        }
        assertEquals("no write per failure", 0, throttle.writes());
        assertFalse(file.exists());
        assertEquals(60_000L, Auth.SNAPSHOT_INTERVAL_MILLIS);
        f.auth.snapshotRun(); // the 60-second tick
        assertEquals(1, throttle.writes());
        String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        assertTrue(text, text.contains(FileLoginThrottle.hashed("login:u:alice")));
        assertFalse("no login name or address on disk: " + text, text.contains("alice") || text.contains("198.51"));
        f.auth.snapshotRun();
        assertEquals("an unchanged tick writes nothing", 1, throttle.writes());
        // the snapshot is read back at start: a restarted node still blocks the user-only key
        f.stop();
        W8 again = new W8();
        others.add(again);
        again.users = new FileUserStore(new File(dir, "users.json"), AuthTestSupport.FILE_KEY);
        again.clock.set(f.clock.get());
        proxied(again, b -> b);
        assertThrottled(login(again, "alice", W8.PW, "192.0.2.1"));
    }

    // REL-FLOW-001 AC-3 (the scheduled tick): the auth scheduler writes the snapshot on its own tick, once per change
    @Test
    public void REL_FLOW_001_AC3_theSchedulerWritesTheSnapshot() throws Exception {
        File dir = tmp.newFolder();
        f.users = fileUsers(dir);
        proxied(f, b -> b.snapshotInterval(50L));
        FileLoginThrottle throttle = (FileLoginThrottle) f.auth.loginThrottle();
        awaitTicks(f.auth, 2);
        assertEquals("nothing changed, nothing written", 0, throttle.writes());
        assertEquals(401, login("alice", "wrong", "198.51.100.1").status());
        long deadline = System.currentTimeMillis() + 10_000L;
        while (throttle.writes() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        // a tick may fall between the keys of one failed login: the tick after the one running now started after the
        // login, so once it finished every change of the login is written
        awaitTicks(f.auth, 2);
        int written = throttle.writes();
        assertTrue(String.valueOf(written), written >= 1);
        awaitTicks(f.auth, 2);
        assertEquals("no change, no further write", written, throttle.writes());
    }

    /** Waits (bounded) until {@code n} more ticks of the snapshot scheduler have finished. */
    private static void awaitTicks(Auth auth, int n) throws InterruptedException {
        long until = auth.snapshotTicks() + n;
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (auth.snapshotTicks() < until && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertTrue("the snapshot scheduler ticks", auth.snapshotTicks() >= until);
    }

    // REL-FLOW-001 AC-4: FileUserStore deployment: the 5th failure on one device token invalidates it and the
    // invalidation is on disk before the next snapshot tick
    @Test
    @SuppressWarnings("unchecked")
    public void REL_FLOW_001_AC4_deviceInvalidationIsWrittenAtOnce() throws Exception {
        File dir = tmp.newFolder();
        f.users = fileUsers(dir);
        proxied(f, b -> b);
        FileDeviceTrust trust = (FileDeviceTrust) f.auth.deviceTrust();
        File file = new File(dir, "users-device-trust.json");
        String d = device(f, "alice", "192.0.2.1");
        for (int i = 0; i < 4; i++) {
            assertEquals(401, login("alice", "wrong", "198.51.100." + i, "Naru-Device", d).status());
        }
        assertEquals("4 failures: nothing written yet", 0, trust.writes());
        assertEquals(401, login("alice", "wrong", "198.51.100.9", "Naru-Device", d).status());
        assertEquals("the invalidation is written at once", 1, trust.writes());
        Map<String, Object> doc = (Map<String, Object>) MiniJson.parse(Files.readAllBytes(file.toPath()),
                MiniJson.Limits.file());
        List<Object> tokens = (List<Object>) doc.get("tokens");
        assertEquals(1, tokens.size());
        assertEquals(Boolean.TRUE, ((Map<String, Object>) tokens.get(0)).get("invalidated"));
        // a restarted node reads the invalidation: D no longer exempts from the IP key
        for (int i = 0; i < 20; i++) {
            login("n" + (i % 5), "wrong", "203.0.113.20");
        }
        f.stop();
        W8 again = new W8();
        others.add(again);
        again.users = new FileUserStore(new File(dir, "users.json"), AuthTestSupport.FILE_KEY);
        again.clock.set(f.clock.get());
        proxied(again, b -> b);
        assertThrottled(login(again, "alice", W8.PW, "203.0.113.20", "Naru-Device", d));
    }

    /** A user store the auth module does not ship. */
    static final class CustomStore extends ForwardingUserStore {
        CustomStore() {
            super(new InMemoryUserStore());
        }
    }

    // REL-FLOW-001 AC-5: the default storage follows the user store: InMemoryUserStore -> memory, FileUserStore ->
    // files in the same directory; a custom (app-provided) store -> memory with exactly one restart-logout
    // doctor WARN under SINGLE_INSTANCE (shared with the refresh fallback), and build() throws under MULTI_INSTANCE
    @Test
    public void REL_FLOW_001_AC5_defaultsFollowTheUserStore() throws Exception {
        Auth mem = Auth.builder(AuthTestSupport.SECRET).hashIterations(1000).users(new InMemoryUserStore()).build();
        assertTrue(mem.loginThrottle() instanceof InMemoryLoginThrottle);
        assertTrue(mem.deviceTrust() instanceof InMemoryDeviceTrust);
        File dir = tmp.newFolder();
        Auth file = Auth.builder(AuthTestSupport.SECRET).hashIterations(1000).users(fileUsers(dir)).build();
        assertEquals(dir.getAbsoluteFile(), ((FileLoginThrottle) file.loginThrottle()).getFile().getParentFile());
        assertEquals(dir.getAbsoluteFile(), ((FileDeviceTrust) file.deviceTrust()).getFile().getParentFile());

        RecordingLogger log = new RecordingLogger();
        Auth custom = Auth.builder(AuthTestSupport.SECRET).hashIterations(1000).logger(log).users(new CustomStore())
                .build();
        assertTrue(custom.loginThrottle() instanceof InMemoryLoginThrottle);
        assertTrue(custom.deviceTrust() instanceof InMemoryDeviceTrust);
        TestApp t = TestApp.create();
        custom.install(t.app());
        t.start();
        try {
            assertEquals(log.lines.toString(), 1, RecordingLogger.countMatching(log.lines,
                    ".*auth doctor WARN restart logout: .*login throttle.*device trust.*"));
            assertEquals(1, RecordingLogger.countMatching(log.lines, ".*restart logout.*"));
        } finally {
            t.app().shutdown(5, java.util.concurrent.TimeUnit.SECONDS);
        }
        // the refresh store set, the throttle and device trust still fall back: still exactly one WARN
        RecordingLogger log2 = new RecordingLogger();
        Auth half = Auth.builder(AuthTestSupport.SECRET).hashIterations(1000).logger(log2).users(new CustomStore())
                .refreshTokens(new InMemoryRefreshTokenStore()).build();
        TestApp t2 = TestApp.create();
        half.install(t2.app());
        t2.start();
        try {
            assertEquals(1, RecordingLogger.countMatching(log2.lines, ".*restart logout.*"));
        } finally {
            t2.app().shutdown(5, java.util.concurrent.TimeUnit.SECONDS);
        }
        try {
            Auth.builder(AuthTestSupport.SECRET).hashIterations(1000).users(new CustomStore())
                    .deployment(Deployment.MULTI_INSTANCE).build();
            fail("MULTI_INSTANCE fell back to memory counters");
        } catch (AuthConfigException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("login throttle"));
            assertTrue(e.getMessage(), e.getMessage().contains("device trust"));
        }
    }

    /**
     * Two nodes over one shared app database: each node gets its own app-provided user store, login throttle and
     * device trust over the same {@link AppStores} (test implementations with atomic counting, standing in for the
     * application's database; no JDBC driver).
     */
    private W8[] twoAppNodes() throws Exception {
        AppStores db = new AppStores(f.clock::get);
        db.users().create(AuthUser.builder().username("alice")
                .passwordHash(new Pbkdf2PasswordHasher(1000).hash(W8.PW.toCharArray())).roles("reader").build());
        W8 a = f;
        W8 b = new W8();
        others.add(b);
        a.users = db.users();
        b.users = db.users();
        proxied(a, x -> x.loginThrottle(db.loginThrottle()).deviceTrust(db.deviceTrust()));
        proxied(b, x -> x.loginThrottle(db.loginThrottle()).deviceTrust(db.deviceTrust()));
        assertTrue(a.auth.loginThrottle() instanceof AppStores.Throttle);
        assertTrue(b.auth.deviceTrust().getClass().getName().startsWith(AppStores.class.getName()));
        return new W8[] {a, b};
    }

    // REL-FLOW-001 AC-6: two Auth instances sharing one app-provided LoginThrottle (atomic counting over one shared
    // backing store, no JDBC driver): 3 failures on node A and 2 on node B for the same (user, IP) key -> the next
    // attempt on either node -> 429
    @Test
    public void REL_FLOW_001_AC6_sharedThrottleCountsAcrossNodes() throws Exception {
        W8[] n = twoAppNodes();
        for (int i = 0; i < 3; i++) {
            assertEquals(401, login(n[0], "alice", "wrong", "203.0.113.30").status());
        }
        for (int i = 0; i < 2; i++) {
            assertEquals(401, login(n[1], "alice", "wrong", "203.0.113.30").status());
        }
        assertThrottled(login(n[0], "alice", W8.PW, "203.0.113.30"));
        assertThrottled(login(n[1], "alice", W8.PW, "203.0.113.30"));
    }

    // REL-FLOW-001 AC-7: two Auth instances sharing one app-provided DeviceTrust (one shared backing store, no JDBC
    // driver): 3 failures with device token D on node A and 2 on node B -> D is invalidated, and the next attempt with
    // D on either node is handled as a request without a device token (here: limited by the exhausted user-only key,
    // which a trusted device would skip)
    @Test
    public void REL_FLOW_001_AC7_sharedDeviceTrustInvalidatesOnEveryNode() throws Exception {
        W8[] n = twoAppNodes();
        String d = device(n[0], "alice", "192.0.2.1");
        String control = device(n[1], "alice", "192.0.2.2");
        for (int i = 0; i < 3; i++) {
            assertEquals(401, login(n[0], "alice", "wrong", "198.51.100." + i, "Naru-Device", d).status());
        }
        for (int i = 3; i < 5; i++) {
            assertEquals(401, login(n[1], "alice", "wrong", "198.51.100." + i, "Naru-Device", d).status());
        }
        for (int i = 0; i < 10; i++) {
            assertEquals(401, login(n[i % 2], "alice", "wrong", "203.0.113." + (100 + i)).status());
        }
        assertThrottled(login(n[0], "alice", W8.PW, "192.0.2.50", "Naru-Device", d));
        assertThrottled(login(n[1], "alice", W8.PW, "192.0.2.51", "Naru-Device", d));
        ClientResponse ok = login(n[1], "alice", W8.PW, "192.0.2.52", "Naru-Device", control);
        assertEquals("a token that was not invalidated still skips the user-only key", 200, ok.status());
    }

    /** Overwrites {@code file} with {@code text}. */
    private static void write(File file, String text) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Asserts that building auth over the FileUserStore of {@code dir} fails naming version 9, leaving {@code file}. */
    private void refusesVersion9(File dir, File file) throws Exception {
        byte[] before = Files.readAllBytes(file.toPath());
        try {
            Auth.builder(AuthTestSupport.SECRET).hashIterations(1000)
                    .users(new FileUserStore(new File(dir, "users.json"))).build();
            fail("an unknown version of " + file.getName() + " was accepted");
        } catch (AuthConfigException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("version 9"));
        }
        assertArrayEquals("not overwritten", before, Files.readAllBytes(file.toPath()));
    }

    // REL-FLOW-001 AC-8: the throttle snapshot file and the device-trust file each carry a format-version field, and a
    // start with either file at an unknown version fails naming the version and does not overwrite the file
    // REL-IAM-004 AC-7: every auth file written through AtomicFiles carries a format version (users, groups, refresh
    // tokens, throttle snapshot, device trust); a start with an unknown version fails naming it, and the file is
    // byte-for-byte unchanged
    @Test
    public void REL_FLOW_001_AC8_REL_IAM_004_AC7_everyAuthFileIsVersioned() throws Exception {
        File dir = tmp.newFolder();
        // DEV without an AuthFileKey: the users file stays format version 1 (FR-IAM-015 AC-4)
        f.users = fileUsers(dir, null);
        FileGroupStore groupStore = new FileGroupStore(new File(dir, "groups.txt"));
        proxied(f, b -> b.groups(groupStore).mode(AuthMode.DEV));
        String d = device(f, "alice", "192.0.2.1"); // writes the refresh file
        for (int i = 0; i < 5; i++) {
            login("alice", "wrong", "198.51.100." + i, "Naru-Device", d); // the 5th invalidation writes the trust file
        }
        groupStore.createGroup("staff");
        f.auth.snapshotRun();
        f.stop();
        File users = new File(dir, "users.json");
        File groups = new File(dir, "groups.txt");
        File refresh = new File(dir, "users-refresh-tokens.json");
        File throttle = new File(dir, "users-login-throttle.json");
        File trust = new File(dir, "users-device-trust.json");
        for (File json : new File[] {users, refresh, throttle, trust}) {
            String text = new String(Files.readAllBytes(json.toPath()), StandardCharsets.UTF_8);
            assertTrue(json.getName() + ": " + text, text.contains("\"version\":1"));
        }
        String groupText = new String(Files.readAllBytes(groups.toPath()), StandardCharsets.UTF_8);
        assertTrue(groupText, groupText.startsWith("naru-groups 1"));

        // the users file: FileUserStore construction fails, the file is unchanged
        for (File json : new File[] {refresh, throttle, trust}) {
            String good = new String(Files.readAllBytes(json.toPath()), StandardCharsets.UTF_8);
            write(json, good.replace("\"version\":1", "\"version\":9"));
            refusesVersion9(dir, json);
            write(json, good);
        }
        String goodUsers = new String(Files.readAllBytes(users.toPath()), StandardCharsets.UTF_8);
        write(users, goodUsers.replace("\"version\":1", "\"version\":9"));
        byte[] before = Files.readAllBytes(users.toPath());
        try {
            new FileUserStore(users);
            fail("users.json version 9 was accepted");
        } catch (java.io.IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("version 9"));
        }
        assertArrayEquals(before, Files.readAllBytes(users.toPath()));
        write(groups, groupText.replaceFirst("naru-groups 1", "naru-groups 9"));
        before = Files.readAllBytes(groups.toPath());
        try {
            new FileGroupStore(groups);
            fail("groups version 9 was accepted");
        } catch (java.io.IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("version 9"));
        }
        assertArrayEquals(before, Files.readAllBytes(groups.toPath()));
    }

    /** The throttle file's text after a snapshot. */
    private String snapshotText(File dir) throws Exception {
        f.auth.snapshotRun();
        return new String(Files.readAllBytes(new File(dir, "users-login-throttle.json").toPath()),
                StandardCharsets.UTF_8);
    }

    // REL-FLOW-001 (retention): the 10-minute cleanup forgets the default throttle's keys whose last failure is older
    // than 24 hours (or than the longest configured window), so the counters and the file keep no old entry
    @Test
    public void REL_FLOW_001_oldKeysAreForgotten() throws Exception {
        File dir = tmp.newFolder();
        f.users = fileUsers(dir);
        proxied(f, b -> b);
        assertEquals(86_400_000L, f.auth.throttleRetentionMillis());
        assertEquals(401, login("alice", "wrong", "198.51.100.1").status());
        f.clock.addAndGet(23 * 3_600_000L);
        assertEquals(401, login("bob", "wrong", "198.51.100.2").status());
        f.auth.cleanupRun();
        assertTrue("alice's failure is younger than 24 h",
                snapshotText(dir).contains(FileLoginThrottle.hashed("login:u:alice")));
        f.clock.addAndGet(2 * 3_600_000L);
        f.auth.cleanupRun();
        String text = snapshotText(dir);
        assertFalse("alice's keys are gone", text.contains(FileLoginThrottle.hashed("login:u:alice")));
        assertTrue("bob's are kept", text.contains(FileLoginThrottle.hashed("login:u:bob")));
        InMemoryLoginThrottle mem = InMemoryLoginThrottle.builder().timeSource(f.clock::get).build();
        mem.recordFailure("k", 5, 60_000L);
        assertEquals(1, mem.purgeOlderThan(f.clock.get() + 1));
    }

    // REL-FLOW-001 (retention): a configured window longer than 24 hours keeps its keys: with a 48-hour window, 5
    // failures, 25 hours and a cleanup, the 6th attempt is still 429 over the in-memory default
    @Test
    public void REL_FLOW_001_longWindowsSurviveTheCleanup() throws Exception {
        f.user("alice", W8.PW, "reader");
        proxied(f, b -> b.loginFailureLimits(5, 20, 10, 48 * 3_600_000L));
        assertEquals(48 * 3_600_000L, f.auth.throttleRetentionMillis());
        for (int i = 0; i < 5; i++) {
            assertEquals(401, login("alice", "wrong", "203.0.113.40").status());
        }
        f.clock.addAndGet(25 * 3_600_000L);
        f.auth.cleanupRun();
        assertThrottled(login("alice", W8.PW, "203.0.113.40"));
        // an app-supplied in-memory throttle is never purged by auth
        W8 own = new W8();
        others.add(own);
        InMemoryLoginThrottle mine = InMemoryLoginThrottle.builder().timeSource(own.clock::get).build();
        proxied(own, b -> b.loginThrottle(mine));
        mine.recordFailure("app-key", 1, 365L * 24 * 3_600_000L);
        own.clock.addAndGet(30L * 24 * 3_600_000L);
        own.auth.cleanupRun();
        assertTrue(mine.retryAfter("app-key", 1, 365L * 24 * 3_600_000L) > 0);
    }

    // REL-FLOW-001 AC-1 (the device trust half): with the cap set to 3, a 4th token drops the token whose window started
    // longest ago among those not invalidated, an invalidated token is kept, and exactly one WARN is logged
    @Test
    public void REL_FLOW_001_AC1_deviceTrustIsCapped() throws Exception {
        RecordingLogger log = new RecordingLogger();
        final java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong(1_000_000L);
        InMemoryDeviceTrust trust = new InMemoryDeviceTrust(now::get, 3, log);
        long exp = 10_000_000L;
        com.snoworca.naruserve.utils.auth.spi.DeviceToken a = new com.snoworca.naruserve.utils.auth.spi.DeviceToken(
                "a", "u", 0, exp);
        com.snoworca.naruserve.utils.auth.spi.DeviceToken b = new com.snoworca.naruserve.utils.auth.spi.DeviceToken(
                "b", "u", 0, exp);
        com.snoworca.naruserve.utils.auth.spi.DeviceToken c = new com.snoworca.naruserve.utils.auth.spi.DeviceToken(
                "c", "u", 0, exp);
        com.snoworca.naruserve.utils.auth.spi.DeviceToken d = new com.snoworca.naruserve.utils.auth.spi.DeviceToken(
                "d", "u", 0, exp);
        com.snoworca.naruserve.utils.auth.spi.DeviceToken e = new com.snoworca.naruserve.utils.auth.spi.DeviceToken(
                "e", "u", 0, exp);
        assertTrue("a is invalidated at once (limit 1)", trust.recordFailure(a, 1, 60_000L));
        now.addAndGet(10);
        trust.recordFailure(b, 5, 60_000L);
        now.addAndGet(10);
        trust.recordFailure(c, 5, 60_000L);
        now.addAndGet(10);
        trust.recordFailure(d, 5, 60_000L); // drops b: the oldest window not invalidated
        assertTrue("the invalidation is kept", trust.isInvalidated(a));
        assertEquals(3, trust.snapshot().size());
        assertFalse(trust.snapshot().containsKey("b"));
        trust.recordFailure(e, 5, 60_000L); // drops c, no second WARN
        assertFalse(trust.snapshot().containsKey("c"));
        assertEquals(log.lines.toString(), 1, RecordingLogger.countMatching(log.lines, "WARN .*device trust.*cap.*"));
    }

    // ---------------------------------------------------------------------------------------------- IR-FLOW-003

    // IR-FLOW-003 AC-2: InMemoryLoginThrottle honours the arguments (3 failures within 60 s block, 60 s later 0); the
    // default for FileUserStore with .apiKeys(store) and no throttle does too, and the doctor has no AC-4 WARN
    @Test
    public void IR_FLOW_003_AC2_builtInThrottlesHonourTheArguments() throws Exception {
        final java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong(1_000_000L);
        LoginThrottle mem = InMemoryLoginThrottle.builder().timeSource(now::get).build();
        assertHonours(mem, now);
        File dir = tmp.newFolder();
        f.users = fileUsers(dir);
        f.clock.set(1_000_000L);
        RecordingLogger log = f.logger;
        proxied(f, b -> b.apiKeys(new com.snoworca.naruserve.utils.auth.apikey.InMemoryApiKeyStore()));
        LoginThrottle effective = f.auth.loginThrottle();
        assertTrue(effective instanceof FileLoginThrottle);
        assertHonours(effective, f.clock);
        assertEquals(log.lines.toString(), 0, RecordingLogger.countMatching(log.lines, ".*login throttle limits.*"));
        for (com.snoworca.naruserve.utils.auth.internal.Doctor.Finding x : f.auth.doctor(f.app).findings()) {
            assertFalse(x.toString(), x.getItem().equals("login throttle limits"));
        }
    }

    private static void assertHonours(LoginThrottle t, java.util.concurrent.atomic.AtomicLong now) throws Exception {
        assertTrue(t.usesLimitArguments());
        String k = "k-" + System.nanoTime();
        for (int i = 0; i < 3; i++) {
            t.recordFailure(k, 3, 60_000L);
        }
        assertTrue(t.retryAfter(k, 3, 60_000L) > 0);
        now.addAndGet(60_000L);
        assertEquals(0L, t.retryAfter(k, 3, 60_000L));
    }

    // ---------------------------------------------------------------------------------------------- IR-APIKEY-001

    /** Records every LoginThrottle call by name and arity. */
    static final class Spy extends ForwardingLoginThrottle {
        final List<String> calls = new CopyOnWriteArrayList<>();

        Spy() {
            super(new InMemoryLoginThrottle());
        }

        @Override
        public long retryAfter(String key) throws Exception {
            calls.add("retryAfter/1 " + key);
            return super.retryAfter(key);
        }

        @Override
        public long recordFailure(String key) throws Exception {
            calls.add("recordFailure/1 " + key);
            return super.recordFailure(key);
        }

        @Override
        public void reset(String key) throws Exception {
            calls.add("reset " + key);
            super.reset(key);
        }

        @Override
        public long retryAfter(String key, int max, long windowMillis) throws Exception {
            calls.add("retryAfter/3 " + key);
            return super.retryAfter(key, max, windowMillis);
        }

        @Override
        public long recordFailure(String key, int max, long windowMillis) throws Exception {
            calls.add("recordFailure/3 " + key);
            return super.recordFailure(key, max, windowMillis);
        }
    }

    // IR-APIKEY-001 AC-1: during key authentication auth calls only the three-argument retryAfter and recordFailure (a
    // spy throttle records no one-argument call for key failures) and never calls reset after a successful key
    @Test
    public void IR_APIKEY_001_AC1_keyPathUsesOnlyTheLimitArgumentMethods() throws Exception {
        Spy spy = new Spy();
        proxied(f, b -> b.loginThrottle(spy)
                .apiKeys(new com.snoworca.naruserve.utils.auth.apikey.InMemoryApiKeyStore()));
        String owner = f.store.create(AuthUser.builder().username("bot").serviceAccount(true).roles("reader")
                .build()).getId();
        String key = f.auth.apiKeys().issue(owner, "ci", null, "posts:read").getPlainKey();
        spy.calls.clear();
        for (int i = 0; i < 3; i++) {
            assertEquals(401, f.get("/api/x", "Authorization", "ApiKey " + Wave9KeyAuthTest.wrongSecret(key),
                    "X-Forwarded-For", "198.51.100.1").status());
        }
        assertEquals(200, f.get("/api/x", "Authorization", "ApiKey " + key, "X-Forwarded-For", "198.51.100.1")
                .status());
        assertFalse(spy.calls.isEmpty());
        int failures = 0;
        for (String c : spy.calls) {
            assertTrue(c, c.startsWith("retryAfter/3 ") || c.startsWith("recordFailure/3 "));
            failures += c.startsWith("recordFailure/3 ") ? 1 : 0;
        }
        assertEquals("one count per failed key", 3, failures);
    }
}
