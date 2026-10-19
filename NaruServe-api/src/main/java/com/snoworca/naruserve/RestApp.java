package com.snoworca.naruserve;

import com.snoworca.naruserve.routing.RouterPolicy;

/** Route registration and routing policy. Every method except {@link #route(String)} returns this app. */
// @req IR-HTTP-005
public interface RestApp {

    RestApp get(String path, RouteHandler h);

    RestApp post(String path, RouteHandler h);

    RestApp put(String path, RouteHandler h);

    RestApp patch(String path, RouteHandler h);

    RestApp delete(String path, RouteHandler h);

    RestApp head(String path, RouteHandler h);

    RestApp options(String path, RouteHandler h);

    RestApp route(Method method, String path, RouteHandler h);

    RestApp route(MethodSet methods, String path, RouteHandler h);

    /** A new builder bound to this app and {@code path}. */
    // @req IR-HTTP-006
    RouteBuilder route(String path);

    /** Sets the router policy, built with {@link RouterPolicy#builder()}. */
    RestApp routerPolicy(RouterPolicy policy);
}
