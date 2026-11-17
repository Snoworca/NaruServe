package com.snoworca.naruserve;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.internal.DefaultRestApp;
import com.snoworca.naruserve.spi.HttpExchange;
import com.snoworca.naruserve.spi.JsonCodec;
import com.snoworca.naruserve.spi.StreamDrain;
import com.snoworca.naruserve.spi.UpgradeHandler;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.RecordingExchange;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;
import org.jspecify.annotations.Nullable;
import org.junit.Test;

/** {@code req.bodyStream()} and {@code req.bodyStream(long)}: the raw body under the one-read rule. */
public class RequestBodyStreamTest {

    /** Decodes any JSON text into a one-entry map {@code text -> the whole text}; draining the reader. */
    static final class MapCodec implements JsonCodec {
        @Override
        @SuppressWarnings("unchecked")
        public <T> @Nullable T decode(Reader reader, Type type) throws IOException {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[256];
            int n;
            while ((n = reader.read(buf)) != -1) {
                sb.append(buf, 0, n);
            }
            return (T) Collections.singletonMap("text", sb.toString());
        }

        @Override
        public void encode(@Nullable Object value, Writer writer) throws IOException {
            writer.write(String.valueOf(value));
        }
    }

    /** One step a handler runs; its outcome (value or thrown exception) is recorded. */
    interface Step {
        @Nullable Object run(Request req) throws Exception;
    }

    private static @Nullable Object attempt(Request req, Step step) {
        try {
            return step.run(req);
        } catch (Throwable t) {
            return t;
        }
    }

    /** A handler body: runs the steps and returns the recorded outcomes in order. */
    interface Probe {
        void run(Request req, List<Object> out) throws Exception;
    }

    /** POSTs {@code body} (no body when null) with the header pairs to a route running {@code probe}. */
    private static List<Object> probe(long rawBodyLimit, byte @Nullable [] body, Probe probe, String... headerPairs) {
        InMemoryHttpAdapter a = new InMemoryHttpAdapter();
        RestApp app = RestApp.builder().httpAdapter(a).jsonCodec(new MapCodec()).build();
        if (rawBodyLimit > 0) {
            app.rawBodyLimit(rawBodyLimit);
        }
        List<Object> out = Collections.synchronizedList(new ArrayList<>());
        app.post("/p", (req, res) -> {
            probe.run(req, out);
            res.send("done");
        });
        app.listen(0);
        ClientRequest r = ClientRequest.of("POST", "/p");
        for (int i = 0; i < headerPairs.length; i += 2) {
            r.header(headerPairs[i], headerPairs[i + 1]);
        }
        if (body != null) {
            r.body(body);
        }
        ClientResponse resp = a.send(r);
        assertEquals(resp.text(), 200, resp.status());
        assertEquals("done", resp.text());
        return out;
    }

    private static List<Object> probe(byte @Nullable [] body, Probe probe, String... headerPairs) {
        return probe(-1, body, probe, headerPairs);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static void assertConsumed(String what, @Nullable Object outcome) {
        assertTrue(what + " gave " + outcome, outcome instanceof BodyAlreadyConsumedException);
    }

    /** Sends {@code body} with the header pairs and returns the bytes {@code bodyStream()} read. */
    private static byte[] echo(byte[] body, String... headerPairs) {
        List<Object> out = probe(body, (req, o) -> o.add(readAll(req.bodyStream())), headerPairs);
        assertEquals(1, out.size());
        assertTrue(String.valueOf(out.get(0)), out.get(0) instanceof byte[]);
        return (byte[]) out.get(0);
    }

    // FR-HTTP-030 AC-1: a JSON body mixing spaces and line breaks comes back byte for byte
    @Test
    public void FR_HTTP_030_AC1_jsonBodyWithSpacesAndLineBreaksIsReturnedExactly() {
        byte[] body = utf8("{ \"a\" : 1,\n  \"b\" :\t[ 1 , 2 ]\r\n}\n");
        assertArrayEquals(body, echo(body, "Content-Type", "application/json"));
    }

    // FR-HTTP-030 AC-1: a binary body holding all 256 byte values comes back byte for byte
    @Test
    public void FR_HTTP_030_AC1_binaryBodyWithAll256ByteValuesIsReturnedExactly() {
        byte[] body = new byte[512];
        for (int i = 0; i < body.length; i++) {
            body[i] = (byte) (255 - (i % 256));
        }
        assertArrayEquals(body, echo(body, "Content-Type", "application/octet-stream"));
    }

    // FR-HTTP-030 AC-1: an application/x-www-form-urlencoded body is returned as sent, not parsed
    @Test
    public void FR_HTTP_030_AC1_urlencodedBodyIsReturnedExactly() {
        byte[] body = utf8("a=1&b=x+y%20z&c=%ED%95%9C&d");
        assertArrayEquals(body, echo(body, "Content-Type", "application/x-www-form-urlencoded"));
    }

    // FR-HTTP-030 AC-1: a Content-Encoding: gzip body is returned still compressed
    @Test
    public void FR_HTTP_030_AC1_gzipBodyIsReturnedStillCompressed() throws IOException {
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (GZIPOutputStream g = new GZIPOutputStream(gz)) {
            g.write(utf8("{\"compressed\":true,\"pad\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"}"));
        }
        byte[] body = gz.toByteArray();
        byte[] got = echo(body, "Content-Type", "application/json", "Content-Encoding", "gzip");
        assertArrayEquals(body, got);
        assertEquals(0x1f, got[0] & 0xff);
        assertEquals(0x8b, got[1] & 0xff);
    }

    // FR-HTTP-030 AC-2: a request without a body gets a non-null stream whose first read() returns -1
    @Test
    public void FR_HTTP_030_AC2_requestWithoutBodyGetsNonNullEmptyStream() {
        List<Object> out = probe(null, (req, o) -> {
            InputStream s = req.bodyStream();
            o.add(s == null ? "null" : "stream");
            o.add(s == null ? -2 : s.read());
        });
        assertEquals("stream", out.get(0));
        assertEquals(-1, out.get(1));
    }

    // FR-HTTP-030 AC-3: after bodyStream() was read, req.body(Map.class) throws BodyAlreadyConsumedException
    @Test
    public void FR_HTTP_030_AC3_bodyAfterBodyStreamWasReadThrows() {
        List<Object> out = probe(utf8("{\"a\":1}"), (req, o) -> {
            o.add(req.bodyStream().read());
            o.add(attempt(req, r -> r.body(Map.class)));
        }, "Content-Type", "application/json");
        assertEquals((int) '{', out.get(0));
        assertConsumed("body(Map.class) after bodyStream() was read", out.get(1));
    }

    // FR-HTTP-030 AC-3: after req.body(Map.class) read a JSON body, bodyStream() throws BodyAlreadyConsumedException
    @Test
    public void FR_HTTP_030_AC3_bodyStreamAfterJsonBodyWasReadThrows() {
        List<Object> out = probe(utf8("{\"a\":1}"), (req, o) -> {
            o.add(req.body(Map.class));
            o.add(attempt(req, Request::bodyStream));
        }, "Content-Type", "application/json");
        assertEquals(Collections.singletonMap("text", "{\"a\":1}"), out.get(0));
        assertConsumed("bodyStream() after body(Map.class)", out.get(1));
    }

    private static final String BOUNDARY = "XyZb0und4ry";

    private static byte[] multipartBody() {
        return utf8("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"a\"\r\n\r\n1\r\n--" + BOUNDARY
                + "--\r\n");
    }

    /**
     * Takes the body with {@code take} without reading a byte, then asserts body(Map.class), form("a") and parts()
     * each throw BodyAlreadyConsumedException.
     */
    private static void assertTakenWithoutRead(String label, byte @Nullable [] body, Step take,
            String... headerPairs) {
        List<Object> out = probe(body, (req, o) -> {
            o.add(attempt(req, take));
            o.add(attempt(req, r -> r.body(Map.class)));
            o.add(attempt(req, r -> r.form("a")));
            o.add(attempt(req, Request::parts));
        }, headerPairs);
        assertTrue(label + ": the take gave " + out.get(0), out.get(0) instanceof InputStream);
        assertConsumed(label + ": body(Map.class)", out.get(1));
        assertConsumed(label + ": form(\"a\")", out.get(2));
        assertConsumed(label + ": parts()", out.get(3));
    }

    private static void assertTakesForEveryContentType(String label, Step take) {
        assertTakenWithoutRead(label + " JSON", utf8("{\"a\":1}"), take, "Content-Type", "application/json");
        assertTakenWithoutRead(label + " urlencoded", utf8("a=1"), take,
                "Content-Type", "application/x-www-form-urlencoded");
        assertTakenWithoutRead(label + " multipart", multipartBody(), take,
                "Content-Type", "multipart/form-data; boundary=" + BOUNDARY);
        assertTakenWithoutRead(label + " no Content-Type", utf8("a=1"), take);
    }

    // FR-HTTP-030 AC-4: once bodyStream() returned, even with no byte read, body, form and parts throw
    // BodyAlreadyConsumedException whatever the Content-Type (JSON, urlencoded, multipart or none)
    @Test
    public void FR_HTTP_030_AC4_bareBodyStreamTakesTheBodyForEveryContentType() {
        assertTakesForEveryContentType("bodyStream()", Request::bodyStream);
    }

    // FR-HTTP-030 AC-4: once bodyStream(long) returned, even with no byte read, body, form and parts throw
    // BodyAlreadyConsumedException whatever the Content-Type (JSON, urlencoded, multipart or none)
    @Test
    public void FR_HTTP_030_AC4_bareBodyStreamWithLimitTakesTheBodyForEveryContentType() {
        assertTakesForEveryContentType("bodyStream(4096)", r -> r.bodyStream(4096L));
    }

    // FR-HTTP-030 AC-5: on a JSON request form("a") is null and parts() empty without reading the body, and a
    // following bodyStream() returns the whole body
    @Test
    public void FR_HTTP_030_AC5_formAndPartsOnJsonLeaveTheBodyForBodyStream() {
        byte[] body = utf8("{\"a\":\"form-looking=1\"}");
        List<Object> out = probe(body, (req, o) -> {
            o.add(attempt(req, r -> r.form("a")));
            o.add(attempt(req, Request::parts));
            o.add(attempt(req, r -> readAll(r.bodyStream())));
        }, "Content-Type", "application/json");
        assertEquals(null, out.get(0));
        assertEquals(Collections.emptyList(), out.get(1));
        assertTrue(String.valueOf(out.get(2)), out.get(2) instanceof byte[]);
        assertArrayEquals(body, (byte[]) out.get(2));
    }

    // FR-HTTP-030 AC-6: without a body a second bodyStream() returns the same object, and a following
    // bodyStream(2048), a limit other than the app's rawBodyLimit, throws IllegalStateException
    @Test
    public void FR_HTTP_030_AC6_repeatWithoutBodyReturnsSameStreamAndOtherLimitThrowsIllegalState() {
        List<Object> out = probe(null, (req, o) -> {
            o.add(attempt(req, Request::bodyStream));
            o.add(attempt(req, Request::bodyStream));
            o.add(attempt(req, r -> r.bodyStream(2048L)));
        });
        assertTrue(String.valueOf(out.get(0)), out.get(0) instanceof InputStream);
        assertSame(out.get(0), out.get(1));
        assertTrue(String.valueOf(out.get(2)), out.get(2) instanceof IllegalStateException);
    }

    // FR-HTTP-030 AC-7: bodyStream() counts as bodyStream(L) with L the app's rawBodyLimit: with rawBodyLimit(4096),
    // bodyStream() followed by bodyStream(4096) before any read returns the same object
    @Test
    public void FR_HTTP_030_AC7_bodyStreamCountsAsBodyStreamOfTheRawBodyLimit() {
        List<Object> out = probe(4096L, utf8("abc"), (req, o) -> {
            o.add(attempt(req, Request::bodyStream));
            o.add(attempt(req, r -> r.bodyStream(4096L)));
        });
        assertTrue(String.valueOf(out.get(0)), out.get(0) instanceof InputStream);
        assertSame(out.get(0), out.get(1));
    }

    // FR-HTTP-030 AC-7: the same equality from the other side: bodyStream(4096) then bodyStream() before any read
    @Test
    public void FR_HTTP_030_AC7_bodyStreamOfTheRawBodyLimitThenBareCallReturnsTheSameObject() {
        List<Object> out = probe(4096L, utf8("abc"), (req, o) -> {
            o.add(attempt(req, r -> r.bodyStream(4096L)));
            o.add(attempt(req, Request::bodyStream));
        });
        assertTrue(String.valueOf(out.get(0)), out.get(0) instanceof InputStream);
        assertSame(out.get(0), out.get(1));
    }

    // FR-HTTP-030 AC-7 with IR-APP-009: the one stream shared by bodyStream(4096) and bodyStream() keeps the app's
    // rawBodyLimit, whichever of the two calls came first
    @Test
    public void FR_HTTP_030_AC7_sharedStreamKeepsTheRawBodyLimitWhicheverCallCameFirst() {
        byte[] body = new byte[10000];
        java.util.Arrays.fill(body, (byte) 'x');
        Probe drainShared = (req, o) -> {
            req.bodyStream(4096L);
            InputStream s = req.bodyStream();
            long n = 0;
            try {
                while (s.read() != -1) {
                    n++;
                }
                o.add("eof " + n);
            } catch (BodyTooLargeException e) {
                o.add("tooLarge " + n + " " + e.limit());
            }
        };
        assertEquals(Collections.singletonList("tooLarge 4096 4096"), probe(4096L, body, drainShared));
    }

    // FR-HTTP-030 AC-8: with a body, after one byte was read from bodyStream(), a further bodyStream() and a
    // further bodyStream(2048) each throw BodyAlreadyConsumedException, whatever the limit
    @Test
    public void FR_HTTP_030_AC8_afterOneByteEveryFurtherCallThrowsWhateverTheLimit() {
        List<Object> out = probe(4096L, utf8("abc"), (req, o) -> {
            o.add(req.bodyStream().read());
            o.add(attempt(req, Request::bodyStream));
            o.add(attempt(req, r -> r.bodyStream(2048L)));
            o.add(attempt(req, r -> r.bodyStream(4096L)));
        });
        assertEquals((int) 'a', out.get(0));
        assertConsumed("bodyStream() after a read", out.get(1));
        assertConsumed("bodyStream(2048) after a read", out.get(2));
        assertConsumed("bodyStream(4096), the app limit, after a read", out.get(3));
    }

    /** Runs {@code op} on the first bodyStream(), then records a second bodyStream() call. */
    private static List<Object> afterOperation(Step op) {
        return probe(utf8("abcdef"), (req, o) -> {
            InputStream s = req.bodyStream();
            o.add(s);
            o.add(attempt(req, r -> op.run(r)));
            o.add(attempt(req, Request::bodyStream));
        });
    }

    // FR-HTTP-030 AC-9: a read(byte[]) that returns at least one byte counts as consumption
    @Test
    public void FR_HTTP_030_AC9_arrayReadReturningBytesConsumes() {
        List<Object> out = afterOperation(r -> r.bodyStream().read(new byte[2]));
        // the op's own bodyStream() is the repeat call before consumption, so it reaches the first stream
        assertEquals(2, out.get(1));
        assertConsumed("bodyStream() after read(byte[])", out.get(2));
    }

    // FR-HTTP-030 AC-9: a skip that advances counts as consumption
    @Test
    public void FR_HTTP_030_AC9_skipThatAdvancesConsumes() {
        List<Object> out = afterOperation(r -> r.bodyStream().skip(3));
        assertEquals(3L, out.get(1));
        assertConsumed("bodyStream() after skip(3)", out.get(2));
    }

    // FR-HTTP-030 AC-9: a close of the returned stream counts as consumption
    @Test
    public void FR_HTTP_030_AC9_closeConsumes() {
        List<Object> out = afterOperation(r -> {
            r.bodyStream().close();
            return "closed";
        });
        assertEquals("closed", out.get(1));
        assertConsumed("bodyStream() after close()", out.get(2));
    }

    // FR-HTTP-030 AC-9: available() does not count, so a later call with the same limit returns the same object
    @Test
    public void FR_HTTP_030_AC9_availableDoesNotConsume() {
        List<Object> out = afterOperation(r -> r.bodyStream().available());
        assertTrue(String.valueOf(out.get(1)), out.get(1) instanceof Integer);
        assertSame(out.get(0), out.get(2));
    }

    // FR-HTTP-030 AC-9: a read at the end of an empty body (-1) and a skip(0) return no byte and do not consume
    @Test
    public void FR_HTTP_030_AC9_operationsThatReturnNoByteDoNotConsume() {
        List<Object> out = probe(null, (req, o) -> {
            InputStream s = req.bodyStream();
            o.add(s);
            o.add(s.read());
            o.add(s.skip(0));
            o.add(attempt(req, Request::bodyStream));
        });
        assertEquals(-1, out.get(1));
        assertEquals(0L, out.get(2));
        assertSame(out.get(0), out.get(3));
    }

    /** An adapter request input that records closes and, unlike a strict one, still reads after close. */
    static final class RecordingInput extends InputStream {
        final ByteArrayInputStream source;
        final AtomicInteger closes = new AtomicInteger();

        RecordingInput(byte[] bytes) {
            source = new ByteArrayInputStream(bytes);
        }

        @Override
        public int read() {
            return source.read();
        }

        @Override
        public int read(byte[] b, int off, int len) {
            return source.read(b, off, len);
        }

        @Override
        public void close() {
            closes.incrementAndGet();
        }
    }

    /** A {@link RecordingExchange} whose request input is a {@link RecordingInput}. */
    static final class InputExchange implements HttpExchange {
        final RecordingExchange recording;
        final RecordingInput input;

        InputExchange(byte[] body, String... headerPairs) {
            recording = new RecordingExchange("POST", "/p", headerPairs);
            input = new RecordingInput(body);
        }

        @Override public String methodToken() { return recording.methodToken(); }
        @Override public String rawPath() { return recording.rawPath(); }
        @Override public @Nullable String rawQuery() { return recording.rawQuery(); }
        @Override public String httpVersion() { return recording.httpVersion(); }
        @Override public List<Map.Entry<String, String>> headers() { return recording.headers(); }
        @Override public InputStream requestBody() { return input; }
        @Override public String remoteAddress() { return recording.remoteAddress(); }
        @Override public boolean isSecure() { return recording.isSecure(); }
        @Override
        public OutputStream commit(int status, List<Map.Entry<String, String>> headers, long contentLength)
                throws IOException {
            return recording.commit(status, headers, contentLength);
        }
        @Override public void finish() throws IOException { recording.finish(); }
        @Override public void abort() { recording.abort(); }
        @Override
        public void sendFile(int status, List<Map.Entry<String, String>> headers, File file, long offset, long length)
                throws IOException {
            recording.sendFile(status, headers, file, offset, length);
        }
        @Override
        public void upgrade(List<Map.Entry<String, String>> headers, UpgradeHandler handler) throws IOException {
            recording.upgrade(headers, handler);
        }
        @Override
        public void eventStream(int status, List<Map.Entry<String, String>> headers, StreamDrain drain)
                throws IOException {
            recording.eventStream(status, headers, drain);
        }
    }

    /**
     * Dispatches one request whose handler takes bodyStream(), reads {@code readFirst} bytes, neither reads to the end
     * nor closes, and answers; returns the exchange, the saved stream and the input closes seen inside the handler.
     */
    private static Object[] leaveOpen(int readFirst) {
        DefaultRestApp app = new DefaultRestApp();
        InputExchange ex = new InputExchange(utf8("abcdefgh"), "Content-Length", "8");
        AtomicReference<InputStream> saved = new AtomicReference<>();
        AtomicInteger closesInHandler = new AtomicInteger(-1);
        app.post("/p", (req, res) -> {
            InputStream s = req.bodyStream();
            for (int i = 0; i < readFirst; i++) {
                s.read();
            }
            saved.set(s);
            res.send("ack");
            closesInHandler.set(ex.input.closes.get());
        });
        app.freeze();
        app.dispatcher().handle(ex);
        return new Object[] {ex, saved.get(), closesInHandler.get()};
    }

    private static void assertReleasedOnce(Object[] r) {
        InputExchange ex = (InputExchange) r[0];
        InputStream saved = (InputStream) r[1];
        assertNotNull(saved);
        assertEquals("ack", new String(ex.recording.body.toByteArray(), StandardCharsets.UTF_8));
        assertEquals("the input was closed before the response ended", 0, r[2]);
        assertEquals("adapter input closes after the request", 1, ex.input.closes.get());
        try {
            int b = saved.read();
            fail("the returned stream still reads after the request cleanup: " + b);
        } catch (IOException expected) {
            // closed by the framework
        }
        assertEquals("a read of the closed stream reached the adapter input again", 1, ex.input.closes.get());
    }

    // FR-HTTP-030 AC-10: an unread, unclosed stream and the adapter input are closed exactly once in the request
    // cleanup after the response ends
    @Test
    public void FR_HTTP_030_AC10_unreadUnclosedStreamIsClosedOnceInRequestCleanup() {
        assertReleasedOnce(leaveOpen(0));
    }

    // FR-HTTP-030 AC-10: a stream read in part and left open is closed once with the adapter input in the cleanup
    @Test
    public void FR_HTTP_030_AC10_partlyReadUnclosedStreamIsClosedOnceInRequestCleanup() {
        assertReleasedOnce(leaveOpen(3));
    }

    // FR-HTTP-030 AC-10: a stream the handler closed itself still leaves exactly one close of the adapter input
    @Test
    public void FR_HTTP_030_AC10_handlerCloseStillClosesTheAdapterInputExactlyOnce() {
        DefaultRestApp app = new DefaultRestApp();
        InputExchange ex = new InputExchange(utf8("abcdefgh"), "Content-Length", "8");
        app.post("/p", (req, res) -> {
            try (InputStream s = req.bodyStream()) {
                s.read();
            }
            res.send("ack");
        });
        app.freeze();
        app.dispatcher().handle(ex);
        assertEquals("ack", new String(ex.recording.body.toByteArray(), StandardCharsets.UTF_8));
        assertEquals(1, ex.input.closes.get());
    }
}
