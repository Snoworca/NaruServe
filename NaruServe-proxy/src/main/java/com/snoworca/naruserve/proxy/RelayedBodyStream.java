package com.snoworca.naruserve.proxy;

import com.snoworca.naruserve.Logger;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import org.jspecify.annotations.Nullable;

/**
 * The backend response body as handed to {@code res.relay}: closing it closes the backend body and then releases the
 * request's {@link ProxyExchange}. The first of this close and the {@code res.onAbort} notification releases the
 * exchange, so the concurrency slot is held until the relayed body ends or the response is aborted.
 *
 * <p>A backend failure while the framework copies the body (FR-PROXY-016) — the end of the stream before the declared
 * length, a read failure such as a malformed chunk, or a read timeout — becomes a {@link ProxyException} of its kind:
 * a failure on the backend socket closed by a firing deadline is the kind that deadline recorded (FR-PROXY-017, the
 * total timeout), a failure already caused by a {@code ProxyException} keeps it, a {@link SocketTimeoutException} is
 * {@code READ_TIMEOUT} and every other read failure is {@code INVALID_RESPONSE}. It is logged once to the app logger
 * and then thrown as an {@link IOException} caused by it, so the framework aborts the client connection; every later
 * read throws the same exception without logging again. A read failing after {@link #abandon} (the client went away
 * and the abort listener closed the backend connection) is not a backend failure and passes through unlogged.
 */
// @req FR-PROXY-019
// @req REL-PROXY-001
// @req FR-PROXY-016
// @req FR-PROXY-017
final class RelayedBodyStream extends FilterInputStream {

    private final ProxyExchange exchange;
    private final Logger logger;
    /** The backend the log record names; never sent to the client. */
    private final String backend;
    /** Body bytes the backend still owes under its declared length, or -1 for a body without one. */
    private long remaining;
    /** Set by the abort listener before it closes the backend connection. */
    private volatile boolean abandoned;
    /** The failure thrown by every read after the first one. */
    private @Nullable IOException failure;

    /**
     * The relayed body {@code body} of {@code exchange}: {@code length} is the declared body length, or -1 when the
     * backend's close ends the body; failures are logged to {@code logger} naming {@code backend}.
     */
    // @req FR-PROXY-019
    // @req FR-PROXY-016
    RelayedBodyStream(InputStream body, ProxyExchange exchange, long length, Logger logger, String backend) {
        super(body);
        this.exchange = exchange;
        this.remaining = length;
        this.logger = logger;
        this.backend = backend;
    }

    /** Marks the client as gone, so the read failure that the backend connection's close causes is not logged. */
    // @req FR-PROXY-016
    void abandon() {
        abandoned = true;
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        int n = read(one, 0, 1);
        return n < 0 ? -1 : one[0] & 0xff;
    }

    // @req FR-PROXY-016
    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (failure != null) {
            throw failure;
        }
        int n;
        try {
            n = in.read(b, off, len);
        } catch (IOException e) {
            throw fail(e);
        }
        if (n < 0) {
            if (remaining > 0) {
                throw fail(new IOException("the backend closed " + remaining + " bytes before its Content-Length"));
            }
            return -1;
        }
        if (remaining > 0) {
            remaining = Math.max(0, remaining - n);
        }
        return n;
    }

    /** Reads and drops up to {@code n} bytes, so a skip is counted against the declared length like a read. */
    @Override
    public long skip(long n) throws IOException {
        if (n <= 0) {
            return 0;
        }
        byte[] buf = new byte[(int) Math.min(n, 8192)];
        long left = n;
        while (left > 0) {
            int k = read(buf, 0, (int) Math.min(buf.length, left));
            if (k < 0) {
                break;
            }
            left -= k;
        }
        return n - left;
    }

    @Override
    public boolean markSupported() {
        return false;
    }

    @Override
    public synchronized void mark(int readlimit) {
        // not supported: a reset could replay bytes past the declared length check
    }

    @Override
    public synchronized void reset() throws IOException {
        throw new IOException("mark/reset not supported");
    }

    /**
     * The exception every read throws from now on for the read failure {@code e}: logged once, as described in the
     * class comment, unless the client went away first.
     */
    // @req FR-PROXY-016
    // @req FR-PROXY-017
    private IOException fail(IOException e) {
        if (abandoned) {
            return e;
        }
        Throwable cause = e.getCause();
        ProxyException own = cause instanceof ProxyException ? (ProxyException) cause
                : new ProxyException(e instanceof SocketTimeoutException ? ProxyException.Kind.READ_TIMEOUT
                        : ProxyException.Kind.INVALID_RESPONSE, e);
        // A deadline that fired first closed the socket this read failed on; otherwise the read's own kind stands.
        ProxyException.Kind settled = exchange.settle(own.kind());
        ProxyException proxyException = settled == null || settled == own.kind() ? own
                : new ProxyException(settled, e);
        // The log comes before the throw: the framework's abort keeps no cause, so this record is the only one.
        try {
            logger.error("proxy: the response body from " + backend + " failed after the response started ("
                    + proxyException.kind() + ")", proxyException);
        } catch (RuntimeException ignored) {
            // a failing app logger must not replace the IOException the framework aborts on
        }
        failure = new IOException(proxyException);
        return failure;
    }

    // @req FR-PROXY-019
    @Override
    public void close() throws IOException {
        try {
            super.close();
        } finally {
            exchange.release();
        }
    }
}
