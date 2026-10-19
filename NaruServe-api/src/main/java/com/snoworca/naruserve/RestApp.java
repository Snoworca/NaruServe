package com.snoworca.naruserve;

import com.snoworca.naruserve.internal.AppFactoryLoader;
import com.snoworca.naruserve.internal.DefaultRestApp;
import com.snoworca.naruserve.routing.RouterPolicy;
import com.snoworca.naruserve.spi.HttpAdapter;
import com.snoworca.naruserve.spi.JsonCodec;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * Route, middleware, error-handler and lifecycle registration, routing policy, logger, access log options and
 * the lifecycle itself. Every registration and configuration method returns this app. Obtain one with
 * {@link #create()} or {@link #builder()}.
 */
// @req IR-HTTP-005
// @req IR-APP-001
// @req IR-APP-002
// @req IR-APP-003
// @req IR-APP-005
// @req IR-APP-006
// @req IR-APP-007
// @req FR-APP-001
public interface RestApp {

    /**
     * The app of the bootstrap {@link com.snoworca.naruserve.spi.AppFactory} on the class path (the
     * {@code com.snoworca.naruserve:NaruServe} module). Throws {@link IllegalStateException} when none or several.
     */
    // @req FR-APP-001
    static RestApp create() {
        return AppFactoryLoader.create(RestApp.class.getClassLoader());
    }

    /** A new builder for an app with an explicit adapter, JSON codec and logger. */
    // @req IR-APP-001
    static Builder builder() {
        return new Builder();
    }

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

    /** Global middleware: runs for every request, in registration order. */
    // @req IR-APP-002
    RestApp use(Middleware mw);

    /** Prefix middleware: runs for paths under {@code prefix}, which must start with {@code /}. */
    // @req IR-APP-002
    RestApp use(String prefix, Middleware mw);

    /** Route with one route middleware {@code mw} before {@code h}; same for the other methods below. */
    // @req IR-APP-002
    RestApp get(String path, Middleware mw, RouteHandler h);

    RestApp post(String path, Middleware mw, RouteHandler h);

    RestApp put(String path, Middleware mw, RouteHandler h);

    RestApp patch(String path, Middleware mw, RouteHandler h);

    RestApp delete(String path, Middleware mw, RouteHandler h);

    RestApp head(String path, Middleware mw, RouteHandler h);

    RestApp options(String path, Middleware mw, RouteHandler h);

    RestApp route(Method method, String path, Middleware mw, RouteHandler h);

    RestApp route(MethodSet methods, String path, Middleware mw, RouteHandler h);

    /** Appends {@code handler} to the error handler chain. */
    // @req IR-APP-003
    RestApp onError(ErrorHandler handler);

    /** Appends {@code cb} to the callbacks {@link #listen(int)} runs, in order, before the app serves. */
    // @req IR-APP-005
    RestApp onStart(LifecycleCallback cb);

    /** Appends {@code cb} to the callbacks {@link #shutdown(long, TimeUnit)} runs after serving stopped. */
    // @req IR-APP-005
    RestApp onStop(LifecycleCallback cb);

    /**
     * Starts the app on {@code 127.0.0.1:port} ({@code 0} picks a free port) and returns once it serves.
     * {@link IllegalArgumentException} for a port outside {@code 0..65535}, before any state change.
     */
    // @req IR-APP-005
    ServerHandle listen(int port);

    /** Stops serving within {@code timeout}, runs the onStop callbacks and ends in {@link AppState#TERMINATED}. */
    // @req IR-APP-005
    void shutdown(long timeout, TimeUnit unit);

    /** The current lifecycle state. */
    // @req IR-APP-005
    AppState state();

    /** The app logger; readable in every state. */
    // @req IR-APP-006
    Logger logger();

    /** Replaces the app logger. */
    // @req IR-APP-006
    RestApp logger(Logger logger);

    /** Runs {@code configurer} at once on the app's one access log options; calls accumulate in order. */
    // @req IR-APP-007
    RestApp accessLog(Configurer<AccessLogOptions> configurer);

    /** Replaces the JSON codec that {@code req.body} and {@code res.json} use. */
    // @req IR-JSON-002
    RestApp jsonCodec(JsonCodec codec);

    /**
     * The largest JSON request body in bytes, {@code 1..1073741824}; default 1048576 (1 MiB), as Express
     * {@code express.json({ limit })}. A larger body is answered 413.
     */
    // @req IR-JSON-002
    RestApp jsonLimit(long bytes);

    /** Explicit app assembly; each setter returns this builder and each {@link #build()} returns a new app. */
    // @req IR-APP-001
    final class Builder {
        private @Nullable HttpAdapter adapter;
        private @Nullable JsonCodec codec;
        private @Nullable Logger logger;

        private Builder() {
        }

        // @req IR-APP-001
        public Builder httpAdapter(HttpAdapter adapter) {
            this.adapter = java.util.Objects.requireNonNull(adapter, "adapter");
            return this;
        }

        // @req IR-APP-001
        public Builder jsonCodec(JsonCodec codec) {
            this.codec = java.util.Objects.requireNonNull(codec, "codec");
            return this;
        }

        // @req IR-APP-001
        public Builder logger(Logger logger) {
            this.logger = java.util.Objects.requireNonNull(logger, "logger");
            return this;
        }

        /** A new app in state NEW; a new {@link ConsoleLogger} when {@link #logger} was not called. */
        // @req IR-APP-001
        public RestApp build() {
            HttpAdapter a = adapter;
            if (a == null) {
                throw new IllegalStateException("RestApp.builder() needs httpAdapter(...) before build()");
            }
            JsonCodec c = codec;
            if (c == null) {
                throw new IllegalStateException("RestApp.builder() needs jsonCodec(...) before build()");
            }
            Logger l = logger;
            return new DefaultRestApp(a, c, l != null ? l : new ConsoleLogger());
        }
    }
}
