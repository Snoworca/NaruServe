package com.snoworca.naruserve.routing;

import com.snoworca.naruserve.Method;
import com.snoworca.naruserve.MethodSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Engine-free route table. The handler type {@code H} is opaque to the router, so it holds any value the
 * caller registers. Configure with {@link #add} and {@link #addWebSocket}, then call {@link #freeze()} once.
 * Registration and freeze are serialised by the router's lock; after freeze the table is read-only and published
 * through a volatile write, so {@link #lookup} is safe from any number of threads.
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

    private static final Method[] METHODS = Method.values();
    private static final Pattern REPEATED_SLASHES = Pattern.compile("/{2,}");

    private final RouterPolicy policy;
    /** Growable while configuring; replaced by an unmodifiable copy at freeze. */
    volatile List<Route<H>> routes = new ArrayList<>();
    /** Written last in freeze(); a reader that sees true also sees the frozen table. */
    private volatile boolean frozen;

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
    public synchronized void add(MethodSet methods, String pattern, H handler) {
        RoutePattern parsed = RoutePattern.parse(pattern);
        checkRegistration(handler);
        for (Method m : methods) {
            routes.add(new Route<>(RouteKind.HTTP, m, parsed, handler));
        }
    }

    // @req FR-ROUTE-002
    public synchronized void add(Method method, String pattern, H handler) {
        add(MethodSet.of(method), pattern, handler);
    }

    /** Internal hook for WebSocket routes: kind {@link RouteKind#WEBSOCKET}, counted as {@code GET}. */
    // @req FR-ROUTE-002
    public synchronized void addWebSocket(String pattern, H handler) {
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
    public synchronized void freeze() {
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
        routes = Collections.unmodifiableList(new ArrayList<>(routes));
        frozen = true;
    }

    /** The most specific HTTP route of {@code method} matching {@code segments}, or null. */
    // @req FR-ROUTE-007
    @Nullable Route<H> find(Method method, List<String> segments) {
        Route<H> best = null;
        for (Route<H> r : routes) {
            if (r.kind == RouteKind.HTTP && r.method == method && r.pattern.matches(segments, policy)) {
                best = better(best, r);
            }
        }
        return best;
    }

    /** {@code candidate} when it is strictly more specific than {@code best}; ties keep the earlier registration. */
    // @req FR-ROUTE-007
    private static <H> Route<H> better(@Nullable Route<H> best, Route<H> candidate) {
        return best == null || candidate.pattern.compareSpecificity(best.pattern) > 0 ? candidate : best;
    }

    /**
     * Resolves one request. {@code rawPath} is the undecoded request target; a path {@link PathValidator} rejects
     * throws {@link IllegalArgumentException}. {@code upgrade} counts only for the token {@code GET}. Order:
     * unsupported method, no match, matched, automatic OPTIONS, automatic HEAD, WebSocket-only, method mismatch.
     */
    // @req IR-ROUTE-004
    // @req FR-ROUTE-009
    // @req FR-ROUTE-013
    // @req FR-ROUTE-014
    // @req FR-ROUTE-016
    // @req FR-ROUTE-017
    public RouteMatch<H> lookup(String methodToken, String rawPath, boolean upgrade) {
        if (!frozen) {
            throw new IllegalStateException("Router is not frozen; call freeze() before lookup");
        }
        // shortcut: decodeSegments re-decodes what isValid decoded; cheap for '%'-free segments, fuse if profiling says so.
        if (!PathValidator.isValid(rawPath)) {
            throw new IllegalArgumentException("Rejected request path: " + rawPath);
        }
        Method method = null;
        for (Method m : METHODS) {
            if (m.name().equals(methodToken)) {
                method = m;
                break;
            }
        }
        if (method == null || (method == Method.TRACE && !policy.enableTrace())
                || (method == Method.CONNECT && !policy.enableConnect())) {
            return RouteMatch.of(RouteMatch.Kind.UNSUPPORTED_METHOD);
        }
        List<String> segments = decodeSegments(rawPath, policy);
        // One allocation-free pass over the frozen table; params are built only for the winner.
        Route<H> ws = null;
        Route<H> direct = null;
        Route<H> get = null;
        boolean httpMatch = false;
        int methodMask = 0;
        for (Route<H> r : routes) {
            if (!r.pattern.matches(segments, policy)) {
                continue;
            }
            methodMask |= 1 << r.method.ordinal();
            if (r.kind == RouteKind.WEBSOCKET) {
                ws = better(ws, r);
            } else {
                httpMatch = true;
                if (r.method == method) {
                    direct = better(direct, r);
                }
                if (r.method == Method.GET) {
                    get = better(get, r);
                }
            }
        }
        boolean wsMatch = ws != null;
        if (upgrade && method == Method.GET && ws != null) {
            return matched(ws, segments, false);
        }
        if (!httpMatch && !wsMatch) {
            return RouteMatch.of(RouteMatch.Kind.NO_MATCH);
        }
        if (direct != null) {
            return matched(direct, segments, false);
        }
        if (method == Method.OPTIONS) {
            return RouteMatch.withAllow(RouteMatch.Kind.AUTO_OPTIONS, allowOf(methodMask));
        }
        if (method == Method.HEAD && get != null) {
            return matched(get, segments, true);
        }
        // Reaching here, GET and HEAD have no HTTP GET route on this path.
        if (wsMatch && (method == Method.GET || method == Method.HEAD || !httpMatch)) {
            return RouteMatch.of(RouteMatch.Kind.WEBSOCKET_ONLY);
        }
        return RouteMatch.withAllow(RouteMatch.Kind.METHOD_MISMATCH, allowOf(methodMask));
    }

    private RouteMatch<H> matched(Route<H> r, List<String> segments, boolean autoHead) {
        Map<String, String> params = r.pattern.match(segments, policy);
        return RouteMatch.matched(r.handler, r.method, r.kind,
                params == null ? Collections.<String, String>emptyMap() : params, autoHead);
    }

    /**
     * The {@code Allow} set for decoded {@code segments}: methods of every matching route (a WebSocket route counts
     * as {@code GET}), plus {@code HEAD} when {@code GET} is present, plus {@code OPTIONS}.
     */
    // @req FR-ROUTE-010
    // @req FR-ROUTE-012
    public MethodSet allowFor(List<String> segments) {
        int methodMask = 0;
        for (Route<H> r : routes) {
            if (r.pattern.matches(segments, policy)) {
                methodMask |= 1 << r.method.ordinal();
            }
        }
        return allowOf(methodMask);
    }

    /** Allow set from a bit per matching route method (bit = ordinal): those methods, HEAD with GET, OPTIONS. */
    private static MethodSet allowOf(int methodMask) {
        MethodSet allow = MethodSet.of(Method.OPTIONS);
        for (Method m : METHODS) {
            if ((methodMask & (1 << m.ordinal())) != 0) {
                allow = allow.with(m);
            }
        }
        return allow.contains(Method.GET) ? allow.with(Method.HEAD) : allow;
    }

    /**
     * Upgrade flag for {@link #lookup}: true only for {@code GET} whose {@code Connection} list holds
     * {@code upgrade} and whose {@code Upgrade} list holds {@code websocket}, tokens compared case-insensitively.
     */
    // @req FR-ROUTE-015
    public static boolean isWebSocketUpgrade(String methodToken, @Nullable String connection,
            @Nullable String upgrade) {
        return "GET".equals(methodToken) && hasToken(connection, "upgrade") && hasToken(upgrade, "websocket");
    }

    private static boolean hasToken(@Nullable String header, String token) {
        if (header == null) {
            return false;
        }
        for (String part : header.split(",")) {
            if (part.trim().equalsIgnoreCase(token)) {
                return true;
            }
        }
        return false;
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
            path = REPEATED_SLASHES.matcher(path).replaceAll("/");
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
