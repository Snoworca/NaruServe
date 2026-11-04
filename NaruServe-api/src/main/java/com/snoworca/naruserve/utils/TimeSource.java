package com.snoworca.naruserve.utils;

/**
 * The current time in epoch milliseconds, injectable so tests can fix the clock. Every public auth timestamp is a
 * {@code long} (or {@code Long}) read from the configured source; {@code java.time} is API 26+ and not used.
 * Kotlin: {@code val t = TimeSource { 1_000L }}. Express has no counterpart; tests there fake {@code Date.now}.
 */
// @req IR-AUTH-001
@FunctionalInterface
public interface TimeSource {

    /** Milliseconds since 1970-01-01T00:00:00Z. */
    long nowMillis();

    /** The system clock: {@link System#currentTimeMillis()}. */
    // @req IR-AUTH-001
    static TimeSource system() {
        return SystemTimeSource.INSTANCE;
    }
}
