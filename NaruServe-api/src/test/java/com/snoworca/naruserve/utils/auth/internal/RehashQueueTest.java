package com.snoworca.naruserve.utils.auth.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.utils.auth.password.HashCost;
import com.snoworca.naruserve.utils.auth.password.PasswordHasher;
import com.snoworca.naruserve.utils.auth.user.AuthUser;
import com.snoworca.naruserve.utils.auth.user.ForwardingUserStore;
import com.snoworca.naruserve.utils.auth.user.InMemoryUserStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.After;
import org.junit.Test;

/** The low-priority rehash queue (PERF-TOKEN-002; the login ACs come with the login endpoint). */
// @req PERF-TOKEN-002
public class RehashQueueTest {

    private static final String OLD = "legacy-md5$5f4dcc3b5aa765d61d8327deb882cf99";

    /** A hasher whose hash() blocks until released, counting how many hashes run at once and on which threads. */
    private static final class BlockingHasher implements PasswordHasher {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger running = new AtomicInteger();
        final AtomicInteger maxRunning = new AtomicInteger();
        final AtomicInteger made = new AtomicInteger();
        final Set<String> threads = Collections.synchronizedSet(new HashSet<String>());
        volatile @Nullable String failOn;

        @Override
        public boolean accepts(String stored) {
            return stored.startsWith("new$");
        }

        @Override
        public String hash(char[] password) {
            int now = running.incrementAndGet();
            maxRunning.accumulateAndGet(now, Math::max);
            threads.add(Thread.currentThread().getName());
            try {
                entered.countDown();
                if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("never released");
                }
                if (new String(password).equals(failOn)) {
                    throw new IllegalStateException("boom");
                }
                return "new$" + made.incrementAndGet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            } finally {
                running.decrementAndGet();
            }
        }

        @Override
        public boolean verify(char[] password, String stored) {
            return false;
        }
    }

    /** Records every updatePasswordHash (id and thread) and counts them down. */
    private static final class RecordingStore extends ForwardingUserStore {
        final List<String> written = Collections.synchronizedList(new ArrayList<String>());
        final Set<Thread> writers = Collections.synchronizedSet(new HashSet<Thread>());
        final AtomicReference<CountDownLatch> writes = new AtomicReference<>(new CountDownLatch(1));
        volatile @Nullable String failFor;

        RecordingStore() {
            super(new InMemoryUserStore());
        }

        @Override
        public boolean updatePasswordHash(String id, @Nullable String expectedOldHash, String newHash)
                throws Exception {
            writers.add(Thread.currentThread());
            try {
                if (id.equals(failFor)) {
                    throw new IllegalStateException("column too short");
                }
                written.add(id);
                return super.updatePasswordHash(id, expectedOldHash, newHash);
            } finally {
                writes.get().countDown();
            }
        }

        AuthUser add(String name) throws Exception {
            return create(AuthUser.builder().username(name).passwordHash(OLD).roles("user").build());
        }

        AuthUser get(String id) throws Exception {
            AuthUser u = findById(id);
            assertNotNull(u);
            return u;
        }
    }

    private final List<HashSlots> pools = new ArrayList<>();
    private final List<RehashQueue> queues = new ArrayList<>();

    @After
    public void stopAll() {
        for (RehashQueue q : queues) {
            q.stop();
        }
        for (HashSlots s : pools) {
            s.shutdown();
        }
    }

    private RehashQueue queue(RecordingStore store, BlockingHasher hasher, RecordingLogger log) {
        HashSlots slots = new HashSlots(1, 8, 10_000L);
        pools.add(slots);
        RehashQueue q = new RehashQueue(store, new Passwords(hasher, HashCost.fixed(1000), slots), log);
        queues.add(q);
        return q;
    }

    private static char[] pw() {
        return "password".toCharArray();
    }

    private static boolean wiped(char[] a) {
        for (char c : a) {
            if (c != '\0') {
                return false;
            }
        }
        return true;
    }

    // PERF-TOKEN-002 queue component (its full-queue criterion counts logins, which wait for POST /auth/login): with 100 entries queued the 101st is dropped without blocking, and the same user's next
    // offer is accepted again once there is room
    @Test(timeout = 30000)
    public void fullQueueDropsWithoutBlockingAndAcceptsLater() throws Exception {
        RecordingStore store = new RecordingStore();
        BlockingHasher hasher = new BlockingHasher();
        RecordingLogger log = new RecordingLogger();
        RehashQueue q = queue(store, hasher, log);
        List<AuthUser> users = new ArrayList<>();
        for (int i = 0; i < 102; i++) {
            users.add(store.add("u" + i));
        }
        store.writes.set(new CountDownLatch(101));

        assertTrue(q.offer(users.get(0), pw()));
        assertTrue(hasher.entered.await(10, TimeUnit.SECONDS)); // the worker holds u0, the queue is empty
        for (int i = 1; i <= RehashQueue.CAPACITY; i++) {
            assertTrue("entry " + i, q.offer(users.get(i), pw()));
        }
        assertEquals(100, q.size());
        char[] dropped = pw();
        long start = System.nanoTime();
        assertFalse("the 101st queued entry is dropped", q.offer(users.get(101), dropped));
        // offer() returned while the worker is still parked in the hasher and nothing was written: it did not wait
        // for room (the blocking alternative waits forever); the time is a generous sanity bound only
        assertEquals("the worker is still parked in the hasher", 1, hasher.running.get());
        assertEquals(0, hasher.made.get());
        assertEquals(0, store.written.size());
        assertTrue("dropping never blocks", System.nanoTime() - start < TimeUnit.SECONDS.toNanos(10));
        assertTrue(wiped(dropped));
        assertEquals(100, q.size());

        hasher.release.countDown();
        assertTrue(store.writes.get().await(20, TimeUnit.SECONDS));
        assertEquals(101, store.written.size());
        assertEquals(OLD, store.get(users.get(101).getId()).getPasswordHash());

        store.writes.set(new CountDownLatch(1));
        assertTrue("the next login of the dropped user queues it again", q.offer(users.get(101), pw()));
        assertTrue(store.writes.get().await(10, TimeUnit.SECONDS));
        assertTrue(store.get(users.get(101).getId()).getPasswordHash().startsWith("new$"));
        assertEquals(0, log.count("WARN"));
    }

    // PERF-TOKEN-002 AC-3: one daemon worker serves the queue and hashes only inside a slot of the shared pool, so
    // concurrent hashes never exceed the slot count
    @Test(timeout = 30000)
    public void PERF_TOKEN_002_AC3_oneDaemonWorkerHashesOnlyInsideASlot() throws Exception {
        RecordingStore store = new RecordingStore();
        BlockingHasher hasher = new BlockingHasher();
        RecordingLogger log = new RecordingLogger();
        HashSlots slots = new HashSlots(1, 8, 10_000L);
        pools.add(slots);
        final Passwords passwords = new Passwords(hasher, HashCost.fixed(1000), slots);
        RehashQueue q = new RehashQueue(store, passwords, log);
        queues.add(q);
        List<AuthUser> users = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            users.add(store.add("w" + i));
        }
        store.writes.set(new CountDownLatch(5));
        assertTrue(q.offer(users.get(0), pw()));
        assertTrue(hasher.entered.await(10, TimeUnit.SECONDS)); // the worker's hash holds the only slot
        for (int i = 1; i < 5; i++) {
            assertTrue(q.offer(users.get(i), pw()));
        }

        // a login hash of the same pool waits for the slot instead of running beside the rehash
        final AtomicReference<String> other = new AtomicReference<>();
        Thread login = new Thread(new Runnable() {
            @Override
            public void run() {
                other.set(passwords.hash("other".toCharArray()));
            }
        });
        login.start();
        while (login.getState() != Thread.State.WAITING && login.getState() != Thread.State.TIMED_WAITING) {
            Thread.yield();
        }
        assertEquals("only one hash runs with one slot", 1, hasher.running.get());

        hasher.release.countDown();
        login.join(10_000L);
        assertTrue(store.writes.get().await(20, TimeUnit.SECONDS));
        assertNotNull(other.get());
        assertEquals(1, hasher.maxRunning.get());
        for (String name : hasher.threads) {
            assertTrue("hashes run on the slot pool's threads: " + name, name.startsWith("naru-auth-hash-"));
        }

        Thread worker = q.worker();
        assertNotNull(worker);
        assertTrue(worker.isDaemon());
        assertEquals(Collections.singleton(worker), new HashSet<>(store.writers));
        int named = 0;
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.getName().equals(worker.getName())) {
                named++;
            }
        }
        assertEquals("exactly one worker thread", 1, named);
        assertEquals(5, store.written.size());
        q.stop();
        worker.join(10_000L);
        assertFalse(worker.isAlive());
        assertSame(worker, q.worker());
    }

    // PERF-TOKEN-002 AC-4: the char[] password of a processed entry and of a dropped one is all '\0' afterwards
    @Test(timeout = 30000)
    public void PERF_TOKEN_002_AC4_processedAndDroppedPasswordsAreWiped() throws Exception {
        RecordingStore store = new RecordingStore();
        BlockingHasher hasher = new BlockingHasher();
        hasher.release.countDown();
        RecordingLogger log = new RecordingLogger();
        RehashQueue q = queue(store, hasher, log);
        AuthUser u = store.add("p");

        char[] processed = pw();
        assertTrue(q.offer(u, processed));
        assertTrue(store.writes.get().await(10, TimeUnit.SECONDS));
        for (int i = 0; i < 500 && !wiped(processed); i++) {
            Thread.sleep(10); // the worker may wipe after the write
        }
        assertTrue(wiped(processed));

        char[] noId = pw();
        assertFalse("a user without an id is dropped", q.offer(AuthUser.builder().username("x").passwordHash(OLD)
                .build(), noId));
        assertTrue(wiped(noId));

        // a hash failure drops the entry with a WARN, and its array is wiped too
        BlockingHasher failing = new BlockingHasher();
        failing.failOn = "boom";
        failing.release.countDown();
        RecordingStore other = new RecordingStore();
        RehashQueue fq = queue(other, failing, log);
        AuthUser f = other.add("f");
        AuthUser s = other.add("s");
        char[] failed = "boom".toCharArray();
        assertTrue(fq.offer(f, failed));
        // processed in order: once s is written, f's entry is done
        fq.offer(s, pw());
        assertTrue(other.writes.get().await(10, TimeUnit.SECONDS));
        assertTrue(wiped(failed));
        assertEquals(1, log.count("WARN"));
        assertEquals(Collections.singletonList(s.getId()), other.written);

        // entries still waiting when the queue stops, and offers after it, are wiped
        RecordingStore third = new RecordingStore();
        BlockingHasher blocked = new BlockingHasher();
        RehashQueue sq = queue(third, blocked, log);
        AuthUser a = third.add("a");
        AuthUser b = third.add("b");
        assertTrue(sq.offer(a, pw()));
        assertTrue(blocked.entered.await(10, TimeUnit.SECONDS));
        char[] waiting = pw();
        assertTrue(sq.offer(b, waiting));
        sq.stop();
        assertTrue(wiped(waiting));
        char[] late = pw();
        assertFalse(sq.offer(b, late));
        assertTrue(wiped(late));
        blocked.release.countDown();
    }

    // PERF-TOKEN-002 AC-5: a passwordHash, ver, enabled or roles change between login and the rehash skips the
    // write, and no exception reaches the caller (a failing store write is a WARN)
    @Test(timeout = 30000)
    public void PERF_TOKEN_002_AC5_changedUserSkipsRehashWithoutError() throws Exception {
        RecordingStore store = new RecordingStore();
        BlockingHasher hasher = new BlockingHasher();
        RecordingLogger log = new RecordingLogger();
        RehashQueue q = queue(store, hasher, log);
        AuthUser hash = store.add("hash");
        AuthUser ver = store.add("ver");
        AuthUser enabled = store.add("enabled");
        AuthUser roles = store.add("roles");
        AuthUser same = store.add("same");

        assertTrue(q.offer(hash, pw()));
        assertTrue(hasher.entered.await(10, TimeUnit.SECONDS));
        assertTrue(q.offer(ver, pw()));
        assertTrue(q.offer(enabled, pw()));
        assertTrue(q.offer(roles, pw()));
        assertTrue(q.offer(same, pw()));
        // changes made after the logins, while the rehashes wait
        store.update(hash.toBuilder().passwordHash("pbkdf2-sha256$changed").build());
        store.update(ver.toBuilder().tokenVersion(ver.getTokenVersion() + 1).build());
        store.update(enabled.toBuilder().enabled(false).build());
        store.update(roles.toBuilder().roles("user", "admin").build());
        hasher.release.countDown();
        assertTrue(store.writes.get().await(10, TimeUnit.SECONDS)); // the last entry, processed after the others

        assertEquals(Collections.singletonList(same.getId()), store.written);
        assertTrue(store.get(same.getId()).getPasswordHash().startsWith("new$"));
        assertEquals("pbkdf2-sha256$changed", store.get(hash.getId()).getPasswordHash());
        AuthUser v = store.get(ver.getId());
        assertEquals(OLD, v.getPasswordHash());
        assertEquals(ver.getTokenVersion() + 1, v.getTokenVersion());
        AuthUser e = store.get(enabled.getId());
        assertEquals(OLD, e.getPasswordHash());
        assertFalse("the disable stays", e.isEnabled());
        assertEquals(OLD, store.get(roles.getId()).getPasswordHash());
        assertEquals(0, log.count("WARN"));

        // a store write that throws is a WARN; the queue goes on with the next entry
        AuthUser broken = store.add("broken");
        AuthUser next = store.add("next");
        store.failFor = broken.getId();
        store.writes.set(new CountDownLatch(2));
        assertTrue(q.offer(broken, pw()));
        assertTrue(q.offer(next, pw()));
        assertTrue(store.writes.get().await(10, TimeUnit.SECONDS));
        assertEquals(1, log.count("WARN"));
        assertTrue(log.lines.toString(), log.lines.get(0).contains("user " + broken.getId()));
        assertTrue(store.get(next.getId()).getPasswordHash().startsWith("new$"));
    }
}
