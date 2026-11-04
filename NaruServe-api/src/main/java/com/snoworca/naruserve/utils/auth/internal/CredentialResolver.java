package com.snoworca.naruserve.utils.auth.internal;

import com.snoworca.naruserve.Request;
import org.jspecify.annotations.Nullable;

/**
 * Reads and verifies the credential of a request for the auth middleware: null when the request carries none, an
 * {@code AuthRejection} (401) when it carries one that does not verify. The token and API key verifiers plug in here.
 */
// @req IR-AUTH-004
@FunctionalInterface
public interface CredentialResolver {

    /** The verified credential of {@code req}, or null. */
    @Nullable Credential resolve(Request req) throws Exception;
}
