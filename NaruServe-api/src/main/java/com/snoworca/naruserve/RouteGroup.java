package com.snoworca.naruserve;

/**
 * HTTP routes under a shared prefix, configured inside {@link RestApp#group}. Group middleware applies to every
 * route in that group and its nested groups, including routes declared before {@link #use}. It does not apply to
 * routes registered outside the group. The group and its builders can be used only during their callback.
 */
// @req FR-DX-009
public interface RouteGroup {
    RouteGroup use(Middleware middleware);

    RouteGroup group(String prefix, Configurer<RouteGroup> configurer);

    RouteBuilder route(String path);

    default RouteGroup get(String path, RouteHandler handler) { route(path).get(handler); return this; }
    default RouteGroup post(String path, RouteHandler handler) { route(path).post(handler); return this; }
    default RouteGroup put(String path, RouteHandler handler) { route(path).put(handler); return this; }
    default RouteGroup patch(String path, RouteHandler handler) { route(path).patch(handler); return this; }
    default RouteGroup delete(String path, RouteHandler handler) { route(path).delete(handler); return this; }
    default RouteGroup head(String path, RouteHandler handler) { route(path).head(handler); return this; }
    default RouteGroup options(String path, RouteHandler handler) { route(path).options(handler); return this; }
}
