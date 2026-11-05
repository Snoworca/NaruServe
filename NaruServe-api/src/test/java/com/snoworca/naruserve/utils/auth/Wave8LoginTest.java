package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.AsyncRouteHandler;
import com.snoworca.naruserve.Registration;
import com.snoworca.naruserve.RegistrationKind;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.utils.auth.internal.Base64Url;
import com.snoworca.naruserve.utils.auth.internal.Passwords;
import com.snoworca.naruserve.utils.auth.internal.RehashQueue;
import com.snoworca.naruserve.utils.auth.jwt.JwtClaims;
import com.snoworca.naruserve.utils.auth.password.BcryptPasswordHasher;
import com.snoworca.naruserve.utils.auth.password.DigestEncoding;
import com.snoworca.naruserve.utils.auth.password.PasswordHasher;
import com.snoworca.naruserve.utils.auth.password.PasswordHashers;
import com.snoworca.naruserve.utils.auth.password.Pbkdf2PasswordHasher;
import com.snoworca.naruserve.utils.auth.password.SaltOrder;
import com.snoworca.naruserve.utils.auth.spi.TokenCodec;
import com.snoworca.naruserve.utils.auth.user.AuthUser;
import com.snoworca.naruserve.utils.auth.user.ForwardingUserStore;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Test;

/**
 * POST /auth/login over the in-memory adapter: the endpoint behind the wave 7 PasswordLogin sequence, the token
 * delivery rule, the device token, the Origin step, the lockout and the rehash queue.
 */
public class Wave8LoginTest {

    private final W8 f = new W8();

    @After
    public void stop() {
        f.stop();
    }

    private static String md5Hex(String text) throws Exception {
        StringBuilder b = new StringBuilder();
        for (byte x : java.security.MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.UTF_8))) {
            b.append(String.format("%02x", x));
        }
        return b.toString();
    }

    // ---------------------------------------------------------------------------------------------- FR-FLOW-004

    // FR-FLOW-004 AC-1: valid credentials -> 200 with a token pair; the access token's fam differs from every family
    // alice held before. FR-FLOW-002 AC-1: HEADER -> accessToken, refreshToken and deviceToken in the body, no
    // Set-Cookie. FR-FLOW-002 AC-8: Cache-Control: no-store. IR-TOKEN-002 AC-1: the default codec's tokens
    // (HS256 with kid, typ at+jwt / rt+jwt).
    @Test
    public void FR_FLOW_004_AC1_loginIssuesAPairInANewFamily() throws Exception {
        f.user("alice", W8.PW);
        f.start(b -> b);
        Set<String> families = new HashSet<>();
        for (int i = 0; i < 3; i++) {
            ClientResponse r = f.post("/auth/login", W8.login("alice", W8.PW));
            assertEquals(r.text(), 200, r.status());
            assertTrue(W8.setCookies(r).isEmpty());
            assertEquals("no-store", r.header("Cache-Control"));
            Map<String, Object> body = W8.object(r);
            String at = (String) body.get("accessToken");
            assertNotNull(body.get("refreshToken"));
            assertNotNull(body.get("deviceToken"));
            assertEquals("Bearer", body.get("tokenType"));
            assertTrue("a new family per login", families.add(f.fam(at)));
            String header = new String(Base64Url.decode(at.substring(0, at.indexOf('.'))), StandardCharsets.UTF_8);
            assertTrue(header, header.contains("\"alg\":\"HS256\"") && header.contains("\"typ\":\"at+jwt\"")
                    && header.contains("\"kid\""));
            String rt = (String) body.get("refreshToken");
            String rh = new String(Base64Url.decode(rt.substring(0, rt.indexOf('.'))), StandardCharsets.UTF_8);
            assertTrue(rh, rh.contains("\"typ\":\"rt+jwt\""));
        }
    }

    // FR-FLOW-004 AC-2: unknown username, wrong password, disabled user and a service account name -> the same 401
    // and body code invalid_credentials. FR-FLOW-009 AC-1 (part): no Lockout configured, no account stays locked.
    @Test
    public void FR_FLOW_004_AC2_everyCredentialFailureIsTheSame401() throws Exception {
        f.user("alice", W8.PW);
        String carol = f.user("carol", W8.PW);
        f.store.setEnabled(carol, false);
        f.store.create(AuthUser.builder().username("svc").serviceAccount(true).build());
        f.start(b -> b);
        List<String> bodies = new ArrayList<>();
        for (String[] c : new String[][] {{"ghost", W8.PW}, {"alice", "wrong-password"}, {"carol", W8.PW},
                {"svc", W8.PW}}) {
            ClientResponse r = f.post("/auth/login", W8.login(c[0], c[1]));
            assertEquals(c[0], 401, r.status());
            assertEquals(c[0], "invalid_credentials", W8.code(r));
            assertEquals("Bearer", r.header("WWW-Authenticate"));
            bodies.add(r.text().replaceAll("\"requestId\":\"[^\"]*\"", ""));
        }
        assertEquals(1, new HashSet<>(bodies).size());
        assertEquals(200, f.post("/auth/login", W8.login("alice", W8.PW)).status());
    }

    // FR-FLOW-004 AC-3: {"username":"ALICE"} with alice's password logs in the user stored as alice
    @Test
    public void FR_FLOW_004_AC3_usernameIsNormalized() throws Exception {
        String alice = f.user("alice", W8.PW);
        f.start(b -> b);
        Map<String, Object> body = f.loginBody("ALICE", W8.PW);
        assertEquals(alice, f.auth.tokens().verifyAccess((String) body.get("accessToken")).getSubject());
    }

    // FR-FLOW-004 AC-4: HEADER -> body deviceToken; COOKIE with a same-origin Origin -> naru_device cookie and no body
    // deviceToken; BOTH follows the delivery rule. FR-FLOW-002 AC-2: COOKIE -> Set-Cookie naru_at, naru_rt,
    // naru_csrf, naru_device and a body with csrfToken only. SEC-FLOW-013 AC-10: naru_device HttpOnly,
    // SameSite=Strict, Path /auth/login, Secure exactly when secure, Max-Age 7776000. SEC-FLOW-006 AC-7: the csrf
    // value is base64url(random16) "." HMAC bound to the sub and fam of the issued access token.
    @Test
    public void FR_FLOW_004_AC4_deviceTokenFollowsTheDeliveryRule() throws Exception {
        f.user("alice", W8.PW);
        f.start(b -> b.transport(TokenTransport.COOKIE));
        ClientResponse r = f.post("/auth/login", W8.login("alice", W8.PW), "Host", W8.HOST, "Origin", W8.SAME);
        assertEquals(r.text(), 200, r.status());
        Map<String, Object> body = W8.object(r);
        assertEquals(body.toString(), 1, body.size());
        String csrf = (String) body.get("csrfToken");
        assertNotNull(csrf);
        for (String n : new String[] {"naru_at", "naru_rt", "naru_csrf", "naru_device"}) {
            assertNotNull(n, W8.cookieValue(r, n));
        }
        String device = W8.setCookie(r, "naru_device");
        assertTrue(device, device.contains("HttpOnly") && device.contains("SameSite=Strict")
                && device.contains("Path=/auth/login") && device.contains("Max-Age=7776000")
                && device.contains("Secure"));
        assertEquals(csrf, W8.cookieValue(r, "naru_csrf"));
        String at = W8.cookieValue(r, "naru_at");
        JwtClaims claims = f.auth.tokens().verifyAccess(at);
        assertTrue(f.auth.csrfTokens().matches(csrf, claims.getSubject(), claims.getString("fam")));
        String[] parts = csrf.split("\\.");
        assertEquals(2, parts.length);
        assertEquals(16, Base64Url.decode(parts[0]).length);
        f.stop();

        W8 plain = new W8();
        try {
            plain.user("alice", W8.PW);
            plain.start("127.0.0.1", false, b -> b.transport(TokenTransport.COOKIE));
            ClientResponse p = plain.post("/auth/login", W8.login("alice", W8.PW), "Host", W8.HOST, "Origin",
                    "http://app.test");
            assertEquals(p.text(), 200, p.status());
            assertFalse("no Secure over plain text", W8.setCookie(p, "naru_device").contains("Secure"));
        } finally {
            plain.stop();
        }

        W8 both = new W8();
        try {
            both.user("alice", W8.PW);
            both.start(b -> b.transport(TokenTransport.BOTH));
            ClientResponse c = both.post("/auth/login", W8.login("alice", W8.PW), "Host", W8.HOST, "Origin", W8.SAME);
            assertNotNull(W8.cookieValue(c, "naru_device"));
            assertNull(W8.object(c).get("deviceToken"));
            ClientResponse noOrigin = both.post("/auth/login", W8.login("alice", W8.PW));
            assertNotNull(W8.object(noOrigin).get("deviceToken"));
            assertNull(W8.setCookie(noOrigin, "naru_device"));
        } finally {
            both.stop();
        }
    }

    /** Records every beforeLogin call; refuses alice with 403 blocked. */
    static final class Blocking implements AuthEvents {
        final List<AuthUser> seen = new CopyOnWriteArrayList<>();

        @Override
        public void beforeLogin(AuthUser user, AuthEventContext ctx) {
            seen.add(user);
            if (user.getUsername().equals("alice")) {
                throw new AuthRejection(403, "blocked", "this account is blocked");
            }
        }
    }

    // FR-FLOW-004 AC-5: a beforeLogin listener refusing alice (correct password) -> 403 blocked, no tokens, the
    // listener saw alice with no hash; it is never called for a wrong password, an unknown name, a disabled user or a
    // service account, which get the AC-2 401; the 403 waits for the failure floor
    @Test
    public void FR_FLOW_004_AC5_beforeLoginRunsOnlyAfterTheVerify() throws Exception {
        f.user("alice", W8.PW);
        String carol = f.user("carol", W8.PW);
        f.store.setEnabled(carol, false);
        f.store.create(AuthUser.builder().username("svc").serviceAccount(true).build());
        Blocking events = new Blocking();
        f.start(b -> b.events(events));
        long floor = f.auth.failureFloor().floorNanos();
        long t0 = System.nanoTime();
        ClientResponse r = f.post("/auth/login", W8.login("alice", W8.PW));
        long elapsed = System.nanoTime() - t0;
        assertEquals(r.text(), 403, r.status());
        assertEquals("blocked", W8.code(r));
        assertFalse(r.text().contains("accessToken"));
        assertTrue("the 403 waited for the floor", elapsed >= floor);
        assertEquals(1, events.seen.size());
        assertEquals("alice", events.seen.get(0).getUsername());
        assertNull(events.seen.get(0).getPasswordHash());
        for (String[] c : new String[][] {{"alice", "wrong-password"}, {"ghost", W8.PW}, {"carol", W8.PW},
                {"svc", W8.PW}}) {
            ClientResponse x = f.post("/auth/login", W8.login(c[0], c[1]));
            assertEquals(c[0], 401, x.status());
            assertEquals(c[0], "invalid_credentials", W8.code(x));
        }
        assertEquals("never called for a failed verify", 1, events.seen.size());
    }

    // FR-FLOW-004 AC-6: a missing password, "password": 7 and "password": "" -> 400 BAD_REQUEST without any hash or
    // hash slot
    @Test
    public void FR_FLOW_004_AC6_badPasswordFieldsAre400WithoutHashing() throws Exception {
        f.user("alice", W8.PW);
        Wave7LoginTest.Counting counting = new Wave7LoginTest.Counting(new Pbkdf2PasswordHasher(1000));
        f.start(b -> b.passwordHasher(counting));
        f.auth.passwords().prepare();
        int before = counting.computations.get();
        long slots = f.auth.hashSlots().requested();
        for (String body : new String[] {"{\"username\":\"alice\"}", "{\"username\":\"alice\",\"password\":7}",
                "{\"username\":\"alice\",\"password\":\"\"}"}) {
            ClientResponse r = f.post("/auth/login", body);
            assertEquals(body, 400, r.status());
            assertEquals(body, "BAD_REQUEST", W8.code(r));
        }
        assertEquals(before, counting.computations.get());
        assertEquals(slots, f.auth.hashSlots().requested());
    }

    // FR-FLOW-004 AC-7: the login route is registered with methodAsync
    @Test
    public void FR_FLOW_004_AC7_loginIsAsync() throws Exception {
        f.start(b -> b);
        int async = 0;
        for (Registration r : f.app.registrations()) {
            if (r.getKind() == RegistrationKind.ROUTE && "/auth/login".equals(r.getPath())
                    && "POST".equals(r.getMethod())) {
                assertTrue(r.getTarget() instanceof AsyncRouteHandler);
                async++;
            }
        }
        assertEquals(1, async);
    }

    // ---------------------------------------------------------------------------------------------- IR-TOKEN-001

    // IR-TOKEN-001 AC-1: a Java lambda replaces the built-in password authenticator through the builder.
    // IR-TOKEN-001 AC-6: an exception of a custom Authenticator -> 503 JSON, not a stack trace and not a login.
    @Test
    public void IR_TOKEN_001_AC1_aJavaLambdaReplacesThePasswordCheck() throws Exception {
        final String alice = f.user("alice", null);
        f.start(b -> b.authenticator((name, password, ctx) -> {
            if (name.equals("boom")) {
                throw new IllegalStateException("directory down: secret detail");
            }
            return name.equals("alice") && new String(password).equals("from-directory") ? f.stored(alice) : null;
        }));
        Map<String, Object> ok = f.loginBody("alice", "from-directory");
        assertEquals(alice, f.auth.tokens().verifyAccess((String) ok.get("accessToken")).getSubject());
        ClientResponse wrong = f.post("/auth/login", W8.login("alice", W8.PW));
        assertEquals(401, wrong.status());
        assertEquals("invalid_credentials", W8.code(wrong));
        ClientResponse boom = f.post("/auth/login", W8.login("boom", "x"));
        assertEquals(boom.text(), 503, boom.status());
        assertEquals("SERVICE_UNAVAILABLE", W8.code(boom));
        assertFalse(boom.text().contains("directory down"));
        assertFalse(boom.text().contains("accessToken"));
    }

    // IR-TOKEN-001 AC-2: the built-in password authenticator returns no user for an unknown name, a wrong password,
    // a disabled user and a service account, each running exactly one target-cost PBKDF2 in one slot
    @Test
    public void IR_TOKEN_001_AC2_builtInAuthenticatorDoesNotTellFailuresApart() throws Exception {
        f.user("alice", W8.PW);
        String carol = f.user("carol", W8.PW);
        f.store.setEnabled(carol, false);
        f.store.create(AuthUser.builder().username("svc").serviceAccount(true).build());
        Wave7LoginTest.Counting counting = new Wave7LoginTest.Counting(new Pbkdf2PasswordHasher(1000));
        f.start(b -> b.passwordHasher(counting));
        f.auth.passwords().prepare();
        for (String[] c : new String[][] {{"ghost", W8.PW}, {"alice", "wrong-password"}, {"carol", W8.PW},
                {"svc", W8.PW}}) {
            int p = counting.computations.get();
            long s = f.auth.hashSlots().requested();
            PasswordLogin.Outcome o = Wave7LoginTest.attempt(f.auth.passwordLogin(), c[0], c[1], "10.9.0.1", null);
            assertEquals(c[0], PasswordLogin.Kind.INVALID_CREDENTIALS, o.kind);
            assertNull(c[0], o.user);
            assertEquals(c[0] + ": one PBKDF2", p + 1, counting.computations.get());
            assertEquals(c[0] + ": one slot", s + 1, f.auth.hashSlots().requested());
        }
    }

    // IR-TOKEN-001 AC-3: the built-in password authenticator returns the user for the correct password and passes a
    // needed rehash to the rehash queue. FR-TOKEN-003 AC-3: a login against an MD5 hash through
    // PasswordHashers.chain(pbkdf2, legacy) schedules a rehash that stores a PBKDF2 hash accepted by
    // Pbkdf2PasswordHasher.
    @Test
    public void IR_TOKEN_001_AC3_correctPasswordLogsInAndQueuesTheRehash() throws Exception {
        String md5 = f.store.create(AuthUser.builder().username("legacy")
                .passwordHash("legacy-md5$" + md5Hex(W8.PW)).build()).getId();
        PasswordHasher chain = PasswordHashers.chain(new Pbkdf2PasswordHasher(1000),
                PasswordHashers.digest("legacy-md5", "MD5", DigestEncoding.HEX));
        f.start(b -> b.passwordHasher(chain));
        assertEquals(200, f.post("/auth/login", W8.login("legacy", W8.PW)).status());
        waitFor(() -> f.stored(md5).getPasswordHash().startsWith(Passwords.PREFIX));
        assertTrue(new Pbkdf2PasswordHasher(1000).verify(W8.PW.toCharArray(), f.stored(md5).getPasswordHash()));
        assertEquals(200, f.post("/auth/login", W8.login("legacy", W8.PW)).status());
    }

    private static String hexDigest(String algorithm, String text) throws Exception {
        StringBuilder b = new StringBuilder();
        for (byte x : java.security.MessageDigest.getInstance(algorithm).digest(text.getBytes(StandardCharsets.UTF_8))) {
            b.append(String.format("%02x", x));
        }
        return b.toString();
    }

    // FR-TOKEN-003 AC-3: a successful POST /auth/login against an MD5, SHA-1, SHA-256, salted SHA-256 or bcrypt hash
    // schedules a rehash, after which the stored hash is accepted by Pbkdf2PasswordHasher
    @Test
    public void FR_TOKEN_003_AC3_everyLegacyFormatRehashesToPbkdf2() throws Exception {
        String salt = "Xy7q";
        String[][] rows = {
            {"md5user", "legacy-md5$" + hexDigest("MD5", W8.PW)},
            {"sha1user", "legacy-sha1$" + hexDigest("SHA-1", W8.PW)},
            {"sha256user", "legacy-sha256$" + hexDigest("SHA-256", W8.PW)},
            {"salteduser", "legacy-ssha$" + salt + "$" + hexDigest("SHA-256", salt + W8.PW)},
            {"bcryptuser", BcryptPasswordHasher.builder().cost(4).build().hash(W8.PW.toCharArray())},
        };
        List<String> ids = new ArrayList<>();
        for (String[] row : rows) {
            ids.add(f.store.create(AuthUser.builder().username(row[0]).passwordHash(row[1]).build()).getId());
        }
        Pbkdf2PasswordHasher pbkdf2 = new Pbkdf2PasswordHasher(1000);
        f.start(b -> b.passwordHasher(PasswordHashers.chain(pbkdf2, PasswordHashers.bcrypt(),
                PasswordHashers.digest("legacy-md5", "MD5", DigestEncoding.HEX),
                PasswordHashers.digest("legacy-sha1", "SHA-1", DigestEncoding.HEX),
                PasswordHashers.digest("legacy-sha256", "SHA-256", DigestEncoding.HEX),
                PasswordHashers.saltedDigest("legacy-ssha", "SHA-256", DigestEncoding.HEX,
                        SaltOrder.SALT_THEN_PASSWORD))));
        for (int i = 0; i < rows.length; i++) {
            final String id = ids.get(i);
            assertFalse(rows[i][0], pbkdf2.accepts(f.stored(id).getPasswordHash()));
            ClientResponse r = f.post("/auth/login", W8.login(rows[i][0], W8.PW));
            assertEquals(rows[i][0] + " " + r.text(), 200, r.status());
            waitFor(() -> pbkdf2.accepts(f.stored(id).getPasswordHash()));
            assertTrue(rows[i][0], pbkdf2.verify(W8.PW.toCharArray(), f.stored(id).getPasswordHash()));
        }
    }

    /** A legacy hasher of {@code legacy-x$<reversed password>}, written against the public PasswordHasher. */
    static final class ReversedHasher implements PasswordHasher {
        @Override
        public boolean accepts(String stored) {
            return stored.startsWith("legacy-x$");
        }

        @Override
        public String hash(char[] password) {
            return "legacy-x$" + new StringBuilder(new String(password)).reverse();
        }

        @Override
        public boolean verify(char[] password, String stored) {
            return stored.equals(hash(password));
        }

        @Override
        public boolean needsRehash(String stored) {
            return true;
        }
    }

    // FR-TOKEN-003 AC-7: a custom PasswordHasher accepting only legacy-x$ hashes in the chain: the right password logs
    // in through its verify and the PBKDF2 rehash follows; a wrong password fails
    @Test
    public void FR_TOKEN_003_AC7_customLegacyHasherInTheChain() throws Exception {
        String id = f.store.create(AuthUser.builder().username("old")
                .passwordHash(new ReversedHasher().hash(W8.PW.toCharArray())).build()).getId();
        f.start(b -> b.passwordHasher(PasswordHashers.chain(new Pbkdf2PasswordHasher(1000), new ReversedHasher())));
        assertEquals(401, f.post("/auth/login", W8.login("old", "wrong-password")).status());
        assertEquals(200, f.post("/auth/login", W8.login("old", W8.PW)).status());
        waitFor(() -> f.stored(id).getPasswordHash().startsWith(Passwords.PREFIX));
        assertTrue(new Pbkdf2PasswordHasher(1000).verify(W8.PW.toCharArray(), f.stored(id).getPasswordHash()));
    }

    /** A store whose updatePasswordHash (the rehash write) throws. */
    static final class FailingRehash extends ForwardingUserStore {
        final AtomicInteger tries = new AtomicInteger();

        FailingRehash(com.snoworca.naruserve.utils.auth.user.UserStore d) {
            super(d);
        }

        @Override
        public boolean updatePasswordHash(String id, String expectedOldHash, String newHash) throws Exception {
            tries.incrementAndGet();
            throw new java.sql.SQLException("disk full");
        }
    }

    // FR-TOKEN-003 AC-6: when storing the rehash fails, the login still succeeds and a WARN is logged
    @Test
    public void FR_TOKEN_003_AC6_failingRehashKeepsTheLogin() throws Exception {
        f.store.create(AuthUser.builder().username("legacy").passwordHash("legacy-md5$" + md5Hex(W8.PW)).build());
        FailingRehash failing = new FailingRehash(f.store);
        f.users = failing;
        f.start(b -> b.passwordHasher(PasswordHashers.chain(new Pbkdf2PasswordHasher(1000),
                PasswordHashers.digest("legacy-md5", "MD5", DigestEncoding.HEX))));
        assertEquals(200, f.post("/auth/login", W8.login("legacy", W8.PW)).status());
        waitFor(() -> failing.tries.get() > 0);
        waitFor(() -> warned("rehash"));
    }

    private boolean warned(String word) {
        synchronized (f.logger.lines) {
            for (String l : f.logger.lines) {
                if (l.startsWith("WARN") && l.toLowerCase(java.util.Locale.ROOT).contains(word)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** A PBKDF2 hasher whose hash() blocks while armed, so the rehash worker waits on the test's latch. */
    static final class LatchedHasher implements PasswordHasher {
        final Pbkdf2PasswordHasher inner = new Pbkdf2PasswordHasher(1000);
        final AtomicBoolean armed = new AtomicBoolean();
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger hashes = new AtomicInteger();
        final AtomicInteger verifies = new AtomicInteger();

        @Override
        public boolean accepts(String stored) {
            return inner.accepts(stored);
        }

        @Override
        public String hash(char[] password) {
            if (armed.get()) {
                hashes.incrementAndGet();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return inner.hash(password);
        }

        @Override
        public boolean verify(char[] password, String stored) {
            verifies.incrementAndGet();
            return inner.verify(password, stored);
        }

        @Override
        public boolean needsRehash(String stored) {
            return inner.needsRehash(stored) || stored.contains("$500$");
        }
    }

    // PERF-TOKEN-002 AC-1: with the rehash blocked on the test's latch, a PBKDF2 user below the rehash threshold gets
    // its 200 after exactly one PBKDF2 verify at the stored count in one slot, and a legacy (MD5) user gets its 200
    // after the legacy verify plus one dummy PBKDF2 in one slot; neither response waits for its rehash
    @Test
    public void PERF_TOKEN_002_AC1_oneVerifyInTheSlotAndTheLegacyLoginDoesNotWait() throws Exception {
        final String low = f.store.create(AuthUser.builder().username("low")
                .passwordHash(new Pbkdf2PasswordHasher(500).hash(W8.PW.toCharArray())).build()).getId();
        final String legacy = f.store.create(AuthUser.builder().username("legacy")
                .passwordHash("legacy-md5$" + md5Hex(W8.PW)).build()).getId();
        LatchedHasher primary = new LatchedHasher();
        Wave7LoginTest.Counting md5 = new Wave7LoginTest.Counting(
                PasswordHashers.digest("legacy-md5", "MD5", DigestEncoding.HEX));
        f.start(b -> b.passwordHasher(PasswordHashers.chain(primary, md5)).hashConcurrency(2));
        f.auth.passwords().prepare();
        primary.armed.set(true);
        try {
            int verifies = primary.verifies.get();
            long slots = f.auth.hashSlots().requested();
            assertEquals(200, f.post("/auth/login", W8.login("low", W8.PW)).status());
            waitFor(() -> primary.hashes.get() == 1); // the worker took low's rehash and waits on the latch
            assertEquals("one PBKDF2 verify", verifies + 1, primary.verifies.get());
            assertEquals("the login's one slot and the rehash's own", slots + 2, f.auth.hashSlots().requested());
            assertTrue(f.stored(low).getPasswordHash().contains("$500$"));

            verifies = primary.verifies.get();
            int legacyVerifies = md5.computations.get();
            slots = f.auth.hashSlots().requested();
            assertEquals(200, f.post("/auth/login", W8.login("legacy", W8.PW)).status());
            assertEquals("the legacy verify", legacyVerifies + 1, md5.computations.get());
            assertEquals("one dummy PBKDF2", verifies + 1, primary.verifies.get());
            assertEquals("the login's one slot; its rehash is still queued", slots + 1,
                    f.auth.hashSlots().requested());
            assertEquals(1, f.auth.rehashQueue().size());
            assertTrue(f.stored(legacy).getPasswordHash().startsWith("legacy-md5$"));
        } finally {
            primary.armed.set(false);
            primary.release.countDown();
        }
        waitFor(() -> f.stored(low).getPasswordHash().startsWith(Passwords.PREFIX)
                && !f.stored(low).getPasswordHash().contains("$500$"));
        waitFor(() -> f.stored(legacy).getPasswordHash().startsWith(Passwords.PREFIX));
    }

    // PERF-TOKEN-002 AC-1: a successful login of a user whose stored count is below the target does one verify in its
    // slot and its 200 arrives while the rehash (blocked on the test's latch) is still pending
    // PERF-TOKEN-002 AC-2: with 100 entries queued, the 101st rehash is dropped without blocking the login, and the
    // same user's next successful login enqueues it again
    @Test
    public void PERF_TOKEN_002_AC1_AC2_rehashRunsOutsideTheLoginAndDropsWhenFull() throws Exception {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < RehashQueue.CAPACITY + 2; i++) {
            ids.add(f.store.create(AuthUser.builder().username("low" + i)
                    .passwordHash(new Pbkdf2PasswordHasher(500).hash(W8.PW.toCharArray())).build()).getId());
        }
        LatchedHasher hasher = new LatchedHasher();
        f.start(b -> b.passwordHasher(hasher));
        f.auth.passwords().prepare();
        hasher.armed.set(true);
        try {
            assertEquals(200, f.post("/auth/login", W8.login("low0", W8.PW)).status());
            waitFor(() -> hasher.hashes.get() == 1); // the worker took low0 and waits on the latch
            assertTrue(f.stored(ids.get(0)).getPasswordHash().contains("$500$"));
            for (int i = 1; i <= RehashQueue.CAPACITY; i++) {
                assertEquals(200, f.post("/auth/login", W8.login("low" + i, W8.PW)).status());
            }
            assertEquals(RehashQueue.CAPACITY, f.auth.rehashQueue().size());
            long t0 = System.nanoTime();
            String last = "low" + (RehashQueue.CAPACITY + 1);
            assertEquals(200, f.post("/auth/login", W8.login(last, W8.PW)).status());
            assertTrue("the dropped rehash does not block", System.nanoTime() - t0 < TimeUnit.SECONDS.toNanos(5));
            assertEquals(RehashQueue.CAPACITY, f.auth.rehashQueue().size());
        } finally {
            hasher.armed.set(false);
            hasher.release.countDown();
        }
        final String lastId = ids.get(RehashQueue.CAPACITY + 1);
        waitFor(() -> f.auth.rehashQueue().size() == 0);
        assertTrue("dropped, not rehashed", f.stored(lastId).getPasswordHash().contains("$500$"));
        assertEquals(200, f.post("/auth/login", W8.login("low" + (RehashQueue.CAPACITY + 1), W8.PW)).status());
        waitFor(() -> !f.stored(lastId).getPasswordHash().contains("$500$"));
    }

    interface Check {
        boolean ok() throws Exception;
    }

    static void waitFor(Check c) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (!c.ok()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timed out");
            }
            Thread.sleep(10);
        }
    }

    // ---------------------------------------------------------------------------------------------- IR-TOKEN-002

    /** A codec in front of the default one that counts its calls and refuses tokens marked as poisoned. */
    static final class CountingCodec implements TokenCodec {
        final TokenCodec inner;
        final AtomicInteger encodes = new AtomicInteger();
        final AtomicInteger decodes = new AtomicInteger();

        CountingCodec(TokenCodec inner) {
            this.inner = inner;
        }

        @Override
        public String encode(JwtClaims claims) throws Exception {
            encodes.incrementAndGet();
            return "x" + inner.encode(claims);
        }

        @Override
        public JwtClaims decode(String token, String expectedType) throws Exception {
            decodes.incrementAndGet();
            if (!token.startsWith("x")) {
                throw new IllegalArgumentException("not ours: the reason stays in the log");
            }
            return inner.decode(token.substring(1), expectedType);
        }
    }

    // IR-TOKEN-002 AC-2: with a custom TokenCodec a login issues the tokens its encode returns, a protected request
    // is authenticated from its decode, and a decode failure is the same 401 invalid_token as a bad signature
    @Test
    public void IR_TOKEN_002_AC2_customCodecIssuesAndVerifies() throws Exception {
        f.user("alice", W8.PW, "reader");
        final CountingCodec[] codec = new CountingCodec[1];
        f.start(b -> {
            Auth plain = f.builder().build();
            codec[0] = new CountingCodec(plain.tokenCodec());
            return b.tokenCodec(codec[0]);
        });
        Map<String, Object> body = f.loginBody("alice", W8.PW);
        String at = (String) body.get("accessToken");
        assertTrue(at.startsWith("x") && ((String) body.get("refreshToken")).startsWith("x"));
        assertTrue(codec[0].encodes.get() >= 2);
        ClientResponse ok = f.get("/api/x", "Authorization", "Bearer " + at);
        assertEquals(ok.text(), 200, ok.status());
        ClientResponse bad = f.get("/api/x", "Authorization", "Bearer " + at.substring(1));
        assertEquals(401, bad.status());
        assertEquals("invalid_token", W8.code(bad));
        assertFalse(bad.text().contains("not ours"));
    }

    // IR-TOKEN-002 AC-2: the reason a custom codec's decode gives is logged and never reaches the response
    @Test
    public void IR_TOKEN_002_AC2_decodeFailureReasonIsLoggedAndNotSent() throws Exception {
        f.user("alice", W8.PW, "reader");
        f.start(b -> b.tokenCodec(new CountingCodec(f.builder().build().tokenCodec())));
        String at = (String) f.loginBody("alice", W8.PW).get("accessToken");
        ClientResponse bad = f.get("/api/x", "Authorization", "Bearer " + at.substring(1));
        assertEquals(401, bad.status());
        assertEquals("invalid_token", W8.code(bad));
        assertFalse(bad.text(), bad.text().contains("not ours"));
        assertTrue(f.logger.lines.toString(), com.snoworca.naruserve.testing.RecordingLogger.countMatching(
                f.logger.lines, "(?s).*not ours: the reason stays in the log.*") > 0);
    }

    // ---------------------------------------------------------------------------------------------- SEC-FLOW-007

    // SEC-FLOW-007 AC-1: COOKIE, a browser holding a valid naru_at logs in with a same-origin Origin and no
    // X-CSRF-Token -> 200 regular login; no principal is attached (SEC-FLOW-003 AC-3 covers the principal)
    // SEC-FLOW-007 AC-2: a cross-origin Origin not in trustedOrigins -> 403 csrf_failed and no Set-Cookie
    // SEC-FLOW-007 AC-3: neither Origin nor Referer, and Origin: null -> 403 csrf_failed
    // SEC-FLOW-007 AC-4: no Origin but a same-origin Referer -> passes (200)
    // SEC-FLOW-007 AC-5: .trustedOrigins("https://admin.example.com") and that Origin -> passes (200)
    @Test
    public void SEC_FLOW_007_AC1_AC5_loginOriginStepInCookieMode() throws Exception {
        String alice = f.user("alice", W8.PW);
        f.start(b -> b.transport(TokenTransport.COOKIE).trustedOrigins("https://admin.example.com"));
        String held = f.auth.accessToken(alice, 0, 60_000L);
        ClientResponse ok = f.post("/auth/login", W8.login("alice", W8.PW), "Host", W8.HOST, "Origin", W8.SAME,
                "Cookie", "naru_at=" + held);
        assertEquals(ok.text(), 200, ok.status());
        for (String[] h : new String[][] {{"Origin", W8.EVIL}, {"Origin", "null"}, {"X-None", "x"}}) {
            ClientResponse r = f.post("/auth/login", W8.login("alice", W8.PW), "Host", W8.HOST, h[0], h[1]);
            assertEquals(h[1], 403, r.status());
            assertEquals("csrf_failed", W8.code(r));
            assertTrue(W8.setCookies(r).isEmpty());
        }
        ClientResponse referer = f.post("/auth/login", W8.login("alice", W8.PW), "Host", W8.HOST, "Referer",
                "https://app.test/login?next=/");
        assertEquals(200, referer.status());
        ClientResponse trusted = f.post("/auth/login", W8.login("alice", W8.PW), "Host", W8.HOST, "Origin",
                "https://admin.example.com");
        assertEquals(200, trusted.status());
        assertTrue(W8.plantsAuthCookie(trusted));
    }

    // SEC-FLOW-007 AC-6: COOKIE, a cookie-authenticated POST with a valid CSRF token but a cross-origin Origin -> 403
    // SEC-FLOW-007 AC-8: BOTH, naru_at of family F with F's CSRF and Origin https://evil.example -> 403, no route run
    @Test
    public void SEC_FLOW_007_AC6_AC8_cookieWritesNeedTheOrigin() throws Exception {
        String alice = f.user("alice", W8.PW, "reader");
        for (TokenTransport t : new TokenTransport[] {TokenTransport.COOKIE, TokenTransport.BOTH}) {
            W8 w = new W8();
            try {
                w.user("alice", W8.PW, "reader");
                w.start(b -> b.transport(t));
                String at = w.auth.accessToken(w.store.findByUsername("alice").getId(), 0, 60_000L);
                String csrf = w.auth.csrfTokens().issue(w.auth.tokens().verifyAccess(at).getSubject(), w.fam(at));
                String cookie = "naru_at=" + at + "; naru_csrf=" + csrf;
                ClientResponse evil = w.post("/api/x", "{}", "Host", W8.HOST, "Origin", W8.EVIL, "Cookie", cookie,
                        "X-CSRF-Token", csrf);
                assertEquals(t + " " + evil.text(), 403, evil.status());
                assertEquals("csrf_failed", W8.code(evil));
                ClientResponse same = w.post("/api/x", "{}", "Host", W8.HOST, "Origin", W8.SAME, "Cookie", cookie,
                        "X-CSRF-Token", csrf);
                assertEquals(t + " " + same.text(), 201, same.status());
            } finally {
                w.stop();
            }
        }
        assertNotNull(alice);
    }

    // SEC-FLOW-007 AC-7: COOKIE or BOTH, no response gives a value to an auth cookie unless the request passed the
    // Origin check (failed COOKIE logins plant nothing; BOTH falls back to body delivery)
    @Test
    public void SEC_FLOW_007_AC7_noCookieWithoutTheOriginCheck() throws Exception {
        for (TokenTransport t : new TokenTransport[] {TokenTransport.COOKIE, TokenTransport.BOTH}) {
            W8 w = new W8();
            try {
                w.user("alice", W8.PW);
                w.start(b -> b.transport(t));
                for (String origin : new String[] {W8.EVIL, "null"}) {
                    ClientResponse r = w.post("/auth/login", W8.login("alice", W8.PW), "Host", W8.HOST, "Origin",
                            origin);
                    assertFalse(t + " " + origin, W8.plantsAuthCookie(r));
                }
                ClientResponse none = w.post("/auth/login", W8.login("alice", W8.PW), "Host", W8.HOST);
                assertFalse(t.toString(), W8.plantsAuthCookie(none));
            } finally {
                w.stop();
            }
        }
    }

    // ---------------------------------------------------------------------------------------------- FR-FLOW-002

    // FR-FLOW-002 AC-5: BOTH login: no Origin and no Referer -> body; same-origin -> cookies with body csrfToken
    // only; capacitor://localhost not trusted -> body without Set-Cookie and not 403; with
    // .trustedOrigins("https://spa.example") that Origin -> cookie delivery
    // FR-FLOW-002 AC-7: BOTH login without Origin but with a same-origin Referer -> cookie delivery
    @Test
    public void FR_FLOW_002_AC5_AC7_bothModeLoginDelivery() throws Exception {
        f.user("alice", W8.PW);
        f.start(b -> b.transport(TokenTransport.BOTH).trustedOrigins("https://spa.example"));
        ClientResponse none = f.post("/auth/login", W8.login("alice", W8.PW), "Host", W8.HOST);
        assertEquals(200, none.status());
        assertNotNull(W8.object(none).get("accessToken"));
        assertTrue(W8.setCookies(none).isEmpty());
        ClientResponse same = f.post("/auth/login", W8.login("alice", W8.PW), "Host", W8.HOST, "Origin", W8.SAME);
        assertEquals(1, W8.object(same).size());
        assertNotNull(W8.object(same).get("csrfToken"));
        ClientResponse cap = f.post("/auth/login", W8.login("alice", W8.PW), "Host", W8.HOST, "Origin",
                "capacitor://localhost");
        assertEquals(200, cap.status());
        assertNotNull(W8.object(cap).get("accessToken"));
        assertTrue(W8.setCookies(cap).isEmpty());
        ClientResponse spa = f.post("/auth/login", W8.login("alice", W8.PW), "Host", W8.HOST, "Origin",
                "https://spa.example");
        assertNotNull(W8.cookieValue(spa, "naru_at"));
        assertNull(W8.object(spa).get("accessToken"));
        ClientResponse referer = f.post("/auth/login", W8.login("alice", W8.PW), "Host", W8.HOST, "Referer",
                "https://app.test/page");
        assertNotNull(W8.cookieValue(referer, "naru_rt"));
    }

    // ---------------------------------------------------------------------------------------------- SEC-FLOW-013

    // SEC-FLOW-013 AC-7: HEADER reads only the Naru-Device header: a login carrying only a valid naru_device cookie is
    // a login without a device token (no exemption from the user-only key); the same token in Naru-Device is trusted
    @Test
    public void SEC_FLOW_013_AC7_headerModeReadsOnlyTheNaruDeviceHeader() throws Exception {
        f.user("alice", W8.PW);
        f.proxied = true;
        f.start(b -> b);
        String device = (String) f.loginBody("alice", W8.PW).get("deviceToken");
        for (int i = 0; i < 10; i++) {
            f.post("/auth/login", W8.login("alice", "guess-" + i), "X-Forwarded-For", "198.51.100." + i);
        }
        ClientResponse cookieOnly = f.post("/auth/login", W8.login("alice", W8.PW), "Cookie", "naru_device=" + device);
        assertEquals(cookieOnly.text(), 429, cookieOnly.status());
        ClientResponse header = f.post("/auth/login", W8.login("alice", W8.PW), "Naru-Device", device);
        assertEquals(header.text(), 200, header.status());
    }

    // ---------------------------------------------------------------------------------------------- SEC-FLOW-002

    // SEC-FLOW-002 AC-1: PROD with .allowInsecureTransport, a plain-text non-local login -> 200, not 403
    // SEC-FLOW-002 AC-4: COOKIE over plain text, a same-origin login whose cookies lack Secure -> a WARN
    @Test
    public void SEC_FLOW_002_AC1_AC4_insecureOptInLogsIn() throws Exception {
        f.user("alice", W8.PW);
        f.start("203.0.113.9", false, b -> b.allowInsecureTransport("TLS adapter pending")
                .transport(TokenTransport.COOKIE));
        ClientResponse r = f.post("/auth/login", W8.login("alice", W8.PW), "Host", W8.HOST, "Origin",
                "http://app.test");
        assertEquals(r.text(), 200, r.status());
        assertFalse(W8.setCookie(r, "naru_at").contains("Secure"));
        assertTrue(warned("secure"));
    }

    // ---------------------------------------------------------------------------------------------- FR-FLOW-009

    // FR-FLOW-009 AC-1: no Lockout: after many failures and 15 minutes, the correct password -> 200
    // FR-FLOW-009 AC-2: Lockout.after(5): five failures from five IPs, then the correct password from a sixth IP ->
    // the same 401 invalid_credentials after the floor; without a Lockout the same sequence -> 200
    // FR-FLOW-009 AC-3: a trusted device on a further IP logs the locked account in -> 200
    // FR-FLOW-009 AC-5: an admin DELETE /auth/admin/users/:id/lockout -> 204, then the correct login -> 200
    @Test
    public void FR_FLOW_009_AC1_AC5_lockoutThroughTheLoginEndpoint() throws Exception {
        W8 none = new W8();
        try {
            none.user("alice", W8.PW);
            none.proxied = true;
            none.start(b -> b);
            for (int i = 0; i < 12; i++) {
                none.post("/auth/login", W8.login("alice", "guess-" + i), "X-Forwarded-For", "198.51.100." + i);
            }
            none.clock.addAndGet(15 * 60_000L + 1);
            assertEquals(200, none.post("/auth/login", W8.login("alice", W8.PW)).status());
            for (int i = 1; i <= 5; i++) {
                none.post("/auth/login", W8.login("alice", "guess-" + i), "X-Forwarded-For", "192.0.2." + i);
            }
            assertEquals(200, none.post("/auth/login", W8.login("alice", W8.PW), "X-Forwarded-For", "192.0.2.6")
                    .status());
        } finally {
            none.stop();
        }
        String alice = f.user("alice", W8.PW);
        f.user("root", W8.PW, "admin");
        f.proxied = true;
        f.start(b -> b.lockout(Lockout.after(5).forMinutes(15)));
        String device = (String) f.loginBody("alice", W8.PW, "X-Forwarded-For", "192.0.2.100").get("deviceToken");
        for (int i = 1; i <= 5; i++) {
            ClientResponse r = f.post("/auth/login", W8.login("alice", "guess-" + i), "X-Forwarded-For", "192.0.2." + i);
            assertEquals(401, r.status());
        }
        long floor = f.auth.failureFloor().floorNanos();
        long t0 = System.nanoTime();
        ClientResponse locked = f.post("/auth/login", W8.login("alice", W8.PW), "X-Forwarded-For", "192.0.2.6");
        assertTrue(System.nanoTime() - t0 >= floor);
        assertEquals(locked.text(), 401, locked.status());
        assertEquals("invalid_credentials", W8.code(locked));
        assertFalse(locked.text().contains("accessToken"));
        ClientResponse trusted = f.post("/auth/login", W8.login("alice", W8.PW), "X-Forwarded-For", "192.0.2.7",
                "Naru-Device", device);
        assertEquals(trusted.text(), 200, trusted.status());
        // a fresh throttle window, then lock again and let an admin release it
        f.clock.addAndGet(16 * 60_000L);
        for (int i = 11; i <= 15; i++) {
            f.post("/auth/login", W8.login("alice", "guess-" + i), "X-Forwarded-For", "192.0.2." + i);
        }
        assertEquals(401, f.post("/auth/login", W8.login("alice", W8.PW), "X-Forwarded-For", "192.0.2.20").status());
        String root = (String) f.loginBody("root", W8.PW, "X-Forwarded-For", "192.0.2.30").get("accessToken");
        ClientResponse release = f.adapter.send(com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest
                .of("DELETE", "/auth/admin/users/" + alice + "/lockout").header("Authorization", "Bearer " + root));
        assertEquals(release.text(), 204, release.status());
        assertEquals(200, f.post("/auth/login", W8.login("alice", W8.PW), "X-Forwarded-For", "192.0.2.21").status());
    }

    // ---------------------------------------------------------------------------------------------- IR-TOKEN-001

    // IR-TOKEN-001 AC-4: the built-in Bearer authenticator accepts only access tokens (typ at+jwt) and yields the
    // token's sub as the principal id; a refresh token, or a token the configured codec cannot decode as an access
    // token, yields no principal
    @Test
    public void IR_TOKEN_001_AC4_bearerAcceptsOnlyAccessTokens() throws Exception {
        String alice = f.user("alice", W8.PW, "reader");
        f.start(b -> b);
        Map<String, Object> pair = f.loginBody("alice", W8.PW);
        ClientResponse ok = f.get("/api/x", "Authorization", "Bearer " + pair.get("accessToken"));
        assertEquals(ok.text(), "x:" + alice, ok.text());
        for (String bad : new String[] {(String) pair.get("refreshToken"), (String) pair.get("deviceToken"),
                "not-a-token"}) {
            ClientResponse r = f.get("/api/x", "Authorization", "Bearer " + bad);
            assertEquals(401, r.status());
            assertEquals("invalid_token", W8.code(r));
        }
    }

    /** The public types of one package of the API classes (a directory or a jar). */
    static List<Class<?>> publicTypes(String pkg) throws Exception {
        java.net.URL where = Auth.class.getProtectionDomain().getCodeSource().getLocation();
        java.io.File loc = new java.io.File(where.toURI());
        String dir = pkg.replace('.', '/') + "/";
        List<String> names = new ArrayList<>();
        if (loc.isDirectory()) {
            java.io.File[] files = new java.io.File(loc, dir).listFiles();
            for (java.io.File c : files == null ? new java.io.File[0] : files) {
                names.add(c.getName());
            }
        } else {
            try (java.util.jar.JarFile jar = new java.util.jar.JarFile(loc)) {
                for (java.util.Enumeration<java.util.jar.JarEntry> e = jar.entries(); e.hasMoreElements();) {
                    String n = e.nextElement().getName();
                    if (n.startsWith(dir) && n.indexOf('/', dir.length()) < 0) {
                        names.add(n.substring(dir.length()));
                    }
                }
            }
        }
        List<Class<?>> out = new ArrayList<>();
        for (String n : names) {
            if (n.endsWith(".class") && !n.contains("$") && !n.equals("package-info.class")) {
                Class<?> c = Class.forName(pkg + "." + n.substring(0, n.length() - 6));
                if (java.lang.reflect.Modifier.isPublic(c.getModifiers())) {
                    out.add(c);
                }
            }
        }
        return out;
    }

    // IR-TOKEN-001 AC-7: the spi package holds exactly one authenticator interface, Authenticator, whose single
    // abstract method takes a username String and a char[] password; no public type or Auth builder option accepts a
    // replacement for the Bearer or the API key authenticator
    @Test
    public void IR_TOKEN_001_AC7_onePublicAuthenticatorSeam() throws Exception {
        List<Class<?>> spi = publicTypes("com.snoworca.naruserve.utils.auth.spi");
        assertTrue(spi.toString(), spi.size() > 3);
        List<Class<?>> authenticators = new ArrayList<>();
        for (Class<?> c : spi) {
            if (c.getSimpleName().contains("Authenticator") || c.getSimpleName().contains("CredentialResolver")
                    || c.getSimpleName().contains("TokenVerifier")) {
                authenticators.add(c);
            }
        }
        assertEquals(java.util.Collections.<Class<?>>singletonList(com.snoworca.naruserve.utils.auth.spi.Authenticator.class),
                authenticators);
        List<java.lang.reflect.Method> abstracts = new ArrayList<>();
        for (java.lang.reflect.Method m : com.snoworca.naruserve.utils.auth.spi.Authenticator.class.getMethods()) {
            if (java.lang.reflect.Modifier.isAbstract(m.getModifiers())) {
                abstracts.add(m);
            }
        }
        assertEquals(1, abstracts.size());
        Class<?>[] params = abstracts.get(0).getParameterTypes();
        assertEquals(String.class, params[0]);
        assertEquals(char[].class, params[1]);
        for (java.lang.reflect.Method m : Auth.Builder.class.getMethods()) {
            for (Class<?> p : m.getParameterTypes()) {
                String n = p.getName();
                assertFalse(m.getName(), n.endsWith("CredentialResolver") || n.endsWith("TokenVerifier")
                        || n.contains("ApiKeyCredentials"));
            }
        }
        assertFalse(java.lang.reflect.Modifier.isPublic(ApiKeyCredentials.class.getModifiers()));
    }

    // review regression (FR-FLOW-004 statement, PasswordLogin.attempt contract): a throttle failure before the hash
    // step is a 503, not a 500, and its message stays out of the body
    @Test
    public void throttleFailureBeforeTheHashIs503() throws Exception {
        f.user("alice", W8.PW);
        final InMemoryLoginThrottle real = InMemoryLoginThrottle.builder().timeSource(f.clock::get).build();
        f.start(b -> b.loginThrottle(new com.snoworca.naruserve.utils.auth.spi.ForwardingLoginThrottle(real) {
            @Override
            public long retryAfter(String key, int max, long windowMillis) {
                throw new IllegalStateException("throttle table gone");
            }
        }));
        ClientResponse r = f.post("/auth/login", W8.login("alice", W8.PW));
        assertEquals(r.text(), 503, r.status());
        assertFalse(r.text().contains("throttle table gone"));
    }

    // review regression (IR-TOKEN-001 statement: a disabled user never logs in): a custom authenticator returning an
    // enabled copy of a user that is disabled in the store -> 401 invalid_credentials, judged on the stored row
    @Test
    public void customAuthenticatorAnswerIsJudgedOnTheStoredRow() throws Exception {
        final String carol = f.user("carol", W8.PW);
        final AuthUser enabledCopy = f.stored(carol);
        f.store.setEnabled(carol, false);
        f.start(b -> b.authenticator((name, password, ctx) -> enabledCopy));
        ClientResponse r = f.post("/auth/login", W8.login("carol", W8.PW));
        assertEquals(401, r.status());
        assertEquals("invalid_credentials", W8.code(r));
    }

    // review regression (REL-TOKEN-001 retry hint): a login whose hash step fails with an AuthUnavailableException
    // carrying a retry delay (as a busy or timed-out hash slot does) -> 503 with that Retry-After
    @Test
    public void unavailableHashKeepsRetryAfter() throws Exception {
        f.user("alice", W8.PW);
        final Pbkdf2PasswordHasher inner = new Pbkdf2PasswordHasher(1000);
        final AtomicBoolean busy = new AtomicBoolean();
        f.start(b -> b.passwordHasher(new PasswordHasher() {
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
                if (busy.get()) {
                    throw new AuthUnavailableException(AuthUnavailableException.SERVICE_UNAVAILABLE, "slots busy", 7L,
                            null);
                }
                return inner.verify(password, stored);
            }

            @Override
            public boolean needsRehash(String stored) {
                return false;
            }
        }));
        f.auth.passwords().prepare();
        busy.set(true);
        ClientResponse r = f.post("/auth/login", W8.login("alice", W8.PW));
        assertEquals(r.text(), 503, r.status());
        assertEquals("7", r.header("Retry-After"));
    }

    // review regression (FR-FLOW-004 AC-2 with a custom authenticator): a service account returned by a custom
    // Authenticator never logs in by password
    @Test
    public void customAuthenticatorCannotLogInAServiceAccount() throws Exception {
        final String svc = f.store.create(AuthUser.builder().username("svc").serviceAccount(true).build()).getId();
        f.start(b -> b.authenticator((name, password, ctx) -> f.stored(svc)));
        ClientResponse r = f.post("/auth/login", W8.login("svc", W8.PW));
        assertEquals(401, r.status());
        assertEquals("invalid_credentials", W8.code(r));
    }
}
