package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.ApplicationContext;
import com.snoworca.naruserve.ConsoleLogger;
import com.snoworca.naruserve.ErrorHandler;
import com.snoworca.naruserve.Method;
import com.snoworca.naruserve.MethodSet;
import com.snoworca.naruserve.Middleware;
import com.snoworca.naruserve.RouteHandler;
import com.snoworca.naruserve.routing.PathValidator;
import com.snoworca.naruserve.routing.RouteKind;
import com.snoworca.naruserve.routing.RouteMatch;
import com.snoworca.naruserve.routing.Router;
import com.snoworca.naruserve.routing.RouterPolicy;
import com.snoworca.naruserve.spi.ExchangeHandler;
import com.snoworca.naruserve.spi.HttpExchange;
import com.snoworca.naruserve.spi.JsonCodec;
import java.io.IOException;
import java.io.OutputStream;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;

/**
 * Engine-free entry the adapter calls per exchange, ungated ({@link GatedExchangeHandler} runs in front of it under
 * {@code listen}). Pre-request rejections are answered before any Request exists; every other request runs the
 * middleware pipeline, ending in the matched handler or in {@link #fallback}, the one core-side place that maps
 * {@link RouteMatch} kinds to automatic answers (adapters still answer an authority-form CONNECT themselves).
 */
// @req CON-HTTP-001
// @req FR-HTTP-021
// @req FR-HTTP-026
// @req FR-HTTP-023
// @req FR-HTTP-024
// @req FR-HTTP-025
// @req FR-APP-003
// @req IR-JSON-002
// @req FR-WS-001
// @req FR-WS-003
// @req FR-WS-004
public final class Dispatcher implements ExchangeHandler {

    private static final Method[] METHODS = Method.values();

    private final Router<RouteHandler> router;
    private final MiddlewarePipeline pipeline;
    private final ErrorPath errors;
    private final AccessLog accessLog;
    private final @Nullable ApplicationContext context;
    private final @Nullable JsonCodec codec;
    private final long jsonLimit;
    private final boolean trustProxy;
    private final MultipartSettings multipart;
    private final long formLimit;
    private final SpoolRegistry spool;
    private final StaticHandler statics;
    private final WebSocketUpgrade upgrades;
    private final SseRegistry sse;
    private @Nullable HttpRouter http;
    private @Nullable AppLifecycle lifecycle;

    /**
     * Wave 3 shape: no middleware, no onError handler, access log off, a {@link ConsoleLogger}. {@code router} must
     * already be frozen.
     */
    // @req FR-HTTP-021
    public Dispatcher(Router<RouteHandler> router) {
        this(router, new MiddlewarePipeline(RouterPolicy.strict()),
                new ErrorPath(Collections.<ErrorHandler>emptyList(), new SafeLogger(new ConsoleLogger())),
                new AccessLog(), null);
    }

    /** The full shape without an application context, for tests that dispatch without an app. */
    // @req FR-APP-003
    Dispatcher(Router<RouteHandler> router, MiddlewarePipeline pipeline, ErrorPath errors, AccessLog accessLog) {
        this(router, pipeline, errors, accessLog, null);
    }

    /**
     * Built by {@code DefaultRestApp.freeze}; {@code router} must already be frozen. Every request gets
     * {@code context} as its {@code application()}.
     */
    // @req FR-APP-003
    // @req FR-APP-007
    Dispatcher(Router<RouteHandler> router, MiddlewarePipeline pipeline, ErrorPath errors, AccessLog accessLog,
            @Nullable ApplicationContext context) {
        this(router, pipeline, errors, accessLog, context, null, JsonBodyReader.DEFAULT_LIMIT);
    }

    /** As above, with the app codec and JSON limit that {@code DefaultRestApp.freeze} read; each request gets them. */
    // @req IR-JSON-002
    Dispatcher(Router<RouteHandler> router, MiddlewarePipeline pipeline, ErrorPath errors, AccessLog accessLog,
            @Nullable ApplicationContext context, @Nullable JsonCodec codec, long jsonLimit) {
        this(router, pipeline, errors, accessLog, context, codec, jsonLimit, false);
    }

    /** As above, with the app.trustProxy setting that {@code DefaultRestApp.freeze} read. */
    // @req FR-ENGINE-008
    Dispatcher(Router<RouteHandler> router, MiddlewarePipeline pipeline, ErrorPath errors, AccessLog accessLog,
            @Nullable ApplicationContext context, @Nullable JsonCodec codec, long jsonLimit, boolean trustProxy) {
        this(router, pipeline, errors, accessLog, context, codec, jsonLimit, trustProxy, new MultipartSettings(),
                1048576L);
    }

    /** As above, with the multipart settings and form limit that {@code DefaultRestApp.freeze} read. */
    // @req FR-MULTIPART-003
    Dispatcher(Router<RouteHandler> router, MiddlewarePipeline pipeline, ErrorPath errors, AccessLog accessLog,
            @Nullable ApplicationContext context, @Nullable JsonCodec codec, long jsonLimit, boolean trustProxy,
            MultipartSettings multipart, long formLimit) {
        this(router, pipeline, errors, accessLog, context, codec, jsonLimit, trustProxy, multipart, formLimit,
                new SpoolRegistry());
    }

    /** As above, with the app's spool registry, which its after-onStop participant closes. */
    // @req FR-MULTIPART-006
    Dispatcher(Router<RouteHandler> router, MiddlewarePipeline pipeline, ErrorPath errors, AccessLog accessLog,
            @Nullable ApplicationContext context, @Nullable JsonCodec codec, long jsonLimit, boolean trustProxy,
            MultipartSettings multipart, long formLimit, SpoolRegistry spool) {
        this(router, pipeline, errors, accessLog, context, codec, jsonLimit, trustProxy, multipart, formLimit, spool,
                new StaticHandler(Collections.<StaticMount>emptyList()));
    }

    /** As above, with the app's static mounts, consulted in the terminal fallback after routes and middleware. */
    // @req FR-STATIC-001
    Dispatcher(Router<RouteHandler> router, MiddlewarePipeline pipeline, ErrorPath errors, AccessLog accessLog,
            @Nullable ApplicationContext context, @Nullable JsonCodec codec, long jsonLimit, boolean trustProxy,
            MultipartSettings multipart, long formLimit, SpoolRegistry spool, StaticHandler statics) {
        this(router, pipeline, errors, accessLog, context, codec, jsonLimit, trustProxy, multipart, formLimit, spool,
                statics, new WebSocketUpgrade(new DefaultWebSocketOptions(), codec, errors.logger));
    }

    /** As above, with the app's upgrade step, which runs after the chain for a matched app.ws route. */
    // @req FR-WS-001
    Dispatcher(Router<RouteHandler> router, MiddlewarePipeline pipeline, ErrorPath errors, AccessLog accessLog,
            @Nullable ApplicationContext context, @Nullable JsonCodec codec, long jsonLimit, boolean trustProxy,
            MultipartSettings multipart, long formLimit, SpoolRegistry spool, StaticHandler statics,
            WebSocketUpgrade upgrades) {
        this(router, pipeline, errors, accessLog, context, codec, jsonLimit, trustProxy, multipart, formLimit, spool,
                statics, upgrades, new SseRegistry(new DefaultSseOptions(), null, errors.logger));
    }

    /** As above, with the app's SSE registry, which every response's {@code sse()} streams join. */
    // @req FR-SSE-004
    Dispatcher(Router<RouteHandler> router, MiddlewarePipeline pipeline, ErrorPath errors, AccessLog accessLog,
            @Nullable ApplicationContext context, @Nullable JsonCodec codec, long jsonLimit, boolean trustProxy,
            MultipartSettings multipart, long formLimit, SpoolRegistry spool, StaticHandler statics,
            WebSocketUpgrade upgrades, SseRegistry sse) {
        this.sse = Objects.requireNonNull(sse, "sse");
        this.upgrades = Objects.requireNonNull(upgrades, "upgrades");
        this.statics = Objects.requireNonNull(statics, "statics");
        this.spool = Objects.requireNonNull(spool, "spool");
        this.multipart = Objects.requireNonNull(multipart, "multipart");
        this.formLimit = formLimit;
        this.trustProxy = trustProxy;
        this.codec = codec;
        this.jsonLimit = jsonLimit;
        this.context = context;
        this.router = Objects.requireNonNull(router, "router");
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
        this.errors = Objects.requireNonNull(errors, "errors");
        this.accessLog = Objects.requireNonNull(accessLog, "accessLog");
    }

    // @req FR-HTTP-021
    // @req FR-HTTP-026
    // @req FR-APP-003
    @Override
    // @req FR-APP-019
    // @req FR-WS-001
    // @req FR-WS-003
    public void handle(HttpExchange ex) {
        if(http!=null) {
            boolean upgrade=Router.isWebSocketUpgrade(ex.methodToken(),joined(ex,"Connection"),joined(ex,"Upgrade"));
            boolean legacyUpgrade=false;
            if(upgrade) {
                try {
                    RouteMatch<RouteHandler> ws=router.lookup(ex.methodToken(),ex.rawPath(),true);
                    legacyUpgrade=ws.kind()==RouteMatch.Kind.MATCHED && ws.routeKind()==RouteKind.WEBSOCKET;
                } catch(IllegalArgumentException invalidLegacyPath) { /* common raw HTTP still owns its own matching */ }
            }
            if(!legacyUpgrade) { handleHttp(ex);return; }
        }
        handleLegacy(ex);
    }

    void commonHttp(HttpRouter http,AppLifecycle lifecycle) { this.http=http;this.lifecycle=lifecycle; }

    // @req FR-HTTP-027; FR-APP-021; FR-APP-022; FR-APP-023
    private void handleHttp(HttpExchange ex) {
        String rid=RequestIds.next();long start=System.nanoTime();
        if("OPTIONS".equals(ex.methodToken()) && "*".equals(ex.rawPath())) {
            automatic(ex,204,Method.any(),false,rid,start);return;
        }
        if(!ex.rawPath().startsWith("/")) { automatic(ex,400,null,false,rid,start);return; }
        Method method=methodOf(ex.methodToken());
        DefaultRequest req=new DefaultRequest(ex,method,Collections.emptyMap(),context,codec,jsonLimit,trustProxy,multipart,formLimit,spool,true);
        req.requestId(rid);req.startNanos(start);
        DefaultResponse res=new DefaultResponse(ex,method==Method.HEAD,codec);
        res.mimeTypes(statics.mimeTypes());res.resolverTap(statics.resolverTap());res.request(req);
        res.applyDefaultCache(statics.defaultCache());res.sseRegistry(sse);
        Runnable terminal=()->{
            if(res.state()!=ResponseState.NEW)return;
            try {
                // A real legacy WS-only footprint retains its separate426/OPTIONS/method profile.
                RouteMatch<RouteHandler> wsPath=null;
                try { wsPath=router.lookup("GET",ex.rawPath(),false); }
                catch(IllegalArgumentException invalidLegacyPath) { /* common raw HTTP still owns this path */ }
                if(wsPath!=null && wsPath.kind()==RouteMatch.Kind.WEBSOCKET_ONLY) {
                    fallback(router.lookup(ex.methodToken(),ex.rawPath(),false),req,res,rid);return;
                }
                if(!statics.serve(req,res,false))FinalHandler.finish(req,res,null,errors.logger);
            } catch(Exception failure) { FinalHandler.finish(req,res,failure,errors.logger); }
        };
        Runnable log=()->{
            DefaultSseClient stream=res.sseClient();
            if(stream!=null) {
                String cause=stream.cause();
                accessLog.writeSse(ex.methodToken(),ex.rawPath(),200,start,req.body().bytesRead(),res.bytesWritten(),rid,
                        stream.startMillis(),cause!=null?cause:"error");
            } else if(!res.upgraded101())accessLog.write(ex.methodToken(),ex.rawPath(),res.statusCode(),
                    res.state()==ResponseState.ABORTED,start,req.body().bytesRead(),res.bytesWritten(),rid);
        };
        new HttpExecution(Objects.requireNonNull(http),req,res,Objects.requireNonNull(lifecycle),errors,terminal,log).start(ex);
    }

    private void handleLegacy(HttpExchange ex) {
        String rid = RequestIds.next();
        long start = System.nanoTime();
        if (preCheck(ex, rid, start)) {
            return;
        }
        String token = ex.methodToken();
        String rawPath = ex.rawPath();
        boolean upgrade = Router.isWebSocketUpgrade(token, joined(ex, "Connection"), joined(ex, "Upgrade"));
        RouteMatch<RouteHandler> m = router.lookup(token, rawPath, upgrade);
        boolean matched = m.kind() == RouteMatch.Kind.MATCHED;
        boolean routed = matched && m.routeKind() == RouteKind.HTTP;
        boolean webSocket = matched && m.routeKind() == RouteKind.WEBSOCKET;
        Method method = m.kind() == RouteMatch.Kind.MATCHED
                ? (m.autoHead() ? Method.HEAD : Objects.requireNonNull(m.method()))
                : Objects.requireNonNull(methodOf(token)); // preCheck answered every unrecognised token
        DefaultRequest req = new DefaultRequest(ex, method,
                matched ? m.params() : Collections.<String, String>emptyMap(), context, codec, jsonLimit, trustProxy,
                multipart, formLimit, spool);
        req.requestId(rid);
        req.startNanos(start);
        DefaultResponse res = new DefaultResponse(ex, method == Method.HEAD, codec);
        res.mimeTypes(statics.mimeTypes());
        res.resolverTap(statics.resolverTap());
        res.request(req);
        res.applyDefaultCache(statics.defaultCache());
        res.sseRegistry(sse);
        // The request ends once the response finished (complete or abort) and the handler chain returned, so a
        // handler may still save() an upload after res.send(); whichever comes last releases the uploads.
        AtomicInteger untilEnd = new AtomicInteger(2);
        Runnable endOne = () -> {
            if (untilEnd.decrementAndGet() == 0) {
                req.releaseUploads();
            }
        };
        // One line when the response ends: an SSE stream's line with its cause, none for an upgraded 101 (the
        // session wrote its handshake line in onOpen and writes its close line), else the request line.
        res.onDone(() -> {
            endOne.run();
            DefaultSseClient stream = res.sseClient();
            if (stream != null) {
                String cause = stream.cause();
                accessLog.writeSse(token, rawPath, 200, start, req.body().bytesRead(), res.bytesWritten(), rid,
                        stream.startMillis(), cause != null ? cause : "error");
            } else if (!res.upgraded101()) {
                accessLog.write(token, rawPath, res.statusCode(), res.state() == ResponseState.ABORTED, start,
                        req.body().bytesRead(), res.bytesWritten(), rid);
            }
        });
        List<Middleware> route = Collections.emptyList();
        AtomicBoolean reached = new AtomicBoolean();
        RouteHandler terminal;
        if (routed) {
            RouteHandler h = Objects.requireNonNull(m.handler());
            if (h instanceof MiddlewarePipeline.RouteChain) {
                route = ((MiddlewarePipeline.RouteChain) h).middleware;
                h = ((MiddlewarePipeline.RouteChain) h).handler;
            }
            RouteHandler handler = h;
            terminal = (rq, rs) -> invokeRoute(req, res, handler);
        } else if (webSocket) {
            // Only record that the chain reached its end; the upgrade runs once pipeline.run returned.
            terminal = (rq, rs) -> reached.set(true);
        } else {
            terminal = (rq, rs) -> fallback(m, req, res, rid);
        }
        try {
            pipeline.run(req, res, route, terminal, errors, rid);
            if (reached.get() && res.state() == ResponseState.NEW) {
                RouteHandler h = m.handler();
                upgrades.run(ex, req, res, h instanceof WebSocketRoute ? ((WebSocketRoute) h).handler() : null, rid);
            }
        } catch (Exception e) {
            errors.handle(e, req, res, rid);
            req.releaseUploads();
            return;
        } catch (Error e) {
            res.abort(e); // closes a pending stream before the Error reaches the adapter
            req.releaseUploads();
            throw e;
        } finally {
            endOne.run();
        }
        finish(req, res);
    }

    /**
     * The terminal step of a matched HTTP route, after its middleware: the query is decoded, then the handler runs.
     * A malformed query throws HttpException(400) from here to the error path, and the handler never runs.
     */
    // @req FR-HTTP-021
    // @req FR-HTTP-025
    void invokeRoute(DefaultRequest req, DefaultResponse res, RouteHandler handler) throws Exception {
        req.decodeQuery();
        handler.handle(req, res);
    }

    /**
     * After the pipeline returned: a response still NEW (no step answered, no error path ran) gets one warn naming
     * the method and path, then ends with the status and headers set so far; STREAMING runs the copy without a warn.
     * The warn goes through the SafeLogger, so a throwing logger never changes this path.
     */
    // @req FR-HTTP-024
    // @req FR-APP-014
    void finish(DefaultRequest req, DefaultResponse res) {
        if (res.state() == ResponseState.NEW) {
            errors.logger.warn("response not finished by the handler: " + req.method().name() + " " + req.rawPath());
        }
        res.afterHandler();
    }

    /**
     * In order: OPTIONS with raw path {@code *} 204, a raw path not starting with '/' 400, a validator rejection
     * 400, an unrecognised method token 501. True when it answered; the router lookup never sees these.
     */
    // @req FR-HTTP-021
    // @req FR-HTTP-023
    // @req FR-APP-003
    // @req FR-APP-019
    boolean preCheck(HttpExchange ex, String rid, long start) {
        String rawPath = ex.rawPath();
        String token = ex.methodToken();
        if ("OPTIONS".equals(token) && "*".equals(rawPath)) {
            automatic(ex, 204, Method.any(), false, rid, start);
            return true;
        }
        if (!rawPath.startsWith("/") || !PathValidator.isValid(rawPath)) {
            automatic(ex, 400, null, false, rid, start);
            return true;
        }
        if (methodOf(token) == null) {
            automatic(ex, 501, null, false, rid, start);
            return true;
        }
        return false;
    }

    /** The {@link Method} whose declaration name equals {@code token} exactly, or null. */
    // @req FR-HTTP-021
    // @req FR-HTTP-026
    static @Nullable Method methodOf(String token) {
        for (Method m : METHODS) {
            if (m.name().equals(token)) {
                return m;
            }
        }
        return null;
    }

    /** A pre-request answer: {@link #answerDirect} with the Allow header or the Upgrade headers when asked. */
    // @req FR-HTTP-021
    // @req FR-HTTP-023
    // @req FR-APP-019
    void automatic(HttpExchange ex, int status, @Nullable MethodSet allow, boolean upgradeHeaders, String rid,
            long start) {
        List<Map.Entry<String, String>> headers = new ArrayList<>(5);
        if (allow != null) {
            headers.add(new SimpleImmutableEntry<>("Allow", allowHeader(allow)));
        }
        if (upgradeHeaders) {
            headers.add(new SimpleImmutableEntry<>("Upgrade", "websocket"));
            headers.add(new SimpleImmutableEntry<>("Connection", "Upgrade"));
        }
        answerDirect(ex, status, headers, rid, start, accessLog);
    }

    /**
     * Writes {@code status} straight to the exchange after {@code headers}: 204 with no body and no Content-Length,
     * any other status with the standard error body carrying {@code rid} (a HEAD token keeps the Content-Length and
     * writes no bytes). Writes its one access line.
     */
    // @req FR-HTTP-021
    // @req FR-APP-013
    // @req FR-APP-019
    static void answerDirect(HttpExchange ex, int status, List<Map.Entry<String, String>> headers, String rid,
            long start, AccessLog accessLog) {
        byte[] body = new byte[0];
        if (status != 204) {
            body = StandardErrors.json(status, StandardErrors.reason(status), rid);
            headers.add(new SimpleImmutableEntry<>("Content-Type", StandardErrors.JSON_TYPE));
            headers.add(new SimpleImmutableEntry<>("Content-Length", String.valueOf(body.length)));
        }
        boolean head = "HEAD".equals(ex.methodToken());
        boolean aborted = false;
        long written = 0;
        try {
            OutputStream out = ex.commit(status, headers, head ? 0 : body.length);
            if (!head && body.length > 0) {
                out.write(body);
                written = body.length;
            }
            ex.finish();
        } catch (IOException | RuntimeException e) {
            aborted = true;
            ex.abort();
        }
        accessLog.write(ex.methodToken(), ex.rawPath(), status, aborted, start, 0, written, rid);
    }

    /** The access log this dispatcher writes, shared with the lifecycle gate in front of it. */
    AccessLog accessLog() {
        return accessLog;
    }

    /**
     * Terminal step for every non-MATCHED lookup (a matched app.ws route upgrades instead). Answers through
     * {@code res}, keeping headers middleware set; a response already left NEW is not touched. NOT_FOUND, and
     * METHOD_MISMATCH for GET and HEAD, try the static mounts first; WEBSOCKET_ONLY and 501 never do.
     */
    // @req FR-HTTP-026
    // @req FR-APP-003
    // @req FR-STATIC-001
    // @req FR-STATIC-002
    // @req FR-WS-004
    void fallback(RouteMatch<RouteHandler> m, DefaultRequest req, DefaultResponse res, String requestId)
            throws IOException {
        if (res.state() != ResponseState.NEW) {
            return;
        }
        boolean mismatch = m.kind() == RouteMatch.Kind.METHOD_MISMATCH;
        boolean getLike = req.method() == Method.GET || req.method() == Method.HEAD;
        if ((m.kind() == RouteMatch.Kind.NO_MATCH || mismatch && getLike) && statics.serve(req, res, mismatch)) {
            return;
        }
        switch (m.kind()) {
            case AUTO_OPTIONS:
                res.status(204).header("Allow", allowHeader(Objects.requireNonNull(m.allow())));
                res.end();
                return;
            case METHOD_MISMATCH:
                res.header("Allow", allowHeader(Objects.requireNonNull(m.allow())));
                error(res, 405, requestId);
                return;
            case WEBSOCKET_ONLY:
                res.header("Upgrade", "websocket").header("Connection", "Upgrade");
                error(res, 426, requestId);
                return;
            case UNSUPPORTED_METHOD:
                error(res, 501, requestId);
                return;
            default:
                error(res, 404, requestId);
        }
    }

    static void error(DefaultResponse res, int status, String requestId) {
        res.status(status).header("Content-Type", StandardErrors.JSON_TYPE);
        res.send(StandardErrors.json(status, StandardErrors.reason(status), requestId));
    }

    /** {@code ", "}-joined members in {@link Method} declaration order. */
    // @req FR-HTTP-026
    // @req FR-HTTP-023
    static String allowHeader(MethodSet allow) {
        return allow.toString();
    }

    /** Every value of header {@code name} (case-insensitive) joined by ", ", or null when absent. */
    private static @Nullable String joined(HttpExchange ex, String name) {
        StringBuilder sb = null;
        for (Map.Entry<String, String> h : ex.headers()) {
            if (h.getKey().equalsIgnoreCase(name)) {
                sb = sb == null ? new StringBuilder() : sb.append(", ");
                sb.append(h.getValue());
            }
        }
        return sb == null ? null : sb.toString();
    }
}
