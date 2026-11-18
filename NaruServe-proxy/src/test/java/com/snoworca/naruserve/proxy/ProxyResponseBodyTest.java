package com.snoworca.naruserve.proxy;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.Response;
import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.TestApp;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.After;
import org.junit.Test;

/**
 * The response body relay of {@link Proxy} (FR-PROXY-014): the backend body goes to {@code res.relay} with its
 * {@code Content-Length} or -1, and the response to a HEAD request, a 204 and a 304 relays no body whatever its framing
 * headers say. Every proxy here sits behind a recording {@link Response} that keeps the length the proxy passes to
 * {@code res.relay}; the backends are raw sockets ({@link RawBackend}) writing the exact bytes, and a backend "kept
 * open" holds its connection after the header block until the test ends, so a proxy that waited for body bytes would
 * sit there until its read timeout.
 */
public class ProxyResponseBodyTest {

    /** The proxy's read timeout: a response that arrives well within it never waited for a body. */
    private static final long READ_TIMEOUT_MILLIS = 10_000;
    /** "Well before the read timeout": a bodyless response must reach the client within this. */
    private static final long WELL_BEFORE_MILLIS = READ_TIMEOUT_MILLIS / 2;

    private final InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
    private final TestApp t = TestApp.create(adapter);
    private final RestApp app = t.app();
    private final List<Closeable> closeables = new ArrayList<>();
    private final List<Throwable> errors = Collections.synchronizedList(new ArrayList<Throwable>());
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

    /** What one mounted proxy route saw: the lengths it passed to {@code res.relay}, and what was read from it. */
    static final class Route {
        final String path;
        final List<Long> relayLengths = Collections.synchronizedList(new ArrayList<Long>());
        /** In draining mode: the bytes read from the stream handed to {@code res.relay}, or null before the call. */
        volatile byte @Nullable [] drained;

        Route(String path) {
            this.path = path;
        }

        /** The only length the proxy passed to {@code res.relay}. */
        long relayLength() {
            synchronized (relayLengths) {
                assertEquals(path + ": res.relay calls " + relayLengths, 1, relayLengths.size());
                return relayLengths.get(0);
            }
        }
    }

    /** A backend that writes {@code rawResponse} and closes the connection. */
    private RawBackend closing(String rawResponse) throws IOException {
        RawBackend b = RawBackend.answering(rawResponse);
        closeables.add(b);
        return b;
    }

    /** A backend that writes {@code rawResponse} and keeps the connection open until the test ends. */
    private RawBackend keptOpen(String rawResponse) throws IOException {
        final byte[] bytes = rawResponse.getBytes(StandardCharsets.ISO_8859_1);
        RawBackend b = RawBackend.start((index, request, out) -> {
            out.write(bytes);
            out.flush();
            try {
                hold.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        closeables.add(b);
        return b;
    }

    /**
     * Mounts a proxy to {@code backend} at a new path behind a {@code Response} wrapper that records the length of
     * every {@code res.relay} call. Delegating mode hands the proxy's stream on to the real {@code res.relay};
     * draining mode reads that stream to its end on the handler thread and relays what it read.
     */
    private Route mount(RawBackend backend, boolean draining) {
        final Route route = new Route("/r" + routes++);
        final Proxy proxy = Proxy.to(backend.url()).readTimeout(READ_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        app.use(route.path, (req, res, next) -> {
            InvocationHandler h = (p, method, args) -> {
                if (method.getName().equals("relay")) {
                    route.relayLengths.add((Long) args[1]);
                    if (draining) {
                        InputStream in = (InputStream) args[0];
                        ByteArrayOutputStream read = new ByteArrayOutputStream();
                        try {
                            byte[] buf = new byte[1024];
                            for (int n; (n = in.read(buf)) >= 0; ) {
                                read.write(buf, 0, n);
                            }
                        } finally {
                            in.close();
                        }
                        route.drained = read.toByteArray();
                        res.relay(new ByteArrayInputStream(route.drained), (Long) args[1]);
                        return null;
                    }
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
        return route;
    }

    private Route mount(RawBackend backend) {
        return mount(backend, false);
    }

    private void start() {
        app.onError((error, req, res) -> errors.add(error));
        t.start();
        started = true;
    }

    private ClientResponse send(String method, Route route) {
        return adapter.send(ClientRequest.of(method, route.path + "/x"));
    }

    /** Sends {@code method} to {@code route} and checks the response arrived well before the read timeout. */
    private ClientResponse sendBodyless(String method, Route route) {
        long t0 = System.nanoTime();
        ClientResponse r = send(method, route);
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        assertTrue(route.path + ": arrived after " + elapsed + " ms", elapsed < WELL_BEFORE_MILLIS);
        return r;
    }

    private void assertNoError() {
        synchronized (errors) {
            assertTrue("errors that reached onError: " + errors, errors.isEmpty());
        }
    }

    private static String repeatPattern(int n) {
        char[] chars = new char[n];
        for (int i = 0; i < n; i++) {
            chars[i] = (char) ('a' + i % 26);
        }
        return new String(chars);
    }

    /** The framing header lines of AC-6 whose Content-Length agrees on 10, each line ending in CRLF. */
    private static final List<String> AGREED_TEN = Arrays.asList(
            "Content-Length: 10\r\nTransfer-Encoding: chunked\r\n",
            "Content-Length: 10\r\nContent-Length: 10\r\n",
            "Content-Length: 10\r\nContent-Length: 010\r\n");

    /** The framing header lines of AC-6 whose Content-Length gives no one usable length. */
    private static final List<String> UNUSABLE = Arrays.asList(
            "Content-Length: abc\r\n",
            "Content-Length: -1\r\n",
            "Content-Length: 99999999999999999999\r\n",
            "Content-Length: 10\r\nContent-Length: 11\r\n");

    // ---------------------------------------------------------------------------------------------------------------
    // AC-1 to AC-3: bodies

    // FR-PROXY-014 AC-1: Content-Length: 10 and 10 body bytes reach the client with Content-Length: 10 and the same
    // bytes, and the recording Response saw res.relay called with 10
    @Test(timeout = 30000)
    public void fr_PROXY_014_AC1_contentLengthBodyIsRelayedWithItsLength() throws IOException {
        Route route = mount(closing("HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\n0123456789"));
        start();

        ClientResponse r = send("GET", route);

        assertEquals(200, r.status());
        assertEquals("10", r.header("Content-Length"));
        assertEquals("0123456789", r.text());
        assertEquals(10L, route.relayLength());
        assertFalse(r.abnormallyClosed());
        assertNoError();
    }

    // FR-PROXY-014 AC-2: a chunked backend body reaches the client as exactly the decoded bytes, and the proxy
    // calls res.relay with -1
    @Test(timeout = 30000)
    public void fr_PROXY_014_AC2_chunkedBodyIsRelayedDecodedWithUnknownLength() throws IOException {
        Route route = mount(closing("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
                + "5\r\nhello\r\n1;ext=1\r\n,\r\n6\r\n world\r\n0\r\nX-Trailer: t\r\n\r\n"));
        start();

        ClientResponse r = send("GET", route);

        assertEquals(200, r.status());
        assertEquals("hello, world", r.text());
        assertEquals(-1L, route.relayLength());
        assertNull("no Content-Length for an unknown length", r.header("Content-Length"));
        assertFalse(r.abnormallyClosed());
        assertNoError();
    }

    // FR-PROXY-014 AC-3: a body with neither Content-Length nor Transfer-Encoding is read until the backend closes,
    // every byte reaches the client, and the proxy calls res.relay with -1
    @Test(timeout = 30000)
    public void fr_PROXY_014_AC3_bodyWithoutLengthHeadersIsReadUntilClose() throws IOException {
        String body = repeatPattern(70_000);
        Route route = mount(closing("HTTP/1.1 200 OK\r\nX-A: 1\r\n\r\n" + body));
        start();

        ClientResponse r = send("GET", route);

        assertEquals(200, r.status());
        assertArrayEquals(body.getBytes(StandardCharsets.ISO_8859_1), r.body());
        assertEquals(-1L, route.relayLength());
        assertFalse(r.abnormallyClosed());
        assertNoError();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // AC-4 to AC-6: HEAD, 204 and 304

    // FR-PROXY-014 AC-4: a HEAD response with Content-Length: 1234 and no body, its connection kept open, reaches
    // the client with Content-Length: 1234 and no body well before the read timeout
    @Test(timeout = 30000)
    public void fr_PROXY_014_AC4_headResponseKeptOpenArrivesWithItsLengthAndNoBody() throws IOException {
        Route route = mount(keptOpen("HTTP/1.1 200 OK\r\nContent-Length: 1234\r\n\r\n"));
        start();

        ClientResponse r = sendBodyless("HEAD", route);

        assertEquals(200, r.status());
        assertEquals("1234", r.header("Content-Length"));
        assertEquals(0, r.body().length);
        assertEquals(1234L, route.relayLength());
        assertNoError();
    }

    // FR-PROXY-014 AC-4: the stream the proxy hands to res.relay for a HEAD response yields no body byte, even when
    // the backend wrongly writes bytes after the header block
    @Test(timeout = 30000)
    public void fr_PROXY_014_AC4_headResponseStreamYieldsNoBodyBytes() throws IOException {
        Route route = mount(closing("HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\nbody"), true);
        start();

        ClientResponse r = send("HEAD", route);

        assertEquals(200, r.status());
        assertEquals(4L, route.relayLength());
        assertNotNull("the proxy called res.relay", route.drained);
        assertEquals("bytes read from the relayed stream", "",
                new String(route.drained, StandardCharsets.ISO_8859_1));
        assertNoError();
    }

    // FR-PROXY-014 AC-5: a 204 kept open after its header block reaches the client as 204 with neither body nor
    // Content-Length, and the proxy calls res.relay with -1
    @Test(timeout = 30000)
    public void fr_PROXY_014_AC5_noContentKeptOpenArrivesWithoutBodyOrLength() throws IOException {
        Route route = mount(keptOpen("HTTP/1.1 204 No Content\r\nX-A: 1\r\n\r\n"));
        start();

        ClientResponse r = sendBodyless("GET", route);

        assertEquals(204, r.status());
        assertNull(r.header("Content-Length"));
        assertEquals(0, r.body().length);
        assertEquals(-1L, route.relayLength());
        assertNoError();
    }

    // FR-PROXY-014 AC-5: a 304 with Content-Length: 10 makes the proxy call res.relay with 10 and reaches the client
    // as 304 with no body and, from the in-memory adapter, Content-Length: 10
    @Test(timeout = 30000)
    public void fr_PROXY_014_AC5_notModifiedWithLengthRelaysTheLengthAndNoBody() throws IOException {
        Route route = mount(keptOpen("HTTP/1.1 304 Not Modified\r\nContent-Length: 10\r\n\r\n"));
        start();

        ClientResponse r = sendBodyless("GET", route);

        assertEquals(304, r.status());
        assertEquals("10", r.header("Content-Length"));
        assertEquals(0, r.body().length);
        assertEquals(10L, route.relayLength());
        assertNoError();
    }

    // FR-PROXY-014 AC-5: a 304 without length headers reaches the client without Content-Length, and the proxy
    // calls res.relay with -1
    @Test(timeout = 30000)
    public void fr_PROXY_014_AC5_notModifiedWithoutLengthArrivesWithoutLength() throws IOException {
        Route route = mount(keptOpen("HTTP/1.1 304 Not Modified\r\nETag: \"a\"\r\n\r\n"));
        start();

        ClientResponse r = sendBodyless("GET", route);

        assertEquals(304, r.status());
        assertNull(r.header("Content-Length"));
        assertEquals(0, r.body().length);
        assertEquals(-1L, route.relayLength());
        assertNoError();
    }

    // FR-PROXY-014 AC-5: the stream the proxy hands to res.relay for a 204 and a 304 yields no body byte, even when
    // the backend wrongly writes bytes after the header block
    @Test(timeout = 30000)
    public void fr_PROXY_014_AC5_noContentAndNotModifiedStreamsYieldNoBodyBytes() throws IOException {
        Route noContent = mount(closing("HTTP/1.1 204 No Content\r\n\r\nbody"), true);
        Route notModified = mount(closing("HTTP/1.1 304 Not Modified\r\nContent-Length: 4\r\n\r\nbody"), true);
        start();

        assertEquals(204, send("GET", noContent).status());
        assertEquals(304, send("GET", notModified).status());

        for (Route route : Arrays.asList(noContent, notModified)) {
            assertNotNull(route.path + ": the proxy called res.relay", route.drained);
            assertEquals(route.path + ": bytes read from the relayed stream", "",
                    new String(route.drained, StandardCharsets.ISO_8859_1));
        }
        assertEquals(-1L, noContent.relayLength());
        assertEquals(4L, notModified.relayLength());
        assertNoError();
    }

    /**
     * Sends {@code method} through one kept-open backend per framing variant answering {@code statusLine} with that
     * variant, and checks each arrives well before the read timeout with {@code status}, no body, no
     * {@code INVALID_RESPONSE}, and {@code res.relay} called with {@code expectedLength}; the client sees
     * {@code Content-Length} only for a non-negative length.
     */
    private void assertBodyless(String method, String statusLine, int status, List<String> variants,
            long expectedLength) throws IOException {
        List<Route> mounted = new ArrayList<>();
        for (String variant : variants) {
            mounted.add(mount(keptOpen(statusLine + variant + "\r\n")));
        }
        start();
        for (int i = 0; i < variants.size(); i++) {
            Route route = mounted.get(i);
            String what = method + " " + statusLine.trim() + " with " + variants.get(i).replace("\r\n", "; ");

            ClientResponse r = sendBodyless(method, route);

            assertEquals(what, status, r.status());
            assertEquals(what, 0, r.body().length);
            assertEquals(what, expectedLength, route.relayLength());
            if (expectedLength < 0 || status == 204) {
                assertNull(what + ": Content-Length", r.header("Content-Length"));
            } else {
                assertEquals(what + ": Content-Length", Long.toString(expectedLength), r.header("Content-Length"));
            }
        }
        assertNoError();
    }

    // FR-PROXY-014 AC-6: a HEAD response with Content-Length: 10 and Transfer-Encoding: chunked, Content-Length: 10
    // twice, or Content-Length: 10 and 010 makes the proxy call res.relay with 10, reading no body
    @Test(timeout = 60000)
    public void fr_PROXY_014_AC6_headResponseWithAgreedLengthRelaysTen() throws IOException {
        assertBodyless("HEAD", "HTTP/1.1 200 OK\r\n", 200, AGREED_TEN, 10);
    }

    // FR-PROXY-014 AC-6: a 304 with Content-Length: 10 and Transfer-Encoding: chunked, Content-Length: 10 twice, or
    // Content-Length: 10 and 010 makes the proxy call res.relay with 10, reading no body
    @Test(timeout = 60000)
    public void fr_PROXY_014_AC6_notModifiedWithAgreedLengthRelaysTen() throws IOException {
        assertBodyless("GET", "HTTP/1.1 304 Not Modified\r\n", 304, AGREED_TEN, 10);
    }

    // FR-PROXY-014 AC-6: a HEAD response with Content-Length abc, -1, past Long.MAX_VALUE, or 10 and 11 makes the
    // proxy call res.relay with -1 and reaches the client with its status, no body and no Content-Length
    @Test(timeout = 60000)
    public void fr_PROXY_014_AC6_headResponseWithUnusableLengthRelaysUnknownLength() throws IOException {
        assertBodyless("HEAD", "HTTP/1.1 200 OK\r\n", 200, UNUSABLE, -1);
    }

    // FR-PROXY-014 AC-6: a 304 with Content-Length abc, -1, past Long.MAX_VALUE, or 10 and 11 makes the proxy call
    // res.relay with -1 and reaches the client as 304 with no body and no Content-Length
    @Test(timeout = 60000)
    public void fr_PROXY_014_AC6_notModifiedWithUnusableLengthRelaysUnknownLength() throws IOException {
        assertBodyless("GET", "HTTP/1.1 304 Not Modified\r\n", 304, UNUSABLE, -1);
    }

    // FR-PROXY-014 AC-6: a 204 with any of these framing headers reaches the client as 204 with no body, the proxy
    // calling res.relay with -1
    @Test(timeout = 60000)
    public void fr_PROXY_014_AC6_noContentWithAnyFramingHeadersRelaysUnknownLength() throws IOException {
        List<String> all = new ArrayList<>(AGREED_TEN);
        all.addAll(UNUSABLE);
        assertBodyless("GET", "HTTP/1.1 204 No Content\r\n", 204, all, -1);
    }
}
