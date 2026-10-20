package com.snoworca.naruserve.servlet;

import com.snoworca.naruserve.spi.ErrorRenderer;
import com.snoworca.naruserve.spi.ExchangeHandler;
import com.snoworca.naruserve.spi.HttpAdapter;
import com.snoworca.naruserve.spi.ServerSettings;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * {@link HttpAdapter} hosted by a Servlet container: the container owns the socket and the threads, so binding only
 * keeps the core's handler and settings. Hand it to {@code RestApp.builder().httpAdapter(...)}; a
 * {@link ServletConfigurer} receives a builder that already holds one.
 */
// @req IR-SVL-001
public final class ServletAdapter implements HttpAdapter {

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
    private synchronized void keep(@Nullable ServerSettings s, ExchangeHandler h) {
        Objects.requireNonNull(h, "handler");
        if (bound) {
            throw new IllegalStateException("ServletAdapter is already bound; one adapter serves one app");
        }
        bound = true;
        settings = s;
        pool = new ServletEventStream.Pool(s != null ? s.maxSseStreams() : DEFAULT_SSE_STREAMS);
        handler = h;
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

    // @req IR-SVL-001
    // @req FR-SVL-001
    @Override
    public void stop(long timeout, TimeUnit unit) {
        stopping = true;
    }

    /** Detaches the handler, so the servlet answers 503 from then on, and lets the SSE pool threads end. */
    // @req IR-SVL-001
    // @req FR-SVL-001
    // @req FR-SVL-005
    @Override
    public void release() {
        handler = null;
        ServletEventStream.Pool p = pool;
        if (p != null) {
            p.shutdown();
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
