package com.snoworca.naruserve;

/** HTTP CORS policy. Configure before authentication middleware; WebSocket origins are configured separately. */
// @req FR-DX-010
public interface CorsOptions {
    /** Exact serialized HTTP(S) origins, literal null, or wildcard *. Default: no allowed origins. */
    CorsOptions allowedOrigins(String... origins);
    /** Allowed preflight methods. Defaults to GET, HEAD and POST. */
    CorsOptions allowedMethods(Method... methods);
    /** Allowed request header names, compared without case. Default: no request headers. */
    CorsOptions allowedHeaders(String... headers);
    /** Allows browser credentials; cannot be combined with wildcard origin. Default: false. */
    CorsOptions credentials(boolean allowed);
    /** Nonnegative preflight cache lifetime in seconds. Zero omits the header; default zero. */
    CorsOptions maxAge(long seconds);
}
