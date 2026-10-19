package com.snoworca.naruserve.spi;

/** Renders the standard error body for a status, with a fresh request id on every call. */
// @req IR-ENGINE-002
@FunctionalInterface
public interface ErrorRenderer {

    /** The Content-Type of the standard error body: {@code application/json; charset=UTF-8}. */
    String JSON_TYPE = "application/json; charset=UTF-8";

    /** The JSON text of the standard error body, sent as {@link #JSON_TYPE}. */
    String render(int status);
}
