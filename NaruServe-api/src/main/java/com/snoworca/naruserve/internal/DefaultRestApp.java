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
import com.snoworca.naruserve.Next;
import com.snoworca.naruserve.Request;
import com.snoworca.naruserve.Response;
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
 * the policy in effect only at {@link #freeze()}, so a policy set after a registration still applies to it. After
 * a successful freeze every registration and policy call throws {@link IllegalStateException}.
 */
// @req FR-HTTP-008
// @req FR-HTTP-007
// @req IR-APP-001
// @req IR-APP-002
// @req IR-APP-003
// @req IR-APP-005
// @req IR-APP-006
// @req IR-APP-007
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
    private volatile AppState state = AppState.NEW;
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

    // shortcut: logger(l), accessLog, onStart and onStop skip the NEW-state guard; the FR-APP-002 SDS adds it.
    // @req IR-APP-006
    @Override
    public RestApp logger(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
        return this;
    }

    // @req IR-APP-007
    @Override
    public RestApp accessLog(Configurer<AccessLogOptions> configurer) {
        Objects.requireNonNull(configurer, "configurer");
        synchronized (accessLogOptions) {
            configurer.configure(accessLogOptions);
        }
        return this;
    }

    /** The app's one context, passed to every lifecycle callback. */
    // @req IR-APP-004
    public DefaultApplicationContext context() {
        return context;
    }

    // @req IR-APP-005
    @Override
    public synchronized RestApp onStart(LifecycleCallback cb) {
        startCallbacks.add(Objects.requireNonNull(cb, "cb"));
        return this;
    }

    // @req IR-APP-005
    @Override
    public synchronized RestApp onStop(LifecycleCallback cb) {
        stopCallbacks.add(Objects.requireNonNull(cb, "cb"));
        return this;
    }

    // @req IR-APP-005
    @Override
    public AppState state() {
        return state;
    }

    /**
     * Runs the onStart callbacks, freezes, binds {@code 127.0.0.1:port} and enters RUNNING.
     * shortcut: no start gate, pending stop or adapter/resource cleanup on failure; the FR-APP-009/FR-APP-010
     * SDSs replace this with the AppLifecycle state machine.
     */
    // @req IR-APP-005
    @Override
    public synchronized ServerHandle listen(int port) {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port must be in 0..65535: " + port);
        }
        HttpAdapter a = adapter;
        if (a == null) {
            throw new IllegalStateException("this app has no HttpAdapter; build it with RestApp.builder()");
        }
        if (state != AppState.NEW) {
            throw new IllegalStateException("listen() not allowed in state " + state);
        }
        state = AppState.STARTING;
        try {
            for (LifecycleCallback cb : new ArrayList<>(startCallbacks)) {
                cb.handle(context);
            }
            freeze();
            a.bind("127.0.0.1", port, dispatcher());
        } catch (RuntimeException | Error e) {
            state = AppState.FAILED;
            throw e;
        } catch (Exception e) {
            state = AppState.FAILED;
            throw new AppStartException("app start failed: " + e, e);
        }
        state = AppState.RUNNING;
        int bound = a.boundPort();
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
                return DefaultRestApp.this.state;
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
        if (state == AppState.NEW) {
            state = AppState.TERMINATED;
            return;
        }
        HttpAdapter a = adapter;
        if (state != AppState.RUNNING || a == null) {
            return;
        }
        state = AppState.STOPPING;
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
            state = AppState.TERMINATED;
        }
    }

    @Override
    public RestApp get(String path, RouteHandler h) {
        return route(Method.GET, path, h);
    }

    @Override
    public RestApp post(String path, RouteHandler h) {
        return route(Method.POST, path, h);
    }

    @Override
    public RestApp put(String path, RouteHandler h) {
        return route(Method.PUT, path, h);
    }

    @Override
    public RestApp patch(String path, RouteHandler h) {
        return route(Method.PATCH, path, h);
    }

    @Override
    public RestApp delete(String path, RouteHandler h) {
        return route(Method.DELETE, path, h);
    }

    @Override
    public RestApp head(String path, RouteHandler h) {
        return route(Method.HEAD, path, h);
    }

    @Override
    public RestApp options(String path, RouteHandler h) {
        return route(Method.OPTIONS, path, h);
    }

    // @req FR-HTTP-007
    // @req FR-HTTP-008
    @Override
    public RestApp route(Method method, String path, RouteHandler h) {
        return route(MethodSet.of(method), path, h);
    }

    // @req FR-HTTP-007
    // @req FR-HTTP-008
    @Override
    public synchronized RestApp route(MethodSet methods, String path, RouteHandler h) {
        record(methods, path, Collections.<Middleware>emptyList(), h, RouteKind.HTTP);
        return this;
    }

    // @req IR-APP-002
    @Override
    public synchronized RestApp use(Middleware mw) {
        checkNotFrozen();
        globalMiddleware.add(Objects.requireNonNull(mw, "mw"));
        return this;
    }

    // @req IR-APP-002
    @Override
    public synchronized RestApp use(String prefix, Middleware mw) {
        checkNotFrozen();
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
        return route(Method.GET, path, mw, h);
    }

    @Override
    public RestApp post(String path, Middleware mw, RouteHandler h) {
        return route(Method.POST, path, mw, h);
    }

    @Override
    public RestApp put(String path, Middleware mw, RouteHandler h) {
        return route(Method.PUT, path, mw, h);
    }

    @Override
    public RestApp patch(String path, Middleware mw, RouteHandler h) {
        return route(Method.PATCH, path, mw, h);
    }

    @Override
    public RestApp delete(String path, Middleware mw, RouteHandler h) {
        return route(Method.DELETE, path, mw, h);
    }

    @Override
    public RestApp head(String path, Middleware mw, RouteHandler h) {
        return route(Method.HEAD, path, mw, h);
    }

    @Override
    public RestApp options(String path, Middleware mw, RouteHandler h) {
        return route(Method.OPTIONS, path, mw, h);
    }

    // @req IR-APP-002
    @Override
    public RestApp route(Method method, String path, Middleware mw, RouteHandler h) {
        Objects.requireNonNull(method, "method");
        return route(MethodSet.of(method), path, mw, h);
    }

    // @req IR-APP-002
    @Override
    public synchronized RestApp route(MethodSet methods, String path, Middleware mw, RouteHandler h) {
        record(methods, path, Collections.singletonList(Objects.requireNonNull(mw, "mw")), h, RouteKind.HTTP);
        return this;
    }

    // @req IR-APP-003
    @Override
    public synchronized RestApp onError(ErrorHandler handler) {
        checkNotFrozen();
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
        checkNotFrozen();
        if (policy == null) {
            throw new NullPointerException("policy");
        }
        this.policy = policy;
        return this;
    }

    /** Internal hook for a WebSocket route (kind WEBSOCKET, counted as GET); not on {@link RestApp}. */
    // @req FR-HTTP-008
    public synchronized DefaultRestApp webSocketRoute(String path, RouteHandler h) {
        record(MethodSet.of(Method.GET), path, Collections.<Middleware>emptyList(), h, RouteKind.WEBSOCKET);
        return this;
    }

    private void record(MethodSet methods, String path, List<Middleware> route, RouteHandler h, RouteKind kind) {
        checkNotFrozen();
        if (methods == null || h == null) {
            throw new NullPointerException(methods == null ? "methods" : "h");
        }
        RoutePattern.parse(path);
        registrations.add(new Registration(methods, path, route.isEmpty() ? h : new RouteChain(route, h), kind));
    }

    /**
     * Route middleware then the handler, on the calling thread.
     * shortcut: no once-only Next or error-handler dispatch; the FR-APP-003/FR-APP-004 pipeline replaces this.
     */
    // @req IR-APP-002
    static final class RouteChain implements RouteHandler {
        final List<Middleware> middleware;
        final RouteHandler handler;

        RouteChain(List<Middleware> middleware, RouteHandler handler) {
            this.middleware = Collections.unmodifiableList(new ArrayList<>(middleware));
            this.handler = handler;
        }

        @Override
        public void handle(Request req, Response res) throws Exception {
            step(0, req, res);
        }

        private void step(int i, Request req, Response res) throws Exception {
            if (i == middleware.size()) {
                handler.handle(req, res);
                return;
            }
            middleware.get(i).handle(req, res, new Next() {
                @Override
                public void run() {
                    try {
                        step(i + 1, req, res);
                    } catch (Exception e) {
                        throw DefaultRestApp.<RuntimeException>sneaky(e);
                    }
                }

                @Override
                public void error(Throwable error) {
                    throw DefaultRestApp.<RuntimeException>sneaky(error);
                }
            });
        }
    }

    // Rethrows a checked exception unchanged through Next.run(), which declares none.
    @SuppressWarnings("unchecked")
    private static <E extends Throwable> E sneaky(Throwable t) throws E {
        throw (E) t;
    }

    private void checkNotFrozen() {
        if (dispatcher != null) {
            throw new IllegalStateException("RestApp is frozen; register routes and set the policy before freeze");
        }
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
        dispatcher = new Dispatcher(router);
    }

    /** The dispatcher built by a successful {@link #freeze()}; {@link IllegalStateException} before that. */
    // @req FR-HTTP-008
    public synchronized ExchangeHandler dispatcher() {
        if (dispatcher == null) {
            throw new IllegalStateException("RestApp is not frozen; call freeze() first");
        }
        return dispatcher;
    }

    /** Snapshot of the recorded registrations, for tests. */
    // @req FR-HTTP-008
    synchronized List<Registration> registrations() {
        return Collections.unmodifiableList(new ArrayList<>(registrations));
    }

    /** Builder bound to one path; each method registers through this app, so freeze rules apply. */
    // @req FR-HTTP-008
    final class DefaultRouteBuilder implements RouteBuilder {
        private final String path;
        // Guarded by DefaultRestApp.this; each registration snapshots it.
        private final List<Middleware> middleware = new ArrayList<>();

        DefaultRouteBuilder(String path) {
            this.path = path;
        }

        private RouteBuilder add(Method method, RouteHandler h) {
            synchronized (DefaultRestApp.this) {
                record(MethodSet.of(method), path, middleware, h, RouteKind.HTTP);
            }
            return this;
        }

        // @req IR-APP-002
        @Override
        public RouteBuilder use(Middleware mw) {
            synchronized (DefaultRestApp.this) {
                checkNotFrozen();
                middleware.add(Objects.requireNonNull(mw, "mw"));
            }
            return this;
        }

        @Override
        public RouteBuilder get(RouteHandler h) {
            return add(Method.GET, h);
        }

        @Override
        public RouteBuilder post(RouteHandler h) {
            return add(Method.POST, h);
        }

        @Override
        public RouteBuilder put(RouteHandler h) {
            return add(Method.PUT, h);
        }

        @Override
        public RouteBuilder patch(RouteHandler h) {
            return add(Method.PATCH, h);
        }

        @Override
        public RouteBuilder delete(RouteHandler h) {
            return add(Method.DELETE, h);
        }

        @Override
        public RouteBuilder head(RouteHandler h) {
            return add(Method.HEAD, h);
        }

        @Override
        public RouteBuilder options(RouteHandler h) {
            return add(Method.OPTIONS, h);
        }
    }
}
