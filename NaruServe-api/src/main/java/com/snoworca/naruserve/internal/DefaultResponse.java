package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.Cookie;
import com.snoworca.naruserve.HttpException;
import com.snoworca.naruserve.Response;
import com.snoworca.naruserve.spi.HttpExchange;
import com.snoworca.naruserve.spi.JsonCodec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;

/**
 * {@link Response} over one exchange. Leaving NEW is decided under the lock, once, and the status and headers are
 * snapshotted there; every exchange call (commit, body write, finish, abort) runs outside the lock so abort never
 * waits on I/O. A copy may outlive the handler (D-103); the adapter keeps the exchange open until the core finishes
 * or aborts it, and a concurrent abort makes the copy's next write fail. An exchange failure ends the response ABORTED instead of throwing, as Express ignores a dead socket
 * on send (UncheckedIOException is also API 24+).
 */
// @req FR-HTTP-013
// @req FR-HTTP-014
// @req FR-HTTP-015
// @req FR-HTTP-016
// @req FR-HTTP-017
// @req FR-HTTP-018
// @req FR-JSON-002
// @req FR-COOKIE-001
public final class DefaultResponse implements Response {

    private static final int CHUNK = 8192;
    private static final byte[] EMPTY = new byte[0];

    private final HttpExchange ex;
    private final boolean headRequest;
    private final @Nullable JsonCodec codec;
    private final AtomicBoolean inputClosed = new AtomicBoolean();
    private final AtomicLong bytesWritten = new AtomicLong();

    // Guarded by this.
    private ResponseState state = ResponseState.NEW;
    private boolean committed;
    private int status = 200;
    private final List<Map.Entry<String, String>> headers = new ArrayList<>();
    private @Nullable InputStream pendingIn;
    private @Nullable OutputStream pendingOut;
    private boolean copying;
    private boolean handlerDone;
    private @Nullable Throwable handlerFailure;
    private @Nullable Runnable onDone;
    private boolean doneFired;

    /** Status 200, no headers, state NEW; {@code headRequest} makes every body bodyless. */
    // @req FR-HTTP-013
    public DefaultResponse(HttpExchange ex, boolean headRequest) {
        this(ex, headRequest, null);
    }

    /** As above, with the app codec {@link #json} uses; no codec refuses json. */
    // @req FR-JSON-002
    public DefaultResponse(HttpExchange ex, boolean headRequest, @Nullable JsonCodec codec) {
        this.ex = ex;
        this.headRequest = headRequest;
        this.codec = codec;
    }

    // @req FR-HTTP-013
    // @req FR-HTTP-016
    @Override
    public synchronized Response status(int code) {
        if (code < 100 || code > 999) {
            throw new IllegalArgumentException("status " + code + " is outside 100..999");
        }
        requireNew();
        status = code;
        return this;
    }

    /**
     * Replaces a same-name header (case-insensitive), as Express res.set does. The name must be an RFC 9110 token
     * and the value may hold no control character but HTAB (so no CR or LF) and no char above U+00FF.
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

    /** Appends one Set-Cookie line, never replacing an earlier one; nothing is added when validation throws. */
    // @req FR-COOKIE-001
    @Override
    public synchronized Response addCookie(Cookie cookie) {
        Objects.requireNonNull(cookie, "cookie");
        requireNew();
        String line = CookieSerializer.serialize(cookie);
        if (!isFieldValue(line)) {
            throw badHeader(); // a char above U+00FF in path or domain, same rule as res.header
        }
        headers.add(new SimpleImmutableEntry<>("Set-Cookie", line));
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

    /**
     * Refuses a response that left NEW before calling the codec, then encodes {@code value} into a buffer and sends it
     * in one commit: a codec failure throws with the response still NEW, so the error path answers 500 and no partial
     * output is sent.
     */
    // @req FR-JSON-002
    @Override
    public void json(@Nullable Object value) {
        JsonCodec c = codec;
        if (c == null) {
            throw new IllegalStateException("this response has no JsonCodec; build the app with RestApp.builder()");
        }
        synchronized (this) {
            requireNew();
        }
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try {
            Writer w = new OutputStreamWriter(buf, StandardCharsets.UTF_8);
            c.encode(value, w);
            w.flush();
        } catch (IOException e) {
            // A buffer write never fails, so this is the codec's own failure: a server error.
            throw new HttpException(500, "JSON encoding failed", e);
        }
        write(buf.toByteArray(), "application/json; charset=UTF-8");
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
            copying = true;
        }
        try {
            byte[] buf = new byte[CHUNK];
            int n;
            while ((n = in.read(buf, 0, buf.length)) != -1) {
                if (state() != ResponseState.STREAMING) {
                    return; // aborted meanwhile
                }
                out.write(buf, 0, n);
                bytesWritten.addAndGet(n);
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
            synchronized (this) {
                copying = false;
            }
            fireDone();
        }
    }

    /** Body bytes handed to the exchange OutputStream; 0 for HEAD and bodyless responses. */
    // @req FR-APP-015
    long bytesWritten() {
        return bytesWritten.get();
    }

    /** The committed status, or the current status when never committed. */
    // @req FR-APP-015
    synchronized int statusCode() {
        return status;
    }

    /**
     * Runs {@code callback} once, on the first entry to COMPLETED or ABORTED and only after a running copy ended;
     * at once when that already happened.
     */
    // @req FR-APP-015
    void onDone(Runnable callback) {
        synchronized (this) {
            onDone = callback;
        }
        fireDone();
    }

    private void fireDone() {
        Runnable cb;
        synchronized (this) {
            boolean ended = state == ResponseState.COMPLETED || state == ResponseState.ABORTED;
            if (!ended || copying || doneFired || onDone == null) {
                return;
            }
            doneFired = true;
            cb = onDone;
        }
        cb.run();
    }

    // @req FR-HTTP-015
    @Override
    public synchronized boolean isCommitted() {
        return committed;
    }

    /**
     * No-op once COMPLETED or ABORTED; otherwise ABORTED, the exchange aborted and a pending stream closed. Safe from
     * any thread: a running copy keeps the input and closes it itself once its read returns, so the input is never
     * closed under a read.
     */
    // @req FR-HTTP-015
    @Override
    public void abort(@Nullable Throwable error) {
        boolean copyRunning;
        synchronized (this) {
            if (state == ResponseState.COMPLETED || state == ResponseState.ABORTED) {
                return;
            }
            state = ResponseState.ABORTED;
            copyRunning = copying;
        }
        ex.abort();
        if (!copyRunning) {
            closeInput();
        }
        fireDone(); // a running copy fires it when it ends
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
     * The error path's outcome, decided by the state read once under the lock after the onError chain: NEW drops the
     * status and headers set so far and sends {@code status} with the JSON {@code body} (bodyless for HEAD),
     * STREAMING and COMMITTED abort, COMPLETED and ABORTED stay unchanged.
     */
    // @req FR-HTTP-016
    // @req FR-APP-005
    // @req FR-HTTP-024
    void afterFailure(int status, byte[] body, Throwable error) {
        Commit c;
        synchronized (this) {
            handlerDone = true;
            handlerFailure = error;
            if (state == ResponseState.STREAMING || state == ResponseState.COMMITTED) {
                c = null;
            } else if (state == ResponseState.NEW) {
                this.status = status;
                headers.clear();
                put("Content-Type", StandardErrors.JSON_TYPE);
                if (status == 408) {
                    put("Connection", "close"); // FR-ENGINE-005 AC-2: the adapter closes after a 408
                }
                c = prepare(body, null);
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
                bytesWritten.addAndGet(c.body.length);
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
        fireDone();
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
        return new IllegalArgumentException("bad header: name not a token, or CR, LF, a control char or a char above U+00FF in the value");
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

    /** RFC 9110 token: one or more tchar; the cookie parser shares it. */
    static boolean isToken(String s) {
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

    /**
     * No control character (CR, LF, NUL, DEL, ...) except HTAB, and no char above U+00FF: an adapter writing the
     * low byte would turn U+010A into LF (cf. Node CVE-2018-12116), so headers stay ISO-8859-1, one byte per char.
     */
    private static boolean isFieldValue(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 && c != '\t' || c == 0x7F || c > 0xFF) {
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
