package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.ErrorHandler;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The one place a failed request goes: a step's Exception (from the Dispatcher) or {@code next.error(e)} (from
 * {@link ChainNext}).
 * shortcut: runs no onError handler and answers the wave 3 interim 500 (Content-Length: 0); the FR-APP-005 SDS adds
 * the handler chain, the standard error body and the ERROR log.
 */
// @req FR-APP-005
final class ErrorPath {

    private final List<ErrorHandler> handlers;

    /** {@code handlers} are the onError registrations in order. */
    // @req FR-APP-005
    ErrorPath(List<ErrorHandler> handlers) {
        this.handlers = Collections.unmodifiableList(new ArrayList<>(handlers));
    }

    /** Never throws an Exception. */
    // @req FR-APP-005
    void handle(Throwable error, DefaultRequest req, DefaultResponse res, String requestId) {
        res.afterFailure(error);
    }
}
