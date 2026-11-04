package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.testing.TestApp;
import com.snoworca.naruserve.utils.auth.internal.AccessTokens;
import com.snoworca.naruserve.utils.auth.internal.TokenKeys;
import com.snoworca.naruserve.utils.auth.jwt.Jwt;
import com.snoworca.naruserve.utils.auth.jwt.JwtClaims;
import com.snoworca.naruserve.utils.auth.rbac.RolePolicy;
import com.snoworca.naruserve.utils.auth.user.AuthUser;
import com.snoworca.naruserve.utils.auth.user.DuplicateUserException;
import com.snoworca.naruserve.utils.auth.user.InMemoryUserStore;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The auth middleware with the real HS256 access-token verifier (SEC-TOKEN-003) behind the transport reading: the
 * representative paths that were first checked with a test verifier (SEC-FLOW-004, SEC-AUTH-005, IR-AUTH-002) and
 * the protectOnly range (SEC-AUTH-006) with real, expired and forged tokens.
 */
public class RealJwtPathTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final long T0 = 1_761_700_900_000L;
    private final AtomicLong clock = new AtomicLong(T0);
    private InMemoryHttpAdapter adapter;
    private Auth auth;
    private String aliceId;

    private Auth.Builder builder() throws Exception {
        InMemoryUserStore s = new InMemoryUserStore();
        aliceId = s.create(AuthUser.builder().username("alice").passwordHash("x").roles("reader").build()).getId();
        return Auth.builder(AuthTestSupport.SECRET).hashIterations(1000).logger(new RecordingLogger()).users(s)
                .timeSource(clock::get).roles(RolePolicy.builder().role("reader", "posts:read").build());
    }

    private String token() {
        return auth.accessToken(aliceId, 0, 900_000L);
    }

    private String expired() {
        long now = clock.get();
        clock.set(now - 3_600_000L);
        String t = token();
        clock.set(now);
        return t;
    }

    private TestApp start(boolean secure, Auth.Builder b) {
        adapter = new InMemoryHttpAdapter(secure ? "203.0.113.9" : "127.0.0.1", secure);
        TestApp t = TestApp.create(adapter);
        auth = b.build();
        auth.install(t.app());
        t.app().get("/api/x", (req, res) -> res.send("x:" + auth.user(req).getId()));
        t.app().get("/health", (req, res) -> res.send("health:" + (auth.userOrNull(req) != null)));
        t.app().get("/api/dup", (req, res) -> {
            throw new DuplicateUserException("taken");
        });
        t.app().onError(auth.errors());
        t.start();
        return t;
    }

    private ClientResponse get(String path, String... headers) {
        ClientRequest r = ClientRequest.of("GET", path);
        for (int i = 0; i < headers.length; i += 2) {
            r.header(headers[i], headers[i + 1]);
        }
        return adapter.send(r);
    }

    /** The access token is an HS256 JWT with typ at+jwt that the standalone Jwt verifies with the auth key set. */
    @Test
    public void accessTokenIsAStandaloneJwt() throws Exception {
        start(true, builder());
        // SEC-TOKEN-005: the access token is signed with the access-purpose key derived from the key set
        JwtClaims c = Jwt.builder(TokenKeys.purpose(auth.keySet(), TokenKeys.ACCESS)).timeSource(clock::get).build()
                .verify(token(), AccessTokens.TYPE);
        assertEquals(aliceId, c.getSubject());
        assertEquals(Long.valueOf(0), c.getLong("ver"));
        assertNotNull(c.getString("fam"));
        assertNotNull(c.getTokenId());
        assertEquals("at+jwt", c.getType());
    }

    // SEC-FLOW-004 AC-2 with a real JWT: COOKIE, a valid naru_at and a Bearer token -> authenticated by naru_at
    @Test
    public void SEC_FLOW_004_AC2_realJwtCookieWinsInCookieMode() throws Exception {
        start(true, builder().transport(TokenTransport.COOKIE));
        ClientResponse r = get("/api/x", "Cookie", "naru_at=" + token(), "Authorization", "Bearer not-a-jwt");
        assertEquals(r.text(), 200, r.status());
        assertEquals("x:" + aliceId, r.text());
    }

    // SEC-FLOW-004 AC-3 with real JWTs: HEADER, only a valid (or an expired) naru_at -> 401 unauthenticated; the
    // Bearer form of the same valid token passes, and a forged or expired Bearer is 401 invalid_token
    @Test
    public void SEC_FLOW_004_AC3_realJwtCookieIsNotReadInHeaderMode() throws Exception {
        start(true, builder());
        for (String t : new String[] {token(), expired()}) {
            ClientResponse r = get("/api/x", "Cookie", "naru_at=" + t);
            assertEquals(401, r.status());
            assertEquals("unauthenticated", AuthTestSupport.code(r));
            assertEquals("Bearer", r.header("WWW-Authenticate"));
        }
        assertEquals(200, get("/api/x", "Authorization", "Bearer " + token()).status());
        String forged = token();
        forged = forged.substring(0, forged.length() - 2) + (forged.endsWith("AA") ? "BA" : "AA");
        for (String t : new String[] {forged, expired()}) {
            ClientResponse r = get("/api/x", "Authorization", "Bearer " + t);
            assertEquals(401, r.status());
            assertEquals("invalid_token", AuthTestSupport.code(r));
            assertEquals("Bearer error=\"invalid_token\"", r.header("WWW-Authenticate"));
        }
    }

    // SEC-AUTH-005 AC-3 with real JWTs: on a secure transport a public request with a valid token has a principal;
    // an expired or invalid token leaves it anonymous
    @Test
    public void SEC_AUTH_005_AC3_realJwtOptionalAuthenticationOnPublicPaths() throws Exception {
        start(true, builder().permit("/health"));
        assertEquals("health:true", get("/health", "Authorization", "Bearer " + token()).text());
        assertEquals("health:false", get("/health", "Authorization", "Bearer " + expired()).text());
        assertEquals("health:false", get("/health", "Authorization", "Bearer x.y.z").text());
        assertEquals("health:false", get("/health").text());
    }

    // IR-AUTH-002 AC-1 representative path with a real JWT: an authenticated handler throwing DuplicateUserException
    // gets 409 JSON through app.onError(auth.errors())
    @Test
    public void IR_AUTH_002_AC1_realJwtHandlerErrorThroughErrors() throws Exception {
        start(true, builder());
        ClientResponse r = get("/api/dup", "Authorization", "Bearer " + token());
        assertEquals(409, r.status());
        assertEquals("duplicate_user", AuthTestSupport.code(r));
    }

    // SEC-AUTH-001 AC-5: in DEV a null secret builds, its tokens verify in the same process, and the build and
    // start log hold exactly one WARN that a temporary key is in use and that restarting invalidates all tokens
    @Test
    public void SEC_AUTH_001_AC5_devTemporaryKeyTokensVerify() throws Exception {
        RecordingLogger log = new RecordingLogger();
        InMemoryUserStore s = new InMemoryUserStore();
        String id = s.create(AuthUser.builder().username("alice").passwordHash("x").build()).getId();
        adapter = new InMemoryHttpAdapter();
        TestApp t = TestApp.create(adapter);
        auth = Auth.builder((String) null).mode(AuthMode.DEV).hashIterations(1000).logger(log).users(s).build();
        auth.install(t.app());
        t.app().get("/api/x", (req, res) -> res.send("x:" + auth.user(req).getId()));
        t.start();
        assertEquals("x:" + id, get("/api/x", "Authorization", "Bearer " + auth.accessToken(id, 0, 60_000L)).text());
        assertEquals(log.lines.toString(), 1, RecordingLogger.countMatching(log.lines,
                "WARN .*temporary signing key.*invalidates all tokens.*"));
    }

    // SEC-TOKEN-004 AC-7: the builder(secret) / builder(KeySources.of(secret)) equivalence, owned by SEC-AUTH-001 AC-6
    // SEC-AUTH-001 AC-6: Auth.builder(String) and Auth.builder(KeySources.of(sameSecret)) verify each other's tokens
    @Test
    public void SEC_AUTH_001_AC6_stringAndKeySourceBuildersShareTokens() throws Exception {
        InMemoryUserStore s = new InMemoryUserStore();
        String id = s.create(AuthUser.builder().username("alice").passwordHash("x").build()).getId();
        Auth byString = Auth.builder(AuthTestSupport.SECRET).hashIterations(1000).users(s).build();
        Auth bySource = Auth.builder(KeySources.of(AuthTestSupport.SECRET)).hashIterations(1000).users(s).build();
        for (Auth[] pair : new Auth[][] {{byString, bySource}, {bySource, byString}}) {
            adapter = new InMemoryHttpAdapter();
            TestApp t = TestApp.create(adapter);
            final Auth verifier = pair[1];
            verifier.install(t.app());
            t.app().get("/api/x", (req, res) -> res.send("x:" + verifier.user(req).getId()));
            t.start();
            ClientResponse r = get("/api/x", "Authorization", "Bearer " + pair[0].accessToken(id, 0, 60_000L));
            assertEquals(r.text(), 200, r.status());
        }
        assertEquals(byString.keySet().getSigningKid(), bySource.keySet().getSigningKid());
    }

    // SEC-AUTH-006 AC-1: static files at / with protectOnly("/api"): /index.html is public, /api/users and /auth/me
    // are 401; with protectOnly("/app") and mountPath("/api/auth") the mount path is protected and the admin users
    // endpoint with an expired access token gets 401 invalid_token from the global middleware
    @Test
    public void SEC_AUTH_006_AC1_protectOnlyKeepsTheAuthMountsInRange() throws Exception {
        File dist = tmp.newFolder("dist");
        Files.write(new File(dist, "index.html").toPath(), "<h1>hi</h1>".getBytes(StandardCharsets.UTF_8));
        adapter = new InMemoryHttpAdapter();
        TestApp t = TestApp.create(adapter);
        auth = builder().protectOnly("/api").build();
        auth.install(t.app());
        t.app().get("/api/users", (req, res) -> res.send("users"));
        t.app().staticFiles("/", dist);
        t.start();
        assertEquals(200, get("/index.html").status());
        assertEquals(401, get("/api/users").status());
        assertEquals(401, get("/auth/me").status());

        adapter = new InMemoryHttpAdapter();
        TestApp t2 = TestApp.create(adapter);
        auth = builder().protectOnly("/app").mountPath("/api/auth").build();
        // the real admin endpoints (IR-IAM-005) own GET /users, so the stand-in admin route has its own path
        auth.adminRoutes().get("/probe", (req, res) -> res.send("admin users"));
        auth.install(t2.app());
        t2.app().staticFiles("/", dist);
        t2.start();
        assertEquals(200, get("/index.html").status());
        ClientResponse me = get("/api/auth/me");
        assertEquals(401, me.status());
        assertEquals("unauthenticated", AuthTestSupport.code(me));
        ClientResponse admin = get("/api/auth/admin/users", "Authorization", "Bearer " + expired());
        assertEquals(401, admin.status());
        assertEquals("invalid_token", AuthTestSupport.code(admin));
        assertFalse(admin.text().contains("admin users"));
        assertTrue(auth.describe(), auth.describe().contains("admin: /api/auth/admin"));
        ClientResponse served = get("/api/auth/admin/probe", "Authorization", "Bearer " + token());
        assertEquals(200, served.status());
        assertEquals("admin users", served.text());
    }

    // SEC-AUTH-006 AC-7: outside the protectOnly range authentication is optional: a guard there passes a valid token
    // holding the scope, answers 401 without a token, and an expired token leaves the request anonymous (no
    // invalid_token from the middleware) so the guard answers 401 unauthenticated
    @Test
    public void SEC_AUTH_006_AC7_outOfRangeRoutesGetOptionalAuthentication() throws Exception {
        for (boolean secure : new boolean[] {true, false}) {
            adapter = new InMemoryHttpAdapter(secure ? "203.0.113.9" : "127.0.0.1", secure);
            TestApp t = TestApp.create(adapter);
            auth = builder().protectOnly("/api").build();
            auth.install(t.app());
            t.app().get("/x", auth.require("posts:read"), (req, res) -> res.send("h:" + auth.user(req).getId()));
            t.start();
            ClientResponse ok = get("/x", "Authorization", "Bearer " + token());
            assertEquals(ok.text(), 200, ok.status());
            assertEquals("h:" + aliceId, ok.text());
            ClientResponse none = get("/x");
            assertEquals(401, none.status());
            assertEquals("unauthenticated", AuthTestSupport.code(none));
            ClientResponse old = get("/x", "Authorization", "Bearer " + expired());
            assertEquals(401, old.status());
            assertEquals("unauthenticated", AuthTestSupport.code(old));
            assertTrue(old.header("WWW-Authenticate"), "Bearer".equals(old.header("WWW-Authenticate")));
        }
    }
}
