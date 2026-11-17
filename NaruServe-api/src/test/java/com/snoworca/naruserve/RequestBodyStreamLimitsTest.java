package com.snoworca.naruserve;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.internal.DefaultRestApp;
import com.snoworca.naruserve.internal.StandardErrors;
import com.snoworca.naruserve.spi.HttpExchange;
import com.snoworca.naruserve.spi.StreamDrain;
import com.snoworca.naruserve.spi.UpgradeHandler;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.RecordingExchange;
import com.snoworca.naruserve.testing.StubJsonCodec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.junit.Test;

/**
 * The size limits of {@code req.bodyStream()} and {@code req.bodyStream(long)}: the app's rawBodyLimit, the per-call
 * limit, the 413 of the call for a declared length over the limit and BodyTooLargeException while reading.
 */
public class RequestBodyStreamLimitsTest {

    private static final long MIB = 1048576L;

    /** {@code size} bytes whose value depends on the position, so a returned prefix can be checked byte for byte. */
    private static byte[] pattern(int size) {
        byte[] b = new byte[size];
        for (int i = 0; i < size; i++) {
            b[i] = (byte) (i % 251);
        }
        return b;
    }

    /** What reading a stream to its end gave: the bytes handed out and the over-limit failure, if any. */
    static final class Drained {
        final byte[] bytes;
        final @Nullable BodyTooLargeException tooLarge;

        Drained(byte[] bytes, @Nullable BodyTooLargeException tooLarge) {
            this.bytes = bytes;
            this.tooLarge = tooLarge;
        }
    }

    /** Reads {@code in} to its end, with array reads or with single-byte reads. */
    private static Drained drain(InputStream in, boolean singleByte) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            if (singleByte) {
                int b;
                while ((b = in.read()) != -1) {
                    out.write(b);
                }
            } else {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
            }
            return new Drained(out.toByteArray(), null);
        } catch (BodyTooLargeException e) {
            return new Drained(out.toByteArray(), e);
        }
    }

    /** One step a handler runs on the request. */
    interface Take {
        InputStream run(Request req) throws Exception;
    }

    /**
     * POSTs {@code body} without Content-Length (unless the header pairs carry one) to an app with the given
     * rawBodyLimit (the default when not positive) whose handler drains the stream {@code take} returns.
     */
    private static Drained post(long rawBodyLimit, byte[] body, Take take, boolean singleByte, String... headerPairs) {
        InMemoryHttpAdapter a = new InMemoryHttpAdapter();
        RestApp app = RestApp.builder().httpAdapter(a).jsonCodec(new StubJsonCodec()).build();
        if (rawBodyLimit > 0) {
            app.rawBodyLimit(rawBodyLimit);
        }
        List<Object> seen = Collections.synchronizedList(new ArrayList<>());
        app.post("/p", (req, res) -> {
            try {
                seen.add(drain(take.run(req), singleByte));
            } catch (Throwable t) {
                seen.add(t);
            }
            res.send("done");
        });
        app.listen(0);
        try {
            ClientRequest r = ClientRequest.of("POST", "/p").body(body);
            for (int i = 0; i < headerPairs.length; i += 2) {
                r.header(headerPairs[i], headerPairs[i + 1]);
            }
            ClientResponse resp = a.send(r);
            assertEquals(resp.text(), 200, resp.status());
        } finally {
            app.shutdown(5, TimeUnit.SECONDS);
        }
        assertEquals(1, seen.size());
        Object o = seen.get(0);
        if (!(o instanceof Drained)) {
            throw new AssertionError("taking or draining the stream threw " + o, (Throwable) o);
        }
        return (Drained) o;
    }

    private static void assertTooLargeAfter(Drained d, byte[] body, int handedOut, long limit) {
        assertTrue("expected BodyTooLargeException after " + handedOut + " bytes, read " + d.bytes.length
                + " bytes to the end", d.tooLarge != null);
        assertEquals(limit, d.tooLarge.limit());
        assertArrayEquals(Arrays.copyOf(body, handedOut), d.bytes);
    }

    // FR-HTTP-031 AC-1: with app.rawBodyLimit(1024), bodyStream() on a 1025-byte body without Content-Length returns
    // the first 1024 bytes and throws BodyTooLargeException with limit() 1024 on the read that would return byte 1025
    @Test
    public void FR_HTTP_031_AC1_rawBodyLimitHandsOutTheFirst1024BytesThenThrowsOnByte1025() throws Exception {
        byte[] body = pattern(1025);
        assertTooLargeAfter(post(1024, body, Request::bodyStream, false), body, 1024, 1024);
        assertTooLargeAfter(post(1024, body, Request::bodyStream, true), body, 1024, 1024);
    }

    // FR-HTTP-031 AC-1: a body of exactly rawBodyLimit bytes reads to its end, so the limit itself is allowed
    @Test
    public void FR_HTTP_031_AC1_aBodyOfExactlyTheLimitReadsToItsEnd() throws Exception {
        byte[] body = pattern(1024);
        Drained d = post(1024, body, Request::bodyStream, false);
        assertTrue(String.valueOf(d.tooLarge), d.tooLarge == null);
        assertArrayEquals(body, d.bytes);
    }

    // FR-HTTP-031 AC-2: with the app's 1 MiB rawBodyLimit, bodyStream(2097152) reads a 1572864-byte body to its end
    @Test
    public void FR_HTTP_031_AC2_bodyStreamOf2MiBReadsA1Point5MiBBodyAboveTheAppLimit() throws Exception {
        byte[] body = pattern(1572864);
        Drained undeclared = post(-1, body, r -> r.bodyStream(2097152L), false);
        assertTrue(String.valueOf(undeclared.tooLarge), undeclared.tooLarge == null);
        assertArrayEquals(body, undeclared.bytes);
        // with the length declared too: 1572864 is over the app limit but within the limit of this call
        Drained declared = post(-1, body, r -> r.bodyStream(2097152L), false, "Content-Length", "1572864");
        assertTrue(String.valueOf(declared.tooLarge), declared.tooLarge == null);
        assertArrayEquals(body, declared.bytes);
    }

    // FR-HTTP-031 AC-2: with the app's 1 MiB rawBodyLimit, bodyStream(1024) throws BodyTooLargeException with limit()
    // 1024 on the read that would return byte 1025
    @Test
    public void FR_HTTP_031_AC2_bodyStreamOf1024ThrowsOnTheReadOfByte1025() throws Exception {
        byte[] body = pattern(1025);
        assertTooLargeAfter(post(-1, body, r -> r.bodyStream(1024L), false), body, 1024, 1024);
        assertTooLargeAfter(post(-1, body, r -> r.bodyStream(1024L), true), body, 1024, 1024);
    }

    // FR-HTTP-031 AC-2: each limit applies to that call only: after bodyStream(2097152) and bodyStream(1024) ran in
    // the same app, bodyStream() still reads under the app's 1 MiB rawBodyLimit
    @Test
    public void FR_HTTP_031_AC2_perCallLimitsLeaveTheAppLimitOfLaterRequestsUnchanged() throws Exception {
        InMemoryHttpAdapter a = new InMemoryHttpAdapter();
        RestApp app = RestApp.builder().httpAdapter(a).jsonCodec(new StubJsonCodec()).build();
        List<Object> seen = Collections.synchronizedList(new ArrayList<>());
        app.post("/big", (req, res) -> {
            seen.add(drain(req.bodyStream(2097152L), false));
            res.send("done");
        });
        app.post("/small", (req, res) -> {
            seen.add(drain(req.bodyStream(1024L), false));
            res.send("done");
        });
        app.post("/app", (req, res) -> {
            seen.add(drain(req.bodyStream(), false));
            res.send("done");
        });
        app.listen(0);
        byte[] big = pattern(1572864);
        byte[] overApp = pattern((int) MIB + 1);
        try {
            for (String[] call : new String[][] {{"/big", "big"}, {"/small", "big"}, {"/app", "overApp"},
                    {"/big", "big"}, {"/app", "overApp"}}) {
                ClientResponse r = a.send(ClientRequest.of("POST", call[0])
                        .body(call[1].equals("big") ? big : overApp));
                assertEquals(r.text(), 200, r.status());
            }
        } finally {
            app.shutdown(5, TimeUnit.SECONDS);
        }
        assertEquals(5, seen.size());
        Drained first = (Drained) seen.get(0);
        assertTrue(String.valueOf(first.tooLarge), first.tooLarge == null);
        assertEquals(big.length, first.bytes.length);
        assertTooLargeAfter((Drained) seen.get(1), big, 1024, 1024);
        assertTooLargeAfter((Drained) seen.get(2), overApp, (int) MIB, MIB);
        Drained again = (Drained) seen.get(3);
        assertTrue(String.valueOf(again.tooLarge), again.tooLarge == null);
        assertEquals(big.length, again.bytes.length);
        assertTooLargeAfter((Drained) seen.get(4), overApp, (int) MIB, MIB);
    }

    // FR-HTTP-031 AC-3: bodyStream(0), bodyStream(-1) and bodyStream(1073741825) throw IllegalArgumentException
    @Test
    public void FR_HTTP_031_AC3_outOfRangeLimitsThrowIllegalArgumentException() {
        InMemoryHttpAdapter a = new InMemoryHttpAdapter();
        RestApp app = RestApp.builder().httpAdapter(a).jsonCodec(new StubJsonCodec()).build();
        List<Object> seen = Collections.synchronizedList(new ArrayList<>());
        app.post("/p", (req, res) -> {
            for (long bad : new long[] {0L, -1L, 1073741825L}) {
                try {
                    req.bodyStream(bad);
                    seen.add("bodyStream(" + bad + ") returned");
                } catch (Throwable t) {
                    seen.add(t);
                }
            }
            res.send("done");
        });
        app.listen(0);
        try {
            ClientResponse r = a.send(ClientRequest.of("POST", "/p").body(pattern(8)));
            assertEquals(r.text(), 200, r.status());
        } finally {
            app.shutdown(5, TimeUnit.SECONDS);
        }
        assertEquals(3, seen.size());
        for (Object o : seen) {
            assertTrue(String.valueOf(o), o instanceof IllegalArgumentException);
            String m = ((IllegalArgumentException) o).getMessage();
            assertTrue(m, m != null && m.contains("1..1073741824"));
        }
    }

    // FR-HTTP-031 AC-3: bodyStream(1) and bodyStream(1073741824), the ends of the range, are accepted
    @Test
    public void FR_HTTP_031_AC3_theEndsOfTheRangeAreAccepted() throws Exception {
        byte[] one = pattern(1);
        Drained low = post(-1, one, r -> r.bodyStream(1L), false);
        assertTrue(String.valueOf(low.tooLarge), low.tooLarge == null);
        assertArrayEquals(one, low.bytes);
        byte[] body = pattern(4096);
        Drained high = post(-1, body, r -> r.bodyStream(1073741824L), false, "Content-Length", "4096");
        assertTrue(String.valueOf(high.tooLarge), high.tooLarge == null);
        assertArrayEquals(body, high.bytes);
        // bodyStream(1) still enforces its one byte
        assertTooLargeAfter(post(-1, pattern(2), r -> r.bodyStream(1L), false), pattern(2), 1, 1);
    }

    /** An adapter request input that counts the bytes and read calls that reached it. */
    static final class CountingInput extends InputStream {
        final ByteArrayInputStream source;
        final AtomicLong reads = new AtomicLong();
        final AtomicLong bytes = new AtomicLong();

        CountingInput(byte[] body) {
            source = new ByteArrayInputStream(body);
        }

        @Override
        public int read() {
            reads.incrementAndGet();
            int b = source.read();
            if (b >= 0) {
                bytes.incrementAndGet();
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            reads.incrementAndGet();
            int n = source.read(b, off, len);
            if (n > 0) {
                bytes.addAndGet(n);
            }
            return n;
        }

        @Override
        public long skip(long n) {
            reads.incrementAndGet();
            long s = source.skip(n);
            bytes.addAndGet(s);
            return s;
        }
    }

    /** A {@link RecordingExchange} whose request input is a {@link CountingInput}. */
    static final class CountingExchange implements HttpExchange {
        final RecordingExchange recording;
        final CountingInput input;

        CountingExchange(byte[] body, String... headerPairs) {
            recording = new RecordingExchange("POST", "/p", headerPairs);
            input = new CountingInput(body);
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
     * Dispatches one POST with a declared Content-Length whose handler calls {@code take} and records what the call
     * itself did: {@code "returned"} or the exception it threw. Returns {outcome, exchange}.
     */
    private static Object[] declared(long rawBodyLimit, int length, Take take) {
        DefaultRestApp app = new DefaultRestApp();
        if (rawBodyLimit > 0) {
            app.rawBodyLimit(rawBodyLimit);
        }
        CountingExchange ex = new CountingExchange(pattern(length), "Content-Length", String.valueOf(length));
        List<Object> seen = Collections.synchronizedList(new ArrayList<>());
        long[] readsAtCall = {-1};
        app.post("/p", (req, res) -> {
            try {
                take.run(req);
                seen.add("returned");
            } catch (Throwable t) {
                seen.add(t);
            }
            readsAtCall[0] = ex.input.reads.get();
            res.send("ack");
        });
        app.freeze();
        app.dispatcher().handle(ex);
        assertEquals(1, seen.size());
        assertEquals("the call reached the adapter input", 0L, readsAtCall[0]);
        return new Object[] {seen.get(0), ex};
    }

    private static void assert413BeforeAnyRead(Object[] r) {
        Object outcome = r[0];
        CountingExchange ex = (CountingExchange) r[1];
        assertTrue("the bodyStream call itself must throw HttpException 413, got " + outcome,
                outcome instanceof HttpException);
        assertEquals(413, ((HttpException) outcome).status());
        assertEquals("no read reached the adapter's request input", 0L, ex.input.bytes.get());
    }

    // FR-HTTP-031 AC-4: a declared Content-Length over the app's rawBodyLimit makes the bodyStream() call itself throw
    // HttpException 413 before any byte is read from the adapter's request input
    @Test
    public void FR_HTTP_031_AC4_bodyStreamCallThrows413ForADeclaredLengthOverTheRawBodyLimit() {
        assert413BeforeAnyRead(declared(1024, 1025, Request::bodyStream));
    }

    // FR-HTTP-031 AC-4: a declared Content-Length over the argument makes the bodyStream(long) call itself throw
    // HttpException 413 before any byte is read, also when the declared length is within the app's rawBodyLimit
    @Test
    public void FR_HTTP_031_AC4_bodyStreamLongCallThrows413ForADeclaredLengthOverTheArgument() {
        assert413BeforeAnyRead(declared(-1, 1025, r -> r.bodyStream(1024L)));
    }

    // FR-HTTP-031 AC-4: the limit in force for bodyStream(long) is the argument: a declared length over the app's
    // rawBodyLimit but within the argument, and a declared length equal to the limit, are not refused
    @Test
    public void FR_HTTP_031_AC4_aDeclaredLengthWithinTheLimitInForceIsNotRefused() {
        assertEquals("returned", declared(1024, 1025, r -> r.bodyStream(2048L))[0]);
        assertEquals("returned", declared(1024, 1024, Request::bodyStream)[0]);
        assertEquals("returned", declared(-1, 1024, r -> r.bodyStream(1024L))[0]);
    }

    // FR-HTTP-031 AC-4: the 413 of the call leaves the body taken, as req.body(...) does for its own 413, so neither
    // a repeated bodyStream with a larger limit nor req.body(Map.class) reads the oversized body afterwards
    @Test
    public void FR_HTTP_031_AC4_the413OfTheCallLeavesTheBodyConsumed() {
        InMemoryHttpAdapter a = new InMemoryHttpAdapter();
        RestApp app = RestApp.builder().httpAdapter(a).jsonCodec(new StubJsonCodec()).build();
        app.rawBodyLimit(1024);
        List<Object> seen = Collections.synchronizedList(new ArrayList<>());
        app.post("/p", (req, res) -> {
            for (Take t : new Take[] {Request::bodyStream, r -> r.bodyStream(4096L), r -> {
                r.body(Map.class);
                return new ByteArrayInputStream(new byte[0]);
            }}) {
                try {
                    t.run(req);
                    seen.add("returned");
                } catch (Throwable e) {
                    seen.add(e);
                }
            }
            res.send("done");
        });
        app.listen(0);
        try {
            ClientResponse r = a.send(ClientRequest.of("POST", "/p").header("Content-Type", "application/json")
                    .header("Content-Length", "1025").body(pattern(1025)));
            assertEquals(r.text(), 200, r.status());
        } finally {
            app.shutdown(5, TimeUnit.SECONDS);
        }
        assertEquals(3, seen.size());
        assertTrue(String.valueOf(seen.get(0)), seen.get(0) instanceof HttpException);
        assertEquals(413, ((HttpException) seen.get(0)).status());
        assertTrue("bodyStream(4096) after the 413 gave " + seen.get(1),
                seen.get(1) instanceof BodyAlreadyConsumedException);
        assertTrue("body(Map.class) after the 413 gave " + seen.get(2),
                seen.get(2) instanceof BodyAlreadyConsumedException);
    }

    // FR-HTTP-031 AC-5: a chunked body (no Content-Length) larger than rawBodyLimit, read through bodyStream(),
    // throws BodyTooLargeException on the read that crosses the limit, and when the handler lets it leave unwrapped
    // the client receives 413 with the standard error body (FR-APP-006 code PAYLOAD_TOO_LARGE)
    @Test
    public void FR_HTTP_031_AC5_anEscapedOverrunOfAChunkedBodyIsAnswered413WithTheStandardBody() {
        InMemoryHttpAdapter a = new InMemoryHttpAdapter();
        RestApp app = RestApp.builder().httpAdapter(a).jsonCodec(new StubJsonCodec()).build();
        app.rawBodyLimit(1024);
        List<Object> seen = Collections.synchronizedList(new ArrayList<>());
        app.post("/p", (req, res) -> {
            long n = 0;
            byte[] buf = new byte[100];
            InputStream in = req.bodyStream();
            try {
                int r;
                while ((r = in.read(buf)) != -1) {
                    n += r;
                }
            } catch (IOException e) {
                seen.add(n);
                seen.add(e);
                throw e; // left unwrapped
            }
            res.send("read to the end: " + n);
        });
        app.listen(0);
        ClientResponse r;
        try {
            r = a.send(ClientRequest.of("POST", "/p").header("Transfer-Encoding", "chunked").body(pattern(5000)));
        } finally {
            app.shutdown(5, TimeUnit.SECONDS);
        }
        assertEquals(r.text(), 413, r.status());
        assertEquals(2, seen.size());
        assertEquals("bytes handed out before the read that crossed the limit", 1024L, seen.get(0));
        assertTrue(String.valueOf(seen.get(1)), seen.get(1) instanceof BodyTooLargeException);
        assertEquals(1024L, ((BodyTooLargeException) seen.get(1)).limit());
        JsonBodyAcceptanceTest.assertDefaultError(413, BodyTooLargeException.class, r);
        assertEquals("PAYLOAD_TOO_LARGE", StandardErrors.code(413));
    }

    // FR-HTTP-031 AC-5: the per-call limit is enforced the same way: bodyStream(1024) on a larger chunked body lets
    // its overrun escape as 413 too
    @Test
    public void FR_HTTP_031_AC5_anEscapedOverrunOfTheCallLimitIsAnswered413() {
        InMemoryHttpAdapter a = new InMemoryHttpAdapter();
        RestApp app = RestApp.builder().httpAdapter(a).jsonCodec(new StubJsonCodec()).build();
        app.post("/p", (req, res) -> {
            InputStream in = req.bodyStream(1024L);
            byte[] buf = new byte[100];
            while (in.read(buf) != -1) {
                // drain
            }
            res.send("read to the end");
        });
        app.listen(0);
        ClientResponse r;
        try {
            r = a.send(ClientRequest.of("POST", "/p").header("Transfer-Encoding", "chunked").body(pattern(5000)));
        } finally {
            app.shutdown(5, TimeUnit.SECONDS);
        }
        JsonBodyAcceptanceTest.assertDefaultError(413, BodyTooLargeException.class, r);
    }

    // FR-HTTP-031 AC-3 guard: a refused argument leaves nothing taken, so the bodyStream call that follows works
    @Test
    public void FR_HTTP_031_AC3_aRefusedArgumentTakesNothing() throws Exception {
        byte[] body = pattern(16);
        Drained d = post(-1, body, r -> {
            try {
                r.bodyStream(0L);
                fail("bodyStream(0) must throw");
            } catch (IllegalArgumentException expected) {
                // refused
            }
            return r.bodyStream(1024L);
        }, false);
        assertArrayEquals(body, d.bytes);
    }
}
