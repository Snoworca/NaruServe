package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.testing.TestApp;
import com.snoworca.naruserve.utils.auth.PasswordLogin.Kind;
import com.snoworca.naruserve.utils.auth.PasswordLogin.Outcome;
import com.snoworca.naruserve.utils.auth.internal.Base64Url;
import com.snoworca.naruserve.utils.auth.internal.DeviceTokens;
import com.snoworca.naruserve.utils.auth.internal.FailureFloor;
import com.snoworca.naruserve.utils.auth.internal.HashSlots;
import com.snoworca.naruserve.utils.auth.internal.HashTiming;
import com.snoworca.naruserve.utils.auth.internal.LoginLimits;
import com.snoworca.naruserve.utils.auth.internal.Passwords;
import com.snoworca.naruserve.utils.auth.jwt.JwtKeys;
import com.snoworca.naruserve.utils.auth.password.DigestEncoding;
import com.snoworca.naruserve.utils.auth.password.HashCost;
import com.snoworca.naruserve.utils.auth.password.PasswordHasher;
import com.snoworca.naruserve.utils.auth.password.PasswordHashers;
import com.snoworca.naruserve.utils.auth.password.Pbkdf2PasswordHasher;
import com.snoworca.naruserve.utils.auth.spi.DeviceToken;
import com.snoworca.naruserve.utils.auth.spi.DeviceTrust;
import com.snoworca.naruserve.utils.auth.testing.AuthTesting;
import com.snoworca.naruserve.utils.auth.user.AuthUser;
import com.snoworca.naruserve.utils.auth.user.ForwardingUserStore;
import com.snoworca.naruserve.utils.auth.user.InMemoryUserStore;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Test;

/**
 * The password-login sequence auth's POST /auth/login runs (the endpoint arrives with it): the throttle and
 * device-trust check order (SEC-FLOW-012), the signed device token and its per-token failure limit (SEC-FLOW-013) and
 * the constant work and failure floor of R38 (SEC-TOKEN-009), driven through the PasswordLogin seam with counting
 * stores and an instrumented hasher.
 */
public class Wave7LoginTest {

    private static final long T0 = 1_761_700_900_000L;
    private static final String PW = "correct horse";
    private final AtomicLong clock = new AtomicLong(T0);
    private final InMemoryUserStore mem = new InMemoryUserStore();
    private final CountingUsers users = new CountingUsers(mem);
    private TestApp app;

    @After
    public void stop() {
        if (app != null) {
            app.app().shutdown(5, TimeUnit.SECONDS);
        }
    }

    /** A user store counting findByUsername, optionally resolving extra names to a user. */
    static final class CountingUsers extends ForwardingUserStore {
        final AtomicInteger finds = new AtomicInteger();
        final java.util.Map<String, String> aliases = new java.util.HashMap<>();

        CountingUsers(InMemoryUserStore d) {
            super(d);
        }

        @Override
        public AuthUser findByUsername(String username) throws Exception {
            finds.incrementAndGet();
            String alias = aliases.get(username);
            return alias != null ? delegate().findById(alias) : delegate().findByUsername(username);
        }

        @Override
        public boolean supportsAtomicFieldWrites() {
            return true;
        }
    }

    /** A hasher wrapper counting hash and verify calls (each is one computation of the wrapped hasher). */
    static final class Counting implements PasswordHasher {
        final PasswordHasher inner;
        final AtomicInteger computations = new AtomicInteger();

        Counting(PasswordHasher inner) {
            this.inner = inner;
        }

        @Override
        public boolean accepts(String stored) {
            return inner.accepts(stored);
        }

        @Override
        public String hash(char[] password) {
            computations.incrementAndGet();
            return inner.hash(password);
        }

        @Override
        public boolean verify(char[] password, String stored) {
            computations.incrementAndGet();
            return inner.verify(password, stored);
        }

        @Override
        public boolean needsRehash(String stored) {
            return inner.needsRehash(stored);
        }
    }

    /** A scheduler recording each delay and running the task at once. */
    static final class RecordingScheduler extends ScheduledThreadPoolExecutor {
        final List<Long> delaysNanos = Collections.synchronizedList(new ArrayList<Long>());

        RecordingScheduler() {
            super(1);
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            delaysNanos.add(unit.toNanos(delay));
            return super.schedule(command, 0, TimeUnit.NANOSECONDS);
        }
    }

    private Auth.Builder builder() {
        return Auth.builder(AuthTestSupport.SECRET).hashIterations(1000).logger(new RecordingLogger()).users(users)
                .timeSource(clock::get);
    }

    private AuthUser user(String name, String password) throws Exception {
        return mem.create(AuthUser.builder().username(name)
                .passwordHash(password == null ? null : new Pbkdf2PasswordHasher(1000).hash(password.toCharArray()))
                .build());
    }

    static Outcome attempt(PasswordLogin login, String name, String password, String ip, String device)
            throws Exception {
        final AtomicReference<Outcome> out = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);
        login.attempt(name, password.toCharArray(), ip, device, o -> {
            out.set(o);
            done.countDown();
        });
        assertTrue("the attempt finished", done.await(30, TimeUnit.SECONDS));
        return out.get();
    }

    private Outcome attempt(Auth auth, String name, String password, String ip, String device) throws Exception {
        return attempt(auth.passwordLogin(), name, password, ip, device);
    }

    private String deviceOf(Auth auth, String name, String ip) throws Exception {
        Outcome o = attempt(auth, name, PW, ip, null);
        assertEquals(Kind.SUCCESS, o.kind);
        assertNotNull(o.deviceToken);
        return o.deviceToken.token();
    }

    // ---------------------------------------------------------------------------------------------- SEC-FLOW-012

    // SEC-FLOW-012 AC-1: an attacker exhausts admin's user-only key (10 failures from rotating IPs); admin from a device
    // holding a valid device token with the correct password -> success
    @Test
    public void SEC_FLOW_012_AC1_trustedDeviceIgnoresTheUserOnlyKey() throws Exception {
        user("admin", PW);
        Auth auth = builder().build();
        String d = deviceOf(auth, "admin", "10.0.0.1");
        for (int i = 0; i < 10; i++) {
            assertEquals(Kind.INVALID_CREDENTIALS, attempt(auth, "admin", "guess" + i, "198.51.100." + i, null).kind);
        }
        assertEquals(Kind.THROTTLED, attempt(auth, "admin", PW, "10.0.0.2", null).kind);
        Outcome o = attempt(auth, "admin", PW, "10.0.0.2", d);
        assertEquals(Kind.SUCCESS, o.kind);
        assertTrue(o.trustedDevice);
    }

    // SEC-FLOW-012 AC-2: without a device token, an attempt from an IP whose IP key is exhausted -> 429 before
    // findByUsername (the store counter stays 0)
    @Test
    public void SEC_FLOW_012_AC2_exhaustedIpKeyAnswersBeforeTheStore() throws Exception {
        Auth auth = builder().build();
        for (int i = 0; i < 20; i++) {
            attempt(auth, "name" + (i % 5), "x", "203.0.113.7", null);
        }
        users.finds.set(0);
        Outcome o = attempt(auth, "someone", "x", "203.0.113.7", null);
        assertEquals(Kind.THROTTLED, o.kind);
        assertTrue(o.retryAfterMillis > 0);
        assertEquals(0, users.finds.get());
    }

    // SEC-FLOW-012 AC-3: an exhausted (user, IP) key -> 429 before findByUsername, with or without a trusted device
    @Test
    public void SEC_FLOW_012_AC3_exhaustedUserIpKeyAnswersBeforeTheStore() throws Exception {
        user("alice", PW);
        Auth auth = builder().build();
        String d = deviceOf(auth, "alice", "10.0.0.9");
        for (int i = 0; i < 5; i++) {
            attempt(auth, "alice", "wrong", "203.0.113.8", d);
        }
        for (String device : new String[] {null, d}) {
            users.finds.set(0);
            assertEquals(Kind.THROTTLED, attempt(auth, "alice", PW, "203.0.113.8", device).kind);
            assertEquals(0, users.finds.get());
        }
    }

    // SEC-FLOW-012 AC-4: a device token passing signature, expiry and fingerprint whose user id or ver differs from
    // the stored user, or whose user is gone, is treated as no device token: the deferred IP key and the user-only key
    // are checked
    @Test
    public void SEC_FLOW_012_AC4_staleDeviceTokenFallsBackToAllKeys() throws Exception {
        AuthUser alice = user("alice", PW);
        Auth auth = builder().build();
        String d = deviceOf(auth, "alice", "10.0.0.1");
        for (int i = 0; i < 20; i++) {
            attempt(auth, "other" + (i % 5), "x", "203.0.113.9", null); // exhaust the IP key of .9
        }
        assertEquals("trusted: exempt from the IP key", Kind.SUCCESS, attempt(auth, "alice", PW, "203.0.113.9", d).kind);
        mem.bumpTokenVersion(alice.getId());
        assertEquals("ver differs: the deferred IP key", Kind.THROTTLED,
                attempt(auth, "alice", PW, "203.0.113.9", d).kind);
        String d2 = deviceOf(auth, "alice", "10.0.0.1");
        for (int i = 0; i < 10; i++) {
            attempt(auth, "alice", "wrong", "192.0.2." + i, null); // untrusted: fills the user-only key
        }
        Outcome viaD2 = attempt(auth, "alice", PW, "10.1.1.1", d2);
        assertEquals("trusted ignores the user-only key", Kind.SUCCESS, viaD2.kind);
        String d3 = viaD2.deviceToken.token();
        mem.bumpTokenVersion(alice.getId());
        assertEquals("ver differs: the user-only key", Kind.THROTTLED, attempt(auth, "alice", PW, "10.1.1.2", d3).kind);
        mem.delete(alice.getId());
        Outcome gone = attempt(auth, "alice", PW, "203.0.113.9", d3);
        assertEquals("user gone: the deferred IP key", Kind.THROTTLED, gone.kind);
        assertFalse(gone.trustedDevice);
    }

    // SEC-FLOW-012 AC-5: an attacker attaching the device token of their own account while guessing another name ->
    // fingerprint mismatch -> the IP key is checked first (before the store)
    @Test
    public void SEC_FLOW_012_AC5_foreignDeviceTokenIsCheckedOnTheIpKeyFirst() throws Exception {
        user("mallory", PW);
        user("victim", PW);
        Auth auth = builder().build();
        String own = deviceOf(auth, "mallory", "203.0.113.50");
        for (int i = 0; i < 20; i++) {
            attempt(auth, "victim" + (i % 5), "x", "203.0.113.50", own);
        }
        users.finds.set(0);
        Outcome o = attempt(auth, "victim", "guess", "203.0.113.50", own);
        assertEquals(Kind.THROTTLED, o.kind);
        assertEquals(0, users.finds.get());
        assertEquals("the own name still trusts it", Kind.SUCCESS, attempt(auth, "mallory", PW, "203.0.113.50", own).kind);
    }

    // SEC-FLOW-012 AC-6: during the AC-1 attack a new device gets 429 from the user-only key while the trusted device
    // works; after another admin's DELETE /auth/admin/users/:id/lockout the new-device login from a fresh IP succeeds,
    // while the attacker's IPs stay limited by their (user, IP) keys
    @Test
    public void SEC_FLOW_012_AC6_lockoutReleaseEndsTheUserOnlyBlock() throws Exception {
        AuthUser admin = user("admin", PW);
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        app = TestApp.create(adapter);
        final Auth auth = builder().build();
        auth.install(app.app());
        app.app().onError(auth.errors());
        app.start();
        String d = deviceOf(auth, "admin", "10.0.0.1");
        for (int i = 0; i < 10; i++) {
            attempt(auth, "admin", "guess" + i, i < 5 ? "198.51.100.1" : "198.51.100.2", null);
        }
        assertEquals(Kind.THROTTLED, attempt(auth, "admin", PW, "10.0.0.3", null).kind);
        assertEquals(Kind.SUCCESS, attempt(auth, "admin", PW, "10.0.0.3", d).kind);
        AuthUser admin2 = mem.create(AuthUser.builder().username("admin2").passwordHash("x").roles("admin").build());
        String other = AuthTesting.tokenForUserId(auth, admin2.getId());
        ClientResponse r = adapter.send(ClientRequest.of("DELETE", "/auth/admin/users/" + admin.getId() + "/lockout")
                .header("Authorization", "Bearer " + other));
        assertEquals(r.text(), 204, r.status());
        assertEquals(Kind.SUCCESS, attempt(auth, "admin", PW, "10.0.0.4", null).kind);
        assertEquals(Kind.THROTTLED, attempt(auth, "admin", PW, "198.51.100.1", null).kind);
        assertEquals(Kind.THROTTLED, attempt(auth, "admin", PW, "198.51.100.2", null).kind);
    }

    // ---------------------------------------------------------------------------------------------- SEC-FLOW-013

    // SEC-FLOW-013 AC-1: an issued device token decodes to user id, ver, fingerprint, random value and expiry, and holds
    // no raw login name
    @Test
    public void SEC_FLOW_013_AC1_tokenContentHasNoLoginName() throws Exception {
        AuthUser alice = user("alice.smith", PW);
        Auth auth = builder().build();
        String d = deviceOf(auth, "alice.smith", "10.0.0.1");
        String[] parts = d.split("\\.");
        assertEquals(3, parts.length);
        String header = new String(Base64Url.decode(parts[0]), StandardCharsets.UTF_8);
        String payload = new String(Base64Url.decode(parts[1]), StandardCharsets.UTF_8);
        assertTrue(header, header.contains("\"kid\"") && header.contains(DeviceTokens.TYPE));
        for (String claim : new String[] {"\"sub\":\"" + alice.getId() + "\"", "\"ver\":0", "\"fp\":", "\"jti\":",
            "\"exp\":"}) {
            assertTrue(payload + " lacks " + claim, payload.contains(claim));
        }
        assertFalse(payload, payload.toLowerCase().contains("alice"));
        DeviceToken c = auth.deviceTokens().read(d, "alice.smith");
        assertNotNull(c);
        assertEquals(alice.getId(), c.getUserId());
        assertEquals(T0 + DeviceTokens.LIFETIME_MILLIS, c.getExpiresAt());
    }

    // SEC-FLOW-013 AC-2: a token issued at login as alice is trusted for the next login as alice; when the store also
    // resolves alice@example.com to the same user, a login as alice@example.com with that token is not trusted
    @Test
    public void SEC_FLOW_013_AC2_fingerprintBindsTheLoginName() throws Exception {
        AuthUser alice = user("alice", PW);
        users.aliases.put("alice@example.com", alice.getId());
        Auth auth = builder().build();
        String d = deviceOf(auth, "alice", "10.0.0.1");
        Outcome same = attempt(auth, "alice", PW, "10.0.0.1", d);
        assertTrue(same.trustedDevice);
        Outcome mail = attempt(auth, "alice@example.com", PW, "10.0.0.1", d);
        assertEquals(Kind.SUCCESS, mail.kind);
        assertFalse(mail.trustedDevice);
    }

    // SEC-FLOW-013 AC-4: after a rename alice -> alice2 the old token is not trusted for alice2, nor for another user
    // who later takes the name alice
    @Test
    public void SEC_FLOW_013_AC4_renameAndNameReuseLoseTrust() throws Exception {
        AuthUser alice = user("alice", PW);
        Auth auth = builder().build();
        String d = deviceOf(auth, "alice", "10.0.0.1");
        mem.update(mem.findById(alice.getId()).toBuilder().username("alice2").build());
        assertFalse(attempt(auth, "alice2", PW, "10.0.0.1", d).trustedDevice);
        user("alice", PW);
        Outcome taken = attempt(auth, "alice", PW, "10.0.0.1", d);
        assertEquals(Kind.SUCCESS, taken.kind);
        assertFalse(taken.trustedDevice);
    }

    // SEC-FLOW-013 AC-5: a token signed under kid k1 stays trusted after the signing key rotates to k2 while k1 remains
    // a verification key, and is not trusted once k1 is removed
    @Test
    public void SEC_FLOW_013_AC5_keyRotationKeepsTrustWhileTheOldKidVerifies() throws Exception {
        user("alice", PW);
        byte[] k1 = "k1-k9Qv2Lm7Xr4Tz8Wb1Nc6Ys3Hd5Gf0Ja".getBytes(StandardCharsets.UTF_8);
        byte[] k2 = "k2-Zp8Rt4Vy1Bx7Nm3Qw6Lk9Hs2Jd5Fg0Cc".getBytes(StandardCharsets.UTF_8);
        Auth one = Auth.builder(KeySources.rotating(JwtKeys.of("k1", k1))).hashIterations(1000)
                .logger(new RecordingLogger()).users(users).timeSource(clock::get).build();
        String d = deviceOf(one, "alice", "10.0.0.1");
        Auth rotated = Auth.builder(KeySources.rotating(JwtKeys.builder().signingKey("k2", k2)
                .verificationKey("k1", k1).build())).hashIterations(1000).logger(new RecordingLogger()).users(users)
                .timeSource(clock::get).build();
        assertTrue(attempt(rotated, "alice", PW, "10.0.0.1", d).trustedDevice);
        Auth dropped = Auth.builder(KeySources.rotating(JwtKeys.of("k2", k2))).hashIterations(1000)
                .logger(new RecordingLogger()).users(users).timeSource(clock::get).build();
        assertFalse(attempt(dropped, "alice", PW, "10.0.0.1", d).trustedDevice);
    }

    // SEC-FLOW-013 AC-6: 20 failures from A6 for u1..u5 (4 each) exhaust A6's IP key; 5 failures as alice with her
    // trusted token D from A1..A5 invalidate D at the 5th, so alice with D and the correct password from A6 -> 429
    // from A6's IP key; with only 4 failures D stays trusted and the A6 login succeeds
    @Test
    public void SEC_FLOW_013_AC6_perTokenFailureLimitInvalidatesTheToken() throws Exception {
        for (int failures : new int[] {5, 4}) {
            InMemoryUserStore store = new InMemoryUserStore();
            store.create(AuthUser.builder().username("alice")
                    .passwordHash(new Pbkdf2PasswordHasher(1000).hash(PW.toCharArray())).build());
            Auth auth = Auth.builder(AuthTestSupport.SECRET).hashIterations(1000).logger(new RecordingLogger())
                    .users(store).timeSource(clock::get).build();
            String d = deviceOf(auth, "alice", "10.9.9.9");
            for (int u = 1; u <= 5; u++) {
                for (int i = 0; i < 4; i++) {
                    assertEquals(Kind.INVALID_CREDENTIALS, attempt(auth, "u" + u, "x", "192.0.2.6", null).kind);
                }
            }
            for (int i = 1; i <= failures; i++) {
                Outcome f = attempt(auth, "alice", "wrong", "192.0.2." + i, d);
                assertEquals(Kind.INVALID_CREDENTIALS, f.kind);
                assertTrue("D is trusted while it fails", f.trustedDevice);
            }
            Outcome last = attempt(auth, "alice", PW, "192.0.2.6", d);
            if (failures == 5) {
                assertEquals(Kind.THROTTLED, last.kind);
                Outcome without = attempt(auth, "alice", PW, "192.0.2.6", null);
                assertEquals("the same answer as without a device token", without.kind, last.kind);
            } else {
                assertEquals(Kind.SUCCESS, last.kind);
                assertTrue(last.trustedDevice);
            }
        }
    }

    // SEC-FLOW-013 AC-8: a token whose 90-day expiry has passed (TimeSource + 90 days + 1 s) is not trusted
    @Test
    public void SEC_FLOW_013_AC8_expiredTokenIsNotTrusted() throws Exception {
        user("alice", PW);
        Auth auth = builder().build();
        String d = deviceOf(auth, "alice", "10.0.0.1");
        clock.addAndGet(DeviceTokens.LIFETIME_MILLIS - 1000);
        assertNotNull(auth.deviceTokens().read(d, "alice"));
        clock.addAndGet(2000);
        assertNull(auth.deviceTokens().read(d, "alice"));
        assertFalse(attempt(auth, "alice", PW, "10.0.0.1", d).trustedDevice);
    }

    /** A DeviceTrust fake recording each call. */
    static final class FakeTrust implements DeviceTrust {
        final List<String> calls = Collections.synchronizedList(new ArrayList<String>());

        @Override
        public void issued(DeviceToken token) {
            calls.add("issued " + token.getUserId());
        }

        @Override
        public boolean isInvalidated(DeviceToken token) {
            calls.add("isInvalidated");
            return false;
        }

        @Override
        public boolean recordFailure(DeviceToken token, int max, long windowMillis) {
            calls.add("recordFailure " + max + "/" + windowMillis);
            return false;
        }
    }

    // SEC-FLOW-013 AC-9: spi.DeviceTrust is public; a fake configured with .deviceTrust(..) is the one a login calls to
    // record the issued token, to judge trust in step 3 and to record the per-token failures; without one the default
    // follows the user store (memory here, a file beside a FileUserStore; app-provided shared stores under
    // MULTI_INSTANCE: Wave7MultiInstanceTest FR_AUTH_003_AC3_multiInstanceNeedsSharedStores; the Kotlin implementation:
    // AuthWave7KotlinTest)
    @Test
    public void SEC_FLOW_013_AC9_configuredDeviceTrustIsTheOneCalled() throws Exception {
        assertTrue(java.lang.reflect.Modifier.isPublic(DeviceTrust.class.getModifiers()) && DeviceTrust.class.isInterface());
        AuthUser alice = user("alice", PW);
        FakeTrust fake = new FakeTrust();
        Auth auth = builder().deviceTrust(fake).build();
        assertSame(fake, auth.deviceTrust());
        String d = deviceOf(auth, "alice", "10.0.0.1");
        attempt(auth, "alice", "wrong", "10.0.0.1", d);
        assertEquals(java.util.Arrays.asList("issued " + alice.getId(), "isInvalidated",
                "recordFailure 5/" + (15 * 60_000L)), fake.calls);
        assertEquals("InMemoryDeviceTrust", builder().build().deviceTrust().getClass().getSimpleName());
    }

    // ---------------------------------------------------------------------------------------------- SEC-TOKEN-009

    private static final String MD5_PREFIX = "legacy-md5";

    private static String md5Hex(String text) throws Exception {
        StringBuilder b = new StringBuilder();
        for (byte x : java.security.MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.UTF_8))) {
            b.append(String.format("%02x", x));
        }
        return b.toString();
    }

    // SEC-TOKEN-009 AC-1: an unknown user, a PBKDF2 user (stored count = target) with a wrong password, an MD5 user
    // with a wrong password and a service account / null-hash user each run exactly one target-cost PBKDF2 inside one
    // slot; an MD5 and a bcrypt user with the correct password run the legacy verify plus one PBKDF2 in one slot, and
    // the rehash runs outside that slot
    @Test
    public void SEC_TOKEN_009_AC1_everyAttemptRunsOnePbkdf2InOneSlot() throws Exception {
        Counting pbkdf2 = new Counting(new Pbkdf2PasswordHasher(1000));
        PasswordHasher md5raw = PasswordHashers.digest(MD5_PREFIX, "MD5", DigestEncoding.HEX);
        Counting md5 = new Counting(md5raw);
        Counting bcrypt = new Counting(PasswordHashers.bcrypt());
        mem.create(AuthUser.builder().username("pbk").passwordHash(new Pbkdf2PasswordHasher(1000)
                .hash(PW.toCharArray())).build());
        mem.create(AuthUser.builder().username("md5").passwordHash(MD5_PREFIX + "$" + md5Hex(PW)).build());
        mem.create(AuthUser.builder().username("bc")
                .passwordHash("$2a$06$m0CrhHm10qJ3lXRY.5zDGO3rS2KdeeWLuGmsfGlMfOxih58VYVfxe").build());
        mem.create(AuthUser.builder().username("svc").serviceAccount(true).build());
        mem.create(AuthUser.builder().username("nohash").build());
        Auth auth = builder().passwordHasher(PasswordHashers.chain(pbkdf2, md5, bcrypt)).build();
        auth.passwords().prepare(); // the start-time dummy hash
        HashSlots slots = auth.passwords().slots();
        PasswordLogin noRehash = new PasswordLogin(users, new LoginLimits(auth.loginThrottle()), auth.deviceTokens(),
                auth.deviceTrust(), auth.passwords(), null);
        String[][] failing = {{"ghost", "x"}, {"pbk", "wrong"}, {"md5", "wrong"}, {"svc", "x"}, {"nohash", "x"}};
        int ip = 1;
        for (String[] f : failing) {
            int p = pbkdf2.computations.get();
            int m = md5.computations.get();
            long s = slots.requested();
            Outcome o = attempt(noRehash, f[0], f[1], "10.2.0." + ip++, null);
            assertEquals(f[0], Kind.INVALID_CREDENTIALS, o.kind);
            assertEquals(f[0] + ": one PBKDF2", p + 1, pbkdf2.computations.get());
            assertEquals(f[0] + ": one slot", s + 1, slots.requested());
            assertEquals(f[0] + ": legacy verify only for md5", m + (f[0].equals("md5") ? 1 : 0), md5.computations.get());
        }
        String[][] legacy = {{"md5", PW}, {"bc", "a"}};
        for (String[] l : legacy) {
            int p = pbkdf2.computations.get();
            int m = md5.computations.get() + bcrypt.computations.get();
            long s = slots.requested();
            Outcome o = attempt(noRehash, l[0], l[1], "10.2.1." + ip++, null);
            assertEquals(l[0], Kind.SUCCESS, o.kind);
            assertEquals(l[0] + ": one PBKDF2", p + 1, pbkdf2.computations.get());
            assertEquals(l[0] + ": one legacy verify", m + 1, md5.computations.get() + bcrypt.computations.get());
            assertEquals(l[0] + ": one slot", s + 1, slots.requested());
        }
        long before = slots.requested();
        assertEquals(Kind.SUCCESS, attempt(auth, "md5", PW, "10.2.2.1", null).kind);
        long deadline = System.currentTimeMillis() + 20_000;
        while (!mem.findByUsername("md5").getPasswordHash().startsWith(Passwords.PREFIX)
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue("the rehash wrote PBKDF2", mem.findByUsername("md5").getPasswordHash().startsWith(Passwords.PREFIX));
        assertEquals("the rehash asked for its own slot, after the attempt's", before + 2, slots.requested());
    }

    // SEC-TOKEN-009 AC-6: a PBKDF2 user whose stored count is below the target verifies with the stored count and runs
    // exactly one PBKDF2 computation
    @Test
    public void SEC_TOKEN_009_AC6_storedIterationCountIsUsed() throws Exception {
        Counting pbkdf2 = new Counting(new Pbkdf2PasswordHasher(2000));
        mem.create(AuthUser.builder().username("low").passwordHash(new Pbkdf2PasswordHasher(1000)
                .hash(PW.toCharArray())).build());
        Auth auth = builder().hashIterations(2000).passwordHasher(pbkdf2).build();
        auth.passwords().prepare();
        PasswordLogin noRehash = new PasswordLogin(users, new LoginLimits(auth.loginThrottle()), auth.deviceTokens(),
                auth.deviceTrust(), auth.passwords(), null);
        int p = pbkdf2.computations.get();
        assertEquals("verified at the stored 1000 rounds", Kind.SUCCESS, attempt(noRehash, "low", PW, "10.3.0.1", null).kind);
        assertEquals(p + 1, pbkdf2.computations.get());
        assertTrue(mem.findByUsername("low").getPasswordHash().contains("1000"));
    }

    /** A PasswordLogin over its own Passwords (measurer stub and slots) for the floor tests. */
    private PasswordLogin component(Passwords passwords) {
        return new PasswordLogin(users, new LoginLimits(InMemoryLoginThrottle.builder().timeSource(clock::get)
                .build()), new DeviceTokens(JwtKeys.of(AuthTestSupport.SECRET.getBytes(StandardCharsets.UTF_8)),
                clock::get), new InMemoryDeviceTrust(clock::get), passwords, null);
    }

    // PERF-TOKEN-001 AC-6 (the calibrate half): under HashCost.calibrate(250) the login timing uses T = 250 ms
    // SEC-TOKEN-009 AC-2: with HashCost.calibrate(250 ms) (T = 250 ms), a measurement stub, a fast hasher and an
    // injected scheduler, a failed login is scheduled at 312.5 ms plus a 0-50 ms jitter after the request started,
    // never earlier
    @Test
    public void SEC_TOKEN_009_AC2_failureFloorIs125TPlusJitter() throws Exception {
        Passwords passwords = new Passwords(new Pbkdf2PasswordHasher(1000), HashCost.calibrate(250), new HashSlots(2),
                iterations -> 1_000_000L);
        assertEquals(250_000_000L, passwords.timing().timingNanos());
        RecordingScheduler sched = new RecordingScheduler();
        final AtomicLong nanos = new AtomicLong(1_000_000_000L);
        final long[] jitter = {0L};
        FailureFloor floor = new FailureFloor(passwords.timing(), sched, nanos::get, () -> jitter[0]);
        PasswordLogin login = component(passwords);
        long[][] cases = {{10_000_000L, 0L}, {10_000_000L, 50_000_000L}, {20_000_000L, 37_000_000L},
            {400_000_000L, 5_000_000L}};
        for (long[] c : cases) {
            long start = nanos.get();
            Outcome o = attempt(login, "ghost", "x", "10.4.0.1", null);
            assertEquals(Kind.INVALID_CREDENTIALS, o.kind);
            nanos.addAndGet(c[0]);
            jitter[0] = c[1];
            sched.delaysNanos.clear();
            final CountDownLatch sent = new CountDownLatch(1);
            PasswordLogin.respond(o, floor, start, sent::countDown);
            assertTrue(sent.await(5, TimeUnit.SECONDS));
            long at = c[0] + sched.delaysNanos.get(0);
            assertEquals("answered at max(1.25T, elapsed) + jitter", Math.max(312_500_000L, c[0]) + c[1], at);
            assertTrue(at >= 312_500_000L);
        }
        FailureFloor real = new FailureFloor(passwords.timing(), sched, nanos::get,
                () -> java.util.concurrent.ThreadLocalRandom.current().nextLong(FailureFloor.MAX_JITTER_NANOS + 1));
        for (int i = 0; i < 200; i++) {
            sched.delaysNanos.clear();
            long start = nanos.get();
            real.send(start, () -> { });
            long d = sched.delaysNanos.get(0);
            assertTrue(d + " in 312.5..362.5 ms", d >= 312_500_000L && d <= 362_500_000L);
        }
        sched.shutdownNow();
        passwords.slots().shutdown();
    }

    // SEC-TOKEN-009 AC-3: a 503 from a full slot queue is delayed to the 1.25T floor too; a 429 throttle answer is sent
    // without the floor and runs no hash
    @Test
    public void SEC_TOKEN_009_AC3_fullQueueIsFlooredAndThrottleIsNot() throws Exception {
        HashSlots slots = new HashSlots(1, 0, 1000);
        Passwords passwords = new Passwords(new Pbkdf2PasswordHasher(1000), HashCost.calibrate(250), slots,
                iterations -> 1_000_000L);
        RecordingScheduler sched = new RecordingScheduler();
        AtomicLong nanos = new AtomicLong();
        FailureFloor floor = new FailureFloor(passwords.timing(), sched, nanos::get, () -> 0L);
        PasswordLogin login = component(passwords);
        final CountDownLatch hold = new CountDownLatch(1);
        final CountDownLatch busy = new CountDownLatch(1);
        slots.submit(() -> {
            busy.countDown();
            hold.await();
            return null;
        }, (v, e) -> { });
        assertTrue(busy.await(5, TimeUnit.SECONDS));
        Outcome full = attempt(login, "ghost", "x", "10.5.0.1", null);
        hold.countDown();
        assertEquals(Kind.UNAVAILABLE, full.kind);
        assertTrue(full.error instanceof AuthUnavailableException);
        PasswordLogin.respond(full, floor, 0L, () -> { });
        assertEquals(Collections.singletonList(312_500_000L), sched.delaysNanos);

        sched.delaysNanos.clear();
        for (int i = 0; i < 5; i++) {
            attempt(login, "bob", "x", "10.5.0.2", null);
        }
        long requested = slots.requested();
        Outcome limited = attempt(login, "bob", "x", "10.5.0.2", null);
        assertEquals(Kind.THROTTLED, limited.kind);
        assertEquals("no hash slot, no hash", requested, slots.requested());
        final boolean[] sentNow = {false};
        PasswordLogin.respond(limited, floor, 0L, () -> sentNow[0] = true);
        assertTrue("429 is sent at once", sentNow[0]);
        assertTrue(sched.delaysNanos.isEmpty());
        sched.shutdownNow();
        slots.shutdown();
    }

    // SEC-TOKEN-009 AC-4: a successful login is not delayed by the floor
    @Test
    public void SEC_TOKEN_009_AC4_successIsNotFloored() throws Exception {
        user("alice", PW);
        Passwords passwords = new Passwords(new Pbkdf2PasswordHasher(1000), HashCost.calibrate(250), new HashSlots(1),
                iterations -> 1_000_000L);
        RecordingScheduler sched = new RecordingScheduler();
        FailureFloor floor = new FailureFloor(passwords.timing(), sched, System::nanoTime, () -> 0L);
        Outcome ok = attempt(component(passwords), "alice", PW, "10.6.0.1", null);
        assertEquals(Kind.SUCCESS, ok.kind);
        final boolean[] sentNow = {false};
        PasswordLogin.respond(ok, floor, System.nanoTime(), () -> sentNow[0] = true);
        assertTrue(sentNow[0]);
        assertTrue(sched.delaysNanos.isEmpty());
        sched.shutdownNow();
        passwords.slots().shutdown();
    }

    // PERF-TOKEN-001 AC-6 (the fixed half): under HashCost.fixed(n) T is one hash at n measured once at start
    // SEC-TOKEN-009 AC-7: under HashCost.fixed(n), T is one start-time measurement of a hash at n (stub: 200 ms), so a
    // failure is scheduled at 250 ms plus 0-50 ms jitter, never earlier, and no request measures again
    @Test
    public void SEC_TOKEN_009_AC7_fixedCostTimesOneHashAtStart() throws Exception {
        final AtomicInteger measured = new AtomicInteger();
        Passwords passwords = new Passwords(new Pbkdf2PasswordHasher(1000), HashCost.fixed(60_000), new HashSlots(2),
                iterations -> {
                    measured.incrementAndGet();
                    assertEquals(60_000, iterations);
                    return 200_000_000L;
                });
        HashTiming t = passwords.timing();
        assertEquals(200_000_000L, t.timingNanos());
        RecordingScheduler sched = new RecordingScheduler();
        AtomicLong nanos = new AtomicLong();
        final long[] jitter = {0L};
        FailureFloor floor = new FailureFloor(passwords.timing(), sched, nanos::get, () -> jitter[0]);
        PasswordLogin login = component(passwords);
        for (long j : new long[] {0L, 25_000_000L, 50_000_000L}) {
            jitter[0] = j;
            sched.delaysNanos.clear();
            nanos.set(0);
            Outcome o = attempt(login, "ghost", "x", "10.7.0.1", null);
            nanos.set(5_000_000L);
            PasswordLogin.respond(o, floor, 0L, () -> { });
            assertEquals(250_000_000L + j, 5_000_000L + sched.delaysNanos.get(0));
        }
        assertEquals("measured once, at start", 1, measured.get());
        sched.shutdownNow();
        passwords.slots().shutdown();
    }
}
