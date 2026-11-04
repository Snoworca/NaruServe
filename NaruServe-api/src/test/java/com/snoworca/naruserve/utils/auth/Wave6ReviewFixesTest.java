package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.WebSocketSession;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.utils.auth.apikey.StoredApiKey;
import com.snoworca.naruserve.utils.auth.audit.AuditEvent;
import com.snoworca.naruserve.utils.auth.internal.PrincipalCache;
import com.snoworca.naruserve.utils.auth.user.AuthUser;
import com.snoworca.naruserve.utils.auth.user.ForwardingUserStore;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Test;

/**
 * Regression tests of the wave 6 review fixes: the principal cache invalidation epoch, the WebSocket index race, the
 * A4 re-check under the user lock, A4 null fields, abandoned async continuations, the background executor width, the
 * A4 enabled step, socket closes on an app delete, the key delete grant rule and the G7 self rule in a large subtree.
 */
public class Wave6ReviewFixesTest {

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

    private static final String KEY_HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    // SEC-AUTH-007 review fix (epoch): a load that started before an invalidation is not kept in the cache
    @Test(timeout = 20000)
    public void staleLoadIsNotCachedAfterInvalidation() throws Exception {
        final AtomicInteger loads = new AtomicInteger();
        final CountDownLatch inLoad = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicLong clock = new AtomicLong(1_000L);
        final PrincipalCache cache = new PrincipalCache(id -> {
            int n = loads.incrementAndGet();
            if (n == 1) {
                inLoad.countDown();
                release.await(10, TimeUnit.SECONDS);
            }
            return new PrincipalCache.Snapshot("u", n == 1 ? 0 : 1, true, Collections.<String>emptySet(),
                    Collections.<String>emptySet(), Collections.<String, String>emptyMap(), clock.get());
        }, clock::get, 5_000L);
        final AtomicReference<PrincipalCache.Snapshot> first = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                first.set(cache.get("1"));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        t.start();
        assertTrue(inLoad.await(10, TimeUnit.SECONDS));
        cache.invalidate("1"); // a disable or ver bump through auth while the load is in flight
        release.countDown();
        t.join(10_000L);
        assertEquals(0, first.get().getTokenVersion());
        PrincipalCache.Snapshot next = cache.get("1");
        assertEquals(2, loads.get());
        assertEquals(1, next.getTokenVersion());
    }

    /** A server-side session that is open until closed. */
    private static WebSocketSession session() {
        final AtomicBoolean open = new AtomicBoolean(true);
        return (WebSocketSession) Proxy.newProxyInstance(Wave6ReviewFixesTest.class.getClassLoader(),
                new Class<?>[] {WebSocketSession.class}, (proxy, m, args) -> {
                    switch (m.getName()) {
                        case "isOpen":
                            return open.get();
                        case "close":
                            open.set(false);
                            return null;
                        case "id":
                            return "s";
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == args[0];
                        case "toString":
                            return "session";
                        default:
                            return null;
                    }
                });
    }

    // FR-FLOW-008 review fix (index race): while the ticker removes emptied per-user sets, a connection tracked at the
    // same time is always in the index, so expiry and revocation reach it
    @Test(timeout = 60000)
    public void trackedSocketNeverLandsInAnOrphanedSet() throws Exception {
        com.snoworca.naruserve.utils.auth.user.InMemoryUserStore store =
                new com.snoworca.naruserve.utils.auth.user.InMemoryUserStore();
        String id = store.create(AuthUser.builder().username("u1").passwordHash("x").build()).getId();
        final Auth auth = Auth.builder(AuthTestSupport.SECRET).hashIterations(1000).users(store)
                .logger(new com.snoworca.naruserve.testing.RecordingLogger()).build();
        final AuthPrincipal p = AuthPrincipal.minimal(id, 0, null, null); // an existing user: rechecks keep it
        final AtomicBoolean stop = new AtomicBoolean();
        Thread ticker = new Thread(() -> {
            while (!stop.get()) {
                auth.webSocketTick();
            }
        });
        ticker.start();
        try {
            for (int i = 0; i < 20_000; i++) {
                WebSocketSession ws = session();
                Auth.TrackedSocket t = new Auth.TrackedSocket(p, ws, Long.MAX_VALUE, System.currentTimeMillis());
                auth.track(t);
                assertTrue("round " + i, auth.isTracked(t));
                ws.close(1000, ""); // closed: the ticker removes it and then the emptied set
            }
        } finally {
            stop.set(true);
            ticker.join(10_000L);
        }
    }

    // FR-IAM-014 / SEC-IAM-004 review fix (re-check under the lock): a target promoted to * while an A4 waited for
    // its hash slot is protected: the delegated admin's password reset is refused and the hash is unchanged
    @Test(timeout = 30000)
    public void a4RechecksTheGrantRuleAfterTheHashWait() throws Exception {
        AdminFixture f = fixture().start(b -> b.hashConcurrency(1));
        String actor = f.user("ua", "userswriter", "reader");
        String bob = f.user("bob", "reader");
        String hash = f.stored(bob).getPasswordHash();
        final CountDownLatch hold = new CountDownLatch(1);
        final CountDownLatch inSlot = new CountDownLatch(1);
        f.auth.hashSlots().submit(() -> {
            inSlot.countDown();
            hold.await();
            return "held";
        }, (v, e) -> { });
        assertTrue(inSlot.await(5, TimeUnit.SECONDS));
        long base = f.auth.hashSlots().requested();
        Future<ClientResponse> pending = f.adapter.submit(ClientRequest.json("PATCH", "/auth/admin/users/" + bob,
                "{\"password\":\"new-password-1\"}").header("Authorization", "Bearer " + f.token(actor)));
        // the A4 asks for the slot only after its first grant check passed: once it has asked, it waits for the slot
        Wave10PatTest.awaitRequested(f.auth.hashSlots(), base + 1);
        assertFalse("the A4 waits for the slot", pending.isDone());
        f.base.setRoles(bob, AdminFixture.set("admin"));
        hold.countDown();
        ClientResponse r = pending.get(10, TimeUnit.SECONDS);
        assertEquals(r.text(), 403, r.status());
        assertEquals("insufficient_scope", AuthTestSupport.code(r));
        assertEquals(hash, f.stored(bob).getPasswordHash());
    }

    // FR-IAM-014 review fix (nulls): an A4 field whose value is JSON null is an absent field: nothing changes
    @Test
    public void a4NullFieldsAreAbsent() throws Exception {
        AdminFixture f = fixture().start();
        String root = f.user("root", "admin");
        String bob = f.user("bob", "reader");
        AuthUser before = f.stored(bob);
        ClientResponse r = f.call("PATCH", "/auth/admin/users/" + bob, root,
                "{\"username\":null,\"password\":null,\"enabled\":null,\"roles\":null,\"attributes\":null}");
        assertEquals(r.text(), 200, r.status());
        assertEquals(before, f.stored(bob));
        assertTrue(f.events.updated.isEmpty());
    }

    // IR-IAM-005 review fix (abandoned continuation): with the background executor stopped, an A2 after its hash
    // answers 503 instead of hanging
    @Test(timeout = 30000)
    public void a2AnswersWhenTheBackgroundExecutorIsStopped() throws Exception {
        AdminFixture f = fixture().start();
        String root = f.user("root", "admin");
        ((java.util.concurrent.ExecutorService) f.auth.adminExecutor()).shutdown();
        ClientResponse r = f.call("POST", "/auth/admin/users", root,
                "{\"username\":\"carl\",\"password\":\"long-password-1\"}");
        assertEquals(r.text(), 503, r.status());
        assertEquals("SERVICE_UNAVAILABLE", AuthTestSupport.code(r));
    }

    /** A key store whose updateLastUsed blocks until released. */
    private static final class BlockingKeys extends AdminFixture.ListKeys {
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public void updateLastUsed(String keyId, long lastUsedAtMillis) throws Exception {
            release.await(20, TimeUnit.SECONDS);
        }
    }

    // PERF-APIKEY-001 / IR-IAM-005 review fix (executor width): a blocking lastUsedAt update does not stall an A2
    // completion on the shared background executor
    @Test(timeout = 30000)
    public void blockedUsageUpdateDoesNotStallAdminCompletion() throws Exception {
        final BlockingKeys keys = new BlockingKeys();
        AdminFixture f = fixture().start(b -> b.apiKeys(keys));
        String root = f.user("root", "admin");
        try {
            assertTrue(f.auth.apiKeyUsage().used("k1"));
            Future<ClientResponse> a2 = f.adapter.submit(ClientRequest.json("POST", "/auth/admin/users",
                    "{\"username\":\"dora\",\"password\":\"long-password-1\"}").header("Authorization",
                    "Bearer " + f.token(root)));
            assertEquals(201, a2.get(10, TimeUnit.SECONDS).status());
        } finally {
            keys.release.countDown();
        }
    }

    // SEC-IAM-003 / FR-IAM-014 review fix (enabled step): a password with enabled:false raises ver exactly once
    @Test
    public void passwordAndDisableBumpVerOnce() throws Exception {
        AdminFixture f = fixture().start();
        String root = f.user("root", "admin");
        String bob = f.user("bob", "reader");
        long v = f.stored(bob).getTokenVersion();
        ClientResponse r = f.call("PATCH", "/auth/admin/users/" + bob, root,
                "{\"password\":\"new-password-1\",\"enabled\":false}");
        assertEquals(r.text(), 200, r.status());
        assertEquals(v + 1, f.stored(bob).getTokenVersion());
        assertFalse(f.stored(bob).isEnabled());
    }

    // FR-IAM-014 review fix (enabled step): when setEnabled(false) applied and the bump then fails, applied names
    // "enabled" and the one onUserUpdated carries ENABLED
    @Test
    public void failedBumpAfterDisableReportsEnabled() throws Exception {
        AdminFixture f = new AdminFixture(base -> new ForwardingUserStore(base) {
            @Override
            public boolean bumpTokenVersion(String id) throws Exception {
                throw new java.sql.SQLException("ver column locked");
            }
        }).start();
        String root = f.user("root", "admin");
        String bob = f.user("bob", "reader");
        ClientResponse r = f.call("PATCH", "/auth/admin/users/" + bob, root, "{\"enabled\":false}");
        assertEquals(503, r.status());
        assertEquals(Collections.singletonList("enabled"), AdminFixture.object(r).get("applied"));
        assertFalse(f.stored(bob).isEnabled());
        assertEquals(Collections.singletonList(java.util.EnumSet.of(UserChange.ENABLED)), f.events.updated);
    }

    // FR-FLOW-008 review fix (app delete): auth.users().delete closes the user's wrapped connections at once
    @Test(timeout = 20000)
    public void appDeleteClosesConnections() throws Exception {
        final java.util.concurrent.LinkedBlockingQueue<WebSocketSession> opened =
                new java.util.concurrent.LinkedBlockingQueue<>();
        AdminFixture f = fixture().start(b -> b, x -> x.app.ws("/ws", x.auth.webSocket((req, ws) -> {
            opened.add(ws);
        })));
        String bob = f.user("bob", "reader");
        Future<ClientResponse> h = f.adapter.submit(ClientRequest.upgrade("/ws", "Authorization", "Bearer "
                + f.token(bob)));
        com.snoworca.naruserve.testing.InMemoryWebSocketChannel ch = f.adapter.nextChannel(5, TimeUnit.SECONDS);
        WebSocketSession server = opened.poll(5, TimeUnit.SECONDS);
        assertNotNull(server);
        assertTrue(server.isOpen());
        assertTrue(f.auth.users().delete(bob));
        assertFalse(server.isOpen());
        if (ch.isOpen()) {
            ch.clientClose(1000, "");
        }
        assertEquals(101, h.get(5, TimeUnit.SECONDS).status());
    }

    // IR-IAM-005 / SEC-IAM-004 review fix (key delete): a delegated auth-apikeys:write actor cannot revoke the key of
    // a * holder; the built-in admin can
    @Test
    public void keyDeleteProtectsTheOwner() throws Exception {
        final AdminFixture.ListKeys keys = new AdminFixture.ListKeys();
        AdminFixture f = fixture().start(b -> b.apiKeys(keys));
        String delegated = f.user("kw", "keyswriter", "reader");
        String root = f.user("root", "admin");
        keys.rows.add(StoredApiKey.builder().id("k1").prefix("aBcd1234").secretHash(KEY_HASH).ownerUserId(root)
                .ownerVer(0).name("ci").scopes(Collections.singleton("posts:read")).createdAt(1L).build());
        ClientResponse r = f.call("DELETE", "/auth/admin/apikeys/k1", delegated, null);
        assertEquals(403, r.status());
        assertEquals("insufficient_scope", AuthTestSupport.code(r));
        assertEquals(1, keys.rows.size());
        assertEquals(204, f.call("DELETE", "/auth/admin/apikeys/k1", root, null).status());
        assertTrue(keys.rows.isEmpty());
        assertEquals(404, f.call("DELETE", "/auth/admin/apikeys/k1", root, null).status());
    }

    // SEC-IAM-004 review fix (G7 self rule): an actor below the child group is refused even when the child's
    // subtree holds more than 1,000 other members
    @Test(timeout = 60000)
    public void edgeSelfRuleInALargeSubtree() throws Exception {
        AdminFixture f = fixture().start();
        String actor = f.user("ga", "groupswriter");
        f.groups.confer("big");
        f.groups.confer("sub");
        f.groups.confer("top");
        f.groups.addParent("sub", "big");
        for (int i = 0; i < 1001; i++) {
            f.groups.addMember("big", "m-" + i);
        }
        f.groups.addMember("sub", actor);
        ClientResponse r = f.call("PUT", "/auth/admin/groups/big/parents/top", actor, null);
        assertEquals(403, r.status());
        assertEquals(AuditEvent.SELF_CHANGE_DENIED, f.audit.get(f.audit.size() - 1).getType());
        assertFalse(f.groups.parentsOf(Collections.singleton("big")).contains("top"));
    }
}
