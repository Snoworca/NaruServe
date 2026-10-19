package com.snoworca.naruserve.slf4j;

import com.snoworca.naruserve.Logger;
import java.util.Objects;
import org.slf4j.LoggerFactory;

/** {@link Logger} forwarding each method to the same-named method of an {@code org.slf4j.Logger}. */
// @req FR-APP-018
public final class Slf4jLogger implements Logger {

    private final org.slf4j.Logger delegate;

    /** Delegates to {@code LoggerFactory.getLogger("NaruServe")}. */
    // @req FR-APP-018
    public Slf4jLogger() {
        this(LoggerFactory.getLogger("NaruServe"));
    }

    // @req FR-APP-018
    public Slf4jLogger(org.slf4j.Logger delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public void trace(String message) {
        delegate.trace(message);
    }

    @Override
    public void debug(String message) {
        delegate.debug(message);
    }

    @Override
    public void info(String message) {
        delegate.info(message);
    }

    @Override
    public void warn(String message) {
        delegate.warn(message);
    }

    @Override
    public void error(String message) {
        delegate.error(message);
    }

    @Override
    public void error(String message, Throwable error) {
        delegate.error(message, error);
    }
}
