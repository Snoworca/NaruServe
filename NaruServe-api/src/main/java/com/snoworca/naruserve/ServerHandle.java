package com.snoworca.naruserve;

/** The result of {@link RestApp#listen(int)}. */
// @req IR-APP-005
public interface ServerHandle {

    /** The bound port. */
    int port();

    /** The address the server was bound with: the configured bind address, {@code 127.0.0.1} by default. */
    String address();

    /** The app's current state, read at each call. */
    AppState state();
}
