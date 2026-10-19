package com.snoworca.naruserve;

/** Thrown by {@link ServiceRegistry#get(Class)} when no instance is registered under the type. */
// @req IR-APP-004
public class ServiceNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    // @req IR-APP-004
    public ServiceNotFoundException(Class<?> type) {
        super("No service registered for " + type.getName());
    }
}
