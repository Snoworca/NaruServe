package com.snoworca.naruserve;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Read-only view of one HTTP request plus a per-request attribute store. Wave 3 holds no body, form, file,
 * cookie, service or application access; later waves add them.
 */
// @req IR-HTTP-001
public interface Request {

    /** The actual request method. */
    Method method();

    /** The percent-decoded request path. */
    String path();

    /** The request path as received, without the query string. */
    String rawPath();

    /** The value of path variable {@code name}; throws {@link IllegalArgumentException} for a name the pattern lacks. */
    String param(String name);

    /** The first value of query parameter {@code name}, or {@code null} when it is absent. */
    @Nullable String query(String name);

    /** Every value of query parameter {@code name} as an unmodifiable list, empty when absent. */
    List<String> queries(String name);

    /** The first value of header {@code name} (case-insensitive), or {@code null} when it is absent. */
    @Nullable String header(String name);

    /** Every value of header {@code name} (case-insensitive) as an unmodifiable list, empty when absent. */
    List<String> headers(String name);

    /** The client address the adapter reports. */
    String remoteAddress();

    /** Whether the adapter received the request over TLS. */
    boolean isSecure();

    /** The {@code Last-Event-ID} header value, or {@code null} when it is absent. */
    @Nullable String lastEventId();

    /** Stores {@code value} under {@code key}; a {@code null} value removes the key. */
    void attribute(String key, @Nullable Object value);

    /** The value stored under {@code key} cast to {@code type}, or {@code null} when nothing is stored. */
    <T> @Nullable T attribute(String key, Class<T> type);
}
