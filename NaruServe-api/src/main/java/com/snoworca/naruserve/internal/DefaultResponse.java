package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.CachePolicy;
import com.snoworca.naruserve.ConsoleLogger;
import com.snoworca.naruserve.Cookie;
import com.snoworca.naruserve.HttpException;
import com.snoworca.naruserve.Method;
import com.snoworca.naruserve.ResourceResolver;
import com.snoworca.naruserve.Response;
import com.snoworca.naruserve.SseClient;
import com.snoworca.naruserve.spi.HttpExchange;
import com.snoworca.naruserve.spi.JsonCodec;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.ArrayList;
import java.util.Collections;
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
// @req IR-SSE-001
// @req FR-SSE-001
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
    private final List<Map.Entry<String, String>> corsHeaders = new ArrayList<>();
    private @Nullable InputStream pendingIn;
    private @Nullable OutputStream pendingOut;
    private boolean copying;
    private boolean handlerDone;
    private @Nullable Throwable handlerFailure;
    private @Nullable Runnable onDone;
    private boolean doneFired;
    private @Nullable DefaultMimeTypes mimeTypes;
    private FileResponder.@Nullable ResolverTap tap;
    private @Nullable DefaultRequest request;
    private @Nullable CachePolicy defaultCache;
    /** Bytes the pending copy must deliver; -1 copies to the end of the input. */
    private long pendingLimit = -1;
    private @Nullable SseRegistry sseRegistry;
    private @Nullable DefaultSseClient sseClient;
    private boolean upgraded101;

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

    /** The app's MIME registry for {@link #file}; unset means the built-in table only. */
    void mimeTypes(DefaultMimeTypes mimeTypes) {
        this.mimeTypes = mimeTypes;
    }

    /** The app's test hook for the resolver {@link #file} and {@link #download} read through. */
    void resolverTap(FileResponder.@Nullable ResolverTap tap) {
        this.tap = tap;
    }

    /** The app's SSE registry for {@link #sse}; unset means a registry of the default options. */
    // @req IR-SSE-001
    void sseRegistry(SseRegistry registry) {
        this.sseRegistry = registry;
    }

    /**
     * Commits 200 with the event-stream headers, replacing an app-set Content-Type and Cache-Control and dropping
     * Content-Length and Transfer-Encoding, and enters STREAMING. A HEAD request gets the headers with no body and an
     * ended client. A RuntimeException from the exchange (a 503 from a stream limit) leaves the response NEW and is
     * rethrown for the error path; an IOException aborts the response and returns an ended client. After the
     * registry's shutdown flag the client is ended at once with the closing notice queued.
     */
    // @req IR-SSE-001
    // @req FR-SSE-001
    // @req FR-APP-020
    @Override
    public SseClient sse() {
        SseRegistry reg;
        Commit head = null;
        List<Map.Entry<String, String>> hs;
        DefaultSseClient client = null;
        int oldStatus;
        List<Map.Entry<String, String>> oldHeaders;
        DefaultRequest req = request;
        String rid = req != null ? req.requestId() : "";
        synchronized (this) {
            requireNew();
            oldStatus = status;
            oldHeaders = new ArrayList<>(headers);
            status = 200;
            put("Content-Type", "text/event-stream; charset=UTF-8");
            put("Cache-Control", "no-cache, no-transform");
            remove("Content-Length");
            remove("Transfer-Encoding");
            hs = new ArrayList<>(headers);
            if (sseRegistry == null) {
                sseRegistry = new SseRegistry(new DefaultSseOptions(), null, new SafeLogger(new ConsoleLogger()));
            }
            reg = sseRegistry;
            if (headRequest) {
                head = begin(0, EMPTY);
            } else {
                committed = true;
                state = ResponseState.STREAMING;
                // Set under the same lock, so an abort from another thread from here on ends this client.
                client = new DefaultSseClient(this, reg, Thread.currentThread(), rid);
                sseClient = client;
            }
        }
        if (client == null) {
            deliver(Objects.requireNonNull(head));
            return DefaultSseClient.ended(rid, reg.log());
        }
        if (!reg.register(client)) {
            // Past the shutdown flag: the stream still starts, carrying only the closing notice, and then ends.
            client.closing();
        }
        try {
            ex.eventStream(200, hs, client);
        } catch (RuntimeException e) {
            reg.remove(client);
            boolean reopened = false;
            synchronized (this) {
                if (state == ResponseState.STREAMING && !handlerDone) {
                    sseClient = null;
                    state = ResponseState.NEW;
                    committed = false;
                    status = oldStatus; // an app that catches the refusal answers with its own status and headers
                    headers.clear();
                    headers.addAll(oldHeaders);
                    reopened = true;
                }
            }
            if (!reopened) {
                abort(e); // the Dispatcher already passed, so nothing else would end this response
            }
            throw e;
        } catch (IOException e) {
            reg.remove(client);
            client.endBy("client");
            abort(e);
            client.runListeners();
        } catch (Error e) {
            reg.remove(client);
            abort(e);
            throw e;
        }
        return client;
    }

    /**
     * The end of an SSE stream, called by its drain: STREAMING becomes COMPLETED through {@code ex.finish()}, or
     * ABORTED for a {@code failure}; either fires onDone.
     */
    // @req IR-SSE-001
    // @req FR-SSE-004
    void sseEnded(@Nullable Throwable failure) {
        if (failure != null) {
            abort(failure);
            return;
        }
        synchronized (this) {
            if (state != ResponseState.STREAMING) {
                return;
            }
        }
        try {
            ex.finish();
        } catch (IOException | RuntimeException e) {
            abort(e);
            return;
        }
        synchronized (this) {
            if (state == ResponseState.STREAMING) {
                state = ResponseState.COMPLETED;
            }
        }
        fireDone();
    }

    /** The stream of a non-HEAD {@link #sse}, or null. */
    // @req FR-SSE-004
    synchronized @Nullable DefaultSseClient sseClient() {
        return sseClient;
    }

    /** Adds SSE bytes written by the client to {@link #bytesWritten}. */
    // @req FR-SSE-002
    void addWritten(long n) {
        bytesWritten.addAndGet(n);
    }

    /** The request whose method and conditional headers {@link #file} and {@link #download} evaluate. */
    void request(DefaultRequest request) {
        this.request = request;
    }

    /** The app's {@code defaultCache}: at commit it becomes Cache-Control when the response holds none. */
    // @req FR-STATIC-008
    synchronized void applyDefaultCache(@Nullable CachePolicy policy) {
        this.defaultCache = policy;
    }

    /** Replaces Cache-Control; the last call before commit wins. */
    // @req FR-STATIC-008
    @Override
    public Response cache(CachePolicy policy) {
        return header("Cache-Control", Objects.requireNonNull(policy, "policy").getHeaderValue());
    }

    /**
     * Sends {@code file} like a static file, with no Content-Disposition; a symbolic link, directory or missing file
     * throws {@link HttpException} 404 for the error path.
     */
    // @req FR-STATIC-006
    // @req FR-STATIC-011
    @Override
    public void file(File file) {
        answerFile(Objects.requireNonNull(file, "file"), null);
    }

    /** As {@link #file}, with {@code Content-Disposition: attachment} naming the sanitised {@code filename}. */
    // @req FR-STATIC-011
    @Override
    public void download(File file, String filename) {
        Objects.requireNonNull(filename, "filename");
        answerFile(Objects.requireNonNull(file, "file"), disposition(filename));
    }

    // @req FR-STATIC-011
    private void answerFile(File file, @Nullable String disposition) {
        DefaultMimeTypes m = mimeTypes != null ? mimeTypes : new DefaultMimeTypes(new AppLifecycle());
        String type = m.lookup(file.getName(), Collections.<String, String>emptyMap());
        DefaultRequest req = request;
        if (req == null) {
            Method method;
            try {
                method = Method.valueOf(ex.methodToken());
            } catch (IllegalArgumentException e) {
                method = Method.GET;
            }
            req = new DefaultRequest(ex, headRequest ? Method.HEAD : method, Collections.<String, String>emptyMap());
        }
        try {
            ResourceResolver r = FileResponder.tapped(FileResourceResolver.forFile(file), tap);
            if (disposition != null) {
                header("Content-Disposition", disposition);
            }
            FileResponder.respond(req, this, r, file.getName(), type, null);
        } catch (FileNotFoundException e) {
            dropDisposition(disposition);
            throw new HttpException(404, null, e);
        } catch (IOException e) {
            dropDisposition(disposition);
            throw new HttpException(500, null, e);
        }
    }

    /**
     * A file body that failed before the commit keeps none of its validator, range and cache headers, so an onError
     * answer does not carry them; {@code cache} also drops the Cache-Control the file answer set.
     */
    // @req FR-STATIC-011
    synchronized void dropFileHeaders(boolean cache) {
        if (state == ResponseState.NEW) {
            remove("ETag");
            remove("Last-Modified");
            remove("Accept-Ranges");
            remove("Content-Range");
            if (cache) {
                remove("Cache-Control");
            }
        }
    }

    /** A failed download keeps no attachment header, so an onError answer is not saved as a file. */
    private synchronized void dropDisposition(@Nullable String disposition) {
        if (disposition != null && state == ResponseState.NEW) {
            remove("Content-Disposition");
        }
    }

    /**
     * {@code attachment; filename="..."} with CR, LF, '"', '\\' and other controls dropped and non-ASCII replaced by
     * '_', plus {@code filename*=UTF-8''} percent-encoding when the name holds non-ASCII.
     */
    // @req FR-STATIC-011
    public static String disposition(String filename) {
        StringBuilder clean = new StringBuilder();
        StringBuilder ascii = new StringBuilder();
        boolean nonAscii = false;
        for (int i = 0; i < filename.length(); i++) {
            char c = filename.charAt(i);
            if (Character.isISOControl(c) || c == '"' || c == '\\') {
                continue;
            }
            clean.append(c);
            if (c > 0x7E) {
                nonAscii = true;
                if (Character.isHighSurrogate(c)) {
                    continue; // one '_' for the pair, written at its low surrogate
                }
                ascii.append('_');
            } else {
                ascii.append(c);
            }
        }
        String v = "attachment; filename=\"" + ascii + "\"";
        if (!nonAscii) {
            return v;
        }
        return v + "; filename*=UTF-8''" + PercentDecoder.percentEncode(clean.toString(), "!#$&+-.^_`|~");
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
        checkHeader(name, value);
        put(name, value);
        return this;
    }

    /** Records only framework CORS grants, which the standard error response must keep. */
    // @req FR-DX-010
    synchronized void corsHeader(String name, String value) {
        header(name, value);
        for (Iterator<Map.Entry<String, String>> it = corsHeaders.iterator(); it.hasNext();) {
            if (it.next().getKey().equalsIgnoreCase(name)) it.remove();
        }
        corsHeaders.add(new SimpleImmutableEntry<>(name, value));
    }

    /** A framework cache dimension, also restored if normal error handling clears application headers. */
    // @req FR-DX-010
    synchronized void corsVary(String field) {
        vary(field);
        String dimensions = null;
        for (Map.Entry<String, String> header : corsHeaders) {
            if (header.getKey().equalsIgnoreCase("Vary")) dimensions = header.getValue();
        }
        // Only framework dimensions survive; unrelated application Vary fields are dropped on error.
        if (dimensions != null) {
            for (String token : dimensions.split(",")) {
                if (field.equalsIgnoreCase(token.trim())) return;
            }
        }
        String value = dimensions == null ? field : dimensions + ", " + field;
        for (Iterator<Map.Entry<String, String>> it = corsHeaders.iterator(); it.hasNext();) {
            if (it.next().getKey().equalsIgnoreCase("Vary")) it.remove();
        }
        corsHeaders.add(new SimpleImmutableEntry<>("Vary", value));
    }

    /** Adds a Vary field without replacing existing cache dimensions or Vary: *. */
    // @req FR-DX-010
    synchronized void vary(String field) {
        requireNew();
        checkHeader(field, "");
        String existing = null;
        for (Map.Entry<String, String> header : headers) {
            if (!header.getKey().equalsIgnoreCase("Vary")) continue;
            existing = header.getValue();
            for (String token : existing.split(",")) {
                if ("*".equals(token.trim()) || field.equalsIgnoreCase(token.trim())) return;
            }
        }
        put("Vary", existing == null || existing.isEmpty() ? field : existing + ", " + field);
    }

    /**
     * The {@code res.header} check, shared with {@code HttpException.withHeader}: {@link NullPointerException} for a
     * null name or value, {@link IllegalArgumentException} for a non-token name or a bad value char. Not API.
     */
    // @req FR-HTTP-015
    // @req IR-HTTP-008
    public static void checkHeader(String name, String value) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        if (!isToken(name) || !isFieldValue(value)) {
            throw badHeader();
        }
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
    // @req FR-DX-002
    public void send(@Nullable Object body) {
        if (body == null) {
            end();
        } else if (body instanceof String) {
            write(((String) body).getBytes(StandardCharsets.UTF_8), "text/plain; charset=UTF-8");
        } else if (body instanceof byte[]) {
            write((byte[]) body, "application/octet-stream");
        } else {
            json(body);
        }
    }

    // @req FR-HTTP-014
    @Override
    public void html(String body) {
        write(body.getBytes(StandardCharsets.UTF_8), "text/html; charset=UTF-8");
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
        write(buf.toByteArray(), StandardErrors.JSON_TYPE);
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
        if (contentType == null) { // only the internal sendBody path passes no type
            closeQuietly(in);
            throw new NullPointerException("contentType");
        }
        startBody(in, 0, contentType, -1);
    }

    /**
     * A file body: skips {@code skip} bytes of {@code in} (looping over {@link InputStream#skip}, then reading),
     * commits {@code status} with Content-Length {@code length} and copies exactly {@code length} bytes in 8192-byte
     * reads like {@link #stream}; never {@link HttpExchange#sendFile}. Closes {@code in}. A skip that reaches the
     * end of {@code in} throws {@link IOException} before the commit; a stream whose skip passes its end, or that ends
     * inside the body, aborts the response after the commit.
     */
    // @req FR-STATIC-003
    // @req FR-STATIC-011
    public void sendBody(int status, InputStream in, long skip, long length) throws IOException {
        try {
            for (long left = skip; left > 0; ) {
                long k = in.skip(left);
                if (k <= 0) {
                    if (in.read() < 0) {
                        throw new IOException("the file ends before byte " + skip);
                    }
                    k = 1;
                }
                left -= k;
            }
        } catch (IOException | RuntimeException e) {
            closeQuietly(in);
            throw e;
        }
        startBody(in, status, null, length);
    }

    /** Commits for a body copied from {@code in}: {@code length} -1 is unknown; status 0 keeps the current one. */
    private void startBody(InputStream in, int newStatus, @Nullable String contentType, long length) {
        RuntimeException refusal;
        Commit c = null;
        boolean bodyless = false;
        synchronized (this) {
            refusal = state != ResponseState.NEW ? notNew()
                    : contentType != null && !isFieldValue(contentType) ? badHeader() : null;
            if (refusal == null) {
                if (newStatus != 0) {
                    status = newStatus;
                }
                pendingIn = in;
                pendingLimit = length;
                if (contentType != null) {
                    put("Content-Type", contentType);
                }
                remove("Content-Length"); // an unknown length leaves the framing to the adapter
                remove("Transfer-Encoding");
                if (length >= 0) {
                    put("Content-Length", String.valueOf(length));
                }
                bodyless = bodyless();
                c = begin(bodyless ? 0 : length, EMPTY);
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
        long left;
        synchronized (this) {
            if (state != ResponseState.STREAMING || pendingIn == null || pendingOut == null) {
                return;
            }
            in = pendingIn;
            out = pendingOut;
            left = pendingLimit;
            pendingOut = null; // claims the copy: a second runTransfer finds nothing to run
            copying = true;
        }
        try {
            byte[] buf = new byte[CHUNK];
            while (left != 0) {
                int n = in.read(buf, 0, left < 0 ? buf.length : (int) Math.min(buf.length, left));
                if (n == -1) {
                    if (left > 0) {
                        throw new IOException("the source ended " + left + " bytes before the committed length");
                    }
                    break;
                }
                if (state() != ResponseState.STREAMING) {
                    return; // aborted meanwhile
                }
                out.write(buf, 0, n);
                bytesWritten.addAndGet(n);
                if (left > 0) {
                    left -= n;
                }
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
    // @req FR-APP-019
    long bytesWritten() {
        return bytesWritten.get();
    }

    /** The committed status, or the current status when never committed. */
    // @req FR-APP-019
    synchronized int statusCode() {
        return status;
    }

    /**
     * Runs {@code callback} once, on the first entry to COMPLETED or ABORTED and only after a running copy ended;
     * at once when that already happened.
     */
    // @req FR-APP-019
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
     * closed under a read. An SSE stream ends with cause error, and runs its listeners here when no drain started.
     */
    // @req FR-HTTP-015
    // @req FR-SSE-004
    @Override
    public void abort(@Nullable Throwable error) {
        boolean copyRunning;
        DefaultSseClient sse;
        synchronized (this) {
            if (state == ResponseState.COMPLETED || state == ResponseState.ABORTED) {
                return;
            }
            state = ResponseState.ABORTED;
            copyRunning = copying;
            sse = sseClient;
        }
        if (sse != null) {
            sse.endBy("error"); // wakes a waiting drain, which writes what is queued and ends
        }
        ex.abort();
        if (!copyRunning) {
            closeInput();
        }
        fireDone(); // a running copy fires it when it ends
        if (sse != null && !sse.drainStarted()) {
            sse.runListeners(); // no drain will run them; a later drain call returns at once
        }
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
     * End state of an upgraded exchange, after {@code HttpExchange.webSocket} returned: NEW becomes COMPLETED with
     * status 101 and the exchange is finished once, without a commit (the adapter wrote the 101). A finish that fails
     * because the connection is already gone aborts instead. No-op when the response already left NEW.
     */
    // @req FR-WS-001
    void upgraded() {
        synchronized (this) {
            if (state != ResponseState.NEW) {
                return;
            }
            handlerDone = true;
            status = 101;
            committed = true;
            state = ResponseState.COMPLETED;
            upgraded101 = true;
        }
        try {
            ex.finish();
        } catch (IOException | RuntimeException e) {
            synchronized (this) {
                state = ResponseState.ABORTED;
            }
            ex.abort();
        }
        fireDone();
    }

    /** True once {@link #upgraded} ended this response: the session writes its own access lines. */
    // @req FR-APP-019
    synchronized boolean upgraded101() {
        return upgraded101;
    }

    /**
     * The error path's outcome, decided by the state read once under the lock after the onError chain: NEW drops the
     * status and headers set so far and sends {@code status} with the JSON {@code body} (bodyless for HEAD),
     * STREAMING and COMMITTED abort, COMPLETED and ABORTED stay unchanged.
     */
    // @req FR-HTTP-016
    // @req FR-APP-005
    // @req FR-HTTP-024
    // @req IR-HTTP-008
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
                if (error instanceof HttpException) {
                    // prepare() then drops any Content-Length or Transfer-Encoding among them
                    List<Map.Entry<String, String>> extra = ((HttpException) error).headers();
                    for (Map.Entry<String, String> h : extra) {
                        remove(h.getKey()); // replaces like Express setHeader; the exception's own duplicates stay
                    }
                    headers.addAll(extra);
                }
                // HTTP exceptions and ordinary application headers cannot replace framework CORS decisions.
                for (Map.Entry<String, String> header : corsHeaders) put(header.getKey(), header.getValue());
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
        CachePolicy fallback = defaultCache;
        if (fallback != null && !hasHeader("Cache-Control")) {
            put("Cache-Control", fallback.getHeaderValue()); // FR-STATIC-008 AC-5: whatever set it, a value stays
        }
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

    /** RFC 9110 token: one or more tchar; the cookie parser and {@code Cookie} share it. Not API. */
    public static boolean isToken(String s) {
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
    static boolean isFieldValue(String s) {
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
