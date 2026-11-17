package com.snoworca.naruserve.tinyhttpd;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.testing.NoLeftoverThreads;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.tinyhttpd.RawTargetSocketTest.Http;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;

/** The raw request access API (header enumeration, raw body stream) over a real TinyHttpd socket. */
// FR-ENGINE-013 AC-1, AC-2, AC-3, AC-5
public class RawRequestSocketTest {

    @Rule
    public final NoLeftoverThreads noLeftoverThreads = new NoLeftoverThreads();

    private final RestApp app = Http.app(new TinyHttpdAdapter(), new RecordingLogger());

    @After
    public void stop() {
        app.shutdown(2, TimeUnit.SECONDS);
    }

    // FR-ENGINE-013 AC-1: X-A: 1, x-a: 2, X-B: 3 give headerNames() [.., x-a, x-b, ..] and rawHeaders() in received
    // order and case
    @Test(timeout = 20000)
    public void FR_ENGINE_013_AC1_headerNamesAndRawHeadersFollowWireOrderAndCase() throws Exception {
        AtomicReference<List<String>> names = new AtomicReference<>();
        AtomicReference<List<Map.Entry<String, String>>> raw = new AtomicReference<>();
        app.get("/h", (req, res) -> {
            names.set(new ArrayList<>(req.headerNames()));
            raw.set(new ArrayList<>(req.rawHeaders()));
            res.send("seen");
        });
        int port = app.listen(0).port();
        String response = Http.send(port, "GET /h HTTP/1.1\r\nHost: 127.0.0.1\r\nX-A: 1\r\nx-a: 2\r\nX-B: 3\r\n"
                + "Connection: close\r\n\r\n");
        assertEquals(response, 200, Http.status(response));
        assertEquals("seen", Http.body(response));
        assertEquals(Arrays.asList("host", "x-a", "x-b", "connection"), names.get());
        assertEquals(Arrays.asList(entry("Host", "127.0.0.1"), entry("X-A", "1"), entry("x-a", "2"),
                entry("X-B", "3"), entry("Connection", "close")), raw.get());
    }

    // FR-ENGINE-013 AC-2: a chunked body read through bodyStream() is exactly the de-chunked payload, without
    // chunk-size lines or trailers
    @Test(timeout = 20000)
    public void FR_ENGINE_013_AC2_chunkedBodyStreamYieldsTheDechunkedPayloadOnly() throws Exception {
        AtomicReference<byte[]> seen = new AtomicReference<>();
        app.post("/raw", (req, res) -> {
            seen.set(readAll(req.bodyStream()));
            res.send("read");
        });
        int port = app.listen(0).port();
        // The payload itself carries CRLF and a chunk-like "0\r\n" so a leak of framing bytes cannot match it.
        String part1 = "hello\r\n0\r\n";
        String part2 = " world";
        String request = "POST /raw HTTP/1.1\r\nHost: 127.0.0.1\r\nTransfer-Encoding: chunked\r\n"
                + "Connection: close\r\n\r\n"
                + Integer.toHexString(part1.length()) + ";ext=1\r\n" + part1 + "\r\n"
                + Integer.toHexString(part2.length()) + "\r\n" + part2 + "\r\n"
                + "0\r\nX-Trailer: t\r\n\r\n";
        String response = Http.send(port, request);
        assertEquals(response, 200, Http.status(response));
        assertEquals("read", Http.body(response));
        assertNotNull(seen.get());
        assertArrayEquals((part1 + part2).getBytes(StandardCharsets.ISO_8859_1), seen.get());
    }

    // FR-ENGINE-013 AC-2: a Content-Encoding: gzip body read through bodyStream() is the compressed bytes as sent
    @Test(timeout = 20000)
    public void FR_ENGINE_013_AC2_gzipBodyStreamYieldsTheCompressedBytesAsSent() throws Exception {
        AtomicReference<byte[]> seen = new AtomicReference<>();
        app.post("/raw", (req, res) -> {
            seen.set(readAll(req.bodyStream()));
            res.send("read");
        });
        int port = app.listen(0).port();
        ByteArrayOutputStream packed = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(packed)) {
            gz.write("compressed payload, compressed payload".getBytes(StandardCharsets.UTF_8));
        }
        byte[] gzip = packed.toByteArray();
        String head = "POST /raw HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Encoding: gzip\r\n"
                + "Content-Type: application/octet-stream\r\nContent-Length: " + gzip.length
                + "\r\nConnection: close\r\n\r\n";
        String response = Http.send(port, head + new String(gzip, StandardCharsets.ISO_8859_1));
        assertEquals(response, 200, Http.status(response));
        assertEquals("read", Http.body(response));
        assertArrayEquals(gzip, seen.get());
    }

    // FR-ENGINE-013 AC-3: a handler leaves the bodyStream() of a 32768-byte Content-Length body unread and unclosed;
    // the next request on the same keep-alive connection is served normally
    @Test(timeout = 20000)
    public void FR_ENGINE_013_AC3_unreadBodyStreamOf32KiBKeepsTheKeepAliveConnectionUsable() throws Exception {
        AtomicInteger firstCalls = new AtomicInteger();
        AtomicInteger nextCalls = new AtomicInteger();
        app.post("/ignore", (req, res) -> {
            InputStream unread = req.bodyStream();
            assertNotNull(unread);
            firstCalls.incrementAndGet();
            res.send("first");
        });
        app.get("/next", (req, res) -> {
            nextCalls.incrementAndGet();
            res.status(201).send("second");
        });
        int port = app.listen(0).port();
        byte[] body = new byte[32768];
        for (int i = 0; i < body.length; i++) {
            body[i] = (byte) ('a' + i % 26);
        }
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            s.setSoTimeout(10000);
            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();
            out.write(("POST /ignore HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/octet-stream\r\n"
                    + "Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.write(body);
            out.flush();
            String first = readOneResponse(in);
            assertEquals(first, 200, Http.status(first));
            assertEquals("first", Http.body(first));
            assertNull(first, closeToken(first));

            out.write("GET /next HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n"
                    .getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            String second = Http.readAll(in);
            assertFalse("the connection was closed after the first response", second.isEmpty());
            assertEquals(second, 201, Http.status(second));
            assertEquals("second", Http.body(second));
        }
        assertEquals(1, firstCalls.get());
        assertEquals(1, nextCalls.get());
    }

    // FR-ENGINE-013 AC-5: a chunked body over rawBodyLimit read through bodyStream() by a handler that lets the
    // BodyTooLargeException escape is answered 413 with the standard error body
    @Test(timeout = 20000)
    public void FR_ENGINE_013_AC5_chunkedBodyOverRawBodyLimitEscapingTheHandlerGives413StandardBody()
            throws Exception {
        app.rawBodyLimit(1024);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        app.post("/raw", (req, res) -> {
            try {
                readAll(req.bodyStream());
            } catch (IOException | RuntimeException e) {
                thrown.set(e);
                throw e;
            }
            res.send("unexpected");
        });
        int port = app.listen(0).port();
        StringBuilder request = new StringBuilder("POST /raw HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                + "Transfer-Encoding: chunked\r\nConnection: close\r\n\r\n");
        for (int i = 0; i < 4; i++) {
            request.append("200\r\n").append(Http.repeat('z', 512)).append("\r\n");
        }
        request.append("0\r\n\r\n");
        String response = Http.send(port, request.toString());
        assertEquals(response, 413, Http.status(response));
        assertTrue(String.valueOf(thrown.get()), thrown.get() instanceof com.snoworca.naruserve.BodyTooLargeException);
        assertEquals(1024, ((com.snoworca.naruserve.BodyTooLargeException) thrown.get()).limit());
        CrossAdapterTest.htmlError(response,
                "com.snoworca.naruserve.BodyTooLargeException: request body exceeds the limit of 1024 bytes");
    }

    private static Map.Entry<String, String> entry(String name, String value) {
        return new SimpleImmutableEntry<>(name, value);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] b = new byte[1024];
        for (int n; (n = in.read(b)) >= 0; ) {
            buf.write(b, 0, n);
        }
        return buf.toByteArray();
    }

    /** The Connection: close value of a raw response head, or null when the response keeps the connection. */
    private static String closeToken(String response) {
        for (String v : Http.headers(response, "Connection")) {
            if (v.equalsIgnoreCase("close")) {
                return v;
            }
        }
        return null;
    }

    /** One response with a Content-Length body, read without waiting for the server to close. */
    private static String readOneResponse(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int matched = 0;
        while (matched < 4) {
            int c = in.read();
            if (c < 0) {
                throw new IOException("connection closed inside the response head: " + head);
            }
            head.write(c);
            matched = (c == (matched % 2 == 0 ? '\r' : '\n')) ? matched + 1 : (c == '\r' ? 1 : 0);
        }
        String h = new String(head.toByteArray(), StandardCharsets.ISO_8859_1);
        String length = Http.header(h, "Content-Length");
        assertNotNull(h, length);
        byte[] body = new byte[Integer.parseInt(length)];
        int off = 0;
        while (off < body.length) {
            int n = in.read(body, off, body.length - off);
            if (n < 0) {
                throw new IOException("connection closed inside the response body");
            }
            off += n;
        }
        return h + new String(body, StandardCharsets.ISO_8859_1);
    }
}
