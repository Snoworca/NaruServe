package com.snoworca.naruserve.utils.auth;

/**
 * The auth-owned error codes and their HTTP statuses. Core codes that auth also answers (400 {@code BAD_REQUEST},
 * 404 {@code NOT_FOUND}, 415 {@code UNSUPPORTED_MEDIA_TYPE}, 503 {@code SERVICE_UNAVAILABLE}) are not listed here.
 * {@code requireRole}, a false {@code auth.check} rule and the self-rule refusal answer 403
 * {@link #INSUFFICIENT_SCOPE}; a wrong current password answers {@link #INVALID_CREDENTIALS}.
 */
// @req IR-AUTH-003
public enum AuthErrorCode {
    /** No credential on a protected path. */
    UNAUTHENTICATED("unauthenticated", 401),
    /** A bearer token that does not verify or has expired. */
    INVALID_TOKEN("invalid_token", 401),
    /** An API key that does not verify. */
    INVALID_API_KEY("invalid_api_key", 401),
    /** More than one credential in one request. */
    INVALID_REQUEST("invalid_request", 400),
    /** The principal lacks a required scope or role, or may not grant or touch the target. */
    INSUFFICIENT_SCOPE("insufficient_scope", 403),
    /** An endpoint that API keys may not call. */
    API_KEY_NOT_ALLOWED("api_key_not_allowed", 403),
    /** A plaintext non-local request in PROD. */
    INSECURE_TRANSPORT("insecure_transport", 403),
    /** A CSRF token or Origin failure in cookie mode. */
    CSRF_FAILED("csrf_failed", 403),
    /** The per-user API key limit is reached. */
    API_KEY_LIMIT("api_key_limit", 409),
    /** Login, password check or API key failures over their limit. */
    TOO_MANY_ATTEMPTS("too_many_attempts", 429),
    /** A password operation on a service account. */
    SERVICE_ACCOUNT_NO_PASSWORD("service_account_no_password", 400),
    /** A setup body whose username is not the configured one. */
    USERNAME_MISMATCH("username_mismatch", 400),
    /** A security write whose re-read does not confirm it. */
    SECURITY_WRITE_CONFLICT("security_write_conflict", 503),
    /** A wrong username or password, also a wrong current password. */
    INVALID_CREDENTIALS("invalid_credentials", 401),
    /** A refresh token that does not verify, was revoked or reused. */
    INVALID_REFRESH_TOKEN("invalid_refresh_token", 401),
    /** A duplicate username on create or rename. */
    DUPLICATE_USER("duplicate_user", 409),
    /** A password change that lost a concurrent write. */
    WRITE_CONFLICT("write_conflict", 409),
    /** A group edge that would close a cycle or exceed the depth limit. */
    GROUP_CYCLE("group_cycle", 409),
    /** A group delete that would touch more than 1,000 users. */
    GROUP_TOO_LARGE("group_too_large", 409),
    /** A setup token that does not match the logged one. */
    INVALID_SETUP_TOKEN("invalid_setup_token", 401),
    /** A setup request that is not local. */
    SETUP_NOT_LOCAL("setup_not_local", 403);

    /** The answer to a wrong current password: the same as a wrong login password. */
    public static final AuthErrorCode WRONG_CURRENT_PASSWORD = INVALID_CREDENTIALS;

    private final String code;
    private final int status;

    AuthErrorCode(String code, int status) {
        this.code = code;
        this.status = status;
    }

    /** The lower-case code of the JSON body. */
    public String getCode() {
        return code;
    }

    /** The HTTP status. */
    public int getStatus() {
        return status;
    }
}
