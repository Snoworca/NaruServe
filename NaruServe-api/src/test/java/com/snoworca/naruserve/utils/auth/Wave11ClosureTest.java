package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.HttpException;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.utils.auth.apikey.IssuedApiKey;
import com.snoworca.naruserve.utils.auth.group.GroupAdminStore;
import com.snoworca.naruserve.utils.auth.group.InMemoryGroupStore;
import com.snoworca.naruserve.utils.auth.internal.MiniJson;
import com.snoworca.naruserve.utils.auth.internal.UserLocks;
import com.snoworca.naruserve.utils.auth.jwt.JwtClaims;
import com.snoworca.naruserve.utils.auth.password.Pbkdf2PasswordHasher;
import com.snoworca.naruserve.utils.auth.password.PasswordHasher;
import com.snoworca.naruserve.utils.auth.spi.DeviceToken;
import com.snoworca.naruserve.utils.auth.spi.ForwardingLoginThrottle;
import com.snoworca.naruserve.utils.auth.spi.LoginThrottle;
import com.snoworca.naruserve.utils.auth.user.AuthUser;
import com.snoworca.naruserve.utils.auth.user.DuplicateUserException;
import com.snoworca.naruserve.utils.auth.user.FileUserStore;
import com.snoworca.naruserve.utils.auth.user.ForwardingUserStore;
import com.snoworca.naruserve.utils.auth.user.InMemoryUserStore;
import com.snoworca.naruserve.utils.auth.user.UserStore;
import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The closing acceptance criteria of the auth facade, the token lifecycle and the identity stores that earlier waves
 * left open, each over the real endpoints and the shipped stores (the in-memory adapter, real HS256 tokens, the auth
 * MiniJson codec); nothing is a mock.
 */
public class Wave11ClosureTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final W8 f = new W8();
    private final List<W8> others = new ArrayList<>();

    @After
    public void stop() {
        f.stop();
        for (W8 o : others) {
            o.stop();
        }
    }

    private W8 other() {
        W8 w = new W8();
        others.add(w);
        return w;
    }

    private static String bearer(W8 w, String id) throws Exception {
        return "Bearer " + w.auth.accessToken(id, w.stored(id).getTokenVersion(), 900_000L);
    }

    private static ClientResponse send(W8 w, String method, String path, @Nullable String json, String... headers) {
        ClientRequest r = json == null ? ClientRequest.of(method, path) : ClientRequest.json(method, path, json);
        for (int i = 0; i < headers.length; i += 2) {
            r.header(headers[i], headers[i + 1]);
        }
        return w.adapter.send(r);
    }

    private static long lifetime(String jwt) {
        String payload = jwt.split("\\.")[1];
        String json = new String(com.snoworca.naruserve.utils.auth.internal.Base64Url.decode(payload),
                StandardCharsets.UTF_8);
        @SuppressWarnings("unchecked")
        Map<String, Object> c = (Map<String, Object>) MiniJson.parse(json, MiniJson.Limits.file());
        return ((Number) c.get("exp")).longValue() - ((Number) c.get("iat")).longValue();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> claims(String jwt) {
        String json = new String(com.snoworca.naruserve.utils.auth.internal.Base64Url.decode(jwt.split("\\.")[1]),
                StandardCharsets.UTF_8);
        return (Map<String, Object>) MiniJson.parse(json, MiniJson.Limits.file());
    }

    // ---------------------------------------------------------------------------------------------- SEC-AUTH-007

    // SEC-AUTH-007 AC-7: .statelessAccess(true), one POST /auth/logout?all=true (one ver++): the old access token is
    // still accepted on an unguarded route until its exp but is 401 on a route guarded by auth.require(...); the old
    // refresh token, the old device token and an API key issued before the bump are rejected at once, and the user's
    // open auth.webSocket connection on this node is closed
    @Test(timeout = 30000)
    public void SEC_AUTH_007_AC7_statelessAccessAfterLogoutAll() throws Exception {
        final java.util.concurrent.LinkedBlockingQueue<Object> handled = new java.util.concurrent.LinkedBlockingQueue<>();
        String alice = f.user("alice", W8.PW, "reader");
        f.routes = x -> x.app.ws("/ws", x.auth.webSocket((req, ws) -> handled.add(ws)));
        f.start(b -> b.statelessAccess(true).apiKeys(new com.snoworca.naruserve.utils.auth.apikey.InMemoryApiKeyStore()));
        Map<String, Object> login = f.loginBody("alice", W8.PW);
        String at = (String) login.get("accessToken");
        String rt = (String) login.get("refreshToken");
        String device = (String) login.get("deviceToken");
        assertNotNull(device);
        IssuedApiKey key = f.auth.apiKeys().issue(alice, "ci", null, "posts:read");
        assertEquals(200, f.get("/api/x", "Authorization", "ApiKey " + key.getPlainKey()).status());
        java.util.concurrent.Future<ClientResponse> handshake = f.adapter.submit(
                ClientRequest.upgrade("/ws", "Authorization", "Bearer " + at));
        com.snoworca.naruserve.testing.InMemoryWebSocketChannel channel = f.adapter.nextChannel(5, TimeUnit.SECONDS);
        com.snoworca.naruserve.WebSocketSession server =
                (com.snoworca.naruserve.WebSocketSession) handled.poll(5, TimeUnit.SECONDS);
        assertNotNull("the wrapped handler ran", server);
        assertEquals("user", f.get("/health", "Authorization", "Bearer " + at).text());
        assertEquals(200, f.get("/api/x", "Authorization", "Bearer " + at).status());

        long ver = f.stored(alice).getTokenVersion();
        assertEquals(204, f.post("/auth/logout?all=true", null, "Authorization", "Bearer " + at).status());
        assertEquals(ver + 1, f.stored(alice).getTokenVersion());

        // the unguarded route still accepts the old access token (the minimal principal, no store read)
        ClientResponse open = f.get("/health", "Authorization", "Bearer " + at);
        assertEquals(200, open.status());
        assertEquals("user", open.text());
        // the guarded route reads the user and refuses the stale ver
        ClientResponse guarded = f.get("/api/x", "Authorization", "Bearer " + at);
        assertEquals(401, guarded.status());
        // the refresh token, the device token and the API key are rejected at once
        ClientResponse refreshed = f.post("/auth/refresh", "{\"refreshToken\":\"" + rt + "\"}");
        assertEquals(401, refreshed.status());
        assertEquals("invalid_refresh_token", W8.code(refreshed));
        PasswordLogin.Outcome viaDevice = Wave7LoginTest.attempt(f.auth.passwordLogin(), "alice", W8.PW, "10.9.0.1",
                device);
        assertEquals(PasswordLogin.Kind.SUCCESS, viaDevice.kind);
        assertFalse("the old device token is not trusted", viaDevice.trustedDevice);
        ClientResponse viaKey = f.get("/api/x", "Authorization", "ApiKey " + key.getPlainKey());
        assertEquals(401, viaKey.status());
        assertEquals("invalid_api_key", W8.code(viaKey));
        // the open connection on this node is closed
        for (int i = 0; i < 500 && channel.isOpen(); i++) {
            Thread.sleep(10);
        }
        assertFalse(channel.isOpen());
        assertEquals(1008, channel.closeCode());
        assertEquals(101, handshake.get(5, TimeUnit.SECONDS).status());
        // until its exp: once expired the old token is refused on the unguarded route too
        f.clock.addAndGet(16 * 60_000L);
        assertEquals(401, f.get("/health", "Authorization", "Bearer " + at).status());
    }

    // ---------------------------------------------------------------------------------------------- FR-AUTH-003

    // FR-AUTH-003 AC-2: a value set explicitly on the builder wins over the profile default for the same setting
    // (STRICT with .accessTtl(15 min) and .refreshTtl(14 days)), and describe() reports the active profile name
    @Test
    public void FR_AUTH_003_AC2_explicitValueWinsAndDescribeNamesTheProfile() throws Exception {
        String alice = f.user("alice", W8.PW, "reader");
        f.start(b -> b.profile(AuthProfile.STRICT).accessTtl(15 * 60_000L).refreshTtl(14 * 86_400_000L));
        TokenPair p = f.auth.tokens().issue(f.stored(alice));
        assertEquals(900, lifetime(p.getAccessToken()));
        assertEquals(1_209_600, lifetime(p.getRefreshToken()));
        assertTrue(f.auth.describe(), f.auth.describe().contains("  profile: STRICT\n"));
        // the other STRICT default (the 15-character password minimum) still applies, and an explicit policy wins
        W8 strict = other();
        strict.start(b -> b.profile(AuthProfile.STRICT).register(true));
        assertEquals(422, strict.post("/auth/register", W8.login("short", "eight-ch")).status());
        W8 relaxed = other();
        relaxed.start(b -> b.profile(AuthProfile.STRICT).register(true).passwordPolicy(PasswordPolicy.length(8, 128)));
        assertEquals(201, relaxed.post("/auth/register", W8.login("short", "eight-ch")).status());
        assertTrue(relaxed.auth.describe().contains("  profile: STRICT\n"));
    }

    // FR-AUTH-003 AC-6 (runtime half): for each profile, an Auth with no explicit lifetime setter issues access and
    // refresh tokens whose exp - iat equal the guide's table: BASELINE 15 min / 14 days, STANDARD 15 min / 1 day,
    // STRICT 5 min / 12 hours; no .profile(...) is STANDARD
    @Test
    public void FR_AUTH_003_AC6_profileLifetimesMatchTheTable() throws Exception {
        String alice = f.user("alice", W8.PW, "reader");
        long[][] expected = {{900, 1_209_600}, {900, 86_400}, {300, 43_200}};
        AuthProfile[] profiles = {AuthProfile.BASELINE, AuthProfile.STANDARD, AuthProfile.STRICT};
        for (int i = 0; i < profiles.length; i++) {
            Auth auth = f.builder().profile(profiles[i]).build();
            TokenPair p = auth.tokens().issue(f.stored(alice));
            assertEquals(profiles[i].name(), expected[i][0], lifetime(p.getAccessToken()));
            assertEquals(profiles[i].name(), expected[i][1], lifetime(p.getRefreshToken()));
        }
        TokenPair standard = f.builder().build().tokens().issue(f.stored(alice));
        assertEquals(900, lifetime(standard.getAccessToken()));
        assertEquals(86_400, lifetime(standard.getRefreshToken()));
    }

    // FR-AUTH-003 AC-5: SINGLE_INSTANCE with the in-memory refresh store, a refresh presenting a family unknown to this
    // node returns 401 and logs exactly one WARN that the token may come from another node (a second one adds none)
    @Test
    public void FR_AUTH_003_AC5_unknownFamilyWarnsOnce() throws Exception {
        String alice = f.user("alice", W8.PW, "reader");
        f.start(b -> b);
        assertTrue(f.auth.refreshTokens() instanceof InMemoryRefreshTokenStore);
        // a node sharing the user store and the key, with its own in-memory refresh store
        Auth nodeB = f.builder().build();
        String foreign = nodeB.tokens().issue(f.stored(alice)).getRefreshToken();
        String again = nodeB.tokens().issue(f.stored(alice)).getRefreshToken();
        for (String rt : new String[] {foreign, again}) {
            ClientResponse r = f.post("/auth/refresh", "{\"refreshToken\":\"" + rt + "\"}");
            assertEquals(401, r.status());
            assertEquals("invalid_refresh_token", W8.code(r));
        }
        assertEquals(f.logger.lines.toString(), 1,
                RecordingLogger.countMatching(f.logger.lines, "WARN .*another node.*MULTI_INSTANCE.*"));
        for (String l : f.logger.lines) {
            assertFalse("the token is not logged", l.contains(foreign) || l.contains(again));
        }
        // a refresh of this node's own token is not reported
        String own = (String) f.loginBody("alice", W8.PW).get("refreshToken");
        assertEquals(200, f.post("/auth/refresh", "{\"refreshToken\":\"" + own + "\"}").status());
        assertEquals(1, RecordingLogger.countMatching(f.logger.lines, "WARN .*another node.*"));
    }

    // ---------------------------------------------------------------------------------------------- FR-AUTH-004

    // FR-AUTH-004 AC-6: PROD, a null-password admin .user whose account is missing: with .recovery(true) (or
    // NARU_AUTH_RECOVERY=1) a one-time setup token is logged and start continues; that setup fixes the username to the
    // .user name and grants the .user roles although recovery is on (the free-username admin rule does not apply);
    // with recovery off the doctor FAIL stops start; DEV logs a generated temporary password once and creates it
    @Test
    public void FR_AUTH_004_AC6_nullPasswordAdminUnderRecovery() throws Exception {
        for (int way = 0; way < 2; way++) {
            W8 w = other();
            final int fWay = way;
            w.start("127.0.0.1", false, b -> (fWay == 0 ? b.recovery(true) : b.recoveryFromEnvValue("1"))
                    .roles(com.snoworca.naruserve.utils.auth.rbac.RolePolicy.builder().role("reader", "posts:read")
                            .role("ops", "ops:run").build())
                    .user("chief", null, "admin", "ops"));
            String token = w.auth.pendingSetupToken();
            assertNotNull(token);
            assertEquals(1, RecordingLogger.countMatching(w.logger.lines, "WARN .*one-time setup token: " + token + ".*"));
            // the free-username recovery rule does not apply: another name is refused
            ClientResponse other = w.post("/auth/setup", "{\"token\":\"" + token + "\",\"username\":\"intruder\","
                    + "\"password\":\"" + W8.PW + "\"}");
            assertEquals(400, other.status());
            assertEquals("username_mismatch", W8.code(other));
            assertNull(w.store.findByUsername("intruder"));
            ClientResponse ok = w.post("/auth/setup", "{\"token\":\"" + token + "\",\"username\":\"chief\","
                    + "\"password\":\"" + W8.PW + "\"}");
            assertEquals(ok.text(), 201, ok.status());
            AuthUser chief = w.store.findByUsername("chief");
            assertEquals(new LinkedHashSet<>(Arrays.asList("admin", "ops")), chief.getRoles());
            assertEquals(404, w.post("/auth/setup", "{\"token\":\"" + token + "\",\"username\":\"chief\","
                    + "\"password\":\"" + W8.PW + "\"}").status());
        }
        W8 off = other();
        try {
            off.start("127.0.0.1", false, b -> b.user("chief", null, "admin"));
            fail("started without a password and without recovery");
        } catch (RuntimeException e) {
            String m = String.valueOf(e.getMessage()) + (e.getCause() == null ? "" : e.getCause().getMessage());
            assertTrue(m, m.contains("FAIL null-password user"));
        }
        W8 dev = other();
        dev.start("127.0.0.1", false, b -> b.mode(AuthMode.DEV).user("chief", null, "admin"));
        assertNotNull(dev.store.findByUsername("chief"));
        assertEquals(1, RecordingLogger.countMatching(dev.logger.lines, "WARN .*temporary password.*"));
        assertNull(dev.auth.pendingSetupToken());
    }

    // ---------------------------------------------------------------------------------------------- FR-AUTH-005

    /** A user store recording the order of the doctor probe and the seeding create. */
    static final class OrderedStore extends ForwardingUserStore {
        final List<String> calls = new CopyOnWriteArrayList<>();

        OrderedStore(UserStore d) {
            super(d);
        }

        @Override
        public @Nullable AuthUser findByUsername(String username) throws Exception {
            calls.add("find " + username);
            return super.findByUsername(username);
        }

        @Override
        public AuthUser create(AuthUser user) throws Exception {
            calls.add("create " + user.getUsername());
            return super.create(user);
        }
    }

    private static List<Thread> cleanupThreads() {
        List<Thread> out = new ArrayList<>();
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.getName().startsWith("naru-auth-cleanup-") && t.isAlive()) {
                out.add(t);
            }
        }
        return out;
    }

    // FR-AUTH-005 AC-6: onStart runs the doctor and then the bootstrap seeding (the doctor probe precedes the .user
    // create, and its report precedes the seeding log); a doctor FAIL fails start before the port is bound and seeds
    // nothing; onStop stops the cleanup scheduler, and every file-store write is on disk when stop returns
    @Test
    public void FR_AUTH_005_AC6_lifecycleOrder() throws Exception {
        OrderedStore ordered = new OrderedStore(f.store);
        f.users = ordered;
        List<Thread> before = cleanupThreads();
        f.start(b -> b.user("seed", "a seed passphrase", "reader"));
        int probe = ordered.calls.indexOf("find __naru_probe__");
        int create = ordered.calls.indexOf("create seed");
        assertTrue(ordered.calls.toString(), probe >= 0 && create > probe);
        int report = -1;
        int seeded = -1;
        for (int i = 0; i < f.logger.lines.size(); i++) {
            String l = f.logger.lines.get(i);
            if (report < 0 && l.contains("auth doctor: public paths")) {
                report = i;
            }
            if (l.contains("created .user(\"seed\")")) {
                seeded = i;
            }
        }
        assertTrue(f.logger.lines.toString(), report >= 0 && seeded > report);
        List<Thread> running = cleanupThreads();
        running.removeAll(before);
        assertEquals("one cleanup thread while running", 1, running.size());
        f.stop();
        running.get(0).join(5_000);
        assertFalse("the cleanup scheduler stopped", running.get(0).isAlive());

        // a doctor FAIL: the start fails before the port is bound and the seeding never runs
        W8 failing = other();
        OrderedStore failingStore = new OrderedStore(failing.store);
        failing.users = failingStore;
        try {
            failing.start(b -> b.user("seed", "a seed passphrase", "reader").user("x", null, "reader"));
            fail("started with a doctor FAIL");
        } catch (RuntimeException expected) {
            // the null-password non-admin .user
        }
        try {
            failing.adapter.boundPort();
            fail("the port was bound");
        } catch (IllegalStateException notBound) {
            // the start stopped before listen bound
        }
        assertFalse(failingStore.calls.toString(), failingStore.calls.contains("create seed"));
        assertNull(failing.store.findByUsername("seed"));

        // file stores: every write is on disk when stop returns; the shipped file stores write through (each write
        // rewrites the file before it returns), so there is no buffered state for onStop to flush
        File file = new File(tmp.newFolder(), "users.json");
        W8 files = other();
        files.users = new FileUserStore(file, AuthTestSupport.FILE_KEY);
        files.start(b -> b.user("seed", "a seed passphrase", "reader"));
        files.auth.users().create("later", "a later passphrase".toCharArray(), "reader");
        files.stop();
        FileUserStore reread = new FileUserStore(file, AuthTestSupport.FILE_KEY);
        assertNotNull(reread.findByUsername("seed"));
        assertNotNull(reread.findByUsername("later"));
    }

    // ---------------------------------------------------------------------------------------------- FR-AUTH-006

    /** A store whose update() silently does not apply, implementing only the five required methods. */
    static final class SilentFive implements UserStore {
        final InMemoryUserStore d = new InMemoryUserStore();
        final AtomicInteger updates = new AtomicInteger();
        volatile boolean silent;
        volatile long slowUpdateMillis;

        @Override
        public @Nullable AuthUser findByUsername(String username) {
            return d.findByUsername(username);
        }

        @Override
        public @Nullable AuthUser findById(String id) {
            return d.findById(id);
        }

        @Override
        public AuthUser create(AuthUser user) {
            return d.create(user);
        }

        @Override
        public @Nullable AuthUser update(AuthUser user) {
            updates.incrementAndGet();
            if (slowUpdateMillis > 0) {
                try {
                    Thread.sleep(slowUpdateMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (silent) {
                return d.findById(String.valueOf(user.getId())); // reports success, writes nothing
            }
            return d.update(user);
        }

        @Override
        public boolean delete(String id) {
            return d.delete(id);
        }
    }

    /** The auth MiniJson codec reporting malformed JSON as an IOException, as the JsonCodec contract and Gson do. */
    static final class StrictJson implements com.snoworca.naruserve.spi.JsonCodec {
        private final AdminFixture.MiniJsonCodec d = new AdminFixture.MiniJsonCodec();

        @Override
        public <T> @Nullable T decode(java.io.Reader reader, java.lang.reflect.Type type) throws java.io.IOException {
            try {
                return d.decode(reader, type);
            } catch (IllegalArgumentException e) {
                throw new java.io.IOException(e.getMessage(), e);
            }
        }

        @Override
        public void encode(@Nullable Object value, java.io.Writer writer) throws java.io.IOException {
            d.encode(value, writer);
        }
    }

    // FR-AUTH-006 AC-3: in the auth routers the auth error middleware answers: an invalid JSON body 400 BAD_REQUEST, an
    // oversized body 413 and a non-JSON content type 415 UNSUPPORTED_MEDIA_TYPE; AuthRejection(422,
    // "domain_not_allowed") 422 with its code and Retry-After when retryAfterSeconds is set; DuplicateUserException
    // 409; AuthUnavailableException 503 with its own code (SERVICE_UNAVAILABLE by default); any other store exception
    // 503 SERVICE_UNAVAILABLE; any other exception 500; the last three are logged. POST /auth/password over a store
    // implementing only the five required methods whose update() silently does not apply gets 503
    // security_write_conflict
    @Test
    public void FR_AUTH_006_AC3_authRouterErrorTable() throws Exception {
        SilentFive five = new SilentFive();
        String alice = five.create(AuthUser.builder().username("alice")
                .passwordHash(new Pbkdf2PasswordHasher(1000).hash(W8.PW.toCharArray())).build()).getId();
        f.users = five;
        f.codec = new StrictJson();
        f.routes = x -> {
            x.auth.routes().post("/w11/reject", (req, res) -> {
                throw new AuthRejection(422, "domain_not_allowed");
            });
            x.auth.routes().post("/w11/slow", (req, res) -> {
                throw new AuthRejection(429, "too_many_attempts", null, 30L);
            });
            x.auth.routes().post("/w11/dup", (req, res) -> {
                throw new DuplicateUserException("the username is taken");
            });
            x.auth.routes().post("/w11/down", (req, res) -> {
                throw new AuthUnavailableException("store down");
            });
            x.auth.routes().post("/w11/coded", (req, res) -> {
                throw new AuthUnavailableException("db_down", "the database is down", null);
            });
            x.auth.routes().post("/w11/sql", (req, res) -> {
                throw new java.sql.SQLException("connection reset");
            });
            x.auth.routes().post("/w11/bug", (req, res) -> {
                throw new IllegalStateException("a bug");
            });
            x.app.jsonLimit(200);
        };
        f.start(b -> b);
        String bad = "{\"username\":";
        ClientResponse invalid = f.adapter.send(ClientRequest.of("POST", "/auth/login")
                .header("Content-Type", "application/json").body(bad.getBytes(StandardCharsets.UTF_8)));
        assertEquals(invalid.text(), 400, invalid.status());
        assertEquals("BAD_REQUEST", W8.code(invalid));
        StringBuilder big = new StringBuilder("{\"username\":\"alice\",\"password\":\"");
        for (int i = 0; i < 300; i++) {
            big.append('x');
        }
        ClientResponse large = f.post("/auth/login", big.append("\"}").toString());
        assertEquals(large.text(), 413, large.status());
        ClientResponse form = f.adapter.send(ClientRequest.of("POST", "/auth/login")
                .header("Content-Type", "text/plain").body("username=a".getBytes(StandardCharsets.UTF_8)));
        assertEquals(415, form.status());
        assertEquals("UNSUPPORTED_MEDIA_TYPE", W8.code(form));
        String who = "Bearer " + f.auth.accessToken(alice, 0, 900_000L);
        ClientResponse reject = f.post("/auth/w11/reject", null, "Authorization", who);
        assertEquals(422, reject.status());
        assertEquals("domain_not_allowed", W8.code(reject));
        ClientResponse slow = f.post("/auth/w11/slow", null, "Authorization", who);
        assertEquals(429, slow.status());
        assertEquals("30", slow.header("Retry-After"));
        ClientResponse dup = f.post("/auth/w11/dup", null, "Authorization", who);
        assertEquals(409, dup.status());
        assertEquals("duplicate_user", W8.code(dup));
        int errors = f.logger.count("ERROR") + f.logger.count("WARN"); // the same metric as the check below
        ClientResponse down = f.post("/auth/w11/down", null, "Authorization", who);
        assertEquals(503, down.status());
        assertEquals("SERVICE_UNAVAILABLE", W8.code(down));
        ClientResponse coded = f.post("/auth/w11/coded", null, "Authorization", who);
        assertEquals(503, coded.status());
        assertEquals("db_down", W8.code(coded));
        ClientResponse sql = f.post("/auth/w11/sql", null, "Authorization", who);
        assertEquals(503, sql.status());
        assertEquals("SERVICE_UNAVAILABLE", W8.code(sql));
        ClientResponse bug = f.post("/auth/w11/bug", null, "Authorization", who);
        assertEquals(500, bug.status());
        assertEquals("INTERNAL_ERROR", W8.code(bug));
        assertTrue(f.logger.lines.toString(), f.logger.count("ERROR") + f.logger.count("WARN") >= errors + 4);
        assertTrue(RecordingLogger.countMatching(f.logger.lines, ".*store down.*|.*w11/down.*") >= 1);
        assertTrue(RecordingLogger.countMatching(f.logger.lines, ".*w11/sql.*") >= 1);
        assertTrue(RecordingLogger.countMatching(f.logger.lines, ".*w11/bug.*") >= 1);
        // the five-method store whose update() does not apply: the password change is 503 security_write_conflict
        five.silent = true;
        ClientResponse change = f.post("/auth/password", Wave9PasswordTest.change(W8.PW, "a fresh new passphrase"),
                "Authorization", "Bearer " + f.auth.accessToken(alice, 0, 900_000L));
        assertEquals(change.text(), 503, change.status());
        assertEquals("security_write_conflict", W8.code(change));
    }

    // ---------------------------------------------------------------------------------------------- FR-IAM-003

    /** Counts the writes of a device trust. */
    static final class CountingTrust implements com.snoworca.naruserve.utils.auth.spi.DeviceTrust {
        final com.snoworca.naruserve.utils.auth.spi.DeviceTrust d;
        final AtomicInteger writes = new AtomicInteger();

        CountingTrust(com.snoworca.naruserve.utils.auth.spi.DeviceTrust d) {
            this.d = d;
        }

        @Override
        public boolean isInvalidated(DeviceToken token) throws Exception {
            return d.isInvalidated(token);
        }

        @Override
        public boolean recordFailure(DeviceToken token, int max, long windowMillis) throws Exception {
            writes.incrementAndGet();
            return d.recordFailure(token, max, windowMillis);
        }
    }

    /** Counts every user-store write. */
    static final class CountingWrites extends ForwardingUserStore {
        final AtomicInteger writes = new AtomicInteger();

        CountingWrites(UserStore d) {
            super(d);
        }

        @Override
        public @Nullable AuthUser update(AuthUser user) throws Exception {
            writes.incrementAndGet();
            return super.update(user);
        }

        @Override
        public boolean updatePasswordHash(String id, @Nullable String expectedOldHash, String newHash)
                throws Exception {
            writes.incrementAndGet();
            return super.updatePasswordHash(id, expectedOldHash, newHash);
        }

        @Override
        public boolean bumpTokenVersion(String id) throws Exception {
            writes.incrementAndGet();
            return super.bumpTokenVersion(id);
        }
    }

    // FR-IAM-003 AC-6: after a rename, a device token issued before the rename is not treated as trusted for a login
    // with the new name (fingerprint mismatch), and the rename made exactly one user-store write and no device write
    @Test
    public void FR_IAM_003_AC6_renameUntrustsTheOldDeviceToken() throws Exception {
        String alice = f.user("alice", W8.PW, "reader");
        CountingWrites counting = new CountingWrites(f.store);
        f.users = counting;
        CountingTrust trust = new CountingTrust(new InMemoryDeviceTrust(f.clock::get));
        f.start(b -> b.deviceTrust(trust));
        String device = (String) f.loginBody("alice", W8.PW).get("deviceToken");
        assertTrue(Wave7LoginTest.attempt(f.auth.passwordLogin(), "alice", W8.PW, "10.9.0.1", device).trustedDevice);
        int userWrites = counting.writes.get();
        int deviceWrites = trust.writes.get();
        assertNotNull(f.auth.users().rename(alice, "alicia"));
        assertEquals("the rename is one user write", userWrites + 1, counting.writes.get());
        assertEquals("the rename writes nothing to the device store", deviceWrites, trust.writes.get());
        assertEquals(f.stored(alice).getTokenVersion(), f.auth.deviceTokens().read(device, "alice").getTokenVersion());
        PasswordLogin.Outcome renamed = Wave7LoginTest.attempt(f.auth.passwordLogin(), "alicia", W8.PW, "10.9.0.1",
                device);
        assertEquals(PasswordLogin.Kind.SUCCESS, renamed.kind);
        assertFalse("the old token's fingerprint names the old username", renamed.trustedDevice);
        assertNull(f.auth.deviceTokens().read(device, "alicia"));
    }

    // ---------------------------------------------------------------------------------------------- FR-IAM-009

    // FR-IAM-009 AC-2: an access token issued for the user holds no scope or roles claim; a role change through auth
    // (which invalidates the principal cache entry) and a store change after userCacheTtl both change the next
    // request's effective scopes for the same, unreissued token
    @Test
    public void FR_IAM_009_AC2_scopesAreReadPerRequest() throws Exception {
        String root = f.user("root", W8.PW, "admin");
        String bob = f.user("bob", W8.PW, "reader");
        f.routes = x -> x.app.post("/api/w", x.auth.require("posts:write"), (req, res) -> res.status(201).send("w"));
        f.start(b -> b);
        String at = (String) f.loginBody("bob", W8.PW).get("accessToken");
        Map<String, Object> c = claims(at);
        assertEquals(new TreeSet<>(Arrays.asList("exp", "fam", "iat", "jti", "sub", "ver")), new TreeSet<>(c.keySet()));
        assertFalse(c.containsKey("scope") || c.containsKey("scopes") || c.containsKey("roles"));
        assertEquals(403, f.post("/api/w", null, "Authorization", "Bearer " + at).status());
        ClientResponse patch = send(f, "PATCH", "/auth/admin/users/" + bob, "{\"roles\":[\"writer\"]}",
                "Authorization", bearer(f, root));
        assertEquals(patch.text(), 200, patch.status());
        assertEquals(201, f.post("/api/w", null, "Authorization", "Bearer " + at).status());
        // a change written straight to the store: seen after userCacheTtl, same token
        f.store.setRoles(bob, Collections.singleton("reader"));
        assertEquals(201, f.post("/api/w", null, "Authorization", "Bearer " + at).status());
        f.clock.addAndGet(5_000L);
        assertEquals(403, f.post("/api/w", null, "Authorization", "Bearer " + at).status());
    }

    // ---------------------------------------------------------------------------------------------- IR-IAM-001

    // IR-IAM-001 AC-6: InMemoryUserStore and FileUserStore override the listing method: with three users
    // list(0, 2, null) is a page of 2 with total 3, offset 0 and limit 2, and list(2, 2, null) the remaining user; with
    // each store, A1 GET /auth/admin/users?offset=0&limit=2 by the built-in admin answers 200 with {"items", "total",
    // "offset", "limit"} holding 2 items, total 3, offset 0 and limit 2
    @Test
    public void IR_IAM_001_AC6_listingOverEveryShippedStore() throws Exception {
        UserStore[] stores = {new InMemoryUserStore(),
            new FileUserStore(new File(tmp.newFolder(), "users.json"), AuthTestSupport.FILE_KEY)};
        for (UserStore s : stores) {
            String name = s.getClass().getSimpleName();
            assertNotSame(name, UserStore.class, declaring(s.getClass(), "list"));
            String root = s.create(AuthUser.builder().username("root")
                    .passwordHash(new Pbkdf2PasswordHasher(1000).hash(W8.PW.toCharArray())).roles("admin").build())
                    .getId();
            s.create(AuthUser.builder().username("bob").roles("reader").build());
            s.create(AuthUser.builder().username("cid").roles("reader").build());
            com.snoworca.naruserve.utils.auth.user.UserPage first = s.list(0, 2, null);
            assertEquals(name, 2, first.getItems().size());
            assertEquals(name, 3, first.getTotal());
            assertEquals(name, 0, first.getOffset());
            assertEquals(name, 2, first.getLimit());
            com.snoworca.naruserve.utils.auth.user.UserPage rest = s.list(2, 2, null);
            assertEquals(name, 1, rest.getItems().size());
            W8 w = other();
            w.users = s;
            w.start(b -> b);
            ClientResponse r = w.get("/auth/admin/users?offset=0&limit=2", "Authorization",
                    "Bearer " + w.auth.accessToken(root, 0, 900_000L));
            assertEquals(name + " " + r.text(), 200, r.status());
            Map<String, Object> body = W8.object(r);
            assertEquals(name, new TreeSet<>(Arrays.asList("items", "total", "offset", "limit")),
                    new TreeSet<>(body.keySet()));
            assertEquals(name, 2, ((List<?>) body.get("items")).size());
            assertEquals(name, 3L, ((Number) body.get("total")).longValue());
            assertEquals(name, 0L, ((Number) body.get("offset")).longValue());
            assertEquals(name, 2L, ((Number) body.get("limit")).longValue());
            w.stop();
        }
    }

    private static void assertNotSame(String message, Class<?> unexpected, Class<?> actual) {
        assertNotEquals(message, unexpected, actual);
    }

    private static Class<?> declaring(Class<?> c, String method) throws Exception {
        return c.getMethod(method, int.class, int.class, String.class).getDeclaringClass();
    }

    // ---------------------------------------------------------------------------------------------- DR-IAM-002

    /** An in-memory group store counting every createGroup call that reaches it. */
    static final class CountingGroups implements GroupAdminStore {
        final InMemoryGroupStore d = new InMemoryGroupStore();
        final List<String> created = new CopyOnWriteArrayList<>();
        final List<String> calls = new CopyOnWriteArrayList<>();

        @Override
        public Set<String> groupsOfUser(String userId) {
            calls.add("groupsOfUser");
            return d.groupsOfUser(userId);
        }

        @Override
        public Set<String> parentsOf(Set<String> groupIds) {
            calls.add("parentsOf");
            return d.parentsOf(groupIds);
        }

        @Override
        public Set<String> rolesOfGroups(Set<String> groupIds) throws Exception {
            calls.add("rolesOfGroups");
            return d.rolesOfGroups(groupIds);
        }

        @Override
        public boolean createGroup(String groupId) {
            calls.add("createGroup");
            created.add(groupId);
            return d.createGroup(groupId);
        }

        @Override
        public boolean deleteGroup(String groupId) {
            calls.add("deleteGroup");
            return d.deleteGroup(groupId);
        }

        @Override
        public List<String> listGroups() {
            calls.add("listGroups");
            return d.listGroups();
        }

        @Override
        public boolean groupExists(String groupId) {
            calls.add("groupExists");
            return d.groupExists(groupId);
        }

        @Override
        public void addMember(String groupId, String userId) {
            calls.add("addMember");
            d.addMember(groupId, userId);
        }

        @Override
        public void removeMember(String groupId, String userId) {
            calls.add("removeMember");
            d.removeMember(groupId, userId);
        }

        @Override
        public Set<String> membersOf(String groupId) {
            calls.add("membersOf");
            return d.membersOf(groupId);
        }

        @Override
        public void addParent(String childId, String parentId) {
            calls.add("addParent");
            d.addParent(childId, parentId);
        }

        @Override
        public void removeParent(String childId, String parentId) {
            calls.add("removeParent");
            d.removeParent(childId, parentId);
        }

        @Override
        public Set<String> childrenOf(String groupId) {
            calls.add("childrenOf");
            return d.childrenOf(groupId);
        }
    }

    // DR-IAM-002 AC-2: group ids "eng", "team.backend", "on-call_1" are accepted by auth's write path (G2 POST
    // /auth/admin/groups); "Eng", "", a 65-character id and "a b" are rejected with 400 before any GroupAdminStore call
    @Test
    public void DR_IAM_002_AC2_groupIdsCheckedBeforeTheStore() throws Exception {
        String root = f.user("root", W8.PW, "admin");
        CountingGroups groups = new CountingGroups();
        f.start(b -> b.groups(groups));
        for (String ok : new String[] {"eng", "team.backend", "on-call_1"}) {
            ClientResponse r = f.post("/auth/admin/groups", "{\"id\":\"" + ok + "\"}", "Authorization", bearer(f, root));
            assertEquals(ok + " " + r.text(), 201, r.status());
        }
        assertEquals(Arrays.asList("eng", "team.backend", "on-call_1"), groups.created);
        char[] long65 = new char[65];
        Arrays.fill(long65, 'a');
        int callsBefore = groups.calls.size();
        for (String bad : new String[] {"Eng", "", new String(long65), "a b"}) {
            ClientResponse r = f.post("/auth/admin/groups", "{\"id\":\"" + bad + "\"}", "Authorization", bearer(f, root));
            assertEquals("'" + bad + "' " + r.text(), 400, r.status());
        }
        assertEquals("no rejected id reached the store", 3, groups.created.size());
        assertEquals("no GroupAdminStore call of any kind for a rejected id", groups.calls.subList(0, callsBefore),
                groups.calls);
    }

    // ---------------------------------------------------------------------------------------------- CON-AUTH-006

    /** Public and protected instance methods of {@code iface} (default ones included) that {@code impl} does not declare. */
    static List<String> missingOverrides(Class<?> iface, Class<?> impl, boolean abstractOnly) {
        List<String> missing = new ArrayList<>();
        for (Method m : iface.getMethods()) {
            if (Modifier.isStatic(m.getModifiers()) || abstractOnly && m.isDefault()) {
                continue;
            }
            boolean overridden = false;
            for (Class<?> c = impl; c != null && c != Object.class && !overridden; c = c.getSuperclass()) {
                try {
                    overridden = !Modifier.isAbstract(c.getDeclaredMethod(m.getName(), m.getParameterTypes())
                            .getModifiers());
                } catch (NoSuchMethodException e) {
                    // not declared at this level
                }
            }
            if (!overridden) {
                missing.add(m.getName() + Arrays.toString(m.getParameterTypes()));
            }
        }
        Collections.sort(missing);
        return missing;
    }

    // CON-AUTH-006 AC-1: ForwardingLoginThrottle declares an override for every method of LoginThrottle, including
    // retryAfter(key, max, windowMillis), recordFailure(key, max, windowMillis) and usesLimitArguments(); and
    // ForwardingUserStore declares an override for every abstract method of UserStore
    @Test
    public void CON_AUTH_006_AC1_forwardingClassesOverrideEverything() throws Exception {
        assertEquals(Collections.<String>emptyList(),
                missingOverrides(LoginThrottle.class, ForwardingLoginThrottle.class, false));
        for (String[] m : new String[][] {{"retryAfter", "3"}, {"recordFailure", "3"}, {"usesLimitArguments", "0"}}) {
            boolean found = false;
            for (Method d : ForwardingLoginThrottle.class.getDeclaredMethods()) {
                found |= d.getName().equals(m[0]) && d.getParameterCount() == Integer.parseInt(m[1]);
            }
            assertTrue(m[0], found);
        }
        assertTrue(LoginThrottle.class.getMethod("usesLimitArguments").isDefault());
        assertEquals(Collections.<String>emptyList(), missingOverrides(UserStore.class, ForwardingUserStore.class, true));
    }

    /** A throttle interface that gained a default method. */
    interface GrownThrottle extends LoginThrottle {
        default long lockedUntil(String key) {
            return 0L;
        }
    }

    /** A forwarding decorator that predates the new default method. */
    static class StaleForwarding extends ForwardingLoginThrottle implements GrownThrottle {
        StaleForwarding(LoginThrottle d) {
            super(d);
        }
    }

    // CON-AUTH-006 AC-2: the same check fails when a default method is added to LoginThrottle without a matching
    // override in the forwarding class (negative-control fixture)
    @Test
    public void CON_AUTH_006_AC2_aNewDefaultWithoutOverrideFails() {
        assertEquals(Collections.singletonList("lockedUntil[class java.lang.String]"),
                missingOverrides(GrownThrottle.class, StaleForwarding.class, false));
    }

    // ---------------------------------------------------------------------------------------------- SEC-AUTH-002

    // SEC-AUTH-002 AC-3: PROD, a hash-cost finding is a doctor FAIL and start stops; DEV reports it as a WARN and start
    // continues; a weak key is the build() AuthConfigException in PROD and a WARN in DEV (start continues)
    @Test
    public void SEC_AUTH_002_AC3_prodFailsDevWarns() throws Exception {
        com.snoworca.naruserve.utils.auth.password.HashCost low =
                com.snoworca.naruserve.utils.auth.password.HashCost.fixed(1000);
        W8 prod = other();
        try {
            prod.start(b -> b.hashCost(low));
            fail("PROD started with a hash cost below the floor");
        } catch (RuntimeException e) {
            String m = String.valueOf(e.getMessage()) + (e.getCause() == null ? "" : e.getCause().getMessage());
            assertTrue(m, m.contains("FAIL hash cost"));
        }
        W8 dev = other();
        dev.start(b -> b.mode(AuthMode.DEV).hashCost(low).permit("/health"));
        assertEquals(1, RecordingLogger.countMatching(dev.logger.lines, "WARN auth doctor WARN hash cost.*"));
        assertEquals(200, dev.get("/health").status());
        try {
            Auth.builder("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa").build();
            fail("PROD built with a low-variety key");
        } catch (AuthConfigException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("1 distinct byte value;"));
        }
        RecordingLogger devKey = new RecordingLogger();
        com.snoworca.naruserve.testing.TestApp t = com.snoworca.naruserve.testing.TestApp.create();
        Auth weak = Auth.builder("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa").mode(AuthMode.DEV).hashIterations(1000)
                .logger(devKey).permit("/x").build();
        try {
            weak.install(t.app());
            t.app().get("/x", (req, res) -> res.send("x"));
            t.start();
            assertTrue(devKey.lines.toString(),
                    RecordingLogger.countMatching(devKey.lines, "WARN .*distinct byte.*") >= 1);
            assertEquals(200, t.send("GET", "/x").status());
        } finally {
            t.app().shutdown(5, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    // SEC-AUTH-002 AC-4: in DEV these stay enforced exactly as in PROD: default deny (401), install order
    // (IllegalStateException), public path rules (/HEALTH is not /health), CSRF in COOKIE mode and on BOTH-mode cookie
    // requests (403 csrf_failed; HEADER does no CSRF check), login throttling (429 too_many_attempts), grant rules
    // (403 insufficient_scope) and JSON error bodies
    @Test
    public void SEC_AUTH_002_AC4_devKeepsTheSecurityRules() throws Exception {
        for (AuthMode mode : new AuthMode[] {AuthMode.PROD, AuthMode.DEV}) {
            W8 w = other();
            w.user("alice", W8.PW, "reader");
            String root = w.user("root", W8.PW, "admin");
            w.routes = x -> x.app.get("/HEALTH", (req, res) -> res.send("upper"));
            String dee = w.user("dee", W8.PW, "userswriter", "reader");
            w.start(b -> b.mode(mode).permit("/health").roles(com.snoworca.naruserve.utils.auth.rbac.RolePolicy
                    .builder().role("reader", "posts:read").role("writer", "posts:write")
                    .role("userswriter", "auth-users:write").build()));
            ClientResponse deny = w.get("/api/x");
            assertEquals(mode + " default deny", 401, deny.status());
            assertTrue(deny.header("Content-Type").startsWith("application/json"));
            assertTrue(deny.text().startsWith("{\"error\":{\"code\":"));
            assertEquals(mode + " public path", 200, w.get("/health").status());
            assertEquals(mode + " /HEALTH is not public", 401, w.get("/HEALTH").status());
            try {
                Auth again = w.builder().mode(mode).build();
                again.install(w.app);
                fail(mode + " installed after routes");
            } catch (IllegalStateException expected) {
                assertTrue(expected.getMessage().contains("must come before routes"));
            }
            // login throttling: five failures, then 429
            ClientResponse last = null;
            for (int i = 0; i < 12; i++) {
                last = w.post("/auth/login", W8.login("alice", "wrong-" + i));
                if (last.status() == 429) {
                    break;
                }
            }
            assertEquals(mode + " throttled", 429, last.status());
            assertEquals("too_many_attempts", W8.code(last));
            // the grant rule: a delegated admin (auth-users:write and posts:read) passes the A4 guard but may not grant
            // a role whose scopes it does not hold (writer: posts:write); the row is unchanged
            String alice = w.store.findByUsername("alice").getId();
            ClientResponse grant = send(w, "PATCH", "/auth/admin/users/" + alice, "{\"roles\":[\"writer\"]}",
                    "Authorization", bearer(w, dee));
            assertEquals(mode + " grant", 403, grant.status());
            assertEquals("insufficient_scope", W8.code(grant));
            assertEquals(Collections.singleton("reader"), w.stored(alice).getRoles());
            ClientResponse allowed = send(w, "PATCH", "/auth/admin/users/" + alice, "{\"roles\":[\"reader\"]}",
                    "Authorization", bearer(w, dee));
            assertEquals(mode + " the same actor passes the A4 guard", 200, allowed.status());
            String nobody = w.user("nobody", W8.PW);
            ClientResponse scope = w.get("/api/x", "Authorization", bearer(w, nobody));
            assertEquals(mode + " scope", 403, scope.status());
            assertEquals("insufficient_scope", W8.code(scope));
            for (TokenTransport t : new TokenTransport[] {TokenTransport.COOKIE, TokenTransport.BOTH,
                TokenTransport.HEADER}) {
                W8 c = other();
                String carol = c.user("carol", W8.PW, "reader");
                c.start(b -> b.mode(mode).transport(t));
                String at = c.auth.accessToken(carol, 0, 900_000L);
                ClientResponse write = c.post("/api/x", null, "Host", W8.HOST, "Origin", W8.SAME,
                        t == TokenTransport.HEADER ? "Authorization" : "Cookie",
                        t == TokenTransport.HEADER ? "Bearer " + at : "naru_at=" + at);
                if (t == TokenTransport.HEADER) {
                    assertEquals(mode + " " + t, 201, write.status());
                } else {
                    assertEquals(mode + " " + t, 403, write.status());
                    assertEquals("csrf_failed", W8.code(write));
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------- REL-IAM-001

    /** A PBKDF2 hasher at 1000 iterations that runs a hook once from hash() when armed (the rehash worker's call). */
    static final class HookHasher implements PasswordHasher {
        final Pbkdf2PasswordHasher d = new Pbkdf2PasswordHasher(1000);
        final AtomicReference<Runnable> hook = new AtomicReference<>();

        @Override
        public boolean accepts(String stored) {
            return d.accepts(stored);
        }

        @Override
        public String hash(char[] password) {
            Runnable r = hook.getAndSet(null);
            if (r != null) {
                r.run();
            }
            return d.hash(password);
        }

        @Override
        public boolean verify(char[] password, String stored) {
            return d.verify(password, stored);
        }

        @Override
        public boolean needsRehash(String stored) {
            return d.needsRehash(stored);
        }
    }

    /** Records updatePasswordHash calls and counts their completion. */
    static final class RehashRecorder extends ForwardingUserStore {
        final List<String> rehashed = new CopyOnWriteArrayList<>();
        final AtomicReference<CountDownLatch> done = new AtomicReference<>(new CountDownLatch(1));

        RehashRecorder(UserStore d) {
            super(d);
        }

        @Override
        public boolean updatePasswordHash(String id, @Nullable String expectedOldHash, String newHash)
                throws Exception {
            try {
                rehashed.add(id);
                return super.updatePasswordHash(id, expectedOldHash, newHash);
            } finally {
                done.get().countDown();
            }
        }
    }

    // REL-IAM-001 AC-3: SINGLE_INSTANCE, the login rehash path re-reads the row inside the per-user lock and calls
    // updatePasswordHash(id, expectedOldHash, newHash) only when passwordHash, ver, enabled and roles equal the
    // login-time copy: an admin disabling the user during the login's rehash (a hasher hook) leaves no write, ver
    // unchanged, the disable kept and no error. The default updatePasswordHash compares only expectedOldHash: true and
    // ver unchanged on a match, false with nothing written on a mismatch; over a store whose update() silently does
    // not apply it raises nothing (no security_write_conflict) and calls update at most once
    @Test(timeout = 30000)
    public void REL_IAM_001_AC3_loginRehashSkipsAChangedRow() throws Exception {
        String old = new Pbkdf2PasswordHasher(500).hash(W8.PW.toCharArray()); // below 80 % of 1000: needs a rehash
        final String other = new Pbkdf2PasswordHasher(1000).hash("another passphrase".toCharArray());
        String[] fields = {"enabled", "roles", "ver", "passwordHash"};
        List<String> changed = new ArrayList<>();
        List<String> controls = new ArrayList<>();
        for (String field : fields) {
            changed.add(f.store.create(AuthUser.builder().username("u-" + field.toLowerCase(java.util.Locale.ROOT))
                    .passwordHash(old).roles("reader").build()).getId());
            controls.add(f.store.create(AuthUser.builder().username("c-" + field.toLowerCase(java.util.Locale.ROOT))
                    .passwordHash(old).roles("reader").build()).getId());
        }
        RehashRecorder recorder = new RehashRecorder(f.store);
        f.users = recorder;
        final HookHasher hasher = new HookHasher();
        f.start(b -> b.passwordHasher(hasher));
        final W8 w = f;
        int errors = f.logger.count("ERROR") + f.logger.count("WARN");
        for (int i = 0; i < fields.length; i++) {
            final String field = fields[i];
            final String id = changed.get(i);
            // the change an admin (or another node) writes while the rehash of this login hashes
            hasher.hook.set(() -> {
                try {
                    switch (field) {
                        case "enabled":
                            assertTrue(w.store.setEnabled(id, false));
                            break;
                        case "roles":
                            assertTrue(w.store.setRoles(id, Collections.singleton("writer")));
                            break;
                        case "ver":
                            assertTrue(w.store.bumpTokenVersion(id));
                            break;
                        default:
                            assertTrue(w.store.updatePasswordHash(id, old, other));
                    }
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            recorder.done.set(new CountDownLatch(1));
            assertEquals(200, f.post("/auth/login", W8.login(f.stored(id).getUsername(), W8.PW)).status());
            // the control user's login queues the next entry; the single worker serves the changed user's first
            assertEquals(200, f.post("/auth/login", W8.login(f.stored(controls.get(i)).getUsername(), W8.PW))
                    .status());
            assertTrue(field, recorder.done.get().await(20, TimeUnit.SECONDS));
            assertEquals(field + ": only the unchanged rows are rehashed", controls.subList(0, i + 1),
                    recorder.rehashed);
            AuthUser a = f.stored(id);
            assertEquals(field, field.equals("passwordHash") ? other : old, a.getPasswordHash());
            assertEquals(field, field.equals("ver") ? 1 : 0, a.getTokenVersion());
            assertEquals(field + ": the disable stays", !field.equals("enabled"), a.isEnabled());
            assertEquals(field, field.equals("roles") ? Collections.singleton("writer")
                    : Collections.singleton("reader"), a.getRoles());
            AuthUser c = f.stored(controls.get(i));
            assertNotEquals(old, c.getPasswordHash());
            assertEquals("the rehash leaves ver unchanged", 0, c.getTokenVersion());
        }
        assertEquals("no error", errors, f.logger.count("ERROR") + f.logger.count("WARN"));

        // the default updatePasswordHash on its own
        SilentFive five = new SilentFive();
        String id = five.create(AuthUser.builder().username("carol").passwordHash("h1").tokenVersion(3).build()).getId();
        assertTrue(five.updatePasswordHash(id, "h1", "h2"));
        assertEquals("h2", five.findById(id).getPasswordHash());
        assertEquals(3, five.findById(id).getTokenVersion());
        int writes = five.updates.get();
        assertFalse(five.updatePasswordHash(id, "not-the-hash", "h3"));
        assertEquals("no write on a mismatch", writes, five.updates.get());
        assertEquals("h2", five.findById(id).getPasswordHash());
        assertEquals(3, five.findById(id).getTokenVersion());
        five.silent = true;
        writes = five.updates.get();
        assertFalse(five.updatePasswordHash(id, "h2", "h4"));
        assertTrue("update called at most once", five.updates.get() - writes <= 1);
        assertEquals("h2", five.findById(id).getPasswordHash());
    }

    // REL-IAM-001 AC-4: two concurrent auth-originated writes to one user on one node, an admin profile PATCH (A4
    // attributes) and a logout ?all=true, never leave ver below the bumped value: the final row has the profile change
    // and ver = old ver + 1 (each round, over a store with only the five required methods and a slow update)
    @Test(timeout = 60000)
    public void REL_IAM_001_AC4_concurrentProfileWriteAndLogoutAll() throws Exception {
        SilentFive five = new SilentFive();
        final String root = five.create(AuthUser.builder().username("root")
                .passwordHash(new Pbkdf2PasswordHasher(1000).hash(W8.PW.toCharArray())).roles("admin").build()).getId();
        final String alice = five.create(AuthUser.builder().username("alice").roles("reader").build()).getId();
        f.users = five;
        f.start(b -> b.allowedAttributes("team"));
        five.slowUpdateMillis = 15;
        for (int round = 0; round < 6; round++) {
            final long ver = five.findById(alice).getTokenVersion();
            final String rootAuth = "Bearer " + f.auth.accessToken(root, 0, 900_000L);
            final String aliceAuth = "Bearer " + f.auth.accessToken(alice, ver, 900_000L);
            final CountDownLatch go = new CountDownLatch(1);
            final int[] status = new int[2];
            final String team = "t" + round;
            Thread patch = new Thread(() -> {
                try {
                    go.await();
                    status[0] = send(f, "PATCH", "/auth/admin/users/" + alice, "{\"attributes\":{\"team\":\"" + team
                            + "\"}}", "Authorization", rootAuth).status();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            Thread logout = new Thread(() -> {
                try {
                    go.await();
                    status[1] = f.post("/auth/logout?all=true", null, "Authorization", aliceAuth).status();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            patch.start();
            logout.start();
            go.countDown();
            patch.join(20_000);
            logout.join(20_000);
            assertEquals("round " + round, 200, status[0]);
            assertEquals("round " + round, 204, status[1]);
            AuthUser end = five.findById(alice);
            assertEquals("round " + round, team, end.getAttribute("team"));
            assertEquals("round " + round, ver + 1, end.getTokenVersion());
        }
    }

    /** Records, for every write reaching the store, the lock of the user and whether this thread holds it. */
    static final class LockProbe extends SilentFiveDelegate {
        final List<String> seen = new CopyOnWriteArrayList<>();
        final Set<Integer> lockIds = Collections.synchronizedSet(new LinkedHashSet<Integer>());

        @Override
        public @Nullable AuthUser update(AuthUser user) {
            ReentrantLock lock = UserLocks.of(String.valueOf(user.getId()));
            lockIds.add(System.identityHashCode(lock));
            seen.add("update held=" + lock.isHeldByCurrentThread());
            return super.update(user);
        }
    }

    /** {@link SilentFive} as an extendable base. */
    static class SilentFiveDelegate implements UserStore {
        final InMemoryUserStore d = new InMemoryUserStore();

        @Override
        public @Nullable AuthUser findByUsername(String username) {
            return d.findByUsername(username);
        }

        @Override
        public @Nullable AuthUser findById(String id) {
            return d.findById(id);
        }

        @Override
        public AuthUser create(AuthUser user) {
            return d.create(user);
        }

        @Override
        public @Nullable AuthUser update(AuthUser user) {
            return d.update(user);
        }

        @Override
        public boolean delete(String id) {
            return d.delete(id);
        }
    }

    // REL-IAM-001 AC-7: SINGLE_INSTANCE, one lock object per user: a lock-identity probe records one lock instance for
    // u across the five default field methods and auth's user write paths (profile write, logout ?all=true, password
    // change, A4 disable), each write made while the writing thread holds it; while that lock is held a default
    // bumpTokenVersion(u) on the same thread completes without blocking (re-entering the same lock), and a default
    // setEnabled(u, false) from another thread blocks until the lock is released
    @Test(timeout = 30000)
    public void REL_IAM_001_AC7_oneLockPerUser() throws Exception {
        final LockProbe probe = new LockProbe();
        String hash = new Pbkdf2PasswordHasher(1000).hash(W8.PW.toCharArray());
        final String root = probe.create(AuthUser.builder().username("root").passwordHash(hash).roles("admin").build())
                .getId();
        final String u = probe.create(AuthUser.builder().username("alice").passwordHash(hash).roles("reader").build())
                .getId();
        f.users = probe;
        f.start(b -> b.allowedAttributes("team"));
        // the five default field methods
        AuthUser row = probe.findById(u);
        assertTrue(probe.updatePasswordHash(u, row.getPasswordHash(), hash));
        assertTrue(probe.setRoles(u, Collections.singleton("reader")));
        assertTrue(probe.setEnabled(u, true));
        assertTrue(probe.bumpTokenVersion(u));
        assertTrue(probe.changePassword(u, hash, probe.findById(u).getTokenVersion(), hash));
        int defaults = probe.seen.size();
        assertTrue(defaults >= 5);
        // auth's user write paths
        String rootAuth = "Bearer " + f.auth.accessToken(root, 0, 900_000L);
        assertEquals(200, send(f, "PATCH", "/auth/admin/users/" + u, "{\"attributes\":{\"team\":\"a\"}}",
                "Authorization", rootAuth).status());
        assertEquals(204, f.post("/auth/logout?all=true", null, "Authorization",
                "Bearer " + f.auth.accessToken(u, probe.findById(u).getTokenVersion(), 900_000L)).status());
        assertEquals(200, f.post("/auth/password", Wave9PasswordTest.change(W8.PW, "a fresh new passphrase"),
                "Authorization", "Bearer " + f.auth.accessToken(u, probe.findById(u).getTokenVersion(), 900_000L))
                .status());
        assertEquals(200, send(f, "PATCH", "/auth/admin/users/" + u, "{\"enabled\":false}", "Authorization", rootAuth)
                .status());
        assertTrue(probe.seen.size() >= defaults + 4);
        for (String s : probe.seen) {
            assertEquals(probe.seen.toString(), "update held=true", s);
        }
        // every write went to u's row under one lock object
        assertEquals(Collections.singleton(System.identityHashCode(UserLocks.of(u))), probe.lockIds);
        // re-entry on the same thread and blocking of another thread
        probe.d.setEnabled(u, true);
        final ReentrantLock lock = UserLocks.of(u);
        lock.lock();
        boolean held = true;
        try {
            long verBefore = probe.findById(u).getTokenVersion();
            assertTrue(probe.bumpTokenVersion(u));
            assertEquals(verBefore + 1, probe.findById(u).getTokenVersion());
            assertSame(lock, UserLocks.of(u));
            final CountDownLatch done = new CountDownLatch(1);
            Thread other = new Thread(() -> {
                try {
                    probe.setEnabled(u, false);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                } finally {
                    done.countDown();
                }
            });
            other.start();
            assertFalse("setEnabled from another thread waits for the lock", done.await(300, TimeUnit.MILLISECONDS));
            assertTrue(probe.findById(u).isEnabled());
            lock.unlock();
            held = false;
            assertTrue(done.await(10, TimeUnit.SECONDS));
        } finally {
            if (held) {
                lock.unlock();
            }
        }
        assertFalse(probe.findById(u).isEnabled());
    }
}
