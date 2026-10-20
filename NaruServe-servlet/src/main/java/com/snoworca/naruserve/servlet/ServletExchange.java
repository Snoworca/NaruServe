package com.snoworca.naruserve.servlet;

import com.snoworca.naruserve.HttpException;
import com.snoworca.naruserve.spi.HttpExchange;
import com.snoworca.naruserve.spi.StreamDrain;
import com.snoworca.naruserve.spi.UpgradeHandler;
import com.snoworca.naruserve.spi.WebSocketEndpoint;
import com.snoworca.naruserve.spi.WebSocketSettings;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import javax.servlet.AsyncContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;

/**
 * {@link HttpExchange} over one container request and response. Request values come from the plain Servlet getters
 * (never {@code getServletPath}, {@code getPathInfo} or {@code getParameter*}); the response is written with
 * {@code setStatus}, {@code addHeader} and {@code setHeader("Content-Length")}, never {@code sendError} or
 * {@code setContentLength}, so the core owns the error body and the length. The body is read only through
 * {@code getInputStream()}, so the core parser handles multipart, urlencoded and JSON bodies; an event stream runs
 * under an {@link AsyncContext}; WebSocket and upgrade answer 501.
 */
// @req FR-SVL-002
// @req FR-SVL-003
// @req FR-SVL-004
// @req FR-SVL-005
// @req FR-SVL-007
final class ServletExchange implements HttpExchange {

    /** The largest chunk {@link #sendFile} reads and writes at once. */
    static final int CHUNK = 65536;

    /** How long abort() waits for a running drain write before completing anyway (a write blocked on the client). */
    static final long ABORT_WAIT_MILLIS = 250;

    private final HttpServletRequest req;
    private final HttpServletResponse res;
    private final String path;
    private final @Nullable ServletAdapter adapter;
    // Guarded by this.
    private boolean started;
    // Guarded by this: the event-stream drain afterHandler() submits, its stream, and whether that already ran.
    private @Nullable StreamDrain drain;
    private @Nullable OutputStream drainOut;
    private boolean handlerReturned;
    // Lock-free so abort() never waits for a writer.
    private volatile boolean aborted;
    // Set once an event stream started async processing.
    private volatile @Nullable AsyncContext async;
    private final AtomicBoolean completed = new AtomicBoolean();
    /** Held by the event stream's body stream around each write; abort() waits for it before completing. */
    final ReentrantLock writeLock = new ReentrantLock();

    /** An exchange without an adapter: no event stream can start on it. */
    // @req FR-SVL-002
    ServletExchange(HttpServletRequest req, HttpServletResponse res, String path) {
        this(req, res, path, null);
    }

    /** An exchange of {@code adapter}, whose {@link ServletAdapter#pool} counts and runs its event stream. */
    // @req FR-SVL-002
    // @req FR-SVL-005
    ServletExchange(HttpServletRequest req, HttpServletResponse res, String path, @Nullable ServletAdapter adapter) {
        this.req = req;
        this.res = res;
        this.path = path;
        this.adapter = adapter;
    }

    // @req FR-SVL-002
    @Override
    public String methodToken() {
        return req.getMethod();
    }

    /** The remainder {@link ServletPath} computed from the raw request URI. */
    // @req FR-SVL-002
    @Override
    public String rawPath() {
        return path;
    }

    // @req FR-SVL-002
    @Override
    public @Nullable String rawQuery() {
        return req.getQueryString();
    }

    // @req FR-SVL-002
    @Override
    public String httpVersion() {
        return req.getProtocol();
    }

    /** Every value of every name, in per-name order; the Servlet API gives no order across names. */
    // @req FR-SVL-002
    @Override
    public List<Map.Entry<String, String>> headers() {
        List<Map.Entry<String, String>> out = new ArrayList<>();
        Enumeration<String> names = req.getHeaderNames();
        while (names != null && names.hasMoreElements()) {
            String name = names.nextElement();
            Enumeration<String> values = req.getHeaders(name);
            while (values != null && values.hasMoreElements()) {
                out.add(new SimpleImmutableEntry<>(name, values.nextElement()));
            }
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * The container's input stream only: the core parser reads multipart, urlencoded and JSON bodies from it, so
     * {@code getParts}, {@code getPart} and {@code getParameter*} are never called and the core limits apply.
     */
    // @req FR-SVL-002
    // @req FR-SVL-004
    @Override
    public InputStream requestBody() {
        try {
            return req.getInputStream();
        } catch (IOException e) {
            throw new UncheckedIOException("request body unavailable", e);
        }
    }

    // @req FR-SVL-002
    @Override
    public String remoteAddress() {
        return req.getRemoteAddr();
    }

    // @req FR-SVL-002
    @Override
    public boolean isSecure() {
        return req.isSecure();
    }

    /** {@code contentLength} needs no action: the core's Content-Length header is passed as given. */
    // @req FR-SVL-003
    @Override
    public synchronized OutputStream commit(int status, List<Map.Entry<String, String>> headers, long contentLength)
            throws IOException {
        requireStartable();
        started = true;
        head(status, headers);
        return new FilterOutputStream(res.getOutputStream()) {
            @Override
            public void write(int b) throws IOException {
                checkNotAborted();
                out.write(b);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                checkNotAborted();
                out.write(b, off, len);
            }

            @Override
            public void flush() throws IOException {
                checkNotAborted();
                out.flush();
            }

            @Override
            public void close() {
                // The container ends the body when service() returns.
            }
        };
    }

    // @req FR-SVL-003
    @Override
    public void finish() throws IOException {
        checkNotAborted();
        res.flushBuffer();
    }

    /** True when {@link #abort} ran on an exchange that never started an event stream: the servlet then fails the request. */
    // @req FR-SVL-003
    boolean abortedWithoutStream() {
        return aborted && async == null;
    }

    /**
     * Marks the exchange aborted; the container owns the socket, so nothing is called on it, except that an event
     * stream's AsyncContext is completed (once) so the container ends the response. A drain write already running
     * is given {@link #ABORT_WAIT_MILLIS} to return first, so complete() does not overlap it; a write blocked on a
     * slow client is cut by complete() after that wait, which is what frees it.
     */
    // @req FR-SVL-003
    // @req FR-SVL-005
    @Override
    public void abort() {
        aborted = true;
        if (async != null) {
            boolean held = false;
            try {
                held = writeLock.tryLock(ABORT_WAIT_MILLIS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            try {
                complete();
            } finally {
                if (held) {
                    writeLock.unlock();
                }
            }
        }
    }

    /**
     * Commits, then copies {@code length} bytes from {@code offset} through java.io in chunks of {@link #CHUNK}. The
     * arguments are checked in the order of the TinyHttpd adapter: the range (IllegalArgumentException), then the
     * abort check and single-start rule, then the open of the file.
     */
    // @req FR-SVL-003
    @Override
    public void sendFile(int status, List<Map.Entry<String, String>> headers, File file, long offset, long length)
            throws IOException {
        // The range first, then the start rule, then the open (the order of the TinyHttpd adapter), so a bad range or
        // a missing file leaves the response unstarted and the core can still answer with an error.
        long size = file.length();
        if (offset < 0 || length < 0 || offset > size - length) {
            throw new IllegalArgumentException(
                    "range offset " + offset + " length " + length + " outside file of " + size + " bytes");
        }
        requireStartable();
        OutputStream out;
        try (InputStream in = new FileInputStream(file)) {
            for (long skipped = 0; skipped < offset; ) {
                long n = in.skip(offset - skipped);
                if (n <= 0) {
                    throw new EOFException("file ended before offset " + offset + ": " + file);
                }
                skipped += n;
            }
            out = commit(status, headers, length);
            byte[] buf = new byte[(int) Math.min(CHUNK, Math.max(1, length))];
            for (long left = length; left > 0; ) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) {
                    throw new EOFException("file ended " + left + " bytes before the requested range: " + file);
                }
                out.write(buf, 0, n);
                left -= n;
            }
        }
        out.flush();
    }

    /** Servlet 3.1 offers no upgrade the core can use here: 501, the exchange left unstarted. */
    // @req FR-SVL-003
    // @req FR-SVL-007
    @Override
    public void upgrade(List<Map.Entry<String, String>> headers, UpgradeHandler handler) {
        throw new HttpException(501, "upgrade is not supported by the Servlet adapter");
    }

    /**
     * WebSocket is not supported on the Servlet adapter in v1: 501 instead of the SPI default, which the core would
     * answer with 500; the exchange stays unstarted, so the core commits the error.
     */
    // @req FR-SVL-007
    @Override
    public void webSocket(WebSocketSettings settings, WebSocketEndpoint endpoint) {
        throw new HttpException(501, "WebSocket is not supported by the Servlet adapter");
    }

    // @req FR-SVL-003
    // @req FR-SVL-005
    @Override
    public void eventStream(int status, List<Map.Entry<String, String>> headers, StreamDrain drain)
            throws IOException {
        ServletEventStream.start(this, status, headers, drain);
    }

    /**
     * Called once by the servlet after the core's handler returned: submits the registered drain to the pool, so
     * the container's request thread returns while the stream stays open. An event stream started later throws
     * IllegalStateException. For an aborted stream, or once the pool was shut down, it cancels the core drain and
     * gives the slot back.
     */
    // @req FR-SVL-005
    void afterHandler() {
        StreamDrain d;
        OutputStream out;
        synchronized (this) {
            if (handlerReturned) {
                return;
            }
            handlerReturned = true;
            d = drain;
            out = drainOut;
            drain = null;
            drainOut = null;
        }
        if (d == null || out == null) {
            return;
        }
        ServletEventStream.Pool pool = pool();
        if (!aborted) {
            try {
                pool.submit(ServletEventStream.drainTask(this, d, out));
                return;
            } catch (RejectedExecutionException e) {
                abort();
            }
        }
        try {
            d.abort();
        } finally {
            pool.finish(this);
        }
    }

    /** The pool of the adapter; IllegalStateException for an exchange without one. */
    // @req FR-SVL-005
    ServletEventStream.Pool pool() {
        ServletEventStream.Pool p = adapter != null ? adapter.pool() : null;
        if (p == null) {
            throw new IllegalStateException("no SSE pool: the exchange has no bound ServletAdapter");
        }
        return p;
    }

    HttpServletRequest request() {
        return req;
    }

    HttpServletResponse response() {
        return res;
    }

    /** An event stream must start before the handler returns, or no drain would run it. */
    // @req FR-SVL-005
    synchronized void requireDrainable() {
        if (handlerReturned) {
            throw new IllegalStateException("eventStream must be called before the handler returns");
        }
    }

    /** The single start mark, set by the event stream once it holds a slot. */
    // @req FR-SVL-005
    synchronized void markStarted() {
        started = true;
    }

    // @req FR-SVL-005
    void async(AsyncContext ac) {
        async = ac;
    }

    /** Records the drain {@link #afterHandler} submits and the stream it writes to. */
    // @req FR-SVL-005
    synchronized void register(StreamDrain d, OutputStream out) {
        requireDrainable(); // afterHandler already ran, so this drain would never be submitted
        drain = d;
        drainOut = out;
    }

    /** Completes the event stream's AsyncContext once; later calls, or one the container refuses, do nothing. */
    // @req FR-SVL-005
    void complete() {
        AsyncContext ac = async;
        if (ac != null && completed.compareAndSet(false, true)) {
            try {
                ac.complete();
            } catch (IllegalStateException e) {
                // the container already ended the request
            }
        }
    }

    /** Status and headers: Content-Length through setHeader, every other header through addHeader, in order. */
    // @req FR-SVL-003
    void head(int status, List<Map.Entry<String, String>> headers) {
        res.setStatus(status);
        for (Map.Entry<String, String> h : headers) {
            if (h.getKey().equalsIgnoreCase("Content-Length")) {
                res.setHeader(h.getKey(), h.getValue());
            } else {
                res.addHeader(h.getKey(), h.getValue());
            }
        }
    }

    void checkNotAborted() throws IOException {
        if (aborted) {
            throw new IOException("exchange aborted");
        }
    }

    /** A second start of one exchange throws IllegalStateException; an aborted one throws IOException. */
    // @req FR-SVL-003
    synchronized void requireStartable() throws IOException {
        checkNotAborted();
        if (started) {
            throw new IllegalStateException("the response was already started");
        }
    }
}
