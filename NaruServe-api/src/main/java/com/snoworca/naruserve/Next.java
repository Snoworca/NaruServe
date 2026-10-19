package com.snoworca.naruserve;

/** Continuation of the middleware chain; there is no asynchronous resume. */
// @req IR-APP-002
public interface Next {

    /** Runs the rest of the chain on the calling thread and returns when it has finished. */
    void run();

    /** Skips the rest of the chain and hands {@code error} to the error handlers. */
    void error(Throwable error);
}
