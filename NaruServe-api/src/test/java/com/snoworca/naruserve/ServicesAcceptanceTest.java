package com.snoworca.naruserve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.internal.DefaultRestApp;
import com.snoworca.naruserve.spi.ExchangeHandler;
import com.snoworca.naruserve.spi.HttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.StubJsonCodec;
import com.snoworca.naruserve.testing.TestApp;
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
import org.junit.Test;

/** T41, T42 and T45: services and owned resources. SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-4-6.sds.md. */
public class ServicesAcceptanceTest {

    static final class UserService {
    }

    static final class Other {
    }

    static final class RecordingLogger implements Logger {
        final List<String> lines = Collections.synchronizedList(new ArrayList<String>());

        @Override
        public void trace(String message) {
            lines.add("TRACE " + message);
        }

        @Override
        public void debug(String message) {
            lines.add("DEBUG " + message);
        }

        @Override
        public void info(String message) {
            lines.add("INFO " + message);
        }

        @Override
        public void warn(String message) {
            lines.add("WARN " + message);
        }

        @Override
        public void error(String message) {
            lines.add("ERROR " + message);
        }

        @Override
        public void error(String message, Throwable error) {
            lines.add("ERROR " + message + " " + error.getMessage());
        }
    }

    private final List<String> closed = Collections.synchronizedList(new ArrayList<String>());

    private AutoCloseable resource(String name) {
        return () -> closed.add(name);
    }

    private static String body(ClientResponse r) {
        return new String(r.body(), StandardCharsets.UTF_8);
    }

    // SDS-AC-1; FR-APP-007 AC-1
    @Test
    public void sdsAc1_FR_APP_007_AC1_T41_serviceFromOnStartIsIdenticalViaServiceAndApplicationGet() {
        TestApp t = TestApp.create();
        RestApp app = t.app();
        UserService users = new UserService();
        app.onStart(ctx -> ctx.register(UserService.class, users));
        app.get("/u", (req, res) -> {
            UserService a = req.service(UserService.class);
            UserService b = req.application().get(UserService.class);
            res.send(String.valueOf(a == users && b == users));
        });
        assertEquals("true", body(t.send("GET", "/u")));
        app.shutdown(1, TimeUnit.SECONDS);
    }

    // SDS-AC-4; FR-APP-007 AC-3
    @Test
    public void sdsAc4_FR_APP_007_AC3_T42_duplicateInOnStartFailsListenAndTheCloseHookSeesTheFirst() {
        TestApp t = TestApp.create();
        RestApp app = t.app();
        UserService first = new UserService();
        List<Object> seen = Collections.synchronizedList(new ArrayList<Object>());
        app.onStart(ctx -> {
            ctx.own(() -> seen.add(String.valueOf(ctx.getOrNull(UserService.class) == first)));
            ctx.register(UserService.class, first);
            ctx.register(UserService.class, new UserService());
        });
        try {
            t.start();
            fail("the duplicate register fails listen");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(UserService.class.getName()));
        }
        assertEquals(Collections.<Object>singletonList("true"), seen);
    }

    // SDS-AC-5; FR-APP-007 AC-4
    @Test
    public void sdsAc5_FR_APP_007_AC4_sixteenThreadsGetTheServiceAndTheSnapshotIsPublishedBeforeRunning()
            throws Exception {
        List<String> duringBind = Collections.synchronizedList(new ArrayList<String>());
        DefaultRestApp[] holder = new DefaultRestApp[1];
        InMemoryHttpAdapter delegate = new InMemoryHttpAdapter();
        HttpAdapter adapter = new HttpAdapter() {
            @Override
            public void bind(String host, int port, ExchangeHandler handler) {
                delegate.bind(host, port, handler);
                // Still STARTING here: the registry must already be frozen.
                try {
                    holder[0].context().register(Other.class, new Other());
                    duringBind.add("registered");
                } catch (IllegalStateException e) {
                    duringBind.add("refused " + holder[0].state());
                }
            }

            @Override
            public int boundPort() {
                return delegate.boundPort();
            }

            @Override
            public void stop(long timeout, TimeUnit unit) {
                delegate.stop(timeout, unit);
            }

            @Override
            public void release() {
                delegate.release();
            }
        };
        RestApp app = RestApp.builder().httpAdapter(adapter).jsonCodec(new StubJsonCodec()).build();
        holder[0] = (DefaultRestApp) app;
        UserService users = new UserService();
        app.onStart(ctx -> ctx.register(UserService.class, users));
        app.get("/u", (req, res) -> res.send(String.valueOf(req.service(UserService.class) == users)));
        app.listen(0);
        assertEquals(Collections.singletonList("refused STARTING"), duringBind);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            futures.add(pool.submit(() -> {
                go.await();
                return body(delegate.send(ClientRequest.of("GET", "/u")));
            }));
        }
        go.countDown();
        for (Future<String> f : futures) {
            assertEquals("true", f.get(10, TimeUnit.SECONDS));
        }
        pool.shutdown();
        app.shutdown(1, TimeUnit.SECONDS);
    }

    // SDS-AC-6; FR-APP-007 AC-5
    @Test
    public void sdsAc6_FR_APP_007_AC5_T41_twoOnStartRunOnceInOrderAndShutdownRunsNone() {
        TestApp t = TestApp.create();
        RestApp app = t.app();
        List<String> calls = Collections.synchronizedList(new ArrayList<String>());
        app.onStart(ctx -> calls.add("a"));
        app.onStart(ctx -> calls.add("b"));
        t.start();
        assertEquals(Arrays.asList("a", "b"), calls);
        app.shutdown(1, TimeUnit.SECONDS);
        assertEquals(Arrays.asList("a", "b"), calls);
    }

    // SDS-AC-8; FR-APP-007 AC-7
    @Test
    public void sdsAc8_FR_APP_007_AC7_ctxLoggerIsTheAppLoggerAfterReplacement() {
        TestApp t = TestApp.create();
        RestApp app = t.app();
        RecordingLogger l = new RecordingLogger();
        app.logger(l);
        List<Logger> seen = Collections.synchronizedList(new ArrayList<Logger>());
        app.onStart(ctx -> seen.add(ctx.logger()));
        app.onStop(ctx -> seen.add(ctx.logger()));
        t.start();
        app.shutdown(1, TimeUnit.SECONDS);
        assertEquals(2, seen.size());
        assertSame(l, seen.get(0));
        assertSame(l, seen.get(1));
        assertSame(app.logger(), seen.get(0));
    }

    // SDS-AC-9; FR-APP-007 AC-8
    @Test
    public void sdsAc9_FR_APP_007_AC8_onStartOnStopAndEveryRequestShareOneContext() {
        TestApp t = TestApp.create();
        RestApp app = t.app();
        List<ApplicationContext> seen = Collections.synchronizedList(new ArrayList<ApplicationContext>());
        app.onStart(seen::add);
        app.get("/c", (req, res) -> {
            seen.add(req.application());
            res.send("ok");
        });
        app.onStop(seen::add);
        t.send("GET", "/c");
        t.send("GET", "/c");
        app.shutdown(1, TimeUnit.SECONDS);
        assertEquals(4, seen.size());
        for (ApplicationContext ctx : seen) {
            assertSame(((DefaultRestApp) app).context(), ctx);
        }
    }

    static final class Users implements Runnable, AutoCloseable {
        final List<String> closed;

        Users(List<String> closed) {
            this.closed = closed;
        }

        @Override
        public void run() {
        }

        @Override
        public void close() {
            closed.add("users");
        }
    }

    // SDS-AC-10; FR-APP-008 AC-1
    @Test
    public void sdsAc10_FR_APP_008_AC1_T45_ownDbThenRegisterUsersCloseUsersThenDbOnShutdown() {
        TestApp t = TestApp.create();
        RestApp app = t.app();
        app.onStart(ctx -> {
            ctx.own(resource("db"));
            ctx.register(Users.class, new Users(closed));
        });
        t.start();
        app.shutdown(1, TimeUnit.SECONDS);
        assertEquals(Arrays.asList("users", "db"), closed);
    }

    // SDS-AC-11; FR-APP-008 AC-2
    @Test
    public void sdsAc11_FR_APP_008_AC2_T45_oneObjectUnderTwoKeysAndOwnedClosesOnceAtItsFirstPosition() {
        TestApp t = TestApp.create();
        RestApp app = t.app();
        app.onStart(ctx -> {
            Users users = new Users(closed);
            ctx.own(resource("a"));
            ctx.register(Runnable.class, users);
            ctx.own(resource("b"));
            ctx.register(Users.class, users);
            ctx.own(users);
        });
        t.start();
        app.shutdown(1, TimeUnit.SECONDS);
        assertEquals(Arrays.asList("b", "users", "a"), closed);
    }

    // SDS-AC-12; FR-APP-008 AC-3
    @Test
    public void sdsAc12_FR_APP_008_AC3_closeFailureOnShutdownIsLoggedAtErrorAndTheOthersClose() {
        TestApp t = TestApp.create();
        RestApp app = t.app();
        RecordingLogger l = new RecordingLogger();
        app.logger(l);
        app.onStart(ctx -> {
            ctx.own(resource("1"));
            ctx.own(() -> {
                throw new IOException("close-2-failed");
            });
            ctx.own(resource("3"));
        });
        t.start();
        app.shutdown(1, TimeUnit.SECONDS);
        assertEquals(Arrays.asList("3", "1"), closed);
        int errors = 0;
        for (String line : l.lines) {
            if (line.startsWith("ERROR ") && line.contains("close-2-failed")) {
                errors++;
            }
        }
        assertEquals(l.lines.toString(), 1, errors);
    }

    // SDS-AC-12; FR-APP-008 AC-3
    @Test
    public void sdsAc12_FR_APP_008_AC3_closeFailureOnStartFailureIsSuppressedOnTheThrownException() {
        TestApp t = TestApp.create();
        RestApp app = t.app();
        IOException closeFailure = new IOException("close-2-failed");
        RuntimeException startFailure = new RuntimeException("start failed");
        app.onStart(ctx -> {
            ctx.own(resource("1"));
            ctx.own(() -> {
                throw closeFailure;
            });
            ctx.own(resource("3"));
            throw startFailure;
        });
        try {
            t.start();
            fail("onStart threw");
        } catch (RuntimeException e) {
            assertSame(startFailure, e);
            assertEquals(Collections.<Throwable>singletonList(closeFailure), Arrays.asList(e.getSuppressed()));
        }
        assertEquals(Arrays.asList("3", "1"), closed);
    }

    // SDS-AC-13; FR-APP-008 AC-4
    @Test
    public void sdsAc13_FR_APP_008_AC4_registerExternalIsNeverClosedOnShutdownAndOwnClosesItOnce() {
        TestApp t = TestApp.create();
        RestApp app = t.app();
        AutoCloseable external = resource("external");
        AutoCloseable both = resource("both");
        app.onStart(ctx -> {
            ctx.registerExternal(AutoCloseable.class, external);
            ctx.registerExternal(Object.class, both);
            ctx.own(both);
        });
        t.start();
        assertSame(external, ((DefaultRestApp) app).context().get(AutoCloseable.class));
        app.shutdown(1, TimeUnit.SECONDS);
        assertEquals(Collections.singletonList("both"), closed);
    }

    // SDS-AC-13; FR-APP-008 AC-4
    @Test
    public void sdsAc13_FR_APP_008_AC4_registerExternalIsNeverClosedOnStartFailure() {
        TestApp t = TestApp.create();
        RestApp app = t.app();
        AutoCloseable external = resource("external");
        AutoCloseable both = resource("both");
        app.onStart(ctx -> {
            ctx.registerExternal(AutoCloseable.class, external);
            ctx.own(both);
            ctx.registerExternal(Object.class, both);
            throw new IllegalStateException("start failed");
        });
        try {
            t.start();
            fail("onStart threw");
        } catch (IllegalStateException expected) {
            // the start failure itself
        }
        assertEquals(Collections.singletonList("both"), closed);
    }
}
