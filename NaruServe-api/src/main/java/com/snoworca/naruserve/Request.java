package com.snoworca.naruserve;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Read-only view of one HTTP request plus a per-request attribute store, the app context, the JSON body reader and
 * the request cookies. No form or file access yet; later waves add them.
 */
// @req IR-HTTP-001
// @req IR-JSON-002
// @req FR-COOKIE-002
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

    /**
     * The value stored under {@code key} cast to {@code type}, or {@code null} when nothing is stored. A primitive
     * class such as {@code int.class} (Kotlin's {@code Int::class.java}) reads the boxed value.
     */
    <T> @Nullable T attribute(String key, Class<T> type);

    /**
     * The app's {@link ApplicationContext}. The default throws {@link UnsupportedOperationException} so request
     * fakes written for wave 3 still compile.
     */
    // @req IR-APP-004
    default ApplicationContext application() {
        throw new UnsupportedOperationException(getClass().getName() + " has no application context");
    }

    /** The service registered under {@code type}: {@code application().get(type)}. */
    // @req IR-APP-004
    default <T> T service(Class<T> type) {
        return application().get(type);
    }

    /**
     * The JSON body decoded into {@code type} by the app codec; null for a JSON {@code null} body. Answers 415 for a
     * body that is not {@code application/json} or {@code application/*+json} in UTF-8, 413 over the JSON limit and
     * 400 for an empty or malformed body. The body is read once: a second read throws
     * {@link BodyAlreadyConsumedException}. The default throws {@link UnsupportedOperationException}.
     */
    // @req IR-JSON-002
    // @req FR-JSON-001
    default <T> @Nullable T body(Class<T> type) {
        throw new UnsupportedOperationException(getClass().getName() + " has no JSON body reader");
    }

    /** As {@link #body(Class)} for a generic type: {@code req.body(new TypeRef<List<User>>() {})}. */
    // @req IR-JSON-002
    // @req FR-JSON-001
    default <T> @Nullable T body(TypeRef<T> type) {
        throw new UnsupportedOperationException(getClass().getName() + " has no JSON body reader");
    }

    /**
     * The first request cookie named {@code name}, or {@code null}; the value is exactly as the client sent it (no
     * decoding), like Express {@code req.cookies[name]} with cookie-parser. The default throws
     * {@link UnsupportedOperationException}.
     */
    // @req FR-COOKIE-002
    default @Nullable Cookie cookie(String name) {
        throw new UnsupportedOperationException(getClass().getName() + " has no cookie reader");
    }

    /**
     * Every request cookie in header order, duplicates kept, as a new array on each call (length 0 without a
     * {@code Cookie} header). Pairs without '=' or with a non-token name are skipped. The default throws
     * {@link UnsupportedOperationException}.
     */
    // @req FR-COOKIE-002
    default Cookie[] getCookies() {
        throw new UnsupportedOperationException(getClass().getName() + " has no cookie reader");
    }
}
