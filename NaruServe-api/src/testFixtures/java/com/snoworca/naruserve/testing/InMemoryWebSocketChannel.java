package com.snoworca.naruserve.testing;

import com.snoworca.naruserve.spi.WebSocketChannel;
import com.snoworca.naruserve.spi.WebSocketEndpoint;
import com.snoworca.naruserve.spi.WebSocketSettings;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Socketless {@link WebSocketChannel} opened by {@link InMemoryHttpAdapter}'s {@code webSocket}, plus the test-side
 * peer. It records every call the core makes; the exchange thread is the read thread that delivers peer events to the
 * endpoint in the order they were queued.
 */
// @req IR-WS-002
@NullMarked
public final class InMemoryWebSocketChannel implements WebSocketChannel {

    /** One peer event; {@code text} and {@code data} null mean a close with {@code code} and {@code reason}. */
    private static final class Event {
        final @Nullable String text;
        final byte @Nullable [] data;
        final int code;
        final String reason;

        Event(@Nullable String text, byte @Nullable [] data, int code, String reason) {
            this.text = text;
            this.data = data;
            this.code = code;
            this.reason = reason;
        }
    }

    private final WebSocketSettings settings;
    private final LinkedBlockingQueue<Event> events = new LinkedBlockingQueue<>();

    // Guarded by this.
    private final List<Object> sent = new ArrayList<>();
    private int pings;
    private int closeCode = -1;
    private String closeReason = "";
    private boolean aborted;
    private boolean open = true;

    InMemoryWebSocketChannel(WebSocketSettings settings) {
        this.settings = settings;
    }

    /** The settings the core passed to {@code webSocket}. */
    public WebSocketSettings settings() {
        return settings;
    }

    /** What the core sent, in call order: a {@link String} per text message and a {@code byte[]} per binary one. */
    // @req IR-WS-002
    public synchronized List<Object> sent() {
        List<Object> out = new ArrayList<>(sent.size());
        for (Object o : sent) {
            out.add(o instanceof byte[] ? ((byte[]) o).clone() : o);
        }
        return out;
    }

    /** The number of pings the core sent. */
    public synchronized int pings() {
        return pings;
    }

    /** The code of the first close called before any abort; -1 when there was none. */
    public synchronized int closeCode() {
        return closeCode;
    }

    /** The reason of the first close called before any abort; empty when there was none. */
    public synchronized String closeReason() {
        return closeReason;
    }

    /** True once abort was called, by the core or by the adapter aborting the exchange, or the loop was interrupted. */
    public synchronized boolean aborted() {
        return aborted;
    }

    /** Queues a text message from the peer. */
    // @req IR-WS-002
    public synchronized void clientText(String text) {
        events.add(new Event(text, null, 0, ""));
    }

    /** Queues a binary message from the peer. */
    // @req IR-WS-002
    public synchronized void clientBinary(byte[] data) {
        events.add(new Event(null, data.clone(), 0, ""));
    }

    /** Queues a close from the peer; the read loop ends after delivering it. */
    // @req IR-WS-002
    public synchronized void clientClose(int code, String reason) {
        events.add(new Event(null, null, code, reason));
    }

    // @req IR-WS-002
    @Override
    public void sendText(String text) throws IOException {
        record(text);
    }

    // @req IR-WS-002
    @Override
    public void sendBinary(byte[] data) throws IOException {
        record(data.clone());
    }

    private synchronized void record(Object message) throws IOException {
        if (!open) {
            throw new IOException("WebSocket closed");
        }
        sent.add(message);
    }

    // @req IR-WS-002
    @Override
    public synchronized void ping(byte[] payload) throws IOException {
        if (!open) {
            throw new IOException("WebSocket closed");
        }
        pings++;
    }

    /** Records the first close; while open the peer echoes it, which ends the read loop. */
    // @req IR-WS-002
    @Override
    public synchronized void close(int code, String reason) {
        if (closeCode != -1 || aborted) {
            return;
        }
        closeCode = code;
        closeReason = reason;
        if (open) {
            open = false;
            events.add(new Event(null, null, code, reason));
        }
    }

    /** Drops pending peer events and ends the read loop with onClose(1006, ""). */
    // @req IR-WS-002
    @Override
    public synchronized void abort() {
        if (aborted) {
            return;
        }
        aborted = true;
        open = false;
        // Under the same lock as the client methods, so no peer event slips in after the 1006.
        events.clear();
        events.add(new Event(null, null, 1006, ""));
    }

    @Override
    public synchronized boolean isOpen() {
        return open;
    }

    /** The read loop: onOpen, then each queued peer event in order, returning after onClose. */
    // @req IR-WS-002
    void run(WebSocketEndpoint endpoint) {
        endpoint.onOpen(this);
        while (true) {
            Event e;
            try {
                e = events.take();
            } catch (InterruptedException ie) {
                // An interrupted read thread (adapter release) loses the connection.
                synchronized (this) {
                    aborted = true;
                    open = false;
                }
                Thread.currentThread().interrupt();
                endpoint.onClose(1006, "");
                return;
            }
            if (e.text != null) {
                endpoint.onText(e.text);
            } else if (e.data != null) {
                endpoint.onBinary(e.data);
            } else {
                synchronized (this) {
                    open = false;
                }
                endpoint.onClose(e.code, e.reason);
                return;
            }
        }
    }
}
