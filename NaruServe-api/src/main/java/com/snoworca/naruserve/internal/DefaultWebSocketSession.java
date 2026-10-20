package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.HttpException;
import com.snoworca.naruserve.Request;
import com.snoworca.naruserve.WebSocketHandler;
import com.snoworca.naruserve.WebSocketSession;
import com.snoworca.naruserve.spi.JsonCodec;
import com.snoworca.naruserve.spi.WebSocketChannel;
import com.snoworca.naruserve.spi.WebSocketEndpoint;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;

/**
 * The core side of one WebSocket connection: the app's {@link WebSocketSession} and the adapter's
 * {@link WebSocketEndpoint}. The handler runs once from {@link #onOpen}, after 101; the endpoint callbacks catch every
 * Exception from user code. Sends, ping and close go through the session's sender slot.
 */
// @req FR-WS-001
// @req FR-WS-005
// @req FR-WS-006
// @req FR-WS-007
// @req FR-WS-008
final class DefaultWebSocketSession implements WebSocketSession, WebSocketEndpoint {

    private final String id;
    private final String rid;
    private final Request req;
    private final @Nullable WebSocketHandler handler;
    private final @Nullable JsonCodec codec;
    private final SafeLogger log;
    private final WebSocketSender sender;
    private final WebSocketIdleScheduler idle;
    private final WebSocketRegistry registry;
    private final int sendQueueSize;
    private final long idleTimeoutMillis;
    /** System.nanoTime() of the last text or binary message received or sent. */
    private volatile long lastActivity = System.nanoTime();
    /** onText and onJson entries in one list, so they run in registration order. */
    private final List<TextListener> textListeners = new CopyOnWriteArrayList<>();
    private final List<BinaryListener> binaryListeners = new CopyOnWriteArrayList<>();
    private final List<CloseListener> closeListeners = new CopyOnWriteArrayList<>();
    private final List<ErrorListener> errorListeners = new CopyOnWriteArrayList<>();
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile @Nullable WebSocketChannel channel;
    private volatile boolean opened;
    // The first close the core started: onClose reports this pair whatever code the peer echoed. Guarded by
    // closeLock, not this: user code holds the session and may synchronize on it.
    private final Object closeLock = new Object();
    private boolean closeCalled;
    private int sentCode;
    private String sentReason = "";

    /** A null {@code handler} completes the upgrade and runs no user code (a hook route that is no app.ws route). */
    // @req FR-WS-001
    // @req FR-WS-006
    DefaultWebSocketSession(String id, String requestId, WebSocketUpgrade upgrade, @Nullable WebSocketHandler handler,
            DefaultRequest req) {
        this.id = Objects.requireNonNull(id, "id");
        this.rid = Objects.requireNonNull(requestId, "requestId");
        this.req = Objects.requireNonNull(req, "req");
        this.handler = handler;
        this.codec = upgrade.codec();
        this.log = upgrade.log();
        this.sender = upgrade.sender();
        this.idle = upgrade.idle();
        this.registry = upgrade.registry();
        this.sendQueueSize = upgrade.sendQueueSize();
        this.idleTimeoutMillis = upgrade.idleTimeoutMillis();
    }

    /** True once {@link #onOpen} ran: the connection is upgraded, so a later failure is no HTTP answer. */
    // @req FR-WS-001
    boolean opened() {
        return opened;
    }

    /** The number of attributes; read by tests to see the map released at close. */
    int attributeCount() {
        return attributes.size();
    }

    WebSocketSender sender() {
        return sender;
    }

    String requestId() {
        return rid;
    }

    /** Restarts the idle clock: a text or binary message was received, or its write returned. */
    // @req FR-WS-008
    void touch() {
        lastActivity = System.nanoTime();
    }

    long lastActivityNanos() {
        return lastActivity;
    }

    /**
     * The UTF-8 length of {@code s}, as {@code s.getBytes(UTF_8).length} without the copy: an unpaired surrogate
     * encodes as the one byte {@code '?'}.
     */
    // @req FR-WS-007
    static int utf8Length(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x80) {
                n += 1;
            } else if (c < 0x800) {
                n += 2;
            } else if (Character.isHighSurrogate(c) && i + 1 < s.length()
                    && Character.isLowSurrogate(s.charAt(i + 1))) {
                n += 4;
                i++;
            } else if (Character.isSurrogate(c)) {
                n += 1;
            } else {
                n += 3;
            }
        }
        return n;
    }

    /** Drops the connection at once, without a close frame; the adapter then delivers onClose(1006). */
    // @req FR-WS-008
    void abort() {
        WebSocketChannel c = channel;
        if (c != null) {
            c.abort();
        }
    }

    // @req FR-WS-001
    // @req FR-WS-006
    // @req FR-WS-008
    @Override
    public void onOpen(WebSocketChannel channel) {
        opening(channel);
    }

    /**
     * Stores the channel, then reads the registry state under the registry lock: OPEN starts the idle watch and runs
     * the handler; CLOSING (the close wait runs and counts this session) hands close 1001 to the sender slot as its
     * only task; ENDED (the wait is over) aborts at once with no close frame. The handler never runs in the last two.
     */
    // @req FR-WS-009
    // @req FR-WS-010
    void opening(WebSocketChannel channel) {
        this.channel = channel;
        WebSocketRegistry.State state;
        synchronized (registry) {
            opened = true;
            state = registry.state();
            try {
                if (state == WebSocketRegistry.State.CLOSING) {
                    closeBy(1001, "server shutdown"); // queued before the wait can see this session opened
                }
            } finally {
                registry.notifyAll(); // the close wait checks opened sessions
            }
        }
        if (state == WebSocketRegistry.State.ENDED) {
            abort();
        }
        if (state != WebSocketRegistry.State.OPEN) {
            return;
        }
        touch();
        idle.watch(this, idleTimeoutMillis);
        WebSocketHandler h = handler;
        if (h == null) {
            return;
        }
        try {
            h.handle(req, this);
        } catch (Exception e) {
            fail(e, "handler");
        }
    }

    // @req FR-WS-001
    // @req FR-WS-006
    @Override
    public void onText(String text) {
        touch();
        for (TextListener l : textListeners) {
            try {
                l.onText(text);
            } catch (Exception e) {
                fail(e, "listener");
                return;
            }
        }
    }

    // @req FR-WS-001
    // @req FR-WS-006
    @Override
    public void onBinary(byte[] data) {
        touch();
        for (BinaryListener l : binaryListeners) {
            try {
                l.onBinary(data);
            } catch (Exception e) {
                fail(e, "listener");
                return;
            }
        }
    }

    /**
     * Runs the CloseListeners once, with the first pair the core sent when there is one, else the adapter's; then
     * releases the attributes, the idle watch and the sender slot.
     */
    // @req FR-WS-001
    // @req FR-WS-005
    // @req FR-WS-006
    // @req FR-WS-007
    @Override
    public void onClose(int code, String reason) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        synchronized (closeLock) {
            if (closeCalled) {
                code = sentCode;
                reason = sentReason;
            }
        }
        for (CloseListener l : closeListeners) {
            try {
                l.onClose(code, reason);
            } catch (Exception e) {
                log.error("websocket " + rid + " close listener failed", e);
            }
        }
        attributes.clear();
        idle.unwatch(this);
        sender.release(this); // after closeLock was taken above, so no closeBy can queue behind the release
    }

    /** Tells the onError listeners, logs at ERROR with the request id and closes with 1011. */
    // @req FR-WS-001
    // @req FR-WS-006
    void fail(Exception error, String what) {
        tellErrorListeners(error);
        log.error("websocket " + rid + " " + what + " failed", error);
        closeBy(1011, "");
    }

    /** An onError listener that throws is logged and skipped, never passed back to fail. */
    private void tellErrorListeners(Exception error) {
        for (ErrorListener l : errorListeners) {
            try {
                l.onError(error);
            } catch (Exception again) {
                log.error("websocket " + rid + " error listener failed", again);
            }
        }
    }

    @Override
    public void onText(TextListener listener) {
        textListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    @Override
    public void onBinary(BinaryListener listener) {
        binaryListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    @Override
    public void onClose(CloseListener listener) {
        closeListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    @Override
    public void onError(ErrorListener listener) {
        errorListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /** The channel is open and no close was started. */
    // @req FR-WS-005
    @Override
    public boolean isOpen() {
        WebSocketChannel c = channel;
        if (closed.get() || c == null || !c.isOpen()) {
            return false;
        }
        synchronized (closeLock) {
            return !closeCalled;
        }
    }

    // @req FR-WS-006
    @Override
    public String id() {
        return id;
    }

    // @req FR-WS-006
    // @req FR-WS-007
    @Override
    public void sendText(String text) {
        Objects.requireNonNull(text, "text");
        submit(c -> c.sendText(text), utf8Length(text), true);
    }

    /** {@code data} is copied, since the write runs later on a sender thread. */
    // @req FR-WS-006
    // @req FR-WS-007
    @Override
    public void sendBinary(byte[] data) {
        Objects.requireNonNull(data, "data");
        if (!isOpen()) {
            return;
        }
        byte[] copy = data.clone();
        submit(c -> c.sendBinary(copy), copy.length, true);
    }

    /**
     * Encodes on the calling thread, so a codec failure throws here with nothing queued: HttpException(500), as
     * Response.json does, since UncheckedIOException needs API 24.
     */
    // @req FR-WS-006
    // @req FR-WS-007
    @Override
    public void sendJson(@Nullable Object value) {
        if (!isOpen()) {
            return;
        }
        StringWriter w = new StringWriter();
        try {
            codec().encode(value, w);
        } catch (IOException e) {
            throw new HttpException(500, "JSON encoding failed", e);
        }
        String text = w.toString();
        submit(c -> c.sendText(text), utf8Length(text), true);
    }

    private interface Write {
        void run(WebSocketChannel c) throws IOException;
    }

    /**
     * Queues {@code write}, counting {@code bytes} of payload, unless the session is closed; a message that would
     * pass sendQueueSize closes the session with 1008 instead and the call returns normally. The check and the queue
     * share closeLock with closeBy, so no write is queued behind the close frame. A {@code touches} write restarts
     * the idle clock when it returns.
     */
    // @req FR-WS-007
    private void submit(Write write, int bytes, boolean touches) {
        WebSocketChannel c = channel;
        synchronized (closeLock) {
            if (closed.get() || closeCalled || c == null || !c.isOpen()) {
                return;
            }
            Runnable task = () -> {
                try {
                    write.run(c);
                    if (touches) {
                        touch();
                    }
                } catch (IOException | RuntimeException e) {
                    // The connection is going away; its onClose follows.
                    log.debug("websocket " + rid + " write not sent: " + e);
                }
            };
            if (!sender.queue(this, task, bytes, sendQueueSize)) {
                closeBy(1008, "send queue overflow");
            }
        }
    }

    /** An empty Ping through the sender slot, so it never interleaves with a message being written. */
    // @req FR-WS-005
    @Override
    public void ping() {
        submit(c -> c.ping(new byte[0]), 0, false);
    }

    /** Checks the code and reason first, also on a closed session, then starts the close. */
    // @req FR-WS-005
    // @req FR-WS-006
    @Override
    public void close(int code, String reason) {
        checkClose(code, reason);
        closeBy(code, reason);
    }

    /** Refuses a code an endpoint may not send (RFC 6455 section 7.4) and a reason over 123 UTF-8 bytes. */
    // @req FR-WS-006
    static void checkClose(int code, String reason) {
        Objects.requireNonNull(reason, "reason");
        if (!(code >= 1000 && code <= 1003 || code >= 1007 && code <= 1014 || code >= 3000 && code <= 4999)) {
            throw new IllegalArgumentException("close code must be 1000-1003, 1007-1014 or 3000-4999: " + code);
        }
        int n = reason.getBytes(StandardCharsets.UTF_8).length;
        if (n > 123) {
            throw new IllegalArgumentException("close reason over 123 UTF-8 bytes: " + n);
        }
    }

    /**
     * Every close the core starts. Only the first pair is recorded; isOpen() turns false at once, so later sends and
     * pings are dropped. The close frame replaces what is still queued and is the slot's last write.
     */
    // @req FR-WS-005
    // @req FR-WS-006
    // @req FR-WS-007
    void closeBy(int code, String reason) {
        WebSocketChannel c = channel;
        if (c == null || !c.isOpen()) {
            return;
        }
        synchronized (closeLock) {
            if (closed.get() || closeCalled) {
                return;
            }
            closeCalled = true;
            sentCode = code;
            sentReason = reason;
            sender.last(this, () -> {
                try {
                    c.close(code, reason);
                } catch (RuntimeException e) {
                    log.error("websocket " + rid + " close failed", e);
                }
            });
        }
    }

    private JsonCodec codec() {
        JsonCodec c = codec;
        if (c == null) {
            throw new IllegalStateException("this session has no JsonCodec; build the app with RestApp.builder()");
        }
        return c;
    }

    /** A decoding entry in the text listener list; a listener exception propagates to onText, which fails. */
    // @req FR-WS-006
    @Override
    public <T> void onJson(Class<T> type, JsonListener<T> listener) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(listener, "listener");
        JsonCodec c = codec();
        textListeners.add(text -> {
            T value;
            try {
                value = c.decode(new StringReader(text), type);
            } catch (IOException | RuntimeException e) {
                decodeFailed(e);
                return;
            }
            listener.onJson(value);
        });
    }

    /** A malformed message is no protocol error: onError hears of it and the session stays open. */
    private void decodeFailed(Exception e) {
        if (errorListeners.isEmpty()) {
            log.warn("websocket " + rid + " json decode failed: " + e);
        } else {
            tellErrorListeners(e);
        }
    }

    /** A null value removes the key; after onClose released the map, writes are ignored. */
    // @req FR-WS-006
    @Override
    public void attribute(String key, @Nullable Object value) {
        Objects.requireNonNull(key, "key");
        if (closed.get()) {
            return;
        }
        if (value == null) {
            attributes.remove(key);
        } else {
            attributes.put(key, value);
        }
    }

    // @req FR-WS-006
    @Override
    @SuppressWarnings("unchecked") // a wrapper class stands for its primitive T, which is the boxed type at runtime
    public <T> @Nullable T attribute(String key, Class<T> type) {
        return (T) DefaultRequest.wrapper(type).cast(attributes.get(Objects.requireNonNull(key, "key")));
    }
}
