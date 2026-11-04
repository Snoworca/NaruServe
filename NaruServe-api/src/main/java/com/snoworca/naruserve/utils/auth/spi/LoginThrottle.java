package com.snoworca.naruserve.utils.auth.spi;

/**
 * Counts failed attempts per key (a login name, a {@code (name, IP)} pair, an IP, a device token) and says how long a
 * key stays blocked. Every method is synchronous and may throw; auth answers a throttle failure on a protected path
 * with 503. Express counterpart: the store of {@code express-rate-limit} / {@code rate-limiter-flexible}.
 *
 * <p>The one-argument methods apply the implementation's own limits. Auth passes its configured limits through the
 * limit-argument methods ({@code retryAfter(key, max, windowMillis)}, {@code recordFailure(key, max, windowMillis)});
 * an implementation that does not override them applies its own limits, ignores the arguments and keeps
 * {@link #usesLimitArguments()} false, which the doctor reports when API keys are on. Decorators extend
 * {@link ForwardingLoginThrottle}, which forwards every method, defaults included, so the declaration and the methods
 * can never disagree. After the first public release this interface gains default methods only.
 */
// @req IR-FLOW-003
public interface LoginThrottle {

    /** 0 when {@code key} may try now, else the remaining block time in milliseconds (own limits). */
    long retryAfter(String key) throws Exception;

    /** Counts a failure of {@code key}; returns the new block time in milliseconds, 0 when not yet blocked. */
    long recordFailure(String key) throws Exception;

    /** Forgets the failures of {@code key} (after a successful login). */
    void reset(String key) throws Exception;

    /** {@link #retryAfter(String)} under at most {@code max} failures per {@code windowMillis}; by default it ignores them. */
    default long retryAfter(String key, int max, long windowMillis) throws Exception {
        return retryAfter(key);
    }

    /** {@link #recordFailure(String)} under at most {@code max} failures per {@code windowMillis}; by default it ignores them. */
    default long recordFailure(String key, int max, long windowMillis) throws Exception {
        return recordFailure(key);
    }

    /** True when the limit-argument methods honour their arguments; false by default, true for the built-in throttles. */
    default boolean usesLimitArguments() {
        return false;
    }
}
