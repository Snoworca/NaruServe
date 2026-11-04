package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.InMemoryWebSocketChannel;
import com.snoworca.naruserve.utils.auth.internal.Doctor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Test;

/** auth.webSocket(handler): authentication before 101, expiry close, recheck and revocation closes (FR-FLOW-008). */
public class WebSocketWrapperTest {

    /** The fixtures this test made; each is stopped after the test (its app, auth scheduler and pools). */
    private final List<AdminFixture> fixtures = new ArrayList<>();

    /** A new fixture, stopped by {@link #stopFixtures}. */
    private AdminFixture fixture() {
        AdminFixture f = new AdminFixture();
        fixtures.add(f);
        return f;
    }

    @After
    public void stopFixtures() {
        AdminFixture.stopAll(fixtures);
    }

    private static final class Opened {
        final InMemoryWebSocketChannel channel;
        final Future<ClientResponse> handshake;
        /** The server-side session the wrapper tracks; its isOpen() turns false as soon as the server closes it. */
        final com.snoworca.naruserve.WebSocketSession server;

        Opened(InMemoryWebSocketChannel channel, Future<ClientResponse> handshake,
                com.snoworca.naruserve.WebSocketSession server) {
            this.channel = channel;
            this.handshake = handshake;
            this.server = server;
        }
    }

    /** Signalled by the wrapped handler, which runs after the wrapper has registered the connection. */
    private final java.util.concurrent.LinkedBlockingQueue<Object> handled =
            new java.util.concurrent.LinkedBlockingQueue<>();

    private com.snoworca.naruserve.WebSocketHandler signal() {
        return (req, ws) -> handled.add(ws);
    }

    private Opened open(AdminFixture f, String token) throws Exception {
        Future<ClientResponse> h = f.adapter.submit(ClientRequest.upgrade("/ws", "Authorization", "Bearer " + token));
        InMemoryWebSocketChannel ch = f.adapter.nextChannel(5, TimeUnit.SECONDS);
        Object server = handled.poll(5, TimeUnit.SECONDS);
        assertTrue("the wrapped handler ran", server != null);
        return new Opened(ch, h, (com.snoworca.naruserve.WebSocketSession) server);
    }

    /** Waits until the server side closed {@code ch}. */
    private static void awaitClosed(InMemoryWebSocketChannel ch) throws InterruptedException {
        for (int i = 0; i < 500 && ch.isOpen(); i++) {
            Thread.sleep(10);
        }
    }

    private static void finish(Opened o) throws Exception {
        if (o.channel.isOpen()) {
            o.channel.clientClose(1000, "");
        }
        assertEquals(101, o.handshake.get(5, TimeUnit.SECONDS).status());
    }

    // FR-FLOW-008 AC-1: an Upgrade without a token is 401 JSON before 101 and the handler is never called
    @Test(timeout = 20000)
    public void FR_FLOW_008_AC1_noTokenNo101() throws Exception {
        AdminFixture f = fixture();
        final boolean[] called = new boolean[1];
        f.start(b -> b, x -> x.app.ws("/ws", x.auth.webSocket((req, ws) -> called[0] = true)));
        ClientResponse r = f.adapter.send(ClientRequest.upgrade("/ws"));
        assertEquals(401, r.status());
        assertTrue(r.header("Content-Type").startsWith("application/json"));
        assertFalse(r.upgradeHandedOver());
        assertFalse(called[0]);
    }

    // FR-FLOW-008 AC-2: a valid access token whose exp is 2 seconds ahead: after 101 the connection is closed with 1008
    // "token expired" when exp is reached (injected TimeSource)
    @Test(timeout = 20000)
    public void FR_FLOW_008_AC2_closedAtExpiry() throws Exception {
        AdminFixture f = fixture().start(b -> b, x -> x.app.ws("/ws", x.auth.webSocket(signal())));
        String bob = f.user("bob", "reader");
        Opened o = open(f, f.auth.accessToken(bob, 0, 2_000L));
        f.auth.webSocketTick();
        assertTrue(o.server.isOpen());
        f.clock.addAndGet(1_999);
        f.auth.webSocketTick();
        assertTrue(o.server.isOpen());
        f.clock.addAndGet(1);
        f.auth.webSocketTick();
        awaitClosed(o.channel);
        assertFalse(o.channel.isOpen());
        assertEquals(1008, o.channel.closeCode());
        assertEquals("token expired", o.channel.closeReason());
        finish(o);
    }

    // FR-FLOW-008 AC-3: a disable through auth on this node closes the open connection at once; a change written
    // straight to the store is seen at the recheck within userCacheTtl
    @Test(timeout = 20000)
    public void FR_FLOW_008_AC3_revocationCloses() throws Exception {
        AdminFixture f = fixture().start(b -> b, x -> x.app.ws("/ws", x.auth.webSocket(signal())));
        String root = f.user("root", "admin");
        String bob = f.user("bob", "reader");
        Opened o = open(f, f.token(bob));
        assertTrue(o.server.isOpen());
        assertEquals(200, f.call("PATCH", "/auth/admin/users/" + bob, root, "{\"enabled\":false}").status());
        awaitClosed(o.channel);
        assertFalse(o.channel.isOpen());
        assertEquals(1008, o.channel.closeCode());
        finish(o);
        String carol = f.user("carol", "reader");
        Opened c = open(f, f.token(carol));
        f.base.setEnabled(carol, false); // another node's write: not seen by auth
        f.auth.webSocketTick();
        assertTrue(c.server.isOpen());
        f.clock.addAndGet(5_000);
        f.auth.webSocketTick();
        awaitClosed(c.channel);
        assertFalse(c.channel.isOpen());
        assertEquals(1008, c.channel.closeCode());
        finish(c);
    }

    // FR-FLOW-008 AC-4: the wrapped handler reads the principal with auth.user(req) after 101
    @Test(timeout = 20000)
    public void FR_FLOW_008_AC4_principalInHandler() throws Exception {
        final AtomicReference<String> seen = new AtomicReference<>();
        AdminFixture f = fixture().start(b -> b,
                x -> x.app.ws("/ws", x.auth.webSocket((req, ws) -> {
                    seen.set(x.auth.user(req).getUsername());
                    handled.add(ws);
                })));
        String bob = f.user("bob", "reader");
        Opened o = open(f, f.token(bob));
        finish(o);
        assertEquals("bob", seen.get());
    }

    // FR-FLOW-008 AC-5: COOKIE transport with WebSocket routes: a WARN about the allowedOrigins wildcard, never a FAIL
    @Test
    public void FR_FLOW_008_AC5_cookieTransportWarns() throws Exception {
        AdminFixture f = fixture().start(b -> b.transport(TokenTransport.COOKIE),
                x -> x.app.ws("/ws", x.auth.webSocket(signal())));
        boolean warned = false;
        for (Doctor.Finding x : f.auth.doctor(f.app).findings()) {
            if (x.getItem().equals("websocket origin")) {
                warned = true;
                assertEquals(Doctor.Level.WARN, x.getLevel());
                assertTrue(x.getMessage().contains("allowedOrigins"));
            }
            assertFalse(x.toString(), x.getItem().startsWith("websocket") && x.getLevel() == Doctor.Level.FAIL);
        }
        assertTrue(warned);
    }

    // FR-FLOW-008 AC-6: two WEBSOCKET registrations and one auth.webSocket use: doctor WARN
    @Test
    public void FR_FLOW_008_AC6_unwrappedRouteWarns() throws Exception {
        AdminFixture f = fixture().start(b -> b, x -> x.app.ws("/ws", x.auth.webSocket(signal())));
        assertNull(item(f, "websocket wrapper"));
        AdminFixture g = fixture().start(b -> b, x -> {
            x.app.ws("/ws", x.auth.webSocket(signal()));
            x.app.ws("/raw", (req, ws) -> { });
        });
        Doctor.Finding w = item(g, "websocket wrapper");
        assertEquals(Doctor.Level.WARN, w.getLevel());
        assertTrue(w.getMessage().startsWith("2 WebSocket routes but 1 use"));
    }

    // FR-FLOW-008 AC-6 (M2-F12): the doctor counts wrapped WEBSOCKET registrations, not auth.webSocket calls: a
    // wrapper made but never registered does not hide an unwrapped route, and one wrapper on two routes is no WARN
    @Test
    public void FR_FLOW_008_AC6_wrappedRegistrationsAreCounted() throws Exception {
        AdminFixture unregistered = fixture().start(b -> b, x -> {
            x.app.ws("/ws", x.auth.webSocket(signal()));
            x.auth.webSocket(signal()); // made, never registered
            x.app.ws("/raw", (req, ws) -> { });
        });
        Doctor.Finding w = item(unregistered, "websocket wrapper");
        assertNotNull("an unwrapped /raw route was not reported", w);
        assertEquals(Doctor.Level.WARN, w.getLevel());
        assertTrue(w.getMessage(), w.getMessage().startsWith("2 WebSocket routes but 1 use"));
        AdminFixture reused = fixture().start(b -> b, x -> {
            com.snoworca.naruserve.WebSocketHandler one = x.auth.webSocket(signal());
            x.app.ws("/a", one);
            x.app.ws("/b", one);
        });
        Doctor.Finding r = item(reused, "websocket wrapper");
        assertNull("both routes are wrapped, yet the doctor warned: " + r, r);
    }

    private static Doctor.Finding item(AdminFixture f, String name) {
        for (Doctor.Finding x : f.auth.doctor(f.app).findings()) {
            if (x.getItem().equals(name)) {
                return x;
            }
        }
        return null;
    }

    // FR-FLOW-008 AC-7: COOKIE transport, a cross-origin page opening the WebSocket under the default Core Origin
    // policy is rejected by Core before 101 (403)
    @Test(timeout = 20000)
    public void FR_FLOW_008_AC7_crossOriginRefusedByCore() throws Exception {
        final boolean[] called = new boolean[1];
        AdminFixture f = fixture().start(b -> b.transport(TokenTransport.COOKIE),
                x -> x.app.ws("/ws", x.auth.webSocket((req, ws) -> called[0] = true)));
        String bob = f.user("bob", "reader");
        ClientResponse r = f.adapter.send(ClientRequest.upgrade("/ws", "Cookie", "naru_at=" + f.token(bob),
                "Origin", "https://evil.example"));
        assertEquals(403, r.status());
        assertFalse(r.upgradeHandedOver());
        assertFalse(called[0]);
    }

    // FR-FLOW-008 AC-8: an unchanged principal keeps the connection open after 3 x userCacheTtl, (a) by default and
    // (b) under statelessAccess, where the first recheck promotes the minimal principal without counting as a change
    @Test(timeout = 20000)
    public void FR_FLOW_008_AC8_unchangedPrincipalStaysOpen() throws Exception {
        for (boolean stateless : new boolean[] {false, true}) {
            AdminFixture f = fixture().start(b -> b.statelessAccess(stateless),
                    x -> x.app.ws("/ws", x.auth.webSocket(signal())));
            String bob = f.user("bob", "reader");
            Opened o = open(f, f.auth.accessToken(bob, 0, 50_000L));
            final List<Integer> closes = new CopyOnWriteArrayList<>();
            final CountDownLatch closed = new CountDownLatch(1);
            o.server.onClose((code, reason) -> {
                closes.add(code);
                closed.countDown();
            });
            for (int i = 0; i < 3; i++) {
                f.clock.addAndGet(5_000);
                f.auth.webSocketTick();
                assertTrue("stateless=" + stateless + " round " + i, o.server.isOpen());
            }
            // the recheck closes on the ticking thread, so isOpen() above is the proof; a regression closing
            // asynchronously would fire the close listener, which ends this wait at once
            assertFalse("stateless=" + stateless + ": closed after the ticks " + closes,
                    closed.await(500, TimeUnit.MILLISECONDS));
            assertTrue(o.channel.isOpen());
            finish(o);
            assertTrue(closed.await(10, TimeUnit.SECONDS));
            assertEquals("stateless=" + stateless + ": the one close is the client's, no access revocation",
                    Arrays.asList(1000), closes);
        }
    }
}
