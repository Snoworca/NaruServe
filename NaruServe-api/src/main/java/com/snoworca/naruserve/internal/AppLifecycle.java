package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.AppState;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The one state machine of an app and its one configuration guard. Per app instance, no static state. The start
 * gate latch is released by {@link #markRunning()} or {@link #fail()}; requests held on it re-read the state.
 * shortcut: no pending stop or termination wait; the FR-APP-010..FR-APP-012 SDS adds them.
 */
// @req FR-APP-002
// @req FR-APP-009
final class AppLifecycle {

    private final AtomicReference<AppState> state = new AtomicReference<>(AppState.NEW);
    private final CountDownLatch startGate = new CountDownLatch(1);
    private final AtomicInteger held = new AtomicInteger();
    private volatile boolean frozen;

    /** Throws {@code "<method> not allowed in state <state>"} unless NEW with the route table not frozen. */
    // @req FR-APP-002
    void requireConfigurable(String method) {
        AppState s = state.get();
        if (s != AppState.NEW) {
            throw new IllegalStateException(method + " not allowed in state " + s);
        }
        if (frozen) {
            throw new IllegalStateException(method + " not allowed: the route table is frozen (state " + s + ")");
        }
    }

    /** Records the route-table freeze; the guard refuses from then on. */
    // @req FR-APP-002
    void markFrozen() {
        frozen = true;
    }

    /** NEW to STARTING for the one compareAndSet winner; everyone else gets the current state in the message. */
    // @req FR-APP-009
    void beginStart() {
        if (!state.compareAndSet(AppState.NEW, AppState.STARTING)) {
            throw new IllegalStateException("listen() not allowed in state " + state.get());
        }
    }

    // @req FR-APP-002
    // @req FR-APP-009
    AppState state() {
        return state.get();
    }

    /** Enters RUNNING and releases requests held on the start gate. */
    // @req FR-APP-009
    void markRunning() {
        state.set(AppState.RUNNING);
        startGate.countDown();
    }

    /** Enters FAILED and releases the start gate as failed. */
    // @req FR-APP-009
    void fail() {
        state.set(AppState.FAILED);
        startGate.countDown();
    }

    /** RUNNING to STOPPING; true only for the caller that runs the stop. */
    // @req FR-APP-002
    boolean beginStop() {
        return state.compareAndSet(AppState.RUNNING, AppState.STOPPING);
    }

    /** NEW to TERMINATED by compareAndSet, so a concurrent listen either wins first or is refused. */
    // @req FR-APP-002
    boolean terminateIfNew() {
        if (!state.compareAndSet(AppState.NEW, AppState.TERMINATED)) {
            return false;
        }
        startGate.countDown();
        return true;
    }

    /** Enters TERMINATED and releases the start gate. */
    // @req FR-APP-002
    void terminate() {
        state.set(AppState.TERMINATED);
        startGate.countDown();
    }

    /** Requests currently held on the start gate. */
    // @req FR-APP-009
    int heldOnStartGate() {
        return held.get();
    }

    /** Blocks while the start gate is closed; interruption keeps the flag and returns. */
    // @req FR-APP-009
    void awaitStartGate() {
        held.incrementAndGet();
        try {
            startGate.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            held.decrementAndGet();
        }
    }
}
