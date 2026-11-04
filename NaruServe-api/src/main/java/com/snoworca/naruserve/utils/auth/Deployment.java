package com.snoworca.naruserve.utils.auth;

/**
 * How many nodes share one user base. {@link #SINGLE_INSTANCE} is the default. {@link #MULTI_INSTANCE} makes
 * {@code build()} refuse every store that is not shared between nodes (in-memory and file stores, and the in-memory
 * defaults of unset refresh, throttle and device-trust stores).
 */
// @req FR-AUTH-003
public enum Deployment {
    /** One process; the default. */
    SINGLE_INSTANCE,
    /** Several processes behind a load balancer, sharing their stores. */
    MULTI_INSTANCE
}
