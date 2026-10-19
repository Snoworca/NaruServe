package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.AccessLogOptions;
import com.snoworca.naruserve.AppStartException;
import com.snoworca.naruserve.AppState;
import com.snoworca.naruserve.Configurer;
import com.snoworca.naruserve.ConsoleLogger;
import com.snoworca.naruserve.ErrorHandler;
import com.snoworca.naruserve.LifecycleCallback;
import com.snoworca.naruserve.Logger;
import com.snoworca.naruserve.Method;
import com.snoworca.naruserve.MethodSet;
import com.snoworca.naruserve.Middleware;
import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.RouteBuilder;
import com.snoworca.naruserve.RouteHandler;
import com.snoworca.naruserve.ServerHandle;
import com.snoworca.naruserve.routing.RouteKind;
import com.snoworca.naruserve.routing.RoutePattern;
import com.snoworca.naruserve.routing.Router;
import com.snoworca.naruserve.routing.RouterPolicy;
import com.snoworca.naruserve.spi.ExchangeHandler;
import com.snoworca.naruserve.spi.HttpAdapter;
import com.snoworca.naruserve.spi.JsonCodec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * {@link RestApp} implementation. Patterns are parsed at each registration call; the {@link Router} is built with
 * the policy in effect only at {@link #freeze()}, so a policy set after a registration still applies to it. Every
 * registration and configuration call goes through {@link AppLifecycle#requireConfigurable}, which throws
 * {@link IllegalStateException} outside NEW or after a successful freeze.
 */
// @req FR-HTTP-008
// @req FR-HTTP-007
// @req IR-APP-001
// @req IR-APP-002
// @req IR-APP-003
// @req IR-APP-005
// @req IR-APP-006
// @req IR-APP-007
// @req FR-APP-002
// @req FR-APP-009
// @req FR-APP-003
public final class DefaultRestApp implements RestApp {

    /** One registration call as recorded; the router is built from these at freeze. */
    // @req FR-HTTP-008
    static final class Registration {
        final MethodSet methods;
        final String pattern;
        final RouteHandler handler;
        final RouteKind kind;

        Registration(MethodSet methods, String pattern, RouteHandler handler, RouteKind kind) {
            this.methods = methods;
            this.pattern = pattern;
            this.handler = handler;
            this.kind = kind;
        }
    }

    /** One {@code use(prefix, mw)} call as recorded. */
    // @req IR-APP-002
    static final class PrefixMiddleware {
        final String prefix;
        final Middleware middleware;

        PrefixMiddleware(String prefix, Middleware middleware) {
            this.prefix = prefix;
            this.middleware = middleware;
        }
    }

    private final @Nullable HttpAdapter adapter;
    private final @Nullable JsonCodec codec;
    private volatile Logger logger;
    private final AppLifecycle lifecycle = new AppLifecycle();
    private final DefaultApplicationContext context = new DefaultApplicationContext(this);
    private final DefaultAccessLogOptions accessLogOptions = new DefaultAccessLogOptions();

    // Guarded by this.
    private final List<Registration> registrations = new ArrayList<>();
    private final List<Middleware> globalMiddleware = new ArrayList<>();
    private final List<PrefixMiddleware> prefixMiddleware = new ArrayList<>();
    private final List<ErrorHandler> errorHandlers = new ArrayList<>();
    private final List<LifecycleCallback> startCallbacks = new ArrayList<>();
    private final List<LifecycleCallback> stopCallbacks = new ArrayList<>();
    private RouterPolicy policy = RouterPolicy.strict();
    private @Nullable Dispatcher dispatcher;

    /** For unit tests that never listen: no adapter, no codec, a {@link ConsoleLogger}. */
    // @req FR-HTTP-008
    public DefaultRestApp() {
        this.adapter = null;
        this.codec = null;
        this.logger = new ConsoleLogger();
    }

    /** The app {@link RestApp.Builder#build()} returns, in state NEW. */
    // @req IR-APP-001
    public DefaultRestApp(HttpAdapter adapter, JsonCodec codec, Logger logger) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** The adapter given to the builder; null for the no-arg test constructor. */
    public @Nullable HttpAdapter httpAdapter() {
        return adapter;
    }

    /** The codec given to the builder; null for the no-arg test constructor. */
    public @Nullable JsonCodec jsonCodec() {
        return codec;
    }

    // @req IR-APP-006
    @Override
    public Logger logger() {
        return logger;
    }

    // @req IR-APP-006
    // @req FR-APP-002
    @Override
    public synchronized RestApp logger(Logger logger) {
        lifecycle.requireConfigurable("logger");
        this.logger = Objects.requireNonNull(logger, "logger");
        return this;
    }

    // @req IR-APP-007
    @Override
    public synchronized RestApp accessLog(Configurer<AccessLogOptions> configurer) {
        lifecycle.requireConfigurable("accessLog");
        Objects.requireNonNull(configurer, "configurer");
        synchronized (accessLogOptions) {
            configurer.configure(accessLogOptions);
        }
        return this;
    }

    /** The app's state machine, for same-package tests. */
    AppLifecycle lifecycle() {
        return lifecycle;
    }

    /** The app's one context, passed to every lifecycle callback. */
    // @req IR-APP-004
    public DefaultApplicationContext context() {
        return context;
    }

    // @req IR-APP-005
    @Override
    public synchronized RestApp onStart(LifecycleCallback cb) {
        lifecycle.requireConfigurable("onStart");
        startCallbacks.add(Objects.requireNonNull(cb, "cb"));
        return this;
    }

    // @req IR-APP-005
    @Override
    public synchronized RestApp onStop(LifecycleCallback cb) {
        lifecycle.requireConfigurable("onStop");
        stopCallbacks.add(Objects.requireNonNull(cb, "cb"));
        return this;
    }

    // @req IR-APP-005
    @Override
    public AppState state() {
        return lifecycle.state();
    }

    /**
     * STARTING by compareAndSet, the onStart callbacks in order, the route-table freeze, one bind of the gated
     * handler on {@code 127.0.0.1:port}, then RUNNING. Not synchronized: requests held on the start gate and
     * onStart callbacks must not wait for this call.
     * shortcut: no pending stop or adapter/resource cleanup on failure; the FR-APP-010/FR-APP-012 SDS adds them.
     */
    // @req IR-APP-005
    // @req FR-APP-009
    @Override
    public ServerHandle listen(int port) {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port must be in 0..65535: " + port);
        }
        HttpAdapter a = adapter;
        if (a == null) {
            throw new IllegalStateException("this app has no HttpAdapter; build it with RestApp.builder()");
        }
        lifecycle.beginStart();
        int bound;
        try {
            List<LifecycleCallback> callbacks;
            synchronized (this) {
                callbacks = new ArrayList<>(startCallbacks);
            }
            for (LifecycleCallback cb : callbacks) {
                cb.handle(context);
            }
            freeze();
            a.bind("127.0.0.1", port, new GatedExchangeHandler(lifecycle, frozenDispatcher(), new AccessLog()));
            bound = a.boundPort();
        } catch (RuntimeException | Error e) {
            lifecycle.fail();
            throw e;
        } catch (Exception e) {
            lifecycle.fail();
            throw new AppStartException("app start failed: " + e, e);
        }
        lifecycle.markRunning();
        return new ServerHandle() {
            @Override
            public int port() {
                return bound;
            }

            @Override
            public String address() {
                return "127.0.0.1";
            }

            @Override
            public AppState state() {
                return lifecycle.state();
            }
        };
    }

    /**
     * RUNNING: adapter stop and release, the onStop callbacks, then TERMINATED. NEW goes straight to TERMINATED;
     * other states do nothing.
     * shortcut: no shutdown participants, owned-resource or logger close; the FR-APP-011/FR-APP-012 SDS replaces
     * this with the fixed shutdown sequence.
     */
    // @req IR-APP-005
    @Override
    public synchronized void shutdown(long timeout, TimeUnit unit) {
        if (timeout < 0) {
            throw new IllegalArgumentException("timeout must not be negative: " + timeout);
        }
        Objects.requireNonNull(unit, "unit");
        if (lifecycle.terminateIfNew()) {
            return;
        }
        HttpAdapter a = adapter;
        if (a == null || !lifecycle.beginStop()) {
            return;
        }
        try {
            a.stop(timeout, unit);
            a.release();
            for (LifecycleCallback cb : new ArrayList<>(stopCallbacks)) {
                try {
                    cb.handle(context);
                } catch (Exception e) {
                    logger.error("onStop callback failed", e);
                }
            }
        } finally {
            lifecycle.terminate();
        }
    }

    @Override
    public RestApp get(String path, RouteHandler h) {
        return register("get", MethodSet.of(Method.GET), path, null, false, h);
    }

    @Override
    public RestApp post(String path, RouteHandler h) {
        return register("post", MethodSet.of(Method.POST), path, null, false, h);
    }

    @Override
    public RestApp put(String path, RouteHandler h) {
        return register("put", MethodSet.of(Method.PUT), path, null, false, h);
    }

    @Override
    public RestApp patch(String path, RouteHandler h) {
        return register("patch", MethodSet.of(Method.PATCH), path, null, false, h);
    }

    @Override
    public RestApp delete(String path, RouteHandler h) {
        return register("delete", MethodSet.of(Method.DELETE), path, null, false, h);
    }

    @Override
    public RestApp head(String path, RouteHandler h) {
        return register("head", MethodSet.of(Method.HEAD), path, null, false, h);
    }

    @Override
    public RestApp options(String path, RouteHandler h) {
        return register("options", MethodSet.of(Method.OPTIONS), path, null, false, h);
    }

    // @req FR-HTTP-007
    // @req FR-HTTP-008
    @Override
    public RestApp route(Method method, String path, RouteHandler h) {
        lifecycle.requireConfigurable("route");
        return route(MethodSet.of(method), path, h);
    }

    // @req FR-HTTP-007
    // @req FR-HTTP-008
    @Override
    public RestApp route(MethodSet methods, String path, RouteHandler h) {
        return register("route", methods, path, null, false, h);
    }

    // @req IR-APP-002
    // @req FR-APP-003
    @Override
    public synchronized RestApp use(Middleware mw) {
        lifecycle.requireConfigurable("use");
        globalMiddleware.add(Objects.requireNonNull(mw, "mw"));
        return this;
    }

    // @req IR-APP-002
    // @req FR-APP-003
    @Override
    public synchronized RestApp use(String prefix, Middleware mw) {
        lifecycle.requireConfigurable("use");
        Objects.requireNonNull(prefix, "prefix");
        Objects.requireNonNull(mw, "mw");
        if (!prefix.startsWith("/")) {
            throw new IllegalArgumentException("middleware prefix must start with '/': " + prefix);
        }
        prefixMiddleware.add(new PrefixMiddleware(prefix, mw));
        return this;
    }

    @Override
    public RestApp get(String path, Middleware mw, RouteHandler h) {
        return register("get", MethodSet.of(Method.GET), path, mw, true, h);
    }

    @Override
    public RestApp post(String path, Middleware mw, RouteHandler h) {
        return register("post", MethodSet.of(Method.POST), path, mw, true, h);
    }

    @Override
    public RestApp put(String path, Middleware mw, RouteHandler h) {
        return register("put", MethodSet.of(Method.PUT), path, mw, true, h);
    }

    @Override
    public RestApp patch(String path, Middleware mw, RouteHandler h) {
        return register("patch", MethodSet.of(Method.PATCH), path, mw, true, h);
    }

    @Override
    public RestApp delete(String path, Middleware mw, RouteHandler h) {
        return register("delete", MethodSet.of(Method.DELETE), path, mw, true, h);
    }

    @Override
    public RestApp head(String path, Middleware mw, RouteHandler h) {
        return register("head", MethodSet.of(Method.HEAD), path, mw, true, h);
    }

    @Override
    public RestApp options(String path, Middleware mw, RouteHandler h) {
        return register("options", MethodSet.of(Method.OPTIONS), path, mw, true, h);
    }

    // @req IR-APP-002
    @Override
    public RestApp route(Method method, String path, Middleware mw, RouteHandler h) {
        lifecycle.requireConfigurable("route");
        Objects.requireNonNull(method, "method");
        return route(MethodSet.of(method), path, mw, h);
    }

    // @req IR-APP-002
    // @req FR-APP-003
    @Override
    public RestApp route(MethodSet methods, String path, Middleware mw, RouteHandler h) {
        return register("route", methods, path, mw, true, h);
    }

    /** Guard, then record one HTTP registration. */
    // @req FR-APP-002
    private synchronized RestApp register(String name, MethodSet methods, String path, @Nullable Middleware mw,
            boolean withMiddleware, RouteHandler h) {
        lifecycle.requireConfigurable(name);
        List<Middleware> route = withMiddleware ? Collections.singletonList(Objects.requireNonNull(mw, "mw"))
                : Collections.<Middleware>emptyList();
        record(name, methods, path, route, h, RouteKind.HTTP);
        return this;
    }

    // @req IR-APP-003
    // @req FR-APP-005
    @Override
    public synchronized RestApp onError(ErrorHandler handler) {
        lifecycle.requireConfigurable("onError");
        errorHandlers.add(Objects.requireNonNull(handler, "handler"));
        return this;
    }

    @Override
    public RouteBuilder route(String path) {
        return new DefaultRouteBuilder(path);
    }

    // @req FR-HTTP-008
    @Override
    public synchronized RestApp routerPolicy(RouterPolicy policy) {
        lifecycle.requireConfigurable("routerPolicy");
        if (policy == null) {
            throw new NullPointerException("policy");
        }
        this.policy = policy;
        return this;
    }

    /** Internal hook for a WebSocket route (kind WEBSOCKET, counted as GET); not on {@link RestApp}. */
    // @req FR-HTTP-008
    public synchronized DefaultRestApp webSocketRoute(String path, RouteHandler h) {
        record("webSocketRoute", MethodSet.of(Method.GET), path, Collections.<Middleware>emptyList(), h,
                RouteKind.WEBSOCKET);
        return this;
    }

    private void record(String name, MethodSet methods, String path, List<Middleware> route, RouteHandler h,
            RouteKind kind) {
        lifecycle.requireConfigurable(name);
        if (methods == null || h == null) {
            throw new NullPointerException(methods == null ? "methods" : "h");
        }
        RoutePattern.parse(path);
        registrations.add(new Registration(methods, path,
                route.isEmpty() ? h : new MiddlewarePipeline.RouteChain(route, h), kind));
    }

    /**
     * Builds and freezes the router from the registrations, then the dispatcher. A {@code RoutingConfigException}
     * propagates unchanged and leaves the app unfrozen. Later calls after success do nothing.
     */
    // @req FR-HTTP-008
    public synchronized void freeze() {
        if (dispatcher != null) {
            return;
        }
        Router<RouteHandler> router = new Router<>(policy);
        for (Registration r : registrations) {
            if (r.kind == RouteKind.WEBSOCKET) {
                router.addWebSocket(r.pattern, r.handler);
            } else {
                router.add(r.methods, r.pattern, r.handler);
            }
        }
        router.freeze();
        MiddlewarePipeline pipeline = new MiddlewarePipeline(policy);
        for (Middleware mw : globalMiddleware) {
            pipeline.addGlobal(mw);
        }
        for (PrefixMiddleware pm : prefixMiddleware) {
            pipeline.addPrefix(pm.prefix, pm.middleware);
        }
        dispatcher = new Dispatcher(router, pipeline, new ErrorPath(errorHandlers, new SafeLogger(logger)),
                new AccessLog());
        lifecycle.markFrozen();
    }

    /** The dispatcher built by a successful {@link #freeze()}; {@link IllegalStateException} before that. */
    // @req FR-HTTP-008
    // @req FR-APP-009
    public ExchangeHandler dispatcher() {
        return frozenDispatcher();
    }

    private synchronized Dispatcher frozenDispatcher() {
        Dispatcher d = dispatcher;
        if (d == null) {
            throw new IllegalStateException("RestApp is not frozen; call freeze() first");
        }
        return d;
    }

    /** Snapshot of the recorded registrations, for tests. */
    // @req FR-HTTP-008
    synchronized List<Registration> registrations() {
        return Collections.unmodifiableList(new ArrayList<>(registrations));
    }

    /** Builder bound to one path; each method registers through this app, so freeze rules apply. */
    // @req FR-HTTP-008
    // @req FR-APP-002
    final class DefaultRouteBuilder implements RouteBuilder {
        private final String path;
        // Guarded by DefaultRestApp.this; each registration snapshots it.
        private final List<Middleware> middleware = new ArrayList<>();

        DefaultRouteBuilder(String path) {
            this.path = path;
        }

        private RouteBuilder add(String name, Method method, RouteHandler h) {
            synchronized (DefaultRestApp.this) {
                record(name, MethodSet.of(method), path, middleware, h, RouteKind.HTTP);
            }
            return this;
        }

        // @req IR-APP-002
        // @req FR-APP-003
        @Override
        public RouteBuilder use(Middleware mw) {
            synchronized (DefaultRestApp.this) {
                lifecycle.requireConfigurable("use");
                middleware.add(Objects.requireNonNull(mw, "mw"));
            }
            return this;
        }

        @Override
        public RouteBuilder get(RouteHandler h) {
            return add("get", Method.GET, h);
        }

        @Override
        public RouteBuilder post(RouteHandler h) {
            return add("post", Method.POST, h);
        }

        @Override
        public RouteBuilder put(RouteHandler h) {
            return add("put", Method.PUT, h);
        }

        @Override
        public RouteBuilder patch(RouteHandler h) {
            return add("patch", Method.PATCH, h);
        }

        @Override
        public RouteBuilder delete(RouteHandler h) {
            return add("delete", Method.DELETE, h);
        }

        @Override
        public RouteBuilder head(RouteHandler h) {
            return add("head", Method.HEAD, h);
        }

        @Override
        public RouteBuilder options(RouteHandler h) {
            return add("options", Method.OPTIONS, h);
        }
    }
}
