package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.HttpException;
import com.snoworca.naruserve.Registration;
import com.snoworca.naruserve.RegistrationKind;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.utils.auth.apikey.InMemoryApiKeyStore;
import com.snoworca.naruserve.utils.auth.testing.AuthTesting;
import com.snoworca.naruserve.utils.auth.user.AuthUser;
import com.snoworca.naruserve.utils.auth.user.FileUserStore;
import com.snoworca.naruserve.utils.auth.user.ForwardingUserStore;
import com.snoworca.naruserve.utils.auth.user.InMemoryUserStore;
import com.snoworca.naruserve.utils.auth.user.UserStore;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jspecify.annotations.Nullable;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Acceptance criteria of earlier waves that waited for the account endpoints, {@code PATCH /auth/me} or the key
 * issuance endpoints: the user-event contract (FR-AUTH-007, FR-AUTH-008), the mount and error layer (FR-AUTH-005),
 * the fixed clock (IR-AUTH-001), the user stores and name handling (FR-IAM-002, FR-IAM-003), deletion (FR-IAM-008)
 * and the shared password-confirmation limit (SEC-FLOW-015), over the in-memory adapter.
 */
public class Wave10CarryTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final W8 f = new W8();
    private final List<W8> others = new ArrayList<>();
    private final Wave10AccountTest.Recorder events = new Wave10AccountTest.Recorder();

    @After
    public void stop() {
        f.stop();
        for (W8 o : others) {
            o.stop();
        }
    }

    private String bearer(W8 w, String id) throws Exception {
        return "Bearer " + w.auth.accessToken(id, w.stored(id).getTokenVersion(), 900_000L);
    }

    private ClientResponse send(W8 w, String method, String path, @Nullable String json, String auth) {
        ClientRequest r = json == null ? ClientRequest.of(method, path) : ClientRequest.json(method, path, json);
        return w.adapter.send(r.header("Authorization", auth));
    }

    private List<String> step(Runnable r) {
        events.calls.clear();
        r.run();
        return new ArrayList<>(events.calls);
    }

    private interface Call {
        void run() throws Exception;
    }

    private static Runnable unchecked(Call c) {
        return () -> {
            try {
                c.run();
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        };
    }

    // ---------------------------------------------------------------------------------------------- FR-AUTH-008

    // FR-AUTH-008 AC-1: origins: register REGISTER, A2 ADMIN, AuthUsers.create APP, bootstrap seed BOOTSTRAP, setup
    // SETUP, recovery RECOVERY, AuthUsers.rename/update APP, A4 and enable/disable ADMIN, PATCH /auth/me and POST
    // /auth/password SELF, A5 ADMIN and AuthUsers.delete APP
    // FR-AUTH-008 AC-2: register, A2 and AuthUsers.create call beforeUserCreate; rename, A4, PATCH /auth/me, POST
    // /auth/password and AuthUsers.update call beforeUserUpdate; A5 and AuthUsers.delete call beforeUserDelete;
    // bootstrap, setup and recovery call no before event
    // FR-AUTH-008 AC-3: UserChange: rename USERNAME; PATCH /auth/me and AuthUsers.update ATTRIBUTES; POST
    // /auth/password PASSWORD; enable/disable ENABLED; recovery of an existing user the changed subset of ENABLED,
    // PASSWORD, ROLES; A4 one beforeUserUpdate over every changed field and one onUserUpdated with the applied fields
    // FR-AUTH-008 AC-4: every path changing a password also emits onPasswordChanged; the creation paths emit
    // onUserCreated and no onPasswordChanged; a user-row change on a path not in the table (logout ?all=true raising
    // ver) emits no user event
    // FR-AUTH-007 AC-5: one listener recording every callback over every path of the user-change table plus login and
    // refresh: getPasswordHash() is null on every received AuthUser, and on the AuthUsers return values and the rows
    // AuthUsers.update mutators receive
    @Test
    public void FR_AUTH_008_AC1_AC2_AC3_AC4_FR_AUTH_007_AC5_theUserChangeTable() throws Exception {
        String root = f.user("root", W8.PW, "admin");
        String alice = f.user("alice", W8.PW, "reader");
        f.start(b -> b.register(true).events(events).allowedAttributes("displayName")
                .user("seed", "a seed passphrase", "reader"));
        assertEquals("bootstrap", Arrays.asList("onUserCreated:BOOTSTRAP"), new ArrayList<>(events.calls));
        assertEquals("register", Arrays.asList("beforeUserCreate:REGISTER", "onUserCreated:REGISTER"),
                step(unchecked(() -> assertEquals(201, f.post("/auth/register", W8.login("neo", W8.PW)).status()))));
        assertEquals("A2", Arrays.asList("beforeUserCreate:ADMIN", "onUserCreated:ADMIN"), step(unchecked(() ->
                assertEquals(201, f.post("/auth/admin/users", "{\"username\":\"made\",\"password\":\"a made "
                        + "passphrase\"}", "Authorization", bearer(f, root)).status()))));
        final AuthUser[] app = new AuthUser[1];
        assertEquals("create", Arrays.asList("beforeUserCreate:APP", "onUserCreated:APP"), step(unchecked(() ->
                app[0] = f.auth.users().create("viaapp", "an app passphrase".toCharArray()))));
        assertNull(app[0].getPasswordHash());
        final String id = app[0].getId();
        assertEquals("rename", Arrays.asList("beforeUserUpdate:APP", "onUserUpdated[USERNAME]:APP"), step(unchecked(() ->
                assertNull(f.auth.users().rename(id, "renamed").getPasswordHash()))));
        final List<String> mutatorHashes = new CopyOnWriteArrayList<>();
        assertEquals("update", Arrays.asList("beforeUserUpdate:APP", "onUserUpdated[ATTRIBUTES]:APP"),
                step(unchecked(() -> assertNull(f.auth.users().update(id, u -> {
                    mutatorHashes.add(String.valueOf(u.getPasswordHash()));
                    return u.toBuilder().attribute("team", "blue").build();
                }).getPasswordHash()))));
        assertEquals(Arrays.asList("null"), mutatorHashes);
        assertNull(f.auth.users().findById(id).getPasswordHash());
        assertEquals("A4", Arrays.asList("beforeUserUpdate:ADMIN", "onUserUpdated[ATTRIBUTES, PASSWORD, ROLES, USERNAME]:ADMIN",
                "onPasswordChanged:ADMIN"), step(unchecked(() -> assertEquals(200, send(f, "PATCH",
                "/auth/admin/users/" + id, "{\"username\":\"again\",\"password\":\"an admin passphrase\",\"roles\":"
                + "[\"reader\"],\"attributes\":{\"team\":\"red\"}}", bearer(f, root)).status()))));
        assertEquals("disable", Arrays.asList("beforeUserUpdate:ADMIN", "onUserUpdated[ENABLED]:ADMIN"),
                step(unchecked(() -> assertEquals(200, send(f, "PATCH", "/auth/admin/users/" + id,
                        "{\"enabled\":false}", bearer(f, root)).status()))));
        assertEquals("enable", Arrays.asList("beforeUserUpdate:ADMIN", "onUserUpdated[ENABLED]:ADMIN"),
                step(unchecked(() -> assertEquals(200, send(f, "PATCH", "/auth/admin/users/" + id,
                        "{\"enabled\":true}", bearer(f, root)).status()))));
        assertEquals("PATCH /me", Arrays.asList("beforeUserUpdate:SELF", "onUserUpdated[ATTRIBUTES]:SELF"),
                step(unchecked(() -> assertEquals(200, send(f, "PATCH", "/auth/me",
                        "{\"attributes\":{\"displayName\":\"Al\"}}", bearer(f, alice)).status()))));
        assertEquals("password", Arrays.asList("beforeUserUpdate:SELF", "onUserUpdated[PASSWORD]:SELF",
                "onPasswordChanged:SELF"), step(unchecked(() -> assertEquals(200, f.post("/auth/password",
                Wave9PasswordTest.change(W8.PW, "a brand new passphrase"), "Authorization", bearer(f, alice))
                .status()))));
        @SuppressWarnings("unchecked")
        final Map<String, Object>[] pair = new Map[1];
        assertEquals("login", Arrays.asList("beforeLogin:null", "onLogin:null"), step(unchecked(() -> {
            pair[0] = f.loginBody("alice", "a brand new passphrase");
            for (int i = 0; i < 500 && !events.calls.contains("onLogin:null"); i++) {
                Thread.sleep(10); // onLogin runs after the response is written
            }
        })));
        assertEquals("refresh", Arrays.asList("onTokenRefreshed:null"), step(unchecked(() -> assertEquals(200,
                f.post("/auth/refresh", "{\"refreshToken\":\"" + pair[0].get("refreshToken") + "\"}").status()))));
        assertEquals("a ver++ outside the table", Arrays.asList("onLogout:null"), step(unchecked(() ->
                assertEquals(204, f.post("/auth/logout?all=true", null, "Authorization", bearer(f, alice))
                        .status()))));
        final String made = f.store.findByUsername("made").getId();
        assertEquals("A5", Arrays.asList("beforeUserDelete:ADMIN", "onUserDeleted:ADMIN"), step(unchecked(() ->
                assertEquals(204, send(f, "DELETE", "/auth/admin/users/" + made, null, bearer(f, root)).status()))));
        assertEquals("delete", Arrays.asList("beforeUserDelete:APP", "onUserDeleted:APP"), step(unchecked(() ->
                assertTrue(f.auth.users().delete(id)))));
        assertTrue("no received AuthUser carried a hash: " + events.hashes, events.hashes.isEmpty());

        // setup and recovery: no before event, origins SETUP and RECOVERY
        Wave10AccountTest.Recorder rec = new Wave10AccountTest.Recorder();
        W8 s = new W8();
        others.add(s);
        String bob = s.user("bob", W8.PW, "reader");
        s.store.setEnabled(bob, false);
        s.start("127.0.0.1", false, b -> b.recovery(true).user("admin", null, "admin").events(rec));
        rec.calls.clear();
        assertEquals(201, s.post("/auth/setup", "{\"token\":\"" + s.auth.pendingSetupToken() + "\",\"username\":"
                + "\"admin\",\"password\":\"" + W8.PW + "\"}").status());
        assertEquals("setup", Arrays.asList("onUserCreated:SETUP"), new ArrayList<>(rec.calls));
        W8 r = new W8();
        others.add(r);
        String carol = r.user("carol", W8.PW, "reader");
        r.store.setEnabled(carol, false);
        Wave10AccountTest.Recorder rr = new Wave10AccountTest.Recorder();
        r.start("127.0.0.1", false, b -> b.recovery(true).events(rr));
        rr.calls.clear();
        assertEquals(200, r.post("/auth/setup", "{\"token\":\"" + r.auth.pendingSetupToken() + "\",\"username\":"
                + "\"carol\",\"password\":\"a recovered passphrase\"}").status());
        assertEquals("recovery of an existing user", Arrays.asList("onUserUpdated[ENABLED, PASSWORD, ROLES]:RECOVERY",
                "onPasswordChanged:RECOVERY"), new ArrayList<>(rr.calls));
        W8 m = new W8();
        others.add(m);
        Wave10AccountTest.Recorder rm = new Wave10AccountTest.Recorder();
        m.start("127.0.0.1", false, b -> b.recovery(true).events(rm));
        rm.calls.clear();
        assertEquals(201, m.post("/auth/setup", "{\"token\":\"" + m.auth.pendingSetupToken() + "\",\"username\":"
                + "\"ops\",\"password\":\"a recovered passphrase\"}").status());
        assertEquals("recovery of a missing user", Arrays.asList("onUserCreated:RECOVERY"), new ArrayList<>(rm.calls));
        assertTrue(rec.hashes.isEmpty() && rr.hashes.isEmpty() && rm.hashes.isEmpty());
    }

    /** Refuses creation unless the origin is not REGISTER, like an app policy for self-registration only. */
    static final class RegisterPolicy implements AuthEvents {
        final List<Object> origins = new CopyOnWriteArrayList<>();

        @Override
        public void beforeUserCreate(AuthUser candidate, AuthEventContext ctx) {
            if (ctx.getOrigin() == UserOrigin.REGISTER) {
                throw new AuthRejection(422, "domain_not_allowed", "self-registration is closed for this domain");
            }
        }

        @Override
        public void beforeLogin(AuthUser user, AuthEventContext ctx) {
            origins.add(String.valueOf(ctx.getOrigin()));
        }

        @Override
        public void onLogin(AuthUser user, AuthEventContext ctx) {
            origins.add(String.valueOf(ctx.getOrigin()));
        }

        @Override
        public void onTokenRefreshed(AuthUser user, AuthEventContext ctx) {
            origins.add(String.valueOf(ctx.getOrigin()));
        }

        @Override
        public void onLogout(String userId, boolean allDevices, AuthEventContext ctx) {
            origins.add(String.valueOf(ctx.getOrigin()));
        }
    }

    // FR-AUTH-007 AC-1: beforeUserCreate throwing AuthRejection(422, "domain_not_allowed") during register -> 422 JSON
    // with code domain_not_allowed and no user is created (the same policy, testing the REGISTER origin, lets A2 create
    // the same user and leaves login, refresh and logout with a null origin)
    @Test
    public void FR_AUTH_007_AC1_aRegisterPolicy() throws Exception {
        String root = f.user("root", W8.PW, "admin");
        RegisterPolicy policy = new RegisterPolicy();
        f.start(b -> b.register(true).events(policy));
        ClientResponse r = f.post("/auth/register", W8.login("neo", W8.PW));
        assertEquals(422, r.status());
        assertEquals("domain_not_allowed", W8.code(r));
        assertTrue(r.header("Content-Type").startsWith("application/json"));
        assertNull(f.store.findByUsername("neo"));
        assertEquals(201, f.post("/auth/admin/users", W8.login("neo", W8.PW), "Authorization", bearer(f, root))
                .status());
        Map<String, Object> pair = f.loginBody("neo", W8.PW);
        assertEquals(200, f.post("/auth/refresh", "{\"refreshToken\":\"" + pair.get("refreshToken") + "\"}").status());
        assertEquals(204, f.post("/auth/logout", null, "Authorization", "Bearer " + pair.get("accessToken")).status());
        for (int i = 0; i < 500 && policy.origins.size() < 4; i++) {
            Thread.sleep(10); // onLogin runs after the login response is written
        }
        assertEquals(Arrays.asList("null", "null", "null", "null"), policy.origins);
    }

    /** beforeUserCreate failing with an unchecked exception that is not an AuthRejection. */
    static final class Broken implements AuthEvents {
        volatile RuntimeException failure;

        @Override
        public void beforeUserCreate(AuthUser candidate, AuthEventContext ctx) {
            throw failure;
        }
    }

    // FR-AUTH-007 AC-2: beforeUserCreate throwing RuntimeException or HttpException(403, ...) is logged and the response
    // is 503 JSON; AuthUsers.create in an app handler throws AuthUnavailableException
    @Test
    public void FR_AUTH_007_AC2_aBrokenListenerIs503() throws Exception {
        Broken broken = new Broken();
        f.start(b -> b.register(true).events(broken));
        for (RuntimeException e : new RuntimeException[] {new IllegalStateException("bug"),
            new HttpException(403, "not like this")}) {
            broken.failure = e;
            int errors = f.logger.count("ERROR");
            ClientResponse r = f.post("/auth/register", W8.login("neo", W8.PW));
            assertEquals(r.text(), 503, r.status());
            assertTrue(r.header("Content-Type").startsWith("application/json"));
            assertTrue("logged", f.logger.count("ERROR") > errors);
            assertNull(f.store.findByUsername("neo"));
            try {
                f.auth.users().create("viaapp", "an app passphrase".toCharArray());
                fail("created");
            } catch (AuthUnavailableException expected) {
                assertNull(f.store.findByUsername("viaapp"));
            }
        }
    }

    /** A name policy in beforeUserUpdate that sees both rows. */
    static final class NamePolicy implements AuthEvents {
        final List<String> seen = new CopyOnWriteArrayList<>();

        @Override
        public void beforeUserUpdate(AuthUser before, AuthUser after, AuthEventContext ctx) {
            seen.add(before.getUsername() + "->" + after.getUsername());
            if (after.getUsername().startsWith("admin")) {
                throw new AuthRejection(422, "name_policy", "that name is reserved");
            }
        }
    }

    // FR-AUTH-007 AC-3: beforeUserUpdate receives both the before and the after AuthUser; a name policy there rejects a
    // rename through AuthUsers.rename and through admin PATCH A4 with the rejection's status
    @Test
    public void FR_AUTH_007_AC3_aNamePolicyOnRename() throws Exception {
        String root = f.user("root", W8.PW, "admin");
        String bob = f.user("bob", W8.PW, "reader");
        NamePolicy policy = new NamePolicy();
        f.start(b -> b.events(policy));
        try {
            f.auth.users().rename(bob, "administrator");
            fail("renamed");
        } catch (AuthRejection e) {
            assertEquals(422, e.getStatus());
            assertEquals("name_policy", e.getCode());
        }
        ClientResponse r = send(f, "PATCH", "/auth/admin/users/" + bob, "{\"username\":\"admin2\"}", bearer(f, root));
        assertEquals(422, r.status());
        assertEquals("name_policy", W8.code(r));
        assertEquals("bob", f.stored(bob).getUsername());
        assertEquals(Arrays.asList("bob->administrator", "bob->admin2"), policy.seen);
    }

    // ---------------------------------------------------------------------------------------------- FR-AUTH-005

    // FR-AUTH-005 AC-5: a malformed JSON body to POST /auth/login gets 400 JSON from the auth router's error layer,
    // with no stack trace
    @Test
    public void FR_AUTH_005_AC5_aMalformedLoginBody() throws Exception {
        f.user("alice", W8.PW, "reader");
        f.codec = new Wave10AccountTest.StrictCodec();
        f.start(b -> b);
        ClientResponse r = f.post("/auth/login", "{\"username\":\"alice\",\"password\":");
        assertEquals(r.text(), 400, r.status());
        assertEquals("BAD_REQUEST", W8.code(r));
        assertTrue(r.header("Content-Type").startsWith("application/json"));
        assertFalse(r.text(), r.text().contains("at com.") || r.text().contains("Exception"));
    }

    // FR-AUTH-005 AC-8: HEADER with .mountPath("/api/auth") on a local connection: app.registrations() shows
    // ROUTER_MOUNT /api/auth for auth.routes(); POST /api/auth/login with valid credentials is the login endpoint (200
    // with tokens in the body); POST /auth/login with the same body and no token gets 401 unauthenticated
    @Test
    public void FR_AUTH_005_AC8_aCustomMountPath() throws Exception {
        f.user("alice", W8.PW, "reader");
        f.start(b -> b.mountPath("/api/auth"));
        boolean mounted = false;
        for (Registration r : f.app.registrations()) {
            mounted |= r.getKind() == RegistrationKind.ROUTER_MOUNT && "/api/auth".equals(r.getPath())
                    && r.getTarget() == f.auth.routes();
        }
        assertTrue(mounted);
        ClientResponse ok = f.post("/api/auth/login", W8.login("alice", W8.PW));
        assertEquals(ok.text(), 200, ok.status());
        assertNotNull(W8.object(ok).get("accessToken"));
        ClientResponse old = f.post("/auth/login", W8.login("alice", W8.PW));
        assertEquals(401, old.status());
        assertEquals("unauthenticated", W8.code(old));
    }

    // ---------------------------------------------------------------------------------------------- IR-AUTH-001

    // IR-AUTH-001 AC-4: an Auth on a fixed TimeSource returning T: an access token issued at T is accepted at
    // T + accessTtl - 1000 and rejected with 401 invalid_token at T + accessTtl + clock tolerance + 1000
    @Test
    public void IR_AUTH_001_AC4_theTimeSourceDecidesTheExpiry() throws Exception {
        String alice = f.user("alice", W8.PW, "reader");
        f.start(b -> b.accessTtl(600_000L));
        long t = f.clock.get();
        String token = f.auth.tokens().issue(f.stored(alice)).getAccessToken();
        f.clock.set(t + 600_000L - 1_000L);
        assertEquals(200, f.get("/api/x", "Authorization", "Bearer " + token).status());
        f.clock.set(t + 600_000L + com.snoworca.naruserve.utils.auth.jwt.Jwt.DEFAULT_LEEWAY_MILLIS + 1_000L);
        ClientResponse r = f.get("/api/x", "Authorization", "Bearer " + token);
        assertEquals(401, r.status());
        assertEquals("invalid_token", W8.code(r));
    }

    // ---------------------------------------------------------------------------------------------- FR-IAM-002/003/008

    // FR-IAM-002 AC-4: both shipped stores, InMemoryUserStore and FileUserStore, pass AuthTesting.verifyUserStore
    @Test
    public void FR_IAM_002_AC4_bothStoresPassTheContract() throws Exception {
        AuthTesting.verifyUserStore(new InMemoryUserStore());
        AuthTesting.verifyUserStore(new FileUserStore(new File(tmp.newFolder(), "users.json")));
    }

    /** A user store recording the strings auth looks up, mapping one e-mail address to one user. */
    static final class Lookups extends ForwardingUserStore {
        final List<String> names = new CopyOnWriteArrayList<>();
        volatile @Nullable String email;
        volatile @Nullable String emailUser;

        Lookups(UserStore d) {
            super(d);
        }

        @Override
        public @Nullable AuthUser findByUsername(String username) throws Exception {
            names.add(username);
            if (username.equals(email)) {
                return super.findByUsername(String.valueOf(emailUser));
            }
            return super.findByUsername(username);
        }
    }

    // FR-IAM-003 AC-1: login passes NFKC then toLowerCase(Locale.ROOT) of the input to findByUsername: "ＡＬＩＣＥ"
    // (fullwidth) and "Alice" both reach the store as "alice"; under a Turkish default locale "ILKER" reaches it as
    // "ilker"
    // FR-IAM-003 AC-2: a store whose findByUsername looks the received string up in an e-mail column authenticates
    // "bob@example.com"; auth applies no format check to the string
    @Test
    public void FR_IAM_003_AC1_AC2_theLoginNameReachesTheStoreNormalized() throws Exception {
        Lookups lookups = new Lookups(f.store);
        f.users = lookups;
        f.user("alice", W8.PW, "reader");
        f.user("ilker", W8.PW, "reader");
        f.user("bob", W8.PW, "reader");
        lookups.email = "bob+ci@example.com";
        lookups.emailUser = "bob";
        f.start(b -> b);
        for (String input : new String[] {"ＡＬＩＣＥ", "Alice"}) {
            lookups.names.clear();
            assertEquals(input, 200, f.post("/auth/login", W8.login(input, W8.PW)).status());
            assertEquals(Arrays.asList("alice"), lookups.names);
        }
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            lookups.names.clear();
            assertEquals(200, f.post("/auth/login", W8.login("ILKER", W8.PW)).status());
            assertEquals(Arrays.asList("ilker"), lookups.names);
        } finally {
            Locale.setDefault(before);
        }
        lookups.names.clear();
        ClientResponse r = f.post("/auth/login", W8.login("bob+ci@example.com", W8.PW));
        assertEquals(r.text(), 200, r.status());
        assertEquals(Arrays.asList("bob+ci@example.com"), lookups.names);
    }

    // FR-IAM-003 AC-1 (every auth lookup, not only login): the facade's findByUsername and A2 reach the store with the
    // NFKC, lower-cased name, and never with the raw input
    @Test
    public void FR_IAM_003_AC1_theOtherLookupsReachTheStoreNormalized() throws Exception {
        Lookups lookups = new Lookups(f.store);
        f.users = lookups;
        String root = f.user("root", W8.PW, "admin");
        String alice = f.user("alice", W8.PW, "reader");
        f.start(b -> b);
        lookups.names.clear();
        assertEquals(alice, f.auth.users().findByUsername("ＡＬＩＣＥ").getId());
        assertEquals(Arrays.asList("alice"), lookups.names);
        lookups.names.clear();
        ClientResponse created = f.post("/auth/admin/users", "{\"username\":\"ＣＡＲＯＬ\",\"password\":\"" + W8.PW + "\"}",
                "Authorization", bearer(f, root));
        assertEquals(created.text(), 201, created.status());
        assertTrue(lookups.names.toString(), lookups.names.contains("carol"));
        assertFalse(lookups.names.toString(), lookups.names.contains("ＣＡＲＯＬ"));
    }

    // FR-IAM-003 AC-2: the literal "bob@example.com" and names of digits, '@' and '+' reach an e-mail column unchanged
    // and authenticate: auth applies no format check to the string
    @Test
    public void FR_IAM_003_AC2_noFormatCheckOnTheLoginName() throws Exception {
        Lookups lookups = new Lookups(f.store);
        f.users = lookups;
        f.user("bob", W8.PW, "reader");
        lookups.emailUser = "bob";
        f.start(b -> b);
        for (String input : new String[] {"bob@example.com", "0107+1@example.com", "12345"}) {
            lookups.email = input;
            lookups.names.clear();
            ClientResponse r = f.post("/auth/login", W8.login(input, W8.PW));
            assertEquals(input + " " + r.text(), 200, r.status());
            assertEquals(Arrays.asList(input), lookups.names);
        }
    }

    // IR-IAM-001 AC-5 (an auth endpoint): a UserStore exception under POST /auth/login is 503 JSON with the Core error
    // shape and no stack trace
    @Test
    public void IR_IAM_001_AC5_storeExceptionOnAnAuthEndpointIs503() throws Exception {
        f.user("alice", W8.PW, "reader");
        final java.util.concurrent.atomic.AtomicBoolean failing = new java.util.concurrent.atomic.AtomicBoolean();
        f.users = new ForwardingUserStore(f.store) {
            @Override
            public @Nullable AuthUser findByUsername(String username) throws Exception {
                if (failing.get()) {
                    throw new java.sql.SQLException("users table gone");
                }
                return super.findByUsername(username);
            }
        };
        f.start(b -> b);
        failing.set(true);
        ClientResponse r = f.post("/auth/login", W8.login("alice", W8.PW));
        assertEquals(r.text(), 503, r.status());
        assertEquals("SERVICE_UNAVAILABLE", W8.code(r));
        assertTrue(r.text(), r.text().startsWith("{\"error\":{\"code\":"));
        assertTrue(r.header("Content-Type").startsWith("application/json"));
        for (String leak : new String[] {"SQLException", "users table gone", "\tat ", "Exception"}) {
            assertFalse(r.text(), r.text().contains(leak));
        }
    }

    // FR-IAM-008 AC-4: each step is idempotent: the full delete run twice on an already-deleted user answers the A5
    // unknown-user 404 NOT_FOUND on the second call with no store error
    // FR-IAM-008 AC-5: after a successful delete, a refresh with the user's refresh token answers 401 because findById
    // returns null
    @Test
    public void FR_IAM_008_AC4_AC5_deletingTwiceAndRefreshingAfter() throws Exception {
        String root = f.user("root", W8.PW, "admin");
        String bob = f.user("bob", W8.PW, "reader");
        f.start(b -> b.apiKeys(new InMemoryApiKeyStore()));
        String rt = (String) f.loginBody("bob", W8.PW).get("refreshToken");
        int errors = f.logger.count("ERROR");
        assertEquals(204, send(f, "DELETE", "/auth/admin/users/" + bob, null, bearer(f, root)).status());
        ClientResponse again = send(f, "DELETE", "/auth/admin/users/" + bob, null, bearer(f, root));
        assertEquals(404, again.status());
        assertEquals("NOT_FOUND", W8.code(again));
        assertEquals("no store error", errors, f.logger.count("ERROR"));
        assertNull(f.store.findById(bob));
        ClientResponse r = f.post("/auth/refresh", "{\"refreshToken\":\"" + rt + "\"}");
        assertEquals(401, r.status());
        assertEquals("invalid_refresh_token", W8.code(r));
    }

    // ---------------------------------------------------------------------------------------------- SEC-FLOW-015

    // SEC-FLOW-015 AC-1: 5 wrong current passwords on POST /auth/password for u1 within 15 minutes -> the 6th request
    // -> 429 too_many_attempts with Retry-After and no hash for the 6th
    // SEC-FLOW-015 AC-2: 3 failed auth.users().checkPassword(u1, ...) calls followed by 2 failed POST /auth/password for
    // u1 -> the next POST /auth/password for u1 -> 429 (one shared counter)
    @Test
    public void SEC_FLOW_015_AC1_AC2_theSharedConfirmationLimit() throws Exception {
        Wave8AccountTest.Counting counting = new Wave8AccountTest.Counting();
        String u1 = f.user("u1", W8.PW, "reader");
        String u2 = f.user("u2", W8.PW, "reader");
        f.start(b -> b.passwordHasher(counting));
        String wrong = Wave9PasswordTest.change("not the password", "a brand new passphrase");
        for (int i = 0; i < 5; i++) {
            assertEquals(401, f.post("/auth/password", wrong, "Authorization", bearer(f, u1)).status());
        }
        int work = counting.hashes.get() + counting.verifies.get();
        ClientResponse sixth = f.post("/auth/password", wrong, "Authorization", bearer(f, u1));
        assertEquals(429, sixth.status());
        assertEquals("too_many_attempts", W8.code(sixth));
        assertNotNull(sixth.header("Retry-After"));
        assertEquals("no hash for the 6th", work, counting.hashes.get() + counting.verifies.get());
        for (int i = 0; i < 3; i++) {
            assertFalse(f.auth.users().checkPassword(u2, "not the password".toCharArray()));
        }
        for (int i = 0; i < 2; i++) {
            assertEquals(401, f.post("/auth/password", wrong, "Authorization", bearer(f, u2)).status());
        }
        assertEquals(429, f.post("/auth/password", wrong, "Authorization", bearer(f, u2)).status());
    }

    // SEC-FLOW-015 AC-6: with API keys on, 5 failed confirmations on POST /auth/me/apikeys for u1 -> the next POST
    // /auth/password for u1 -> 429; failed confirmations on POST /auth/admin/apikeys count toward the caller's own id
    @Test
    public void SEC_FLOW_015_AC6_keyIssuanceSharesTheLimit() throws Exception {
        String u1 = f.user("u1", W8.PW, "reader");
        String root = f.user("root", W8.PW, "admin");
        f.start(b -> b.apiKeys(new InMemoryApiKeyStore()));
        for (int i = 0; i < 5; i++) {
            assertEquals(401, f.post("/auth/me/apikeys", Wave10PatTest.body("not the password", "posts:read"),
                    "Authorization", bearer(f, u1)).status());
        }
        ClientResponse r = f.post("/auth/password", Wave9PasswordTest.change(W8.PW, "a brand new passphrase"),
                "Authorization", bearer(f, u1));
        assertEquals(429, r.status());
        assertEquals("too_many_attempts", W8.code(r));
        for (int i = 0; i < 5; i++) {
            assertEquals(401, f.post("/auth/admin/apikeys", Wave10PatTest.admin(u1, "not the password", "posts:read"),
                    "Authorization", bearer(f, root)).status());
        }
        assertEquals("the admin's own id counted", 429, f.post("/auth/password",
                Wave9PasswordTest.change(W8.PW, "a brand new passphrase"), "Authorization", bearer(f, root)).status());
    }
}
