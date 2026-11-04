package com.snoworca.naruserve;

/**
 * The kind of one {@link Registration}: which registration API made it. Express keeps the same layers in
 * {@code app._router.stack} without a kind; here every call is classified once.
 */
// @req IR-APP-008
// @req FR-APP-025
public enum RegistrationKind {
    /** {@code get}, {@code post}, ... {@code options}, {@code route(...)}, {@code method(...)}, {@code getAsync},
     * {@code methodAsync} and each method of a {@code route(path)} builder, its {@code use(mw)} included. */
    ROUTE,
    /** {@code ws(path, handler)}. */
    WEBSOCKET,
    /** {@code staticFiles(...)}. */
    STATIC,
    /** {@code use(path, router)} and {@code use(router)}. */
    ROUTER_MOUNT,
    /** {@code group(prefix, configurer)}. */
    GROUP,
    /** {@code cors(...)}. */
    CORS,
    /** Middleware without a path: {@code use(mw)}, {@code use("", mw)}, {@code use("/", mw)}, {@code useAsync(h)}. */
    MIDDLEWARE,
    /** Middleware with any other path, a {@link java.util.regex.Pattern} or an array of paths. */
    PATH_MIDDLEWARE,
    /** {@code use(ErrorMiddleware)}, {@code use(path, ErrorMiddleware)} and {@code onError(handler)}. */
    ERROR_MIDDLEWARE,
    /** {@code param(name, handler)}. */
    PARAM
}
