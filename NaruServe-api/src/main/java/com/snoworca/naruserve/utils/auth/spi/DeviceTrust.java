package com.snoworca.naruserve.utils.auth.spi;

/**
 * The state behind trusted-device login (00 R34 (2)): which device tokens their per-token failure limit has
 * invalidated. Auth signs, verifies and fingerprints the device tokens itself with keys derived from the
 * {@link KeySource} (the key material never reaches this interface) and calls the trust store at three points of a
 * password login: {@link #issued} after a successful login issued a token, {@link #isInvalidated} when it confirms
 * trust in step 3 of the check order, and {@link #recordFailure} for each failed login carrying a trusted token.
 * Set it with {@code Auth.builder(..).deviceTrust(trust)}; by default it follows the user store (in memory, or a file
 * beside a {@code FileUserStore}); under {@code MULTI_INSTANCE} the app implements it over its shared database, since
 * auth ships no database store. Every method is synchronous and may throw; a
 * failure answers the login with 503. After the first public release this interface gains default methods only.
 * Express counterpart: the device-cookie table of the OWASP "device cookies" pattern.
 */
// @req SEC-FLOW-013
public interface DeviceTrust {

    /** Called after a successful password login issued {@code token}; the default records nothing. */
    default void issued(DeviceToken token) throws Exception {
    }

    /** True when {@code token} was invalidated by its failure limit and must no longer be trusted. */
    boolean isInvalidated(DeviceToken token) throws Exception;

    /**
     * Counts one failed login carrying {@code token} in a fixed window of {@code windowMillis} and invalidates the
     * token once {@code max} failures fall in one window (auth passes 5 per 15 minutes); true when the token is
     * invalidated after this call.
     */
    boolean recordFailure(DeviceToken token, int max, long windowMillis) throws Exception;

    /**
     * Forgets the state of tokens whose expiry is at or before {@code nowMillis}; auth calls it from its 10-minute
     * cleanup. Returns how many were removed; the default removes nothing.
     */
    default int purgeExpired(long nowMillis) throws Exception {
        return 0;
    }
}
