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
    private final int sendQueueSize;
    private final long idleTimeoutMillis;
    private final WebSocketSender sender;
    private final WebSocketIdleScheduler idle;

    /**
     * For an app without server options (the Dispatcher's test constructors): the default limit of 128 sender threads
     * and port 0 in the thread names.
     */
    WebSocketUpgrade(DefaultWebSocketOptions options, @Nullable JsonCodec codec, SafeLogger log) {
        this(options, codec, log, new WebSocketSender(128, () -> 0, log), new WebSocketIdleScheduler(() -> 0, log));
    }

    /**
     * {@code options} must be the frozen copy; {@code sender} and {@code idle} are the app's, built at freeze. An
     * allowlist entry that is not {@code *} or {@code scheme://host[:port]} never matches, so it gets one warning here.
     */
    // @req FR-WS-001
    // @req FR-WS-003
    // @req FR-WS-007
    WebSocketUpgrade(DefaultWebSocketOptions options, @Nullable JsonCodec codec, SafeLogger log,
            WebSocketSender sender, WebSocketIdleScheduler idle) {
        this.codec = codec;
        this.log = Objects.requireNonNull(log, "log");
        this.sender = Objects.requireNonNull(sender, "sender");
        this.idle = Objects.requireNonNull(idle, "idle");
        this.origins = new OriginCheck(options.origins());
        this.settings = options.settings();
        this.sendQueueSize = options.sendQueueSize();
        this.idleTimeoutMillis = options.idleTimeoutMillis();
        for (String o : options.origins()) {
            if (!"*".equals(o) && OriginCheck.normalise(o) == null) {
                log.warn("allowedOrigins entry never matches, expected scheme://host[:port]: " + o);
            }
        }
    }

    /**
     * Decodes the query, checks Origin (HttpException 403 before any session exists), then hands the exchange to
     * the adapter. A failure before onOpen propagates to the error path; after onOpen the connection is upgraded,
     * so it is logged and the exchange still ends, but only once the session's write in flight returned, so the
     * adapter never frees the connection slot while a sender thread still writes to it. A null {@code handler}
     * upgrades without running user code.
     */
    // @req FR-WS-001
    // @req FR-WS-003
    // @req FR-WS-007
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
        sender.awaitIdle(session);
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

    WebSocketIdleScheduler idle() {
        return idle;
    }

    int sendQueueSize() {
        return sendQueueSize;
    }

    long idleTimeoutMillis() {
        return idleTimeoutMillis;
    }
}
