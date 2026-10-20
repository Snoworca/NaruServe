package com.snoworca.naruserve.tinyhttpd;

import com.snoworca.naruserve.spi.ExchangeHandler;
import com.snoworca.naruserve.spi.HttpExchange;
import com.snoworca.naruserve.spi.StreamDrain;
import com.snoworca.naruserve.spi.UpgradeHandler;
import com.snoworca.naruserve.spi.WebSocketEndpoint;
import com.snoworca.naruserve.spi.WebSocketSettings;
import com.snoworca.tinyhttpd.http.Request;
import com.snoworca.tinyhttpd.http.Response;
import java.io.File;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import org.jspecify.annotations.Nullable;

/**
 * {@link HttpExchange} over one TinyHttpd request and response. The raw path comes from {@code getRequestURI()} and
 * the raw query from {@code getQueryString()}, never from the decoded, slash-merging {@code getPath()}.
 */
// @req FR-ENGINE-001
// @req FR-ENGINE-010
final class TinyHttpdExchange implements HttpExchange {

    private final Request req;
    private final Response res;
    private final CountDownLatch ended = new CountDownLatch(1);
    // Guarded by this, which also serialises every touch of res by the core so abort() can hand res back safely.
    private boolean started;
    // Guarded by this: the worker runs a WebSocket handshake on res without the lock, so no other start may touch it.
    private boolean upgrading;
    private volatile boolean aborted;

    private TinyHttpdExchange(Request req, Response res) {
        this.req = req;
        this.res = res;
    }

    /**
     * Runs {@code handler} on the TinyHttpd worker, then holds the worker until the core finishes or aborts the
     * exchange, which may happen on another thread; TinyHttpd completes the response once this returns.
     */
    // @req FR-ENGINE-001
    static void run(Request req, Response res, ExchangeHandler handler) throws IOException {
        TinyHttpdExchange ex = new TinyHttpdExchange(req, res);
        handler.handle(ex);
        try {
            ex.ended.await();
        } catch (InterruptedException e) {
            // TinyHttpd interrupts workers at its stop deadline.
            ex.abort();
            Thread.currentThread().interrupt();
        }
        if (ex.aborted) {
            // Waits for a core write still inside res (bounded by writeTimeout) before TinyHttpd takes res back.
            synchronized (ex) {
                res.setCloseConnection(true);
                if (!ex.started && !res.isCommitted()) {
                    // TinyHttpd has no abort hook: a declared byte that never comes makes it close the connection,
                    // so the client sees a truncated response instead of a complete error page.
                    ex.started = true;
                    res.setStatus(500);
                    res.setContentLengthLong(1);
                    return;
                }
            }
            throw new IOException("exchange aborted");
        }
    }

    @Override
    public String methodToken() {
        return req.getMethod();
    }

    @Override
    public String rawPath() {
        String uri = req.getRequestURI();
        // TinyHttpd already strips the query; the split guards the SPI contract if that ever changes.
        int q = uri.indexOf('?');
        return q < 0 ? uri : uri.substring(0, q);
    }

    @Override
    public @Nullable String rawQuery() {
        return req.getQueryString();
    }

    @Override
    public String httpVersion() {
        return req.getProtocol();
    }

    // shortcut: grouped by first occurrence of each name (TinyHttpd exposes no ordered list), so interleaved
    // duplicates of two names lose their relative order; upgrade if a caller needs exact arrival order.
    @Override
    public List<Map.Entry<String, String>> headers() {
        List<Map.Entry<String, String>> out = new ArrayList<>();
        for (String name : req.getHeaderNames()) {
            for (String value : req.getHeaders(name)) {
                out.add(new SimpleImmutableEntry<>(name, value));
            }
        }
        return Collections.unmodifiableList(out);
    }

    @Override
    public InputStream requestBody() {
        try {
            return new TinyHttpdBodyStream(req.getInputStream());
        } catch (IOException e) {
            throw new IllegalStateException("request body unavailable", e);
        }
    }

    @Override
    public String remoteAddress() {
        return req.getRemoteAddr();
    }

    @Override
    public boolean isSecure() {
        return req.isSecure();
    }

    @Override
    public synchronized OutputStream commit(int status, List<Map.Entry<String, String>> headers, long contentLength)
            throws IOException {
        start();
        res.setStatus(status);
        for (Map.Entry<String, String> h : headers) {
            res.addHeader(h.getKey(), h.getValue());
        }
        final TinyHttpdExchange lock = this;
        return new FilterOutputStream(res.getOutputStream()) {
            @Override
            public void write(int b) throws IOException {
                synchronized (lock) {
                    check();
                    out.write(b);
                }
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                synchronized (lock) {
                    check();
                    out.write(b, off, len);
                }
            }

            @Override
            public void flush() throws IOException {
                synchronized (lock) {
                    check();
                    out.flush();
                }
            }

            @Override
            public void close() {
                // TinyHttpd ends the body when the worker returns.
            }

            private void check() throws IOException {
                if (aborted) {
                    throw new IOException("exchange aborted");
                }
            }
        };
    }

    @Override
    public synchronized void finish() throws IOException {
        if (aborted) {
            throw new IOException("exchange aborted");
        }
        ended.countDown();
    }

    // shortcut: a body write already blocked in the socket is not woken; it fails at TinyHttpd's writeTimeout.
    // Lock-free on purpose so abort() never waits for a writer.
    @Override
    public void abort() {
        aborted = true;
        ended.countDown();
    }

    /** The abort check and single-start rule shared by every way to start the response; caller holds this. */
    private void start() throws IOException {
        requireStartable();
        started = true;
    }

    /** The checks of {@link #start()} without starting; caller holds this. */
    private void requireStartable() throws IOException {
        if (aborted) {
            throw new IOException("exchange aborted");
        }
        if (started) {
            throw new IllegalStateException("the response was already started");
        }
        if (upgrading) {
            throw new IllegalStateException("a WebSocket handshake is using the response");
        }
    }

    // A feature not yet available never touched res, so the response stays unstarted and the core can still commit.
    private void unsupported(UnsupportedOperationException e) {
        started = false;
        throw e;
    }

    // Writes that later waves make from another thread (an event-stream drain) must also synchronize on this.
    @Override
    public synchronized void sendFile(int status, List<Map.Entry<String, String>> headers, File file, long offset,
            long length) throws IOException {
        // Checked before commit so a bad range leaves the response unstarted.
        long size = file.length();
        if (offset < 0 || length < 0 || offset > size - length) {
            throw new IllegalArgumentException(
                    "range offset " + offset + " length " + length + " outside file of " + size + " bytes");
        }
        requireStartable(); // the abort check and single-start rule win over a failed open, which comes next
        TinyHttpdFeatures.sendFile(this, status, headers, file, offset, length);
    }

    @Override
    public synchronized void upgrade(List<Map.Entry<String, String>> headers, UpgradeHandler handler)
            throws IOException {
        start();
        try {
            TinyHttpdFeatures.upgrade(res, headers, handler);
        } catch (UnsupportedOperationException e) {
            unsupported(e);
        }
    }

    /**
     * Holds this lock only to check the start rule and, through {@link #markUpgraded}, to record the 101: the read
     * loop runs on this worker without it, so abort and the other exchange calls never wait for the connection.
     */
    // @req FR-WS-002
    @Override
    public void webSocket(WebSocketSettings settings, WebSocketEndpoint endpoint) throws IOException {
        synchronized (this) {
            requireStartable();
            upgrading = true;
        }
        try {
            new TinyHttpdWebSocket(req, res, settings, endpoint, this::markUpgraded).run();
        } finally {
            synchronized (this) {
                upgrading = false; // a refusal before 101 leaves the exchange startable for the error path
            }
        }
    }

    /** The 101 was written: the response is started, so the core can no longer commit an error answer. */
    // @req FR-WS-002
    synchronized void markUpgraded() {
        started = true;
        upgrading = false;
    }

    @Override
    public synchronized void eventStream(int status, List<Map.Entry<String, String>> headers, StreamDrain drain)
            throws IOException {
        start();
        try {
            TinyHttpdFeatures.eventStream(res, status, headers, drain);
        } catch (UnsupportedOperationException e) {
            unsupported(e);
        }
    }
}
