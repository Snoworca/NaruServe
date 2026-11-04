package com.snoworca.naruserve.utils.auth.internal;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * R38: the failure-response floor of password login. A failed attempt (one that reached the hash step) is answered no
 * earlier than {@code max(1.25T, elapsed) + jitter} after the request started, where T is the login timing base of
 * {@link HashTiming} (the calibration target, or under {@code HashCost.fixed(n)} one timing of a hash at n taken once
 * at start) and the jitter is uniform in 0..50 ms. The wait is a scheduled task, never a blocked thread: the response
 * is written from the scheduler thread.
 */
// @req SEC-TOKEN-009
public final class FailureFloor {

    /** The monotonic clock of the floor; {@code System::nanoTime} in production. */
    @FunctionalInterface
    public interface Clock {
        /** Nanoseconds of a monotonic clock. */
        long nanoTime();
    }

    /** The jitter source; uniform 0..50 ms in production. */
    @FunctionalInterface
    public interface Jitter {
        /** A jitter in nanoseconds, within 0..{@link #MAX_JITTER_NANOS}. */
        long nanos();
    }

    /** The largest jitter: 50 ms. */
    public static final long MAX_JITTER_NANOS = 50_000_000L;

    private final long timingNanos;
    private final ScheduledExecutorService scheduler;
    private final Clock clock;
    private final Jitter jitter;

    /** The floor of {@code timing} on the production clock and jitter. */
    // @req SEC-TOKEN-009
    public FailureFloor(HashTiming timing, ScheduledExecutorService scheduler) {
        this(timing, scheduler, System::nanoTime,
                () -> ThreadLocalRandom.current().nextLong(MAX_JITTER_NANOS + 1));
    }

    /** The floor of {@code timing} with an injected clock and jitter (tests). */
    // @req SEC-TOKEN-009
    public FailureFloor(HashTiming timing, ScheduledExecutorService scheduler, Clock clock, Jitter jitter) {
        this.timingNanos = timing.timingNanos();
        this.scheduler = scheduler;
        this.clock = clock;
        this.jitter = jitter;
    }

    /** The floor's clock now: the start value of a request for {@link #send}. */
    public long now() {
        return clock.nanoTime();
    }

    /** 1.25T in nanoseconds. */
    public long floorNanos() {
        return timingNanos + timingNanos / 4;
    }

    /**
     * Sends {@code response} at {@code max(1.25T, elapsed) + jitter} after {@code startNanos} (a value of the floor's
     * clock taken when the request started), on the scheduler thread; at once when the scheduler refuses the task.
     */
    // @req SEC-TOKEN-009
    public void send(long startNanos, Runnable response) {
        long elapsed = clock.nanoTime() - startNanos;
        long j = Math.max(0L, Math.min(MAX_JITTER_NANOS, jitter.nanos()));
        long due = Math.max(floorNanos(), elapsed) + j;
        long wait = Math.max(0L, due - elapsed);
        try {
            scheduler.schedule(response, wait, TimeUnit.NANOSECONDS);
        } catch (java.util.concurrent.RejectedExecutionException stopped) {
            response.run(); // the app is stopping: answer now rather than never
        }
    }
}
