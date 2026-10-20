package com.snoworca.naruserve;

import org.jspecify.annotations.Nullable;

/** One open WebSocket connection as the app sees it. */
// @req IR-WS-001
public interface WebSocketSession {

    /** The session id. */
    String id();

    void sendText(String text);

    void sendBinary(byte[] data);

    /** Sends {@code value} encoded by the app's JSON codec as one text message. */
    void sendJson(@Nullable Object value);

    void ping();

    /** Same as {@code close(1000, "")}. */
    // @req IR-WS-001
    default void close() {
        close(1000, "");
    }

    void close(int code, String reason);

    boolean isOpen();

    /** Adds a text message listener; listeners of one kind run in registration order. */
    void onText(TextListener listener);

    void onBinary(BinaryListener listener);

    /** Adds a listener that gets each text message decoded to {@code type} by the app's JSON codec. */
    <T> void onJson(Class<T> type, JsonListener<T> listener);

    void onClose(CloseListener listener);

    void onError(ErrorListener listener);

    /** Stores {@code value} under {@code key}; a {@code null} value removes the key. */
    void attribute(String key, @Nullable Object value);

    /** The value stored under {@code key} cast to {@code type}, or {@code null} when nothing is stored. */
    <T> @Nullable T attribute(String key, Class<T> type);

    /** A text message arrived. */
    @FunctionalInterface
    interface TextListener {
        void onText(String text) throws Exception;
    }

    /** A binary message arrived. */
    @FunctionalInterface
    interface BinaryListener {
        void onBinary(byte[] data) throws Exception;
    }

    /** A text message arrived and was decoded. */
    @FunctionalInterface
    interface JsonListener<T> {
        void onJson(@Nullable T value) throws Exception;
    }

    /** The connection closed with {@code code} and {@code reason}. */
    @FunctionalInterface
    interface CloseListener {
        void onClose(int code, String reason) throws Exception;
    }

    /** A listener or the connection failed. */
    @FunctionalInterface
    interface ErrorListener {
        void onError(Throwable error) throws Exception;
    }
}
