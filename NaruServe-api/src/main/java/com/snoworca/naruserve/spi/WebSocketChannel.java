package com.snoworca.naruserve.spi;

import java.io.IOException;

/**
 * Adapter-owned WebSocket connection the core writes to. Each adapter implements it; RFC 6455 framing stays in the
 * adapter's engine. The core calls the send methods only from the session's single sender slot.
 */
// @req IR-WS-002
public interface WebSocketChannel {

    /** Sends one text message. */
    // @req IR-WS-002
    void sendText(String text) throws IOException;

    /** Sends one binary message. */
    // @req IR-WS-002
    void sendBinary(byte[] data) throws IOException;

    /** Sends a ping with {@code payload}. */
    // @req IR-WS-002
    void ping(byte[] payload) throws IOException;

    /** Starts the closing handshake with {@code code} and {@code reason}. */
    // @req IR-WS-002
    void close(int code, String reason);

    /** Closes the socket at once, with no close frame. */
    // @req IR-WS-002
    void abort();

    /** True until a close was sent or received, or the connection was lost. */
    // @req IR-WS-002
    boolean isOpen();
}
