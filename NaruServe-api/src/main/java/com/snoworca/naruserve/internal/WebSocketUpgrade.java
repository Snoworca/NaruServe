package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.HttpException;
import com.snoworca.naruserve.WebSocketHandler;
import com.snoworca.naruserve.spi.HttpExchange;
import com.snoworca.naruserve.spi.JsonCodec;
import com.snoworca.naruserve.spi.WebSocketSettings;
import java.io.IOException;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The core side of one upgrade, shared by the app and built at freeze. The Dispatcher calls {@link #run} only after
 * the whole middleware chain returned with the response still NEW.
 */
// @req FR-WS-001
// @req FR-WS-003
final class WebSocketUpgrade {

    private final @Nullable JsonCodec codec; // read by the session's JSON methods once FR-WS-006 lands
    private final SafeLogger log;
    private final OriginCheck origins;
    private final WebSocketSettings settings;

    /**
     * {@code options} must be the frozen copy; the sender slot joins with FR-WS-005. An allowlist entry that is not
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
        DefaultWebSocketSession session = new DefaultWebSocketSession(req, handler, log, rid);
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
}
