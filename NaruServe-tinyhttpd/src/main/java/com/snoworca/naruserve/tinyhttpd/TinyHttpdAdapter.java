package com.snoworca.naruserve.tinyhttpd;

import com.snoworca.naruserve.ConsoleLogger;
import com.snoworca.naruserve.spi.ExchangeHandler;
import com.snoworca.naruserve.spi.HttpAdapter;
import com.snoworca.naruserve.spi.ServerSettings;
import com.snoworca.tinyhttpd.ServerConfig;
import com.snoworca.tinyhttpd.TinyHttpd;
import com.snoworca.tinyhttpd.http.Handler;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * The TinyHttpd {@link HttpAdapter}. Its core maps every TinyHttpd builder limit from the frozen
 * {@link ServerSettings}; exchanges and the file-transfer, event-stream and upgrade paths live in separate classes.
 * Binds {@code 127.0.0.1} unless configured otherwise and never configures TLS.
 */
// @req FR-ENGINE-001
// @req FR-ENGINE-002
// @req FR-ENGINE-003
// @req FR-ENGINE-004
public final class TinyHttpdAdapter implements HttpAdapter {

    /** How long {@link #release()} waits for TinyHttpd's threads to end. */
    private static final long RELEASE_MARGIN_MS = 2000;

    // Guarded by this.
    private @Nullable TinyHttpd server;

    public TinyHttpdAdapter() {
    }

    /** Binds with the default settings and {@code host}/{@code port}; the core calls the settings overload. */
    // @req FR-ENGINE-001
    @Override
    public void bind(String host, int port, ExchangeHandler handler) throws IOException {
        // No error body is rendered by this SDS's adapter, so the renderer is never called here.
        bind(ServerSettings.builder().port(port).bindAddress(host).logger(new ConsoleLogger())
                .errorRenderer(status -> "").build(), handler);
    }

    /** Builds TinyHttpd from {@code settings} and starts it; a failed start stops the half-built server. */
    // @req FR-ENGINE-002
    @Override
    public synchronized void bind(ServerSettings settings, ExchangeHandler handler) throws IOException {
        if (server != null) {
            throw new IllegalStateException("this adapter is already bound");
        }
        TinyHttpd s = configure(settings, (req, res) -> TinyHttpdExchange.run(req, res, handler)).build();
        try {
            s.start();
        } catch (IOException | RuntimeException e) {
            s.stop(0);
            throw e;
        }
        server = s;
    }

    /** The TinyHttpd builder for {@code s}: port, explicit bind address, every limit and the Host allowlist. */
    // @req FR-ENGINE-002
    // @req FR-ENGINE-003
    // @req FR-ENGINE-004
    static TinyHttpd.Builder configure(ServerSettings s, Handler h) throws UnknownHostException {
        int line = s.maxRequestLineLength();
        // maxRequestBodyBytes and maxDrainBytes stay at TinyHttpd's defaults: the core answers body limits.
        TinyHttpd.Builder b = TinyHttpd.builder()
                .port(s.port())
                .bindAddress(InetAddress.getByName(s.bindAddress()))
                .handler(h)
                .maxHeaderBytes(s.maxHeaderSize())
                .maxHeaderCount(s.maxHeaderCount())
                .maxRequestLineBytes(line)
                .inputBufferBytes((int) Math.min(Integer.MAX_VALUE, Math.max(8192L, line + 2L)))
                .headerTimeout(millis(s.headerTimeoutMillis()))
                .bodyReadTimeout(millis(s.bodyReadTimeoutMillis()))
                .writeTimeout(millis(s.writeTimeoutMillis()))
                .minRequestBodyRate(s.minBodyRate())
                // HTTP worker budget plus SSE streams; wait queue = maxConnections - (maxWorkers + maxSseStreams).
                .maxWorkers(s.maxWorkers() + s.maxSseStreams())
                .maxEventStreams(s.maxSseStreams())
                .maxConnections(s.maxConnections())
                .maxUpgradedConnections(s.maxUpgradedConnections())
                .maxConnectionsPerAddress(s.maxConnectionsPerAddress());
        List<String> names = s.allowedHosts();
        b.allowedHosts(names.toArray(new String[0]));
        if (!isIpLiteral(s.bindAddress())) {
            b.allowedHosts(s.bindAddress());
        }
        return b;
    }

    private static int millis(long ms) {
        return (int) Math.min(Integer.MAX_VALUE, ms);
    }

    private static boolean isIpLiteral(String address) {
        return address.indexOf(':') >= 0 || address.matches("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");
    }

    /**
     * The actual bound port.
     *
     * @throws IllegalStateException before bind
     */
    // @req FR-ENGINE-001
    @Override
    public synchronized int boundPort() {
        return bound().getPort();
    }

    /** The built TinyHttpd configuration, for tests. */
    // @req FR-ENGINE-003
    // @req FR-ENGINE-004
    synchronized ServerConfig config() {
        return bound().getConfig();
    }

    private TinyHttpd bound() {
        TinyHttpd s = server;
        if (s == null) {
            throw new IllegalStateException("the adapter is not bound");
        }
        return s;
    }

    /** Stops TinyHttpd with {@code timeout} as its grace; does nothing before bind. */
    // @req FR-ENGINE-001
    @Override
    public void stop(long timeout, TimeUnit unit) {
        TinyHttpd s;
        synchronized (this) {
            s = server;
        }
        if (s != null) {
            s.stop(unit.toMillis(timeout));
        }
    }

    /** Waits at most {@link #RELEASE_MARGIN_MS} for TinyHttpd to end. */
    // @req FR-ENGINE-001
    @Override
    public void release() {
        TinyHttpd s;
        synchronized (this) {
            s = server;
        }
        if (s != null) {
            try {
                s.awaitTermination(RELEASE_MARGIN_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
