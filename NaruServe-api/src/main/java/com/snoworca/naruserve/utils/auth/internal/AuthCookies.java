package com.snoworca.naruserve.utils.auth.internal;

import com.snoworca.naruserve.Configurer;
import com.snoworca.naruserve.Cookie;
import com.snoworca.naruserve.CookieOptions;
import com.snoworca.naruserve.Request;
import com.snoworca.naruserve.Response;
import com.snoworca.naruserve.SameSite;
import org.jspecify.annotations.Nullable;

/**
 * The auth cookies and their attributes (SEC-FLOW-009): {@code naru_at} (access, HttpOnly, Path=/), {@code naru_rt}
 * (refresh, HttpOnly, Path = the mount path, read only by refresh and logout), {@code naru_csrf} (readable by
 * scripts, Path=/) and {@code naru_device}; all SameSite=Strict, Secure exactly when the request is secure. The
 * shared configurer (Domain, for example) runs after the defaults, and a cookie is cleared with the same Path and
 * Domain it was set with. Two or more {@code naru_rt} cookies count as none (fail closed). Values never reach a log.
 */
// @req SEC-FLOW-009
public final class AuthCookies {

    /** The access-token cookie. */
    public static final String ACCESS = "naru_at";
    /** The refresh-token cookie. */
    public static final String REFRESH = "naru_rt";
    /** The CSRF cookie. */
    public static final String CSRF = "naru_csrf";
    /** The trusted-device cookie. */
    public static final String DEVICE = "naru_device";

    private final String mountPath;
    private final @Nullable Configurer<CookieOptions> shared;

    /** The cookies of an auth mounted at {@code mountPath}, with the app's shared attributes or null. */
    // @req SEC-FLOW-009
    public AuthCookies(String mountPath, @Nullable Configurer<CookieOptions> shared) {
        this.mountPath = mountPath;
        this.shared = shared;
    }

    private Configurer<CookieOptions> attributes(final String path, final boolean httpOnly, final int maxAge,
            final boolean secure) {
        return o -> {
            o.path(path).httpOnly(httpOnly).sameSite(SameSite.STRICT).secure(secure);
            Configurer<CookieOptions> s = shared;
            if (s != null) {
                s.configure(o);
            }
            o.httpOnly(httpOnly).secure(secure);
            if (maxAge >= 0) {
                o.maxAge(maxAge);
            }
        };
    }

    /** Sets {@code naru_at} for {@code accessTtlSeconds}. */
    // @req SEC-FLOW-009
    public void setAccess(Request req, Response res, String token, int accessTtlSeconds) {
        res.cookie(ACCESS, token, attributes("/", true, accessTtlSeconds, req.isSecure()));
    }

    /** Sets {@code naru_rt}, scoped to the mount path, for {@code refreshTtlSeconds}. */
    // @req SEC-FLOW-009
    public void setRefresh(Request req, Response res, String token, int refreshTtlSeconds) {
        res.cookie(REFRESH, token, attributes(mountPath, true, refreshTtlSeconds, req.isSecure()));
    }

    /** Sets {@code naru_csrf}, readable by scripts, for {@code refreshTtlSeconds}. */
    // @req SEC-FLOW-009
    public void setCsrf(Request req, Response res, String token, int refreshTtlSeconds) {
        res.cookie(CSRF, token, attributes("/", false, refreshTtlSeconds, req.isSecure()));
    }

    /** The {@code naru_device} lifetime in seconds: the 90-day device-token lifetime. */
    public static final int DEVICE_MAX_AGE_SECONDS = (int) (DeviceTokens.LIFETIME_MILLIS / 1000L);

    /**
     * Sets {@code naru_device} (open question Q118, decided 2026-11-02): HttpOnly, SameSite=Strict, Path = the mount
     * path plus {@code /login} (only the login reads it), Secure exactly when the request is secure, Max-Age the
     * 90-day device-token lifetime.
     */
    // @req SEC-FLOW-013
    public void setDevice(Request req, Response res, String token) {
        final String path = mountPath.endsWith("/") ? mountPath + "login" : mountPath + "/login";
        final Configurer<CookieOptions> base = attributes(path, true, DEVICE_MAX_AGE_SECONDS, req.isSecure());
        // the shared configurer may set a Domain, but never loosen SameSite or move the Path of the device cookie
        res.cookie(DEVICE, token, o -> {
            base.configure(o);
            o.sameSite(SameSite.STRICT).path(path);
        });
    }

    /** Clears {@code naru_at}, {@code naru_rt} and {@code naru_csrf} with the Path and Domain they were set with. */
    // @req SEC-FLOW-009
    public void clearSession(Request req, Response res) {
        res.clearCookie(ACCESS, attributes("/", true, -1, req.isSecure()));
        res.clearCookie(REFRESH, attributes(mountPath, true, -1, req.isSecure()));
        res.clearCookie(CSRF, attributes("/", false, -1, req.isSecure()));
    }

    /** The one {@code naru_rt} value of {@code req}; null when there is none or more than one. */
    // @req SEC-FLOW-009
    public static @Nullable String refreshToken(Request req) {
        String value = null;
        int count = 0;
        Cookie[] cookies;
        try {
            cookies = req.getCookies();
        } catch (UnsupportedOperationException none) {
            return null;
        }
        for (Cookie c : cookies) {
            if (REFRESH.equals(c.getName())) {
                count++;
                value = c.getValue();
            }
        }
        return count == 1 ? value : null;
    }
}
