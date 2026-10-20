package com.snoworca.naruserve;

import java.util.concurrent.TimeUnit;

/**
 * Server settings, configured through {@code app.server(o -> o.bindAddress("0.0.0.0"))}. Each setter returns these
 * options; a value outside its range throws {@link IllegalArgumentException} naming the setting and the range. There is
 * no unlimited value; an unset setting keeps the default shown on its setter.
 */
// @req IR-ENGINE-001
public interface ServerOptions {

    /** The address to bind; default {@code 127.0.0.1}. A non-loopback address makes listen() log a warning. */
    ServerOptions bindAddress(String address);

    /** Host names accepted besides the bind address, localhost and IP literals; default none. */
    ServerOptions allowedHosts(String... names);

    /** Total request header bytes, {@code 1..1048576}; default 16384. */
    ServerOptions maxHeaderSize(int bytes);

    /** Request header count, {@code 1..10000}; default 100. */
    ServerOptions maxHeaderCount(int count);

    /** Request line bytes, {@code 1..65534}; default 8190. */
    ServerOptions maxRequestLineLength(int bytes);

    /** Time to receive the request headers, 1 ms..1 h; default 10 s. */
    ServerOptions headerTimeout(long timeout, TimeUnit unit);

    /** Socket read timeout while reading a request body, 1 ms..1 h; default 30 s. */
    ServerOptions bodyReadTimeout(long timeout, TimeUnit unit);

    /** Socket write timeout, 1 ms..1 h; default 30 s. */
    ServerOptions writeTimeout(long timeout, TimeUnit unit);

    /** Minimum request body rate, {@code 1..104857600} bytes per second; default 240. */
    ServerOptions minBodyRate(int bytesPerSecond);

    /**
     * HTTP worker budget, {@code 1..1024}; default 64. The engine thread pool is maxWorkers + maxSseStreams:
     * SSE streams use at most maxSseStreams of it, so at least maxWorkers threads stay for ordinary requests,
     * which may also use idle SSE slots (up to maxWorkers + maxSseStreams threads when no stream is open).
     */
    ServerOptions maxWorkers(int workers);

    /** Concurrent SSE streams, {@code 1..1024}; default 128. */
    ServerOptions maxSseStreams(int streams);

    /** Open connections, {@code 1..65536}; default 512; at least maxWorkers + maxSseStreams. */
    ServerOptions maxConnections(int connections);

    /** Upgraded (WebSocket) connections, {@code 1..65536}; default 128. */
    ServerOptions maxUpgradedConnections(int connections);

    /** Open connections from one remote address, {@code 1..65536}; default 256. */
    ServerOptions maxConnectionsPerAddress(int connections);
}
