package com.snoworca.naruserve.internal;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The app-wide WebSocket sender: each session has one slot, which runs that session's writes one at a time in submit
 * order on a sender thread. A session with a write in flight holds only its own thread, so it never delays another
 * session's write. FR-WS-007 adds the queue bound, the thread limit and the overflow policy; FR-WS-010 the shutdown.
 */
// @req FR-WS-005
final class WebSocketSender {

    // shortcut: unbounded daemon threads, one per session with a write pending, so the TinyHttpd upgrade limit bounds
    // them; FR-WS-007 replaces this with the fixed limit equal to the WebSocket connection limit.
    private final ThreadPoolExecutor pool = new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS,
            new SynchronousQueue<Runnable>(), new SenderThreads());

    /** Writes waiting behind the running one, per session; a key is present exactly while a write runs. */
    private final Map<DefaultWebSocketSession, ArrayDeque<Runnable>> slots = new HashMap<>(); // guarded by this

    /** Queues {@code write} in the session's slot; it starts at once when the slot is idle. */
    // @req FR-WS-005
    void submit(DefaultWebSocketSession session, Runnable write) {
        synchronized (this) {
            ArrayDeque<Runnable> waiting = slots.get(session);
            if (waiting != null) {
                waiting.add(write);
                return;
            }
            slots.put(session, new ArrayDeque<Runnable>());
        }
        try {
            pool.execute(() -> drain(session, write));
        } catch (RuntimeException | Error e) {
            free(session); // no drain will run, so a stuck slot would swallow every later write
            throw e;
        }
    }

    /**
     * Runs {@code first}, then each write queued behind it, and frees the slot when none is left. An Error frees the
     * slot too, dropping what was queued, so the session's later writes still run.
     */
    private void drain(DefaultWebSocketSession session, Runnable first) {
        boolean freed = false;
        try {
            Runnable next = first;
            while (next != null) {
                try {
                    next.run();
                } catch (RuntimeException e) {
                    // Each write logs its own failure; this only keeps the slot draining.
                }
                synchronized (this) {
                    next = slots.get(session).poll();
                    if (next == null) {
                        slots.remove(session);
                        freed = true;
                    }
                }
            }
        } finally {
            if (!freed) {
                free(session);
            }
        }
    }

    private synchronized void free(DefaultWebSocketSession session) {
        slots.remove(session);
    }

    private static final class SenderThreads implements ThreadFactory {
        private final AtomicInteger n = new AtomicInteger();

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "naruserve-ws-sender-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    }
}
