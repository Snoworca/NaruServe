package com.snoworca.naruserve;

import java.util.concurrent.TimeUnit;

/**
 * WebSocket settings, configured through {@code app.webSocket(o -> o.maxMessageSize(64 * 1024))}. Each setter returns
 * these options; a value outside its range throws {@link IllegalArgumentException} naming the setting and the range.
 * There is no unlimited value. The dispatcher uses a copy taken at {@code listen}. There is no pong-timeout setting,
 * since the pong timeout is the ping interval, and no handshake-timeout setting, since the server-wide
 * {@link ServerOptions#headerTimeout} and {@link ServerOptions#bodyReadTimeout} bound the handshake.
 */
// @req IR-WS-001
// @req FR-WS-008
public interface WebSocketOptions {

    /**
     * Replaces the Origin allowlist, as the {@code origin} option of Express {@code cors}; with no argument it resets
     * to the default, which allows only the same origin.
     */
    WebSocketOptions allowedOrigins(String... origins);

    /** Bytes of one received message, {@code 1..16777216}; default 1048576 (1 MiB). */
    WebSocketOptions maxMessageSize(int bytes);

    /**
     * Bytes queued for sending on one connection, {@code 1..67108864}; default 1048576 (1 MiB). A send that would pass
     * it is dropped with the queue and closes the connection with {@code 1008}; the send itself returns normally.
     */
    WebSocketOptions sendQueueSize(int bytes);

    /** Ping interval, {@code 1 s..1 h}; default 30 s. A pong must arrive within the same interval. */
    WebSocketOptions pingInterval(long time, TimeUnit unit);

    /**
     * Time without a text or binary message received or sent after which the connection is closed with {@code 1001},
     * {@code 1 s..24 h}; default 300 s. Pings and pongs do not count.
     */
    WebSocketOptions idleTimeout(long time, TimeUnit unit);
}
