package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.Configurer;
import com.snoworca.naruserve.Method;
import com.snoworca.naruserve.Middleware;
import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.RouteBuilder;
import com.snoworca.naruserve.RouteGroup;
import com.snoworca.naruserve.RouteHandler;
import com.snoworca.naruserve.common.RoutePaths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Buffers group declarations until their configuration has completed successfully. */
// @req FR-DX-009
final class DefaultRouteGroup implements RouteGroup {
    private final String prefix;
    private final @Nullable DefaultRouteGroup parent;
    private final List<Middleware> middleware = new ArrayList<>();
    private final List<Runnable> registrations;
    private final RestApp app;
    private boolean open = true;

    private DefaultRouteGroup(RestApp app, String prefix, @Nullable DefaultRouteGroup parent,
            List<Runnable> registrations) {
        this.app = app;
        this.prefix = prefix;
        this.parent = parent;
        this.registrations = registrations;
    }

    static void configure(RestApp app, String prefix, Configurer<RouteGroup> configurer) {
        List<Runnable> registrations = new ArrayList<>();
        DefaultRouteGroup group = new DefaultRouteGroup(app, RoutePaths.join("/", prefix), null, registrations);
        group.configure(configurer);
        for (Runnable registration : registrations) registration.run();
    }

    private void configure(Configurer<RouteGroup> configurer) {
        int firstRegistration = registrations.size();
        try {
            Objects.requireNonNull(configurer, "configurer").configure(this);
        } catch (RuntimeException | Error failure) {
            registrations.subList(firstRegistration, registrations.size()).clear();
            throw failure;
        } finally {
            open = false;
        }
    }

    private void requireOpen() {
        if (!open) throw new IllegalStateException("route groups can only be configured inside their callback");
    }

    @Override public RouteGroup use(Middleware value) {
        requireOpen();
        middleware.add(Objects.requireNonNull(value, "middleware"));
        return this;
    }

    @Override public RouteGroup group(String path, Configurer<RouteGroup> configurer) {
        requireOpen();
        new DefaultRouteGroup(app, RoutePaths.join(prefix, path), this, registrations).configure(configurer);
        return this;
    }

    @Override public RouteBuilder route(String path) {
        requireOpen();
        return new GroupRouteBuilder(RoutePaths.join(prefix, path));
    }

    private void applyMiddleware(RouteBuilder builder) {
        if (parent != null) parent.applyMiddleware(builder);
        for (Middleware value : middleware) builder.use(value);
    }

    private final class GroupRouteBuilder implements RouteBuilder {
        private final String path;
        private final List<Middleware> routeMiddleware = new ArrayList<>();

        GroupRouteBuilder(String path) { this.path = path; }

        @Override public RouteBuilder use(Middleware value) {
            requireOpen();
            routeMiddleware.add(Objects.requireNonNull(value, "middleware"));
            return this;
        }

        private RouteBuilder add(Method method, RouteHandler handler) {
            requireOpen();
            Objects.requireNonNull(handler, "handler");
            List<Middleware> snapshot = new ArrayList<>(routeMiddleware);
            registrations.add(() -> {
                RouteBuilder builder = app.route(path);
                applyMiddleware(builder);
                for (Middleware value : snapshot) builder.use(value);
                switch (method) {
                    case GET: builder.get(handler); break;
                    case POST: builder.post(handler); break;
                    case PUT: builder.put(handler); break;
                    case PATCH: builder.patch(handler); break;
                    case DELETE: builder.delete(handler); break;
                    case HEAD: builder.head(handler); break;
                    case OPTIONS: builder.options(handler); break;
                    default: throw new AssertionError(method);
                }
            });
            return this;
        }

        @Override public RouteBuilder get(RouteHandler handler) { return add(Method.GET, handler); }
        @Override public RouteBuilder post(RouteHandler handler) { return add(Method.POST, handler); }
        @Override public RouteBuilder put(RouteHandler handler) { return add(Method.PUT, handler); }
        @Override public RouteBuilder patch(RouteHandler handler) { return add(Method.PATCH, handler); }
        @Override public RouteBuilder delete(RouteHandler handler) { return add(Method.DELETE, handler); }
        @Override public RouteBuilder head(RouteHandler handler) { return add(Method.HEAD, handler); }
        @Override public RouteBuilder options(RouteHandler handler) { return add(Method.OPTIONS, handler); }
    }
}
