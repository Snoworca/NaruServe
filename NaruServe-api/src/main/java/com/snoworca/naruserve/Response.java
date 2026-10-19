package com.snoworca.naruserve;

import java.io.InputStream;
import org.jspecify.annotations.Nullable;

/**
 * Writer for one HTTP response. Wave 3 holds no json, file, download, cache, sse or cookie method; later waves add
 * them.
 */
// @req IR-HTTP-001
public interface Response {

    /**
     * Sets the status code; returns this response. A code outside 100..999 throws {@link IllegalArgumentException},
     * as Express 5 {@code res.status} throws a RangeError.
     */
    Response status(int code);

    /** Adds a header; returns this response. */
    Response header(String name, String value);

    /** Sends {@code body} as the whole response body. */
    void send(String body);

    /** Sends {@code body} as the whole response body. */
    void send(byte[] body);

    /** Sends {@code body} as an HTML response body. */
    void html(String body);

    /** Streams {@code in} as the response body with {@code contentType}; the response takes ownership of {@code in}. */
    void stream(InputStream in, String contentType);

    /** Ends the response without a body. */
    void end();

    /** Whether the status line and headers have been sent. */
    boolean isCommitted();

    /** Aborts the exchange, optionally recording {@code error} as the cause. */
    void abort(@Nullable Throwable error);
}
