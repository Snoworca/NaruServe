package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.*;
import java.util.Objects;

/** Public mounted Router and RestApp share the same registry and execution implementation. */
// @req FR-ROUTE-020 AC-1; FR-APP-021 AC-1 AC-3
public final class DefaultRouter implements com.snoworca.naruserve.Router {
    final HttpRouter registry;
    public DefaultRouter(boolean mergeParams,boolean caseSensitive,boolean strict) {
        registry=new HttpRouter(mergeParams,caseSensitive,strict);
    }
    static HttpRouter unwrap(com.snoworca.naruserve.Router router) {
        Objects.requireNonNull(router,"router");
        if(!(router instanceof DefaultRouter))throw new IllegalArgumentException("mount a Router created by Router.create()");
        return ((DefaultRouter)router).registry;
    }
    @Override public RouteBuilder route(Object path) { return registry.route(path); }
    @Override public com.snoworca.naruserve.Router method(String token,Object path,Middleware... callbacks) {
        registry.method(token,path,callbacks);return this;
    }
    @Override public com.snoworca.naruserve.Router use(Object path,Middleware callback) {
        registry.use(path,callback);return this;
    }
    @Override public com.snoworca.naruserve.Router use(Object path,com.snoworca.naruserve.Router child) {
        registry.mount(path,unwrap(child));return this;
    }
    @Override public com.snoworca.naruserve.Router param(String name,ParamHandler callback) {
        registry.param(name,callback);return this;
    }
}
