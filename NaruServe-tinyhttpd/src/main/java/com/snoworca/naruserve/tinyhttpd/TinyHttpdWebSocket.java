package com.snoworca.naruserve.tinyhttpd;

import com.snoworca.naruserve.HttpException;
import com.snoworca.naruserve.spi.WebSocketChannel;
import com.snoworca.naruserve.spi.WebSocketEndpoint;
import com.snoworca.naruserve.spi.WebSocketSettings;
import com.snoworca.tinyhttpd.http.Request;
import com.snoworca.tinyhttpd.http.Response;
import com.snoworca.tinyhttpd.http.ResponseWrapper;
import com.snoworca.tinyhttpd.http.UpgradedConnection;
import com.snoworca.tinyhttpd.websocket.WebSocket;
import com.snoworca.tinyhttpd.websocket.WebSocketHandler;
import com.snoworca.tinyhttpd.websocket.WebSocketListener;
import java.io.IOException;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One WebSocket upgrade over TinyHttpd. RFC 6455 framing, UTF-8 and size checks all come from TinyHttpd's websocket
 * module; this class only configures its handler, maps its refusals before 101 to NaruServe and bridges its listener
 * to the core's endpoint.
 */
// @req FR-WS-002
// @req FR-WS-005
final class TinyHttpdWebSocket {

    private final Request req;
    private final Response res;
    private final WebSocketSettings settings;
    private final WebSocketEndpoint endpoint;
    private final Runnable onCommitted;

    /** {@code onCommitted} marks the exchange started; it runs only once 101 was written. */
    // @req FR-WS-002
    TinyHttpdWebSocket(Request req, Response res, WebSocketSettings settings, WebSocketEndpoint endpoint,
            Runnable onCommitted) {
        this.req = req;
        this.res = res;
        this.settings = settings;
        this.endpoint = endpoint;
        this.onCommitted = onCommitted;
    }

    /**
     * Runs the handshake and the read loop on the calling worker; returns when the connection ended. A TinyHttpd
     * refusal before 101 becomes a NaruServe HttpException with the same status and headers, and the handshake
     * headers TinyHttpd already set are dropped so the error answer does not carry them.
     */
    // @req FR-WS-002
    void run() throws IOException {
        Listener listener = new Listener();
        CapturingResponse capturing = new CapturingResponse(res, listener);
        WebSocketHandler h = configure(new WebSocketHandler(handshake -> listener), settings);
        try {
            h.handle(req, capturing);
        } catch (com.snoworca.tinyhttpd.http.HttpException e) {
            dropHandshakeHeaders();
            HttpException mapped = new HttpException(e.getStatus(), e.getMessage(), e);
            List<String> pairs = e.getHeaders();
            for (int i = 0; i + 1 < pairs.size(); i += 2) {
                mapped.withHeader(pairs.get(i), pairs.get(i + 1));
            }
            throw mapped;
        } catch (IOException | RuntimeException e) {
            dropHandshakeHeaders(); // any failure before 101 may still be answered by the error path
            throw e;
        }
    }

    private void dropHandshakeHeaders() {
        if (!res.isCommitted()) {
            // TinyHttpd's setHeader with a null value removes the header.
            res.setHeader("Sec-WebSocket-Accept", null);
            res.setHeader("Sec-WebSocket-Protocol", null);
        }
    }

    /**
     * Any origin, because the core checks Origin before the upgrade; the one message limit goes to both TinyHttpd
     * limits, so neither one large frame nor many fragments get past it.
     */
    // @req FR-WS-002
    // @req FR-WS-005
    static WebSocketHandler configure(WebSocketHandler h, WebSocketSettings s) {
        int limit = s.maxMessageBytes();
        h.allowAnyOrigin();
        h.maxFramePayloadBytes(limit);
        h.maxMessageBytes(limit);
        h.pingInterval((int) s.pingIntervalMillis()); // the core caps the interval at 1 h
        return h;
    }

    /** Keeps the upgraded connection for {@link Channel#abort()}; marks the exchange started once 101 is out. */
    // @req FR-WS-002
    private final class CapturingResponse extends ResponseWrapper {

        private final Listener listener;

        CapturingResponse(Response wrapped, Listener listener) {
            super(wrapped);
            this.listener = listener;
        }

        // @req FR-WS-002
        @Override
        public UpgradedConnection upgrade(String protocol) throws IOException,
                com.snoworca.tinyhttpd.http.HttpException {
            try {
                UpgradedConnection c = super.upgrade(protocol);
                listener.connection = c;
                return c;
            } finally {
                // 413 and 503 leave the response uncommitted, so the error path can still answer on the exchange.
                if (isCommitted()) {
                    onCommitted.run();
                }
            }
        }
    }

    /**
     * The listener TinyHttpd's factory always returns, so no user code runs during the handshake. The core's endpoint
     * never throws; if it does anyway, the connection is aborted instead of letting TinyHttpd see the exception.
     */
    // @req FR-WS-002
    private final class Listener implements WebSocketListener {

        // Written by upgrade and read by onOpen, both on the worker thread.
        @Nullable UpgradedConnection connection;
        private volatile @Nullable Channel channel;

        // @req FR-WS-002
        @Override
        public void onOpen(WebSocket ws) {
            UpgradedConnection c = connection;
            if (c == null) {
                throw new IllegalStateException("onOpen before the upgrade returned");
            }
            Channel ch = new Channel(ws, c);
            channel = ch;
            try {
                endpoint.onOpen(ch);
            } catch (RuntimeException e) {
                ch.abort();
            }
        }

        // @req FR-WS-002
        @Override
        public void onText(WebSocket ws, String text) {
            try {
                endpoint.onText(text);
            } catch (RuntimeException e) {
                abort();
            }
        }

        // @req FR-WS-002
        @Override
        public void onBinary(WebSocket ws, byte[] data) {
            try {
                endpoint.onBinary(data);
            } catch (RuntimeException e) {
                abort();
            }
        }

        // @req FR-WS-002
        @Override
        public void onClose(WebSocket ws, int code, String reason) {
            try {
                endpoint.onClose(code, reason);
            } catch (RuntimeException e) {
                abort();
            }
        }

        private void abort() {
            Channel ch = channel;
            if (ch != null) {
                ch.abort();
            }
        }
    }

    /** The core's view of the connection: TinyHttpd's WebSocket for sends, the captured connection for abort. */
    // @req FR-WS-002
    // @req FR-WS-005
    private static final class Channel implements WebSocketChannel {

        private final WebSocket ws;
        private final UpgradedConnection connection;

        Channel(WebSocket ws, UpgradedConnection connection) {
            this.ws = ws;
            this.connection = connection;
        }

        @Override
        public void sendText(String text) throws IOException {
            ws.sendText(text);
        }

        @Override
        public void sendBinary(byte[] data) throws IOException {
            ws.sendBinary(data);
        }

        // @req FR-WS-005
        @Override
        public void ping(byte[] payload) throws IOException {
            ws.ping(payload);
        }

        @Override
        public void close(int code, String reason) {
            ws.close(code, reason);
        }

        // @req FR-WS-002
        @Override
        public void abort() {
            connection.close();
        }

        @Override
        public boolean isOpen() {
            return ws.isOpen();
        }
    }
}
