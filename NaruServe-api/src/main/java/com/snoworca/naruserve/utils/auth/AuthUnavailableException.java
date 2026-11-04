package com.snoworca.naruserve.utils.auth;

import org.jspecify.annotations.Nullable;

/**
 * Auth could not answer because a store, a listener or the hash slots failed: HTTP 503 with the code
 * {@code SERVICE_UNAVAILABLE} unless another code is given. An unconfirmed security write carries
 * {@link AuthErrorCode#SECURITY_WRITE_CONFLICT}. Unchecked, so app handlers need no {@code throws} clause.
 */
// @req IR-AUTH-003
public class AuthUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** The code of a plain store or slot failure, the Core spelling. */
    public static final String SERVICE_UNAVAILABLE = "SERVICE_UNAVAILABLE";

    private final String code;
    private final @Nullable Long retryAfterSeconds;

    /** A 503 with the code {@code SERVICE_UNAVAILABLE}. */
    // @req IR-AUTH-003
    public AuthUnavailableException(@Nullable String message) {
        this(SERVICE_UNAVAILABLE, message, null);
    }

    /** A 503 with the code {@code SERVICE_UNAVAILABLE} and its cause. */
    // @req IR-AUTH-003
    public AuthUnavailableException(@Nullable String message, @Nullable Throwable cause) {
        this(SERVICE_UNAVAILABLE, message, cause);
    }

    /** A 503 with its own code, for example {@code security_write_conflict}. */
    // @req IR-AUTH-003
    public AuthUnavailableException(String code, @Nullable String message, @Nullable Throwable cause) {
        this(code, message, null, cause);
    }

    /** A 503 with its own code and a {@code Retry-After} delay in seconds, for example a busy hash slot pool. */
    // @req IR-AUTH-003
    // @req REL-TOKEN-001
    public AuthUnavailableException(String code, @Nullable String message, @Nullable Long retryAfterSeconds,
            @Nullable Throwable cause) {
        super(message, cause);
        if (retryAfterSeconds != null && retryAfterSeconds < 0) {
            throw new IllegalArgumentException("retryAfterSeconds must not be negative: " + retryAfterSeconds);
        }
        this.code = Codes.check(code, true);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /** The error code of the JSON body. */
    public String getCode() {
        return code;
    }

    /** The {@code Retry-After} delay in seconds, or null for none. */
    public @Nullable Long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    /** Always 503. */
    public int getStatus() {
        return 503;
    }
}
