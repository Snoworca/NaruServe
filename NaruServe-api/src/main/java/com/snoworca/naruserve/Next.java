package com.snoworca.naruserve;

/** Continuation of an HTTP layer; captured continuations may resume after callback return. */
// @req IR-APP-002
public interface Next {

    /** Runs the rest of the chain on the calling thread and returns when it has finished. */
    void run();

    /** Skips the rest of the chain and hands {@code error} to the error handlers. */
    void error(Throwable error);

    /** Skip the current Route; ordinary use middleware continues normally. */
    // @req FR-APP-022 AC-2 AC-6
    default void route() { run(); }

    /** Exit the nearest mounted router (or top-level stack). */
    // @req FR-APP-022 AC-2 AC-6
    default void router() { run(); }

}
