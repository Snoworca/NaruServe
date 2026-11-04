package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.testing.TestApp;
import com.snoworca.naruserve.utils.auth.internal.AuthArgumentException;
import com.snoworca.naruserve.utils.auth.internal.Doctor;
import com.snoworca.naruserve.utils.auth.user.AuthUser;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Test;

/** The code findings of the twelfth junior study are answered with the fix (OPS-AUTH-024). */
public class JuniorRound12Test {

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

    private static AuthConfigException refused(Auth.Builder b) {
        try {
            b.build();
        } catch (AuthConfigException e) {
            return e;
        }
        throw new AssertionError("built");
    }

    // OPS-AUTH-024 AC-1: a before-event failure names AuthRejection, and register logs no second ERROR with a short path
    @Test
    public void OPS_AUTH_024_AC1_beforeEventFailureNamesAuthRejection() {
        W8 f = fixture();
        f.start(b -> b.register(true).events(new AuthEvents() {
            @Override
            public void beforeUserCreate(AuthUser user, AuthEventContext ctx) {
                throw new IllegalStateException("user is banned");
            }
        }));
        ClientResponse r = f.post("/auth/register", W8.login("zed", "long-password-1"));
        assertEquals(r.text(), 503, r.status());
        List<String> event = RecordingLogger.matching(f.logger.lines, "(?s)ERROR auth event beforeUserCreate failed.*");
        assertEquals(f.logger.lines.toString(), 1, event.size());
        assertTrue(event.get(0), event.get(0).contains("throw new AuthRejection("));
        assertTrue(f.logger.lines.toString(), RecordingLogger.matching(f.logger.lines, "(?s).*POST /register is unavailable.*").isEmpty());
    }

    // OPS-AUTH-024 AC-2: a query or fragment in a permit path is refused
    @Test
    public void OPS_AUTH_024_AC2_permitWithQueryIsRefused() {
        for (String p : new String[] {"/health2?x=1", "/health#top"}) {
            String m = refused(Auth.builder(AuthTestSupport.SECRET).permit(p)).getMessage();
            assertTrue(m, m.contains("no query or fragment"));
        }
    }

    // OPS-AUTH-024 AC-3: the route-pattern refusal suggests the prefix of the given path
    @Test
    public void OPS_AUTH_024_AC3_patternRefusalEchoesThePrefix() {
        String m = refused(Auth.builder(AuthTestSupport.SECRET).permit("/public/*")).getMessage();
        assertTrue(m, m.contains(".permitPrefix(\"/public\")"));
    }

    // OPS-AUTH-024 AC-4: a Bearer refresh token is named
    @Test
    public void OPS_AUTH_024_AC4_bearerRefreshTokenIsNamed() throws Exception {
        W8 f = fixture();
        f.start(b -> b);
        f.user("bob", W8.PW, "reader");
        String rt = String.valueOf(f.loginBody("bob", W8.PW).get("refreshToken"));
        String m = message(f.get("/api/x", "Authorization", "Bearer " + rt));
        assertTrue(m, m.contains("this is a refresh token"));
    }

    // OPS-AUTH-024 AC-5: a near miss of an exact permit is named
    @Test
    public void OPS_AUTH_024_AC5_nearMissOfPermitIsNamed() {
        W8 f = fixture();
        f.start(b -> b.permit("/health"));
        String hint = "; .permit(\"/health\") makes only GET and HEAD of exactly /health public";
        assertEquals("authentication is required" + hint, message(f.get("/health/")));
        assertEquals("authentication is required" + hint, message(f.post("/health", "{}")));
    }

    // OPS-AUTH-024 AC-6: POST /auth/setup without an open setup says so
    @Test
    public void OPS_AUTH_024_AC6_closedSetupIsNamed() {
        W8 f = fixture();
        f.start(b -> b);
        ClientResponse r = f.post("/auth/setup", "{\"token\":\"x\",\"username\":\"a\",\"password\":\"long-password-1\"}");
        assertEquals(r.text(), 404, r.status());
        assertTrue(message(r), message(r).startsWith("no setup is open"));
    }

    // OPS-AUTH-024 AC-7: a second Auth on one app names the first
    @Test
    public void OPS_AUTH_024_AC7_secondAuthIsNamed() {
        TestApp t = TestApp.create();
        Auth.builder(AuthTestSupport.SECRET).logger(new RecordingLogger()).build().install(t.app());
        try {
            Auth.builder(AuthTestSupport.SECRET).logger(new RecordingLogger()).build().install(t.app());
            fail("installed twice");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("another Auth is already installed on this app"));
        }
    }

    // OPS-AUTH-024 AC-8: a refresh lifetime shorter than the access lifetime is a doctor WARN
    @Test
    public void OPS_AUTH_024_AC8_shortRefreshTtlIsWarned() {
        Auth auth = Auth.builder(AuthTestSupport.SECRET).logger(new RecordingLogger()).accessTtl(3_600_000L)
                .refreshTtl(60_000L).build();
        TestApp t = TestApp.create();
        auth.install(t.app());
        List<String> warns = new ArrayList<>();
        for (Doctor.Finding x : auth.doctor(t.app()).findings()) {
            if (x.getLevel() == Doctor.Level.WARN && x.getItem().equals("token lifetimes")) {
                warns.add(x.getMessage());
            }
        }
        assertEquals(warns.toString(), 1, warns.size());
    }

    // OPS-AUTH-024 AC-9: a stale or expired API key says so once its secret matched; the own-key 404 hints the prefix
    // only for a value of prefix length
    @Test
    public void OPS_AUTH_024_AC9_staleKeyIsNamed() throws Exception {
        W8 f = fixture();
        f.start(b -> b.apiKeys(new AdminFixture.ListKeys()));
        String id = f.user("bob", W8.PW, "reader");
        IssuedApiKeyHolder k = new IssuedApiKeyHolder(f.auth.apiKeys().issue(id, "ci", null, "posts:read"));
        assertEquals(200, f.get("/api/x", "Authorization", "ApiKey " + k.key).status());
        f.auth.userStore().bumpTokenVersion(id);
        f.auth.principalCache().invalidate(id);
        String m = message(f.get("/api/x", "Authorization", "ApiKey " + k.key));
        assertTrue(m, m.contains("stale") && m.contains("issue a new key"));
        String t = String.valueOf(f.loginBody("bob", W8.PW).get("accessToken"));
        String unknown = "AAAAAAAAAAAAAAAAAAAAAA"; // an id-shaped value that is not one of bob's keys
        ClientResponse del = f.adapter.send(ClientRequest.of("DELETE", "/auth/me/apikeys/" + unknown)
                .header("Authorization", "Bearer " + t));
        assertEquals(404, del.status());
        assertEquals("no API key with id " + unknown + " among your keys", message(del));
    }

    // OPS-AUTH-024 AC-10: the mutator refusal names admin A4 for roles and the enabled flag
    @Test
    public void OPS_AUTH_024_AC10_mutatorRefusalNamesA4() throws Exception {
        W8 f = fixture();
        f.start(b -> b);
        String id = f.user("bob", W8.PW, "reader");
        try {
            f.auth.users().update(id, u -> u.toBuilder().roles("writer").build());
            fail("roles changed");
        } catch (AuthArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("PATCH /auth/admin/users/:id"));
        }
    }

    /** The id and the full key of an issued key. */
    private static final class IssuedApiKeyHolder {
        final String id;
        final String key;

        IssuedApiKeyHolder(com.snoworca.naruserve.utils.auth.apikey.IssuedApiKey k) {
            this.id = k.getKey().getId();
            this.key = k.getPlainKey();
        }
    }
}
