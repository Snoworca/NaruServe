package com.snoworca.naruserve;

/** Returns an explicit work lease; settle it after the last asynchronous request/resource access. */
// @req FR-APP-023 AC-1 AC-2 AC-7
@FunctionalInterface
public interface AsyncRouteHandler {
    Deferred handle(Request request,Response response,Next next) throws Exception;
}
