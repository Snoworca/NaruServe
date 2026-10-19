package com.snoworca.naruserve;

/**
 * Registers per-method handlers on the one path given to {@link RestApp#route(String)}. Each method returns this
 * builder, so {@code app.route("/u/:id").get(h1).put(h2).delete(h3)} chains.
 */
// @req IR-HTTP-006
public interface RouteBuilder {

    RouteBuilder get(RouteHandler h);

    RouteBuilder post(RouteHandler h);

    RouteBuilder put(RouteHandler h);

    RouteBuilder patch(RouteHandler h);

    RouteBuilder delete(RouteHandler h);

    RouteBuilder head(RouteHandler h);

    RouteBuilder options(RouteHandler h);

    /**
     * Adds route middleware for the methods this builder registers after this call, in {@code use} order:
     * {@code app.route("/a").use(m1).use(m2).get(h)} runs m1, m2, then h.
     */
    // @req IR-APP-002
    RouteBuilder use(Middleware mw);
}
