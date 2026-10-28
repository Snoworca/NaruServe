package com.snoworca.naruserve.tinyhttpd;

import com.snoworca.naruserve.ConsoleLogger;
import com.snoworca.naruserve.Logger;
import com.snoworca.naruserve.spi.ExchangeHandler;
import com.snoworca.naruserve.spi.HttpAdapter;
import com.snoworca.naruserve.spi.ServerSettings;
import com.snoworca.tinyhttpd.ServerConfig;
import com.snoworca.tinyhttpd.TinyHttpd;
import com.snoworca.tinyhttpd.RequestTargetPolicy;
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
 * Binds {@code 127.0.0.1} unless configured otherwise and never configures TLS. TinyHttpd's errors after parsing
 * get the standard error JSON, its log goes to the app logger, and a non-daemon keeper thread holds the JVM from a
 * successful bind until the stop.
 */
// @req FR-ENGINE-011
// @req FR-ENGINE-002
// @req FR-ENGINE-003
// @req FR-ENGINE-004
// @req FR-ENGINE-005
// @req FR-ENGINE-006
public final class TinyHttpdAdapter implements HttpAdapter {

    /** How long {@link #release()} waits for TinyHttpd's threads to end. */
    private static final long RELEASE_MARGIN_MS = 1000;

    // All guarded by this; set together by a successful bind.
    private @Nullable TinyHttpd server;
    private @Nullable KeeperThread keeper;
    private @Nullable WorkerThreadFactory workers;
    private @Nullable Logger logger;
    private boolean stopped;
    private final java.util.Set<TinyHttpdExchange> exchanges = java.util.Collections.newSetFromMap(
            new java.util.concurrent.ConcurrentHashMap<TinyHttpdExchange, Boolean>());

    void track(TinyHttpdExchange exchange) {
        boolean closed;
        synchronized (this) { exchanges.add(exchange); closed = stopped; }
        if (closed) exchange.abort();
    }

    void retire(TinyHttpdExchange exchange) { exchanges.remove(exchange); }

    public TinyHttpdAdapter() {
    }

    /** Binds with the default settings and {@code host}/{@code port}; the core calls the settings overload. */
    // @req FR-ENGINE-011
    @Override
    public void bind(String host, int port, ExchangeHandler handler) throws IOException {
        // This overload answers TinyHttpd's own errors with a zero-byte body and no content type.
        bind(ServerSettings.builder().port(port).bindAddress(host).logger(new ConsoleLogger())
                .errorRenderer(status -> "").build(), handler);
    }

    /**
     * Builds TinyHttpd from {@code settings} and starts it, then the keeper thread; a failed start stops the
     * half-built server and starts no keeper.
     */
    // @req FR-ENGINE-002
    // @req FR-ENGINE-006
    // @req FR-SSE-003
    @Override
    public synchronized void bind(ServerSettings settings, ExchangeHandler handler) throws IOException {
        if (server != null) {
            throw new IllegalStateException("this adapter is already bound");
        }
        WorkerThreadFactory f = new WorkerThreadFactory(settings.logger());
        // The NaruServe SSE counter; TinyHttpd's own maxEventStreams counts only startEventStream, never called.
        TinyHttpdEventStream.Slots slots = new TinyHttpdEventStream.Slots(settings.maxSseStreams());
        TinyHttpd s = configure(settings, (req, res) -> TinyHttpdExchange.run(req, res, handler, slots, this))
                .threadFactory(f).build();
        f.server(s);
        KeeperThread k = new KeeperThread();
        try {
            s.start();
            k.start(s.getPort());
        } catch (IOException | RuntimeException | Error e) {
            s.stop(0);
            throw e;
        }
        server = s;
        keeper = k;
        workers = f;
        logger = settings.logger();
    }

    /**
     * The TinyHttpd builder for {@code s}: port, explicit bind address, every limit, the Host allowlist, the JSON
     * error handler and the log bridge.
     */
    // @req FR-ENGINE-002
    // @req FR-ENGINE-003
    // @req FR-ENGINE-004
    // @req FR-ENGINE-005
    static TinyHttpd.Builder configure(ServerSettings s, Handler h) throws UnknownHostException {
        int line = s.maxRequestLineLength();
        // maxRequestBodyBytes and maxDrainBytes stay at TinyHttpd's defaults: the core answers body limits.
        // @req FR-ENGINE-012 AC-1 AC-2: framework routing owns decoding; transport syntax/Host/framing stay checked.
        TinyHttpd.Builder b = TinyHttpd.builder()
                .requestTargetPolicy(RequestTargetPolicy.RAW)
                .port(s.port())
                .bindAddress(InetAddress.getByName(s.bindAddress()))
                .handler(h)
                .errorHandler(new JsonErrorHandler(s.errorRenderer()))
                .logger(new TinyLogBridge(s.logger()))
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
    // @req FR-ENGINE-011
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

    /**
     * Ends the keeper thread, then stops TinyHttpd once with {@code timeout} (the time left until the shutdown
     * deadline) as its grace; does nothing before bind or on a later call.
     */
    // @req FR-ENGINE-011
    // @req FR-ENGINE-006
    @Override
    public void stop(long timeout, TimeUnit unit) {
        TinyHttpd s;
        KeeperThread k;
        synchronized (this) {
            s = server;
            k = keeper;
            if (stopped || s == null || k == null) {
                return;
            }
            stopped = true;
        }
        k.end();
        try {
            s.stop(Math.max(0, unit.toMillis(timeout)));
        } finally {
            for (TinyHttpdExchange exchange : exchanges) exchange.abort();
        }
    }

    /**
     * Waits at most {@link #RELEASE_MARGIN_MS} for TinyHttpd and its worker threads to end and warns when workers
     * remain.
     */
    // @req FR-ENGINE-011
    // @req FR-ENGINE-006
    @Override
    public void release() {
        TinyHttpd s;
        WorkerThreadFactory f;
        Logger log;
        synchronized (this) {
            s = server;
            f = workers;
            log = logger;
        }
        if (s == null || f == null || log == null) {
            return;
        }
        long deadline = System.currentTimeMillis() + RELEASE_MARGIN_MS;
        try {
            boolean ended = s.awaitTermination(RELEASE_MARGIN_MS);
            int left = f.awaitWorkers(deadline);
            if (!ended || left > 0) {
                log.warn("TinyHttpd release: " + left + " worker threads still running"
                        + (ended ? "" : ", server not stopped"));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
