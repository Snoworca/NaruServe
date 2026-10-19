package com.snoworca.naruserve;

/** Configures {@code target} in place, e.g. {@code app.accessLog(options -> options.exclude("/health"))}. */
// @req IR-APP-007
@FunctionalInterface
public interface Configurer<T> {

    void configure(T target);
}
