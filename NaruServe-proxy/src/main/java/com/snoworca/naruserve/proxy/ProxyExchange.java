package com.snoworca.naruserve.proxy;

import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;

/**
 * The resources one proxied request holds from the moment its concurrency slot is taken to the end of the request:
 * the slot, when the proxy has a limit, the total-timeout watchdog's reservation and the backend socket once it is
 * open. {@link #release} gives all of them back exactly once, whichever end of the request calls it first: the close
 * of the relayed body, the {@code res.onAbort} notification, or the forward path when the request ends before the
 * body is handed to {@code res.relay}.
 */
// @req FR-PROXY-019
// @req REL-PROXY-001
final class ProxyExchange {

    /**
     * The total-timeout watchdog of proxied requests: {@link #watch} reserves it once per request, right after the
     * slot is taken and before the backend connect, and {@link #release} cancels that reservation.
     */
    interface Watchdog {
        /** Starts watching {@code exchange} for {@code totalTimeoutNanos}; the result is cancelled once at its end. */
        Reservation reserve(ProxyExchange exchange, long totalTimeoutNanos);
    }

    /** One request's reservation of the {@link Watchdog}. */
    interface Reservation {
        void cancel();
    }

    /** A reservation that holds nothing. */
    static final Reservation NO_RESERVATION = new Reservation() {
        @Override
        public void cancel() {
        }
    };

    /** The watchdog that watches nothing: the default until the total-timeout scheduler is installed. */
    static final Watchdog NO_WATCHDOG = new Watchdog() {
        @Override
        public Reservation reserve(ProxyExchange exchange, long totalTimeoutNanos) {
            return NO_RESERVATION;
        }
    };

    /** Stands in for the reservation once {@link #release} took it, so a later {@link #watch} cancels at once. */
    private static final Reservation RELEASED = new Reservation() {
        @Override
        public void cancel() {
        }
    };

    private final @Nullable ConcurrencyLimiter limiter;
    private final AtomicBoolean released = new AtomicBoolean();
    private final AtomicReference<@Nullable Reservation> reservation = new AtomicReference<>();
    private volatile @Nullable Socket socket;

    /** An exchange holding a slot of {@code limiter}, which the caller has already taken, or none for null. */
    // @req FR-PROXY-019
    ProxyExchange(@Nullable ConcurrencyLimiter limiter) {
        this.limiter = limiter;
    }

    /**
     * Reserves {@code watchdog} for this request; a reservation made after the release is cancelled at once, so every
     * reservation is cancelled exactly once.
     */
    // @req REL-PROXY-001
    void watch(Watchdog watchdog, long totalTimeoutNanos) {
        Reservation r = watchdog.reserve(this, totalTimeoutNanos);
        if (!reservation.compareAndSet(null, r)) {
            r.cancel();
        }
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

    /**
     * Cancels the watchdog reservation, closes the backend socket and returns the slot, in that order, so the slot is
     * free only once the connection is gone; only the first call does anything.
     */
    // @req FR-PROXY-019
    // @req REL-PROXY-001
    void release() {
        if (!released.compareAndSet(false, true)) {
            return;
        }
        try {
            Reservation r = reservation.getAndSet(RELEASED);
            if (r != null) {
                r.cancel();
            }
        } finally {
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
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
            // the connection is dropped either way
        }
    }
}
