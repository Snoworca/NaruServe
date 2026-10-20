package com.snoworca.naruserve.servlet;

import com.snoworca.naruserve.RestApp;

/**
 * Builds the app of one web application. Name the implementing class in the context init-parameter
 * {@code naruserve.configurer}; it needs a public no-argument constructor. {@code builder} already holds a
 * {@link ServletAdapter}; add the codec and logger, register routes and return the app.
 */
// @req IR-SVL-001
@FunctionalInterface
public interface ServletConfigurer {

    // @req IR-SVL-001
    RestApp configure(RestApp.Builder builder) throws Exception;
}
