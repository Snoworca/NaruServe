package com.snoworca.naruserve;

/** App lifecycle states, in lifecycle order. */
// @req IR-APP-005
public enum AppState {
    NEW, STARTING, RUNNING, STOPPING, FAILED, TERMINATED
}
