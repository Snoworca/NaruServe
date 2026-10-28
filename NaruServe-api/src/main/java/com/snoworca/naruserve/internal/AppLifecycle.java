package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.AppState;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The one state machine of an app and its one configuration guard. Per app instance, no static state. The start
 * gate latch is released by {@link #markRunning()}, {@link #fail()} or a move to TERMINATED; requests held on it
 * re-read the state. A
 * second latch is released on TERMINATED for {@link #awaitTerminated}. The pending stop and the move to RUNNING
 * share this object's lock, so a stop recorded during STARTING is never lost to a concurrent markRunning.
 */
// @req FR-APP-002
// @req FR-APP-009
// @req FR-APP-010
// @req FR-APP-011
// @req FR-APP-012
final class AppLifecycle {

    private final AtomicReference<AppState> state = new AtomicReference<>(AppState.NEW);
    private final CountDownLatch startGate = new CountDownLatch(1);
    private final CountDownLatch terminated = new CountDownLatch(1);
    // Released when the listen() that won beginStart returns or throws.
    private final CountDownLatch listenEnded = new CountDownLatch(1);
    // Set on a thread while it dispatches one of this app's requests (per app, so no static state).
    private final ThreadLocal<Boolean> requestThread = new ThreadLocal<>();
    private final AtomicInteger held = new AtomicInteger();
    private volatile boolean frozen;
    // Written under this; read without it by the gate and by listen.
    private volatile boolean pendingStop;

    private final java.util.Set<HttpExecution> executions=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<HttpExecution,Boolean>());
    private boolean httpClosed;
    // @req FR-APP-023 AC-3 AC-5 AC-6
    boolean registerHttp(HttpExecution execution) {
        synchronized(executions) { if(httpClosed)return false;executions.add(execution);return true; }
    }
    void releaseHttp(HttpExecution execution) {
        synchronized(executions) { executions.remove(execution);executions.notifyAll(); }
    }
    void awaitHttp(long deadline) {
        synchronized(executions) {
            while(!executions.isEmpty()) {
                long left=deadline-System.nanoTime();if(left<=0)return;
                try { TimeUnit.NANOSECONDS.timedWait(executions,left); }
                catch(InterruptedException interrupted) { Thread.currentThread().interrupt();return; }
            }
        }
    }
    void cancelHttp() {
        java.util.List<HttpExecution> pending;
        synchronized(executions) { httpClosed=true;pending=new java.util.ArrayList<>(executions); }
        for(HttpExecution execution:pending)execution.cancel();
    }

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

    /**
     * Enters RUNNING and releases requests held on the start gate, unless a stop is pending: then returns false and
     * the state stays STARTING for listen's failure path.
     */
    // @req FR-APP-009
    // @req FR-APP-012
    synchronized boolean markRunning() {
        if (pendingStop) {
            return false;
        }
        state.set(AppState.RUNNING);
        startGate.countDown();
        return true;
    }

    /**
     * Records a stop requested during STARTING; listen checks it between onStart callbacks, before bind and at the
     * move to RUNNING. A caller that finds RUNNING afterwards lost the race to markRunning and stops normally.
     */
    // @req FR-APP-012
    synchronized void requestPendingStop() {
        pendingStop = true;
    }

    /** True once {@link #requestPendingStop()} ran. */
    // @req FR-APP-012
    boolean pendingStop() {
        return pendingStop;
    }

    /** Waits for TERMINATED; false on timeout. An interrupt keeps the flag and returns false. */
    // @req FR-APP-012
    boolean awaitTerminated(long timeout, TimeUnit unit) {
        try {
            return terminated.await(timeout, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Marks the calling thread as running one of this app's requests until {@link #exitRequest()}. */
    // @req FR-APP-011
    void enterRequest() {
        requestThread.set(Boolean.TRUE);
    }

    // @req FR-APP-011
    void exitRequest() {
        requestThread.remove();
    }

    /** Restores an existing request scope, including nested dispatch and deferred reentry. */
    // @req FR-APP-023 AC-4
    void withRequest(Runnable work) {
        Boolean previous=requestThread.get(); requestThread.set(Boolean.TRUE);
        try { work.run(); }
        finally { if(previous==null)requestThread.remove();else requestThread.set(previous); }
    }

    // @req FR-APP-021 AC-4; CON-APP-010 AC-3
    void requireHttpRegistration(String method) {
        AppState s=state.get();
        if(s==AppState.STOPPING || s==AppState.TERMINATED)
            throw new IllegalStateException(method+" not allowed in state "+s);
    }

    /** True on a thread inside one of this app's requests; a shutdown() there must not wait for that request. */
    // @req FR-APP-011
    boolean onRequestThread() {
        return requestThread.get() != null;
    }

    /** Called from the finally of the listen() that won beginStart. */
    // @req FR-APP-012
    void markListenEnded() {
        listenEnded.countDown();
    }

    /** True once {@link #markListenEnded()} ran. */
    // @req FR-APP-012
    boolean listenEnded() {
        return listenEnded.getCount() == 0;
    }

    /** Waits for {@link #markListenEnded()}; false on timeout. An interrupt keeps the flag and returns false. */
    // @req FR-APP-012
    boolean awaitListenEnded(long timeout, TimeUnit unit) {
        try {
            return listenEnded.await(timeout, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Enters FAILED and releases the start gate as failed. */
    // @req FR-APP-009
    // @req FR-APP-010
    void fail() {
        state.set(AppState.FAILED);
        startGate.countDown();
    }

    /** RUNNING to STOPPING; true only for the caller that runs the stop. */
    // @req FR-APP-002
    // @req FR-APP-011
    boolean beginStop() {
        return state.compareAndSet(AppState.RUNNING, AppState.STOPPING);
    }

    /** NEW to TERMINATED by compareAndSet, so a concurrent listen either wins first or is refused. */
    // @req FR-APP-002
    // @req FR-APP-012
    boolean terminateIfNew() {
        if (!state.compareAndSet(AppState.NEW, AppState.TERMINATED)) {
            return false;
        }
        startGate.countDown();
        terminated.countDown();
        return true;
    }

    /** Enters TERMINATED and releases the start gate and every {@link #awaitTerminated} waiter. */
    // @req FR-APP-002
    // @req FR-APP-012
    void terminate() {
        state.set(AppState.TERMINATED);
        startGate.countDown();
        terminated.countDown();
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
