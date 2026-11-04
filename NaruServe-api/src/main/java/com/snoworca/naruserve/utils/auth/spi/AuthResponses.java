package com.snoworca.naruserve.utils.auth.spi;

import com.snoworca.naruserve.Request;
import com.snoworca.naruserve.Response;

/**
 * Writes the body of every auth error: the auth middleware, the guards and the auth routers call it with the status,
 * code and a safe message (never an exception text). The status is already set on {@code res} and a
 * {@code Retry-After} header, when there is one, is already added; an implementation changes only the body (and may
 * add headers). The default writes the Core shape {@code {"error":{"code","message","requestId"}}}. If an
 * implementation throws, auth logs it and writes the default body with the same status. Kotlin:
 * {@code AuthResponses { _, res, _, code, _ -> res.json(mapOf("err" to code)) }}.
 *
 * <p>After the first public release this interface gains default methods only.
 */
// @req FR-AUTH-006
@FunctionalInterface
public interface AuthResponses {

    /** Writes the error body of a response whose status is {@code status}. */
    void error(Request req, Response res, int status, String code, String message) throws Exception;
}
