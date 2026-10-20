package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.Cookie;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Reaches the package-private non-validating {@code Cookie.unvalidated} factory, so request cookies with odd values
 * never fail; an immutable Method handle (no java.util.function type, absent from API 21) keeps static state final.
 * The NaruServe-api consumer rules keep the factory by name.
 */
// @req IR-COOKIE-001
// @req FR-COOKIE-002
final class CookieFactoryHook {

    private static final Method UNVALIDATED;

    static {
        try {
            // Class.forName also initialises Cookie (IR-COOKIE-001 AC-6)
            UNVALIDATED = PackageMethods.lookup(Class.forName("com.snoworca.naruserve.Cookie"), "unvalidated",
                    String.class, String.class);
        } catch (ClassNotFoundException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private CookieFactoryHook() {
    }

    /** A cookie with only {@code name} and {@code value} set, built without validation. */
    // @req FR-COOKIE-002
    static Cookie create(String name, String value) {
        try {
            return (Cookie) UNVALIDATED.invoke(null, name, value);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("Cookie.unvalidated is unreachable", e);
        }
    }
}
