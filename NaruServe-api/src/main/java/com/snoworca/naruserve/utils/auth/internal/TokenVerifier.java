package com.snoworca.naruserve.utils.auth.internal;

/**
 * Verifies one access token string read by {@link TransportCredentials}: the credential, or an
 * {@code AuthRejection} 401 {@code invalid_token}. The JWT verifier plugs in here; until it exists every token is
 * refused.
 */
// @req SEC-FLOW-004
@FunctionalInterface
public interface TokenVerifier {

    /** The verified credential of {@code token}. */
    Credential verify(String token) throws Exception;
}
