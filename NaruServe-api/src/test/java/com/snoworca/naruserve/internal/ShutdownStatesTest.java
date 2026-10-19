package com.snoworca.naruserve.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.AppStartException;
import com.snoworca.naruserve.AppState;
import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.internal.ShutdownSequenceTest.Closer;
import com.snoworca.naruserve.internal.ShutdownSequenceTest.RecordingAdapter;
import com.snoworca.naruserve.internal.ShutdownSequenceTest.RecordingLogger;
import com.snoworca.naruserve.spi.ExchangeHandler;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.StubJsonCodec;
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
import org.junit.After;
import org.junit.Test;

/** shutdown in NEW, STARTING, FAILED and TERMINATED. SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-4-7.sds.md. */
public class ShutdownStatesTest {

    private final List<String> events = Collections.synchronizedList(new ArrayList<>());
    private final RecordingLogger logger = new RecordingLogger(events);
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final DefaultRestApp[] appRef = new DefaultRestApp[1];

    @After
    public void tearDown() {
        pool.shutdownNow();
    }

    private DefaultRestApp app(RecordingAdapter adapter) {
        DefaultRestApp app = (DefaultRestApp) RestApp.builder().httpAdapter(adapter).jsonCodec(new StubJsonCodec())
                .logger(logger).build();
        logger.app = app;
        app.onStop(ctx -> events.add("onStop"));
        return app;
    }

    private static void awaitTrue(String what, java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            assertTrue(what, System.nanoTime() < deadline);
            Thread.yield();
        }
    }

    // SDS-AC-15; FR-APP-012 AC-1
    @Test
    public void sdsAc15_FR_APP_012_AC1_shutdownInNewTerminatesWithoutCallbacksOrBindAndClosesTheLoggerOnce() {
        DefaultRestApp app = app(new RecordingAdapter(events));
        app.onStart(ctx -> events.add("onStart"));
        app.shutdown(1, TimeUnit.SECONDS);
        assertEquals(AppState.TERMINATED, app.state());
        assertEquals(Arrays.asList("logger.flush TERMINATED", "logger.close TERMINATED"), events);
        assertEquals(1, logger.closes.get());
    }

    // SDS-AC-16; FR-APP-012 AC-2
    @Test
    public void sdsAc16_FR_APP_012_AC2_shutdownWhileStartingSkipsLaterCallbacksAndBindAndListenThrows()
            throws Exception {
        DefaultRestApp app = app(new RecordingAdapter(events));
        CountDownLatch inOnStart = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        app.onStart(ctx -> {
            ctx.own(new Closer(events, "first", app));
            ctx.own(new Closer(events, "second", app));
            inOnStart.countDown();
            release.await(5, TimeUnit.SECONDS);
            events.add("onStart1 done");
        });
        app.onStart(ctx -> events.add("onStart2"));
        Future<Throwable> listen = pool.submit(() -> {
            try {
                app.listen(0);
                return null;
            } catch (RuntimeException e) {
                events.add("listen threw");
                return e;
            }
        });
        assertTrue(inOnStart.await(5, TimeUnit.SECONDS));
        boolean[] listenEndedFirst = new boolean[1];
        Future<?> shutdown = pool.submit(() -> {
            app.shutdown(5, TimeUnit.SECONDS);
            // FR-APP-012 AC-2: shutdown returns only after listen() ended.
            listenEndedFirst[0] = app.lifecycle().listenEnded();
            events.add("shutdown returned");
        });
        awaitTrue("pending stop recorded", () -> app.lifecycle().pendingStop());
        release.countDown();
        Throwable thrown = listen.get(5, TimeUnit.SECONDS);
        shutdown.get(5, TimeUnit.SECONDS);
        assertTrue("shutdown returned before listen() ended", listenEndedFirst[0]);
        assertTrue(String.valueOf(thrown), thrown instanceof AppStartException);
        assertTrue(thrown.getMessage(), thrown.getMessage().contains("shutdown was requested while starting"));
        assertEquals(Arrays.asList("onStart1 done", "close second FAILED", "close first FAILED",
                "logger.flush FAILED", "logger.close FAILED"), events.subList(0, 5));
        // TERMINATED is listen's last step before it throws, so these two may land in either order.
        assertEquals(7, events.size());
        assertTrue(events.toString(), events.containsAll(Arrays.asList("listen threw", "shutdown returned")));
        assertEquals(AppState.TERMINATED, app.state());
    }

    // SDS-AC-17; FR-APP-012 AC-3
    @Test
    public void sdsAc17_FR_APP_012_AC3_pendingStopDuringBindReleasesTheSocketAndHeldRequestsGet503()
            throws Exception {
        List<String> calls = Collections.synchronizedList(new ArrayList<>());
        List<Future<ClientResponse>> held = new ArrayList<>();
        List<ClientResponse> late = new ArrayList<>();
        List<Future<?>> stopper = new ArrayList<>();
        RecordingAdapter adapter = new RecordingAdapter(events) {
            @Override
            public void bind(String host, int port, ExchangeHandler handler) throws IOException {
                super.bind(host, port, handler);
                held.add(delegate.submit(ClientRequest.of("GET", "/x")));
                awaitTrue("request held", () -> appRef[0].lifecycle().heldOnStartGate() == 1);
                stopper.add(pool.submit(() -> appRef[0].shutdown(5, TimeUnit.SECONDS)));
                awaitTrue("pending stop recorded", () -> appRef[0].lifecycle().pendingStop());
                // A request arriving once the stop is pending is answered at once, not held.
                try {
                    late.add(delegate.submit(ClientRequest.of("GET", "/x")).get(5, TimeUnit.SECONDS));
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
                assertEquals(1, appRef[0].lifecycle().heldOnStartGate());
            }
        };
        DefaultRestApp app = app(adapter);
        appRef[0] = app;
        app.use((req, res, next) -> {
            calls.add("middleware");
            next.run();
        });
        app.get("/x", (req, res) -> calls.add("handler"));
        app.onError((e, req, res) -> calls.add("onError"));
        try {
            app.listen(0);
            fail("listen should throw");
        } catch (AppStartException expected) {
            // ended by the pending stop
        }
        stopper.get(0).get(5, TimeUnit.SECONDS);
        for (ClientResponse r : Arrays.asList(held.get(0).get(5, TimeUnit.SECONDS), late.get(0))) {
            assertEquals(503, r.status());
            assertEquals("close", r.header("Connection"));
            assertTrue(new String(r.body(), StandardCharsets.UTF_8).contains("\"code\":\"SERVICE_UNAVAILABLE\""));
        }
        assertEquals(new ArrayList<String>(), calls);
        assertTrue(events.toString(), events.containsAll(Arrays.asList("adapter.stop", "adapter.release")));
        assertTrue(events.indexOf("adapter.stop") < events.indexOf("adapter.release"));
        assertFalse(events.toString(), events.contains("onStop"));
        assertEquals(AppState.TERMINATED, app.state());
    }

    // SDS-AC-16; FR-APP-012 AC-2
    @Test
    public void sdsAc16_FR_APP_012_AC2_shutdownFromAnOnStartCallbackEndsTheStartWithoutWaitingForItself() {
        DefaultRestApp app = app(new RecordingAdapter(events));
        app.onStart(ctx -> app.shutdown(Long.MAX_VALUE, TimeUnit.NANOSECONDS));
        app.onStart(ctx -> events.add("onStart2"));
        try {
            app.listen(0);
            fail("listen should throw");
        } catch (AppStartException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("shutdown was requested while starting"));
        }
        assertFalse(events.toString(), events.contains("onStart2"));
        assertFalse(events.toString(), events.contains("bind"));
        assertEquals(AppState.TERMINATED, app.state());
    }

    // SDS-AC-11; FR-APP-011 AC-5
    @Test
    public void sdsAc11_FR_APP_011_AC5_shutdownFromAnOnStopCallbackReturnsWithoutWaitingForItself() {
        DefaultRestApp app = app(new RecordingAdapter(events));
        app.onStop(ctx -> app.shutdown(Long.MAX_VALUE, TimeUnit.NANOSECONDS));
        app.listen(0);
        app.shutdown(5, TimeUnit.SECONDS);
        assertEquals(AppState.TERMINATED, app.state());
    }

    // FR-APP-011 AC-5 — shutdown() from a route handler neither waits for its own request nor cuts its response
    @Test
    public void FR_APP_011_AC5_shutdownFromARouteHandlerReturnsPromptlyAndTheResponseIsDelivered() throws Exception {
        RecordingAdapter adapter = new RecordingAdapter(events);
        DefaultRestApp app = app(adapter);
        long[] took = new long[1];
        app.get("/stop", (req, res) -> {
            long t0 = System.nanoTime();
            app.shutdown(5, TimeUnit.SECONDS);
            took[0] = System.nanoTime() - t0;
            res.send("bye");
        });
        app.listen(0);
        ClientResponse r = adapter.delegate.submit(ClientRequest.of("GET", "/stop")).get(10, TimeUnit.SECONDS);
        assertEquals(200, r.status());
        assertEquals("bye", new String(r.body(), StandardCharsets.UTF_8));
        assertFalse(r.abnormallyClosed());
        assertTrue("shutdown took " + took[0] + " ns", took[0] < TimeUnit.SECONDS.toNanos(2));
        assertTrue("app reaches TERMINATED", app.lifecycle().awaitTerminated(5, TimeUnit.SECONDS));
        assertTrue(events.toString(), events.contains("onStop"));
    }

    // FR-APP-011 AC-5 — listen()'s exit never clears the shutdown thread's identity, so an onStop shutdown() that
    // races listen() returning still does not wait for itself. The window is narrow, so it is tried repeatedly.
    @Test
    public void FR_APP_011_AC5_shutdownRacingListenReturnStillDoesNotWaitForItself() throws Exception {
        for (int i = 0; i < 200; i++) {
            DefaultRestApp app = app(new RecordingAdapter(new ArrayList<>()));
            long[] inner = new long[1];
            app.onStop(ctx -> {
                long t0 = System.nanoTime();
                app.shutdown(2, TimeUnit.SECONDS);
                inner[0] = System.nanoTime() - t0;
            });
            Future<?> stopper = pool.submit(() -> {
                while (app.state() != AppState.RUNNING) {
                    Thread.yield();
                }
                app.shutdown(5, TimeUnit.SECONDS);
            });
            app.listen(0);
            stopper.get(10, TimeUnit.SECONDS);
            assertTrue("iteration " + i + ": onStop shutdown waited " + inner[0] + " ns",
                    inner[0] < TimeUnit.SECONDS.toNanos(1));
        }
    }

    // SDS-AC-18; FR-APP-012 AC-4
    @Test
    public void sdsAc18_FR_APP_012_AC4_shutdownTimeoutWhileStartingReturnsNormally() throws Exception {
        DefaultRestApp app = app(new RecordingAdapter(events));
        CountDownLatch inOnStart = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        app.onStart(ctx -> {
            inOnStart.countDown();
            release.await(5, TimeUnit.SECONDS);
        });
        Future<Throwable> listen = pool.submit(() -> {
            try {
                app.listen(0);
                return null;
            } catch (RuntimeException e) {
                return e;
            }
        });
        assertTrue(inOnStart.await(5, TimeUnit.SECONDS));
        long t0 = System.nanoTime();
        app.shutdown(100, TimeUnit.MILLISECONDS);
        assertTrue(System.nanoTime() - t0 >= TimeUnit.MILLISECONDS.toNanos(90));
        AppState s = app.state();
        assertTrue(String.valueOf(s), s == AppState.STARTING || s == AppState.FAILED);
        release.countDown();
        assertTrue(listen.get(5, TimeUnit.SECONDS) instanceof AppStartException);
        assertEquals(AppState.TERMINATED, app.state());
    }

    // SDS-AC-19; FR-APP-012 AC-5
    @Test
    public void sdsAc19_FR_APP_012_AC5_shutdownInFailedWaitsForTerminatedAndInTerminatedReturnsAtOnce()
            throws Exception {
        DefaultRestApp app = app(new RecordingAdapter(events));
        List<Future<Long>> b = new ArrayList<>();
        app.onStart(ctx -> {
            ctx.own(() -> {
                b.add(pool.submit(() -> {
                    long t0 = System.nanoTime();
                    app.shutdown(5, TimeUnit.SECONDS);
                    events.add("B returned " + app.state());
                    return System.nanoTime() - t0;
                }));
                Thread.sleep(100);
            });
            throw new IllegalStateException("start broke");
        });
        try {
            app.listen(0);
            fail("listen should throw");
        } catch (IllegalStateException expected) {
            // the start failure
        }
        long waited = b.get(0).get(5, TimeUnit.SECONDS);
        assertTrue(events.toString(), events.contains("B returned TERMINATED"));
        assertTrue("B waited " + waited, waited < TimeUnit.SECONDS.toNanos(5));
        events.clear();
        long t0 = System.nanoTime();
        app.shutdown(5, TimeUnit.SECONDS);
        assertTrue(System.nanoTime() - t0 < TimeUnit.SECONDS.toNanos(1));
        assertEquals(new ArrayList<String>(), events);
    }

    // SDS-AC-20; FR-APP-012 AC-6
    @Test
    public void sdsAc20_FR_APP_012_AC6_listenOnATerminatedAppThrowsIllegalState() {
        DefaultRestApp app = app(new RecordingAdapter(events));
        app.shutdown(1, TimeUnit.SECONDS);
        try {
            app.listen(0);
            fail("listen on TERMINATED");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("TERMINATED"));
        }
    }
}
