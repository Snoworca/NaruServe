package com.snoworca.naruserve.proxy;

import java.io.BufferedOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;

/**
 * The resources one proxied request holds from the moment its concurrency slot is taken to the end of the request:
 * the slot, when the proxy has a limit, the total-timeout watchdog's reservation and the backend socket once it is
 * open. {@link #release} gives all of them back exactly once, whichever end of the request calls it first: the close
 * of the relayed body, the {@code res.onAbort} notification ({@link #releaseOnAbortThread}, which leaves the
 * {@code SSLSocket} to the relayed body's close), or the forward path when the request ends before the body is handed
 * to {@code res.relay}.
 *
 * <p>The write timeout and the total timeout are enforced by closing the plain backend socket, never an
 * {@code SSLSocket} (FR-PROXY-017): a firing deadline first records its kind ({@link #firedKind}) and then closes the
 * plain socket, so the call that fails on the closed socket maps to {@code WRITE_TIMEOUT} or {@code TOTAL_TIMEOUT}.
 * Neither can unblock name resolution before the plain socket exists, nor a read of the client body.
 */
// @req FR-PROXY-019
// @req REL-PROXY-001
// @req FR-PROXY-017
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

    /** A watchdog that watches nothing: no total-timeout reservation fires on a proxy that uses it. */
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
    /** The plain backend socket a firing deadline closes: the socket itself for http, the one under TLS for https. */
    private volatile @Nullable Socket plain;
    /**
     * The settled kind of the exchange's failure: a deadline's kind, recorded before it closes the plain socket, or
     * the kind the request thread settled first for a failure it saw itself ({@link #settle}); null while neither.
     */
    private final AtomicReference<ProxyException.@Nullable Kind> fired = new AtomicReference<>();

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
     * Records the backend socket so {@link #release} closes its connection; a socket attached after the release has its
     * connection closed at once, so no connection outlives its request. The connection is closed the way
     * {@link #release} closes it: the plain socket recorded by {@link #plainSocket} first, then the {@code SSLSocket}.
     */
    // @req FR-PROXY-019
    void attach(Socket backend) {
        socket = backend;
        if (released.get()) {
            closeConnection(backend);
        }
    }

    /**
     * Records the plain backend socket, before its connect, so a firing deadline closes it; a socket recorded after a
     * deadline fired is closed at once.
     */
    // @req FR-PROXY-017
    void plainSocket(Socket s) {
        plain = s;
        if (fired.get() != null) {
            closeQuietly(s);
        }
    }

    /**
     * Fires the deadline of {@code kind}: records the kind, unless one fired before or the exchange has ended, and then
     * closes the plain socket, without blocking, so the backend call waiting on it fails at once.
     */
    // @req FR-PROXY-017
    void fire(ProxyException.Kind kind) {
        if (released.get() || !fired.compareAndSet(null, kind)) {
            return;
        }
        Socket s = plain;
        if (s != null) {
            closeQuietly(s);
        }
    }

    /**
     * The settled kind: {@code WRITE_TIMEOUT} or {@code TOTAL_TIMEOUT} when a deadline fired first, the kind
     * {@link #settle} recorded when the request thread's own failure came first, or null for neither.
     */
    ProxyException.@Nullable Kind firedKind() {
        return fired.get();
    }

    /**
     * Settles the kind of the failure the request thread just saw: the kind of a deadline that fired before it, else
     * {@code own}, the kind the failure reports itself, which from now on keeps a later deadline from firing, so the
     * first timeout reached is the one reported. A null {@code own} settles nothing and returns the fired kind or null.
     */
    // @req FR-PROXY-017
    ProxyException.@Nullable Kind settle(ProxyException.@Nullable Kind own) {
        if (own != null) {
            fired.compareAndSet(null, own);
        }
        return fired.get();
    }

    /**
     * The request output to the backend over {@code out}, the raw output of the backend socket: buffered, with the
     * write deadline of {@code writeTimeoutNanos} armed for each write to the socket only and disarmed when that write
     * returns, so time spent waiting for client body bytes between writes is not counted, and a write still blocked
     * when the deadline passes fires {@code WRITE_TIMEOUT}. Every write to it fails once a deadline has fired, so a
     * client body that keeps trickling into the buffer ends at its first read after the total deadline.
     */
    // @req FR-PROXY-017
    OutputStream backendOutput(OutputStream out, long writeTimeoutNanos) {
        return new FiredGuard(new BufferedOutputStream(new WatchedOutputStream(out, this, writeTimeoutNanos)), this);
    }

    /**
     * Cancels the watchdog reservation, closes the backend connection and returns the slot, in that order, so the slot
     * is free only once the connection is gone; only the first call of this or {@link #releaseOnAbortThread} does
     * anything. The connection is ended by {@link BackendClient#closeConnection}: its plain socket is closed first, so
     * no TLS duplex close waits for the backend while the worker and the slot are held (FR-PROXY-020, FR-PROXY-017),
     * and then the {@code SSLSocket} of an https backend, so none is left for its finalizer (SEC-PROXY-005). Its callers
     * run only while no read of the connection is under way: the forward path on the request thread before the body is
     * handed to {@code res.relay}, and the relayed body's close, which runs on the copy thread after its read returned,
     * or on the thread that aborts the response while no copy is running (the response then closes the relayed input
     * itself, before its abort listeners). The connection is never reused, so no {@code close_notify} exchange is
     * needed.
     */
    // @req FR-PROXY-019
    // @req REL-PROXY-001
    void release() {
        release(true);
    }

    /**
     * The release run by the {@code res.onAbort} listener, on the adapter's or the {@code res.abort} caller's thread,
     * while the request thread may still be blocked in a read of the backend connection: like {@link #release}, but it
     * closes only the plain socket, which makes that read fail at once, and never the {@code SSLSocket}. The
     * {@code SSLSocket} is closed by the relayed body's close, which the response runs on every path and which runs
     * {@link BackendClient#closeConnection} whether or not the exchange was released: on the copy thread after its read
     * returned, or, when the abort comes while no copy is running, on the aborting thread itself, where no read is under
     * way. So no {@code SSLSocket} close ever runs under another thread's read (on Android, Conscrypt's close waits for
     * a pending {@code SSL_read}).
     */
    // @req FR-PROXY-019
    // @req REL-PROXY-001
    // @req SEC-PROXY-005
    void releaseOnAbortThread() {
        release(false);
    }

    private void release(boolean closeTls) {
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
                    if (closeTls) {
                        closeConnection(s);
                    } else {
                        Socket p = plain;
                        closeQuietly(p != null ? p : s);
                    }
                }
            } finally {
                if (limiter != null) {
                    limiter.release();
                }
            }
        }
    }

    /**
     * Ends the connection of the attached {@code backend} by {@link BackendClient#closeConnection}: the recorded plain
     * socket, the one under TLS for https and {@code backend} itself for http, first, then {@code backend}; when no
     * plain socket was recorded, {@code backend} alone.
     */
    // @req SEC-PROXY-005
    // @req FR-PROXY-019
    private void closeConnection(Socket backend) {
        Socket p = plain;
        BackendClient.closeConnection(p != null ? p : backend, backend);
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
            // the connection is dropped either way
        }
    }

    /** Fails every write once a deadline of {@code exchange} has fired (see {@link #backendOutput}). */
    private static final class FiredGuard extends FilterOutputStream {
        private final ProxyExchange exchange;

        FiredGuard(OutputStream out, ProxyExchange exchange) {
            super(out);
            this.exchange = exchange;
        }

        @Override
        public void write(int b) throws IOException {
            check();
            out.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            check();
            out.write(b, off, len);
        }

        @Override
        public void flush() throws IOException {
            check();
            out.flush();
        }

        // @req FR-PROXY-017
        private void check() throws IOException {
            ProxyException.Kind kind = exchange.firedKind();
            if (kind != null) {
                throw new IOException("the exchange already failed with " + kind);
            }
        }
    }

    /** The backend socket's output with the write deadline armed around each write (see {@link #backendOutput}). */
    private static final class WatchedOutputStream extends FilterOutputStream {
        private final ProxyExchange exchange;
        private final long writeTimeoutNanos;

        WatchedOutputStream(OutputStream out, ProxyExchange exchange, long writeTimeoutNanos) {
            super(out);
            this.exchange = exchange;
            this.writeTimeoutNanos = writeTimeoutNanos;
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        // @req FR-PROXY-017
        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            WriteDeadline deadline = new WriteDeadline(exchange);
            deadline.future = Scheduler.INSTANCE.schedule(deadline, writeTimeoutNanos);
            try {
                out.write(b, off, len);
            } finally {
                deadline.disarm();
            }
        }
    }

    /** The deadline of one backend write: fires {@code WRITE_TIMEOUT} unless the write returned first. */
    private static final class WriteDeadline implements Runnable {
        private final ProxyExchange exchange;
        private final AtomicBoolean done = new AtomicBoolean();
        volatile @Nullable ScheduledFuture<?> future;

        WriteDeadline(ProxyExchange exchange) {
            this.exchange = exchange;
        }

        @Override
        public void run() {
            if (done.compareAndSet(false, true)) {
                exchange.fire(ProxyException.Kind.WRITE_TIMEOUT);
            }
        }

        /** Called when the write returned: a deadline that has not fired yet never fires. */
        void disarm() {
            if (done.compareAndSet(false, true)) {
                ScheduledFuture<?> f = future;
                if (f != null) {
                    f.cancel(false);
                }
            }
        }
    }

    /**
     * The JVM-wide scheduler of the write, total and TLS handshake deadlines, the default {@link Watchdog} of
     * {@code Proxy.to}: its executor is created by the first deadline a proxied request arms, belongs to no app and is
     * never shut down, and runs at most one daemon thread without context class loader that ends once idle;
     * cancelled deadlines leave its queue at once. A deadline only records its kind and closes a plain socket, so it
     * never blocks the thread. {@link TlsConnector} arms the handshake's total deadline here too, so the proxy module
     * has this one watchdog thread and no other.
     */
    // @req FR-PROXY-017
    // @req REL-PROXY-002
    static final class Scheduler implements Watchdog {
        static final Scheduler INSTANCE = new Scheduler();
        /** How long the idle watchdog thread waits for a new deadline before it ends. */
        private static final long IDLE_SECONDS = 10;

        private volatile @Nullable ScheduledThreadPoolExecutor executor;

        private Scheduler() {
        }

        /** Fires {@code TOTAL_TIMEOUT} on {@code exchange} after {@code totalTimeoutNanos} unless cancelled first. */
        // @req FR-PROXY-017
        @Override
        public Reservation reserve(final ProxyExchange exchange, long totalTimeoutNanos) {
            final ScheduledFuture<?> f = schedule(new Runnable() {
                @Override
                public void run() {
                    exchange.fire(ProxyException.Kind.TOTAL_TIMEOUT);
                }
            }, totalTimeoutNanos);
            return new Reservation() {
                @Override
                public void cancel() {
                    f.cancel(false);
                }
            };
        }

        ScheduledFuture<?> schedule(Runnable task, long delayNanos) {
            return executor().schedule(task, delayNanos, TimeUnit.NANOSECONDS);
        }

        /**
         * Package-private test accessor: the executor, or null while no proxied request has armed a deadline yet, read
         * without creating it (REL-PROXY-002 AC-1).
         */
        // @req REL-PROXY-002
        @Nullable ScheduledThreadPoolExecutor executorIfCreated() {
            return executor;
        }

        /**
         * Package-private test accessor: how many deadlines wait in the executor's queue, 0 before it exists; a
         * cancelled deadline leaves the queue at once (REL-PROXY-002 AC-6).
         */
        // @req REL-PROXY-002
        int queuedReservations() {
            ScheduledThreadPoolExecutor e = executor;
            return e == null ? 0 : e.getQueue().size();
        }

        // @req FR-PROXY-017
        private ScheduledThreadPoolExecutor executor() {
            ScheduledThreadPoolExecutor e = executor;
            if (e != null) {
                return e;
            }
            synchronized (this) {
                e = executor;
                if (e == null) {
                    e = new ScheduledThreadPoolExecutor(1, new ThreadFactory() {
                        @Override
                        public Thread newThread(Runnable r) {
                            Thread t = new Thread(r, "naruserve-proxy-watchdog");
                            t.setDaemon(true);
                            t.setContextClassLoader(null);
                            return t;
                        }
                    });
                    e.setRemoveOnCancelPolicy(true);
                    e.setKeepAliveTime(IDLE_SECONDS, TimeUnit.SECONDS);
                    e.allowCoreThreadTimeOut(true);
                    executor = e;
                }
                return e;
            }
        }
    }
}
