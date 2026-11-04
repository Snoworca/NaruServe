package com.snoworca.naruserve.utils.auth.internal;

import com.snoworca.naruserve.Logger;
import com.snoworca.naruserve.utils.auth.user.AuthUser;
import com.snoworca.naruserve.utils.auth.user.UserStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;

/**
 * The low-priority rehash of one Auth: a login that verified a hash needing a rehash (a legacy format, or a PBKDF2
 * cost outside the target) hands the password here after it has released its hash slot, and the response does not
 * wait. At most {@link #CAPACITY} entries wait; a full queue drops the entry at once and the next successful login of
 * that user offers it again. One daemon worker thread (started on the first offer) hashes each entry with the Auth's
 * {@link Passwords}, so the hash runs inside a slot of the shared {@link HashSlots} pool like every other hash. Then,
 * under {@link UserLocks#of(String)}, it re-reads the user and writes the new hash with
 * {@link UserStore#updatePasswordHash(String, String, String)} only when the passwordHash, ver, enabled and roles
 * still equal the copy seen at login; otherwise the rehash is skipped silently (REL-IAM-001 AC-3). The password
 * {@code char[]} of every entry, processed or dropped, is wiped with {@code '\0'}. Failures are logged as WARN and never
 * reach the caller. {@link #stop()} is for the auth onStop hook.
 */
// @req PERF-TOKEN-002
public final class RehashQueue {

    /** How many rehashes may wait. */
    public static final int CAPACITY = 100;

    private static final AtomicInteger QUEUES = new AtomicInteger();

    private final UserStore users;
    private final Passwords passwords;
    private final ArrayBlockingQueue<Entry> queue = new ArrayBlockingQueue<>(CAPACITY);
    private final String threadName = "naru-auth-rehash-" + QUEUES.incrementAndGet();
    private volatile Logger logger;
    private volatile boolean stopped;
    private @Nullable Thread worker;
    private final AtomicInteger accepted = new AtomicInteger();

    /** One queued rehash: the user as read at login and the password, owned by the queue. */
    private static final class Entry {
        final AuthUser seen;
        final char[] password;

        Entry(AuthUser seen, char[] password) {
            this.seen = seen;
            this.password = password;
        }
    }

    /**
     * A queue writing to {@code users} (the decorator-aware store auth writes through) and hashing with
     * {@code passwords} (its hasher, inside its slots); failures go to {@code logger}.
     */
    // @req PERF-TOKEN-002
    public RehashQueue(UserStore users, Passwords passwords, Logger logger) {
        this.users = Objects.requireNonNull(users, "users");
        this.passwords = Objects.requireNonNull(passwords, "passwords");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** Replaces the logger (auth swaps in the app's logger at install). */
    public void logger(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /**
     * Queues the rehash of {@code seenAtLogin} (the row the login verified, with its id) to {@code password}, taking
     * ownership of the array: it is wiped once processed or dropped, so the caller must not use it afterwards. The
     * caller has already released its hash slot; this never blocks. False when the entry was dropped (queue full,
     * stopped, or a user without an id or a hash), in which case the array is already wiped.
     */
    // @req PERF-TOKEN-002
    public boolean offer(AuthUser seenAtLogin, char[] password) {
        if (stopped || seenAtLogin.getId() == null || seenAtLogin.getPasswordHash() == null
                || !queue.offer(new Entry(seenAtLogin, password))) {
            wipe(password);
            return false;
        }
        accepted.incrementAndGet();
        startWorker();
        if (stopped) {
            drain(); // stop() raced with this offer
        }
        return true;
    }

    /** How many entries {@link #offer} accepted so far (queued, processed or not); for the tests. */
    public int accepted() {
        return accepted.get();
    }

    /** How many entries wait (not counting the one being processed). */
    public int size() {
        return queue.size();
    }

    /** The worker thread, or null before the first offer. */
    synchronized @Nullable Thread worker() {
        return worker;
    }

    private synchronized void startWorker() {
        if (worker != null || stopped) {
            return;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                work();
            }
        }, threadName);
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        worker = t;
        t.start();
    }

    private void work() {
        while (!stopped) {
            Entry e;
            try {
                e = queue.take();
            } catch (InterruptedException ie) {
                break;
            }
            try {
                rehash(e);
            } catch (Throwable t) {
                if (stopped) {
                    break; // interrupted by stop()
                }
                String id = e.seen.getId();
                logger.warn("auth: the password rehash of user " + id + " failed and is skipped (it runs again at the "
                        + "next login): " + t);
            } finally {
                wipe(e.password);
            }
        }
        drain();
    }

    private void rehash(Entry e) throws Exception {
        String newHash;
        try {
            newHash = passwords.hash(e.password); // inside a slot of the shared pool
        } finally {
            wipe(e.password);
        }
        String id = Objects.requireNonNull(e.seen.getId());
        ReentrantLock lock = UserLocks.of(id);
        lock.lock();
        try {
            AuthUser now = users.findById(id);
            if (now == null || !unchanged(e.seen, now)) {
                return; // changed since login: the rehash is skipped
            }
            users.updatePasswordHash(id, e.seen.getPasswordHash(), newHash);
        } finally {
            lock.unlock();
        }
    }

    private static boolean unchanged(AuthUser seen, AuthUser now) {
        return Objects.equals(seen.getPasswordHash(), now.getPasswordHash())
                && seen.getTokenVersion() == now.getTokenVersion()
                && seen.isEnabled() == now.isEnabled()
                && seen.getRoles().equals(now.getRoles());
    }

    /** Stops the worker and wipes every waiting entry; later offers are dropped. */
    // @req PERF-TOKEN-002
    public void stop() {
        stopped = true;
        Thread t;
        synchronized (this) {
            t = worker;
        }
        if (t != null) {
            t.interrupt();
        }
        drain();
    }

    private void drain() {
        List<Entry> left = new ArrayList<>();
        queue.drainTo(left);
        for (Entry e : left) {
            wipe(e.password);
        }
    }

    private static void wipe(char[] password) {
        Arrays.fill(password, '\0');
    }
}
