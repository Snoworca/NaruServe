package com.snoworca.naruserve;

/**
 * HTTP request method. Sets of methods are built with the static factories {@link #get()}, {@link #any()}
 * and {@link #of(Method...)}; the constants themselves have no combinators.
 */
// @req IR-ROUTE-001
// @req FR-ROUTE-001
public enum Method {
    GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS, TRACE, CONNECT;

    private static final MethodSet ANY = MethodSet.of(GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS);

    /** {@code {GET}}. */
    // @req IR-ROUTE-001
    public static MethodSet get() {
        return MethodSet.of(GET);
    }

    /** Every routable method except {@code TRACE} and {@code CONNECT}, whatever router policy is in effect. */
    // @req FR-ROUTE-001
    public static MethodSet any() {
        return ANY;
    }

    /** Exactly the given methods; throws {@link NullPointerException} for a null array or element. */
    // @req IR-ROUTE-001
    public static MethodSet of(Method... methods) {
        return MethodSet.of(methods);
    }
}
