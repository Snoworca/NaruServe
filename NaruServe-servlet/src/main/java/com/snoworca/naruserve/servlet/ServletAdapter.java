package com.snoworca.naruserve.servlet;

import com.snoworca.naruserve.spi.ErrorRenderer;
import com.snoworca.naruserve.spi.ExchangeHandler;
import com.snoworca.naruserve.spi.HttpAdapter;
import com.snoworca.naruserve.spi.ServerSettings;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;

/**
 * {@link HttpAdapter} hosted by a Servlet container: the container owns the socket and the threads, so binding only
 * keeps the core's handler and settings. Hand it to {@code RestApp.builder().httpAdapter(...)}; a
 * {@link ServletConfigurer} receives a builder that already holds one.
 */
// @req IR-SVL-001
// @req FR-SVL-006
public final class ServletAdapter implements HttpAdapter {

    /** How often a stop waiting for open streams looks again: a stream end does not signal {@link #changed}. */
    private static final long POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(10);

    // One lock and condition guard inFlight, stopping and the handler.
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private int inFlight;

    private volatile @Nullable ExchangeHandler handler;
    private volatile @Nullable ServerSettings settings;
    private volatile ServletEventStream.@Nullable Pool pool;
    private volatile boolean bound;
    private volatile boolean stopping;

    // @req IR-SVL-001
    public ServletAdapter() {
    }

    /** Keeps the handler only: the container owns the socket and the threads, so host and port are ignored. */
    // @req IR-SVL-001
    // @req FR-SVL-001
    @Override
    public void bind(String host, int port, ExchangeHandler handler) {
        keep(null, handler);
    }

    /**
     * Keeps the handler and the frozen settings, whose maxSseStreams, error renderer and logger the adapter uses;
     * no other option is read and no container-side body limit is set, so the core limits answer 413.
     */
    // @req FR-SVL-001
    // @req FR-SVL-008
    @Override
    public void bind(ServerSettings settings, ExchangeHandler handler) {
        keep(Objects.requireNonNull(settings, "settings"), handler);
    }

    /** The {@code maxSseStreams} default of {@link ServerSettings}, for a bind without settings. */
    static final int DEFAULT_SSE_STREAMS = 128;

    /**
     * The settings and the SSE pool go in before the handler, so a request that sees the handler also sees the
     * renderer and the pool.
     */
    // @req FR-SVL-001
    // @req FR-SVL-005
    private void keep(@Nullable ServerSettings s, ExchangeHandler h) {
        Objects.requireNonNull(h, "handler");
        lock.lock();
        try {
            if (bound) {
                throw new IllegalStateException("ServletAdapter is already bound; one adapter serves one app");
            }
            bound = true;
            settings = s;
            pool = new ServletEventStream.Pool(s != null ? s.maxSseStreams() : DEFAULT_SSE_STREAMS);
            handler = h;
        } finally {
            lock.unlock();
        }
    }

    /** {@code 0}: the container, not the adapter, owns the listening socket. */
    // @req IR-SVL-001
    // @req FR-SVL-001
    @Override
    public int boundPort() {
        if (!bound) {
            throw new IllegalStateException("ServletAdapter is not bound");
        }
        return 0;
    }

    /**
     * Counts one request in, or answers {@code null} when the adapter is unbound, stopping or released, so the
     * servlet refuses it before the core's handler runs; every non-null answer is paired with {@link #leave}.
     */
    // @req FR-SVL-006
    @Nullable ExchangeHandler enter() {
        lock.lock();
        try {
            ExchangeHandler h = handler;
            if (h == null || stopping) {
                return null;
            }
            inFlight++;
            return h;
        } finally {
            lock.unlock();
        }
    }

    /** Counts one request out and wakes a waiting {@link #stop}. */
    // @req FR-SVL-006
    void leave() {
        lock.lock();
        try {
            inFlight--;
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Refuses new requests at once, waits until no request is in flight and no stream is open or the deadline
     * passed, then completes the AsyncContext of every stream still open and stops the SSE executor. Idempotent and
     * harmless before bind. It never interrupts a container thread: a request still running at the deadline is
     * left to finish, and a stream is cut only by {@code AsyncContext.complete()}.
     */
    // @req IR-SVL-001
    // @req FR-SVL-001
    // @req FR-SVL-006
    @Override
    public void stop(long timeout, TimeUnit unit) {
        long deadline = System.nanoTime() + Math.max(0, unit.toNanos(timeout));
        boolean interrupted = false;
        lock.lock();
        try {
            stopping = true;
            while (inFlight > 0 || openStreams() > 0) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    break;
                }
                try {
                    changed.awaitNanos(Math.min(left, POLL_NANOS));
                } catch (InterruptedException e) {
                    interrupted = true;
                    break;
                }
            }
        } finally {
            lock.unlock();
        }
        ServletEventStream.Pool p = pool;
        if (p != null) {
            p.completeAll();
            p.shutdownNow();
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private int openStreams() {
        ServletEventStream.Pool p = pool;
        return p != null ? p.open() : 0;
    }

    /**
     * Detaches the handler, so the servlet answers 503 from then on, shuts the SSE pool down and waits at most 1 s
     * for its threads. Idempotent.
     */
    // @req IR-SVL-001
    // @req FR-SVL-001
    // @req FR-SVL-005
    // @req FR-SVL-006
    @Override
    public void release() {
        lock.lock();
        try {
            handler = null;
            changed.signalAll();
        } finally {
            lock.unlock();
        }
        ServletEventStream.Pool p = pool;
        if (p != null) {
            p.shutdown();
            try {
                p.awaitTermination(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** The SSE slots and drain executor built at bind from {@code maxSseStreams}; {@code null} before bind. */
    // @req FR-SVL-005
    ServletEventStream.@Nullable Pool pool() {
        return pool;
    }

    /** True once {@link #stop} ran. */
    // @req FR-SVL-001
    boolean stopping() {
        return stopping;
    }

    /** The core's handler once bound, else {@code null}. */
    // @req IR-SVL-001
    // @req FR-SVL-002
    @Nullable ExchangeHandler handler() {
        return handler;
    }

    /**
     * The renderer of the bound settings; before that, or when only {@code bind(host, port, handler)} ran, a plain
     * JSON body in the standard shape naming the status, with a fresh request id and no request text.
     */
    // @req FR-SVL-002
    ErrorRenderer errorRenderer() {
        ServerSettings s = settings;
        return s != null ? s.errorRenderer()
                : status -> "{\"error\":{\"code\":\"HTTP_" + status + "\",\"message\":\"HTTP " + status
                        + "\",\"requestId\":\"" + UUID.randomUUID() + "\"}}";
    }

    /** The frozen settings once bound through them, else {@code null}. */
    // @req IR-SVL-001
    @Nullable ServerSettings settings() {
        return settings;
    }
}
