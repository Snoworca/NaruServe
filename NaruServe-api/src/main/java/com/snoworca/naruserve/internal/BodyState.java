package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.BodyAlreadyConsumedException;
import java.util.concurrent.Callable;
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

    private State state = State.UNREAD;
    private @Nullable Kind kind;
    private boolean shared;
    private boolean sharing;
    private @Nullable Object cache;

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
