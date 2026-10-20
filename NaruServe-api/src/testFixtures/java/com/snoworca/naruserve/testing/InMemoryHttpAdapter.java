package com.snoworca.naruserve.testing;

import com.snoworca.naruserve.HttpException;
import com.snoworca.naruserve.spi.ExchangeHandler;
import com.snoworca.naruserve.spi.HttpAdapter;
import com.snoworca.naruserve.spi.HttpExchange;
import com.snoworca.naruserve.spi.StreamDrain;
import com.snoworca.naruserve.spi.UpgradeHandler;
import com.snoworca.naruserve.spi.WebSocketEndpoint;
import com.snoworca.naruserve.spi.WebSocketSettings;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Socketless {@link HttpAdapter}: requests go straight to the bound {@link ExchangeHandler} and the response is
 * captured in memory. Target handling mirrors TinyHttpd: authority-form CONNECT gets 501 without dispatch,
 * absolute-form targets keep only path and query, and {@code *} passes as raw path {@code *}.
 */
// @req FR-HTTP-001
@NullMarked
public final class InMemoryHttpAdapter implements HttpAdapter {

    private static final AtomicInteger NEXT_PORT = new AtomicInteger(49152);
    private static final long RELEASE_MARGIN_MS = 1000;
    /** How long {@link #send} waits for the core to end an exchange left open after handle() returned. */
    private static final long END_TIMEOUT_MS = 5000;
    private static final AtomicInteger THREADS = new AtomicInteger();

    private final String remoteAddress;
    private final boolean secure;
    private final ExecutorService workers = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "in-memory-http-" + THREADS.incrementAndGet());
        t.setDaemon(true);
        return t;
    });

    // Guarded by this.
    private final Set<Exchange> inFlight = new HashSet<>();
    private @Nullable ExchangeHandler handler;
    private int port;
    private boolean stopped;
    private @Nullable HttpException webSocketRefusal;
    private @Nullable Runnable beforeOpen;

    private final LinkedBlockingQueue<InMemoryWebSocketChannel> openedChannels = new LinkedBlockingQueue<>();
    private final LinkedBlockingQueue<InMemoryEventStream> eventStreams = new LinkedBlockingQueue<>();

    /** Remote address {@code 127.0.0.1}, not secure. */
    // @req FR-HTTP-001
    public InMemoryHttpAdapter() {
        this("127.0.0.1", false);
    }

    // @req FR-HTTP-001
    public InMemoryHttpAdapter(String remoteAddress, boolean secure) {
        this.remoteAddress = remoteAddress;
        this.secure = secure;
    }

    /** Port 0 takes the next in-memory port from a counter starting at 49152. */
    // @req IR-HTTP-007
    @Override
    public synchronized void bind(String host, int port, ExchangeHandler handler) {
        if (this.handler != null) {
            throw new IllegalStateException("already bound");
        }
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port " + port);
        }
        this.port = port == 0 ? NEXT_PORT.getAndIncrement() : port;
        this.handler = handler;
    }

    // @req IR-HTTP-007
    @Override
    public synchronized int boundPort() {
        if (handler == null) {
            throw new IllegalStateException("not bound");
        }
        return port;
    }

    // @req IR-HTTP-007
    @Override
    public void stop(long timeout, TimeUnit unit) {
        List<Exchange> rest;
        boolean interrupted = false;
        synchronized (this) {
            stopped = true;
            long deadline = System.nanoTime() + unit.toNanos(timeout);
            while (!inFlight.isEmpty()) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    break;
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(this, left);
                } catch (InterruptedException e) {
                    interrupted = true;
                    break;
                }
            }
            rest = new ArrayList<>(inFlight);
        }
        for (Exchange e : rest) {
            e.abort();
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** Shuts the worker pool, waiting at most 1 second for its threads. */
    // @req IR-HTTP-007
    @Override
    public void release() {
        workers.shutdown();
        try {
            if (!workers.awaitTermination(RELEASE_MARGIN_MS, TimeUnit.MILLISECONDS)) {
                workers.shutdownNow();
            }
        } catch (InterruptedException e) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /** Runs one exchange on the caller thread and returns what the core wrote. */
    // @req FR-HTTP-001
    public ClientResponse send(ClientRequest request) {
        Exchange exchange = new Exchange(request);
        ExchangeHandler h;
        synchronized (this) {
            h = handler;
            if (h == null) {
                throw new IllegalStateException("not bound");
            }
            if (stopped) {
                throw new IllegalStateException("stopped");
            }
            inFlight.add(exchange);
        }
        try {
            if (request.method.equals("CONNECT") && !request.target.startsWith("/")) {
                exchange.answerNotImplemented();
            } else {
                try {
                    h.handle(exchange);
                } catch (RuntimeException | Error e) {
                    exchange.abort();
                    throw e;
                }
                exchange.awaitDrain();
                // The core may still be copying on another thread (HttpExchange contract): wait for its finish or
                // abort, bounded so a test that never ends the exchange still returns, closed abnormally.
                exchange.awaitEnd(END_TIMEOUT_MS);
            }
            return exchange.response();
        } finally {
            synchronized (this) {
                inFlight.remove(exchange);
                notifyAll();
            }
        }
    }

    /** Runs one exchange on an adapter worker thread. */
    // @req FR-HTTP-001
    // @req IR-HTTP-007
    public Future<ClientResponse> submit(ClientRequest request) {
        return workers.submit(() -> send(request));
    }

    /** Every later {@code webSocket} call throws {@code e} before 101; null accepts handshakes again. */
    // @req IR-WS-002
    public synchronized void refuseWebSocket(@Nullable HttpException e) {
        webSocketRefusal = e;
    }

    /**
     * Every later {@code webSocket} call runs {@code hook} on its thread after 101 is recorded and the channel is
     * offered to {@link #nextChannel}, before {@code onOpen}; null removes it.
     */
    // @req FR-WS-009
    public synchronized void beforeOpen(@Nullable Runnable hook) {
        beforeOpen = hook;
    }

    /**
     * The next WebSocket channel opened by {@code webSocket}, in opening order.
     *
     * @throws AssertionError when none opened within the timeout
     */
    // @req IR-WS-002
    public InMemoryWebSocketChannel nextChannel(long timeout, TimeUnit unit) {
        return next(openedChannels, timeout, unit, "WebSocket channel opened");
    }

    /**
     * The next event stream started by {@code eventStream}, in start order.
     *
     * @throws AssertionError when none started within the timeout
     */
    // @req FR-SSE-002
    public InMemoryEventStream nextEventStream(long timeout, TimeUnit unit) {
        return next(eventStreams, timeout, unit, "event stream started");
    }

    private static <T> T next(BlockingQueue<T> queue, long timeout, TimeUnit unit, String what) {
        T item;
        try {
            item = queue.poll(timeout, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted waiting for: " + what, e);
        }
        if (item == null) {
            throw new AssertionError("no " + what + " within " + timeout + " " + unit);
        }
        return item;
    }

    /** Splits a request target into raw path and raw query (null without '?'), dropping an absolute-form prefix. */
    // @req FR-HTTP-001
    static @Nullable String[] splitTarget(String target) {
        String t = target;
        int scheme = t.indexOf("://");
        if (!t.startsWith("/") && scheme > 0) {
            int end = scheme + 3;
            while (end < t.length() && t.charAt(end) != '/' && t.charAt(end) != '?') {
                end++;
            }
            String rest = t.substring(end);
            t = rest.startsWith("/") ? rest : "/" + rest;
        }
        int q = t.indexOf('?');
        return q < 0 ? new String[] {t, null} : new String[] {t.substring(0, q), t.substring(q + 1)};
    }

    /** A request to send: method token, request target, headers in order and body. */
    // @req FR-HTTP-001
    public static final class ClientRequest {
        final String method;
        final String target;
        final List<Map.Entry<String, String>> headers = new ArrayList<>();
        byte[] body = new byte[0];

        private ClientRequest(String method, String target) {
            this.method = method;
            this.target = target;
        }

        // @req FR-HTTP-001
        public static ClientRequest of(String method, String target) {
            return new ClientRequest(method, target);
        }

        /** A request with a {@code Content-Type: application/json} header and {@code body} as UTF-8 bytes. */
        // @req FR-HTTP-001
        public static ClientRequest json(String method, String target, String body) {
            return of(method, target).header("Content-Type", "application/json")
                    .body(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        /** A GET upgrade request for {@code target} with the given extra header name/value pairs. */
        // @req FR-WS-001
        public static ClientRequest upgrade(String target, String... headerPairs) {
            ClientRequest r = of("GET", target).header("Connection", "Upgrade").header("Upgrade", "websocket");
            for (int i = 0; i < headerPairs.length; i += 2) {
                r.header(headerPairs[i], headerPairs[i + 1]);
            }
            return r;
        }

        /** Appends a header; duplicates are kept in order. */
        // @req FR-HTTP-001
        public ClientRequest header(String name, String value) {
            headers.add(new SimpleImmutableEntry<>(name, value));
            return this;
        }

        // @req FR-HTTP-001
        public ClientRequest body(byte[] body) {
            this.body = body.clone();
            return this;
        }
    }

    /** What the core wrote for one exchange. */
    // @req FR-HTTP-001
    public static final class ClientResponse {
        private final int status;
        private final List<Map.Entry<String, String>> headers;
        private final byte[] body;
        private final boolean abnormallyClosed;
        private final boolean upgradeHandedOver;
        private final long contentLength;

        ClientResponse(int status, List<Map.Entry<String, String>> headers, byte[] body, boolean abnormallyClosed,
                boolean upgradeHandedOver, long contentLength) {
            this.contentLength = contentLength;
            this.status = status;
            this.headers = Collections.unmodifiableList(new ArrayList<>(headers));
            this.body = body;
            this.abnormallyClosed = abnormallyClosed;
            this.upgradeHandedOver = upgradeHandedOver;
        }

        /** The status the core sent; 0 when it sent none. */
        public int status() {
            return status;
        }

        /** The first value of {@code name}, case-insensitive, or null. */
        public @Nullable String header(String name) {
            for (Map.Entry<String, String> e : headers) {
                if (e.getKey().equalsIgnoreCase(name)) {
                    return e.getValue();
                }
            }
            return null;
        }

        /** Response headers in the order sent, duplicates kept. */
        public List<Map.Entry<String, String>> headers() {
            return headers;
        }

        public byte[] body() {
            return body.clone();
        }

        /** The body decoded as UTF-8. */
        public String text() {
            return new String(body, java.nio.charset.StandardCharsets.UTF_8);
        }

        /** True when the exchange was aborted or ended without a normal finish. */
        public boolean abnormallyClosed() {
            return abnormallyClosed;
        }

        /** True when the core handed the exchange over through {@link HttpExchange#upgrade}. */
        public boolean upgradeHandedOver() {
            return upgradeHandedOver;
        }

        /**
         * The {@code contentLength} the core passed to {@link HttpExchange#commit}: -1 unknown, or -2 when the
         * response was not started by commit.
         */
        public long contentLength() {
            return contentLength;
        }
    }

    // @req FR-HTTP-001
    private final class Exchange implements HttpExchange {
        private final ClientRequest request;
        private final String rawPath;
        private final @Nullable String rawQuery;
        private final InputStream requestBody;
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();

        // Guarded by this.
        private int status;
        private List<Map.Entry<String, String>> responseHeaders = Collections.emptyList();
        private boolean started;
        private boolean ended;
        private boolean aborted;
        private boolean upgradeHandedOver;
        private long contentLength = -2;
        private @Nullable Future<?> drain;
        private @Nullable InMemoryWebSocketChannel webSocket;

        Exchange(ClientRequest request) {
            this.request = request;
            @Nullable String[] parts = splitTarget(request.target);
            this.rawPath = String.valueOf(parts[0]);
            this.rawQuery = parts[1];
            this.requestBody = new ByteArrayInputStream(request.body);
        }

        @Override
        public String methodToken() {
            return request.method;
        }

        @Override
        public String rawPath() {
            return rawPath;
        }

        @Override
        public @Nullable String rawQuery() {
            return rawQuery;
        }

        @Override
        public String httpVersion() {
            return "HTTP/1.1";
        }

        @Override
        public List<Map.Entry<String, String>> headers() {
            return Collections.unmodifiableList(request.headers);
        }

        @Override
        public InputStream requestBody() {
            return requestBody;
        }

        @Override
        public String remoteAddress() {
            return remoteAddress;
        }

        @Override
        public boolean isSecure() {
            return secure;
        }

        private synchronized void start(int status, List<Map.Entry<String, String>> headers) throws IOException {
            if (started) {
                throw new IllegalStateException("response already started");
            }
            if (aborted) {
                throw new IOException("exchange aborted");
            }
            started = true;
            this.status = status;
            this.responseHeaders = new ArrayList<>(headers);
        }

        private synchronized void write(byte[] b, int off, int len) throws IOException {
            if (ended) {
                throw new IOException(aborted ? "exchange aborted" : "exchange finished");
            }
            if (contentLength >= 0 && body.size() + len > contentLength) {
                // A framed connection cannot carry more than the committed length.
                throw new IOException("body exceeds the committed length " + contentLength);
            }
            body.write(b, off, len);
        }

        private OutputStream bodyStream() {
            return new OutputStream() {
                @Override
                public void write(int b) throws IOException {
                    Exchange.this.write(new byte[] {(byte) b}, 0, 1);
                }

                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    Exchange.this.write(b, off, len);
                }
            };
        }

        @Override
        public OutputStream commit(int status, List<Map.Entry<String, String>> headers, long contentLength)
                throws IOException {
            if (contentLength < -1) {
                throw new IllegalArgumentException("content length " + contentLength);
            }
            int lengths = 0;
            for (Map.Entry<String, String> h : headers) {
                if (h.getKey().equalsIgnoreCase("Content-Length")) {
                    lengths++;
                }
            }
            if (lengths > 1) {
                throw new IllegalArgumentException("more than one Content-Length header");
            }
            start(status, headers);
            synchronized (this) {
                this.contentLength = contentLength;
            }
            return bodyStream();
        }

        @Override
        public synchronized void finish() throws IOException {
            if (!started) {
                throw new IllegalStateException("response not started");
            }
            if (aborted) {
                throw new IOException("exchange aborted");
            }
            ended = true;
            notifyAll();
        }

        @Override
        public void abort() {
            InMemoryWebSocketChannel ws = null;
            synchronized (this) {
                if (!ended) {
                    ended = true;
                    aborted = true;
                    notifyAll();
                    ws = webSocket;
                }
            }
            // The adapter's stop deadline or a failing handler also loses an open WebSocket.
            if (ws != null) {
                ws.abort();
            }
        }

        @Override
        public void sendFile(int status, List<Map.Entry<String, String>> headers, File file, long offset,
                long length) throws IOException {
            if (offset < 0 || length < 0 || offset + length > file.length()) {
                throw new IllegalArgumentException("range " + offset + "+" + length + " of " + file.length());
            }
            start(status, headers);
            try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
                in.seek(offset);
                byte[] buf = new byte[8192];
                long left = length;
                while (left > 0) {
                    int n = (int) Math.min(buf.length, left);
                    in.readFully(buf, 0, n);
                    write(buf, 0, n);
                    left -= n;
                }
            } catch (IOException e) {
                abort();
                throw e;
            }
            finish();
        }

        @Override
        public void upgrade(List<Map.Entry<String, String>> headers, UpgradeHandler handler) throws IOException {
            start(101, headers);
            synchronized (this) {
                upgradeHandedOver = true;
            }
            try {
                handler.upgraded(requestBody, bodyStream());
            } catch (IOException | RuntimeException e) {
                abort();
                throw e;
            }
            finish();
        }

        @Override
        public void eventStream(int status, List<Map.Entry<String, String>> headers, StreamDrain drain)
                throws IOException {
            start(status, headers);
            InMemoryEventStream es = new InMemoryEventStream(bodyStream());
            OutputStream out = es.stream();
            eventStreams.add(es);
            try {
                es.attachedTo(out);
                drain.attach(out);
            } catch (RuntimeException e) {
                abort();
                throw e;
            }
            Future<?> f = workers.submit(() -> {
                try {
                    es.drainedTo(out);
                    drain.drain(out);
                    finish();
                } catch (IOException | RuntimeException e) {
                    abort();
                }
            });
            synchronized (this) {
                this.drain = f;
            }
        }

        /** Records 101, opens a channel and runs its read loop on this thread until the connection closed. */
        // @req IR-WS-002
        @Override
        public void webSocket(WebSocketSettings settings, WebSocketEndpoint endpoint) throws IOException {
            synchronized (this) {
                if (started) {
                    throw new IllegalStateException("response already started");
                }
            }
            HttpException refusal;
            Runnable hook;
            synchronized (InMemoryHttpAdapter.this) {
                refusal = webSocketRefusal;
                hook = beforeOpen;
            }
            if (refusal != null) {
                throw refusal;
            }
            start(101, Collections.<Map.Entry<String, String>>emptyList());
            InMemoryWebSocketChannel channel = new InMemoryWebSocketChannel(settings);
            boolean abortedBeforeOpen;
            synchronized (this) {
                webSocket = channel;
                abortedBeforeOpen = aborted;
            }
            if (abortedBeforeOpen) {
                channel.abort();
            }
            openedChannels.add(channel);
            try {
                if (hook != null) {
                    hook.run();
                }
                channel.run(endpoint);
            } catch (RuntimeException | Error e) {
                abort();
                throw e;
            }
            if (channel.aborted()) {
                abort();
                return;
            }
            // Like finish(), but a stop deadline that aborted the exchange after onClose is not an error here.
            synchronized (this) {
                if (!ended) {
                    ended = true;
                    notifyAll();
                }
            }
        }

        synchronized void answerNotImplemented() {
            started = true;
            ended = true;
            status = 501;
            responseHeaders = Collections.<Map.Entry<String, String>>singletonList(
                    new SimpleImmutableEntry<>("Content-Length", "0"));
        }

        /** Waits for an event-stream drain to return. */
        void awaitDrain() {
            Future<?> f;
            synchronized (this) {
                f = drain;
            }
            if (f == null) {
                return;
            }
            try {
                f.get();
            } catch (ExecutionException e) {
                abort();
            } catch (InterruptedException e) {
                abort();
                Thread.currentThread().interrupt();
            }
        }

        /** Waits up to {@code timeoutMs} for finish or abort, then aborts an exchange still open. */
        synchronized void awaitEnd(long timeoutMs) {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            try {
                while (!ended) {
                    long left = deadline - System.nanoTime();
                    if (left <= 0) {
                        break;
                    }
                    TimeUnit.NANOSECONDS.timedWait(this, left);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            abort();
        }

        synchronized ClientResponse response() {
            return new ClientResponse(status, responseHeaders, body.toByteArray(), aborted, upgradeHandedOver,
                    contentLength);
        }
    }
}
