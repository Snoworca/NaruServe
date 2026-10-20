package com.snoworca.naruserve.tinyhttpd;

import com.snoworca.naruserve.spi.StreamDrain;
import com.snoworca.naruserve.spi.UpgradeHandler;
import com.snoworca.naruserve.spi.WebSocketEndpoint;
import com.snoworca.naruserve.spi.WebSocketSettings;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.List;
import java.util.Map;

/**
 * File transfer, event stream, WebSocket and raw upgrade over TinyHttpd, kept apart from the exchange bridge. Each
 * hook receives the exchange and keeps its abort check and single-start rule; the event-stream and WebSocket hooks
 * hold the exchange lock only for those checks, so their loops never block other calls on the exchange.
 */
// @req FR-ENGINE-011
final class TinyHttpdFeatures {

    private TinyHttpdFeatures() {
    }

    /**
     * Commits through {@code ex} and copies exactly {@code length} bytes of {@code file} from {@code offset} with
     * plain buffered reads; the caller holds the exchange lock and has checked the range. The file is opened before
     * the commit, so a missing or unreadable file throws with the response still unstarted.
     */
    // @req FR-ENGINE-011
    static void sendFile(TinyHttpdExchange ex, int status, List<Map.Entry<String, String>> headers, File file,
            long offset, long length) throws IOException {
        OutputStream out;
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            raf.seek(offset);
            out = ex.commit(status, headers, length);
            byte[] buf = new byte[8192];
            for (long left = length; left > 0; ) {
                int n = raf.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) {
                    throw new EOFException("file ended " + left + " bytes before the requested range: " + file);
                }
                out.write(buf, 0, n);
                left -= n;
            }
        }
        out.flush();
    }

    /**
     * Commits the headers with no length, so TinyHttpd frames the body chunked, sends them at once and hands the
     * exchange-synchronized body stream to {@code drain.attach}; {@link TinyHttpdExchange#run} runs the drain on this
     * worker after the handler returned. Never TinyHttpd's {@code startEventStream()}, which adds its own headers.
     */
    // @req FR-ENGINE-011
    static void eventStream(TinyHttpdExchange ex, int status, List<Map.Entry<String, String>> headers,
            StreamDrain drain) throws IOException {
        if (ex.handlerReturned()) {
            // Refused before the start; drainAfterHandler checks again for a handler returning meanwhile.
            throw new IllegalStateException(TinyHttpdExchange.LATE_EVENT_STREAM);
        }
        OutputStream out = ex.commit(status, headers, -1); // the lock is held only inside commit
        out.flush();
        drain.attach(out);
        ex.drainAfterHandler(drain, out);
    }

    /**
     * Checks the start rule and marks the exchange upgrading under its lock, then runs the handshake and the read
     * loop on this worker without it; the 101 marks the exchange started through {@link TinyHttpdExchange#markUpgraded}.
     */
    // @req FR-ENGINE-011
    // @req FR-WS-002
    static void webSocket(TinyHttpdExchange ex, WebSocketSettings settings, WebSocketEndpoint endpoint)
            throws IOException {
        synchronized (ex) {
            ex.requireStartable();
            ex.beginUpgrade();
        }
        try {
            new TinyHttpdWebSocket(ex.req, ex.res, settings, endpoint, ex::markUpgraded).run();
        } finally {
            ex.endUpgrade();
        }
    }

    /** Raw upgrades stay unsupported in v1; the caller checked the start rule, so the response is untouched. */
    // @req FR-ENGINE-011
    static void upgrade(TinyHttpdExchange ex, List<Map.Entry<String, String>> headers, UpgradeHandler handler) {
        throw new UnsupportedOperationException(
                "raw protocol upgrades are not supported by the TinyHttpd adapter; WebSocket goes through webSocket");
    }
}
