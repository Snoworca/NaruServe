package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.Logger;
import java.io.Flushable;

/**
 * Framework-side wrapper of the app logger: a RuntimeException from the delegate is dropped, so a failing logger
 * never changes a response. User code gets the raw logger.
 */
// @req FR-APP-014
final class SafeLogger implements Logger {

    private final Logger delegate;

    // @req FR-APP-014
    SafeLogger(Logger delegate) {
        this.delegate = delegate;
    }

    /**
     * The log-writer step of every path into TERMINATED: flush if {@link Flushable}, then close if
     * {@link AutoCloseable}, each failure dropped so the path still reaches TERMINATED.
     */
    // @req FR-APP-014
    // @req FR-APP-010
    // @req FR-APP-011
    static void flushAndClose(Logger logger) {
        if (logger instanceof Flushable) {
            try {
                ((Flushable) logger).flush();
            } catch (Exception ignored) {
                // a logger failure never stops shutdown
            }
        }
        if (logger instanceof AutoCloseable) {
            try {
                ((AutoCloseable) logger).close();
            } catch (Exception ignored) {
                // a logger failure never stops shutdown
            }
        }
    }

    @Override
    public void trace(String message) {
        try {
            delegate.trace(message);
        } catch (RuntimeException ignored) {
            // a logger failure is never a request failure
        }
    }

    @Override
    public void debug(String message) {
        try {
            delegate.debug(message);
        } catch (RuntimeException ignored) {
            // a logger failure is never a request failure
        }
    }

    @Override
    public void info(String message) {
        try {
            delegate.info(message);
        } catch (RuntimeException ignored) {
            // a logger failure is never a request failure
        }
    }

    @Override
    public void warn(String message) {
        try {
            delegate.warn(message);
        } catch (RuntimeException ignored) {
            // a logger failure is never a request failure
        }
    }

    @Override
    public void error(String message) {
        try {
            delegate.error(message);
        } catch (RuntimeException ignored) {
            // a logger failure is never a request failure
        }
    }

    @Override
    public void error(String message, Throwable error) {
        try {
            delegate.error(message, error);
        } catch (RuntimeException ignored) {
            // a logger failure is never a request failure
        }
    }
}
