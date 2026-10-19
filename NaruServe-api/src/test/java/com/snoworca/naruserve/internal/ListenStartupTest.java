package com.snoworca.naruserve.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.AppState;
import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.ServerHandle;
import com.snoworca.naruserve.routing.RoutingConfigException;
import com.snoworca.naruserve.spi.ExchangeHandler;
import com.snoworca.naruserve.spi.HttpAdapter;
import com.snoworca.naruserve.testing.StubJsonCodec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/** listen() startup sequence. SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-4-3.sds.md. */
public class ListenStartupTest {

    /** Records bind and boundPort calls with the app state seen at bind. */
    static final class RecordingAdapter implements HttpAdapter {
        final List<String> events = Collections.synchronizedList(new ArrayList<>());
        volatile RestApp app;
        volatile boolean bound;
        final int port;

        RecordingAdapter(int port) {
            this.port = port;
        }

        @Override
        public void bind(String host, int port, ExchangeHandler handler) {
            String frozen;
            try {
                ((DefaultRestApp) app).dispatcher();
                frozen = "frozen";
            } catch (IllegalStateException e) {
                frozen = "unfrozen";
            }
            events.add("bind " + app.state() + " " + frozen);
            bound = true;
        }

        @Override
        public int boundPort() {
            if (!bound) {
                throw new IllegalStateException("not bound");
            }
            return port;
        }

        @Override
        public void stop(long timeout, TimeUnit unit) {
        }

        @Override
        public void release() {
        }
    }

    private static RestApp app(RecordingAdapter adapter) {
        RestApp app = RestApp.builder().httpAdapter(adapter).jsonCodec(new StubJsonCodec()).build();
        adapter.app = app;
        return app;
    }

    // SDS-AC-6; FR-APP-009 AC-1
    @Test
    public void sdsAc6_FR_APP_009_AC1_concurrentListenLetsOnlyTheCompareAndSetWinnerProceed() throws Exception {
        for (int round = 0; round < 20; round++) {
            RecordingAdapter adapter = new RecordingAdapter(8080);
            RestApp app = app(adapter);
            CountDownLatch go = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                List<Future<Object>> results = new ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    results.add(pool.submit(() -> {
                        go.await();
                        try {
                            return app.listen(0);
                        } catch (IllegalStateException e) {
                            return e;
                        }
                    }));
                }
                go.countDown();
                int handles = 0;
                int refused = 0;
                for (Future<Object> f : results) {
                    Object r = f.get(5, TimeUnit.SECONDS);
                    if (r instanceof ServerHandle) {
                        handles++;
                    } else if (r instanceof IllegalStateException) {
                        refused++;
                    }
                }
                assertEquals(1, handles);
                assertEquals(1, refused);
                assertEquals(Arrays.asList("bind STARTING frozen"), adapter.events);
            } finally {
                pool.shutdownNow();
            }
            try {
                app.listen(0);
                fail("listen after a successful listen");
            } catch (IllegalStateException e) {
                assertTrue(e.getMessage(), e.getMessage().contains("RUNNING"));
            }
        }
    }

    // SDS-AC-7; FR-APP-009 AC-2
    @Test
    public void sdsAc7_FR_APP_009_AC2_listenRunsStartingCallbacksFreezeOneBindRunningInOrder() {
        RecordingAdapter adapter = new RecordingAdapter(8081);
        DefaultRestApp app = (DefaultRestApp) app(adapter);
        app.get("/x", (req, res) -> { });
        app.onStart(ctx -> {
            adapter.events.add("cb1 " + app.state());
            try {
                app.dispatcher();
                adapter.events.add("frozen too early");
            } catch (IllegalStateException expected) {
                // not frozen yet
            }
        });
        app.onStart(ctx -> adapter.events.add("cb2 " + app.state()));
        ServerHandle h = app.listen(0);
        adapter.events.add("returned " + app.state());
        assertEquals(Arrays.asList("cb1 STARTING", "cb2 STARTING", "bind STARTING frozen", "returned RUNNING"),
                adapter.events);
        // frozen by listen
        app.dispatcher();
        assertEquals(AppState.RUNNING, h.state());
    }

    // SDS-AC-8; FR-APP-009 AC-3
    @Test
    public void sdsAc8_FR_APP_009_AC3_duplicateRouteThrowsTheFreezeExceptionAfterOnStartAndBeforeBind() {
        RecordingAdapter adapter = new RecordingAdapter(8082);
        RestApp app = app(adapter);
        app.get("/x", (req, res) -> { });
        app.get("/x", (req, res) -> { });
        app.onStart(ctx -> adapter.events.add("cb"));
        try {
            app.listen(0);
            fail("duplicate GET /x accepted");
        } catch (RoutingConfigException e) {
            assertEquals(RoutingConfigException.class, e.getClass());
            assertNull(e.getCause());
        }
        assertEquals(Arrays.asList("cb"), adapter.events);
    }

    // SDS-AC-9; FR-APP-009 AC-4
    @Test
    public void sdsAc9_FR_APP_009_AC4_handlePortIsTheBoundPortAndListenReturnsAfterBind() {
        RecordingAdapter adapter = new RecordingAdapter(8123);
        RestApp app = app(adapter);
        ServerHandle h = app.listen(0);
        assertTrue(adapter.bound);
        assertEquals(8123, h.port());
        assertEquals(AppState.RUNNING, h.state());
    }

    /** Records every bind(ServerSettings, ...) call; warns through settings.logger() when asked. */
    static final class SettingsRecordingAdapter implements HttpAdapter {
        final List<com.snoworca.naruserve.spi.ServerSettings> settings =
                Collections.synchronizedList(new ArrayList<>());
        volatile boolean useLogger;
        volatile boolean bound;

        @Override
        public void bind(com.snoworca.naruserve.spi.ServerSettings s, ExchangeHandler handler) {
            settings.add(s);
            if (useLogger) {
                s.logger().warn("adapter warning");
                s.logger().error("adapter error", new RuntimeException("x"));
                s.logger().info("adapter info");
            }
            bound = true;
        }

        @Override
        public void bind(String host, int port, ExchangeHandler handler) {
            throw new AssertionError("listen must use bind(ServerSettings, ExchangeHandler)");
        }

        @Override
        public int boundPort() {
            return 8080;
        }

        @Override
        public void stop(long timeout, TimeUnit unit) {
        }

        @Override
        public void release() {
        }
    }

    private static RestApp settingsApp(SettingsRecordingAdapter adapter, com.snoworca.naruserve.Logger logger) {
        return RestApp.builder().httpAdapter(adapter).jsonCodec(new StubJsonCodec()).logger(logger).build();
    }

    // SDS-AC-5 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-5-4.sds.md; IR-ENGINE-002 AC-2, FR-ENGINE-002 AC-2
    @Test
    public void sdsAc5_IR_ENGINE_002_AC2_listenBindsOnceWithTheFrozenSettings() {
        SettingsRecordingAdapter loopback = new SettingsRecordingAdapter();
        RestApp first = settingsApp(loopback, new com.snoworca.naruserve.testing.RecordingLogger());
        ServerHandle h1 = first.listen(8080);
        assertEquals(1, loopback.settings.size());
        assertEquals("127.0.0.1", loopback.settings.get(0).bindAddress());
        assertEquals(8080, loopback.settings.get(0).port());
        assertEquals("127.0.0.1", h1.address());
        first.shutdown(1, TimeUnit.SECONDS);

        SettingsRecordingAdapter lan = new SettingsRecordingAdapter();
        RestApp second = settingsApp(lan, new com.snoworca.naruserve.testing.RecordingLogger())
                .server(o -> o.bindAddress("0.0.0.0").maxHeaderSize(32768));
        ServerHandle h2 = second.listen(9090);
        assertEquals(1, lan.settings.size());
        assertEquals("0.0.0.0", lan.settings.get(0).bindAddress());
        assertEquals(9090, lan.settings.get(0).port());
        assertEquals(32768, lan.settings.get(0).maxHeaderSize());
        assertEquals("0.0.0.0", h2.address());
        second.shutdown(1, TimeUnit.SECONDS);
    }

    // SDS-AC-6 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-5-4.sds.md; IR-ENGINE-002 AC-4
    @Test
    public void sdsAc6_IR_ENGINE_002_AC4_aThrowingAppLoggerNeverReachesTheAdapterThroughSettingsLogger() {
        SettingsRecordingAdapter adapter = new SettingsRecordingAdapter();
        adapter.useLogger = true;
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        com.snoworca.naruserve.testing.RecordingLogger throwing = new com.snoworca.naruserve.testing.RecordingLogger() {
            @Override
            public void warn(String message) {
                calls.incrementAndGet();
                throw new IllegalStateException("logger down");
            }

            @Override
            public void info(String message) {
                calls.incrementAndGet();
                throw new IllegalStateException("logger down");
            }

            @Override
            public void error(String message, Throwable error) {
                calls.incrementAndGet();
                throw new IllegalStateException("logger down");
            }
        };
        RestApp app = settingsApp(adapter, throwing);
        app.listen(8080);
        assertEquals(AppState.RUNNING, app.state());
        assertEquals(3, calls.get());
        app.shutdown(1, TimeUnit.SECONDS);
    }

    private static int lanWarnings(String bindAddress) {
        return lanWarnings(bindAddress, true);
    }

    private static int lanWarnings(String bindAddress, boolean withMiddleware) {
        com.snoworca.naruserve.testing.RecordingLogger log = new com.snoworca.naruserve.testing.RecordingLogger();
        RestApp app = settingsApp(new SettingsRecordingAdapter(), log);
        if (bindAddress != null) {
            app.server(o -> o.bindAddress(bindAddress));
        }
        if (withMiddleware) {
            app.use((req, res, next) -> next.run());
        }
        app.listen(8080);
        app.shutdown(1, TimeUnit.SECONDS);
        int n = 0;
        for (String line : new ArrayList<>(log.lines)) {
            if (line.startsWith("WARN ")) {
                assertTrue(line, line.contains(bindAddress == null ? "127.0.0.1" : bindAddress));
                assertTrue(line, line.contains("authentication"));
                n++;
            }
        }
        return n;
    }

    // SDS-AC-10 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-5-4.sds.md; FR-ENGINE-002 AC-3
    @Test
    public void sdsAc10_FR_ENGINE_002_AC3_oneWarningForANonLoopbackBindAndNoneForLoopback() {
        assertEquals(1, lanWarnings("0.0.0.0"));
        assertEquals(1, lanWarnings("0.0.0.0", false));
        assertEquals(1, lanWarnings("192.168.1.20"));
        assertEquals(1, lanWarnings("myphone.local"));
        assertEquals(0, lanWarnings(null));
        assertEquals(0, lanWarnings("127.0.0.1"));
        assertEquals(0, lanWarnings("127.5.6.7"));
        assertEquals(0, lanWarnings("localhost"));
        assertEquals(0, lanWarnings("::1"));
    }
}
