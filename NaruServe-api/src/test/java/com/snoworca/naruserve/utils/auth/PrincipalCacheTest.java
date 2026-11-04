package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.utils.auth.internal.Doctor;
import com.snoworca.naruserve.utils.auth.user.AuthUser;
import com.snoworca.naruserve.utils.auth.user.ForwardingUserStore;
import com.snoworca.naruserve.utils.auth.user.InMemoryUserStore;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/** The principal cache, its invalidation and the statelessAccess minimal principal (SEC-AUTH-007). */
public class PrincipalCacheTest {

    /** Counts findById. */
    private static final class Counting extends ForwardingUserStore {
        final AtomicInteger finds = new AtomicInteger();

        Counting(InMemoryUserStore d) {
            super(d);
        }

        @Override
        public AuthUser findById(String id) throws Exception {
            finds.incrementAndGet();
            return super.findById(id);
        }
    }

    // SEC-AUTH-007 AC-1: within userCacheTtl a second request of the same user does not call findById; after the TTL
    // it does (fixed TimeSource)
    @Test
    public void SEC_AUTH_007_AC1_cachedWithinTtl() throws Exception {
        final Counting[] store = new Counting[1];
        AdminFixture f = new AdminFixture(b -> store[0] = new Counting(b)).start();
        f.app.get("/api/me", (req, res) -> res.send(f.auth.user(req).getUsername()));
        String id = f.user("alice");
        String token = f.auth.accessToken(id, 0, 900_000L);
        store[0].finds.set(0);
        assertEquals("alice", get(f, token).text());
        assertEquals(1, store[0].finds.get());
        f.clock.addAndGet(4_999);
        assertEquals(200, get(f, token).status());
        assertEquals(1, store[0].finds.get());
        f.clock.addAndGet(1);
        assertEquals(200, get(f, token).status());
        assertEquals(2, store[0].finds.get());
    }

    private static ClientResponse get(AdminFixture f, String token) {
        return f.adapter.send(com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest.of("GET", "/api/me")
                .header("Authorization", "Bearer " + token));
    }

    // SEC-AUTH-007 AC-2: an admin disable through auth makes the next request 401 at once (default and on a guarded
    // route under statelessAccess); a role or group change through auth clears the cache; a direct store change is
    // seen within userCacheTtl
    @Test
    public void SEC_AUTH_007_AC2_invalidation() throws Exception {
        for (boolean stateless : new boolean[] {false, true}) {
            AdminFixture f = new AdminFixture().start(b -> b.statelessAccess(stateless));
            f.app.get("/api/w", f.auth.require("posts:write"), (req, res) -> res.send("w"));
            f.app.get("/api/g", f.auth.authenticated(), (req, res) -> res.send("g"));
            f.app.get("/api/d", f.auth.require("docs:read"), (req, res) -> res.send("d"));
            String root = f.user("root", "admin");
            String bob = f.user("bob", "reader");
            String tb = f.token(bob);
            assertEquals(200, f.call("GET", "/api/g", bob, null).status());
            assertEquals(403, f.call("GET", "/api/w", bob, null).status());
            // a role change through auth: seen by the next request
            assertEquals(200, f.call("PATCH", "/auth/admin/users/" + bob, root, "{\"roles\":[\"editor\"]}").status());
            assertEquals(200, f.adapter.send(req("/api/w", tb)).status());
            // a group change through auth clears the whole cache: bob gains docs:read through the group at once, and
            // carol, whose row changed straight in the store while cached, is reloaded too (no clock advance)
            String carol = f.user("carol", "reader");
            String tc = f.token(carol);
            assertEquals(403, f.adapter.send(req("/api/w", tc)).status()); // carol cached without posts:write
            f.base.setRoles(carol, AdminFixture.set("editor"));
            assertEquals(403, f.adapter.send(req("/api/w", tc)).status()); // still the cached carol
            assertEquals(403, f.adapter.send(req("/api/d", tb)).status());
            f.groups.confer("eng", "docs");
            assertEquals(204, f.call("PUT", "/auth/admin/groups/eng/members/" + bob, root, null).status());
            assertEquals(200, f.adapter.send(req("/api/d", tb)).status());
            assertEquals(200, f.adapter.send(req("/api/w", tc)).status());
            assertEquals(200, f.adapter.send(req("/api/w", tb)).status()); // reloaded after the clear, now cached
            // a direct store change is visible after the TTL
            f.base.setRoles(bob, AdminFixture.set());
            assertEquals(200, f.adapter.send(req("/api/w", tb)).status());
            f.clock.addAndGet(5_000);
            assertEquals(403, f.adapter.send(req("/api/w", tb)).status());
            // a disable through auth: 401 at once
            assertEquals(200, f.call("PATCH", "/auth/admin/users/" + bob, root, "{\"enabled\":false}").status());
            ClientResponse r = f.adapter.send(req("/api/g", tb));
            assertEquals(401, r.status());
        }
    }

    private static com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest req(String path, String token) {
        return com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest.of("GET", path).header("Authorization",
                "Bearer " + token);
    }

    // SEC-AUTH-007 AC-3: a token whose ver is lower than the store's is 401 (default, and a guarded route under
    // statelessAccess)
    @Test
    public void SEC_AUTH_007_AC3_staleVersionIs401() throws Exception {
        for (boolean stateless : new boolean[] {false, true}) {
            AdminFixture f = new AdminFixture().start(b -> b.statelessAccess(stateless));
            f.app.get("/api/g", f.auth.authenticated(), (req, res) -> res.send("g"));
            String bob = f.user("bob", "reader");
            String old = f.token(bob);
            f.base.bumpTokenVersion(bob);
            ClientResponse r = f.adapter.send(req("/api/g", old));
            assertEquals(401, r.status());
            assertEquals("invalid_token", AuthTestSupport.code(r));
            assertEquals(200, f.adapter.send(req("/api/g", f.token(bob))).status());
        }
    }

    // SEC-AUTH-007 AC-4: statelessAccess, a route without a guard: the minimal principal has the token sub, an empty
    // name, empty roles, scopes and attributes, isLoaded false, hasScope false, and no store lookup happens
    @Test
    public void SEC_AUTH_007_AC4_minimalPrincipal() throws Exception {
        final Counting[] store = new Counting[1];
        AdminFixture f = new AdminFixture(b -> store[0] = new Counting(b)).start(b -> b.statelessAccess(true));
        f.app.get("/api/me", (req, res) -> {
            AuthPrincipal p = f.auth.user(req);
            res.send(p.getId() + "|" + p.getUsername() + "|" + p.getRoles() + p.getScopes() + p.getAttributes() + "|"
                    + p.isLoaded() + "|" + p.hasScope("posts:read"));
        });
        String id = f.user("alice", "admin");
        store[0].finds.set(0);
        assertEquals(id + "||[][]{}|false|false", get(f, f.auth.accessToken(id, 0, 900_000L)).text());
        assertEquals(0, store[0].finds.get());
    }

    // SEC-AUTH-007 AC-5: statelessAccess, auth.require("posts:write") promotes through the cache, checks ver and
    // enabled and decides on the loaded scopes; doctor emits one stateless WARN about guard-less routes and getId()
    @Test
    public void SEC_AUTH_007_AC5_guardPromotesAndDoctorWarns() throws Exception {
        AdminFixture f = new AdminFixture().start(b -> b.statelessAccess(true));
        f.app.get("/api/w", f.auth.require("posts:write"), (req, res) -> res.send(f.auth.user(req).isLoaded() + ""));
        String ed = f.user("ed", "editor");
        String rd = f.user("rd", "reader");
        assertEquals("true", f.adapter.send(req("/api/w", f.token(ed))).text());
        assertEquals(403, f.adapter.send(req("/api/w", f.token(rd))).status());
        String old = f.token(ed);
        f.base.setEnabled(ed, false);
        f.auth.userChanged(ed);
        assertEquals(401, f.adapter.send(req("/api/w", old)).status());
        Doctor d = f.auth.doctor(f.app);
        int stateless = 0;
        for (Doctor.Finding x : d.findings()) {
            if (x.getItem().equals("stateless access")) {
                stateless++;
                assertEquals(Doctor.Level.WARN, x.getLevel());
                assertTrue(x.getMessage().contains("access token lifetime"));
                assertTrue(x.getMessage().contains("getId()"));
            }
        }
        assertEquals(1, stateless);
        Auth plain = Auth.builder(AuthTestSupport.SECRET).hashIterations(1000).logger(new RecordingLogger()).build();
        for (Doctor.Finding x : plain.doctor(f.app).findings()) {
            assertTrue(!x.getItem().equals("stateless access"));
        }
    }
}
