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
}
