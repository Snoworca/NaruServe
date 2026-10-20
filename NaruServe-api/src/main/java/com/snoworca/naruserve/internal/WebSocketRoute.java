package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.Request;
import com.snoworca.naruserve.Response;
import com.snoworca.naruserve.RouteHandler;
import com.snoworca.naruserve.WebSocketHandler;
import java.util.Objects;

/** Carries an {@code app.ws} handler through the router; the upgrade step reads it and never runs it as HTTP. */
// @req IR-WS-001
public final class WebSocketRoute implements RouteHandler {

    private final WebSocketHandler handler;

    // @req IR-WS-001
    WebSocketRoute(WebSocketHandler handler) {
        this.handler = Objects.requireNonNull(handler, "handler");
    }

    public WebSocketHandler handler() {
        return handler;
    }

    // @req IR-WS-001
    @Override
    public void handle(Request req, Response res) {
        throw new IllegalStateException("a WebSocket route is not an HTTP handler");
    }
}
