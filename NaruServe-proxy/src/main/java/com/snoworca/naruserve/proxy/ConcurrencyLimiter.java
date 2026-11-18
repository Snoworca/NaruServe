package com.snoworca.naruserve.proxy;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * The in-flight request counter of one {@code .maxConcurrent(n)} call, shared by reference by every {@link Proxy}
 * derived from its result and by every mount of those proxies in any app. A slot is taken with a compare-and-set
 * loop that refuses at the limit and returned with a decrement: no lock and no blocking wait, since waiting for a
 * slot would itself hold a worker thread.
 */
// @req FR-PROXY-019
final class ConcurrencyLimiter {

    private final AtomicInteger counter = new AtomicInteger();
    private final int limit;

    // @req FR-PROXY-019
    ConcurrencyLimiter(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1, got " + limit);
        }
        this.limit = limit;
    }

    int limit() {
        return limit;
    }

    /** The shared counter of in-flight requests. */
    AtomicInteger counter() {
        return counter;
    }

    /** Takes a slot and returns true, or returns false at once when the counter is at the limit. */
    // @req FR-PROXY-019
    boolean tryAcquire() {
        while (true) {
            int current = counter.get();
            if (current >= limit) {
                return false;
            }
            if (counter.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    /** Returns a slot taken by {@link #tryAcquire}; the caller makes sure it returns each slot once. */
    // @req FR-PROXY-019
    void release() {
        counter.decrementAndGet();
    }
}
