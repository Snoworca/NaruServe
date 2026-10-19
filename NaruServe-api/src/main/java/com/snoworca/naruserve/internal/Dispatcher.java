package com.snoworca.naruserve.internal;

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
import java.io.IOException;
import java.io.OutputStream;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Engine-free entry the adapter calls per exchange, ungated ({@link GatedExchangeHandler} runs in front of it under
 * {@code listen}). Pre-request rejections are answered before any Request exists; every other request runs the
 * middleware pipeline, ending in the matched handler or in {@link #fallback}, the one core-side place that maps
 * {@link RouteMatch} kinds to automatic answers (adapters still answer an authority-form CONNECT themselves).
 */
// @req CON-HTTP-001
// @req FR-HTTP-021
// @req FR-HTTP-022
// @req FR-HTTP-023
// @req FR-APP-003
public final class Dispatcher implements ExchangeHandler {

    private static final Method[] METHODS = Method.values();

    private final Router<RouteHandler> router;
    private final MiddlewarePipeline pipeline;
    private final ErrorPath errors;
    private final AccessLog accessLog;

    /** Wave 3 shape: no middleware, no onError handler, access log off. {@code router} must already be frozen. */
    // @req FR-HTTP-021
    public Dispatcher(Router<RouteHandler> router) {
        this(router, new MiddlewarePipeline(RouterPolicy.strict()), new ErrorPath(Collections.emptyList()),
                new AccessLog());
    }

    /** Built by {@code DefaultRestApp.freeze}; {@code router} must already be frozen. */
    // @req FR-APP-003
    Dispatcher(Router<RouteHandler> router, MiddlewarePipeline pipeline, ErrorPath errors, AccessLog accessLog) {
        this.router = Objects.requireNonNull(router, "router");
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
        this.errors = Objects.requireNonNull(errors, "errors");
        this.accessLog = Objects.requireNonNull(accessLog, "accessLog");
    }

    // @req FR-HTTP-021
    // @req FR-HTTP-022
    // @req FR-APP-003
    @Override
    public void handle(HttpExchange ex) {
        if (preCheck(ex)) {
            return;
        }
        String rid = RequestIds.next();
        String token = ex.methodToken();
        boolean upgrade = Router.isWebSocketUpgrade(token, joined(ex, "Connection"), joined(ex, "Upgrade"));
        RouteMatch<RouteHandler> m = router.lookup(token, ex.rawPath(), upgrade);
        boolean routed = m.kind() == RouteMatch.Kind.MATCHED && m.routeKind() == RouteKind.HTTP;
        Method method = m.kind() == RouteMatch.Kind.MATCHED
                ? (m.autoHead() ? Method.HEAD : Objects.requireNonNull(m.method()))
                : Objects.requireNonNull(methodOf(token)); // preCheck answered every unrecognised token
        DefaultRequest req = new DefaultRequest(ex, method,
                routed ? m.params() : Collections.<String, String>emptyMap());
        DefaultResponse res = new DefaultResponse(ex, method == Method.HEAD);
        List<Middleware> route = Collections.emptyList();
        RouteHandler terminal;
        if (routed) {
            RouteHandler h = Objects.requireNonNull(m.handler());
            if (h instanceof MiddlewarePipeline.RouteChain) {
                route = ((MiddlewarePipeline.RouteChain) h).middleware;
                h = ((MiddlewarePipeline.RouteChain) h).handler;
            }
            terminal = invokeRoute(h);
        } else {
            terminal = (rq, rs) -> fallback(m, res, rid);
        }
        try {
            pipeline.run(req, res, route, terminal, errors, rid);
        } catch (Exception e) {
            errors.handle(e, req, res, rid);
            return;
        } catch (Error e) {
            res.abort(e); // closes a pending stream before the Error reaches the adapter
            throw e;
        }
        res.afterHandler();
    }

    /**
     * The terminal step of a matched HTTP route: the query is decoded, then the handler runs.
     * shortcut: a malformed query still gets the wave 3 400 with Content-Length: 0 and no onError; the FR-HTTP-025
     * SDS turns it into an HttpException(400) for the error path.
     */
    // @req FR-HTTP-021
    private static RouteHandler invokeRoute(RouteHandler handler) {
        return (req, res) -> {
            try {
                ((DefaultRequest) req).decodeQuery();
            } catch (IllegalArgumentException e) {
                res.status(400).end();
                return;
            }
            handler.handle(req, res);
        };
    }

    /**
     * In order: OPTIONS with raw path {@code *} 204, a raw path not starting with '/' 400, a validator rejection
     * 400, an unrecognised method token 501. True when it answered; the router lookup never sees these.
     */
    // @req FR-HTTP-021
    // @req FR-HTTP-023
    // @req FR-APP-003
    boolean preCheck(HttpExchange ex) {
        String rawPath = ex.rawPath();
        String token = ex.methodToken();
        if ("OPTIONS".equals(token) && "*".equals(rawPath)) {
            automatic(ex, 204, Method.any(), false);
            return true;
        }
        if (!rawPath.startsWith("/") || !PathValidator.isValid(rawPath)) {
            automatic(ex, 400, null, false);
            return true;
        }
        if (methodOf(token) == null) {
            automatic(ex, 501, null, false);
            return true;
        }
        return false;
    }

    /** The {@link Method} whose declaration name equals {@code token} exactly, or null. */
    // @req FR-HTTP-021
    // @req FR-HTTP-022
    static @Nullable Method methodOf(String token) {
        for (Method m : METHODS) {
            if (m.name().equals(token)) {
                return m;
            }
        }
        return null;
    }

    /**
     * A pre-request answer written straight to the exchange: 204 with no body and no Content-Length, any other
     * status with the standard error body (a HEAD token keeps the Content-Length and writes no bytes).
     */
    // @req FR-HTTP-021
    // @req FR-HTTP-023
    void automatic(HttpExchange ex, int status, @Nullable MethodSet allow, boolean upgradeHeaders) {
        List<Map.Entry<String, String>> headers = new ArrayList<>(5);
        if (allow != null) {
            headers.add(new SimpleImmutableEntry<>("Allow", allowHeader(allow)));
        }
        if (upgradeHeaders) {
            headers.add(new SimpleImmutableEntry<>("Upgrade", "websocket"));
            headers.add(new SimpleImmutableEntry<>("Connection", "Upgrade"));
        }
        byte[] body = new byte[0];
        if (status != 204) {
            body = StandardErrors.json(status, StandardErrors.reason(status), RequestIds.next());
            headers.add(new SimpleImmutableEntry<>("Content-Type", "application/json; charset=UTF-8"));
            headers.add(new SimpleImmutableEntry<>("Content-Length", String.valueOf(body.length)));
        }
        boolean head = "HEAD".equals(ex.methodToken());
        try {
            OutputStream out = ex.commit(status, headers, head ? 0 : body.length);
            if (!head && body.length > 0) {
                out.write(body);
            }
            ex.finish();
        } catch (IOException | RuntimeException e) {
            ex.abort();
        }
    }

    /**
     * Terminal step for every non-MATCHED lookup and for a MATCHED WebSocket route (no handshake until app.ws
     * exists). Answers through {@code res}, keeping headers middleware set; a response already left NEW is not
     * touched.
     */
    // @req FR-HTTP-022
    // @req FR-APP-003
    void fallback(RouteMatch<RouteHandler> m, DefaultResponse res, String requestId) {
        if (res.state() != ResponseState.NEW) {
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
            case MATCHED: // only a WEBSOCKET route reaches the fallback
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

    private static void error(DefaultResponse res, int status, String requestId) {
        res.status(status).header("Content-Type", "application/json; charset=UTF-8");
        res.send(StandardErrors.json(status, StandardErrors.reason(status), requestId));
    }

    /** {@code ", "}-joined members in {@link Method} declaration order. */
    // @req FR-HTTP-022
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
