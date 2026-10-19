package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.Logger;

/**
 * Framework-side wrapper of the app logger: a RuntimeException from the delegate is dropped, so a failing logger
 * never changes a response. User code gets the raw logger.
 * shortcut: no flushAndClose yet; the FR-APP-014 SDS adds it with the shutdown close.
 */
// @req FR-APP-014
final class SafeLogger implements Logger {

    private final Logger delegate;

    // @req FR-APP-014
    SafeLogger(Logger delegate) {
        this.delegate = delegate;
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
