package com.snoworca.naruserve;

/** Express-style middleware; call {@code next.run()} to continue or {@code next.error(e)} to fail the request. */
// @req IR-APP-002
@FunctionalInterface
public interface Middleware {

    /** Runs synchronously on the request thread; any exception goes to the error handlers. */
    void handle(Request req, Response res, Next next) throws Exception;
}
