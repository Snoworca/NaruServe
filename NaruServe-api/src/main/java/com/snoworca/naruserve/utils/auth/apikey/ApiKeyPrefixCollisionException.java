package com.snoworca.naruserve.utils.auth.apikey;

/**
 * Thrown by {@link ApiKeyStore#insertIfUnderLimit(StoredApiKey, int, long)} when the prefix of the new key already
 * exists; the issuer draws a new key and tries again (at most three times). The message never holds the key.
 */
// @req IR-APIKEY-004
public class ApiKeyPrefixCollisionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** A collision described by {@code message}. */
    public ApiKeyPrefixCollisionException(String message) {
        super(message);
    }
}
