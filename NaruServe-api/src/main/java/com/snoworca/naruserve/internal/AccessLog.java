package com.snoworca.naruserve.internal;

/**
 * One access-log line per request.
 * shortcut: writes nothing yet; the FR-APP-015 SDS adds the options, the SafeLogger and the line format.
 */
// @req FR-APP-015
final class AccessLog {

    // @req FR-APP-015
    void write(String methodToken, String rawPath, int status, boolean aborted, long startNanos, long in, long out,
            String rid) {
        // no-op until the FR-APP-015 SDS
    }
}
