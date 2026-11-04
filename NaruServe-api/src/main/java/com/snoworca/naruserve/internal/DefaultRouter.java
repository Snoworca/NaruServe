package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Public mounted Router and RestApp share the same registry and execution implementation. Each registration is
 * validated, shown to the watchers of every app this Router is mounted in (with the mount path in front), applied and
 * then recorded there, also after those apps started.
 */
// @req FR-ROUTE-020 AC-1; FR-APP-021 AC-1 AC-3
// @req FR-APP-026
// @req SEC-APP-002
public final class DefaultRouter implements com.snoworca.naruserve.Router {
    final HttpRouter registry;
    /** This Router's own registrations in order. Guarded by RegistrationLog.LOCK. */
    final List<RegistrationLog.Entry> entries = new ArrayList<>();
    /** Every app-level place this Router is mounted, directly or inside another Router. Guarded by RegistrationLog.LOCK. */
    final List<RegistrationLog.Mount> mounts = new ArrayList<>();

    public DefaultRouter(boolean mergeParams,boolean caseSensitive,boolean strict) {
        registry=new HttpRouter(mergeParams,caseSensitive,strict);
    }
    static HttpRouter unwrap(com.snoworca.naruserve.Router router) {
        return unwrapRouter(router).registry;
    }
    // @req FR-APP-026
    static DefaultRouter unwrapRouter(com.snoworca.naruserve.Router router) {
        Objects.requireNonNull(router,"router");
        if(!(router instanceof DefaultRouter))throw new IllegalArgumentException("mount a Router created by Router.create()");
        return (DefaultRouter)router;
    }
    // @req FR-APP-025 AC-1
    @Override public RouteBuilder route(Object path) {
        RegistrationLog.requireNotWatching();
        return registry.route(path,(method,callbacks,apply)->RegistrationLog.admitRouter(this,
                new RegistrationLog.Entry(RegistrationKind.ROUTE,method,path,RegistrationLog.routeTarget(callbacks),null),apply));
    }
    @Override public com.snoworca.naruserve.Router method(String token,Object path,Middleware... callbacks) {
        RegistrationLog.requireNotWatching();
        HttpRouter.Layer layer=registry.prepareMethod(token,path,callbacks);
        RegistrationLog.admitRouter(this,new RegistrationLog.Entry(RegistrationKind.ROUTE,HttpRouter.method(token),path,
                RegistrationLog.routeTarget(callbacks),null),()->registry.apply(layer));
        return this;
    }
    @Override public com.snoworca.naruserve.Router use(Object path,Middleware callback) {
        RegistrationLog.requireNotWatching();
        HttpRouter.Layer layer=registry.prepareUse(path,callback);
        RegistrationLog.admitRouter(this,new RegistrationLog.Entry(RegistrationKind.MIDDLEWARE,null,path,
                RegistrationLog.target(callback),null),()->registry.apply(layer));
        return this;
    }
    @Override public com.snoworca.naruserve.Router use(Object path,com.snoworca.naruserve.Router child) {
        RegistrationLog.requireNotWatching();
        DefaultRouter mounted=unwrapRouter(child);
        HttpRouter.Layer layer=registry.prepareMount(path,mounted.registry);
        RegistrationLog.admitRouter(this,new RegistrationLog.Entry(RegistrationKind.ROUTER_MOUNT,null,path,child,mounted),
                ()->registry.apply(layer));
        return this;
    }
    @Override public com.snoworca.naruserve.Router use(Object path,ErrorMiddleware callback) {
        RegistrationLog.requireNotWatching();
        HttpRouter.Layer layer=registry.prepareError(path,callback);
        RegistrationLog.admitRouter(this,new RegistrationLog.Entry(RegistrationKind.ERROR_MIDDLEWARE,null,path,callback,null),
                ()->registry.apply(layer));
        return this;
    }
    @Override public com.snoworca.naruserve.Router param(String name,ParamHandler callback) {
        RegistrationLog.requireNotWatching();
        HttpRouter.checkParam(name,callback);
        RegistrationLog.admitRouter(this,new RegistrationLog.Entry(RegistrationKind.PARAM,null,"",callback,null),
                ()->registry.param(name,callback));
        return this;
    }
}
