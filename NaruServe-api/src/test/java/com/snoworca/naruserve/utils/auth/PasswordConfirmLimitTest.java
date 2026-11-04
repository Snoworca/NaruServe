package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.testing.TestApp;
import com.snoworca.naruserve.utils.auth.user.AuthUser;
import com.snoworca.naruserve.utils.auth.user.AuthUsers;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

/**
 * The per-user current-password attempt limit shared by every confirmation (SEC-FLOW-015) through
 * {@code auth.users().checkPassword}, and the wipe of password buffers (SEC-AUTH-008 AC-3).
 */
public class PasswordConfirmLimitTest {

    private final AtomicLong clock = new AtomicLong(1_000_000L);

    private Auth auth() {
        return AuthTestSupport.builder().timeSource(clock::get).build();
    }

    private static boolean check(AuthUsers users, String id, String password) {
        return users.checkPassword(id, password.toCharArray());
    }

    private static AuthRejection limited(AuthUsers users, String id, String password) {
        try {
            check(users, id, password);
        } catch (AuthRejection r) {
            return r;
        }
        fail("the confirmation was not limited");
        return null;
    }

    // SEC-FLOW-015 AC-3: over the limit checkPassword throws AuthRejection 429 too_many_attempts with a retry time,
    // and through app.onError(auth.errors()) the answer is 429 JSON with Retry-After
    @Test
    public void SEC_FLOW_015_AC3_overTheLimitIs429WithRetryAfter() {
        Auth auth = auth();
        AuthUser u1 = auth.users().create("u1", "right-password".toCharArray());
        for (int i = 0; i < 5; i++) {
            assertFalse(check(auth.users(), u1.getId(), "wrong"));
        }
        AuthRejection r = limited(auth.users(), u1.getId(), "right-password");
        assertEquals(429, r.getStatus());
        assertEquals("too_many_attempts", r.getCode());
        assertNotNull(r.getRetryAfterSeconds());
        assertTrue(r.getRetryAfterSeconds() > 0 && r.getRetryAfterSeconds() <= 900);

        TestApp t = TestApp.create();
        Auth http = auth();
        final AuthUser u = http.users().create("u2", "right-password".toCharArray());
        http.install(t.app());
        t.app().post("/confirm", (req, res) -> res.send(String.valueOf(
                http.users().checkPassword(u.getId(), "wrong".toCharArray()))));
        t.app().onError(http.errors());
        for (int i = 0; i < 5; i++) {
            assertEquals("false", t.send("POST", "/confirm", AuthTestSupport.HEADER, u.getId() + ":0").text());
        }
        ClientResponse resp = t.send("POST", "/confirm", AuthTestSupport.HEADER, u.getId() + ":0");
        assertEquals(429, resp.status());
        assertEquals("too_many_attempts", AuthTestSupport.code(resp));
        assertNotNull(resp.header("Retry-After"));
        assertTrue(Long.parseLong(resp.header("Retry-After")) > 0);
        // the block ends with the window
        clock.addAndGet(15 * 60_000L);
        assertEquals("false", t.send("POST", "/confirm", AuthTestSupport.HEADER, u.getId() + ":0").text());
    }

    // SEC-FLOW-015 AC-4: a successful confirmation does not reset the counter
    @Test
    public void SEC_FLOW_015_AC4_successDoesNotReset() {
        Auth auth = auth();
        AuthUser u1 = auth.users().create("u1", "right-password".toCharArray());
        for (int i = 0; i < 3; i++) {
            assertFalse(check(auth.users(), u1.getId(), "wrong"));
        }
        assertTrue(check(auth.users(), u1.getId(), "right-password"));
        assertFalse(check(auth.users(), u1.getId(), "wrong"));
        assertFalse(check(auth.users(), u1.getId(), "wrong"));
        assertEquals(429, limited(auth.users(), u1.getId(), "right-password").getStatus());
    }

    // SEC-FLOW-015 AC-5: the key is the user id only: failures of u1 do not limit u2, and failures of u1 from
    // different client addresses add up
    @Test
    public void SEC_FLOW_015_AC5_keyIsTheUserIdOnly() {
        Auth direct = auth();
        AuthUser a = direct.users().create("u1", "pw-one-one-1".toCharArray());
        AuthUser b = direct.users().create("u2", "pw-two-two-2".toCharArray());
        for (int i = 0; i < 5; i++) {
            assertFalse(check(direct.users(), a.getId(), "wrong"));
        }
        assertEquals(429, limited(direct.users(), a.getId(), "pw-one-one-1").getStatus());
        assertTrue(check(direct.users(), b.getId(), "pw-two-two-2"));

        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter("10.0.0.1", true);
        TestApp t = TestApp.create(adapter);
        t.app().trustProxyHops(1);
        Auth auth = AuthTestSupport.builder().timeSource(clock::get).build();
        final AuthUser u1 = auth.users().create("u1", "pw-one-one-1".toCharArray());
        auth.install(t.app());
        final StringBuilder seen = new StringBuilder();
        t.app().post("/confirm", (req, res) -> {
            seen.append(req.remoteAddress()).append(' ');
            res.send(String.valueOf(auth.users().checkPassword(u1.getId(), "wrong".toCharArray())));
        });
        t.app().onError(auth.errors());
        t.start();
        for (int i = 0; i < 5; i++) {
            ClientResponse r = adapter.send(ClientRequest.of("POST", "/confirm")
                    .header(AuthTestSupport.HEADER, u1.getId() + ":0").header("X-Forwarded-For", "203.0.113." + i));
            assertEquals(r.text(), "false", r.text());
        }
        ClientResponse sixth = adapter.send(ClientRequest.of("POST", "/confirm")
                .header(AuthTestSupport.HEADER, u1.getId() + ":0").header("X-Forwarded-For", "198.51.100.77"));
        assertEquals(429, sixth.status());
        assertTrue(seen.toString(), seen.toString().contains("203.0.113.0") && seen.toString().contains("203.0.113.4"));
    }

    // SEC-AUTH-008 AC-3: the char[] passwords passed to auth APIs hold only '\0' after the call returns
    @Test
    public void SEC_AUTH_008_AC3_passwordBuffersAreWiped() {
        Auth auth = AuthTestSupport.builder(new AuthTestSupport.TestCredentials(), new RecordingLogger()).build();
        char[] created = "secret-pw".toCharArray();
        AuthUser u = auth.users().create("alice", created);
        assertWiped(created);
        char[] right = "secret-pw".toCharArray();
        assertTrue(auth.users().checkPassword(u.getId(), right));
        assertWiped(right);
        char[] wrong = "nope".toCharArray();
        assertFalse(auth.users().checkPassword(u.getId(), wrong));
        assertWiped(wrong);
        char[] unknown = "nope".toCharArray();
        assertFalse(auth.users().checkPassword("no-such-id", unknown));
        assertWiped(unknown);
        char[] withInit = "other-pw".toCharArray();
        auth.users().create("bob", withInit, u2 -> u2.toBuilder().attribute("team", "a").build());
        assertWiped(withInit);
        char[] refused = "pw-pw-pw-pw".toCharArray();
        try {
            auth.users().create("alice", refused);
            fail("a duplicate name was created");
        } catch (com.snoworca.naruserve.utils.auth.user.DuplicateUserException expected) {
            // the name is taken
        }
        assertWiped(refused);
    }

    private static void assertWiped(char[] buffer) {
        for (char c : buffer) {
            assertEquals('\0', c);
        }
    }
}
