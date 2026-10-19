package com.snoworca.naruserve.tinyhttpd;

import java.util.concurrent.CountDownLatch;
import org.jspecify.annotations.Nullable;

/**
 * One non-daemon thread from a successful bind until the stop begins: TinyHttpd's own threads are daemon, so
 * without it a main that only calls listen would let the JVM exit.
 */
// @req FR-ENGINE-006
final class KeeperThread {

    private final CountDownLatch ended = new CountDownLatch(1);
    // Guarded by this.
    private @Nullable Thread thread;

    /** Starts {@code naruserve-keeper-<port>}; called after TinyHttpd started. */
    // @req FR-ENGINE-006
    synchronized void start(int port) {
        Thread t = new Thread(() -> {
            try {
                ended.await();
            } catch (InterruptedException e) {
                // an interrupt also ends the keeper
            }
        }, "naruserve-keeper-" + port);
        t.setDaemon(false);
        t.start();
        thread = t;
    }

    /** Ends the keeper and waits up to 1 s for it, so no keeper is left once the stop returns. */
    // @req FR-ENGINE-006
    void end() {
        ended.countDown();
        Thread t;
        synchronized (this) {
            t = thread;
        }
        if (t != null && t != Thread.currentThread()) {
            try {
                t.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
