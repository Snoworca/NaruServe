package com.snoworca.naruserve.routing;

import com.snoworca.naruserve.Method;
import com.snoworca.naruserve.MethodSet;
import java.util.Collections;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Immutable result of {@link Router#lookup}: exactly one {@link Kind}. Fields a kind does not carry are null,
 * {@code false} or an empty map. Built only by {@link Router}.
 */
// @req IR-ROUTE-004
public final class RouteMatch<H> {

    /** The six lookup outcomes. The dispatcher, not the router, maps them to HTTP responses. */
    // @req IR-ROUTE-004
    public enum Kind {
        MATCHED, METHOD_MISMATCH, AUTO_OPTIONS, WEBSOCKET_ONLY, UNSUPPORTED_METHOD, NO_MATCH
    }

    private final Kind kind;
    private final @Nullable H handler;
    private final @Nullable Method method;
    private final @Nullable RouteKind routeKind;
    private final Map<String, String> params;
    private final boolean autoHead;
    private final @Nullable MethodSet allow;

    private RouteMatch(Kind kind, @Nullable H handler, @Nullable Method method, @Nullable RouteKind routeKind,
            Map<String, String> params, boolean autoHead, @Nullable MethodSet allow) {
        this.kind = kind;
        this.handler = handler;
        this.method = method;
        this.routeKind = routeKind;
        this.params = params;
        this.autoHead = autoHead;
        this.allow = allow;
    }

    // @req IR-ROUTE-004
    // @req FR-ROUTE-011
    static <H> RouteMatch<H> matched(H handler, Method method, RouteKind routeKind, Map<String, String> params,
            boolean autoHead) {
        return new RouteMatch<>(Kind.MATCHED, handler, method, routeKind, params, autoHead, null);
    }

    // @req FR-ROUTE-010
    // @req FR-ROUTE-012
    static <H> RouteMatch<H> withAllow(Kind kind, MethodSet allow) {
        return new RouteMatch<>(kind, null, null, null, Collections.<String, String>emptyMap(), false, allow);
    }

    // @req IR-ROUTE-004
    static <H> RouteMatch<H> of(Kind kind) {
        return new RouteMatch<>(kind, null, null, null, Collections.<String, String>emptyMap(), false, null);
    }

    public Kind kind() {
        return kind;
    }

    /** The registered handler; MATCHED only. */
    public @Nullable H handler() {
        return handler;
    }

    /** The route's method: the request method, or {@code GET} under automatic HEAD and for WebSocket; MATCHED only. */
    public @Nullable Method method() {
        return method;
    }

    /** MATCHED only. */
    public @Nullable RouteKind routeKind() {
        return routeKind;
    }

    /** Unmodifiable path parameters in pattern order; empty unless MATCHED. */
    public Map<String, String> params() {
        return params;
    }

    /** True only for a HEAD request served by a GET route. */
    public boolean autoHead() {
        return autoHead;
    }

    /** The {@code Allow} set; METHOD_MISMATCH and AUTO_OPTIONS only. */
    public @Nullable MethodSet allow() {
        return allow;
    }
}
