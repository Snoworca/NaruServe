package com.snoworca.naruserve.internal;

import java.lang.reflect.Method;

/** Looks up a package-private method of a public-API class for a static initializer; a failure ends the caller's class initialisation. Not API. */
final class PackageMethods {

    private PackageMethods() {
    }

    /** The declared method {@code name(params)} of {@code owner}, made accessible. */
    static Method lookup(Class<?> owner, String name, Class<?>... params) {
        try {
            Method m = owner.getDeclaredMethod(name, params);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
