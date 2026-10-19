package com.snoworca.naruserve;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One cookie for {@link Response#addCookie}, or one read by {@link Request#cookie}; Servlet-style accessors so
 * Kotlin uses property syntax ({@code cookie.maxAge = 60}). The constructor and setters reject a name or value
 * outside the RFC 6265 grammar with {@link IllegalArgumentException}. The value is never encoded or decoded.
 */
// @req IR-COOKIE-001
public final class Cookie {

    private final String name;
    private String value;
    private @Nullable String path;
    private @Nullable String domain;
    private int maxAge = -1;
    private boolean secure;
    private boolean httpOnly;
    private @Nullable SameSite sameSite;

    /** A cookie with no attributes; {@code name} must be an RFC 7230 token and {@code value} cookie-octets. */
    // @req IR-COOKIE-001
    public Cookie(String name, String value) {
        this(Objects.requireNonNull(name, "name"), Objects.requireNonNull(value, "value"), true);
    }

    private Cookie(String name, String value, boolean validate) {
        if (validate) {
            if (!isValidName(name)) {
                throw new IllegalArgumentException("cookie name is not an RFC 7230 token: " + name);
            }
            checkValue(value);
        }
        this.name = name;
        this.value = value;
    }

    /** A cookie built without validation; only the request-cookie parser reaches it, through CookieFactoryHook. */
    // @req IR-COOKIE-001
    static Cookie unvalidated(String name, String value) {
        return new Cookie(name, value, false);
    }

    /** RFC 7230 token: one or more tchar. */
    // @req IR-COOKIE-001
    static boolean isValidName(String name) {
        if (name.isEmpty()) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean alnum = c >= '0' && c <= '9' || c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z';
            if (!alnum && "!#$%&'*+-.^_`|~".indexOf(c) < 0) {
                return false;
            }
        }
        return true;
    }

    /** RFC 6265 cookie-value: cookie-octets, optionally inside one pair of double quotes; empty is allowed. */
    // @req IR-COOKIE-001
    static boolean isValidValue(String value) {
        int from = 0;
        int to = value.length();
        if (to >= 2 && value.charAt(0) == '"' && value.charAt(to - 1) == '"') {
            from = 1;
            to--;
        }
        for (int i = from; i < to; i++) {
            char c = value.charAt(i);
            if (c < 0x21 || c > 0x7E || c == '"' || c == ',' || c == ';' || c == '\\') {
                return false;
            }
        }
        return true;
    }

    private static void checkValue(String value) {
        if (!isValidValue(value)) {
            throw new IllegalArgumentException("cookie value holds a character outside RFC 6265 cookie-octet");
        }
    }

    /** No control character (C0 including CR, LF and HTAB, DEL, C1) and no ';'; null passes. */
    private static @Nullable String checkAttribute(String attribute, @Nullable String s) {
        if (s != null) {
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c < 0x20 || c >= 0x7F && c <= 0x9F || c == ';') {
                    throw new IllegalArgumentException("cookie " + attribute + " holds ';' or a control character");
                }
            }
        }
        return s;
    }

    public String getName() {
        return name;
    }

    public String getValue() {
        return value;
    }

    // @req IR-COOKIE-001
    public void setValue(String value) {
        checkValue(Objects.requireNonNull(value, "value"));
        this.value = value;
    }

    public @Nullable String getPath() {
        return path;
    }

    // @req IR-COOKIE-001
    public void setPath(@Nullable String path) {
        this.path = checkAttribute("path", path);
    }

    public @Nullable String getDomain() {
        return domain;
    }

    // @req IR-COOKIE-001
    public void setDomain(@Nullable String domain) {
        this.domain = checkAttribute("domain", domain);
    }

    /** Seconds; negative (the default -1) writes no Max-Age, so the cookie lasts for the session; 0 deletes it. */
    public int getMaxAge() {
        return maxAge;
    }

    public void setMaxAge(int seconds) {
        this.maxAge = seconds;
    }

    public boolean getSecure() {
        return secure;
    }

    public void setSecure(boolean secure) {
        this.secure = secure;
    }

    public boolean isHttpOnly() {
        return httpOnly;
    }

    public void setHttpOnly(boolean httpOnly) {
        this.httpOnly = httpOnly;
    }

    public @Nullable SameSite getSameSite() {
        return sameSite;
    }

    public void setSameSite(@Nullable SameSite sameSite) {
        this.sameSite = sameSite;
    }
}
