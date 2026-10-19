package com.snoworca.naruserve.internal;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.TestApp;
import com.snoworca.naruserve.spi.HttpExchange;
import com.snoworca.naruserve.spi.StreamDrain;
import com.snoworca.naruserve.spi.UpgradeHandler;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.Map;
import java.io.FilterInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/** res.stream: deferred copy after the handler returns, bounded reads, single close, abort and read failure. */
public class ResponseStreamTest {

    private static final int MIB = 1024 * 1024;

    /** Real byte source that records what the copy asked for and how often it was closed. */
    private static class CountingInputStream extends FilterInputStream {
        int maxRequested;
        long bytesRead;
        int closes;
        long failAfter = Long.MAX_VALUE;
        Runnable afterFirstRead = () -> { };

        CountingInputStream(byte[] data) {
            super(new ByteArrayInputStream(data));
        }

        @Override
        public int read() throws IOException {
            throw new AssertionError("single-byte read used by the copy");
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            maxRequested = Math.max(maxRequested, len);
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

    private ClientResponse stream(CountingInputStream in, String contentType) {
        t.app().get("/s", (req, res) -> {
            last.set((DefaultResponse) res);
            res.stream(in, contentType);
        });
        return t.send("GET", "/s");
    }

    // SDS-AC-3; FR-HTTP-014 AC-4
    @Test
    public void sdsAc3_FR_HTTP_014_AC4_oneMibIsCopiedInOrderInBoundedReads() {
        byte[] d = data(MIB);
        CountingInputStream in = new CountingInputStream(d);
        ClientResponse r = stream(in, "application/pdf");
        assertEquals(200, r.status());
        assertEquals("application/pdf", r.header("Content-Type"));
        assertNull(r.header("Content-Length"));
        assertArrayEquals(d, r.body());
        assertTrue("requested " + in.maxRequested, in.maxRequested < MIB);
        assertEquals(8192, in.maxRequested);
        assertFalse(r.abnormallyClosed());
    }

    // SDS-AC-4; FR-HTTP-014 AC-5
    @Test
    public void sdsAc4_FR_HTTP_014_AC5_inputClosedOnceAfterSuccess() {
        CountingInputStream in = new CountingInputStream(data(20000));
        stream(in, "text/plain");
        assertEquals(1, in.closes);
        assertEquals(ResponseState.COMPLETED, last.get().state());
    }

    // SDS-AC-4; FR-HTTP-014 AC-5
    @Test
    public void sdsAc4_FR_HTTP_014_AC5_inputClosedOnceAfterReadFailure() {
        CountingInputStream in = new CountingInputStream(data(20000));
        in.failAfter = 8192;
        stream(in, "text/plain");
        assertEquals(1, in.closes);
        assertEquals(ResponseState.ABORTED, last.get().state());
    }

    // SDS-AC-4; FR-HTTP-014 AC-5, SDS-AC-9; FR-HTTP-015 AC-2
    @Test
    public void sdsAc4_sdsAc9_FR_HTTP_014_AC5_FR_HTTP_015_AC2_abortDuringTransferAfterHandlerReturned() {
        CountingInputStream in = new CountingInputStream(data(100000));
        boolean[] handlerReturned = new boolean[1];
        List<Boolean> returnedAtAbort = new ArrayList<>();
        in.afterFirstRead = () -> {
            returnedAtAbort.add(handlerReturned[0]);
            last.get().abort(new IOException("x"));
        };
        t.app().get("/s", (req, res) -> {
            last.set((DefaultResponse) res);
            res.stream(in, "text/plain");
            handlerReturned[0] = true;
        });
        ClientResponse r = t.send("GET", "/s");
        assertEquals(Collections.singletonList(true), returnedAtAbort);
        assertEquals(1, in.closes);
        assertEquals(ResponseState.ABORTED, last.get().state());
        assertTrue(r.abnormallyClosed());
        assertTrue(r.body().length < 100000);
    }

    // SDS-AC-5; FR-HTTP-014 AC-7, FR-HTTP-016 AC-4
    @Test
    public void sdsAc5_FR_HTTP_014_AC7_FR_HTTP_016_AC4_streamReturnsBeforeTheInputIsRead() {
        CountingInputStream in = new CountingInputStream(data(50000));
        List<Object> seen = new ArrayList<>();
        t.app().get("/s", (req, res) -> {
            last.set((DefaultResponse) res);
            res.stream(in, "text/plain");
            seen.add(in.bytesRead);
            seen.add(last.get().state());
            seen.add(res.isCommitted());
        });
        ClientResponse r = t.send("GET", "/s");
        assertEquals(0L, seen.get(0));
        assertEquals(ResponseState.STREAMING, seen.get(1));
        assertEquals(true, seen.get(2));
        assertEquals(50000L, in.bytesRead);
        assertEquals(ResponseState.COMPLETED, last.get().state());
        assertEquals(50000, r.body().length);
    }

    // SDS-AC-5; FR-HTTP-016 AC-5
    @Test
    public void sdsAc5_FR_HTTP_016_AC5_statusAndHeaderThrowWhileStreaming() {
        CountingInputStream in = new CountingInputStream(data(10));
        List<String> refused = new ArrayList<>();
        t.app().get("/s", (req, res) -> {
            res.status(201).header("X-A", "1").stream(in, "text/plain");
            try {
                res.status(500);
            } catch (IllegalStateException e) {
                refused.add(e.getMessage());
            }
            try {
                res.header("X", "1");
            } catch (IllegalStateException e) {
                refused.add(e.getMessage());
            }
        });
        ClientResponse r = t.send("GET", "/s");
        assertEquals(2, refused.size());
        assertTrue(refused.get(0), refused.get(0).contains("STREAMING"));
        assertTrue(refused.get(1), refused.get(1).contains("STREAMING"));
        assertEquals(201, r.status());
        assertEquals("1", r.header("X-A"));
        assertNull(r.header("X"));
        assertEquals(10, r.body().length);
    }

    // SDS-AC-9; FR-HTTP-015 AC-3, FR-HTTP-018 AC-3
    @Test
    public void sdsAc9_FR_HTTP_015_AC3_FR_HTTP_018_AC3_abortAfterStreamCompletedIsNoOp() {
        CountingInputStream in = new CountingInputStream(data(100));
        ClientResponse r = stream(in, "text/plain");
        last.get().abort(new IOException("late"));
        assertEquals(ResponseState.COMPLETED, last.get().state());
        assertEquals(1, in.closes);
        assertFalse(r.abnormallyClosed());
    }

    // SDS-AC-11; FR-HTTP-016 AC-4
    @Test
    public void sdsAc11_FR_HTTP_016_AC4_readFailureAfterHandlerReturnEndsAbortedWithoutRethrow() {
        CountingInputStream in = new CountingInputStream(data(30000));
        in.failAfter = 16384;
        ClientResponse r = stream(in, "text/plain");
        assertEquals(ResponseState.ABORTED, last.get().state());
        assertEquals(1, in.closes);
        assertTrue(r.abnormallyClosed());
        assertEquals(16384, r.body().length);
    }

    // SDS-AC-13; FR-HTTP-017 AC-3
    @Test
    public void sdsAc13_FR_HTTP_017_AC3_headStreamClosesTheInputWithoutCopying() {
        CountingInputStream in = new CountingInputStream(data(100));
        t.app().head("/s", (req, res) -> {
            last.set((DefaultResponse) res);
            res.stream(in, "text/plain");
        });
        ClientResponse r = t.send("HEAD", "/s");
        assertEquals(200, r.status());
        assertEquals("text/plain", r.header("Content-Type"));
        assertEquals(0, r.body().length);
        assertEquals(0L, in.bytesRead);
        assertEquals(1, in.closes);
        assertEquals(ResponseState.COMPLETED, last.get().state());
        assertFalse(r.abnormallyClosed());
    }

    // SDS-AC-4; FR-HTTP-014 AC-5
    @Test
    public void sdsAc4_FR_HTTP_014_AC5_streamRejectedAfterCompletionStillClosesTheInput() {
        CountingInputStream in = new CountingInputStream(data(10));
        t.app().get("/s", (req, res) -> {
            res.send("a");
            try {
                res.stream(in, "text/plain");
                fail("expected IllegalStateException");
            } catch (IllegalStateException expected) {
                // already completed
            }
        });
        t.send("GET", "/s");
        assertEquals(1, in.closes);
        assertEquals(0L, in.bytesRead);
    }

    /**
     * Hand-written exchange that counts the calls the core makes, so a test can tell the response's own abort from
     * the in-memory adapter's fallback abort of an unfinished exchange.
     */
    static final class RecordingExchange implements HttpExchange {
        final String method;
        final String path;
        int commits;
        int finishes;
        int aborts;
        int status;
        long failWriteAfter = Long.MAX_VALUE;
        Runnable onWrite = () -> { };
        Runnable onCommit = () -> { };
        List<Map.Entry<String, String>> headers = Collections.emptyList();
        final ByteArrayOutputStream body = new ByteArrayOutputStream();

        /** Values of committed header {@code name}, case-insensitive. */
        List<String> committed(String name) {
            List<String> values = new ArrayList<>();
            for (Map.Entry<String, String> h : headers) {
                if (h.getKey().equalsIgnoreCase(name)) {
                    values.add(h.getValue());
                }
            }
            return values;
        }

        RecordingExchange(String method, String path) {
            this.method = method;
            this.path = path;
        }

        @Override
        public String methodToken() {
            return method;
        }

        @Override
        public String rawPath() {
            return path;
        }

        @Override
        public String rawQuery() {
            return null;
        }

        @Override
        public String httpVersion() {
            return "HTTP/1.1";
        }

        @Override
        public List<Map.Entry<String, String>> headers() {
            return Collections.emptyList();
        }

        @Override
        public InputStream requestBody() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public String remoteAddress() {
            return "127.0.0.1";
        }

        @Override
        public boolean isSecure() {
            return false;
        }

        @Override
        public OutputStream commit(int status, List<Map.Entry<String, String>> headers, long contentLength) {
            commits++;
            this.status = status;
            this.headers = headers;
            onCommit.run();
            return new OutputStream() {
                @Override
                public void write(int b) throws IOException {
                    write(new byte[] {(byte) b}, 0, 1);
                }

                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    onWrite.run();
                    if (body.size() >= failWriteAfter) {
                        throw new IOException("peer gone");
                    }
                    body.write(b, off, len);
                }
            };
        }

        @Override
        public void finish() {
            finishes++;
        }

        @Override
        public void abort() {
            aborts++;
        }

        @Override
        public void sendFile(int status, List<Map.Entry<String, String>> headers, File file, long offset,
                long length) {
            throw new AssertionError("not used");
        }

        @Override
        public void upgrade(List<Map.Entry<String, String>> headers, UpgradeHandler handler) {
            throw new AssertionError("not used");
        }

        @Override
        public void eventStream(int status, List<Map.Entry<String, String>> headers, StreamDrain drain) {
            throw new AssertionError("not used");
        }
    }

    // SDS-AC-11; FR-HTTP-016 AC-4, SDS-AC-4; FR-HTTP-014 AC-5
    @Test
    public void sdsAc11_sdsAc4_FR_HTTP_016_AC4_readFailureAbortsTheExchangeItself() {
        RecordingExchange ex = new RecordingExchange("GET", "/s");
        DefaultResponse res = new DefaultResponse(ex, false);
        CountingInputStream in = new CountingInputStream(data(30000));
        in.failAfter = 8192;
        res.stream(in, "text/plain");
        res.runTransfer();
        assertEquals(ResponseState.ABORTED, res.state());
        assertEquals(1, ex.commits);
        assertEquals(1, ex.aborts);
        assertEquals(0, ex.finishes);
        assertEquals(1, in.closes);
    }

    // SDS-AC-4; FR-HTTP-014 AC-5
    @Test
    public void sdsAc4_FR_HTTP_014_AC5_writeFailureAbortsAndClosesOnceWithoutRethrow() {
        RecordingExchange ex = new RecordingExchange("GET", "/s");
        ex.failWriteAfter = 8192;
        DefaultResponse res = new DefaultResponse(ex, false);
        CountingInputStream in = new CountingInputStream(data(30000));
        res.stream(in, "text/plain");
        res.runTransfer();
        assertEquals(ResponseState.ABORTED, res.state());
        assertEquals(1, ex.aborts);
        assertEquals(1, in.closes);
        assertEquals(8192, ex.body.size());
    }

    // SDS-AC-9; FR-HTTP-015 AC-2, FR-HTTP-015 AC-3
    @Test
    public void sdsAc9_FR_HTTP_015_AC2_AC3_abortDuringTransferCallsTheExchangeOnceThenIsNoOp() {
        RecordingExchange ex = new RecordingExchange("GET", "/s");
        DefaultResponse res = new DefaultResponse(ex, false);
        CountingInputStream in = new CountingInputStream(data(30000));
        in.afterFirstRead = () -> res.abort(new IOException("x"));
        res.stream(in, "text/plain");
        res.runTransfer();
        assertEquals(ResponseState.ABORTED, res.state());
        assertEquals(1, ex.aborts);
        res.abort(new IOException("again"));
        res.abort(null);
        assertEquals(ResponseState.ABORTED, res.state());
        assertEquals(1, ex.aborts);
        assertEquals(1, in.closes);
        assertEquals(0, ex.body.size());
    }

    // SDS-AC-9; FR-HTTP-015 AC-3, FR-HTTP-018 AC-3
    @Test
    public void sdsAc9_FR_HTTP_015_AC3_abortAfterCompletionNeverCallsTheExchange() {
        RecordingExchange ex = new RecordingExchange("GET", "/s");
        DefaultResponse res = new DefaultResponse(ex, false);
        res.send("x");
        res.abort(new IOException("late"));
        assertEquals(ResponseState.COMPLETED, res.state());
        assertEquals(0, ex.aborts);
        assertEquals(1, ex.finishes);
    }

    /** Runs {@code call} on another thread and reports whether it returned within two seconds. */
    private static boolean returnsPromptly(Runnable call) throws InterruptedException {
        Thread th = new Thread(call, "abort-caller");
        th.setDaemon(true);
        th.start();
        th.join(2000);
        return !th.isAlive();
    }

    // SDS-AC-4; FR-HTTP-014 AC-5, SDS-AC-9; FR-HTTP-015 AC-2
    @Test
    public void sdsAc4_sdsAc9_FR_HTTP_014_AC5_FR_HTTP_015_AC2_abortFromAnotherThreadWhileTheCopyBlocks()
            throws Exception {
        RecordingExchange ex = new RecordingExchange("GET", "/s");
        DefaultResponse res = new DefaultResponse(ex, false);
        java.util.concurrent.CountDownLatch reading = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        CountingInputStream in = new CountingInputStream(data(30000)) {
            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                reading.countDown();
                try {
                    release.await(5, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    throw new IOException(e);
                }
                return super.read(b, off, len);
            }
        };
        res.stream(in, "text/plain");
        Thread copier = new Thread(res::runTransfer, "copier");
        copier.start();
        assertTrue(reading.await(5, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue("abort waited on the blocked copy", returnsPromptly(() -> res.abort(new IOException("x"))));
        release.countDown();
        copier.join(5000);
        assertFalse(copier.isAlive());
        assertEquals(ResponseState.ABORTED, res.state());
        assertEquals(1, ex.aborts);
        assertEquals(1, in.closes);
        assertEquals(0, ex.body.size());
    }

    // SDS-AC-4; FR-HTTP-014 AC-5, SDS-AC-5; FR-HTTP-014 AC-7
    @Test
    public void sdsAc4_sdsAc5_FR_HTTP_014_AC5_AC7_streamCommittingWhileTheHandlerReturnsStillCopiesAndCloses()
            throws Exception {
        RecordingExchange ex = new RecordingExchange("GET", "/s");
        DefaultResponse res = new DefaultResponse(ex, false);
        java.util.concurrent.CountDownLatch inCommit = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        ex.onCommit = () -> {
            inCommit.countDown();
            try {
                release.await(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        CountingInputStream in = new CountingInputStream(data(20000));
        Thread writer = new Thread(() -> res.stream(in, "text/plain"), "late-stream");
        writer.start();
        assertTrue(inCommit.await(5, java.util.concurrent.TimeUnit.SECONDS));
        res.afterHandler(); // the handler returned while the other thread is inside ex.commit
        release.countDown();
        writer.join(5000);
        assertFalse(writer.isAlive());
        assertEquals(ResponseState.COMPLETED, res.state());
        assertEquals(20000, ex.body.size());
        assertEquals(1, ex.finishes);
        assertEquals(1, in.closes);
    }

    // SDS-AC-8; FR-HTTP-015 AC-1, FR-HTTP-016 AC-1
    @Test
    public void sdsAc8_FR_HTTP_015_AC1_FR_HTTP_016_AC1_exchangeCommitRunsOutsideTheLock() throws Exception {
        RecordingExchange ex = new RecordingExchange("GET", "/r");
        DefaultResponse res = new DefaultResponse(ex, false);
        boolean[] prompt = new boolean[1];
        ex.onCommit = () -> {
            try {
                prompt[0] = returnsPromptly(res::isCommitted);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        res.send("x");
        assertTrue("isCommitted waited on the exchange commit", prompt[0]);
        assertEquals(1, ex.commits);
        assertEquals(ResponseState.COMPLETED, res.state());
    }

    // SDS-AC-5; FR-HTTP-014 AC-7
    @Test
    public void sdsAc5_FR_HTTP_014_AC7_streamDropsUserFramingHeaders() {
        RecordingExchange ex = new RecordingExchange("GET", "/s");
        DefaultResponse res = new DefaultResponse(ex, false);
        res.header("Content-Length", "99").header("Transfer-Encoding", "gzip");
        res.stream(new CountingInputStream(data(10)), "text/plain");
        res.runTransfer();
        assertTrue(ex.committed("Content-Length").isEmpty());
        assertTrue(ex.committed("Transfer-Encoding").isEmpty());
        assertEquals(Collections.singletonList("text/plain"), ex.committed("Content-Type"));
        assertEquals(10, ex.body.size());
    }

    // SDS-AC-12; FR-HTTP-017 AC-1
    @Test
    public void sdsAc12_FR_HTTP_017_AC1_status204DropsAUserContentLength() {
        RecordingExchange ex = new RecordingExchange("GET", "/r");
        DefaultResponse res = new DefaultResponse(ex, false);
        res.status(204).header("Content-Length", "5").send("hello");
        assertTrue(ex.committed("Content-Length").isEmpty());
        assertEquals(0, ex.body.size());
        RecordingExchange ex2 = new RecordingExchange("GET", "/r");
        new DefaultResponse(ex2, false).header("content-length", "99").send("hello");
        assertEquals(Collections.singletonList("5"), ex2.committed("Content-Length"));
    }
}
