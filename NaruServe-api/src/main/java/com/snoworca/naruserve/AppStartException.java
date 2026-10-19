package com.snoworca.naruserve;

/** Thrown by {@link RestApp#listen(int)} for a checked start-failure cause or a start ended by a stop. */
// @req IR-APP-005
public class AppStartException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AppStartException(String message) {
        super(message);
    }

    public AppStartException(String message, Throwable cause) {
        super(message, cause);
    }
}
