package com.snoworca.naruserve.utils.auth;

import com.snoworca.naruserve.HttpException;

/**
 * A client error whose message auth wrote for the client: a refused new password (the rule, never the password,
 * FR-TOKEN-004 AC-8) or a request-shape 400 naming the field (OPS-AUTH-006 AC-1). The auth router error layer sends
 * this message; the message of any other exception stays out of the body (FR-AUTH-006 AC-4).
 */
// @req FR-TOKEN-004
// @req OPS-AUTH-006
final class ShownError extends HttpException {

    private static final long serialVersionUID = 1L;

    ShownError(int status, String message) {
        super(status, message);
    }
}
