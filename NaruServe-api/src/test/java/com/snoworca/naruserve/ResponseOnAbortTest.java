package com.snoworca.naruserve;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.common.AbortListeners;
import com.snoworca.naruserve.internal.DefaultResponse;
import com.snoworca.naruserve.internal.DefaultRestApp;
import com.snoworca.naruserve.internal.ResponseState;
import com.snoworca.naruserve.spi.HttpExchange;
import com.snoworca.naruserve.spi.StreamDrain;
import com.snoworca.naruserve.spi.UpgradeHandler;
import com.snoworca.naruserve.spi.WebSocketChannel;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.RecordingExchange;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.testing.TestApp;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/** res.onAbort listener execution: once on an abnormal end, never on success, isolated failures. */
public class ResponseOnAbortTest {

    private static final long WAIT_SECONDS = 10;

    /** A body that counts its closes. */
    private static final class CountingInput extends ByteArrayInputStream {
        final AtomicInteger closes = new AtomicInteger();

        CountingInput(byte[] bytes) {
            super(bytes);
        }

        @Override
        public void close() throws IOException {
            closes.incrementAndGet();
            super.close();
        }
    }

    /**
     * Delegates every abstract call to a RecordingExchange; {@link #clientClosed} is the client side going away,
     * which this exchange reports to nobody, since it keeps the SPI default {@code onAbort}.
     */
    private static class SilentExchange implements HttpExchange {
        final RecordingExchange recording;
        volatile boolean clientGone;
        /** Runs inside {@link #abort}, while the core is still in its abort cleanup. */
        volatile Runnable duringAbort = () -> { };

        SilentExchange(String method, String path) {
            recording = new RecordingExchange(method, path);
        }

        /** The client side goes away: the request body fails from now on, and nothing is reported. */
        void clientClosed() {
            clientGone = true;
        }

        @Override
        public String methodToken() {
            return recording.methodToken();
        }

        @Override
        public String rawPath() {
            return recording.rawPath();
        }

        @Override
        public String rawQuery() {
            return recording.rawQuery();
        }

        @Override
        public String httpVersion() {
            return recording.httpVersion();
        }

        @Override
        public List<Map.Entry<String, String>> headers() {
            return recording.headers();
        }

        @Override
        public InputStream requestBody() {
            InputStream body = recording.requestBody();
            return new InputStream() {
                @Override
                public int read() throws IOException {
                    if (clientGone) {
                        throw new IOException("client gone");
                    }
                    return body.read();
                }
            };
        }

        @Override
        public String remoteAddress() {
            return recording.remoteAddress();
        }

        @Override
        public boolean isSecure() {
            return recording.isSecure();
        }

        @Override
        public OutputStream commit(int status, List<Map.Entry<String, String>> headers, long contentLength) {
            return recording.commit(status, headers, contentLength);
        }

        @Override
        public void finish() throws IOException {
            recording.finish();
        }

        @Override
        public void abort() {
            recording.abort();
            duringAbort.run();
        }

        @Override
        public void sendFile(int status, List<Map.Entry<String, String>> headers, File file, long offset,
                long length) {
            recording.sendFile(status, headers, file, offset, length);
        }

        @Override
        public void upgrade(List<Map.Entry<String, String>> headers, UpgradeHandler handler) {
            recording.upgrade(headers, handler);
        }

        @Override
        public void eventStream(int status, List<Map.Entry<String, String>> headers, StreamDrain drain)
                throws IOException {
            recording.eventStream(status, headers, drain);
        }
    }

    /** As SilentExchange, but reports the client side going away through the SPI {@code onAbort}. */
    private static final class ReportingExchange extends SilentExchange {
        final AbortListeners aborts = new AbortListeners();

        ReportingExchange(String method, String path) {
            super(method, path);
        }

        @Override
        void clientClosed() {
            super.clientClosed();
            aborts.abort();
        }

        @Override
        public void onAbort(Runnable listener) {
            aborts.onAbort(listener);
        }
    }

    private static DefaultResponse direct() {
        return new DefaultResponse(new RecordingExchange("GET", "/x"), false);
    }

    private static ResponseState stateOf(Response res) {
        return ((DefaultResponse) res).state();
    }

    // FR-HTTP-035 AC-1: a handler that registers a listener and calls res.abort sees it run exactly once, and a
    // second res.abort runs it no further time
    @Test
    public void FR_HTTP_035_AC1_abortRunsTheListenerExactlyOnceAndASecondAbortNoMore() {
        TestApp t = TestApp.create();
        AtomicInteger runs = new AtomicInteger();
        AtomicInteger seenAfterFirst = new AtomicInteger(-1);
        t.app().get("/a", (req, res) -> {
            res.onAbort(runs::incrementAndGet);
            res.abort(new IOException("x"));
            seenAfterFirst.set(runs.get());
            res.abort(new IOException("x"));
        });
        ClientResponse r = t.send("GET", "/a");
        assertTrue(r.abnormallyClosed());
        assertEquals(1, seenAfterFirst.get());
        assertEquals(1, runs.get());
    }

    // FR-HTTP-035 AC-1: the same on a directly built response, which has no app
    @Test
    public void FR_HTTP_035_AC1_directResponseRunsTheListenerOnceOverTwoAborts() {
        DefaultResponse res = direct();
        AtomicInteger runs = new AtomicInteger();
        res.onAbort(runs::incrementAndGet);
        assertEquals(0, runs.get());
        res.abort(new IOException("x"));
        assertEquals(1, runs.get());
        res.abort(new IOException("x"));
        assertEquals(1, runs.get());
        assertEquals(ResponseState.ABORTED, res.state());
    }

    // FR-HTTP-035 AC-2: a response completed through send, through stream copied to its end and through relay
    // copied to its end never runs its listener, also not when res.abort is called after completion
    @Test
    public void FR_HTTP_035_AC2_normallyCompletedResponsesNeverRunTheListener() throws Exception {
        TestApp t = TestApp.create();
        AtomicInteger runs = new AtomicInteger();
        AtomicReference<Response> sent = new AtomicReference<>();
        AtomicReference<Response> streamed = new AtomicReference<>();
        AtomicReference<Response> relayed = new AtomicReference<>();
        byte[] payload = "payload".getBytes(StandardCharsets.UTF_8);
        t.app().get("/send", (req, res) -> {
            sent.set(res);
            res.onAbort(runs::incrementAndGet);
            res.send("sent");
        });
        t.app().get("/stream", (req, res) -> {
            streamed.set(res);
            res.onAbort(runs::incrementAndGet);
            res.stream(new ByteArrayInputStream(payload), "application/octet-stream");
        });
        t.app().get("/relay", (req, res) -> {
            relayed.set(res);
            res.onAbort(runs::incrementAndGet);
            res.header("Content-Type", "application/octet-stream");
            res.relay(new ByteArrayInputStream(payload), payload.length);
        });
        ClientResponse s = t.send("GET", "/send");
        assertEquals(200, s.status());
        assertEquals("sent", s.text());
        ClientResponse st = t.send("GET", "/stream");
        assertEquals(200, st.status());
        assertArrayEquals(payload, st.body());
        ClientResponse rl = t.send("GET", "/relay");
        assertEquals(200, rl.status());
        assertArrayEquals(payload, rl.body());
        for (AtomicReference<Response> ref : java.util.Arrays.asList(sent, streamed, relayed)) {
            Response res = ref.get();
            assertNotNull(res);
            assertEquals(ResponseState.COMPLETED, stateOf(res));
            res.abort(new IOException("after completion"));
            assertEquals(ResponseState.COMPLETED, stateOf(res));
        }
        assertEquals(0, runs.get());
    }

    // FR-HTTP-035 AC-2: an abort the exchange reports after the response completed through send runs no listener
    @Test
    public void FR_HTTP_035_AC2_exchangeReportedAbortAfterCompletionRunsNoListener() {
        DefaultRestApp app = new DefaultRestApp();
        AtomicInteger runs = new AtomicInteger();
        AtomicReference<Response> captured = new AtomicReference<>();
        app.get("/p", (req, res) -> {
            captured.set(res);
            res.onAbort(runs::incrementAndGet);
            res.send("ok");
        });
        app.freeze();
        ReportingExchange ex = new ReportingExchange("GET", "/p");
        app.dispatcher().handle(ex);
        assertEquals(ResponseState.COMPLETED, stateOf(captured.get()));
        ex.clientClosed();
        assertEquals(ResponseState.COMPLETED, stateOf(captured.get()));
        assertEquals(0, runs.get());
    }

    // FR-HTTP-035 AC-3: a listener registered after the abort runs immediately, once, on the registering thread
    @Test
    public void FR_HTTP_035_AC3_listenerRegisteredAfterAbortRunsAtOnceOnTheCallingThread() {
        DefaultResponse res = direct();
        res.abort(new IOException("x"));
        AtomicInteger runs = new AtomicInteger();
        AtomicReference<Thread> ranOn = new AtomicReference<>();
        res.onAbort(() -> {
            runs.incrementAndGet();
            ranOn.set(Thread.currentThread());
        });
        assertEquals(1, runs.get());
        assertSame(Thread.currentThread(), ranOn.get());
        res.abort(new IOException("again"));
        assertEquals(1, runs.get());
    }

    // FR-HTTP-035 AC-3: a listener another thread registers once the response is ABORTED, while the aborting thread
    // is still inside the exchange abort, runs at once on that registering thread
    @Test
    public void FR_HTTP_035_AC3_registrationDuringTheAbortCleanupRunsAtOnceOnTheRegisteringThread() throws Exception {
        SilentExchange ex = new SilentExchange("GET", "/x");
        DefaultResponse res = new DefaultResponse(ex, false);
        AtomicInteger runs = new AtomicInteger();
        AtomicInteger seenOnReturn = new AtomicInteger(-1);
        AtomicReference<Thread> registering = new AtomicReference<>();
        AtomicReference<Thread> ranOn = new AtomicReference<>();
        AtomicReference<ResponseState> stateSeen = new AtomicReference<>();
        ex.duringAbort = () -> {
            Thread other = new Thread(() -> {
                registering.set(Thread.currentThread());
                stateSeen.set(res.state());
                res.onAbort(() -> {
                    runs.incrementAndGet();
                    ranOn.set(Thread.currentThread());
                });
                seenOnReturn.set(runs.get());
            });
            other.start();
            try {
                other.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        res.abort(new IOException("x"));
        assertEquals(ResponseState.ABORTED, stateSeen.get());
        assertEquals(1, seenOnReturn.get());
        assertEquals(1, runs.get());
        assertNotNull(registering.get());
        assertSame(registering.get(), ranOn.get());
    }

    // FR-HTTP-035 AC-3: the same inside a handler of an app, after the handler's own res.abort
    @Test
    public void FR_HTTP_035_AC3_handlerRegisteringAfterItsAbortSeesTheListenerRunBeforeOnAbortReturns() {
        TestApp t = TestApp.create();
        AtomicInteger runs = new AtomicInteger();
        AtomicInteger seenOnReturn = new AtomicInteger(-1);
        AtomicReference<Thread> handlerThread = new AtomicReference<>();
        AtomicReference<Thread> ranOn = new AtomicReference<>();
        t.app().get("/late", (req, res) -> {
            handlerThread.set(Thread.currentThread());
            res.abort(new IOException("x"));
            res.onAbort(() -> {
                runs.incrementAndGet();
                ranOn.set(Thread.currentThread());
            });
            seenOnReturn.set(runs.get());
        });
        t.send("GET", "/late");
        assertEquals(1, seenOnReturn.get());
        assertEquals(1, runs.get());
        assertSame(handlerThread.get(), ranOn.get());
    }

    // FR-HTTP-035 AC-4: the first listener throws RuntimeException; the abort still runs the second, logs the
    // first one's exception through the app logger, ends ABORTED and closes the stream input it owned
    @Test
    public void FR_HTTP_035_AC4_throwingListenerIsLoggedAndTheNextListenerAndCleanupStillRun() {
        RecordingLogger log = new RecordingLogger();
        DefaultRestApp app = new DefaultRestApp();
        app.logger(log);
        RuntimeException boom = new IllegalStateException("listener boom");
        AtomicInteger second = new AtomicInteger();
        CountingInput in = new CountingInput("never copied".getBytes(StandardCharsets.UTF_8));
        AtomicReference<Response> captured = new AtomicReference<>();
        app.get("/a", (req, res) -> {
            captured.set(res);
            res.onAbort(() -> {
                throw boom;
            });
            res.onAbort(second::incrementAndGet);
            res.stream(in, "text/plain");
            res.abort(new IOException("x"));
        });
        app.freeze();
        RecordingExchange ex = new RecordingExchange("GET", "/a");
        app.dispatcher().handle(ex);
        assertEquals(1, second.get());
        assertTrue(log.lines.toString(), log.errors.contains(boom));
        int failed = log.lines.indexOf("ERROR onAbort listener failed listener boom");
        assertTrue(log.lines.toString(), failed >= 0);
        assertEquals(ResponseState.ABORTED, stateOf(captured.get()));
        assertEquals(1, in.closes.get());
        assertTrue(ex.aborts.get() >= 1);
        // the terminal step that runs after the listeners still ran: the aborted request's access line
        boolean terminalLine = false;
        for (String line : log.lines.subList(failed + 1, log.lines.size())) {
            terminalLine |= line.startsWith("INFO GET /a ") && line.contains(" aborted ");
        }
        assertTrue(log.lines.toString(), terminalLine);
    }

    // FR-HTTP-035 AC-4: on the WebSocket upgrade path, whose failed finish ends the response ABORTED without an
    // HttpExecution, a throwing listener is logged through the app logger and the next listener still runs once
    @Test
    public void FR_HTTP_035_AC4_failedUpgradeFinishRunsListenersAndLogsThroughTheAppLogger() {
        RecordingLogger log = new RecordingLogger();
        DefaultRestApp app = new DefaultRestApp();
        app.logger(log);
        RuntimeException boom = new IllegalStateException("upgrade listener boom");
        AtomicInteger second = new AtomicInteger();
        AtomicReference<Response> captured = new AtomicReference<>();
        app.use((req, res, next) -> {
            captured.set(res);
            res.onAbort(() -> {
                throw boom;
            });
            res.onAbort(second::incrementAndGet);
            next.run();
        });
        app.ws("/ws", (req, ws) -> { });
        app.freeze();
        RecordingExchange ex = new RecordingExchange("GET", "/ws", "Connection", "Upgrade", "Upgrade", "websocket");
        ex.webSocketScript = (settings, endpoint) -> { };
        ex.finishFailure = new IOException("connection gone");
        app.dispatcher().handle(ex);
        assertNotNull(captured.get());
        assertEquals(ResponseState.ABORTED, stateOf(captured.get()));
        assertEquals(1, second.get());
        assertTrue(log.lines.toString(), log.errors.contains(boom));
        assertTrue(log.lines.toString(), log.lines.contains("ERROR onAbort listener failed upgrade listener boom"));
    }

    // FR-HTTP-035 AC-4: a response without an app still isolates the throwing listener and runs the next one
    @Test
    public void FR_HTTP_035_AC4_directResponseIsolatesAThrowingListener() {
        DefaultResponse res = direct();
        AtomicInteger second = new AtomicInteger();
        CountingInput in = new CountingInput(new byte[] {1, 2, 3});
        res.onAbort(() -> {
            throw new IllegalStateException("listener boom");
        });
        res.onAbort(second::incrementAndGet);
        res.stream(in, "text/plain");
        res.abort(new IOException("x"));
        assertEquals(1, second.get());
        assertEquals(ResponseState.ABORTED, res.state());
        assertEquals(1, in.closes.get());
    }

    // FR-HTTP-035 AC-4: a stream input whose close throws RuntimeException during the abort cleanup does not stop
    // the listeners registered before the abort
    @Test
    public void FR_HTTP_035_AC4_failingInputCloseInTheAbortCleanupStillRunsTheListeners() {
        DefaultResponse res = direct();
        AtomicInteger runs = new AtomicInteger();
        res.onAbort(runs::incrementAndGet);
        res.stream(new ByteArrayInputStream(new byte[] {1}) {
            @Override
            public void close() {
                throw new IllegalStateException("close boom");
            }
        }, "text/plain");
        try {
            res.abort(new IOException("x"));
        } catch (IllegalStateException propagated) {
            assertEquals("close boom", propagated.getMessage());
        }
        assertEquals(ResponseState.ABORTED, res.state());
        assertEquals(1, runs.get());
    }

    // FR-HTTP-035 AC-5: res.onAbort(null) throws NullPointerException, before and after an abort, and onAbort
    // returns void
    @Test
    public void FR_HTTP_035_AC5_nullListenerThrowsAndOnAbortReturnsVoid() throws Exception {
        DefaultResponse res = direct();
        try {
            res.onAbort(null);
            fail("a null listener was accepted");
        } catch (NullPointerException expected) {
            // the contract
        }
        res.abort(new IOException("x"));
        try {
            res.onAbort(null);
            fail("a null listener was accepted after the abort");
        } catch (NullPointerException expected) {
            // the contract
        }
        assertEquals(void.class, Response.class.getMethod("onAbort", Runnable.class).getReturnType());
        assertEquals(void.class, DefaultResponse.class.getMethod("onAbort", Runnable.class).getReturnType());
    }

    // FR-HTTP-035 AC-6: a thread other than the request thread calling res.abort during a streaming transfer runs
    // the listener on that aborting thread, never on the request thread
    @Test
    public void FR_HTTP_035_AC6_abortFromAnotherThreadDuringStreamingRunsTheListenerOnTheAbortingThread()
            throws Exception {
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        TestApp t = TestApp.create(adapter);
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> requestThread = new AtomicReference<>();
        AtomicReference<Thread> ranOn = new AtomicReference<>();
        AtomicReference<Response> captured = new AtomicReference<>();
        AtomicInteger runs = new AtomicInteger();
        InputStream blocking = new InputStream() {
            @Override
            public int read() throws IOException {
                byte[] one = new byte[1];
                return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                reading.countDown();
                try {
                    if (!release.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                        throw new IOException("never released");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(e);
                }
                return -1;
            }
        };
        t.app().get("/s", (req, res) -> {
            requestThread.set(Thread.currentThread());
            captured.set(res);
            res.onAbort(() -> {
                runs.incrementAndGet();
                ranOn.set(Thread.currentThread());
            });
            res.stream(blocking, "application/octet-stream");
        });
        t.start();
        Future<ClientResponse> pending = adapter.submit(InMemoryHttpAdapter.ClientRequest.of("GET", "/s"));
        try {
            assertTrue("the copy started reading", reading.await(WAIT_SECONDS, TimeUnit.SECONDS));
            assertEquals(ResponseState.STREAMING, stateOf(captured.get()));
            captured.get().abort(new IOException("x"));
            assertEquals(1, runs.get());
            assertSame(Thread.currentThread(), ranOn.get());
            assertNotSame(requestThread.get(), ranOn.get());
        } finally {
            release.countDown();
        }
        pending.get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertEquals(1, runs.get());
        assertSame(Thread.currentThread(), ranOn.get());
    }

    // FR-HTTP-035 AC-7: an abort the exchange reports through HttpExchange.onAbort runs each res.onAbort listener
    // once
    @Test
    public void FR_HTTP_035_AC7_exchangeReportedAbortRunsEachListenerOnce() throws Exception {
        DefaultRestApp app = new DefaultRestApp();
        Deferred pending = Deferred.pending();
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();
        AtomicReference<Response> captured = new AtomicReference<>();
        app.getAsync("/p", (req, res, next) -> {
            captured.set(res);
            res.onAbort(first::incrementAndGet);
            res.onAbort(second::incrementAndGet);
            return pending;
        });
        app.freeze();
        ReportingExchange ex = new ReportingExchange("GET", "/p");
        app.dispatcher().handle(ex);
        assertEquals(0, first.get());
        ex.clientClosed();
        assertEquals(1, first.get());
        assertEquals(1, second.get());
        assertEquals(ResponseState.ABORTED, stateOf(captured.get()));
        ex.clientClosed();
        captured.get().abort(new IOException("x"));
        pending.resolve();
        assertEquals(1, first.get());
        assertEquals(1, second.get());
    }

    // FR-HTTP-035 AC-7: the core adds no detection of its own; with an exchange keeping the SPI default onAbort,
    // closing the client side runs no listener while res.abort still does
    @Test
    public void FR_HTTP_035_AC7_exchangeKeepingTheSpiDefaultReportsNoDisconnectButResAbortStillRuns()
            throws Exception {
        assertEquals(HttpExchange.class,
                SilentExchange.class.getMethod("onAbort", Runnable.class).getDeclaringClass());
        DefaultRestApp app = new DefaultRestApp();
        Deferred pending = Deferred.pending();
        AtomicInteger runs = new AtomicInteger();
        AtomicReference<Response> captured = new AtomicReference<>();
        app.getAsync("/p", (req, res, next) -> {
            captured.set(res);
            res.onAbort(runs::incrementAndGet);
            return pending;
        });
        app.freeze();
        SilentExchange ex = new SilentExchange("GET", "/p");
        app.dispatcher().handle(ex);
        ex.clientClosed();
        assertTrue(ex.clientGone);
        assertEquals(0, runs.get());
        assertEquals(ResponseState.NEW, stateOf(captured.get()));
        captured.get().abort(new IOException("x"));
        assertEquals(1, runs.get());
        pending.resolve();
        assertEquals(1, runs.get());
    }

    // FR-HTTP-035 AC-7: on the legacy dispatch path (a WebSocket-upgrade request) an abort the exchange reports
    // through HttpExchange.onAbort, while middleware runs, runs each res.onAbort listener once and stops the upgrade
    @Test
    public void FR_HTTP_035_AC7_legacyUpgradePathExchangeReportedAbortRunsEachListenerOnce() {
        RecordingExchange rec = new RecordingExchange("GET", "/ws", "Connection", "Upgrade", "Upgrade", "websocket");
        AtomicInteger upgrades = new AtomicInteger();
        rec.webSocketScript = (settings, endpoint) -> upgrades.incrementAndGet();
        AbortListeners aborts = new AbortListeners();
        HttpExchange ex = (HttpExchange) Proxy.newProxyInstance(HttpExchange.class.getClassLoader(),
                new Class<?>[] {HttpExchange.class}, (proxy, m, args) -> {
                    if (m.getName().equals("onAbort")) {
                        aborts.onAbort((Runnable) args[0]);
                        return null;
                    }
                    try {
                        return m.invoke(rec, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        DefaultRestApp app = new DefaultRestApp();
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();
        AtomicReference<Response> captured = new AtomicReference<>();
        app.use((req, res, next) -> {
            captured.set(res);
            res.onAbort(first::incrementAndGet);
            res.onAbort(second::incrementAndGet);
            aborts.abort(); // the adapter reports the client disconnect before the 101
            aborts.abort();
            next.run();
        });
        app.ws("/ws", (req, ws) -> { });
        app.freeze();
        app.dispatcher().handle(ex);
        assertEquals(1, first.get());
        assertEquals(1, second.get());
        assertEquals(ResponseState.ABORTED, stateOf(captured.get()));
        assertEquals(0, upgrades.get());
    }

    // FR-APP-019 AC-8: an abort the exchange reports through HttpExchange.onAbort while the upgraded session is open
    // (an adapter stop after its grace) leaves the 101 handshake line the only GET /ws line, and the response that
    // completed with the 101 runs no res.onAbort listener (FR-HTTP-035: never for a completed response)
    @Test
    public void FR_APP_019_AC8_exchangeReportedAbortDuringAnOpenSessionWritesNoSecondRequestLine() {
        RecordingExchange rec = new RecordingExchange("GET", "/ws", "Connection", "Upgrade", "Upgrade", "websocket");
        AbortListeners aborts = new AbortListeners();
        rec.webSocketScript = (settings, endpoint) -> {
            endpoint.onOpen(new WebSocketChannel() {
                volatile boolean open = true;

                @Override
                public void sendText(String text) { }

                @Override
                public void sendBinary(byte[] data) { }

                @Override
                public void ping(byte[] payload) { }

                @Override
                public void close(int code, String reason) {
                    open = false;
                }

                @Override
                public void abort() {
                    open = false;
                }

                @Override
                public boolean isOpen() {
                    return open;
                }
            });
            aborts.abort(); // the adapter aborts the open session's socket at its stop deadline
            endpoint.onClose(1006, "");
        };
        HttpExchange ex = (HttpExchange) Proxy.newProxyInstance(HttpExchange.class.getClassLoader(),
                new Class<?>[] {HttpExchange.class}, (proxy, m, args) -> {
                    if (m.getName().equals("onAbort")) {
                        aborts.onAbort((Runnable) args[0]);
                        return null;
                    }
                    try {
                        return m.invoke(rec, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        RecordingLogger log = new RecordingLogger();
        DefaultRestApp app = new DefaultRestApp();
        app.logger(log);
        AtomicInteger runs = new AtomicInteger();
        AtomicReference<Response> captured = new AtomicReference<>();
        app.use((req, res, next) -> {
            captured.set(res);
            res.onAbort(runs::incrementAndGet);
            next.run();
        });
        app.ws("/ws", (req, ws) -> { });
        app.freeze();
        app.dispatcher().handle(ex);
        List<String> requestLines = new java.util.ArrayList<>();
        for (String line : log.info()) {
            if (line.startsWith("GET /ws ")) {
                requestLines.add(line);
            }
        }
        assertEquals(log.lines.toString(), 1, requestLines.size());
        assertTrue(requestLines.get(0), requestLines.get(0).matches("GET /ws 101 \\d+ms in=0 out=0 rid=[0-9a-f]{16}"));
        assertEquals(0, runs.get());
        assertEquals(ResponseState.COMPLETED, stateOf(captured.get()));
    }

    /** {@code rec} behind a proxy whose onAbort registers with {@code aborts}, so a test reports the disconnect. */
    private static HttpExchange reporting(RecordingExchange rec, AbortListeners aborts) {
        return (HttpExchange) Proxy.newProxyInstance(HttpExchange.class.getClassLoader(),
                new Class<?>[] {HttpExchange.class}, (proxy, m, args) -> {
                    if (m.getName().equals("onAbort")) {
                        aborts.onAbort((Runnable) args[0]);
                        return null;
                    }
                    try {
                        return m.invoke(rec, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private static List<String> requestLines(RecordingLogger log, String prefix) {
        List<String> lines = new java.util.ArrayList<>();
        for (String line : log.info()) {
            if (line.startsWith(prefix)) {
                lines.add(line);
            }
        }
        return lines;
    }

    // FR-HTTP-035 AC-7 / FR-APP-019 AC-3: the adapter reports the disconnect while writing the 101, before onOpen,
    // and webSocket throws; no 101 was written, so the response ends ABORTED, the listener runs once and the one
    // GET /ws line is marked aborted
    @Test
    public void FR_HTTP_035_AC7_legacyUpgradeAbortReportedBeforeOnOpenAbortsTheResponse() {
        RecordingExchange rec = new RecordingExchange("GET", "/ws", "Connection", "Upgrade", "Upgrade", "websocket");
        AbortListeners aborts = new AbortListeners();
        rec.webSocketScript = (settings, endpoint) -> {
            aborts.abort();
            throw new IOException("peer gone before the 101");
        };
        RecordingLogger log = new RecordingLogger();
        DefaultRestApp app = new DefaultRestApp();
        app.logger(log);
        AtomicInteger runs = new AtomicInteger();
        AtomicReference<Response> captured = new AtomicReference<>();
        app.use((req, res, next) -> {
            captured.set(res);
            res.onAbort(runs::incrementAndGet);
            next.run();
        });
        app.ws("/ws", (req, ws) -> { });
        app.freeze();
        app.dispatcher().handle(reporting(rec, aborts));
        List<String> lines = requestLines(log, "GET /ws ");
        assertEquals(log.lines.toString(), 1, runs.get());
        assertEquals(ResponseState.ABORTED, stateOf(captured.get()));
        assertEquals(log.lines.toString(), 1, lines.size());
        assertTrue(lines.get(0), lines.get(0).contains("aborted"));
        assertTrue(lines.get(0), !lines.get(0).startsWith("GET /ws 101 "));
        assertEquals(0, rec.commits.get());
    }

    // FR-HTTP-035 AC-7 / FR-APP-019 AC-3 AC-8: the disconnect is reported before onOpen but a permissive adapter
    // still opens the session; the aborted response wins, so no 101 line is written, the handler never runs, the
    // channel is dropped and the one GET /ws line is marked aborted
    @Test
    public void FR_HTTP_035_AC7_legacyUpgradeAbortReportedBeforeOnOpenRefusesTheLateOpen() {
        RecordingExchange rec = new RecordingExchange("GET", "/ws", "Connection", "Upgrade", "Upgrade", "websocket");
        AbortListeners aborts = new AbortListeners();
        AtomicInteger channelEnds = new AtomicInteger();
        rec.webSocketScript = (settings, endpoint) -> {
            aborts.abort();
            endpoint.onOpen(new WebSocketChannel() {
                volatile boolean open = true;

                @Override
                public void sendText(String text) { }

                @Override
                public void sendBinary(byte[] data) { }

                @Override
                public void ping(byte[] payload) { }

                @Override
                public void close(int code, String reason) {
                    open = false;
                    channelEnds.incrementAndGet();
                }

                @Override
                public void abort() {
                    open = false;
                    channelEnds.incrementAndGet();
                }

                @Override
                public boolean isOpen() {
                    return open;
                }
            });
            endpoint.onClose(1006, "");
        };
        RecordingLogger log = new RecordingLogger();
        DefaultRestApp app = new DefaultRestApp();
        app.logger(log);
        AtomicInteger runs = new AtomicInteger();
        AtomicInteger handlerRuns = new AtomicInteger();
        AtomicReference<Response> captured = new AtomicReference<>();
        app.use((req, res, next) -> {
            captured.set(res);
            res.onAbort(runs::incrementAndGet);
            next.run();
        });
        app.ws("/ws", (req, ws) -> handlerRuns.incrementAndGet());
        app.freeze();
        app.dispatcher().handle(reporting(rec, aborts));
        List<String> lines = requestLines(log, "GET /ws ");
        assertEquals(log.lines.toString(), 1, runs.get());
        assertEquals(ResponseState.ABORTED, stateOf(captured.get()));
        assertEquals(log.lines.toString(), 1, lines.size());
        assertTrue(lines.get(0), lines.get(0).contains("aborted"));
        assertTrue(lines.get(0), !lines.get(0).startsWith("GET /ws 101 "));
        assertEquals(0, handlerRuns.get());
        assertTrue(log.lines.toString(), channelEnds.get() >= 1);
        assertEquals(log.lines.toString(), 0, requestLines(log, "WS /ws ").size());
    }

    /** The javadoc block directly above {@code default void onAbort(Runnable listener)}, as one line of prose. */
    private static String onAbortJavadoc() throws IOException {
        File source = new File(System.getProperty("user.dir"), "src/main/java/com/snoworca/naruserve/Response.java");
        String text = new String(java.nio.file.Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);
        int decl = text.indexOf("default void onAbort(Runnable listener)");
        assertTrue("Response declares onAbort", decl > 0);
        int end = text.lastIndexOf("*/", decl);
        int start = text.lastIndexOf("/**", end);
        assertTrue("onAbort has a javadoc block", start >= 0 && end > start);
        String between = text.substring(end + 2, decl);
        assertTrue("the javadoc block belongs to onAbort: " + between,
                between.replaceAll("//[^\n]*", "").trim().isEmpty());
        StringBuilder prose = new StringBuilder();
        for (String line : text.substring(start + 3, end).split("\n")) {
            prose.append(' ').append(line.trim().replaceFirst("^\\*\\s?", ""));
        }
        return prose.toString().replaceAll("\\s+", " ").trim();
    }

    // FR-HTTP-035 AC-8: the onAbort javadoc states the listener may run on a thread other than the request thread
    // and that an adapter may not report an idle-period disconnect until the next I/O
    @Test
    public void FR_HTTP_035_AC8_onAbortJavadocStatesTheThreadAndIdleDisconnectLimits() throws Exception {
        String doc = onAbortJavadoc();
        assertTrue(doc, doc.contains("may run on a thread other than the request thread"));
        assertTrue(doc, doc.contains("may not report a disconnect during an idle period until the next I/O"));
    }
}
