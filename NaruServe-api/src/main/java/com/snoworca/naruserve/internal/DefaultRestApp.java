package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.AccessLogOptions;
import com.snoworca.naruserve.AppStartException;
import com.snoworca.naruserve.AppState;
import com.snoworca.naruserve.CachePolicy;
import com.snoworca.naruserve.Configurer;
import com.snoworca.naruserve.CorsOptions;
import com.snoworca.naruserve.ConsoleLogger;
import com.snoworca.naruserve.ErrorHandler;
import com.snoworca.naruserve.LifecycleCallback;
import com.snoworca.naruserve.Logger;
import com.snoworca.naruserve.Method;
import com.snoworca.naruserve.MimeTypes;
import com.snoworca.naruserve.MethodSet;
import com.snoworca.naruserve.Middleware;
import com.snoworca.naruserve.MultipartOptions;
import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.RouteBuilder;
import com.snoworca.naruserve.RouteGroup;
import com.snoworca.naruserve.RouteHandler;
import com.snoworca.naruserve.ServerHandle;
import com.snoworca.naruserve.ServerOptions;
import com.snoworca.naruserve.StaticOptions;
import com.snoworca.naruserve.WebSocketHandler;
import com.snoworca.naruserve.SseOptions;
import com.snoworca.naruserve.WebSocketOptions;
import com.snoworca.naruserve.routing.RouteKind;
import com.snoworca.naruserve.routing.RoutePattern;
import com.snoworca.naruserve.routing.Router;
import com.snoworca.naruserve.routing.RouterPolicy;
import com.snoworca.naruserve.spi.ExchangeHandler;
import com.snoworca.naruserve.spi.HttpAdapter;
import com.snoworca.naruserve.spi.JsonCodec;
import com.snoworca.naruserve.spi.ServerSettings;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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
// @req IR-ENGINE-001
// @req IR-ENGINE-002
// @req FR-ENGINE-002
// @req FR-ENGINE-004
// @req IR-APP-002
// @req IR-APP-003
// @req IR-APP-005
// @req IR-APP-006
// @req IR-APP-007
// @req FR-APP-002
// @req FR-APP-009
// @req FR-APP-003
// @req FR-APP-007
// @req FR-APP-008
// @req FR-APP-010
// @req FR-APP-011
// @req FR-APP-012
// @req IR-JSON-002
// @req IR-WS-001
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

    /** One use call as recorded; a null prefix denotes global middleware. */
    // @req IR-APP-002
    static final class MiddlewareRegistration {
        final @Nullable String prefix;
        final Middleware middleware;

        MiddlewareRegistration(@Nullable String prefix, Middleware middleware) {
            this.prefix = prefix;
            this.middleware = middleware;
        }
    }

    private final @Nullable HttpAdapter adapter;
    // Written only in NEW under this; the Dispatcher reads both once at freeze.
    private volatile @Nullable JsonCodec codec;
    private volatile long jsonLimit = JsonBodyReader.DEFAULT_LIMIT;
    private volatile MultipartSettings multipartSettings = new MultipartSettings();
    private volatile long formLimit = 1048576L;
    private volatile DefaultWebSocketOptions webSocketOptions = new DefaultWebSocketOptions();
    /** The copy freeze took for the upgrade step; null before freeze. */
    volatile @Nullable DefaultWebSocketOptions frozenWebSocketOptions;
    private volatile DefaultSseOptions sseOptions = new DefaultSseOptions();
    /** The copy freeze took for the SSE streams; null before freeze. */
    volatile @Nullable DefaultSseOptions frozenSseOptions;
    private final List<StaticMount> staticMounts = new ArrayList<>();
    private volatile @Nullable CachePolicy defaultCache;
    private volatile Logger logger;
    private final AppLifecycle lifecycle = new AppLifecycle();
    private final DefaultMimeTypes mimeTypes = new DefaultMimeTypes(lifecycle);
    private final DefaultApplicationContext context = new DefaultApplicationContext(lifecycle, this);
    private final DefaultAccessLogOptions accessLogOptions = new DefaultAccessLogOptions();
    private final DefaultServerOptions serverOptions = new DefaultServerOptions();
    // Written only in NEW; the Dispatcher reads it once at freeze.
    private volatile boolean trustProxy;
    private final ShutdownParticipants shutdownParticipants = new ShutdownParticipants();
    private final SpoolRegistry spool = new SpoolRegistry();

    {
        // Deletes the temp uploads still open once in-flight requests finished or were force-closed.
        shutdownParticipants.add(ShutdownParticipants.Phase.AFTER_ON_STOP, spool::closeAndDeleteAll);
    }
    // Set by the one listen() that won beginStart.
    private volatile boolean bindAttempted;
    /** The adapter's bound port once bind returned, 0 before; named in the WebSocket sender and idle threads. */
    private volatile int boundPort;
    /** The WebSocket session registry; null before freeze. Guarded by this. */
    private @Nullable WebSocketRegistry webSocketRegistry;
    /** The SSE stream registry; null before freeze. Guarded by this. */
    private @Nullable SseRegistry sseRegistry;
    // The thread inside listen() or the shutdown sequence; a shutdown() from it must not wait for itself. Each owner
    // clears only its own entry, so listen() returning never wipes the identity a racing shutdown thread just set.
    private final AtomicReference<@Nullable Thread> lifecycleThread = new AtomicReference<>();
    /** Makes the thread a shutdown() from a request runs the sequence on; a test seam. */
    ThreadFactory shutdownThreads = r -> new Thread(r, "naruserve-shutdown");

    // Guarded by this.
    private final List<Registration> registrations = new ArrayList<>();
    private final List<MiddlewareRegistration> middlewareRegistrations = new ArrayList<>();
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

    /**
     * The app's one codec: the builder's, or the last {@link #jsonCodec(JsonCodec)}; null for the no-arg test
     * constructor. Every JSON feature reads it here.
     */
    // @req IR-JSON-002
    public @Nullable JsonCodec jsonCodec() {
        return codec;
    }

    // @req IR-JSON-002
    @Override
    public synchronized RestApp jsonCodec(JsonCodec codec) {
        lifecycle.requireConfigurable("jsonCodec");
        this.codec = Objects.requireNonNull(codec, "codec");
        return this;
    }

    /** The JSON body limit in bytes; 1048576 unless {@link #jsonLimit(long)} changed it. */
    // @req IR-JSON-002
    public long jsonLimit() {
        return jsonLimit;
    }

    // @req IR-JSON-002
    @Override
    public synchronized RestApp jsonLimit(long bytes) {
        lifecycle.requireConfigurable("jsonLimit");
        this.jsonLimit = byteLimit("jsonLimit", bytes);
        return this;
    }

    /** A field-by-field copy of an options object. */
    private interface Copier<T> {
        T copy();
    }

    /** Runs {@code configurer} on a copy, so one that throws leaves the current options untouched; returns the copy. */
    private <T> T reconfigure(String name, Configurer<? super T> configurer, Copier<T> copier) {
        lifecycle.requireConfigurable(name);
        Objects.requireNonNull(configurer, "configurer");
        T next = copier.copy();
        configurer.configure(next);
        return next;
    }

    /** The current multipart settings; the defaults unless {@link #multipart(Configurer)} changed them. */
    // @req IR-MULTIPART-001
    public MultipartSettings multipartSettings() {
        return multipartSettings;
    }

    // @req IR-MULTIPART-001
    @Override
    public synchronized RestApp multipart(Configurer<MultipartOptions> configurer) {
        this.multipartSettings = reconfigure("multipart", configurer, multipartSettings::copy);
        return this;
    }

    /** The current WebSocket options; the defaults unless {@link #webSocket(Configurer)} changed them. */
    // @req IR-WS-001
    public DefaultWebSocketOptions webSocketOptions() {
        return webSocketOptions;
    }

    // @req IR-WS-001
    @Override
    public synchronized RestApp webSocket(Configurer<WebSocketOptions> configurer) {
        this.webSocketOptions = reconfigure("webSocket", configurer, webSocketOptions::copy);
        return this;
    }

    /** The current SSE options; the defaults unless {@link #sse(Configurer)} changed them. */
    // @req IR-SSE-001
    public DefaultSseOptions sseOptions() {
        return sseOptions;
    }

    // @req IR-SSE-001
    @Override
    public synchronized RestApp sse(Configurer<SseOptions> configurer) {
        this.sseOptions = reconfigure("sse", configurer, sseOptions::copy);
        return this;
    }

    // @req IR-WS-001
    @Override
    public synchronized RestApp ws(String path, WebSocketHandler handler) {
        lifecycle.requireConfigurable("ws");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(handler, "handler");
        return webSocketRoute(path, new WebSocketRoute(handler));
    }

    /** {@code bytes} checked against 1..{@link JsonBodyReader#MAX_LIMIT}, the range of every body limit. */
    private static long byteLimit(String name, long bytes) {
        if (bytes < 1 || bytes > JsonBodyReader.MAX_LIMIT) {
            throw new IllegalArgumentException(name + " must be in 1.." + JsonBodyReader.MAX_LIMIT + " bytes: " + bytes);
        }
        return bytes;
    }

    /** The urlencoded form body limit in bytes; 1048576 unless {@link #formLimit(long)} changed it. */
    // @req IR-MULTIPART-001
    public long formLimit() {
        return formLimit;
    }

    // @req IR-MULTIPART-001
    @Override
    public synchronized RestApp formLimit(long bytes) {
        lifecycle.requireConfigurable("formLimit");
        this.formLimit = byteLimit("formLimit", bytes);
        return this;
    }

    // @req IR-STATIC-001
    @Override
    public RestApp staticFiles(String prefix, File dir) {
        return addStatic(prefix, dir, null);
    }

    // @req IR-STATIC-001
    @Override
    public RestApp staticFiles(String prefix, File dir, Configurer<StaticOptions> options) {
        return addStatic(prefix, dir, Objects.requireNonNull(options, "options"));
    }

    /**
     * Registers one mount after the configurer ran on fresh options; a throwing configurer registers nothing. The
     * directory is not checked here: a missing root serves nothing.
     */
    // @req IR-STATIC-001
    synchronized RestApp addStatic(String prefix, File dir, @Nullable Configurer<StaticOptions> options) {
        lifecycle.requireConfigurable("staticFiles");
        Objects.requireNonNull(prefix, "prefix");
        Objects.requireNonNull(dir, "dir");
        if (!prefix.startsWith("/")) {
            throw new IllegalArgumentException("static prefix must start with '/': " + prefix);
        }
        String p = prefix.length() > 1 && prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
        DefaultStaticOptions o = new DefaultStaticOptions();
        if (options != null) {
            options.configure(o);
        }
        staticMounts.add(new StaticMount(p, dir, o));
        return this;
    }

    /** The registered mounts in registration order. */
    // @req IR-STATIC-001
    public synchronized List<StaticMount> staticMounts() {
        return Collections.unmodifiableList(new ArrayList<>(staticMounts));
    }

    // @req IR-STATIC-002
    @Override
    public MimeTypes mimeTypes() {
        return mimeTypes;
    }

    // @req IR-STATIC-002
    @Override
    public synchronized RestApp defaultCache(@Nullable CachePolicy policy) {
        lifecycle.requireConfigurable("defaultCache");
        this.defaultCache = policy;
        return this;
    }

    /** The app-wide Cache-Control policy; null (the default) adds none. */
    // @req IR-STATIC-002
    public @Nullable CachePolicy defaultCachePolicy() {
        return defaultCache;
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

    // @req IR-ENGINE-001
    @Override
    public synchronized RestApp server(Configurer<ServerOptions> configurer) {
        lifecycle.requireConfigurable("server");
        Objects.requireNonNull(configurer, "configurer");
        configurer.configure(serverOptions);
        return this;
    }

    // @req IR-ENGINE-001
    // @req FR-ENGINE-008
    @Override
    public synchronized RestApp trustProxy(boolean trust) {
        lifecycle.requireConfigurable("trustProxy");
        this.trustProxy = trust;
        return this;
    }

    /** True for 127.0.0.0/8, ::1 and localhost, the binds that need no LAN warning. */
    // @req FR-ENGINE-002
    static boolean isLoopback(String address) {
        String a = address.startsWith("[") && address.endsWith("]") ? address.substring(1, address.length() - 1)
                : address;
        return a.equalsIgnoreCase("localhost") || a.equals("::1") || a.equals("0:0:0:0:0:0:0:1")
                || a.matches("127\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");
    }

    /** The app's internal shutdown participant registry; later waves add their steps here. */
    // @req FR-APP-011
    ShutdownParticipants shutdownParticipants() {
        return shutdownParticipants;
    }

    /** The app's open temp uploads, closed by its after-onStop shutdown participant. */
    // @req FR-MULTIPART-006
    SpoolRegistry spool() {
        return spool;
    }

    /** The app's state machine, for same-package tests. */
    AppLifecycle lifecycle() {
        return lifecycle;
    }

    /** The app's one context, created with the app: onStart, onStop and every request receive this instance. */
    // @req IR-APP-004
    // @req FR-APP-007
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
     * STARTING by compareAndSet, the onStart callbacks in order (each once), the context freeze, the route-table
     * freeze, the server-settings freeze (its connection check throws {@link IllegalStateException}), one warning
     * for a non-loopback bind address, one settings bind of the gated handler, then RUNNING. A stop requested meanwhile is
     * checked before each callback, before bind and at the move to RUNNING, and ends the start like a failure. Not
     * synchronized: requests held on the start gate and onStart callbacks must not wait for this call.
     */
    // @req IR-APP-005
    // @req FR-APP-009
    // @req FR-APP-007
    // @req FR-APP-008
    // @req FR-APP-010
    // @req FR-APP-012
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
        Thread self = Thread.currentThread();
        lifecycleThread.set(self);
        try {
            return start(a, port);
        } finally {
            lifecycleThread.compareAndSet(self, null);
            lifecycle.markListenEnded();
        }
    }

    private ServerHandle start(HttpAdapter a, int port) {
        int bound;
        ServerSettings settings;
        try {
            List<LifecycleCallback> callbacks;
            synchronized (this) {
                callbacks = new ArrayList<>(startCallbacks);
            }
            for (LifecycleCallback cb : callbacks) {
                throwIfStopPending();
                cb.handle(context);
            }
            context.freeze();
            freeze();
            ServerSettings frozen = serverOptions.freeze(port, logger, StandardErrors.renderer());
            settings = frozen;
            throwIfStopPending();
            if (!isLoopback(frozen.bindAddress())) {
                frozen.logger().warn("listening on " + frozen.bindAddress() + ", reachable beyond this device:"
                        + " the app must add its own authentication");
            }
            bindAttempted = true;
            Dispatcher d = frozenDispatcher();
            a.bind(frozen, new GatedExchangeHandler(lifecycle, d, d.accessLog()));
            bound = a.boundPort();
            boundPort = bound;
            if (!lifecycle.markRunning()) {
                throw stopWhileStarting();
            }
        } catch (Throwable e) {
            throw failStart(e);
        }
        return new ServerHandle() {
            @Override
            public int port() {
                return bound;
            }

            @Override
            public String address() {
                return settings.bindAddress();
            }

            @Override
            public AppState state() {
                return lifecycle.state();
            }
        };
    }

    private void throwIfStopPending() {
        if (lifecycle.pendingStop()) {
            throw stopWhileStarting();
        }
    }

    private static AppStartException stopWhileStarting() {
        return new AppStartException("shutdown was requested while starting");
    }

    /**
     * The failed-start path: FAILED (held requests get 503), adapter stop and release if bind was attempted, the
     * owned close in reverse, the logger flush and close, TERMINATED. Each cleanup failure is suppressed on the
     * result. Returns an unchecked cause as is and wraps a checked one in {@link AppStartException}; an
     * {@link Error} is rethrown from here after the cleanup.
     */
    // @req FR-APP-008
    // @req FR-APP-010
    // @req FR-APP-012
    RuntimeException failStart(Throwable cause) {
        Throwable result = cause instanceof RuntimeException || cause instanceof Error ? cause
                : new AppStartException("app start failed: " + cause, cause);
        Error cleanupError = null;
        try {
            lifecycle.fail();
            HttpAdapter a = adapter;
            if (bindAttempted && a != null) {
                try {
                    // 1 s grace so requests released from the start gate can send their 503.
                    a.stop(1, TimeUnit.SECONDS);
                } catch (Throwable e) {
                    cleanupError = cleanupFailure(result, cleanupError, e);
                }
                try {
                    a.release();
                } catch (Throwable e) {
                    cleanupError = cleanupFailure(result, cleanupError, e);
                }
            }
            try {
                for (Exception closeFailure : context.closeOwned(logger)) {
                    suppress(result, closeFailure);
                }
            } catch (Error e) {
                cleanupError = cleanupFailure(result, cleanupError, e);
            }
            SafeLogger.flushAndClose(logger);
        } finally {
            lifecycle.terminate();
        }
        if (result instanceof Error) {
            throw (Error) result;
        }
        if (cleanupError != null) {
            suppress(cleanupError, result);
            throw cleanupError;
        }
        return (RuntimeException) result;
    }

    /**
     * Records a failed-start cleanup failure: an Exception, or any failure when the result is itself an Error, is
     * suppressed on the result; otherwise the first Error is returned to be thrown after TERMINATED, later ones
     * suppressed on it.
     */
    @Nullable
    private static Error cleanupFailure(Throwable result, @Nullable Error cleanupError, Throwable failure) {
        if (!(failure instanceof Error) || result instanceof Error) {
            suppress(result, failure);
            return cleanupError;
        }
        if (cleanupError == null) {
            return (Error) failure;
        }
        suppress(cleanupError, failure);
        return cleanupError;
    }

    /** addSuppressed refuses the throwable itself, which a close() rethrowing the cause would pass. */
    private static void suppress(Throwable result, Throwable failure) {
        if (failure != result) {
            result.addSuppressed(failure);
        }
    }

    /**
     * Dispatch on the state: NEW terminates at once; STARTING records a pending stop and waits for listen to end;
     * RUNNING runs the sequence in the one beginStop winner (on a new thread, without waiting, when the winner is a
     * request of this app); STOPPING and FAILED wait for TERMINATED (not from a request of this app); TERMINATED
     * returns. A wait that times out returns normally. Not synchronized, so a loser waits only its own timeout.
     */
    // @req IR-APP-005
    // @req FR-APP-008
    // @req FR-APP-011
    // @req FR-APP-012
    @Override
    public void shutdown(long timeout, TimeUnit unit) {
        if (timeout < 0) {
            throw new IllegalArgumentException("timeout must not be negative: " + timeout);
        }
        Objects.requireNonNull(unit, "unit");
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        boolean ownThread = Thread.currentThread() == lifecycleThread.get();
        for (;;) {
            switch (lifecycle.state()) {
                case NEW:
                    if (lifecycle.terminateIfNew()) {
                        SafeLogger.flushAndClose(logger);
                        return;
                    }
                    break;
                case STARTING:
                    lifecycle.requestPendingStop();
                    // RUNNING now means markRunning won the race before the stop was recorded: stop normally.
                    if (lifecycle.state() != AppState.RUNNING) {
                        if (!ownThread) {
                            lifecycle.awaitTerminated(left(deadline), TimeUnit.NANOSECONDS);
                            // TERMINATED is set just before listen() throws; return only once listen() ended.
                            lifecycle.awaitListenEnded(left(deadline), TimeUnit.NANOSECONDS);
                        }
                        return;
                    }
                    break;
                case RUNNING:
                    if (lifecycle.beginStop()) {
                        if (lifecycle.onRequestThread()) {
                            // The adapter stop waits for in-flight requests, this one included: run the sequence on
                            // its own thread so this request can finish and the caller returns at once.
                            Thread t = shutdownThreads.newThread(() -> runShutdownSequence(deadline));
                            t.setDaemon(false);
                            try {
                                t.start();
                            } catch (Throwable e) {
                                // No thread (OOM on low-end devices): run inline so the app still reaches TERMINATED.
                                runShutdownSequence(deadline);
                            }
                        } else {
                            runShutdownSequence(deadline);
                        }
                        return;
                    }
                    break;
                case TERMINATED:
                    return;
                default:
                    // STOPPING or FAILED: a thread is on its way to TERMINATED; when it is this one, or TERMINATED
                    // waits for this request to finish, don't wait.
                    if (!ownThread && !lifecycle.onRequestThread()) {
                        lifecycle.awaitTerminated(left(deadline), TimeUnit.NANOSECONDS);
                    }
                    return;
            }
        }
    }

    private static long left(long deadlineNanos) {
        return Math.max(0, deadlineNanos - System.nanoTime());
    }

    /**
     * The FR-APP-011 order: before-wait participants, adapter stop with the time left until the deadline,
     * force-close participants, onStop callbacks (services still registered), after-onStop participants, the owned
     * close in reverse (the app logger skipped), the logger flush and close, adapter release, TERMINATED. A failing
     * step is logged at ERROR and the next step runs; as on the failed-start path, the first {@link Error} is
     * rethrown once TERMINATED. Every phase hands its participants {@code deadlineNanos}, computed at shutdown entry.
     */
    // @req FR-APP-011
    // @req FR-APP-020
    private void runShutdownSequence(long deadlineNanos) {
        SafeLogger log = new SafeLogger(logger);
        Thread self = Thread.currentThread();
        lifecycleThread.set(self);
        Error error = null;
        try {
            HttpAdapter a = Objects.requireNonNull(adapter, "adapter");
            error = firstError(error, shutdownParticipants.run(ShutdownParticipants.Phase.BEFORE_WAIT, log, deadlineNanos));
            try {
                a.stop(left(deadlineNanos), TimeUnit.NANOSECONDS);
            } catch (Throwable e) {
                log.error("adapter stop failed", e);
                error = firstError(error, e);
            }
            error = firstError(error, shutdownParticipants.run(ShutdownParticipants.Phase.FORCE_CLOSE, log, deadlineNanos));
            List<LifecycleCallback> callbacks;
            synchronized (this) {
                callbacks = new ArrayList<>(stopCallbacks);
            }
            for (LifecycleCallback cb : callbacks) {
                try {
                    cb.handle(context);
                } catch (Throwable e) {
                    log.error("onStop callback failed", e);
                    error = firstError(error, e);
                }
            }
            error = firstError(error, shutdownParticipants.run(ShutdownParticipants.Phase.AFTER_ON_STOP, log, deadlineNanos));
            try {
                for (Exception e : context.closeOwned(logger)) {
                    log.error("owned resource close failed", e);
                }
            } catch (Error e) {
                log.error("owned resource close failed", e);
                error = firstError(error, e);
            }
            SafeLogger.flushAndClose(logger);
            try {
                a.release();
            } catch (Throwable e) {
                log.error("adapter release failed", e);
                error = firstError(error, e);
            }
        } finally {
            lifecycleThread.compareAndSet(self, null);
            lifecycle.terminate();
        }
        if (error != null) {
            throw error;
        }
    }

    /** The first Error of a shutdown: {@code first} when set, else {@code failure} when it is an Error, else null. */
    @Nullable
    static Error firstError(@Nullable Error first, @Nullable Throwable failure) {
        return first != null || !(failure instanceof Error) ? first : (Error) failure;
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

    // @req FR-DX-010
    @Override
    public synchronized RestApp cors(Configurer<CorsOptions> configurer) {
        lifecycle.requireConfigurable("cors");
        DefaultCorsOptions options = new DefaultCorsOptions();
        Objects.requireNonNull(configurer, "configurer").configure(options);
        return use(options.middleware());
    }

    // @req FR-DX-009
    @Override
    public synchronized RestApp group(String prefix, Configurer<RouteGroup> configurer) {
        lifecycle.requireConfigurable("group");
        DefaultRouteGroup.configure(this, prefix, configurer);
        return this;
    }

    // @req IR-APP-002
    // @req FR-APP-003
    @Override
    public synchronized RestApp use(Middleware mw) {
        lifecycle.requireConfigurable("use");
        middlewareRegistrations.add(new MiddlewareRegistration(null, Objects.requireNonNull(mw, "mw")));
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
        // Decoding does not depend on the policy, so a prefix that decodes now also decodes at freeze.
        try {
            Router.decodeSegments(prefix, policy);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("middleware prefix is not decodable: " + prefix, e);
        }
        middlewareRegistrations.add(new MiddlewareRegistration(prefix, mw));
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
    // @req FR-APP-014
    // @req FR-APP-019
    // @req FR-WS-001
    // @req FR-WS-007
    // @req FR-WS-008
    // @req FR-WS-010
    // @req FR-SSE-005
    // @req FR-APP-020
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
        for (MiddlewareRegistration registration : middlewareRegistrations) {
            if (registration.prefix == null) {
                pipeline.addGlobal(registration.middleware);
            } else {
                pipeline.addPrefix(registration.prefix, registration.middleware);
            }
        }
        DefaultWebSocketOptions ws = webSocketOptions().copy();
        frozenWebSocketOptions = ws;
        DefaultSseOptions sse = sseOptions().copy();
        frozenSseOptions = sse;
        SafeLogger safe = new SafeLogger(logger); // the logger is fixed from here: logger(...) needs an unfrozen app
        WebSocketSender.IntSupplier port = () -> boundPort; // threads are made after bind, so they see the real port
        WebSocketSender sender = new WebSocketSender(serverOptions.maxUpgradedConnections(), port, safe);
        WebSocketIdleScheduler idle = new WebSocketIdleScheduler(port, safe);
        WebSocketRegistry registry = new WebSocketRegistry(sender, idle);
        SseRegistry streams = new SseRegistry(sse, lifecycle, safe);
        AccessLog accessLog = new AccessLog(accessLogOptions, safe); // shared with the sessions for their lines
        dispatcher = new Dispatcher(router, pipeline, new ErrorPath(errorHandlers, safe),
                accessLog, context, jsonCodec(), jsonLimit(), trustProxy,
                multipartSettings().copy(), formLimit(), spool(), new StaticHandler(staticMounts, mimeTypes, defaultCache),
                new WebSocketUpgrade(ws, jsonCodec(), safe, sender, idle, registry, accessLog),
                streams);
        webSocketRegistry = registry;
        sseRegistry = streams;
        // SSE first: its closeAll returns at once, while the WebSocket one waits for the closes.
        shutdownParticipants.add(ShutdownParticipants.Phase.BEFORE_WAIT,
                (ShutdownParticipants.DeadlineParticipant) streams::closeAll);
        shutdownParticipants.add(ShutdownParticipants.Phase.BEFORE_WAIT,
                (ShutdownParticipants.DeadlineParticipant) registry::closeAll);
        shutdownParticipants.add(ShutdownParticipants.Phase.FORCE_CLOSE, registry::terminate);
        shutdownParticipants.add(ShutdownParticipants.Phase.FORCE_CLOSE, streams::terminate);
        lifecycle.markFrozen();
    }

    /** The WebSocket session registry built by a successful {@link #freeze()}; for internal tests. */
    // @req FR-WS-010
    synchronized WebSocketRegistry webSocketRegistry() {
        WebSocketRegistry r = webSocketRegistry;
        if (r == null) {
            throw new IllegalStateException("RestApp is not frozen; call freeze() first");
        }
        return r;
    }

    /** The SSE stream registry built by a successful {@link #freeze()}; for internal tests. */
    // @req FR-SSE-005
    synchronized SseRegistry sseRegistry() {
        SseRegistry r = sseRegistry;
        if (r == null) {
            throw new IllegalStateException("RestApp is not frozen; call freeze() first");
        }
        return r;
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
