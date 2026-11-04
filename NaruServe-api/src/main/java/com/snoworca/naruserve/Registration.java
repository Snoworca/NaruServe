package com.snoworca.naruserve;

import org.jspecify.annotations.Nullable;

/**
 * One registration as {@link RestApp#registrations()} lists it and {@link RegistrationListener} receives it. A value
 * object read with getters, so Kotlin reads it with property syntax ({@code r.kind}, {@code r.path}).
 */
// @req IR-APP-008
// @req FR-APP-025
// @req FR-APP-026
public interface Registration {

    /** The registration API that made this record. */
    RegistrationKind getKind();

    /**
     * The upper-case method token of a {@link RegistrationKind#ROUTE} ({@code "GET"}, {@code "PURGE"}); several tokens
     * of one {@code route(MethodSet, ...)} call are joined with {@code ","}. Null for the {@code use(mw)} of a
     * {@code route(path)} builder (it runs for every method of that route) and for every other kind.
     */
    @Nullable String getMethod();

    /**
     * The full string path, with the paths of the enclosing mounts in front ({@code "/api/users"}); {@code ""} for an
     * app-level registration without a path ({@code use(mw)}, {@code cors}, {@code onError}, {@code param}). Null when
     * this path or the path of an enclosing mount is not a string (a {@link java.util.regex.Pattern} or an array).
     */
    @Nullable String getPath();

    /** True exactly when {@link #getPath()} is not null. */
    boolean isStringPath();

    /** The path object this call passed, as given (a String, Pattern, array or list); {@code ""} when it passed none. */
    Object getPathSource();

    /**
     * The object the caller registered: the middleware, handler, {@link Router}, error handler, WebSocket handler,
     * configurer ({@code cors}, {@code group}) or directory ({@code staticFiles}); for a route, its last callback. Never
     * a wrapper the framework made.
     */
    Object getTarget();

    /** The {@link RegistrationKind#ROUTER_MOUNT} record this record was expanded under; null at app level. */
    @Nullable Registration getMount();
}
