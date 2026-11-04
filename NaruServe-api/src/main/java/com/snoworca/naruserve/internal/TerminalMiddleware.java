package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.Middleware;
import com.snoworca.naruserve.Next;
import com.snoworca.naruserve.Request;
import com.snoworca.naruserve.Response;
import com.snoworca.naruserve.RouteHandler;
import java.util.Objects;

/**
 * The last callback {@link com.snoworca.naruserve.HttpRoutes#terminal(RouteHandler)} makes of a route handler; it keeps
 * the handler so a registration record names the handler the caller passed, not this adapter.
 */
// @req FR-APP-025
public final class TerminalMiddleware implements Middleware {
    final RouteHandler handler;

    public TerminalMiddleware(RouteHandler handler) {
        this.handler = Objects.requireNonNull(handler, "callback");
    }

    @Override
    public void handle(Request request, Response response, Next next) throws Exception {
        handler.handle(request, response);
    }
}
