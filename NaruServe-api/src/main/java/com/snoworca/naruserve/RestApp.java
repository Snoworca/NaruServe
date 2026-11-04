package com.snoworca.naruserve;

import com.snoworca.naruserve.internal.AppFactoryLoader;
import com.snoworca.naruserve.internal.AppStarter;
import com.snoworca.naruserve.internal.DefaultRestApp;
import com.snoworca.naruserve.routing.RouterPolicy;
import com.snoworca.naruserve.spi.HttpAdapter;
import com.snoworca.naruserve.spi.JsonCodec;
import java.io.File;
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
// @req FR-DX-012
// @req IR-STATIC-001
// @req IR-STATIC-002
// @req IR-WS-001
public interface RestApp extends HttpRoutes<RestApp> {

    /**
     * The app of the bootstrap {@link com.snoworca.naruserve.spi.AppFactory} on the class path (the
     * {@code com.snoworca.naruserve:naruserve} module). Throws {@link IllegalStateException} when none or several.
     */
    // @req FR-APP-001
    static RestApp create() {
        return AppFactoryLoader.create(RestApp.class.getClassLoader());
    }

    /**
     * Creates the app with {@link #create()}, lets {@code routes} configure it while it is still {@code NEW}, calls
     * {@link #listen(int)}, writes one {@code Listening on ...} INFO line and, on a JVM but not on Android,
     * registers a shutdown hook that stops the app on Ctrl+C or SIGTERM. Returns without blocking; the
     * {@link ServerHandle} gives the bound port, the URL and {@link ServerHandle#shutdown(long, java.util.concurrent.TimeUnit)}.
     * It binds {@code 127.0.0.1} like {@code listen}: open the LAN inside {@code routes} with
     * {@code app.server(o -> o.bindAddress("0.0.0.0"))}. An exception of {@code routes} or of {@code listen} ends the
     * app and is thrown as it is. Express counterpart: {@code app.listen(3000, callback)}.
     */
    // @req FR-DX-012
    static ServerHandle start(int port, Configurer<RestApp> routes) {
        return AppStarter.start(port, routes);
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

    /**
     * Configures HTTP routes under a shared prefix. Nested group middleware applies to every declared route;
     * configuration finishes when the callback returns. Call before listen().
     */
    // @req FR-DX-009
    RestApp group(String prefix, Configurer<RouteGroup> configurer);

    /** Registers validated HTTP CORS middleware at this position, before downstream authentication. */
    // @req FR-DX-010
    RestApp cors(Configurer<CorsOptions> configurer);

    /** Sets the router policy, built with {@link RouterPolicy#builder()}. */
    RestApp routerPolicy(RouterPolicy policy);

    /**
     * Global middleware: runs for every request, in registration order. A middleware that is also an
     * {@link Installable} (such as auth) is not added as such: {@code mw.install(this)} runs instead and registers
     * what it needs.
     */
    // @req IR-APP-002
    // @req FR-AUTH-010
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
     * Starts the app on the bind address ({@code 127.0.0.1} unless {@link #server} changed it) and {@code port}
     * ({@code 0} picks a free port) and returns once it serves.
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

    /** Runs {@code configurer} at once on the app's one access log options; calls accumulate in order. A configurer that throws part-way leaves the options it already set applied, unlike {@code multipart}, {@code webSocket} and {@code sse}, which apply a copy. */
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

    /**
     * Runs {@code configurer} at once on a copy of the app's multipart options, as {@code app.multipart(o ->
     * o.maxFileSize(1024 * 1024))}, and keeps the copy only when it returns; calls apply in order. Out-of-range
     * values throw {@link IllegalArgumentException}.
     */
    // @req IR-MULTIPART-001
    RestApp multipart(Configurer<MultipartOptions> configurer);

    /**
     * The largest urlencoded form body in bytes, {@code 1..1073741824}; default 1048576 (1 MiB), as Express
     * {@code express.urlencoded({ limit })}.
     */
    // @req IR-MULTIPART-001
    RestApp formLimit(long bytes);

    /**
     * Serves the files below {@code dir} under {@code prefix}, like Express {@code app.use(prefix,
     * express.static(dir))}. The prefix must start with {@code /} ({@link IllegalArgumentException}); a trailing
     * {@code /} is ignored. Several calls register several mounts in order; {@code dir} need not exist yet.
     */
    // @req IR-STATIC-001
    RestApp staticFiles(String prefix, File dir);

    /** As {@link #staticFiles(String, File)}, with {@code options} run at once on this mount's options. */
    // @req IR-STATIC-001
    RestApp staticFiles(String prefix, File dir, Configurer<StaticOptions> options);

    /** The app's own MIME registry; registering after {@code listen()} throws {@link IllegalStateException}. */
    // @req IR-STATIC-002
    MimeTypes mimeTypes();

    /**
     * The {@code Cache-Control} policy for responses that set none themselves; default {@code null}, which adds no
     * header.
     */
    // @req IR-STATIC-002
    RestApp defaultCache(@Nullable CachePolicy policy);

    /**
     * Runs {@code configurer} at once on the app's one server options, as {@code app.server(o ->
     * o.bindAddress("0.0.0.0"))}; calls apply in order. Out-of-range values throw {@link IllegalArgumentException}. A configurer that throws part-way leaves the options it already set applied, unlike {@code multipart}, {@code webSocket} and {@code sse}, which apply a copy.
     */
    // @req IR-ENGINE-001
    RestApp server(Configurer<ServerOptions> configurer);

    /**
     * Trusts X-Forwarded-For and X-Forwarded-Proto for {@code req.remoteAddress()} and {@code req.isSecure()}, as
     * Express {@code app.set('trust proxy', true)}; default false. Forwarded is never read.
     * <p>Security: {@code true} takes the left-most X-Forwarded-For entry, which the client controls: any client can
     * claim any address. It stays for compatibility; behind a proxy use {@link #trustProxyHops(int)} or
     * {@link #trustProxyCidrs(String...)}, which count from the right. The last of {@code trustProxy},
     * {@code trustProxyHops} and {@code trustProxyCidrs} replaces the earlier setting.
     */
    // @req IR-ENGINE-001
    // @req FR-ENGINE-008
    // @req CON-ENGINE-001
    RestApp trustProxy(boolean trust);

    /**
     * Hop mode, as Express {@code app.set('trust proxy', hops)}: the socket peer is trusted when {@code hops >= 1}, and
     * {@code req.remoteAddress()} is the {@code hops}-th X-Forwarded-For entry counted from the right (all header lines
     * combined in arrival order; the left-most when there are fewer). {@code 0} trusts nothing;
     * {@link IllegalArgumentException} below 0. Use it only when the node is reachable only through the load balancer.
     * X-Forwarded-Proto is read by the same position (one value is used as is). Call before {@link #listen(int)}.
     */
    // @req IR-ENGINE-003
    // @req SEC-ENGINE-001
    RestApp trustProxyHops(int hops);

    /**
     * CIDR mode, as Express {@code app.set('trust proxy', '10.0.0.0/8')}: the socket peer is trusted only inside one of
     * {@code cidrs} (IPv4 or IPv6 literals with an optional {@code /prefix}; an address alone is one host), and
     * {@code req.remoteAddress()} is the first X-Forwarded-For entry from the right outside every trusted CIDR (the
     * left-most when all are inside). {@link NullPointerException} for a null array or element,
     * {@link IllegalArgumentException} naming any other value; no name is resolved. An empty array trusts nothing.
     * Call before {@link #listen(int)}.
     */
    // @req IR-ENGINE-003
    // @req SEC-ENGINE-002
    RestApp trustProxyCidrs(String... cidrs);

    /** True after {@code trustProxy(true)} was the last trust call; readable in every state. */
    // @req IR-ENGINE-003
    boolean trustProxy();

    /** The hop count of hop mode; {@code 0} when hop mode is not the setting. Readable in every state. */
    // @req IR-ENGINE-003
    int trustProxyHops();

    /** The CIDRs of CIDR mode as given, unmodifiable; empty when CIDR mode is not the setting. Readable in every state. */
    // @req IR-ENGINE-003
    java.util.List<String> trustProxyCidrs();

    /**
     * Every registration so far in registration order, as Express {@code app._router.stack} but classified: a
     * call-time snapshot, unmodifiable, safe to iterate while other threads register. Readable in every state.
     */
    // @req IR-APP-008
    // @req FR-APP-025
    java.util.List<Registration> registrations();

    /**
     * Adds a watcher that each later registration (also those made after {@code listen} and through a mounted
     * {@link Router}) passes on the registering thread in the order validate, watcher, apply, record: an invalid call
     * never reaches it, and an exception it throws stops the registration and reaches the caller unchanged. Call before
     * {@link #listen(int)}.
     */
    // @req IR-APP-008
    // @req SEC-APP-002
    RestApp onRegistration(RegistrationListener listener);

    /**
     * Registers a WebSocket route, as express-ws {@code app.ws(path, (ws, req) => ...)} with the arguments reversed.
     * {@code path} uses the route pattern syntax of {@link #get}; {@code handler} runs once per connection after
     * {@code 101}.
     */
    // @req IR-WS-001
    RestApp ws(String path, WebSocketHandler handler);

    /**
     * Runs {@code configurer} at once on a copy of the app's WebSocket options, as {@code app.webSocket(o ->
     * o.maxMessageSize(64 * 1024))}, and keeps the copy only when it returns; calls apply in order.
     */
    // @req IR-WS-001
    RestApp webSocket(Configurer<WebSocketOptions> configurer);

    /**
     * Runs {@code configurer} at once on a copy of the app's SSE options, as {@code app.sse(o ->
     * o.heartbeatInterval(30, TimeUnit.SECONDS))}, and keeps the copy only when it returns; calls apply in order.
     */
    // @req IR-SSE-001
    RestApp sse(Configurer<SseOptions> configurer);

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
