package com.snoworca.naruserve.utils.auth;

/**
 * A programming error in the use of auth, such as {@code auth.user(req)} on a request without a principal (a public
 * path): use {@code auth.userOrNull(req)} there. The auth error handling answers it 500 JSON without the message;
 * in an app route it reaches the app's own error handlers.
 */
// @req IR-AUTH-004
public class AuthStateException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    /** The error with its message. */
    // @req IR-AUTH-004
    public AuthStateException(String message) {
        super(message);
    }
}
