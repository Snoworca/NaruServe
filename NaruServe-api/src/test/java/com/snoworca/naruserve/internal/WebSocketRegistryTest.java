package com.snoworca.naruserve.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.HttpException;
import com.snoworca.naruserve.WebSocketHandler;
import com.snoworca.naruserve.WebSocketSession;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.InMemoryWebSocketChannel;
import com.snoworca.naruserve.testing.TestApp;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Test;

/** Session reservation and refusal. SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-7.sds.md. */
public class WebSocketRegistryTest {

    final InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
    final TestApp t = TestApp.create(adapter);
    final DefaultRestApp app = (DefaultRestApp) t.app();
    /** Every session whose ws handler ran. */
    final BlockingQueue<WebSocketSession> handled = new LinkedBlockingQueue<>();

    @After
    public void stop() {
        adapter.beforeOpen(null);
        app.shutdown(2, TimeUnit.SECONDS);
    }

    /** Registers /ws with a handler that only records the session, starts the app and returns its registry. */
    WebSocketRegistry start() {
        return start((req, ws) -> handled.add(ws));
    }

    WebSocketRegistry start(WebSocketHandler handler) {
        app.ws("/ws", handler);
        t.start();
        return app.webSocketRegistry();
    }

    static ClientRequest upgradeRequest() {
        return ClientRequest.of("GET", "/ws").header("Connection", "Upgrade").header("Upgrade", "websocket");
    }

    static void assert503Close(ClientResponse r) {
        assertEquals(503, r.status());
        assertEquals("close", r.header("Connection"));
        String body = new String(r.body(), StandardCharsets.UTF_8);
        assertTrue(body, body.matches("\\{\"error\":\\{\"code\":\"SERVICE_UNAVAILABLE\",\"message\":\"Service "
                + "Unavailable\",\"requestId\":\"[0-9a-f]{16}\"}}"));
    }

    interface Check {
        boolean ok();
    }

    /** Polls {@code check} for up to 5 s. */
    static boolean eventually(Check check) throws InterruptedException {
        for (long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5); System.nanoTime() < end; ) {
            if (check.ok()) {
                return true;
            }
            Thread.sleep(2);
        }
        return check.ok();
    }

    /** Runs {@code registry.closeAll} with a deadline {@code millis} from now on its own thread. */
    static Thread closeAllAsync(WebSocketRegistry registry, long millis) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        Thread th = new Thread(() -> registry.closeAll(deadline), "test-close-all");
        th.start();
        return th;
    }

    /** A beforeOpen hook that counts down {@code entered} and then waits for {@code release}. */
    static Runnable holdingHook(CountDownLatch entered, CountDownLatch release) {
        return () -> {
            entered.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
    }

    // SDS-AC-1; FR-WS-009 AC-2: the session is reserved before HttpExchange.webSocket, so the registry already counts
    // it inside webSocket, before onOpen
    @Test(timeout = 20000)
    public void sdsAc1_FR_WS_009_AC2_registrySizeIsOneInsideWebSocketBeforeOnOpen() throws Exception {
        WebSocketRegistry registry = start();
        assertEquals(0, registry.size());
        AtomicInteger seen = new AtomicInteger(-1);
        AtomicInteger handledBefore = new AtomicInteger(-1);
        CountDownLatch hooked = new CountDownLatch(1);
        adapter.beforeOpen(() -> {
            seen.set(registry.size());
            handledBefore.set(handled.size());
            hooked.countDown();
        });
        Future<ClientResponse> f = adapter.submit(upgradeRequest());
        InMemoryWebSocketChannel ch = adapter.nextChannel(5, TimeUnit.SECONDS);
        assertTrue(hooked.await(5, TimeUnit.SECONDS));
        assertEquals(1, seen.get());
        assertEquals("onOpen ran before the hook", 0, handledBefore.get());
        handled.poll(5, TimeUnit.SECONDS);
        ch.clientClose(1000, "");
        assertEquals(101, f.get(5, TimeUnit.SECONDS).status());
        assertTrue("reservation left after close", eventually(() -> registry.size() == 0));
    }

    // SDS-AC-2; FR-WS-009 AC-3: once the closed flag is set an upgrade gets 503 + Connection: close before any 101,
    // HttpExchange.webSocket is not called and nothing is registered
    @Test(timeout = 20000)
    public void sdsAc2_FR_WS_009_AC3_afterTheClosedFlagAnUpgradeGets503CloseAndNoWebSocketCall() throws Exception {
        WebSocketRegistry registry = start();
        registry.closeAll(System.nanoTime());
        assertEquals(WebSocketRegistry.State.ENDED, registry.state());
        AtomicBoolean called = new AtomicBoolean();
        adapter.beforeOpen(() -> called.set(true));
        ClientResponse r = adapter.send(upgradeRequest());
        assert503Close(r);
        assertFalse("HttpExchange.webSocket ran past the refusal", called.get());
        assertEquals(0, registry.size());
        assertNull(handled.poll(100, TimeUnit.MILLISECONDS));
    }

    // SDS-AC-3; FR-WS-009 AC-4: 10 handshakes refused with 426 by the adapter and 10 refused with 403 by the Origin
    // check leave the registry size where it was
    @Test(timeout = 20000)
    public void sdsAc3_FR_WS_009_AC4_tenRefused426AndTenOrigin403LeaveTheSizeUnchanged() throws Exception {
        WebSocketRegistry registry = start();
        Future<ClientResponse> open = adapter.submit(upgradeRequest());
        InMemoryWebSocketChannel ch = adapter.nextChannel(5, TimeUnit.SECONDS);
        handled.poll(5, TimeUnit.SECONDS);
        assertEquals(1, registry.size());
        adapter.refuseWebSocket(new HttpException(426));
        for (int i = 0; i < 10; i++) {
            assertEquals(426, adapter.send(upgradeRequest()).status());
            assertEquals("after refused handshake " + i, 1, registry.size());
        }
        adapter.refuseWebSocket(null);
        for (int i = 0; i < 10; i++) {
            ClientResponse r = adapter.send(upgradeRequest().header("Origin", "https://evil.example")
                    .header("Host", "app.example.com"));
            assertEquals(403, r.status());
            assertEquals("after Origin refusal " + i, 1, registry.size());
        }
        ch.clientClose(1000, "");
        open.get(5, TimeUnit.SECONDS);
        assertTrue(eventually(() -> registry.size() == 0));
    }

    // SDS-AC-5; FR-WS-009 AC-5: a reserved session whose onOpen comes after the closed flag hands close 1001 to its
    // sender slot as its only task and the ws handler never runs
    @Test(timeout = 20000)
    public void sdsAc5_FR_WS_009_AC5_flagSetBeforeOnOpenSendsClose1001OnlyAndSkipsTheHandler() throws Exception {
        WebSocketRegistry registry = start();
        AtomicReference<Thread> closer = new AtomicReference<>();
        adapter.beforeOpen(() -> {
            closer.set(closeAllAsync(registry, 5000));
            try {
                assertTrue(eventually(() -> registry.state() == WebSocketRegistry.State.CLOSING));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        Future<ClientResponse> f = adapter.submit(upgradeRequest());
        InMemoryWebSocketChannel ch = adapter.nextChannel(5, TimeUnit.SECONDS);
        assertEquals(101, f.get(5, TimeUnit.SECONDS).status());
        assertEquals(1001, ch.closeCode());
        assertEquals("server shutdown", ch.closeReason());
        assertTrue("a message was sent: " + ch.sent(), ch.sent().isEmpty());
        assertEquals(0, ch.pings());
        assertFalse(ch.aborted());
        assertNull("the ws handler ran", handled.poll(100, TimeUnit.MILLISECONDS));
        closer.get().join(5000);
        assertFalse("closeAll still waiting", closer.get().isAlive());
        assertEquals(WebSocketRegistry.State.ENDED, registry.state());
        assertEquals(0, registry.size());
    }

    // SDS-AC-5; FR-WS-009 AC-6: an upgrade reaching the dispatcher after STOPPING began gets the stopping gate's 503
    // + Connection: close and HttpExchange.webSocket is never called
    @Test(timeout = 20000)
    public void sdsAc5_FR_WS_009_AC6_upgradeDuringStoppingGetsTheGate503AndNoWebSocketCall() throws Exception {
        AtomicReference<ClientResponse> answer = new AtomicReference<>();
        AtomicReference<WebSocketRegistry.State> stateThen = new AtomicReference<>();
        AtomicBoolean called = new AtomicBoolean();
        app.ws("/ws", (req, ws) -> handled.add(ws));
        // added before freeze, so it runs before the WebSocket closing participant: the registry is still OPEN
        app.shutdownParticipants().add(ShutdownParticipants.Phase.BEFORE_WAIT, () -> {
            stateThen.set(app.webSocketRegistry().state());
            answer.set(adapter.send(upgradeRequest()));
        });
        adapter.beforeOpen(() -> called.set(true));
        t.start();
        app.shutdown(2, TimeUnit.SECONDS);
        assertEquals(WebSocketRegistry.State.OPEN, stateThen.get());
        assert503Close(answer.get());
        assertFalse("HttpExchange.webSocket was called", called.get());
        assertTrue(handled.isEmpty());
    }
}
