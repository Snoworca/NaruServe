package com.snoworca.naruserve.spi;

/**
 * Core-owned WebSocket callbacks, handed to {@link HttpExchange#webSocket}. The adapter calls them on its read thread,
 * one at a time, starting with {@link #onOpen} and ending with {@link #onClose}. No adapter implements this type, and
 * the core's implementation never throws from it.
 */
// @req IR-WS-002
public interface WebSocketEndpoint {

    /** The handshake completed; {@code channel} is the connection to write to. */
    // @req IR-WS-002
    void onOpen(WebSocketChannel channel);

    /** A complete text message arrived. */
    // @req IR-WS-002
    void onText(String text);

    /** A complete binary message arrived. */
    // @req IR-WS-002
    void onBinary(byte[] data);

    /** The connection closed with {@code code} and {@code reason}; 1006 and an empty reason when it was lost. */
    // @req IR-WS-002
    void onClose(int code, String reason);
}
