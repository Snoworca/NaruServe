package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.RecordingLogger;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import org.junit.After;
import org.junit.Test;

/**
 * The fixes of the junior-usability study: the insecure_transport refusal explains itself (OPS-AUTH-004), a refused
 * new password says why (FR-TOKEN-004 AC-8), and .user passwords follow the policy (FR-AUTH-004 AC-7).
 */
public class JuniorFixesTest {

    private static final String REFUSED = "was refused with 403 insecure_transport";

    private final List<W8> fixtures = new ArrayList<>();
    private final List<AdminFixture> admins = new ArrayList<>();

    @After
    public void stop() {
        for (W8 f : fixtures) {
            f.stop();
        }
        AdminFixture.stopAll(admins);
    }

    private W8 started(String peer, boolean secure, UnaryOperator<Auth.Builder> config) {
        W8 f = new W8();
        fixtures.add(f);
        f.start(peer, secure, config);
        return f;
    }

    private static List<String> refusalWarns(W8 f) {
        return RecordingLogger.matching(f.logger.lines, "(?s)WARN .*" + REFUSED + ".*");
    }

    // OPS-AUTH-004 AC-1: PROD, a plain request from 203.0.113.9: 403 insecure_transport each time and exactly one WARN
    // naming the request and the three ways out
    @Test
    public void OPS_AUTH_004_AC1_refusalLogsOneExplainingWarn() {
        W8 f = started("203.0.113.9", false, b -> b);
        for (int i = 0; i < 3; i++) {
            ClientResponse r = f.post("/auth/login", W8.login("alice", "correct horse battery"));
            assertEquals(r.text(), 403, r.status());
            assertEquals("insecure_transport", W8.code(r));
        }
        List<String> warns = refusalWarns(f);
        assertEquals(f.logger.lines.toString(), 1, warns.size());
        String w = warns.get(0);
        for (String part : new String[] {"POST /auth/login", "trustProxyHops", ".allowInsecureTransport(",
                "AuthMode.DEV"}) {
            assertTrue(w, w.contains(part));
        }
    }

    // OPS-AUTH-004 AC-2: no such WARN for a local plain request, a secure request, DEV or .allowInsecureTransport; the
    // WARN never carries a header value or credential
    @Test
    public void OPS_AUTH_004_AC2_noWarnWhenNotRefused() {
        W8 local = started("127.0.0.1", false, b -> b);
        W8 secure = started("203.0.113.9", true, b -> b);
        W8 dev = started("203.0.113.9", false, b -> b.mode(AuthMode.DEV));
        W8 lan = started("203.0.113.9", false, b -> b.allowInsecureTransport("LAN demo without TLS"));
        for (W8 f : new W8[] {local, secure, dev, lan}) {
            f.post("/auth/login", W8.login("alice", "correct horse battery"));
            assertEquals(f.logger.lines.toString(), 0, refusalWarns(f).size());
        }
        W8 refused = started("203.0.113.9", false, b -> b);
        refused.post("/auth/login", W8.login("alice", "SECRET-PW-XYZ"), "Authorization", "Bearer SECRET-TOKEN-XYZ");
        String w = refusalWarns(refused).get(0);
        assertFalse(w, w.contains("SECRET-PW-XYZ") || w.contains("SECRET-TOKEN-XYZ"));
    }

    // FR-TOKEN-004 AC-8: register, the self-service change and admin create answer 422 UNPROCESSABLE_ENTITY with the
    // rule in the message and never the password
    @Test
    public void FR_TOKEN_004_AC8_policyRefusalSaysWhy() throws Exception {
        String rule = "the password must have at least 8 characters";
        W8 f = started("127.0.0.1", true, b -> b.register(true));
        f.user("alice", W8.PW, "reader");
        ClientResponse reg = f.post("/auth/register", "{\"username\":\"neo\",\"password\":\"xyzzy\"}");
        assertEquals(reg.text(), 422, reg.status());
        assertEquals("UNPROCESSABLE_ENTITY", W8.code(reg));
        assertTrue(reg.text(), reg.text().contains(rule));
        assertFalse(reg.text(), reg.text().contains("xyzzy"));
        String at = (String) f.loginBody("alice", W8.PW).get("accessToken");
        ClientResponse change = f.post("/auth/password",
                "{\"currentPassword\":\"" + W8.PW + "\",\"newPassword\":\"xyzzy\"}", "Authorization", "Bearer " + at);
        assertEquals(change.text(), 422, change.status());
        assertTrue(change.text(), change.text().contains(rule));

        AdminFixture a = new AdminFixture();
        admins.add(a);
        a.start();
        String root = a.user("root", "admin");
        ClientResponse create = a.call("POST", "/auth/admin/users", root,
                "{\"username\":\"bob\",\"password\":\"xyzzy\",\"roles\":[\"reader\"]}");
        assertEquals(create.text(), 422, create.status());
        assertTrue(create.text(), create.text().contains(rule));
        assertFalse(create.text(), create.text().contains("xyzzy"));
    }

    private static void saysWhy(String path, ClientResponse r, String rule, String password) {
        assertEquals(path + " " + r.text(), 422, r.status());
        assertEquals(path, "UNPROCESSABLE_ENTITY", W8.code(r));
        assertTrue(path + " " + r.text(), r.text().contains(rule));
        assertFalse(path + " " + r.text(), r.text().contains(password));
    }

    // FR-TOKEN-004 AC-8: each HTTP set-password path answers 422 UNPROCESSABLE_ENTITY with the rule and never the
    // password: POST /auth/setup and admin A4 for a policy refusal, and all five (register, setup, A2, A4,
    // POST /auth/password) for a breach-check refusal
    @Test
    public void FR_TOKEN_004_AC8_everyHttpPathSaysWhy() throws Exception {
        String shortRule = "the password must have at least 8 characters";
        String breachRule = "the password appears in a list of breached passwords; choose another";
        String breached = "Password123!";
        W8 f = new W8();
        fixtures.add(f);
        f.user("root", W8.PW, "admin");
        String target = f.user("target", W8.PW, "reader");
        f.start("127.0.0.1", true, b -> b.register(true)
                .breachedPasswordChecker(pw -> breached.equals(new String(pw))));
        String root = "Bearer " + f.loginBody("root", W8.PW).get("accessToken");
        String own = "Bearer " + f.loginBody("target", W8.PW).get("accessToken");
        saysWhy("A4", f.adapter.send(ClientRequest.json("PATCH", "/auth/admin/users/" + target,
                "{\"password\":\"xyzzy\"}").header("Authorization", root)), shortRule, "xyzzy");
        saysWhy("A4", f.adapter.send(ClientRequest.json("PATCH", "/auth/admin/users/" + target,
                "{\"password\":\"" + breached + "\"}").header("Authorization", root)), breachRule, breached);
        saysWhy("A2", f.post("/auth/admin/users", "{\"username\":\"made\",\"password\":\"" + breached + "\"}",
                "Authorization", root), breachRule, breached);
        saysWhy("register", f.post("/auth/register", W8.login("neo", breached)), breachRule, breached);
        saysWhy("password", f.post("/auth/password", "{\"currentPassword\":\"" + W8.PW + "\",\"newPassword\":\""
                + breached + "\"}", "Authorization", own), breachRule, breached);

        W8 s = started("127.0.0.1", false, b -> b.recovery(true).user("admin", null, "admin")
                .breachedPasswordChecker(pw -> breached.equals(new String(pw))));
        for (String[] c : new String[][] {{"xyzzy", shortRule}, {breached, breachRule}}) {
            saysWhy("setup", s.post("/auth/setup", "{\"token\":\"" + s.auth.pendingSetupToken()
                    + "\",\"username\":\"admin\",\"password\":\"" + c[0] + "\"}"), c[1], c[0]);
        }
    }

    // FR-AUTH-004 AC-7: PROD refuses a .user password that breaks the policy (naming the .user and the rule, not the
    // password); DEV builds and WARNs once per such .user; a custom policy applies too
    @Test
    public void FR_AUTH_004_AC7_seedPasswordsFollowThePolicy() {
        try {
            AuthTestSupport.builder().user("hank", "xyzzy", "reader").build();
            fail("PROD accepted a 5-character .user password");
        } catch (AuthConfigException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(
                    ".user(\"hank\") password: the password must have at least 8 characters"));
            assertFalse(e.getMessage(), e.getMessage().contains("xyzzy"));
        }
        try {
            AuthTestSupport.builder().passwordPolicy(PasswordPolicy.length(20, 128))
                    .user("ivy", "sixteen-chars-ok", "reader").build();
            fail("PROD accepted a .user password below the custom policy");
        } catch (AuthConfigException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(".user(\"ivy\") password: "));
        }
        RecordingLogger log = new RecordingLogger();
        AuthTestSupport.builder(new AuthTestSupport.TestCredentials(), log).mode(AuthMode.DEV)
                .user("hank", "xyzzy", "reader").user("ok", "long-enough-password", "reader").build();
        List<String> warns = RecordingLogger.matching(log.lines, "(?s)WARN .*\\.user\\(\"[a-z]+\"\\) password: .*");
        assertEquals(log.lines.toString(), 1, warns.size());
        assertTrue(warns.get(0), warns.get(0).contains(".user(\"hank\")"));
        assertFalse(warns.get(0), warns.get(0).contains("xyzzy"));
    }

    // FR-AUTH-004 AC-7: a .user password PROD refuses is not hashed
    @Test
    public void FR_AUTH_004_AC7_aRefusedSeedPasswordIsNotHashed() {
        AuthBootstrapTest.CountingHasher hasher = new AuthBootstrapTest.CountingHasher();
        try {
            AuthTestSupport.builder().passwordHasher(hasher).passwordPolicy(PasswordPolicy.length(20, 128))
                    .user("ivy", "first-password-1", "reader").build();
            fail("PROD accepted a .user password below the policy");
        } catch (AuthConfigException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(".user(\"ivy\") password: "));
        }
        assertEquals(0, hasher.hashes.get());
    }

    // FR-AUTH-004 AC-7: the BreachedPasswordChecker is not consulted for .user passwords, in build() or at start
    @Test
    public void FR_AUTH_004_AC7_seedPasswordsSkipTheBreachChecker() {
        final AtomicInteger consulted = new AtomicInteger();
        W8 f = started("127.0.0.1", true, b -> b.breachedPasswordChecker(pw -> {
            consulted.incrementAndGet();
            return true;
        }).user("hank", "long-enough-password", "reader"));
        assertTrue(f.auth.users().toString(), f.store.findByUsername("hank") != null);
        assertEquals(0, consulted.get());
    }
}
