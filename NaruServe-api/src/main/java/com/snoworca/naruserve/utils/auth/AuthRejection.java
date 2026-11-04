package com.snoworca.naruserve.utils.auth;

import com.snoworca.naruserve.HttpException;
import org.jspecify.annotations.Nullable;

/**
 * A refusal with a 4xx status and a lower-case error code, thrown by an {@link AuthEvents} before-event or by the
 * app-facing auth facades. Auth answers it as the JSON error body {@code {"error":{"code","message","requestId"}}};
 * a null message is answered with a default one, and a retry delay becomes a {@code Retry-After} header.
 * Express has no counterpart; it is like calling {@code next(createError(422, ...))} with a fixed code. It is a Core
 * {@link HttpException} with that status, so Core treats it as a client error: it is not logged, and without an auth
 * error layer Core still answers its status (OPS-AUTH-003).
 */
// @req IR-AUTH-003
// @req OPS-AUTH-003
public class AuthRejection extends HttpException {

    private static final long serialVersionUID = 1L;

    private final int status;
    private final String code;
    private final @Nullable Long retryAfterSeconds;

    /** A rejection without a message; {@code status} is 400-499 and {@code code} matches {@code [a-z][a-z0-9_]{0,63}}. */
    // @req IR-AUTH-003
    public AuthRejection(int status, String code) {
        this(status, code, null, null);
    }

    /** A rejection with a message, which may be null. */
    // @req IR-AUTH-003
    public AuthRejection(int status, String code, @Nullable String message) {
        this(status, code, message, null);
    }

    /** A rejection with a message and a retry delay in seconds, either of which may be null. */
    // @req IR-AUTH-003
    public AuthRejection(int status, String code, @Nullable String message, @Nullable Long retryAfterSeconds) {
        super(clientStatus(status), message);
        if (retryAfterSeconds != null && retryAfterSeconds < 0) {
            throw new IllegalArgumentException("retryAfterSeconds must not be negative: " + retryAfterSeconds);
        }
        this.status = status;
        this.code = Codes.check(code, false);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    private static int clientStatus(int status) {
        if (status < 400 || status > 499) {
            throw new IllegalArgumentException("AuthRejection status must be 4xx: " + status + "; it refuses a client "
                    + "request. For an unavailable dependency throw AuthUnavailableException (503); for a server error "
                    + "throw an ordinary exception"); // OPS-AUTH-032 AC-3
        }
        return status;
    }

    /** The HTTP status, 400-499. */
    public int getStatus() {
        return status;
    }

    /** The lower-case error code of the JSON body. */
    public String getCode() {
        return code;
    }

    /** The {@code Retry-After} delay in seconds, or null for none. */
    public @Nullable Long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
