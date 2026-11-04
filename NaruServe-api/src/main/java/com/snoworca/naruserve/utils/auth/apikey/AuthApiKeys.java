package com.snoworca.naruserve.utils.auth.apikey;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The app's API key facade, {@code auth.apiKeys()}: issue, list and revoke the keys of any user from app code, for
 * example the keys of a service account an operator script creates. It is the only way to create a key that never
 * expires ({@code expiresAt} null). It throws the exceptions of {@code auth.users()}: {@code IllegalArgumentException}
 * (400; an unknown or disabled owner, an invalid scope, an {@code expiresAt} that is not in the future),
 * {@code AuthRejection} (409 {@code api_key_limit} when the owner already holds {@code maxKeysPerUser} live keys) and
 * {@code AuthUnavailableException} (503; a store failure, or a key prefix that kept colliding), plus
 * {@code AuthStateException} for any call when the builder had no {@code .apiKeys(store)}. {@code auth.errors()} turns
 * them into JSON.
 *
 * <p><b>This facade performs no actor checks.</b> It runs none of the admin grant rule (an actor may give only what it
 * holds) and none of the API-key actor restrictions; it trusts its caller as app code. An app that builds a delegated
 * key-management screen on it must check the acting user itself first, or use the admin endpoints
 * ({@code /auth/admin/apikeys}), which do. Express has no counterpart; it is like the service layer behind a
 * personal-access-token page.
 */
// @req IR-APIKEY-002
public interface AuthApiKeys {

    /**
     * A new key of {@code ownerUserId} named {@code name} with {@code scopes}, expiring at {@code expiresAt} (epoch
     * milliseconds) or never when null; the plain key is in the result this one time. The key is bound to the owner's
     * current {@code ver} read from the store, so a later logout of all devices or password change makes it stale.
     * Admin scopes ({@code auth-*}) need {@code .allowAdminScopesOnApiKeys(true)}.
     */
    IssuedApiKey issue(String ownerUserId, String name, @Nullable Long expiresAt, String... scopes);

    /** Every key of {@code ownerUserId} with its status ({@code ACTIVE}, {@code EXPIRED}, {@code STALE}), no secret. */
    List<ApiKey> list(String ownerUserId);

    /** Deletes the key; false (no exception) when there is no such key. */
    boolean revoke(String keyId);
}
