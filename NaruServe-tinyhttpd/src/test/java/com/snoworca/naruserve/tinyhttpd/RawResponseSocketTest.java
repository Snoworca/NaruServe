package com.snoworca.naruserve.tinyhttpd;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.tinyhttpd.RawTargetSocketTest.Http;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Test;

/** res.onAbort and res.relay as a raw client sees them over a real TinyHttpd socket. */
public class RawResponseSocketTest {

    private final TinyHttpdAdapter adapter = new TinyHttpdAdapter();
    private final RestApp app = Http.app(adapter, new RecordingLogger());

    @After
    public void stop() {
        app.shutdown(2, TimeUnit.SECONDS);
    }

    /** An endless source that yields 4096 bytes every 2 ms until it is closed; records the close. */
    static final class SlowSource extends InputStream {
        final CountDownLatch closed = new CountDownLatch(1);

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (closed.getCount() == 0) {
                throw new IOException("source closed");
            }
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("interrupted");
            }
            int n = Math.min(len, 4096);
            Arrays.fill(b, off, off + n, (byte) 'x');
            return n;
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }

    /** Reads one response head up to and including the blank line; fails on EOF before it. */
    static String readHead(InputStream in) throws IOException {
        StringBuilder head = new StringBuilder();
        while (head.length() < 4 || !head.substring(head.length() - 4).equals("\r\n\r\n")) {
            int b = in.read();
            if (b < 0) {
                throw new EOFException("EOF inside the response head: " + head);
            }
            head.append((char) b);
        }
        return head.toString();
    }

    static byte[] readExactly(InputStream in, int n) throws IOException {
        byte[] out = new byte[n];
        for (int off = 0; off < n; ) {
            int k = in.read(out, off, n - off);
            if (k < 0) {
                throw new EOFException("EOF after " + off + " of " + n + " body bytes");
            }
            off += k;
        }
        return out;
    }

    /** Every byte until the server closes the connection; a reset after the close counts as the end. */
    static byte[] readToEof(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        try {
            for (int n; (n = in.read(b)) >= 0; ) {
                buf.write(b, 0, n);
            }
        } catch (SocketTimeoutException e) {
            throw e;
        } catch (SocketException reset) {
            // a reset is how an aborted connection may end
        }
        return buf.toByteArray();
    }

    /** Decodes a chunked body that follows a head already read; fails on EOF before the last chunk. */
    static byte[] readChunked(InputStream in) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (;;) {
            String sizeLine = readLine(in);
            int semi = sizeLine.indexOf(';');
            int size = Integer.parseInt((semi < 0 ? sizeLine : sizeLine.substring(0, semi)).trim(), 16);
            if (size == 0) {
                while (!readLine(in).isEmpty()) {
                    // trailer fields
                }
                return body.toByteArray();
            }
            body.write(readExactly(in, size));
            assertEquals("chunk must end with CRLF", "", readLine(in));
        }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (;;) {
            int b = in.read();
            if (b < 0) {
                throw new EOFException("EOF inside a chunked body");
            }
            if (b == '\n' && sb.length() > 0 && sb.charAt(sb.length() - 1) == '\r') {
                return sb.substring(0, sb.length() - 1);
            }
            sb.append((char) b);
        }
    }

    private static void send(Socket socket, String raw) throws IOException {
        OutputStream out = socket.getOutputStream();
        out.write(raw.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    private static String keepAlive(String method, String target) {
        return method + " " + target + " HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n";
    }

    private Socket connect(int port) throws IOException {
        Socket socket = new Socket("127.0.0.1", port);
        socket.setSoTimeout(10000);
        return socket;
    }

    /** Waits up to 10 s for {@code done}, then confirms {@code count} stays at {@code expected} for a further 300 ms. */
    private static void settlesAt(CountDownLatch done, AtomicInteger count, int expected, String what)
            throws InterruptedException {
        assertTrue(what + ": the transfer never ended", done.await(10, TimeUnit.SECONDS));
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(300);
        while (System.nanoTime() < end) {
            assertEquals(what, expected, count.get());
            Thread.sleep(10);
        }
        assertEquals(what, expected, count.get());
    }

    /** Starts the slow transfer, reads its head and first body bytes, then resets the connection. */
    private void disconnectMidTransfer(int port, String target) throws IOException {
        try (Socket socket = connect(port)) {
            send(socket, Http.get(target));
            InputStream in = socket.getInputStream();
            String head = readHead(in);
            assertEquals(head, 200, Http.status(head));
            readExactly(in, 1); // the copy is running: body bytes already reached the client
            socket.setSoLinger(true, 0); // close with a reset so the next server write fails promptly
        }
    }

    // FR-ENGINE-014 AC-1: a client disconnect during res.relay(in, -1) from a slow source runs onAbort exactly once.
    @Test(timeout = 30000)
    public void FR_ENGINE_014_AC1_disconnectDuringRelayRunsOnAbortOnce() throws Exception {
        SlowSource source = new SlowSource();
        AtomicInteger aborts = new AtomicInteger();
        app.get("/relay", (req, res) -> {
            res.onAbort(aborts::incrementAndGet);
            res.relay(source, -1);
        });
        int port = app.listen(0).port();
        disconnectMidTransfer(port, "/relay");
        settlesAt(source.closed, aborts, 1, "onAbort runs exactly once for a disconnected relay");
    }

    // FR-ENGINE-014 AC-1: a client disconnect during res.stream(...) from a slow source runs onAbort exactly once.
    @Test(timeout = 30000)
    public void FR_ENGINE_014_AC1_disconnectDuringStreamRunsOnAbortOnce() throws Exception {
        SlowSource source = new SlowSource();
        AtomicInteger aborts = new AtomicInteger();
        app.get("/stream", (req, res) -> {
            res.onAbort(aborts::incrementAndGet);
            res.stream(source, "application/octet-stream");
        });
        int port = app.listen(0).port();
        disconnectMidTransfer(port, "/stream");
        settlesAt(source.closed, aborts, 1, "onAbort runs exactly once for a disconnected stream");
    }

    // FR-ENGINE-014 AC-2: a normally completed response never runs onAbort, also after the client closes the
    // keep-alive connection once it read the response.
    @Test(timeout = 30000)
    public void FR_ENGINE_014_AC2_completedResponseNeverRunsOnAbortAfterTheClientCloses() throws Exception {
        AtomicInteger aborts = new AtomicInteger();
        CountDownLatch relayClosed = new CountDownLatch(2);
        app.get("/send", (req, res) -> {
            res.onAbort(aborts::incrementAndGet);
            res.send("ok");
        });
        app.get("/relay", (req, res) -> {
            res.onAbort(aborts::incrementAndGet);
            res.relay(new ByteArrayInputStream("hello".getBytes(StandardCharsets.US_ASCII)) {
                @Override
                public void close() {
                    relayClosed.countDown();
                }
            }, 5);
        });
        int port = app.listen(0).port();
        for (int round = 0; round < 2; round++) {
            try (Socket socket = connect(port)) {
                InputStream in = socket.getInputStream();
                send(socket, keepAlive("GET", "/send"));
                String sendHead = readHead(in);
                assertEquals(sendHead, 200, Http.status(sendHead));
                assertEquals("ok", new String(readExactly(in, Integer.parseInt(Http.header(sendHead,
                        "Content-Length"))), StandardCharsets.UTF_8));
                send(socket, keepAlive("GET", "/relay"));
                String relayHead = readHead(in);
                assertEquals(relayHead, 200, Http.status(relayHead));
                assertEquals("5", Http.header(relayHead, "Content-Length"));
                assertEquals("hello", new String(readExactly(in, 5), StandardCharsets.US_ASCII));
            } // the client closes the connection after reading both responses
        }
        settlesAt(relayClosed, aborts, 0, "onAbort never runs after a normally completed response");
    }

    // FR-ENGINE-014 AC-3: relay(in, 5) with no app Content-Type sends no Content-Type, Content-Length 5 and 5 bytes.
    @Test(timeout = 20000)
    public void FR_ENGINE_014_AC3_relayWithoutContentTypeSendsNoneAndTheLength() throws Exception {
        app.get("/relay", (req, res) -> res.relay(new ByteArrayInputStream("hello".getBytes(StandardCharsets.US_ASCII)), 5));
        int port = app.listen(0).port();
        try (Socket socket = connect(port)) {
            send(socket, Http.get("/relay"));
            InputStream in = socket.getInputStream();
            String head = readHead(in);
            assertEquals(head, 200, Http.status(head));
            assertNull(head, Http.header(head, "Content-Type"));
            assertEquals(head, Arrays.asList("5"), Http.headers(head, "Content-Length"));
            assertNull(head, Http.header(head, "Transfer-Encoding"));
            assertArrayEquals("hello".getBytes(StandardCharsets.US_ASCII), readToEof(in));
        }
    }

    // FR-ENGINE-014 AC-4: relay(in, -1) sends no Content-Length and the client reads the complete body under the
    // framing TinyHttpd chooses, both for a body that fits TinyHttpd's response buffer and for one larger than it,
    // where the response commits before the copy ends.
    @Test(timeout = 20000)
    public void FR_ENGINE_014_AC4_unknownLengthRelayHasNoContentLengthAndACompleteBody() throws Exception {
        byte[] small = "hello".getBytes(StandardCharsets.US_ASCII);
        byte[] large = new byte[300 * 1024];
        for (int i = 0; i < large.length; i++) {
            large[i] = (byte) (i * 31 + 7);
        }
        app.get("/small", (req, res) -> res.relay(new ByteArrayInputStream(small), -1));
        app.get("/large", (req, res) -> res.relay(new ByteArrayInputStream(large), -1));
        int port = app.listen(0).port();
        for (Object[] round : new Object[][] {{"/small", small}, {"/large", large}}) {
            try (Socket socket = connect(port)) {
                send(socket, Http.get((String) round[0]));
                InputStream in = socket.getInputStream();
                String head = readHead(in);
                assertEquals(head, 200, Http.status(head));
                assertNull(head, Http.header(head, "Content-Length"));
                String encoding = Http.header(head, "Transfer-Encoding");
                byte[] body = encoding == null ? readToEof(in) : readChunked(in);
                if (encoding != null) {
                    assertEquals(head, "chunked", encoding);
                }
                assertArrayEquals(round[0] + " body", (byte[]) round[1], body);
            }
        }
    }

    // FR-ENGINE-014 AC-5: relay(in, 5) answers HEAD with Content-Length 5 and no body, a 304 with no body and no
    // Content-Length (TinyHttpd 3.1.1 writes none on a 304), a 204 with neither, and the same keep-alive connection
    // then serves the next request normally. Each bodyless head is followed directly by the next status line, so a
    // stray body byte would break the next readHead.
    @Test(timeout = 20000)
    public void FR_ENGINE_014_AC5_bodylessRelayResponsesKeepTheConnectionUsable() throws Exception {
        AtomicInteger closes = new AtomicInteger();
        app.get("/relay", (req, res) -> {
            String code = req.query("status");
            if (code != null) {
                res.status(Integer.parseInt(code));
            }
            res.relay(new ByteArrayInputStream("hello".getBytes(StandardCharsets.US_ASCII)) {
                @Override
                public void close() {
                    closes.incrementAndGet();
                }
            }, 5);
        });
        app.get("/next", (req, res) -> res.send("next"));
        int port = app.listen(0).port();
        try (Socket socket = connect(port)) {
            InputStream in = socket.getInputStream();

            send(socket, keepAlive("HEAD", "/relay"));
            String head = readHead(in);
            assertEquals(head, 200, Http.status(head));
            assertEquals(head, Arrays.asList("5"), Http.headers(head, "Content-Length"));
            assertNull(head, Http.header(head, "Transfer-Encoding"));

            send(socket, keepAlive("GET", "/relay?status=304"));
            String notModified = readHead(in);
            assertEquals(notModified, 304, Http.status(notModified));
            assertNull(notModified, Http.header(notModified, "Content-Length"));
            assertNull(notModified, Http.header(notModified, "Transfer-Encoding"));

            send(socket, keepAlive("GET", "/relay?status=204"));
            String noContent = readHead(in);
            assertEquals(noContent, 204, Http.status(noContent));
            assertNull(noContent, Http.header(noContent, "Content-Length"));
            assertNull(noContent, Http.header(noContent, "Transfer-Encoding"));

            send(socket, Http.get("/next"));
            String next = readHead(in);
            assertEquals(next, 200, Http.status(next));
            assertEquals(next, "4", Http.header(next, "Content-Length"));
            assertEquals("next", new String(readToEof(in), StandardCharsets.UTF_8));
        }
        assertEquals("every relayed stream is closed", 3, closes.get());
    }

    // FR-ENGINE-014 AC-6: relay(in, 5) over a stream that ends after 3 bytes closes the keep-alive connection
    // before 5 body bytes arrive, so the client never reads a complete response.
    @Test(timeout = 20000)
    public void FR_ENGINE_014_AC6_shortRelaySourceClosesTheConnectionBeforeTheLength() throws Exception {
        app.get("/short", (req, res) -> res.relay(new ByteArrayInputStream("abc".getBytes(StandardCharsets.US_ASCII)), 5));
        int port = app.listen(0).port();
        try (Socket socket = connect(port)) {
            send(socket, keepAlive("GET", "/short"));
            byte[] received;
            try {
                received = readToEof(socket.getInputStream());
            } catch (SocketTimeoutException e) {
                fail("the server kept the connection open instead of closing it on a short relay source");
                return;
            }
            String raw = new String(received, StandardCharsets.ISO_8859_1);
            int end = raw.indexOf("\r\n\r\n");
            if (end >= 0) {
                assertEquals(raw, 200, Http.status(raw));
                assertEquals(raw, "5", Http.header(raw, "Content-Length"));
                assertTrue("the client must not receive 5 body bytes: " + raw, raw.length() - (end + 4) < 5);
            }
            // no head at all is also a truncated response: the connection closed before a complete one
        }
    }
}
