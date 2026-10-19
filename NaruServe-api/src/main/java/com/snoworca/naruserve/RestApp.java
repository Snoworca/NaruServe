package com.snoworca.naruserve;

import com.snoworca.naruserve.internal.AppFactoryLoader;
import com.snoworca.naruserve.internal.DefaultRestApp;
import com.snoworca.naruserve.routing.RouterPolicy;
import com.snoworca.naruserve.spi.HttpAdapter;
import com.snoworca.naruserve.spi.JsonCodec;
import org.jspecify.annotations.Nullable;

/**
 * Route, middleware and error-handler registration plus routing policy. Every instance method except
 * {@link #route(String)} returns this app. Obtain one with {@link #create()} or {@link #builder()}.
 */
// @req IR-HTTP-005
// @req IR-APP-001
// @req IR-APP-002
// @req IR-APP-003
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
