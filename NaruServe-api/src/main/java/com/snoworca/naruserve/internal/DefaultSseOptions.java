package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.SseOptions;
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
        heartbeatMillis = DefaultWebSocketOptions.millis("heartbeatInterval", time, unit, 1);
        return this;
    }

    // @req IR-SSE-001
    @Override
    public SseOptions sendQueueSize(int bytes) {
        sendQueueSize = DefaultServerOptions.range("sendQueueSize", bytes, MAX_QUEUE);
        return this;
    }

    public long heartbeatMillis() {
        return heartbeatMillis;
    }

    public int sendQueueSize() {
        return sendQueueSize;
    }
}
