package com.snoworca.naruserve.utils.auth;

/** Where the access token travels; {@link #HEADER} when {@code .transport(...)} is not called. */
// @req FR-AUTH-001
public enum TokenTransport {
    /** {@code Authorization: Bearer} headers and token bodies; the default. */
    HEADER,
    /** HttpOnly cookies with a CSRF token. */
    COOKIE,
    /** Either, decided per request. */
    BOTH
}
