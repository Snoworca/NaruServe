package com.snoworca.naruserve.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.AppState;
import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.routing.RoutingConfigException;
import com.snoworca.naruserve.ServerHandle;
import com.snoworca.naruserve.spi.ExchangeHandler;
import com.snoworca.naruserve.spi.ServerSettings;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.testing.SettingsCapturingAdapter;
import com.snoworca.naruserve.testing.StubJsonCodec;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/**
 * The behavior of {@code RestApp.start} (FR-DX-012, FR-DX-014) over an in-memory adapter and a recording hook
 * registry, so no real shutdown hook is ever registered by these tests.
 */
public class AppStarterTest {

    /** Records the order of binds and hook registrations. */
    static final class Adapter extends SettingsCapturingAdapter {
        final List<String> events;

        Adapter(List<String> events) {
            this.events = events;
        }

        @Override
        public void bind(ServerSettings s, ExchangeHandler h) {
            events.add("bind");
            super.bind(s, h);
        }
    }

    /** A registry that remembers what is registered and the order of its calls. */
    static final class Hooks implements AppStarter.Hooks {
        final List<Thread> registered = Collections.synchronizedList(new ArrayList<Thread>());
        final List<String> events;
        int removes;

        Hooks(List<String> events) {
            this.events = events;
        }

        @Override
        public void add(Thread hook) {
            events.add("hook");
            registered.add(hook);
        }

        @Override
        public void remove(Thread hook) {
            removes++;
            registered.remove(hook);
        }
    }

    private final List<String> events = Collections.synchronizedList(new ArrayList<String>());
    private final Hooks hooks = new Hooks(events);
    private final RecordingLogger log = new RecordingLogger();
    private final Adapter adapter = new Adapter(events);

    private RestApp app() {
        return RestApp.builder().httpAdapter(adapter).jsonCodec(new StubJsonCodec()).logger(log).build();
    }

    private static final ClassLoader JVM = AppStarterTest.class.getClassLoader();

    /** A loader that defines an empty {@code android.util.Log}, as an Android class path exposes one. */
    private static ClassLoader androidLike() {
        return new ClassLoader(JVM) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                if (!name.equals("android.util.Log")) {
                    throw new ClassNotFoundException(name);
                }
                try {
                    java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                    java.io.DataOutputStream out = new java.io.DataOutputStream(bytes);
                    out.writeInt(0xCAFEBABE);
                    out.writeShort(0);
                    out.writeShort(52);
                    out.writeShort(5); // constant pool count
                    out.writeByte(1);
                    out.writeUTF("android/util/Log");
                    out.writeByte(7);
                    out.writeShort(1);
                    out.writeByte(1);
                    out.writeUTF("java/lang/Object");
                    out.writeByte(7);
                    out.writeShort(3);
                    out.writeShort(0x21); // public super
                    out.writeShort(2); // this
                    out.writeShort(4); // super
                    out.writeShort(0); // interfaces
                    out.writeShort(0); // fields
                    out.writeShort(0); // methods
                    out.writeShort(0); // attributes
                    byte[] b = bytes.toByteArray();
                    return defineClass(name, b, 0, b.length);
                } catch (IOException e) {
                    throw new AssertionError(e);
                }
            }
        };
    }

    // FR-DX-012 AC-2
    @Test
    public void FR_DX_012_AC2_createdAppGoesToTheLambdaInNewThenListenReturnsItsHandle() {
        RestApp app = app();
        List<AppState> inLambda = new ArrayList<>();
        ServerHandle h = AppStarter.start(app, 0, a -> {
            events.add("lambda");
            inLambda.add(a.state());
            assertSame(app, a);
            a.get("/x", (req, res) -> res.send("x"));
            a.use((req, res, next) -> next.run());
            a.onStart(ctx -> events.add("onStart"));
            a.onStop(ctx -> events.add("onStop"));
            a.trustProxy(false);
            a.server(o -> o.maxHeaderCount(50));
        }, JVM, hooks);
        assertEquals(Arrays.asList(AppState.NEW), inLambda);
        assertEquals(Arrays.asList("lambda", "onStart", "bind", "hook"), events);
        assertEquals(AppState.RUNNING, h.state());
        assertEquals(8080, h.port());
        assertEquals(50, adapter.settings.maxHeaderCount());
        h.shutdown(1, TimeUnit.SECONDS);
    }

    // FR-DX-012 AC-5
    @Test
    public void FR_DX_012_AC5_defaultBindIsLoopbackAndTheLambdaIsTheOnlyWayToOpenTheLan() {
        ServerHandle h = AppStarter.start(app(), 0, a -> { }, JVM, hooks);
        assertEquals("127.0.0.1", h.address());
        assertEquals(0, log.count("WARN"));
        h.shutdown(1, TimeUnit.SECONDS);

        RecordingLogger lanLog = new RecordingLogger();
        RestApp lan = RestApp.builder().httpAdapter(new SettingsCapturingAdapter()).jsonCodec(new StubJsonCodec())
                .logger(lanLog).build();
        ServerHandle h2 = AppStarter.start(lan, 0, a -> a.server(o -> o.bindAddress("0.0.0.0")), JVM, hooks);
        assertEquals("0.0.0.0", h2.address());

        RecordingLogger listenLog = new RecordingLogger();
        RestApp viaListen = RestApp.builder().httpAdapter(new SettingsCapturingAdapter())
                .jsonCodec(new StubJsonCodec()).logger(listenLog).build()
                .server(o -> o.bindAddress("0.0.0.0"));
        viaListen.listen(0);
        assertEquals(1, listenLog.count("WARN"));
        assertEquals("the same one warning as listen", 1, lanLog.count("WARN"));
        assertEquals(listenLog.lines.get(0), lanLog.lines.get(0));
        h2.shutdown(1, TimeUnit.SECONDS);
        viaListen.shutdown(1, TimeUnit.SECONDS);
    }

    // FR-DX-012 AC-5, AC-6: listen's own exceptions pass through, and the app ends TERMINATED
    @Test
    public void FR_DX_012_AC5_AC6_portRangeErrorsArePassedThroughAndTheAppIsTerminated() {
        for (int port : new int[] {-1, 65536}) {
            RestApp app = app();
            try {
                AppStarter.start(app, port, a -> { }, JVM, hooks);
                fail("port " + port + " accepted");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains(String.valueOf(port)));
            }
            assertEquals(AppState.TERMINATED, app.state());
        }
        assertEquals(0, hooks.registered.size());
        assertFalse(events.contains("bind"));
    }

    // FR-DX-012 AC-5
    @Test
    public void FR_DX_012_AC5_routeConflictAndBindFailureKeepTheirTypes() {
        RestApp conflict = app();
        try {
            AppStarter.start(conflict, 0, a -> {
                a.get("/x", (req, res) -> { });
                a.get("/x", (req, res) -> { });
            }, JVM, hooks);
            fail("duplicate route accepted");
        } catch (RoutingConfigException expected) {
            assertEquals(RoutingConfigException.class, expected.getClass());
        }
        assertEquals(AppState.TERMINATED, conflict.state());

        IllegalStateException boom = new IllegalStateException("boom");
        RestApp failing = RestApp.builder().httpAdapter(new SettingsCapturingAdapter() {
            @Override
            public void bind(ServerSettings s, ExchangeHandler h) {
                throw boom;
            }
        }).jsonCodec(new StubJsonCodec()).logger(log).build();
        try {
            AppStarter.start(failing, 0, a -> { }, JVM, hooks);
            fail("bind failure swallowed");
        } catch (IllegalStateException e) {
            assertSame(boom, e);
        }
        assertEquals(AppState.TERMINATED, failing.state());
        assertEquals(0, hooks.registered.size());
        assertEquals(0, log.info().size());

        RestApp ioFailing = RestApp.builder().httpAdapter(new com.snoworca.naruserve.spi.HttpAdapter() {
            @Override
            public void bind(String host, int port, ExchangeHandler h) throws IOException {
                throw new IOException("in use");
            }

            @Override
            public int boundPort() {
                throw new IllegalStateException("not bound");
            }

            @Override
            public void stop(long timeout, TimeUnit unit) {
            }

            @Override
            public void release() {
            }
        }).jsonCodec(new StubJsonCodec()).logger(log).build();
        try {
            AppStarter.start(ioFailing, 0, a -> { }, JVM, hooks);
            fail("checked bind failure swallowed");
        } catch (com.snoworca.naruserve.AppStartException e) {
            assertEquals("in use", e.getCause().getMessage());
        }
        assertEquals(0, hooks.registered.size());
    }

    // FR-DX-012 AC-6
    @Test
    public void FR_DX_012_AC6_lambdaFailureTerminatesTheAppAndRethrowsTheSameException() {
        RestApp app = app();
        IllegalStateException failure = new IllegalStateException("config failed");
        try {
            AppStarter.start(app, 0, a -> {
                a.get("/x", (req, res) -> { });
                throw failure;
            }, JVM, hooks);
            fail("lambda failure swallowed");
        } catch (IllegalStateException e) {
            assertSame(failure, e);
        }
        assertEquals(AppState.TERMINATED, app.state());
        assertEquals(0, adapter.binds.size());
        assertEquals(0, hooks.registered.size());
        assertEquals(0, log.info().size());
        // the logger was flushed and closed: the app can be shut down again without effect
        app.shutdown(1, TimeUnit.SECONDS);
    }

    // FR-DX-014 AC-1
    @Test
    public void FR_DX_014_AC1_oneStartLineForLoopbackWithTheJvmHint() {
        ServerHandle h = AppStarter.start(app(), 0, a -> { }, JVM, hooks);
        assertEquals(Arrays.asList("Listening on http://127.0.0.1:8080 (this device only). Press Ctrl+C to stop."),
                log.info());
        assertEquals("only the start line is logged", 1, log.lines.size());
        h.shutdown(1, TimeUnit.SECONDS);
    }

    // FR-DX-014 AC-1
    @Test
    public void FR_DX_014_AC1_wildcardAndOtherAddressLines() {
        RecordingLogger wildLog = new RecordingLogger();
        RestApp wild = RestApp.builder().httpAdapter(new SettingsCapturingAdapter()).jsonCodec(new StubJsonCodec())
                .logger(wildLog).build();
        AppStarter.start(wild, 0, a -> a.server(o -> o.bindAddress("0.0.0.0")), JVM, hooks);
        assertEquals(Arrays.asList("Listening on http://0.0.0.0:8080 - open http://localhost:8080 or"
                + " http://<this-device-ip>:8080. Press Ctrl+C to stop."), wildLog.info());
        wild.shutdown(1, TimeUnit.SECONDS);

        RecordingLogger otherLog = new RecordingLogger();
        RestApp other = RestApp.builder().httpAdapter(new SettingsCapturingAdapter()).jsonCodec(new StubJsonCodec())
                .logger(otherLog).build();
        AppStarter.start(other, 0, a -> a.server(o -> o.bindAddress("10.0.0.5")), JVM, hooks);
        assertEquals(Arrays.asList("Listening on http://10.0.0.5:8080. Press Ctrl+C to stop."), otherLog.info());
        other.shutdown(1, TimeUnit.SECONDS);
    }

    // FR-DX-014 AC-1, AC-4: on Android the line has no Ctrl+C text and no hook is registered
    @Test
    public void FR_DX_014_AC1_AC4_androidGetsNeitherTheCtrlCHintNorAHook() {
        ServerHandle h = AppStarter.start(app(), 0, a -> { }, androidLike(), hooks);
        assertEquals(Arrays.asList("Listening on http://127.0.0.1:8080 (this device only)"), log.info());
        assertEquals(0, hooks.registered.size());
        assertFalse(events.contains("hook"));
        h.shutdown(1, TimeUnit.SECONDS);
    }

    // FR-DX-014 AC-4
    @Test
    public void FR_DX_014_AC4_androidIsDetectedOnlyByReflectionOnAndroidUtilLog() {
        assertFalse(AppStarter.isAndroid(JVM));
        assertTrue(AppStarter.isAndroid(androidLike()));
        assertFalse(AppStarter.isAndroid(null));
    }

    // FR-DX-014 AC-2
    @Test
    public void FR_DX_014_AC2_listenAloneLogsNoStartLineAndRegistersNoHook() {
        RestApp app = app();
        app.get("/x", (req, res) -> { });
        ServerHandle h = app.listen(0);
        assertEquals(0, log.lines.size());
        assertEquals(0, hooks.registered.size());
        assertEquals(0, hooks.removes);
        h.shutdown(1, TimeUnit.SECONDS);
    }

    // FR-DX-014 AC-3
    @Test
    public void FR_DX_014_AC3_oneHookIsRegisteredAfterListenAndStopsTheAppWithAStoppingLine() {
        AtomicInteger stops = new AtomicInteger();
        RestApp app = app();
        ServerHandle h = AppStarter.start(app, 0, a -> a.onStop(ctx -> stops.incrementAndGet()), JVM, hooks);
        assertEquals(Arrays.asList("bind", "hook"), events);
        assertEquals(1, hooks.registered.size());
        Thread hook = hooks.registered.get(0);
        assertFalse("not started by the registry", hook.isAlive());

        hook.run();
        assertEquals(AppState.TERMINATED, h.state());
        assertEquals(1, stops.get());
        List<String> stopping = new ArrayList<>();
        for (String l : log.info()) {
            if (l.startsWith("Stopping")) {
                stopping.add(l);
            }
        }
        assertEquals(Arrays.asList("Stopping (grace 5s)"), stopping);
        assertEquals("the hook removed itself", 0, hooks.registered.size());
    }

    // FR-DX-014 AC-6
    @Test
    public void FR_DX_014_AC6_aHookOfATerminatedAppDoesNothingAndRemovalIsRepeatable() {
        RestApp app = app();
        ServerHandle h = AppStarter.start(app, 0, a -> { }, JVM, hooks);
        Thread hook = hooks.registered.get(0);
        h.shutdown(1, TimeUnit.SECONDS);
        int infoLines = log.info().size();
        hook.run();
        hook.run();
        assertEquals(infoLines, log.info().size());
        assertEquals(AppState.TERMINATED, app.state());
        hooks.remove(hook);
        hooks.remove(hook);
        assertEquals(0, hooks.registered.size());
    }

    // FR-DX-014 AC-5
    @Test
    public void FR_DX_014_AC5_theHookIsRemovedAfterHandleShutdownAndAfterAppShutdown() {
        RestApp viaHandle = app();
        ServerHandle h = AppStarter.start(viaHandle, 0, a -> { }, JVM, hooks);
        assertEquals(1, hooks.registered.size());
        h.shutdown(1, TimeUnit.SECONDS);
        assertEquals(0, hooks.registered.size());

        RestApp[] captured = new RestApp[1];
        AppStarter.start(app2(), 0, a -> captured[0] = a, JVM, hooks);
        assertEquals(1, hooks.registered.size());
        captured[0].shutdown(1, TimeUnit.SECONDS);
        assertEquals("removed after RestApp.shutdown on the captured app", 0, hooks.registered.size());
    }

    private RestApp app2() {
        return RestApp.builder().httpAdapter(new SettingsCapturingAdapter()).jsonCodec(new StubJsonCodec())
                .logger(new RecordingLogger()).build();
    }

    // FR-DX-014 AC-5: removal that throws while the JVM is shutting down is swallowed
    @Test
    public void FR_DX_014_AC5_removalDuringJvmShutdownIsSwallowed() {
        AppStarter.Hooks refusing = new AppStarter.Hooks() {
            @Override
            public void add(Thread hook) {
            }

            @Override
            public void remove(Thread hook) {
                throw new IllegalStateException("Shutdown in progress");
            }
        };
        RestApp app = app();
        ServerHandle h = AppStarter.start(app, 0, a -> { }, JVM, refusing);
        h.shutdown(1, TimeUnit.SECONDS);
        assertEquals(AppState.TERMINATED, app.state());
        assertEquals("no ERROR log from the failed removal", 0, log.count("ERROR"));
    }

    // FR-DX-014 AC-7: a hook registry that refuses the hook (JVM already shutting down) ends the bound app and the
    // caller gets the original exception, not a running server without a handle
    @Test
    public void FR_DX_014_AC7_refusedHookRegistrationEndsTheAppAndRethrows() {
        IllegalStateException refusal = new IllegalStateException("Shutdown in progress");
        AppStarter.Hooks refusing = new AppStarter.Hooks() {
            @Override
            public void add(Thread hook) {
                throw refusal;
            }

            @Override
            public void remove(Thread hook) {
            }
        };
        RestApp app = app();
        try {
            AppStarter.start(app, 0, a -> { }, JVM, refusing);
            fail("start must rethrow");
        } catch (IllegalStateException e) {
            assertSame(refusal, e);
        }
        assertEquals(AppState.TERMINATED, app.state());
    }

    // FR-DX-013 AC-2: the URL of an IPv6 address with a zone id escapes the percent sign; ::0 is a wildcard too
    @Test
    public void FR_DX_013_AC2_zoneIdIsEscapedAndColonColonZeroIsAWildcard() {
        assertEquals("http://[fe80::1%25en0]:3000", ServerUrls.of("fe80::1%en0", 3000));
        assertTrue(ServerUrls.isWildcard("::0"));
        assertEquals("http://localhost:3000", ServerUrls.of("::0", 3000));
        assertEquals("http://[::1]:3000", ServerUrls.of("::1", 3000));
    }

    // FR-DX-014 AC-5: ten cycles leave nothing behind
    @Test
    public void FR_DX_014_AC5_tenStartShutdownCyclesLeaveZeroHooks() {
        for (int i = 0; i < 10; i++) {
            ServerHandle h = AppStarter.start(app2(), 0, a -> { }, JVM, hooks);
            assertEquals(1, hooks.registered.size());
            h.shutdown(1, TimeUnit.SECONDS);
        }
        assertEquals(0, hooks.registered.size());
        // and the failing paths never register one
        for (int i = 0; i < 3; i++) {
            try {
                AppStarter.start(app2(), 0, a -> {
                    throw new IllegalStateException("x");
                }, JVM, hooks);
            } catch (IllegalStateException expected) {
                // by design
            }
        }
        assertEquals(0, hooks.registered.size());
    }
}
