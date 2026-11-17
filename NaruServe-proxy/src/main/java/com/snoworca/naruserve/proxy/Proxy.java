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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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

    /** The longest status or header line the backend may send. */
    private static final int MAX_LINE = 64 * 1024;
    /** The most header lines one backend response head may hold. */
    private static final int MAX_HEADER_LINES = 1000;

    /** Request header names the proxy writes itself or never forwards. */
    private static final Set<String> NOT_FORWARDED = new HashSet<>(Arrays.asList("connection", "keep-alive",
            "proxy-connection", "te", "transfer-encoding", "upgrade", "proxy-authenticate", "proxy-authorization",
            "trailer", "host", "content-length", "expect"));

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
        return new Proxy(Target.parse(url), new Options());
    }

    /** The limit for the TCP connect to the backend (default 10 seconds). */
    // @req IR-PROXY-001
    public Proxy connectTimeout(long amount, TimeUnit unit) {
        Options c = options.copy();
        c.connectTimeoutNanos = unit.toNanos(amount);
        return new Proxy(target, c);
    }

    /** The limit for a backend write that makes no progress (default 60 seconds). */
    // @req IR-PROXY-001
    public Proxy writeTimeout(long amount, TimeUnit unit) {
        Options c = options.copy();
        c.writeTimeoutNanos = unit.toNanos(amount);
        return new Proxy(target, c);
    }

    /** The limit for the first response byte and between response bytes (default 60 seconds). */
    // @req IR-PROXY-001
    public Proxy readTimeout(long amount, TimeUnit unit) {
        Options c = options.copy();
        c.readTimeoutNanos = unit.toNanos(amount);
        return new Proxy(target, c);
    }

    /** The limit for a whole proxied request, retries included (default 5 minutes). */
    // @req IR-PROXY-001
    public Proxy totalTimeout(long amount, TimeUnit unit) {
        Options c = options.copy();
        c.totalTimeoutNanos = unit.toNanos(amount);
        return new Proxy(target, c);
    }

    /** How many more attempts a failed request may get (default 0). */
    // @req IR-PROXY-001
    public Proxy retries(int count) {
        Options c = options.copy();
        c.retries = count;
        return new Proxy(target, c);
    }

    /** The wait before each retry (default 200 milliseconds). */
    // @req IR-PROXY-001
    public Proxy retryDelay(long amount, TimeUnit unit) {
        Options c = options.copy();
        c.retryDelayNanos = unit.toNanos(amount);
        return new Proxy(target, c);
    }

    /** Whether a backend 502 or 503 is retried for methods other than bodyless GET, HEAD and OPTIONS (default false). */
    // @req IR-PROXY-001
    public Proxy retryUnsafeMethods(boolean enabled) {
        Options c = options.copy();
        c.retryUnsafeMethods = enabled;
        return new Proxy(target, c);
    }

    /** Whether the mount prefix is removed from the forwarded path (default false: the prefix is kept). */
    // @req IR-PROXY-001
    public Proxy stripPrefix(boolean enabled) {
        Options c = options.copy();
        c.stripPrefix = enabled;
        return new Proxy(target, c);
    }

    /** Whether the received {@code Host} is sent instead of the target host (default false). */
    // @req IR-PROXY-001
    public Proxy preserveHost(boolean enabled) {
        Options c = options.copy();
        c.preserveHost = enabled;
        return new Proxy(target, c);
    }

    /** The request body limit in bytes for this mount (default: the app's raw body limit). */
    // @req IR-PROXY-001
    public Proxy bodyLimit(long bytes) {
        Options c = options.copy();
        c.bodyLimit = bytes;
        return new Proxy(target, c);
    }

    /** The most in-flight requests of this proxy and the proxies derived from it (default: no limit). */
    // @req IR-PROXY-001
    public Proxy maxConcurrent(int limit) {
        Options c = options.copy();
        c.maxConcurrent = limit;
        return new Proxy(target, c);
    }

    /** Sends {@code value} as request header {@code name} in place of the client's lines of that name. */
    // @req IR-PROXY-001
    public Proxy requestHeader(String name, String value) {
        Options c = options.copy();
        c.requestHeaderRules = appended(c.requestHeaderRules, name, value);
        return new Proxy(target, c);
    }

    /** Drops the client's request header {@code name}. */
    // @req IR-PROXY-001
    public Proxy removeRequestHeader(String name) {
        Options c = options.copy();
        c.requestHeaderRules = appended(c.requestHeaderRules, name, null);
        return new Proxy(target, c);
    }

    /** Sends {@code value} as response header {@code name} in place of the backend's lines of that name. */
    // @req IR-PROXY-001
    public Proxy responseHeader(String name, String value) {
        Options c = options.copy();
        c.responseHeaderRules = appended(c.responseHeaderRules, name, value);
        return new Proxy(target, c);
    }

    /** Drops the backend's response header {@code name}. */
    // @req IR-PROXY-001
    public Proxy removeResponseHeader(String name) {
        Options c = options.copy();
        c.responseHeaderRules = appended(c.responseHeaderRules, name, null);
        return new Proxy(target, c);
    }

    /** Whether a same-origin backend {@code Location} is rewritten to a path under the mount (default true). */
    // @req IR-PROXY-001
    public Proxy rewriteLocation(boolean enabled) {
        Options c = options.copy();
        c.rewriteLocation = enabled;
        return new Proxy(target, c);
    }

    /** The socket factory for an {@code https} target (default: the JDK default factory). */
    // @req IR-PROXY-001
    public Proxy sslSocketFactory(SSLSocketFactory factory) {
        Options c = options.copy();
        c.sslSocketFactory = factory;
        return new Proxy(target, c);
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
    private void forward(Request req, Response res, Outgoing outgoing) {
        Socket socket = new Socket();
        boolean relayed = false;
        try {
            socket.connect(new InetSocketAddress(target.host, target.port), socketMillis(options.connectTimeoutNanos));
            socket.setSoTimeout(socketMillis(options.readTimeoutNanos));
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
            if (head.transferEncoding != null && !bodyless) {
                // Chunked response bodies are decoded by the response-relay requirements (FR-PROXY-011, FR-PROXY-014).
                throw new HttpException(502);
            }
            res.status(head.status);
            if (head.contentType != null) {
                res.header("Content-Type", head.contentType);
            }
            long length = head.status == 204 || head.transferEncoding != null ? -1 : head.contentLength;
            res.relay(new SocketBodyStream(in, socket), length);
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
            if (!NOT_FORWARDED.contains(lower)) {
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

    /** The configured values; a fresh copy is filled by each option call and never changed after it is published. */
    private static final class Options {
        long connectTimeoutNanos = DEFAULT_CONNECT_TIMEOUT_NANOS;
        long writeTimeoutNanos = DEFAULT_WRITE_TIMEOUT_NANOS;
        long readTimeoutNanos = DEFAULT_READ_TIMEOUT_NANOS;
        long totalTimeoutNanos = DEFAULT_TOTAL_TIMEOUT_NANOS;
        int retries;
        long retryDelayNanos = DEFAULT_RETRY_DELAY_NANOS;
        boolean retryUnsafeMethods;
        boolean stripPrefix;
        boolean preserveHost;
        long bodyLimit = NO_BODY_LIMIT;
        int maxConcurrent = NO_CONCURRENCY_LIMIT;
        List<Map.Entry<String, @Nullable String>> requestHeaderRules = Collections.emptyList();
        List<Map.Entry<String, @Nullable String>> responseHeaderRules = Collections.emptyList();
        boolean rewriteLocation = true;
        @Nullable SSLSocketFactory sslSocketFactory;

        // @req IR-PROXY-001
        Options copy() {
            Options c = new Options();
            c.connectTimeoutNanos = connectTimeoutNanos;
            c.writeTimeoutNanos = writeTimeoutNanos;
            c.readTimeoutNanos = readTimeoutNanos;
            c.totalTimeoutNanos = totalTimeoutNanos;
            c.retries = retries;
            c.retryDelayNanos = retryDelayNanos;
            c.retryUnsafeMethods = retryUnsafeMethods;
            c.stripPrefix = stripPrefix;
            c.preserveHost = preserveHost;
            c.bodyLimit = bodyLimit;
            c.maxConcurrent = maxConcurrent;
            c.requestHeaderRules = requestHeaderRules;
            c.responseHeaderRules = responseHeaderRules;
            c.rewriteLocation = rewriteLocation;
            c.sslSocketFactory = sslSocketFactory;
            return c;
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

    /** The status, Content-Type and framing headers of one backend response head, interim 1xx heads skipped. */
    private static final class ResponseHead {
        final int status;
        final @Nullable String contentType;
        /** The Content-Length, or -1 for a body that runs to the end of the connection. */
        final long contentLength;
        final @Nullable String transferEncoding;

        private ResponseHead(int status, @Nullable String contentType, long contentLength,
                @Nullable String transferEncoding) {
            this.status = status;
            this.contentType = contentType;
            this.contentLength = contentLength;
            this.transferEncoding = transferEncoding;
        }

        // @req IR-PROXY-001
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
                String contentType = null;
                String transferEncoding = null;
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
                    String name = line.substring(0, colon).toLowerCase(Locale.ROOT);
                    String value = line.substring(colon + 1).trim();
                    if (name.equals("content-type") && contentType == null) {
                        if (!HttpHeaderValues.isFieldValue(value)) {
                            throw new IOException("malformed Content-Type");
                        }
                        contentType = value;
                    } else if (name.equals("transfer-encoding")) {
                        transferEncoding = value;
                    } else if (name.equals("content-length")) {
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
                    return new ResponseHead(status, contentType, contentLength, transferEncoding);
                }
            }
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
