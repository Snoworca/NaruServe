package com.snoworca.naruserve;

import com.snoworca.naruserve.common.ResponseValues;
import com.snoworca.naruserve.internal.StandardErrors;
import java.io.File;
import java.io.InputStream;
import org.jspecify.annotations.Nullable;

/**
 * Writer for one HTTP response, with {@link #json} and {@link #addCookie} since wave 5, {@link #cache},
 * {@link #file} and {@link #download} since wave 6 and {@link #sse} since wave 7.
 */
// @req IR-HTTP-001
// @req IR-JSON-002
// @req FR-COOKIE-001
// @req IR-STATIC-002
// @req IR-SSE-001
public interface Response {

    /**
     * Sets the status code; returns this response. A code outside 100..999 throws {@link IllegalArgumentException},
     * as Express 5 {@code res.status} throws a RangeError.
     */
    Response status(int code);

    /** Sets Location after validating it; returns this response. */
    // @req FR-DX-004
    default Response location(String url) {
        return header("Location", ResponseValues.location(url));
    }

    /**
     * Sets an explicit MIME type or resolves an extension (for example png or .png) with the built-in MIME table.
     * An unknown extension uses application/octet-stream. Returns this response.
     */
    // @req FR-DX-004
    default Response type(String value) {
        return header("Content-Type", ResponseValues.contentType(value));
    }

    /** Sends an empty 302 redirect. */
    // @req FR-DX-004
    default void redirect(String url) {
        redirect(302, url);
    }

    /** Sends an empty redirect with status 301, 302, 303, 307 or 308; validates before changing the response. */
    // @req FR-DX-004
    default void redirect(int code, String url) {
        ResponseValues.redirectStatus(code);
        String target = ResponseValues.location(url);
        header("Location", target);
        status(code);
        end();
    }

    /** Sends the shared HTTP reason phrase; 204, 304 and HEAD responses have no body. */
    // @req FR-DX-004
    default void sendStatus(int code) {
        status(code);
        if (code == 204 || code == 304) {
            end();
        } else {
            send(StandardErrors.reason(code));
        }
    }

    /** Sets a header, replacing a same-name header as Express {@code res.set} does; returns this response. */
    Response header(String name, String value);

    /**
     * Sends a string as UTF-8 text, a byte array as binary, or any other value through {@link #json}.
     * A null value ends the response without a body. Dispatch uses the actual value, even when declared Object.
     */
    // @req FR-DX-002
    void send(@Nullable Object body);

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

    /** Appends a session cookie with Path=/; returns this response. Values follow Cookie's existing validation. */
    // @req FR-DX-005
    default Response cookie(String name, String value) {
        return cookie(name, value, options -> { });
    }

    /** Appends a cookie configured fluently; its default Path is / and maxAge is measured in seconds. */
    // @req FR-DX-005
    default Response cookie(String name, String value, Configurer<CookieOptions> configurer) {
        return addCookie(CookieOptions.configured(name, value, configurer, false));
    }

    /** Deletes a cookie scoped to Path=/ and no Domain; returns this response. */
    // @req FR-DX-005
    default Response clearCookie(String name) {
        return clearCookie(name, options -> { });
    }

    /** Deletes a cookie with matching Path/Domain; Max-Age is forced to zero after the callback. */
    // @req FR-DX-005
    default Response clearCookie(String name, Configurer<CookieOptions> configurer) {
        return addCookie(CookieOptions.configured(name, "", configurer, true));
    }

    /**
     * Sets {@code Cache-Control} from {@code policy}, replacing an earlier value; returns this response. A committed
     * response throws {@link IllegalStateException} as {@link #header} does.
     */
    // @req IR-STATIC-002
    default Response cache(CachePolicy policy) {
        return header("Cache-Control", policy.getHeaderValue());
    }

    /**
     * Sends {@code file} as the whole body with its MIME type and no {@code Content-Disposition}, like Express
     * {@code res.sendFile}; ends the response like {@link #send(Object)}. The default throws
     * {@link UnsupportedOperationException}.
     */
    // @req IR-STATIC-002
    default void file(File file) {
        throw new UnsupportedOperationException(getClass().getName() + " has no file writer");
    }

    /**
     * Sends {@code file} as an attachment named {@code filename}, like Express {@code res.download}; ends the
     * response like {@link #send(Object)}. The default throws {@link UnsupportedOperationException}.
     */
    // @req IR-STATIC-002
    default void download(File file, String filename) {
        throw new UnsupportedOperationException(getClass().getName() + " has no file writer");
    }

    /**
     * Starts a Server-Sent Events stream: commits status {@code 200} with {@code Content-Type: text/event-stream;
     * charset=UTF-8} and {@code Cache-Control: no-cache, no-transform} and returns the stream, which stays open after
     * the handler returns. A committed response throws {@link IllegalStateException}. The default throws
     * {@link UnsupportedOperationException}.
     */
    // @req IR-SSE-001
    default SseClient sse() {
        throw new UnsupportedOperationException(getClass().getName() + " has no SSE writer");
    }
}
