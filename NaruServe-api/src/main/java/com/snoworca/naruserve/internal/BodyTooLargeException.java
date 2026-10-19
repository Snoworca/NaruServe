package com.snoworca.naruserve.internal;

import java.io.IOException;

/** The request body passed its byte limit; body readers answer it with 413, unlike a transport IOException. */
// @req FR-HTTP-020
public final class BodyTooLargeException extends IOException {

    private static final long serialVersionUID = 1L;

    private final long limit;

    // @req FR-HTTP-020
    public BodyTooLargeException(long limit) {
        super("request body exceeds the limit of " + limit + " bytes");
        this.limit = limit;
    }

    public long limit() {
        return limit;
    }
}
