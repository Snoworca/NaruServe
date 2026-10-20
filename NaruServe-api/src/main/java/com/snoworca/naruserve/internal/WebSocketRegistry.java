package com.snoworca.naruserve.internal;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * The reserved and open WebSocket sessions of one app and its closed flag. A session is reserved before the adapter
 * upgrades and removed once its exchange ended; the closing participant sets the flag and closes every session it
 * holds. Lock order: this, then a session's close lock, then the sender.
 */
// @req FR-WS-009
// @req FR-WS-010
final class WebSocketRegistry {

    /** OPEN accepts reservations; CLOSING is the flag set with the close wait running; ENDED is after the wait. */
    // @req FR-WS-010
    enum State {
        OPEN,
        CLOSING,
        ENDED
    }

    private static final long CLOSE_WAIT_NANOS = TimeUnit.SECONDS.toNanos(1);
    private static final long TERMINATE_WAIT_MILLIS = 500;

    private final WebSocketSender sender;
    private final WebSocketIdleScheduler idle;
    private final SafeLogger log;
    // Guarded by this.
    private final Set<DefaultWebSocketSession> sessions = new LinkedHashSet<>();
    private State state = State.OPEN;

    /** Built at freeze with the app's sender and idle scheduler, which {@link #terminate} stops. */
    // @req FR-WS-010
    WebSocketRegistry(WebSocketSender sender, WebSocketIdleScheduler idle) {
        this.sender = Objects.requireNonNull(sender, "sender");
        this.idle = Objects.requireNonNull(idle, "idle");
        this.log = sender.log();
    }

    /** Adds {@code session}; false, adding nothing, once the closed flag is set. */
    // @req FR-WS-009
    synchronized boolean reserve(DefaultWebSocketSession session) {
        if (state != State.OPEN) {
            return false;
        }
        sessions.add(session);
        return true;
    }

    // @req FR-WS-009
    synchronized void remove(DefaultWebSocketSession session) {
        if (sessions.remove(session)) {
            notifyAll(); // a close wait may be waiting for it
        }
    }

    synchronized int size() {
        return sessions.size();
    }

    synchronized State state() {
        return state;
    }

    /**
     * The before-wait shutdown participant. Sets the flag and takes the snapshot in one step, hands close 1001 to every
     * opened session (its last task: the queue is discarded, later sends are refused), then waits until each snapshot
     * session was removed or has an opened, idle sender slot, at most until the earlier of 1 s from now and
     * {@code deadlineNanos}. Sets ENDED and aborts every snapshot session whose slot is still busy.
     */
    // @req FR-WS-010
    void closeAll(long deadlineNanos) {
        long now = System.nanoTime();
        // compared as differences, since nanoTime may overflow
        long until = deadlineNanos - now < CLOSE_WAIT_NANOS ? deadlineNanos : now + CLOSE_WAIT_NANOS;
        List<DefaultWebSocketSession> snapshot;
        synchronized (this) {
            if (state != State.OPEN) {
                return;
            }
            state = State.CLOSING;
            snapshot = new ArrayList<>(sessions);
        }
        try {
            for (DefaultWebSocketSession s : snapshot) {
                try {
                    s.closeBy(1001, "server shutdown"); // a session not yet opened does this itself in onOpen
                } catch (RuntimeException e) {
                    log.error("websocket " + s.requestId() + " shutdown close failed", e);
                }
            }
            for (DefaultWebSocketSession s : snapshot) {
                if (!awaitDone(s, until)) {
                    break; // out of time: the rest are checked below
                }
            }
        } finally {
            // the bound holds whatever failed above: ENDED, then the aborts
            synchronized (this) {
                state = State.ENDED;
            }
            for (DefaultWebSocketSession s : snapshot) {
                if (!sender.idle(s)) {
                    s.abort(); // no-op without a channel
                }
            }
        }
    }

    /** True once {@code s} was removed, or opened with an idle slot, before {@code untilNanos}. */
    private boolean awaitDone(DefaultWebSocketSession s, long untilNanos) {
        boolean opened;
        synchronized (this) {
            while (sessions.contains(s) && !s.opened()) {
                long left = untilNanos - System.nanoTime();
                if (left <= 0) {
                    return false;
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(this, left); // remove and the onOpen state read notify
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            opened = sessions.contains(s);
        }
        return !opened || sender.awaitIdleUntil(s, untilNanos);
    }

    /**
     * The force-close participant, after the adapter stop: stops the sender and idle executors, waiting up to 500 ms
     * each for their threads, then forgets every session.
     */
    // @req FR-WS-010
    void terminate() {
        synchronized (this) {
            state = State.ENDED; // first, so no session opening from here on starts an idle watch
        }
        sender.shutdownNow(TERMINATE_WAIT_MILLIS);
        idle.shutdownNow(TERMINATE_WAIT_MILLIS);
        synchronized (this) {
            sessions.clear();
            notifyAll();
        }
    }
}
