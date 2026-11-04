package com.snoworca.naruserve;

import java.util.List;

/** Exposes {@link ApiJarClasses#PUBLIC_PACKAGES} to tests in other packages. */
public final class ApiJarClassesAccess {

    private ApiJarClassesAccess() {
    }

    /** The public API package list the API surface tests check. */
    public static List<String> publicPackages() {
        return ApiJarClasses.PUBLIC_PACKAGES;
    }

    /** Every public type of the public API packages in the main jar. */
    public static List<Class<?>> publicTypes() {
        return ApiJarClasses.publicTypes();
    }
}
