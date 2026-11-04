package com.snoworca.naruserve.utils.auth.internal;

import com.snoworca.naruserve.utils.auth.spi.LoginThrottle;

/**
 * The three password-login limits over a {@link LoginThrottle} (SEC-FLOW-011), all counted on the normalized login
 * name so an unknown name is counted exactly like an existing one: {@code (user, IP)} 5 failures per 15 minutes, IP
 * 20 per 15 minutes (requests with a valid trusted-device token are exempt), and user-only 10 per 15 minutes (only
 * for requests without a trusted device). Over a limit the login answers 429 {@code too_many_attempts} with
 * {@code Retry-After} at once, never by sleeping. API-key failures use their own counter and never touch these keys.
 */
// @req SEC-FLOW-011
public final class LoginLimits {

    /** The default {@code (user, IP)} limit. */
    public static final int USER_IP_MAX = 5;
    /** The default IP limit. */
    public static final int IP_MAX = 20;
    /** The default user-only limit. */
    public static final int USER_MAX = 10;
    /** The default window: 15 minutes. */
    public static final long WINDOW_MILLIS = 15 * 60_000L;

    private final LoginThrottle throttle;
    private final int userIpMax;
    private final int ipMax;
    private final int userMax;
    private final long window;

    /** The default limits over {@code throttle}. */
    // @req SEC-FLOW-011
    public LoginLimits(LoginThrottle throttle) {
        this(throttle, USER_IP_MAX, IP_MAX, USER_MAX, WINDOW_MILLIS);
    }

    /** Configured limits over {@code throttle}; each limit replaces its default. */
    // @req SEC-FLOW-011
    public LoginLimits(LoginThrottle throttle, int userIpMax, int ipMax, int userMax, long windowMillis) {
        if (userIpMax < 1 || ipMax < 1 || userMax < 1 || windowMillis < 1) {
            throw new IllegalArgumentException("login limits need at least 1 failure and 1 ms");
        }
        this.throttle = throttle;
        this.userIpMax = userIpMax;
        this.ipMax = ipMax;
        this.userMax = userMax;
        this.window = windowMillis;
    }

    /** Milliseconds until a login of {@code name} from {@code ip} may try, 0 when it may try now. */
    // @req SEC-FLOW-011
    public long retryAfter(String name, String ip, boolean trustedDevice) throws Exception {
        long wait = throttle.retryAfter(ThrottleKeys.loginUserIp(name, ip), userIpMax, window);
        if (!trustedDevice) {
            wait = Math.max(wait, throttle.retryAfter(ThrottleKeys.loginIp(ip), ipMax, window));
            wait = Math.max(wait, throttle.retryAfter(ThrottleKeys.loginUser(name), userMax, window));
        }
        return wait;
    }

    /** Milliseconds the IP key of {@code ip} blocks, 0 when it does not (step 1, or the deferred step 3 check). */
    // @req SEC-FLOW-012
    public long ipWait(String ip) throws Exception {
        return throttle.retryAfter(ThrottleKeys.loginIp(ip), ipMax, window);
    }

    /** Milliseconds the {@code (user, IP)} key blocks, 0 when it does not (step 2). */
    // @req SEC-FLOW-012
    public long userIpWait(String name, String ip) throws Exception {
        return throttle.retryAfter(ThrottleKeys.loginUserIp(name, ip), userIpMax, window);
    }

    /** Milliseconds the user-only key of {@code name} blocks, 0 when it does not (step 3 without a trusted device). */
    // @req SEC-FLOW-012
    public long userWait(String name) throws Exception {
        return throttle.retryAfter(ThrottleKeys.loginUser(name), userMax, window);
    }

    /**
     * Counts one failed login of {@code name} from {@code ip}, whether or not the name exists: under the
     * {@code (user, IP)} key always, and under the IP and user-only keys only without a trusted device (the keys that
     * apply to such requests).
     */
    // @req SEC-FLOW-011
    public void recordFailure(String name, String ip, boolean trustedDevice) throws Exception {
        Exception first = null;
        if (!trustedDevice) {
            first = record(ThrottleKeys.loginIp(ip), ipMax, null);
            first = record(ThrottleKeys.loginUser(name), userMax, first);
        }
        first = record(ThrottleKeys.loginUserIp(name, ip), userIpMax, first);
        if (first != null) {
            throw first; // every key was still counted that could be
        }
    }

    /** Counts one key; a failure is kept (the first one wins) so the remaining keys are still counted. */
    private @org.jspecify.annotations.Nullable Exception record(String key, int max,
            @org.jspecify.annotations.Nullable Exception first) {
        try {
            throttle.recordFailure(key, max, window);
            return first;
        } catch (Exception e) {
            return first != null ? first : e;
        }
    }
}
