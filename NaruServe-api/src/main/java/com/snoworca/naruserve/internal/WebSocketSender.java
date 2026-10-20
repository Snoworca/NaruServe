package com.snoworca.naruserve.internal;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The app-wide WebSocket sender: each session has one slot, a queue bounded in bytes whose writes run one at a time
 * in queue order on one drain task. The executor has up to maxUpgradedConnections threads, the most sessions that can
 * have a drain task at once, so a session with a write in flight holds only its own thread and never delays another
 * session's write. A slot lives from the session's first write until {@link #release}.
 */
// @req FR-WS-005
// @req FR-WS-007
final class WebSocketSender {

    /** Reads the app's bound port when a thread is made; {@code java.util.function.IntSupplier} needs API 24. */
    interface IntSupplier {
        int getAsInt();
    }

    static final long STACK_BYTES = 256 * 1024;
    private static final long KEEP_ALIVE_MILLIS = 60000;
    /** Payload bytes alone cannot bound empty frames or Ping tasks. Includes the write in flight. */
    private static final int MAX_PENDING_TASKS = 1024;

    private final ThreadPoolExecutor pool;
    private final SenderThreads threads;
    private final SafeLogger log;
    private final Map<DefaultWebSocketSession, Slot> slots = new HashMap<>(); // guarded by this

    /** One queued write and the payload bytes it counts. */
    private static final class Write {
        final Runnable task;
        final int bytes;

        Write(Runnable task, int bytes) {
            this.task = task;
            this.bytes = bytes;
        }
    }

    /** All fields guarded by the sender. */
    private static final class Slot {
        final ArrayDeque<Write> queued = new ArrayDeque<>();
        /** Bytes queued plus the bytes of the write in flight. */
        long bytes;
        int pending;
        /** A drain task is submitted or running; the write it holds is in flight, not in {@link #queued}. */
        boolean running;
        /** last() or release() ran: later writes are refused. */
        boolean closed;
        /** release() ran while a write was in flight; the drain drops the slot when it ends. */
        boolean released;

        void discard() {
            for (Write w : queued) {
                bytes -= w.bytes;
                pending--;
            }
            queued.clear();
        }
    }

    /** {@code maxThreads} is the app's maxUpgradedConnections; {@code port} is read when each thread is made. */
    // @req FR-WS-007
    WebSocketSender(int maxThreads, IntSupplier port, SafeLogger log) {
        this.log = Objects.requireNonNull(log, "log");
        threads = new SenderThreads(Objects.requireNonNull(port, "port"));
        pool = new ThreadPoolExecutor(maxThreads, maxThreads, KEEP_ALIVE_MILLIS, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>(), threads);
        pool.allowCoreThreadTimeOut(true);
    }

    /** Queues {@code write} with no byte limit; it starts at once when the slot is idle. */
    // @req FR-WS-005
    void submit(DefaultWebSocketSession session, Runnable write) {
        queue(session, write, 0, Integer.MAX_VALUE);
    }

    /**
     * Queues {@code write}, counting {@code bytes} until it returned. When the slot's bytes plus {@code bytes} would
     * pass {@code limit}, drops it, empties the queue (the write in flight stays counted) and returns false; also false
     * once {@link #last} or {@link #release} ran.
     */
    // @req FR-WS-007
    boolean queue(DefaultWebSocketSession session, Runnable write, int bytes, int limit) {
        Write w = new Write(write, bytes);
        Slot s;
        synchronized (this) {
            s = slot(session);
            if (s.closed) {
                return false;
            }
            if (s.bytes + bytes > limit || s.pending >= MAX_PENDING_TASKS) {
                s.discard();
                return false;
            }
            s.bytes += bytes;
            s.pending++;
            if (s.running) {
                s.queued.add(w);
                return true;
            }
            s.running = true;
        }
        start(session, s, w);
        return true;
    }

    /** Discards the queued writes and appends {@code close} as the slot's final task; later writes are refused. */
    // @req FR-WS-007
    void last(DefaultWebSocketSession session, Runnable close) {
        Write w = new Write(close, 0);
        Slot s;
        synchronized (this) {
            s = slot(session);
            if (s.closed) {
                return;
            }
            s.discard();
            s.closed = true;
            s.pending++; // the final close replaces the queue and must always be deliverable
            if (s.running) {
                s.queued.add(w);
                return;
            }
            s.running = true;
        }
        start(session, s, w);
    }

    /** True when no write of the session is running or queued. */
    // @req FR-WS-007
    synchronized boolean idle(DefaultWebSocketSession session) {
        Slot s = slots.get(session);
        return s == null || !s.running;
    }

    /**
     * Discards the queued writes, then blocks until the write in flight returned. Called once the adapter's read loop
     * ended, so the connection is closed and that write fails at once; an interrupt ends the wait with the flag kept.
     */
    // @req FR-WS-007
    synchronized void awaitIdle(DefaultWebSocketSession session) {
        Slot s = slots.get(session);
        if (s == null) {
            return;
        }
        s.discard();
        while (s.running) {
            try {
                wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * Waits until no write of the session is running or queued, at most until {@code untilNanos}; true when idle by
     * then. An interrupt ends the wait with the flag kept.
     */
    // @req FR-WS-010
    synchronized boolean awaitIdleUntil(DefaultWebSocketSession session, long untilNanos) {
        Slot s = slots.get(session);
        while (s != null && s.running) {
            long left = untilNanos - System.nanoTime();
            if (left <= 0) {
                return false;
            }
            try {
                TimeUnit.NANOSECONDS.timedWait(this, left);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return !s.running;
            }
        }
        return true;
    }

    /**
     * Stops the executor at shutdown: interrupts its threads and waits up to {@code waitMillis} for them to end. A write
     * started later is refused by the executor.
     */
    // @req FR-WS-010
    void shutdownNow(long waitMillis) {
        pool.shutdownNow();
        try {
            if (!pool.awaitTermination(waitMillis, TimeUnit.MILLISECONDS)) {
                log.warn("websocket sender threads still running " + waitMillis + " ms after shutdown");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        synchronized (this) {
            // a drain task shutdownNow dropped never marks its slot idle: do it here, so no awaitIdle waits forever
            for (Slot s : slots.values()) {
                s.discard();
                s.closed = true;
                s.running = false;
            }
            notifyAll();
        }
    }

    /** Drops the slot and anything still queued; a write in flight ends first and then drops the slot itself. */
    // @req FR-WS-007
    synchronized void release(DefaultWebSocketSession session) {
        Slot s = slots.get(session);
        if (s == null) {
            return;
        }
        s.discard();
        s.closed = true;
        if (s.running) {
            s.released = true;
        } else {
            slots.remove(session);
        }
    }

    synchronized int queuedBytes(DefaultWebSocketSession session) {
        Slot s = slots.get(session);
        return s == null ? 0 : (int) s.bytes;
    }

    synchronized int slots() {
        return slots.size();
    }

    ThreadPoolExecutor executor() {
        return pool;
    }

    /** Internal test hook: the stackSize last passed to the Thread constructor; 0 before the first thread. */
    // @req PERF-APP-001
    long requestedStack() {
        return threads.requested;
    }

    SafeLogger log() {
        return log;
    }

    /** Internal test hook: the time an idle sender thread waits before it ends. */
    void keepAlive(long millis) {
        pool.setKeepAliveTime(millis, TimeUnit.MILLISECONDS);
    }

    private Slot slot(DefaultWebSocketSession session) {
        Slot s = slots.get(session);
        if (s == null) {
            s = new Slot();
            slots.put(session, s);
        }
        return s;
    }

    private void start(DefaultWebSocketSession session, Slot s, Write first) {
        try {
            pool.execute(() -> drain(session, s, first));
        } catch (RuntimeException | Error e) {
            synchronized (this) {
                // no drain will run: drop what queued up behind first too, or it would run out of order later
                s.bytes -= first.bytes;
                s.pending--;
                s.discard();
                idled(session, s);
            }
            if (e instanceof RejectedExecutionException) {
                log.debug("websocket write dropped after shutdown: " + e); // the session is closing; sends return
                return;
            }
            throw e;
        }
    }

    /**
     * Runs {@code first}, then each write queued behind it, and marks the slot idle when none is left. An Error drops
     * what was queued and marks the slot idle too, so the session's later writes still run.
     */
    private void drain(DefaultWebSocketSession session, Slot s, Write first) {
        Write next = first;
        try {
            while (next != null) {
                try {
                    next.task.run();
                } catch (RuntimeException e) {
                    log.error("websocket sender task failed", e); // a write logs its own failures; this keeps draining
                }
                synchronized (this) {
                    s.bytes -= next.bytes;
                    s.pending--;
                    next = s.queued.poll();
                    if (next == null) {
                        idled(session, s);
                    }
                }
            }
        } finally {
            if (next != null) {
                synchronized (this) {
                    s.bytes -= next.bytes;
                    s.pending--;
                    s.discard();
                    idled(session, s);
                }
            }
        }
    }

    /** Called with the lock held. */
    private void idled(DefaultWebSocketSession session, Slot s) {
        s.running = false;
        if (s.released && slots.get(session) == s) {
            slots.remove(session);
        }
        notifyAll();
    }

    /** Daemon threads of {@link #STACK_BYTES} named {@code naruserve-<port>-ws-sender-<n>}. */
    private static final class SenderThreads implements ThreadFactory {
        private final AtomicInteger n = new AtomicInteger();
        private final IntSupplier port;
        volatile long requested;

        SenderThreads(IntSupplier port) {
            this.port = port;
        }

        @Override
        public Thread newThread(Runnable r) {
            long stack = STACK_BYTES;
            requested = stack;
            return daemon("naruserve-" + port.getAsInt() + "-ws-sender-" + n.incrementAndGet(), r);
        }
    }

    /** A daemon thread named {@code name} with a {@link #STACK_BYTES} stack. */
    static Thread daemon(String name, Runnable r) {
        Thread t = new Thread(null, r, name, STACK_BYTES);
        t.setDaemon(true);
        return t;
    }
}
