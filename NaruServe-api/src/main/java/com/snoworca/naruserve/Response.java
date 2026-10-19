package com.snoworca.naruserve;

import java.io.InputStream;
import org.jspecify.annotations.Nullable;

/**
 * Writer for one HTTP response, with {@link #json} and {@link #addCookie} since wave 5. No file, download, cache or
 * sse method yet; later waves add them.
 */
// @req IR-HTTP-001
// @req IR-JSON-002
// @req FR-COOKIE-001
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

    /**
     * Sends {@code value} encoded by the app codec ({@code null} as JSON {@code null}) with
     * {@code application/json; charset=UTF-8}, like Express {@code res.json}. The value is encoded before anything
     * is sent, so an encoding failure leaves the response uncommitted. The default throws
     * {@link UnsupportedOperationException}.
     */
    // @req IR-JSON-002
    // @req FR-JSON-002
    default void json(@Nullable Object value) {
        throw new UnsupportedOperationException(getClass().getName() + " has no JSON writer");
    }

    /**
     * Appends one {@code Set-Cookie} header for {@code cookie}, like Express {@code res.cookie}; returns this response.
     * The name, value, path and domain are validated again ({@link IllegalArgumentException}), and a committed
     * response throws {@link IllegalStateException} as {@link #header} does. The default throws
     * {@link UnsupportedOperationException}.
     */
    // @req FR-COOKIE-001
    default Response addCookie(Cookie cookie) {
        throw new UnsupportedOperationException(getClass().getName() + " has no cookie writer");
    }
}
