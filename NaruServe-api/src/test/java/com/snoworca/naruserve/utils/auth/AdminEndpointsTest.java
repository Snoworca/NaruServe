package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.AsyncRouteHandler;
import com.snoworca.naruserve.Registration;
import com.snoworca.naruserve.RegistrationKind;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.utils.auth.apikey.StoredApiKey;
import com.snoworca.naruserve.utils.auth.audit.AuditEvent;
import com.snoworca.naruserve.utils.auth.user.AuthUser;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Test;

/**
 * The admin endpoints of auth.adminRoutes() (IR-IAM-005), their scope guards (SEC-IAM-007), the grant rule on the
 * admin write paths (SEC-IAM-004), group delete and edge removal protection (SEC-IAM-005), the A2 attribute rule
 * (SEC-IAM-001 AC-6) and the A4 field order (FR-IAM-014), over real access tokens and the in-memory stores.
 */
public class AdminEndpointsTest {

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

    private static final List<String> FIFTEEN = Arrays.asList("GET /users", "POST /users", "GET /users/:id",
            "PATCH /users/:id", "DELETE /users/:id", "GET /users/:id/groups", "GET /groups", "POST /groups",
            "GET /groups/:id", "DELETE /groups/:id", "PUT /groups/:id/members/:userId",
            "DELETE /groups/:id/members/:userId", "PUT /groups/:id/parents/:parentId",
            "DELETE /groups/:id/parents/:parentId", "DELETE /users/:id/lockout");

    private static List<String> adminPairs(AdminFixture f) {
        List<String> out = new ArrayList<>();
        for (Registration r : f.app.registrations()) {
            String p = r.getPath();
            if (r.getKind() == RegistrationKind.ROUTE && p != null && p.startsWith("/auth/admin/")) {
                out.add(r.getMethod() + " " + p.substring("/auth/admin".length()));
            }
        }
        return out;
    }

    // IR-IAM-005 AC-1: with API keys off the admin router registers exactly the 15 method/path pairs
    @Test
    public void IR_IAM_005_AC1_exactlyFifteenAdminRoutes() throws Exception {
        AdminFixture f = fixture().start();
        List<String> pairs = adminPairs(f);
        assertEquals(pairs.toString(), 15, pairs.size());
        assertEquals(new HashSet<>(FIFTEEN), new HashSet<>(pairs));
    }

    // IR-IAM-005 AC-2: with .apiKeys(store) the three key routes are added (18 pairs); without, they are absent
    @Test
    public void IR_IAM_005_AC2_keyRoutesOnlyWithApiKeys() throws Exception {
        AdminFixture f = fixture().start(b -> b.apiKeys(new AdminFixture.ListKeys()));
        List<String> pairs = adminPairs(f);
        assertEquals(pairs.toString(), 18, pairs.size());
        assertTrue(pairs.containsAll(Arrays.asList("GET /apikeys", "POST /apikeys", "DELETE /apikeys/:id")));
        List<String> off = adminPairs(fixture().start());
        assertFalse(off.contains("GET /apikeys"));
        assertFalse(off.contains("POST /apikeys"));
        assertFalse(off.contains("DELETE /apikeys/:id"));
    }

    // IR-IAM-005 AC-3: no G9 route: PUT /auth/admin/groups/eng/roles reaches no auth handler (404)
    @Test
    public void IR_IAM_005_AC3_noGroupRoleRoute() throws Exception {
        AdminFixture f = fixture().start();
        String root = f.user("root", "admin");
        f.groups.confer("eng");
        for (String p : adminPairs(f)) {
            assertFalse(p, p.contains("/roles"));
        }
        ClientResponse r = f.call("PUT", "/auth/admin/groups/eng/roles", root, "{\"roles\":[\"editor\"]}");
        assertEquals(r.text(), 404, r.status());
    }

    // IR-IAM-005 AC-4: after auth.install(app) the built-in admin gets 200 on GET /auth/admin/users; the app mounted
    // nothing itself
    @Test
    public void IR_IAM_005_AC4_installMountsAdminRoutes() throws Exception {
        AdminFixture f = fixture().start();
        String root = f.user("root", "admin");
        ClientResponse r = f.call("GET", "/auth/admin/users", root, null);
        assertEquals(r.text(), 200, r.status());
        int adminMounts = 0;
        List<String> mounts = new java.util.ArrayList<>();
        for (Registration reg : f.app.registrations()) {
            if (reg.getKind() == RegistrationKind.ROUTER_MOUNT) {
                mounts.add(reg.getPath());
            }
            if (reg.getKind() == RegistrationKind.ROUTER_MOUNT && "/auth/admin".equals(reg.getPath())) {
                assertNotNull(reg.getTarget());
                if (reg.getMount() == null) {
                    adminMounts++; // the app-level mount; the routers inside it are expanded under it
                }
            }
        }
        // install recorded exactly one app-level admin mount, and every mount of the app is one install made under
        // /auth
        assertEquals(mounts.toString(), 1, adminMounts);
        for (String m : mounts) {
            assertTrue(mounts.toString(), m != null && (m.equals("/auth") || m.startsWith("/auth/")));
        }
    }

    // IR-IAM-005 AC-5: the success statuses and shapes of A1–A6 and G1–G8, G5/G6/G8 idempotent on repeat
    @Test
    @SuppressWarnings("unchecked")
    public void IR_IAM_005_AC5_successStatuses() throws Exception {
        AdminFixture f = fixture().start();
        String root = f.user("root", "admin");
        ClientResponse a1 = f.call("GET", "/auth/admin/users", root, null);
        assertEquals(200, a1.status());
        Map<String, Object> page = AdminFixture.object(a1);
        assertEquals(new HashSet<>(Arrays.asList("items", "total", "offset", "limit")), page.keySet());
        ClientResponse a2 = f.call("POST", "/auth/admin/users", root,
                "{\"username\":\"bob\",\"password\":\"bob-password-1\",\"roles\":[\"reader\"]}");
        assertEquals(a2.text(), 201, a2.status());
        String bob = (String) AdminFixture.object(a2).get("id");
        assertEquals("/auth/admin/users/" + bob, a2.header("Location"));
        assertFalse(a2.text(), a2.text().contains("pbkdf2"));
        ClientResponse a3 = f.call("GET", "/auth/admin/users/" + bob, root, null);
        assertEquals(200, a3.status());
        assertEquals(bob, AdminFixture.object(a3).get("id"));
        assertEquals("bob", AdminFixture.object(a3).get("username"));
        assertFalse(a3.text().contains("pbkdf2"));
        ClientResponse a4 = f.call("PATCH", "/auth/admin/users/" + bob, root, "{\"attributes\":{\"n\":\"1\"}}");
        assertEquals(a4.text(), 200, a4.status());
        assertEquals("1", ((Map<String, Object>) AdminFixture.object(a4).get("attributes")).get("n"));
        ClientResponse a6 = f.call("GET", "/auth/admin/users/" + bob + "/groups", root, null);
        assertEquals(200, a6.status());
        assertEquals("[]", a6.text());
        assertEquals(200, f.call("GET", "/auth/admin/groups", root, null).status());
        ClientResponse g2 = f.call("POST", "/auth/admin/groups", root, "{\"id\":\"eng\"}");
        assertEquals(g2.text(), 201, g2.status());
        assertEquals("/auth/admin/groups/eng", g2.header("Location"));
        ClientResponse g1 = f.call("GET", "/auth/admin/groups", root, null);
        assertEquals(200, g1.status());
        assertEquals("[\"eng\"]", g1.text());
        f.groups.confer("all");
        for (int i = 0; i < 2; i++) {
            assertEquals(204, f.call("PUT", "/auth/admin/groups/eng/members/" + bob, root, null).status());
        }
        assertEquals(204, f.call("PUT", "/auth/admin/groups/eng/parents/all", root, null).status());
        ClientResponse g3 = f.call("GET", "/auth/admin/groups/eng", root, null);
        assertEquals(200, g3.status());
        Map<String, Object> g = AdminFixture.object(g3);
        assertEquals(Arrays.asList("id", "members", "parents", "children"), new ArrayList<>(g.keySet()));
        assertEquals(Collections.singletonList(bob), g.get("members"));
        assertEquals(Collections.singletonList("all"), g.get("parents"));
        assertEquals("[\"eng\"]", f.call("GET", "/auth/admin/users/" + bob + "/groups", root, null).text());
        for (int i = 0; i < 2; i++) {
            assertEquals(204, f.call("DELETE", "/auth/admin/groups/eng/parents/all", root, null).status());
            assertEquals(204, f.call("DELETE", "/auth/admin/groups/eng/members/" + bob, root, null).status());
        }
        assertEquals(204, f.call("DELETE", "/auth/admin/groups/eng", root, null).status());
        assertEquals(204, f.call("DELETE", "/auth/admin/users/" + bob, root, null).status());
        assertNull(f.base.findById(bob));
    }

    // IR-IAM-005 AC-6: unknown user ids on A3–A6 and unknown group ids on G3, G4, G5 and G7 answer 404 NOT_FOUND
    @Test
    public void IR_IAM_005_AC6_unknownIdsAre404() throws Exception {
        AdminFixture f = fixture().start();
        String root = f.user("root", "admin");
        f.groups.confer("eng");
        String[][] calls = {{"GET", "/auth/admin/users/nope"}, {"PATCH", "/auth/admin/users/nope"},
                {"DELETE", "/auth/admin/users/nope"}, {"GET", "/auth/admin/users/nope/groups"},
                {"GET", "/auth/admin/groups/nope"}, {"DELETE", "/auth/admin/groups/nope"},
                {"PUT", "/auth/admin/groups/nope/members/" + root}, {"PUT", "/auth/admin/groups/nope/parents/eng"}};
        for (String[] c : calls) {
            ClientResponse r = f.call(c[0], c[1], root, c[0].equals("PATCH") ? "{\"attributes\":{}}" : null);
            assertEquals(c[0] + " " + c[1] + " " + r.text(), 404, r.status());
            assertEquals("NOT_FOUND", AuthTestSupport.code(r));
        }
    }

    // IR-IAM-005 AC-7: A2 and A4 are registered with methodAsync: with every hash slot taken an A2 is pending (its
    // worker returned) and it completes once a slot frees
    @Test(timeout = 30000)
    public void IR_IAM_005_AC7_hashingHandlersAreAsync() throws Exception {
        AdminFixture f = fixture();
        final java.util.concurrent.atomic.AtomicReference<Thread> dispatcher = new java.util.concurrent.atomic
                .AtomicReference<>();
        f.preInstall = a -> a.use((req, res, next) -> {
            if (req.method() == com.snoworca.naruserve.Method.POST) {
                dispatcher.set(Thread.currentThread()); // the worker that dispatches the A2
            }
            next.run();
        });
        f.start(b -> b.hashConcurrency(1));
        String root = f.user("root", "admin");
        int async = 0;
        for (Registration r : f.app.registrations()) {
            String p = r.getPath();
            if (r.getKind() == RegistrationKind.ROUTE && ("/auth/admin/users".equals(p) && "POST".equals(r.getMethod())
                    || "/auth/admin/users/:id".equals(p) && "PATCH".equals(r.getMethod()))) {
                assertTrue(p, r.getTarget() instanceof AsyncRouteHandler);
                async++;
            }
        }
        assertEquals(2, async);
        final CountDownLatch hold = new CountDownLatch(1);
        final CountDownLatch inSlot = new CountDownLatch(1);
        f.auth.hashSlots().submit(() -> {
            inSlot.countDown();
            hold.await();
            return "held";
        }, (v, e) -> { });
        assertTrue(inSlot.await(5, TimeUnit.SECONDS));
        Future<ClientResponse> pending = f.adapter.submit(ClientRequest.json("POST", "/auth/admin/users",
                "{\"username\":\"carol\",\"password\":\"carol-password-1\"}").header("Authorization",
                "Bearer " + f.token(root)));
        // the worker that dispatched the A2 has left the handler while the A2 still waits for the slot
        boolean released = false;
        for (int i = 0; i < 500 && !released; i++) {
            Thread w = dispatcher.get();
            if (w != null) {
                boolean awaiting = false;
                StackTraceElement[] stack = w.getStackTrace();
                for (StackTraceElement e : stack) {
                    awaiting |= e.getClassName().contains("InMemoryHttpAdapter") && e.getMethodName().startsWith("await");
                }
                released = awaiting;
                for (StackTraceElement e : stack) {
                    if (e.getClassName().contains("AdminEndpoints") || e.getClassName().contains("HashSlots")
                            || e.getClassName().contains("Passwords")) {
                        released = false;
                    }
                }
            }
            if (!released) {
                Thread.sleep(10);
            }
        }
        assertTrue("the dispatching worker still runs the A2 handler", released);
        assertFalse(pending.isDone());
        assertEquals(200, f.call("GET", "/auth/admin/users", root, null).status());
        assertFalse(pending.isDone());
        hold.countDown();
        assertEquals(201, pending.get(10, TimeUnit.SECONDS).status());
    }

    // SEC-IAM-007 AC-1: GET /auth/admin/users without any credential is 401 unauthenticated; the handler does not run
    @Test
    public void SEC_IAM_007_AC1_noCredentialIs401() throws Exception {
        AdminFixture f = fixture().start();
        ClientResponse r = f.call("GET", "/auth/admin/users", null, null);
        assertEquals(401, r.status());
        assertEquals("unauthenticated", AuthTestSupport.code(r));
        assertFalse(r.text().contains("items"));
    }

    // SEC-IAM-007 AC-2: auth-users:read reads users, but PATCH is 403 (row unchanged) and the groups list is 403
    @Test
    public void SEC_IAM_007_AC2_usersReadOnly() throws Exception {
        AdminFixture f = fixture().start();
        String reader = f.user("ann", "usersreader");
        String bob = f.user("bob", "reader");
        assertEquals(200, f.call("GET", "/auth/admin/users", reader, null).status());
        assertEquals(200, f.call("GET", "/auth/admin/users/" + bob, reader, null).status());
        AuthUser before = f.stored(bob);
        ClientResponse patch = f.call("PATCH", "/auth/admin/users/" + bob, reader, "{\"attributes\":{\"x\":\"y\"}}");
        assertEquals(403, patch.status());
        assertEquals("insufficient_scope", AuthTestSupport.code(patch));
        assertEquals(before, f.stored(bob));
        ClientResponse groups = f.call("GET", "/auth/admin/groups", reader, null);
        assertEquals(403, groups.status());
        assertEquals("insufficient_scope", AuthTestSupport.code(groups));
    }

    // SEC-IAM-007 AC-3: auth-groups:read reads groups, but the users list and a membership PUT are 403 (no membership)
    @Test
    public void SEC_IAM_007_AC3_groupsReadOnly() throws Exception {
        AdminFixture f = fixture().start();
        String reader = f.user("gina", "groupsreader");
        String u1 = f.user("u1", "reader");
        f.groups.confer("eng");
        assertEquals(200, f.call("GET", "/auth/admin/groups", reader, null).status());
        assertEquals(200, f.call("GET", "/auth/admin/groups/eng", reader, null).status());
        assertEquals(403, f.call("GET", "/auth/admin/users", reader, null).status());
        ClientResponse put = f.call("PUT", "/auth/admin/groups/eng/members/" + u1, reader, null);
        assertEquals(403, put.status());
        assertEquals("insufficient_scope", AuthTestSupport.code(put));
        assertTrue(f.groups.groupsOfUser(u1).isEmpty());
    }

    // SEC-IAM-007 AC-4: auth-users:* covers read and write; the built-in admin passes the guard of every admin route
    @Test
    public void SEC_IAM_007_AC4_wildcardsCover() throws Exception {
        AdminFixture f = fixture().start();
        String all = f.user("una", "usersall");
        String bob = f.user("bob");
        assertEquals(200, f.call("GET", "/auth/admin/users", all, null).status());
        assertEquals(200, f.call("PATCH", "/auth/admin/users/" + bob, all, "{\"attributes\":{\"a\":\"b\"}}").status());
        String root = f.user("root", "admin");
        f.groups.confer("eng");
        for (String p : FIFTEEN) {
            String[] mp = p.split(" ");
            String path = "/auth/admin" + mp[1].replace(":id", mp[1].startsWith("/groups") ? "eng" : bob)
                    .replace(":userId", bob).replace(":parentId", "eng");
            ClientResponse r = f.call(mp[0], path, root, mp[0].equals("POST") || mp[0].equals("PATCH") ? "{}" : null);
            assertTrue(p + " " + r.status() + " " + r.text(), r.status() != 401 && r.status() != 403);
        }
    }

    // SEC-IAM-007 AC-5 / SEC-IAM-004 AC-2: a delegated admin (auth-users:write, posts:read) passes the PATCH guard
    // but R31 refuses it on a target holding *; the row is unchanged
    @Test
    public void SEC_IAM_007_AC5_guardThenGrantRule() throws Exception {
        AdminFixture f = fixture().start();
        String delegated = f.user("dee", "userswriter", "reader");
        String root2 = f.user("root2", "admin");
        AuthUser before = f.stored(root2);
        ClientResponse r = f.call("PATCH", "/auth/admin/users/" + root2, delegated, "{\"attributes\":{\"a\":\"b\"}}");
        assertEquals(403, r.status());
        assertEquals("insufficient_scope", AuthTestSupport.code(r));
        assertEquals(before, f.stored(root2));
        assertTrue(f.auditTypes().contains(AuditEvent.GRANT_DENIED));
    }

    // SEC-IAM-007 AC-6 / FR-FLOW-009 AC-6: the lockout release by an actor without auth-users:write is 403 and the
    // lock stays
    @Test
    public void SEC_IAM_007_AC6_lockoutReleaseNeedsUsersWrite() throws Exception {
        AdminFixture f = fixture().start(b -> b.lockout(Lockout.after(2).forMinutes(15)));
        String reader = f.user("ann", "usersreader");
        String alice = f.user("alice", "reader");
        f.auth.lockouts().recordFailure(alice);
        f.auth.lockouts().recordFailure(alice);
        assertTrue(f.auth.lockouts().isLocked(alice));
        ClientResponse r = f.call("DELETE", "/auth/admin/users/" + alice + "/lockout", reader, null);
        assertEquals(403, r.status());
        assertEquals("insufficient_scope", AuthTestSupport.code(r));
        assertTrue(f.auth.lockouts().isLocked(alice));
    }

    // SEC-IAM-007 AC-7: the guard runs before the target is read: PATCH of an unknown id without auth-users:write is
    // 403, not 404
    @Test
    public void SEC_IAM_007_AC7_guardBeforeTargetRead() throws Exception {
        AdminFixture f = fixture().start();
        String reader = f.user("ann", "usersreader");
        ClientResponse r = f.call("PATCH", "/auth/admin/users/unknown-id", reader, "{\"attributes\":{}}");
        assertEquals(403, r.status());
        assertEquals("insufficient_scope", AuthTestSupport.code(r));
    }

    // SEC-IAM-007 AC-8: keys on: auth-users:* gets 403 on the three key routes and no handler runs; auth-apikeys:read
    // lists (200) but cannot POST (403)
    @Test
    public void SEC_IAM_007_AC8_keyRoutesNeedApikeyScopes() throws Exception {
        final AdminFixture.ListKeys keys = new AdminFixture.ListKeys();
        AdminFixture f = fixture().start(b -> b.apiKeys(keys));
        String usersAll = f.user("una", "usersall");
        String keysReader = f.user("kay", "keysreader");
        // the owner is a stored user: an unknown ownerUserId is 404 since FR-APIKEY-004 AC-1
        String u1 = f.user("u1");
        keys.rows.add(StoredApiKey.builder().id("k1").prefix("aBcd1234").secretHash("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef").ownerUserId(u1)
                .ownerVer(0).name("ci").scopes(Collections.singleton("posts:read")).createdAt(1L).build());
        for (String[] c : new String[][] {{"GET", "/auth/admin/apikeys?ownerUserId=" + u1}, {"POST", "/auth/admin/apikeys"},
                {"DELETE", "/auth/admin/apikeys/k1"}}) {
            ClientResponse r = f.call(c[0], c[1], usersAll, c[0].equals("POST") ? "{}" : null);
            assertEquals(c[1], 403, r.status());
            assertEquals("insufficient_scope", AuthTestSupport.code(r));
        }
        assertEquals(1, keys.rows.size());
        assertEquals(0, keys.writes.get());
        ClientResponse list = f.call("GET", "/auth/admin/apikeys?ownerUserId=" + u1, keysReader, null);
        assertEquals(200, list.status());
        assertTrue(list.text().contains("aBcd1234"));
        assertFalse(list.text().contains("secretHash"));
        assertEquals(403, f.call("POST", "/auth/admin/apikeys", keysReader, "{}").status());
        assertEquals(0, keys.writes.get());
    }

    // SEC-IAM-004 AC-1: the built-in admin grants editor to bob; a delegated admin (auth-users:write, posts:read)
    // granting editor (posts:write) is 403 and bob's roles stay
    @Test
    public void SEC_IAM_004_AC1_grantOnlyWhatYouHold() throws Exception {
        AdminFixture f = fixture().start();
        String root = f.user("root", "admin");
        String delegated = f.user("dee", "userswriter", "reader");
        String bob = f.user("bob", "reader");
        ClientResponse denied = f.call("PATCH", "/auth/admin/users/" + bob, delegated, "{\"roles\":[\"reader\",\"editor\"]}");
        assertEquals(403, denied.status());
        assertEquals("insufficient_scope", AuthTestSupport.code(denied));
        assertEquals(AdminFixture.set("reader"), f.stored(bob).getRoles());
        ClientResponse ok = f.call("PATCH", "/auth/admin/users/" + bob, root, "{\"roles\":[\"reader\",\"editor\"]}");
        assertEquals(ok.text(), 200, ok.status());
        assertEquals(AdminFixture.set("reader", "editor"), f.stored(bob).getRoles());
    }

    // SEC-IAM-004 AC-2: a delegated admin removing an admin from a group, disabling it or resetting its password is
    // 403 (the target's * is not covered)
    @Test
    public void SEC_IAM_004_AC2_targetProtection() throws Exception {
        AdminFixture f = fixture().start();
        String delegated = f.user("dee", "userswriter", "groupswriter", "reader");
        String admin = f.user("adm", "admin");
        f.groups.confer("ops");
        f.groups.addMember("ops", admin);
        ClientResponse rm = f.call("DELETE", "/auth/admin/groups/ops/members/" + admin, delegated, null);
        assertEquals(403, rm.status());
        assertEquals("insufficient_scope", AuthTestSupport.code(rm));
        assertEquals(AuditEvent.GRANT_DENIED, f.audit.get(f.audit.size() - 1).getType());
        assertTrue(f.groups.groupsOfUser(admin).contains("ops"));
        ClientResponse dis = f.call("PATCH", "/auth/admin/users/" + admin, delegated, "{\"enabled\":false}");
        assertEquals(403, dis.status());
        assertEquals("insufficient_scope", AuthTestSupport.code(dis));
        assertTrue(f.stored(admin).isEnabled());
        String hash = f.stored(admin).getPasswordHash();
        assertEquals(403, f.call("PATCH", "/auth/admin/users/" + admin, delegated,
                "{\"password\":\"new-password-1\"}").status());
        assertEquals(hash, f.stored(admin).getPasswordHash());
    }

    // SEC-IAM-004 AC-3 / AC-6: an admin demoting, disabling or deleting itself, changing its own groups, or adding a
    // role to itself through a group-conferred posts:* is refused under the self rule with one audit event naming it
    // as actor and target, even as the only admin
    @Test
    public void SEC_IAM_004_AC3_AC6_selfRule() throws Exception {
        AdminFixture f = fixture().start();
        String root = f.user("root", "admin");
        f.groups.confer("eng");
        String[][] calls = {{"PATCH", "/auth/admin/users/" + root, "{\"roles\":[]}"},
                {"PATCH", "/auth/admin/users/" + root, "{\"enabled\":false}"},
                {"DELETE", "/auth/admin/users/" + root, null},
                {"PUT", "/auth/admin/groups/eng/members/" + root, null}};
        for (String[] c : calls) {
            int before = f.audit.size();
            ClientResponse r = f.call(c[0], c[1], root, c[2]);
            assertEquals(c[0] + " " + c[1], 403, r.status());
            assertEquals(before + 1, f.audit.size());
            AuditEvent e = f.audit.get(f.audit.size() - 1);
            assertEquals(AuditEvent.SELF_CHANGE_DENIED, e.getType());
            assertEquals(root, e.getActorId());
            assertEquals(root, e.getTargetId());
        }
        assertEquals(AdminFixture.set("admin"), f.stored(root).getRoles());
        assertTrue(f.stored(root).isEnabled());
        // a delegated admin in group oncall (confers poster: posts:*) adding poster to itself
        f.groups.confer("oncall", "poster");
        String self = f.user("dee", "userswriter");
        f.groups.addMember("oncall", self);
        f.auth.rolesChanged();
        int before = f.audit.size();
        ClientResponse r = f.call("PATCH", "/auth/admin/users/" + self, self, "{\"roles\":[\"userswriter\",\"poster\"]}");
        assertEquals(403, r.status());
        assertEquals(AdminFixture.set("userswriter"), f.stored(self).getRoles());
        assertEquals(before + 1, f.audit.size());
        assertEquals(AuditEvent.SELF_CHANGE_DENIED, f.audit.get(f.audit.size() - 1).getType());
        assertEquals(self, f.audit.get(f.audit.size() - 1).getActorId());
        assertEquals(self, f.audit.get(f.audit.size() - 1).getTargetId());
        // two admins demoting each other is not blocked
        String a2 = f.user("adm2", "admin");
        assertEquals(200, f.call("PATCH", "/auth/admin/users/" + a2, root, "{\"roles\":[\"reader\"]}").status());
    }

    // SEC-IAM-004 AC-6 (the concurrent half): two admins demoting each other at once, both grant checks passing
    // before either write, are not blocked: both answers are 200 and no * holder remains (recovery is the remedy)
    @Test(timeout = 30000)
    public void SEC_IAM_004_AC6_concurrentMutualDemotionNotBlocked() throws Exception {
        final java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(2);
        AdminFixture f = new AdminFixture(base -> new com.snoworca.naruserve.utils.auth.user.ForwardingUserStore(base) {
            @Override
            public boolean setRoles(String id, Set<String> roles) throws Exception {
                barrier.await(10, TimeUnit.SECONDS); // both requests are past their grant check
                return super.setRoles(id, roles);
            }
        }).start();
        String a1 = f.user("a1", "admin");
        String a2 = f.user("a2", "admin");
        Future<ClientResponse> r1 = f.adapter.submit(ClientRequest.json("PATCH", "/auth/admin/users/" + a2,
                "{\"roles\":[\"reader\"]}").header("Authorization", "Bearer " + f.token(a1)));
        Future<ClientResponse> r2 = f.adapter.submit(ClientRequest.json("PATCH", "/auth/admin/users/" + a1,
                "{\"roles\":[\"reader\"]}").header("Authorization", "Bearer " + f.token(a2)));
        assertEquals(200, r1.get(20, TimeUnit.SECONDS).status());
        assertEquals(200, r2.get(20, TimeUnit.SECONDS).status());
        for (AuthUser u : f.base.list(0, 10, null).getItems()) {
            assertFalse(u.getUsername(), u.getRoles().contains("admin"));
        }
    }

    // SEC-IAM-004 AC-4 / SEC-AUTH-007 AC-6: the actor is re-read from the store, not from the principal cache: editor
    // removed directly in the store while the cache still holds it, granting editor is refused
    @Test
    public void SEC_IAM_004_AC4_SEC_AUTH_007_AC6_actorReReadFromStore() throws Exception {
        AdminFixture f = fixture().start();
        String actor = f.user("ua", "userswriter", "editor");
        String bob = f.user("bob");
        assertEquals(200, f.call("PATCH", "/auth/admin/users/" + bob, actor, "{\"attributes\":{\"a\":\"1\"}}")
                .status());
        f.base.setRoles(actor, AdminFixture.set("userswriter")); // straight to the store; the cache keeps editor
        f.clock.addAndGet(1000);
        ClientResponse r = f.call("PATCH", "/auth/admin/users/" + bob, actor, "{\"roles\":[\"editor\"]}");
        assertEquals(403, r.status());
        assertEquals("insufficient_scope", AuthTestSupport.code(r));
        assertTrue(f.stored(bob).getRoles().isEmpty());
    }

    // SEC-IAM-004 AC-4: an actor disabled (or deleted) directly in the store while the principal cache still admits it
    // is refused by the write-time re-read even for a role-less target, with a grant_denied event
    @Test
    public void SEC_IAM_004_AC4_disabledOrMissingActorIsRefusedOnScopeLessTargets() throws Exception {
        AdminFixture f = fixture().start();
        String actor = f.user("ua", "userswriter", "groupswriter");
        String bob = f.user("bob");
        assertEquals(200, f.call("PATCH", "/auth/admin/users/" + bob, actor, "{\"attributes\":{\"a\":\"1\"}}")
                .status()); // the actor's principal is now cached
        String hash = f.stored(bob).getPasswordHash();
        f.base.setEnabled(actor, false); // straight to the store, no ver bump; the cache still admits the actor
        f.clock.addAndGet(1000);
        String[][] calls = {{"PATCH", "/auth/admin/users/" + bob, "{\"password\":\"new-password-1\"}"},
                {"PATCH", "/auth/admin/users/" + bob, "{\"attributes\":{\"a\":\"2\"}}"},
                {"PATCH", "/auth/admin/users/" + bob, "{\"enabled\":false}"},
                {"DELETE", "/auth/admin/users/" + bob, null},
                {"POST", "/auth/admin/users", "{\"username\":\"carol\",\"password\":\"carol-password-1\"}"}};
        for (String[] c : calls) {
            ClientResponse r = f.call(c[0], c[1], actor, c[2]);
            assertEquals(c[0] + " " + c[1] + " " + c[2] + ": " + r.text(), 403, r.status());
            assertEquals("insufficient_scope", AuthTestSupport.code(r));
            assertEquals(AuditEvent.GRANT_DENIED, f.audit.get(f.audit.size() - 1).getType());
        }
        assertEquals(hash, f.stored(bob).getPasswordHash());
        assertEquals("1", f.stored(bob).getAttributes().get("a"));
        assertTrue(f.stored(bob).isEnabled());
        assertNull(f.base.findByUsername("carol"));
        f.groups.confer("eng");
        assertEquals(403, f.call("DELETE", "/auth/admin/groups/eng", actor, null).status());
        f.base.delete(actor);
        f.clock.addAndGet(1000);
        assertEquals(403, f.call("DELETE", "/auth/admin/users/" + bob, actor, null).status());
        assertNotNull(f.base.findById(bob));
    }

    // SEC-IAM-004 AC-5: adding a user to a group conferring posts:* by an actor with auth-groups:write and posts:read
    @Test
    public void SEC_IAM_004_AC5_membershipGrant() throws Exception {
        AdminFixture f = fixture().start();
        String actor = f.user("ga", "groupswriter", "reader");
        String bob = f.user("bob", "reader");
        f.groups.confer("writers", "poster");
        ClientResponse r = f.call("PUT", "/auth/admin/groups/writers/members/" + bob, actor, null);
        assertEquals(403, r.status());
        assertEquals("insufficient_scope", AuthTestSupport.code(r));
        assertTrue(f.groups.groupsOfUser(bob).isEmpty());
    }

    // SEC-IAM-004 AC-7: the target is re-read: bob given admin straight in the store while the cache holds his old
    // scopes; a delegated admin (auth-users:write, posts:*) disabling him is 403 and he stays enabled
    @Test
    public void SEC_IAM_004_AC7_targetReReadFromStore() throws Exception {
        AdminFixture f = fixture().start();
        String actor = f.user("ua", "userswriter", "poster");
        String bob = f.user("bob", "reader");
        assertEquals(403, f.call("GET", "/auth/admin/users", bob, null).status()); // bob's principal is now cached
        f.base.setRoles(bob, AdminFixture.set("admin"));
        f.clock.addAndGet(1000);
        ClientResponse r = f.call("PATCH", "/auth/admin/users/" + bob, actor, "{\"enabled\":false}");
        assertEquals(403, r.status());
        assertEquals("insufficient_scope", AuthTestSupport.code(r));
        assertTrue(f.stored(bob).isEnabled());
    }

    // SEC-IAM-004 AC-8: G7 eng -> writers (posts:*) with members by an actor holding posts:read is 403 and addParent
    // is not called; the built-in admin gets 204 and addParent is called
    @Test
    public void SEC_IAM_004_AC8_edgeGrant() throws Exception {
        AdminFixture f = fixture().start();
        String actor = f.user("ga", "groupswriter", "reader");
        String root = f.user("root", "admin");
        String bob = f.user("bob", "reader");
        f.groups.confer("eng");
        f.groups.confer("writers", "poster");
        f.groups.addMember("eng", bob);
        ClientResponse r = f.call("PUT", "/auth/admin/groups/eng/parents/writers", actor, null);
        assertEquals(403, r.status());
        assertEquals("insufficient_scope", AuthTestSupport.code(r));
        assertEquals(AuditEvent.GRANT_DENIED, f.audit.get(f.audit.size() - 1).getType());
        assertEquals(0, f.groups.addParentCalls.get());
        assertEquals(204, f.call("PUT", "/auth/admin/groups/eng/parents/writers", root, null).status());
        assertEquals(1, f.groups.addParentCalls.get());
    }

    // SEC-IAM-005 AC-1: a delegated admin (auth-groups:write, posts:read) deleting writers (posts:*) with an admin
    // member: enumerated, the admin is not covered, 403 and the group exists
    @Test
    public void SEC_IAM_005_AC1_deleteBypassRefused() throws Exception {
        AdminFixture f = fixture().start();
        String actor = f.user("ga", "groupswriter", "reader");
        String admin = f.user("adm", "admin");
        f.groups.confer("writers", "poster");
        f.groups.addMember("writers", admin);
        ClientResponse r = f.call("DELETE", "/auth/admin/groups/writers", actor, null);
        assertEquals(403, r.status());
        assertEquals("insufficient_scope", AuthTestSupport.code(r));
        assertTrue(f.groups.groupExists("writers"));
    }

    // SEC-IAM-005 AC-2: the same actor with 1,001 affected users (direct plus indirect) gets 409 and nothing is
    // deleted; with 1,000 the limit passes and the per-user check answers 403
    @Test(timeout = 60000)
    public void SEC_IAM_005_AC2_affectedUserLimit() throws Exception {
        AdminFixture f = fixture().start();
        String actor = f.user("ga", "groupswriter", "reader");
        f.groups.confer("writers", "poster");
        f.groups.confer("sub");
        f.groups.addParent("sub", "writers");
        for (int i = 0; i < 1001; i++) {
            String id = f.base.create(AuthUser.builder().username("m" + i).passwordHash("x").build()).getId();
            f.groups.addMember(i % 2 == 0 ? "writers" : "sub", id);
        }
        ClientResponse r = f.call("DELETE", "/auth/admin/groups/writers", actor, null);
        assertEquals(409, r.status());
        assertTrue(f.groups.groupExists("writers"));
        String one = f.groups.membersOf("sub").iterator().next();
        f.groups.removeMember("sub", one);
        ClientResponse at = f.call("DELETE", "/auth/admin/groups/writers", actor, null);
        assertEquals(403, at.status());
        assertEquals("insufficient_scope", AuthTestSupport.code(at));
        assertTrue(f.groups.groupExists("writers"));
    }

    // SEC-IAM-005 AC-3: the built-in admin deletes a group of 5,000 members without any membersOf/childrenOf call
    @Test(timeout = 60000)
    public void SEC_IAM_005_AC3_adminSkipsEnumeration() throws Exception {
        AdminFixture f = fixture().start();
        String root = f.user("root", "admin");
        f.groups.confer("big", "poster");
        for (int i = 0; i < 5000; i++) {
            f.groups.addMember("big", "member-" + i);
        }
        f.groups.traversals.set(0);
        assertEquals(204, f.call("DELETE", "/auth/admin/groups/big", root, null).status());
        assertEquals(0, f.groups.traversals.get());
        assertFalse(f.groups.groupExists("big"));
    }

    // SEC-IAM-005 AC-4: the only admin deleting the admin group it belongs to, directly or through a sub-group, is
    // refused under the self rule
    @Test
    public void SEC_IAM_005_AC4_selfInAffectedUsers() throws Exception {
        AdminFixture f = fixture().start();
        String root = f.user("root", "admin");
        f.groups.confer("admins", "admin");
        f.groups.addMember("admins", root);
        assertEquals(403, f.call("DELETE", "/auth/admin/groups/admins", root, null).status());
        assertTrue(f.groups.groupExists("admins"));
        f.groups.removeMember("admins", root);
        f.groups.confer("sub");
        f.groups.addParent("sub", "admins");
        f.groups.addMember("sub", root);
        assertEquals(403, f.call("DELETE", "/auth/admin/groups/admins", root, null).status());
        assertTrue(f.groups.groupExists("admins"));
        assertEquals(AuditEvent.SELF_CHANGE_DENIED, f.audit.get(f.audit.size() - 1).getType());
    }

    // SEC-IAM-005 AC-5: removing the edge child -> parent checks every user who loses scopes through it
    @Test
    public void SEC_IAM_005_AC5_edgeRemovalChecksAffected() throws Exception {
        AdminFixture f = fixture().start();
        String actor = f.user("ga", "groupswriter", "reader");
        String admin = f.user("adm", "admin");
        String plain = f.user("pat", "reader");
        f.groups.confer("writers", "poster");
        f.groups.confer("eng");
        f.groups.addParent("eng", "writers");
        f.groups.addMember("eng", admin);
        assertEquals(403, f.call("DELETE", "/auth/admin/groups/eng/parents/writers", actor, null).status());
        assertTrue(f.groups.parentsOf(Collections.singleton("eng")).contains("writers"));
        f.groups.removeMember("eng", admin);
        f.groups.addMember("eng", plain);
        f.base.setRoles(plain, AdminFixture.set("reader"));
        // pat holds posts:* only through the edge: the actor (posts:read) does not cover it
        assertEquals(403, f.call("DELETE", "/auth/admin/groups/eng/parents/writers", actor, null).status());
        String root = f.user("root", "admin");
        assertEquals(204, f.call("DELETE", "/auth/admin/groups/eng/parents/writers", root, null).status());
        assertFalse(f.groups.parentsOf(Collections.singleton("eng")).contains("writers"));
        // the same 1,000-user limit: 1,001 users below the edge answer 409 and the edge stays
        f.groups.confer("big");
        f.groups.addParent("big", "writers");
        for (int i = 0; i < 1001; i++) {
            f.groups.addMember("big", "b-" + i);
        }
        ClientResponse limit = f.call("DELETE", "/auth/admin/groups/big/parents/writers", actor, null);
        assertEquals(409, limit.status());
        assertEquals("group_too_large", AuthTestSupport.code(limit));
        assertTrue(f.groups.parentsOf(Collections.singleton("big")).contains("writers"));
        // the same self rule: an actor below the edge is refused
        f.groups.confer("mine");
        f.groups.addParent("mine", "writers");
        f.groups.addMember("mine", actor);
        assertEquals(403, f.call("DELETE", "/auth/admin/groups/mine/parents/writers", actor, null).status());
        assertEquals(AuditEvent.SELF_CHANGE_DENIED, f.audit.get(f.audit.size() - 1).getType());
        assertTrue(f.groups.parentsOf(Collections.singleton("mine")).contains("writers"));
    }

    // SEC-IAM-005 AC-6: an empty *-conferring group is deleted by an actor who cannot cover *
    @Test
    public void SEC_IAM_005_AC6_emptyStarGroupDeletable() throws Exception {
        AdminFixture f = fixture().start();
        String actor = f.user("ga", "groupswriter", "reader");
        f.groups.confer("supers", "admin");
        assertEquals(204, f.call("DELETE", "/auth/admin/groups/supers", actor, null).status());
        assertFalse(f.groups.groupExists("supers"));
    }

    // SEC-IAM-005 AC-8: a delegated admin covering posts:* deletes writers (posts:*) with 1,001 members without
    // traversal and without the limit
    @Test(timeout = 60000)
    public void SEC_IAM_005_AC8_skipForCoveringDelegate() throws Exception {
        AdminFixture f = fixture().start();
        String actor = f.user("ga", "groupswriter", "poster");
        f.groups.confer("writers", "poster");
        for (int i = 0; i < 1001; i++) {
            f.groups.addMember("writers", "w-" + i);
        }
        f.groups.traversals.set(0);
        assertEquals(204, f.call("DELETE", "/auth/admin/groups/writers", actor, null).status());
        assertEquals(0, f.groups.traversals.get());
    }

    // SEC-IAM-001 AC-6: A2 whose attributes name a 00 R37 security field (each separately) is 400 BAD_REQUEST and
    // creates no user; with nickname it succeeds (201) and stores it
    @Test
    public void SEC_IAM_001_AC6_reservedAttributeKeysOnA2() throws Exception {
        AdminFixture f = fixture().start();
        String root = f.user("root", "admin");
        String[] reserved = {"passwordHash", "ver", "enabled", "roles"};
        for (int i = 0; i < reserved.length; i++) {
            ClientResponse r = f.call("POST", "/auth/admin/users", root, "{\"username\":\"new" + i
                    + "\",\"password\":\"long-password-1\",\"attributes\":{\"" + reserved[i] + "\":\"x\"}}");
            assertEquals(reserved[i] + " " + r.text(), 400, r.status());
            assertEquals("BAD_REQUEST", AuthTestSupport.code(r));
            assertNull(f.base.findByUsername("new" + i));
        }
        ClientResponse ok = f.call("POST", "/auth/admin/users", root,
                "{\"username\":\"nick\",\"password\":\"long-password-1\",\"attributes\":{\"nickname\":\"n\"}}");
        assertEquals(201, ok.status());
        assertEquals("n", f.base.findByUsername("nick").getAttribute("nickname"));
    }

    // FR-IAM-014 AC-1: a taken username plus a password is 409 duplicate_user with applied [] written by A4; name and
    // hash unchanged
    @Test
    public void FR_IAM_014_AC1_duplicateNameFirst() throws Exception {
        AdminFixture f = fixture().start();
        String root = f.user("root", "admin");
        String alice = f.user("alice", "reader");
        f.user("bob", "reader");
        String hash = f.stored(alice).getPasswordHash();
        ClientResponse r = f.call("PATCH", "/auth/admin/users/" + alice, root,
                "{\"username\":\"bob\",\"password\":\"new-password-1\"}");
        assertEquals(409, r.status());
        assertEquals("duplicate_user", AuthTestSupport.code(r));
        assertEquals(Collections.emptyList(), AdminFixture.object(r).get("applied"));
        assertEquals("alice", f.stored(alice).getUsername());
        assertEquals(hash, f.stored(alice).getPasswordHash());
    }

    /** A store whose changePassword loses every race (returns false). */
    private static AdminFixture.StoreWrapper losingPassword() {
        return base -> new com.snoworca.naruserve.utils.auth.user.ForwardingUserStore(base) {
            @Override
            public boolean changePassword(String id, String expectedOldHash, long expectedVer, String newHash) {
                return false;
            }
        };
    }

    // FR-IAM-014 AC-2 / REL-IAM-002 AC-4: a password that loses its race is 409 write_conflict with applied []; the
    // roles are not applied and the reset is not retried
    @Test
    public void FR_IAM_014_AC2_REL_IAM_002_AC4_lostPasswordRace() throws Exception {
        final int[] calls = new int[1];
        AdminFixture f = new AdminFixture(base -> new com.snoworca.naruserve.utils.auth.user.ForwardingUserStore(base) {
            @Override
            public boolean changePassword(String id, String expectedOldHash, long expectedVer, String newHash) {
                calls[0]++;
                return false;
            }
        }).start();
        String root = f.user("root", "admin");
        String bob = f.user("bob", "reader");
        ClientResponse r = f.call("PATCH", "/auth/admin/users/" + bob, root,
                "{\"password\":\"new-password-1\",\"roles\":[\"editor\"]}");
        assertEquals(409, r.status());
        assertEquals("write_conflict", AuthTestSupport.code(r));
        assertEquals(Collections.emptyList(), AdminFixture.object(r).get("applied"));
        assertEquals(AdminFixture.set("reader"), f.stored(bob).getRoles());
        assertEquals(1, calls[0]);
    }

    // FR-IAM-014 AC-3: a free username plus a losing password: 409 write_conflict, applied ["username"], the name is
    // stored, the hash unchanged, one onUserUpdated (ADMIN, USERNAME) and no onPasswordChanged
    @Test
    public void FR_IAM_014_AC3_renameThenLostPassword() throws Exception {
        AdminFixture f = new AdminFixture(losingPassword()).start();
        String root = f.user("root", "admin");
        String alice = f.user("alice", "reader");
        String hash = f.stored(alice).getPasswordHash();
        ClientResponse r = f.call("PATCH", "/auth/admin/users/" + alice, root,
                "{\"username\":\"alicia\",\"password\":\"new-password-1\"}");
        assertEquals(409, r.status());
        assertEquals("write_conflict", AuthTestSupport.code(r));
        assertEquals(Collections.singletonList("username"), AdminFixture.object(r).get("applied"));
        assertEquals("alicia", f.stored(alice).getUsername());
        assertEquals(hash, f.stored(alice).getPasswordHash());
        assertEquals(Collections.singletonList(EnumSet.of(UserChange.USERNAME)), f.events.updated);
        assertEquals(Collections.singletonList(UserOrigin.ADMIN), f.events.origins);
        assertEquals(0, f.events.passwordChanged.get());
    }

    // FR-IAM-014 AC-4: "Bob" is stored as "bob"; the target's own current name is not a 409 and the rest applies
    @Test
    public void FR_IAM_014_AC4_renameNormalizesAndSameNameIsNoOp() throws Exception {
        AdminFixture f = fixture().start();
        String root = f.user("root", "admin");
        String alice = f.user("alice", "reader");
        assertEquals(200, f.call("PATCH", "/auth/admin/users/" + alice, root, "{\"username\":\"Bob\"}").status());
        assertEquals("bob", f.stored(alice).getUsername());
        ClientResponse same = f.call("PATCH", "/auth/admin/users/" + alice, root,
                "{\"username\":\"bob\",\"attributes\":{\"k\":\"v\"}}");
        assertEquals(same.text(), 200, same.status());
        assertEquals("v", f.stored(alice).getAttribute("k"));
        assertEquals(EnumSet.of(UserChange.ATTRIBUTES), f.events.updated.get(f.events.updated.size() - 1));
    }

    // FR-IAM-014 AC-5: a delegated admin sending attributes plus roles [editor] is 403 and nothing, attributes
    // included, is applied
    @Test
    public void FR_IAM_014_AC5_grantRuleOnceBeforeAnything() throws Exception {
        AdminFixture f = fixture().start();
        String delegated = f.user("dee", "userswriter", "reader");
        String bob = f.user("bob", "reader");
        AuthUser before = f.stored(bob);
        ClientResponse r = f.call("PATCH", "/auth/admin/users/" + bob, delegated,
                "{\"attributes\":{\"a\":\"b\"},\"roles\":[\"editor\"]}");
        assertEquals(403, r.status());
        assertEquals("insufficient_scope", AuthTestSupport.code(r));
        assertEquals(before, f.stored(bob));
        assertTrue(f.events.updated.isEmpty());
    }

    /** A store whose setRoles and setEnabled always fail to confirm (the default's 3 retries, then the throw). */
    private static AdminFixture.StoreWrapper unconfirmedSecurityWrites() {
        return base -> new com.snoworca.naruserve.utils.auth.user.UserStore() {
            @Override
            public AuthUser findByUsername(String username) throws Exception {
                return base.findByUsername(username);
            }

            @Override
            public AuthUser findById(String id) throws Exception {
                return base.findById(id);
            }

            @Override
            public AuthUser create(AuthUser user) throws Exception {
                return base.create(user);
            }

            @Override
            public AuthUser update(AuthUser user) throws Exception {
                AuthUser cur = base.findById(user.getId());
                if (cur != null && (!cur.getRoles().equals(user.getRoles()) || cur.isEnabled() != user.isEnabled())) {
                    return cur; // the role and enabled changes silently do not apply
                }
                return base.update(user);
            }

            @Override
            public boolean delete(String id) throws Exception {
                return base.delete(id);
            }
        };
    }

    // FR-IAM-014 AC-6: password applied, then setRoles fails after its retries (security_write_conflict): 503 with that
    // code, applied ["password"], one security_write_conflict audit event, one onUserUpdated (PASSWORD) and one
    // onPasswordChanged
    // REL-IAM-001 AC-2 (the A4 half): the answer is the A4 handler's own 503 security_write_conflict with one audit event
    @Test
    public void FR_IAM_014_AC6_securityWriteAfterPassword() throws Exception {
        AdminFixture f = new AdminFixture(unconfirmedSecurityWrites()).start();
        String root = f.user("root", "admin");
        String bob = f.user("bob", "reader");
        String hash = f.stored(bob).getPasswordHash();
        ClientResponse r = f.call("PATCH", "/auth/admin/users/" + bob, root,
                "{\"password\":\"new-password-1\",\"roles\":[\"editor\"]}");
        assertEquals(503, r.status());
        assertEquals("security_write_conflict", AuthTestSupport.code(r));
        assertEquals(Collections.singletonList("password"), AdminFixture.object(r).get("applied"));
        assertFalse(hash.equals(f.stored(bob).getPasswordHash()));
        assertEquals(Collections.singletonList(AuditEvent.SECURITY_WRITE_CONFLICT), f.auditTypes());
        assertEquals(Collections.singletonList(EnumSet.of(UserChange.PASSWORD)), f.events.updated);
        assertEquals(Collections.singletonList(UserOrigin.ADMIN), f.events.origins);
        assertEquals(1, f.events.passwordChanged.get());
    }

    // FR-IAM-014 AC-7: username, enabled and attributes in one A4: 200, a hash-free body and one onUserUpdated (ADMIN)
    // with all three changes
    @Test
    public void FR_IAM_014_AC7_successOneEvent() throws Exception {
        AdminFixture f = fixture().start();
        String root = f.user("root", "admin");
        String bob = f.user("bob", "reader");
        f.base.setEnabled(bob, false);
        ClientResponse r = f.call("PATCH", "/auth/admin/users/" + bob, root,
                "{\"username\":\"robert\",\"enabled\":true,\"attributes\":{\"team\":\"a\"}}");
        assertEquals(r.text(), 200, r.status());
        assertFalse(r.text().contains("passwordHash"));
        assertFalse(r.text().contains("pbkdf2"));
        assertEquals("robert", AdminFixture.object(r).get("username"));
        assertEquals(Collections.singletonList(EnumSet.of(UserChange.USERNAME, UserChange.ENABLED,
                UserChange.ATTRIBUTES)), f.events.updated);
        assertEquals(Collections.singletonList(UserOrigin.ADMIN), f.events.origins);
    }

    // FR-IAM-014 AC-8 / REL-IAM-002 AC-8 (and the admin reset and admin disable rows of SEC-IAM-003): roles leave ver, a
    // password adds exactly one, enabled false adds exactly one, enabled true alone leaves it; a reset of a disabled
    // user with enabled:true stores the hash, bumps ver once and enables
    @Test
    public void FR_IAM_014_AC8_REL_IAM_002_AC8_whichFieldsBumpVer() throws Exception {
        AdminFixture f = fixture().start();
        String root = f.user("root", "admin");
        String bob = f.user("bob", "reader");
        long v0 = f.stored(bob).getTokenVersion();
        assertEquals(200, f.call("PATCH", "/auth/admin/users/" + bob, root, "{\"roles\":[\"editor\"]}").status());
        assertEquals(v0, f.stored(bob).getTokenVersion());
        assertEquals(200, f.call("PATCH", "/auth/admin/users/" + bob, root, "{\"password\":\"new-password-1\"}")
                .status());
        assertEquals(v0 + 1, f.stored(bob).getTokenVersion());
        assertEquals(200, f.call("PATCH", "/auth/admin/users/" + bob, root, "{\"enabled\":false}").status());
        assertEquals(v0 + 2, f.stored(bob).getTokenVersion());
        assertFalse(f.stored(bob).isEnabled());
        String hash = f.stored(bob).getPasswordHash();
        ClientResponse reset = f.call("PATCH", "/auth/admin/users/" + bob, root,
                "{\"password\":\"other-password-2\",\"enabled\":true}");
        assertEquals(reset.text(), 200, reset.status());
        assertEquals(v0 + 3, f.stored(bob).getTokenVersion());
        assertTrue(f.stored(bob).isEnabled());
        assertFalse(hash.equals(f.stored(bob).getPasswordHash()));
        assertTrue(f.auth.passwords().verify("other-password-2".toCharArray(), f.stored(bob).getPasswordHash()));
        assertEquals(200, f.call("PATCH", "/auth/admin/users/" + bob, root, "{\"enabled\":true}").status());
        assertEquals(v0 + 3, f.stored(bob).getTokenVersion());
    }

    /** Five required methods; update applies the rename but silently drops a hash and ver change. */
    private static AdminFixture.StoreWrapper hashDroppingStore() {
        return base -> new com.snoworca.naruserve.utils.auth.user.UserStore() {
            @Override
            public AuthUser findByUsername(String username) throws Exception {
                return base.findByUsername(username);
            }

            @Override
            public AuthUser findById(String id) throws Exception {
                return base.findById(id);
            }

            @Override
            public AuthUser create(AuthUser user) throws Exception {
                return base.create(user);
            }

            @Override
            public AuthUser update(AuthUser user) throws Exception {
                AuthUser cur = base.findById(user.getId());
                if (cur == null) {
                    return null;
                }
                return base.update(user.toBuilder().passwordHash(cur.getPasswordHash())
                        .tokenVersion(cur.getTokenVersion()).build());
            }

            @Override
            public boolean delete(String id) throws Exception {
                return base.delete(id);
            }
        };
    }

    // FR-IAM-014 AC-9 (a): a free username plus a password the store default cannot confirm: 503
    // security_write_conflict with applied ["username"], the name stored, hash and ver unchanged, one audit
    // security_write_conflict, one onUserUpdated (USERNAME) and no onPasswordChanged
    @Test
    public void FR_IAM_014_AC9a_unconfirmedPasswordAfterRename() throws Exception {
        AdminFixture f = new AdminFixture(hashDroppingStore()).start();
        String root = f.user("root", "admin");
        String alice = f.user("alice", "reader");
        AuthUser before = f.stored(alice);
        ClientResponse r = f.call("PATCH", "/auth/admin/users/" + alice, root,
                "{\"username\":\"alicia\",\"password\":\"new-password-1\"}");
        assertEquals(503, r.status());
        assertEquals("security_write_conflict", AuthTestSupport.code(r));
        assertEquals(Collections.singletonList("username"), AdminFixture.object(r).get("applied"));
        assertEquals("alicia", f.stored(alice).getUsername());
        assertEquals(before.getPasswordHash(), f.stored(alice).getPasswordHash());
        assertEquals(before.getTokenVersion(), f.stored(alice).getTokenVersion());
        assertEquals(Collections.singletonList(AuditEvent.SECURITY_WRITE_CONFLICT), f.auditTypes());
        assertEquals(Collections.singletonList(EnumSet.of(UserChange.USERNAME)), f.events.updated);
        assertEquals(Collections.singletonList(UserOrigin.ADMIN), f.events.origins);
        assertEquals(0, f.events.passwordChanged.get());
    }

    private static AdminFixture.StoreWrapper failingSetRoles(final RuntimeException thrown) {
        return base -> new com.snoworca.naruserve.utils.auth.user.ForwardingUserStore(base) {
            @Override
            public boolean setRoles(String id, Set<String> roles) throws Exception {
                if (thrown instanceof IllegalStateException) {
                    throw new java.sql.SQLException("roles table down");
                }
                throw thrown;
            }
        };
    }

    // FR-IAM-014 AC-9 (b) / AC-10: setRoles throwing a plain store exception after the password: 503
    // SERVICE_UNAVAILABLE, applied in application order (["username", "password"] with a rename), one audit
    // user_update_failed, one onUserUpdated and one onPasswordChanged
    @Test
    public void FR_IAM_014_AC9b_AC10_storeFailureAfterPassword() throws Exception {
        AdminFixture f = new AdminFixture(failingSetRoles(new IllegalStateException())).start();
        String root = f.user("root", "admin");
        String bob = f.user("bob", "reader");
        ClientResponse r = f.call("PATCH", "/auth/admin/users/" + bob, root,
                "{\"password\":\"new-password-1\",\"roles\":[\"editor\"]}");
        assertEquals(503, r.status());
        assertEquals("SERVICE_UNAVAILABLE", AuthTestSupport.code(r));
        assertEquals(Collections.singletonList("password"), AdminFixture.object(r).get("applied"));
        assertTrue(r.text().startsWith("{\"error\":{\"code\":"));
        assertEquals(Collections.singletonList(AuditEvent.USER_UPDATE_FAILED), f.auditTypes());
        assertEquals(Collections.singletonList(EnumSet.of(UserChange.PASSWORD)), f.events.updated);
        assertEquals(Collections.singletonList(UserOrigin.ADMIN), f.events.origins);
        assertEquals(1, f.events.passwordChanged.get());
        ClientResponse three = f.call("PATCH", "/auth/admin/users/" + bob, root,
                "{\"username\":\"robert\",\"password\":\"newer-password-2\",\"roles\":[\"editor\"]}");
        assertEquals(503, three.status());
        assertEquals(Arrays.asList("username", "password"), AdminFixture.object(three).get("applied"));
    }

    // FR-IAM-014 AC-6 (the setEnabled branch): password applied, then setEnabled fails after its retries: 503
    // security_write_conflict, applied ["password"], one audit event, one onUserUpdated (PASSWORD), one onPasswordChanged
    @Test
    public void FR_IAM_014_AC6_setEnabledAfterPassword() throws Exception {
        AdminFixture f = new AdminFixture(unconfirmedSecurityWrites()).start();
        String root = f.user("root", "admin");
        String bob = f.user("bob", "reader");
        String hash = f.stored(bob).getPasswordHash();
        ClientResponse r = f.call("PATCH", "/auth/admin/users/" + bob, root,
                "{\"password\":\"new-password-1\",\"enabled\":false}");
        assertEquals(r.text(), 503, r.status());
        assertEquals("security_write_conflict", AuthTestSupport.code(r));
        assertEquals(Collections.singletonList("password"), AdminFixture.object(r).get("applied"));
        assertFalse(hash.equals(f.stored(bob).getPasswordHash()));
        assertTrue(f.stored(bob).isEnabled());
        assertEquals(Collections.singletonList(AuditEvent.SECURITY_WRITE_CONFLICT), f.auditTypes());
        assertEquals(Collections.singletonList(EnumSet.of(UserChange.PASSWORD)), f.events.updated);
        assertEquals(Collections.singletonList(UserOrigin.ADMIN), f.events.origins);
        assertEquals(1, f.events.passwordChanged.get());
    }

    @SuppressWarnings("unchecked")
    private static void errorBesideApplied(ClientResponse r) {
        Map<String, Object> body = AdminFixture.object(r);
        assertEquals(r.text(), AdminFixture.set("error", "applied"), new HashSet<>(body.keySet()));
        assertEquals(r.text(), AdminFixture.set("code", "message", "requestId"),
                new HashSet<>(((Map<String, Object>) body.get("error")).keySet()));
    }

    // FR-IAM-014 AC-10: in a 409 and in a 503 of A4, applied is a top-level member beside the error object, which holds
    // code, message and requestId
    @Test
    public void FR_IAM_014_AC10_appliedSitsBesideTheErrorObject() throws Exception {
        AdminFixture lost = new AdminFixture(losingPassword()).start();
        ClientResponse conflict = lost.call("PATCH", "/auth/admin/users/" + lost.user("alice", "reader"),
                lost.user("root", "admin"), "{\"username\":\"alicia\",\"password\":\"new-password-1\"}");
        assertEquals(409, conflict.status());
        errorBesideApplied(conflict);
        AdminFixture down = new AdminFixture(failingSetRoles(new IllegalStateException())).start();
        ClientResponse failed = down.call("PATCH", "/auth/admin/users/" + down.user("bob", "reader"),
                down.user("root", "admin"), "{\"password\":\"new-password-1\",\"roles\":[\"editor\"]}");
        assertEquals(503, failed.status());
        errorBesideApplied(failed);
    }

    // FR-IAM-009 AC-5 (the R31 side, with the four cases of AC-3 and AC-4): an API-key actor grants a role only when the
    // key's scopes and the owner's scopes both cover what it confers, never by intersecting wildcard sets
    @Test
    public void FR_IAM_009_AC5_theGrantRuleUsesTheBothCoverPredicate() throws Exception {
        AdminFixture f = fixture().start(b -> b.credentials(new AuthTestSupport.TestCredentials()));
        String[][] cases = {
            // owner role, key scope, the role both cover, the role only one covers
            {"reader", "posts:*", "reader", "editor"},
            {"admin", "posts:read", "reader", "editor"},
            {"poster", "*", "editor", "docs"},
        };
        for (int i = 0; i < cases.length; i++) {
            String[] c = cases[i];
            String owner = f.user("owner" + i, "userswriter", c[0]);
            String key = "auth-users:write," + c[1];
            String allowed = f.user("allowed" + i);
            ClientResponse ok = asKey(f, "PATCH", "/auth/admin/users/" + allowed, owner, key,
                    "{\"roles\":[\"" + c[2] + "\"]}");
            assertEquals(Arrays.toString(c) + " " + ok.text(), 200, ok.status());
            assertEquals(AdminFixture.set(c[2]), f.stored(allowed).getRoles());
            String refused = f.user("refused" + i);
            ClientResponse no = asKey(f, "PATCH", "/auth/admin/users/" + refused, owner, key,
                    "{\"roles\":[\"" + c[3] + "\"]}");
            assertEquals(Arrays.toString(c) + " " + no.text(), 403, no.status());
            assertEquals("insufficient_scope", AuthTestSupport.code(no));
            assertTrue(f.stored(refused).getRoles().isEmpty());
        }
    }

    // FR-IAM-014 AC-11: setRoles throwing AuthRejection(422, role_policy) or an IllegalArgumentException after the
    // password: 503 SERVICE_UNAVAILABLE with applied ["password"] by A4, one user_update_failed audit event, the new
    // hash stored, the roles unchanged, one onUserUpdated (PASSWORD) and one onPasswordChanged
    @Test
    public void FR_IAM_014_AC11_rejectionAfterPassword() throws Exception {
        for (RuntimeException thrown : new RuntimeException[] {new AuthRejection(422, "role_policy"),
                new IllegalArgumentException("no such role")}) {
            AdminFixture f = new AdminFixture(failingSetRoles(thrown)).start();
            String root = f.user("root", "admin");
            String bob = f.user("bob", "reader");
            String hash = f.stored(bob).getPasswordHash();
            ClientResponse r = f.call("PATCH", "/auth/admin/users/" + bob, root,
                    "{\"password\":\"new-password-1\",\"roles\":[\"editor\"]}");
            assertEquals(thrown.toString(), 503, r.status());
            assertEquals("SERVICE_UNAVAILABLE", AuthTestSupport.code(r));
            assertEquals(Collections.singletonList("password"), AdminFixture.object(r).get("applied"));
            assertEquals(Collections.singletonList(AuditEvent.USER_UPDATE_FAILED), f.auditTypes());
            assertFalse(hash.equals(f.stored(bob).getPasswordHash()));
            assertEquals(AdminFixture.set("reader"), f.stored(bob).getRoles());
            assertEquals(Collections.singletonList(EnumSet.of(UserChange.PASSWORD)), f.events.updated);
            assertEquals(Collections.singletonList(UserOrigin.ADMIN), f.events.origins);
            assertEquals(1, f.events.passwordChanged.get());
        }
    }

    // SEC-IAM-003 AC-2 (auth paths): A4 roles, a group membership (G5) and edge (G7) change, auth.users().update of
    // attributes and rename leave ver unchanged; a direct UserStore.setEnabled leaves it too; the rehash
    // updatePasswordHash does not change it
    @Test
    public void SEC_IAM_003_AC2_changesThatKeepVer() throws Exception {
        AdminFixture f = fixture().start();
        String root = f.user("root", "admin");
        String bob = f.user("bob", "reader");
        f.groups.confer("eng");
        f.groups.confer("all", "editor");
        long v = f.stored(bob).getTokenVersion();
        assertEquals(200, f.call("PATCH", "/auth/admin/users/" + bob, root, "{\"roles\":[\"editor\"]}").status());
        assertEquals(204, f.call("PUT", "/auth/admin/groups/eng/members/" + bob, root, null).status());
        assertEquals(204, f.call("PUT", "/auth/admin/groups/eng/parents/all", root, null).status());
        f.auth.users().update(bob, u -> u.toBuilder().attribute("nick", "b").build());
        f.auth.users().rename(bob, "bobby");
        f.base.setEnabled(bob, false);
        String hash = f.stored(bob).getPasswordHash();
        assertTrue(f.base.updatePasswordHash(bob, hash, f.auth.passwords().hash("rehashed-pw".toCharArray())));
        assertEquals(v, f.stored(bob).getTokenVersion());
    }

    // IR-IAM-004 AC-4 (the real route): G7 refuses a cycle and a self edge with 409 group_cycle and neither reaches
    // addParent
    @Test
    public void IR_IAM_004_AC4_g7RefusesCycleAndSelfEdge() throws Exception {
        AdminFixture f = fixture().start();
        String root = f.user("root", "admin");
        f.groups.confer("eng");
        f.groups.confer("backend");
        f.groups.addParent("backend", "eng");
        f.groups.addParentCalls.set(0);
        for (String path : new String[] {"/auth/admin/groups/eng/parents/backend",
                "/auth/admin/groups/eng/parents/eng"}) {
            ClientResponse r = f.call("PUT", path, root, null);
            assertEquals(path + " " + r.text(), 409, r.status());
            assertEquals("group_cycle", AuthTestSupport.code(r));
        }
        assertEquals(0, f.groups.addParentCalls.get());
        assertTrue(f.groups.parentsOf(Collections.singleton("eng")).isEmpty());
    }

    // IR-IAM-004 AC-5 (the real route): G7 refuses an edge whose chain would pass depth 8 with 409 and no addParent
    // call; the same chain at exactly 8 is added
    @Test
    public void IR_IAM_004_AC5_g7RefusesADepthAboveEight() throws Exception {
        AdminFixture f = fixture().start();
        String root = f.user("root", "admin");
        for (int i = 0; i <= 9; i++) {
            f.groups.confer("g" + i);
        }
        for (int i = 0; i < 8; i++) {
            f.groups.addParent("g" + i, "g" + (i + 1));
        }
        f.groups.confer("h0");
        f.groups.confer("h1");
        f.groups.addParent("h0", "h1");
        f.groups.addParentCalls.set(0);
        // above g9: 0, below g8: 8 -> 9 edges; above g1: 7, below h1: 1 -> 9 edges
        for (String path : new String[] {"/auth/admin/groups/g8/parents/g9", "/auth/admin/groups/h1/parents/g1"}) {
            ClientResponse r = f.call("PUT", path, root, null);
            assertEquals(path + " " + r.text(), 409, r.status());
            assertEquals("group_cycle", AuthTestSupport.code(r));
        }
        assertEquals(0, f.groups.addParentCalls.get());
        // above g2: 6, below h1: 1 -> 8 edges
        assertEquals(204, f.call("PUT", "/auth/admin/groups/h1/parents/g2", root, null).status());
        assertEquals(1, f.groups.addParentCalls.get());
    }

    // REL-IAM-002 AC-8 (order and the store): A4 {password, enabled:true} on a disabled user writes the hash while the
    // user is still disabled and enables after it, with one ver++; the store-level changePassword has no enabled
    // condition
    @Test
    public void REL_IAM_002_AC8_resetEnablesAfterTheHash() throws Exception {
        final List<String> order = new java.util.concurrent.CopyOnWriteArrayList<>();
        AdminFixture f = new AdminFixture(base -> new com.snoworca.naruserve.utils.auth.user.ForwardingUserStore(base) {
            @Override
            public boolean changePassword(String id, String expectedOldHash, long expectedVer, String newHash)
                    throws Exception {
                order.add("changePassword enabled=" + base.findById(id).isEnabled());
                return super.changePassword(id, expectedOldHash, expectedVer, newHash);
            }

            @Override
            public boolean setEnabled(String id, boolean enabled) throws Exception {
                order.add("setEnabled " + enabled);
                return super.setEnabled(id, enabled);
            }
        });
        fixtures.add(f);
        f.start();
        String root = f.user("root", "admin");
        String bob = f.user("bob", "reader");
        f.base.setEnabled(bob, false);
        long v = f.stored(bob).getTokenVersion();
        ClientResponse r = f.call("PATCH", "/auth/admin/users/" + bob, root,
                "{\"password\":\"new-password-1\",\"enabled\":true}");
        assertEquals(r.text(), 200, r.status());
        assertEquals(Arrays.asList("changePassword enabled=false", "setEnabled true"), order);
        assertEquals(v + 1, f.stored(bob).getTokenVersion());
        assertTrue(f.stored(bob).isEnabled());
        String carol = f.user("carol", "reader");
        f.base.setEnabled(carol, false);
        AuthUser disabled = f.stored(carol);
        assertTrue(f.base.changePassword(carol, disabled.getPasswordHash(), disabled.getTokenVersion(), "new-hash"));
        assertEquals("new-hash", f.stored(carol).getPasswordHash());
        assertFalse(f.stored(carol).isEnabled());
    }

    // SEC-IAM-004 AC-2 (the reset row): a refused password reset of an admin carries the code insufficient_scope too
    @Test
    public void SEC_IAM_004_AC2_refusedResetCarriesTheCode() throws Exception {
        AdminFixture f = fixture().start();
        String delegated = f.user("dee", "userswriter", "reader");
        String admin = f.user("adm", "admin");
        String hash = f.stored(admin).getPasswordHash();
        ClientResponse r = f.call("PATCH", "/auth/admin/users/" + admin, delegated, "{\"password\":\"new-password-1\"}");
        assertEquals(403, r.status());
        assertEquals("insufficient_scope", AuthTestSupport.code(r));
        assertEquals(hash, f.stored(admin).getPasswordHash());
    }

    // SEC-IAM-007 AC-3 (the users list): auth-groups:read on GET /auth/admin/users answers the code insufficient_scope
    @Test
    public void SEC_IAM_007_AC3_usersListRefusalCarriesTheCode() throws Exception {
        AdminFixture f = fixture().start();
        ClientResponse r = f.call("GET", "/auth/admin/users", f.user("gina", "groupsreader"), null);
        assertEquals(403, r.status());
        assertEquals("insufficient_scope", AuthTestSupport.code(r));
    }

    // SEC-IAM-007 AC-4 (keys on): the built-in admin also passes the guard of the three key routes
    @Test
    public void SEC_IAM_007_AC4_adminPassesTheKeyRouteGuards() throws Exception {
        final AdminFixture.ListKeys keys = new AdminFixture.ListKeys();
        AdminFixture f = fixture().start(b -> b.apiKeys(keys));
        String root = f.user("root", "admin");
        String u1 = f.user("u1");
        keys.rows.add(StoredApiKey.builder().id("k1").prefix("aBcd1234").secretHash("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef").ownerUserId(u1)
                .ownerVer(0).name("ci").scopes(Collections.singleton("posts:read")).createdAt(1L).build());
        for (String[] c : new String[][] {{"GET", "/auth/admin/apikeys?ownerUserId=" + u1},
                {"POST", "/auth/admin/apikeys"}, {"DELETE", "/auth/admin/apikeys/k1"}}) {
            ClientResponse r = f.call(c[0], c[1], root, c[0].equals("POST") ? "{}" : null);
            assertFalse(c[1] + " " + r.status() + " " + r.text(), r.status() == 401 || r.status() == 403);
        }
    }

    // SEC-IAM-007 AC-8 (the reader's POST): auth-apikeys:read on POST /auth/admin/apikeys answers the code
    // insufficient_scope
    @Test
    public void SEC_IAM_007_AC8_keyReaderPostCarriesTheCode() throws Exception {
        final AdminFixture.ListKeys keys = new AdminFixture.ListKeys();
        AdminFixture f = fixture().start(b -> b.apiKeys(keys));
        ClientResponse r = f.call("POST", "/auth/admin/apikeys", f.user("kay", "keysreader"), "{}");
        assertEquals(403, r.status());
        assertEquals("insufficient_scope", AuthTestSupport.code(r));
        assertEquals(0, keys.writes.get());
    }

    private static ClientResponse asKey(AdminFixture f, String method, String path, String owner, String scopes,
            String json) {
        ClientRequest r = json == null ? ClientRequest.of(method, path) : ClientRequest.json(method, path, json);
        return f.adapter.send(r.header(AuthTestSupport.HEADER, owner + ":0:k1:" + scopes));
    }

    // SEC-IAM-005 / SEC-IAM-004 key-actor component (the opt-in for admin-scope keys arrives with the API key
    // endpoints): an API-key actor's scopes are the owner's narrowed by the key, and a key never covers * : with an
    // owner holding *, a key with auth-groups:* and * cannot delete a group whose member holds *, can delete an empty
    // *-conferring group, and a key with only auth-users:write cannot grant editor
    @Test
    public void keyActorScopesInTheGrantRule() throws Exception {
        AdminFixture f = fixture().start(b -> b.credentials(new AuthTestSupport.TestCredentials()));
        String owner = f.user("owner", "admin");
        String other = f.user("other", "admin");
        String bob = f.user("bob");
        f.groups.confer("admins", "admin");
        f.groups.addMember("admins", other);
        ClientResponse denied = asKey(f, "DELETE", "/auth/admin/groups/admins", owner, "auth-groups:*,*", null);
        assertEquals(denied.text(), 403, denied.status());
        assertEquals("insufficient_scope", AuthTestSupport.code(denied));
        assertTrue(f.groups.groupExists("admins"));
        f.groups.confer("supers", "admin");
        assertEquals(204, asKey(f, "DELETE", "/auth/admin/groups/supers", owner, "auth-groups:*,*", null).status());
        ClientResponse grant = asKey(f, "PATCH", "/auth/admin/users/" + bob, owner, "auth-users:write",
                "{\"roles\":[\"editor\"]}");
        assertEquals(403, grant.status());
        assertTrue(f.stored(bob).getRoles().isEmpty());
        // a key whose only scope is * passes no admin guard
        assertEquals(403, asKey(f, "GET", "/auth/admin/users", owner, "*", null).status());
    }
}
