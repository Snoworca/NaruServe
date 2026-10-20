package com.snoworca.naruserve.spi;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/** Binds an HTTP engine to the core. Implemented by every engine adapter. */
// @req IR-HTTP-007
// @req CON-HTTP-001
public interface HttpAdapter {

    /** Binds synchronously and returns once bound; port 0 picks a free port. Exchanges go to {@code handler}. */
    // @req IR-HTTP-007
    void bind(String host, int port, ExchangeHandler handler) throws IOException;

    /**
     * Binds with the frozen server settings; {@code listen()} calls this once. The default binds
     * {@code settings.bindAddress()} and {@code settings.port()} through {@link #bind(String, int, ExchangeHandler)}.
     */
    // @req IR-ENGINE-002
    default void bind(ServerSettings settings, ExchangeHandler handler) throws IOException {
        bind(settings.bindAddress(), settings.port(), handler);
    }

    /**
     * The actual bound port, stable until stop.
     *
     * @throws IllegalStateException before bind
     */
    // @req IR-HTTP-007
    int boundPort();

    /** Stops accepting, waits for in-flight exchanges until the deadline, then force-closes the rest. */
    // @req IR-HTTP-007
    void stop(long timeout, TimeUnit unit);

    /** Final release: waits at most the adapter's fixed margin for its threads to end. */
    // @req IR-HTTP-007
    void release();
}
