package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.Cookie;
import com.snoworca.naruserve.SameSite;
import java.util.Locale;

/** Builds one {@code Set-Cookie} value; never writes {@code Expires}. */
// @req FR-COOKIE-001
final class CookieSerializer {

    private CookieSerializer() {
    }

    /**
     * Re-validates name, value, path and domain (a request cookie was built unvalidated) through the public
     * constructor and setters, which run Cookie's own checks, then writes the attributes in the order Path, Domain,
     * Max-Age, Secure, HttpOnly, SameSite. Throws {@link IllegalArgumentException} naming the bad attribute.
     */
    // @req FR-COOKIE-001
    static String serialize(Cookie c) {
        // Each field read once, so what is validated is what is written.
        String name = c.getName();
        String value = c.getValue();
        String path = c.getPath();
        String domain = c.getDomain();
        Cookie check = new Cookie(name, value);
        check.setPath(path);
        check.setDomain(domain);
        StringBuilder sb = new StringBuilder(name).append('=').append(value);
        if (path != null) {
            sb.append("; Path=").append(path);
        }
        if (domain != null) {
            sb.append("; Domain=").append(domain);
        }
        if (c.getMaxAge() >= 0) {
            sb.append("; Max-Age=").append(c.getMaxAge());
        }
        if (c.getSecure()) {
            sb.append("; Secure");
        }
        if (c.isHttpOnly()) {
            sb.append("; HttpOnly");
        }
        SameSite s = c.getSameSite();
        if (s != null) {
            String n = s.name();
            sb.append("; SameSite=").append(n.charAt(0)).append(n.substring(1).toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }
}
