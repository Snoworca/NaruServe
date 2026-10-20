package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.SseOptions;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** The app's SSE settings: range checks on every setter, the defaults until set. */
// @req IR-SSE-001
public final class DefaultSseOptions implements SseOptions {

    private static final int MAX_QUEUE = 16777216;

    private long heartbeatMillis = 15000L;
    private int sendQueueSize = 262144;

    /** A field-by-field copy, so a configurer that throws leaves this one untouched. */
    // @req IR-SSE-001
    public DefaultSseOptions copy() {
        DefaultSseOptions c = new DefaultSseOptions();
        c.heartbeatMillis = heartbeatMillis;
        c.sendQueueSize = sendQueueSize;
        return c;
    }

    /** toMillis saturates, so an overflowing time lands above the maximum instead of wrapping. */
    // @req IR-SSE-001
    @Override
    public SseOptions heartbeatInterval(long time, TimeUnit unit) {
        Objects.requireNonNull(unit, "unit");
        long ms = unit.toMillis(time);
        // toMillis truncates: the nanos check keeps one hour plus a fraction from passing as one hour
        if (ms < 1000L || unit.toNanos(time) > TimeUnit.HOURS.toNanos(1)) {
            throw new IllegalArgumentException("heartbeatInterval must be in 1 s..1 h: " + time + " " + unit);
        }
        heartbeatMillis = ms;
        return this;
    }

    // @req IR-SSE-001
    @Override
    public SseOptions sendQueueSize(int bytes) {
        if (bytes < 1 || bytes > MAX_QUEUE) {
            throw new IllegalArgumentException("sendQueueSize must be in 1.." + MAX_QUEUE + ": " + bytes);
        }
        sendQueueSize = bytes;
        return this;
    }

    public long heartbeatMillis() {
        return heartbeatMillis;
    }

    public int sendQueueSize() {
        return sendQueueSize;
    }
}
