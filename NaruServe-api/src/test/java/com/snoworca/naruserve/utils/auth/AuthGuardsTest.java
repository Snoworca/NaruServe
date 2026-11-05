package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.TestApp;
import com.snoworca.naruserve.utils.auth.group.GroupStore;
import com.snoworca.naruserve.utils.auth.rbac.RolePolicy;
import com.snoworca.naruserve.utils.auth.user.AuthUser;
import com.snoworca.naruserve.utils.auth.user.ForwardingUserStore;
import com.snoworca.naruserve.utils.auth.user.InMemoryUserStore;
import com.snoworca.naruserve.utils.auth.user.UserStore;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.Test;

/** The principal access API and the route guards (IR-AUTH-004), and the coverage predicate (FR-IAM-009). */
public class AuthGuardsTest {

    static final RolePolicy POLICY = RolePolicy.builder()
            .role("editor", "posts:read", "posts:write")
            .role("viewer", "posts:read")
            .role("writer-all", "posts:*")
            .role("reader", "users:read")
            .build();

    /** A store that counts findById calls and can be made to fail. */
    static final class CountingStore extends ForwardingUserStore {
        final AtomicInteger finds = new AtomicInteger();
        volatile boolean failing;

        CountingStore() {
            super(new InMemoryUserStore());
        }

        @Override
        public @Nullable AuthUser findById(String id) throws Exception {
            finds.incrementAndGet();
            if (failing) {
                throw new IllegalStateException("store down SECRET-XYZ");
            }
            return super.findById(id);
        }
    }

    static AuthUser user(UserStore store, String name, String... roles) {
        try {
            return store.create(AuthUser.builder().username(name).roles(roles).build());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    static String cred(AuthUser u) {
        return u.getId() + ":" + u.getTokenVersion();
    }

    // IR-AUTH-004 component (principal access): auth.user(req) returns the principal on a protected route; on a public
    // route it throws AuthStateException and auth.userOrNull(req) is null (the 500 mapping comes with auth.errors())
    @Test
    public void userOnProtectedAndPublicRoutes() {
        InMemoryUserStore store = new InMemoryUserStore();
        AuthUser alice = user(store, "alice", "editor");
        Auth auth = AuthTestSupport.builder().users(store).roles(POLICY).permit("/open").build();
        TestApp t = TestApp.create();
        auth.install(t.app());
        AtomicReference<Object> seen = new AtomicReference<>();
        t.app().get("/me", (req, res) -> res.send(auth.user(req).getUsername()));
        t.app().get("/open", (req, res) -> {
            seen.set(auth.userOrNull(req));
            try {
                auth.user(req);
                res.send("no exception");
            } catch (AuthStateException e) {
                assertTrue(e instanceof IllegalStateException);
                res.send("state");
            }
        });
        assertEquals("alice", t.send("GET", "/me", AuthTestSupport.HEADER, cred(alice)).text());
        assertEquals("state", t.send("GET", "/open").text());
        assertNull(seen.get());
    }

    // IR-AUTH-004 AC-2: loadUser never throws, promotes a minimal principal, returns a loaded one without a store call
    @Test
    public void IR_AUTH_004_AC2_loadUserPromotesAndNeverThrows() throws Exception {
        CountingStore store = new CountingStore();
        AuthUser alice = user(store, "alice", "editor");
        AuthUser off = store.create(AuthUser.builder().username("off").enabled(false).build());
        // userCacheTtl(0): every promotion reads the store, so the direct store changes below are seen at once
        Auth stateless = AuthTestSupport.builder().users(store).roles(POLICY).statelessAccess(true)
                .permit("/anon").userCacheTtl(0).build();
        TestApp t = TestApp.create();
        stateless.install(t.app());
        t.app().get("/me", (req, res) -> {
            AuthPrincipal minimal = stateless.user(req);
            AuthPrincipal loaded = stateless.loadUser(req);
            res.send(minimal.isLoaded() + " " + minimal.getUsername().isEmpty() + " "
                    + (loaded == null ? "null" : loaded.isLoaded() + " " + loaded.hasScope("posts:write") + " "
                    + loaded.getRoles()));
        });
        t.app().get("/anon", (req, res) -> res.send(String.valueOf(stateless.loadUser(req))));
        store.finds.set(0);
        assertEquals("false true true true [editor]", t.send("GET", "/me", AuthTestSupport.HEADER, cred(alice)).text());
        assertEquals(1, store.finds.get());
        assertEquals("null", t.send("GET", "/anon").text());
        assertEquals("false true null", t.send("GET", "/me", AuthTestSupport.HEADER, cred(off)).text());
        assertEquals("false true null", t.send("GET", "/me", AuthTestSupport.HEADER, alice.getId() + ":7").text());
        assertEquals("false true null", t.send("GET", "/me", AuthTestSupport.HEADER, "999:0").text());
        store.failing = true;
        assertEquals("false true null", t.send("GET", "/me", AuthTestSupport.HEADER, cred(alice)).text());
        store.failing = false;

        // loaded principal: no further store call
        Auth loading = AuthTestSupport.builder().users(store).roles(POLICY).build();
        TestApp t2 = TestApp.create();
        loading.install(t2.app());
        t2.app().get("/me", (req, res) -> {
            AuthPrincipal p = loading.user(req);
            int before = store.finds.get();
            AuthPrincipal again = loading.loadUser(req);
            res.send((again == p) + " " + (store.finds.get() - before));
        });
        assertEquals("true 0", t2.send("GET", "/me", AuthTestSupport.HEADER, cred(alice)).text());
    }

    // IR-AUTH-004 AC-2: loadUser logs the store error it swallows, and a principal a guard already promoted comes back
    // as that same principal with no further store call
    @Test
    public void IR_AUTH_004_AC2_storeErrorsAreLoggedAndAGuardPromotionIsKept() throws Exception {
        CountingStore store = new CountingStore();
        AuthUser alice = user(store, "alice", "editor");
        com.snoworca.naruserve.testing.RecordingLogger log = new com.snoworca.naruserve.testing.RecordingLogger();
        Auth auth = AuthTestSupport.builder(new AuthTestSupport.TestCredentials(), log).users(store).roles(POLICY)
                .statelessAccess(true).userCacheTtl(0).build();
        TestApp t = TestApp.create();
        auth.install(t.app());
        t.app().get("/open", (req, res) -> res.send(String.valueOf(auth.loadUser(req))));
        t.app().get("/guarded", auth.authenticated(), (req, res) -> {
            AuthPrincipal p = auth.user(req);
            int before = store.finds.get();
            AuthPrincipal again = auth.loadUser(req);
            res.send(p.isLoaded() + " " + (again == p) + " " + (store.finds.get() - before));
        });
        assertEquals("true true 0", t.send("GET", "/guarded", AuthTestSupport.HEADER, cred(alice)).text());
        store.failing = true;
        assertEquals("null", t.send("GET", "/open", AuthTestSupport.HEADER, cred(alice)).text());
        assertEquals(log.lines.toString(), 1, com.snoworca.naruserve.testing.RecordingLogger.countMatching(log.lines,
                "ERROR auth: loadUser could not read the user store.*"));
    }

    // IR-AUTH-004 AC-3: auth.require("posts:write") as a route middleware; 403 insufficient_scope; posts:* or * pass
    @Test
    public void IR_AUTH_004_AC3_requireScope() {
        InMemoryUserStore store = new InMemoryUserStore();
        AuthUser viewer = user(store, "vera", "viewer");
        AuthUser star = user(store, "sam", "writer-all");
        AuthUser admin = user(store, "root", RolePolicy.ADMIN);
        Auth auth = AuthTestSupport.builder().users(store).roles(POLICY).build();
        TestApp t = TestApp.create();
        auth.install(t.app());
        AtomicInteger handled = new AtomicInteger();
        t.app().post("/posts", auth.require("posts:write"), (req, res) -> {
            handled.incrementAndGet();
            res.status(201).send("created");
        });
        ClientResponse denied = t.send("POST", "/posts", AuthTestSupport.HEADER, cred(viewer));
        assertEquals(403, denied.status());
        assertEquals("insufficient_scope", AuthTestSupport.code(denied));
        assertEquals(0, handled.get());
        assertEquals(201, t.send("POST", "/posts", AuthTestSupport.HEADER, cred(star)).status());
        assertEquals(201, t.send("POST", "/posts", AuthTestSupport.HEADER, cred(admin)).status());
        assertEquals(2, handled.get());
        try {
            auth.require("posts:*");
            fail("a wildcard required scope was accepted");
        } catch (IllegalArgumentException expected) {
            // only grants may contain *
        }
    }

    // IR-AUTH-004 AC-4: requireRole passes role holders, otherwise 403; without a principal every guard answers 401
    @Test
    public void IR_AUTH_004_AC4_requireRoleAnd401WithoutPrincipal() {
        InMemoryUserStore store = new InMemoryUserStore();
        AuthUser editor = user(store, "ed", "editor");
        AuthUser viewer = user(store, "vi", "viewer");
        Auth auth = AuthTestSupport.builder().users(store).roles(POLICY).permitPrefix("/pub").build();
        TestApp t = TestApp.create();
        auth.install(t.app());
        t.app().get("/edit", auth.requireRole("editor"), (req, res) -> res.send("ok"));
        t.app().get("/pub/role", auth.requireRole("editor"), (req, res) -> res.send("ok"));
        t.app().get("/pub/scope", auth.require("posts:read"), (req, res) -> res.send("ok"));
        t.app().get("/pub/auth", auth.authenticated(), (req, res) -> res.send("ok"));
        t.app().get("/pub/check", auth.check((p, req) -> true), (req, res) -> res.send("ok"));
        assertEquals(200, t.send("GET", "/edit", AuthTestSupport.HEADER, cred(editor)).status());
        assertEquals(403, t.send("GET", "/edit", AuthTestSupport.HEADER, cred(viewer)).status());
        for (String path : Arrays.asList("/pub/role", "/pub/scope", "/pub/auth", "/pub/check")) {
            ClientResponse r = t.send("GET", path);
            assertEquals(path, 401, r.status());
            assertTrue(path, r.header("Content-Type").startsWith("application/json"));
        }
    }

    // IR-AUTH-004 AC-5: authenticated() promotes; 401 for a disabled user or another version; 503 when the store fails
    @Test
    public void IR_AUTH_004_AC5_authenticatedPromotes() throws Exception {
        CountingStore store = new CountingStore();
        AuthUser alice = user(store, "alice", "editor");
        // userCacheTtl(0): the direct store disable and the store failure below are seen by the next request
        Auth auth = AuthTestSupport.builder().users(store).roles(POLICY).statelessAccess(true).userCacheTtl(0).build();
        TestApp t = TestApp.create();
        auth.install(t.app());
        t.app().get("/x", auth.authenticated(), (req, res) -> res.send(auth.user(req).isLoaded() + " "
                + auth.user(req).getUsername()));
        assertEquals("true alice", t.send("GET", "/x", AuthTestSupport.HEADER, cred(alice)).text());
        assertEquals(401, t.send("GET", "/x", AuthTestSupport.HEADER, alice.getId() + ":3").status());
        store.setEnabled(alice.getId(), false);
        ClientResponse disabled = t.send("GET", "/x", AuthTestSupport.HEADER, cred(alice));
        assertEquals(401, disabled.status());
        assertTrue(disabled.header("Content-Type").startsWith("application/json"));
        store.failing = true;
        ClientResponse down = t.send("GET", "/x", AuthTestSupport.HEADER, cred(alice));
        assertEquals(503, down.status());
        assertEquals("SERVICE_UNAVAILABLE", AuthTestSupport.code(down));
        assertFalse(down.text(), down.text().contains("SECRET-XYZ"));
    }

    // IR-AUTH-004 AC-7: AuthPrincipal getters declare no checked exception; getApiKeyId is @Nullable (null for JWT)
    @Test
    public void IR_AUTH_004_AC7_principalGettersNeverThrow() throws Exception {
        for (String name : Arrays.asList("getId", "getUsername", "getRoles", "getScopes", "getAttributes", "isLoaded",
                "hasScope", "getApiKeyId")) {
            Method m = null;
            for (Method c : AuthPrincipal.class.getMethods()) {
                if (c.getName().equals(name)) {
                    m = c;
                }
            }
            assertNotNull(name, m);
            assertEquals(name, 0, m.getExceptionTypes().length);
            assertTrue(name, Modifier.isPublic(m.getModifiers()));
        }
        Method key = AuthPrincipal.class.getMethod("getApiKeyId");
        assertTrue(key.getAnnotatedReturnType().isAnnotationPresent(Nullable.class));
        AuthPrincipal minimal = AuthPrincipal.minimal("7", 0, null, null);
        assertNull(minimal.getApiKeyId());
        assertEquals("", minimal.getUsername());
        assertFalse(minimal.hasScope("not a scope"));
        assertFalse(minimal.hasScope("posts:*"));
    }

    // IR-AUTH-004 AC-8: AccessRule is a SAM with allows(AuthPrincipal, Request); auth.check(rule) guards with it
    @Test
    public void IR_AUTH_004_AC8_checkWithAccessRule() {
        assertTrue(AccessRule.class.isAnnotationPresent(FunctionalInterface.class));
        int abstractMethods = 0;
        for (Method m : AccessRule.class.getMethods()) {
            if (Modifier.isAbstract(m.getModifiers())) {
                abstractMethods++;
                assertEquals("allows", m.getName());
                assertEquals(Arrays.asList(AuthPrincipal.class, com.snoworca.naruserve.Request.class),
                        Arrays.asList(m.getParameterTypes()));
                assertEquals(boolean.class, m.getReturnType());
            }
        }
        assertEquals(1, abstractMethods);
        InMemoryUserStore store = new InMemoryUserStore();
        AuthUser alice = user(store, "alice", "viewer");
        Auth auth = AuthTestSupport.builder().users(store).roles(POLICY).permit("/pub").build();
        TestApp t = TestApp.create();
        auth.install(t.app());
        AtomicInteger handled = new AtomicInteger();
        t.app().get("/own/:id", auth.check((p, req) -> p.getId().equals(req.param("id"))), (req, res) -> {
            handled.incrementAndGet();
            res.send("mine");
        });
        t.app().get("/pub", auth.check((p, req) -> true), (req, res) -> res.send("x"));
        assertEquals(200, t.send("GET", "/own/" + alice.getId(), AuthTestSupport.HEADER, cred(alice)).status());
        ClientResponse other = t.send("GET", "/own/999", AuthTestSupport.HEADER, cred(alice));
        assertEquals(403, other.status());
        assertEquals(1, handled.get());
        assertEquals(401, t.send("GET", "/pub").status());
    }

    /** groupsOfUser(u) = {eng}, eng ⊂ company, and rolesOfGroups({eng, company}) = {viewer}. */
    static final class TwoGroups implements GroupStore {
        @Override
        public Set<String> groupsOfUser(String userId) {
            return Collections.singleton("eng");
        }

        @Override
        public Set<String> parentsOf(Set<String> groupIds) {
            return groupIds.contains("eng") ? Collections.singleton("company") : Collections.<String>emptySet();
        }

        @Override
        public Set<String> rolesOfGroups(Set<String> groupIds) {
            return groupIds.equals(new HashSet<>(Arrays.asList("eng", "company")))
                    ? Collections.singleton("viewer") : Collections.<String>emptySet();
        }
    }

    // FR-IAM-009 AC-1: effective roles = own roles ∪ roles of the expanded groups; scopes = union over those roles
    @Test
    public void FR_IAM_009_AC1_effectiveRolesAndScopes() {
        RolePolicy policy = RolePolicy.builder().role("editor", "posts:write").role("viewer", "posts:read").build();
        InMemoryUserStore store = new InMemoryUserStore();
        AuthUser u = user(store, "una", "editor");
        Auth auth = AuthTestSupport.builder().users(store).roles(policy).groups(new TwoGroups()).build();
        TestApp t = TestApp.create();
        auth.install(t.app());
        t.app().get("/p", (req, res) -> res.send(auth.user(req).getRoles() + " " + auth.user(req).getScopes()));
        assertEquals("[editor, viewer] [posts:write, posts:read]",
                t.send("GET", "/p", AuthTestSupport.HEADER, cred(u)).text());
    }

    private static int status(TestApp t, String path, String keyScopes, AuthUser owner) {
        return t.send("GET", path, AuthTestSupport.HEADER, owner.getId() + ":0:k1:" + keyScopes).status();
    }

    // FR-IAM-009 AC-3: key {posts:*}, owner {posts:read}: posts:read allowed, posts:write 403 insufficient_scope
    @Test
    public void FR_IAM_009_AC3_keyWildcardOwnerNarrow() {
        TestApp t = keyApp("viewer");
        assertEquals(200, status(t, "/read", "posts:*", owner));
        ClientResponse w = t.send("GET", "/write", AuthTestSupport.HEADER, owner.getId() + ":0:k1:posts:*");
        assertEquals(403, w.status());
        assertEquals("insufficient_scope", AuthTestSupport.code(w));
    }

    // FR-IAM-009 AC-4: key {posts:read} with owner {*}; key {*} with owner {posts:*}
    @Test
    public void FR_IAM_009_AC4_bothMustCover() {
        TestApp admin = keyApp(RolePolicy.ADMIN);
        assertEquals(200, status(admin, "/read", "posts:read", owner));
        assertEquals(403, status(admin, "/write", "posts:read", owner));
        TestApp star = keyApp("writer-all");
        assertEquals(200, status(star, "/write", "*", owner));
        assertEquals(403, status(star, "/users", "*", owner));
    }

    private AuthUser owner;

    private TestApp keyApp(String ownerRole) {
        InMemoryUserStore store = new InMemoryUserStore();
        owner = user(store, "bot", ownerRole);
        Auth auth = AuthTestSupport.builder().users(store).roles(POLICY).build();
        TestApp t = TestApp.create();
        auth.install(t.app());
        t.app().get("/read", auth.require("posts:read"), (req, res) -> res.send("r"));
        t.app().get("/write", auth.require("posts:write"), (req, res) -> res.send("w"));
        t.app().get("/users", auth.require("users:read"), (req, res) -> res.send("u"));
        return t;
    }

    // FR-IAM-009 AC-5: the predicate decides every API-key coverage; no code path intersects wildcard scope sets
    @Test
    public void FR_IAM_009_AC5_noWildcardSetIntersection() throws Exception {
        java.io.File root = new java.io.File("src/main/java/com/snoworca/naruserve/utils/auth");
        assertTrue(root.getPath(), root.isDirectory());
        java.util.List<String> hits = new java.util.ArrayList<>();
        scan(root, hits);
        assertEquals(Collections.emptyList(), hits);
        // the four cases above, through the principal itself
        Set<String> key = new LinkedHashSet<>(Collections.singleton("posts:*"));
        AuthPrincipal p = new AuthPrincipal("1", "bot", 0, Collections.singleton("viewer"),
                Collections.singleton("posts:read"), Collections.<String, String>emptyMap(), true, "k1", key);
        assertTrue(p.hasScope("posts:read"));
        assertFalse(p.hasScope("posts:write"));
        AuthPrincipal q = new AuthPrincipal("1", "bot", 0, Collections.singleton("admin"), Collections.singleton("*"),
                Collections.<String, String>emptyMap(), true, "k1", Collections.singleton("posts:read"));
        assertTrue(q.hasScope("posts:read"));
        assertFalse(q.hasScope("posts:write"));
        assertSame("k1", q.getApiKeyId());
    }

    private static void scan(java.io.File dir, java.util.List<String> hits) throws java.io.IOException {
        java.io.File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (java.io.File f : files) {
            if (f.isDirectory()) {
                scan(f, hits);
            } else if (f.getName().endsWith(".java")) {
                String src = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8);
                if (src.contains("retainAll(")) {
                    hits.add(f.getName() + " intersects a scope set with retainAll");
                }
            }
        }
    }

    // IR-AUTH-004 AC-6: on a RouteGroup, route(path).use(auth.require(...)) guards the routes registered through it
    @Test
    public void IR_AUTH_004_AC6_routeGroupUsesTheGuard() {
        InMemoryUserStore store = new InMemoryUserStore();
        AuthUser viewer = user(store, "vi", "viewer");
        AuthUser reader = user(store, "re", "reader");
        Auth auth = AuthTestSupport.builder().users(store).roles(POLICY).build();
        TestApp t = TestApp.create();
        auth.install(t.app());
        t.app().group("/api", g -> g.route("/posts").use(auth.require("posts:read")).get((req, res) -> res.send("[]")));
        assertEquals(200, t.send("GET", "/api/posts", AuthTestSupport.HEADER, cred(viewer)).status());
        ClientResponse denied = t.send("GET", "/api/posts", AuthTestSupport.HEADER, cred(reader));
        assertEquals(403, denied.status());
        assertEquals("insufficient_scope", AuthTestSupport.code(denied));
    }

    /** A store whose reads fail while {@code failing}. */
    static final class FlakyStore extends ForwardingUserStore {
        volatile boolean failing;

        FlakyStore() {
            super(new InMemoryUserStore());
        }

        @Override
        public @Nullable AuthUser findById(String id) throws Exception {
            if (failing) {
                throw new java.sql.SQLException("down");
            }
            return super.findById(id);
        }
    }

    // IR-IAM-001 AC-5: a store exception is 503 JSON on a protected path, leaves a public request anonymous with the
    // error logged at most once a minute, and makes loadUser return null and AuthUsers throw AuthUnavailableException
    @Test
    public void IR_IAM_001_AC5_storeExceptionsByPlace() throws Exception {
        FlakyStore store = new FlakyStore();
        AuthUser u = user(store, "u", "viewer");
        com.snoworca.naruserve.testing.RecordingLogger log = new com.snoworca.naruserve.testing.RecordingLogger();
        final long[] now = {1_000_000L};
        Auth auth = AuthTestSupport.builder(new AuthTestSupport.TestCredentials(), log).users(store).roles(POLICY)
                .timeSource(() -> now[0]).permit("/pub").build();
        TestApp t = TestApp.create();
        auth.install(t.app());
        t.app().get("/pub", (req, res) -> res.send(String.valueOf(auth.userOrNull(req))));
        t.app().get("/me", (req, res) -> res.send("me"));
        store.failing = true;
        ClientResponse protectedPath = t.send("GET", "/me", AuthTestSupport.HEADER, cred(u));
        assertEquals(503, protectedPath.status());
        assertEquals("SERVICE_UNAVAILABLE", AuthTestSupport.code(protectedPath));
        assertFalse(protectedPath.text(), protectedPath.text().contains("SQLException"));
        log.lines.clear();
        for (int i = 0; i < 3; i++) {
            ClientResponse pub = t.send("GET", "/pub", AuthTestSupport.HEADER, cred(u));
            assertEquals(200, pub.status());
            assertEquals("null", pub.text());
        }
        assertEquals(1, com.snoworca.naruserve.testing.RecordingLogger.countMatching(log.lines,
                ".*optional authentication on a public path failed.*"));
        now[0] += 61_000L;
        t.send("GET", "/pub", AuthTestSupport.HEADER, cred(u));
        assertEquals(2, com.snoworca.naruserve.testing.RecordingLogger.countMatching(log.lines,
                ".*optional authentication on a public path failed.*"));
        // in an app handler (the principal attached before the store failed): loadUser is null, AuthUsers throws
        Auth stateless = AuthTestSupport.builder().users(store).roles(POLICY).statelessAccess(true).build();
        TestApp s = TestApp.create();
        stateless.install(s.app());
        s.app().get("/app", (req, res) -> {
            String loaded = String.valueOf(stateless.loadUser(req));
            try {
                stateless.users().findById(u.getId());
                res.send(loaded + " no exception");
            } catch (AuthUnavailableException e) {
                res.send(loaded + " unavailable");
            }
        });
        assertEquals("null unavailable", s.send("GET", "/app", AuthTestSupport.HEADER, cred(u)).text());
    }
}
