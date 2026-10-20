package com.snoworca.naruserve.servlet;

import com.snoworca.naruserve.spi.ExchangeHandler;
import com.snoworca.naruserve.spi.HttpAdapter;
import com.snoworca.naruserve.spi.ServerSettings;
import java.util.Objects;
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
    @Nullable ExchangeHandler handler() {
        return handler;
    }

    /** The frozen settings once bound through them, else {@code null}. */
    // @req IR-SVL-001
    @Nullable ServerSettings settings() {
        return settings;
    }
}
