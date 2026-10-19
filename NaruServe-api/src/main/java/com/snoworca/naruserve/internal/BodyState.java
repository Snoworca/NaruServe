package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.BodyAlreadyConsumedException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;

/**
 * Who owns one request body. A body is read once; the shared form/multipart parse is the only repeatable read.
 */
// @req FR-HTTP-019
public final class BodyState {

    // @req FR-HTTP-019
    public enum State {
        UNREAD, PARSING, CONSUMED, CLOSED
    }

    /** Reader kinds; FORM covers both form and multipart. */
    // @req FR-HTTP-019
    public enum Kind {
        JSON, FORM, RAW
    }

    private final AtomicLong bytesRead = new AtomicLong();
    private State state = State.UNREAD;
    private @Nullable Kind kind;
    private boolean shared;
    private boolean sharing;
    private @Nullable Object cache;

    /**
     * Wraps the adapter's body stream; every byte returned by read and skip is added to {@link #bytesRead}.
     * shortcut: no core body reader exists yet, so in= stays 0 in real traffic; the body readers must read through this.
     */
    // @req FR-APP-015
    public InputStream stream(InputStream source) {
        return new FilterInputStream(source) {
            @Override
            public int read() throws IOException {
                int b = super.read();
                if (b >= 0) {
                    bytesRead.incrementAndGet();
                }
                return b;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                int n = super.read(b, off, len);
                if (n > 0) {
                    bytesRead.addAndGet(n);
                }
                return n;
            }

            @Override
            public long skip(long n) throws IOException {
                long skipped = super.skip(n);
                if (skipped > 0) {
                    bytesRead.addAndGet(skipped);
                }
                return skipped;
            }

            @Override
            public boolean markSupported() {
                return false; // a reset would re-read counted bytes
            }
        };
    }

    /** Body bytes the core read so far; 0 when never read. */
    // @req FR-APP-015
    public long bytesRead() {
        return bytesRead.get();
    }

    public synchronized State state() {
        return state;
    }

    /** Takes the body for a reader of {@code kind}: UNREAD to PARSING. */
    // @req FR-HTTP-019
    public synchronized void begin(Kind kind) {
        if (state != State.UNREAD) {
            throw consumed();
        }
        this.kind = kind;
        state = State.PARSING;
    }

    /** The reader finished: PARSING to CONSUMED. */
    // @req FR-HTTP-019
    public synchronized void complete() {
        if (state == State.CLOSED) {
            return; // a concurrent close wins
        }
        if (state != State.PARSING) {
            throw new IllegalStateException("body is " + state + ", not PARSING");
        }
        state = State.CONSUMED;
    }

    // @req FR-HTTP-019
    public synchronized void close() {
        state = State.CLOSED;
        notifyAll();
    }

    /**
     * FORM only: the first call runs {@code parser} and caches its result, later calls return the cache. The parser
     * runs outside the lock so close() never waits on body I/O; a concurrent caller waits for the result. A failed
     * parse leaves CONSUMED with no cache, so later calls throw {@link BodyAlreadyConsumedException}.
     */
    // @req FR-HTTP-019
    @SuppressWarnings("unchecked")
    public <T> T sharedParse(Callable<T> parser) throws Exception {
        synchronized (this) {
            while (state == State.PARSING && sharing) {
                wait();
            }
            if (state == State.CONSUMED && shared) {
                return (T) cache;
            }
            if (state != State.UNREAD) {
                throw consumed();
            }
            kind = Kind.FORM;
            state = State.PARSING;
            sharing = true;
        }
        boolean ok = false;
        T result = null;
        try {
            result = parser.call();
            ok = true;
            return result;
        } finally {
            synchronized (this) {
                if (state == State.PARSING) {
                    state = State.CONSUMED;
                    cache = ok ? result : null;
                    shared = ok;
                }
                sharing = false;
                notifyAll();
            }
        }
    }

    private BodyAlreadyConsumedException consumed() {
        String by = kind == null ? "" : " by a " + kind + " reader";
        return new BodyAlreadyConsumedException("request body already taken" + by + "; state " + state);
    }
}
