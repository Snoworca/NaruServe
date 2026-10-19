package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.Response;
import com.snoworca.naruserve.spi.HttpExchange;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;

/**
 * {@link Response} over one exchange. Leaving NEW is decided under the lock, once, and the status and headers are
 * snapshotted there; every exchange call (commit, body write, finish, abort) runs outside the lock so abort never
 * waits on I/O. An exchange failure ends the response ABORTED instead of throwing, as Express ignores a dead socket
 * on send (UncheckedIOException is also API 24+).
 */
// @req FR-HTTP-013
// @req FR-HTTP-014
// @req FR-HTTP-015
// @req FR-HTTP-016
// @req FR-HTTP-017
// @req FR-HTTP-018
public final class DefaultResponse implements Response {

    private static final int CHUNK = 8192;
    private static final byte[] EMPTY = new byte[0];

    private final HttpExchange ex;
    private final boolean headRequest;
    private final AtomicBoolean inputClosed = new AtomicBoolean();

    // Guarded by this.
    private ResponseState state = ResponseState.NEW;
    private boolean committed;
    private int status = 200;
    private final List<Map.Entry<String, String>> headers = new ArrayList<>();
    private @Nullable InputStream pendingIn;
    private @Nullable OutputStream pendingOut;
    private boolean handlerDone;
    private @Nullable Throwable handlerFailure;

    /** Status 200, no headers, state NEW; {@code headRequest} makes every body bodyless. */
    // @req FR-HTTP-013
    public DefaultResponse(HttpExchange ex, boolean headRequest) {
        this.ex = ex;
        this.headRequest = headRequest;
    }

    // @req FR-HTTP-013
    // @req FR-HTTP-016
    @Override
    public synchronized Response status(int code) {
        requireNew();
        status = code;
        return this;
    }

    /**
     * Replaces a same-name header (case-insensitive), as Express res.set does. The name must be an RFC 9110 token
     * and the value may hold no control character but HTAB (so no CR or LF).
     */
    // @req FR-HTTP-013
    // @req FR-HTTP-015
    @Override
    public synchronized Response header(String name, String value) {
        requireNew();
        if (!isToken(name) || !isFieldValue(value)) {
            throw badHeader();
        }
        put(name, value);
        return this;
    }

    // @req FR-HTTP-014
    @Override
    public void send(String body) {
        write(body.getBytes(StandardCharsets.UTF_8), "text/plain; charset=UTF-8");
    }

    // @req FR-HTTP-014
    @Override
    public void html(String body) {
        write(body.getBytes(StandardCharsets.UTF_8), "text/html; charset=UTF-8");
    }

    // @req FR-HTTP-014
    @Override
    public void send(byte[] body) {
        write(body, "application/octet-stream");
    }

    // @req FR-HTTP-014
    @Override
    public void end() {
        write(EMPTY, null);
    }

    /**
     * Commits with unknown length and enters STREAMING without reading {@code in}; the Dispatcher calls
     * {@link #runTransfer} after the handler returns. A bodyless response closes {@code in} without copying. The
     * response owns {@code in} even when it refuses the call, so a refusal closes it too.
     */
    // @req FR-HTTP-014
    // @req FR-HTTP-016
    // @req FR-HTTP-017
    @Override
    public void stream(InputStream in, String contentType) {
        RuntimeException refusal;
        Commit c = null;
        boolean bodyless = false;
        synchronized (this) {
            refusal = state != ResponseState.NEW ? notNew() : !isFieldValue(contentType) ? badHeader() : null;
            if (refusal == null) {
                pendingIn = in;
                put("Content-Type", contentType);
                remove("Content-Length"); // the length is unknown; the adapter frames the body
                remove("Transfer-Encoding");
                bodyless = bodyless();
                c = begin(bodyless ? 0 : -1, EMPTY);
            }
        }
        if (refusal != null) {
            closeQuietly(in);
            throw refusal;
        }
        if (bodyless) {
            closeInput();
            deliver(c);
            return;
        }
        OutputStream out;
        try {
            out = ex.commit(c.status, c.headers, c.length);
        } catch (IOException | RuntimeException e) {
            abort(e);
            return;
        } catch (Error e) {
            abort(e);
            throw e;
        }
        synchronized (this) {
            if (state != ResponseState.COMMITTED) {
                return; // aborted meanwhile; abort closed the input
            }
            pendingOut = out;
            state = ResponseState.STREAMING;
            if (!handlerDone) {
                return; // the Dispatcher runs or aborts the copy when the handler finishes
            }
        }
        // Called from another thread after the handler finished: the Dispatcher already passed, so this call owns it.
        Throwable failure;
        synchronized (this) {
            failure = handlerFailure;
        }
        if (failure != null) {
            abort(failure);
        } else {
            runTransfer();
        }
    }

    /** Copies the pending stream to the exchange in 8192-byte reads; never throws to the adapter. */
    // @req FR-HTTP-014
    // @req FR-HTTP-016
    public void runTransfer() {
        InputStream in;
        OutputStream out;
        synchronized (this) {
            if (state != ResponseState.STREAMING || pendingIn == null || pendingOut == null) {
                return;
            }
            in = pendingIn;
            out = pendingOut;
            pendingOut = null; // claims the copy: a second runTransfer finds nothing to run
        }
        try {
            byte[] buf = new byte[CHUNK];
            int n;
            while ((n = in.read(buf, 0, buf.length)) != -1) {
                if (state() != ResponseState.STREAMING) {
                    return; // aborted meanwhile
                }
                out.write(buf, 0, n);
            }
            ex.finish();
            synchronized (this) {
                if (state == ResponseState.STREAMING) {
                    state = ResponseState.COMPLETED;
                }
            }
        } catch (IOException | RuntimeException e) {
            abort(e);
        } catch (Error e) {
            abort(e);
            throw e;
        } finally {
            closeInput();
        }
    }

    // @req FR-HTTP-015
    @Override
    public synchronized boolean isCommitted() {
        return committed;
    }

    /** No-op once COMPLETED or ABORTED; otherwise ABORTED, the exchange aborted and a pending stream closed. */
    // @req FR-HTTP-015
    @Override
    public void abort(@Nullable Throwable error) {
        // shortcut: error is not kept; record it when wave 4 onError needs the cause.
        synchronized (this) {
            if (state == ResponseState.COMPLETED || state == ResponseState.ABORTED) {
                return;
            }
            state = ResponseState.ABORTED;
        }
        ex.abort();
        closeInput();
    }

    // @req FR-HTTP-016
    public synchronized ResponseState state() {
        return state;
    }

    /** True for status 204 or 304 and for a HEAD request: no body bytes are written. */
    // @req FR-HTTP-017
    public synchronized boolean bodyless() {
        return status == 204 || status == 304 || headRequest;
    }

    /**
     * The Dispatcher's rule after the handler returned: NEW ends as {@link #end} does, STREAMING runs the copy.
     * Deciding and leaving NEW happen under one lock, so a late write from another thread cannot commit twice.
     */
    // @req FR-HTTP-016
    void afterHandler() {
        Commit c;
        synchronized (this) {
            handlerDone = true;
            if (state == ResponseState.STREAMING) {
                c = null;
            } else if (state == ResponseState.NEW) {
                c = prepare(EMPTY, null);
            } else {
                return;
            }
        }
        if (c == null) {
            runTransfer();
        } else {
            deliver(c);
        }
    }

    /**
     * The Dispatcher's rule after the handler threw: NEW drops the status and headers set so far and sends 500 with
     * only Content-Length: 0, STREAMING aborts, COMMITTED, COMPLETED and ABORTED are left to the writer.
     */
    // @req FR-HTTP-016
    void afterFailure(Throwable error) {
        Commit c;
        synchronized (this) {
            handlerDone = true;
            handlerFailure = error;
            if (state == ResponseState.STREAMING) {
                c = null;
            } else if (state == ResponseState.NEW) {
                status = 500;
                headers.clear();
                c = prepare(EMPTY, null);
            } else {
                return;
            }
        }
        if (c == null) {
            abort(error);
        } else {
            deliver(c);
        }
    }

    /** Commit, write unless bodyless, finish; a HEAD response keeps the Content-Length the body would have had. */
    // @req FR-HTTP-014
    // @req FR-HTTP-017
    private void write(byte[] body, @Nullable String defaultType) {
        Commit c;
        synchronized (this) {
            requireNew();
            c = prepare(body, defaultType);
        }
        deliver(c);
    }

    /** Sets the framing headers (none for 204 and 304) and leaves NEW; the caller holds the lock. */
    private Commit prepare(byte[] body, @Nullable String defaultType) {
        if (defaultType != null && !hasHeader("Content-Type")) {
            put("Content-Type", defaultType);
        }
        remove("Content-Length");
        remove("Transfer-Encoding");
        if (status != 204 && status != 304) {
            put("Content-Length", String.valueOf(body.length));
        }
        // HEAD: commit length 0 (no body bytes) while the header keeps the GET length, per HttpExchange.commit.
        return bodyless() ? begin(0, EMPTY) : begin(body.length, body);
    }

    /** NEW to COMMITTED with a snapshot of status and headers; the caller holds the lock. */
    private Commit begin(long length, byte[] body) {
        committed = true;
        state = ResponseState.COMMITTED;
        return new Commit(status, new ArrayList<>(headers), length, body);
    }

    /** Runs the exchange calls of a prepared commit outside the lock; COMMITTED to COMPLETED, or ABORTED. */
    private void deliver(Commit c) {
        try {
            OutputStream out = ex.commit(c.status, c.headers, c.length);
            if (c.body.length > 0) {
                out.write(c.body);
            }
            ex.finish();
        } catch (IOException | RuntimeException e) {
            abort(e);
            return;
        } catch (Error e) {
            abort(e);
            throw e;
        }
        synchronized (this) {
            if (state == ResponseState.COMMITTED) {
                state = ResponseState.COMPLETED;
            }
        }
    }

    /** Closes the stream's input at most once, whichever of success, failure or abort gets here first. */
    private void closeInput() {
        InputStream in;
        synchronized (this) {
            in = pendingIn;
        }
        if (in != null && inputClosed.compareAndSet(false, true)) {
            closeQuietly(in);
        }
    }

    private void requireNew() {
        if (state != ResponseState.NEW) {
            throw notNew();
        }
    }

    private IllegalStateException notNew() {
        return new IllegalStateException("response is " + state);
    }

    private static IllegalArgumentException badHeader() {
        return new IllegalArgumentException("bad header: name not a token, or CR, LF or a control char in the value");
    }

    private void put(String name, String value) {
        remove(name);
        headers.add(new SimpleImmutableEntry<>(name, value));
    }

    private void remove(String name) {
        for (Iterator<Map.Entry<String, String>> it = headers.iterator(); it.hasNext();) {
            if (it.next().getKey().equalsIgnoreCase(name)) {
                it.remove();
            }
        }
    }

    private boolean hasHeader(String name) {
        for (Map.Entry<String, String> h : headers) {
            if (h.getKey().equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /** RFC 9110 token: one or more tchar. */
    private static boolean isToken(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean alnum = c >= '0' && c <= '9' || c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z';
            if (!alnum && "!#$%&'*+-.^_`|~".indexOf(c) < 0) {
                return false;
            }
        }
        return true;
    }

    /** No control character (CR, LF, NUL, DEL, ...) except HTAB. */
    private static boolean isFieldValue(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 && c != '\t' || c == 0x7F) {
                return false;
            }
        }
        return true;
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // nothing left to do with a source we were closing anyway
        }
    }

    /** Status, header snapshot, commit length and the body bytes to write, fixed when leaving NEW. */
    private static final class Commit {
        final int status;
        final List<Map.Entry<String, String>> headers;
        final long length;
        final byte[] body;

        Commit(int status, List<Map.Entry<String, String>> headers, long length, byte[] body) {
            this.status = status;
            this.headers = headers;
            this.length = length;
            this.body = body;
        }
    }
}
