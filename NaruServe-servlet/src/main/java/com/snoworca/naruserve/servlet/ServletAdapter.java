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

    // @req IR-SVL-001
    public ServletAdapter() {
    }

    // @req IR-SVL-001
    @Override
    public void bind(String host, int port, ExchangeHandler handler) {
        this.handler = Objects.requireNonNull(handler, "handler");
    }

    // @req IR-SVL-001
    @Override
    public void bind(ServerSettings settings, ExchangeHandler handler) {
        this.settings = Objects.requireNonNull(settings, "settings");
        bind(settings.bindAddress(), settings.port(), handler);
    }

    /** {@code 0}: the container, not the adapter, owns the listening socket. */
    // @req IR-SVL-001
    @Override
    public int boundPort() {
        if (handler == null) {
            throw new IllegalStateException("ServletAdapter is not bound");
        }
        return 0;
    }

    // @req IR-SVL-001
    @Override
    public void stop(long timeout, TimeUnit unit) {
    }

    // @req IR-SVL-001
    @Override
    public void release() {
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
