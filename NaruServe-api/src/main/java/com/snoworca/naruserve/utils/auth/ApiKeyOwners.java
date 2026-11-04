package com.snoworca.naruserve.utils.auth;

import com.snoworca.naruserve.utils.auth.apikey.ApiKeyStore;
import com.snoworca.naruserve.utils.auth.user.AuthUser;
import com.snoworca.naruserve.utils.auth.internal.UserLocks;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;

/**
 * The per-owner side of API keys inside one node (00 R47 (2) 27th–29th editions): the in-node key owner lock, the
 * {@code SINGLE_INSTANCE} future-key deletion before every auth-triggered {@code ver++} and before every issuance, the
 * in-memory pending marker a failed deletion leaves, and the off-request deletions the request path schedules (stale
 * keys, invariant-violating keys, pending re-deletes). Lock order is one way: an auth-triggered {@code ver++} takes the
 * key owner lock while the R37 user write lock of that user is already held; issuance takes the key owner lock alone
 * and never the user lock inside it. Under {@code MULTI_INSTANCE} no future-key or invariant deletion runs and no
 * marker is ever made: a lagging replica makes a valid key look like a future key, and deleting it would lose it (the
 * deletion of stale keys, whose ownerVer is below a ver the store once held, still runs).
 */
// @req REL-APIKEY-003
// @req REL-APIKEY-004
// @req SEC-APIKEY-006
// @req REL-APIKEY-002
final class ApiKeyOwners {

    /** The longest wait for the owner lock before answering 503: 5 seconds, like a hash slot. */
    static final long LOCK_WAIT_MILLIS = 5_000L;

    private static final ReentrantLock[] LOCKS = new ReentrantLock[64];

    static {
        for (int i = 0; i < LOCKS.length; i++) {
            LOCKS[i] = new ReentrantLock();
        }
    }

    /** Sees every key owner lock acquisition and whether the same thread then held the user write lock; for tests. */
    @FunctionalInterface
    interface LockListener {
        void acquired(String ownerId, boolean userLockHeld);
    }

    /** A test seam run by a marker retry after it chose the owner and before it takes the owner lock. */
    @FunctionalInterface
    interface RetryHook {
        void beforeLock(String ownerId);
    }

    /** One store write done under the key owner lock. */
    @FunctionalInterface
    interface Write<T> {
        T run() throws Exception;
    }

    private final Auth auth;
    /** The pending markers: owner id to the ver read before the {@code ver++} whose future-key deletion failed. */
    private final ConcurrentHashMap<String, Long> markers = new ConcurrentHashMap<>();
    /** The off-request deletions queued and not started yet, so a burst of requests queues each one once. */
    private final ConcurrentHashMap<String, Boolean> queued = new ConcurrentHashMap<>();
    /** The pending stale-key deletions: owner id to the largest ver observed while the deletion waits to start. */
    private final ConcurrentHashMap<String, Long> staleVers = new ConcurrentHashMap<>();
    /** One once-a-minute ERROR limiter per kind: the ver++ path, and the off-request jobs. */
    private final AtomicLong bumpErrorAt = new AtomicLong(Long.MIN_VALUE);
    private final AtomicLong jobErrorAt = new AtomicLong(Long.MIN_VALUE);
    private volatile @Nullable Executor deletions;
    private volatile @Nullable LockListener listener;
    private volatile @Nullable RetryHook retryHook;

    ApiKeyOwners(Auth auth) {
        this.auth = auth;
    }

    /** Replaces the executor of the off-request deletions (the auth background executor by default); for tests. */
    void deletions(Executor executor) {
        this.deletions = executor;
    }

    void lockListener(@Nullable LockListener l) {
        this.listener = l;
    }

    void retryHook(@Nullable RetryHook h) {
        this.retryHook = h;
    }

    /** The pending marker of {@code ownerId}: the old ver, or null. */
    @Nullable Long marker(String ownerId) {
        return markers.get(ownerId);
    }

    /** Places a marker as a failed deletion before a {@code ver++} would; for tests. */
    void mark(String ownerId, long oldVer) {
        markers.put(ownerId, oldVer);
    }

    /** True when the current thread holds the key owner lock of {@code ownerId}. */
    static boolean heldByCurrentThread(String ownerId) {
        return lockOf(ownerId).isHeldByCurrentThread();
    }

    private static ReentrantLock lockOf(String ownerId) {
        return LOCKS[(ownerId.hashCode() & 0x7fffffff) % LOCKS.length];
    }

    /** True when deletions run: keys on and {@code SINGLE_INSTANCE}. */
    private boolean single() {
        return auth.apiKeyStore() != null && auth.deployment() == Deployment.SINGLE_INSTANCE;
    }

    /** Takes the owner lock, waiting at most 5 seconds; 503 when it stays taken. */
    void lock(String ownerId) {
        ReentrantLock lock = lockOf(ownerId);
        boolean held;
        try {
            held = lock.tryLock(LOCK_WAIT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            held = false;
        }
        if (!held) {
            throw new AuthUnavailableException("another API key operation of this owner did not finish in time");
        }
        LockListener l = listener;
        if (l != null) {
            l.acquired(ownerId, UserLocks.of(ownerId).isHeldByCurrentThread());
        }
    }

    void unlock(String ownerId) {
        lockOf(ownerId).unlock();
    }

    /**
     * An auth-triggered {@code ver++} of {@code ownerId}, called while the R37 user write lock is held: under
     * {@code SINGLE_INSTANCE} inside the key owner lock, first the retry of a present marker, then (when there was none
     * or the retry cleared it) {@code deleteFutureByOwner(owner, ver)} with the ver re-read now, then {@code write}. A
     * failed deletion logs ERROR (once a minute), leaves or keeps the marker, and never stops the {@code ver++}; when
     * the owner lock stays taken past its wait, the {@code ver++} still runs and a marker at the re-read ver covers the
     * deletion that could not run. A failing owner re-read fails the {@code ver++} (503) rather than raising ver
     * without a deletion or a marker.
     */
    // @req REL-APIKEY-003
    // @req REL-APIKEY-004
    // @req REL-APIKEY-002
    <T> T bump(String ownerId, Write<T> write) throws Exception {
        T out = bumpInLock(ownerId, write);
        if (!Boolean.FALSE.equals(out)) {
            afterBump(ownerId);
        }
        return out;
    }

    /**
     * After an auth-triggered {@code ver++} that applied (00 R47 (6) dead keys deleted after ver++): when the owner,
     * re-read now, holds a key whose ownerVer is below the new ver, {@code deleteStaleByOwner(owner, ver)} is scheduled
     * off the request path through the per-owner dedupe of {@link #scheduleStale}. A failing read only logs; the
     * {@code ver++} already happened and the next request meeting such a key schedules the deletion again.
     */
    // @req REL-APIKEY-002
    private void afterBump(String ownerId) {
        ApiKeyStore keys = auth.apiKeyStore();
        if (keys == null) {
            return;
        }
        try {
            AuthUser u = auth.userStore().findById(ownerId);
            if (u == null) {
                return;
            }
            long ver = u.getTokenVersion();
            for (com.snoworca.naruserve.utils.auth.apikey.StoredApiKey k : keys.listByOwner(ownerId)) {
                if (k.getOwnerVer() < ver) {
                    scheduleStale(ownerId, ver);
                    return;
                }
            }
        } catch (Exception e) {
            error(jobErrorAt, "finding the stale API keys of user " + ownerId + " after a ver++ failed; they are "
                    + "deleted when a request meets one", e);
        }
    }

    private <T> T bumpInLock(String ownerId, Write<T> write) throws Exception {
        if (!single()) {
            return write.run();
        }
        try {
            lock(ownerId);
        } catch (AuthUnavailableException busy) {
            // no deletion can run: mark at the ver read now, before the write, without the lock. That is safe because
            // the marker is put before the ver++ (a key issued at the new ver carries ownerVer = marker + 1 only after
            // an issuance that re-checks the marker under the lock and deletes first), and a retry removes it only
            // when the value is unchanged
            AuthUser u = auth.userStore().findById(ownerId); // a store failure here fails the ver++ with 503
            if (u != null) {
                markers.putIfAbsent(ownerId, u.getTokenVersion());
            }
            error(bumpErrorAt, "the API key owner lock of user " + ownerId + " stayed taken; the ver++ goes on and a "
                    + "pending marker rejects its future keys until a retry succeeds", busy);
            return write.run();
        }
        try {
            ApiKeyStore keys = auth.apiKeyStore();
            if (keys != null && !retryMarker(keys, ownerId)) {
                AuthUser u = auth.userStore().findById(ownerId); // a store failure here fails the ver++ with 503
                if (u != null) {
                    long ver = u.getTokenVersion();
                    try {
                        keys.deleteFutureByOwner(ownerId, ver);
                    } catch (Exception e) {
                        error(bumpErrorAt, "the future-key deletion before a ver++ of user " + ownerId + " failed; the "
                                + "ver++ goes on and a pending marker rejects those keys until a retry succeeds", e);
                        markers.putIfAbsent(ownerId, ver);
                    }
                }
            }
            return write.run();
        } finally {
            unlock(ownerId);
        }
    }

    /**
     * The retry of a present marker under the held owner lock: true when one exists and its deletion failed again
     * (the marker keeps its old ver), false when there was none or the retry cleared it.
     */
    private boolean retryMarker(ApiKeyStore keys, String ownerId) {
        Long old = markers.get(ownerId);
        if (old == null) {
            return false;
        }
        try {
            keys.deleteFutureByOwner(ownerId, old);
            markers.remove(ownerId, old);
            return false;
        } catch (Exception e) {
            error(bumpErrorAt, "the retried future-key deletion of user " + ownerId + " failed; its pending marker "
                    + "stays", e);
            return true;
        }
    }

    /**
     * Before an issuance, inside the owner lock the caller holds: under {@code SINGLE_INSTANCE} a present marker's
     * deletion is retried first, then {@code deleteFutureByOwner(owner, ver)} runs with the ver re-read now (it only
     * selects what to delete and never becomes the new key's ownerVer); a failure of either is 503 and inserts nothing.
     */
    // @req REL-APIKEY-003
    // @req REL-APIKEY-004
    void beforeIssue(String ownerId) {
        if (!single()) {
            return;
        }
        ApiKeyStore keys = auth.apiKeyStore();
        if (keys == null) {
            return;
        }
        try {
            Long old = markers.get(ownerId);
            if (old != null) {
                keys.deleteFutureByOwner(ownerId, old);
                markers.remove(ownerId, old);
            }
            AuthUser u = auth.userStore().findById(ownerId);
            if (u != null) {
                keys.deleteFutureByOwner(ownerId, u.getTokenVersion());
            }
        } catch (AuthUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new AuthUnavailableException("the future-key deletion before an issuance failed", e);
        }
    }

    /**
     * The pending-marker check of the key procedure (after the expiry, before the owner read): true when a marker of
     * the owner rejects a key of {@code ownerVer}, which also schedules one re-delete off the request path.
     */
    // @req REL-APIKEY-004
    boolean rejects(String ownerId, long ownerVer) {
        if (!single()) {
            return false;
        }
        Long old = markers.get(ownerId);
        if (old == null || ownerVer <= old) {
            return false;
        }
        schedule("r|" + ownerId, () -> retry(ownerId));
        return true;
    }

    /** A marker retry: the owner lock, the marker re-read inside it, the deletion, the clear; nothing without one. */
    // @req REL-APIKEY-004
    void retry(String ownerId) {
        RetryHook h = retryHook;
        if (h != null) {
            h.beforeLock(ownerId);
        }
        ApiKeyStore keys = auth.apiKeyStore();
        if (keys == null) {
            return;
        }
        try {
            lock(ownerId);
        } catch (AuthUnavailableException busy) {
            return; // the next request, issuance, ver++ or cleanup run retries it
        }
        try {
            retryMarker(keys, ownerId);
        } finally {
            unlock(ownerId);
        }
    }

    /** The R42 cleanup run: every owner with a marker is retried, each re-reading its marker inside its lock. */
    // @req REL-APIKEY-004
    void cleanupRun() {
        for (String ownerId : new ArrayList<>(((java.util.Map<String, Long>) markers).keySet())) { // API 21: no KeySetView
            retry(ownerId);
        }
    }

    /**
     * User deletion (FR-IAM-008), called while the user write lock is held: inside the key owner lock, {@code keys}
     * deletes every key of the owner, then the marker is cleared, then {@code row} deletes the user row; an issuance
     * waiting for the lock then finds no owner, so no key outlives the row.
     */
    // @req FR-APIKEY-008
    void deleteKeys(String ownerId, Write<?> keys, Write<?> row) throws Exception {
        lock(ownerId);
        try {
            keys.run();
            markers.remove(ownerId);
            row.run();
        } finally {
            unlock(ownerId);
        }
    }

    /**
     * A stale key (ownerVer below the owner's ver) met by a request, or a {@code ver++} that left stale keys:
     * {@code deleteStaleByOwner(owner, ver)} off the request path, at most one pending per owner (00 R47 (2) 31st
     * edition). A deletion already pending for the owner only raises its ver argument to the largest value observed;
     * the owner leaves the pending set when the deletion starts, so a stale key met after that schedules a new one.
     * Because {@code ver} only grows, the largest observed ver is one the store held, and the store deletes only keys
     * whose ownerVer is strictly below it.
     */
    // @req SEC-APIKEY-006
    // @req REL-APIKEY-002
    void scheduleStale(final String ownerId, final long ver) {
        final ApiKeyStore keys = auth.apiKeyStore();
        if (keys == null) {
            return;
        }
        for (;;) { // API 21: no ConcurrentHashMap.merge
            Long pending = staleVers.get(ownerId);
            if (pending == null) {
                if (staleVers.putIfAbsent(ownerId, ver) == null) {
                    break;
                }
            } else if (pending >= ver || staleVers.replace(ownerId, pending, ver)) {
                return; // the pending deletion runs with the largest ver
            }
        }
        Executor ex = deletions;
        try {
            (ex != null ? ex : auth.adminExecutor()).execute(() -> {
                Long v = staleVers.remove(ownerId);
                if (v == null) {
                    return;
                }
                try {
                    keys.deleteStaleByOwner(ownerId, v);
                } catch (Exception e) {
                    error(jobErrorAt, "deleting the stale API keys of user " + ownerId + " failed; it runs again on "
                            + "their next use", e);
                }
            });
        } catch (RejectedExecutionException e) {
            staleVers.remove(ownerId);
            auth.log().warn("auth: an off-request API key deletion was dropped; the background queue is full or "
                    + "stopped");
        }
    }

    /** The ver a pending stale-key deletion of {@code ownerId} will use, or null when none is pending; for tests. */
    @Nullable Long pendingStale(String ownerId) {
        return staleVers.get(ownerId);
    }

    /**
     * An invariant-violating key under {@code SINGLE_INSTANCE}: off the request path, inside the owner lock, the owner's
     * ver is re-read from the store and {@code deleteById} runs only when the key's ownerVer is still above it.
     */
    // @req SEC-APIKEY-006
    void scheduleDelete(final String keyId, final String ownerId, final long keyOwnerVer) {
        final ApiKeyStore keys = auth.apiKeyStore();
        if (keys == null || !single()) {
            return;
        }
        schedule("d|" + keyId, () -> {
            try {
                lock(ownerId);
            } catch (AuthUnavailableException busy) {
                return; // the next use of the key schedules it again
            }
            try {
                AuthUser u = auth.userStore().findById(ownerId);
                if (u == null || keyOwnerVer > u.getTokenVersion()) {
                    keys.deleteById(keyId);
                }
            } catch (Exception e) {
                error(jobErrorAt, "deleting an API key whose ownerVer is above its owner's ver failed; it runs again "
                        + "on its next use", e);
            } finally {
                unlock(ownerId);
            }
        });
    }

    private void schedule(final String what, final Runnable job) {
        if (queued.putIfAbsent(what, Boolean.TRUE) != null) {
            return;
        }
        Executor ex = deletions;
        try {
            (ex != null ? ex : auth.adminExecutor()).execute(() -> {
                queued.remove(what);
                job.run();
            });
        } catch (RejectedExecutionException e) {
            queued.remove(what);
            auth.log().warn("auth: an off-request API key deletion was dropped; the background queue is full or "
                    + "stopped");
        }
    }

    /** ERROR at most once a minute of {@link com.snoworca.naruserve.utils.TimeSource} time per limiter. */
    private void error(AtomicLong limiter, String message, @Nullable Exception e) {
        long now = auth.time().nowMillis();
        long last = limiter.get();
        if ((last == Long.MIN_VALUE || now - last >= 60_000L || now < last) && limiter.compareAndSet(last, now)) {
            if (e == null) {
                auth.log().error("auth: " + message + " (logged at most once a minute)");
            } else {
                auth.log().error("auth: " + message + " (logged at most once a minute)", e);
            }
        }
    }

    @Override
    public String toString() {
        return "ApiKeyOwners{markers=" + markers.size() + "}";
    }
}
