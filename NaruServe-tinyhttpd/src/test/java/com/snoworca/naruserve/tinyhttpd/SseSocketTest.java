package com.snoworca.naruserve.tinyhttpd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.AppState;
import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.SseClient;
import com.snoworca.naruserve.SseEvent;
import com.snoworca.naruserve.internal.DefaultResponse;
import com.snoworca.naruserve.spi.HttpExchange;
import com.snoworca.naruserve.spi.StreamDrain;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.tinyhttpd.RawTargetSocketTest.Http;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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
 * first test, docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-10.sds.md from the T25 cases on, and
 * docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-11.sds.md from the event-stream class cases on.
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
    static final class Stream implements Closeable {
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

        void setReadTimeout(int millis) throws IOException {
            socket.setSoTimeout(millis);
        }

        /** Reads until the status line and headers arrived; the 10 s read timeout fails the test. */
        String readHeaders() throws IOException {
            byte[] b = new byte[16384];
            while (true) {
                String all = new String(raw.toByteArray(), StandardCharsets.ISO_8859_1);
                int end = all.indexOf("\r\n\r\n");
                if (end >= 0) {
                    return all.substring(0, end + 4);
                }
                int n = in.read(b);
                if (n < 0) {
                    throw new AssertionError("end of stream before the headers");
                }
                raw.write(b, 0, n);
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

    // SDS-AC-9; FR-SSE-004 AC-2 (T25): the client closes its socket; with heartbeat 1 s the onClose listeners run
    // once within 3 s, isOpen() turns false, a later send returns false and the access-log line carries cause=client
    @Test(timeout = 30000)
    public void sdsAc9_FR_SSE_004_AC2_aClientCloseRunsOnCloseOnceWithin3sAndLogsCauseClient() throws Exception {
        AtomicReference<SseClient> client = new AtomicReference<>();
        CountDownLatch opened = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger runs = new java.util.concurrent.atomic.AtomicInteger();
        app = Http.app(adapter, log);
        app.sse(o -> o.heartbeatInterval(1, TimeUnit.SECONDS));
        app.get("/events", (req, res) -> {
            SseClient c = res.sse();
            c.onClose(() -> {
                runs.incrementAndGet();
                closed.countDown();
            });
            client.set(c);
            opened.countDown();
        });
        int port = app.listen(0).port();
        Stream s = new Stream(port, Http.get("/events"));
        s.readHeaders();
        assertTrue(opened.await(5, TimeUnit.SECONDS));
        assertTrue(client.get().isOpen());
        long t0 = System.nanoTime();
        s.close();
        assertTrue("onClose did not run", closed.await(10, TimeUnit.SECONDS));
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        assertTrue("onClose ran " + ms + " ms after the client closed", ms <= 3000);
        SseClient c = client.get();
        assertFalse(c.isOpen());
        assertFalse(c.send("late"));
        String regex = "INFO GET /events 200 \\d+ms in=0 out=\\d+ rid=[0-9a-f]{16} sse start=\\d+ cause=client";
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        int lines = 0;
        while (lines == 0 && System.nanoTime() < end) {
            for (String line : new ArrayList<>(log.lines)) {
                lines += line.matches(regex) ? 1 : 0;
            }
            Thread.sleep(lines == 0 ? 5 : 0);
        }
        assertEquals(log.lines.toString(), 1, lines);
        assertEquals(1, runs.get());
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

    /** The payloads of the chunks of a chunked body, in order, without the 0 chunk. */
    private static List<String> chunks(String body) {
        List<String> out = new ArrayList<>();
        int at = 0;
        while (true) {
            int eol = body.indexOf("\r\n", at);
            String size = body.substring(at, eol);
            int semi = size.indexOf(';');
            int n = Integer.parseInt((semi < 0 ? size : size.substring(0, semi)).trim(), 16);
            if (n == 0) {
                return out;
            }
            out.add(body.substring(eol + 2, eol + 2 + n));
            at = eol + 2 + n + 2;
        }
    }

    // SDS-AC-3; FR-SSE-003 AC-1: chunked, exactly one Cache-Control of no-cache, no-transform, an event with retry
    // in one chunk in the FR-SSE-001 field order, and no main class of the module names TinyHttpd's startEventStream
    @Test(timeout = 20000)
    public void sdsAc3_FR_SSE_003_AC1_chunkedOneCacheControlARetryEventInOneBlockAndNoStartEventStream()
            throws Exception {
        app = Http.app(adapter, log);
        app.get("/events", (req, res) -> {
            SseClient c = res.sse();
            c.send(SseEvent.event("tick").id("7").retry(3000).data("a\nb"));
            c.close();
        });
        int port = app.listen(0).port();
        String response = Http.send(port, Http.get("/events"));
        assertEquals(200, Http.status(response));
        assertEquals("chunked", Http.header(response, "Transfer-Encoding"));
        assertEquals(1, count(response, "Cache-Control"));
        assertEquals("no-cache, no-transform", Http.header(response, "Cache-Control"));
        assertNull(Http.header(response, "Content-Length"));
        assertEquals(Collections.singletonList("event: tick\nid: 7\nretry: 3000\ndata: a\ndata: b\n\n"),
                chunks(Http.body(response)));
        boolean eventStreamClass = false;
        for (File f : AdapterSurfaceTest.mainClassFiles()) {
            String text = AdapterSurfaceTest.classText(f);
            assertFalse(f.getName(), text.contains("startEventStream"));
            eventStreamClass |= f.getName().startsWith("TinyHttpdEventStream") && text.contains("flushBuffer");
        }
        assertTrue("TinyHttpdEventStream flushes through flushBuffer", eventStreamClass);
    }

    // SDS-AC-4, SDS-AC-5; FR-SSE-003 AC-6, AC-2: attach and drain receive the same Body instance, and the drain
    // writes on the worker that ran the handler, only after the handler returned
    @Test(timeout = 20000)
    public void sdsAc4_sdsAc5_FR_SSE_003_AC6_AC2_oneBodyForAttachAndDrainWrittenOnTheHandlerWorkerAfterItReturned()
            throws Exception {
        AtomicReference<OutputStream> attached = new AtomicReference<>();
        AtomicReference<OutputStream> drained = new AtomicReference<>();
        AtomicReference<Thread> handlerThread = new AtomicReference<>();
        AtomicReference<Thread> writeThread = new AtomicReference<>();
        AtomicBoolean returned = new AtomicBoolean();
        AtomicReference<Boolean> returnedAtWrite = new AtomicReference<>();
        List<Map.Entry<String, String>> headers = Collections.<Map.Entry<String, String>>singletonList(
                new SimpleEntry<>("Content-Type", "text/event-stream"));
        adapter.bind(Http.settings().build(), ex -> {
            handlerThread.set(Thread.currentThread());
            try {
                ex.eventStream(200, headers, new StreamDrain() {
                    @Override
                    public void attach(OutputStream out) {
                        attached.set(out);
                    }

                    @Override
                    public void drain(OutputStream out) throws IOException {
                        drained.set(out);
                        returnedAtWrite.set(returned.get());
                        writeThread.set(Thread.currentThread());
                        out.write("data: x\n\n".getBytes(StandardCharsets.UTF_8));
                        out.flush();
                        ex.finish();
                    }
                });
            } catch (IOException e) {
                ex.abort();
            }
            returned.set(true);
        });
        String response = Http.send(adapter.boundPort(), Http.get("/sse"));
        assertEquals("data: x\n\n", dechunk(Http.body(response)));
        assertSame(attached.get(), drained.get());
        assertTrue(String.valueOf(attached.get()), attached.get() instanceof TinyHttpdEventStream.Body);
        assertSame(handlerThread.get(), writeThread.get());
        assertEquals(Boolean.TRUE, returnedAtWrite.get());
    }

    // SDS-AC-5; FR-SSE-003 AC-2: a RuntimeException from the drain aborts the exchange, so the client sees the body
    // end without the terminating chunk, and the slot of the stream is released
    @Test(timeout = 20000)
    public void sdsAc5_FR_SSE_003_AC2_aRuntimeExceptionFromTheDrainAbortsTheExchangeAndReleasesTheSlot()
            throws Exception {
        AtomicReference<HttpExchange> exchange = new AtomicReference<>();
        adapter.bind(Http.settings().build(), ex -> {
            exchange.set(ex);
            try {
                ex.eventStream(200, Collections.<Map.Entry<String, String>>emptyList(), out -> {
                    out.write("data: x\n\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    throw new IllegalStateException("drain failed");
                });
            } catch (IOException e) {
                ex.abort();
            }
        });
        String response = Http.send(adapter.boundPort(), Http.get("/sse"));
        assertTrue(response, response.contains("data: x"));
        assertFalse(response, Http.body(response).endsWith("0\r\n\r\n"));
        TinyHttpdEventStream.Slots slots = ((TinyHttpdExchange) exchange.get()).slots();
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (slots.open() != 0 && System.nanoTime() < end) {
            Thread.sleep(5);
        }
        assertEquals(0, slots.open());
    }

    // SDS-AC-7; FR-SSE-003 AC-8: Body.flush reaches the socket: with heartbeat 1 s a ping arrives within 1.5 s with
    // nothing sent, and a small event sent from another thread after the handler returned within 500 ms
    @Test(timeout = 30000)
    public void sdsAc7_FR_SSE_003_AC8_aPingWithin1500MsAndASmallEventFromAnotherThreadWithin500Ms() throws Exception {
        AtomicReference<SseClient> client = new AtomicReference<>();
        CountDownLatch opened = new CountDownLatch(1);
        app = Http.app(adapter, log);
        app.sse(o -> o.heartbeatInterval(1, TimeUnit.SECONDS));
        app.get("/events", (req, res) -> {
            client.set(res.sse());
            opened.countDown();
        });
        int port = app.listen(0).port();
        try (Stream s = new Stream(port, Http.get("/events"))) {
            s.readHeaders();
            long t0 = System.nanoTime();
            s.readUntil(": ping\n\n");
            long pingMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            assertTrue("ping after " + pingMs + " ms", pingMs <= 1500);
            assertTrue(opened.await(5, TimeUnit.SECONDS));
            AtomicLong sentAt = new AtomicLong();
            Thread sender = new Thread(() -> {
                sentAt.set(System.nanoTime());
                client.get().send("small");
            });
            sender.start();
            s.readUntil("data: small\n\n");
            long received = System.nanoTime();
            sender.join(5000);
            long eventMs = TimeUnit.NANOSECONDS.toMillis(received - sentAt.get());
            assertTrue("event after " + eventMs + " ms", eventMs <= 500);
            client.get().close();
        }
    }
}
