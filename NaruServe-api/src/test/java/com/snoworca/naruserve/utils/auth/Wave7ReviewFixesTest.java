package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.CookieOptions;
import com.snoworca.naruserve.SameSite;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.testing.TestApp;
import com.snoworca.naruserve.utils.auth.PasswordLogin.Kind;
import com.snoworca.naruserve.utils.auth.PasswordLogin.Outcome;
import com.snoworca.naruserve.utils.auth.internal.AuthCookies;
import com.snoworca.naruserve.utils.auth.internal.LoginLimits;
import com.snoworca.naruserve.utils.auth.internal.Passwords;
import com.snoworca.naruserve.utils.auth.internal.ThrottleKeys;
import com.snoworca.naruserve.utils.auth.password.HashCost;
import com.snoworca.naruserve.utils.auth.password.PasswordHasher;
import com.snoworca.naruserve.utils.auth.password.Pbkdf2PasswordHasher;
import com.snoworca.naruserve.utils.auth.spi.DeviceToken;
import com.snoworca.naruserve.utils.auth.spi.DeviceTrust;
import com.snoworca.naruserve.utils.auth.spi.LoginThrottle;
import com.snoworca.naruserve.utils.auth.user.AuthUser;
import com.snoworca.naruserve.utils.auth.user.InMemoryUserStore;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Regression tests for the wave 7 review findings (constant work, hash-thread offload, keys, cleanup, cookies). */
public class Wave7ReviewFixesTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final String PW = "correct horse";
    private final AtomicLong clock = new AtomicLong(1_761_700_900_000L);
    private TestApp app;

    @After
    public void stop() {
        if (app != null) {
            app.app().shutdown(5, TimeUnit.SECONDS);
        }
    }

    /** A non-PBKDF2 primary: PBKDF2 under another prefix, counting its computations. */
    static final class OtherPrimary implements PasswordHasher {
        final Pbkdf2PasswordHasher inner = new Pbkdf2PasswordHasher(1000);
        final AtomicInteger computations = new AtomicInteger();

        @Override
        public boolean accepts(String stored) {
            return stored.startsWith("other$");
        }

        @Override
        public String hash(char[] password) {
            computations.incrementAndGet();
            return "other$" + inner.hash(password);
        }

        @Override
        public boolean verify(char[] password, String stored) {
            computations.incrementAndGet();
            return accepts(stored) && inner.verify(password, stored.substring(6));
        }
    }

    private static Outcome attempt(PasswordLogin login, String name, char[] password, String ip, String device)
            throws Exception {
        final AtomicReference<Outcome> out = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);
        login.attempt(name, password, ip, device, o -> {
            out.set(o);
            done.countDown();
        });
        assertTrue(done.await(30, TimeUnit.SECONDS));
        return out.get();
    }

    // review H1: a custom non-PBKDF2 primary hasher still runs exactly one computation for a known user with a wrong
    // password and for an unknown user, and a primary-format hash is not queued for a rehash after a success
    @Test
    public void customPrimaryKeepsConstantWorkAndNoRehash() throws Exception {
        OtherPrimary primary = new OtherPrimary();
        Passwords passwords = new Passwords(primary, HashCost.fixed(1000), null, iterations -> 1_000_000L);
        passwords.prepare();
        String stored = primary.hash(PW.toCharArray());
        int c = primary.computations.get();
        assertFalse(passwords.verifyForLogin("wrong".toCharArray(), stored).matched());
        assertEquals("known user, wrong password", c + 1, primary.computations.get());
        passwords.verifyForLogin("x".toCharArray(), null);
        assertEquals("unknown user", c + 2, primary.computations.get());
        Passwords.LoginCheck ok = passwords.verifyForLogin(PW.toCharArray(), stored);
        assertTrue(ok.matched());
        assertFalse("a primary-format hash needs no rehash", ok.needsRehash());
        assertEquals(c + 3, primary.computations.get());
    }

    /** The real PBKDF2 hasher, recording each hash it makes and each stored string it is asked to verify against. */
    static final class RecordingPbkdf2 implements PasswordHasher {
        final Pbkdf2PasswordHasher inner = new Pbkdf2PasswordHasher(1000);
        final List<String> made = Collections.synchronizedList(new ArrayList<String>());
        final List<String> verified = Collections.synchronizedList(new ArrayList<String>());

        @Override
        public boolean accepts(String stored) {
            return inner.accepts(stored);
        }

        @Override
        public String hash(char[] password) {
            String h = inner.hash(password);
            made.add(h);
            return h;
        }

        @Override
        public boolean verify(char[] password, String stored) {
            verified.add(stored);
            return inner.verify(password, stored);
        }

        @Override
        public boolean needsRehash(String stored) {
            return inner.needsRehash(stored);
        }
    }

    // SEC-TOKEN-009 (M4-F09): a stored hash in the PBKDF2 format that does not parse (malformed, or a cost above
    // MAX_STORED_ITERATIONS) costs the same one target-cost PBKDF2 as an unknown user: it is verified against the dummy
    // made at start, never against the malformed string (which derives nothing), and it never matches
    @Test
    public void malformedAcceptedHashCostsOneDummyDerivation() {
        RecordingPbkdf2 hasher = new RecordingPbkdf2();
        Passwords passwords = new Passwords(hasher, HashCost.fixed(1000), null, iterations -> 1_000_000L);
        passwords.prepare();
        assertEquals(1, hasher.made.size());
        String dummy = hasher.made.get(0);
        String salt = "AAAAAAAAAAAAAAAAAAAAAA";
        String key = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        for (String malformed : new String[] {"pbkdf2-sha256$x$y$z", "pbkdf2-sha256$", "pbkdf2-sha256$1000$" + salt,
                "pbkdf2-sha256$3000000$" + salt + "$" + key, "pbkdf2-sha256$1000$" + salt + "$short"}) {
            hasher.verified.clear();
            Passwords.LoginCheck check = passwords.verifyForLogin("wrong".toCharArray(), malformed);
            assertFalse(malformed, check.matched());
            assertFalse(malformed, check.needsRehash());
            assertEquals(malformed + " is checked once, against the dummy", Collections.singletonList(dummy),
                    hasher.verified);
            hasher.verified.clear();
            assertFalse(malformed, passwords.verify("wrong".toCharArray(), malformed));
            assertEquals(malformed + " (verify) is checked once, against the dummy", Collections.singletonList(dummy),
                    hasher.verified);
        }
        // a well-formed hash is still verified against itself, once
        String real = new Pbkdf2PasswordHasher(1000).hash(PW.toCharArray());
        hasher.verified.clear();
        assertTrue(passwords.verifyForLogin(PW.toCharArray(), real).matched());
        assertEquals(Collections.singletonList(real), hasher.verified);
    }

    // SEC-TOKEN-009 (M4-F09) on the default hasher: the CPU work of a login against a malformed PBKDF2-format hash is
    // of the order of a real verification (thread CPU time, so a loaded build machine does not change the ratio)
    @Test(timeout = 120000)
    public void malformedAcceptedHashTakesRealWorkOnTheDefaultHasher() {
        java.lang.management.ThreadMXBean mx = java.lang.management.ManagementFactory.getThreadMXBean();
        org.junit.Assume.assumeTrue(mx.isCurrentThreadCpuTimeSupported());
        if (!mx.isThreadCpuTimeEnabled()) {
            mx.setThreadCpuTimeEnabled(true);
        }
        Passwords passwords = new Passwords(100_000);
        passwords.prepare();
        String real = passwords.hash(PW.toCharArray());
        long realCpu = Long.MAX_VALUE;
        long malformedCpu = Long.MAX_VALUE;
        for (int i = 0; i < 3; i++) {
            long t0 = mx.getCurrentThreadCpuTime();
            assertFalse(passwords.verifyForLogin("wrong".toCharArray(), real).matched());
            long t1 = mx.getCurrentThreadCpuTime();
            assertFalse(passwords.verifyForLogin("wrong".toCharArray(), "pbkdf2-sha256$x$y$z").matched());
            long t2 = mx.getCurrentThreadCpuTime();
            realCpu = Math.min(realCpu, t1 - t0);
            malformedCpu = Math.min(malformedCpu, t2 - t1);
        }
        assertTrue("malformed " + malformedCpu / 1000 + " us vs real " + realCpu / 1000 + " us",
                malformedCpu * 4 >= realCpu);
    }

    // review M1: the counting and success work after the hash, and the outcome callback, run off the hash threads
    @Test
    public void outcomeIsDeliveredOffTheHashThread() throws Exception {
        InMemoryUserStore users = new InMemoryUserStore();
        users.create(AuthUser.builder().username("alice").passwordHash(new Pbkdf2PasswordHasher(1000)
                .hash(PW.toCharArray())).build());
        Auth auth = Auth.builder(AuthTestSupport.SECRET).hashIterations(1000).logger(new RecordingLogger())
                .users(users).timeSource(clock::get).build();
        for (String pw : new String[] {PW, "wrong"}) {
            final AtomicReference<String> thread = new AtomicReference<>();
            final CountDownLatch done = new CountDownLatch(1);
            auth.passwordLogin().attempt("alice", pw.toCharArray(), "10.0.0.1", null, o -> {
                thread.set(Thread.currentThread().getName());
                done.countDown();
            });
            assertTrue(done.await(30, TimeUnit.SECONDS));
            assertFalse(thread.get(), thread.get().startsWith("naru-auth-hash-"));
        }
    }

    /** An in-memory throttle refusing keys longer than 255 characters, like a VARCHAR(255) column. */
    static final class ColumnLimitedThrottle extends com.snoworca.naruserve.utils.auth.spi.ForwardingLoginThrottle {
        ColumnLimitedThrottle(LoginThrottle inner) {
            super(inner);
        }

        @Override
        public long recordFailure(String key, int max, long windowMillis) throws Exception {
            if (key.length() > 255) {
                throw new java.sql.SQLException("value too long for type character varying(255)", "22001");
            }
            return super.recordFailure(key, max, windowMillis);
        }
    }

    // review M2: an overlong login name is counted under a fixed-length key, so the IP key still stops the 21st
    // attempt from one IP; and a failing key write never skips the other keys
    @Test
    public void overlongNamesStillCountAgainstTheIpKey() throws Exception {
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            longName.append('a');
        }
        Auth auth = Auth.builder(AuthTestSupport.SECRET).hashIterations(1000).logger(new RecordingLogger())
                .timeSource(clock::get).loginThrottle(new ColumnLimitedThrottle(InMemoryLoginThrottle.builder()
                        .timeSource(clock::get).build())).build();
        for (int i = 0; i < 20; i++) {
            Outcome o = attempt(auth.passwordLogin(), longName.toString() + i, "x".toCharArray(), "203.0.113.5", null);
            assertEquals(String.valueOf(o.error), Kind.INVALID_CREDENTIALS, o.kind);
        }
        assertEquals(Kind.THROTTLED, attempt(auth.passwordLogin(), longName.toString(), "x".toCharArray(),
                "203.0.113.5", null).kind);

        final List<String> written = new ArrayList<>();
        LoginThrottle failingUserIp = new com.snoworca.naruserve.utils.auth.spi.ForwardingLoginThrottle(
                InMemoryLoginThrottle.builder().build()) {
            @Override
            public long recordFailure(String key, int max, long windowMillis) throws Exception {
                if (key.startsWith("login:ui:")) {
                    throw new java.sql.SQLException("down");
                }
                written.add(key);
                return super.recordFailure(key, max, windowMillis);
            }
        };
        try {
            new LoginLimits(failingUserIp).recordFailure("bob", "10.1.1.1", false);
            fail("the failed write was swallowed");
        } catch (java.sql.SQLException expected) {
            // reported after the other keys were written
        }
        assertTrue(written.toString(), written.contains(ThrottleKeys.loginIp("10.1.1.1"))
                && written.contains(ThrottleKeys.loginUser("bob")));
    }

    // review L5: a store failure in steps 1 to 3 still wipes the password array
    @Test
    public void passwordIsWipedWhenAnEarlyStepThrows() throws Exception {
        Auth auth = Auth.builder(AuthTestSupport.SECRET).hashIterations(1000).logger(new RecordingLogger())
                .loginThrottle(new com.snoworca.naruserve.utils.auth.spi.ForwardingLoginThrottle(
                        InMemoryLoginThrottle.builder().build()) {
                    @Override
                    public long retryAfter(String key, int max, long windowMillis) throws Exception {
                        throw new java.sql.SQLException("down");
                    }
                }).build();
        char[] pw = PW.toCharArray();
        try {
            auth.passwordLogin().attempt("alice", pw, "10.0.0.1", null, o -> { });
            fail("the throttle failure was swallowed");
        } catch (java.sql.SQLException expected) {
            // 503 for the endpoint
        }
        for (char ch : pw) {
            assertEquals('\0', ch);
        }
    }

    // review round 2: an after-hash completion dropped by a stopping pool is abandoned: the password is wiped and the
    // caller gets an UNAVAILABLE outcome instead of no answer
    @Test
    public void abandonedCompletionAnswersUnavailableAndWipes() throws Exception {
        java.util.concurrent.ThreadPoolExecutor pool = new java.util.concurrent.ThreadPoolExecutor(1, 1, 1,
                TimeUnit.SECONDS, new java.util.concurrent.LinkedBlockingQueue<Runnable>());
        final CountDownLatch block = new CountDownLatch(1);
        pool.execute(() -> {
            try {
                block.await();
            } catch (InterruptedException stop) {
                Thread.currentThread().interrupt();
            }
        });
        Auth auth = Auth.builder(AuthTestSupport.SECRET).hashIterations(1000).logger(new RecordingLogger()).build();
        PasswordLogin login = new PasswordLogin(auth.userStore(), new LoginLimits(auth.loginThrottle()),
                auth.deviceTokens(), auth.deviceTrust(), auth.passwords(), null, pool);
        final AtomicReference<Outcome> out = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);
        char[] pw = PW.toCharArray();
        login.attempt("ghost", pw, "10.0.0.1", null, o -> {
            out.set(o);
            done.countDown();
        });
        long deadline = System.currentTimeMillis() + 20_000;
        while (pool.getQueue().isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        int abandoned = 0;
        for (Runnable r : pool.shutdownNow()) {
            if (r instanceof PasswordLogin.Completion) {
                ((PasswordLogin.Completion) r).abandon();
                abandoned++;
            }
        }
        assertEquals(1, abandoned);
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(Kind.UNAVAILABLE, out.get().kind);
        for (char ch : pw) {
            assertEquals('\0', ch);
        }
    }

    // review round 4: an Error escaping the after-hash work still answers exactly once (UNAVAILABLE), wipes the
    // password and propagates
    @Test
    public void completionAnswersOnceWhenWorkThrowsAnError() {
        final List<Outcome> answers = new ArrayList<>();
        char[] pw = PW.toCharArray();
        PasswordLogin.Completion c = new PasswordLogin.Completion(pw, false, answers::add) {
            @Override
            Outcome work() {
                throw new AssertionError("boom");
            }
        };
        try {
            c.run();
            fail("the Error was swallowed");
        } catch (AssertionError expected) {
            assertEquals("boom", expected.getMessage());
        }
        assertEquals(1, answers.size());
        assertEquals(Kind.UNAVAILABLE, answers.get(0).kind);
        for (char ch : pw) {
            assertEquals('\0', ch);
        }
        c.abandon();
        assertEquals("abandon after run answers nothing more", 1, answers.size());
    }

    // review round 4: at stop, a failing answer of one dropped task does not stop the others from being abandoned
    @Test
    public void stopAbandonsEveryDroppedTaskEvenWhenOneAnswerThrows() {
        final List<Outcome> answers = new ArrayList<>();
        PasswordLogin.Completion first = new PasswordLogin.Completion("a".toCharArray(), false, o -> {
            throw new IllegalStateException("the response is gone");
        }) {
            @Override
            Outcome work() {
                throw new AssertionError("not run");
            }
        };
        PasswordLogin.Completion second = new PasswordLogin.Completion("b".toCharArray(), false, answers::add) {
            @Override
            Outcome work() {
                throw new AssertionError("not run");
            }
        };
        RecordingLogger log = new RecordingLogger();
        java.util.List<Runnable> dropped = new ArrayList<>();
        dropped.add(first);
        dropped.add(second);
        Auth.abandonAll(dropped, log);
        assertEquals(1, answers.size());
        assertEquals(Kind.UNAVAILABLE, answers.get(0).kind);
        assertEquals(1, RecordingLogger.countMatching(log.lines, "ERROR .*abandoning a queued task.*"));
    }

    /** A DeviceTrust counting purgeExpired calls. */
    static final class PurgeCounting implements DeviceTrust {
        final AtomicInteger purges = new AtomicInteger();

        @Override
        public boolean isInvalidated(DeviceToken token) {
            return false;
        }

        @Override
        public boolean recordFailure(DeviceToken token, int max, long windowMillis) {
            return false;
        }

        @Override
        public int purgeExpired(long nowMillis) {
            purges.incrementAndGet();
            return 0;
        }
    }

    // review M3: the 10-minute cleanup also purges the device-trust state and the default throttle's old keys
    @Test
    public void cleanupPurgesDeviceTrustAndThrottleRows() throws Exception {
        PurgeCounting trust = new PurgeCounting();
        Auth auth = Auth.builder(AuthTestSupport.SECRET).hashIterations(1000).logger(new RecordingLogger())
                .timeSource(clock::get).deviceTrust(trust).build();
        InMemoryLoginThrottle throttle = (InMemoryLoginThrottle) auth.loginThrottle();
        throttle.recordFailure("login:ip:old", 5, 900_000L);
        clock.addAndGet(Auth.DEFAULT_THROTTLE_RETENTION_MILLIS + 1);
        throttle.recordFailure("login:ip:new", 5, 900_000L);
        auth.cleanupRun();
        assertEquals(1, trust.purges.get());
        assertEquals(Collections.singletonList("login:ip:new"), new ArrayList<>(throttle.snapshot().keySet()));
        InMemoryDeviceTrust mem = new InMemoryDeviceTrust(clock::get);
        mem.recordFailure(new DeviceToken("t1", "u1", 0, clock.get() + 10), 5, 900_000L);
        clock.addAndGet(20);
        assertEquals(1, mem.purgeExpired(clock.get()));
    }

    // review L8: the shared cookie configurer cannot loosen naru_device's SameSite=Strict or move its Path
    @Test
    public void deviceCookieKeepsStrictAndItsPath() {
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        app = TestApp.create(adapter);
        final AuthCookies cookies = new AuthCookies("/auth", (CookieOptions o) -> o.sameSite(SameSite.NONE).path("/"));
        app.app().get("/set", (req, res) -> {
            cookies.setDevice(req, res, "d");
            res.send("ok");
        });
        app.start();
        ClientResponse r = adapter.send(ClientRequest.of("GET", "/set"));
        String device = null;
        for (java.util.Map.Entry<String, String> h : r.headers()) {
            if (h.getKey().equalsIgnoreCase("Set-Cookie") && h.getValue().startsWith("naru_device=")) {
                device = h.getValue();
            }
        }
        assertNotNull(device);
        assertTrue(device, device.contains("SameSite=Strict") && device.contains("Path=/auth/login"));
    }
}
