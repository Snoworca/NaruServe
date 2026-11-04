package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.utils.auth.user.AuthUser;
import com.snoworca.naruserve.utils.auth.user.AuthUsers;
import com.snoworca.naruserve.utils.auth.user.DuplicateUserException;
import com.snoworca.naruserve.utils.auth.user.FileUserStore;
import com.snoworca.naruserve.utils.auth.user.InMemoryUserStore;
import com.snoworca.naruserve.utils.auth.user.UserStore;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Auth over the shipped user stores and an app-provided one: concurrent renames and the refresh store that follows the
 * user store (there is no built-in database store, CON-IAM-003).
 */
public class AuthStoreDefaultsTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static Auth auth(UserStore store) {
        return Auth.builder(AuthTestSupport.SECRET).hashIterations(1000).users(store).build();
    }

    /** Ten rounds of two threads renaming two different users to one new name at the same moment. */
    private static void raceRenames(UserStore store) throws Exception {
        final AuthUsers users = auth(store).users();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 10; round++) {
                final String target = "taken" + round;
                String pw = "pw-" + round + "-Long-Enough";
                final String[] ids = {users.create("a" + round, pw.toCharArray()).getId(),
                        users.create("b" + round, pw.toCharArray()).getId()};
                final CountDownLatch go = new CountDownLatch(1);
                List<Future<AuthUser>> results = new ArrayList<>();
                for (final String id : ids) {
                    results.add(pool.submit((Callable<AuthUser>) () -> {
                        go.await();
                        return users.rename(id, target);
                    }));
                }
                go.countDown();
                int wins = 0;
                int duplicates = 0;
                for (Future<AuthUser> f : results) {
                    try {
                        assertEquals(target, f.get(30, TimeUnit.SECONDS).getUsername());
                        wins++;
                    } catch (ExecutionException e) {
                        assertTrue(store + ": " + e.getCause(), e.getCause() instanceof DuplicateUserException);
                        duplicates++;
                    }
                }
                assertEquals(store + " round " + round, 1, wins);
                assertEquals(store + " round " + round, 1, duplicates);
                assertNotNull(users.findByUsername(target));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // FR-IAM-001 AC-2: two threads renaming two different users to the same new name through auth.users().rename:
    // exactly one succeeds and the other gets DuplicateUserException, with InMemoryUserStore, FileUserStore and an
    // app-provided test UserStore whose create and update enforce the unique name atomically
    @Test(timeout = 180000)
    public void FR_IAM_001_AC2_concurrentRenamesToOneNameOneWins() throws Exception {
        raceRenames(new InMemoryUserStore());
        raceRenames(new FileUserStore(new File(tmp.newFolder(), "users.json"), AuthTestSupport.FILE_KEY));
        raceRenames(new AppStores().users());
    }

    // FR-TOKEN-002 AC-2: with no refresh store configured, InMemoryUserStore gets the in-memory store and FileUserStore
    // a file store in the same directory as the user file; an app-provided UserStore gets the in-memory store under
    // SINGLE_INSTANCE (AC-3) and a build() failure under MULTI_INSTANCE (AC-6), as no built-in database store exists
    @Test
    public void FR_TOKEN_002_AC2_defaultRefreshStoreFollowsTheUserStore() throws Exception {
        assertTrue(auth(new InMemoryUserStore()).refreshTokens() instanceof InMemoryRefreshTokenStore);
        File dir = tmp.newFolder("data");
        Auth file = auth(new FileUserStore(new File(dir, "people.json"), AuthTestSupport.FILE_KEY));
        assertTrue(file.refreshTokens() instanceof FileRefreshTokenStore);
        assertEquals(new File(dir, "people-refresh-tokens.json").getAbsoluteFile(),
                ((FileRefreshTokenStore) file.refreshTokens()).getFile().getAbsoluteFile());
        AppStores db = new AppStores();
        Auth app = auth(db.users());
        assertTrue(app.refreshTokens().getClass().getName(), app.refreshTokens() instanceof InMemoryRefreshTokenStore);
        try {
            Auth.builder(AuthTestSupport.SECRET).hashIterations(1000).deployment(Deployment.MULTI_INSTANCE)
                    .users(db.users()).loginThrottle(db.loginThrottle()).deviceTrust(db.deviceTrust()).build();
            fail("MULTI_INSTANCE fell back to the in-memory refresh store");
        } catch (AuthConfigException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("in-memory defaults of the unset refresh token store"));
        }
        // a configured store still wins
        InMemoryRefreshTokenStore mine = new InMemoryRefreshTokenStore();
        assertSame(mine, Auth.builder(AuthTestSupport.SECRET).hashIterations(1000).users(db.users())
                .refreshTokens(mine).build().refreshTokens());
    }

    // IR-FLOW-003 AC-2 and REL-FLOW-001 (M2-F19): the file throttle shared by every Auth over one FileUserStore reads
    // the clock of the Auth built last over it, so after 60 seconds of that Auth's .timeSource the block is over
    @Test
    public void IR_FLOW_003_AC2_sharedFileThrottleReadsTheLatestAuthsClock() throws Exception {
        FileUserStore users = new FileUserStore(new File(tmp.newFolder(), "users.json"), AuthTestSupport.FILE_KEY);
        final long t0 = 1_761_700_900_000L;
        Auth a = Auth.builder(AuthTestSupport.SECRET).hashIterations(1000).users(users).timeSource(() -> t0).build();
        final java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(t0);
        Auth b = Auth.builder(AuthTestSupport.SECRET).hashIterations(1000).users(users).timeSource(clock::get)
                .build();
        assertSame("one throttle per FileUserStore", a.loginThrottle(), b.loginThrottle());
        for (int i = 0; i < 3; i++) {
            b.loginThrottle().recordFailure("k", 3, 60_000L);
        }
        assertEquals(60_000L, b.loginThrottle().retryAfter("k", 3, 60_000L));
        clock.addAndGet(10 * 60_000L);
        assertEquals("the shared throttle still reads the first Auth's frozen clock", 0L,
                b.loginThrottle().retryAfter("k", 3, 60_000L));
    }

    // REL-FLOW-001 (R2-M1-F08): with no .logger, the file defaults shared over one FileUserStore log to the app of the
    // Auth installed last over it, not to an earlier, stopped app
    @Test(timeout = 120000)
    public void REL_FLOW_001_sharedFileDefaultsLogToTheLatestApp() throws Exception {
        FileUserStore users = new FileUserStore(new File(tmp.newFolder(), "users.json"), AuthTestSupport.FILE_KEY);
        com.snoworca.naruserve.testing.RecordingLogger la = new com.snoworca.naruserve.testing.RecordingLogger();
        com.snoworca.naruserve.RestApp appA = com.snoworca.naruserve.RestApp.builder()
                .httpAdapter(new com.snoworca.naruserve.testing.InMemoryHttpAdapter())
                .jsonCodec(new AdminFixture.MiniJsonCodec()).logger(la).build();
        Auth a = Auth.builder(AuthTestSupport.SECRET).hashIterations(1000).users(users).build();
        a.install(appA);
        appA.listen(0);
        appA.shutdown(5, TimeUnit.SECONDS);
        int aLines = la.lines.size();
        com.snoworca.naruserve.testing.RecordingLogger lb = new com.snoworca.naruserve.testing.RecordingLogger();
        com.snoworca.naruserve.RestApp appB = com.snoworca.naruserve.RestApp.builder()
                .httpAdapter(new com.snoworca.naruserve.testing.InMemoryHttpAdapter())
                .jsonCodec(new AdminFixture.MiniJsonCodec()).logger(lb).build();
        Auth b = Auth.builder(AuthTestSupport.SECRET).hashIterations(1000).users(users).build();
        b.install(appB);
        appB.listen(0);
        try {
            assertSame(a.loginThrottle(), b.loginThrottle());
            for (int i = 0; i < 100_002; i++) {
                b.loginThrottle().recordFailure("key-" + i, 3, 60_000L);
            }
            String cap = "(?s)WARN .*login throttle reached its cap.*";
            assertEquals(lb.lines.toString(), 1, com.snoworca.naruserve.testing.RecordingLogger.countMatching(lb.lines,
                    cap));
            assertEquals("the stopped app's logger got the WARN", 0, com.snoworca.naruserve.testing.RecordingLogger
                    .countMatching(la.lines.subList(aLines, la.lines.size()), cap));
        } finally {
            appB.shutdown(5, TimeUnit.SECONDS);
        }
    }
}
