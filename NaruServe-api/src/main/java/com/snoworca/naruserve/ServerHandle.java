package com.snoworca.naruserve;

import com.snoworca.naruserve.internal.ServerUrls;
import java.util.concurrent.TimeUnit;

/** The result of {@link RestApp#listen(int)} and {@link RestApp#start(int, Configurer)}. */
// @req IR-APP-005
// @req FR-DX-013
public interface ServerHandle {

    /** The bound port. */
    int port();

    /** The address the server was bound with: the configured bind address, {@code 127.0.0.1} by default. */
    String address();

    /** The app's current state, read at each call. */
    AppState state();

    /**
     * The address to open: {@code http://<address>:<port>} with the bound port, {@code localhost} for a wildcard
     * bind address and brackets around an IPv6 literal.
     */
    // @req FR-DX-013
    default String url() {
        return ServerUrls.of(address(), port());
    }

    /**
     * Stops the app like {@link RestApp#shutdown(long, TimeUnit)}: safe to call again, a negative timeout throws
     * {@link IllegalArgumentException} and a {@code null} unit {@link NullPointerException}.
     */
    // @req FR-DX-013
    void shutdown(long timeout, TimeUnit unit);
}
