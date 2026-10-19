package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.Method;
import com.snoworca.naruserve.MethodSet;
import com.snoworca.naruserve.RouteHandler;
import com.snoworca.naruserve.routing.PathValidator;
import com.snoworca.naruserve.routing.RouteKind;
import com.snoworca.naruserve.routing.RouteMatch;
import com.snoworca.naruserve.routing.Router;
import com.snoworca.naruserve.spi.ExchangeHandler;
import com.snoworca.naruserve.spi.HttpExchange;
import java.io.IOException;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Engine-free entry the adapter calls per exchange. It is the only core-side place that maps pre-checks and
 * {@link RouteMatch} kinds to HTTP statuses, so every adapter answers identically (adapters still answer an
 * authority-form CONNECT themselves).
 */
// @req CON-HTTP-001
// @req FR-HTTP-002
// @req FR-HTTP-003
public final class Dispatcher implements ExchangeHandler {

    private final Router<RouteHandler> router;

    /** {@code router} must already be frozen. */
    // @req FR-HTTP-002
    public Dispatcher(Router<RouteHandler> router) {
        if (router == null) {
            throw new NullPointerException("router");
        }
        this.router = router;
    }

    // @req FR-HTTP-002
    // @req FR-HTTP-003
    @Override
    public void handle(HttpExchange ex) {
        if (preCheck(ex)) {
            return;
        }
        String token = ex.methodToken();
        boolean upgrade = Router.isWebSocketUpgrade(token, joined(ex, "Connection"), joined(ex, "Upgrade"));
        RouteMatch<RouteHandler> m = router.lookup(token, ex.rawPath(), upgrade);
        switch (m.kind()) {
            case MATCHED:
                if (m.routeKind() == RouteKind.WEBSOCKET) {
                    // No handshake until app.ws exists: a matching upgrade gets the same 426 as a plain GET.
                    automatic(ex, 426, null, true);
                } else {
                    invoke(m, ex);
                }
                return;
            case METHOD_MISMATCH:
                automatic(ex, 405, m.allow(), false);
                return;
            case AUTO_OPTIONS:
                automatic(ex, 204, m.allow(), false);
                return;
            case WEBSOCKET_ONLY:
                automatic(ex, 426, null, true);
                return;
            case UNSUPPORTED_METHOD:
                automatic(ex, 501, null, false);
                return;
            default:
                automatic(ex, 404, null, false);
        }
    }

    /** Answers OPTIONS *, a raw path not starting with '/' and a validator rejection; true when it answered. */
    // @req FR-HTTP-002
    // @req FR-HTTP-005
    boolean preCheck(HttpExchange ex) {
        String rawPath = ex.rawPath();
        if ("OPTIONS".equals(ex.methodToken()) && "*".equals(rawPath)) {
            automatic(ex, 204, Method.any(), false);
            return true;
        }
        if (!rawPath.startsWith("/") || !PathValidator.isValid(rawPath)) {
            automatic(ex, 400, null, false);
            return true;
        }
        return false;
    }

    /** Writes an empty-body reply straight to the exchange; 204 carries no Content-Length header. */
    // @req FR-HTTP-003
    void automatic(HttpExchange ex, int status, @Nullable MethodSet allow, boolean upgradeHeaders) {
        List<Map.Entry<String, String>> headers = new ArrayList<>(4);
        if (allow != null) {
            headers.add(new SimpleImmutableEntry<>("Allow", allowHeader(allow)));
        }
        if (upgradeHeaders) {
            headers.add(new SimpleImmutableEntry<>("Upgrade", "websocket"));
            headers.add(new SimpleImmutableEntry<>("Connection", "Upgrade"));
        }
        if (status != 204) {
            headers.add(new SimpleImmutableEntry<>("Content-Length", "0"));
        }
        try {
            ex.commit(status, headers, 0);
            ex.finish();
        } catch (IOException e) {
            ex.abort();
        }
    }

    /**
     * Runs the matched HTTP handler.
     *
     * <p>shortcut: DefaultRequest and DefaultResponse arrive with wave-3 parts 4 and 5; until then a matched HTTP
     * route cannot run, so this throws and the adapter closes the exchange abnormally. Replace with the handler
     * call and the interim failure rules (FR-HTTP-004, FR-HTTP-006) when those classes land. A malformed query is
     * already answered 400 before the handler (FR-HTTP-010).
     */
    // @req FR-HTTP-004
    // @req FR-HTTP-006
    // @req FR-HTTP-010
    void invoke(RouteMatch<RouteHandler> m, HttpExchange ex) {
        Method method = m.autoHead() ? Method.HEAD : Objects.requireNonNull(m.method());
        DefaultRequest req = new DefaultRequest(ex, method, m.params());
        try {
            req.decodeQuery();
        } catch (IllegalArgumentException e) {
            automatic(ex, 400, null, false);
            return;
        }
        throw new UnsupportedOperationException(
                "handler invocation needs DefaultRequest and DefaultResponse (wave-3 parts 4 and 5)");
    }

    /** {@code ", "}-joined members in {@link Method} declaration order. */
    // @req FR-HTTP-003
    // @req FR-HTTP-005
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
