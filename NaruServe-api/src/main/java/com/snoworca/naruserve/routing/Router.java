package com.snoworca.naruserve.routing;

import com.snoworca.naruserve.Method;
import com.snoworca.naruserve.MethodSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Engine-free route table. The handler type {@code H} is opaque to the router, so it holds any value the
 * caller registers. Configure with {@link #add} and {@link #addWebSocket}, then call {@link #freeze()} once.
 */
// @req CON-ROUTE-001
// @req FR-ROUTE-002
public final class Router<H> {

    /** One registration for one method. A MethodSet registration stores one entry per member. */
    static final class Route<H> {
        final RouteKind kind;
        final Method method;
        final RoutePattern pattern;
        final H handler;

        Route(RouteKind kind, Method method, RoutePattern pattern, H handler) {
            this.kind = kind;
            this.method = method;
            this.pattern = pattern;
            this.handler = handler;
        }

        @Override
        public String toString() {
            return method + " " + pattern;
        }
    }

    private final RouterPolicy policy;
    final List<Route<H>> routes = new ArrayList<>();
    private boolean frozen;

    // RouterPolicy is immutable, so holding the reference is the copy.
    // @req FR-ROUTE-006
    public Router(RouterPolicy policy) {
        if (policy == null) {
            throw new NullPointerException("policy");
        }
        this.policy = policy;
    }

    /** Registers {@code handler} for every method in {@code methods}; invalid patterns throw here. */
    // @req FR-ROUTE-002
    // @req FR-ROUTE-003
    public void add(MethodSet methods, String pattern, H handler) {
        RoutePattern parsed = RoutePattern.parse(pattern);
        checkRegistration(handler);
        for (Method m : methods) {
            routes.add(new Route<>(RouteKind.HTTP, m, parsed, handler));
        }
    }

    // @req FR-ROUTE-002
    public void add(Method method, String pattern, H handler) {
        add(MethodSet.of(method), pattern, handler);
    }

    /** Internal hook for WebSocket routes: kind {@link RouteKind#WEBSOCKET}, counted as {@code GET}. */
    // @req FR-ROUTE-002
    public void addWebSocket(String pattern, H handler) {
        RoutePattern parsed = RoutePattern.parse(pattern);
        checkRegistration(handler);
        routes.add(new Route<>(RouteKind.WEBSOCKET, Method.GET, parsed, handler));
    }

    private void checkRegistration(@Nullable Object handler) {
        if (frozen) {
            throw new IllegalStateException("Router is frozen; register routes before freeze()");
        }
        if (handler == null) {
            throw new NullPointerException("handler");
        }
    }

    /**
     * Checks the table once. Throws one {@link RoutingConfigException} with a line per conflicting pair
     * ("METHOD pattern conflicts with METHOD pattern") and per TRACE/CONNECT route while that method is disabled.
     */
    // @req FR-ROUTE-008
    public void freeze() {
        if (frozen) {
            return;
        }
        List<String> errors = new ArrayList<>();
        List<String> keys = new ArrayList<>(routes.size());
        for (Route<H> r : routes) {
            keys.add(r.pattern.conflictKey(policy));
        }
        // shortcut: pairwise scan is O(n^2) at startup only; switch to a key map if tables reach thousands.
        for (int i = 0; i < routes.size(); i++) {
            Route<H> a = routes.get(i);
            for (int j = i + 1; j < routes.size(); j++) {
                Route<H> b = routes.get(j);
                if (a.kind == b.kind && a.method == b.method && keys.get(i).equals(keys.get(j))) {
                    errors.add(a + " conflicts with " + b);
                }
            }
        }
        for (Route<H> r : routes) {
            boolean disabled = (r.method == Method.TRACE && !policy.enableTrace())
                    || (r.method == Method.CONNECT && !policy.enableConnect());
            if (disabled) {
                errors.add(r + " registered while " + r.method + " routing is disabled");
            }
        }
        if (!errors.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (String e : errors) {
                sb.append(sb.length() == 0 ? "" : "\n").append(e);
            }
            throw new RoutingConfigException(sb.toString());
        }
        frozen = true;
    }

    /** The most specific HTTP route of {@code method} matching {@code segments}, or null. */
    // @req FR-ROUTE-007
    @Nullable Route<H> find(Method method, List<String> segments) {
        Route<H> best = null;
        for (Route<H> r : routes) {
            if (r.kind == RouteKind.HTTP && r.method == method && r.pattern.match(segments, policy) != null
                    && (best == null || r.pattern.compareSpecificity(best.pattern) > 0)) {
                best = r;
            }
        }
        return best;
    }

    /**
     * Path segments of {@code rawPath}: query dropped, split on '/', each segment UTF-8 percent-decoded once
     * ('+' kept), then the policy's slash rules applied. Authority and absolute-form targets are not parsed.
     * Throws {@link IllegalArgumentException} for a segment that does not decode; run {@link PathValidator} first.
     */
    // @req FR-ROUTE-005
    // @req FR-ROUTE-006
    public static List<String> decodeSegments(String rawPath, RouterPolicy policy) {
        String path = PathValidator.stripQuery(rawPath);
        if (policy.mergeSlashes()) {
            path = path.replaceAll("/{2,}", "/");
        }
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        List<String> out = new ArrayList<>();
        for (String raw : path.split("/", -1)) {
            String seg = PathValidator.decode(raw);
            if (seg == null) {
                throw new IllegalArgumentException("Undecodable path segment in " + rawPath);
            }
            out.add(seg);
        }
        if (policy.ignoreTrailingSlash() && out.size() > 1 && out.get(out.size() - 1).isEmpty()) {
            out.remove(out.size() - 1);
        }
        return Collections.unmodifiableList(out);
    }
}
