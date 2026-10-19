package com.snoworca.naruserve.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.AppState;
import com.snoworca.naruserve.testing.TestApp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/** The one context of an app. SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-4-6.sds.md. */
public class DefaultApplicationContextTest {

    static final class UserService {
    }

    static final class Closer implements AutoCloseable {
        final List<String> closed;
        final String name;

        Closer(List<String> closed, String name) {
            this.closed = closed;
            this.name = name;
        }

        @Override
        public void close() {
            closed.add(name);
        }
    }

    private final List<String> closed = Collections.synchronizedList(new ArrayList<String>());

    private static void assertRefused(Runnable call, String method, AppState state) {
        try {
            call.run();
            fail(method + " must be refused in " + state);
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(method));
            assertTrue(e.getMessage(), e.getMessage().contains(state.name()));
        }
    }

    // SDS-AC-2; FR-APP-007 AC-2
    @Test
    public void sdsAc2_FR_APP_007_AC2_registerRegisterExternalAndOwnFromOnStopThrowAndTheRegistryIsUnchanged() {
        TestApp t = TestApp.create();
        DefaultRestApp app = (DefaultRestApp) t.app();
        List<Throwable> refused = Collections.synchronizedList(new ArrayList<Throwable>());
        Closer late = new Closer(closed, "late");
        app.onStop(ctx -> {
            for (Runnable call : new Runnable[] {
                    () -> ctx.register(UserService.class, new UserService()),
                    () -> ctx.registerExternal(UserService.class, new UserService()),
                    () -> ctx.own(late)}) {
                try {
                    call.run();
                } catch (IllegalStateException e) {
                    refused.add(e);
                }
            }
        });
        t.start();
        app.shutdown(1, TimeUnit.SECONDS);
        assertEquals(3, refused.size());
        assertTrue(refused.get(0).getMessage(), refused.get(0).getMessage().startsWith("register not allowed"));
        assertTrue(refused.get(1).getMessage(), refused.get(1).getMessage().startsWith("registerExternal not"));
        assertTrue(refused.get(2).getMessage(), refused.get(2).getMessage().startsWith("own not allowed"));
        for (Throwable e : refused) {
            assertTrue(e.getMessage(), e.getMessage().contains(AppState.STOPPING.name()));
        }
        assertFalse(app.context().contains(UserService.class));
        assertTrue(app.context().closeOwned(null).isEmpty());
        assertEquals(new ArrayList<String>(), closed);
    }

    // SDS-AC-2; FR-APP-007 AC-2
    @Test
    public void sdsAc2_FR_APP_007_AC2_registerInRunningAndInNewThrowsNamingMethodAndState() {
        TestApp t = TestApp.create();
        DefaultRestApp app = (DefaultRestApp) t.app();
        DefaultApplicationContext ctx = app.context();
        assertRefused(() -> ctx.register(UserService.class, new UserService()), "register", AppState.NEW);
        t.start();
        assertRefused(() -> ctx.register(UserService.class, new UserService()), "register", AppState.RUNNING);
        assertRefused(() -> ctx.registerExternal(UserService.class, new UserService()), "registerExternal",
                AppState.RUNNING);
        assertRefused(() -> ctx.own(new Closer(closed, "x")), "own", AppState.RUNNING);
        assertFalse(ctx.contains(UserService.class));
        assertNull(ctx.getOrNull(UserService.class));
        app.shutdown(1, TimeUnit.SECONDS);
        assertEquals(new ArrayList<String>(), closed);
    }

    // SDS-AC-2; FR-APP-007 AC-2
    @Test
    public void sdsAc2_FR_APP_007_AC2_registerAndOwnAfterAFailedStartThrowNamingFailed() {
        TestApp t = TestApp.create();
        DefaultRestApp app = (DefaultRestApp) t.app();
        app.onStart(ctx -> {
            throw new IllegalArgumentException("start failed");
        });
        try {
            t.start();
            fail("onStart threw");
        } catch (IllegalArgumentException expected) {
            // the start failure itself
        }
        DefaultApplicationContext ctx = app.context();
        assertRefused(() -> ctx.register(UserService.class, new UserService()), "register", AppState.TERMINATED);
        assertRefused(() -> ctx.own(new Closer(closed, "x")), "own", AppState.TERMINATED);
        assertFalse(ctx.contains(UserService.class));
    }

    // SDS-AC-3; FR-APP-007 AC-3
    @Test
    public void sdsAc3_FR_APP_007_AC3_secondRegisterThrowsNamingTheKeyKeepsTheFirstAndDoesNotTrackTheSecond() {
        TestApp t = TestApp.create();
        DefaultRestApp app = (DefaultRestApp) t.app();
        Closer first = new Closer(closed, "first");
        Closer second = new Closer(closed, "second");
        List<IllegalStateException> thrown = new ArrayList<>();
        app.onStart(ctx -> {
            ctx.register(Closer.class, first);
            try {
                ctx.register(Closer.class, second);
            } catch (IllegalStateException e) {
                thrown.add(e);
            }
            try {
                ctx.registerExternal(Closer.class, second);
            } catch (IllegalStateException e) {
                thrown.add(e);
            }
        });
        t.start();
        assertEquals(2, thrown.size());
        for (IllegalStateException e : thrown) {
            assertTrue(e.getMessage(), e.getMessage().contains(Closer.class.getName()));
        }
        assertSame(first, app.context().get(Closer.class));
        app.shutdown(1, TimeUnit.SECONDS);
        assertEquals(Collections.singletonList("first"), closed);
    }

    // SDS-AC-7; FR-APP-007 AC-6
    @Test
    public void sdsAc7_FR_APP_007_AC6_eightThreadsWritingDistinctKeysInRunningLoseNothing() throws Exception {
        TestApp t = TestApp.create();
        DefaultRestApp app = (DefaultRestApp) t.app();
        t.start();
        DefaultApplicationContext ctx = app.context();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int n = 0; n < 8; n++) {
            int thread = n;
            futures.add(pool.submit(() -> {
                go.await();
                for (int i = 0; i < 500; i++) {
                    String key = "t" + thread + "-" + i;
                    ctx.attribute(key, i);
                    assertEquals(Integer.valueOf(i), ctx.attribute(key, Integer.class));
                }
                return null;
            }));
        }
        go.countDown();
        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();
        for (int n = 0; n < 8; n++) {
            for (int i = 0; i < 500; i++) {
                assertEquals(Integer.valueOf(i), ctx.attribute("t" + n + "-" + i, Integer.class));
            }
        }
        app.shutdown(1, TimeUnit.SECONDS);
    }

    // SDS-AC-7; FR-APP-007 AC-6
    @Test
    public void sdsAc7_FR_APP_007_AC6_onStartAttributeIsReadByHandlerAndOnStopWritesAndReadsK2() {
        TestApp t = TestApp.create();
        DefaultRestApp app = (DefaultRestApp) t.app();
        List<Object> seen = Collections.synchronizedList(new ArrayList<Object>());
        app.onStart(ctx -> ctx.attribute("k1", "v1"));
        app.get("/a", (req, res) -> res.send(String.valueOf(req.application().attribute("k1", String.class))));
        app.onStop(ctx -> {
            seen.add(ctx.attribute("k1", String.class));
            ctx.attribute("k2", "v2");
            seen.add(ctx.attribute("k2", String.class));
            ctx.attribute("k2", (Object) null);
            seen.add(ctx.attribute("k2", String.class) == null ? "removed" : "kept");
        });
        assertEquals("v1", new String(t.send("GET", "/a").body(), java.nio.charset.StandardCharsets.UTF_8));
        app.shutdown(1, TimeUnit.SECONDS);
        assertEquals(java.util.Arrays.<Object>asList("v1", "v2", "removed"), seen);
    }

    // SDS-AC-14; FR-APP-008 AC-5
    @Test
    public void sdsAc14_FR_APP_008_AC5_plainServiceIsRegisteredReturnedAndNothingIsClosed() {
        TestApp t = TestApp.create();
        DefaultRestApp app = (DefaultRestApp) t.app();
        UserService users = new UserService();
        app.onStart(ctx -> {
            ctx.register(UserService.class, users);
            ctx.register(int.class, 7);
        });
        t.start();
        assertSame(users, app.context().get(UserService.class));
        assertEquals(Integer.valueOf(7), app.context().get(int.class));
        app.shutdown(1, TimeUnit.SECONDS);
        assertTrue(app.context().closeOwned(null).isEmpty());
        assertSame(users, app.context().get(UserService.class));
    }
}
