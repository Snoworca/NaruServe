package com.snoworca.naruserve;

import com.snoworca.naruserve.internal.StandardErrors;
import org.jspecify.annotations.Nullable;

/** Unchecked error carrying an HTTP status in {@code 400..599}; throw it to answer with that status. */
// @req IR-APP-003
public class HttpException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int status;

    public HttpException(int status) {
        this(status, null, null);
    }

    public HttpException(int status, @Nullable String message) {
        this(status, message, null);
    }

    // @req IR-APP-003
    public HttpException(int status, @Nullable String message, @Nullable Throwable cause) {
        super(message, cause);
        if (status < 400 || status > 599) {
            throw new IllegalArgumentException("HTTP error status " + status + " is outside 400..599");
        }
        this.status = status;
    }

    public int status() {
        return status;
    }

    /** The standard error code of {@link #status()}, e.g. {@code BAD_REQUEST} for 400. */
    // @req IR-APP-003
    public String code() {
        return StandardErrors.code(status);
    }
}
