package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.Method;
import com.snoworca.naruserve.MethodSet;
import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.RouteBuilder;
import com.snoworca.naruserve.RouteHandler;
import com.snoworca.naruserve.routing.RouteKind;
import com.snoworca.naruserve.routing.RoutePattern;
import com.snoworca.naruserve.routing.Router;
import com.snoworca.naruserve.routing.RouterPolicy;
import com.snoworca.naruserve.spi.ExchangeHandler;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * {@link RestApp} implementation. Patterns are parsed at each registration call; the {@link Router} is built with
 * the policy in effect only at {@link #freeze()}, so a policy set after a registration still applies to it. After
 * a successful freeze every registration and policy call throws {@link IllegalStateException}.
 */
// @req FR-HTTP-008
// @req FR-HTTP-007
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

    // Guarded by this.
    private final List<Registration> registrations = new ArrayList<>();
    private RouterPolicy policy = RouterPolicy.strict();
    private @Nullable Dispatcher dispatcher;

    /** Policy {@link RouterPolicy#strict()} until {@link #routerPolicy} is called. */
    // @req FR-HTTP-008
    public DefaultRestApp() {
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
        record(methods, path, h, RouteKind.HTTP);
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
        record(MethodSet.of(Method.GET), path, h, RouteKind.WEBSOCKET);
        return this;
    }

    private void record(MethodSet methods, String path, RouteHandler h, RouteKind kind) {
        checkNotFrozen();
        if (methods == null || h == null) {
            throw new NullPointerException(methods == null ? "methods" : "h");
        }
        RoutePattern.parse(path);
        registrations.add(new Registration(methods, path, h, kind));
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

        DefaultRouteBuilder(String path) {
            this.path = path;
        }

        private RouteBuilder add(Method method, RouteHandler h) {
            route(method, path, h);
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
