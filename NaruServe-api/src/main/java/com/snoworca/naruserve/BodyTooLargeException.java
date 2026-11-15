package com.snoworca.naruserve;

import java.io.IOException;

/**
 * The request body passed its byte limit. An {@code IOException}, so code reading a body stream already handles it;
 * the core's body readers answer it with 413, unlike a transport {@code IOException}.
 */
// @req IR-HTTP-011
public final class BodyTooLargeException extends IOException {

    private static final long serialVersionUID = 1L;

    private final long limit;

    /** {@code limit} is the byte limit the body passed. */
    // @req IR-HTTP-011
    public BodyTooLargeException(long limit) {
        super("request body exceeds the limit of " + limit + " bytes");
        this.limit = limit;
    }

    /** The byte limit the body passed. */
    // @req IR-HTTP-011
    public long limit() {
        return limit;
    }
}
