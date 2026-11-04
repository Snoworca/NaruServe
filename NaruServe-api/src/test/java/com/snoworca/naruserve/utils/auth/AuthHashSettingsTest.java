package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.testing.TestApp;
import com.snoworca.naruserve.utils.auth.internal.HashSlots;
import com.snoworca.naruserve.utils.auth.password.BcryptPasswordHasher;
import com.snoworca.naruserve.utils.auth.password.HashCost;
import com.snoworca.naruserve.utils.auth.password.PasswordHasher;
import com.snoworca.naruserve.utils.auth.password.Pbkdf2PasswordHasher;
import com.snoworca.naruserve.utils.auth.user.AuthUser;
import com.snoworca.naruserve.utils.auth.user.InMemoryUserStore;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/** The builder's hash settings: HashCost (PERF-TOKEN-001), hash concurrency (REL-TOKEN-001), the hasher (SEC-TOKEN-008). */
public class AuthHashSettingsTest {

    // PERF-TOKEN-001 AC-5: the doctor reports this device's hash cost (iterations and time per hash) at startup
    @Test
    public void PERF_TOKEN_001_AC5_doctorReportsTheMeasuredCost() {
        for (HashCost cost : new HashCost[] {HashCost.fixed(50_000), HashCost.calibrate(250)}) {
            RecordingLogger log = new RecordingLogger();
            Auth auth = Auth.builder(AuthTestSupport.SECRET).logger(log).hashCost(cost).build();
            TestApp t = TestApp.create();
            auth.install(t.app());
            t.start();
            String line = null;
            for (String l : log.info()) {
                if (l.startsWith("auth doctor: hash cost ")) {
                    line = l;
                }
            }
            assertTrue(log.lines.toString(), line != null
                    && line.matches("auth doctor: hash cost \\d+ PBKDF2 iterations, \\d+\\.\\d ms per hash .*"));
            int iterations = Integer.parseInt(line.substring(23, line.indexOf(' ', 23)));
            assertEquals(auth.passwords().timing().iterations(), iterations);
            if (cost.isFixed()) {
                assertEquals(50_000, iterations);
            } else {
                assertTrue(iterations >= HashCost.MIN_ITERATIONS && iterations <= HashCost.MAX_ITERATIONS);
            }
        }
    }

    // a fixed cost below the 50,000 floor is a doctor FAIL in PROD and a WARN in DEV
    @Test
    public void lowFixedCostIsAFindingByMode() {
        Auth prod = Auth.builder(AuthTestSupport.SECRET).logger(new RecordingLogger()).hashCost(HashCost.fixed(1_000))
                .build();
        TestApp t = TestApp.create();
        prod.install(t.app());
        try {
            t.start();
            fail("started with a fixed cost of 1,000");
        } catch (RuntimeException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("hash cost"));
        }
        RecordingLogger log = new RecordingLogger();
        Auth dev = Auth.builder(AuthTestSupport.SECRET).mode(AuthMode.DEV).logger(log)
                .hashCost(HashCost.fixed(1_000)).build();
        TestApp d = TestApp.create();
        dev.install(d.app());
        d.start();
        assertEquals(1, com.snoworca.naruserve.testing.RecordingLogger.countMatching(log.lines,
                "WARN auth doctor WARN hash cost: .*"));
    }

    // PERF-TOKEN-001 AC-7: calibrate(250) and calibrate(500) give T = 250 and 500 ms; targets outside 250..500 are
    // refused by build(); with no HashCost T lies in 250..500 ms
    @Test
    public void PERF_TOKEN_001_AC7_targetRange() {
        for (long target : new long[] {250, 500}) {
            Auth auth = Auth.builder(AuthTestSupport.SECRET).hashCost(HashCost.calibrate(target)).build();
            assertEquals(target * 1_000_000L, auth.passwords().timing().timingNanos());
        }
        for (long target : new long[] {10, 249, 501, 5_000}) {
            try {
                Auth.builder(AuthTestSupport.SECRET).hashCost(HashCost.calibrate(target)).build();
                fail("accepted calibrate(" + target + ")");
            } catch (AuthConfigException e) {
                assertTrue(e.getMessage(), e.getMessage().contains("250..500 ms"));
            }
        }
        long t = Auth.builder(AuthTestSupport.SECRET).build().passwords().timing().timingNanos() / 1_000_000L;
        assertTrue(String.valueOf(t), t >= 250 && t <= 500);
    }

    // REL-TOKEN-001 AC-1: max(1, cores / 2) by default (8 cores 4, 1 core 1); hashConcurrency(2) sets 2
    @Test
    public void REL_TOKEN_001_AC1_slotCount() {
        assertEquals(4, HashSlots.defaultSlots(8));
        assertEquals(1, HashSlots.defaultSlots(1));
        Auth plain = AuthTestSupport.builder().build();
        assertEquals(HashSlots.defaultSlots(Runtime.getRuntime().availableProcessors()), plain.hashSlots().slots());
        assertEquals(2, AuthTestSupport.builder().hashConcurrency(2).build().hashSlots().slots());
        try {
            AuthTestSupport.builder().hashConcurrency(0).build();
            fail("accepted zero slots");
        } catch (AuthConfigException expected) {
            // at least one slot
        }
    }

    /** A real PBKDF2 hasher whose hash blocks until released, to hold the one slot. */
    static final class BlockingHasher implements PasswordHasher {
        final Pbkdf2PasswordHasher real = new Pbkdf2PasswordHasher(1000);
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean block = new AtomicBoolean();

        @Override
        public boolean accepts(String stored) {
            return real.accepts(stored);
        }

        @Override
        public String hash(char[] password) {
            if (block.getAndSet(false)) {
                entered.countDown();
                try {
                    release.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return real.hash(password);
        }

        @Override
        public boolean verify(char[] password, String stored) {
            return real.verify(password, stored);
        }
    }

    // REL-TOKEN-001 AC-4: AuthUsers.create waiting more than 5 seconds for a slot throws AuthUnavailableException,
    // which auth.errors() renders as 503 JSON
    @Test(timeout = 30000)
    public void REL_TOKEN_001_AC4_createWaitingTooLongIs503() throws Exception {
        final BlockingHasher hasher = new BlockingHasher();
        final Auth auth = AuthTestSupport.builder().passwordHasher(hasher).hashConcurrency(1).permit(
                com.snoworca.naruserve.Method.POST, "/make").build();
        TestApp t = TestApp.create();
        auth.install(t.app());
        t.app().post("/make", (req, res) -> res.send(auth.users().create("late", "pw-pw-pw-pw".toCharArray()).getId()));
        t.app().onError(auth.errors());
        t.start();
        hasher.block.set(true);
        final AtomicReference<Throwable> holderError = new AtomicReference<>();
        Thread holder = new Thread(() -> {
            try {
                auth.users().create("holder", "pw-pw-pw-pw".toCharArray());
            } catch (Throwable e) {
                holderError.set(e);
            }
        });
        holder.start();
        assertTrue(hasher.entered.await(10, TimeUnit.SECONDS));
        long start = System.nanoTime();
        ClientResponse r = t.send("POST", "/make");
        long waited = (System.nanoTime() - start) / 1_000_000L;
        assertEquals(r.text(), 503, r.status());
        assertEquals("SERVICE_UNAVAILABLE", AuthTestSupport.code(r));
        assertTrue(r.header("Content-Type").startsWith("application/json"));
        assertEquals("1", r.header("Retry-After"));
        assertTrue("waited " + waited + " ms", waited >= 4_900);
        hasher.release.countDown();
        holder.join(10_000);
        assertEquals(null, holderError.get());
    }

    /** Counts how many verifications run at once; each waits a little for a partner to show overlap when allowed. */
    static final class Overlap {
        final AtomicInteger inside = new AtomicInteger();
        final AtomicInteger max = new AtomicInteger();

        final long waitNanos;

        Overlap(long waitMillis) {
            this.waitNanos = waitMillis * 1_000_000L;
        }

        boolean run(PasswordHasher h, char[] pw, String stored) {
            int now = inside.incrementAndGet();
            max.accumulateAndGet(now, Math::max);
            long until = System.nanoTime() + waitNanos;
            while (inside.get() < 2 && System.nanoTime() < until) {
                Thread.yield();
            }
            max.accumulateAndGet(inside.get(), Math::max);
            try {
                return h.verify(pw, stored);
            } finally {
                inside.decrementAndGet();
            }
        }
    }

    /** An app-side legacy chain: bcrypt for $2 strings, PBKDF2 otherwise, every verify watched by {@link Overlap}. */
    static PasswordHasher chain(final Overlap o) {
        final BcryptPasswordHasher bcrypt = BcryptPasswordHasher.builder().build();
        final Pbkdf2PasswordHasher pbkdf2 = new Pbkdf2PasswordHasher(20_000);
        return new PasswordHasher() {
            @Override
            public boolean accepts(String stored) {
                return bcrypt.accepts(stored) || pbkdf2.accepts(stored);
            }

            @Override
            public String hash(char[] password) {
                return pbkdf2.hash(password);
            }

            @Override
            public boolean verify(char[] password, String stored) {
                return o.run(bcrypt.accepts(stored) ? bcrypt : pbkdf2, password, stored);
            }
        };
    }

    private static int maxConcurrent(int slots, long waitMillis) throws Exception {
        Overlap o = new Overlap(waitMillis);
        InMemoryUserStore store = new InMemoryUserStore();
        String bcrypt = BcryptPasswordHasher.builder().cost(6).build().hash("legacy".toCharArray());
        String pbkdf2 = new Pbkdf2PasswordHasher(20_000).hash("modern".toCharArray());
        final String a = store.create(AuthUser.builder().username("a").passwordHash(bcrypt).build()).getId();
        final String b = store.create(AuthUser.builder().username("b").passwordHash(pbkdf2).build()).getId();
        final Auth auth = AuthTestSupport.builder().users(store).passwordHasher(chain(o)).hashConcurrency(slots)
                .build();
        final CountDownLatch go = new CountDownLatch(1);
        final AtomicBoolean okA = new AtomicBoolean();
        final AtomicBoolean okB = new AtomicBoolean();
        Thread ta = new Thread(() -> {
            try {
                go.await();
                okA.set(auth.users().checkPassword(a, "legacy".toCharArray()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        Thread tb = new Thread(() -> {
            try {
                go.await();
                okB.set(auth.users().checkPassword(b, "modern".toCharArray()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        ta.start();
        tb.start();
        go.countDown();
        ta.join(10_000);
        tb.join(10_000);
        assertTrue(okA.get());
        assertTrue(okB.get());
        return o.max.get();
    }

    // SEC-TOKEN-008 AC-6: with hash concurrency 1 a bcrypt verify and a PBKDF2 verify started together never overlap
    @Test(timeout = 30000)
    public void SEC_TOKEN_008_AC6_bcryptAndPbkdf2ShareTheSlots() throws Exception {
        assertEquals(1, maxConcurrent(1, 300));
        assertEquals(2, maxConcurrent(2, 10_000)); // two slots: each waits (up to 10 s) until its partner is inside
    }
}
