package com.snoworca.naruserve.proxy;

import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;

/**
 * The resources one proxied request holds from the moment its concurrency slot is taken to the end of the request:
 * the slot, when the proxy has a limit, and the backend socket once it is open. {@link #release} gives both back
 * exactly once, whichever end of the request calls it first: the close of the relayed body, or the forward path when
 * the request ends before the body is handed to {@code res.relay}.
 */
// @req FR-PROXY-019
final class ProxyExchange {

    private final @Nullable ConcurrencyLimiter limiter;
    private final AtomicBoolean released = new AtomicBoolean();
    private volatile @Nullable Socket socket;

    /** An exchange holding a slot of {@code limiter}, which the caller has already taken, or none for null. */
    // @req FR-PROXY-019
    ProxyExchange(@Nullable ConcurrencyLimiter limiter) {
        this.limiter = limiter;
    }

    /**
     * Records the backend socket so {@link #release} closes it; a socket attached after the release is closed at once,
     * so no connection outlives its request.
     */
    // @req FR-PROXY-019
    void attach(Socket backend) {
        socket = backend;
        if (released.get()) {
            closeQuietly(backend);
        }
    }

    /** Closes the backend socket and returns the slot; only the first call does anything. */
    // @req FR-PROXY-019
    void release() {
        if (!released.compareAndSet(false, true)) {
            return;
        }
        try {
            Socket s = socket;
            if (s != null) {
                closeQuietly(s);
            }
        } finally {
            if (limiter != null) {
                limiter.release();
            }
        }
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
            // the connection is dropped either way
        }
    }
}
