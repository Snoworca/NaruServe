package com.snoworca.naruserve.utils.auth.testing;

import com.snoworca.naruserve.utils.auth.Auth;
import com.snoworca.naruserve.utils.auth.apikey.ApiKeyStore;
import com.snoworca.naruserve.utils.auth.internal.TestingBridge;
import com.snoworca.naruserve.utils.auth.user.UserStore;
import java.util.concurrent.Callable;

/**
 * Test support for applications using auth: real credentials issued through the normal issue paths (the signing key
 * is never copied or exposed) and the contract tests of the store interfaces. It ships in the main jar and needs no
 * test framework: a broken contract throws {@link AssertionError}, which JUnit 4, JUnit 5 and Kotlin tests all report
 * as a failure. Express counterpart: the helpers a {@code supertest} suite writes to log a user in.
 */
// @req IR-AUTH-005
public final class AuthTesting {

    private AuthTesting() {
    }

    /**
     * An access token for the user {@code name} with exactly {@code roles}: the user is created in the
     * {@link com.snoworca.naruserve.utils.auth.user.InMemoryUserStore} of {@code auth}, or its roles are overwritten
     * when it exists. Every role must be declared in the builder's RolePolicy ({@link IllegalArgumentException}
     * otherwise); any other user store throws {@link IllegalStateException} (use {@link #tokenForUserId}). The token is
     * issued by {@code auth.tokens()} at the user's current version in a synthetic family with no refresh record.
     * {@code tokenFor(auth, "alice")} issues a token for alice with no roles.
     */
    // @req IR-AUTH-005
    public static String tokenFor(Auth auth, String name, String... roles) {
        return TestingBridge.ops().tokenFor(auth, name, roles);
    }

    /**
     * An access token for the stored user {@code userId} at its current version, for any user store; the user is not
     * modified. {@link IllegalArgumentException} ("no user with id ...") for an unknown id.
     */
    // @req IR-AUTH-005
    public static String tokenForUserId(Auth auth, String userId) {
        return TestingBridge.ops().tokenForUserId(auth, userId);
    }

    /**
     * A real, non-expiring API key of {@code ownerUserId} with {@code scopes}, issued through the normal
     * {@code auth.apiKeys().issue} path (any {@code ApiKeyStore}, the same {@code .maxKeysPerUser} limit:
     * {@code AuthRejection(409, "api_key_limit")} over it); returns the plaintext key to send as
     * {@code Authorization: <scheme> <key>}. {@link IllegalArgumentException} for an unknown owner,
     * {@code AuthStateException} without {@code .apiKeys(store)}.
     */
    // @req IR-AUTH-005
    // @req FR-APIKEY-010
    public static String apiKeyFor(Auth auth, String ownerUserId, String... scopes) {
        return TestingBridge.ops().apiKeyFor(auth, ownerUserId, scopes);
    }

    /**
     * The CSRF token of the session of {@code accessToken} (its {@code sub} and {@code fam}) for cookie-mode write
     * tests: send it as the {@code naru_csrf} cookie and the {@code X-CSRF-Token} header next to
     * {@code naru_at = accessToken}. {@link IllegalArgumentException} for a token {@code auth} does not accept.
     */
    // @req IR-AUTH-005
    // @req SEC-FLOW-006
    public static String csrfTokenFor(Auth auth, String accessToken) {
        return TestingBridge.ops().csrfTokenFor(auth, accessToken);
    }

    /**
     * Checks {@code store} against the {@link UserStore} contract: create, find, update and delete,
     * {@code DuplicateUserException} on a second username, that a deleted id is never handed to the next user, the
     * password-hash round trip read back through the same instance (a store that caches rows and loses the hash when
     * it saves them passes: use {@link #verifyUserStore(UserStore, Callable)} to read it back after a reopen),
     * {@code changePassword} with a null hash and with a version mismatch, the field-method results, and, when the
     * store declares {@code supportsAtomicFieldWrites()}, that {@code update()} never writes the security fields.
     * Throws {@link AssertionError} naming the broken clause. The store must start empty of the user names it uses
     * ({@code contract-*}).
     */
    // @req FR-IAM-004
    public static void verifyUserStore(UserStore store) {
        UserStoreContract.verify(store);
    }

    /**
     * {@link #verifyUserStore(UserStore)} plus the round trip after a restart: {@code reopen} opens a fresh instance
     * over the same storage (for a store that re-reads its backing storage on every call, {@code () -> store}), and the
     * password hash written and kept through {@code update()} must read back from it by id and by name. This catches a
     * store that caches rows in memory and loses the transient {@code passwordHash} when it saves them, which the
     * one-instance overload cannot see. A {@code reopen} that throws fails the contract.
     */
    // @req FR-IAM-004
    public static void verifyUserStore(UserStore store, Callable<? extends UserStore> reopen) {
        UserStoreContract.verify(store, java.util.Objects.requireNonNull(reopen, "reopen"));
    }

    /**
     * Checks {@code store} against the {@link ApiKeyStore} method table on one instance; throws {@link AssertionError}
     * naming the broken rule.
     */
    // @req FR-APIKEY-009
    public static void verifyApiKeyStore(ApiKeyStore store) {
        ApiKeyStoreContract.verify(store);
    }

    /**
     * {@link #verifyApiKeyStore(ApiKeyStore)} on {@code a}, then the concurrent limited insertion over two instances
     * {@code a} and {@code b} of one storage: 8 threads split over both, exactly one insertion below the limit wins.
     */
    // @req FR-APIKEY-009
    public static void verifyApiKeyStore(ApiKeyStore a, ApiKeyStore b) {
        ApiKeyStoreContract.verify(a);
        ApiKeyStoreContract.verifyConcurrent(a, b);
    }
}
