package com.snoworca.naruserve;

/** Callback of {@link RestApp#onStart} and {@link RestApp#onStop}. */
// @req IR-APP-005
@FunctionalInterface
public interface LifecycleCallback {

    void handle(ApplicationContext ctx) throws Exception;
}
