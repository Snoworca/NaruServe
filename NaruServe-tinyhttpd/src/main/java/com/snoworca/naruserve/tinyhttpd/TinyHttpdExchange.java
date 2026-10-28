package com.snoworca.naruserve.tinyhttpd;

import com.snoworca.naruserve.common.AbortListeners;
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
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;

/**
 * {@link HttpExchange} over one TinyHttpd request and response. The raw path comes from {@code getRequestURI()} and
 * the raw query from {@code getQueryString()}, never from the decoded, slash-merging {@code getPath()}.
 */
// @req FR-ENGINE-011
final class TinyHttpdExchange implements HttpExchange {

    final Request req;
    final Response res;
    // Transport buffer ownership uses this monitor; lifecycle/drain signals use a separate short-held lock.
    private final Object state = new Object();
    private final AbortListeners abortListeners = new AbortListeners();
    private boolean started;
    private boolean upgrading;
    private volatile boolean aborted;
    private volatile boolean finished;
    private volatile @Nullable Throwable streamFailure;
    private volatile boolean handlerReturned;
    private boolean settingUpStream;
    private @Nullable DrainTask drain;
    private final TinyHttpdEventStream.Slots slots;
    private boolean slotHeld;

    private TinyHttpdExchange(Request req, Response res, TinyHttpdEventStream.Slots slots) {
        this.req = req;
        this.res = res;
        this.slots = slots;
    }

    // @req FR-APP-023 AC-1 AC-3 AC-6; IR-HTTP-009 AC-2 AC-3
    static void run(Request req, Response res, ExchangeHandler handler, TinyHttpdEventStream.Slots slots)
            throws IOException {
        run(req, res, handler, slots, null);
    }

    static void run(Request req, Response res, ExchangeHandler handler, TinyHttpdEventStream.Slots slots,
            @Nullable TinyHttpdAdapter owner) throws IOException {
        TinyHttpdExchange ex = new TinyHttpdExchange(req, res, slots);
        if (owner != null) owner.track(ex);
        try {
            try {
                handler.handle(ex);
            } finally {
                ex.handlerReturned = true;
            }
            ex.awaitWork();
        } finally {
            DrainTask pending;
            boolean held;
            synchronized (ex.state) {
                pending = ex.drain;
                held = ex.slotHeld;
                ex.slotHeld = false;
            }
            try {
                if (pending != null) pending.cancelBeforeStart();
            } finally {
                if (held) slots.release();
                if (owner != null) owner.retire(ex);
            }
        }
    }

    /** A late drain wakes the same bounded HTTP worker; running drain code never holds either state lock. */
    private void awaitWork() throws IOException {
        boolean interrupted = false;
        for (;;) {
            DrainTask next;
            try {
                synchronized (state) {
                    while (settingUpStream || (!finished && (drain == null || !drain.ready))) state.wait();
                    if (finished) break;
                    next = drain;
                }
            } catch (InterruptedException stopSignal) {
                abort();
                interrupted = true;
                // A late attach still owns the response and its slot, even after the stop deadline.
                continue;
            }
            if (next != null && next.claimed.compareAndSet(false, true)) {
                try {
                    checkNotAborted();
                    next.callback.drain(next.output);
                    if (streamFailure != null) abort();
                    else finish();
                } catch (IOException | RuntimeException failure) {
                    abort();
                }
            }
        }
        // abort closes the socket first, then waits here for any writer to relinquish response buffers.
        synchronized (this) { }
        if (interrupted) Thread.currentThread().interrupt();
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
            return new TinyHttpdBodyStream(req.getInputStream(), this::abort);
        } catch (IOException e) {
            abort();
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
    public OutputStream commit(int status, List<Map.Entry<String, String>> headers, long contentLength)
            throws IOException {
        boolean claimed = false;
        try {
            synchronized (this) {
                start();
                claimed = true;
                res.setStatus(status);
                for (Map.Entry<String, String> h : headers) res.addHeader(h.getKey(), h.getValue());
                return new FilterOutputStream(res.getOutputStream()) {
                    @Override public void write(int b) throws IOException { writeBody(() -> out.write(b)); }
                    @Override public void write(byte[] b, int off, int len) throws IOException { writeBody(() -> out.write(b, off, len)); }
                    @Override public void flush() throws IOException { writeBody(() -> out.flush()); }
                    @Override public void close() { /* The engine owns terminal framing. */ }
                };
            }
        } catch (IOException | RuntimeException failure) {
            if (claimed) abort();
            throw failure;
        }
    }

    interface IoOperation { void run() throws IOException; }

    // @req IR-HTTP-009 AC-2 AC-3: notify only after the response-buffer monitor has been released.
    void writeBody(IoOperation operation) throws IOException {
        try {
            streamBody(operation);
        } catch (IOException | RuntimeException failure) {
            abort();
            throw failure;
        }
    }

    /** SSE's drain owns failure classification before it invokes abort; ordinary writes notify immediately. */
    void streamBody(IoOperation operation) throws IOException {
        try {
            synchronized (this) {
                checkNotAborted();
                if (finished) throw new IOException("exchange finished");
                operation.run();
            }
        } catch (IOException | RuntimeException failure) {
            // Let the owning SSE drain classify client/error first; remember swallowed failures until it returns.
            synchronized (state) { if (streamFailure == null) streamFailure = failure; }
            throw failure;
        }
    }

    @Override
    public void finish() throws IOException {
        try {
            synchronized (this) {
                checkNotAborted();
                if (finished) return;
                Throwable pendingFailure = streamFailure;
                if (pendingFailure instanceof IOException) throw (IOException) pendingFailure;
                if (pendingFailure instanceof RuntimeException) throw (RuntimeException) pendingFailure;
                res.flushBuffer();
                synchronized (state) {
                    checkNotAborted();
                    finished = true;
                    state.notifyAll();
                }
            }
        } catch (IOException | RuntimeException failure) {
            abort();
            throw failure;
        }
    }

    // @req IR-HTTP-009 AC-1 AC-2 AC-3 AC-5
    @Override public void onAbort(Runnable listener) { abortListeners.onAbort(listener); }

    /** Socket cancellation cannot wait for a blocked writer's response-buffer monitor. */
    @Override
    public void abort() {
        DrainTask pending;
        synchronized (state) {
            if (finished) return;
            aborted = true;
            finished = true;
            pending = drain;
            state.notifyAll();
        }
        try {
            res.abort();
        } finally {
            abortListeners.abort();
            if (pending != null) pending.cancelBeforeStart();
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
        if (finished) throw new IOException("exchange finished");
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
    public void sendFile(int status, List<Map.Entry<String, String>> headers, File file, long offset,
            long length) throws IOException {
        // Checked before commit so a bad range leaves the response unstarted.
        long size = file.length();
        if (offset < 0 || length < 0 || offset > size - length) {
            throw new IllegalArgumentException(
                    "range offset " + offset + " length " + length + " outside file of " + size + " bytes");
        }
        synchronized (this) { requireStartable(); } // validate before opening the file
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
     * Start checks, headers and transport writes use the response-buffer monitor. User attach/drain callbacks
     * and terminal notifications run outside it; setup publication wakes the worker after the initial return.
     */
    // @req FR-ENGINE-011
    @Override
    public void eventStream(int status, List<Map.Entry<String, String>> headers, StreamDrain drain)
            throws IOException {
        TinyHttpdFeatures.eventStream(this, status, headers, drain);
    }

    /** Reserve drain cancellation ownership before setup can invoke user attach code. Caller owns the slot. */
    // @req FR-APP-023 AC-1 AC-3 AC-6
    void acceptDrain(StreamDrain callback) {
        synchronized (state) {
            drain = new DrainTask(callback);
            slotHeld = true;
            settingUpStream = true;
        }
    }

    /** Publish attached work to the waiting worker without replacing the request identity. */
    void register(StreamDrain callback, OutputStream output, boolean slot) throws IOException {
        synchronized (state) {
            checkNotAborted();
            DrainTask task = drain;
            if (task == null || task.callback != callback) throw new IllegalStateException("unreserved drain");
            task.output = output;
            task.ready = true;
        }
    }

    void finishStreamSetup() {
        synchronized (state) { settingUpStream = false; state.notifyAll(); }
    }

    private static final class DrainTask {
        final StreamDrain callback;
        final AtomicBoolean claimed = new AtomicBoolean();
        boolean ready;
        OutputStream output;
        DrainTask(StreamDrain callback) { this.callback = callback; }
        void cancelBeforeStart() {
            if (claimed.compareAndSet(false, true)) callback.abort();
        }
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

    /** Whether the initial SPI callback returned; used by transport tests to establish the late-work boundary. */
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
