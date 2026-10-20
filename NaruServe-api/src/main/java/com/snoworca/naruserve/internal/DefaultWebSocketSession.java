package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.Request;
import com.snoworca.naruserve.WebSocketHandler;
import com.snoworca.naruserve.WebSocketSession;
import com.snoworca.naruserve.spi.WebSocketChannel;
import com.snoworca.naruserve.spi.WebSocketEndpoint;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jspecify.annotations.Nullable;

/**
 * The core side of one WebSocket connection: the app's {@link WebSocketSession} and the adapter's
 * {@link WebSocketEndpoint}. The handler runs once from {@link #onOpen}, after 101; the endpoint callbacks catch and
 * log every Exception from user code. Sends, close, ping, id, attributes and JSON arrive with FR-WS-005 and FR-WS-006.
 */
// @req FR-WS-001
final class DefaultWebSocketSession implements WebSocketSession, WebSocketEndpoint {

    private final Request req;
    private final @Nullable WebSocketHandler handler;
    private final SafeLogger log;
    private final String rid;
    private final List<TextListener> textListeners = new CopyOnWriteArrayList<>();
    private final List<BinaryListener> binaryListeners = new CopyOnWriteArrayList<>();
    private final List<CloseListener> closeListeners = new CopyOnWriteArrayList<>();
    private final List<ErrorListener> errorListeners = new CopyOnWriteArrayList<>();
    private volatile @Nullable WebSocketChannel channel;
    private volatile boolean opened;

    /** A null {@code handler} completes the upgrade and runs no user code (a hook route that is no app.ws route). */
    // @req FR-WS-001
    DefaultWebSocketSession(Request req, @Nullable WebSocketHandler handler, SafeLogger log, String rid) {
        this.req = req;
        this.handler = handler;
        this.log = log;
        this.rid = rid;
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

    // @req FR-WS-001
    @Override
    public void onClose(int code, String reason) {
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

    @Override
    public boolean isOpen() {
        WebSocketChannel c = channel;
        return c != null && c.isOpen();
    }

    // shortcut: the members below throw until FR-WS-005 and FR-WS-006 add the sender slot and session state.
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

    @Override
    public void ping() {
        throw later("ping");
    }

    @Override
    public void close(int code, String reason) {
        throw later("close");
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
