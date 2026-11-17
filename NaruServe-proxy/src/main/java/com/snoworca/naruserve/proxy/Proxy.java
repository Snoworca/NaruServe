package com.snoworca.naruserve.proxy;

import com.snoworca.naruserve.BodyTooLargeException;
import com.snoworca.naruserve.HttpException;
import com.snoworca.naruserve.Middleware;
import com.snoworca.naruserve.Next;
import com.snoworca.naruserve.Request;
import com.snoworca.naruserve.Response;
import com.snoworca.naruserve.common.HttpHeaderValues;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLSocketFactory;
import org.jspecify.annotations.Nullable;

/**
 * A reverse proxy middleware that forwards each request to one backend origin and relays the response, like
 * {@code createProxyMiddleware({ target })} of Express's {@code http-proxy-middleware}:
 * {@code app.use("/api/orders", Proxy.to("http://127.0.0.1:8081"))}. A {@code Proxy} is immutable; every option
 * method returns a new {@code Proxy} and leaves its receiver unchanged, so one configured value can be shared by
 * several mounts.
 */
// @req IR-PROXY-001
public final class Proxy implements Middleware {

    private static final long DEFAULT_CONNECT_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(10);
    private static final long DEFAULT_WRITE_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(60);
    private static final long DEFAULT_READ_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(60);
    private static final long DEFAULT_TOTAL_TIMEOUT_NANOS = TimeUnit.MINUTES.toNanos(5);
    private static final long DEFAULT_RETRY_DELAY_NANOS = TimeUnit.MILLISECONDS.toNanos(200);
    /** {@link #bodyLimit()} when no {@code bodyLimit} is set: the app's raw body limit applies. */
    static final long NO_BODY_LIMIT = -1;
    /** {@link #maxConcurrent()} when no {@code maxConcurrent} is set: no per-mount limit. */
    static final int NO_CONCURRENCY_LIMIT = 0;
    /** The largest {@code bodyLimit}, the same 1 GiB bound as {@code app.rawBodyLimit}. */
    static final long MAX_BODY_LIMIT = 1073741824L;

    /** The longest status or header line the backend may send. */
    private static final int MAX_LINE = 64 * 1024;
    /** The most header lines one backend response head may hold. */
    private static final int MAX_HEADER_LINES = 1000;
    /** The most trailer lines one chunked backend response body may end with. */
    private static final int MAX_TRAILER_LINES = 1000;

    private final Target target;
    private final Options options;

    private Proxy(Target target, Options options) {
        this.target = target;
        this.options = options;
    }

    /**
     * A proxy to {@code url}, an {@code http} or {@code https} origin with an optional base path, for example
     * {@code Proxy.to("http://127.0.0.1:8081")}; every option has its default.
     */
    // @req IR-PROXY-001
    public static Proxy to(String url) {
        return new Proxy(Target.parse(url), Options.defaults());
    }

    /** The limit for the TCP connect to the backend (default 10 seconds). */
    // @req IR-PROXY-001
    public Proxy connectTimeout(long amount, TimeUnit unit) {
        return new Proxy(target, options.withConnectTimeoutNanos(positiveNanos(amount, unit, "connectTimeout")));
    }

    /** The limit for a backend write that makes no progress (default 60 seconds). */
    // @req IR-PROXY-001
    public Proxy writeTimeout(long amount, TimeUnit unit) {
        return new Proxy(target, options.withWriteTimeoutNanos(positiveNanos(amount, unit, "writeTimeout")));
    }

    /** The limit for the first response byte and between response bytes (default 60 seconds). */
    // @req IR-PROXY-001
    public Proxy readTimeout(long amount, TimeUnit unit) {
        return new Proxy(target, options.withReadTimeoutNanos(positiveNanos(amount, unit, "readTimeout")));
    }

    /** The limit for a whole proxied request, retries included (default 5 minutes). */
    // @req IR-PROXY-001
    public Proxy totalTimeout(long amount, TimeUnit unit) {
        return new Proxy(target, options.withTotalTimeoutNanos(positiveNanos(amount, unit, "totalTimeout")));
    }

    /** How many more attempts a failed request may get (default 0). */
    // @req IR-PROXY-001
    public Proxy retries(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("retries must be at least 0, got " + count);
        }
        return new Proxy(target, options.withRetries(count));
    }

    /** The wait before each retry (default 200 milliseconds). */
    // @req IR-PROXY-001
    public Proxy retryDelay(long amount, TimeUnit unit) {
        return new Proxy(target, options.withRetryDelayNanos(nonNegativeNanos(amount, unit, "retryDelay")));
    }

    /** Whether a backend 502 or 503 is retried for methods other than bodyless GET, HEAD and OPTIONS (default false). */
    // @req IR-PROXY-001
    public Proxy retryUnsafeMethods(boolean enabled) {
        return new Proxy(target, options.withRetryUnsafeMethods(enabled));
    }

    /** Whether the mount prefix is removed from the forwarded path (default false: the prefix is kept). */
    // @req IR-PROXY-001
    public Proxy stripPrefix(boolean enabled) {
        return new Proxy(target, options.withStripPrefix(enabled));
    }

    /** Whether the received {@code Host} is sent instead of the target host (default false). */
    // @req IR-PROXY-001
    public Proxy preserveHost(boolean enabled) {
        return new Proxy(target, options.withPreserveHost(enabled));
    }

    /** The request body limit in bytes for this mount (default: the app's raw body limit). */
    // @req IR-PROXY-001
    public Proxy bodyLimit(long bytes) {
        if (bytes < 1 || bytes > MAX_BODY_LIMIT) {
            throw new IllegalArgumentException("bodyLimit must be within 1.." + MAX_BODY_LIMIT + ", got " + bytes);
        }
        return new Proxy(target, options.withBodyLimit(bytes));
    }

    /** The most in-flight requests of this proxy and the proxies derived from it (default: no limit). */
    // @req IR-PROXY-001
    public Proxy maxConcurrent(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("maxConcurrent must be at least 1, got " + limit);
        }
        return new Proxy(target, options.withMaxConcurrent(limit));
    }

    /** Sends {@code value} as request header {@code name} in place of the client's lines of that name. */
    // @req IR-PROXY-001
    public Proxy requestHeader(String name, String value) {
        HeaderRules.checkRequestRule(name, value, "requestHeader");
        return new Proxy(target, options.withRequestHeaderRules(appended(options.requestHeaderRules, name, value)));
    }

    /** Drops the client's request header {@code name}. */
    // @req IR-PROXY-001
    public Proxy removeRequestHeader(String name) {
        HeaderRules.checkRequestRule(name, "removeRequestHeader");
        return new Proxy(target, options.withRequestHeaderRules(appended(options.requestHeaderRules, name, null)));
    }

    /** Sends {@code value} as response header {@code name} in place of the backend's lines of that name. */
    // @req IR-PROXY-001
    public Proxy responseHeader(String name, String value) {
        HeaderRules.checkResponseRule(name, value, "responseHeader");
        return new Proxy(target, options.withResponseHeaderRules(appended(options.responseHeaderRules, name, value)));
    }

    /** Drops the backend's response header {@code name}. */
    // @req IR-PROXY-001
    public Proxy removeResponseHeader(String name) {
        HeaderRules.checkResponseRule(name, "removeResponseHeader");
        return new Proxy(target, options.withResponseHeaderRules(appended(options.responseHeaderRules, name, null)));
    }

    /** Whether a same-origin backend {@code Location} is rewritten to a path under the mount (default true). */
    // @req IR-PROXY-001
    public Proxy rewriteLocation(boolean enabled) {
        return new Proxy(target, options.withRewriteLocation(enabled));
    }

    /**
     * The socket factory for an {@code https} target (default: the JDK default factory). An {@code http} target
     * accepts the factory and never uses it.
     */
    // @req IR-PROXY-001
    // @req FR-PROXY-002
    public Proxy sslSocketFactory(SSLSocketFactory factory) {
        if (factory == null) {
            throw new NullPointerException("sslSocketFactory factory");
        }
        return new Proxy(target, options.withSslSocketFactory(factory));
    }

    /** {@code amount} of {@code unit} in nanoseconds, saturated at Long.MAX_VALUE; the amount must be above 0. */
    // @req FR-PROXY-002
    private static long positiveNanos(long amount, TimeUnit unit, String option) {
        if (unit == null) {
            throw new NullPointerException(option + " unit");
        }
        if (amount <= 0) {
            throw new IllegalArgumentException(option + " must be above 0, got " + amount + " " + unit);
        }
        return unit.toNanos(amount);
    }

    /** {@code amount} of {@code unit} in nanoseconds, saturated at Long.MAX_VALUE; the amount must be at least 0. */
    // @req FR-PROXY-002
    private static long nonNegativeNanos(long amount, TimeUnit unit, String option) {
        if (unit == null) {
            throw new NullPointerException(option + " unit");
        }
        if (amount < 0) {
            throw new IllegalArgumentException(option + " must be at least 0, got " + amount + " " + unit);
        }
        return unit.toNanos(amount);
    }

    // @req IR-PROXY-001
    private static List<Map.Entry<String, @Nullable String>> appended(List<Map.Entry<String, @Nullable String>> rules,
            String name, @Nullable String value) {
        List<Map.Entry<String, @Nullable String>> out = new ArrayList<>(rules);
        out.add(new SimpleImmutableEntry<String, @Nullable String>(name, value));
        return Collections.unmodifiableList(out);
    }

    // Package-private accessors: the configured values, read by unit tests only (D-046).

    long connectTimeoutNanos() {
        return options.connectTimeoutNanos;
    }

    long writeTimeoutNanos() {
        return options.writeTimeoutNanos;
    }

    long readTimeoutNanos() {
        return options.readTimeoutNanos;
    }

    long totalTimeoutNanos() {
        return options.totalTimeoutNanos;
    }

    /** The connect timeout as the socket sees it: whole milliseconds, rounded up, within 1..Integer.MAX_VALUE. */
    // @req FR-PROXY-002
    int connectTimeoutMillis() {
        return socketMillis(options.connectTimeoutNanos);
    }

    /** The write timeout as the socket sees it: whole milliseconds, rounded up, within 1..Integer.MAX_VALUE. */
    // @req FR-PROXY-002
    int writeTimeoutMillis() {
        return socketMillis(options.writeTimeoutNanos);
    }

    /** The read timeout as the socket sees it: whole milliseconds, rounded up, within 1..Integer.MAX_VALUE. */
    // @req FR-PROXY-002
    int readTimeoutMillis() {
        return socketMillis(options.readTimeoutNanos);
    }

    int retries() {
        return options.retries;
    }

    long retryDelayNanos() {
        return options.retryDelayNanos;
    }

    boolean retryUnsafeMethods() {
        return options.retryUnsafeMethods;
    }

    boolean stripPrefix() {
        return options.stripPrefix;
    }

    boolean preserveHost() {
        return options.preserveHost;
    }

    /** The configured body limit, or {@link #NO_BODY_LIMIT}. */
    long bodyLimit() {
        return options.bodyLimit;
    }

    /** The configured concurrency limit, or {@link #NO_CONCURRENCY_LIMIT}. */
    int maxConcurrent() {
        return options.maxConcurrent;
    }

    /** The request header rules in call order; a null value removes the name. */
    List<Map.Entry<String, @Nullable String>> requestHeaderRules() {
        return options.requestHeaderRules;
    }

    /** The response header rules in call order; a null value removes the name. */
    List<Map.Entry<String, @Nullable String>> responseHeaderRules() {
        return options.responseHeaderRules;
    }

    boolean rewriteLocation() {
        return options.rewriteLocation;
    }

    @Nullable SSLSocketFactory sslSocketFactory() {
        return options.sslSocketFactory;
    }

    /** Every configured value as one string, so a test can see that an option call left its receiver unchanged. */
    // @req IR-PROXY-001
    String describe() {
        Options o = options;
        return "target=" + target.scheme + "://" + target.authority + target.basePath
                + " connect=" + o.connectTimeoutNanos + " write=" + o.writeTimeoutNanos + " read=" + o.readTimeoutNanos
                + " total=" + o.totalTimeoutNanos + " retries=" + o.retries + " retryDelay=" + o.retryDelayNanos
                + " retryUnsafeMethods=" + o.retryUnsafeMethods + " stripPrefix=" + o.stripPrefix
                + " preserveHost=" + o.preserveHost + " bodyLimit=" + o.bodyLimit + " maxConcurrent=" + o.maxConcurrent
                + " requestHeaders=" + o.requestHeaderRules + " responseHeaders=" + o.responseHeaderRules
                + " rewriteLocation=" + o.rewriteLocation + " sslSocketFactory="
                + (o.sslSocketFactory == null ? "default" : Integer.toHexString(System.identityHashCode(o.sslSocketFactory)));
    }

    /**
     * Forwards the request to the backend over a new connection and hands the response body to {@code res.relay},
     * which owns the backend socket from then on; never calls {@code next.run()}. A backend that cannot be reached
     * or answers with a response this proxy cannot read gives 502 through the error flow; a failure while reading
     * the client body is the client's and reaches the error flow as itself, an over-limit body as 413.
     */
    // @req IR-PROXY-001
    @Override
    public void handle(Request req, Response res, Next next) throws Exception {
        if (!"http".equals(target.scheme)) {
            // https backends arrive with the TLS requirement (SEC-PROXY-005); until then they are not reached.
            throw new HttpException(502);
        }
        try {
            forward(req, res, prepare(req));
        } catch (ClientBodyFailure f) {
            throw f.forErrorFlow();
        }
    }

    // @req IR-PROXY-001
    // @req FR-PROXY-011
    private void forward(Request req, Response res, Outgoing outgoing) {
        Socket socket = new Socket();
        boolean relayed = false;
        try {
            socket.connect(new InetSocketAddress(target.host, target.port), connectTimeoutMillis());
            socket.setSoTimeout(readTimeoutMillis());
            OutputStream out = new BufferedOutputStream(socket.getOutputStream());
            out.write(outgoing.head);
            if (outgoing.bufferedBody != null) {
                out.write(outgoing.bufferedBody);
            } else if (outgoing.bodyStream != null) {
                copyClientBody(outgoing.bodyStream, out, outgoing.contentLength);
            }
            out.flush();
            InputStream in = new BufferedInputStream(socket.getInputStream());
            ResponseHead head = ResponseHead.read(in);
            boolean bodyless = "HEAD".equals(req.methodToken()) || head.status == 204 || head.status == 304;
            boolean chunked = head.transferEncoding != null && !bodyless;
            if (chunked && !head.chunkedLast) {
                // A body framed by another final coding is refused by the response parser rules (SEC-PROXY-004).
                throw new HttpException(502);
            }
            // The adapter writes its own reason phrase for the status (D-059); the backend phrase is not kept.
            res.status(head.status);
            ResponseHeaderCopier.copy(head.headers, res);
            long length = head.status == 204 || head.transferEncoding != null ? -1 : head.contentLength;
            InputStream body = chunked ? new ChunkedBodyStream(in, socket) : new SocketBodyStream(in, socket);
            res.relay(body, length);
            relayed = true;
        } catch (IOException e) {
            // A null message: the default error handler echoes non-500 messages, which would name the backend.
            throw new HttpException(502, null, e);
        } finally {
            if (!relayed) {
                closeQuietly(socket);
            }
            if (outgoing.bodyStream != null) {
                closeQuietly(outgoing.bodyStream);
            }
        }
    }

    /**
     * The request line, Host, the forwardable client headers, the framing header and Connection as bytes; a chunked
     * client body is read here and a declared one is opened here, so the body limit answers before any backend
     * connection.
     */
    // @req IR-PROXY-001
    private Outgoing prepare(Request req) {
        StringBuilder head = new StringBuilder();
        head.append(req.methodToken()).append(' ').append(target.basePath).append(req.originalUrl())
                .append(" HTTP/1.1\r\n");
        head.append("Host: ").append(target.authority).append("\r\n");
        @Nullable String contentLength = null;
        boolean transferEncoding = false;
        for (Map.Entry<String, String> h : req.rawHeaders()) {
            String lower = h.getKey().toLowerCase(Locale.ROOT);
            if (lower.equals("content-length")) {
                contentLength = h.getValue().trim();
            } else if (lower.equals("transfer-encoding")) {
                transferEncoding = true;
            }
            if (!HeaderRules.REQUEST_NOT_FORWARDED.contains(lower)) {
                head.append(h.getKey()).append(": ").append(h.getValue()).append("\r\n");
            }
        }
        byte @Nullable [] buffered = null;
        long length = 0;
        if (transferEncoding) {
            // The request-body framing requirement (FR-PROXY-007) streams this; until then it is sent with a length.
            buffered = readClientBody(req.bodyStream());
            length = buffered.length;
        } else if (contentLength != null) {
            try {
                length = Long.parseLong(contentLength);
            } catch (NumberFormatException e) {
                throw new HttpException(400);
            }
            if (length < 0) {
                throw new HttpException(400);
            }
        }
        if (length > 0 || contentLength != null) {
            head.append("Content-Length: ").append(length).append("\r\n");
        }
        head.append("Connection: close\r\n\r\n");
        @Nullable InputStream bodyStream = buffered == null && length > 0 ? req.bodyStream() : null;
        return new Outgoing(head.toString().getBytes(StandardCharsets.ISO_8859_1), buffered, bodyStream, length);
    }

    private static byte[] readClientBody(InputStream in) {
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            for (int n; (n = clientRead(in, b, b.length)) >= 0; ) {
                buf.write(b, 0, n);
            }
            return buf.toByteArray();
        } finally {
            closeQuietly(in);
        }
    }

    private static void copyClientBody(InputStream in, OutputStream out, long length) throws IOException {
        try {
            byte[] b = new byte[8192];
            for (long left = length; left > 0; ) {
                int n = clientRead(in, b, (int) Math.min(b.length, left));
                if (n < 0) {
                    throw new ClientBodyFailure(new EOFException("the client body ended " + left + " bytes early"));
                }
                out.write(b, 0, n);
                left -= n;
            }
        } finally {
            closeQuietly(in);
        }
    }

    /** One read of the client body; its IOException is marked as the client's, apart from backend I/O failures. */
    private static int clientRead(InputStream in, byte[] b, int len) {
        try {
            return in.read(b, 0, len);
        } catch (IOException e) {
            throw new ClientBodyFailure(e);
        }
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // the client body is released either way
        }
    }

    /** {@code nanos} as a socket timeout: rounded up to whole milliseconds, at least 1 and at most Integer.MAX_VALUE. */
    private static int socketMillis(long nanos) {
        long millis = nanos / 1_000_000L + (nanos % 1_000_000L == 0 ? 0 : 1);
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, millis));
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // the connection is dropped either way
        }
    }

    /** The bytes of a backend request head and how its body follows. */
    private static final class Outgoing {
        final byte[] head;
        /** A chunked client body read in full, or null when the body is copied from the client stream. */
        final byte @Nullable [] bufferedBody;
        /** The client body stream of a declared non-zero length, or null. */
        final @Nullable InputStream bodyStream;
        final long contentLength;

        Outgoing(byte[] head, byte @Nullable [] bufferedBody, @Nullable InputStream bodyStream, long contentLength) {
            this.head = head;
            this.bufferedBody = bufferedBody;
            this.bodyStream = bodyStream;
            this.contentLength = contentLength;
        }
    }

    /** An IOException of the client body, carried past the backend's IOException-to-502 mapping. */
    private static final class ClientBodyFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final IOException failure;

        ClientBodyFailure(IOException failure) {
            super(failure);
            this.failure = failure;
        }

        /** An over-limit body as 413 with the limit exception as cause; any other failure unwrapped. */
        // @req IR-PROXY-001
        Exception forErrorFlow() {
            return failure instanceof BodyTooLargeException ? new HttpException(413, null, failure) : failure;
        }
    }

    /**
     * The configured option values. Immutable: every field is final and set by the one constructor, so a
     * {@code withX} copy that omits an argument does not compile. The compiler does not catch arguments of the same
     * type swapped by position, so each {@code withX} must pass the fields in constructor order and change only its
     * own position.
     */
    private static final class Options {
        final long connectTimeoutNanos;
        final long writeTimeoutNanos;
        final long readTimeoutNanos;
        final long totalTimeoutNanos;
        final int retries;
        final long retryDelayNanos;
        final boolean retryUnsafeMethods;
        final boolean stripPrefix;
        final boolean preserveHost;
        final long bodyLimit;
        final int maxConcurrent;
        final List<Map.Entry<String, @Nullable String>> requestHeaderRules;
        final List<Map.Entry<String, @Nullable String>> responseHeaderRules;
        final boolean rewriteLocation;
        final @Nullable SSLSocketFactory sslSocketFactory;

        // @req IR-PROXY-001
        private Options(long connectTimeoutNanos, long writeTimeoutNanos, long readTimeoutNanos, long totalTimeoutNanos,
                int retries, long retryDelayNanos, boolean retryUnsafeMethods, boolean stripPrefix,
                boolean preserveHost, long bodyLimit, int maxConcurrent,
                List<Map.Entry<String, @Nullable String>> requestHeaderRules,
                List<Map.Entry<String, @Nullable String>> responseHeaderRules, boolean rewriteLocation,
                @Nullable SSLSocketFactory sslSocketFactory) {
            this.connectTimeoutNanos = connectTimeoutNanos;
            this.writeTimeoutNanos = writeTimeoutNanos;
            this.readTimeoutNanos = readTimeoutNanos;
            this.totalTimeoutNanos = totalTimeoutNanos;
            this.retries = retries;
            this.retryDelayNanos = retryDelayNanos;
            this.retryUnsafeMethods = retryUnsafeMethods;
            this.stripPrefix = stripPrefix;
            this.preserveHost = preserveHost;
            this.bodyLimit = bodyLimit;
            this.maxConcurrent = maxConcurrent;
            this.requestHeaderRules = requestHeaderRules;
            this.responseHeaderRules = responseHeaderRules;
            this.rewriteLocation = rewriteLocation;
            this.sslSocketFactory = sslSocketFactory;
        }

        /** Every option at its default. */
        // @req IR-PROXY-001
        static Options defaults() {
            return new Options(DEFAULT_CONNECT_TIMEOUT_NANOS, DEFAULT_WRITE_TIMEOUT_NANOS, DEFAULT_READ_TIMEOUT_NANOS,
                    DEFAULT_TOTAL_TIMEOUT_NANOS, 0, DEFAULT_RETRY_DELAY_NANOS, false, false, false, NO_BODY_LIMIT,
                    NO_CONCURRENCY_LIMIT, Collections.emptyList(), Collections.emptyList(), true, null);
        }

        Options withConnectTimeoutNanos(long v) {
            return new Options(v, writeTimeoutNanos, readTimeoutNanos, totalTimeoutNanos, retries, retryDelayNanos,
                    retryUnsafeMethods, stripPrefix, preserveHost, bodyLimit, maxConcurrent, requestHeaderRules,
                    responseHeaderRules, rewriteLocation, sslSocketFactory);
        }

        Options withWriteTimeoutNanos(long v) {
            return new Options(connectTimeoutNanos, v, readTimeoutNanos, totalTimeoutNanos, retries, retryDelayNanos,
                    retryUnsafeMethods, stripPrefix, preserveHost, bodyLimit, maxConcurrent, requestHeaderRules,
                    responseHeaderRules, rewriteLocation, sslSocketFactory);
        }

        Options withReadTimeoutNanos(long v) {
            return new Options(connectTimeoutNanos, writeTimeoutNanos, v, totalTimeoutNanos, retries, retryDelayNanos,
                    retryUnsafeMethods, stripPrefix, preserveHost, bodyLimit, maxConcurrent, requestHeaderRules,
                    responseHeaderRules, rewriteLocation, sslSocketFactory);
        }

        Options withTotalTimeoutNanos(long v) {
            return new Options(connectTimeoutNanos, writeTimeoutNanos, readTimeoutNanos, v, retries, retryDelayNanos,
                    retryUnsafeMethods, stripPrefix, preserveHost, bodyLimit, maxConcurrent, requestHeaderRules,
                    responseHeaderRules, rewriteLocation, sslSocketFactory);
        }

        Options withRetries(int v) {
            return new Options(connectTimeoutNanos, writeTimeoutNanos, readTimeoutNanos, totalTimeoutNanos, v,
                    retryDelayNanos, retryUnsafeMethods, stripPrefix, preserveHost, bodyLimit, maxConcurrent,
                    requestHeaderRules, responseHeaderRules, rewriteLocation, sslSocketFactory);
        }

        Options withRetryDelayNanos(long v) {
            return new Options(connectTimeoutNanos, writeTimeoutNanos, readTimeoutNanos, totalTimeoutNanos, retries, v,
                    retryUnsafeMethods, stripPrefix, preserveHost, bodyLimit, maxConcurrent, requestHeaderRules,
                    responseHeaderRules, rewriteLocation, sslSocketFactory);
        }

        Options withRetryUnsafeMethods(boolean v) {
            return new Options(connectTimeoutNanos, writeTimeoutNanos, readTimeoutNanos, totalTimeoutNanos, retries,
                    retryDelayNanos, v, stripPrefix, preserveHost, bodyLimit, maxConcurrent, requestHeaderRules,
                    responseHeaderRules, rewriteLocation, sslSocketFactory);
        }

        Options withStripPrefix(boolean v) {
            return new Options(connectTimeoutNanos, writeTimeoutNanos, readTimeoutNanos, totalTimeoutNanos, retries,
                    retryDelayNanos, retryUnsafeMethods, v, preserveHost, bodyLimit, maxConcurrent, requestHeaderRules,
                    responseHeaderRules, rewriteLocation, sslSocketFactory);
        }

        Options withPreserveHost(boolean v) {
            return new Options(connectTimeoutNanos, writeTimeoutNanos, readTimeoutNanos, totalTimeoutNanos, retries,
                    retryDelayNanos, retryUnsafeMethods, stripPrefix, v, bodyLimit, maxConcurrent, requestHeaderRules,
                    responseHeaderRules, rewriteLocation, sslSocketFactory);
        }

        Options withBodyLimit(long v) {
            return new Options(connectTimeoutNanos, writeTimeoutNanos, readTimeoutNanos, totalTimeoutNanos, retries,
                    retryDelayNanos, retryUnsafeMethods, stripPrefix, preserveHost, v, maxConcurrent,
                    requestHeaderRules, responseHeaderRules, rewriteLocation, sslSocketFactory);
        }

        Options withMaxConcurrent(int v) {
            return new Options(connectTimeoutNanos, writeTimeoutNanos, readTimeoutNanos, totalTimeoutNanos, retries,
                    retryDelayNanos, retryUnsafeMethods, stripPrefix, preserveHost, bodyLimit, v, requestHeaderRules,
                    responseHeaderRules, rewriteLocation, sslSocketFactory);
        }

        Options withRequestHeaderRules(List<Map.Entry<String, @Nullable String>> v) {
            return new Options(connectTimeoutNanos, writeTimeoutNanos, readTimeoutNanos, totalTimeoutNanos, retries,
                    retryDelayNanos, retryUnsafeMethods, stripPrefix, preserveHost, bodyLimit, maxConcurrent, v,
                    responseHeaderRules, rewriteLocation, sslSocketFactory);
        }

        Options withResponseHeaderRules(List<Map.Entry<String, @Nullable String>> v) {
            return new Options(connectTimeoutNanos, writeTimeoutNanos, readTimeoutNanos, totalTimeoutNanos, retries,
                    retryDelayNanos, retryUnsafeMethods, stripPrefix, preserveHost, bodyLimit, maxConcurrent,
                    requestHeaderRules, v, rewriteLocation, sslSocketFactory);
        }

        Options withRewriteLocation(boolean v) {
            return new Options(connectTimeoutNanos, writeTimeoutNanos, readTimeoutNanos, totalTimeoutNanos, retries,
                    retryDelayNanos, retryUnsafeMethods, stripPrefix, preserveHost, bodyLimit, maxConcurrent,
                    requestHeaderRules, responseHeaderRules, v, sslSocketFactory);
        }

        Options withSslSocketFactory(@Nullable SSLSocketFactory v) {
            return new Options(connectTimeoutNanos, writeTimeoutNanos, readTimeoutNanos, totalTimeoutNanos, retries,
                    retryDelayNanos, retryUnsafeMethods, stripPrefix, preserveHost, bodyLimit, maxConcurrent,
                    requestHeaderRules, responseHeaderRules, rewriteLocation, v);
        }
    }
    /**
     * The backend origin and base path of {@link #to}. Only the shape needed to connect is read here; the full URL
     * grammar and its rejections belong to the target URL requirement (FR-PROXY-001).
     */
    private static final class Target {
        final String scheme;
        /** The authority as written, used as the Host value. */
        final String authority;
        /** The host to connect to, without IPv6 brackets. */
        final String host;
        final int port;
        /** The base path without trailing slashes; empty for none. */
        final String basePath;

        private Target(String scheme, String authority, String host, int port, String basePath) {
            this.scheme = scheme;
            this.authority = authority;
            this.host = host;
            this.port = port;
            this.basePath = basePath;
        }

        // @req IR-PROXY-001
        static Target parse(String url) {
            if (url == null) {
                throw new NullPointerException("url");
            }
            int sep = url.indexOf("://");
            if (sep <= 0) {
                throw new IllegalArgumentException("not an http or https URL");
            }
            String scheme = url.substring(0, sep).toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                throw new IllegalArgumentException("not an http or https URL");
            }
            String rest = url.substring(sep + 3);
            int slash = rest.indexOf('/');
            String authority = slash < 0 ? rest : rest.substring(0, slash);
            String basePath = slash < 0 ? "" : rest.substring(slash);
            while (basePath.endsWith("/")) {
                basePath = basePath.substring(0, basePath.length() - 1);
            }
            String host;
            String port = null;
            if (authority.startsWith("[")) {
                int close = authority.indexOf(']');
                if (close < 0) {
                    throw new IllegalArgumentException("unclosed IPv6 literal");
                }
                host = authority.substring(1, close);
                if (close + 1 < authority.length()) {
                    if (authority.charAt(close + 1) != ':') {
                        throw new IllegalArgumentException("bad authority");
                    }
                    port = authority.substring(close + 2);
                }
            } else {
                int colon = authority.lastIndexOf(':');
                host = colon < 0 ? authority : authority.substring(0, colon);
                port = colon < 0 ? null : authority.substring(colon + 1);
            }
            if (host.isEmpty()) {
                throw new IllegalArgumentException("no host");
            }
            int portNumber = scheme.equals("https") ? 443 : 80;
            if (port != null) {
                try {
                    portNumber = Integer.parseInt(port);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("bad port");
                }
                if (portNumber < 1 || portNumber > 65535) {
                    throw new IllegalArgumentException("bad port");
                }
            }
            return new Target(scheme, authority, host, portNumber, basePath);
        }
    }

    /**
     * The status, header lines and framing headers of one backend response head, interim 1xx heads skipped. Every
     * header line must have a token name and a value {@code res.header} accepts, or the head is unreadable.
     */
    private static final class ResponseHead {
        final int status;
        /** The header lines in received order, duplicates kept: the name as received, the value without OWS. */
        final List<Map.Entry<String, String>> headers;
        /** The Content-Length, or -1 for a body that runs to the end of the connection. */
        final long contentLength;
        /** The Transfer-Encoding values joined with commas, or null when the backend sent none. */
        final @Nullable String transferEncoding;
        /** Whether the last transfer coding is {@code chunked}. */
        final boolean chunkedLast;

        private ResponseHead(int status, List<Map.Entry<String, String>> headers, long contentLength,
                @Nullable String transferEncoding, boolean chunkedLast) {
            this.status = status;
            this.headers = headers;
            this.contentLength = contentLength;
            this.transferEncoding = transferEncoding;
            this.chunkedLast = chunkedLast;
        }

        // @req IR-PROXY-001
        // @req FR-PROXY-011
        static ResponseHead read(InputStream in) throws IOException {
            while (true) {
                String statusLine = readLine(in);
                if (statusLine.length() < 12 || !statusLine.startsWith("HTTP/1.") || statusLine.charAt(8) != ' '
                        || (statusLine.length() > 12 && statusLine.charAt(12) != ' ')) {
                    throw new IOException("malformed status line");
                }
                int status;
                try {
                    status = Integer.parseInt(statusLine.substring(9, 12));
                } catch (NumberFormatException e) {
                    throw new IOException("malformed status code");
                }
                if (status < 100 || status > 599 || status == 101) {
                    throw new IOException("unexpected status " + status);
                }
                List<Map.Entry<String, String>> headers = new ArrayList<>();
                @Nullable StringBuilder transferEncoding = null;
                long contentLength = -1;
                for (int lines = 0; ; lines++) {
                    String line = readLine(in);
                    if (line.isEmpty()) {
                        break;
                    }
                    if (lines >= MAX_HEADER_LINES) {
                        throw new IOException("too many header lines");
                    }
                    int colon = line.indexOf(':');
                    if (colon <= 0) {
                        throw new IOException("malformed header line");
                    }
                    String name = line.substring(0, colon);
                    String value = HeaderRules.trimOws(line.substring(colon + 1));
                    if (!HttpHeaderValues.isToken(name) || !HttpHeaderValues.isFieldValue(value)) {
                        throw new IOException("malformed header line");
                    }
                    headers.add(new SimpleImmutableEntry<>(name, value));
                    String lower = name.toLowerCase(Locale.ROOT);
                    if (lower.equals("transfer-encoding")) {
                        transferEncoding = transferEncoding == null ? new StringBuilder(value)
                                : transferEncoding.append(',').append(value);
                    } else if (lower.equals("content-length")) {
                        try {
                            contentLength = Long.parseLong(value);
                        } catch (NumberFormatException e) {
                            throw new IOException("malformed Content-Length");
                        }
                        if (contentLength < 0) {
                            throw new IOException("malformed Content-Length");
                        }
                    }
                }
                if (status >= 200) {
                    @Nullable String te = transferEncoding == null ? null : transferEncoding.toString();
                    return new ResponseHead(status, headers, contentLength, te, te != null && lastCodingIsChunked(te));
                }
            }
        }

        /** Whether the last non-empty element of a Transfer-Encoding list is {@code chunked}, case-insensitively. */
        private static boolean lastCodingIsChunked(String transferEncoding) {
            String[] codings = transferEncoding.split(",", -1);
            for (int i = codings.length - 1; i >= 0; i--) {
                String coding = HeaderRules.trimOws(codings[i]);
                if (!coding.isEmpty()) {
                    return coding.equalsIgnoreCase("chunked");
                }
            }
            return false;
        }

        private static String readLine(InputStream in) throws IOException {
            StringBuilder line = new StringBuilder();
            for (int b; (b = in.read()) != '\n'; ) {
                if (b < 0) {
                    throw new EOFException("the backend closed inside the response head");
                }
                if (line.length() >= MAX_LINE) {
                    throw new IOException("response head line too long");
                }
                line.append((char) b);
            }
            int end = line.length();
            if (end > 0 && line.charAt(end - 1) == '\r') {
                end--;
            }
            return line.substring(0, end);
        }
    }

    /**
     * A chunked backend response body decoded to its data: chunk extensions are ignored and the trailer fields after
     * the last chunk are read and dropped, so they never reach the client. Closing it closes the backend connection.
     * The strict response parser rules belong to SEC-PROXY-004.
     */
    // @req FR-PROXY-011
    private static final class ChunkedBodyStream extends InputStream {
        /** At most 15 significant hex digits, so a chunk size always fits a non-negative long. */
        private static final int MAX_SIZE_DIGITS = 15;
        private final InputStream in;
        private final Socket socket;
        /** Data bytes left in the current chunk. */
        private long remaining;
        private boolean started;
        private boolean done;

        ChunkedBodyStream(InputStream in, Socket socket) {
            this.in = in;
            this.socket = socket;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xff;
        }

        // @req FR-PROXY-011
        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) {
                return 0;
            }
            if (remaining == 0 && !nextChunk()) {
                return -1;
            }
            int n = in.read(b, off, (int) Math.min(len, remaining));
            if (n < 0) {
                throw new EOFException("the backend closed inside a chunk");
            }
            remaining -= n;
            return n;
        }

        /** Reads the next chunk-size line; false after the last chunk, whose trailer lines are read and dropped. */
        private boolean nextChunk() throws IOException {
            if (done) {
                return false;
            }
            if (started && !ResponseHead.readLine(in).isEmpty()) {
                throw new IOException("chunk data not followed by CRLF");
            }
            started = true;
            String line = ResponseHead.readLine(in);
            int semi = line.indexOf(';');
            String digits = HeaderRules.trimOws(semi < 0 ? line : line.substring(0, semi));
            if (digits.isEmpty()) {
                throw new IOException("malformed chunk size");
            }
            int first = 0;
            while (first < digits.length() - 1 && digits.charAt(first) == '0') {
                first++; // leading zeros do not count toward the digit limit
            }
            if (digits.length() - first > MAX_SIZE_DIGITS) {
                throw new IOException("chunk size too large");
            }
            long size = 0;
            for (int i = 0; i < digits.length(); i++) {
                char c = digits.charAt(i);
                int d = c >= '0' && c <= '9' ? c - '0' : c >= 'a' && c <= 'f' ? c - 'a' + 10
                        : c >= 'A' && c <= 'F' ? c - 'A' + 10 : -1;
                if (d < 0) {
                    throw new IOException("malformed chunk size");
                }
                size = size * 16 + d;
            }
            if (size > 0) {
                remaining = size;
                return true;
            }
            for (int lines = 0; !ResponseHead.readLine(in).isEmpty(); lines++) {
                if (lines >= MAX_TRAILER_LINES) {
                    throw new IOException("too many trailer lines");
                }
            }
            done = true;
            return false;
        }

        @Override
        public void close() throws IOException {
            try {
                in.close();
            } finally {
                socket.close();
            }
        }
    }

    /** The backend response body; closing it closes the backend connection. */
    private static final class SocketBodyStream extends FilterInputStream {
        private final Socket socket;

        SocketBodyStream(InputStream in, Socket socket) {
            super(in);
            this.socket = socket;
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                socket.close();
            }
        }
    }
}
