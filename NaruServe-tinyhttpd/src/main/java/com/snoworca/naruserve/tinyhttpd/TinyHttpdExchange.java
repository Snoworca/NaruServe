package com.snoworca.naruserve.tinyhttpd;

import com.snoworca.naruserve.spi.ExchangeHandler;
import com.snoworca.naruserve.spi.HttpExchange;
import com.snoworca.naruserve.spi.StreamDrain;
import com.snoworca.naruserve.spi.UpgradeHandler;
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
final class TinyHttpdExchange implements HttpExchange {

    private final Request req;
    private final Response res;
    private final CountDownLatch ended = new CountDownLatch(1);
    // Guarded by this.
    private boolean started;
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
            // shortcut: before commit TinyHttpd answers this IOException with its plain 500 page instead of a bare
            // close (its public API has no abort hook); SDS wave-5-6 owns the TinyHttpd error path.
            res.setCloseConnection(true);
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
            return req.getInputStream();
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

    private synchronized void start() {
        if (started) {
            throw new IllegalStateException("the response was already started");
        }
        started = true;
    }

    @Override
    public OutputStream commit(int status, List<Map.Entry<String, String>> headers, long contentLength)
            throws IOException {
        if (aborted) {
            throw new IOException("exchange aborted");
        }
        start();
        res.setStatus(status);
        for (Map.Entry<String, String> h : headers) {
            res.addHeader(h.getKey(), h.getValue());
        }
        return new FilterOutputStream(res.getOutputStream()) {
            @Override
            public void write(int b) throws IOException {
                check();
                out.write(b);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                check();
                out.write(b, off, len);
            }

            @Override
            public void flush() throws IOException {
                check();
                out.flush();
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
    public void finish() throws IOException {
        if (aborted) {
            throw new IOException("exchange aborted");
        }
        ended.countDown();
    }

    // shortcut: a body write already blocked in the socket is not woken; it fails at TinyHttpd's writeTimeout.
    @Override
    public void abort() {
        aborted = true;
        ended.countDown();
    }

    @Override
    public void sendFile(int status, List<Map.Entry<String, String>> headers, File file, long offset, long length) {
        TinyHttpdFeatures.sendFile(res, status, headers, file, offset, length);
    }

    @Override
    public void upgrade(List<Map.Entry<String, String>> headers, UpgradeHandler handler) {
        TinyHttpdFeatures.upgrade(res, headers, handler);
    }

    @Override
    public void eventStream(int status, List<Map.Entry<String, String>> headers, StreamDrain drain) {
        TinyHttpdFeatures.eventStream(res, status, headers, drain);
    }
}
