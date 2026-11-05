package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.AsyncRouteHandler;
import com.snoworca.naruserve.Registration;
import com.snoworca.naruserve.RegistrationKind;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.utils.auth.audit.AuditEvent;
import com.snoworca.naruserve.utils.auth.password.PasswordHasher;
import com.snoworca.naruserve.utils.auth.password.Pbkdf2PasswordHasher;
import com.snoworca.naruserve.utils.auth.user.AuthUser;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Test;

/**
 * POST /auth/register, POST /auth/setup with recovery, the new-password policy on every set-password path and
 * auth.users().checkPassword.
 */
public class Wave8AccountTest {

    private final W8 f = new W8();

    @After
    public void stop() {
        f.stop();
    }

    /** Records the user events with their origin, optionally refusing creates. */
    static final class Events implements AuthEvents {
        final List<String> calls = new CopyOnWriteArrayList<>();
        volatile AuthRejection refuseCreate;

        @Override
        public void beforeUserCreate(AuthUser candidate, AuthEventContext ctx) {
            calls.add("beforeUserCreate:" + ctx.getOrigin());
            AuthRejection r = refuseCreate;
            if (r != null) {
                throw r;
            }
        }

        @Override
        public void onUserCreated(AuthUser user, AuthEventContext ctx) {
            calls.add("onUserCreated:" + ctx.getOrigin());
        }

        @Override
        public void onUserUpdated(AuthUser before, AuthUser after, java.util.Set<UserChange> changes,
                AuthEventContext ctx) {
            calls.add("onUserUpdated:" + ctx.getOrigin());
        }
    }

    private static String register(String name, String pw) {
        return W8.login(name, pw);
    }

    // ---------------------------------------------------------------------------------------------- FR-FLOW-007

    // FR-FLOW-007 AC-1: default configuration, POST /auth/register with a valid body creates no user, no 201
    // FR-FLOW-007 AC-5: the builder exposes register(boolean) and no signup method; no /auth/signup route exists
    @Test
    public void FR_FLOW_007_AC1_AC5_registerIsOffByDefault() throws Exception {
        f.start(b -> b);
        ClientResponse r = f.post("/auth/register", register("neo", W8.PW));
        assertFalse(r.text(), r.status() == 201);
        assertNull(f.store.findByUsername("neo"));
        Method m = Auth.Builder.class.getMethod("register", boolean.class);
        assertEquals(Auth.Builder.class, m.getReturnType());
        for (Method x : Auth.Builder.class.getMethods()) {
            assertFalse(x.getName(), x.getName().toLowerCase(java.util.Locale.ROOT).contains("signup"));
        }
        for (Registration reg : f.app.registrations()) {
            assertFalse(String.valueOf(reg.getPath()), String.valueOf(reg.getPath()).contains("signup"));
        }
        f.stop();
        W8 on = new W8();
        try {
            on.start(b -> b.register(true));
            for (Registration reg : on.app.registrations()) {
                assertFalse(String.valueOf(reg.getPath()), String.valueOf(reg.getPath()).contains("signup"));
            }
        } finally {
            on.stop();
        }
    }

    // FR-FLOW-007 AC-2: with .register(true) a new username -> 201 with tokens per the delivery rule; beforeUserCreate
    // fires once with origin REGISTER, followed by onUserCreated
    // FR-FLOW-007 AC-7: the route is registered with methodAsync and the hash uses the shared hash slot
    @Test
    public void FR_FLOW_007_AC2_AC7_registerCreatesAndIssues() throws Exception {
        Events events = new Events();
        f.start(b -> b.register(true).events(events));
        long slots = f.auth.hashSlots().requested();
        ClientResponse r = f.post("/auth/register", register("Neo", W8.PW));
        assertEquals(r.text(), 201, r.status());
        String at = (String) W8.object(r).get("accessToken");
        assertNotNull(W8.object(r).get("refreshToken"));
        AuthUser neo = f.store.findByUsername("neo");
        assertNotNull(neo);
        assertEquals(neo.getId(), f.auth.tokens().verifyAccess(at).getSubject());
        assertTrue(new Pbkdf2PasswordHasher(1000).verify(W8.PW.toCharArray(), neo.getPasswordHash()));
        assertEquals(Arrays.asList("beforeUserCreate:REGISTER", "onUserCreated:REGISTER"), events.calls);
        assertEquals("one hash in the shared slot pool", slots + 1, f.auth.hashSlots().requested());
        boolean async = false;
        for (Registration reg : f.app.registrations()) {
            if (reg.getKind() == RegistrationKind.ROUTE && "/auth/register".equals(reg.getPath())) {
                async = reg.getTarget() instanceof AsyncRouteHandler;
            }
        }
        assertTrue(async);
    }

    // FR-FLOW-007 AC-3: an existing username -> 409 JSON error
    // FR-FLOW-007 AC-4: beforeUserCreate refusing with 422 domain_not_allowed -> 422 and no user created
    @Test
    public void FR_FLOW_007_AC3_AC4_duplicateAndRefusal() throws Exception {
        f.user("alice", W8.PW);
        Events events = new Events();
        f.start(b -> b.register(true).events(events));
        ClientResponse dup = f.post("/auth/register", register("ALICE", W8.PW));
        assertEquals(409, dup.status());
        assertEquals("duplicate_user", W8.code(dup));
        events.refuseCreate = new AuthRejection(422, "domain_not_allowed", "not this domain");
        ClientResponse refused = f.post("/auth/register", register("mallory", W8.PW));
        assertEquals(422, refused.status());
        assertEquals("domain_not_allowed", W8.code(refused));
        assertNull(f.store.findByUsername("mallory"));
    }

    // FR-FLOW-007 AC-6: COOKIE, register checks only the Origin (no X-CSRF-Token needed); a cross-origin Origin ->
    // 403 csrf_failed
    @Test
    public void FR_FLOW_007_AC6_cookieModeChecksTheOriginOnly() throws Exception {
        f.start(b -> b.register(true).transport(TokenTransport.COOKIE));
        ClientResponse ok = f.post("/auth/register", register("neo", W8.PW), "Host", W8.HOST, "Origin", W8.SAME);
        assertEquals(ok.text(), 201, ok.status());
        assertNotNull(W8.cookieValue(ok, "naru_at"));
        ClientResponse evil = f.post("/auth/register", register("trinity", W8.PW), "Host", W8.HOST, "Origin", W8.EVIL);
        assertEquals(403, evil.status());
        assertEquals("csrf_failed", W8.code(evil));
        assertTrue("SEC-FLOW-007 AC-7: no Set-Cookie without the Origin check", W8.setCookies(evil).isEmpty());
        assertNull(f.store.findByUsername("trinity"));
    }

    // FR-FLOW-002 AC-6: BOTH, Origin: null, POST /auth/login -> 200 body delivery without Set-Cookie; POST
    // /auth/register with .register(true) -> 201 body delivery without Set-Cookie
    @Test
    public void FR_FLOW_002_AC6_nullOriginIsBodyDelivery() throws Exception {
        f.user("alice", W8.PW);
        f.start(b -> b.register(true).transport(TokenTransport.BOTH));
        ClientResponse login = f.post("/auth/login", W8.login("alice", W8.PW), "Host", W8.HOST, "Origin", "null");
        assertEquals(200, login.status());
        assertNotNull(W8.object(login).get("accessToken"));
        assertTrue(W8.setCookies(login).isEmpty());
        ClientResponse reg = f.post("/auth/register", register("neo", W8.PW), "Host", W8.HOST, "Origin", "null");
        assertEquals(201, reg.status());
        assertNotNull(W8.object(reg).get("accessToken"));
        assertTrue(W8.setCookies(reg).isEmpty());
    }

    // ---------------------------------------------------------------------------------------------- SEC-AUTH-003

    private static String setup(String token, String name, String pw) {
        return "{\"token\":\"" + token + "\",\"username\":\"" + name + "\",\"password\":\"" + pw + "\"}";
    }

    private W8 adminSetup(String peer, boolean secure) {
        W8 w = new W8();
        w.start(peer, secure, b -> b.recovery(true).user("admin", null, "admin"));
        return w;
    }

    // SEC-AUTH-003 AC-1: setup open, a local request with the logged token creates the user; a non-local secure
    // request with the correct token creates nothing (403 setup_not_local); a plain-text non-local one is 403
    // insecure_transport
    @Test
    public void SEC_AUTH_003_AC1_localWithTheTokenCreatesTheAdmin() throws Exception {
        W8 remote = adminSetup("10.0.0.5", true);
        W8 plain = adminSetup("10.0.0.5", false);
        try {
            ClientResponse r = remote.post("/auth/setup", setup(remote.auth.pendingSetupToken(), "admin", W8.PW));
            assertEquals(403, r.status());
            assertEquals("setup_not_local", W8.code(r));
            ClientResponse p = plain.post("/auth/setup", setup(plain.auth.pendingSetupToken(), "admin", W8.PW));
            assertEquals(403, p.status());
            assertEquals("insecure_transport", W8.code(p));
            assertNull(remote.store.findByUsername("admin"));
            assertNull(plain.store.findByUsername("admin"));
        } finally {
            remote.stop();
            plain.stop();
        }
        f.start("127.0.0.1", false, b -> b.recovery(true).user("admin", null, "admin"));
        String token = f.auth.pendingSetupToken();
        assertNotNull(token);
        ClientResponse ok = f.post("/auth/setup", setup(token, "admin", W8.PW));
        assertEquals(ok.text(), 201, ok.status());
        AuthUser admin = f.store.findByUsername("admin");
        assertEquals(java.util.Collections.singleton("admin"), admin.getRoles());
        assertNotNull(W8.object(ok).get("accessToken"));
    }

    // SEC-AUTH-003 AC-2: a body without a token or with a non-string token -> 400 BAD_REQUEST; a wrong string token
    // -> 401 invalid_setup_token; nothing created; after one success a second POST -> 404
    @Test
    public void SEC_AUTH_003_AC2_tokenChecksAndClosing() throws Exception {
        f.start("127.0.0.1", false, b -> b.recovery(true).user("admin", null, "admin"));
        String token = f.auth.pendingSetupToken();
        for (String body : new String[] {"{\"username\":\"admin\",\"password\":\"" + W8.PW + "\"}",
                "{\"token\":7,\"username\":\"admin\",\"password\":\"" + W8.PW + "\"}"}) {
            ClientResponse r = f.post("/auth/setup", body);
            assertEquals(body, 400, r.status());
            assertEquals("BAD_REQUEST", W8.code(r));
        }
        ClientResponse wrong = f.post("/auth/setup", setup(token + "x", "admin", W8.PW));
        assertEquals(401, wrong.status());
        assertEquals("invalid_setup_token", W8.code(wrong));
        assertNull(f.store.findByUsername("admin"));
        assertEquals(201, f.post("/auth/setup", setup(token, "admin", W8.PW)).status());
        ClientResponse again = f.post("/auth/setup", setup(token, "admin", "another-password-1"));
        assertEquals(404, again.status());
        assertEquals("NOT_FOUND", W8.code(again));
    }

    // SEC-AUTH-003 AC-3: an admin exists and recovery is off -> 404
    @Test
    public void SEC_AUTH_003_AC3_closedWithoutRecovery() throws Exception {
        f.user("root", W8.PW, "admin");
        f.start("127.0.0.1", false, b -> b.user("admin", "admin-password-1", "admin"));
        assertNull(f.auth.pendingSetupToken());
        assertEquals(404, f.post("/auth/setup", setup("anything", "root", W8.PW)).status());
    }

    // SEC-AUTH-003 AC-3 (closed setup looks absent): a closed setup in COOKIE mode with a foreign Origin is still 404
    @Test
    public void SEC_AUTH_003_AC3_closedSetupIs404BeforeTheOriginStep() throws Exception {
        f.start("127.0.0.1", false, b -> b.transport(TokenTransport.COOKIE));
        ClientResponse r = f.post("/auth/setup", setup("x", "root", W8.PW), "Host", W8.HOST, "Origin", W8.EVIL);
        assertEquals(404, r.status());
    }

    /** A store that misses the taken name on the read and refuses it on the create (a register race). */
    static final class LateDuplicate extends com.snoworca.naruserve.utils.auth.user.ForwardingUserStore {
        LateDuplicate(com.snoworca.naruserve.utils.auth.user.UserStore d) {
            super(d);
        }

        @Override
        public AuthUser findByUsername(String username) throws Exception {
            return username.equals("raced") ? null : delegate().findByUsername(username);
        }

        @Override
        public AuthUser create(AuthUser user) throws Exception {
            if (user.getUsername().equals("raced")) {
                throw new com.snoworca.naruserve.utils.auth.user.DuplicateUserException("taken by another node");
            }
            return delegate().create(user);
        }
    }

    // FR-FLOW-007 AC-3 (the store's own refusal): DuplicateUserException from UserStore.create -> 409, not 503
    @Test
    public void FR_FLOW_007_AC3_storeDuplicateIs409() throws Exception {
        f.users = new LateDuplicate(f.store);
        f.start(b -> b.register(true));
        ClientResponse r = f.post("/auth/register", register("raced", W8.PW));
        assertEquals(r.text(), 409, r.status());
        assertEquals("duplicate_user", W8.code(r));
    }

    // SEC-AUTH-003 AC-4: .recovery(true) (or NARU_AUTH_RECOVERY=1) logs a new one-time token with one WARN per
    // start; an unknown username is created with admin; an existing one is enabled, gets the new password and admin,
    // and its ver rises (old tokens rejected); a second POST in the process -> 404
    // SEC-IAM-003 AC-1 (recovery row): recovery of an existing user raises ver by exactly 1
    @Test
    public void SEC_AUTH_003_AC4_recoveryCreatesOrReactivates() throws Exception {
        assertTrue(f.builder().recoveryFromEnvValue("1").build().toString().length() > 0);
        String bob = f.user("bob", "old-password-1", "reader");
        f.store.setEnabled(bob, false);
        f.start("127.0.0.1", false, b -> b.recoveryFromEnvValue("1"));
        String token = f.auth.pendingSetupToken();
        assertNotNull("NARU_AUTH_RECOVERY=1 opened recovery", token);
        int warns = 0;
        synchronized (f.logger.lines) {
            for (String l : f.logger.lines) {
                if (l.startsWith("WARN") && l.contains("recovery is enabled")) {
                    warns++;
                    assertTrue(l.contains(token));
                }
            }
        }
        assertEquals(1, warns);
        String old = f.auth.accessToken(bob, 0, 60_000L);
        ClientResponse r = f.post("/auth/setup", setup(token, "bob", W8.PW));
        assertEquals(r.text(), 200, r.status());
        AuthUser after = f.stored(bob);
        assertTrue(after.isEnabled());
        assertTrue(after.getRoles().contains("admin") && after.getRoles().contains("reader"));
        assertEquals(1, after.getTokenVersion());
        assertTrue(new Pbkdf2PasswordHasher(1000).verify(W8.PW.toCharArray(), after.getPasswordHash()));
        assertEquals(401, f.get("/api/x", "Authorization", "Bearer " + old).status());
        assertEquals(404, f.post("/auth/setup", setup(token, "carol", W8.PW)).status());
        assertNull(f.store.findByUsername("carol"));
        f.stop();

        W8 fresh = new W8();
        try {
            fresh.start("127.0.0.1", false, b -> b.recovery(true));
            ClientResponse created = fresh.post("/auth/setup", setup(fresh.auth.pendingSetupToken(), "Ops", W8.PW));
            assertEquals(201, created.status());
            assertEquals(java.util.Collections.singleton("admin"), fresh.store.findByUsername("ops").getRoles());
        } finally {
            fresh.stop();
        }
        assertNull(new W8().builder().recoveryFromEnvValue("0").build().pendingSetupToken());
    }

    // SEC-AUTH-003 AC-5: setup opened by .user("admin", null, "admin") with username root -> 400 username_mismatch
    // (no user); with admin -> admin with the .user roles; .user("ops", null, "admin", "ops") gives admin and ops
    @Test
    public void SEC_AUTH_003_AC5_userSetupNamesItsUser() throws Exception {
        f.start("127.0.0.1", false, b -> b.recovery(true).user("admin", null, "admin"));
        String token = f.auth.pendingSetupToken();
        ClientResponse root = f.post("/auth/setup", setup(token, "root", W8.PW));
        assertEquals(400, root.status());
        assertEquals("username_mismatch", W8.code(root));
        assertNull(f.store.findByUsername("root"));
        assertEquals(201, f.post("/auth/setup", setup(token, "admin", W8.PW)).status());
        assertEquals(java.util.Collections.singleton("admin"), f.store.findByUsername("admin").getRoles());
        assertNull(f.store.findByUsername("root"));
        f.stop();
        W8 ops = new W8();
        try {
            ops.start("127.0.0.1", false, b -> b.recovery(true).roles(com.snoworca.naruserve.utils.auth.rbac.RolePolicy
                    .builder().role("ops", "ops:run").build()).user("ops", null, "admin", "ops"));
            assertEquals(201, ops.post("/auth/setup", setup(ops.auth.pendingSetupToken(), "ops", W8.PW)).status());
            assertEquals(new java.util.LinkedHashSet<>(Arrays.asList("admin", "ops")),
                    ops.store.findByUsername("ops").getRoles());
        } finally {
            ops.stop();
        }
    }

    // SEC-AUTH-003 AC-6: recovery naming an existing service account -> 400 service_account_no_password, unchanged
    @Test
    public void SEC_AUTH_003_AC6_serviceAccountIsRefused() throws Exception {
        String svc = f.store.create(AuthUser.builder().username("svc").serviceAccount(true).build()).getId();
        f.start("127.0.0.1", false, b -> b.recovery(true));
        ClientResponse r = f.post("/auth/setup", setup(f.auth.pendingSetupToken(), "svc", W8.PW));
        assertEquals(400, r.status());
        assertEquals("service_account_no_password", W8.code(r));
        AuthUser after = f.stored(svc);
        assertNull(after.getPasswordHash());
        assertEquals(0, after.getTokenVersion());
        assertTrue(after.getRoles().isEmpty());
        assertNotNull("setup stays open", f.auth.pendingSetupToken());
    }

    // SEC-AUTH-003 AC-7: setup and recovery call no before event even with a throwing beforeUserCreate; they emit
    // onUserCreated / onUserUpdated with origin SETUP or RECOVERY, and each recovery use is audited
    @Test
    public void SEC_AUTH_003_AC7_eventsAndAudit() throws Exception {
        Events events = new Events();
        events.refuseCreate = new AuthRejection(403, "never", "always refuses");
        List<AuditEvent> audit = new CopyOnWriteArrayList<>();
        f.start("127.0.0.1", false, b -> b.recovery(true).user("admin", null, "admin").events(events)
                .audit(audit::add));
        assertEquals(201, f.post("/auth/setup", setup(f.auth.pendingSetupToken(), "admin", W8.PW)).status());
        assertEquals(java.util.Collections.singletonList("onUserCreated:SETUP"), events.calls);
        f.stop();
        Events rec = new Events();
        rec.refuseCreate = new AuthRejection(403, "never", "always refuses");
        List<AuditEvent> trail = new CopyOnWriteArrayList<>();
        W8 w = new W8();
        try {
            w.user("bob", "old-password-1");
            w.start("127.0.0.1", false, b -> b.recovery(true).events(rec).audit(trail::add));
            assertEquals(200, w.post("/auth/setup", setup(w.auth.pendingSetupToken(), "bob", W8.PW)).status());
            assertEquals(java.util.Collections.singletonList("onUserUpdated:RECOVERY"), rec.calls);
            W8 again = new W8();
            try {
                again.start("127.0.0.1", false, b -> b.recovery(true).events(rec).audit(trail::add));
                assertEquals(201, again.post("/auth/setup", setup(again.auth.pendingSetupToken(), "zed", W8.PW))
                        .status());
            } finally {
                again.stop();
            }
            assertEquals(Arrays.asList("onUserUpdated:RECOVERY", "onUserCreated:RECOVERY"), rec.calls);
            int used = 0;
            for (AuditEvent e : trail) {
                if (AuditEvent.RECOVERY_USED.equals(e.getType())) {
                    used++;
                }
            }
            assertEquals(2, used);
        } finally {
            w.stop();
        }
    }

    // SEC-AUTH-003 AC-8: PROD, setup open, the correct token: (a) a secure non-local request and (b) socket
    // 127.0.0.1 with X-Forwarded-For and X-Forwarded-Proto: https under trustProxyHops(1) -> 403 setup_not_local and
    // no user; the token stays unused and a local request then creates the user
    @Test
    public void SEC_AUTH_003_AC8_localityIsSeparateFromSecureTransport() throws Exception {
        W8 remote = adminSetup("10.0.0.5", true);
        try {
            ClientResponse a = remote.post("/auth/setup", setup(remote.auth.pendingSetupToken(), "admin", W8.PW));
            assertEquals(403, a.status());
            assertEquals("setup_not_local", W8.code(a));
        } finally {
            remote.stop();
        }
        f.proxied = true;
        f.start("127.0.0.1", false, b -> b.recovery(true).user("admin", null, "admin"));
        String token = f.auth.pendingSetupToken();
        ClientResponse b = f.post("/auth/setup", setup(token, "admin", W8.PW), "X-Forwarded-For", "203.0.113.7",
                "X-Forwarded-Proto", "https");
        assertEquals(b.text(), 403, b.status());
        assertEquals("setup_not_local", W8.code(b));
        assertNull(f.store.findByUsername("admin"));
        assertEquals(201, f.post("/auth/setup", setup(token, "admin", W8.PW)).status());
    }

    // FR-FLOW-002 AC-9: setup delivery: COOKIE same-origin -> cookies and body csrfToken only; COOKIE evil Origin ->
    // 403 csrf_failed, no Set-Cookie, no user; HEADER -> body tokens; BOTH with Origin: null -> body delivery
    @Test
    public void FR_FLOW_002_AC9_setupDelivery() throws Exception {
        f.start("127.0.0.1", false, b -> b.recovery(true).user("admin", null, "admin")
                .transport(TokenTransport.COOKIE));
        String token = f.auth.pendingSetupToken();
        ClientResponse evil = f.post("/auth/setup", setup(token, "admin", W8.PW), "Host", W8.HOST, "Origin", W8.EVIL);
        assertEquals(403, evil.status());
        assertEquals("csrf_failed", W8.code(evil));
        assertTrue(W8.setCookies(evil).isEmpty());
        assertNull(f.store.findByUsername("admin"));
        ClientResponse same = f.post("/auth/setup", setup(token, "admin", W8.PW), "Host", W8.HOST, "Origin",
                "http://app.test");
        assertEquals(same.text(), 201, same.status());
        assertEquals(1, W8.object(same).size());
        assertNotNull(W8.cookieValue(same, "naru_rt"));
        f.stop();
        for (TokenTransport t : new TokenTransport[] {TokenTransport.HEADER, TokenTransport.BOTH}) {
            W8 w = new W8();
            try {
                w.start("127.0.0.1", false, b -> b.recovery(true).user("admin", null, "admin").transport(t));
                ClientResponse r = w.post("/auth/setup", setup(w.auth.pendingSetupToken(), "admin", W8.PW), "Host",
                        W8.HOST, "Origin", "null");
                assertEquals(t.toString(), 201, r.status());
                assertNotNull(W8.object(r).get("accessToken"));
                assertTrue(W8.setCookies(r).isEmpty());
            } finally {
                w.stop();
            }
        }
    }

    // ---------------------------------------------------------------------------------------------- FR-TOKEN-004

    /** Counts every hash and verify of PBKDF2 at 1000 rounds. */
    static final class Counting implements PasswordHasher {
        final Pbkdf2PasswordHasher inner = new Pbkdf2PasswordHasher(1000);
        final AtomicInteger hashes = new AtomicInteger();
        final AtomicInteger verifies = new AtomicInteger();

        @Override
        public boolean accepts(String stored) {
            return inner.accepts(stored);
        }

        @Override
        public String hash(char[] password) {
            hashes.incrementAndGet();
            return inner.hash(password);
        }

        @Override
        public boolean verify(char[] password, String stored) {
            verifies.incrementAndGet();
            return inner.verify(password, stored);
        }

        @Override
        public boolean needsRehash(String stored) {
            return inner.needsRehash(stored);
        }
    }

    /** Each path setting {@code pw}; returns a description of the refusal, or fails when the password was set. */
    private List<String> refusals(String pw, String rootToken, String targetId, String targetToken)
            throws Exception {
        List<String> out = new ArrayList<>();
        ClientResponse reg = f.post("/auth/register", register("newcomer", pw));
        out.add("register " + reg.status() + " " + W8.code(reg));
        ClientResponse a2 = f.post("/auth/admin/users", "{\"username\":\"made\",\"password\":\"" + pw + "\"}",
                "Authorization", "Bearer " + rootToken);
        out.add("A2 " + a2.status() + " " + W8.code(a2));
        ClientResponse a4 = f.adapter.send(ClientRequest.json("PATCH", "/auth/admin/users/" + targetId,
                "{\"password\":\"" + pw + "\"}").header("Authorization", "Bearer " + rootToken));
        out.add("A4 " + a4.status() + " " + W8.code(a4));
        try {
            f.auth.users().create("viaApp", pw.toCharArray());
            fail("auth.users().create set " + pw.length() + " characters");
        } catch (IllegalArgumentException expected) {
            out.add("users().create IAE");
        }
        String before = f.stored(targetId).getPasswordHash();
        ClientResponse change = f.post("/auth/password", "{\"currentPassword\":\"" + W8.PW + "\",\"newPassword\":\""
                + pw + "\"}", "Authorization", "Bearer " + targetToken);
        assertEquals("the password change set " + pw.length() + " characters", before,
                f.stored(targetId).getPasswordHash());
        out.add("password " + change.status());
        assertNull(f.store.findByUsername("newcomer"));
        assertNull(f.store.findByUsername("made"));
        assertNull(f.store.findByUsername("viaapp"));
        return out;
    }

    private String setupRefusal(String pw) throws Exception {
        W8 w = new W8();
        try {
            Counting counting = new Counting();
            w.start("127.0.0.1", false, b -> b.recovery(true).user("admin", null, "admin").passwordHasher(counting));
            w.auth.passwords().prepare();
            int hashes = counting.hashes.get();
            ClientResponse r = w.post("/auth/setup", setup(w.auth.pendingSetupToken(), "admin", pw));
            assertEquals("setup hashed nothing", hashes, counting.hashes.get());
            assertNull(w.store.findByUsername("admin"));
            return "setup " + r.status() + " " + W8.code(r);
        } finally {
            w.stop();
        }
    }

    // FR-TOKEN-004 AC-1: a password shorter than the minimum is refused on register, setup, admin create, admin reset,
    // auth.users().create and the password change with zero hasher calls
    // FR-TOKEN-004 AC-2: a password longer than 128 characters likewise
    // FR-TOKEN-004 AC-3: an empty password is refused before hashing on those paths (login's empty password is the
    // 400 of FR-FLOW-004 AC-6); no IllegalArgumentException of SecretKeySpec escapes
    @Test
    public void FR_TOKEN_004_AC1_AC3_policyRunsBeforeAnyHash() throws Exception {
        String root = f.user("root", W8.PW, "admin");
        String target = f.user("target", W8.PW, "reader");
        Counting counting = new Counting();
        f.start(b -> b.register(true).passwordHasher(counting));
        String rootToken = (String) f.loginBody("root", W8.PW).get("accessToken");
        String targetToken = (String) f.loginBody("target", W8.PW).get("accessToken");
        StringBuilder longPw = new StringBuilder();
        for (int i = 0; i < 129; i++) {
            longPw.append('a');
        }
        for (String pw : new String[] {"short", longPw.toString(), ""}) {
            int work = counting.hashes.get() + counting.verifies.get();
            List<String> r = refusals(pw, rootToken, target, targetToken);
            assertEquals(pw.length() + " characters, no hash or verify: " + r, work,
                    counting.hashes.get() + counting.verifies.get());
            String expected = pw.isEmpty() ? "[register 422 UNPROCESSABLE_ENTITY, A2 400 BAD_REQUEST, A4 400 "
                    + "BAD_REQUEST, users().create IAE, password 422]" : "[register 422 UNPROCESSABLE_ENTITY, A2 422 "
                    + "UNPROCESSABLE_ENTITY, A4 422 UNPROCESSABLE_ENTITY, users().create IAE, password 422]";
            assertEquals(expected, r.toString());
            assertEquals("setup 422 UNPROCESSABLE_ENTITY", setupRefusal(pw));
        }
        ClientResponse login = f.post("/auth/login", W8.login("root", ""));
        assertEquals(400, login.status());
        assertEquals(PasswordPolicy.of(AuthProfile.STANDARD).getMinLength(), f.auth.newPasswords().policy()
                .getMinLength());
        assertEquals(8, PasswordPolicy.of(AuthProfile.BASELINE).getMinLength());
        assertEquals(15, PasswordPolicy.of(AuthProfile.STRICT).getMinLength());
        assertEquals(128, PasswordPolicy.of(AuthProfile.STRICT).getMaxLength());
    }

    // FR-TOKEN-004 AC-4: a BreachedPasswordChecker that returns true for Password123! refuses that password; with no
    // checker configured the password is set without any checker
    // FR-TOKEN-004 AC-5: a throwing checker: not stored, zero hasher calls, the message not in the body
    @Test
    public void FR_TOKEN_004_AC4_AC5_breachedPasswordChecker() throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        Counting counting = new Counting();
        f.start(b -> b.register(true).passwordHasher(counting).breachedPasswordChecker(pw -> {
            calls.incrementAndGet();
            String s = new String(pw);
            if (s.equals("explode-the-check")) {
                throw new IllegalStateException("breach service secret detail");
            }
            return s.equals("Password123!");
        }));
        f.auth.passwords().prepare();
        int hashes = counting.hashes.get();
        ClientResponse breached = f.post("/auth/register", register("neo", "Password123!"));
        assertEquals(422, breached.status());
        assertEquals(1, calls.get());
        ClientResponse broken = f.post("/auth/register", register("neo", "explode-the-check"));
        assertEquals(503, broken.status());
        assertFalse(broken.text().contains("secret detail"));
        assertEquals(hashes, counting.hashes.get());
        assertNull(f.store.findByUsername("neo"));
        try {
            f.auth.users().create("app", "Password123!".toCharArray());
            fail("a breached password was set");
        } catch (IllegalArgumentException expected) {
            // refused
        }
        assertEquals(201, f.post("/auth/register", register("neo", "a-fine-password-1")).status());
        f.stop();
        W8 none = new W8();
        try {
            none.start(b -> b.register(true));
            assertEquals(201, none.post("/auth/register", register("neo", "Password123!")).status());
        } finally {
            none.stop();
        }
    }

    // FR-TOKEN-004 AC-6: login with an existing password that violates the current policy still succeeds
    @Test
    public void FR_TOKEN_004_AC6_loginIgnoresThePolicy() throws Exception {
        f.user("old", "pw");
        f.start(b -> b.passwordPolicy(PasswordPolicy.length(20, 128)));
        assertEquals(200, f.post("/auth/login", W8.login("old", "pw")).status());
    }

    // ---------------------------------------------------------------------------------------------- SEC-IAM-002

    // SEC-IAM-002 AC-1: checkPassword(id, correct) true, wrong false; a true result does not rehash a hash that would
    // qualify for it
    // SEC-IAM-002 AC-2: an unknown id and a null-hash user -> false after one dummy verify each
    @Test
    public void SEC_IAM_002_AC1_AC2_checkPassword() throws Exception {
        String low = f.store.create(AuthUser.builder().username("low")
                .passwordHash(new Pbkdf2PasswordHasher(500).hash(W8.PW.toCharArray())).build()).getId();
        String svc = f.store.create(AuthUser.builder().username("svc").serviceAccount(true).build()).getId();
        Counting counting = new Counting();
        f.start(b -> b.passwordHasher(counting));
        f.auth.passwords().prepare();
        String before = f.stored(low).getPasswordHash();
        assertTrue("the hash qualifies for a rehash", counting.needsRehash(before));
        int hashes = counting.hashes.get();
        assertTrue(f.auth.users().checkPassword(low, W8.PW.toCharArray()));
        // the rehash decision is made inside checkPassword: no entry was offered, so none can still be running
        assertEquals("no rehash was queued", 0, f.auth.rehashQueue().accepted());
        assertEquals("no rehash hash", hashes, counting.hashes.get());
        assertEquals("no rehash", before, f.stored(low).getPasswordHash());
        assertEquals(0, f.auth.rehashQueue().size());
        assertFalse(f.auth.users().checkPassword(low, "wrong-password".toCharArray()));
        for (String id : new String[] {"no-such-id", svc}) {
            int v = counting.verifies.get();
            assertFalse(f.auth.users().checkPassword(id, W8.PW.toCharArray()));
            assertEquals(id + ": one dummy verify", v + 1, counting.verifies.get());
        }
    }

    /** A PBKDF2 hasher whose verify waits on a latch, to hold the one hash slot. */
    static final class Holding implements PasswordHasher {
        final Pbkdf2PasswordHasher inner = new Pbkdf2PasswordHasher(1000);
        final CountDownLatch inSlot = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        volatile boolean hold;

        @Override
        public boolean accepts(String stored) {
            return inner.accepts(stored);
        }

        @Override
        public String hash(char[] password) {
            return inner.hash(password);
        }

        @Override
        public boolean verify(char[] password, String stored) {
            if (hold) {
                hold = false;
                inSlot.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return inner.verify(password, stored);
        }

        @Override
        public boolean needsRehash(String stored) {
            return false;
        }
    }

    // SEC-IAM-002 AC-3: with the hash slot occupied for more than 5 seconds, checkPassword throws
    // AuthUnavailableException (503)
    @Test
    public void SEC_IAM_002_AC3_fullSlotFor5SecondsIs503() throws Exception {
        final String a = f.user("a", W8.PW);
        final String b = f.user("b", W8.PW);
        final Holding holding = new Holding();
        f.start(x -> x.passwordHasher(holding).hashConcurrency(1));
        f.auth.passwords().prepare();
        holding.hold = true;
        Thread holder = new Thread(() -> f.auth.users().checkPassword(a, W8.PW.toCharArray()));
        holder.start();
        assertTrue(holding.inSlot.await(10, TimeUnit.SECONDS));
        long t0 = System.nanoTime();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        try {
            f.auth.users().checkPassword(b, W8.PW.toCharArray());
        } catch (Throwable e) {
            thrown.set(e);
        } finally {
            holding.release.countDown();
            holder.join(10_000);
        }
        assertTrue(String.valueOf(thrown.get()), thrown.get() instanceof AuthUnavailableException);
        assertEquals(503, ((AuthUnavailableException) thrown.get()).getStatus());
        assertTrue(System.nanoTime() - t0 >= TimeUnit.MILLISECONDS.toNanos(4_900));
    }

    // SEC-IAM-002 AC-3 (a held confirmation of another user on the same lock stripe): the wait is bounded by the same
    // 5 seconds and ends in AuthUnavailableException, never an unbounded wait
    @Test
    public void SEC_IAM_002_AC3_sharedStripeWaitIsBounded() throws Exception {
        final String a = f.user("a", W8.PW);
        String b = null;
        for (int i = 0; i < 400 && b == null; i++) {
            String id = f.user("u" + i, W8.PW);
            if ((id.hashCode() & 0x7fffffff) % 64 == (a.hashCode() & 0x7fffffff) % 64) {
                b = id;
            }
        }
        assertNotNull("an id on the same stripe", b);
        final Holding holding = new Holding();
        f.start(x -> x.passwordHasher(holding).hashConcurrency(2));
        f.auth.passwords().prepare();
        holding.hold = true;
        Thread holder = new Thread(() -> f.auth.users().checkPassword(a, W8.PW.toCharArray()));
        holder.start();
        assertTrue(holding.inSlot.await(10, TimeUnit.SECONDS));
        long t0 = System.nanoTime();
        Throwable thrown = null;
        try {
            f.auth.users().checkPassword(b, W8.PW.toCharArray());
        } catch (Throwable e) {
            thrown = e;
        } finally {
            long waited = System.nanoTime() - t0;
            holding.release.countDown();
            holder.join(10_000);
            assertTrue("bounded wait", waited < TimeUnit.SECONDS.toNanos(8));
        }
        assertTrue(String.valueOf(thrown), thrown instanceof AuthUnavailableException);
    }

    // SEC-IAM-002 AC-4: the Javadoc of checkPassword tells callers to wipe the char[] and to read JSON passwords as
    // String and pass toCharArray(), because Gson cannot read a JSON string into a char[]
    @Test
    public void SEC_IAM_002_AC4_javadocExplainsTheCharArray() throws Exception {
        File src = new File(System.getProperty("user.dir"),
                "src/main/java/com/snoworca/naruserve/utils/auth/user/AuthUsers.java");
        String text;
        try (InputStream in = new FileInputStream(src)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0;) {
                out.write(buf, 0, n);
            }
            text = new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
        int end = text.indexOf("boolean checkPassword(String id, char[] password);");
        int start = text.lastIndexOf("/**", end);
        String doc = text.substring(start, end).replaceAll("\\s*\\*\\s*", " ");
        assertTrue(doc, doc.contains("wipe"));
        assertTrue(doc, doc.contains("Gson"));
        assertTrue(doc, doc.contains("String"));
        assertTrue(doc, doc.contains("toCharArray()"));
    }

    // FR-FLOW-002 AC-8: register and setup are token-issuing responses too: Cache-Control: no-store, by body and by
    // cookies (login, refresh and password assert it in their own tests)
    @Test
    public void FR_FLOW_002_AC8_registerAndSetupAreNoStore() throws Exception {
        for (TokenTransport t : new TokenTransport[] {TokenTransport.HEADER, TokenTransport.COOKIE}) {
            W8 w = new W8();
            try {
                w.start("127.0.0.1", false, b -> b.register(true).recovery(true).user("admin", null, "admin")
                        .transport(t));
                ClientResponse setup = w.post("/auth/setup", setup(w.auth.pendingSetupToken(), "admin", W8.PW), "Host",
                        W8.HOST, "Origin", "http://app.test");
                assertEquals(t + " " + setup.text(), 201, setup.status());
                assertEquals(t.toString(), "no-store", setup.header("Cache-Control"));
                ClientResponse reg = w.post("/auth/register", register("neo", W8.PW), "Host", W8.HOST, "Origin",
                        "http://app.test");
                assertEquals(t + " " + reg.text(), 201, reg.status());
                assertEquals(t.toString(), "no-store", reg.header("Cache-Control"));
            } finally {
                w.stop();
            }
        }
    }

    // FR-FLOW-002 AC-9 (the response shapes): COOKIE same-origin -> Set-Cookie for naru_at, naru_rt and naru_csrf and a
    // body of csrfToken only; HEADER -> body accessToken and refreshToken and no Set-Cookie
    @Test
    public void FR_FLOW_002_AC9_setupResponseShapes() throws Exception {
        f.start("127.0.0.1", false, b -> b.recovery(true).user("admin", null, "admin")
                .transport(TokenTransport.COOKIE));
        ClientResponse cookie = f.post("/auth/setup", setup(f.auth.pendingSetupToken(), "admin", W8.PW), "Host",
                W8.HOST, "Origin", "http://app.test");
        assertEquals(cookie.text(), 201, cookie.status());
        for (String n : new String[] {"naru_at", "naru_rt", "naru_csrf"}) {
            assertNotNull(n, W8.cookieValue(cookie, n));
        }
        assertEquals(1, W8.object(cookie).size());
        assertNotNull(W8.object(cookie).get("csrfToken"));
        W8 w = new W8();
        try {
            w.start("127.0.0.1", false, b -> b.recovery(true).user("admin", null, "admin")
                    .transport(TokenTransport.HEADER));
            ClientResponse body = w.post("/auth/setup", setup(w.auth.pendingSetupToken(), "admin", W8.PW));
            assertEquals(body.text(), 201, body.status());
            assertNotNull(W8.object(body).get("accessToken"));
            assertNotNull(W8.object(body).get("refreshToken"));
            assertTrue(W8.setCookies(body).isEmpty());
        } finally {
            w.stop();
        }
    }
}
