package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.utils.auth.jwt.JwtKeys;
import com.snoworca.naruserve.utils.auth.password.HashCost;
import com.snoworca.naruserve.utils.auth.password.PasswordHasher;
import com.snoworca.naruserve.utils.auth.password.Pbkdf2PasswordHasher;
import com.snoworca.naruserve.utils.auth.spi.KeySource;
import com.snoworca.naruserve.utils.auth.user.AuthUser;
import com.snoworca.naruserve.utils.auth.user.FileUserStore;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The key sources over real logins and protected requests (SEC-TOKEN-004), the hash slots on the login and password
 * endpoints (REL-TOKEN-001), the writes of the file refresh store (FR-TOKEN-002 AC-4) and the calibrated login timing
 * (PERF-TOKEN-001 AC-6), over the in-memory adapter with real HS256 tokens; nothing is a mock.
 */
public class Wave12TokenTest {

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

    private W8 node(KeySource keys) {
        W8 w = new W8();
        others.add(w);
        w.users = f.store;
        w.keys = keys;
        return w.start(b -> b);
    }

    private static byte[] key() {
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        return k;
    }

    /** The {@code kid} of an access token's header. */
    private static String kid(String token) {
        String header = new String(Base64.getUrlDecoder().decode(token.substring(0, token.indexOf('.'))),
                StandardCharsets.UTF_8);
        Object kid = ((Map<?, ?>) com.snoworca.naruserve.utils.auth.internal.MiniJson.parse(header,
                com.snoworca.naruserve.utils.auth.internal.MiniJson.Limits.claims())).get("kid");
        return String.valueOf(kid);
    }

    private static ClientResponse protectedGet(W8 w, String token) {
        return w.get("/api/x", "Authorization", "Bearer " + token);
    }

    // ---------------------------------------------------------------------------------------------- SEC-TOKEN-004

    // SEC-TOKEN-004 AC-1: a key set of KeySources.rotating(signing k2, verification k1) signs new tokens with kid k2 and
    // still verifies a token signed earlier with k1; after k1 is removed from the verification list the k1 token is
    // rejected
    @Test
    public void SEC_TOKEN_004_AC1_rotationKeepsOldTokensUntilTheKeyIsDropped() throws Exception {
        f.user("alice", W8.PW, "reader");
        byte[] k1 = key();
        byte[] k2 = key();
        W8 before = node(KeySources.rotating(JwtKeys.of("k1", k1)));
        String old = (String) before.loginBody("alice", W8.PW).get("accessToken");
        assertEquals("k1", kid(old));
        W8 rotated = node(KeySources.rotating(JwtKeys.builder().signingKey("k2", k2).verificationKey("k1", k1).build()));
        String fresh = (String) rotated.loginBody("alice", W8.PW).get("accessToken");
        assertEquals("k2", kid(fresh));
        assertEquals(200, protectedGet(rotated, fresh).status());
        assertEquals("the k1 token still verifies", 200, protectedGet(rotated, old).status());
        W8 dropped = node(KeySources.rotating(JwtKeys.of("k2", k2)));
        assertEquals(200, protectedGet(dropped, fresh).status());
        ClientResponse r = protectedGet(dropped, old);
        assertEquals(401, r.status());
        assertEquals("invalid_token", W8.code(r));
    }

    // SEC-TOKEN-004 AC-6: KeySources.fromBytes(bytes) and KeySources.of(secret) with the same key material produce
    // tokens that verify against each other's key set
    @Test
    public void SEC_TOKEN_004_AC6_fromBytesAndOfAgree() throws Exception {
        f.user("alice", W8.PW, "reader");
        String secret = "a-32-byte-or-longer-secret-for-the-test-only";
        W8 bytes = node(KeySources.fromBytes(secret.getBytes(StandardCharsets.UTF_8)));
        W8 text = node(KeySources.of(secret));
        String fromBytes = (String) bytes.loginBody("alice", W8.PW).get("accessToken");
        String fromText = (String) text.loginBody("alice", W8.PW).get("accessToken");
        assertEquals(kid(fromBytes), kid(fromText));
        assertEquals(200, protectedGet(text, fromBytes).status());
        assertEquals(200, protectedGet(bytes, fromText).status());
        assertEquals("x:" + f.store.findByUsername("alice").getId(), protectedGet(text, fromBytes).text());
    }

    /** A key source the test writes itself: one fixed signing key under kid vault-1. */
    static final class Vault implements KeySource {
        final byte[] key;

        Vault(byte[] key) {
            this.key = key.clone();
        }

        @Override
        public byte[] signingKey() {
            return key.clone();
        }

        @Override
        public String signingKid() {
            return "vault-1";
        }
    }

    // SEC-TOKEN-004 AC-8: an app KeySource (not from KeySources) returning one fixed 32-byte key under kid vault-1,
    // passed to Auth.builder(KeySource), is what auth signs with: the login's access token carries kid vault-1 and
    // authenticates; a token signed with any other key gets 401 invalid_token (the Kotlin half is AuthWave12KotlinTest)
    @Test
    public void SEC_TOKEN_004_AC8_appKeySourceSigns() throws Exception {
        f.user("alice", W8.PW, "reader");
        byte[] k = key();
        W8 vault = node(new Vault(k));
        assertTrue(KeySource.class.isInterface());
        String at = (String) vault.loginBody("alice", W8.PW).get("accessToken");
        assertEquals("vault-1", kid(at));
        assertEquals(200, protectedGet(vault, at).status());
        W8 other = node(KeySources.rotating(JwtKeys.of("vault-1", key())));
        String forged = (String) other.loginBody("alice", W8.PW).get("accessToken");
        assertEquals("same kid, another key", "vault-1", kid(forged));
        ClientResponse r = protectedGet(vault, forged);
        assertEquals(401, r.status());
        assertEquals("invalid_token", W8.code(r));
        assertArrayEquals(k, vault.auth.keySet().getSigningKey());
    }

    // ---------------------------------------------------------------------------------------------- REL-TOKEN-001

    /** A PBKDF2 hasher that can hold its verify and tracks the most computations seen at once. */
    static final class Gate implements PasswordHasher {
        final Pbkdf2PasswordHasher real = new Pbkdf2PasswordHasher(1000);
        final AtomicInteger running = new AtomicInteger();
        final AtomicInteger most = new AtomicInteger();
        final AtomicInteger computations = new AtomicInteger();
        volatile CountDownLatch hold;
        volatile CountDownLatch entered = new CountDownLatch(1);

        /** Holds every later computation until {@link #hold} is counted down; resets the counters. */
        void close() {
            computations.set(0);
            entered = new CountDownLatch(1);
            hold = new CountDownLatch(1);
        }
        volatile long workMillis;
        volatile boolean rehashAll;

        @Override
        public boolean accepts(String stored) {
            return real.accepts(stored);
        }

        private void enter() {
            computations.incrementAndGet();
            int now = running.incrementAndGet();
            most.accumulateAndGet(now, Math::max);
            entered.countDown();
            try {
                CountDownLatch h = hold;
                if (h != null) {
                    h.await(30, TimeUnit.SECONDS);
                }
                if (workMillis > 0) {
                    Thread.sleep(workMillis);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public String hash(char[] password) {
            enter();
            try {
                return real.hash(password);
            } finally {
                running.decrementAndGet();
            }
        }

        @Override
        public boolean verify(char[] password, String stored) {
            enter();
            try {
                return real.verify(password, stored);
            } finally {
                running.decrementAndGet();
            }
        }

        @Override
        public boolean needsRehash(String stored) {
            return rehashAll || real.needsRehash(stored);
        }
    }

    private Future<ClientResponse> loginAsync(W8 w, String name) {
        return w.adapter.submit(ClientRequest.json("POST", "/auth/login", W8.login(name, W8.PW)));
    }

    /** Threads calling AuthUsers.checkPassword, each a synchronous hashing request that holds or waits for a slot. */
    private static List<Thread> checks(Auth auth, List<String> ids) {
        List<Thread> out = new ArrayList<>();
        for (String id : ids) {
            Thread t = new Thread(() -> {
                try {
                    auth.users().checkPassword(id, W8.PW.toCharArray());
                } catch (RuntimeException e) {
                    // a waiter may time out once the test is done with it
                }
            });
            t.setDaemon(true);
            t.start();
            out.add(t);
        }
        return out;
    }

    private static void awaitRequested(Auth auth, long n) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (auth.hashSlots().requested() < n && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(n, auth.hashSlots().requested());
    }

    // REL-TOKEN-001 AC-2: with 1 slot held and 4 waiters queued, a 6th hashing request gets 503 with Retry-After without
    // entering the queue and without hashing; on POST /auth/login that 503 is sent no earlier than the 1.25T floor
    @Test(timeout = 60_000)
    public void REL_TOKEN_001_AC2_fullQueueAnswers503AtTheFloor() throws Exception {
        f.user("alice", W8.PW, "reader");
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ids.add(f.user("waiter" + i, W8.PW)); // one confirmation per user at a time: five users
        }
        Gate gate = new Gate();
        f.start(b -> b.passwordHasher(gate).hashConcurrency(1).hashCost(HashCost.calibrate(250)));
        gate.close();
        long floorNanos = f.auth.passwords().timing().timingNanos() * 5 / 4;
        assertEquals(312_500_000L, floorNanos);
        assertEquals(4, f.auth.hashSlots().queueCapacity());
        long base = f.auth.hashSlots().requested();
        List<Thread> waiting = checks(f.auth, ids.subList(0, 1));
        assertTrue(gate.entered.await(10, TimeUnit.SECONDS));
        waiting.addAll(checks(f.auth, ids.subList(1, 5)));
        awaitRequested(f.auth, base + 5);
        long t0 = System.nanoTime();
        ClientResponse sixth = f.post("/auth/login", W8.login("alice", W8.PW));
        long took = System.nanoTime() - t0;
        assertEquals(sixth.text(), 503, sixth.status());
        assertEquals("SERVICE_UNAVAILABLE", W8.code(sixth));
        assertEquals("1", sixth.header("Retry-After"));
        assertTrue("sent at the floor: " + took, took >= floorNanos);
        assertEquals("no hash for the refused request", 1, gate.computations.get());
        gate.hold.countDown();
        for (Thread t : waiting) {
            t.join(30_000L);
            assertFalse(t.isAlive());
        }
        assertEquals("the four queued requests ran after the holder, the refused one never", 5,
                gate.computations.get());
    }

    // REL-TOKEN-001 AC-3: a queued request that has not obtained a slot within 5 seconds fails with 503 and Retry-After:
    // a queued login gets the UNAVAILABLE outcome its endpoint answers as 503 Retry-After 1, and an app route's
    // checkPassword answers 503 JSON with Retry-After through auth.errors()
    @Test(timeout = 60_000)
    public void REL_TOKEN_001_AC3_waitCapIs503() throws Exception {
        String id = f.user("alice", W8.PW, "reader");
        Gate gate = new Gate();
        f.routes = w -> w.app.post("/check", (req, res) -> res.send(String.valueOf(
                w.auth.users().checkPassword(id, W8.PW.toCharArray()))));
        f.start(b -> b.passwordHasher(gate).hashConcurrency(1).permit(com.snoworca.naruserve.Method.POST, "/check"));
        gate.close();
        assertEquals(5000L, f.auth.hashSlots().waitMillis());
        List<Thread> holder = checks(f.auth, java.util.Collections.singletonList(f.user("holder", W8.PW)));
        assertTrue(gate.entered.await(10, TimeUnit.SECONDS));
        long t0 = System.nanoTime();
        PasswordLogin.Outcome o = Wave7LoginTest.attempt(f.auth.passwordLogin(), "alice", W8.PW, "203.0.113.1", null);
        long took = System.nanoTime() - t0;
        assertEquals(PasswordLogin.Kind.UNAVAILABLE, o.kind);
        assertTrue(String.valueOf(o.error), o.error instanceof AuthUnavailableException);
        assertEquals(String.valueOf(o.error.getMessage()), Long.valueOf(1L),
                ((AuthUnavailableException) o.error).getRetryAfterSeconds());
        assertTrue("waited the 5-second cap: " + took, took >= TimeUnit.MILLISECONDS.toNanos(4900));
        t0 = System.nanoTime();
        ClientResponse waited = f.post("/check", null);
        took = System.nanoTime() - t0;
        assertEquals(waited.text(), 503, waited.status());
        assertEquals("SERVICE_UNAVAILABLE", W8.code(waited));
        assertEquals("1", waited.header("Retry-After"));
        assertTrue("waited the 5-second cap: " + took, took >= TimeUnit.MILLISECONDS.toNanos(4900));
        assertEquals("the timed-out requests never hashed", 1, gate.computations.get());
        gate.hold.countDown();
        holder.get(0).join(30_000L);
    }

    // REL-TOKEN-001 AC-6: at no time do more than the configured number of computations run at once, counting logins,
    // the rehash work they trigger and concurrent POST /auth/password changes with their retries
    @Test(timeout = 120_000)
    public void REL_TOKEN_001_AC6_neverMoreComputationsThanSlots() throws Exception {
        Gate gate = new Gate();
        gate.workMillis = 15;
        gate.rehashAll = true;
        List<String> names = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            names.add("user" + i);
            f.user("user" + i, W8.PW, "reader");
        }
        f.start(b -> b.passwordHasher(gate).hashConcurrency(2));
        List<String> tokens = new ArrayList<>();
        for (String n : names) {
            tokens.add((String) f.loginBody(n, W8.PW).get("accessToken"));
        }
        gate.most.set(0);
        List<Future<ClientResponse>> all = new ArrayList<>();
        for (int round = 0; round < 3; round++) {
            for (String n : names) {
                all.add(loginAsync(f, n));
            }
            for (int i = 0; i < 3; i++) {
                // two changes of the same user race, so the atomic swap retries
                all.add(f.adapter.submit(ClientRequest.json("POST", "/auth/password", "{\"currentPassword\":\""
                        + W8.PW + "\",\"newPassword\":\"" + W8.PW + "\"}").header("Authorization", "Bearer "
                        + tokens.get(i % 2))));
            }
        }
        int ok = 0;
        for (Future<ClientResponse> r : all) {
            int s = r.get(60, TimeUnit.SECONDS).status();
            ok += s == 200 ? 1 : 0;
        }
        long idle = System.currentTimeMillis() + 30_000L;
        while (f.auth.rehashQueue().size() > 0 && System.currentTimeMillis() < idle) {
            Thread.sleep(10);
        }
        assertTrue("work ran: " + ok, ok > 0);
        assertTrue("computations: " + gate.computations.get(), gate.computations.get() > all.size());
        assertTrue("at most 2 at once, saw " + gate.most.get(), gate.most.get() <= 2);
        assertEquals("both slots were used", 2, gate.most.get());
    }

    // ---------------------------------------------------------------------------------------------- FR-TOKEN-002

    // FR-TOKEN-002 AC-4: the file refresh store beside a FileUserStore writes only when tokens are issued (login,
    // register, setup, password change), rotated or revoked on refresh, revoked on logout, or when the cleanup removes
    // expired records; 100 failed logins cause zero refresh-store writes
    @Test
    public void FR_TOKEN_002_AC4_fileRefreshStoreWritesOnlyOnTokenChanges() throws Exception {
        File dir = tmp.newFolder();
        FileUserStore users = new FileUserStore(new File(dir, "users.json"), AuthTestSupport.FILE_KEY);
        users.create(AuthUser.builder().username("alice")
                .passwordHash(new Pbkdf2PasswordHasher(1000).hash(W8.PW.toCharArray())).roles("reader").build());
        f.users = users;
        f.proxied = true;
        f.start("127.0.0.1", true, b -> b.register(true).recovery(true).user("admin", null, "admin"));
        File file = new File(dir, "users-refresh-tokens.json");
        failures(100);
        assertFalse("100 failed logins wrote nothing", file.exists());

        Map<String, Object> login = f.loginBody("alice", W8.PW, "X-Forwarded-For", "192.0.2.1");
        assertWritten(file, "login");
        String rt = (String) login.get("refreshToken");
        Map<String, Object> rotated = W8.object(f.post("/auth/refresh", "{\"refreshToken\":\"" + rt + "\"}"));
        assertWritten(file, "rotation");
        f.post("/auth/refresh", "{\"refreshToken\":\"" + rt + "\"}");
        assertWritten(file, "reuse revokeFamily");
        Map<String, Object> second = f.loginBody("alice", W8.PW, "X-Forwarded-For", "192.0.2.1");
        mark(file);
        ClientResponse out = f.post("/auth/logout", null, "Authorization", "Bearer " + second.get("accessToken"));
        assertEquals(204, out.status());
        assertWritten(file, "logout");
        ClientResponse reg = f.post("/auth/register", W8.login("bob", "another long password"));
        assertEquals(reg.text(), 201, reg.status());
        assertWritten(file, "register");
        ClientResponse setup = f.post("/auth/setup", "{\"token\":\"" + f.auth.pendingSetupToken()
                + "\",\"username\":\"admin\",\"password\":\"" + W8.PW + "\"}");
        assertEquals(setup.text(), 201, setup.status());
        assertWritten(file, "setup");
        Map<String, Object> third = f.loginBody("alice", W8.PW, "X-Forwarded-For", "192.0.2.1");
        mark(file);
        ClientResponse pw = f.post("/auth/password", "{\"currentPassword\":\"" + W8.PW
                + "\",\"newPassword\":\"another long password\"}", "Authorization", "Bearer " + third.get("accessToken"));
        assertEquals(pw.text(), 200, pw.status());
        assertWritten(file, "password change");
        failures(20);
        assertFalse("failed logins rewrote the refresh file", written(file));
        f.auth.cleanupRun();
        assertFalse("nothing expired: the cleanup wrote nothing", written(file));
        f.clock.addAndGet(30L * 24 * 3_600_000L);
        f.auth.cleanupRun();
        assertTrue("the cleanup removed expired records", written(file));
        assertNotNull(rotated.get("refreshToken"));
    }

    private void failures(int n) {
        for (int i = 0; i < n; i++) {
            ClientResponse r = f.post("/auth/login", W8.login("ghost" + i, "wrong password"),
                    "X-Forwarded-For", "198.51." + (i / 4) + "." + i);
            assertEquals(r.text(), 401, r.status());
        }
    }

    private static final long MARK = 1_000_000_000_000L;

    /** Sets an old modification time, so a later write (a new file renamed into place) shows. */
    private static void mark(File file) {
        assertTrue(file.setLastModified(MARK));
    }

    private static boolean written(File file) {
        return file.exists() && file.lastModified() != MARK;
    }

    private static void assertWritten(File file, String why) throws Exception {
        assertTrue(why + " wrote the refresh file", written(file));
        assertTrue(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).contains("\"version\":1"));
        mark(file);
    }

    // ---------------------------------------------------------------------------------------------- PERF-TOKEN-001

    // PERF-TOKEN-001 AC-6: under HashCost.calibrate(t) the login timing uses T = t: built with calibrate(250) auth's
    // timing is 250 ms, so a failure floor is 312.5 ms (the scheduled-delay half with a stub, a fast hasher and an
    // injected scheduler is SEC_TOKEN_009_AC2 in Wave7LoginTest); under HashCost.fixed(n) T is one hash measured at start
    @Test
    public void PERF_TOKEN_001_AC6_calibratedTargetIsTheLoginTime() throws Exception {
        Auth calibrated = Auth.builder(AuthTestSupport.SECRET).hashCost(HashCost.calibrate(250)).build();
        assertEquals(250_000_000L, calibrated.passwords().timing().timingNanos());
        Auth slower = Auth.builder(AuthTestSupport.SECRET).hashCost(HashCost.calibrate(400)).build();
        assertEquals(400_000_000L, slower.passwords().timing().timingNanos());
        Auth fixed = Auth.builder(AuthTestSupport.SECRET).hashCost(HashCost.fixed(50_000)).build();
        long t = fixed.passwords().timing().timingNanos();
        assertTrue("one measured hash: " + t, t > 0 && t != 250_000_000L);
        assertEquals(t, fixed.passwords().timing().timingNanos());
    }
}
