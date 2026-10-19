package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.Logger;
import com.snoworca.naruserve.ServerOptions;
import com.snoworca.naruserve.spi.ErrorRenderer;
import com.snoworca.naruserve.spi.ServerSettings;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** The app's one {@link ServerOptions}: range checks on every setter, the defaults until set. Guarded by this. */
// @req IR-ENGINE-001
final class DefaultServerOptions implements ServerOptions {

    private static final long MAX_TIMEOUT_MILLIS = TimeUnit.HOURS.toMillis(1);

    private final ServerSettings.Builder values = ServerSettings.builder();
    private int maxWorkers = 64;
    private int maxSseStreams = 128;
    private int maxConnections = 512;

    // @req IR-ENGINE-001
    private static int range(String name, int value, int max) {
        if (value < 1 || value > max) {
            throw new IllegalArgumentException(name + " must be in 1.." + max + ": " + value);
        }
        return value;
    }

    /** toMillis saturates, so an overflowing timeout lands above the maximum instead of wrapping. */
    // @req IR-ENGINE-001
    private static long millis(String name, long timeout, TimeUnit unit) {
        Objects.requireNonNull(unit, "unit");
        long ms = unit.toMillis(timeout);
        // toMillis truncates: the nanos check keeps 1 h plus a fraction from passing as 1 h
        if (timeout < 1 || ms < 1 || unit.toNanos(timeout) > TimeUnit.HOURS.toNanos(1)) {
            throw new IllegalArgumentException(name + " must be in 1 ms..1 h: " + timeout + " " + unit);
        }
        return ms;
    }

    // @req IR-ENGINE-001
    private static String name(String what, String value) {
        Objects.requireNonNull(value, what);
        if (value.isEmpty()) {
            throw new IllegalArgumentException(what + " must not be empty");
        }
        return value;
    }

    @Override
    public synchronized ServerOptions bindAddress(String address) {
        values.bindAddress(name("bindAddress", address));
        return this;
    }

    @Override
    public synchronized ServerOptions allowedHosts(String... names) {
        Objects.requireNonNull(names, "names");
        List<String> checked = new ArrayList<>(names.length);
        for (String n : names) {
            checked.add(name("allowedHosts name", n));
        }
        values.allowedHosts(checked);
        return this;
    }

    @Override
    public synchronized ServerOptions maxHeaderSize(int bytes) {
        values.maxHeaderSize(range("maxHeaderSize", bytes, 1048576));
        return this;
    }

    @Override
    public synchronized ServerOptions maxHeaderCount(int count) {
        values.maxHeaderCount(range("maxHeaderCount", count, 10000));
        return this;
    }

    @Override
    public synchronized ServerOptions maxRequestLineLength(int bytes) {
        values.maxRequestLineLength(range("maxRequestLineLength", bytes, 65534));
        return this;
    }

    @Override
    public synchronized ServerOptions headerTimeout(long timeout, TimeUnit unit) {
        values.headerTimeoutMillis(millis("headerTimeout", timeout, unit));
        return this;
    }

    @Override
    public synchronized ServerOptions bodyReadTimeout(long timeout, TimeUnit unit) {
        values.bodyReadTimeoutMillis(millis("bodyReadTimeout", timeout, unit));
        return this;
    }

    @Override
    public synchronized ServerOptions writeTimeout(long timeout, TimeUnit unit) {
        values.writeTimeoutMillis(millis("writeTimeout", timeout, unit));
        return this;
    }

    @Override
    public synchronized ServerOptions minBodyRate(int bytesPerSecond) {
        values.minBodyRate(range("minBodyRate", bytesPerSecond, 104857600));
        return this;
    }

    @Override
    public synchronized ServerOptions maxWorkers(int workers) {
        maxWorkers = range("maxWorkers", workers, 1024);
        values.maxWorkers(maxWorkers);
        return this;
    }

    @Override
    public synchronized ServerOptions maxSseStreams(int streams) {
        maxSseStreams = range("maxSseStreams", streams, 1024);
        values.maxSseStreams(maxSseStreams);
        return this;
    }

    @Override
    public synchronized ServerOptions maxConnections(int connections) {
        maxConnections = range("maxConnections", connections, 65536);
        values.maxConnections(maxConnections);
        return this;
    }

    @Override
    public synchronized ServerOptions maxUpgradedConnections(int connections) {
        values.maxUpgradedConnections(range("maxUpgradedConnections", connections, 65536));
        return this;
    }

    @Override
    public synchronized ServerOptions maxConnectionsPerAddress(int connections) {
        values.maxConnectionsPerAddress(range("maxConnectionsPerAddress", connections, 65536));
        return this;
    }

    /**
     * The immutable snapshot for {@code port}, with {@code log} wrapped in a {@link SafeLogger}. Throws
     * {@link IllegalStateException} when maxConnections is below maxWorkers + maxSseStreams.
     */
    // @req IR-ENGINE-001
    // @req IR-ENGINE-002
    // @req FR-ENGINE-004
    synchronized ServerSettings freeze(int port, Logger log, ErrorRenderer r) {
        if (maxConnections < maxWorkers + maxSseStreams) {
            throw new IllegalStateException("maxConnections (" + maxConnections + ") must be at least maxWorkers ("
                    + maxWorkers + ") + maxSseStreams (" + maxSseStreams + ")");
        }
        return values.port(port).logger(new SafeLogger(log)).errorRenderer(r).build();
    }
}
