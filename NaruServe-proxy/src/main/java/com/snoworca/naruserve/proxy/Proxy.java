package com.snoworca.naruserve.proxy;

import com.snoworca.naruserve.HttpException;
import com.snoworca.naruserve.Middleware;
import com.snoworca.naruserve.Next;
import com.snoworca.naruserve.Request;
import com.snoworca.naruserve.Response;
import com.snoworca.naruserve.common.HttpHeaderValues;
import com.snoworca.naruserve.routing.Router;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
    /** The decided request framing of a chunked client body (see {@link #requestBodyLength}). */
    private static final long CHUNKED_BODY = -1;
    /** The decided request framing when the client sent neither Content-Length nor Transfer-Encoding. */
    private static final long NO_FRAMING_HEADER = -2;
    /** The framing header of a body sent in the chunked coding. */
    private static final Map.Entry<String, String> CHUNKED_FRAMING =
            new SimpleImmutableEntry<>("Transfer-Encoding", "chunked");
    /** The framing header of an empty body for the methods in {@link #EMPTY_BODY_LENGTH_METHODS}. */
    private static final Map.Entry<String, String> ZERO_LENGTH_FRAMING =
            new SimpleImmutableEntry<>("Content-Length", "0");
    /**
     * The methods whose bodyless request without length headers is sent with {@code Content-Length: 0}, for
     * backends that answer 411 to such a request without a length (RFC 9110 §8.6); method tokens are case-sensitive.
     */
    private static final Set<String> EMPTY_BODY_LENGTH_METHODS =
            Collections.unmodifiableSet(new HashSet<>(Arrays.asList("POST", "PUT", "PATCH")));
    /**
     * The {@code Allow} value of the 405 to {@code CONNECT} and {@code TRACE}: the standard methods only, since
     * extension methods differ per backend.
     */
    private static final String ALLOW_METHODS = "GET, HEAD, POST, PUT, DELETE, PATCH, OPTIONS";

    private final TargetUrl target;
    private final Options options;

    private Proxy(TargetUrl target, Options options) {
        this.target = target;
        this.options = options;
    }

    /**
     * A proxy to {@code url}, an {@code http} or {@code https} origin with an optional base path, for example
     * {@code Proxy.to("http://127.0.0.1:8081")}; every option has its default. The URL is checked here: {@code null}
     * throws {@code NullPointerException} and every other URL throws {@code IllegalArgumentException}: one with
     * a query, a fragment or user information, another scheme, a host that is not an ASCII name, a dotted-quad IPv4
     * literal or a bracketed IPv6 literal without a zone id, a port outside {@code 1..65535}, or a base path that
     * fails the request path checks once its trailing slashes are removed.
     */
    // @req IR-PROXY-001
    // @req FR-PROXY-001
    public static Proxy to(String url) {
        return new Proxy(TargetUrl.parse(url), Options.defaults());
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

    /**
     * Sends {@code value} as response header {@code name} in place of the backend's lines and the app's value of that
     * name, set after the backend header copy and the {@code Location} rewrite. Names compare case-insensitively and
     * the later {@code responseHeader} or {@code removeResponseHeader} call for a name wins.
     */
    // @req IR-PROXY-001
    public Proxy responseHeader(String name, String value) {
        HeaderRules.checkResponseRule(name, value, "responseHeader");
        return new Proxy(target, options.withResponseHeaderRules(appended(options.responseHeaderRules, name, value)));
    }

    /**
     * Drops the backend's response header lines of {@code name}; a value the app set before the proxy stays. Names
     * compare case-insensitively and the later {@code responseHeader} or {@code removeResponseHeader} call for a name
     * wins.
     */
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
        ConcurrencyLimiter limiter = options.limiter;
        return limiter == null ? NO_CONCURRENCY_LIMIT : limiter.limit();
    }

    /** The in-flight counter shared with the proxies derived from this one, or null when there is no limit. */
    // @req FR-PROXY-019
    @Nullable AtomicInteger concurrencyCounter() {
        ConcurrencyLimiter limiter = options.limiter;
        return limiter == null ? null : limiter.counter();
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
                + " preserveHost=" + o.preserveHost + " bodyLimit=" + o.bodyLimit + " maxConcurrent=" + maxConcurrent()
                + " requestHeaders=" + o.requestHeaderRules + " responseHeaders=" + o.responseHeaderRules
                + " rewriteLocation=" + o.rewriteLocation + " sslSocketFactory="
                + (o.sslSocketFactory == null ? "default" : Integer.toHexString(System.identityHashCode(o.sslSocketFactory)));
    }

    /**
     * Forwards the request to the backend over a new connection and hands the response body to {@code res.relay},
     * which owns the backend socket from then on; never calls {@code next.run()} for a request it forwarded. A
     * WebSocket upgrade, judged as {@link Router#isWebSocketUpgrade} judges it on each header's lines joined by
     * {@code ", "}, goes to {@code next.run()} without a backend connection, so an {@code app.ws} route under the mount
     * gets it; a method that is not a token answers 400 and {@code CONNECT} and {@code TRACE}, in any letter case,
     * answer 405 with {@code Allow}, both before any backend connection. A backend that cannot be reached
     * or answers with a response this proxy cannot read gives 502 through the error flow; a failure while reading
     * the client body is the client's and reaches the error flow as itself, an over-limit body as 413.
     */
    // @req IR-PROXY-001
    // @req SEC-PROXY-003
    // @req FR-PROXY-003
    // @req FR-PROXY-008
    // @req SEC-PROXY-001
    // @req FR-PROXY-004
    // @req FR-PROXY-009
    // @req FR-PROXY-019
    @Override
    public void handle(Request req, Response res, Next next) throws Exception {
        // The upgrade check comes first, then the method checks, then the request-target and header checks (D-050).
        if (Router.isWebSocketUpgrade(req.methodToken(), joinedHeader(req, "Connection"),
                joinedHeader(req, "Upgrade"))) {
            next.run();
            return;
        }
        String method = req.methodToken();
        if (!HttpHeaderValues.isToken(method)) {
            throw new HttpException(400);
        }
        // A token is ASCII, so equalsIgnoreCase matches CONNECT and TRACE in any letter case without locale effects.
        if ("CONNECT".equalsIgnoreCase(method) || "TRACE".equalsIgnoreCase(method)) {
            throw new HttpException(405, null).withHeader("Allow", ALLOW_METHODS);
        }
        // The request-target check (400) runs on the raw originalUrl() path and query whatever stripPrefix says
        // (D-052); then the header check and the multiple-Host check (400) and the framing answers (400/501), from
        // the header lines alone, for every target scheme (D-050). A null message, like every other 400 of the
        // proxy (D-051).
        if (!PathChecker.isSafeTarget(req.originalUrl())) {
            throw new HttpException(400);
        }
        String requestTarget = requestTarget(req);
        BackendRequest.checkClientHeaders(req.rawHeaders());
        @Nullable String receivedHost = BackendRequest.receivedHost(req.rawHeaders());
        long declaredFraming = requestBodyLength(req);
        try {
            Outgoing outgoing = prepare(req, requestTarget, declaredFraming, receivedHost);
            // The slot comes after every check and req.bodyStream (FR-PROXY-010) and before the backend connect.
            forward(req, res, outgoing, takeSlot(outgoing));
        } catch (RequestBodyCopier.ClientBodyFailure f) {
            throw f.forErrorFlow();
        }
    }

    /** Every value of the request header {@code name} (case-insensitive) joined by {@code ", "}, or null for none. */
    // @req FR-PROXY-009
    private static @Nullable String joinedHeader(Request req, String name) {
        StringBuilder joined = null;
        for (Map.Entry<String, String> h : req.rawHeaders()) {
            if (h.getKey().equalsIgnoreCase(name)) {
                joined = joined == null ? new StringBuilder() : joined.append(", ");
                joined.append(h.getValue());
            }
        }
        return joined == null ? null : joined.toString();
    }

    // @req IR-PROXY-001
    // @req FR-PROXY-011
    // @req FR-PROXY-007
    // @req SEC-PROXY-005
    // @req FR-PROXY-013
    // @req FR-PROXY-012
    // @req FR-PROXY-019
    private void forward(Request req, Response res, Outgoing outgoing, ProxyExchange exchange) {
        boolean relayed = false;
        try {
            // Plain for http; TLS-layered and verified for https, whose failures are ProxyExceptions (SEC-PROXY-005).
            Socket socket = BackendClient.open(target, connectTimeoutMillis(), options.totalTimeoutNanos,
                    options.sslSocketFactory);
            exchange.attach(socket);
            socket.setSoTimeout(readTimeoutMillis());
            OutputStream out = new BufferedOutputStream(socket.getOutputStream());
            out.write(outgoing.head);
            BodyFraming framing = outgoing.framing;
            if (framing.chunked) {
                RequestBodyCopier.copyChunked(outgoing.body, framing.firstByte, out);
            } else {
                RequestBodyCopier.copyLength(outgoing.body, out, framing.contentLength);
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
            // A removed name's backend lines are skipped, so an app value of it stays and the rewrite never sees them.
            List<Map.Entry<String, String>> copied =
                    ResponseHeaderCopier.copy(head.headers, options.hiddenResponseNames, res);
            // The Location rewrite follows the copy and comes before the .responseHeader values (FR-PROXY-012).
            if (options.rewriteLocation) {
                LocationRewriter.apply(copied, target, options.stripPrefix ? req.baseUrl() : "", res);
            }
            for (Map.Entry<String, String> fixed : options.fixedResponseHeaders) {
                res.header(fixed.getKey(), fixed.getValue());
            }
            long length = head.status == 204 || head.transferEncoding != null ? -1 : head.contentLength;
            InputStream body = chunked ? new ChunkedBodyStream(in, socket) : new SocketBodyStream(in, socket);
            // From here the relayed body owns the exchange: its close returns the slot (FR-PROXY-019).
            res.relay(new RelayedBodyStream(body, exchange), length);
            relayed = true;
        } catch (IOException e) {
            // A null message: the default error handler echoes non-500 messages, which would name the backend.
            throw new HttpException(502, null, e);
        } finally {
            if (!relayed) {
                // A request that ends before res.relay took the body releases here, before its exception leaves.
                exchange.release();
            }
            closeQuietly(outgoing.body);
        }
    }

    /**
     * The exchange of a request that may go to the backend: with a slot of the shared counter when the proxy has a
     * limit, taken without waiting. A request that finds the counter at its limit closes its body and gets 503
     * {@code CONCURRENCY_LIMIT} without a backend connection.
     */
    // @req FR-PROXY-019
    private ProxyExchange takeSlot(Outgoing outgoing) {
        ConcurrencyLimiter limiter = options.limiter;
        if (limiter != null && !limiter.tryAcquire()) {
            closeQuietly(outgoing.body);
            throw new ProxyException(ProxyException.Kind.CONCURRENCY_LIMIT, null);
        }
        return new ProxyExchange(limiter);
    }

    /**
     * The request line, Host, the forwardable client headers, the framing header and Connection as bytes, and the
     * client body stream, for the {@code declaredFraming} {@link #requestBodyLength} decided; all before any backend
     * connection. The body is taken once through {@code req.bodyStream(n)} for a mount with {@code .bodyLimit(n)} and
     * {@code req.bodyStream()} otherwise. The framing decision (the pre-read and the list-form 413) lives in
     * {@link #bodyFraming}. The exceptions {@code req.bodyStream} throws for a body earlier code took pass through
     * unwrapped, and the body is closed on every failure. {@code Host} is the received one ({@code receivedHost},
     * null for none) under {@code .preserveHost(true)} and the target host otherwise; the received one also goes out
     * as {@code X-Forwarded-Host}.
     */
    // @req IR-PROXY-001
    // @req SEC-PROXY-003
    // @req FR-PROXY-003
    // @req FR-PROXY-007
    // @req FR-PROXY-008
    // @req FR-PROXY-004
    // @req FR-PROXY-006
    private Outgoing prepare(Request req, String requestTarget, long declaredFraming, @Nullable String receivedHost) {
        InputStream body = options.bodyLimit == NO_BODY_LIMIT ? req.bodyStream() : req.bodyStream(options.bodyLimit);
        try {
            BodyFraming framing = bodyFraming(req, body, declaredFraming);
            String host = backendHost(receivedHost);
            // X-Forwarded-For and X-Forwarded-Proto (SEC-PROXY-002) join X-Forwarded-Host in the forwarding slot, and
            // the .requestHeader values (FR-PROXY-005) fill theirs (D-053).
            byte[] head = BackendRequest.head(req.methodToken() + " " + requestTarget + " HTTP/1.1", host,
                    BackendRequest.forwardedClientHeaders(req.rawHeaders()),
                    BackendRequest.forwardingHeaders(receivedHost),
                    Collections.<Map.Entry<String, String>>emptyList(), framing.header);
            return new Outgoing(head, body, framing);
        } catch (RuntimeException | Error e) {
            closeQuietly(body);
            throw e;
        }
    }

    /**
     * The request target written to the backend: the target's base path followed by the raw {@code url()}, the
     * remainder after the mount prefix, under {@code .stripPrefix(true)} and by the raw {@code originalUrl()}
     * otherwise (design D-052). On a mounted request the path of {@code url()} is {@code /} (a request to the mount
     * path itself) or a suffix of the raw path of {@code originalUrl()} that starts with {@code /}, followed by the
     * same raw query, so the path and the query go out exactly as the client encoded them and the
     * {@link PathChecker} check of {@code originalUrl()} covers them. A WebSocket upgrade to an {@code app.ws} route
     * reaches the middleware without a mount view: its {@code url()} is the percent-decoded full path. The guard
     * answers 400 before any backend connection when that decoded path differs from the raw one, so decoded
     * characters never reach the backend request line; the WebSocket upgrade check of FR-PROXY-009, which hands such
     * requests to {@code next()} first, is what keeps them away from this method altogether.
     */
    // @req FR-PROXY-006
    String requestTarget(Request req) {
        String original = req.originalUrl();
        if (!options.stripPrefix) {
            return target.basePath + original;
        }
        String remainder = req.url();
        int q = original.indexOf('?');
        String query = q < 0 ? "" : original.substring(q);
        String originalPath = q < 0 ? original : original.substring(0, q);
        if (!remainder.endsWith(query)) {
            throw new HttpException(400);
        }
        String remainderPath = remainder.substring(0, remainder.length() - query.length());
        if (!"/".equals(remainderPath)
                && (!remainderPath.startsWith("/") || !originalPath.endsWith(remainderPath))) {
            throw new HttpException(400);
        }
        return target.basePath + remainder;
    }

    /**
     * The {@code Host} value written to the backend: {@code receivedHost} under {@code .preserveHost(true)} when the
     * client sent one, and {@link TargetUrl#hostHeader} of the target otherwise.
     */
    // @req FR-PROXY-004
    String backendHost(@Nullable String receivedHost) {
        return options.preserveHost && receivedHost != null ? receivedHost : target.hostHeader();
    }

    /**
     * The framing of the body actually sent, for the {@code declaredFraming} {@link #requestBodyLength} decided:
     * chunked for a chunked body, the same Content-Length for a declared one, and for a request with neither header the
     * one-byte pre-read decides: no body (with {@code Content-Length: 0} for POST, PUT and PATCH only) when the stream
     * ends at once, chunked otherwise. The pre-read comes after {@code req.bodyStream} and before the backend connect,
     * the place of the concurrency slot and the total-timeout watchdog (design D-050, D-056). A declared length over
     * the {@code .bodyLimit} answers 413 here, before any connection.
     */
    // @req SEC-PROXY-003
    // @req FR-PROXY-007
    // @req FR-PROXY-008
    private BodyFraming bodyFraming(Request req, InputStream body, long declaredFraming) {
        if (declaredFraming == CHUNKED_BODY) {
            return new BodyFraming(true, 0, -1, CHUNKED_FRAMING);
        }
        if (declaredFraming == NO_FRAMING_HEADER) {
            int first = RequestBodyCopier.preRead(body);
            if (first >= 0) {
                return new BodyFraming(true, 0, first, CHUNKED_FRAMING);
            }
            Map.@Nullable Entry<String, String> emptyBodyHeader =
                    EMPTY_BODY_LENGTH_METHODS.contains(req.methodToken()) ? ZERO_LENGTH_FRAMING : null;
            return new BodyFraming(false, 0, -1, emptyBodyHeader);
        }
        if (options.bodyLimit != NO_BODY_LIMIT && declaredFraming > options.bodyLimit) {
            // req.bodyStream(n) already answers 413 for a declared length over n, a list of one agreed length such
            // as "2048, 2048" (SEC-PROXY-003) included; this guard keeps the refusal before any connection regardless.
            throw new HttpException(413);
        }
        return new BodyFraming(false, declaredFraming, -1,
                new SimpleImmutableEntry<>("Content-Length", Long.toString(declaredFraming)));
    }

    /** {@link #requestBodyLength(List, List)} of the request's Content-Length and Transfer-Encoding field lines. */
    // @req SEC-PROXY-003
    private static long requestBodyLength(Request req) {
        List<String> contentLengths = new ArrayList<>();
        List<String> transferEncodings = new ArrayList<>();
        for (Map.Entry<String, String> h : req.rawHeaders()) {
            String lower = h.getKey().toLowerCase(Locale.ROOT);
            if (lower.equals("content-length")) {
                contentLengths.add(h.getValue());
            } else if (lower.equals("transfer-encoding")) {
                transferEncodings.add(h.getValue());
            }
        }
        return requestBodyLength(contentLengths, transferEncodings);
    }

    /**
     * The declared body length, {@link #NO_FRAMING_HEADER} for no framing header, or {@link #CHUNKED_BODY} for a
     * chunked body; decided from the header lines alone, never from the request's HTTP version. Both headers, a
     * Content-Length list that is not one agreed number and a Transfer-Encoding list that does not end in a single
     * {@code chunked} answer 400; another coding before {@code chunked} answers 501.
     */
    // @req SEC-PROXY-003
    private static long requestBodyLength(List<String> contentLengths, List<String> transferEncodings) {
        if (!contentLengths.isEmpty() && !transferEncodings.isEmpty()) {
            throw new HttpException(400);
        }
        if (!transferEncodings.isEmpty()) {
            switch (FramingLists.transferEncoding(transferEncodings)) {
                case CHUNKED:
                    return CHUNKED_BODY;
                case UNSUPPORTED:
                    throw new HttpException(501);
                default:
                    throw new HttpException(400);
            }
        }
        if (contentLengths.isEmpty()) {
            return NO_FRAMING_HEADER;
        }
        long length = FramingLists.contentLength(contentLengths);
        if (length == FramingLists.INVALID_LENGTH) {
            throw new HttpException(400);
        }
        return length;
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

    /** How the client body is framed to the backend: the coding, the length, the pre-read byte and the header. */
    private static final class BodyFraming {
        /** Whether the body is sent in the chunked coding; otherwise exactly {@link #contentLength} bytes are sent. */
        final boolean chunked;
        final long contentLength;
        /** The byte the pre-read took from the body and that leads a chunked body, or -1. */
        final int firstByte;
        /** The framing header, or null for a body-less request that carries none. */
        final Map.@Nullable Entry<String, String> header;

        BodyFraming(boolean chunked, long contentLength, int firstByte, Map.@Nullable Entry<String, String> header) {
            this.chunked = chunked;
            this.contentLength = contentLength;
            this.firstByte = firstByte;
            this.header = header;
        }
    }

    /** The bytes of a backend request head and how the client body follows it. */
    private static final class Outgoing {
        final byte[] head;
        /** The client body stream from {@code req.bodyStream}; closed when the forward ends. */
        final InputStream body;
        /** How {@link #body} is framed to the backend. */
        final BodyFraming framing;

        Outgoing(byte[] head, InputStream body, BodyFraming framing) {
            this.head = head;
            this.body = body;
            this.framing = framing;
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
        /** The counter of {@code .maxConcurrent}, shared by reference with every derived proxy; null for no limit. */
        final @Nullable ConcurrencyLimiter limiter;
        final List<Map.Entry<String, @Nullable String>> requestHeaderRules;
        final List<Map.Entry<String, @Nullable String>> responseHeaderRules;
        /** The lower-case names whose later response rule is a removal: their backend lines are not copied. */
        final Set<String> hiddenResponseNames;
        /** The later {@code .responseHeader} call of each name not removed afterwards, as (name, value) in call order. */
        final List<Map.Entry<String, String>> fixedResponseHeaders;
        final boolean rewriteLocation;
        final @Nullable SSLSocketFactory sslSocketFactory;

        // @req IR-PROXY-001
        private Options(long connectTimeoutNanos, long writeTimeoutNanos, long readTimeoutNanos, long totalTimeoutNanos,
                int retries, long retryDelayNanos, boolean retryUnsafeMethods, boolean stripPrefix,
                boolean preserveHost, long bodyLimit, @Nullable ConcurrencyLimiter limiter,
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
            this.limiter = limiter;
            this.requestHeaderRules = requestHeaderRules;
            this.responseHeaderRules = responseHeaderRules;
            Map<String, Map.Entry<String, @Nullable String>> resolved = laterRuleOfEachName(responseHeaderRules);
            Set<String> hidden = new HashSet<>();
            List<Map.Entry<String, String>> fixed = new ArrayList<>();
            for (Map.Entry<String, Map.Entry<String, @Nullable String>> e : resolved.entrySet()) {
                String value = e.getValue().getValue();
                if (value == null) {
                    hidden.add(e.getKey());
                } else {
                    fixed.add(new SimpleImmutableEntry<>(e.getValue().getKey(), value));
                }
            }
            this.hiddenResponseNames = Collections.unmodifiableSet(hidden);
            this.fixedResponseHeaders = Collections.unmodifiableList(fixed);
            this.rewriteLocation = rewriteLocation;
            this.sslSocketFactory = sslSocketFactory;
        }

        /**
         * One ordered map from lower-case name to the later rule of that name (FR-PROXY-012): a later call for a name
         * replaces the earlier entry whatever its kind, names compare case-insensitively (RFC 9110 §5.1), and an
         * entry keeps the name as the later call wrote it with its value, null for a removal.
         */
        // @req FR-PROXY-012
        private static Map<String, Map.Entry<String, @Nullable String>> laterRuleOfEachName(
                List<Map.Entry<String, @Nullable String>> rules) {
            Map<String, Map.Entry<String, @Nullable String>> resolved = new LinkedHashMap<>();
            for (Map.Entry<String, @Nullable String> rule : rules) {
                String lower = rule.getKey().toLowerCase(Locale.ROOT);
                resolved.remove(lower);
                resolved.put(lower, rule);
            }
            return resolved;
        }

        /** Every option at its default. */
        // @req IR-PROXY-001
        static Options defaults() {
            return new Options(DEFAULT_CONNECT_TIMEOUT_NANOS, DEFAULT_WRITE_TIMEOUT_NANOS, DEFAULT_READ_TIMEOUT_NANOS,
                    DEFAULT_TOTAL_TIMEOUT_NANOS, 0, DEFAULT_RETRY_DELAY_NANOS, false, false, false, NO_BODY_LIMIT,
                    null, Collections.emptyList(), Collections.emptyList(), true, null);
        }

        Options withConnectTimeoutNanos(long v) {
            return new Options(v, writeTimeoutNanos, readTimeoutNanos, totalTimeoutNanos, retries, retryDelayNanos,
                    retryUnsafeMethods, stripPrefix, preserveHost, bodyLimit, limiter, requestHeaderRules,
                    responseHeaderRules, rewriteLocation, sslSocketFactory);
        }

        Options withWriteTimeoutNanos(long v) {
            return new Options(connectTimeoutNanos, v, readTimeoutNanos, totalTimeoutNanos, retries, retryDelayNanos,
                    retryUnsafeMethods, stripPrefix, preserveHost, bodyLimit, limiter, requestHeaderRules,
                    responseHeaderRules, rewriteLocation, sslSocketFactory);
        }

        Options withReadTimeoutNanos(long v) {
            return new Options(connectTimeoutNanos, writeTimeoutNanos, v, totalTimeoutNanos, retries, retryDelayNanos,
                    retryUnsafeMethods, stripPrefix, preserveHost, bodyLimit, limiter, requestHeaderRules,
                    responseHeaderRules, rewriteLocation, sslSocketFactory);
        }

        Options withTotalTimeoutNanos(long v) {
            return new Options(connectTimeoutNanos, writeTimeoutNanos, readTimeoutNanos, v, retries, retryDelayNanos,
                    retryUnsafeMethods, stripPrefix, preserveHost, bodyLimit, limiter, requestHeaderRules,
                    responseHeaderRules, rewriteLocation, sslSocketFactory);
        }

        Options withRetries(int v) {
            return new Options(connectTimeoutNanos, writeTimeoutNanos, readTimeoutNanos, totalTimeoutNanos, v,
                    retryDelayNanos, retryUnsafeMethods, stripPrefix, preserveHost, bodyLimit, limiter,
                    requestHeaderRules, responseHeaderRules, rewriteLocation, sslSocketFactory);
        }

        Options withRetryDelayNanos(long v) {
            return new Options(connectTimeoutNanos, writeTimeoutNanos, readTimeoutNanos, totalTimeoutNanos, retries, v,
                    retryUnsafeMethods, stripPrefix, preserveHost, bodyLimit, limiter, requestHeaderRules,
                    responseHeaderRules, rewriteLocation, sslSocketFactory);
        }

        Options withRetryUnsafeMethods(boolean v) {
            return new Options(connectTimeoutNanos, writeTimeoutNanos, readTimeoutNanos, totalTimeoutNanos, retries,
                    retryDelayNanos, v, stripPrefix, preserveHost, bodyLimit, limiter, requestHeaderRules,
                    responseHeaderRules, rewriteLocation, sslSocketFactory);
        }

        Options withStripPrefix(boolean v) {
            return new Options(connectTimeoutNanos, writeTimeoutNanos, readTimeoutNanos, totalTimeoutNanos, retries,
                    retryDelayNanos, retryUnsafeMethods, v, preserveHost, bodyLimit, limiter, requestHeaderRules,
                    responseHeaderRules, rewriteLocation, sslSocketFactory);
        }

        Options withPreserveHost(boolean v) {
            return new Options(connectTimeoutNanos, writeTimeoutNanos, readTimeoutNanos, totalTimeoutNanos, retries,
                    retryDelayNanos, retryUnsafeMethods, stripPrefix, v, bodyLimit, limiter, requestHeaderRules,
                    responseHeaderRules, rewriteLocation, sslSocketFactory);
        }

        Options withBodyLimit(long v) {
            return new Options(connectTimeoutNanos, writeTimeoutNanos, readTimeoutNanos, totalTimeoutNanos, retries,
                    retryDelayNanos, retryUnsafeMethods, stripPrefix, preserveHost, v, limiter,
                    requestHeaderRules, responseHeaderRules, rewriteLocation, sslSocketFactory);
        }

        /** A copy with a new counter of limit {@code v}: every other option method copies the counter reference. */
        // @req FR-PROXY-019
        Options withMaxConcurrent(int v) {
            return new Options(connectTimeoutNanos, writeTimeoutNanos, readTimeoutNanos, totalTimeoutNanos, retries,
                    retryDelayNanos, retryUnsafeMethods, stripPrefix, preserveHost, bodyLimit,
                    new ConcurrencyLimiter(v), requestHeaderRules,
                    responseHeaderRules, rewriteLocation, sslSocketFactory);
        }

        Options withRequestHeaderRules(List<Map.Entry<String, @Nullable String>> v) {
            return new Options(connectTimeoutNanos, writeTimeoutNanos, readTimeoutNanos, totalTimeoutNanos, retries,
                    retryDelayNanos, retryUnsafeMethods, stripPrefix, preserveHost, bodyLimit, limiter, v,
                    responseHeaderRules, rewriteLocation, sslSocketFactory);
        }

        Options withResponseHeaderRules(List<Map.Entry<String, @Nullable String>> v) {
            return new Options(connectTimeoutNanos, writeTimeoutNanos, readTimeoutNanos, totalTimeoutNanos, retries,
                    retryDelayNanos, retryUnsafeMethods, stripPrefix, preserveHost, bodyLimit, limiter,
                    requestHeaderRules, v, rewriteLocation, sslSocketFactory);
        }

        Options withRewriteLocation(boolean v) {
            return new Options(connectTimeoutNanos, writeTimeoutNanos, readTimeoutNanos, totalTimeoutNanos, retries,
                    retryDelayNanos, retryUnsafeMethods, stripPrefix, preserveHost, bodyLimit, limiter,
                    requestHeaderRules, responseHeaderRules, v, sslSocketFactory);
        }

        Options withSslSocketFactory(@Nullable SSLSocketFactory v) {
            return new Options(connectTimeoutNanos, writeTimeoutNanos, readTimeoutNanos, totalTimeoutNanos, retries,
                    retryDelayNanos, retryUnsafeMethods, stripPrefix, preserveHost, bodyLimit, limiter,
                    requestHeaderRules, responseHeaderRules, rewriteLocation, v);
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
