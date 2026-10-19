package com.snoworca.naruserve.tinyhttpd;

import com.snoworca.naruserve.Logger;
import com.snoworca.tinyhttpd.TinyHttpd;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;

/**
 * TinyHttpd's worker threads with its default properties (daemon, 256 KiB stack, uncaught exceptions logged) and
 * the name {@code naruserve-<port>-worker-<n>}, the port read from the server when the thread is created.
 */
// @req FR-ENGINE-006
final class WorkerThreadFactory implements ThreadFactory {

    private static final int STACK_BYTES = 256 * 1024;

    private final AtomicInteger count = new AtomicInteger();
    private final Logger logger;
    // Guarded by itself: the workers not yet ended, so release can join them.
    private final Set<Thread> live = new HashSet<>();
    private volatile @Nullable TinyHttpd server;

    WorkerThreadFactory(Logger logger) {
        this.logger = logger;
    }

    /** The server whose bound port names the workers; set before it starts. */
    // @req FR-ENGINE-006
    void server(TinyHttpd s) {
        server = s;
    }

    // @req FR-ENGINE-006
    @Override
    public Thread newThread(Runnable r) {
        TinyHttpd s = server;
        int port = s == null ? 0 : s.getPort();
        Thread[] self = new Thread[1];
        Thread t = new Thread(null, () -> {
            try {
                r.run();
            } finally {
                synchronized (live) {
                    live.remove(self[0]);
                }
            }
        }, "naruserve-" + port + "-worker-" + count.incrementAndGet(), STACK_BYTES);
        self[0] = t;
        t.setDaemon(true);
        t.setUncaughtExceptionHandler((thread, e) -> logger.error("uncaught exception in " + thread.getName(), e));
        synchronized (live) {
            live.add(t);
        }
        return t;
    }

    /** Joins the workers until {@code deadlineMillis} (currentTimeMillis); returns how many are still alive. */
    // @req FR-ENGINE-006
    int awaitWorkers(long deadlineMillis) throws InterruptedException {
        List<Thread> threads;
        synchronized (live) {
            threads = new ArrayList<>(live);
        }
        int left = 0;
        for (Thread t : threads) {
            long wait = deadlineMillis - System.currentTimeMillis();
            if (wait > 0) {
                t.join(wait);
            }
            if (t.isAlive()) {
                left++;
            }
        }
        return left;
    }
}
