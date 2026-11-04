package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.*;
import java.util.*;

/** Ordered HTTP registry. Publication locks never surround user callbacks. */
// @req FR-APP-021 AC-1 AC-2 AC-3 AC-4 AC-5
public final class HttpRouter {
    volatile Layer[] layers=new Layer[0];
    private final boolean caseSensitive,strict;
    private volatile com.snoworca.naruserve.routing.RouterPolicy policy;
    private HttpPathSpec path(Object source,boolean end,boolean strict) {
        HttpPathSpec path=new HttpPathSpec(source,end,caseSensitive,strict);
        com.snoworca.naruserve.routing.RouterPolicy explicit=policy;
        return explicit==null?path:path.withPolicy(explicit);
    }
    // Configuration occurs before listen; retain Route identity while atomically replacing matcher snapshots.
    public synchronized void configurePolicy(com.snoworca.naruserve.routing.RouterPolicy policy) {
        Objects.requireNonNull(policy,"policy");Layer[] configured=new Layer[layers.length];
        for(int i=0;i<layers.length;i++)configured[i]=new Layer(layers[i],layers[i].path.withPolicy(policy));
        this.policy=policy;layers=configured;
    }
    final boolean mergeParams;
    volatile Map<String,ParamHandler[]> paramHooks=Collections.emptyMap();
    public HttpRouter() { this(false,false,false); }
    public HttpRouter(boolean mergeParams,boolean caseSensitive,boolean strict) {
        this.mergeParams=mergeParams;this.caseSensitive=caseSensitive;this.strict=strict;
    }
    public synchronized void param(String name,ParamHandler callback) {
        checkParam(name,callback);
        Map<String,ParamHandler[]> copy=new LinkedHashMap<>(paramHooks);
        ParamHandler[] old=copy.get(name);if(old==null)old=new ParamHandler[0];
        ParamHandler[] next=Arrays.copyOf(old,old.length+1);next[old.length]=callback;copy.put(name,next);
        paramHooks=Collections.unmodifiableMap(copy);
    }
    // FR-APP-021 AC-3; SEC-APP-002: each prepare* validates every input and builds the unpublished layer, so the
    // registration watcher runs between validation and apply(Layer), and apply can no longer fail for an input.
    public void error(Object path,ErrorMiddleware callback) { apply(prepareError(path,callback)); }
    // @req SEC-APP-002
    Layer prepareError(Object path,ErrorMiddleware callback) {
        Objects.requireNonNull(callback,"callback");
        return new Layer(path(path,false,false),callback);
    }
    public void mount(Object path,HttpRouter child) { apply(prepareMount(path,child)); }
    // @req SEC-APP-002
    Layer prepareMount(Object path,HttpRouter child) {
        Objects.requireNonNull(child,"router");
        return new Layer(path(path,false,false),null,null,child);
    }
    public RouteBuilder route(Object path) { return route(path,DIRECT); }
    /** A builder whose method registrations pass through {@code hook}, which must run the apply it receives. */
    // @req FR-APP-025
    // @req SEC-APP-002
    RouteBuilder route(Object path,BuilderHook hook) {
        Route route=new Route(path(path,true,strict));
        append(new Layer(route.path,route,null));
        return new Builder(route,Objects.requireNonNull(hook,"hook"));
    }
    public void method(String token,Object path,Middleware... callbacks) { apply(prepareMethod(token,path,callbacks)); }
    // @req SEC-APP-002
    Layer prepareMethod(String token,Object path,Middleware[] callbacks) {
        String method=method(token); Middleware[] checked=callbacks(callbacks);
        Route route=new Route(path(path,true,strict));
        route.append(method,checked); return new Layer(route.path,route,null);
    }
    void methods(MethodSet methods,Object path,Middleware[] callbacks) { apply(prepareMethods(methods,path,callbacks)); }
    // @req SEC-APP-002
    Layer prepareMethods(MethodSet methods,Object path,Middleware[] callbacks) {
        Middleware[] checked=callbacks(callbacks);
        Route route=new Route(path(path,true,strict));
        for(Method method:Objects.requireNonNull(methods,"methods")) route.append(method.name(),checked);
        return new Layer(route.path,route,null);
    }
    public void use(Object path,Middleware middleware) { apply(prepareUse(path,middleware)); }
    // @req SEC-APP-002
    Layer prepareUse(Object path,Middleware middleware) {
        Objects.requireNonNull(middleware,"middleware");
        return new Layer(path(path,false,false),null,middleware);
    }
    /** Publishes a layer a prepare* method built. */
    // @req SEC-APP-002
    void apply(Layer layer) { append(layer); }
    static void checkParam(String name,ParamHandler callback) {
        Objects.requireNonNull(name,"name");Objects.requireNonNull(callback,"callback");
    }
    /** Runs the apply of one builder method registration; the app and Router hooks watch and record around it. */
    // @req SEC-APP-002
    @FunctionalInterface
    interface BuilderHook { void admit(@org.jspecify.annotations.Nullable String method,Middleware[] callbacks,Runnable apply); }
    private static final BuilderHook DIRECT=(method,callbacks,apply)->apply.run();
    private synchronized void append(Layer layer) {
        Layer[] copy=Arrays.copyOf(layers,layers.length+1); copy[copy.length-1]=layer; layers=copy;
    }
    static String method(String token) {
        Objects.requireNonNull(token,"methodToken");
        if(!DefaultResponse.isToken(token)) throw new IllegalArgumentException("invalid method token: "+token);
        return token.toUpperCase(Locale.ROOT);
    }
    static Middleware[] callbacks(Middleware[] callbacks) {
        Objects.requireNonNull(callbacks,"callbacks");
        if(callbacks.length==0) throw new IllegalArgumentException("at least one callback is required");
        Middleware[] copy=callbacks.clone(); for(Middleware callback:copy) Objects.requireNonNull(callback,"callback"); return copy;
    }
    static final class Layer {
        final HttpPathSpec path; final Route route; final Middleware middleware; final HttpRouter child;
        final ErrorMiddleware error;
        Layer(HttpPathSpec path,Route route,Middleware middleware) { this(path,route,middleware,null); }
        Layer(HttpPathSpec path,Route route,Middleware middleware,HttpRouter child) {
            this.path=path;this.route=route;this.middleware=middleware;this.child=child;this.error=null;
        }
        Layer(Layer source,HttpPathSpec path) {
            this.path=path;this.route=source.route;this.middleware=source.middleware;this.child=source.child;this.error=source.error;
        }
        Layer(HttpPathSpec path,ErrorMiddleware error) {
            this.path=path;this.route=null;this.middleware=null;this.child=null;this.error=error;
        }
    }
    static final class Handler {
        final String method; final Middleware callback;
        Handler(String method,Middleware callback) { this.method=method; this.callback=callback; }
    }
    static final class Route {
        final HttpPathSpec path; volatile Handler[] handlers=new Handler[0];
        Route(HttpPathSpec path) { this.path=path; }
        synchronized void append(String token,Middleware[] callbacks) {
            Handler[] copy=Arrays.copyOf(handlers,handlers.length+callbacks.length);
            for(int i=0;i<callbacks.length;i++) copy[handlers.length+i]=new Handler(token,callbacks[i]);
            handlers=copy;
        }
        boolean handles(String token) { return has(token) || token.equals("HEAD") && !has("HEAD") && has("GET"); }
        boolean has(String token) { for(Handler handler:handlers)if(handler.method.equals(token)||handler.method.equals("*"))return true;return false; }
        Set<String> methods() {
            Set<String> result=new TreeSet<>();
            for(Handler handler:handlers)if(!handler.method.isEmpty() && !handler.method.equals("*"))result.add(handler.method);
            if(result.contains("GET"))result.add("HEAD");return result;
        }
    }
    private static final class Builder implements RouteBuilder {
        final Route route; final BuilderHook hook;
        Builder(Route route,BuilderHook hook) { this.route=route;this.hook=hook; }
        public RouteBuilder method(String token,Middleware... callbacks) {
            String method=HttpRouter.method(token);Middleware[] checked=HttpRouter.callbacks(callbacks);
            hook.admit(method,checked,()->route.append(method,checked));return this;
        }
        // FR-APP-025 AC-1: a route-level use is a builder method too; the hook watches it (method null) before append.
        public RouteBuilder use(Middleware callback) {
            Middleware[] checked=HttpRouter.callbacks(new Middleware[]{callback});
            hook.admit(null,checked,()->route.append("",checked));return this;
        }
        public RouteBuilder get(RouteHandler callback) { return method("GET",HttpRoutes.terminal(callback)); }
        public RouteBuilder post(RouteHandler callback) { return method("POST",HttpRoutes.terminal(callback)); }
        public RouteBuilder put(RouteHandler callback) { return method("PUT",HttpRoutes.terminal(callback)); }
        public RouteBuilder patch(RouteHandler callback) { return method("PATCH",HttpRoutes.terminal(callback)); }
        public RouteBuilder delete(RouteHandler callback) { return method("DELETE",HttpRoutes.terminal(callback)); }
        public RouteBuilder head(RouteHandler callback) { return method("HEAD",HttpRoutes.terminal(callback)); }
        public RouteBuilder options(RouteHandler callback) { return method("OPTIONS",HttpRoutes.terminal(callback)); }
    }
}
