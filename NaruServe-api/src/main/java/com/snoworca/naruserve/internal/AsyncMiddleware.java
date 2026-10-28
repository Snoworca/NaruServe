package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.*;
import java.util.Objects;

/** Registration adapter recognized by the common HTTP execution owner. */
// @req FR-APP-023 AC-1 AC-7
public final class AsyncMiddleware implements Middleware {
    final AsyncRouteHandler handler;
    public AsyncMiddleware(AsyncRouteHandler handler) { this.handler=Objects.requireNonNull(handler,"handler"); }
    @Override public void handle(Request request,Response response,Next next) throws Exception {
        Objects.requireNonNull(handler.handle(request,response,next),"async result").onRejected(next::error);
    }
}
