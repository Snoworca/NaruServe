package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.WebSocketOptions;
import com.snoworca.naruserve.spi.WebSocketSettings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** The app's WebSocket settings: range checks on every setter, the defaults until set. */
// @req IR-WS-001
public final class DefaultWebSocketOptions implements WebSocketOptions {

    private List<String> origins = Collections.emptyList();
    private int maxMessageSize = 1048576;
    private int sendQueueSize = 1048576;
    private long pingIntervalMillis = 30000L;
    private long idleTimeoutMillis = 300000L;

    // @req IR-WS-001
    private static int range(String name, int value, int max) {
        if (value < 1 || value > max) {
            throw new IllegalArgumentException(name + " must be in 1.." + max + ": " + value);
        }
        return value;
    }

    /** toMillis saturates, so an overflowing time lands above the maximum instead of wrapping. */
    // @req IR-WS-001
    private static long millis(String name, long time, TimeUnit unit, long maxHours) {
        Objects.requireNonNull(unit, "unit");
        long ms = unit.toMillis(time);
        // toMillis truncates: the nanos check keeps the maximum plus a fraction from passing as the maximum
        if (ms < 1000L || unit.toNanos(time) > TimeUnit.HOURS.toNanos(maxHours)) {
            throw new IllegalArgumentException(name + " must be in 1 s.." + maxHours + " h: " + time + " " + unit);
        }
        return ms;
    }

    /** A field-by-field copy, so a configurer that throws leaves this one untouched. */
    // @req IR-WS-001
    public DefaultWebSocketOptions copy() {
        DefaultWebSocketOptions c = new DefaultWebSocketOptions();
        c.origins = origins;
        c.maxMessageSize = maxMessageSize;
        c.sendQueueSize = sendQueueSize;
        c.pingIntervalMillis = pingIntervalMillis;
        c.idleTimeoutMillis = idleTimeoutMillis;
        return c;
    }

    // @req IR-WS-001
    @Override
    public WebSocketOptions allowedOrigins(String... origins) {
        Objects.requireNonNull(origins, "origins");
        List<String> checked = new ArrayList<>(origins.length);
        for (String o : origins) {
            checked.add(Objects.requireNonNull(o, "origin"));
        }
        this.origins = Collections.unmodifiableList(checked);
        return this;
    }

    // @req IR-WS-001
    @Override
    public WebSocketOptions maxMessageSize(int bytes) {
        maxMessageSize = range("maxMessageSize", bytes, 16777216);
        return this;
    }

    // @req IR-WS-001
    @Override
    public WebSocketOptions sendQueueSize(int bytes) {
        sendQueueSize = range("sendQueueSize", bytes, 67108864);
        return this;
    }

    // @req IR-WS-001
    @Override
    public WebSocketOptions pingInterval(long time, TimeUnit unit) {
        pingIntervalMillis = millis("pingInterval", time, unit, 1);
        return this;
    }

    // @req IR-WS-001
    @Override
    public WebSocketOptions idleTimeout(long time, TimeUnit unit) {
        idleTimeoutMillis = millis("idleTimeout", time, unit, 24);
        return this;
    }

    /** The Origin allowlist, unmodifiable; empty means same origin only. */
    public List<String> origins() {
        return origins;
    }

    public int maxMessageSize() {
        return maxMessageSize;
    }

    public int sendQueueSize() {
        return sendQueueSize;
    }

    public long pingIntervalMillis() {
        return pingIntervalMillis;
    }

    public long idleTimeoutMillis() {
        return idleTimeoutMillis;
    }

    /** The SPI value handed to {@code HttpExchange.webSocket}. */
    // @req IR-WS-001
    public WebSocketSettings settings() {
        return new WebSocketSettings(maxMessageSize, pingIntervalMillis);
    }
}
