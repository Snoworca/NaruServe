package com.snoworca.naruserve.testing;

import com.snoworca.naruserve.spi.WebSocketChannel;
import com.snoworca.naruserve.spi.WebSocketEndpoint;
import com.snoworca.naruserve.spi.WebSocketSettings;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
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
        /** Thrown by the read loop instead of a delivery: the connection broke after onOpen. */
        final @Nullable RuntimeException failure;

        Event(@Nullable String text, byte @Nullable [] data, int code, String reason) {
            this(text, data, code, reason, null);
        }

        Event(@Nullable String text, byte @Nullable [] data, int code, String reason,
                @Nullable RuntimeException failure) {
            this.text = text;
            this.data = data;
            this.code = code;
            this.reason = reason;
            this.failure = failure;
        }
    }

    private final WebSocketSettings settings;
    private final LinkedBlockingQueue<Event> events = new LinkedBlockingQueue<>();
    private final Map<String, CountDownLatch> holds = new ConcurrentHashMap<>();

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

    /**
     * Makes the read loop end by throwing {@code failure} out of {@code webSocket} without calling onClose, like an
     * adapter whose connection broke after onOpen.
     */
    // @req FR-WS-006
    public synchronized void readLoopFails(RuntimeException failure) {
        events.add(new Event(null, null, 0, "", failure));
    }

    /** Queues a close from the peer; the read loop ends after delivering it. */
    // @req IR-WS-002
    public synchronized void clientClose(int code, String reason) {
        events.add(new Event(null, null, code, reason));
    }

    /**
     * The next call of channel method {@code method} (sendText, sendBinary, ping, close or abort) waits, before it
     * does anything, until the returned latch is counted down.
     */
    // @req FR-WS-006
    public CountDownLatch hold(String method) {
        if (!Arrays.asList("sendText", "sendBinary", "ping", "close", "abort").contains(method)) {
            throw new IllegalArgumentException("not a held channel method: " + method);
        }
        CountDownLatch latch = new CountDownLatch(1);
        if (holds.putIfAbsent(method, latch) != null) {
            throw new IllegalStateException("a hold on " + method + " is still pending");
        }
        return latch;
    }

    /** Called outside the lock, so a held call blocks only its own thread. */
    private void awaitHold(String method) {
        CountDownLatch latch = holds.remove(method);
        if (latch != null) {
            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // @req IR-WS-002
    @Override
    public void sendText(String text) throws IOException {
        awaitHold("sendText");
        record(text);
    }

    // @req IR-WS-002
    @Override
    public void sendBinary(byte[] data) throws IOException {
        awaitHold("sendBinary");
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
    public void ping(byte[] payload) throws IOException {
        awaitHold("ping");
        countPing();
    }

    private synchronized void countPing() throws IOException {
        if (!open) {
            throw new IOException("WebSocket closed");
        }
        pings++;
    }

    /** Records the first close; while open the peer echoes it, which ends the read loop. */
    // @req IR-WS-002
    @Override
    public void close(int code, String reason) {
        awaitHold("close");
        recordClose(code, reason);
    }

    private synchronized void recordClose(int code, String reason) {
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
    public void abort() {
        awaitHold("abort");
        recordAbort();
    }

    private synchronized void recordAbort() {
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
            if (e.failure != null) {
                synchronized (this) {
                    open = false;
                }
                throw e.failure;
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
