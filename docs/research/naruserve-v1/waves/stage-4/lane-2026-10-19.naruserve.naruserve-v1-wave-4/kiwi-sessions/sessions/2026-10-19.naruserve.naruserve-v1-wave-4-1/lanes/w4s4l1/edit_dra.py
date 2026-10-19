p='NaruServe-api/src/main/java/com/snoworca/naruserve/internal/DefaultRestApp.java'
s=open(p).read()
def rep(a,b):
    global s
    assert a in s, a
    s=s.replace(a,b,1)
rep('''import com.snoworca.naruserve.Method;
import com.snoworca.naruserve.MethodSet;
import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.RouteBuilder;
import com.snoworca.naruserve.RouteHandler;
''','''import com.snoworca.naruserve.ConsoleLogger;
import com.snoworca.naruserve.ErrorHandler;
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
''')
rep('''import com.snoworca.naruserve.spi.ExchangeHandler;
''','''import com.snoworca.naruserve.spi.ExchangeHandler;
import com.snoworca.naruserve.spi.HttpAdapter;
import com.snoworca.naruserve.spi.JsonCodec;
''')
rep('''import java.util.List;
''','''import java.util.List;
import java.util.Objects;
''')
rep('''    // Guarded by this.
    private final List<Registration> registrations = new ArrayList<>();
    private RouterPolicy policy = RouterPolicy.strict();
    private @Nullable Dispatcher dispatcher;

    /** Policy {@link RouterPolicy#strict()} until {@link #routerPolicy} is called. */
    // @req FR-HTTP-008
    public DefaultRestApp() {
    }
''','''    /** One {@code use(prefix, mw)} call as recorded. */
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
    private final Logger logger;

    // Guarded by this.
    private final List<Registration> registrations = new ArrayList<>();
    private final List<Middleware> globalMiddleware = new ArrayList<>();
    private final List<PrefixMiddleware> prefixMiddleware = new ArrayList<>();
    private final List<ErrorHandler> errorHandlers = new ArrayList<>();
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

    public Logger logger() {
        return logger;
    }
''')
rep('''    // @req FR-HTTP-007
    // @req FR-HTTP-008
    @Override
    public synchronized RestApp route(MethodSet methods, String path, RouteHandler h) {
        record(methods, path, h, RouteKind.HTTP);
        return this;
    }
''','''    // @req FR-HTTP-007
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
''')
rep('''        record(MethodSet.of(Method.GET), path, h, RouteKind.WEBSOCKET);''','''        record(MethodSet.of(Method.GET), path, Collections.<Middleware>emptyList(), h, RouteKind.WEBSOCKET);''')
rep('''    private void record(MethodSet methods, String path, RouteHandler h, RouteKind kind) {
        checkNotFrozen();
        if (methods == null || h == null) {
            throw new NullPointerException(methods == null ? "methods" : "h");
        }
        RoutePattern.parse(path);
        registrations.add(new Registration(methods, path, h, kind));
    }''','''    private void record(MethodSet methods, String path, List<Middleware> route, RouteHandler h, RouteKind kind) {
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
    }''')
rep('''    final class DefaultRouteBuilder implements RouteBuilder {
        private final String path;

        DefaultRouteBuilder(String path) {
            this.path = path;
        }

        private RouteBuilder add(Method method, RouteHandler h) {
            route(method, path, h);
            return this;
        }
''','''    final class DefaultRouteBuilder implements RouteBuilder {
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
                middleware.add(Objects.requireNonNull(mw, "mw"));
            }
            return this;
        }
''')
rep('''// @req FR-HTTP-008
// @req FR-HTTP-007
public final class DefaultRestApp''','''// @req FR-HTTP-008
// @req FR-HTTP-007
// @req IR-APP-001
// @req IR-APP-002
// @req IR-APP-003
public final class DefaultRestApp''')
open(p,'w').write(s)
