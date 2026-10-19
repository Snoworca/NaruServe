package com.snoworca.naruserve;

/** Error callback registered with {@link RestApp#onError}; Express's {@code (err, req, res, next)}. */
// @req IR-APP-003
@FunctionalInterface
public interface ErrorHandler {

    void handle(Throwable error, Request req, Response res) throws Exception;
}
