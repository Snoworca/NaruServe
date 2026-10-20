package com.snoworca.naruserve.servlet;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;

/** A {@link Proxy} of {@link HttpServletRequest} that records the name of every method called on it. */
final class RecordingRequest {

    private RecordingRequest() {
    }

    /** Wraps {@code target}, appending each called method name to {@code calls}. */
    static HttpServletRequest wrap(HttpServletRequest target, List<String> calls) {
        return (HttpServletRequest) Proxy.newProxyInstance(RecordingRequest.class.getClassLoader(),
                new Class<?>[] {HttpServletRequest.class}, (proxy, method, args) -> {
                    calls.add(method.getName());
                    try {
                        return method.invoke(target, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    /** A filter for {@link ContainerRule#filter} that hands the chain a recording request. */
    static Filter filter(List<String> calls) {
        return new Filter() {
            @Override
            public void init(FilterConfig config) {
            }

            @Override
            public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
                    throws java.io.IOException, ServletException {
                chain.doFilter(wrap((HttpServletRequest) req, calls), res);
            }

            @Override
            public void destroy() {
            }
        };
    }
}
