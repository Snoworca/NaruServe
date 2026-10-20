package com.snoworca.naruserve.spi;

/**
 * Immutable WebSocket settings the core passes to {@link HttpExchange#webSocket}, built from the frozen
 * {@code WebSocketOptions}. The adapter reads them at upgrade time.
 */
// @req IR-WS-002
public final class WebSocketSettings {

    private final int maxMessageBytes;
    private final long pingIntervalMillis;

    /** Values are already range-checked by the core. */
    // @req IR-WS-002
    public WebSocketSettings(int maxMessageBytes, long pingIntervalMillis) {
        this.maxMessageBytes = maxMessageBytes;
        this.pingIntervalMillis = pingIntervalMillis;
    }

    /** The largest message accepted, in bytes. */
    public int maxMessageBytes() {
        return maxMessageBytes;
    }

    /** The ping interval in milliseconds. */
    public long pingIntervalMillis() {
        return pingIntervalMillis;
    }
}
