package com.snoworca.naruserve.internal;

/** Lifecycle of one response; status and headers reach the exchange at most once, on leaving NEW. */
// @req FR-HTTP-016
public enum ResponseState {
    NEW, COMMITTED, STREAMING, COMPLETED, ABORTED
}
