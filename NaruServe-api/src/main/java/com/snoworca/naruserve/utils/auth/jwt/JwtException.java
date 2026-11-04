package com.snoworca.naruserve.utils.auth.jwt;

/**
 * A token that {@link Jwt#verify(String, String)} refused, with the {@link Reason}. The message names the reason
 * only, never the token or a key. Express counterpart: the {@code JsonWebTokenError} / {@code TokenExpiredError} of
 * {@code jsonwebtoken}.
 */
// @req SEC-TOKEN-003
public final class JwtException extends Exception {

    private static final long serialVersionUID = 1L;

    /** Why a token was refused. */
    public enum Reason {
        /** Not three base64url parts, not canonical base64url, not strict JSON, or a claim of the wrong type. */
        MALFORMED,
        /** The header {@code alg} is not exactly {@code HS256}, or the header carries {@code crit}. */
        BAD_ALGORITHM,
        /** The header {@code kid} names no key of the set. */
        UNKNOWN_KEY,
        /** The signature does not match. */
        BAD_SIGNATURE,
        /** {@code exp} plus the leeway has passed. */
        EXPIRED,
        /** {@code nbf} or {@code iat} lies further in the future than the leeway. */
        NOT_YET_VALID,
        /** The header {@code typ} is not the expected type. */
        WRONG_TYPE,
        /** {@code iss} is not the configured issuer. */
        WRONG_ISSUER,
        /** {@code aud} does not hold the configured audience. */
        WRONG_AUDIENCE,
        /** The token is longer than the maximum token size. */
        TOO_LARGE
    }

    private final Reason reason;

    /** A refusal for {@code reason}, with a message that describes it. */
    // @req SEC-TOKEN-003
    public JwtException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    /** Why the token was refused. */
    public Reason getReason() {
        return reason;
    }
}
