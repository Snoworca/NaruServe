package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.testing.TestApp;
import com.snoworca.naruserve.utils.auth.apikey.ApiKeyStore;
import com.snoworca.naruserve.utils.auth.apikey.StoredApiKey;
import com.snoworca.naruserve.utils.auth.audit.AuditEvent;
import com.snoworca.naruserve.utils.auth.group.GroupAdminStore;
import com.snoworca.naruserve.utils.auth.group.InMemoryGroupStore;
import com.snoworca.naruserve.utils.auth.user.AuthUser;
import com.snoworca.naruserve.utils.auth.user.DuplicateUserException;
import com.snoworca.naruserve.utils.auth.user.InMemoryUserStore;
import com.snoworca.naruserve.utils.auth.user.UserStore;
import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.jspecify.annotations.Nullable;
import org.junit.Test;

/**
 * The user paths of the app facade and the store defaults: rename (FR-IAM-003), delete ordering (FR-IAM-008), the
 * default field-write algorithm (REL-IAM-001), the default user store (FR-IAM-002 AC-1) and the security write
 * conflict audit event on an auth-router path (FR-AUTH-009).
 */
public class UserLifecycleTest {

    /** Every store call in order, as "method arg". */
    private final List<String> calls = new CopyOnWriteArrayList<>();

    /** A store with only the five required methods, recording each call; {@code applies} false drops updates. */
    private final class MinimalStore implements UserStore {
        final InMemoryUserStore inner = new InMemoryUserStore();
        volatile boolean applies = true;
        final List<AuthUser> updates = new CopyOnWriteArrayList<>();

        @Override
        public @Nullable AuthUser findByUsername(String username) {
            return inner.findByUsername(username);
        }

        @Override
        public @Nullable AuthUser findById(String id) {
            calls.add("findById " + id);
            return inner.findById(id);
        }

        @Override
        public AuthUser create(AuthUser user) {
            return inner.create(user);
        }

        @Override
        public @Nullable AuthUser update(AuthUser user) {
            calls.add("update " + user.getId());
            updates.add(user);
            return applies ? inner.update(user) : user;
        }

        @Override
        public boolean delete(String id) {
            calls.add("delete " + id);
            return inner.delete(id);
        }
    }

    /** A group store recording removeUser and optionally failing it. */
    private final class Groups implements GroupAdminStore {
        final InMemoryGroupStore inner = new InMemoryGroupStore();
        volatile boolean fail;

        @Override
        public Set<String> groupsOfUser(String userId) {
            return inner.groupsOfUser(userId);
        }

        @Override
        public Set<String> parentsOf(Set<String> groupIds) {
            return inner.parentsOf(groupIds);
        }

        @Override
        public boolean createGroup(String groupId) {
            return inner.createGroup(groupId);
        }

        @Override
        public boolean deleteGroup(String groupId) {
            return inner.deleteGroup(groupId);
        }

        @Override
        public List<String> listGroups() {
            return inner.listGroups();
        }

        @Override
        public boolean groupExists(String groupId) {
            return inner.groupExists(groupId);
        }

        @Override
        public void addMember(String groupId, String userId) {
            inner.addMember(groupId, userId);
        }

        @Override
        public void removeMember(String groupId, String userId) {
            inner.removeMember(groupId, userId);
        }

        @Override
        public Set<String> membersOf(String groupId) {
            return inner.membersOf(groupId);
        }

        @Override
        public void addParent(String childId, String parentId) {
            inner.addParent(childId, parentId);
        }

        @Override
        public void removeParent(String childId, String parentId) {
            inner.removeParent(childId, parentId);
        }

        @Override
        public Set<String> childrenOf(String groupId) {
            return inner.childrenOf(groupId);
        }

        @Override
        public void removeUser(String userId) throws Exception {
            calls.add("removeUser " + userId);
            if (fail) {
                throw new java.io.IOException("group store down");
            }
            inner.removeUser(userId);
        }
    }

    /** An API key store over a list, recording deleteAllByOwner and optionally failing it. */
    private final class Keys implements ApiKeyStore {
        final List<StoredApiKey> rows = new CopyOnWriteArrayList<>();
        volatile boolean fail;

        @Override
        public @Nullable StoredApiKey findByPrefix(String prefix) {
            return null;
        }

        @Override
        public @Nullable StoredApiKey findById(String keyId) {
            return null;
        }

        @Override
        public List<StoredApiKey> listByOwner(String ownerUserId) {
            List<StoredApiKey> out = new ArrayList<>();
            for (StoredApiKey k : rows) {
                if (k.getOwnerUserId().equals(ownerUserId)) {
                    out.add(k);
                }
            }
            return out;
        }

        @Override
        public boolean insertIfUnderLimit(StoredApiKey key, int maxLiveKeys, long nowMillis) {
            return rows.add(key);
        }

        @Override
        public boolean deleteByIdAndOwner(String keyId, String ownerUserId) {
            return false;
        }

        @Override
        public boolean deleteById(String keyId) {
            return false;
        }

        @Override
        public int deleteAllByOwner(String ownerUserId) throws Exception {
            calls.add("deleteAllByOwner " + ownerUserId);
            if (fail) {
                throw new java.sql.SQLException("key store down");
            }
            List<StoredApiKey> mine = listByOwner(ownerUserId);
            rows.removeAll(mine);
            return mine.size();
        }

        @Override
        public int deleteStaleByOwner(String ownerUserId, long currentOwnerVer) {
            return 0;
        }

        @Override
        public int deleteExpired(long nowMillis) {
            return 0;
        }
    }

    private static StoredApiKey key(String id, String owner) {
        return StoredApiKey.builder().id(id).prefix(id + "pppppppp".substring(id.length())).ownerUserId(owner)
                .secretHash("9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08").ownerVer(0L)
                .name(id).scopes(Collections.singleton("posts:read")).createdAt(1L).build();
    }

    /** The delete fixture: user u7 in groups eng and ops with two keys. */
    private final class DeleteFixture {
        final MinimalStore users = new MinimalStore();
        final Groups groups = new Groups();
        final Keys keys = new Keys();
        final String u7;
        final Auth auth;

        DeleteFixture() {
            u7 = users.inner.create(AuthUser.builder().username("u7").passwordHash("h").build()).getId();
            groups.createGroup("eng");
            groups.createGroup("ops");
            groups.addMember("eng", u7);
            groups.addMember("ops", u7);
            keys.rows.add(key("k1", u7));
            keys.rows.add(key("k2", u7));
            auth = AuthTestSupport.builder().users(users).groups(groups).apiKeys(keys).build();
        }

        List<String> deleteCalls() {
            List<String> out = new ArrayList<>();
            for (String c : calls) {
                if (!c.startsWith("findById")) {
                    out.add(c);
                }
            }
            return out;
        }

        String adminId;

        ClientResponse deleteOverHttp(TestApp t) {
            return t.send("DELETE", "/users/" + u7, AuthTestSupport.HEADER, adminId + ":0");
        }

        TestApp app() {
            adminId = users.inner.create(AuthUser.builder().username("admin").passwordHash("h").build()).getId();
            TestApp t = TestApp.create();
            auth.install(t.app());
            t.app().delete("/users/:id", (req, res) -> res.send(String.valueOf(auth.users().delete(req.param("id")))));
            t.app().onError(auth.errors());
            return t;
        }
    }

    // FR-IAM-002 AC-1: without .users(...) the store is an InMemoryUserStore and users created through
    // auth.users().create are found by findByUsername on that same instance
    @Test
    public void FR_IAM_002_AC1_defaultStoreIsInMemory() throws Exception {
        Auth auth = Auth.builder(AuthTestSupport.SECRET).hashIterations(1000).logger(new RecordingLogger()).build();
        assertTrue(auth.userStore() instanceof InMemoryUserStore);
        AuthUser alice = auth.users().create("Alice", "pw-pw-pw".toCharArray());
        assertEquals(alice.getId(), auth.userStore().findByUsername("alice").getId());
        assertEquals(alice.getId(), auth.users().findByUsername("ALICE").getId());
    }

    // FR-IAM-003 AC-3: the public utils.auth API names no email, phone or login-key method or builder option
    @Test
    public void FR_IAM_003_AC3_noLoginKeyApi() throws Exception {
        String path = System.getProperty("naruserve.apiJar");
        assertNotNull("naruserve.apiJar", path);
        List<String> offending = new ArrayList<>();
        int scanned = 0;
        try (JarFile jar = new JarFile(new File(path))) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                String e = entries.nextElement().getName();
                if (!e.startsWith("com/snoworca/naruserve/utils/") || !e.endsWith(".class")
                        || e.contains("/internal/")) {
                    continue;
                }
                Class<?> c = Class.forName(e.substring(0, e.length() - 6).replace('/', '.'), false,
                        getClass().getClassLoader());
                if (!Modifier.isPublic(c.getModifiers())) {
                    continue;
                }
                scanned++;
                for (Method m : c.getMethods()) {
                    String n = m.getName().toLowerCase(Locale.ROOT);
                    if (n.contains("loginkey") || n.contains("email") || n.contains("phone")) {
                        offending.add(c.getName() + "." + m.getName());
                    }
                }
            }
        }
        assertTrue(scanned > 40);
        assertEquals(Collections.emptyList(), offending);
    }

    /** Records the update events of the facade. */
    private static final class UpdateEvents implements AuthEvents {
        final List<String> seen = new CopyOnWriteArrayList<>();

        @Override
        public void beforeUserUpdate(AuthUser before, AuthUser after, AuthEventContext ctx) {
            seen.add("before " + before.getUsername() + "->" + after.getUsername() + " " + ctx.getOrigin());
        }

        @Override
        public void onUserUpdated(AuthUser before, AuthUser after, Set<UserChange> changes, AuthEventContext ctx) {
            seen.add("on " + after.getUsername() + " " + changes + " " + ctx.getOrigin());
        }
    }

    // FR-IAM-003 AC-4: rename normalizes, stores the new name and fires the update events with USERNAME and APP; a
    // rename to the current name writes nothing, fires nothing and raises nothing
    @Test
    public void FR_IAM_003_AC4_renameNormalizesAndFiresEvents() throws Exception {
        MinimalStore store = new MinimalStore();
        UpdateEvents events = new UpdateEvents();
        Auth auth = AuthTestSupport.builder().users(store).events(events).build();
        String id = auth.users().create("alice", "pw-pw-pw".toCharArray()).getId();
        AuthUser renamed = auth.users().rename(id, "Bob");
        assertEquals("bob", renamed.getUsername());
        assertEquals("bob", store.inner.findById(id).getUsername());
        assertEquals(Arrays.asList("before alice->bob APP", "on bob [USERNAME] APP"), events.seen);
        int updates = store.updates.size();
        assertEquals("bob", auth.users().rename(id, "BOB").getUsername());
        assertEquals(updates, store.updates.size());
        assertEquals(2, events.seen.size());
    }

    // FR-IAM-003 AC-5: a rename to a name another user holds throws DuplicateUserException and leaves the row
    @Test
    public void FR_IAM_003_AC5_renameToATakenNameIsADuplicate() throws Exception {
        MinimalStore store = new MinimalStore();
        Auth auth = AuthTestSupport.builder().users(store).build();
        String a = auth.users().create("alice", "pw-pw-pw".toCharArray()).getId();
        auth.users().create("bob", "pw-pw-pw".toCharArray());
        AuthUser before = store.inner.findById(a);
        try {
            auth.users().rename(a, "Bob");
            fail("renamed onto a taken name");
        } catch (DuplicateUserException expected) {
            // 409
        }
        assertEquals(before, store.inner.findById(a));
    }

    // FR-IAM-008 AC-1: delete removes the memberships, then the user's keys, then the row, in that order
    @Test
    public void FR_IAM_008_AC1_deleteOrder() throws Exception {
        DeleteFixture f = new DeleteFixture();
        assertTrue(f.auth.users().delete(f.u7));
        assertEquals(Arrays.asList("removeUser " + f.u7, "deleteAllByOwner " + f.u7, "delete " + f.u7),
                f.deleteCalls());
        assertTrue(f.groups.membersOf("eng").isEmpty());
        assertTrue(f.groups.membersOf("ops").isEmpty());
        assertTrue(f.keys.rows.isEmpty());
        assertNull(f.users.inner.findById(f.u7));
    }

    // FR-IAM-008 AC-2: a failing removeUser answers 503 and calls neither deleteAllByOwner nor delete; the row stays
    @Test
    public void FR_IAM_008_AC2_membershipFailureStopsTheDelete() throws Exception {
        DeleteFixture f = new DeleteFixture();
        f.groups.fail = true;
        TestApp t = f.app();
        calls.clear();
        ClientResponse r = f.deleteOverHttp(t);
        assertEquals(503, r.status());
        assertEquals("SERVICE_UNAVAILABLE", AuthTestSupport.code(r));
        assertEquals(Arrays.asList("removeUser " + f.u7), f.deleteCalls());
        assertNotNull(f.users.inner.findById(f.u7));
        assertEquals(2, f.keys.rows.size());
    }

    // FR-IAM-008 AC-3: a failing deleteAllByOwner answers 503 and keeps the row; a second delete completes and leaves
    // no membership, key or row
    @Test
    public void FR_IAM_008_AC3_keyFailureKeepsTheRowAndARetryFinishes() throws Exception {
        DeleteFixture f = new DeleteFixture();
        f.keys.fail = true;
        TestApp t = f.app();
        calls.clear();
        ClientResponse r = f.deleteOverHttp(t);
        assertEquals(503, r.status());
        assertEquals(Arrays.asList("removeUser " + f.u7, "deleteAllByOwner " + f.u7), f.deleteCalls());
        assertNotNull(f.users.inner.findById(f.u7));
        f.keys.fail = false;
        ClientResponse again = f.deleteOverHttp(t);
        assertEquals(again.text(), 200, again.status());
        assertEquals("true", again.text());
        assertNull(f.users.inner.findById(f.u7));
        assertTrue(f.keys.listByOwner(f.u7).isEmpty());
        assertTrue(f.groups.groupsOfUser(f.u7).isEmpty());
    }

    // REL-IAM-001 AC-1: with only the five required methods, setEnabled reads, writes a copy changing only enabled,
    // and reads again; the hash, roles and attributes stay
    @Test
    public void REL_IAM_001_AC1_defaultSetEnabledReadsWritesRereads() throws Exception {
        MinimalStore store = new MinimalStore();
        AuthUser u = store.inner.create(AuthUser.builder().username("alice").passwordHash("hash-1").roles("editor")
                .attribute("team", "a").tokenVersion(4).build());
        calls.clear();
        assertTrue(store.setEnabled(u.getId(), false));
        assertEquals(Arrays.asList("findById " + u.getId(), "update " + u.getId(), "findById " + u.getId()), calls);
        AuthUser written = store.updates.get(0);
        assertEquals(u.toBuilder().enabled(false).build(), written);
        AuthUser now = store.inner.findById(u.getId());
        assertFalse(now.isEnabled());
        assertEquals("hash-1", now.getPasswordHash());
        assertEquals(u.getRoles(), now.getRoles());
        assertEquals(u.getAttributes(), now.getAttributes());
        assertEquals(4, now.getTokenVersion());
    }

    // REL-IAM-001 AC-2: an update that silently does not apply makes setEnabled, bumpTokenVersion and setRoles call
    // update 4 times and throw security_write_conflict; on an auth-router path the answer is 503 with that code and
    // exactly one audit event; a direct default call emits none
    @Test
    public void REL_IAM_001_AC2_unconfirmedSecurityWritesConflict() throws Exception {
        MinimalStore store = new MinimalStore();
        final String id = store.inner.create(AuthUser.builder().username("alice").passwordHash("h").build()).getId();
        store.applies = false;
        final List<AuditEvent> audited = new CopyOnWriteArrayList<>();
        Auth auth = AuthTestSupport.builder().users(store).audit(audited::add).build();
        String[] names = {"setEnabled", "bumpTokenVersion", "setRoles"};
        for (String name : names) {
            store.updates.clear();
            try {
                switch (name) {
                    case "setEnabled":
                        store.setEnabled(id, false);
                        break;
                    case "bumpTokenVersion":
                        store.bumpTokenVersion(id);
                        break;
                    default:
                        store.setRoles(id, Collections.singleton("admin"));
                }
                fail(name + " did not throw");
            } catch (AuthUnavailableException e) {
                assertEquals(name, "security_write_conflict", e.getCode());
            }
            assertEquals(name, 4, store.updates.size());
        }
        assertTrue("a direct default call emitted an audit event", audited.isEmpty());
        TestApp t = TestApp.create();
        auth.routes().post("/logout-all", (req, res) -> {
            auth.userStore().bumpTokenVersion(auth.user(req).getId());
            res.status(204).end();
        });
        auth.install(t.app());
        ClientResponse r = t.send("POST", "/auth/logout-all", AuthTestSupport.HEADER, id + ":0");
        assertEquals(503, r.status());
        assertEquals("security_write_conflict", AuthTestSupport.code(r));
        assertEquals(1, audited.size());
        assertEquals(AuditEvent.SECURITY_WRITE_CONFLICT, audited.get(0).getType());
        assertEquals(AuditEvent.Outcome.FAILURE, audited.get(0).getOutcome());
        assertEquals(id, audited.get(0).getActorId());
    }

    // REL-IAM-001 AC-5: a profile write through auth never writes a passwordHash, ver, enabled or roles older than
    // the row read inside the lock; a concurrent default ver++ waits for the lock and is kept
    @Test(timeout = 30000)
    public void REL_IAM_001_AC5_profileWriteKeepsTheLockedRow() throws Exception {
        final MinimalStore store = new MinimalStore();
        final Auth auth = AuthTestSupport.builder().users(store).build();
        final String id = auth.users().create("alice", "pw-pw-pw".toCharArray(), "editor").getId();
        final String hash = store.inner.findById(id).getPasswordHash();
        // a change made in the store before the facade takes the lock is read inside the lock and kept
        store.inner.update(store.inner.findById(id).toBuilder().tokenVersion(7).roles("admin").build());
        auth.users().update(id, u -> u.toBuilder().attribute("team", "a").build());
        AuthUser written = store.updates.get(store.updates.size() - 1);
        assertEquals(7, written.getTokenVersion());
        assertEquals(new LinkedHashSet<>(Collections.singleton("admin")), written.getRoles());
        assertEquals(hash, written.getPasswordHash());
        assertTrue(written.isEnabled());

        final CountDownLatch inMutator = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        Thread profile = new Thread(() -> auth.users().update(id, u -> {
            inMutator.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return u.toBuilder().attribute("team", "b").build();
        }));
        profile.start();
        assertTrue(inMutator.await(10, TimeUnit.SECONDS));
        final boolean[] bumped = new boolean[1];
        Thread logoutAll = new Thread(() -> {
            try {
                bumped[0] = store.bumpTokenVersion(id);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        logoutAll.start();
        // wait (bounded) until the ver++ thread blocks or ends; a ver++ that takes no lock ends here
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        Thread.State state = logoutAll.getState();
        while ((state == Thread.State.NEW || state == Thread.State.RUNNABLE) && System.nanoTime() < deadline) {
            Thread.sleep(5);
            state = logoutAll.getState();
        }
        assertTrue("the ver++ did not wait for the profile write's lock (" + state + ")", logoutAll.isAlive());
        assertTrue(String.valueOf(state), state == Thread.State.BLOCKED || state == Thread.State.WAITING
                || state == Thread.State.TIMED_WAITING);
        assertEquals("the ver++ ran while the profile write held the lock", 7,
                store.inner.findById(id).getTokenVersion());
        release.countDown();
        profile.join(10_000);
        logoutAll.join(10_000);
        assertTrue(bumped[0]);
        AuthUser end = store.inner.findById(id);
        assertEquals("b", end.getAttribute("team"));
        assertEquals(8, end.getTokenVersion());
    }

    // FR-AUTH-009 AC-3 and AC-4: an event raised during a request carries that request's requestId(); a sink that
    // throws does not change the response and its failure is logged
    @Test
    public void FR_AUTH_009_AC3_AC4_requestIdAndFailingSink() throws Exception {
        for (final boolean failing : new boolean[] {false, true}) {
            MinimalStore store = new MinimalStore();
            final String id = store.inner.create(AuthUser.builder().username("alice").passwordHash("h").build())
                    .getId();
            store.applies = false;
            final List<AuditEvent> audited = new CopyOnWriteArrayList<>();
            final List<String> requestIds = new CopyOnWriteArrayList<>();
            RecordingLogger log = new RecordingLogger();
            Auth auth = AuthTestSupport.builder(new AuthTestSupport.TestCredentials(), log).users(store)
                    .audit(e -> {
                        audited.add(e);
                        if (failing) {
                            throw new IllegalStateException("sink down");
                        }
                    }).build();
            TestApp t = TestApp.create();
            auth.routes().post("/disable", (req, res) -> {
                requestIds.add(req.requestId());
                auth.userStore().setEnabled(auth.user(req).getId(), false);
                res.send("disabled");
            });
            auth.install(t.app());
            ClientResponse r = t.send("POST", "/auth/disable", AuthTestSupport.HEADER, id + ":0");
            assertEquals(503, r.status());
            assertEquals("security_write_conflict", AuthTestSupport.code(r));
            assertEquals(1, audited.size());
            assertNotNull(requestIds.get(0));
            assertFalse(requestIds.get(0).isEmpty());
            assertEquals(requestIds.get(0), audited.get(0).getRequestId());
            assertNotNull(audited.get(0).getSourceIp());
            boolean logged = RecordingLogger.countMatching(log.lines, "ERROR auth: the audit sink failed.*") == 1;
            assertEquals(failing, logged);
        }
    }

    /** The default user store identity of a facade create; the facade never hands out the stored hash. */
    @Test
    public void facadeCopiesCarryNoHash() {
        Auth auth = AuthTestSupport.builder().build();
        AuthUser u = auth.users().create("carol", "pw-pw-pw".toCharArray());
        assertNull(u.getPasswordHash());
        assertSame(InMemoryUserStore.class, auth.userStore().getClass());
    }
}
