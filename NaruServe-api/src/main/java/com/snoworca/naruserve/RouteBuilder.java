package com.snoworca.naruserve;

/**
 * Registers per-method handlers on the one path given to {@link RestApp#route(String)}. Each method returns this
 * builder, so {@code app.route("/u/:id").get(h1).put(h2).delete(h3)} chains.
 */
// @req IR-HTTP-006
public interface RouteBuilder {
    // @req FR-APP-021 AC-2; FR-APP-022 AC-6
    default RouteBuilder method(String token,Middleware... callbacks) { throw new UnsupportedOperationException("method callbacks"); }
    // @req FR-KT-003 AC-3; FR-APP-021 AC-3
    default RouteBuilder method(String token,Middleware callback) { return method(token,new Middleware[]{callback}); }
    default RouteBuilder get(Middleware callback) { return method("GET",new Middleware[]{callback}); }
    default RouteBuilder post(Middleware callback) { return method("POST",new Middleware[]{callback}); }
    default RouteBuilder put(Middleware callback) { return method("PUT",new Middleware[]{callback}); }
    default RouteBuilder patch(Middleware callback) { return method("PATCH",new Middleware[]{callback}); }
    default RouteBuilder delete(Middleware callback) { return method("DELETE",new Middleware[]{callback}); }
    default RouteBuilder head(Middleware callback) { return method("HEAD",new Middleware[]{callback}); }
    default RouteBuilder options(Middleware callback) { return method("OPTIONS",new Middleware[]{callback}); }

    default RouteBuilder get(Middleware... callbacks) { return method("GET",callbacks); }
    default RouteBuilder post(Middleware... callbacks) { return method("POST",callbacks); }
    default RouteBuilder put(Middleware... callbacks) { return method("PUT",callbacks); }
    default RouteBuilder patch(Middleware... callbacks) { return method("PATCH",callbacks); }
    default RouteBuilder delete(Middleware... callbacks) { return method("DELETE",callbacks); }
    default RouteBuilder head(Middleware... callbacks) { return method("HEAD",callbacks); }
    default RouteBuilder options(Middleware... callbacks) { return method("OPTIONS",callbacks); }


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
