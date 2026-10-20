package com.snoworca.naruserve;

/**
 * The {@code app.ws(path, handler)} callback, as express-ws {@code app.ws(path, (ws, req) => ...)} with the
 * arguments reversed. It runs once, after the {@code 101} answer, with the upgrade request and the open session;
 * Java and Kotlin lambdas {@code (req, ws) -> ...} implement it directly.
 */
// @req IR-WS-001
@FunctionalInterface
public interface WebSocketHandler {

    /** Sets up one connection, usually by registering listeners on {@code ws}. */
    void handle(Request req, WebSocketSession ws) throws Exception;
}
