package com.snoworca.naruserve.internal;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Fails a read once more than {@code limit} bytes came from the source, with or without a declared length. A
 * declared length within the limit ends the body there and the source is never read past it. Without a declared
 * length it hands out at most {@code limit} bytes, then takes one probe byte from the source; a probe byte fails the
 * read.
 */
// @req FR-HTTP-020
public final class LimitedInputStream extends FilterInputStream {

    private final long limit;
    private final long declaredLength;
    /** Bytes the body may hold: the declared length when known and within the limit, else the limit. */
    private final long cap;
    private long count;

    /** {@code declaredLength} is the request Content-Length, or -1 when absent. */
    // @req FR-HTTP-020
    public LimitedInputStream(InputStream source, long limit, long declaredLength) {
        super(source);
        if (limit <= 0) {
            throw new IllegalArgumentException("body limit must be positive: " + limit);
        }
        this.limit = limit;
        this.declaredLength = declaredLength;
        this.cap = declaredLength >= 0 && declaredLength <= limit ? declaredLength : limit;
    }

    // @req FR-HTTP-020
    @Override
    public int read() throws IOException {
        checkLimit();
        if (count == cap) {
            return probe();
        }
        int r = in.read();
        if (r >= 0) {
            count++;
        }
        return r;
    }

    // @req FR-HTTP-020
    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        checkLimit();
        if (len == 0) {
            return 0;
        }
        long room = cap - count;
        if (room == 0) {
            return probe();
        }
        int n = in.read(b, off, (int) Math.min(len, room));
        if (n > 0) {
            count += n;
        }
        return n;
    }

    // @req FR-HTTP-020
    @Override
    public long skip(long n) throws IOException {
        checkLimit();
        long room = cap - count;
        if (room == 0) {
            return n <= 0 ? 0 : Math.max(0, probe());
        }
        long s = in.skip(Math.min(n, room));
        if (s > 0) {
            count += s;
        }
        return s;
    }

    @Override
    public boolean markSupported() {
        return false;
    }

    @Override
    public synchronized void mark(int readlimit) {
        // Unsupported: a reset would let bytes be counted twice or not at all.
    }

    @Override
    public synchronized void reset() throws IOException {
        throw new IOException("mark/reset not supported");
    }

    /**
     * At the cap: a declared length ends the body; otherwise takes one more byte from the source, so byte limit+1
     * is detected but never handed out.
     */
    private int probe() throws IOException {
        if (declaredLength >= 0 || in.read() < 0) {
            return -1;
        }
        count++;
        throw new BodyTooLargeException(limit);
    }

    /** A declared length over the limit fails before the source is touched; so does any read after a failure. */
    private void checkLimit() throws BodyTooLargeException {
        if (declaredLength > limit || count > limit) {
            throw new BodyTooLargeException(limit);
        }
    }
}
