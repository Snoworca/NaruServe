package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.utils.auth.apikey.ApiKeyStatus;
import com.snoworca.naruserve.utils.auth.apikey.InMemoryApiKeyStore;
import com.snoworca.naruserve.utils.auth.testing.AuthTesting;
import java.util.Map;
import org.junit.After;
import org.junit.Test;

/** The single ver++ of SEC-IAM-003 through the wave 8 endpoints: which changes raise it and what it revokes. */
public class Wave8RevocationTest {

    private final W8 f = new W8();

    @After
    public void stop() {
        f.stop();
    }

    private long ver(String id) throws Exception {
        return f.stored(id).getTokenVersion();
    }

    private ClientResponse patch(String rootToken, String id, String json) {
        return f.adapter.send(ClientRequest.json("PATCH", "/auth/admin/users/" + id, json)
                .header("Authorization", "Bearer " + rootToken));
    }

    // SEC-IAM-003 AC-1: each of POST /auth/logout?all=true, a successful password change, an admin password reset,
    // an admin disable and recovery of an existing user raises ver by exactly 1 (the stored row before and after)
    @Test
    public void SEC_IAM_003_AC1_eachRevokingChangeRaisesVerOnce() throws Exception {
        f.user("root", W8.PW, "admin");
        String a = f.user("a", W8.PW, "reader");
        String b = f.user("b", W8.PW, "reader");
        String c = f.user("c", W8.PW, "reader");
        String d = f.user("d", W8.PW, "reader");
        String e = f.user("e", W8.PW, "reader");
        f.start("127.0.0.1", false, x -> x.recovery(true));
        String root = (String) f.loginBody("root", W8.PW).get("accessToken");

        long v = ver(a);
        String at = (String) f.loginBody("a", W8.PW).get("accessToken");
        assertEquals(204, f.post("/auth/logout?all=true", null, "Authorization", "Bearer " + at).status());
        assertEquals("logout?all=true", v + 1, ver(a));

        v = ver(b);
        String bt = (String) f.loginBody("b", W8.PW).get("accessToken");
        ClientResponse changed = f.post("/auth/password", "{\"currentPassword\":\"" + W8.PW
                + "\",\"newPassword\":\"a-new-password-1\"}", "Authorization", "Bearer " + bt);
        assertEquals(changed.text(), 200, changed.status());
        assertEquals("password change", v + 1, ver(b));

        v = ver(c);
        ClientResponse reset = patch(root, c, "{\"password\":\"reset-password-1\"}");
        assertEquals(reset.text(), 200, reset.status());
        assertEquals("admin reset", v + 1, ver(c));

        v = ver(d);
        ClientResponse disable = patch(root, d, "{\"enabled\":false}");
        assertEquals(disable.text(), 200, disable.status());
        assertEquals("admin disable", v + 1, ver(d));

        v = ver(e);
        ClientResponse recovered = f.post("/auth/setup", "{\"token\":\"" + f.auth.pendingSetupToken()
                + "\",\"username\":\"e\",\"password\":\"recovered-password-1\"}");
        assertEquals(recovered.text(), 200, recovered.status());
        assertEquals("recovery of an existing user", v + 1, ver(e));
    }

    // SEC-IAM-003 AC-3: after one ver++ (logout of all devices) the user's earlier access token is 401, its refresh
    // token is refused, its device token is no longer trusted and its earlier API key is 401 invalid_api_key and
    // listed as stale while the off-request stale-key deletion is held (an executor that runs no queued task)
    @Test
    public void SEC_IAM_003_AC3_oneBumpRevokesEveryCredential() throws Exception {
        String alice = f.user("alice", W8.PW, "reader");
        f.start(x -> x.apiKeys(new InMemoryApiKeyStore()));
        f.auth.apiKeyOwners().deletions(job -> { }); // the off-request stale-key deletion is held (the AC's setup)
        Map<String, Object> old = f.loginBody("alice", W8.PW);
        String key = AuthTesting.apiKeyFor(f.auth, alice, "posts:read");
        String oldAt = (String) old.get("accessToken");
        assertEquals(200, f.get("/api/x", "Authorization", "Bearer " + oldAt).status());
        assertEquals(200, f.get("/api/x", "Authorization", "ApiKey " + key).status());
        String now = (String) f.loginBody("alice", W8.PW).get("accessToken");
        assertEquals(204, f.post("/auth/logout?all=true", null, "Authorization", "Bearer " + now).status());

        ClientResponse access = f.get("/api/x", "Authorization", "Bearer " + oldAt);
        assertEquals(401, access.status());
        assertEquals("invalid_token", W8.code(access));
        try {
            f.auth.tokens().refresh((String) old.get("refreshToken"));
            fail("the old refresh token still refreshed");
        } catch (AuthRejection expected) {
            assertEquals("invalid_refresh_token", expected.getCode());
        }
        PasswordLogin.Outcome o = Wave7LoginTest.attempt(f.auth.passwordLogin(), "alice", W8.PW, "10.7.0.1",
                (String) old.get("deviceToken"));
        assertEquals(PasswordLogin.Kind.SUCCESS, o.kind);
        assertFalse("the old device token is not trusted", o.trustedDevice);
        ClientResponse k = f.get("/api/x", "Authorization", "ApiKey " + key);
        assertEquals(401, k.status());
        assertEquals("invalid_api_key", W8.code(k));
        assertEquals(ApiKeyStatus.STALE, f.auth.apiKeys().list(alice).get(0).getStatus());
        assertTrue(f.auth.apiKeys().list(alice).size() == 1);
    }
}
