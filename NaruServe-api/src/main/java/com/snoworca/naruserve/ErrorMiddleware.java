package com.snoworca.naruserve;

/** Ordered error callback: run clears the error, error propagates/replaces it, and no continuation holds it. */
// @req FR-APP-024 AC-1 AC-2
@FunctionalInterface
public interface ErrorMiddleware {
    void handle(Throwable error,Request request,Response response,Next next) throws Exception;
}
