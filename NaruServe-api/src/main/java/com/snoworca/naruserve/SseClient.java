package com.snoworca.naruserve;

/**
 * One open Server-Sent Events stream, returned by {@link Response#sse()}. Every method may be called from any thread,
 * also after the handler returned. A send writes one whole event; it returns {@code false} exactly when the stream has
 * ended or this send ends it, and {@code true} when the event was written or queued.
 */
// @req IR-SSE-001
public interface SseClient {

    /**
     * Sends an event with an {@code event:} line naming {@code event} and the {@code data:} lines of {@code data}. An
     * event name with CR, LF or NUL throws {@link IllegalArgumentException} on the caller, before anything is queued.
     */
    boolean send(String event, String data);

    /** Sends an event with only {@code data:} lines, which {@code EventSource} dispatches as {@code message}. */
    boolean send(String data);

    /** Sends {@code event} with its {@code event}, {@code id}, {@code retry} and {@code data} fields in that order. */
    boolean send(SseEvent event);

    /** Ends the stream once the events already queued are written; idempotent. */
    void close();

    /** Whether the stream has not ended yet. */
    boolean isOpen();

    /** Adds a listener run once when the stream ends, like Express {@code req.on('close')}. */
    void onClose(CloseListener listener);

    /** Runs when an SSE stream ends: {@code client.onClose(() -> ...)}. */
    // @req IR-SSE-001
    @FunctionalInterface
    interface CloseListener {
        void onClose() throws Exception;
    }
}
