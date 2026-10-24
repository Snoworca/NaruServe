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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import org.jspecify.annotations.Nullable;

/**
 * {@link HttpExchange} over one TinyHttpd request and response. The raw path comes from {@code getRequestURI()} and
 * the raw query from {@code getQueryString()}, never from the decoded, slash-merging {@code getPath()}.
 */
// @req FR-ENGINE-011
final class TinyHttpdExchange implements HttpExchange {

    final Request req;
    final Response res;
    private final CountDownLatch ended = new CountDownLatch(1);
    // Guarded by this, which also serialises every touch of res by the core so abort() can hand res back safely;
    // TinyHttpdEventStream.start sets the event-stream headers after the start mark without it.
    // shortcut: an eventStream from a thread other than the worker races a handler return plus abort only in that
    // header window; take the lock there if such a caller appears.
    private boolean started;
    // Guarded by this: the worker runs a WebSocket handshake on res without the lock, so no other start may touch it.
    private boolean upgrading;
    private volatile boolean aborted;
    // Guarded by this: the event-stream drain run() starts on the worker once the handler returned, and its stream.
    private @Nullable StreamDrain drain;
    private @Nullable OutputStream drainOut;
    // Guarded by this: set when run() took the drain, so a drain recorded later would never run.
    private boolean handlerReturned;

    static final String LATE_EVENT_STREAM = "eventStream must be called before the handler returns";

    private final TinyHttpdEventStream.Slots slots;
    // Guarded by this: register recorded a slot for this exchange, which run() releases when it leaves.
    private boolean slotHeld;

    private TinyHttpdExchange(Request req, Response res, TinyHttpdEventStream.Slots slots) {
        this.req = req;
        this.res = res;
        this.slots = slots;
    }

    /**
     * Runs {@code handler} on the TinyHttpd worker, then holds the worker until the core finishes or aborts the
     * exchange, which may happen on another thread; TinyHttpd completes the response once this returns. A drain
     * registered by the handler runs on this worker in between, and the SSE slot it holds is released on the way out.
     */
    // @req FR-ENGINE-011
    // @req FR-SSE-003
    static void run(Request req, Response res, ExchangeHandler handler, TinyHttpdEventStream.Slots slots)
            throws IOException {
        TinyHttpdExchange ex = new TinyHttpdExchange(req, res, slots);
        try {
            run(ex, handler);
        } finally {
            boolean held;
            synchronized (ex) {
                ex.handlerReturned = true; // also after a throwing handler, so no later stream takes a slot
                held = ex.slotHeld;
                ex.slotHeld = false;
            }
            if (held) {
                slots.release();
            }
        }
    }

    // @req FR-SSE-003
    private static void run(TinyHttpdExchange ex, ExchangeHandler handler) throws IOException {
        handler.handle(ex);
        StreamDrain d;
        OutputStream out;
        synchronized (ex) {
            ex.handlerReturned = true;
            d = ex.drain;
            out = ex.drainOut;
        }
        if (d != null && out != null && !ex.aborted) {
            try {
                // On this worker, outside the lock; its writes synchronize on ex and fail at once after an abort.
                d.drain(out);
            } catch (IOException | RuntimeException e) {
                ex.abort();
            }
        }
        try {
            ex.ended.await();
        } catch (InterruptedException e) {
            // TinyHttpd interrupts workers at its stop deadline.
            ex.abort();
            Thread.currentThread().interrupt();
        }
        if (ex.aborted) {
            // abort() already closed the transport and woke blocked writes. Wait for their buffer ownership to end
            // before TinyHttpd reclaims the response, without manufacturing any HTTP response (including HEAD).
            synchronized (ex) {
                return;
            }
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

    // @req IR-HTTP-003
    @Override
    public List<Map.Entry<String, String>> headers() {
        return req.getHeaderEntries();
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

    // No exchange lock: TinyHttpd cancels the socket even while a writer owns that lock.
    @Override
    public void abort() {
        aborted = true;
        try {
            res.abort();
        } finally {
            ended.countDown();
        }
    }

    /** The abort check and single-start rule shared by every way to start the response; caller holds this. */
    private void start() throws IOException {
        requireStartable();
        started = true;
    }

    /** The checks of {@link #start()} without starting; caller holds this. */
    // @req FR-ENGINE-011
    void requireStartable() throws IOException {
        checkNotAborted();
        if (started) {
            throw new IllegalStateException("the response was already started");
        }
        if (upgrading) {
            throw new IllegalStateException("a WebSocket handshake is using the response");
        }
    }

    // Writes through commit()'s stream and TinyHttpdEventStream.Body (the event-stream drain, the core's attached
    // sends) also synchronize on this.
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

    /** Checks the start rule without starting, so the unsupported raw upgrade leaves the response unstarted. */
    // @req FR-ENGINE-011
    @Override
    public synchronized void upgrade(List<Map.Entry<String, String>> headers, UpgradeHandler handler)
            throws IOException {
        requireStartable();
        TinyHttpdFeatures.upgrade(this, headers, handler);
    }

    /**
     * Not synchronized: the hook holds this lock only to check the start rule and, through {@link #markUpgraded}, to
     * record the 101, so abort and the other exchange calls never wait for the read loop.
     */
    // @req FR-WS-002
    // @req FR-ENGINE-011
    @Override
    public void webSocket(WebSocketSettings settings, WebSocketEndpoint endpoint) throws IOException {
        TinyHttpdFeatures.webSocket(this, settings, endpoint);
    }

    /** The 101 was written: the response is started, so the core can no longer commit an error answer. */
    // @req FR-WS-002
    synchronized void markUpgraded() {
        started = true;
        upgrading = false;
    }

    /**
     * Not synchronized: the hook takes this lock only for the start checks, the SSE slot and the start mark, and the
     * headers, the first flush and the drain run without it.
     */
    // @req FR-ENGINE-011
    @Override
    public void eventStream(int status, List<Map.Entry<String, String>> headers, StreamDrain drain)
            throws IOException {
        TinyHttpdFeatures.eventStream(this, status, headers, drain);
    }

    /**
     * Records the drain {@link #run} starts on the worker once the handler returned, and whether it holds an SSE slot
     * for run() to release; after that nothing would run it, so a call from another thread past that point throws
     * instead of leaving the stream silent.
     */
    // @req FR-ENGINE-011
    // @req FR-SSE-003
    synchronized void register(StreamDrain d, OutputStream out, boolean slot) {
        if (handlerReturned) {
            throw new IllegalStateException(LATE_EVENT_STREAM);
        }
        drain = d;
        drainOut = out;
        slotHeld = slot;
    }

    /** The SSE slots of the server this exchange runs on. */
    // @req FR-SSE-003
    TinyHttpdEventStream.Slots slots() {
        return slots;
    }

    /** The abort check of the body streams. */
    // @req FR-SSE-003
    void checkNotAborted() throws IOException {
        if (aborted) {
            throw new IOException("exchange aborted");
        }
    }

    /** Whether run() has taken the drain, so an event stream started now would never be drained. */
    // @req FR-ENGINE-011
    synchronized boolean handlerReturned() {
        return handlerReturned;
    }

    /** Marks the response busy with a WebSocket handshake that runs without this lock; after requireStartable. */
    // @req FR-ENGINE-011
    synchronized void beginUpgrade() {
        upgrading = true;
    }

    /** Ends the handshake mark; after a refusal before 101 the exchange is startable again for the error path. */
    // @req FR-ENGINE-011
    synchronized void endUpgrade() {
        upgrading = false;
    }
}
