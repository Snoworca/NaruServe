package com.snoworca.naruserve;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Fluent options for {@link Response#cookie(String, String, Configurer)} and cookie deletion. Max-Age is seconds. */
// @req FR-DX-005
public final class CookieOptions {
    private final Cookie cookie;

    private CookieOptions(Cookie cookie) {
        this.cookie = cookie;
    }

    /** Cookie path; null omits Path. Convenience cookies default to /. */
    public CookieOptions path(@Nullable String path) {
        cookie.setPath(path);
        return this;
    }

    /** Cookie domain; null omits Domain. */
    public CookieOptions domain(@Nullable String domain) {
        cookie.setDomain(domain);
        return this;
    }

    /** Lifetime in seconds; negative means a session cookie and zero deletes it. */
    public CookieOptions maxAge(int seconds) {
        cookie.setMaxAge(seconds);
        return this;
    }

    public CookieOptions secure(boolean secure) {
        cookie.setSecure(secure);
        return this;
    }

    public CookieOptions httpOnly(boolean httpOnly) {
        cookie.setHttpOnly(httpOnly);
        return this;
    }

    public CookieOptions sameSite(@Nullable SameSite sameSite) {
        cookie.setSameSite(sameSite);
        return this;
    }

    /** Builds locally before addCookie mutates headers; both setting and deletion share the same validation path. */
    static Cookie configured(String name, String value, Configurer<CookieOptions> configurer, boolean clear) {
        Objects.requireNonNull(configurer, "configurer");
        Cookie cookie = new Cookie(name, value);
        cookie.setPath("/");
        configurer.configure(new CookieOptions(cookie));
        if (clear) {
            cookie.setMaxAge(0);
        }
        return cookie;
    }
}
