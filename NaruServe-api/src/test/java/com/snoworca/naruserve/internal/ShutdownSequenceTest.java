package com.snoworca.naruserve.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.AppState;
import com.snoworca.naruserve.Logger;
import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.ConsoleLogger;
import com.snoworca.naruserve.spi.ExchangeHandler;
import com.snoworca.naruserve.spi.HttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.StubJsonCodec;
import java.io.Flushable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/** The RUNNING shutdown sequence. SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-4-7.sds.md. */
public class ShutdownSequenceTest {

    /** An in-memory adapter that records bind, stop (with its timeout in nanoseconds) and release. */
    static class RecordingAdapter implements HttpAdapter {
        final InMemoryHttpAdapter delegate = new InMemoryHttpAdapter();
        final List<String> events;
        volatile long stopNanos = -1;
        volatile IOException bindFailure;

        RecordingAdapter(List<String> events) {
            this.events = events;
        }

        @Override
        public void bind(String host, int port, ExchangeHandler handler) throws IOException {
            events.add("bind");
            delegate.bind(host, port, handler);
            if (bindFailure != null) {
                throw bindFailure;
            }
        }

        @Override
        public int boundPort() {
            return delegate.boundPort();
        }

        @Override
        public void stop(long timeout, TimeUnit unit) {
            stopNanos = unit.toNanos(timeout);
            events.add("adapter.stop");
            delegate.stop(timeout, unit);
        }

        @Override
        public void release() {
            events.add("adapter.release");
            delegate.release();
        }
    }

    /** A Flushable/AutoCloseable logger that records flush, close and ERROR records. */
    static final class RecordingLogger implements Logger, Flushable, AutoCloseable {
        final List<String> events;
        final List<String> errors = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger closes = new AtomicInteger();
        volatile RestApp app;

        RecordingLogger(List<String> events) {
            this.events = events;
        }

        @Override
        public void trace(String message) {
        }

        @Override
        public void debug(String message) {
        }

        @Override
        public void info(String message) {
        }

        @Override
        public void warn(String message) {
        }

        @Override
        public void error(String message) {
            errors.add(message);
        }

        @Override
        public void error(String message, Throwable error) {
            errors.add(message + ": " + error.getMessage());
        }

        @Override
        public void flush() {
            events.add("logger.flush" + (app == null ? "" : " " + app.state()));
        }

        @Override
        public void close() {
            closes.incrementAndGet();
            events.add("logger.close" + (app == null ? "" : " " + app.state()));
        }
    }

    /** Records its close and the app state seen by it. */
    static final class Closer implements AutoCloseable {
        final List<String> events;
        final String name;
        final RestApp app;

        Closer(List<String> events, String name, RestApp app) {
            this.events = events;
            this.name = name;
            this.app = app;
        }

        @Override
        public void close() {
            events.add("close " + name + " " + app.state());
        }
    }

    static final class UserService {
    }

    private final List<String> events = Collections.synchronizedList(new ArrayList<>());
    private final RecordingAdapter adapter = new RecordingAdapter(events);
    private final RecordingLogger logger = new RecordingLogger(events);

    private DefaultRestApp app() {
        DefaultRestApp app = (DefaultRestApp) RestApp.builder().httpAdapter(adapter).jsonCodec(new StubJsonCodec())
                .logger(logger).build();
        logger.app = app;
        return app;
    }

    private void listen(DefaultRestApp app) {
        app.listen(0);
        events.clear();
    }

    // SDS-AC-7; FR-APP-011 AC-1
    @Test
    public void sdsAc7_FR_APP_011_AC1_sequenceRunsTheFixedStepOrderThenTerminated() {
        DefaultRestApp app = app();
        app.onStart(ctx -> ctx.own(new Closer(events, "owned", app)));
        app.onStop(ctx -> events.add("onStop " + app.state()));
        ShutdownParticipants p = app.shutdownParticipants();
        p.add(ShutdownParticipants.Phase.AFTER_ON_STOP, () -> events.add("afterOnStop"));
        p.add(ShutdownParticipants.Phase.FORCE_CLOSE, () -> events.add("forceClose"));
        p.add(ShutdownParticipants.Phase.BEFORE_WAIT, () -> events.add("beforeWait " + app.state()));
        listen(app);
        app.shutdown(1, TimeUnit.SECONDS);
        assertEquals(Arrays.asList("beforeWait STOPPING", "adapter.stop", "forceClose", "onStop STOPPING",
                "afterOnStop", "close owned STOPPING", "logger.flush STOPPING", "logger.close STOPPING",
                "adapter.release"), events);
        assertEquals(AppState.TERMINATED, app.state());
    }

    // SDS-AC-8; FR-APP-011 AC-2
    @Test
    public void sdsAc8_FR_APP_011_AC2_adapterStopGetsTheTimeLeftUntilTheOneDeadline() {
        DefaultRestApp app = app();
        app.shutdownParticipants().add(ShutdownParticipants.Phase.BEFORE_WAIT, () -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        listen(app);
        // A 10 s budget leaves a 2 s scheduler margin below while keeping the 200 ms deduction exact above.
        app.shutdown(10, TimeUnit.SECONDS);
        assertTrue("stop got " + adapter.stopNanos,
                adapter.stopNanos >= TimeUnit.MILLISECONDS.toNanos(10000 - 200 - 2000));
        assertTrue("stop got " + adapter.stopNanos, adapter.stopNanos <= TimeUnit.MILLISECONDS.toNanos(10000 - 200));
    }

    // SDS-AC-9; FR-APP-011 AC-3
    @Test
    public void sdsAc9_FR_APP_011_AC3_throwingParticipantIsLoggedAtErrorAndTheSequenceContinues() {
        DefaultRestApp app = app();
        ShutdownParticipants p = app.shutdownParticipants();
        p.add(ShutdownParticipants.Phase.BEFORE_WAIT, () -> {
            throw new IllegalStateException("participant broke");
        });
        p.add(ShutdownParticipants.Phase.BEFORE_WAIT, () -> events.add("second"));
        listen(app);
        app.shutdown(1, TimeUnit.SECONDS);
        assertTrue(events.toString(), events.contains("second"));
        assertTrue(events.toString(), events.contains("adapter.release"));
        assertEquals(1, logger.errors.size());
        assertTrue(logger.errors.toString(), logger.errors.get(0).contains("participant broke"));
        assertEquals(AppState.TERMINATED, app.state());
    }

    // SDS-AC-10; FR-APP-011 AC-4
    @Test
    public void sdsAc10_FR_APP_011_AC4_onStopStillReadsServicesBeforeAnyResourceIsClosed() {
        DefaultRestApp app = app();
        UserService users = new UserService();
        List<Object> seen = new ArrayList<>();
        app.onStart(ctx -> {
            ctx.register(UserService.class, users);
            ctx.own(new Closer(events, "owned", app));
        });
        app.onStop(ctx -> {
            seen.add(ctx.get(UserService.class));
            seen.add(new ArrayList<>(events));
        });
        listen(app);
        app.shutdown(1, TimeUnit.SECONDS);
        assertSame(users, seen.get(0));
        assertFalse(seen.get(1).toString(), seen.get(1).toString().contains("close owned"));
    }

    // SDS-AC-11; FR-APP-011 AC-5
    @Test
    public void sdsAc11_FR_APP_011_AC5_concurrentShutdownRunsTheSequenceOnceAndTheLoserWaits() throws Exception {
        DefaultRestApp app = app();
        CountDownLatch inOnStop = new CountDownLatch(1);
        CountDownLatch releaseOnStop = new CountDownLatch(1);
        AtomicInteger onStops = new AtomicInteger();
        app.onStop(ctx -> {
            onStops.incrementAndGet();
            inOnStop.countDown();
            releaseOnStop.await(5, TimeUnit.SECONDS);
        });
        listen(app);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> winner = pool.submit(() -> app.shutdown(5, TimeUnit.SECONDS));
            assertTrue(inOnStop.await(5, TimeUnit.SECONDS));
            long t0 = System.nanoTime();
            app.shutdown(100, TimeUnit.MILLISECONDS);
            long waited = System.nanoTime() - t0;
            assertTrue("loser waited " + waited, waited >= TimeUnit.MILLISECONDS.toNanos(90));
            assertEquals(AppState.STOPPING, app.state());
            releaseOnStop.countDown();
            winner.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        app.shutdown(1, TimeUnit.SECONDS);
        assertEquals(1, onStops.get());
        assertEquals(1, Collections.frequency(events, "adapter.stop"));
        assertEquals(1, Collections.frequency(events, "adapter.release"));
        assertEquals(1, logger.closes.get());
        assertEquals(AppState.TERMINATED, app.state());
    }

    // SDS-AC-12; FR-APP-011 AC-6
    @Test
    public void sdsAc12_FR_APP_011_AC6_inFlightRequestCompletesWhileAdapterStopWaits() throws Exception {
        DefaultRestApp app = app();
        CountDownLatch inHandler = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        app.get("/slow", (req, res) -> {
            inHandler.countDown();
            release.await(5, TimeUnit.SECONDS);
            res.send("done");
        });
        listen(app);
        Future<ClientResponse> r = adapter.delegate.submit(ClientRequest.of("GET", "/slow"));
        assertTrue(inHandler.await(5, TimeUnit.SECONDS));
        // Release the handler only once adapter.stop is waiting, so the test proves stop waits for it.
        Thread releaser = new Thread(() -> {
            try {
                while (!events.contains("adapter.stop")) {
                    Thread.sleep(5);
                }
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            release.countDown();
        });
        releaser.start();
        app.shutdown(5, TimeUnit.SECONDS);
        releaser.join(5000);
        ClientResponse resp = r.get(5, TimeUnit.SECONDS);
        assertEquals(200, resp.status());
        assertEquals("done", new String(resp.body(), StandardCharsets.UTF_8));
        assertFalse(resp.abnormallyClosed());
    }

    // SDS-AC-13; FR-APP-011 AC-7
    @Test
    public void sdsAc13_FR_APP_011_AC7_ownedAppLoggerIsSkippedAtItsOwnedPositionAndClosedOnce() {
        DefaultRestApp app = app();
        app.onStart(ctx -> {
            ctx.own(new Closer(events, "first", app));
            ctx.own(logger);
            ctx.own(new Closer(events, "last", app));
        });
        listen(app);
        app.shutdown(1, TimeUnit.SECONDS);
        assertEquals(1, logger.closes.get());
        assertEquals(Arrays.asList("adapter.stop", "close last STOPPING", "close first STOPPING",
                "logger.flush STOPPING", "logger.close STOPPING", "adapter.release"), events);
    }

    // SDS-AC-13; FR-APP-011 AC-7
    @Test
    public void sdsAc13_FR_APP_011_AC7_closingTheConsoleLoggerLeavesSystemOutOpen() {
        ConsoleLogger console = new ConsoleLogger();
        DefaultRestApp app = (DefaultRestApp) RestApp.builder().httpAdapter(adapter).jsonCodec(new StubJsonCodec())
                .logger(console).build();
        app.onStart(ctx -> ctx.own(console));
        app.listen(0);
        app.shutdown(1, TimeUnit.SECONDS);
        System.out.print("");
        assertFalse(System.out.checkError());
        assertEquals(AppState.TERMINATED, app.state());
    }

    // SDS-AC-14; FR-APP-011 AC-8
    @Test
    public void sdsAc14_FR_APP_011_AC8_throwingOnStopIsLoggedTheRestRunAndTheSequenceEnds() {
        DefaultRestApp app = app();
        app.onStop(ctx -> {
            throw new IOException("onStop broke");
        });
        app.onStop(ctx -> events.add("second onStop"));
        listen(app);
        app.shutdown(1, TimeUnit.SECONDS);
        assertTrue(events.toString(), events.contains("second onStop"));
        assertTrue(events.toString(), events.contains("adapter.release"));
        assertEquals(1, logger.errors.size());
        assertTrue(logger.errors.toString(), logger.errors.get(0).contains("onStop broke"));
        assertEquals(AppState.TERMINATED, app.state());
    }

    // FR-APP-011 AC-8: an Error from onStop is logged, every later step runs, and it is rethrown after TERMINATED.
    @Test
    public void FR_APP_011_AC8_onStopErrorStillRunsEveryLaterStepThenIsRethrown() {
        DefaultRestApp app = app();
        app.onStart(ctx -> ctx.own(new Closer(events, "owned", app)));
        app.onStop(ctx -> {
            throw new AssertionError("onStop error");
        });
        app.onStop(ctx -> events.add("second onStop"));
        app.shutdownParticipants().add(ShutdownParticipants.Phase.AFTER_ON_STOP, () -> events.add("afterOnStop"));
        listen(app);
        try {
            app.shutdown(1, TimeUnit.SECONDS);
            fail("the Error must be rethrown after cleanup");
        } catch (AssertionError e) {
            assertEquals("onStop error", e.getMessage());
        }
        assertEquals(Arrays.asList("adapter.stop", "second onStop", "afterOnStop", "close owned STOPPING",
                "logger.flush STOPPING", "logger.close STOPPING", "adapter.release"), events);
        assertEquals(1, logger.errors.size());
        assertTrue(logger.errors.toString(), logger.errors.get(0).contains("onStop error"));
        assertEquals(AppState.TERMINATED, app.state());
    }

    // FR-APP-011 AC-3: an Error from a participant is logged, every later step runs, and it is rethrown after TERMINATED.
    @Test
    public void FR_APP_011_AC3_participantErrorStillRunsEveryLaterStepThenIsRethrown() {
        DefaultRestApp app = app();
        app.onStart(ctx -> ctx.own(new Closer(events, "owned", app)));
        ShutdownParticipants p = app.shutdownParticipants();
        p.add(ShutdownParticipants.Phase.BEFORE_WAIT, () -> {
            throw new AssertionError("participant error");
        });
        p.add(ShutdownParticipants.Phase.BEFORE_WAIT, () -> events.add("second"));
        app.onStop(ctx -> events.add("onStop"));
        listen(app);
        try {
            app.shutdown(1, TimeUnit.SECONDS);
            fail("the Error must be rethrown after cleanup");
        } catch (AssertionError e) {
            assertEquals("participant error", e.getMessage());
        }
        assertEquals(Arrays.asList("second", "adapter.stop", "onStop", "close owned STOPPING",
                "logger.flush STOPPING", "logger.close STOPPING", "adapter.release"), events);
        assertEquals(1, logger.errors.size());
        assertTrue(logger.errors.toString(), logger.errors.get(0).contains("participant error"));
        assertEquals(AppState.TERMINATED, app.state());
    }

    // review P2-001; FR-APP-011 AC-8: an Error from an owned close() is logged, the earlier-registered resource, the
    // logger flush and close and the adapter release still run, and it is rethrown after TERMINATED.
    @Test
    public void review_P2_001_FR_APP_011_AC8_ownedCloseErrorStillRunsEveryLaterStepThenIsRethrown() {
        DefaultRestApp app = app();
        app.onStart(ctx -> {
            ctx.own(new Closer(events, "first", app));
            ctx.own(() -> {
                events.add("close broken");
                throw new AssertionError("close error");
            });
        });
        listen(app);
        try {
            app.shutdown(1, TimeUnit.SECONDS);
            fail("the Error must be rethrown after cleanup");
        } catch (AssertionError e) {
            assertEquals("close error", e.getMessage());
        }
        assertEquals(Arrays.asList("adapter.stop", "close broken", "close first STOPPING",
                "logger.flush STOPPING", "logger.close STOPPING", "adapter.release"), events);
        assertEquals(1, logger.errors.size());
        assertTrue(logger.errors.toString(), logger.errors.get(0).contains("close error"));
        assertEquals(AppState.TERMINATED, app.state());
    }

    // review P2-002; FR-APP-011 AC-8: an Error from the adapter's stop() and release() is logged like one from a
    // participant, every other step runs, and the first Error is rethrown after TERMINATED.
    @Test
    public void review_P2_002_FR_APP_011_AC8_adapterStopAndReleaseErrorsStillRunEveryStepThenFirstIsRethrown() {
        RecordingAdapter failing = new RecordingAdapter(events) {
            @Override
            public void stop(long timeout, TimeUnit unit) {
                super.stop(timeout, unit);
                throw new AssertionError("stop error");
            }

            @Override
            public void release() {
                super.release();
                throw new AssertionError("release error");
            }
        };
        DefaultRestApp app = (DefaultRestApp) RestApp.builder().httpAdapter(failing).jsonCodec(new StubJsonCodec())
                .logger(logger).build();
        logger.app = app;
        app.onStart(ctx -> ctx.own(new Closer(events, "owned", app)));
        app.onStop(ctx -> events.add("onStop"));
        app.shutdownParticipants().add(ShutdownParticipants.Phase.FORCE_CLOSE, () -> events.add("forceClose"));
        listen(app);
        try {
            app.shutdown(1, TimeUnit.SECONDS);
            fail("the Error must be rethrown after cleanup");
        } catch (AssertionError e) {
            assertEquals("stop error", e.getMessage());
        }
        assertEquals(Arrays.asList("adapter.stop", "forceClose", "onStop", "close owned STOPPING",
                "logger.flush STOPPING", "logger.close STOPPING", "adapter.release"), events);
        assertEquals(2, logger.errors.size());
        assertEquals(AppState.TERMINATED, app.state());
    }

    // SDS-AC-7 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-12.sds.md; FR-APP-020 AC-1: every phase passes
    // the deadline shutdown(timeout, unit) computed at entry; at the before-wait entry deadline - nanoTime lies
    // within [timeout - 100 ms, timeout]
    @Test
    public void wave7SdsAc7_FR_APP_020_AC1_everyPhaseGetsTheDeadlineComputedAtShutdownEntry() {
        DefaultRestApp app = app();
        List<Long> left = Collections.synchronizedList(new ArrayList<Long>());
        List<Long> deadlines = Collections.synchronizedList(new ArrayList<Long>());
        ShutdownParticipants p = app.shutdownParticipants();
        p.add(ShutdownParticipants.Phase.BEFORE_WAIT, (ShutdownParticipants.DeadlineParticipant) d -> {
            left.add(d - System.nanoTime());
            deadlines.add(d);
        });
        p.add(ShutdownParticipants.Phase.FORCE_CLOSE, (ShutdownParticipants.DeadlineParticipant) deadlines::add);
        p.add(ShutdownParticipants.Phase.AFTER_ON_STOP, (ShutdownParticipants.DeadlineParticipant) deadlines::add);
        listen(app);
        app.shutdown(5, TimeUnit.SECONDS);
        long timeout = TimeUnit.SECONDS.toNanos(5);
        assertEquals(1, left.size());
        assertTrue("left " + left.get(0), left.get(0) <= timeout);
        assertTrue("left " + left.get(0), left.get(0) >= timeout - TimeUnit.MILLISECONDS.toNanos(100));
        assertEquals(deadlines.toString(), 3, deadlines.size());
        assertEquals(deadlines.get(0), deadlines.get(1));
        assertEquals(deadlines.get(0), deadlines.get(2));
    }

    // SDS-AC-7 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-12.sds.md; FR-APP-020 AC-5: a 300 ms request
    // that started before shutdown(5, SECONDS) completes with 200 and its full body before onStop runs (T44)
    @Test
    public void wave7SdsAc7_FR_APP_020_AC5_inFlight300MsRequestCompletesBeforeOnStop() throws Exception {
        DefaultRestApp app = app();
        List<String> order = Collections.synchronizedList(new ArrayList<String>());
        CountDownLatch inHandler = new CountDownLatch(1);
        app.get("/short", (req, res) -> {
            inHandler.countDown();
            Thread.sleep(300);
            res.send("complete body");
            order.add("response completed");
        });
        app.onStop(ctx -> order.add("onStop"));
        listen(app);
        Future<ClientResponse> r = adapter.delegate.submit(ClientRequest.of("GET", "/short"));
        assertTrue(inHandler.await(5, TimeUnit.SECONDS));
        app.shutdown(5, TimeUnit.SECONDS);
        ClientResponse resp = r.get(5, TimeUnit.SECONDS);
        assertEquals(200, resp.status());
        assertEquals("complete body", new String(resp.body(), StandardCharsets.UTF_8));
        assertFalse(resp.abnormallyClosed());
        assertEquals(Arrays.asList("response completed", "onStop"), order);
    }
}
