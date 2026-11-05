package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.InMemoryWebSocketChannel;
import com.snoworca.naruserve.utils.auth.apikey.IssuedApiKey;
import com.snoworca.naruserve.utils.auth.apikey.StoredApiKey;
import com.snoworca.naruserve.utils.auth.audit.AuditEvent;
import com.snoworca.naruserve.utils.auth.internal.Doctor;
import com.snoworca.naruserve.utils.auth.internal.UserLocks;
import com.snoworca.naruserve.utils.auth.rbac.RolePolicy;
import com.snoworca.naruserve.utils.auth.user.AuthUser;
import com.snoworca.naruserve.utils.auth.user.FileUserStore;
import com.snoworca.naruserve.utils.auth.user.ForwardingUserStore;
import com.snoworca.naruserve.utils.auth.user.InMemoryUserStore;
import com.snoworca.naruserve.utils.auth.user.UserStore;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The per-owner key lock and the future-key deletion around every auth-triggered ver++ (REL-APIKEY-003), the pending
 * marker after a failed deletion (REL-APIKEY-004), user deletion (FR-APIKEY-008), admin scopes on keys
 * (SEC-APIKEY-009), the key doctor items (OPS-APIKEY-001), service accounts (FR-APIKEY-006) and the recovery outcome
 * (SEC-IAM-006).
 */
public class Wave9KeyOwnersTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final W8 f = new W8();
    private final List<W8> others = new ArrayList<>();
    private final Wave9KeyAuthTest.Keys keys = new Wave9KeyAuthTest.Keys();
    private final Wave9KeyAuthTest.Held held = new Wave9KeyAuthTest.Held();
    private final List<String> lockLog = new CopyOnWriteArrayList<>();
    private final List<AuditEvent> audit = new CopyOnWriteArrayList<>();
    private String owner;
    private String root;

    @After
    public void stop() {
        f.stop();
        for (W8 o : others) {
            o.stop();
        }
    }

    /** A user store whose bump, setEnabled and changePassword record the two lock states. */
    final class LockSpy extends ForwardingUserStore {
        volatile java.util.function.Consumer<String> onDelete = id -> { };

        LockSpy(UserStore d) {
            super(d);
        }

        @Override
        public boolean delete(String id) throws Exception {
            onDelete.accept(id);
            return super.delete(id);
        }

        private void note(String what, String id) {
            lockLog.add(what + " key=" + ApiKeyOwners.heldByCurrentThread(id) + " user="
                    + UserLocks.of(id).isHeldByCurrentThread());
        }

        @Override
        public boolean bumpTokenVersion(String id) throws Exception {
            note("bump", id);
            return super.bumpTokenVersion(id);
        }

        @Override
        public boolean changePassword(String id, @Nullable String expectedOldHash, long expectedVer, String newHash)
                throws Exception {
            note("changePassword", id);
            return super.changePassword(id, expectedOldHash, expectedVer, newHash);
        }
    }

    private void start(java.util.function.UnaryOperator<Auth.Builder> config) throws Exception {
        owner = f.user("bot", W8.PW, "reader");
        root = f.user("root", W8.PW, "admin");
        f.users = new LockSpy(f.store);
        f.start(b -> config.apply(b.apiKeys(keys).audit(audit::add)));
        f.auth.apiKeyOwners().deletions(held);
        f.auth.apiKeyOwners().lockListener((o, userHeld) -> lockLog.add("keyLock " + o + " user=" + userHeld));
    }

    /** Sets the stored ver of {@code id} (a file store losing writes moves it back). */
    private void setVer(String id, long ver) throws Exception {
        f.store.update(f.stored(id).toBuilder().tokenVersion(ver).build());
    }

    private String bearer(String id) throws Exception {
        return "Bearer " + f.auth.accessToken(id, f.stored(id).getTokenVersion(), 900_000L);
    }

    private long verOf(String id) {
        try {
            return f.stored(id).getTokenVersion();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private ClientResponse withKey(String key) {
        return f.get("/api/x", "Authorization", "ApiKey " + key);
    }

    private ClientResponse patch(String id, String json) throws Exception {
        return f.adapter.send(ClientRequest.json("PATCH", "/auth/admin/users/" + id, json)
                .header("Authorization", bearer(root)));
    }

    /** ver 6, a key K issued at ver 6 and never used, then the store loses that write: ver 5. */
    private IssuedApiKey futureKey() throws Exception {
        setVer(owner, 6);
        IssuedApiKey k = f.auth.apiKeys().issue(owner, "future", null, "posts:read");
        assertEquals(6, k.getKey().getOwnerVer());
        setVer(owner, 5);
        keys.deletes.clear();
        lockLog.clear();
        return k;
    }

    // ---------------------------------------------------------------------------------------------- REL-APIKEY-003

    // REL-APIKEY-003 AC-1: SINGLE_INSTANCE, ver rolled back 6 -> 5, key K with ownerVer 6 never used, the owner's
    // logout ?all=true: deleteFutureByOwner(u, 5) deletes K inside the owner lock before ver becomes 6, the ver++ write
    // runs while the same thread holds that lock, and K then gets 401 invalid_api_key
    @Test
    public void REL_APIKEY_003_AC1_logoutAllDeletesTheFutureKeyFirst() throws Exception {
        start(b -> b);
        IssuedApiKey k = futureKey();
        keys.futureCheck = id -> lockLog.add("delete key=" + ApiKeyOwners.heldByCurrentThread(id) + " ver="
                + verOf(id));
        ClientResponse out = f.post("/auth/logout?all=true", null, "Authorization", bearer(owner));
        assertEquals(out.text(), 204, out.status());
        assertEquals(6, f.stored(owner).getTokenVersion());
        assertEquals(Collections.singletonList("deleteFutureByOwner " + owner + " 5"), keys.deletes);
        assertEquals(Arrays.asList("keyLock " + owner + " user=true", "delete key=true ver=5",
                "bump key=true user=true"), lockLog);
        assertNull(keys.d.findById(k.getKey().getId()));
        ClientResponse r = withKey(k.getPlainKey());
        assertEquals(401, r.status());
        assertEquals("invalid_api_key", W8.code(r));
    }

    // REL-APIKEY-003 AC-3: SINGLE_INSTANCE, deleteFutureByOwner throws during a disable: the user is disabled, ver++
    // happens, an ERROR log is written and a pending marker is left
    @Test
    public void REL_APIKEY_003_AC3_aFailedDeletionStillDisables() throws Exception {
        start(b -> b);
        futureKey();
        keys.failFuture = 1;
        int errors = f.logger.count("ERROR");
        ClientResponse r = patch(owner, "{\"enabled\":false}");
        assertEquals(r.text(), 200, r.status());
        assertFalse(f.stored(owner).isEnabled());
        assertEquals(6, f.stored(owner).getTokenVersion());
        assertEquals(errors + 1, f.logger.count("ERROR"));
        assertEquals(Long.valueOf(5), f.auth.apiKeyOwners().marker(owner));
    }

    // REL-APIKEY-003 AC-7: ver rolled back 6 -> 5, key K with ownerVer 6, the built-in admin sends A4 {password} for
    // owner u: deleteFutureByOwner(u, 5) deletes K inside u's key lock before changePassword raises ver to 6, the
    // changePassword write runs while the same thread holds that lock, and K then gets 401
    @Test
    public void REL_APIKEY_003_AC7_adminResetDeletesTheFutureKeyFirst() throws Exception {
        start(b -> b);
        IssuedApiKey k = futureKey();
        keys.futureCheck = id -> lockLog.add("delete key=" + ApiKeyOwners.heldByCurrentThread(id) + " ver="
                + verOf(id));
        ClientResponse r = patch(owner, "{\"password\":\"an admin reset password\"}");
        assertEquals(r.text(), 200, r.status());
        assertEquals(6, f.stored(owner).getTokenVersion());
        assertEquals(Collections.singletonList("deleteFutureByOwner " + owner + " 5"), keys.deletes);
        assertEquals(Arrays.asList("keyLock " + owner + " user=true", "delete key=true ver=5",
                "changePassword key=true user=true"), lockLog);
        assertEquals(401, withKey(k.getPlainKey()).status());
    }

    // REL-APIKEY-003 AC-7: after the A4 reset K gets 401 with body code invalid_api_key
    @Test
    public void REL_APIKEY_003_AC7_theKeyThenAnswersInvalidApiKey() throws Exception {
        start(b -> b);
        IssuedApiKey k = futureKey();
        assertEquals(200, patch(owner, "{\"password\":\"an admin reset password\"}").status());
        ClientResponse r = withKey(k.getPlainKey());
        assertEquals(401, r.status());
        assertEquals("invalid_api_key", W8.code(r));
    }

    // The lock order of REL-APIKEY-003 over the five ver++ paths and the app issue: the key owner lock is taken only
    // while the same thread holds the user write lock, and the app issue never takes the user lock inside the key lock
    @Test
    public void lockOrderOnEveryVerBumpAndOnTheAppIssue() throws Exception {
        start(b -> b.recovery(true));
        String setup = f.auth.pendingSetupToken();
        // logout ?all=true
        assertEquals(204, f.post("/auth/logout?all=true", null, "Authorization", bearer(owner)).status());
        // self-service password change
        assertEquals(200, f.post("/auth/password", Wave9PasswordTest.change(W8.PW, "a fresh new passphrase"),
                "Authorization", bearer(owner)).status());
        // A4 password reset and A4 disable
        assertEquals(200, patch(owner, "{\"password\":\"an admin reset password\"}").status());
        assertEquals(200, patch(owner, "{\"enabled\":false}").status());
        // recovery of the existing user
        assertEquals(200, f.post("/auth/setup", "{\"token\":\"" + setup + "\",\"username\":\"bot\","
                + "\"password\":\"a recovered passphrase\"}").status());
        List<String> keyLocks = new ArrayList<>();
        for (String l : lockLog) {
            if (l.startsWith("keyLock")) {
                keyLocks.add(l);
            }
            if (l.startsWith("bump") || l.startsWith("changePassword")) {
                assertEquals(l, true, l.endsWith("key=true user=true"));
            }
        }
        assertEquals(lockLog.toString(), 5, keyLocks.size());
        for (String l : keyLocks) {
            assertTrue(l, l.endsWith("user=true"));
        }
        lockLog.clear();
        keys.insertCheck = id -> assertFalse("no user lock inside the key lock", UserLocks.of(id).isHeldByCurrentThread());
        f.auth.apiKeys().issue(owner, "app", null, "posts:read");
        assertEquals(Collections.singletonList("keyLock " + owner + " user=false"), lockLog);
    }

    // ---------------------------------------------------------------------------------------------- REL-APIKEY-004

    // REL-APIKEY-004 AC-1: ver rolled back 6 -> 5, key K ownerVer 6, ?all=true with a failing deleteFutureByOwner(u, 5):
    // ver becomes 6, marker (u, 5) remains, a request with K gets 401 invalid_api_key and schedules one re-delete
    // REL-APIKEY-004 AC-5: with the deletion executor held, 100 requests with violating keys produce one pending
    // re-delete; after release, a request after that job started schedules another
    @Test
    public void REL_APIKEY_004_AC1_AC5_theMarkerRejectsAndReschedules() throws Exception {
        start(b -> b.apiKeyFailureLimit(1000, 900_000L));
        IssuedApiKey k = futureKey();
        keys.failFuture = 1;
        assertEquals(204, f.post("/auth/logout?all=true", null, "Authorization", bearer(owner)).status());
        assertEquals(6, f.stored(owner).getTokenVersion());
        assertEquals(Long.valueOf(5), f.auth.apiKeyOwners().marker(owner));
        for (int i = 0; i < 100; i++) {
            ClientResponse r = withKey(k.getPlainKey());
            assertEquals(401, r.status());
            assertEquals("invalid_api_key", W8.code(r));
        }
        assertEquals(1, held.queued.size());
        keys.failFuture = 1; // the re-delete fails too, so the marker stays
        held.runAll();
        assertEquals(Long.valueOf(5), f.auth.apiKeyOwners().marker(owner));
        assertEquals(401, withKey(k.getPlainKey()).status());
        assertEquals("a request after the job started schedules another", 1, held.queued.size());
    }

    // REL-APIKEY-004 AC-3: with marker (u, 5), a later ver++ whose retry deletion fails keeps the marker at 5
    @Test
    public void REL_APIKEY_004_AC3_aFailedRetryKeepsTheOldVer() throws Exception {
        start(b -> b);
        futureKey();
        keys.failFuture = 1;
        assertEquals(204, f.post("/auth/logout?all=true", null, "Authorization", bearer(owner)).status());
        keys.failFuture = 1;
        assertEquals(204, f.post("/auth/logout?all=true", null, "Authorization", bearer(owner)).status());
        assertEquals(7, f.stored(owner).getTokenVersion());
        assertEquals(Long.valueOf(5), f.auth.apiKeyOwners().marker(owner));
    }

    // REL-APIKEY-004 AC-4: the cleanup scheduler reads u from the marker list, an issuance then clears the marker and
    // inserts key N (ownerVer 6), then the scheduler takes u's lock: it re-reads the marker, finds none, does nothing,
    // and N remains
    @Test
    public void REL_APIKEY_004_AC4_theSchedulerReReadsTheMarkerInsideTheLock() throws Exception {
        start(b -> b);
        futureKey();
        keys.failFuture = 1;
        assertEquals(204, f.post("/auth/logout?all=true", null, "Authorization", bearer(owner)).status());
        final IssuedApiKey[] n = new IssuedApiKey[1];
        f.auth.apiKeyOwners().retryHook(o -> {
            if (n[0] == null) {
                n[0] = f.auth.apiKeys().issue(owner, "N", null, "posts:read");
            }
        });
        keys.deletes.clear();
        f.auth.cleanupRun();
        assertNotNull(n[0]);
        assertEquals(6, n[0].getKey().getOwnerVer());
        assertNull(f.auth.apiKeyOwners().marker(owner));
        assertEquals("only the issuance deleted", Arrays.asList("deleteFutureByOwner " + owner + " 5",
                "deleteFutureByOwner " + owner + " 6"), keys.deletes);
        assertNotNull(keys.d.findById(n[0].getKey().getId()));
        assertEquals(200, withKey(n[0].getPlainKey()).status());
    }

    // REL-APIKEY-004 AC-7: the marker is not persisted: a new Auth over the same stores starts with none
    @Test
    public void REL_APIKEY_004_AC7_theMarkerIsInMemoryOnly() throws Exception {
        start(b -> b);
        futureKey();
        keys.failFuture = 1;
        assertEquals(204, f.post("/auth/logout?all=true", null, "Authorization", bearer(owner)).status());
        assertNotNull(f.auth.apiKeyOwners().marker(owner));
        W8 again = new W8();
        again.users = f.store;
        others.add(again);
        again.start(b -> b.apiKeys(keys));
        assertNull(again.auth.apiKeyOwners().marker(owner));
    }

    // REL-APIKEY-004 AC-8: after one failed ?all=true (marker (u, 5)) the store recovers: (a) one cleanup run calls
    // deleteFutureByOwner(u, 5) inside u's lock, K is gone and the marker is cleared; (b) the re-delete a request with K
    // scheduled does the same once the executor runs; (c) a later ?all=true first deletes, then raises ver to 7, and a
    // key issued afterwards with ownerVer 7 authenticates
    @Test
    public void REL_APIKEY_004_AC8_successfulRetries() throws Exception {
        for (int way = 0; way < 3; way++) {
            W8 w = way == 0 ? f : new W8();
            if (way > 0) {
                others.add(w);
            }
            Wave9KeyAuthTest.Keys ks = way == 0 ? keys : new Wave9KeyAuthTest.Keys();
            Wave9KeyAuthTest.Held h = new Wave9KeyAuthTest.Held();
            String u = w.user("bot", W8.PW, "reader");
            w.start(b -> b.apiKeys(ks));
            w.auth.apiKeyOwners().deletions(h);
            w.store.update(w.stored(u).toBuilder().tokenVersion(6).build());
            IssuedApiKey k = w.auth.apiKeys().issue(u, "future", null, "posts:read");
            w.store.update(w.stored(u).toBuilder().tokenVersion(5).build());
            ks.failFuture = 1;
            assertEquals(204, w.post("/auth/logout?all=true", null, "Authorization",
                    "Bearer " + w.auth.accessToken(u, 5, 900_000L)).status());
            final List<Boolean> inLock = new CopyOnWriteArrayList<>();
            ks.futureCheck = id -> inLock.add(ApiKeyOwners.heldByCurrentThread(id));
            if (way == 0) {
                w.auth.cleanupRun();
            } else if (way == 1) {
                assertEquals(401, w.get("/api/x", "Authorization", "ApiKey " + k.getPlainKey()).status());
                h.runAll();
            } else {
                assertEquals(204, w.post("/auth/logout?all=true", null, "Authorization",
                        "Bearer " + w.auth.accessToken(u, 6, 900_000L)).status());
                assertEquals(7, w.stored(u).getTokenVersion());
                String k7 = w.auth.apiKeys().issue(u, "seven", null, "posts:read").getPlainKey();
                assertEquals(200, w.get("/api/x", "Authorization", "ApiKey " + k7).status());
            }
            assertFalse("way " + way, inLock.isEmpty());
            assertFalse(inLock.contains(Boolean.FALSE));
            assertNull("way " + way, ks.d.findById(k.getKey().getId()));
            assertNull(w.auth.apiKeyOwners().marker(u));
        }
    }

    // REL-APIKEY-004 AC-6: a WebSocket opened with a key whose ownerVer is above the oldVer of a present marker is
    // closed at the next recheck; deleting the owner via A5 deletes all keys and then clears the marker under the lock
    @Test(timeout = 20000)
    public void REL_APIKEY_004_AC6_webSocketRecheckAndDelete() throws Exception {
        final LinkedBlockingQueue<Object> handled = new LinkedBlockingQueue<>();
        f.routes = w -> w.app.ws("/ws", w.auth.webSocket((req, ws) -> handled.add(ws)));
        start(b -> b);
        IssuedApiKey k = f.auth.apiKeys().issue(owner, "ws", null, "posts:read");
        Future<ClientResponse> hs = f.adapter.submit(ClientRequest.upgrade("/ws", "Authorization",
                "ApiKey " + k.getPlainKey()));
        InMemoryWebSocketChannel ch = f.adapter.nextChannel(5, TimeUnit.SECONDS);
        assertNotNull(handled.poll(5, TimeUnit.SECONDS));
        f.auth.apiKeyOwners().mark(owner, k.getKey().getOwnerVer() - 1);
        f.clock.addAndGet(5_000L);
        f.auth.webSocketTick();
        for (int i = 0; i < 500 && ch.isOpen(); i++) {
            Thread.sleep(10);
        }
        assertFalse(ch.isOpen());
        assertEquals(1008, ch.closeCode());
        assertEquals(101, hs.get(5, TimeUnit.SECONDS).status());
        keys.deleteCheck = id -> assertNotNull("the marker is still there while the keys go",
                f.auth.apiKeyOwners().marker(id));
        assertEquals(204, f.adapter.send(ClientRequest.of("DELETE", "/auth/admin/users/" + owner)
                .header("Authorization", bearer(root))).status());
        assertNull(f.auth.apiKeyOwners().marker(owner));
        assertTrue(keys.d.listByOwner(owner).isEmpty());
    }

    // REL-APIKEY-004 AC-8: every successful retry passes the marker's oldVer: (a) one cleanup run and (b) the re-delete a
    // request scheduled each call deleteFutureByOwner(u, 5); (c) a later ?all=true calls it first, while ver is still 6
    @Test
    public void REL_APIKEY_004_AC8_theRetriesPassTheMarkersVer() throws Exception {
        for (int way = 0; way < 3; way++) {
            final W8 w = new W8();
            others.add(w);
            Wave9KeyAuthTest.Keys ks = new Wave9KeyAuthTest.Keys();
            Wave9KeyAuthTest.Held h = new Wave9KeyAuthTest.Held();
            String u = w.user("bot", W8.PW, "reader");
            w.start(b -> b.apiKeys(ks));
            w.auth.apiKeyOwners().deletions(h);
            w.store.update(w.stored(u).toBuilder().tokenVersion(6).build());
            IssuedApiKey k = w.auth.apiKeys().issue(u, "future", null, "posts:read");
            w.store.update(w.stored(u).toBuilder().tokenVersion(5).build());
            ks.failFuture = 1;
            assertEquals(204, w.post("/auth/logout?all=true", null, "Authorization",
                    "Bearer " + w.auth.accessToken(u, 5, 900_000L)).status());
            ks.deletes.clear();
            final List<Long> verAtCall = new CopyOnWriteArrayList<>();
            ks.futureCheck = id -> {
                try {
                    verAtCall.add(w.stored(id).getTokenVersion());
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            };
            if (way == 0) {
                w.auth.cleanupRun();
            } else if (way == 1) {
                assertEquals(401, w.get("/api/x", "Authorization", "ApiKey " + k.getPlainKey()).status());
                h.runAll();
            } else {
                assertEquals(204, w.post("/auth/logout?all=true", null, "Authorization",
                        "Bearer " + w.auth.accessToken(u, 6, 900_000L)).status());
                assertEquals(7, w.stored(u).getTokenVersion());
            }
            assertEquals("way " + way, "deleteFutureByOwner " + u + " 5", ks.deletes.get(0));
            assertEquals("way " + way, Long.valueOf(6), verAtCall.get(0));
        }
    }

    // REL-APIKEY-004 AC-6: A5 deletes all keys and then clears the marker under the owner lock: one lock acquisition
    // covers the key deletion (marker still there) and the row deletion (marker gone)
    @Test
    public void REL_APIKEY_004_AC6_theMarkerIsClearedInsideTheOwnerLock() throws Exception {
        final List<String> order = new CopyOnWriteArrayList<>();
        start(b -> b);
        f.auth.apiKeys().issue(owner, "k", null, "posts:read");
        f.auth.apiKeyOwners().mark(owner, 5);
        keys.deleteCheck = id -> order.add("keys lock=" + ApiKeyOwners.heldByCurrentThread(id) + " marker="
                + f.auth.apiKeyOwners().marker(id));
        ((LockSpy) f.users).onDelete = id -> order.add("row lock=" + ApiKeyOwners.heldByCurrentThread(id) + " marker="
                + f.auth.apiKeyOwners().marker(id));
        lockLog.clear();
        assertEquals(204, f.adapter.send(ClientRequest.of("DELETE", "/auth/admin/users/" + owner)
                .header("Authorization", bearer(root))).status());
        assertEquals(Arrays.asList("keys lock=true marker=5", "row lock=true marker=null"), order);
        assertEquals(lockLog.toString(), Collections.singletonList("keyLock " + owner + " user=true"), lockLog);
    }

    // ---------------------------------------------------------------------------------------------- FR-APIKEY-008

    // FR-APIKEY-008 AC-1: SINGLE_INSTANCE with marker (u, 5): deleting u removes all keys, then clears the marker, then
    // deletes the user row
    // FR-APIKEY-008 AC-2: after deleting a user who owns 3 keys via A5, each of the 3 keys gets 401 invalid_api_key
    @Test
    public void FR_APIKEY_008_AC1_AC2_deletionOrder() throws Exception {
        final List<String> order = new CopyOnWriteArrayList<>();
        start(b -> b);
        List<String> plain = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            plain.add(f.auth.apiKeys().issue(owner, "k" + i, null, "posts:read").getPlainKey());
        }
        for (String p : plain) {
            assertEquals(200, withKey(p).status());
        }
        f.auth.apiKeyOwners().mark(owner, 5);
        keys.deleteCheck = id -> order.add("keys marker=" + f.auth.apiKeyOwners().marker(id));
        ((LockSpy) f.users).onDelete = id -> order.add("row marker=" + f.auth.apiKeyOwners().marker(id));
        assertEquals(204, f.adapter.send(ClientRequest.of("DELETE", "/auth/admin/users/" + owner)
                .header("Authorization", bearer(root))).status());
        assertEquals(Arrays.asList("keys marker=5", "row marker=null"), order);
        for (String p : plain) {
            ClientResponse r = withKey(p);
            assertEquals(401, r.status());
            assertEquals("invalid_api_key", W8.code(r));
        }
    }

    // ---------------------------------------------------------------------------------------------- SEC-APIKEY-009

    private W8 node(java.util.function.UnaryOperator<Auth.Builder> config) throws Exception {
        W8 w = new W8();
        w.users = f.store;
        others.add(w);
        w.start(b -> config.apply(b.apiKeys(keys).roles(RolePolicy.builtin())));
        return w;
    }

    // SEC-APIKEY-009 AC-2: opt-in off or on, a key with scope * calling GET /auth/admin/users -> 403 insufficient_scope
    // SEC-APIKEY-009 AC-3: a * key issued without the opt-in, app restarted with the opt-in: the same key on GET
    // /auth/admin/users -> 403 insufficient_scope
    @Test
    public void SEC_APIKEY_009_AC2_AC3_starNeverCoversAdminScopes() throws Exception {
        start(b -> b);
        String star = f.auth.apiKeys().issue(root, "star", null, "*").getPlainKey();
        ClientResponse off = f.get("/auth/admin/users", "Authorization", "ApiKey " + star);
        assertEquals(403, off.status());
        assertEquals("insufficient_scope", W8.code(off));
        W8 on = node(b -> b.allowAdminScopesOnApiKeys(true));
        ClientResponse r = on.get("/auth/admin/users", "Authorization", "ApiKey " + star);
        assertEquals(403, r.status());
        assertEquals("insufficient_scope", W8.code(r));
        ClientResponse app = on.get("/api/x", "Authorization", "ApiKey " + star);
        assertEquals(200, app.status());
    }

    // SEC-APIKEY-009 AC-4: a key issued with auth-users:write under the opt-in, app restarted without it: 403 on admin
    // endpoints, its other scopes keep working, and the key row is not deleted
    @Test
    public void SEC_APIKEY_009_AC4_storedAdminScopesAreIgnoredWithoutTheOptIn() throws Exception {
        owner = f.user("bot", W8.PW, "reader");
        root = f.user("root", W8.PW, "admin");
        f.start(b -> b.apiKeys(keys).allowAdminScopesOnApiKeys(true).roles(RolePolicy.builtin()));
        IssuedApiKey k = f.auth.apiKeys().issue(root, "admin", null, "auth-users:read", "posts:read");
        assertEquals(200, f.get("/auth/admin/users", "Authorization", "ApiKey " + k.getPlainKey()).status());
        W8 off = node(b -> b);
        ClientResponse r = off.get("/auth/admin/users", "Authorization", "ApiKey " + k.getPlainKey());
        assertEquals(403, r.status());
        assertEquals("insufficient_scope", W8.code(r));
        assertEquals(200, off.get("/api/x", "Authorization", "ApiKey " + k.getPlainKey()).status());
        assertNotNull(keys.d.findById(k.getKey().getId()));
    }

    // SEC-APIKEY-009 AC-4: the scope the criterion names: a key issued with auth-users:write under the opt-in writes
    // through A4 there; on a restart without the opt-in the same write gets 403, its other scopes keep working and the
    // key row stays
    @Test
    public void SEC_APIKEY_009_AC4_aStoredWriteScopeIsIgnoredToo() throws Exception {
        owner = f.user("bot", W8.PW);
        root = f.user("root", W8.PW, "admin");
        f.start(b -> b.apiKeys(keys).allowAdminScopesOnApiKeys(true).roles(RolePolicy.builtin()));
        IssuedApiKey k = f.auth.apiKeys().issue(root, "admin", null, "auth-users:write", "posts:read");
        String path = "/auth/admin/users/" + owner;
        ClientResponse on = f.adapter.send(ClientRequest.json("PATCH", path, "{\"attributes\":{\"team\":\"blue\"}}")
                .header("Authorization", "ApiKey " + k.getPlainKey()));
        assertEquals(on.text(), 200, on.status());
        W8 off = node(b -> b);
        ClientResponse r = off.adapter.send(ClientRequest.json("PATCH", path, "{\"attributes\":{\"team\":\"red\"}}")
                .header("Authorization", "ApiKey " + k.getPlainKey()));
        assertEquals(r.text(), 403, r.status());
        assertEquals("insufficient_scope", W8.code(r));
        assertEquals("blue", f.stored(owner).getAttributes().get("team"));
        assertEquals(200, off.get("/api/x", "Authorization", "ApiKey " + k.getPlainKey()).status());
        assertNotNull(keys.d.findById(k.getKey().getId()));
    }

    // SEC-APIKEY-009 AC-5: opt-in on, a key holding named auth-* scopes: granting the built-in admin role (*),
    // disabling a * holder, or adding a user to a group that grants * each gets 403 insufficient_scope
    // SEC-APIKEY-009 AC-7: opt-in on, a key with scopes * and auth-users:write whose owner holds *: granting user X a
    // role whose scopes include auth-apikeys:read -> 403 insufficient_scope and X's roles are unchanged
    @Test
    public void SEC_APIKEY_009_AC5_AC7_noKeyCoversAStar() throws Exception {
        AdminFixture a = new AdminFixture();
        a.start(b -> b.apiKeys(keys).allowAdminScopesOnApiKeys(true));
        try {
            noKeyCoversAStar(a);
        } finally {
            a.app.shutdown(5, TimeUnit.SECONDS);
        }
    }

    private void noKeyCoversAStar(AdminFixture a) throws Exception {
        String admin = a.user("root", "admin");
        String other = a.user("other", "admin");
        String x = a.user("x", "reader");
        a.groups.confer("admins", "admin");
        String named = a.auth.apiKeys().issue(admin, "named", null, "auth-users:*", "auth-groups:*").getPlainKey();
        List<ClientResponse> answers = new ArrayList<>();
        answers.add(a.adapter.send(ClientRequest.json("PATCH", "/auth/admin/users/" + x, "{\"roles\":[\"admin\"]}")
                .header("Authorization", "ApiKey " + named)));
        answers.add(a.adapter.send(ClientRequest.json("PATCH", "/auth/admin/users/" + other, "{\"enabled\":false}")
                .header("Authorization", "ApiKey " + named)));
        answers.add(a.adapter.send(ClientRequest.of("PUT", "/auth/admin/groups/admins/members/" + x)
                .header("Authorization", "ApiKey " + named)));
        for (ClientResponse r : answers) {
            assertEquals(r.text(), 403, r.status());
            assertEquals("insufficient_scope", AuthTestSupport.code(r));
        }
        String mixed = a.auth.apiKeys().issue(admin, "mixed", null, "*", "auth-users:write").getPlainKey();
        ClientResponse grant = a.adapter.send(ClientRequest.json("PATCH", "/auth/admin/users/" + x,
                "{\"roles\":[\"reader\",\"keysreader\"]}").header("Authorization", "ApiKey " + mixed));
        assertEquals(grant.text(), 403, grant.status());
        assertEquals("insufficient_scope", AuthTestSupport.code(grant));
        assertEquals(Collections.singleton("reader"), a.stored(x).getRoles());
        assertTrue(a.stored(other).isEnabled());
        assertFalse(a.groups.membersOf("admins").contains(x));
    }

    // SEC-APIKEY-009 statement (the app API half of its first criterion; the endpoint half waits for W10): auth.apiKeys().issue(owner, "n", null, "auth-users:write")
    // throws IllegalArgumentException with the opt-in off
    @Test
    public void appIssueRefusesAdminScopesWithoutTheOptIn() throws Exception {
        start(b -> b);
        try {
            f.auth.apiKeys().issue(owner, "n", null, "auth-users:write");
            fail("issued an admin scope");
        } catch (IllegalArgumentException expected) {
            // refused
        }
    }

    // SEC-IAM-005 AC-7: opt-in on, a key holding auth-groups:* and * whose owner holds * through admin and is not a
    // member: DELETE /auth/admin/groups/admins (confers *, its member another * holder) -> 403 insufficient_scope
    // without the enumeration skip and the group remains; the same key deleting an empty *-conferring group succeeds
    @Test
    public void SEC_IAM_005_AC7_aKeyNeverSkipsTheEnumeration() throws Exception {
        AdminFixture a = new AdminFixture();
        a.start(b -> b.apiKeys(keys).allowAdminScopesOnApiKeys(true));
        try {
            keyNeverSkips(a);
        } finally {
            a.app.shutdown(5, TimeUnit.SECONDS);
        }
    }

    private void keyNeverSkips(AdminFixture a) throws Exception {
        String admin = a.user("root", "admin");
        String other = a.user("other", "admin");
        a.groups.confer("admins", "admin");
        a.groups.addMember("admins", other);
        a.groups.confer("empty", "admin");
        String k = a.auth.apiKeys().issue(admin, "groups", null, "auth-groups:*", "*").getPlainKey();
        a.groups.traversals.set(0);
        ClientResponse r = a.adapter.send(ClientRequest.of("DELETE", "/auth/admin/groups/admins")
                .header("Authorization", "ApiKey " + k));
        assertEquals(r.text(), 403, r.status());
        assertEquals("insufficient_scope", AuthTestSupport.code(r));
        assertTrue("the members were enumerated", a.groups.traversals.get() > 0);
        assertTrue(a.groups.groupExists("admins"));
        ClientResponse empty = a.adapter.send(ClientRequest.of("DELETE", "/auth/admin/groups/empty")
                .header("Authorization", "ApiKey " + k));
        assertEquals(empty.text(), 204, empty.status());
        assertFalse(a.groups.groupExists("empty"));
    }

    // ---------------------------------------------------------------------------------------------- OPS-APIKEY-001

    /** A key store whose atomic-insert declaration can be turned off. */
    static final class Declared extends Wave9KeyAuthTest.Keys {
        final boolean atomic;

        Declared(boolean atomic) {
            this.atomic = atomic;
        }

        @Override
        public boolean supportsAtomicLimitInsert() {
            return atomic;
        }
    }

    /**
     * The MULTI_INSTANCE key fixture of OPS-APIKEY-001 AC-7 over one shared app database ({@link AppStores}: test
     * implementations of the SPIs, none a built-in in-memory or file store, the user store declaring atomic field
     * writes), with {@code keyStore} for API keys.
     */
    private Auth.Builder multi(AppStores ds, com.snoworca.naruserve.utils.auth.apikey.ApiKeyStore keyStore) {
        return Auth.builder(AuthTestSupport.SECRET).hashIterations(1000).logger(f.logger).users(ds.users())
                .deployment(Deployment.MULTI_INSTANCE)
                .refreshTokens(ds.refreshTokens())
                .loginThrottle(ds.loginThrottle())
                .deviceTrust(ds.deviceTrust()).apiKeys(keyStore);
    }

    private static List<String> items(Doctor d, Doctor.Level level) {
        List<String> out = new ArrayList<>();
        for (Doctor.Finding x : d.findings()) {
            if (x.getLevel() == level) {
                out.add(x.getItem() + ": " + x.getMessage());
            }
        }
        return out;
    }

    // OPS-APIKEY-001 AC-7: the MULTI_INSTANCE key fixture (shared app-provided stores, atomic field writes and atomic
    // limit insert declared): build() succeeds, the doctor reports no FAIL naming supportsAtomicFieldWrites or
    // supportsAtomicLimitInsert, and the app starts
    // OPS-APIKEY-001 AC-1: the same fixture with an ApiKeyStore whose supportsAtomicLimitInsert() is false: build()
    // succeeds, the doctor FAILs naming supportsAtomicLimitInsert (not supportsAtomicFieldWrites), the app does not start
    @Test
    public void OPS_APIKEY_001_AC1_AC7_atomicLimitInsertUnderMultiInstance() throws Exception {
        AppStores ds = new AppStores();
        AppStores.Keys keys = ds.apiKeys();
        W8 ok = new W8();
        others.add(ok);
        ok.start(b -> multi(ds, keys));
        List<String> fails = items(ok.auth.doctor(ok.app), Doctor.Level.FAIL);
        assertTrue(fails.toString(), fails.isEmpty());
        assertEquals(401, ok.get("/api/x").status());

        W8 bad = new W8();
        others.add(bad);
        try {
            bad.start(b -> multi(ds, new Declared(false)));
            fail("started");
        } catch (RuntimeException expected) {
            assertTrue(expected.toString(), String.valueOf(expected.getMessage()).contains("supportsAtomicLimitInsert")
                    || String.valueOf(expected.getCause()).contains("supportsAtomicLimitInsert"));
        }
        List<String> badFails = items(bad.auth.doctor(bad.app), Doctor.Level.FAIL);
        assertEquals(badFails.toString(), 1, badFails.size());
        assertTrue(badFails.get(0).contains("supportsAtomicLimitInsert"));
        assertFalse(badFails.get(0).contains("supportsAtomicFieldWrites"));
    }

    /** A user store whose reads come from a replica that can lag one ver behind. */
    static final class Replica extends ForwardingUserStore {
        volatile boolean lag;

        Replica(UserStore d) {
            super(d);
        }

        @Override
        public @Nullable AuthUser findById(String id) throws Exception {
            AuthUser u = super.findById(id);
            return u != null && lag ? u.toBuilder().tokenVersion(u.getTokenVersion() - 1).build() : u;
        }
    }

    // SEC-APIKEY-006 AC-4: MULTI_INSTANCE on the key fixture of OPS-APIKEY-001 AC-7, node B's UserStore reads a
    // replica one ver behind the key's ownerVer: 401 invalid_api_key and an ERROR, no delete call; once the replica
    // catches up the same key authenticates
    @Test
    public void SEC_APIKEY_006_AC4_multiInstanceKeepsAFutureLookingKey() throws Exception {
        AppStores ds = new AppStores();
        final Declared ks = new Declared(true); // a shared key store declaring atomic limited inserts
        W8 b = new W8();
        others.add(b);
        final Replica[] replica = new Replica[1];
        b.start(x -> {
            replica[0] = new Replica(ds.users());
            return multi(ds, ks).users(replica[0]).roles(RolePolicy.builder().role("reader", "posts:read").build())
                    .logger(b.logger).timeSource(b.clock::get);
        });
        AppStores.Users primary = ds.users();
        AuthUser u = primary.create(AuthUser.builder().username("bot").passwordHash("x").roles("reader").build());
        primary.bumpTokenVersion(u.getId());
        IssuedApiKey k = b.auth.apiKeys().issue(u.getId(), "ci", null, "posts:read");
        b.auth.apiKeyOwners().deletions(held);
        replica[0].lag = true;
        int errors = b.logger.count("ERROR");
        ClientResponse r = b.get("/api/x", "Authorization", "ApiKey " + k.getPlainKey());
        assertEquals(r.text(), 401, r.status());
        assertEquals("invalid_api_key", W8.code(r));
        assertEquals(errors + 1, b.logger.count("ERROR"));
        held.runAll();
        assertTrue(ks.deletes.toString(), ks.deletes.isEmpty());
        replica[0].lag = false;
        b.clock.addAndGet(5_000L);
        assertEquals(200, b.get("/api/x", "Authorization", "ApiKey " + k.getPlainKey()).status());
    }

    // OPS-APIKEY-001 AC-5: SINGLE_INSTANCE with a store whose supportsAtomicLimitInsert() is false: no FAIL
    // OPS-APIKEY-001 AC-2: .allowAdminScopesOnApiKeys(true): a doctor WARN at each start with the lasting-effects text
    @Test
    public void OPS_APIKEY_001_AC2_AC5_singleInstanceAndTheOptInWarning() throws Exception {
        f.start(b -> b.apiKeys(new Declared(false)).allowAdminScopesOnApiKeys(true));
        Doctor d = f.auth.doctor(f.app);
        assertTrue(items(d, Doctor.Level.FAIL).isEmpty());
        List<String> warns = items(d, Doctor.Level.WARN);
        boolean found = false;
        for (String w : warns) {
            found |= w.contains("API keys may hold auth-* admin scopes; role grants and reactivations made by a key "
                    + "persist after the key is deleted. Review audit events by apiKeyId.");
        }
        assertTrue(warns.toString(), found);
        int started = 0;
        for (String l : new ArrayList<>(f.logger.lines)) {
            if (l.startsWith("WARN") && l.contains("API keys may hold auth-* admin scopes")) {
                started++;
            }
        }
        assertEquals("one WARN at the start", 1, started);
        W8 again = new W8();
        others.add(again);
        again.start(b -> b.apiKeys(new Declared(false)).allowAdminScopesOnApiKeys(true));
        int second = 0;
        for (String l : new ArrayList<>(again.logger.lines)) {
            if (l.startsWith("WARN") && l.contains("API keys may hold auth-* admin scopes")) {
                second++;
            }
        }
        assertEquals("and again at the next start", 1, second);
    }

    /** A key store with a fixed countKeysWithAdminScopes answer. */
    static final class Counted extends Wave9KeyAuthTest.Keys {
        final int count;

        Counted(int count) {
            this.count = count;
        }

        @Override
        public int countKeysWithAdminScopes() {
            return count;
        }
    }

    // OPS-APIKEY-001 AC-3: opt-in off, a store with three * keys and one auth-users:read key: the doctor WARN reports 1
    @Test
    public void OPS_APIKEY_001_AC3_theCountOfIgnoredAdminKeys() throws Exception {
        String u = f.user("root", W8.PW, "admin");
        com.snoworca.naruserve.utils.auth.apikey.InMemoryApiKeyStore store =
                new com.snoworca.naruserve.utils.auth.apikey.InMemoryApiKeyStore();
        for (int i = 0; i < 3; i++) {
            store.insertIfUnderLimit(row("s" + i, "Star000" + i, u, "*"), 20, W8.T0);
        }
        store.insertIfUnderLimit(row("a", "Admin000", u, "auth-users:read"), 20, W8.T0);
        f.start(b -> b.apiKeys(store));
        boolean found = false;
        for (String w : items(f.auth.doctor(f.app), Doctor.Level.WARN)) {
            found |= w.contains("1 API key") && w.contains("auth-");
        }
        assertTrue(found);
    }

    private static StoredApiKey row(String id, String prefix, String ownerId, String scope) {
        return StoredApiKey.builder().id(id).prefix(prefix).secretHash(
                "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08").ownerUserId(ownerId).ownerVer(0L)
                .name("n").scopes(Collections.singleton(scope)).createdAt(W8.T0).build();
    }

    // OPS-APIKEY-001 AC-4: opt-in off and a store answering -1: no start-time count WARN; two different keys whose
    // auth-* scopes are ignored within one minute produce one WARN holding only the first key id
    @Test
    public void OPS_APIKEY_001_AC4_requestTimeWarnWhenTheCountIsUnknown() throws Exception {
        Counted unknown = new Counted(-1);
        String u = f.user("root", W8.PW, "admin");
        f.start(b -> b.apiKeys(unknown));
        for (String w : items(f.auth.doctor(f.app), Doctor.Level.WARN)) {
            assertFalse(w, w.contains("auth-* scope"));
        }
        StoredApiKey one = row("first-key", "First000", u, "auth-users:read");
        StoredApiKey two = row("second-key", "Secnd000", u, "auth-groups:read");
        String secret = "S3cretS3cretS3cretS3cretS3cretAB";
        unknown.insertIfUnderLimit(one.toBuilder().secretHash(
                com.snoworca.naruserve.utils.auth.internal.ApiKeyFormat.secretHash(secret)).build(), 20, W8.T0);
        unknown.insertIfUnderLimit(two.toBuilder().secretHash(
                com.snoworca.naruserve.utils.auth.internal.ApiKeyFormat.secretHash(secret)).build(), 20, W8.T0);
        f.get("/api/x", "Authorization", "ApiKey nk_First000_" + secret);
        f.get("/api/x", "Authorization", "ApiKey nk_Secnd000_" + secret);
        List<String> warns = new ArrayList<>();
        for (String l : new ArrayList<>(f.logger.lines)) {
            if (l.startsWith("WARN") && l.contains("ignored")) {
                warns.add(l);
            }
        }
        assertEquals(warns.toString(), 1, warns.size());
        assertTrue(warns.get(0).contains("first-key"));
        assertFalse(warns.get(0).contains("second-key"));
    }

    // OPS-APIKEY-001 AC-6: the .allowAdminScopesOnApiKeys Javadoc states the lasting-effects risk of the AC-2 WARN
    @Test
    public void OPS_APIKEY_001_AC6_theOptInJavadoc() throws Exception {
        String src = Wave9KeyAuthTest.read("NaruServe-api/src/main/java/com/snoworca/naruserve/utils/auth/Auth.java");
        int at = src.indexOf("public Builder allowAdminScopesOnApiKeys(");
        String doc = src.substring(src.lastIndexOf("/**", at), at);
        assertTrue(doc, doc.contains("role grants and reactivations made by a key persist after the key is deleted"));
        assertTrue(doc, doc.contains("review audit events by apiKeyId"));
    }

    // ---------------------------------------------------------------------------------------------- FR-APIKEY-006

    // FR-APIKEY-006 AC-1: POST /auth/login with a service account's name returns the same 401 as a wrong password,
    // runs the same one dummy PBKDF2, and counts toward the login counters
    @Test
    public void FR_APIKEY_006_AC1_serviceAccountLoginIsAWrongPassword() throws Exception {
        Wave8AccountTest.Counting counting = new Wave8AccountTest.Counting();
        f.user("human", W8.PW, "reader");
        f.start(b -> b.passwordHasher(counting));
        f.auth.users().create("svc", null, u -> u.toBuilder().serviceAccount(true).build(), "reader");
        f.auth.passwords().prepare();
        int v0 = counting.verifies.get();
        ClientResponse svc = f.post("/auth/login", W8.login("svc", W8.PW));
        int svcWork = counting.verifies.get() - v0;
        int v1 = counting.verifies.get();
        ClientResponse wrong = f.post("/auth/login", W8.login("human", "not the password"));
        int wrongWork = counting.verifies.get() - v1;
        assertEquals(401, svc.status());
        assertEquals(W8.code(wrong), W8.code(svc));
        assertEquals(wrong.text().replaceAll("\"requestId\":\"[^\"]*\"", ""),
                svc.text().replaceAll("\"requestId\":\"[^\"]*\"", ""));
        assertEquals(1, svcWork);
        assertEquals(wrongWork, svcWork);
        for (int i = 0; i < 4; i++) {
            f.post("/auth/login", W8.login("svc", W8.PW));
        }
        assertEquals(429, f.post("/auth/login", W8.login("svc", W8.PW)).status());
    }

    // FR-APIKEY-006 AC-2: admin PATCH A4 setting password on a service account -> 400 service_account_no_password
    // FR-APIKEY-006 AC-3: A2 with the service-account flag and a password from a JWT admin -> 400
    // service_account_no_password
    @Test
    public void FR_APIKEY_006_AC2_AC3_noPasswordForAServiceAccount() throws Exception {
        start(b -> b);
        AuthUser svc = f.auth.users().create("svc", null, u -> u.toBuilder().serviceAccount(true).build(), "reader");
        ClientResponse a4 = patch(svc.getId(), "{\"password\":\"a password for a bot\"}");
        assertEquals(a4.text(), 400, a4.status());
        assertEquals("service_account_no_password", W8.code(a4));
        assertNull(f.stored(svc.getId()).getPasswordHash());
        ClientResponse a2 = f.post("/auth/admin/users", "{\"username\":\"svc2\",\"serviceAccount\":true,"
                + "\"password\":\"a password for a bot\"}", "Authorization", bearer(root));
        assertEquals(a2.text(), 400, a2.status());
        assertEquals("service_account_no_password", W8.code(a2));
        assertNull(f.store.findByUsername("svc2"));
        assertEquals(201, f.post("/auth/admin/users", "{\"username\":\"svc3\",\"serviceAccount\":true}",
                "Authorization", bearer(root)).status());
    }

    // FR-APIKEY-006 AC-3 (key half): a key actor's password-setting A2 or A4 is refused first with 403
    // api_key_not_allowed, whatever its scopes (an opt-in key holding auth-users:write); nothing is written or hashed
    @Test
    public void FR_APIKEY_006_AC3_aKeyActorNeverSetsAPassword() throws Exception {
        Wave8AccountTest.Counting counting = new Wave8AccountTest.Counting();
        owner = f.user("bot", W8.PW, "reader");
        root = f.user("root", W8.PW, "admin");
        f.start(b -> b.apiKeys(keys).allowAdminScopesOnApiKeys(true).passwordHasher(counting));
        String k = f.auth.apiKeys().issue(root, "ops", null, "auth-users:write", "auth-users:read").getPlainKey();
        int work = counting.hashes.get() + counting.verifies.get();
        String before = f.stored(owner).getPasswordHash();
        ClientResponse a2 = f.post("/auth/admin/users", "{\"username\":\"svc\",\"serviceAccount\":true,"
                + "\"password\":\"a password for a bot\"}", "Authorization", "ApiKey " + k);
        ClientResponse a4 = f.adapter.send(ClientRequest.json("PATCH", "/auth/admin/users/" + owner,
                "{\"password\":\"taken over by a key\"}").header("Authorization", "ApiKey " + k));
        for (ClientResponse r : new ClientResponse[] {a2, a4}) {
            assertEquals(r.text(), 403, r.status());
            assertEquals("api_key_not_allowed", W8.code(r));
            assertNull(r.header("WWW-Authenticate"));
        }
        assertNull(f.store.findByUsername("svc"));
        assertEquals(before, f.stored(owner).getPasswordHash());
        assertEquals(work, counting.hashes.get() + counting.verifies.get());
        assertEquals(201, f.post("/auth/admin/users", "{\"username\":\"svc\",\"serviceAccount\":true}",
                "Authorization", "ApiKey " + k).status());
        // a key without auth-users:write is refused by the ban too, before the scope guard's insufficient_scope
        String narrow = f.auth.apiKeys().issue(root, "narrow", null, "posts:read").getPlainKey();
        ClientResponse low = f.post("/auth/admin/users", "{\"username\":\"svc2\",\"serviceAccount\":true,"
                + "\"password\":\"a password for a bot\"}", "Authorization", "ApiKey " + narrow);
        assertEquals(low.text(), 403, low.status());
        assertEquals("api_key_not_allowed", W8.code(low));
        ClientResponse lowA4 = f.adapter.send(ClientRequest.json("PATCH", "/auth/admin/users/" + owner,
                "{\"password\":\"taken over by a key\"}").header("Authorization", "ApiKey " + narrow));
        assertEquals(403, lowA4.status());
        assertEquals("api_key_not_allowed", W8.code(lowA4));
        ClientResponse noPw = f.post("/auth/admin/users", "{\"username\":\"svc3\",\"serviceAccount\":true}",
                "Authorization", "ApiKey " + narrow);
        assertEquals("without a password the scope guard decides", "insufficient_scope", W8.code(noPw));
    }

    // The issuance core under REL-APIKEY-003 and REL-APIKEY-004 through the app issue (the PAT and admin endpoints
    // arrive in a later wave): a failing future-key deletion fails the issuance with 503 and inserts nothing, and a
    // present marker stays when its retry fails
    @Test
    public void appIssueBehindTheFutureKeyDeletion() throws Exception {
        start(b -> b);
        futureKey();
        keys.failFuture = 1;
        int before = keys.d.listByOwner(owner).size();
        try {
            f.auth.apiKeys().issue(owner, "n", null, "posts:read");
            fail("issued");
        } catch (AuthUnavailableException expected) {
            assertEquals(before, keys.d.listByOwner(owner).size());
        }
        f.auth.apiKeyOwners().mark(owner, 5);
        keys.failFuture = 1;
        try {
            f.auth.apiKeys().issue(owner, "n", null, "posts:read");
            fail("issued");
        } catch (AuthUnavailableException expected) {
            assertEquals(Long.valueOf(5), f.auth.apiKeyOwners().marker(owner));
            assertEquals(before, keys.d.listByOwner(owner).size());
        }
    }

    // REL-APIKEY-003 statement: a key owner lock that stays taken does not stop an auth-triggered ver++; a marker at
    // the re-read ver covers the deletion that could not run
    @Test(timeout = 30000)
    public void aBusyOwnerLockStillRaisesVer() throws Exception {
        start(b -> b);
        final java.util.concurrent.CountDownLatch taken = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        Thread holder = new Thread(() -> {
            f.auth.apiKeyOwners().lock(owner);
            taken.countDown();
            try {
                release.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                f.auth.apiKeyOwners().unlock(owner);
            }
        });
        holder.start();
        assertTrue(taken.await(5, TimeUnit.SECONDS));
        try {
            long ver = f.stored(owner).getTokenVersion();
            assertEquals(204, f.post("/auth/logout?all=true", null, "Authorization", bearer(owner)).status());
            assertEquals(ver + 1, f.stored(owner).getTokenVersion());
            assertEquals(Long.valueOf(ver), f.auth.apiKeyOwners().marker(owner));
        } finally {
            release.countDown();
            holder.join(5000);
        }
    }

    // SEC-APIKEY-006 statement: the off-request deletion of an invariant-violating key re-reads the owner inside its
    // lock and leaves the key when the owner's ver caught up meanwhile
    @Test
    public void theInvariantDeletionReChecksTheStore() throws Exception {
        start(b -> b);
        setVer(owner, 6);
        IssuedApiKey k = f.auth.apiKeys().issue(owner, "k", null, "posts:read");
        setVer(owner, 5);
        assertEquals(401, withKey(k.getPlainKey()).status());
        assertEquals(1, held.queued.size());
        setVer(owner, 6); // the store caught up before the job ran
        keys.deletes.clear();
        held.runAll();
        assertTrue(keys.deletes.toString(), keys.deletes.isEmpty());
        assertNotNull(keys.d.findById(k.getKey().getId()));
    }

    // FR-APIKEY-006 AC-4: recovery whose target is a service account -> 400 service_account_no_password
    @Test
    public void FR_APIKEY_006_AC4_recoveryOfAServiceAccount() throws Exception {
        f.start(b -> b.recovery(true));
        f.auth.users().create("svc", null, u -> u.toBuilder().serviceAccount(true).build(), "reader");
        ClientResponse r = f.post("/auth/setup", "{\"token\":\"" + f.auth.pendingSetupToken() + "\",\"username\":"
                + "\"svc\",\"password\":\"a recovered passphrase\"}");
        assertEquals(400, r.status());
        assertEquals("service_account_no_password", W8.code(r));
        assertNull(f.store.findByUsername("svc").getPasswordHash());
    }

    // FR-APIKEY-006 AC-5: a UserMutator that changes the service-account flag of an existing user throws
    // IllegalArgumentException
    @Test
    public void FR_APIKEY_006_AC5_theFlagIsImmutable() throws Exception {
        f.start(b -> b);
        AuthUser human = f.auth.users().create("human", W8.PW.toCharArray(), "reader");
        try {
            f.auth.users().update(human.getId(), u -> u.toBuilder().serviceAccount(true).build());
            fail("changed the flag");
        } catch (IllegalArgumentException expected) {
            assertFalse(f.stored(human.getId()).isServiceAccount());
        }
        try {
            f.auth.users().create("svc", W8.PW.toCharArray(), u -> u.toBuilder().serviceAccount(true).build());
            fail("a service account with a password");
        } catch (IllegalArgumentException expected) {
            assertNull(f.store.findByUsername("svc"));
        }
    }

    // FR-APIKEY-006 AC-6: a service account created through auth.users().create with the flag round-trips
    // isServiceAccount() true through InMemoryUserStore and FileUserStore, and through an app-provided UserStore that
    // persists the flag
    @Test
    public void FR_APIKEY_006_AC6_theFlagIsPersisted() throws Exception {
        List<UserStore> stores = new ArrayList<>();
        stores.add(new InMemoryUserStore());
        stores.add(new FileUserStore(new File(tmp.newFolder(), "users.json"), AuthTestSupport.FILE_KEY));
        stores.add(new AppStores().users());
        for (UserStore s : stores) {
            Auth a = Auth.builder(AuthTestSupport.SECRET).hashIterations(1000).logger(f.logger).users(s).build();
            AuthUser svc = a.users().create("svc", null, u -> u.toBuilder().serviceAccount(true).build());
            AuthUser back = s.findById(svc.getId());
            assertTrue(s.getClass().getSimpleName(), back.isServiceAccount());
            assertNull(back.getPasswordHash());
            assertTrue(s.findByUsername("svc").isServiceAccount());
        }
    }

    // FR-APIKEY-006 AC-6: the UserStore Javadoc tells an app-provided store to persist the service-account flag
    @Test
    public void FR_APIKEY_006_AC6_userStoreJavadocRequiresTheFlag() throws Exception {
        File dir = new File("").getAbsoluteFile();
        File source = new File(dir, "src/main/java/com/snoworca/naruserve/utils/auth/user/UserStore.java");
        if (!source.isFile()) {
            source = new File(dir, "NaruServe-api/src/main/java/com/snoworca/naruserve/utils/auth/user/UserStore.java");
        }
        String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);
        String typeJavadoc = text.substring(0, text.indexOf("public interface UserStore"));
        assertTrue(typeJavadoc, typeJavadoc.contains("An app-provided store must persist the service-account flag"));
    }

    // FR-APIKEY-006 AC-7: audit events for actions by a service account mark the actor as a service account
    @Test
    public void FR_APIKEY_006_AC7_auditMarksTheServiceAccountActor() throws Exception {
        f.start(b -> b.apiKeys(keys).audit(audit::add).allowAdminScopesOnApiKeys(true)
                .roles(RolePolicy.builder().role("ops", "auth-users:write", "posts:read").build()));
        AuthUser svc = f.auth.users().create("svc", null, u -> u.toBuilder().serviceAccount(true).build(), "ops");
        String target = f.user("target", W8.PW, "admin");
        String k = f.auth.apiKeys().issue(svc.getId(), "ci", null, "auth-users:write").getPlainKey();
        ClientResponse r = f.adapter.send(ClientRequest.json("PATCH", "/auth/admin/users/" + target,
                "{\"enabled\":false}").header("Authorization", "ApiKey " + k));
        assertEquals(r.text(), 403, r.status());
        AuditEvent e = null;
        for (AuditEvent x : audit) {
            if (AuditEvent.GRANT_DENIED.equals(x.getType()) && target.equals(x.getTargetId())) {
                e = x;
            }
        }
        assertNotNull("the denied disable is audited", e);
        assertEquals(svc.getId(), e.getActorId());
        assertEquals("true", e.getDetails().get("serviceAccount"));
    }

    // ---------------------------------------------------------------------------------------------- SEC-IAM-006

    /** Records user events with their origin. */
    static final class Origins implements AuthEvents {
        final List<String> calls = new CopyOnWriteArrayList<>();

        @Override
        public void beforeUserCreate(AuthUser candidate, AuthEventContext ctx) {
            calls.add("beforeUserCreate");
        }

        @Override
        public void beforeUserUpdate(AuthUser before, AuthUser after, AuthEventContext ctx) {
            calls.add("beforeUserUpdate");
        }

        @Override
        public void onUserCreated(AuthUser user, AuthEventContext ctx) {
            calls.add("onUserCreated:" + ctx.getOrigin());
        }

        @Override
        public void onUserUpdated(AuthUser before, AuthUser after, Set<UserChange> changes, AuthEventContext ctx) {
            java.util.TreeSet<String> names = new java.util.TreeSet<>();
            for (UserChange c : changes) {
                names.add(c.name());
            }
            calls.add("onUserUpdated:" + ctx.getOrigin() + ":" + names);
        }

        @Override
        public void onPasswordChanged(AuthUser user, AuthEventContext ctx) {
            calls.add("onPasswordChanged:" + ctx.getOrigin());
        }
    }

    // SEC-IAM-006 AC-1: recovery with username ops when no user ops exists creates ops with role admin and the given
    // password; onUserCreated fires with origin RECOVERY and no before event fires
    @Test
    public void SEC_IAM_006_AC1_recoveryCreatesAnAdmin() throws Exception {
        Origins events = new Origins();
        f.start(b -> b.recovery(true).events(events));
        ClientResponse r = f.post("/auth/setup", "{\"token\":\"" + f.auth.pendingSetupToken() + "\",\"username\":"
                + "\"ops\",\"password\":\"a recovered passphrase\"}");
        assertEquals(r.text(), 201, r.status());
        AuthUser ops = f.store.findByUsername("ops");
        assertTrue(ops.getRoles().contains("admin"));
        assertTrue(f.auth.passwords().verify("a recovered passphrase".toCharArray(), ops.getPasswordHash()));
        assertEquals(Collections.singletonList("onUserCreated:RECOVERY"), events.calls);
    }

    // SEC-IAM-006 AC-2: recovery of alice (disabled, roles {editor}, hash null, ver 7): enabled, roles contain admin, a
    // hash verifying the new password and ver 8; onUserUpdated once with ENABLED, PASSWORD and ROLES and origin
    // RECOVERY, then onPasswordChanged
    @Test
    public void SEC_IAM_006_AC2_recoveryReactivatesWithOneVerBump() throws Exception {
        Origins events = new Origins();
        String alice = f.store.create(AuthUser.builder().username("alice").roles("editor").enabled(false)
                .tokenVersion(7).build()).getId();
        f.start(b -> b.recovery(true).events(events).roles(RolePolicy.builder().role("editor", "posts:write")
                .build()));
        ClientResponse r = f.post("/auth/setup", "{\"token\":\"" + f.auth.pendingSetupToken() + "\",\"username\":"
                + "\"alice\",\"password\":\"a recovered passphrase\"}");
        assertEquals(r.text(), 200, r.status());
        AuthUser now = f.stored(alice);
        assertTrue(now.isEnabled());
        assertTrue(now.getRoles().contains("admin"));
        assertTrue(now.getRoles().contains("editor"));
        assertEquals(8, now.getTokenVersion());
        assertTrue(f.auth.passwords().verify("a recovered passphrase".toCharArray(), now.getPasswordHash()));
        assertEquals(Arrays.asList("onUserUpdated:RECOVERY:[ENABLED, PASSWORD, ROLES]", "onPasswordChanged:RECOVERY"),
                events.calls);
    }
    // ---------------------------------------------------------------------------------------------- wave 10: PAT and
    // admin issuance on the paths REL-APIKEY-003 and REL-APIKEY-004 waited for

    private ClientResponse pat(String id, String password) throws Exception {
        return f.post("/auth/me/apikeys", Wave10PatTest.body(password, "posts:read"), "Authorization", bearer(id));
    }

    private ClientResponse adminIssue(String ownerId) throws Exception {
        return f.post("/auth/admin/apikeys", Wave10PatTest.admin(ownerId, W8.PW, "posts:read"), "Authorization",
                bearer(root));
    }

    // REL-APIKEY-003 AC-2: SINGLE_INSTANCE, an owner with 19 live keys and 1 key whose ownerVer exceeds the store ver
    // issues a PAT: the violating key is deleted before counting and the response is 201
    @Test
    public void REL_APIKEY_003_AC2_theFutureKeyGoesBeforeTheCount() throws Exception {
        start(b -> b);
        setVer(owner, 5);
        for (int i = 0; i < 19; i++) {
            f.auth.apiKeys().issue(owner, "k" + i, null, "posts:read");
        }
        IssuedApiKey future = futureKey(); // ownerVer 6, then the store loses that write: ver 5
        assertEquals(20, keys.d.listByOwner(owner).size());
        ClientResponse r = pat(owner, W8.PW);
        assertEquals(r.text(), 201, r.status());
        assertNull(keys.d.findById(future.getKey().getId()));
        assertEquals(20, keys.d.listByOwner(owner).size());
    }

    // REL-APIKEY-003 AC-4: SINGLE_INSTANCE, deleteFutureByOwner throws during PAT issuance: 503 SERVICE_UNAVAILABLE and
    // no key is inserted (the same for the admin issuance; the app issue throws AuthUnavailableException)
    // promotes appIssueBehindTheFutureKeyDeletion to the endpoints
    @Test
    public void REL_APIKEY_003_AC4_aFailedFutureDeletionFailsTheIssuance() throws Exception {
        start(b -> b);
        futureKey();
        int before = keys.d.listByOwner(owner).size();
        keys.failFuture = 1;
        ClientResponse r = pat(owner, W8.PW);
        assertEquals(r.text(), 503, r.status());
        assertEquals("SERVICE_UNAVAILABLE", W8.code(r));
        assertEquals(before, keys.d.listByOwner(owner).size());
        keys.failFuture = 1;
        ClientResponse a = adminIssue(owner);
        assertEquals(a.text(), 503, a.status());
        assertEquals("SERVICE_UNAVAILABLE", W8.code(a));
        assertEquals(before, keys.d.listByOwner(owner).size());
    }

    // REL-APIKEY-003 AC-6: the key owner lock of u is acquired on logout ?all=true, self-service password change, A4
    // password reset, A4 disable and recovery only while the same thread holds u's user write lock, and PAT, admin and
    // app issuance never take the user write lock while holding the key owner lock (promotes
    // lockOrderOnEveryVerBumpAndOnTheAppIssue with the PAT and admin issuance paths)
    @Test
    public void REL_APIKEY_003_AC6_lockOrderOnTheVerBumpsAndTheThreeIssuers() throws Exception {
        start(b -> b.recovery(true));
        String setup = f.auth.pendingSetupToken();
        assertEquals(204, f.post("/auth/logout?all=true", null, "Authorization", bearer(owner)).status());
        assertEquals(200, f.post("/auth/password", Wave9PasswordTest.change(W8.PW, "a fresh new passphrase"),
                "Authorization", bearer(owner)).status());
        assertEquals(200, patch(owner, "{\"password\":\"an admin reset password\"}").status());
        assertEquals(200, patch(owner, "{\"enabled\":false}").status());
        assertEquals(200, f.post("/auth/setup", "{\"token\":\"" + setup + "\",\"username\":\"bot\","
                + "\"password\":\"a recovered passphrase\"}").status());
        List<String> keyLocks = new ArrayList<>();
        for (String l : lockLog) {
            if (l.startsWith("keyLock")) {
                keyLocks.add(l);
            }
            if (l.startsWith("bump") || l.startsWith("changePassword")) {
                assertTrue(l, l.endsWith("key=true user=true"));
            }
        }
        assertEquals(lockLog.toString(), 5, keyLocks.size());
        for (String l : keyLocks) {
            assertTrue(l, l.endsWith("user=true"));
        }
        lockLog.clear();
        keys.insertCheck = id -> assertFalse("no user lock inside the key lock", UserLocks.of(id).isHeldByCurrentThread());
        assertEquals(201, pat(owner, "a recovered passphrase").status());
        assertEquals(201, adminIssue(owner).status());
        f.auth.apiKeys().issue(owner, "app", null, "posts:read");
        assertEquals(Arrays.asList("keyLock " + owner + " user=false", "keyLock " + owner + " user=false",
                "keyLock " + owner + " user=false"), lockLog);
    }

    // REL-APIKEY-004 AC-2: with marker (u, 5) a PAT issuance first runs deleteFutureByOwner(u, 5) under the owner lock;
    // on success the marker is cleared and the new key (ownerVer 6) is inserted afterwards and not deleted; on failure
    // the issuance gets 503 and the marker stays
    @Test
    public void REL_APIKEY_004_AC2_aPatRetriesTheMarkerFirst() throws Exception {
        start(b -> b);
        setVer(owner, 6);
        f.auth.apiKeyOwners().mark(owner, 5);
        keys.failFuture = 1;
        ClientResponse failed = pat(owner, W8.PW);
        assertEquals(failed.text(), 503, failed.status());
        assertEquals(Long.valueOf(5), f.auth.apiKeyOwners().marker(owner));
        assertTrue(keys.d.listByOwner(owner).isEmpty());
        keys.deletes.clear();
        final List<String> inLock = new CopyOnWriteArrayList<>();
        keys.futureCheck = id -> inLock.add(String.valueOf(ApiKeyOwners.heldByCurrentThread(id)));
        ClientResponse ok = pat(owner, W8.PW);
        assertEquals(ok.text(), 201, ok.status());
        assertEquals("deleteFutureByOwner " + owner + " 5", keys.deletes.get(0));
        assertFalse(inLock.contains("false"));
        assertNull(f.auth.apiKeyOwners().marker(owner));
        String id = (String) W8.object(ok).get("id");
        assertEquals(6, keys.d.findById(id).getOwnerVer());
        assertEquals(200, withKey((String) W8.object(ok).get("key")).status());
    }

    // REL-APIKEY-004 AC-9: with marker (u, 5) and the deletion executor held, a request with K queues one re-delete; a
    // PAT issuance for u then clears the marker by a successful deleteFutureByOwner(u, 5) and inserts N (ownerVer 6);
    // released, the queued re-delete takes u's owner lock, re-reads the marker, finds none and calls no
    // deleteFutureByOwner, and N still authenticates
    @Test
    public void REL_APIKEY_004_AC9_theQueuedReDeleteFindsNoMarker() throws Exception {
        start(b -> b);
        setVer(owner, 6);
        IssuedApiKey k = f.auth.apiKeys().issue(owner, "K", null, "posts:read");
        f.auth.apiKeyOwners().mark(owner, 5);
        assertEquals(401, withKey(k.getPlainKey()).status());
        assertEquals(1, held.queued.size());
        ClientResponse n = pat(owner, W8.PW);
        assertEquals(n.text(), 201, n.status());
        assertNull(f.auth.apiKeyOwners().marker(owner));
        assertEquals(6, keys.d.findById((String) W8.object(n).get("id")).getOwnerVer());
        keys.deletes.clear();
        lockLog.clear();
        held.runAll();
        assertTrue(keys.deletes.toString(), keys.deletes.isEmpty());
        assertEquals("the re-delete took the owner lock", Collections.singletonList("keyLock " + owner + " user=false"),
                lockLog);
        assertEquals(200, withKey((String) W8.object(n).get("key")).status());
    }

    // REL-APIKEY-003 AC-5: MULTI_INSTANCE on the key fixture of OPS-APIKEY-001 AC-7: PAT issuance, admin issuance, app
    // issue and the auth-triggered ver++ paths (logout ?all=true, self-service password change, A4 password reset,
    // disable, recovery) never call deleteFutureByOwner
    @Test
    public void REL_APIKEY_003_AC5_multiInstanceNeverDeletesFutureKeys() throws Exception {
        AppStores ds = new AppStores();
        final Declared ks = new Declared(true);
        W8 m = new W8();
        others.add(m);
        m.start(x -> multi(ds, ks).recovery(true).roles(RolePolicy.builder().role("reader", "posts:read").build())
                .logger(m.logger).timeSource(m.clock::get));
        AppStores.Users primary = ds.users();
        String hash = new com.snoworca.naruserve.utils.auth.password.Pbkdf2PasswordHasher(1000).hash(W8.PW.toCharArray());
        String u = primary.create(AuthUser.builder().username("bot").passwordHash(hash).roles("reader").build()).getId();
        String admin = primary.create(AuthUser.builder().username("root").passwordHash(hash).roles("admin").build())
                .getId();
        String setup = m.auth.pendingSetupToken();
        java.util.function.Function<String, String> bearerOf = id -> {
            try {
                return "Bearer " + m.auth.accessToken(id, primary.findById(id).getTokenVersion(), 900_000L);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        };
        assertEquals(201, m.post("/auth/me/apikeys", Wave10PatTest.body(W8.PW, "posts:read"), "Authorization",
                bearerOf.apply(u)).status());
        assertEquals(201, m.post("/auth/admin/apikeys", Wave10PatTest.admin(u, W8.PW, "posts:read"), "Authorization",
                bearerOf.apply(admin)).status());
        m.auth.apiKeys().issue(u, "app", null, "posts:read");
        assertEquals(204, m.post("/auth/logout?all=true", null, "Authorization", bearerOf.apply(u)).status());
        m.clock.addAndGet(5_001L);
        assertEquals(200, m.post("/auth/password", Wave9PasswordTest.change(W8.PW, "a fresh new passphrase"),
                "Authorization", bearerOf.apply(u)).status());
        assertEquals(200, m.adapter.send(ClientRequest.json("PATCH", "/auth/admin/users/" + u,
                "{\"password\":\"an admin reset password\"}").header("Authorization", bearerOf.apply(admin))).status());
        assertEquals(200, m.adapter.send(ClientRequest.json("PATCH", "/auth/admin/users/" + u,
                "{\"enabled\":false}").header("Authorization", bearerOf.apply(admin))).status());
        assertEquals(200, m.post("/auth/setup", "{\"token\":\"" + setup + "\",\"username\":\"bot\","
                + "\"password\":\"a recovered passphrase\"}").status());
        for (String d : ks.deletes) {
            assertFalse(ks.deletes.toString(), d.startsWith("deleteFutureByOwner"));
        }
    }

    // IR-APIKEY-003 AC-5: MULTI_INSTANCE on the key fixture of OPS-APIKEY-001 AC-7, an owner with 19 live keys and one
    // key whose ownerVer exceeds the replica-read ver 5: PAT issuance -> 409 api_key_limit and that key is listed as
    // stale; a stale key with ownerVer below ver is not counted
    @Test
    public void IR_APIKEY_003_AC5_aFutureLookingKeyStillCounts() throws Exception {
        AppStores ds = new AppStores();
        final Declared ks = new Declared(true);
        W8 b = new W8();
        others.add(b);
        final Replica[] replica = new Replica[1];
        b.start(x -> {
            replica[0] = new Replica(ds.users());
            return multi(ds, ks).users(replica[0]).roles(RolePolicy.builder().role("reader", "posts:read").build())
                    .logger(b.logger).timeSource(b.clock::get);
        });
        AppStores.Users primary = ds.users();
        String hash = new com.snoworca.naruserve.utils.auth.password.Pbkdf2PasswordHasher(1000).hash(W8.PW.toCharArray());
        String u = primary.create(AuthUser.builder().username("bot").passwordHash(hash).roles("reader").build()).getId();
        for (int i = 0; i < 6; i++) {
            primary.bumpTokenVersion(u);
        }
        replica[0].lag = true; // the replica reads ver 5, the primary holds 6
        long now = b.clock.get();
        for (int i = 0; i < 19; i++) {
            ks.insertIfUnderLimit(row("live" + i, u, 5, now), 100, now);
        }
        ks.insertIfUnderLimit(row("future", u, 6, now), 100, now);
        ks.insertIfUnderLimit(row("stale", u, 4, now), 100, now);
        ClientResponse r = b.post("/auth/me/apikeys", Wave10PatTest.body(W8.PW, "posts:read"), "Authorization",
                "Bearer " + b.auth.accessToken(u, 5, 900_000L));
        assertEquals(r.text(), 409, r.status());
        assertEquals("api_key_limit", W8.code(r));
        String future = null;
        String stale = null;
        for (Object o : Wave10PatTest.array(b.get("/auth/me/apikeys", "Authorization",
                "Bearer " + b.auth.accessToken(u, 5, 900_000L)))) {
            java.util.Map<?, ?> k = (java.util.Map<?, ?>) o;
            if ("future".equals(k.get("name"))) {
                future = (String) k.get("status");
            }
            if ("stale".equals(k.get("name"))) {
                stale = (String) k.get("status");
            }
        }
        assertEquals("stale", future);
        assertEquals("stale", stale);
        // one live key fewer: 18 live plus the future-looking key is under the limit only if the ownerVer-4 key is
        // not counted
        assertTrue(ks.d.deleteById("id-live0"));
        assertNotNull(ks.d.findById("id-stale"));
        ClientResponse fits = b.post("/auth/me/apikeys", Wave10PatTest.body(W8.PW, "posts:read"), "Authorization",
                "Bearer " + b.auth.accessToken(u, 5, 900_000L));
        assertEquals(fits.text(), 201, fits.status());
    }

    private static final java.util.concurrent.atomic.AtomicInteger ROWS = new java.util.concurrent.atomic.AtomicInteger();

    private static StoredApiKey row(String name, String owner, long ownerVer, long now) {
        String prefix = String.format("r%07d", ROWS.incrementAndGet());
        return StoredApiKey.builder().id("id-" + name).prefix(prefix)
                .secretHash("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef").ownerUserId(owner)
                .ownerVer(ownerVer).name(name).scopes(Collections.singleton("posts:read")).createdAt(now).build();
    }

    // SEC-APIKEY-011 AC-7 (MULTI_INSTANCE half): on the key fixture of OPS-APIKEY-001 AC-7, node B's UserStore returns
    // ver 5 while the key of a connection has ownerVer 6: the connection is closed at the next recheck and no delete
    // call is made for that key
    @Test(timeout = 30000)
    public void SEC_APIKEY_011_AC7_multiInstanceClosesWithoutADelete() throws Exception {
        AppStores ds = new AppStores();
        final Declared ks = new Declared(true);
        W8 b = new W8();
        others.add(b);
        final Replica[] replica = new Replica[1];
        final LinkedBlockingQueue<Object> handled = new LinkedBlockingQueue<>();
        b.routes = w -> w.app.ws("/ws", w.auth.webSocket((req, ws) -> handled.add(ws)));
        b.start(x -> {
            replica[0] = new Replica(ds.users());
            return multi(ds, ks).users(replica[0]).roles(RolePolicy.builder().role("reader", "posts:read").build())
                    .logger(b.logger).timeSource(b.clock::get);
        });
        b.auth.apiKeyOwners().deletions(held);
        AppStores.Users primary = ds.users();
        String u = primary.create(AuthUser.builder().username("bot").passwordHash("x").roles("reader").build()).getId();
        for (int i = 0; i < 6; i++) {
            primary.bumpTokenVersion(u);
        }
        IssuedApiKey k = b.auth.apiKeys().issue(u, "ws", null, "posts:read");
        assertEquals(6, k.getKey().getOwnerVer());
        Future<ClientResponse> hs = b.adapter.submit(ClientRequest.upgrade("/ws", "Authorization",
                "ApiKey " + k.getPlainKey()));
        InMemoryWebSocketChannel ch = b.adapter.nextChannel(5, TimeUnit.SECONDS);
        assertNotNull(handled.poll(5, TimeUnit.SECONDS));
        replica[0].lag = true;
        ks.deletes.clear();
        b.clock.addAndGet(5_000L);
        b.auth.webSocketTick();
        for (int i = 0; i < 500 && ch.isOpen(); i++) {
            Thread.sleep(10);
        }
        assertFalse(ch.isOpen());
        assertEquals(1008, ch.closeCode());
        assertEquals(101, hs.get(5, TimeUnit.SECONDS).status());
        held.runAll();
        assertTrue(ks.deletes.toString(), ks.deletes.isEmpty());
        assertNotNull(ks.d.findById(k.getKey().getId()));
    }
}
