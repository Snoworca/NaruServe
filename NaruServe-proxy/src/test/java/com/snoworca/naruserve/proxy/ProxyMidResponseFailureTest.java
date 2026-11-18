package com.snoworca.naruserve.proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.Response;
import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.testing.StubJsonCodec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.After;
import org.junit.Test;

/**
 * A backend failure after the response was handed to {@code res.relay} (FR-PROXY-016): a body shorter than its
 * {@code Content-Length}, a malformed chunked body and a read timeout between body bytes are logged once to the app
 * logger as a {@link ProxyException} of their kind, and then the relayed stream throws an {@link IOException} caused by
 * that {@code ProxyException}, so the framework aborts the client connection; the proxy never calls
 * {@code res.abort} itself. The backends are raw sockets ({@link RawBackend}); a backend "kept open" holds its
 * connection after what it wrote until the test ends.
 */
public class ProxyMidResponseFailureTest {

    /** The read timeout of the AC-3 proxies, in milliseconds. */
    private static final long READ_TIMEOUT_MILLIS = 1000;
    /** The read timeout of every other proxy: a failure that is not a timeout arrives well within it. */
    private static final long LONG_READ_TIMEOUT_MILLIS = 20_000;
    /** How long a test waits for a log record written on the copy thread. */
    private static final long WAIT_MILLIS = 5000;

    private final InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
    /** Every log call and every exception the recording Response saw leave the relayed stream, in order. */
    private final List<String> events = Collections.synchronizedList(new ArrayList<String>());
    private final OrderedLogger log = new OrderedLogger(events);
    private final RestApp app =
            RestApp.builder().httpAdapter(adapter).jsonCodec(new StubJsonCodec()).logger(log).build();
    private final List<Closeable> closeables = new ArrayList<>();
    /** Released at tear-down: backends kept open wait on it before their connection closes. */
    private final CountDownLatch hold = new CountDownLatch(1);
    private boolean started;
    private int routes;

    @After
    public void tearDown() throws IOException {
        hold.countDown();
        if (started) {
            app.shutdown(2, TimeUnit.SECONDS);
        }
        for (Closeable c : closeables) {
            c.close();
        }
    }

    // ---- helpers ----

    /** A {@link RecordingLogger} that also appends {@code logged <kind>} to the shared event list for each error. */
    static final class OrderedLogger extends RecordingLogger {
        private final List<String> events;

        OrderedLogger(List<String> events) {
            this.events = events;
        }

        @Override
        public void error(String message, Throwable error) {
            events.add("logged " + (error instanceof ProxyException ? ((ProxyException) error).kind() : error));
            super.error(message, error);
        }
    }

    /** A backend that writes {@code raw} (ISO-8859-1) and closes the connection. */
    private RawBackend closing(String raw) throws IOException {
        RawBackend b = RawBackend.answering(raw);
        closeables.add(b);
        return b;
    }

    /** A backend that writes {@code raw} (ISO-8859-1) and then sends nothing more until the test ends. */
    private RawBackend keptOpen(String raw) throws IOException {
        final byte[] bytes = raw.getBytes(StandardCharsets.ISO_8859_1);
        RawBackend b = RawBackend.start((index, request, out) -> {
            out.write(bytes);
            out.flush();
            try {
                hold.await(60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        closeables.add(b);
        return b;
    }

    /** Mounts {@code proxy} at a new path and returns that path. */
    private String mount(Proxy proxy) {
        String path = "/m" + routes++;
        app.use(path, proxy);
        return path;
    }

    private void start() {
        app.listen(0);
        started = true;
    }

    private ClientResponse get(String path) {
        return adapter.send(ClientRequest.of("GET", path + "/x"));
    }

    /** The ProxyExceptions logged through {@code error(String, Throwable)}, after waiting for at least {@code n}. */
    private List<ProxyException> awaitLogged(int n) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MILLIS);
        List<ProxyException> logged = loggedProxyExceptions();
        while (logged.size() < n && System.nanoTime() < end) {
            Thread.sleep(5);
            logged = loggedProxyExceptions();
        }
        return logged;
    }

    private List<ProxyException> loggedProxyExceptions() {
        List<ProxyException> out = new ArrayList<>();
        synchronized (log.errors) {
            for (Throwable t : log.errors) {
                if (t instanceof ProxyException) {
                    out.add((ProxyException) t);
                }
            }
        }
        return out;
    }

    /** Checks the app logger received exactly one ProxyException, of {@code kind}, and nothing else as an error. */
    private void assertLoggedOnce(ProxyException.Kind kind) throws InterruptedException {
        List<ProxyException> logged = awaitLogged(1);
        assertEquals("ProxyExceptions logged: " + log.lines, 1, logged.size());
        assertEquals(kind, logged.get(0).kind());
        assertEquals("error(String, Throwable) calls: " + log.lines, 1, log.errors.size());
    }

    private static String pattern(int n) {
        char[] chars = new char[n];
        for (int i = 0; i < n; i++) {
            chars[i] = (char) ('a' + i % 26);
        }
        return new String(chars);
    }

    /** {@code n} trailer lines of about 1 KiB each, each ending in CRLF. */
    private static String trailerLines(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append("X-T").append(i).append(": ").append(pattern(1000)).append("\r\n");
        }
        return sb.toString();
    }

    /** A chunked body whose second chunk-size line is longer than 4 KiB. */
    private static final String LONG_CHUNK_SIZE_LINE = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
            + "5\r\nhello\r\n" + "a;" + pattern(5000) + "\r\n" + "0123456789\r\n0\r\n\r\n";
    /** A chunked body whose trailer section is larger than 64 KiB. */
    private static final String LARGE_TRAILER = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
            + "5\r\nhello\r\n0\r\n" + trailerLines(70) + "\r\n";
    /** A Content-Length: 100 response with 50 body bytes. */
    private static final String SHORT_BODY = "HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\n" + pattern(50);

    // ---------------------------------------------------------------------------------------------------------------
    // AC-1 to AC-3: the client connection is aborted and the app logger receives the kind

    // FR-PROXY-016 AC-1: Content-Length: 100, then 50 body bytes and a close: the client connection is closed before
    // 100 body bytes arrive, and the app logger receives a ProxyException INVALID_RESPONSE
    @Test(timeout = 30000)
    public void fr_PROXY_016_AC1_backendClosingBeforeItsLengthAbortsTheClientAndLogsInvalidResponse()
            throws Exception {
        String path = mount(Proxy.to(closing(SHORT_BODY).url())
                .readTimeout(LONG_READ_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
        start();

        ClientResponse r = get(path);

        assertEquals(200, r.status());
        assertTrue("the client connection was aborted", r.abnormallyClosed());
        assertTrue("body bytes that arrived: " + r.body().length, r.body().length < 100);
        assertLoggedOnce(ProxyException.Kind.INVALID_RESPONSE);
    }

    // FR-PROXY-016 AC-2: a chunked body breaking with a chunk-size line longer than 4 KiB aborts the client before the
    // chunked body completes, and the app logger receives a ProxyException INVALID_RESPONSE
    @Test(timeout = 30000)
    public void fr_PROXY_016_AC2_chunkSizeLineOver4KiBAbortsTheClientAndLogsInvalidResponse() throws Exception {
        String path = mount(Proxy.to(closing(LONG_CHUNK_SIZE_LINE).url())
                .readTimeout(LONG_READ_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
        start();

        ClientResponse r = get(path);

        assertEquals(200, r.status());
        assertTrue("the client connection was aborted", r.abnormallyClosed());
        assertFalse("the chunked body never completed", r.text().contains("0123456789"));
        assertLoggedOnce(ProxyException.Kind.INVALID_RESPONSE);
    }

    // FR-PROXY-016 AC-2: a chunked body breaking with a trailer section larger than 64 KiB aborts the client before
    // the chunked body completes, and the app logger receives a ProxyException INVALID_RESPONSE
    @Test(timeout = 30000)
    public void fr_PROXY_016_AC2_trailerOver64KiBAbortsTheClientAndLogsInvalidResponse() throws Exception {
        String path = mount(Proxy.to(closing(LARGE_TRAILER).url())
                .readTimeout(LONG_READ_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
        start();

        ClientResponse r = get(path);

        assertEquals(200, r.status());
        assertTrue("the client connection was aborted", r.abnormallyClosed());
        assertLoggedOnce(ProxyException.Kind.INVALID_RESPONSE);
    }

    // FR-PROXY-016 AC-3: with .readTimeout(1, SECONDS), a backend that sends its header block and part of the body and
    // then stops sending makes the client connection abort after about 1 second, and the app logger receives a
    // ProxyException READ_TIMEOUT
    @Test(timeout = 30000)
    public void fr_PROXY_016_AC3_stalledBodyAbortsTheClientAfterTheReadTimeoutAndLogsReadTimeout() throws Exception {
        String path = mount(Proxy.to(keptOpen("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\n" + pattern(10)).url())
                .readTimeout(1, TimeUnit.SECONDS));
        start();

        long t0 = System.nanoTime();
        ClientResponse r = get(path);
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);

        assertEquals(200, r.status());
        assertTrue("the client connection was aborted", r.abnormallyClosed());
        assertTrue("aborted after " + elapsed + " ms", elapsed >= READ_TIMEOUT_MILLIS - 100);
        assertTrue("aborted after " + elapsed + " ms", elapsed < READ_TIMEOUT_MILLIS + 4000);
        assertLoggedOnce(ProxyException.Kind.READ_TIMEOUT);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // AC-4 and AC-5: what a recording Response sees of the relayed stream

    /** What the recording Response of one mount saw. */
    static final class Seen {
        /** The names of the Response methods the proxy called, in order. */
        final List<String> calls = Collections.synchronizedList(new ArrayList<String>());
        /** The first exception a read of the relayed stream threw, or null. */
        volatile @Nullable IOException first;
        /** The exception a second read after the first failure threw, or null. */
        volatile @Nullable IOException second;
        /** The abort listener the proxy registered, or null. */
        volatile @Nullable Runnable abortListener;
    }

    /**
     * Mounts {@code proxy} behind a recording {@code Response}: it records every method the proxy calls, reads the
     * stream passed to {@code res.relay} on the handler thread until it ends or throws (running the proxy's abort
     * listener first, after {@code abortAfter} bytes, when that is not negative), appends {@code thrown} to the event
     * list when a read throws and reads once more, and then relays a stream that fails in the same way, so the
     * framework aborts the client connection.
     */
    private String mountRecording(Proxy proxy, Seen seen, int abortAfter) {
        String path = "/m" + routes++;
        app.use(path, (req, res, next) -> {
            InvocationHandler h = (p, method, args) -> {
                seen.calls.add(method.getName());
                if (method.getName().equals("onAbort")) {
                    seen.abortListener = (Runnable) args[0];
                } else if (method.getName().equals("relay")) {
                    InputStream in = (InputStream) args[0];
                    ByteArrayOutputStream read = new ByteArrayOutputStream();
                    byte[] buf = new byte[1024];
                    try {
                        for (int n; (n = in.read(buf)) >= 0; ) {
                            read.write(buf, 0, n);
                            Runnable listener = seen.abortListener;
                            if (abortAfter >= 0 && read.size() >= abortAfter && listener != null) {
                                listener.run(); // the client went away
                            }
                        }
                    } catch (IOException e) {
                        events.add("thrown");
                        seen.first = e;
                        try {
                            in.read(buf);
                        } catch (IOException again) {
                            seen.second = again;
                        }
                    } finally {
                        in.close();
                    }
                    IOException failure = seen.first;
                    InputStream relayed = failure == null ? new ByteArrayInputStream(read.toByteArray())
                            : new InputStream() {
                                @Override
                                public int read() throws IOException {
                                    throw failure;
                                }
                            };
                    res.relay(relayed, failure == null ? (Long) args[1] : -1L);
                    return null;
                }
                try {
                    return method.invoke(res, args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            };
            Response recording = (Response) java.lang.reflect.Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[] {Response.class}, h);
            proxy.handle(req, recording, next);
        });
        return path;
    }

    private static ProxyException causeOf(@Nullable IOException e) {
        assertNotNull("a read of the relayed stream threw", e);
        assertTrue("cause of " + e + ": " + e.getCause(), e.getCause() instanceof ProxyException);
        return (ProxyException) e.getCause();
    }

    // FR-PROXY-016 AC-4: a body shorter than its Content-Length makes read of the relayed stream throw an IOException
    // whose cause is a ProxyException INVALID_RESPONSE, and the proxy never calls res.abort
    @Test(timeout = 30000)
    public void fr_PROXY_016_AC4_shortBodyThrowsIoExceptionCausedByInvalidResponseWithoutAbort() throws Exception {
        Seen seen = new Seen();
        String path = mountRecording(Proxy.to(closing(SHORT_BODY).url())
                .readTimeout(LONG_READ_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS), seen, -1);
        start();

        ClientResponse r = get(path);

        assertEquals(ProxyException.Kind.INVALID_RESPONSE, causeOf(seen.first).kind());
        assertTrue("the client connection was aborted", r.abnormallyClosed());
        assertFalse("Response calls of the proxy: " + seen.calls, seen.calls.contains("abort"));
    }

    // FR-PROXY-016 AC-4: a read timeout between body bytes makes read of the relayed stream throw an IOException whose
    // cause is a ProxyException READ_TIMEOUT, and the proxy never calls res.abort
    @Test(timeout = 30000)
    public void fr_PROXY_016_AC4_readTimeoutThrowsIoExceptionCausedByReadTimeoutWithoutAbort() throws Exception {
        Seen seen = new Seen();
        String path = mountRecording(
                Proxy.to(keptOpen("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\n" + pattern(10)).url())
                        .readTimeout(READ_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS), seen, -1);
        start();

        ClientResponse r = get(path);

        assertEquals(ProxyException.Kind.READ_TIMEOUT, causeOf(seen.first).kind());
        assertTrue("the client connection was aborted", r.abnormallyClosed());
        assertFalse("Response calls of the proxy: " + seen.calls, seen.calls.contains("abort"));
    }

    // FR-PROXY-016 AC-4: a malformed chunked body makes read of the relayed stream throw an IOException whose cause is
    // a ProxyException INVALID_RESPONSE, and the proxy never calls res.abort
    @Test(timeout = 30000)
    public void fr_PROXY_016_AC4_malformedChunkThrowsIoExceptionCausedByInvalidResponseWithoutAbort()
            throws Exception {
        Seen seen = new Seen();
        String path = mountRecording(Proxy.to(closing(LONG_CHUNK_SIZE_LINE).url())
                .readTimeout(LONG_READ_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS), seen, -1);
        start();

        get(path);

        assertEquals(ProxyException.Kind.INVALID_RESPONSE, causeOf(seen.first).kind());
        assertFalse("Response calls of the proxy: " + seen.calls, seen.calls.contains("abort"));
    }

    // FR-PROXY-016 AC-5: a body shorter than its Content-Length is logged exactly once through error(String,
    // Throwable) with the ProxyException the IOException carries, before the IOException leaves the stream, and a
    // second read neither logs again nor stops failing
    @Test(timeout = 30000)
    public void fr_PROXY_016_AC5_shortBodyIsLoggedOnceBeforeTheIoExceptionLeaves() throws Exception {
        Seen seen = new Seen();
        String path = mountRecording(Proxy.to(closing(SHORT_BODY).url())
                .readTimeout(LONG_READ_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS), seen, -1);
        start();

        get(path);

        ProxyException cause = causeOf(seen.first);
        synchronized (events) {
            assertEquals("log and throw order", 2, events.size());
            assertEquals("logged INVALID_RESPONSE", events.get(0));
            assertEquals("thrown", events.get(1));
        }
        assertEquals("error(String, Throwable) calls: " + log.lines, 1, log.errors.size());
        assertSame("the logged throwable is the cause of the IOException", cause, log.errors.get(0));
        assertSame("a second read fails with the same ProxyException", cause, causeOf(seen.second));
    }

    // FR-PROXY-016 AC-5: a read timeout between body bytes is logged exactly once through error(String, Throwable)
    // with the ProxyException READ_TIMEOUT, before the IOException leaves the stream
    @Test(timeout = 30000)
    public void fr_PROXY_016_AC5_readTimeoutIsLoggedOnceBeforeTheIoExceptionLeaves() throws Exception {
        Seen seen = new Seen();
        String path = mountRecording(
                Proxy.to(keptOpen("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\n" + pattern(10)).url())
                        .readTimeout(READ_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS), seen, -1);
        start();

        get(path);

        ProxyException cause = causeOf(seen.first);
        synchronized (events) {
            assertEquals("log and throw order", 2, events.size());
            assertEquals("logged READ_TIMEOUT", events.get(0));
            assertEquals("thrown", events.get(1));
        }
        assertEquals("error(String, Throwable) calls: " + log.lines, 1, log.errors.size());
        assertSame("the logged throwable is the cause of the IOException", cause, log.errors.get(0));
    }

    // FR-PROXY-016 AC-5: a malformed chunk, whose failure already carries a ProxyException from the chunked decoder,
    // keeps its kind and is logged exactly once, before the IOException leaves the stream; a second read does not log
    // again
    @Test(timeout = 30000)
    public void fr_PROXY_016_AC5_malformedChunkIsLoggedOnceWithItsKind() throws Exception {
        Seen seen = new Seen();
        String path = mountRecording(Proxy.to(closing(LARGE_TRAILER).url())
                .readTimeout(LONG_READ_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS), seen, -1);
        start();

        get(path);

        ProxyException cause = causeOf(seen.first);
        assertEquals(ProxyException.Kind.INVALID_RESPONSE, cause.kind());
        synchronized (events) {
            assertEquals("log and throw order", 2, events.size());
            assertEquals("logged INVALID_RESPONSE", events.get(0));
            assertEquals("thrown", events.get(1));
        }
        assertEquals("error(String, Throwable) calls: " + log.lines, 1, log.errors.size());
        assertSame("the logged throwable is the cause of the IOException", cause, log.errors.get(0));
        assertNotNull("a second read still fails", seen.second);
    }

    // FR-PROXY-016 AC-5: a read that fails because the client went away (the proxy's abort listener closed the
    // backend connection) is not a backend failure: nothing is logged as an error
    @Test(timeout = 30000)
    public void fr_PROXY_016_AC5_clientAbortIsNotLoggedAsABackendFailure() throws Exception {
        Seen seen = new Seen();
        String path = mountRecording(
                Proxy.to(keptOpen("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\n" + pattern(10)).url())
                        .readTimeout(LONG_READ_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS), seen, 10);
        start();

        get(path);

        assertNotNull("the read after the abort listener ran failed", seen.first);
        assertEquals("error(String, Throwable) calls: " + log.lines, 0, log.errors.size());
        assertFalse("Response calls of the proxy: " + seen.calls, seen.calls.contains("abort"));
    }
}
