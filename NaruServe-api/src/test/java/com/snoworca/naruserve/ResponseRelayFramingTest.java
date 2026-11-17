package com.snoworca.naruserve;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.internal.DefaultResponse;
import com.snoworca.naruserve.internal.ResponseState;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.RecordingExchange;
import com.snoworca.naruserve.testing.TestApp;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.Test;

/** res.relay framing: exact length, surplus stop, unknown length, bad lengths, bodyless statuses, stream ownership. */
public class ResponseRelayFramingTest {

    /** Real byte source that records how much the copy read, how often it was closed, and can fail mid-read. */
    private static final class CountingInputStream extends FilterInputStream {
        long bytesRead;
        int closes;
        long failAfter = Long.MAX_VALUE;
        Runnable afterFirstRead = () -> { };

        CountingInputStream(byte[] data) {
            super(new ByteArrayInputStream(data));
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (bytesRead >= failAfter) {
                throw new IOException("source failed");
            }
            int n = super.read(b, off, len);
            if (n > 0) {
                boolean first = bytesRead == 0;
                bytesRead += n;
                if (first) {
                    afterFirstRead.run();
                }
            }
            return n;
        }

        @Override
        public void close() throws IOException {
            closes++;
            super.close();
        }
    }

    private static byte[] data(int size) {
        byte[] d = new byte[size];
        for (int i = 0; i < size; i++) {
            d[i] = (byte) (i * 31 + i / 251);
        }
        return d;
    }

    private final TestApp t = TestApp.create();
    private final AtomicReference<DefaultResponse> last = new AtomicReference<>();

    private ClientResponse relay(String method, int status, CountingInputStream in, long length) {
        t.app().get("/r", (req, res) -> {
            last.set((DefaultResponse) res);
            if (status != 0) {
                res.status(status);
            }
            res.relay(in, length);
        });
        return t.send(method, "/r");
    }

    // FR-HTTP-037 AC-1: relay(in, 5) over a stream that ends after 3 bytes aborts after the commit
    @Test
    public void FR_HTTP_037_AC1_streamEndingBeforeTheLengthAbortsTheResponse() {
        CountingInputStream in = new CountingInputStream(data(3));
        ClientResponse r = relay("GET", 0, in, 5);
        assertEquals(200, r.status());
        assertEquals("5", r.header("Content-Length"));
        assertTrue(r.abnormallyClosed());
        assertEquals(ResponseState.ABORTED, last.get().state());
        assertEquals(1, in.closes);
    }

    // FR-HTTP-037 AC-2: relay(in, 5) over 8 bytes sends exactly 5, closes the stream and completes without abort
    @Test
    public void FR_HTTP_037_AC2_surplusBytesBeyondTheLengthAreNotSent() {
        byte[] eight = data(8);
        CountingInputStream in = new CountingInputStream(eight);
        ClientResponse r = relay("GET", 0, in, 5);
        assertEquals(200, r.status());
        assertEquals("5", r.header("Content-Length"));
        assertEquals(5L, r.contentLength());
        assertArrayEquals(Arrays.copyOf(eight, 5), r.body());
        assertFalse(r.abnormallyClosed());
        assertEquals(ResponseState.COMPLETED, last.get().state());
        assertEquals(1, in.closes);
    }

    // FR-HTTP-037 AC-2: the surplus stop holds across several 8192-byte reads too
    @Test
    public void FR_HTTP_037_AC2_surplusStopAcrossSeveralReads() {
        byte[] d = data(30000);
        CountingInputStream in = new CountingInputStream(d);
        ClientResponse r = relay("GET", 0, in, 20000);
        assertArrayEquals(Arrays.copyOf(d, 20000), r.body());
        assertEquals(20000L, in.bytesRead);
        assertFalse(r.abnormallyClosed());
        assertEquals(ResponseState.COMPLETED, last.get().state());
        assertEquals(1, in.closes);
    }

    // FR-HTTP-037 AC-3: relay(in, -1) commits with unknown length, no Content-Length, and copies to the end
    @Test
    public void FR_HTTP_037_AC3_unknownLengthCopiesTheStreamToItsEnd() {
        byte[] d = data(20000);
        CountingInputStream in = new CountingInputStream(d);
        ClientResponse r = relay("GET", 0, in, -1);
        assertEquals(200, r.status());
        assertEquals(-1L, r.contentLength());
        assertNull(r.header("Content-Length"));
        assertArrayEquals(d, r.body());
        assertFalse(r.abnormallyClosed());
        assertEquals(ResponseState.COMPLETED, last.get().state());
        assertEquals(1, in.closes);
    }

    // FR-HTTP-037 AC-4: relay(in, -2) and relay(in, Long.MIN_VALUE) throw IllegalArgumentException and close the stream
    @Test
    public void FR_HTTP_037_AC4_lengthBelowMinusOneThrowsAndClosesTheStream() {
        for (long bad : new long[] {-2L, Long.MIN_VALUE}) {
            RecordingExchange ex = new RecordingExchange("GET", "/r");
            DefaultResponse res = new DefaultResponse(ex, false);
            CountingInputStream in = new CountingInputStream(data(10));
            try {
                res.relay(in, bad);
                fail("expected IllegalArgumentException for " + bad);
            } catch (IllegalArgumentException expected) {
                // refused
            }
            assertEquals("closes for " + bad, 1, in.closes);
            assertEquals(0L, in.bytesRead);
            assertEquals(0, ex.commits.get());
        }
    }

    // FR-HTTP-037 AC-4: through the app the handler sees the IllegalArgumentException and the stream is closed
    @Test
    public void FR_HTTP_037_AC4_handlerSeesTheRefusal() {
        CountingInputStream in = new CountingInputStream(data(10));
        List<Throwable> thrown = new ArrayList<>();
        t.app().get("/r", (req, res) -> {
            try {
                res.relay(in, -2);
            } catch (RuntimeException e) {
                thrown.add(e);
            }
            res.send("fallback");
        });
        ClientResponse r = t.send("GET", "/r");
        assertEquals(1, thrown.size());
        assertTrue(thrown.get(0).toString(), thrown.get(0) instanceof IllegalArgumentException);
        assertEquals(1, in.closes);
        assertArrayEquals("fallback".getBytes(StandardCharsets.UTF_8), r.body());
    }

    // FR-HTTP-037 AC-5: HEAD and 304 relay(in, 5) commit Content-Length 5, no body bytes, close unread, complete
    @Test
    public void FR_HTTP_037_AC5_headAnd304KeepTheLengthWithNoBody() {
        String[][] cases = {{"HEAD", "0"}, {"GET", "304"}};
        for (String[] c : cases) {
            TestApp app = TestApp.create();
            AtomicReference<DefaultResponse> seen = new AtomicReference<>();
            CountingInputStream in = new CountingInputStream(data(5));
            int status = Integer.parseInt(c[1]);
            app.app().get("/r", (req, res) -> {
                seen.set((DefaultResponse) res);
                if (status != 0) {
                    res.status(status);
                }
                res.relay(in, 5);
            });
            ClientResponse r = app.send(c[0], "/r");
            String label = c[0] + " " + c[1];
            assertEquals(label, status == 0 ? 200 : status, r.status());
            assertEquals(label, Collections.singletonList("5"), values(r, "Content-Length"));
            assertEquals(label, 0L, r.contentLength());
            assertEquals(label, 0, r.body().length);
            assertEquals(label, 0L, in.bytesRead);
            assertEquals(label, 1, in.closes);
            assertFalse(label, r.abnormallyClosed());
            assertEquals(label, ResponseState.COMPLETED, seen.get().state());
        }
    }

    // FR-HTTP-037 AC-5: HEAD and 304 relay(in, -1) commit no Content-Length and no body
    @Test
    public void FR_HTTP_037_AC5_headAnd304WithUnknownLengthSendNoLengthAndNoBody() {
        String[][] cases = {{"HEAD", "0"}, {"GET", "304"}};
        for (String[] c : cases) {
            TestApp app = TestApp.create();
            CountingInputStream in = new CountingInputStream(data(5));
            int status = Integer.parseInt(c[1]);
            app.app().get("/r", (req, res) -> {
                if (status != 0) {
                    res.status(status);
                }
                res.relay(in, -1);
            });
            ClientResponse r = app.send(c[0], "/r");
            String label = c[0] + " " + c[1];
            assertNull(label, r.header("Content-Length"));
            assertEquals(label, 0L, r.contentLength());
            assertEquals(label, 0, r.body().length);
            assertEquals(label, 0L, in.bytesRead);
            assertEquals(label, 1, in.closes);
            assertFalse(label, r.abnormallyClosed());
        }
    }

    // FR-HTTP-037 AC-6: a 204 relay(in, 5) sends neither body nor Content-Length, closes unread and completes
    @Test
    public void FR_HTTP_037_AC6_status204SendsNoLengthAndNoBody() {
        CountingInputStream in = new CountingInputStream(data(5));
        ClientResponse r = relay("GET", 204, in, 5);
        assertEquals(204, r.status());
        assertNull(r.header("Content-Length"));
        assertEquals(0L, r.contentLength());
        assertEquals(0, r.body().length);
        assertEquals(0L, in.bytesRead);
        assertEquals(1, in.closes);
        assertFalse(r.abnormallyClosed());
        assertEquals(ResponseState.COMPLETED, last.get().state());
    }

    // FR-HTTP-037 AC-6: the committed header list of a 204 relay carries no Content-Length, also over an app-set one
    @Test
    public void FR_HTTP_037_AC6_status204CommitsNoContentLengthHeader() {
        RecordingExchange ex = new RecordingExchange("GET", "/r");
        DefaultResponse res = new DefaultResponse(ex, false);
        CountingInputStream in = new CountingInputStream(data(5));
        res.status(204).header("Content-Length", "5");
        res.relay(in, 5);
        assertTrue(ex.committed("Content-Length").isEmpty());
        assertEquals(0L, ex.contentLength);
        assertEquals(0, ex.body.size());
        assertEquals(1, in.closes);
        assertEquals(ResponseState.COMPLETED, res.state());
    }

    // FR-HTTP-037 AC-7: relay commits and returns before the input is read; the copy runs after the handler returns
    @Test
    public void FR_HTTP_037_AC7_relayReturnsBeforeTheInputIsRead() {
        CountingInputStream in = new CountingInputStream(data(50000));
        List<Object> seen = new ArrayList<>();
        AtomicBoolean handlerReturned = new AtomicBoolean();
        List<Boolean> returnedAtFirstRead = new ArrayList<>();
        in.afterFirstRead = () -> returnedAtFirstRead.add(handlerReturned.get());
        t.app().get("/r", (req, res) -> {
            last.set((DefaultResponse) res);
            res.relay(in, 50000);
            seen.add(in.bytesRead);
            seen.add(last.get().state());
            seen.add(res.isCommitted());
            pause(50); // an eager copy started by relay itself would read during this pause
            handlerReturned.set(true);
        });
        ClientResponse r = t.send("GET", "/r");
        assertEquals(0L, seen.get(0));
        assertEquals(ResponseState.STREAMING, seen.get(1));
        assertEquals(true, seen.get(2));
        assertEquals(Collections.singletonList(true), returnedAtFirstRead);
        assertEquals(50000L, in.bytesRead);
        assertEquals(50000, r.body().length);
        assertEquals(ResponseState.COMPLETED, last.get().state());
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // FR-HTTP-037 AC-8: the stream is closed exactly once after a successful copy
    @Test
    public void FR_HTTP_037_AC8_closedOnceAfterSuccess() {
        CountingInputStream in = new CountingInputStream(data(20000));
        relay("GET", 0, in, 20000);
        assertEquals(1, in.closes);
        assertEquals(ResponseState.COMPLETED, last.get().state());
    }

    // FR-HTTP-037 AC-8: the stream is closed exactly once after a read failure
    @Test
    public void FR_HTTP_037_AC8_closedOnceAfterReadFailure() {
        CountingInputStream in = new CountingInputStream(data(20000));
        in.failAfter = 8192;
        ClientResponse r = relay("GET", 0, in, 20000);
        assertEquals(1, in.closes);
        assertTrue(r.abnormallyClosed());
        assertEquals(ResponseState.ABORTED, last.get().state());
    }

    // FR-HTTP-037 AC-8: the stream is closed exactly once after res.abort during the transfer
    @Test
    public void FR_HTTP_037_AC8_closedOnceAfterAbortDuringTheTransfer() {
        CountingInputStream in = new CountingInputStream(data(100000));
        in.afterFirstRead = () -> last.get().abort(new IOException("x"));
        ClientResponse r = relay("GET", 0, in, 100000);
        assertEquals(1, in.closes);
        assertTrue(r.abnormallyClosed());
        assertEquals(ResponseState.ABORTED, last.get().state());
        assertTrue(r.body().length < 100000);
    }

    // FR-HTTP-037 AC-8: relay on an already committed response throws IllegalStateException and closes its stream
    @Test
    public void FR_HTTP_037_AC8_relayOnACommittedResponseThrowsAndClosesTheStream() {
        List<Consumer<Response>> commits = new ArrayList<>();
        commits.add(res -> res.send("a"));
        commits.add(res -> res.stream(new ByteArrayInputStream(data(10)), "text/plain"));
        commits.add(res -> res.relay(new ByteArrayInputStream(data(10)), 10));
        for (int i = 0; i < commits.size(); i++) {
            TestApp app = TestApp.create();
            Consumer<Response> first = commits.get(i);
            CountingInputStream in = new CountingInputStream(data(10));
            List<Throwable> thrown = new ArrayList<>();
            app.app().get("/r", (req, res) -> {
                first.accept(res);
                try {
                    res.relay(in, 10);
                } catch (RuntimeException e) {
                    thrown.add(e);
                }
            });
            app.send("GET", "/r");
            assertEquals("case " + i, 1, thrown.size());
            assertTrue("case " + i + ": " + thrown.get(0), thrown.get(0) instanceof IllegalStateException);
            assertEquals("case " + i, 1, in.closes);
            assertEquals("case " + i, 0L, in.bytesRead);
        }
    }

    // FR-HTTP-037 AC-9: res.status(204).send("x") yields 204 with zero body bytes
    @Test
    public void FR_HTTP_037_AC9_status204SendYieldsZeroBodyBytes() {
        t.app().get("/r", (req, res) -> res.status(204).send("x"));
        ClientResponse r = t.send("GET", "/r");
        assertEquals(204, r.status());
        assertEquals(0, r.body().length);
        assertNull(r.header("Content-Length"));
    }

    // FR-HTTP-037 AC-9: every body writer other than relay commits a 204 or 304 with length 0, no Content-Length, no body
    @Test
    public void FR_HTTP_037_AC9_otherWritersOn204And304CommitNoLengthAndNoBody() {
        List<Consumer<Response>> writers = new ArrayList<>();
        writers.add(res -> res.send("x"));
        writers.add(res -> res.send(new byte[] {1, 2}));
        writers.add(res -> res.html("<p>"));
        writers.add(Response::end);
        writers.add(res -> res.stream(new ByteArrayInputStream(data(4)), "text/plain"));
        for (int status : new int[] {204, 304}) {
            for (int i = 0; i < writers.size(); i++) {
                RecordingExchange ex = new RecordingExchange("GET", "/r");
                DefaultResponse res = new DefaultResponse(ex, false);
                res.status(status).header("Content-Length", "7");
                writers.get(i).accept(res);
                String label = status + " writer " + i;
                assertTrue(label, ex.committed("Content-Length").isEmpty());
                assertEquals(label, 0L, ex.contentLength);
                assertEquals(label, 0, ex.body.size());
                assertEquals(label, ResponseState.COMPLETED, res.state());
            }
        }
    }

    // FR-HTTP-037 AC-9: relay alone keeps a non-negative length as Content-Length on a 304 in the committed headers
    @Test
    public void FR_HTTP_037_AC9_relayAloneKeepsTheLengthOn304() {
        RecordingExchange ex = new RecordingExchange("GET", "/r");
        DefaultResponse res = new DefaultResponse(ex, false);
        CountingInputStream in = new CountingInputStream(data(5));
        res.status(304).relay(in, 5);
        assertEquals(Collections.singletonList("5"), ex.committed("Content-Length"));
        assertEquals(0L, ex.contentLength);
        assertEquals(0, ex.body.size());
        assertEquals(0L, in.bytesRead);
        assertEquals(1, in.closes);
    }

    private static List<String> values(ClientResponse r, String name) {
        List<String> out = new ArrayList<>();
        for (java.util.Map.Entry<String, String> h : r.headers()) {
            if (h.getKey().equalsIgnoreCase(name)) {
                out.add(h.getValue());
            }
        }
        return out;
    }
}
