package com.snoworca.naruserve.common;

import java.util.Objects;

/**
 * Reusable request ownership count. Each retention returns one thread-safe, idempotent release
 * action. The callback observes a transition from a nonzero count to zero; a subsequent retention
 * starts a new cycle. Adapters separately decide whether a terminal request can still be retained.
 *
 * <p>Callbacks run outside the state monitor and their failures cannot interrupt resource cleanup.
 * A new retention may be acquired while an earlier zero-transition callback is running, so an
 * adapter must check its own terminal state and current retention count before recycling input.
 */
// @req FR-APP-023 AC-8
public final class RequestRetentions {
    private final Object state = new Object();
    private final Runnable onReleased;
    private int count;

    public RequestRetentions(Runnable onReleased) {
        this.onReleased = Objects.requireNonNull(onReleased, "onReleased");
    }

    /** Acquire one ownership hold and return its independent, idempotent release action. */
    // @req FR-APP-023 AC-8
    public Runnable retain() {
        synchronized (state) {
            count++;
        }
        return new Runnable() {
            // Guarded by state together with the shared count, including calls from other threads.
            private boolean released;

            @Override
            public void run() {
                boolean last;
                synchronized (state) {
                    if (released) {
                        return;
                    }
                    released = true;
                    last = --count == 0;
                }
                if (last) {
                    try {
                        onReleased.run();
                    } catch (Throwable ignored) {
                        // An observer failure must not poison the count or prevent transport cleanup.
                    }
                }
            }
        };
    }

    /** Whether at least one acquired hold has not yet been released. */
    // @req FR-APP-023 AC-8
    public boolean isRetained() {
        synchronized (state) {
            return count != 0;
        }
    }
}
