package com.snoworca.naruserve.routing;

/**
 * A route configuration error: an invalid pattern at registration, or conflicting registrations at freeze.
 * It is deliberately neither an {@link IllegalStateException} nor an {@link IllegalArgumentException}.
 */
// @req FR-ROUTE-003
// @req FR-ROUTE-008
public final class RoutingConfigException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public RoutingConfigException(String message) {
        super(message);
    }
}
