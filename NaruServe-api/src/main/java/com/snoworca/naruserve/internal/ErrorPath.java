package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.ErrorHandler;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The one place a failed request goes: a step's Exception (from the Dispatcher) or {@code next.error(e)} (from
 * {@link ChainNext}). The onError handlers run in registration order, each next one only while the response is still
 * NEW; then {@link DefaultResponse#afterFailure} decides the outcome by the state the chain left, adding
 * {@code Connection: close} to every 408 it writes.
 */
// @req FR-APP-005
// @req FR-ENGINE-005
// @req FR-HTTP-024
final class ErrorPath {

    private final List<ErrorHandler> handlers;
    final SafeLogger logger;

    /** {@code handlers} are the onError registrations in order; {@code logger} wraps the app logger. */
    // @req FR-APP-005
    ErrorPath(List<ErrorHandler> handlers, SafeLogger logger) {
        this.handlers = Collections.unmodifiableList(new ArrayList<>(handlers));
        this.logger = logger;
    }

    /**
     * Never throws an Exception; a java.lang.Error (from an onError handler or the logger) aborts the response and
     * propagates. A stream an onError handler started on a response that arrived NEW is its answer and is copied.
     */
    // @req FR-APP-005
    // @req FR-HTTP-024
    void handle(Throwable error, DefaultRequest req, DefaultResponse res, String requestId) {
        try {
            ResponseState arrival = res.state();
            int status = StandardErrors.statusOf(error);
            if (status == 500) {
                logger.error("request " + requestId + " failed", error);
            }
            Throwable cause = error;
            for (int i = 0; i < handlers.size(); i++) {
                if (i > 0 && res.state() != ResponseState.NEW) {
                    break;
                }
                try {
                    handlers.get(i).handle(error, req, res);
                } catch (Exception thrown) {
                    logger.error("onError handler failed for request " + requestId, thrown);
                    status = 500;
                    cause = thrown;
                    break;
                }
            }
            if (cause == error && arrival == ResponseState.NEW && res.state() == ResponseState.STREAMING) {
                res.afterHandler();
                return;
            }
            byte[] body = StandardErrors.json(status, StandardErrors.messageOf(cause, status), requestId);
            res.afterFailure(status, body, cause);
        } catch (Error e) {
            res.abort(e);
            throw e;
        }
    }
}
