package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.testing.TestApp;
import com.snoworca.naruserve.utils.auth.audit.AuditEvent;
import com.snoworca.naruserve.utils.auth.group.GroupLimits;
import com.snoworca.naruserve.utils.auth.group.InMemoryGroupStore;
import com.snoworca.naruserve.utils.auth.internal.AuditTrail;
import com.snoworca.naruserve.utils.auth.internal.AuthCookies;
import com.snoworca.naruserve.utils.auth.internal.EffectiveGrants;
import com.snoworca.naruserve.utils.auth.internal.GrantRule;
import com.snoworca.naruserve.utils.auth.internal.LoginLimits;
import com.snoworca.naruserve.utils.auth.internal.ThrottleKeys;
import com.snoworca.naruserve.utils.auth.rbac.RolePolicy;
import com.snoworca.naruserve.utils.auth.user.AuthUser;
import com.snoworca.naruserve.utils.auth.user.InMemoryUserStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.After;
import org.junit.Test;

/**
 * Component tests of the wave-5 building blocks that the endpoint requirements of later waves wire in: the grant
 * rule (SEC-IAM-004), the login throttle keys and limits (SEC-FLOW-011) and the auth cookies (SEC-FLOW-009). Their
 * acceptance criteria are judged through the endpoints and stay open until those exist.
 */
public class Wave5ComponentsTest {

    /** The apps this test made; each is shut down after the test (its adapter pool and the auth cleanup scheduler). */
    private final List<TestApp> apps = new ArrayList<>();

    private TestApp app(InMemoryHttpAdapter adapter) {
        TestApp t = TestApp.create(adapter);
        apps.add(t);
        return t;
    }

    @After
    public void stopApps() {
        for (TestApp t : apps) {
            t.app().shutdown(5, TimeUnit.SECONDS);
        }
        apps.clear();
    }

    // -------------------------------------------------------------------------------------------- grant rule

    private final InMemoryUserStore users = new InMemoryUserStore();
    private final InMemoryGroupStore groups = new InMemoryGroupStore();
    private final List<AuditEvent> audited = new CopyOnWriteArrayList<>();

    private GrantRule rule() {
        RolePolicy roles = RolePolicy.builder().role("usersadmin", "auth-users:write").role("editor", "posts:write")
                .role("reader", "posts:read").role("poster", "posts:*").build();
        EffectiveGrants grants = new EffectiveGrants(roles, groups, GroupLimits.defaults(), new RecordingLogger());
        return new GrantRule(users, grants, new AuditTrail(audited::add, () -> 5L, new RecordingLogger()));
    }

    private String user(String name, String... roles) {
        return users.create(AuthUser.builder().username(name).passwordHash("h").roles(roles).build()).getId();
    }

    private static AuthRejection refused(GrantRule r, String actor, String target, GrantRule.Action a,
            String... gained) throws Exception {
        try {
            r.check(null, actor, target, a, new LinkedHashSet<>(java.util.Arrays.asList(gained)));
        } catch (AuthRejection e) {
            return e;
        }
        fail("allowed");
        return null;
    }

    @Test
    public void grantRuleCoversGrantsTargetsAndSelf() throws Exception {
        GrantRule r = rule();
        String admin = user("admin", "admin");
        String delegated = user("delegate", "usersadmin", "reader");
        String bob = user("bob", "reader");
        r.check(null, admin, bob, GrantRule.Action.ADD_ROLES, Collections.singleton("posts:write"));
        AuthRejection grant = refused(r, delegated, bob, GrantRule.Action.ADD_ROLES, "posts:write");
        assertEquals(403, grant.getStatus());
        assertEquals("insufficient_scope", grant.getCode());
        AuthRejection target = refused(r, delegated, admin, GrantRule.Action.DISABLE);
        assertEquals("insufficient_scope", target.getCode());
        refused(r, delegated, admin, GrantRule.Action.PASSWORD_RESET);
        r.check(null, delegated, bob, GrantRule.Action.MODIFY, Collections.<String>emptySet());
        audited.clear();
        refused(r, admin, admin, GrantRule.Action.REMOVE_ROLES);
        assertEquals(1, audited.size());
        assertEquals(AuditEvent.SELF_CHANGE_DENIED, audited.get(0).getType());
        assertEquals(admin, audited.get(0).getActorId());
        assertEquals(admin, audited.get(0).getTargetId());
        // the actor is re-read from the store: a role removed there no longer grants
        users.setRoles(delegated, new LinkedHashSet<>(java.util.Arrays.asList("usersadmin", "poster")));
        r.check(null, delegated, bob, GrantRule.Action.ADD_ROLES, Collections.singleton("posts:write"));
        users.setRoles(delegated, Collections.singleton("usersadmin"));
        refused(r, delegated, bob, GrantRule.Action.ADD_ROLES, "posts:write");
        // a disabled admin stays protected: its scopes still count for target protection
        users.setEnabled(admin, false);
        refused(r, delegated, admin, GrantRule.Action.PASSWORD_RESET);
        refused(r, delegated, admin, GrantRule.Action.DELETE);
        // a group conferring a wildcard needs that wildcard
        assertFalse(GrantRule.covers(Collections.singleton("posts:write"), "posts:*"));
        assertTrue(GrantRule.covers(Collections.singleton("posts:*"), "posts:write"));
        assertFalse(GrantRule.covers(Collections.singleton("posts:*"), "*"));
        assertTrue(GrantRule.covers(Collections.singleton("*"), "*"));
    }

    // -------------------------------------------------------------------------------------------- login limits

    @Test
    public void throttleKeysGroupIpv6By64() {
        assertEquals(ThrottleKeys.loginIp("2001:db8::1"), ThrottleKeys.loginIp("2001:db8::ffff"));
        assertEquals(ThrottleKeys.loginIp("2001:db8:0:0:1::1"), ThrottleKeys.loginIp("[2001:DB8::abcd]"));
        assertFalse(ThrottleKeys.loginIp("2001:db8::1").equals(ThrottleKeys.loginIp("2001:db8:0:1::1")));
        assertEquals(ThrottleKeys.loginIp("203.0.113.5"), ThrottleKeys.loginIp("::ffff:203.0.113.5"));
        assertFalse(ThrottleKeys.loginUser("alice").equals(ThrottleKeys.passwordConfirm("alice")));
        assertEquals("not-an-ip", ThrottleKeys.ipKey("not-an-ip"));
    }

    @Test
    public void loginLimitsApplyTheThreeKeys() throws Exception {
        AtomicLong clock = new AtomicLong(1_000L);
        LoginLimits limits = new LoginLimits(InMemoryLoginThrottle.builder().timeSource(clock::get).build());
        for (int i = 0; i < 5; i++) {
            assertEquals(0, limits.retryAfter("alice", "203.0.113.1", false));
            limits.recordFailure("alice", "203.0.113.1", false);
        }
        assertTrue(limits.retryAfter("alice", "203.0.113.1", false) > 0);
        assertTrue(limits.retryAfter("alice", "203.0.113.1", true) > 0);
        assertEquals(0, limits.retryAfter("alice", "203.0.113.2", false));
        // the IP key: 20 failures over many names; a trusted device is exempt
        for (int i = 0; i < 20; i++) {
            limits.recordFailure("name" + (i / 4), "198.51.100.7", false);
        }
        assertTrue(limits.retryAfter("fresh", "198.51.100.7", false) > 0);
        assertEquals(0, limits.retryAfter("fresh", "198.51.100.7", true));
        // the user-only key: 10 failures from 10 addresses; a trusted device is exempt
        for (int i = 0; i < 10; i++) {
            limits.recordFailure("admin", "192.0.2." + i, false);
        }
        assertTrue(limits.retryAfter("admin", "192.0.2.99", false) > 0);
        assertEquals(0, limits.retryAfter("admin", "192.0.2.99", true));
        clock.addAndGet(LoginLimits.WINDOW_MILLIS);
        assertEquals(0, limits.retryAfter("admin", "192.0.2.99", false));
        LoginLimits three = new LoginLimits(new InMemoryLoginThrottle(), 3, 20, 10, LoginLimits.WINDOW_MILLIS);
        for (int i = 0; i < 3; i++) {
            three.recordFailure("bob", "203.0.113.9", false);
        }
        assertTrue(three.retryAfter("bob", "203.0.113.9", true) > 0);
    }

    // -------------------------------------------------------------------------------------------- cookies

    @Test
    public void authCookiesCarryTheirAttributes() {
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter("203.0.113.9", true);
        TestApp t = app(adapter);
        final AuthCookies cookies = new AuthCookies("/api/auth", o -> o.domain("example.com"));
        t.app().get("/set", (req, res) -> {
            cookies.setAccess(req, res, "AT", 900);
            cookies.setRefresh(req, res, "RT", 1_209_600);
            cookies.setCsrf(req, res, "CS", 1_209_600);
            res.send("set");
        });
        t.app().get("/clear", (req, res) -> {
            cookies.clearSession(req, res);
            res.send("cleared");
        });
        t.app().get("/rt", (req, res) -> res.send(String.valueOf(AuthCookies.refreshToken(req))));
        t.start();
        List<String> set = setCookies(adapter.send(ClientRequest.of("GET", "/set")));
        assertEquals(3, set.size());
        String at = set.get(0);
        assertTrue(at, at.startsWith("naru_at=AT") && at.contains("Path=/") && !at.contains("Path=/api"));
        for (String c : set) {
            assertTrue(c, c.contains("SameSite=Strict") && c.contains("Secure") && c.contains("Domain=example.com"));
        }
        assertTrue(at, at.contains("HttpOnly") && at.contains("Max-Age=900"));
        assertTrue(set.get(1), set.get(1).contains("Path=/api/auth") && set.get(1).contains("HttpOnly"));
        assertFalse(set.get(2), set.get(2).contains("HttpOnly"));
        List<String> cleared = setCookies(adapter.send(ClientRequest.of("GET", "/clear")));
        assertEquals(3, cleared.size());
        assertTrue(cleared.get(1), cleared.get(1).contains("Path=/api/auth") && cleared.get(1).contains("Max-Age=0")
                && cleared.get(1).contains("Domain=example.com"));
        assertEquals("RT", adapter.send(ClientRequest.of("GET", "/rt").header("Cookie", "naru_rt=RT")).text());
        assertEquals("null", adapter.send(ClientRequest.of("GET", "/rt")
                .header("Cookie", "naru_rt=one; naru_rt=two")).text());
    }

    private static List<String> setCookies(ClientResponse r) {
        List<String> out = new ArrayList<>();
        for (java.util.Map.Entry<String, String> h : r.headers()) {
            if (h.getKey().equalsIgnoreCase("Set-Cookie")) {
                out.add(h.getValue());
            }
        }
        return out;
    }

    // SEC-FLOW-009 AC-2: COOKIE or BOTH with mountPath("/") makes build() throw AuthConfigException
    @Test
    public void SEC_FLOW_009_AC2_rootMountRefusedInCookieModes() {
        for (TokenTransport mode : new TokenTransport[] {TokenTransport.COOKIE, TokenTransport.BOTH}) {
            try {
                Auth.builder(AuthTestSupport.SECRET).hashIterations(1000).transport(mode).mountPath("/").build();
                fail(mode + " accepted mountPath(\"/\")");
            } catch (AuthConfigException e) {
                assertTrue(e.getMessage(), e.getMessage().contains(".mountPath(\"/\")"));
            }
        }
        Auth.builder(AuthTestSupport.SECRET).hashIterations(1000).mountPath("/").build();
    }

    // SEC-FLOW-009 AC-6: COOKIE or BOTH, a naru_rt cookie sent to GET /auth/me or a protected path is neither read
    // nor counted as a credential
    @Test
    public void SEC_FLOW_009_AC6_refreshCookieIsNoCredential() throws Exception {
        for (TokenTransport mode : new TokenTransport[] {TokenTransport.COOKIE, TokenTransport.BOTH}) {
            InMemoryHttpAdapter adapter = new InMemoryHttpAdapter("203.0.113.9", true);
            TestApp t = app(adapter);
            InMemoryUserStore s = new InMemoryUserStore();
            String id = s.create(AuthUser.builder().username("alice").passwordHash("h").build()).getId();
            final Auth auth = Auth.builder(AuthTestSupport.SECRET).hashIterations(1000).logger(new RecordingLogger())
                    .transport(mode).users(s).build();
            auth.routes().get("/me", (req, res) -> res.send("me:" + auth.user(req).getId()));
            auth.install(t.app());
            t.app().get("/api/x", (req, res) -> res.send("x:" + auth.user(req).getId()));
            t.start();
            String token = auth.accessToken(id, 0, 900_000L);
            for (String path : new String[] {"/auth/me", "/api/x"}) {
                ClientResponse onlyRt = adapter.send(ClientRequest.of("GET", path).header("Cookie", "naru_rt=" + token));
                assertEquals(mode + " " + path, 401, onlyRt.status());
                assertEquals("unauthenticated", AuthTestSupport.code(onlyRt));
                ClientResponse both = adapter.send(ClientRequest.of("GET", path)
                        .header("Cookie", "naru_at=" + token + "; naru_rt=garbage"));
                assertEquals(mode + " " + path, 200, both.status());
            }
        }
    }
}
