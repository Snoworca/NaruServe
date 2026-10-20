package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.HttpException;
import com.snoworca.naruserve.WebSocketHandler;
import com.snoworca.naruserve.spi.HttpExchange;
import com.snoworca.naruserve.spi.JsonCodec;
import com.snoworca.naruserve.spi.WebSocketSettings;
import java.io.IOException;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;

/**
 * The core side of one upgrade, shared by the app and built at freeze. The Dispatcher calls {@link #run} only after
 * the whole middleware chain returned with the response still NEW.
 */
// @req FR-WS-001
// @req FR-WS-003
// @req FR-WS-006
final class WebSocketUpgrade {

    private final @Nullable JsonCodec codec;
    private final AtomicLong ids = new AtomicLong();
    private final SafeLogger log;
    private final OriginCheck origins;
    private final WebSocketSettings settings;
    // shortcut: built here until FR-WS-007 builds it at freeze with the thread limit and FR-WS-010 shuts it down
    private final WebSocketSender sender = new WebSocketSender();

    /**
     * {@code options} must be the frozen copy. An allowlist entry that is not
     * {@code *} or {@code scheme://host[:port]} never matches, so it gets one warning here.
     */
    // @req FR-WS-001
    // @req FR-WS-003
    WebSocketUpgrade(DefaultWebSocketOptions options, @Nullable JsonCodec codec, SafeLogger log) {
        this.codec = codec;
        this.log = Objects.requireNonNull(log, "log");
        this.origins = new OriginCheck(options.origins());
        this.settings = options.settings();
        for (String o : options.origins()) {
            if (!"*".equals(o) && OriginCheck.normalise(o) == null) {
                log.warn("allowedOrigins entry never matches, expected scheme://host[:port]: " + o);
            }
        }
    }

    /**
     * Decodes the query, checks Origin (HttpException 403 before any session exists), then hands the exchange to
     * the adapter. A failure before onOpen propagates to the error path; after onOpen the connection is upgraded,
     * so it is logged and the exchange still ends. A null {@code handler} upgrades without running user code.
     */
    // @req FR-WS-001
    // @req FR-WS-003
    void run(HttpExchange ex, DefaultRequest req, DefaultResponse res, @Nullable WebSocketHandler handler,
            String rid) throws IOException {
        req.decodeQuery();
        if (!origins.admits(req.header("Origin"), req.header("Host"), req.isSecure())) {
            throw new HttpException(403);
        }
        DefaultWebSocketSession session = new DefaultWebSocketSession(nextId(), rid, this, handler, req);
        try {
            ex.webSocket(settings, session);
        } catch (IOException | RuntimeException e) {
            if (!session.opened()) {
                throw e;
            }
            log.error("websocket " + rid + " failed", e);
        }
        res.upgraded();
    }

    /** The next session id of this app: 16 lowercase hex digits of a counter starting at 1. */
    // @req FR-WS-006
    String nextId() {
        return String.format(Locale.ROOT, "%016x", ids.incrementAndGet());
    }

    @Nullable JsonCodec codec() {
        return codec;
    }

    SafeLogger log() {
        return log;
    }

    WebSocketSender sender() {
        return sender;
    }
}
