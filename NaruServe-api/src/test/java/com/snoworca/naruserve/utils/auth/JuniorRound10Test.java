package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.utils.auth.internal.AuthArgumentException;
import com.snoworca.naruserve.utils.auth.user.FileUserStore;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** The code findings of the tenth junior study are answered with the fix (OPS-AUTH-022). */
public class JuniorRound10Test {

    private static final String KEY = "nk_aBcd1234_0123456789abcdef0123456789abcdef";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final List<W8> fixtures = new ArrayList<>();

    @After
    public void stop() {
        for (W8 f : fixtures) {
            f.stop();
        }
    }

    private W8 fixture() {
        W8 f = new W8();
        fixtures.add(f);
        return f;
    }

    private static String message(ClientResponse r) {
        return String.valueOf(((Map<?, ?>) W8.object(r).get("error")).get("message"));
    }

    // OPS-AUTH-022 AC-1: with API keys off, an ApiKey credential names .apiKeys(store)
    @Test
    public void OPS_AUTH_022_AC1_apiKeysOffIsNamed() {
        W8 f = fixture();
        f.start(b -> b);
        ClientResponse r = f.get("/api/x", "Authorization", "ApiKey " + KEY);
        assertEquals(401, r.status());
        assertTrue(message(r), message(r).contains("API keys are off") && message(r).contains(".apiKeys("));
    }

    // OPS-AUTH-022 AC-2: with API keys on, a key without the scheme or with another scheme names the scheme
    @Test
    public void OPS_AUTH_022_AC2_keyWithoutSchemeNamesTheScheme() {
        W8 f = fixture();
        f.start(b -> b.apiKeys(new AdminFixture.ListKeys()));
        for (String h : new String[] {KEY, "Api-Key " + KEY}) {
            ClientResponse r = f.get("/api/x", "Authorization", h);
            assertEquals(h, 401, r.status());
            assertTrue(message(r), message(r).contains("an API key goes in the Authorization header as ApiKey <key>"));
        }
    }

    // OPS-AUTH-022 AC-3: COOKIE mode names the cookie when a Bearer token arrives
    @Test
    public void OPS_AUTH_022_AC3_cookieModeNamesTheCookie() {
        W8 f = fixture();
        f.start(b -> b.transport(TokenTransport.COOKIE));
        String m = message(f.get("/api/x", "Authorization", "Bearer abc"));
        assertTrue(m, m.contains("naru_at cookie") && m.contains("TokenTransport.COOKIE"));
    }

    // OPS-AUTH-022 AC-4: an undeclared role given through auth.users().create logs one WARN per role
    @Test
    public void OPS_AUTH_022_AC4_undeclaredRoleFromUsersFacadeIsWarned() {
        W8 f = fixture();
        f.start(b -> b);
        f.auth.users().create("morpheus", "long-password-1".toCharArray(), "superuser");
        f.auth.users().create("neo", "long-password-1".toCharArray(), "superuser", "reader");
        List<String> warns = RecordingLogger.matching(f.logger.lines, "(?s)WARN .*'superuser'.*");
        assertEquals(f.logger.lines.toString(), 1, warns.size());
        assertTrue(RecordingLogger.matching(f.logger.lines, "(?s)WARN .*'reader'.*").isEmpty());
    }

    // OPS-AUTH-022 AC-5: a COOKIE login without Origin or Referer says so
    @Test
    public void OPS_AUTH_022_AC5_missingOriginIsNamed() throws Exception {
        W8 f = fixture();
        f.start(b -> b.transport(TokenTransport.COOKIE));
        f.user("bob", W8.PW, "reader");
        ClientResponse r = f.post("/auth/login", W8.login("bob", W8.PW));
        assertEquals(403, r.status());
        assertEquals("csrf_failed", W8.code(r));
        assertTrue(message(r), message(r).startsWith("no Origin or Referer header"));
        ClientResponse evil = f.post("/auth/login", W8.login("bob", W8.PW), "Origin", W8.EVIL);
        assertEquals("the Origin of this request may not receive auth cookies", message(evil));
    }

    // OPS-AUTH-022 AC-6: an access token sent to refresh is named
    @Test
    public void OPS_AUTH_022_AC6_accessTokenOnRefreshIsNamed() throws Exception {
        W8 f = fixture();
        f.start(b -> b);
        f.user("bob", W8.PW, "reader");
        String at = String.valueOf(f.loginBody("bob", W8.PW).get("accessToken"));
        ClientResponse r = f.post("/auth/refresh", "{\"refreshToken\":\"" + at + "\"}");
        assertEquals(401, r.status());
        assertEquals("invalid_refresh_token", W8.code(r));
        assertTrue(message(r), message(r).startsWith("the refresh token is not valid") && message(r).contains("access token"));
    }

    // OPS-AUTH-022 AC-7: a past expiresAt names its unit
    @Test
    public void OPS_AUTH_022_AC7_expiresAtNamesItsUnit() throws Exception {
        W8 f = fixture();
        f.start(b -> b.apiKeys(new AdminFixture.ListKeys()));
        String id = f.user("bob", W8.PW, "reader");
        try {
            f.auth.apiKeys().issue(id, "bot", 30L, "posts:read");
            fail("issued");
        } catch (AuthArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("epoch milliseconds"));
        }
    }

    // OPS-AUTH-022 AC-8: a FileUserStore over an empty file or a directory says which
    @Test
    public void OPS_AUTH_022_AC8_emptyFileAndDirectoryAreNamed() throws Exception {
        File empty = tmp.newFile("users.json");
        try {
            new FileUserStore(empty, AuthTestSupport.FILE_KEY);
            fail("opened an empty file");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("is empty"));
        }
        File dir = tmp.newFolder("users-dir.json");
        try {
            new FileUserStore(dir, AuthTestSupport.FILE_KEY);
            fail("opened a directory");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("is a directory"));
        }
    }

    // OPS-AUTH-022 AC-1 (partial): with API keys off, a bare nk_ value or one under another scheme says so too; a Bearer
    // nk_ value does not, so it is not asserted here (docs/analysis/verify-0.11.0/batch-10.md, PRODUCT-GAP)
    @Test
    public void OPS_AUTH_022_AC1_nkFormWithKeysOffIsNamed() {
        W8 f = fixture();
        f.start(b -> b);
        for (String h : new String[] {KEY, "Token " + KEY}) {
            ClientResponse r = f.get("/api/x", "Authorization", h);
            assertEquals(h, 401, r.status());
            assertTrue(h + ": " + message(r), message(r).contains("API keys are off") && message(r).contains(".apiKeys(store)"));
        }
    }
}
