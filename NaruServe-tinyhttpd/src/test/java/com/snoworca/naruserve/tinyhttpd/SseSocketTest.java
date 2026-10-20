package com.snoworca.naruserve.tinyhttpd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.AppState;
import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.SseClient;
import com.snoworca.naruserve.internal.DefaultResponse;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.tinyhttpd.RawTargetSocketTest.Http;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Test;

/**
 * res.sse() over TinyHttpd. SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-9.sds.md up to the
 * first test, and docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-10.sds.md from the T25 cases on.
 */
public class SseSocketTest {

    private final RecordingLogger log = new RecordingLogger();
    private final TinyHttpdAdapter adapter = new TinyHttpdAdapter();
    private RestApp app;

    @After
    public void stop() {
        if (app != null) {
            app.shutdown(2, TimeUnit.SECONDS);
        }
        Http.stopQuietly(adapter);
    }

    private static int count(String response, String name) {
        int n = 0;
        for (String line : response.substring(0, response.indexOf("\r\n\r\n")).split("\r\n")) {
            int c = line.indexOf(':');
            if (c > 0 && line.substring(0, c).equalsIgnoreCase(name)) {
                n++;
            }
        }
        return n;
    }

    /** The payload of a chunked body: hex size line, that many bytes, CRLF, until the 0 chunk. */
    private static String dechunk(String body) {
        StringBuilder out = new StringBuilder();
        int at = 0;
        while (true) {
            int eol = body.indexOf("\r\n", at);
            String size = body.substring(at, eol);
            int semi = size.indexOf(';');
            int n = Integer.parseInt((semi < 0 ? size : size.substring(0, semi)).trim(), 16);
            if (n == 0) {
                return out.toString();
            }
            out.append(body, eol + 2, eol + 2 + n);
            at = eol + 2 + n + 2;
        }
    }

    // SDS-AC-6; FR-SSE-001 AC-1, AC-3 (T24)
    @Test(timeout = 20000)
    public void sdsAc6_FR_SSE_001_AC1_AC3_sseCommits200WithEachEventStreamHeaderOnceOverTinyHttpd() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        app = Http.app(adapter, log);
        app.get("/events", (req, res) -> {
            res.status(201).header("Content-Type", "text/plain").header("Cache-Control", "max-age=60");
            SseClient c = res.sse();
            seen.set(res.isCommitted() + " " + ((DefaultResponse) res).state());
            c.send("connected", "ready");
            c.close();
        });
        int port = app.listen(0).port();
        String response = Http.send(port, Http.get("/events"));
        assertEquals(200, Http.status(response));
        assertEquals("text/event-stream; charset=UTF-8", Http.header(response, "Content-Type"));
        assertEquals("no-cache, no-transform", Http.header(response, "Cache-Control"));
        assertEquals(1, count(response, "Content-Type"));
        assertEquals(1, count(response, "Cache-Control"));
        assertNull(Http.header(response, "Content-Length"));
        assertEquals("chunked", Http.header(response, "Transfer-Encoding"));
        assertEquals("event: connected\ndata: ready\n\n", dechunk(Http.body(response)));
        assertEquals("true STREAMING", seen.get());
    }

    /** A service registered in onStart; java.time.Clock is API 26, so the test brings its own type. */
    static final class Clock {
    }

    /** A raw client that keeps its socket open and decodes the chunked body as it arrives. */
    private static final class Stream implements Closeable {
        private final Socket socket = new Socket();
        private final InputStream in;
        private final ByteArrayOutputStream raw = new ByteArrayOutputStream();

        Stream(int port, String request) throws IOException {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            socket.setSoTimeout(10000);
            socket.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
            in = socket.getInputStream();
        }

        /** The payload of the complete chunks received so far. */
        String payload() {
            String all = new String(raw.toByteArray(), StandardCharsets.ISO_8859_1);
            int head = all.indexOf("\r\n\r\n");
            if (head < 0) {
                return "";
            }
            StringBuilder out = new StringBuilder();
            int at = head + 4;
            while (true) {
                int eol = all.indexOf("\r\n", at);
                if (eol < 0) {
                    return out.toString();
                }
                String size = all.substring(at, eol);
                int semi = size.indexOf(';');
                int n = Integer.parseInt((semi < 0 ? size : size.substring(0, semi)).trim(), 16);
                if (n == 0 || all.length() < eol + 2 + n + 2) {
                    return out.toString();
                }
                out.append(all, eol + 2, eol + 2 + n);
                at = eol + 2 + n + 2;
            }
        }

        /** Reads until the payload contains {@code part}; the 10 s read timeout fails the test. */
        void readUntil(String part) throws IOException {
            byte[] b = new byte[16384];
            while (!payload().contains(part)) {
                int n = in.read(b);
                if (n < 0) {
                    throw new AssertionError("end of stream before " + part);
                }
                raw.write(b, 0, n);
            }
        }

        /** Reads until the server closes and returns the whole raw response. */
        String readAll() throws IOException {
            byte[] b = new byte[16384];
            for (int n; (n = in.read(b)) >= 0; ) {
                raw.write(b, 0, n);
            }
            return new String(raw.toByteArray(), StandardCharsets.ISO_8859_1);
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    // SDS-AC-1; FR-SSE-002 AC-1 (T25)
    @Test(timeout = 60000)
    public void sdsAc1_FR_SSE_002_AC1_eightThreadsSendingConcurrentlyKeepEachEventWholeAndTheirOwnOrder()
            throws Exception {
        AtomicReference<SseClient> client = new AtomicReference<>();
        CountDownLatch opened = new CountDownLatch(1);
        app = Http.app(adapter, log);
        app.get("/events", (req, res) -> {
            client.set(res.sse());
            opened.countDown();
        });
        int port = app.listen(0).port();
        ExecutorService reader = Executors.newSingleThreadExecutor();
        try {
            Future<String> response = reader.submit(() -> Http.send(port, Http.get("/events")));
            assertTrue(opened.await(10, TimeUnit.SECONDS));
            SseClient c = client.get();
            AtomicBoolean allTrue = new AtomicBoolean(true);
            List<Thread> senders = new ArrayList<>();
            for (int k = 0; k < 8; k++) {
                int thread = k;
                senders.add(new Thread(() -> {
                    for (int i = 0; i < 500; i++) {
                        String id = "t" + thread + "-" + i;
                        if (!c.send(id + "\n" + id + "-b")) {
                            allTrue.set(false);
                        }
                    }
                }));
            }
            for (Thread t : senders) {
                t.start();
            }
            for (Thread t : senders) {
                t.join(30000);
            }
            assertTrue("a send returned false", allTrue.get());
            c.close();
            String body = dechunk(Http.body(response.get(30, TimeUnit.SECONDS)));
            int[] last = new int[8];
            Arrays.fill(last, -1);
            int events = 0;
            for (String block : body.split("\n\n")) {
                if (block.startsWith(":")) {
                    continue; // a heartbeat comment
                }
                String[] lines = block.split("\n", -1);
                assertEquals(block, 2, lines.length);
                String id = lines[0].substring("data: ".length());
                assertEquals(block, "data: " + id + "-b", lines[1]);
                int thread = Integer.parseInt(id.substring(1, id.indexOf('-')));
                int i = Integer.parseInt(id.substring(id.indexOf('-') + 1));
                assertEquals("thread " + thread + " out of order", last[thread] + 1, i);
                last[thread] = i;
                events++;
            }
            assertEquals(4000, events);
        } finally {
            reader.shutdownNow();
        }
    }

    /** 1024 characters: a 4-digit index, a dash and padding. */
    private static String kib(int i) {
        char[] pad = new char[1019];
        Arrays.fill(pad, 'x');
        return String.format("%04d-", i) + new String(pad);
    }

    // SDS-AC-4; FR-SSE-002 AC-5, FR-SSE-004 AC-1 (T25)
    @Test(timeout = 60000)
    public void sdsAc4_FR_SSE_002_AC5_handlerSendsReachTheSocketWhileTheHandlerRunsBeyondTheQueueLimit()
            throws Exception {
        AtomicReference<SseClient> client = new AtomicReference<>();
        CountDownLatch firstRead = new CountDownLatch(1);
        CountDownLatch handlerDone = new CountDownLatch(1);
        AtomicBoolean allTrue = new AtomicBoolean(true);
        app = Http.app(adapter, log);
        app.get("/events", (req, res) -> {
            SseClient c = res.sse();
            client.set(c);
            allTrue.set(c.send(kib(0)));
            if (!firstRead.await(20, TimeUnit.SECONDS)) {
                allTrue.set(false);
            }
            for (int i = 1; i < 600; i++) {
                if (!c.send(kib(i))) {
                    allTrue.set(false);
                }
            }
            handlerDone.countDown();
        });
        int port = app.listen(0).port();
        try (Stream s = new Stream(port, Http.get("/events"))) {
            s.readUntil("data: 0000-");
            firstRead.countDown();
            s.readUntil("data: 0599-");
            assertTrue(handlerDone.await(10, TimeUnit.SECONDS));
            assertTrue("a handler send returned false", allTrue.get());
            SseClient c = client.get();
            Thread.sleep(2000); // FR-SSE-004 AC-1: a send 2 s after the handler returned, from another thread
            assertTrue(c.isOpen());
            assertTrue(c.send("after the handler"));
            s.readUntil("data: after the handler\n\n");
            assertTrue(c.isOpen());
            c.close();
            String body = dechunk(Http.body(s.readAll()));
            StringBuilder expected = new StringBuilder();
            for (int i = 0; i < 600; i++) {
                expected.append("data: ").append(kib(i)).append("\n\n");
            }
            expected.append("data: after the handler\n\n");
            assertEquals(expected.toString(), body);
        }
    }

    // SDS-AC-11; FR-SSE-004 AC-6
    @Test(timeout = 30000)
    public void sdsAc11_FR_SSE_004_AC6_headTakesNoSseSlotSoThreeHeadsThenAGetAllSucceed() throws Exception {
        app = Http.app(adapter, log);
        app.server(o -> o.maxSseStreams(1));
        app.get("/events", (req, res) -> {
            SseClient c = res.sse();
            c.send("x");
            c.close();
        });
        int port = app.listen(0).port();
        for (int i = 0; i < 3; i++) {
            String head = Http.send(port, "HEAD /events HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n");
            assertEquals(head, 200, Http.status(head));
            assertEquals("text/event-stream; charset=UTF-8", Http.header(head, "Content-Type"));
            assertEquals("", Http.body(head));
        }
        String get = Http.send(port, Http.get("/events"));
        assertEquals(200, Http.status(get));
        assertEquals("data: x\n\n", dechunk(Http.body(get)));
    }

    // SDS-AC-12; FR-SSE-004 AC-7, AC-8 (T41)
    @Test(timeout = 30000)
    public void sdsAc12_FR_SSE_004_AC7_AC8_shutdownFromOnCloseReturnsAtOnceAndTheAppTerminates() throws Exception {
        Clock clock = new Clock();
        AtomicReference<Boolean> sameClock = new AtomicReference<>();
        AtomicLong shutdownMillis = new AtomicLong(-1);
        CountDownLatch listenerDone = new CountDownLatch(1);
        RestApp a = Http.app(adapter, log);
        app = a;
        a.onStart(ctx -> ctx.register(Clock.class, clock));
        a.get("/events", (req, res) -> {
            sameClock.set(req.service(Clock.class) == clock);
            SseClient c = res.sse();
            c.onClose(() -> {
                long t0 = System.nanoTime();
                a.shutdown(5, TimeUnit.SECONDS);
                shutdownMillis.set(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0));
                listenerDone.countDown();
            });
            c.send("x");
            c.close();
        });
        int port = a.listen(0).port();
        String response = Http.send(port, Http.get("/events"));
        assertEquals("data: x\n\n", dechunk(Http.body(response)));
        assertTrue("onClose did not finish", listenerDone.await(10, TimeUnit.SECONDS));
        assertTrue("shutdown took " + shutdownMillis.get() + " ms", shutdownMillis.get() < 500);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (a.state() != AppState.TERMINATED && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(AppState.TERMINATED, a.state());
        assertEquals(Boolean.TRUE, sameClock.get());
    }
}
