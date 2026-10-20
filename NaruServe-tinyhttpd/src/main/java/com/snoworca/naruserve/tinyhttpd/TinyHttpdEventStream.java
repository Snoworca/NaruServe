package com.snoworca.naruserve.tinyhttpd;

import com.snoworca.naruserve.HttpException;
import com.snoworca.naruserve.spi.StreamDrain;
import com.snoworca.tinyhttpd.http.Response;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The event stream over TinyHttpd: a chunked body set up through {@code setHeader}, {@code flushBuffer()} and
 * {@code getOutputStream()}, never TinyHttpd's {@code startEventStream()}, which adds its own headers. Open streams
 * are counted against the NaruServe SSE slots of the server, which refuse with 503 before the response starts.
 */
// @req FR-SSE-003
final class TinyHttpdEventStream {

    private TinyHttpdEventStream() {
    }

    /**
     * Under the exchange lock only the start checks, a slot and the start mark; then the status and headers, one
     * flush so the client sees them at once, {@code drain.attach} and the registration of the drain that
     * {@link TinyHttpdExchange#run} starts once the handler returned. A refused slot throws a 503
     * {@link HttpException} with the response unstarted.
     */
    // @req FR-SSE-003
    static void start(TinyHttpdExchange ex, Response res, int status, List<Map.Entry<String, String>> headers,
            StreamDrain drain) throws IOException {
        Slots slots = ex.slots();
        synchronized (ex) {
            if (ex.handlerReturned()) {
                throw new IllegalStateException(TinyHttpdExchange.LATE_EVENT_STREAM);
            }
            ex.requireStartable();
            if (!slots.tryAcquire()) {
                throw new HttpException(503);
            }
            ex.markUpgraded(); // the FR-WS-002 start mark: started, so nothing else may commit
        }
        boolean registered = false;
        try {
            res.setStatus(status);
            Set<String> set = new HashSet<>();
            for (Map.Entry<String, String> h : headers) {
                if (set.add(h.getKey().toLowerCase(Locale.ROOT))) {
                    res.setHeader(h.getKey(), h.getValue());
                } else {
                    res.addHeader(h.getKey(), h.getValue()); // a repeated name such as Set-Cookie keeps every line
                }
            }
            Body body = new Body(ex, res);
            body.flush(); // no Content-Length: TinyHttpd commits chunked
            drain.attach(body);
            ex.register(drain, body, true);
            registered = true;
        } finally {
            if (!registered) {
                slots.release();
            }
        }
    }

    /** The NaruServe SSE counter of one bound server, built from {@code maxSseStreams}. */
    // @req FR-SSE-003
    static final class Slots {

        private final int max;
        private final AtomicInteger open = new AtomicInteger();

        Slots(int max) {
            this.max = max;
        }

        /** Takes a slot when fewer than {@code max} are open. */
        // @req FR-SSE-003
        boolean tryAcquire() {
            while (true) {
                int n = open.get();
                if (n >= max) {
                    return false;
                }
                if (open.compareAndSet(n, n + 1)) {
                    return true;
                }
            }
        }

        // @req FR-SSE-003
        void release() {
            open.decrementAndGet();
        }

        int open() {
            return open.get();
        }
    }

    /**
     * The body stream handed to attach and drain: every write and flush runs under the exchange lock and fails at
     * once after an abort; flush passes buffered bytes to the socket through {@code res.flushBuffer()}.
     */
    // @req FR-SSE-003
    static final class Body extends FilterOutputStream {

        private final TinyHttpdExchange ex;
        private final Response res;

        Body(TinyHttpdExchange ex, Response res) throws IOException {
            super(res.getOutputStream());
            this.ex = ex;
            this.res = res;
        }

        @Override
        public void write(int b) throws IOException {
            synchronized (ex) {
                ex.checkNotAborted();
                out.write(b);
            }
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            synchronized (ex) {
                ex.checkNotAborted();
                out.write(b, off, len);
            }
        }

        @Override
        public void flush() throws IOException {
            synchronized (ex) {
                ex.checkNotAborted();
                res.flushBuffer();
            }
        }

        @Override
        public void close() {
            // TinyHttpd ends the body when the worker returns.
        }
    }
}
