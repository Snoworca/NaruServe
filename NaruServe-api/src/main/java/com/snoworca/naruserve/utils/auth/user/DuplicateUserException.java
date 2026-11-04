package com.snoworca.naruserve.utils.auth.user;

import com.snoworca.naruserve.HttpException;
import org.jspecify.annotations.Nullable;

/**
 * A username that already exists: HTTP 409 {@code duplicate_user}. Unchecked; a store throws it on a unique-key
 * violation. A Core {@link HttpException} with status 409, so Core does not log it as a server failure (OPS-AUTH-003).
 */
// @req IR-IAM-001
// @req OPS-AUTH-003
public class DuplicateUserException extends HttpException {

    private static final long serialVersionUID = 1L;

    /** A duplicate with a message, which may be null. */
    // @req IR-IAM-001
    public DuplicateUserException(@Nullable String message) {
        super(409, message);
    }

    /** A duplicate with a message and the store's cause. */
    // @req IR-IAM-001
    public DuplicateUserException(@Nullable String message, @Nullable Throwable cause) {
        super(409, message, cause);
    }
}
