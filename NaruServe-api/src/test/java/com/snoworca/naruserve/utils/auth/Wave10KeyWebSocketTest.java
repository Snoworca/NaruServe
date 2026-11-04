package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.WebSocketSession;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.InMemoryWebSocketChannel;
import com.snoworca.naruserve.utils.auth.apikey.IssuedApiKey;
import com.snoworca.naruserve.utils.auth.rbac.RolePolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import org.junit.After;
import org.junit.Test;

/** API keys on WebSocket connections wrapped in {@code auth.webSocket} (SEC-APIKEY-011), over the in-memory adapter. */
public class Wave10KeyWebSocketTest {

    /** One connection: the client channel, the handshake and the server session the wrapper tracks. */
    static final class Opened {
        final InMemoryWebSocketChannel channel;
        final Future<ClientResponse> handshake;
        final WebSocketSession server;

        Opened(InMemoryWebSocketChannel channel, Future<ClientResponse> handshake, WebSocketSession server) {
            this.channel = channel;
            this.handshake = handshake;
            this.server = server;
        }

        void awaitClosed() throws InterruptedException {
            for (int i = 0; i < 500 && channel.isOpen(); i++) {
                Thread.sleep(10);
            }
        }

        void finish() throws Exception {
            if (channel.isOpen()) {
                channel.clientClose(1000, "");
            }
            assertEquals(101, handshake.get(5, TimeUnit.SECONDS).status());
        }
    }

    private final W8 f = new W8();
    private final List<W8> others = new ArrayList<>();
    private final Wave9KeyAuthTest.Keys keys = new Wave9KeyAuthTest.Keys();
    private final Wave9KeyAuthTest.Held held = new Wave9KeyAuthTest.Held();
    private final AdminFixture.RoleGroups groups = new AdminFixture.RoleGroups();
    private String owner;
    private String root;

    @After
    public void stop() {
        f.stop();
        for (W8 o : others) {
            o.stop();
        }
    }

    private static RolePolicy roles() {
        return RolePolicy.builder().role("reader", "posts:read").role("editor", "posts:*").build();
    }

    /** A node with the probe routes and {@code /ws} wrapped in {@code auth.webSocket}, reporting each session. */
    private W8 node(W8 w, LinkedBlockingQueue<Object> handled, UnaryOperator<Auth.Builder> config) {
        w.routes = x -> x.app.ws("/ws", x.auth.webSocket((req, ws) -> handled.add(ws)));
        w.start(b -> config.apply(b.apiKeys(keys).roles(roles()).groups(groups)));
        w.auth.apiKeyOwners().deletions(held);
        return w;
    }

    private final LinkedBlockingQueue<Object> handledA = new LinkedBlockingQueue<>();
    private final LinkedBlockingQueue<Object> handledB = new LinkedBlockingQueue<>();

    private void start() throws Exception {
        owner = f.user("bot", W8.PW, "editor");
        root = f.user("root", W8.PW, "admin");
        node(f, handledA, b -> b);
    }

    private W8 nodeB() {
        W8 b = new W8();
        b.users = f.store;
        b.clock.set(f.clock.get());
        others.add(b);
        return node(b, handledB, x -> x);
    }

    private static Opened open(W8 w, LinkedBlockingQueue<Object> handled, String key) throws Exception {
        Future<ClientResponse> h = w.adapter.submit(ClientRequest.upgrade("/ws", "Authorization", "ApiKey " + key));
        InMemoryWebSocketChannel ch = w.adapter.nextChannel(5, TimeUnit.SECONDS);
        Object server = handled.poll(5, TimeUnit.SECONDS);
        assertNotNull("the wrapped handler ran", server);
        return new Opened(ch, h, (WebSocketSession) server);
    }

    private String bearer(String id) throws Exception {
        return "Bearer " + f.auth.accessToken(id, f.stored(id).getTokenVersion(), 900_000L);
    }

    private static void assertRevoked(Opened o) throws Exception {
        o.awaitClosed();
        assertFalse(o.channel.isOpen());
        assertEquals(1008, o.channel.closeCode());
        o.finish();
    }

    // SEC-APIKEY-011 AC-1: an Upgrade with Authorization: ApiKey <wrong key> gets 401 invalid_api_key JSON and no 101
    @Test(timeout = 20000)
    public void SEC_APIKEY_011_AC1_aWrongKeyGetsNo101() throws Exception {
        start();
        ClientResponse r = f.adapter.send(ClientRequest.upgrade("/ws", "Authorization",
                "ApiKey nk_AAAAAAAA_BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB"));
        assertEquals(401, r.status());
        assertEquals("invalid_api_key", W8.code(r));
        assertTrue(r.header("Content-Type").startsWith("application/json"));
        assertFalse(r.upgradeHandedOver());
        assertNull(handledA.poll(100, TimeUnit.MILLISECONDS));
    }

    // SEC-APIKEY-011 AC-2: a valid key expiring at T, wrapped in auth.webSocket: at TimeSource time T the session is
    // closed with 1008 "api key expired"; a non-expiring key schedules no close
    @Test(timeout = 20000)
    public void SEC_APIKEY_011_AC2_closedAtTheKeysExpiry() throws Exception {
        start();
        long t = f.clock.get() + 60_000L;
        IssuedApiKey expiring = f.auth.apiKeys().issue(owner, "short", t, "posts:read");
        IssuedApiKey forever = f.auth.apiKeys().issue(owner, "long", null, "posts:read");
        Opened a = open(f, handledA, expiring.getPlainKey());
        Opened b = open(f, handledA, forever.getPlainKey());
        f.clock.set(t - 1);
        f.auth.webSocketTick();
        assertTrue(a.server.isOpen());
        f.clock.set(t);
        f.auth.webSocketTick();
        a.awaitClosed();
        assertFalse(a.channel.isOpen());
        assertEquals(1008, a.channel.closeCode());
        assertEquals("api key expired", a.channel.closeReason());
        a.finish();
        f.clock.addAndGet(400L * 86_400_000L);
        f.auth.webSocketTick();
        f.auth.webSocketTick();
        assertTrue("a key without expiry is never closed for it", b.server.isOpen());
        b.finish();
    }

    // SEC-APIKEY-011 AC-3: a key deleted through another node's DELETE /auth/me/apikeys/:id: the connection on this
    // node is closed at the next recheck, no later than userCacheTtl after the delete
    @Test(timeout = 20000)
    public void SEC_APIKEY_011_AC3_deletedOnAnotherNode() throws Exception {
        start();
        W8 b = nodeB();
        IssuedApiKey k = f.auth.apiKeys().issue(owner, "ws", null, "posts:read");
        Opened o = open(f, handledA, k.getPlainKey());
        ClientResponse del = b.adapter.send(ClientRequest.of("DELETE", "/auth/me/apikeys/" + k.getKey().getId())
                .header("Authorization", "Bearer " + b.auth.accessToken(owner, 0, 900_000L)));
        assertEquals(204, del.status());
        f.auth.webSocketTick();
        assertTrue("not before the recheck", o.server.isOpen());
        f.clock.addAndGet(5_000L);
        f.auth.webSocketTick();
        assertRevoked(o);
    }

    // SEC-APIKEY-011 AC-4: a key revoked through auth.apiKeys().revoke on this node: the connection opened with it is
    // closed immediately; a connection of another key of the same owner stays open
    @Test(timeout = 20000)
    public void SEC_APIKEY_011_AC4_revokedOnThisNode() throws Exception {
        start();
        IssuedApiKey k = f.auth.apiKeys().issue(owner, "ws", null, "posts:read");
        IssuedApiKey other = f.auth.apiKeys().issue(owner, "other", null, "posts:read");
        Opened o = open(f, handledA, k.getPlainKey());
        Opened keep = open(f, handledA, other.getPlainKey());
        assertTrue(f.auth.apiKeys().revoke(k.getKey().getId()));
        assertRevoked(o);
        assertTrue(keep.server.isOpen());
        assertEquals(204, f.adapter.send(ClientRequest.of("DELETE", "/auth/admin/apikeys/" + other.getKey().getId())
                .header("Authorization", bearer(root))).status());
        assertRevoked(keep);
    }

    // SEC-APIKEY-011 AC-5: the owner's POST /auth/logout?all=true on node A: a connection opened with the key on A is
    // closed immediately; one opened with the same key on node B sharing the stores is closed at B's next recheck, no
    // later than userCacheTtl after the ver++; the same pair holds when an admin disables the owner through A4 on A
    @Test(timeout = 30000)
    public void SEC_APIKEY_011_AC5_ownerRevocationThroughAuth() throws Exception {
        start();
        W8 b = nodeB();
        for (int way = 0; way < 2; way++) {
            if (way == 1) {
                f.store.setEnabled(owner, true);
            }
            IssuedApiKey k = f.auth.apiKeys().issue(owner, "ws" + way, null, "posts:read");
            Opened onA = open(f, handledA, k.getPlainKey());
            Opened onB = open(b, handledB, k.getPlainKey());
            if (way == 0) {
                assertEquals(204, f.post("/auth/logout?all=true", null, "Authorization", bearer(owner)).status());
            } else {
                assertEquals(200, f.adapter.send(ClientRequest.json("PATCH", "/auth/admin/users/" + owner,
                        "{\"enabled\":false}").header("Authorization", bearer(root))).status());
            }
            assertRevoked(onA);
            b.auth.webSocketTick();
            assertTrue("B waits for its recheck", onB.server.isOpen());
            b.clock.addAndGet(5_000L);
            b.auth.webSocketTick();
            assertRevoked(onB);
            held.queued.clear();
        }
    }

    // SEC-APIKEY-011 AC-6: SINGLE_INSTANCE, marker (u, 5), a connection opened with a key of ownerVer 6: closed at the
    // next recheck
    @Test(timeout = 20000)
    public void SEC_APIKEY_011_AC6_aMarkerClosesAFutureKey() throws Exception {
        start();
        f.store.update(f.stored(owner).toBuilder().tokenVersion(6).build());
        IssuedApiKey k = f.auth.apiKeys().issue(owner, "ws", null, "posts:read");
        assertEquals(6, k.getKey().getOwnerVer());
        Opened o = open(f, handledA, k.getPlainKey());
        f.auth.apiKeyOwners().mark(owner, 5);
        f.auth.webSocketTick();
        assertTrue(o.server.isOpen());
        f.clock.addAndGet(5_000L);
        f.auth.webSocketTick();
        assertRevoked(o);
    }

    // SEC-APIKEY-011 AC-7 (SINGLE_INSTANCE half): a store whose ver went back from 6 to 5 after a connection was opened
    // with a key of ownerVer 6: at the next recheck the connection is closed and deleteById(keyId) runs off the request
    // path (the MULTI_INSTANCE half: Wave9KeyOwnersTest)
    @Test(timeout = 20000)
    public void SEC_APIKEY_011_AC7_anInvariantViolationAtTheRecheck() throws Exception {
        start();
        f.store.update(f.stored(owner).toBuilder().tokenVersion(6).build());
        IssuedApiKey k = f.auth.apiKeys().issue(owner, "ws", null, "posts:read");
        Opened o = open(f, handledA, k.getPlainKey());
        f.store.update(f.stored(owner).toBuilder().tokenVersion(5).build());
        keys.deletes.clear();
        f.clock.addAndGet(5_000L);
        f.auth.webSocketTick();
        assertRevoked(o);
        assertTrue("not on the recheck path", keys.deletes.isEmpty());
        held.runAll();
        assertEquals(Collections.singletonList("deleteById " + k.getKey().getId()), keys.deletes);
        assertNull(keys.d.findById(k.getKey().getId()));
    }

    // SEC-APIKEY-011 AC-8: fixed TimeSource, a connection through auth.webSocket with a key whose scopes are posts:*, by
    // an owner holding posts:* only through role editor: (a) editor removed straight in the store (UserStore.setRoles,
    // ver unchanged, the key not stale): closed at the next recheck; (b) the same when the owner holds posts:* only
    // through a group that confers editor and the GroupStore stops returning that group; (c) a recheck at which neither
    // the key row nor the owner principal changed leaves the connection open
    @Test(timeout = 30000)
    public void SEC_APIKEY_011_AC8_ownerDemotionWithoutAVerChange() throws Exception {
        start();
        IssuedApiKey k = f.auth.apiKeys().issue(owner, "ws", null, "posts:*");
        Opened o = open(f, handledA, k.getPlainKey());
        f.clock.addAndGet(5_000L);
        f.auth.webSocketTick();
        assertTrue("(c) nothing changed", o.server.isOpen());
        long ver = f.stored(owner).getTokenVersion();
        f.store.setRoles(owner, Collections.<String>emptySet());
        assertEquals(ver, f.stored(owner).getTokenVersion());
        f.clock.addAndGet(5_000L);
        f.auth.webSocketTick();
        assertRevoked(o);

        String member = f.user("member", W8.PW);
        groups.confer("editors", "editor");
        groups.addMember("editors", member);
        IssuedApiKey g = f.auth.apiKeys().issue(member, "ws", null, "posts:*");
        Opened viaGroup = open(f, handledA, g.getPlainKey());
        f.clock.addAndGet(5_000L);
        f.auth.webSocketTick();
        assertTrue("(c) nothing changed", viaGroup.server.isOpen());
        groups.removeMember("editors", member); // a change made outside auth
        f.clock.addAndGet(5_000L);
        f.auth.webSocketTick();
        assertRevoked(viaGroup);
    }

    // SEC-APIKEY-011 AC-8 (a): the recheck re-reads the owner principal itself: a cache entry an ordinary key request
    // refreshed just before a change made outside auth does not hide that change from the recheck
    @Test(timeout = 20000)
    public void SEC_APIKEY_011_AC8_theRecheckDoesNotTrustAFreshCacheEntry() throws Exception {
        start();
        IssuedApiKey k = f.auth.apiKeys().issue(owner, "ws", null, "posts:*");
        Opened o = open(f, handledA, k.getPlainKey());
        f.clock.addAndGet(5_000L);
        assertEquals(200, f.get("/api/x", "Authorization", "ApiKey " + k.getPlainKey()).status()); // cache refreshed
        f.store.setRoles(owner, Collections.<String>emptySet());
        f.auth.webSocketTick();
        assertRevoked(o);
    }

    // SEC-APIKEY-011 AC-8: the recheck's re-read of one user (PrincipalCache.refresh) replaces only that user's entry and
    // does not stop a concurrent load of another user from being cached, unlike an invalidation
    @Test
    public void SEC_APIKEY_011_AC8_aRecheckDoesNotDefeatTheCache() throws Exception {
        final java.util.concurrent.atomic.AtomicInteger loads = new java.util.concurrent.atomic.AtomicInteger();
        final com.snoworca.naruserve.utils.auth.internal.PrincipalCache[] cache =
                new com.snoworca.naruserve.utils.auth.internal.PrincipalCache[1];
        cache[0] = new com.snoworca.naruserve.utils.auth.internal.PrincipalCache(id -> {
            loads.incrementAndGet();
            if (id.equals("b")) {
                cache[0].refresh("a"); // a recheck of user a while user b is being loaded
            }
            return new com.snoworca.naruserve.utils.auth.internal.PrincipalCache.Snapshot(id, 0, true,
                    Collections.<String>emptySet(), Collections.<String>emptySet(),
                    Collections.<String, String>emptyMap(), 0L);
        }, () -> 0L, 5_000L);
        cache[0].get("b");
        int after = loads.get();
        cache[0].get("b");
        cache[0].get("a");
        assertEquals("b stayed cached and a was cached by the refresh", after, loads.get());
    }
}
