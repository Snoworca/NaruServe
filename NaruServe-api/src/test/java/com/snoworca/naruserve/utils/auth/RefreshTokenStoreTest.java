package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.testing.TestApp;
import com.snoworca.naruserve.utils.auth.apikey.ApiKeyStore;
import com.snoworca.naruserve.utils.auth.apikey.StoredApiKey;
import com.snoworca.naruserve.utils.auth.internal.MiniJson;
import com.snoworca.naruserve.utils.auth.spi.RefreshRecord;
import com.snoworca.naruserve.utils.auth.spi.RefreshTokenStore;
import com.snoworca.naruserve.utils.auth.user.AuthUser;
import com.snoworca.naruserve.utils.auth.user.FileUserStore;
import com.snoworca.naruserve.utils.auth.user.ForwardingUserStore;
import com.snoworca.naruserve.utils.auth.user.InMemoryUserStore;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** The RefreshTokenStore SPI, its default selection and the scheduled cleanup (FR-TOKEN-002). */
public class RefreshTokenStoreTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    static RefreshRecord record(String tokenId, String familyId, long expiresAt) {
        return RefreshRecord.builder().tokenId(tokenId).familyId(familyId).userId("u1")
                .tokenHash("5e884898da28047151d0e56f8dc6292773603d0d6aabbdd62a11ef721d1542d8").createdAt(1_000L)
                .expiresAt(expiresAt).build();
    }

    private static int race(final RefreshTokenStore s, final String jti) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            final CountDownLatch go = new CountDownLatch(1);
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                results.add(pool.submit((Callable<Boolean>) () -> {
                    go.await();
                    return s.markUsed(jti, 5_000L);
                }));
            }
            go.countDown();
            int wins = 0;
            for (Future<Boolean> f : results) {
                wins += f.get(30, TimeUnit.SECONDS) ? 1 : 0;
            }
            return wins;
        } finally {
            pool.shutdownNow();
        }
    }

    // FR-TOKEN-002 AC-1: concurrent markUsed of one unused record returns true exactly once, in memory and on file
    @Test(timeout = 60000)
    public void FR_TOKEN_002_AC1_markUsedIsAtomic() throws Exception {
        RefreshTokenStore[] stores = {new InMemoryRefreshTokenStore(),
            new FileRefreshTokenStore(new File(tmp.getRoot(), "refresh-tokens.json"))};
        for (RefreshTokenStore s : stores) {
            for (int round = 0; round < 20; round++) {
                s.save(record("t" + round, "f", 9_000_000L));
                assertEquals(s + " round " + round, 1, race(s, "t" + round));
            }
            assertTrue(s.find("t0").getUsedAt() != null);
        }
    }

    private static final class CustomUserStore extends ForwardingUserStore {
        CustomUserStore() {
            super(new InMemoryUserStore());
        }
    }

    // FR-TOKEN-002 AC-3: SINGLE_INSTANCE, a custom user store and no refresh store: the in-memory store is used and the
    // doctor reports one restart-logout WARN line, which the throttle and device-trust fallbacks share
    @Test
    public void FR_TOKEN_002_AC3_customStoreFallsBackWithOneWarn() throws Exception {
        RecordingLogger log = new RecordingLogger();
        Auth auth = Auth.builder(AuthTestSupport.SECRET).hashIterations(1000).logger(log)
                .users(new CustomUserStore()).build();
        assertTrue(auth.refreshTokens() instanceof InMemoryRefreshTokenStore);
        TestApp t = TestApp.create();
        auth.install(t.app());
        t.start();
        assertEquals(log.lines.toString(), 1, RecordingLogger.countMatching(log.lines,
                "WARN auth doctor WARN restart logout: .*refresh token.*login throttle.*device trust.*restart logs "
                        + "every user out.*"));
        assertEquals(1, RecordingLogger.countMatching(log.lines, ".*restart logout.*"));
    }

    /** Counts the scheduled calls of a store. */
    private static final class CountingStore implements RefreshTokenStore {
        final AtomicInteger purges = new AtomicInteger();
        private final InMemoryRefreshTokenStore inner = new InMemoryRefreshTokenStore();

        @Override
        public void save(RefreshRecord record) {
            inner.save(record);
        }

        @Override
        public RefreshRecord find(String tokenId) {
            return inner.find(tokenId);
        }

        @Override
        public boolean markUsed(String tokenId, long nowMillis) {
            return inner.markUsed(tokenId, nowMillis);
        }

        @Override
        public int revokeFamily(String familyId) {
            return inner.revokeFamily(familyId);
        }

        @Override
        public int purgeExpired(long nowMillis) {
            purges.incrementAndGet();
            return inner.purgeExpired(nowMillis);
        }
    }

    /** An API key store counting deleteExpired calls; the other methods are unused here. */
    private static final class ExpiringKeys implements ApiKeyStore {
        final AtomicInteger deletes = new AtomicInteger();
        final AtomicLong lastNow = new AtomicLong();

        @Override
        public StoredApiKey findByPrefix(String prefix) {
            return null;
        }

        @Override
        public StoredApiKey findById(String keyId) {
            return null;
        }

        @Override
        public List<StoredApiKey> listByOwner(String ownerUserId) {
            return Collections.emptyList();
        }

        @Override
        public boolean insertIfUnderLimit(StoredApiKey key, int maxLiveKeys, long nowMillis) {
            return false;
        }

        @Override
        public boolean deleteByIdAndOwner(String keyId, String ownerUserId) {
            return false;
        }

        @Override
        public boolean deleteById(String keyId) {
            return false;
        }

        @Override
        public int deleteAllByOwner(String ownerUserId) {
            return 0;
        }

        @Override
        public int deleteStaleByOwner(String ownerUserId, long currentOwnerVer) {
            return 0;
        }

        @Override
        public int deleteExpired(long nowMillis) {
            lastNow.set(nowMillis);
            deletes.incrementAndGet();
            return 0;
        }
    }

    /** A store that keeps the default purgeExpired. */
    private static final class NoPurgeStore implements RefreshTokenStore {
        private final Map<String, RefreshRecord> rows = new ConcurrentHashMap<>();

        @Override
        public void save(RefreshRecord record) {
            rows.put(record.getTokenId(), record);
        }

        @Override
        public RefreshRecord find(String tokenId) {
            return rows.get(tokenId);
        }

        @Override
        public boolean markUsed(String tokenId, long nowMillis) {
            return false;
        }

        @Override
        public int revokeFamily(String familyId) {
            return 0;
        }
    }

    private static java.util.Set<Thread> cleanupThreads() {
        java.util.Set<Thread> out = new java.util.HashSet<>();
        for (Thread th : Thread.getAllStackTraces().keySet()) {
            if (th.getName().startsWith("naru-auth-cleanup") && th.isAlive()) {
                out.add(th);
            }
        }
        return out;
    }

    /** The cleanup thread that started after {@code before} was taken. */
    private static Thread newCleanupThread(java.util.Set<Thread> before) throws Exception {
        final Thread[] found = new Thread[1];
        await(() -> {
            for (Thread th : cleanupThreads()) {
                if (!before.contains(th)) {
                    found[0] = th;
                    return true;
                }
            }
            return false;
        });
        assertTrue(found[0].isDaemon());
        return found[0];
    }

    private static void await(Callable<Boolean> condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.call()) {
            if (System.nanoTime() > end) {
                fail("condition not reached");
            }
            Thread.sleep(10);
        }
    }

    // FR-TOKEN-002 AC-5: every 10 minutes a daemon scheduler purges expired refresh records and, with .apiKeys(store),
    // deletes expired keys on the same run; a store keeping the default purgeExpired still runs; after onStop the
    // thread is gone and nothing is called again
    @Test(timeout = 60000)
    public void FR_TOKEN_002_AC5_scheduledCleanupStopsOnStop() throws Exception {
        assertEquals(600_000L, Auth.CLEANUP_INTERVAL_MILLIS);
        final AtomicLong clock = new AtomicLong(50_000L);
        CountingStore refresh = new CountingStore();
        refresh.save(record("expired", "f", 40_000L));
        refresh.save(record("live", "f", 90_000L));
        ExpiringKeys keys = new ExpiringKeys();
        Auth auth = Auth.builder(AuthTestSupport.SECRET).hashIterations(1000).logger(new RecordingLogger())
                .timeSource(clock::get).refreshTokens(refresh).apiKeys(keys).cleanupInterval(30L).build();
        TestApp t = TestApp.create();
        auth.install(t.app());
        java.util.Set<Thread> before = cleanupThreads();
        t.start();
        final Thread thread = newCleanupThread(before);
        await(() -> refresh.purges.get() >= 2 && keys.deletes.get() >= 2);
        assertNull(refresh.find("expired"));
        assertNotNull(refresh.find("live"));
        assertEquals(50_000L, keys.lastNow.get());
        t.app().shutdown(5, TimeUnit.SECONDS);
        await(() -> !thread.isAlive());
        int purges = refresh.purges.get();
        int deletes = keys.deletes.get();
        Thread.sleep(200);
        assertEquals(purges, refresh.purges.get());
        assertEquals(deletes, keys.deletes.get());

        NoPurgeStore plain = new NoPurgeStore();
        plain.save(record("x", "f", 1L));
        assertEquals(0, plain.purgeExpired(5_000L));
        // the key store is called after the refresh purge on every run, so its count shows the runs happened
        final ExpiringKeys keys2 = new ExpiringKeys();
        RecordingLogger log2 = new RecordingLogger();
        Auth withDefault = Auth.builder(AuthTestSupport.SECRET).hashIterations(1000).logger(log2)
                .refreshTokens(plain).apiKeys(keys2).cleanupInterval(20L).build();
        TestApp t2 = TestApp.create();
        withDefault.install(t2.app());
        java.util.Set<Thread> before2 = cleanupThreads();
        t2.start();
        final Thread thread2 = newCleanupThread(before2);
        await(() -> keys2.deletes.get() >= 2);
        assertSame(plain, withDefault.refreshTokens());
        assertTrue(thread2.isAlive());
        // and the default purgeExpired those runs called did not fail
        assertEquals(log2.lines.toString(), 0, RecordingLogger.countMatching(log2.lines,
                "(?s).*cleanup of expired refresh tokens failed.*"));
        assertEquals(log2.lines.toString(), 0, log2.count("ERROR"));
        t2.app().shutdown(5, TimeUnit.SECONDS);
        await(() -> !thread2.isAlive());
    }

    // FR-TOKEN-002 AC-5 (R2-M1-F02): an interrupt pending on the stopping thread does not skip the cleanup stop:
    // after onStop the scheduler thread has terminated and onStop itself did not fail
    @Test(timeout = 60000)
    public void FR_TOKEN_002_AC5_interruptDuringStopStillStopsTheCleanupThread() throws Exception {
        W8 w = new W8();
        java.util.Set<Thread> before = cleanupThreads();
        w.start(b -> b);
        final Thread thread = newCleanupThread(before);
        w.user("alice", W8.PW, "reader");
        for (int i = 0; i < 3; i++) {
            w.loginBody("alice", W8.PW);
        }
        Thread.currentThread().interrupt();
        try {
            w.app.shutdown(5, TimeUnit.SECONDS);
        } finally {
            Thread.interrupted(); // clear whatever the stop left, so the waits below are not interrupted
        }
        assertEquals(com.snoworca.naruserve.AppState.TERMINATED, w.app.state());
        assertEquals(w.logger.lines.toString(), 0, RecordingLogger.countMatching(w.logger.lines,
                "(?s).*onStop callback failed.*"));
        await(() -> !thread.isAlive());
    }

    // FR-TOKEN-002 AC-6: MULTI_INSTANCE refuses an in-memory or file refresh store, and a custom user store without a
    // refresh store fails instead of falling back to memory
    @Test
    public void FR_TOKEN_002_AC6_multiInstanceRefusesLocalRefreshStores() throws Exception {
        RefreshTokenStore[] local = {new InMemoryRefreshTokenStore(),
            new FileRefreshTokenStore(new File(tmp.getRoot(), "rt.json"))};
        for (RefreshTokenStore s : local) {
            try {
                Auth.builder(AuthTestSupport.SECRET).hashIterations(1000).deployment(Deployment.MULTI_INSTANCE)
                        .users(new CustomUserStore()).refreshTokens(s).build();
                fail("MULTI_INSTANCE accepted " + s);
            } catch (AuthConfigException e) {
                assertTrue(e.getMessage(), e.getMessage().contains(s.getClass().getSimpleName()
                        + " (refresh token store)"));
            }
        }
        try {
            Auth.builder(AuthTestSupport.SECRET).hashIterations(1000).deployment(Deployment.MULTI_INSTANCE)
                    .users(new CustomUserStore()).build();
            fail("MULTI_INSTANCE fell back to an in-memory refresh store");
        } catch (AuthConfigException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("in-memory defaults of the unset refresh token store"));
        }
    }

    // FR-TOKEN-002 AC-7: a file refresh store reopened after a clean stop still accepts an unused token issued before
    @Test
    public void FR_TOKEN_002_AC7_fileStoreSurvivesARestart() throws Exception {
        File f = new File(tmp.getRoot(), "refresh-tokens.json");
        FileRefreshTokenStore before = new FileRefreshTokenStore(f);
        before.save(record("t1", "fam", 9_000_000L));
        before.save(record("t2", "fam", 9_000_000L));
        assertTrue(before.markUsed("t2", 2_000L));
        FileRefreshTokenStore after = new FileRefreshTokenStore(f);
        RefreshRecord t1 = after.find("t1");
        assertNotNull(t1);
        assertNull(t1.getUsedAt());
        assertFalse(t1.isRevoked());
        assertTrue(after.markUsed("t1", 3_000L));
        assertFalse(after.markUsed("t2", 3_000L));
        assertEquals(Long.valueOf(2_000L), after.find("t2").getUsedAt());
        assertEquals(2, new FileRefreshTokenStore(f).revokeFamily("fam"));
        assertTrue(new FileRefreshTokenStore(f).find("t1").isRevoked());
    }

    // FR-TOKEN-002 AC-7: at token level: a refresh token issued before the restart refreshes on a new Auth over a new
    // store opened on the same file
    @Test
    public void FR_TOKEN_002_AC7_tokenIssuedBeforeARestartStillRefreshes() throws Exception {
        File f = new File(tmp.getRoot(), "refresh-tokens.json");
        InMemoryUserStore users = new InMemoryUserStore();
        AuthUser alice = users.create(AuthUser.builder().username("alice").passwordHash("x").build());
        Auth before = Auth.builder(AuthTestSupport.SECRET).hashIterations(1000).logger(new RecordingLogger())
                .users(users).refreshTokens(new FileRefreshTokenStore(f)).build();
        TokenPair issued = before.tokens().issue(alice);
        Auth after = Auth.builder(AuthTestSupport.SECRET).hashIterations(1000).logger(new RecordingLogger())
                .users(users).refreshTokens(new FileRefreshTokenStore(f)).build();
        assertNotNull(after.tokens().refresh(issued.getRefreshToken()).getRefreshToken());
    }

    // FR-TOKEN-002 AC-5: an Auth built without the test-only interval override runs its cleanup every 10 minutes
    @Test
    public void FR_TOKEN_002_AC5_defaultIntervalIsTenMinutes() throws Exception {
        java.lang.reflect.Field interval = Auth.class.getDeclaredField("cleanupIntervalMillis");
        interval.setAccessible(true);
        assertEquals(600_000L, interval.getLong(Auth.builder(AuthTestSupport.SECRET).hashIterations(1000).build()));
    }

    // FR-TOKEN-002 AC-8: the file carries a format version; an unknown version fails start with a message naming it
    // and the file is not overwritten
    @Test
    public void FR_TOKEN_002_AC8_unknownVersionFails() throws Exception {
        File f = new File(tmp.getRoot(), "users-refresh-tokens.json");
        new FileRefreshTokenStore(f).save(record("t1", "fam", 9_000L));
        String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        assertTrue(text, text.contains("\"version\":1"));
        String future = text.replace("\"version\":1", "\"version\":2");
        Files.write(f.toPath(), future.getBytes(StandardCharsets.UTF_8));
        try {
            new FileRefreshTokenStore(f);
            fail("a version 2 refresh file was opened");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().startsWith("FAIL") && e.getMessage().contains("version 2"));
        }
        assertEquals(future, new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        // through the default selection the start of a FileUserStore app fails the same way, in build()
        File users = new File(tmp.getRoot(), "users.json");
        new FileUserStore(users).create(AuthUser.builder().username("a").passwordHash("h").build());
        try {
            Auth.builder(AuthTestSupport.SECRET).hashIterations(1000).users(new FileUserStore(users)).build();
            fail("a version 2 refresh file next to the users file was accepted");
        } catch (AuthConfigException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("version 2"));
        }
        assertEquals(future, new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
    }

    // A long-lived session on the file store does not grow the file: each rotation keeps the current record and only
    // its 32 most recently used predecessors, so 500 rotations of one family leave 33 records, and presenting a recent
    // predecessor again still revokes the family (SEC-TOKEN-006 AC-1 on the file default)
    @Test(timeout = 120000)
    public void fileStoreKeepsABoundedHistoryPerFamily() throws Exception {
        File f = new File(tmp.getRoot(), "refresh-tokens.json");
        FileRefreshTokenStore s = new FileRefreshTokenStore(f);
        s.save(record("t0", "fam", 9_000_000L));
        s.save(record("other", "fam2", 9_000_000L));
        for (int i = 1; i <= 500; i++) {
            assertTrue(s.markUsed("t" + (i - 1), 2_000L + i));
            s.save(record("t" + i, "fam", 9_000_000L));
        }
        assertTrue(s.toString(), s.toString().contains("records=34"));
        assertNull(s.find("t1"));
        assertNull(s.find("t467"));
        assertNotNull(s.find("t468"));
        assertNotNull(s.find("t499"));
        assertNotNull(s.find("t500"));
        assertNotNull("another family is untouched", s.find("other"));
        assertTrue(new FileRefreshTokenStore(f).toString().contains("records=34"));

        final AtomicLong clock = new AtomicLong(1_761_700_900_000L);
        InMemoryUserStore users = new InMemoryUserStore();
        AuthUser alice = users.create(AuthUser.builder().username("alice").passwordHash("x").build());
        FileRefreshTokenStore store = new FileRefreshTokenStore(new File(tmp.getRoot(), "auth-refresh.json"));
        Auth auth = Auth.builder(AuthTestSupport.SECRET).hashIterations(1000).logger(new RecordingLogger())
                .users(users).refreshTokens(store).timeSource(clock::get).reuseGrace(0).build();
        TokenPair previous = auth.tokens().issue(alice);
        TokenPair current = auth.tokens().refresh(previous.getRefreshToken());
        for (int i = 0; i < 50; i++) {
            clock.addAndGet(1000);
            previous = current;
            current = auth.tokens().refresh(current.getRefreshToken());
        }
        assertTrue(store.toString(), store.toString().contains("records=33"));
        try {
            auth.tokens().refresh(previous.getRefreshToken());
            fail("the used predecessor was accepted");
        } catch (AuthRejection expected) {
            assertEquals("invalid_refresh_token", expected.getCode());
        }
        try {
            auth.tokens().refresh(current.getRefreshToken());
            fail("the family survived the reuse of the predecessor");
        } catch (AuthRejection expected) {
            assertEquals("invalid_refresh_token", expected.getCode());
        }
    }

    private static RefreshRecord churn(String tokenId, String familyId, long createdAt, long expiresAt) {
        return RefreshRecord.builder().tokenId(tokenId).familyId(familyId).userId("u1")
                .tokenHash("5e884898da28047151d0e56f8dc6292773603d0d6aabbdd62a11ef721d1542d8").createdAt(createdAt)
                .expiresAt(expiresAt).build();
    }

    // FR-TOKEN-002 AC-4 (M1-F01): login/logout churn does not grow the file store: every login starts a family and
    // every logout revokes it; a revoked record is dropped when the next record is saved, so 5000 sign-in/sign-out
    // cycles fit a 64 KB file (about 290 records) and leave only the last logged-out family
    @Test(timeout = 120000)
    public void fileStoreStaysBoundedUnderLoginLogoutChurn() throws Exception {
        File f = new File(tmp.getRoot(), "refresh-tokens.json");
        FileRefreshTokenStore s = new FileRefreshTokenStore(f, 64 * 1024, new RecordingLogger());
        for (int i = 0; i < 5000; i++) {
            s.save(churn("t" + i, "fam" + i, 1_000L + i, 9_000_000_000L));
            assertEquals(1, s.revokeFamily("fam" + i));
        }
        assertTrue(s.toString(), s.toString().contains("records=1}"));
        assertTrue("a logged-out record is still revoked until the next save", s.find("t4999").isRevoked());
        assertNull("an older logged-out record is gone", s.find("t4998"));
        assertTrue(f.length() + " bytes", f.length() < 1024);
        s.save(churn("live", "famLive", 9_000L, 9_000_000_000L));
        assertNull(s.find("t4999"));
        assertNotNull(new FileRefreshTokenStore(f).find("live"));
    }

    // FR-TOKEN-002 AC-5 (M1-F01): sessions dropped without a logout do not grow the file past their lifetime either:
    // saving a record removes the records that expired at its creation time, so 5000 logins 100 ms apart with a 1 s
    // refresh lifetime keep at most about ten records in a 64 KB file
    @Test(timeout = 120000)
    public void fileStorePurgesExpiredRecordsOnSave() throws Exception {
        File f = new File(tmp.getRoot(), "refresh-tokens.json");
        FileRefreshTokenStore s = new FileRefreshTokenStore(f, 64 * 1024, new RecordingLogger());
        for (int i = 0; i < 5000; i++) {
            long now = 1_000L + i * 100L;
            s.save(churn("t" + i, "fam" + i, now, now + 1_000L));
        }
        assertTrue(s.toString(), s.toString().contains("records=10}"));
        assertNull("an expired record is gone", s.find("t4989"));
        assertNotNull("an unexpired record stays", s.find("t4990"));
        assertNotNull(s.find("t4999"));
    }

    // FR-TOKEN-002 AC-7 (M1-F01): when live sessions fill the file close to its limit, only a new sign-in (a record
    // of a family the file does not hold) is refused, with a message that names the cause; the existing sessions keep
    // rotating in the reserved last eighth, and nothing already stored is lost
    @Test(timeout = 120000)
    public void fullFileStoreRefusesOnlyNewSignIns() throws Exception {
        File f = new File(tmp.getRoot(), "refresh-tokens.json");
        long limit = 32 * 1024;
        FileRefreshTokenStore s = new FileRefreshTokenStore(f, limit, new RecordingLogger());
        int stored = 0;
        IOException refused = null;
        for (int i = 0; i < 1000 && refused == null; i++) {
            try {
                s.save(churn("t" + i, "fam" + i, 1_000L + i, 9_000_000_000L));
                stored++;
            } catch (IOException e) {
                refused = e;
            }
        }
        assertNotNull("the file never refused a new sign-in", refused);
        assertTrue(refused.getMessage(), refused.getMessage().contains("new sign-ins are refused"));
        assertTrue(refused.getMessage(), refused.getMessage().contains("app-provided RefreshTokenStore"));
        assertTrue(f.length() + " bytes", f.length() <= limit - limit / 8);
        assertTrue(s.toString(), s.toString().contains("records=" + stored + "}"));
        // an existing session still rotates: its family is in the file
        String current = "t0";
        for (int r = 1; r <= 8; r++) {
            assertTrue(s.markUsed(current, 2_000L + r));
            current = "t0r" + r;
            s.save(churn(current, "fam0", 2_000L + r, 9_000_000_000L));
        }
        assertNotNull(new FileRefreshTokenStore(f, limit, new RecordingLogger()).find(current));
        assertTrue(f.length() + " bytes", f.length() <= limit);
    }

    // FR-TOKEN-002 / SEC-TOKEN-006 AC-1 (F7-N01): once new sign-ins are refused, every session the file admitted keeps
    // refreshing, however often: each family rotates 40 times in turn (the worst order for the file) and every
    // rotation succeeds within the limit, presenting the immediate predecessor still finds it used (so it still revokes
    // the family), and when sessions log out new sign-ins are admitted again
    @Test(timeout = 120000)
    public void everyAdmittedSessionKeepsRotatingAfterSignInsAreRefused() throws Exception {
        File f = new File(tmp.getRoot(), "refresh-tokens.json");
        long limit = 16 * 1024;
        FileRefreshTokenStore s = new FileRefreshTokenStore(f, limit, new RecordingLogger());
        List<String> current = new ArrayList<>();
        IOException refused = null;
        for (int i = 0; i < 1000 && refused == null; i++) {
            try {
                String id = String.format("t%04dr%03d", i, 0);
                s.save(churn(id, "fam" + String.format("%04d", i), 1_000L, 9_000_000_000L));
                current.add(id);
            } catch (IOException e) {
                refused = e;
            }
        }
        assertNotNull("the file never refused a new sign-in", refused);
        assertTrue(refused.getMessage(), refused.getMessage().contains("new sign-ins are refused"));
        int families = current.size();
        assertTrue(families + " families", families >= 10);
        long now = 2_000L;
        for (int r = 1; r <= 40; r++) {
            for (int i = 0; i < families; i++) {
                now++;
                String previous = current.get(i);
                try {
                    assertTrue(previous, s.markUsed(previous, now));
                    String next = String.format("t%04dr%03d", i, r);
                    s.save(churn(next, "fam" + String.format("%04d", i), now, 9_000_000_000L));
                    current.set(i, next);
                } catch (IOException | RuntimeException e) {
                    throw new AssertionError("rotation " + r + " of family " + i + " of " + families + " failed at "
                            + f.length() + " bytes: " + e, e);
                }
                assertTrue(f.length() + " bytes", f.length() <= limit);
                RefreshRecord predecessor = s.find(previous);
                assertNotNull("the immediate predecessor " + previous + " is kept", predecessor);
                assertNotNull("the immediate predecessor is used", predecessor.getUsedAt());
            }
        }
        FileRefreshTokenStore reopened = new FileRefreshTokenStore(f, limit, new RecordingLogger());
        for (String id : current) {
            assertNotNull(id, reopened.find(id));
            assertNull(id, reopened.find(id).getUsedAt());
        }
        // sessions that log out make room: a new sign-in is admitted again
        for (int i = 0; i < families / 2; i++) {
            assertTrue(s.revokeFamily("fam" + String.format("%04d", i)) > 0);
        }
        s.save(churn("t9999r000", "fam9999", now + 1, 9_000_000_000L));
        assertNotNull(s.find("t9999r000"));
    }

    // FR-TOKEN-002 (F7-N01) on the real token path: after a FileRefreshTokenStore refuses new sign-ins (503), every
    // signed-in session keeps refreshing through TokenService, 40 times each in turn, with no 503
    @Test(timeout = 120000)
    public void signedInSessionsKeepRefreshingThroughTokenServiceWhenTheFileIsFull() throws Exception {
        final AtomicLong clock = new AtomicLong(1_761_700_900_000L);
        InMemoryUserStore users = new InMemoryUserStore();
        AuthUser alice = users.create(AuthUser.builder().username("alice").passwordHash("x").build());
        FileRefreshTokenStore store = new FileRefreshTokenStore(new File(tmp.getRoot(), "auth-refresh.json"),
                16 * 1024, new RecordingLogger());
        Auth auth = Auth.builder(AuthTestSupport.SECRET).hashIterations(1000).logger(new RecordingLogger())
                .users(users).refreshTokens(store).timeSource(clock::get).reuseGrace(0).build();
        List<String> sessions = new ArrayList<>();
        AuthUnavailableException full = null;
        for (int i = 0; i < 1000 && full == null; i++) {
            try {
                sessions.add(auth.tokens().issue(alice).getRefreshToken());
            } catch (AuthUnavailableException e) {
                full = e;
            }
        }
        assertNotNull("the 16 KB file never refused a sign-in", full);
        assertTrue(sessions.size() + " sessions", sessions.size() >= 10);
        for (int r = 1; r <= 40; r++) {
            for (int i = 0; i < sessions.size(); i++) {
                clock.addAndGet(1000);
                try {
                    sessions.set(i, auth.tokens().refresh(sessions.get(i)).getRefreshToken());
                } catch (AuthUnavailableException e) {
                    throw new AssertionError("refresh " + r + " of session " + i + " of " + sessions.size()
                            + " answered 503: " + e.getCause(), e);
                }
            }
        }
        assertTrue(store.getFile().length() + " bytes", store.getFile().length() <= 16 * 1024);
    }

    // The refresh store's size limit is applied before its file is read: a file one byte above the default limit
    // fails with the FAIL of the length check (a read would stop at the limit with "cannot read" instead); the file is
    // limit + 1 bytes, not a 3 GB sparse file, so the test also runs where the file system has no sparse files and
    // little free space (R2-T1-F03)
    @Test(timeout = 60000)
    public void hugeRefreshFileFailsBeforeItIsRead() throws Exception {
        File f = new File(tmp.getRoot(), "refresh-tokens.json");
        long size = MiniJson.DEFAULT_FILE_BYTES + 1;
        try (java.io.RandomAccessFile r = new java.io.RandomAccessFile(f, "rw")) {
            r.setLength(size);
        }
        try {
            new FileRefreshTokenStore(f);
            fail("a refresh file above the default limit was loaded");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().startsWith("FAIL"));
            assertTrue(e.getMessage(), e.getMessage().contains("above the limit"));
            assertTrue(e.getMessage(), e.getMessage().contains(
                    "app-provided RefreshTokenStore backed by the application's database"));
            assertFalse(e.getMessage(), e.getMessage().contains("Jdbc"));
            assertTrue(e.getMessage(), e.getMessage().contains("has " + size + " bytes"));
            assertTrue(e.getMessage(), e.getMessage().contains("limit of " + MiniJson.DEFAULT_FILE_BYTES + " bytes"));
        }
        assertEquals(size, f.length());
    }

    /** The default selection follows the user store: memory for InMemoryUserStore, a file beside a FileUserStore. */
    @Test
    public void defaultSelectionFollowsTheUserStore() throws Exception {
        Auth mem = Auth.builder(AuthTestSupport.SECRET).hashIterations(1000).build();
        assertTrue(mem.refreshTokens() instanceof InMemoryRefreshTokenStore);
        File dir = tmp.newFolder("data");
        Auth file = Auth.builder(AuthTestSupport.SECRET).hashIterations(1000)
                .users(new FileUserStore(new File(dir, "users.json"), AuthTestSupport.FILE_KEY)).build();
        assertTrue(file.refreshTokens() instanceof FileRefreshTokenStore);
        assertEquals(new File(dir, "users-refresh-tokens.json"), ((FileRefreshTokenStore) file.refreshTokens()).getFile());
        assertTrue(file.describe(), file.describe().contains("refresh tokens: FileRefreshTokenStore"));
    }
}
