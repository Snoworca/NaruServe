package com.snoworca.naruserve.utils.auth.internal;

import com.snoworca.naruserve.utils.auth.AuthUnavailableException;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;

/**
 * The one pool every password hash of an Auth instance runs in: {@code slots} hash threads, a wait queue of
 * {@code queueCapacity} and a wait cap. A request that finds the queue full is refused at once without hashing; a
 * waiter that gets no slot within the cap fails and its work never runs. Both failures are
 * {@link AuthUnavailableException} (503, {@code Retry-After} 1 second). Async callers ({@link #submit}) hold no thread while they wait, so a route
 * registered with {@code methodAsync} keeps the app's workers free.
 */
// @req REL-TOKEN-001
public final class HashSlots {

    /** Receives the outcome of {@link #submit}: a value, or an error. */
    @FunctionalInterface
    public interface Callback<T> {
        /** Called once, on a hash thread or the timer thread. */
        void done(@Nullable T value, @Nullable Throwable error);
    }

    private static final long DEFAULT_WAIT_MILLIS = 5000L;
    private static final Long RETRY_AFTER_SECONDS = 1L;
    /** Set while a hash thread runs work, so a nested {@link #call} reuses that slot instead of waiting for another. */
    private final ThreadLocal<Boolean> inSlot = new ThreadLocal<>();
    private static final AtomicInteger POOLS = new AtomicInteger();

    private final int slots;
    private final int queueCapacity;
    private final long waitMillis;
    private final ThreadPoolExecutor hashers;
    private final ScheduledThreadPoolExecutor timer;
    private final AtomicInteger admitted = new AtomicInteger();
    private volatile boolean closed;

    /** {@code max(1, availableProcessors / 2)}. */
    // @req REL-TOKEN-001
    public static int defaultSlots(int availableProcessors) {
        return Math.max(1, availableProcessors / 2);
    }

    /** {@code slots} slots, a queue of four times that, and a 5-second wait cap. */
    // @req REL-TOKEN-001
    public HashSlots(int slots) {
        this(slots, 4 * slots, DEFAULT_WAIT_MILLIS);
    }

    /** Explicit slots, queue capacity and wait cap in milliseconds. */
    // @req REL-TOKEN-001
    public HashSlots(int slots, int queueCapacity, long waitMillis) {
        if (slots < 1 || queueCapacity < 0 || waitMillis < 1) {
            throw new IllegalArgumentException("hash slots need slots >= 1, queue >= 0 and wait >= 1 ms");
        }
        this.slots = slots;
        this.queueCapacity = queueCapacity;
        this.waitMillis = waitMillis;
        final int pool = POOLS.incrementAndGet();
        final AtomicInteger thread = new AtomicInteger();
        ThreadFactory factory = new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "naru-auth-hash-" + pool + "-" + thread.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
        this.hashers = new ThreadPoolExecutor(slots, slots, 30L, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(),
                factory);
        this.hashers.allowCoreThreadTimeOut(true);
        this.timer = new ScheduledThreadPoolExecutor(1, factory);
        this.timer.setRemoveOnCancelPolicy(true);
    }

    private final AtomicLong submitted = new AtomicLong();

    /** How many requests asked for a slot so far, admitted or refused (each login attempt asks once). */
    public long requested() {
        return submitted.get();
    }

    /** The number of concurrent hash computations. */
    public int slots() {
        return slots;
    }

    /** How many requests may wait for a slot. */
    public int queueCapacity() {
        return queueCapacity;
    }

    /** How long a request may wait for a slot, in milliseconds. */
    public long waitMillis() {
        return waitMillis;
    }

    /**
     * Runs {@code work} in a slot without blocking the caller; the outcome goes to {@code done} exactly once. The
     * callback runs on a hash thread (holding no slot by then) or on the timer thread, so it must not block; a
     * {@link #call} made from inside {@code work} reuses the slot it runs in.
     */
    // @req REL-TOKEN-001
    public <T> void submit(final Callable<T> work, final Callback<T> done) {
        submitted.incrementAndGet();
        if (closed || !admit()) {
            done.done(null, busy(closed ? "the password hash slots are shut down"
                    : "all password hash slots are busy"));
            return;
        }
        final Task<T> task = new Task<>(work, done);
        try {
            task.deadline = timer.schedule(new Runnable() {
                @Override
                public void run() {
                    task.fail("no password hash slot within " + waitMillis + " ms");
                }
            }, waitMillis, TimeUnit.MILLISECONDS);
            hashers.execute(task);
        } catch (RejectedExecutionException e) {
            task.fail("the password hash slots are shut down");
        }
    }

    private boolean admit() {
        for (;;) {
            int now = admitted.get();
            if (now >= slots + queueCapacity) {
                return false;
            }
            if (admitted.compareAndSet(now, now + 1)) {
                return true;
            }
        }
    }

    private AuthUnavailableException busy(String why) {
        return new AuthUnavailableException(AuthUnavailableException.SERVICE_UNAVAILABLE, why + "; retry later",
                RETRY_AFTER_SECONDS, null);
    }

    /** One admitted hash request; its state moves once from waiting to running or failed. */
    private final class Task<T> implements Runnable {
        private final Callable<T> work;
        private final Callback<T> done;
        private final AtomicInteger state = new AtomicInteger(); // 0 waiting, 1 running, 2 failed
        volatile @Nullable ScheduledFuture<?> deadline;

        Task(Callable<T> work, Callback<T> done) {
            this.work = work;
            this.done = done;
        }

        void fail(String why) {
            if (state.compareAndSet(0, 2)) {
                admitted.decrementAndGet();
                done.done(null, busy(why));
            }
        }

        @Override
        public void run() {
            if (!state.compareAndSet(0, 1)) {
                return;
            }
            ScheduledFuture<?> d = deadline;
            if (d != null) {
                d.cancel(false);
            }
            T value = null;
            Throwable error = null;
            inSlot.set(Boolean.TRUE);
            try {
                value = work.call();
            } catch (Throwable e) {
                error = e;
            } finally {
                inSlot.remove();
                admitted.decrementAndGet();
            }
            done.done(value, error);
        }
    }

    /**
     * Runs {@code work} in a slot and waits for it; for synchronous app calls. A full queue or the wait cap throws
     * {@link AuthUnavailableException}; an exception of {@code work} is rethrown.
     */
    // @req REL-TOKEN-001
    public <T> T call(Callable<T> work) throws Exception {
        if (inSlot.get() != null) {
            return work.call(); // a retry or rehash inside a hash already holds this pool's slot
        }
        final CountDownLatch latch = new CountDownLatch(1);
        final Object[] value = new Object[1];
        final Throwable[] error = new Throwable[1];
        submit(work, new Callback<T>() {
            @Override
            public void done(@Nullable T v, @Nullable Throwable e) {
                value[0] = v;
                error[0] = e;
                latch.countDown();
            }
        });
        latch.await();
        Throwable e = error[0];
        if (e instanceof Exception) {
            throw (Exception) e;
        }
        if (e instanceof Error) {
            throw (Error) e;
        }
        if (e != null) {
            throw new IllegalStateException(e);
        }
        @SuppressWarnings("unchecked")
        T result = (T) value[0];
        return result;
    }

    /** Stops the hash and timer threads; every waiting request fails with 503 and later requests are refused. */
    // @req REL-TOKEN-001
    public void shutdown() {
        closed = true;
        for (Runnable r : hashers.shutdownNow()) {
            if (r instanceof Task) {
                ((Task<?>) r).fail("the password hash slots are shut down");
            }
        }
        timer.shutdownNow();
    }

    /**
     * After {@link #shutdown()}: waits up to {@code timeout} for the hashes still running (and their callbacks, which
     * run on the hash threads) to finish; true when they did.
     */
    // @req REL-TOKEN-001
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return hashers.awaitTermination(timeout, unit);
    }
}
