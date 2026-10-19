package com.snoworca.naruserve.spi;

/** Renders the standard error body for a status, with a fresh request id on every call. */
// @req IR-ENGINE-002
@FunctionalInterface
public interface ErrorRenderer {

    /** The JSON text of the standard error body, sent as {@code application/json; charset=UTF-8}. */
    String render(int status);
}
