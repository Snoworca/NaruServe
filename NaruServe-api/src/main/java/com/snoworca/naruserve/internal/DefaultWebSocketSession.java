package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.Request;
import com.snoworca.naruserve.WebSocketHandler;
import com.snoworca.naruserve.WebSocketSession;
import com.snoworca.naruserve.spi.WebSocketChannel;
import com.snoworca.naruserve.spi.WebSocketEndpoint;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jspecify.annotations.Nullable;

/**
 * The core side of one WebSocket connection: the app's {@link WebSocketSession} and the adapter's
 * {@link WebSocketEndpoint}. The handler runs once from {@link #onOpen}, after 101; the endpoint callbacks catch and
 * log every Exception from user code. Ping and close(code, reason) go through the session's sender slot; sends, id,
 * attributes and JSON arrive with FR-WS-006.
 */
// @req FR-WS-001
// @req FR-WS-005
final class DefaultWebSocketSession implements WebSocketSession, WebSocketEndpoint {

    private final Request req;
    private final @Nullable WebSocketHandler handler;
    private final SafeLogger log;
    private final String rid;
    private final WebSocketSender sender;
    private final List<TextListener> textListeners = new CopyOnWriteArrayList<>();
    private final List<BinaryListener> binaryListeners = new CopyOnWriteArrayList<>();
    private final List<CloseListener> closeListeners = new CopyOnWriteArrayList<>();
    private final List<ErrorListener> errorListeners = new CopyOnWriteArrayList<>();
    private volatile @Nullable WebSocketChannel channel;
    private volatile boolean opened;
    // The first close(code, reason): onClose reports this pair whatever code the peer echoed. Guarded by closeLock,
    // not this: user code holds the session and may synchronize on it.
    private final Object closeLock = new Object();
    private boolean closeCalled;
    private int sentCode;
    private String sentReason = "";

    /** A null {@code handler} completes the upgrade and runs no user code (a hook route that is no app.ws route). */
    // @req FR-WS-001
    DefaultWebSocketSession(Request req, @Nullable WebSocketHandler handler, SafeLogger log, String rid,
            WebSocketSender sender) {
        this.req = req;
        this.handler = handler;
        this.log = log;
        this.rid = rid;
        this.sender = Objects.requireNonNull(sender, "sender");
    }

    /** True once {@link #onOpen} ran: the connection is upgraded, so a later failure is no HTTP answer. */
    // @req FR-WS-001
    boolean opened() {
        return opened;
    }

    // @req FR-WS-001
    @Override
    public void onOpen(WebSocketChannel channel) {
        this.channel = channel;
        opened = true;
        WebSocketHandler h = handler;
        if (h == null) {
            return;
        }
        try {
            h.handle(req, this);
        } catch (Exception e) {
            fail("handler", e);
        }
    }

    // @req FR-WS-001
    @Override
    public void onText(String text) {
        for (TextListener l : textListeners) {
            try {
                l.onText(text);
            } catch (Exception e) {
                fail("listener", e);
                return;
            }
        }
    }

    // @req FR-WS-001
    @Override
    public void onBinary(byte[] data) {
        for (BinaryListener l : binaryListeners) {
            try {
                l.onBinary(data);
            } catch (Exception e) {
                fail("listener", e);
                return;
            }
        }
    }

    /** Reports the pair this side sent with close(code, reason) when there is one, else the adapter's. */
    // @req FR-WS-001
    // @req FR-WS-005
    @Override
    public void onClose(int code, String reason) {
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
    }

    /** Logs with the request id, tells the onError listeners and closes with 1011. */
    // @req FR-WS-001
    private void fail(String what, Exception e) {
        log.error("websocket " + rid + " " + what + " failed", e);
        for (ErrorListener l : errorListeners) {
            try {
                l.onError(e);
            } catch (Exception again) {
                log.error("websocket " + rid + " error listener failed", again);
            }
        }
        WebSocketChannel c = channel;
        if (c != null) {
            try {
                c.close(1011, "");
            } catch (RuntimeException again) {
                log.error("websocket " + rid + " close failed", again);
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

    /** The channel is open and close was not called. */
    // @req FR-WS-005
    @Override
    public boolean isOpen() {
        WebSocketChannel c = channel;
        if (c == null || !c.isOpen()) {
            return false;
        }
        synchronized (closeLock) {
            return !closeCalled;
        }
    }

    // shortcut: the members below throw until FR-WS-006 adds the sends and the session state.
    private static UnsupportedOperationException later(String what) {
        return new UnsupportedOperationException(what + " is not available yet");
    }

    @Override
    public String id() {
        throw later("id");
    }

    @Override
    public void sendText(String text) {
        throw later("sendText");
    }

    @Override
    public void sendBinary(byte[] data) {
        throw later("sendBinary");
    }

    @Override
    public void sendJson(@Nullable Object value) {
        throw later("sendJson");
    }

    /** An empty Ping through the sender slot, so it never interleaves with a message being written. */
    // @req FR-WS-005
    @Override
    public void ping() {
        WebSocketChannel c = channel;
        if (c == null || !isOpen()) {
            return;
        }
        sender.submit(this, () -> {
            try {
                c.ping(new byte[0]);
            } catch (IOException | RuntimeException e) {
                // The connection is going away; its onClose follows.
                log.debug("websocket " + rid + " ping not sent: " + e);
            }
        });
    }

    /**
     * isOpen() turns false at once and later pings are dropped; the close frame goes through the sender slot, behind
     * any write already queued. Code and reason checks arrive with FR-WS-006.
     */
    // @req FR-WS-005
    @Override
    public void close(int code, String reason) {
        Objects.requireNonNull(reason, "reason");
        WebSocketChannel c = channel;
        if (c == null || !c.isOpen()) {
            return;
        }
        synchronized (closeLock) {
            if (closeCalled) {
                return;
            }
            closeCalled = true;
            sentCode = code;
            sentReason = reason;
        }
        sender.submit(this, () -> {
            try {
                c.close(code, reason);
            } catch (RuntimeException e) {
                log.error("websocket " + rid + " close failed", e);
            }
        });
    }

    @Override
    public <T> void onJson(Class<T> type, JsonListener<T> listener) {
        throw later("onJson");
    }

    @Override
    public void attribute(String key, @Nullable Object value) {
        throw later("attribute");
    }

    @Override
    public <T> @Nullable T attribute(String key, Class<T> type) {
        throw later("attribute");
    }
}
