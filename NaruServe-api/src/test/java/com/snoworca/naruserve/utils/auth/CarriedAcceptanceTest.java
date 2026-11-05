package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.testing.TestApp;
import com.snoworca.naruserve.utils.auth.group.GroupLimits;
import com.snoworca.naruserve.utils.auth.group.GroupStore;
import com.snoworca.naruserve.utils.auth.group.InMemoryGroupStore;
import com.snoworca.naruserve.utils.auth.rbac.RolePolicy;
import com.snoworca.naruserve.utils.auth.user.AuthUser;
import com.snoworca.naruserve.utils.auth.user.InMemoryUserStore;
import com.snoworca.naruserve.utils.auth.user.UserStore;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.Test;

/**
 * Acceptance criteria of earlier 0.11.0 requirements that waited for the Auth facade and its guards: they now run
 * through auth.require, auth.users() and the global middleware.
 */
public class CarriedAcceptanceTest {

    private static TestApp guarded(Auth auth, String... scopes) {
        TestApp t = TestApp.create();
        auth.install(t.app());
        for (String s : scopes) {
            t.app().get("/" + s.replace(':', '-'), auth.require(s), (req, res) -> res.send("ok"));
        }
        return t;
    }

    private static int get(TestApp t, String scope, AuthUser u) {
        return t.send("GET", "/" + scope.replace(':', '-'), AuthTestSupport.HEADER, u.getId() + ":0").status();
    }

    // FR-IAM-005 AC-3: a required scope with * is refused when the guard is declared
    @Test
    public void FR_IAM_005_AC3_wildcardRequiredScopeIsRefused() {
        Auth auth = AuthTestSupport.builder().build();
        for (String bad : Arrays.asList("posts:*", "*", "posts:re*d")) {
            try {
                auth.require(bad);
                fail("accepted " + bad);
            } catch (IllegalArgumentException expected) {
                // only grants may contain wildcards
            }
        }
        assertNotNull(auth.require("posts:read"));
    }

    // FR-IAM-006 AC-1: built-in admin passes posts:write and auth-users:write; editor passes posts:write only
    @Test
    public void FR_IAM_006_AC1_builtinAdminAndDeclaredEditor() {
        InMemoryUserStore store = new InMemoryUserStore();
        AuthUser admin = store.create(AuthUser.builder().username("root").roles("admin").build());
        AuthUser editor = store.create(AuthUser.builder().username("ed").roles("editor").build());
        TestApp t = guarded(AuthTestSupport.builder().users(store).roles(RolePolicy.builder()
                .role("editor", "posts:read", "posts:write").build()).build(),
                "posts:write", "auth-users:write", "auth-users:read");
        assertEquals(200, get(t, "posts:write", admin));
        assertEquals(200, get(t, "auth-users:write", admin));
        assertEquals(200, get(t, "posts:write", editor));
        assertEquals(403, get(t, "auth-users:read", editor));
    }

    // FR-IAM-006 AC-3: replaceBuiltinRoles() without an admin role leaves admin without scopes
    @Test
    public void FR_IAM_006_AC3_replacedBuiltinsLeaveAdminEmpty() {
        InMemoryUserStore store = new InMemoryUserStore();
        AuthUser admin = store.create(AuthUser.builder().username("root").roles("admin").build());
        TestApp t = guarded(AuthTestSupport.builder().users(store).roles(RolePolicy.builder().replaceBuiltinRoles()
                .role("editor", "posts:write").build()).build(), "posts:read");
        ClientResponse r = t.send("GET", "/posts-read", AuthTestSupport.HEADER, admin.getId() + ":0");
        assertEquals(403, r.status());
        assertEquals("insufficient_scope", AuthTestSupport.code(r));
    }

    // FR-IAM-006 AC-6: a throwing RoleStore fails the guarded request closed with 503 SERVICE_UNAVAILABLE
    @Test
    public void FR_IAM_006_AC6_throwingRoleStoreIs503() {
        InMemoryUserStore store = new InMemoryUserStore();
        AuthUser u = store.create(AuthUser.builder().username("u").roles("editor").build());
        TestApp t = guarded(AuthTestSupport.builder().users(store).roles(role -> {
            throw new java.sql.SQLException("roles table gone");
        }).build(), "posts:read");
        ClientResponse r = t.send("GET", "/posts-read", AuthTestSupport.HEADER, u.getId() + ":0");
        assertEquals(503, r.status());
        assertEquals("SERVICE_UNAVAILABLE", AuthTestSupport.code(r));
    }

    // DR-IAM-002 AC-3: memberships are keyed by user id, so a rename keeps groupsOfUser
    @Test
    public void DR_IAM_002_AC3_renameKeepsMemberships() throws Exception {
        InMemoryGroupStore groups = new InMemoryGroupStore();
        Auth auth = AuthTestSupport.builder().groups(groups).build();
        AuthUser alice = auth.users().create("alice", "pw-pw-pw-pw".toCharArray());
        groups.createGroup("eng");
        groups.addMember("eng", alice.getId());
        Set<String> before = groups.groupsOfUser(alice.getId());
        assertEquals("alicia", auth.users().rename(alice.getId(), "Alicia").getUsername());
        assertEquals(before, groups.groupsOfUser(alice.getId()));
    }

    /** A group store failing every read. */
    static final class BrokenGroups implements GroupStore {
        @Override
        public Set<String> groupsOfUser(String userId) throws Exception {
            throw new java.io.IOException("directory down");
        }

        @Override
        public Set<String> parentsOf(Set<String> groupIds) throws Exception {
            throw new java.io.IOException("directory down");
        }
    }

    // DR-IAM-002 AC-5: a throwing GroupStore makes the guarded request 503 JSON, never allowed
    @Test
    public void DR_IAM_002_AC5_throwingGroupStoreIs503() {
        InMemoryUserStore store = new InMemoryUserStore();
        AuthUser root = store.create(AuthUser.builder().username("root").roles("admin").build());
        AtomicInteger handled = new AtomicInteger();
        Auth auth = AuthTestSupport.builder().users(store).groups(new BrokenGroups()).build();
        TestApp t = TestApp.create();
        auth.install(t.app());
        t.app().get("/x", auth.require("posts:read"), (req, res) -> {
            handled.incrementAndGet();
            res.send("ok");
        });
        ClientResponse r = t.send("GET", "/x", AuthTestSupport.HEADER, root.getId() + ":0");
        assertEquals(503, r.status());
        assertTrue(r.header("Content-Type").startsWith("application/json"));
        assertEquals(0, handled.get());
    }

    /** {@code depth} parents above the direct group g0, or {@code width} direct groups. */
    static final class Shaped implements GroupStore {
        final int depth;
        final int width;

        Shaped(int depth, int width) {
            this.depth = depth;
            this.width = width;
        }

        @Override
        public Set<String> groupsOfUser(String userId) {
            Set<String> out = new LinkedHashSet<>();
            for (int i = 0; i < Math.max(1, width); i++) {
                out.add("w" + i);
            }
            if (width == 0) {
                return Collections.singleton("g0");
            }
            return out;
        }

        @Override
        public Set<String> parentsOf(Set<String> groupIds) {
            Set<String> out = new LinkedHashSet<>();
            for (String g : groupIds) {
                if (g.startsWith("g")) {
                    int level = Integer.parseInt(g.substring(1));
                    if (level < depth) {
                        out.add("g" + (level + 1));
                    }
                }
            }
            return out;
        }
    }

    private static ClientResponse guardedWithGroups(GroupStore groups, @Nullable GroupLimits limits) {
        InMemoryUserStore store = new InMemoryUserStore();
        AuthUser u = store.create(AuthUser.builder().username("u").roles("admin").build());
        Auth.Builder b = AuthTestSupport.builder().users(store).groups(groups);
        if (limits != null) {
            b.groupLimits(limits);
        }
        TestApp t = TestApp.create();
        Auth auth = b.build();
        auth.install(t.app());
        t.app().get("/x", auth.require("posts:read"), (req, res) -> res.send("ok"));
        return t.send("GET", "/x", AuthTestSupport.HEADER, u.getId() + ":0");
    }

    // FR-IAM-007 AC-3: a chain 9 levels above the direct group fails the guarded request with a JSON error
    @Test
    public void FR_IAM_007_AC3_tooDeepFailsTheRequest() {
        ClientResponse deep = guardedWithGroups(new Shaped(9, 0), null);
        assertEquals(503, deep.status());
        assertTrue(deep.header("Content-Type").startsWith("application/json"));
        assertEquals(200, guardedWithGroups(new Shaped(8, 0), null).status());
    }

    // FR-IAM-007 AC-4: 257 distinct groups fail the same way; 256 succeed
    @Test
    public void FR_IAM_007_AC4_tooManyGroupsFailTheRequest() {
        assertEquals(503, guardedWithGroups(new Shaped(0, 257), null).status());
        assertEquals(200, guardedWithGroups(new Shaped(0, 256), null).status());
    }

    // FR-IAM-007 AC-4: "the same way" as AC-3: the answer to 257 groups is a JSON error too
    @Test
    public void FR_IAM_007_AC4_tooManyGroupsAreAJsonError() {
        ClientResponse wide = guardedWithGroups(new Shaped(0, 257), null);
        assertEquals(503, wide.status());
        assertTrue(wide.header("Content-Type").startsWith("application/json"));
    }

    // FR-IAM-007 AC-5: defaults depth 8 and size 256; other limits go through the builder
    @Test
    public void FR_IAM_007_AC5_limitsThroughTheBuilder() {
        assertEquals(8, GroupLimits.defaults().getMaxDepth());
        assertEquals(256, GroupLimits.defaults().getMaxSize());
        assertEquals(503, guardedWithGroups(new Shaped(3, 0), GroupLimits.of(2, 256)).status());
        assertEquals(200, guardedWithGroups(new Shaped(0, 300), GroupLimits.of(8, 400)).status());
    }

    // SEC-AUTH-002 AC-2: DEV serves a plain non-local protected request with a valid credential; PROD answers 403
    @Test
    public void SEC_AUTH_002_AC2_devServesPlainRemoteRequests() {
        for (AuthMode mode : AuthMode.values()) {
            InMemoryUserStore store = new InMemoryUserStore();
            AuthUser u = store.create(AuthUser.builder().username("u").build());
            TestApp t = TestApp.create(new InMemoryHttpAdapter("203.0.113.9", false));
            Auth auth = AuthTestSupport.builder().users(store).mode(mode).build();
            auth.install(t.app());
            t.app().get("/x", (req, res) -> res.send("served"));
            ClientResponse r = t.send("GET", "/x", AuthTestSupport.HEADER, u.getId() + ":0");
            if (mode == AuthMode.DEV) {
                assertEquals(200, r.status());
            } else {
                assertEquals(403, r.status());
                assertEquals("insecure_transport", AuthTestSupport.code(r));
            }
        }
    }

    // SEC-AUTH-002 AC-5: in DEV the first non-local request logs one exposure WARN; later ones log none
    @Test
    public void SEC_AUTH_002_AC5_devExposureWarnsOnce() {
        RecordingLogger log = new RecordingLogger();
        TestApp local = TestApp.create();
        AuthTestSupport.builder(new AuthTestSupport.TestCredentials(), log).mode(AuthMode.DEV).permit("/x").build()
                .install(local.app());
        local.app().get("/x", (req, res) -> res.send("x"));
        local.send("GET", "/x");
        assertEquals(0, RecordingLogger.countMatching(log.lines, ".*DEV mode is serving.*"));
        RecordingLogger remoteLog = new RecordingLogger();
        TestApp remote = TestApp.create(new InMemoryHttpAdapter("203.0.113.9", false));
        AuthTestSupport.builder(new AuthTestSupport.TestCredentials(), remoteLog).mode(AuthMode.DEV).permit("/x")
                .build().install(remote.app());
        remote.app().get("/x", (req, res) -> res.send("x"));
        for (int i = 0; i < 3; i++) {
            remote.send("GET", "/x");
        }
        assertEquals(1, RecordingLogger.countMatching(remoteLog.lines, ".*WARN.*DEV mode is serving.*not local.*"));
    }

    /** Claims every update succeeded and writes nothing; counts the calls. */
    static final class DroppingStore implements UserStore {
        final AuthUser row = AuthUser.builder().id("1").username("u").passwordHash("h1").build();
        final AtomicInteger updates = new AtomicInteger();

        @Override
        public @Nullable AuthUser findByUsername(String username) {
            return null;
        }

        @Override
        public @Nullable AuthUser findById(String id) {
            return row;
        }

        @Override
        public AuthUser create(AuthUser user) {
            return user;
        }

        @Override
        public @Nullable AuthUser update(AuthUser user) {
            updates.incrementAndGet();
            return user;
        }

        @Override
        public boolean delete(String id) {
            return false;
        }
    }

    private interface Write {
        void run(DroppingStore s) throws Exception;
    }

    // IR-AUTH-003 AC-6: the default codes; the security field defaults throw security_write_conflict (setEnabled,
    // bumpTokenVersion and setRoles after 3 retries, changePassword after one try); the rehash is skipped silently
    // REL-IAM-002 AC-7 (store half): the default changePassword throws on an unconfirmed write, not returns false
    @Test
    public void IR_AUTH_003_AC6_unconfirmedSecurityWrites() throws Exception {
        assertEquals("SERVICE_UNAVAILABLE", new AuthUnavailableException("x").getCode());
        assertEquals("security_write_conflict", new AuthUnavailableException("security_write_conflict", "x", null)
                .getCode());
        Object[][] cases = {
            {(Write) s -> s.setEnabled("1", false), 4},
            {(Write) s -> s.bumpTokenVersion("1"), 4},
            {(Write) s -> s.setRoles("1", Collections.singleton("editor")), 4},
            {(Write) s -> s.changePassword("1", "h1", 0, "h2"), 1},
        };
        for (Object[] c : cases) {
            DroppingStore s = new DroppingStore();
            try {
                ((Write) c[0]).run(s);
                fail("an unconfirmed security write succeeded");
            } catch (AuthUnavailableException e) {
                assertEquals("security_write_conflict", e.getCode());
            }
            assertEquals(c[1], s.updates.get());
        }
        DroppingStore rehash = new DroppingStore();
        assertEquals(false, rehash.updatePasswordHash("1", "h1", "h2"));
        assertEquals(1, rehash.updates.get());
    }

    // FR-AUTH-007 AC-4: a throwing onUserCreated is logged; the user stays created and the response is the success
    @Test
    public void FR_AUTH_007_AC4_throwingOnUserCreatedKeepsTheUser() {
        RecordingLogger log = new RecordingLogger();
        InMemoryUserStore store = new InMemoryUserStore();
        Auth auth = AuthTestSupport.builder(new AuthTestSupport.TestCredentials(), log).users(store)
                .permit(com.snoworca.naruserve.Method.POST, "/signup").events(new AuthEvents() {
                    @Override
                    public void onUserCreated(AuthUser user, AuthEventContext ctx) {
                        throw new IllegalStateException("listener broke");
                    }
                }).build();
        TestApp t = TestApp.create();
        auth.install(t.app());
        t.app().post("/signup", (req, res) -> res.status(201).send(auth.users().create("neo", "pw-pw-pw-pw".toCharArray())
                .getId()));
        ClientResponse r = t.send("POST", "/signup");
        assertEquals(201, r.status());
        assertNotNull(store.findByUsername("neo"));
        assertEquals(1, RecordingLogger.countMatching(log.lines, ".*onUserCreated failed after the write.*"));
    }
}
