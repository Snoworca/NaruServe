package com.snoworca.naruserve.spi;

import com.snoworca.naruserve.Logger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The frozen server settings {@code listen()} hands to {@link HttpAdapter#bind(ServerSettings, ExchangeHandler)}:
 * the port, every {@code ServerOptions} value, the exception-safe app logger and the error renderer. Immutable.
 */
// @req IR-ENGINE-002
public final class ServerSettings {

    /** The default {@code maxUpgradedConnections}, shared by the core's own defaults. */
    public static final int DEFAULT_MAX_UPGRADED_CONNECTIONS = 128;

    private final int port;
    private final String bindAddress;
    private final List<String> allowedHosts;
    private final int maxHeaderSize;
    private final int maxHeaderCount;
    private final int maxRequestLineLength;
    private final long headerTimeoutMillis;
    private final long bodyReadTimeoutMillis;
    private final long writeTimeoutMillis;
    private final int minBodyRate;
    private final int maxWorkers;
    private final int maxSseStreams;
    private final int maxConnections;
    private final int maxUpgradedConnections;
    private final int maxConnectionsPerAddress;
    private final Logger logger;
    private final ErrorRenderer errorRenderer;

    private ServerSettings(Builder b) {
        this.port = b.port;
        this.bindAddress = b.bindAddress;
        this.allowedHosts = Collections.unmodifiableList(new ArrayList<>(b.allowedHosts));
        this.maxHeaderSize = b.maxHeaderSize;
        this.maxHeaderCount = b.maxHeaderCount;
        this.maxRequestLineLength = b.maxRequestLineLength;
        this.headerTimeoutMillis = b.headerTimeoutMillis;
        this.bodyReadTimeoutMillis = b.bodyReadTimeoutMillis;
        this.writeTimeoutMillis = b.writeTimeoutMillis;
        this.minBodyRate = b.minBodyRate;
        this.maxWorkers = b.maxWorkers;
        this.maxSseStreams = b.maxSseStreams;
        this.maxConnections = b.maxConnections;
        this.maxUpgradedConnections = b.maxUpgradedConnections;
        this.maxConnectionsPerAddress = b.maxConnectionsPerAddress;
        this.logger = Objects.requireNonNull(b.logger, "logger");
        this.errorRenderer = Objects.requireNonNull(b.errorRenderer, "errorRenderer");
    }

    /** A builder holding the documented defaults, port 0 and no logger or renderer yet. */
    // @req IR-ENGINE-002
    public static Builder builder() {
        return new Builder();
    }

    public int port() {
        return port;
    }

    public String bindAddress() {
        return bindAddress;
    }

    /** Unmodifiable. */
    public List<String> allowedHosts() {
        return allowedHosts;
    }

    public int maxHeaderSize() {
        return maxHeaderSize;
    }

    public int maxHeaderCount() {
        return maxHeaderCount;
    }

    public int maxRequestLineLength() {
        return maxRequestLineLength;
    }

    public long headerTimeoutMillis() {
        return headerTimeoutMillis;
    }

    public long bodyReadTimeoutMillis() {
        return bodyReadTimeoutMillis;
    }

    public long writeTimeoutMillis() {
        return writeTimeoutMillis;
    }

    public int minBodyRate() {
        return minBodyRate;
    }

    public int maxWorkers() {
        return maxWorkers;
    }

    public int maxSseStreams() {
        return maxSseStreams;
    }

    public int maxConnections() {
        return maxConnections;
    }

    public int maxUpgradedConnections() {
        return maxUpgradedConnections;
    }

    public int maxConnectionsPerAddress() {
        return maxConnectionsPerAddress;
    }

    /** The exception-safe wrapper of the app logger: it never throws a RuntimeException. */
    public Logger logger() {
        return logger;
    }

    public ErrorRenderer errorRenderer() {
        return errorRenderer;
    }

    /** Unvalidated: the core range-checks in {@code ServerOptions}; {@link #build()} needs a logger and renderer. */
    // @req IR-ENGINE-002
    public static final class Builder {
        private int port;
        private String bindAddress = "127.0.0.1";
        private List<String> allowedHosts = Collections.emptyList();
        private int maxHeaderSize = 16384;
        private int maxHeaderCount = 100;
        private int maxRequestLineLength = 8190;
        private long headerTimeoutMillis = 10000;
        private long bodyReadTimeoutMillis = 30000;
        private long writeTimeoutMillis = 30000;
        private int minBodyRate = 240;
        private int maxWorkers = 64;
        private int maxSseStreams = 128;
        private int maxConnections = 512;
        private int maxUpgradedConnections = DEFAULT_MAX_UPGRADED_CONNECTIONS;
        private int maxConnectionsPerAddress = 256;
        private @Nullable Logger logger;
        private @Nullable ErrorRenderer errorRenderer;

        private Builder() {
        }

        public Builder port(int port) {
            this.port = port;
            return this;
        }

        public Builder bindAddress(String address) {
            this.bindAddress = Objects.requireNonNull(address, "address");
            return this;
        }

        public Builder allowedHosts(List<String> names) {
            this.allowedHosts = new ArrayList<>(names);
            return this;
        }

        public Builder allowedHosts(String... names) {
            return allowedHosts(Arrays.asList(names));
        }

        public Builder maxHeaderSize(int bytes) {
            this.maxHeaderSize = bytes;
            return this;
        }

        public Builder maxHeaderCount(int count) {
            this.maxHeaderCount = count;
            return this;
        }

        public Builder maxRequestLineLength(int bytes) {
            this.maxRequestLineLength = bytes;
            return this;
        }

        public Builder headerTimeoutMillis(long millis) {
            this.headerTimeoutMillis = millis;
            return this;
        }

        public Builder bodyReadTimeoutMillis(long millis) {
            this.bodyReadTimeoutMillis = millis;
            return this;
        }

        public Builder writeTimeoutMillis(long millis) {
            this.writeTimeoutMillis = millis;
            return this;
        }

        public Builder minBodyRate(int bytesPerSecond) {
            this.minBodyRate = bytesPerSecond;
            return this;
        }

        public Builder maxWorkers(int workers) {
            this.maxWorkers = workers;
            return this;
        }

        public Builder maxSseStreams(int streams) {
            this.maxSseStreams = streams;
            return this;
        }

        public Builder maxConnections(int connections) {
            this.maxConnections = connections;
            return this;
        }

        public Builder maxUpgradedConnections(int connections) {
            this.maxUpgradedConnections = connections;
            return this;
        }

        public Builder maxConnectionsPerAddress(int connections) {
            this.maxConnectionsPerAddress = connections;
            return this;
        }

        public Builder logger(Logger logger) {
            this.logger = Objects.requireNonNull(logger, "logger");
            return this;
        }

        public Builder errorRenderer(ErrorRenderer renderer) {
            this.errorRenderer = Objects.requireNonNull(renderer, "renderer");
            return this;
        }

        /** A new immutable value; {@link NullPointerException} without a logger or renderer. */
        public ServerSettings build() {
            return new ServerSettings(this);
        }
    }
}
