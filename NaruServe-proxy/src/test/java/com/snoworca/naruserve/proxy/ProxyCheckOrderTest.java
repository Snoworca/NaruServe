package com.snoworca.naruserve.proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.HttpException;
import com.snoworca.naruserve.Middleware;
import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.testing.StubJsonCodec;
import com.snoworca.naruserve.testing.TestApp;
import com.snoworca.naruserve.tinyhttpd.TinyHttpdAdapter;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Test;

/**
 * The proxy's check order (FR-PROXY-010): a request that fails several checks gets the answer of the first one in
 * the order upgrade, method token, CONNECT and TRACE, request target and path, headers, framing,
 * {@code req.bodyStream(limit)}, concurrency slot; and the proxy's own 400, 405 and 501 reach the error flow as a plain
 * {@code HttpException} with a null message. The AC-1 to AC-5 tests pair the request that fails two checks with a
 * contrast request that fails only one of them, so each shows which check wins, not only which status comes back.
 */
public class ProxyCheckOrderTest {

    private static final byte[] HELLO = "hello".getBytes(StandardCharsets.ISO_8859_1);
    /** The request header that makes {@link #earlyBodyReader} read one byte of the body before the proxy runs. */
    private static final String READ_EARLY = "X-Read-Early";

    private final InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
    private final TestApp t = TestApp.create(adapter);
    private final RestApp app = t.app();
    private final List<Closeable> closeables = new ArrayList<>();
    private final List<RestApp> started = new ArrayList<>();
    private final List<CountDownLatch> releases = new ArrayList<>();
    /** The errors that reached the app's onError, in order. */
    private final List<Throwable> errors = Collections.synchronizedList(new ArrayList<Throwable>());

    @After
    public void tearDown() throws IOException {
        for (CountDownLatch release : releases) {
            release.countDown();
        }
        for (RestApp a : started) {
            a.shutdown(2, TimeUnit.SECONDS);
        }
        for (Closeable c : closeables) {
            c.close();
        }
    }

    /** A backend that answers 200 with body {@code ok} and counts the requests it read. */
    private RawBackend okBackend() throws IOException {
        RawBackend b = RawBackend.answering(RawBackend.response(200, "ok"));
        closeables.add(b);
        return b;
    }

    private void start() {
        t.start();
        started.add(app);
    }

    /** Registers, after the mounts it covers, an onError handler that records the error and answers its status. */
    private void recordErrors(RestApp a) {
        a.onError((error, req, res) -> {
            errors.add(error);
            res.sendStatus(error instanceof HttpException ? ((HttpException) error).status() : 500);
        });
    }

    /** A middleware that reads one byte of the body of a request carrying {@link #READ_EARLY}, then calls next. */
    private static Middleware earlyBodyReader() {
        return (req, res, next) -> {
            if (req.header(READ_EARLY) != null) {
                assertEquals('h', req.bodyStream().read());
            }
            next.run();
        };
    }

    /** {@code method target} with the given header name/value pairs, in order, and {@code body}. */
    private static ClientRequest request(String method, String target, byte[] body, String... headerPairs) {
        ClientRequest r = ClientRequest.of(method, target);
        for (int i = 0; i < headerPairs.length; i += 2) {
            r.header(headerPairs[i], headerPairs[i + 1]);
        }
        return r.body(body);
    }

    // ---- AC-1 ----

    // FR-PROXY-010 AC-1: a WebSocket upgrade to /api/orders//live goes to next() and is not answered 400, while the
    // same path without the upgrade is 400 from the path check: the upgrade check runs first
    @Test
    public void fr_PROXY_010_AC1_anUpgradeToAnEmptySegmentPathGoesToNextBeforeThePathCheck() throws IOException {
        RawBackend backend = okBackend();
        app.use("/api/orders", Proxy.to(backend.url()));
        app.use("/api/orders", (req, res, next) -> res.status(200).send("after proxy"));
        start();

        ClientResponse upgrade = adapter.send(ClientRequest.upgrade("/api/orders//live"));
        ClientResponse plain = adapter.send(ClientRequest.of("GET", "/api/orders//live"));

        assertEquals("upgrade status", 200, upgrade.status());
        assertEquals("after proxy", upgrade.text());
        assertEquals("the same path without the upgrade", 400, plain.status());
        assertEquals("backend requests", 0, backend.requestCount());
    }

    // ---- AC-2 ----

    // FR-PROXY-010 AC-2: TRACE /api/orders/../x is 405 with Allow, not 400, while GET to the same path is 400: the
    // method checks run before the path check
    @Test
    public void fr_PROXY_010_AC2_traceToADotDotPathIs405BeforeThePathCheck() throws IOException {
        RawBackend backend = okBackend();
        app.use("/api/orders", Proxy.to(backend.url()));
        start();

        ClientResponse trace = adapter.send(ClientRequest.of("TRACE", "/api/orders/../x"));
        ClientResponse get = adapter.send(ClientRequest.of("GET", "/api/orders/../x"));

        assertEquals("TRACE status", 405, trace.status());
        assertEquals("Allow", "GET, HEAD, POST, PUT, DELETE, PATCH, OPTIONS", trace.header("Allow"));
        assertEquals("GET to the same path", 400, get.status());
        assertEquals("backend requests", 0, backend.requestCount());
    }

    // ---- AC-3 ----

    // FR-PROXY-010 AC-3: POST /api/orders/%2e%2e/x with Transfer-Encoding: gzip, chunked is 400, not 501, while the
    // same framing on a clean path is 501: the path check runs before the framing check
    @Test
    public void fr_PROXY_010_AC3_anEncodedDotDotPathWithAnUnsupportedCodingIs400BeforeTheFramingCheck()
            throws IOException {
        RawBackend backend = okBackend();
        app.use("/api/orders", Proxy.to(backend.url()));
        start();

        ClientResponse both = adapter.send(
                request("POST", "/api/orders/%2e%2e/x", HELLO, "Transfer-Encoding", "gzip, chunked"));
        ClientResponse framingOnly = adapter.send(
                request("POST", "/api/orders/x", HELLO, "Transfer-Encoding", "gzip, chunked"));

        assertEquals("path and framing both fail", 400, both.status());
        assertEquals("framing alone fails", 501, framingOnly.status());
        assertEquals("backend requests", 0, backend.requestCount());
    }

    // ---- AC-4 ----

    // FR-PROXY-010 AC-4: a header value holding NUL with Transfer-Encoding: gzip, chunked is 400, not 501, while the
    // same framing with clean headers is 501: the header check runs before the framing check
    @Test
    public void fr_PROXY_010_AC4_aNulHeaderValueWithAnUnsupportedCodingIs400BeforeTheFramingCheck()
            throws IOException {
        RawBackend backend = okBackend();
        app.use("/api/orders", Proxy.to(backend.url()));
        start();

        ClientResponse both = adapter.send(request("POST", "/api/orders/x", HELLO,
                "X-A", "a\u0000b", "Transfer-Encoding", "gzip, chunked"));
        ClientResponse framingOnly = adapter.send(request("POST", "/api/orders/x", HELLO,
                "X-A", "ab", "Transfer-Encoding", "gzip, chunked"));

        assertEquals("header and framing both fail", 400, both.status());
        assertEquals("framing alone fails", 501, framingOnly.status());
        assertEquals("backend requests", 0, backend.requestCount());
    }

    // ---- AC-5 ----

    // FR-PROXY-010 AC-5: on a .bodyLimit(1) mount, the two lines Content-Length: 5 and Content-Length: 6 are 400, not
    // 413, while one Content-Length: 5 line is 413: the framing check runs before req.bodyStream(limit)
    @Test
    public void fr_PROXY_010_AC5_conflictingLengthsOverTheBodyLimitAre400BeforeBodyStream() throws IOException {
        RawBackend backend = okBackend();
        app.use("/api/orders", Proxy.to(backend.url()).bodyLimit(1));
        start();

        ClientResponse conflicting = adapter.send(
                request("POST", "/api/orders/x", HELLO, "Content-Length", "5", "Content-Length", "6"));
        ClientResponse overLimit = adapter.send(request("POST", "/api/orders/x", HELLO, "Content-Length", "5"));

        assertEquals("conflicting lengths over the limit", 400, conflicting.status());
        assertEquals("one length over the limit", 413, overLimit.status());
        assertEquals("backend requests", 0, backend.requestCount());
    }

    // FR-PROXY-010 AC-5: Transfer-Encoding: gzip, chunked on a body an earlier middleware already read is 501, not
    // 500, while Transfer-Encoding: chunked on such a body is 500: the framing check runs before req.bodyStream(limit)
    @Test
    public void fr_PROXY_010_AC5_anUnsupportedCodingOnABodyAlreadyReadIs501BeforeBodyStream() throws IOException {
        RawBackend backend = okBackend();
        app.use("/api/orders", earlyBodyReader());
        app.use("/api/orders", Proxy.to(backend.url()));
        start();

        ClientResponse unsupported = adapter.send(request("POST", "/api/orders/x", HELLO,
                READ_EARLY, "1", "Transfer-Encoding", "gzip, chunked"));
        ClientResponse chunked = adapter.send(request("POST", "/api/orders/x", HELLO,
                READ_EARLY, "1", "Transfer-Encoding", "chunked"));

        assertEquals("unsupported coding on a body already read", 501, unsupported.status());
        assertEquals("chunked on a body already read", 500, chunked.status());
        assertEquals("backend requests", 0, backend.requestCount());
    }

    // ---- AC-6 ----

    // FR-PROXY-010 AC-6: on a .maxConcurrent(1) mount whose only slot a pending request holds, a Content-Length over
    // the body limit is 413 and a body an earlier middleware read is 500, neither 503: req.bodyStream(limit) runs
    // before the slot is taken. The pending request is the one the backend has read and not yet answered.
    @Test(timeout = 30000)
    public void fr_PROXY_010_AC6_bodyStreamAnswersComeBeforeTheConcurrencySlot() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        releases.add(release);
        CountDownLatch arrived = new CountDownLatch(1);
        RawBackend backend = RawBackend.start((index, received, out) -> {
            if (index == 0) {
                arrived.countDown();
                try {
                    release.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            out.write(RawBackend.response(200, "ok").getBytes(StandardCharsets.ISO_8859_1));
        });
        closeables.add(backend);
        app.use("/api/orders", earlyBodyReader());
        app.use("/api/orders", Proxy.to(backend.url()).bodyLimit(4).maxConcurrent(1));
        start();

        Future<ClientResponse> pending = adapter.submit(ClientRequest.of("GET", "/api/orders/hold"));
        assertTrue("the pending request reached the backend", arrived.await(10, TimeUnit.SECONDS));

        ClientResponse overLimit = adapter.send(request("POST", "/api/orders/x", HELLO, "Content-Length", "5"));
        ClientResponse alreadyRead = adapter.send(request("POST", "/api/orders/x", HELLO,
                READ_EARLY, "1", "Content-Length", "5"));

        assertEquals("Content-Length over the body limit while the slot is held", 413, overLimit.status());
        assertEquals("body an earlier middleware read while the slot is held", 500, alreadyRead.status());
        assertEquals("backend requests while the slot is held", 1, backend.requestCount());

        release.countDown();
        ClientResponse first = pending.get(10, TimeUnit.SECONDS);
        assertEquals("the pending request", 200, first.status());
        assertEquals("ok", first.text());
    }

    // ---- AC-7 ----

    /** The requests of AC-2 to AC-5 that the proxy answers itself, with the status each gets. */
    private static final class OwnAnswer {
        final String name;
        final int status;
        final String reason;
        final ClientRequest request;

        OwnAnswer(String name, int status, String reason, ClientRequest request) {
            this.name = name;
            this.status = status;
            this.reason = reason;
            this.request = request;
        }
    }

    private static List<OwnAnswer> ownAnswers() {
        return Arrays.asList(
                new OwnAnswer("TRACE to a dot-dot path", 405, "Method Not Allowed",
                        ClientRequest.of("TRACE", "/api/orders/../x")),
                new OwnAnswer("encoded dot-dot path with gzip, chunked", 400, "Bad Request",
                        request("POST", "/api/orders/%2e%2e/x", HELLO, "Transfer-Encoding", "gzip, chunked")),
                new OwnAnswer("NUL header value with gzip, chunked", 400, "Bad Request",
                        request("POST", "/api/orders/x", HELLO, "X-A", "a\u0000b",
                                "Transfer-Encoding", "gzip, chunked")),
                new OwnAnswer("Content-Length 5 and 6 over .bodyLimit(1)", 400, "Bad Request",
                        request("POST", "/api/orders/x", HELLO, "Content-Length", "5", "Content-Length", "6")),
                new OwnAnswer("gzip, chunked on a body already read", 501, "Not Implemented",
                        request("POST", "/api/orders/x", HELLO, READ_EARLY, "1",
                                "Transfer-Encoding", "gzip, chunked")));
    }

    /** Mounts the AC-2 to AC-5 proxy, with the early body reader before it, on {@code a}. */
    private static void mountOwnAnswerProxy(RestApp a, RawBackend backend) {
        a.use("/api/orders", earlyBodyReader());
        a.use("/api/orders", Proxy.to(backend.url()).bodyLimit(1));
    }

    // FR-PROXY-010 AC-7: the 400, 405 and 501 answers of AC-2 to AC-5 reach onError as an exception whose class is
    // exactly HttpException, whose message is null and which is not a ProxyException
    @Test
    public void fr_PROXY_010_AC7_ownAnswersReachOnErrorAsPlainHttpExceptionsWithANullMessage() throws IOException {
        RawBackend backend = okBackend();
        mountOwnAnswerProxy(app, backend);
        recordErrors(app);
        start();

        for (OwnAnswer c : ownAnswers()) {
            errors.clear();

            ClientResponse r = adapter.send(c.request);

            assertEquals(c.name + " status", c.status, r.status());
            assertEquals(c.name + " errors that reached onError: " + errors, 1, errors.size());
            Throwable e = errors.get(0);
            assertSame(c.name + " exception class", HttpException.class, e.getClass());
            assertFalse(c.name + " is not a ProxyException", e instanceof ProxyException);
            assertEquals(c.name + " exception status", c.status, ((HttpException) e).status());
            assertNull(c.name + " exception message", e.getMessage());
        }
        assertEquals("backend requests", 0, backend.requestCount());
    }

    /**
     * The raw HTTP/1.1 form of each {@link #ownAnswers} request that a TinyHttpd gateway hands to the app, with the
     * status line the client must see. TinyHttpd refuses the NUL header value, the conflicting Content-Length lines
     * and the {@code gzip, chunked} coding itself before any app code runs (FR-ENGINE-003), so over a socket only the
     * method and path answers are the proxy's; the in-memory test above covers the other three.
     */
    private static List<String[]> socketOwnAnswers() {
        return Arrays.asList(
                new String[] {"TRACE to a dot-dot path",
                    "TRACE /api/orders/../x HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n",
                    "HTTP/1.1 405 Method Not Allowed"},
                new String[] {"encoded dot-dot path",
                    "GET /api/orders/%2e%2e/x HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n",
                    "HTTP/1.1 400 Bad Request"});
    }

    // FR-PROXY-010 AC-7: without onError, the default error response to the proxy's own 405 and 400 carries the
    // standard reason phrase of the status in the status line a TinyHttpd gateway writes; a recording middleware
    // before the proxy shows each request reached the app, so the answer is the proxy's and not the adapter's own
    @Test(timeout = 30000)
    public void fr_PROXY_010_AC7_withoutOnErrorTheDefaultResponseCarriesTheReasonPhrase() throws IOException {
        RawBackend backend = okBackend();
        List<String> reached = Collections.synchronizedList(new ArrayList<String>());
        RestApp g = RestApp.builder().httpAdapter(new TinyHttpdAdapter()).jsonCodec(new StubJsonCodec())
                .logger(new RecordingLogger()).build();
        started.add(g);
        g.use("/api/orders", (req, res, next) -> {
            reached.add(req.methodToken() + " " + req.originalUrl());
            next.run();
        });
        g.use("/api/orders", Proxy.to(backend.url()));
        int port = g.listen(0).port();

        for (String[] c : socketOwnAnswers()) {
            reached.clear();

            String response = exchange(port, c[1]);

            assertEquals(c[0] + " reached the app: " + response, 1, reached.size());
            assertEquals(c[0] + " status line", c[2], response.substring(0, response.indexOf("\r\n")));
        }
        assertEquals("backend requests", 0, backend.requestCount());
    }

    /** Writes {@code raw} to 127.0.0.1:{@code port} and reads the response as ISO-8859-1 until the server closes. */
    private static String exchange(int port, String raw) throws IOException {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            s.setSoTimeout(10000);
            OutputStream out = s.getOutputStream();
            out.write(raw.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            InputStream in = s.getInputStream();
            byte[] b = new byte[8192];
            try {
                for (int n; (n = in.read(b)) >= 0; ) {
                    buf.write(b, 0, n);
                }
            } catch (SocketException reset) {
                // a reset after the close counts as the end
            }
            String text = new String(buf.toByteArray(), StandardCharsets.ISO_8859_1);
            if (text.indexOf("\r\n") < 0) {
                throw new EOFException("no status line: " + text);
            }
            return text;
        }
    }
}
