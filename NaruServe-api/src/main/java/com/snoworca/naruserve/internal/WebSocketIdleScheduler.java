package com.snoworca.naruserve.internal;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * The idle timeouts of one app's WebSocket sessions, all checked on one scheduler thread. The thread never writes to a
 * socket: an idle session gets close 1001 through its sender slot, and one check 1 s later aborts the socket when that
 * close has not returned. Pings are TinyHttpd's job, so nothing here sends one.
 */
// @req FR-WS-008
final class WebSocketIdleScheduler {

    private static final long ABORT_AFTER_MILLIS = 1000;

    private final ScheduledThreadPoolExecutor exec;
    private final SafeLogger log;
    private final Map<DefaultWebSocketSession, Watch> watches = new HashMap<>(); // guarded by this
    private volatile long requestedStack;

    /** Guarded by the scheduler. */
    private static final class Watch {
        final long idleNanos;
        ScheduledFuture<?> next;
        /** The close was handed over; only the abort check is left. */
        boolean closing;

        Watch(long idleNanos) {
            this.idleNanos = idleNanos;
        }
    }

    /** The thread, a daemon named {@code naruserve-<port>-ws-idle}, is made on the first schedule. */
    // @req FR-WS-008
    WebSocketIdleScheduler(WebSocketSender.IntSupplier port, SafeLogger log) {
        Objects.requireNonNull(port, "port");
        this.log = Objects.requireNonNull(log, "log");
        exec = new ScheduledThreadPoolExecutor(1, r -> {
            long stack = WebSocketSender.STACK_BYTES;
            requestedStack = stack;
            Thread t = new Thread(null, r, "naruserve-" + port.getAsInt() + "-ws-idle", stack);
            t.setDaemon(true);
            return t;
        });
        exec.setRemoveOnCancelPolicy(true);
    }

    /** Internal test hook: the stackSize last passed to the Thread constructor; 0 before the first thread. */
    // @req PERF-APP-001
    long requestedStack() {
        return requestedStack;
    }

    /** Schedules the session's first idle check {@code idleMillis} from now; called from onOpen. */
    // @req FR-WS-008
    synchronized void watch(DefaultWebSocketSession session, long idleMillis) {
        Watch w = new Watch(TimeUnit.MILLISECONDS.toNanos(idleMillis));
        watches.put(session, w);
        w.next = exec.schedule(() -> check(session), idleMillis, TimeUnit.MILLISECONDS);
    }

    /** Cancels the session's pending check; called from onClose. */
    // @req FR-WS-008
    synchronized void unwatch(DefaultWebSocketSession session) {
        Watch w = watches.remove(session);
        if (w != null && w.next != null) {
            w.next.cancel(false);
        }
    }

    /**
     * Hands close (1001, "idle timeout") to the session's sender slot once no text or binary message went either way
     * for the idle timeout, else checks again when the timeout would be reached.
     */
    // @req FR-WS-008
    void check(DefaultWebSocketSession session) {
        try {
            synchronized (this) {
                Watch w = watches.get(session);
                if (w == null || w.closing) {
                    return;
                }
                long left = w.idleNanos - (System.nanoTime() - session.lastActivityNanos());
                if (left > 0) {
                    w.next = exec.schedule(() -> check(session), left, TimeUnit.NANOSECONDS);
                    return;
                }
                w.closing = true;
                w.next = exec.schedule(() -> abortUnlessClosed(session), ABORT_AFTER_MILLIS, TimeUnit.MILLISECONDS);
            }
            session.closeBy(1001, "idle timeout"); // outside this lock: it takes the session's and the sender's
        } catch (RuntimeException e) {
            log.error("websocket idle check failed", e);
        }
    }

    /** Cancels every check and ends the thread, waiting up to {@code waitMillis} for it; called at shutdown. */
    // @req FR-WS-010
    void shutdownNow(long waitMillis) {
        synchronized (this) {
            watches.clear();
        }
        exec.shutdownNow();
        try {
            if (!exec.awaitTermination(waitMillis, TimeUnit.MILLISECONDS)) {
                log.warn("websocket idle thread still running " + waitMillis + " ms after shutdown");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Aborts the socket when the session's close, or a write before it, has still not returned. */
    private void abortUnlessClosed(DefaultWebSocketSession session) {
        try {
            synchronized (this) {
                if (watches.remove(session) == null) {
                    return;
                }
            }
            if (!session.sender().idle(session)) {
                session.abort();
            }
        } catch (RuntimeException e) {
            log.error("websocket idle abort failed", e);
        }
    }
}
