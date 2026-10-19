package com.snoworca.naruserve;

/** Route callback; Java and Kotlin lambdas {@code (req, res) -> ...} implement it directly. */
// @req IR-HTTP-002
@FunctionalInterface
public interface RouteHandler {

    /** Handles one request; any exception goes to the framework's error handling. */
    void handle(Request req, Response res) throws Exception;
}
