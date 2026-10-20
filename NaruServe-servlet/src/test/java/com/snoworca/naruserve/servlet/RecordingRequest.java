package com.snoworca.naruserve.servlet;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.function.Consumer;
import javax.servlet.http.HttpServletRequest;

/** A {@link Proxy} of {@link HttpServletRequest} that records the name of every method called on it. */
final class RecordingRequest {

    private RecordingRequest() {
    }

    /** A proxy of {@code type} that reports each called method name to {@code onCall} and forwards to {@code target}. */
    static <T> T delegating(Class<T> type, T target, Consumer<String> onCall) {
        return type.cast(Proxy.newProxyInstance(RecordingRequest.class.getClassLoader(), new Class<?>[] {type},
                (proxy, method, args) -> {
                    onCall.accept(method.getName());
                    try {
                        return method.invoke(target, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                }));
    }

    /** Wraps {@code target}, appending each called method name to {@code calls}. */
    static HttpServletRequest wrap(HttpServletRequest target, List<String> calls) {
        return delegating(HttpServletRequest.class, target, calls::add);
    }

    /** A filter body for {@link ContainerRule#filter} that hands the chain a recording request. */
    static ContainerRule.Body filter(List<String> calls) {
        return (req, res, chain) -> chain.doFilter(wrap((HttpServletRequest) req, calls), res);
    }
}
