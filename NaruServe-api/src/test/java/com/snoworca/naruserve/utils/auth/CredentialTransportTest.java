package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;

import com.snoworca.naruserve.Method;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.testing.TestApp;
import com.snoworca.naruserve.utils.auth.user.AuthUser;
import com.snoworca.naruserve.utils.auth.user.ForwardingUserStore;
import com.snoworca.naruserve.utils.auth.user.InMemoryUserStore;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/**
 * Reading the access credential per transport mode (SEC-FLOW-004) and the public-path rules (SEC-AUTH-005), through
 * the real auth middleware. The JWT verifier is not built yet, so {@link AuthTestSupport.TestTokens} stands in for it
 * behind the transport reading: {@code t:<id>:<ver>} verifies, anything else is 401 invalid_token.
 */
public class CredentialTransportTest {

    private InMemoryHttpAdapter adapter;
    private AuthTestSupport.TestTokens tokens;
    private AtomicInteger reached;
    private Auth auth;

    private String aliceId;
    private String ALICE;
    private String BOB;

    private InMemoryUserStore store() throws Exception {
        InMemoryUserStore s = new InMemoryUserStore();
        aliceId = s.create(AuthUser.builder().username("alice").passwordHash("x").build()).getId();
        String bobId = s.create(AuthUser.builder().username("bob").passwordHash("x").build()).getId();
        ALICE = AuthTestSupport.token(aliceId);
        BOB = AuthTestSupport.token(bobId);
        return s;
    }

    private Auth.Builder builder() throws Exception {
        tokens = new AuthTestSupport.TestTokens();
        return AuthTestSupport.transportBuilder(tokens, new RecordingLogger()).users(store());
    }

    private TestApp app(String peer, boolean secure, Auth.Builder b) {
        adapter = new InMemoryHttpAdapter(peer, secure);
        TestApp t = TestApp.create(adapter);
        reached = new AtomicInteger();
        auth = b.build();
        auth.routes().post("/login", (req, res) -> {
            reached.incrementAndGet();
            res.send("login");
        });
        auth.routes().post("/password", (req, res) -> {
            reached.incrementAndGet();
            res.send("password");
        });
        auth.install(t.app());
        t.app().get("/api/x", (req, res) -> res.send("x:" + auth.user(req).getId()));
        t.app().post("/api/posts", (req, res) -> res.send("posted"));
        t.app().get("/health", (req, res) -> res.send("health"));
        t.app().get("/health/x", (req, res) -> res.send("deep"));
        t.app().post("/health", (req, res) -> res.send("posted health"));
        t.app().post("/hook", (req, res) -> res.send("hook"));
        t.app().get("/docs", (req, res) -> res.send("docs"));
        t.app().get("/docs/a/b", (req, res) -> res.send("docs ab"));
        t.app().get("/docsx", (req, res) -> res.send("docsx"));
        t.app().onError(auth.errors());
        t.start();
        return t;
    }

    private TestApp secure(Auth.Builder b) {
        return app("203.0.113.9", true, b);
    }

    private ClientResponse get(String path, String... headers) {
        ClientRequest r = ClientRequest.of("GET", path);
        for (int i = 0; i < headers.length; i += 2) {
            r.header(headers[i], headers[i + 1]);
        }
        return adapter.send(r);
    }

    private static void unauthenticated(ClientResponse r) {
        assertEquals(r.text(), 401, r.status());
        assertEquals("unauthenticated", AuthTestSupport.code(r));
        assertEquals("Bearer", r.header("WWW-Authenticate"));
    }

    /** A public /health route that reports the principal id, or "anon". */
    private TestApp publicApp(String peer, boolean secure, Auth.Builder b) {
        adapter = new InMemoryHttpAdapter(peer, secure);
        TestApp t = TestApp.create(adapter);
        auth = b.permit("/health").build();
        auth.install(t.app());
        t.app().get("/health", (req, res) -> {
            AuthPrincipal p = auth.userOrNull(req);
            res.send(p == null ? "anon" : p.getId());
        });
        t.start();
        return t;
    }

    // SEC-FLOW-004 AC-1: COOKIE, protected path with only a valid Bearer -> 401 unauthenticated, plain Bearer challenge
    @Test
    public void SEC_FLOW_004_AC1_cookieModeDoesNotReadBearer() throws Exception {
        secure(builder().transport(TokenTransport.COOKIE));
        unauthenticated(get("/api/x", "Authorization", "Bearer " + ALICE));
        assertEquals(0, tokens.calls.get());
    }

    // SEC-FLOW-004 AC-2: COOKIE, valid naru_at plus a Bearer -> authenticated by naru_at, not 400
    @Test
    public void SEC_FLOW_004_AC2_cookieModeIgnoresTheBearerBesideTheCookie() throws Exception {
        secure(builder().transport(TokenTransport.COOKIE));
        ClientResponse r = get("/api/x", "Cookie", "naru_at=" + ALICE, "Authorization", "Bearer " + BOB);
        assertEquals(r.text(), 200, r.status());
        assertEquals("x:" + aliceId, r.text());
    }

    // SEC-FLOW-004 AC-3: HEADER, only a naru_at cookie (valid or expired) -> 401 unauthenticated; the cookie is not read
    @Test
    public void SEC_FLOW_004_AC3_headerModeDoesNotReadTheCookie() throws Exception {
        secure(builder().transport(TokenTransport.HEADER));
        unauthenticated(get("/api/x", "Cookie", "naru_at=" + ALICE));
        unauthenticated(get("/api/x", "Cookie", "naru_at=expired-token"));
        assertEquals(0, tokens.calls.get());
    }

    // SEC-FLOW-004 AC-6: two Authorization headers -> 400 on a protected path and a credential endpoint, anonymous 200
    // on .permit("/health"), in every mode
    // IR-AUTH-003 AC-5: invalid_request only for multiple credentials on protected paths and credential endpoints
    @Test
    public void SEC_FLOW_004_AC6_IR_AUTH_003_AC5_twoAuthorizationHeaders() throws Exception {
        for (TokenTransport mode : TokenTransport.values()) {
            secure(builder().transport(mode).permit("/health"));
            ClientRequest two = ClientRequest.of("GET", "/api/x").header("Authorization", "Bearer " + ALICE)
                    .header("Authorization", "Bearer " + BOB);
            ClientResponse r = adapter.send(two);
            assertEquals(mode + " " + r.text(), 400, r.status());
            assertEquals("invalid_request", AuthTestSupport.code(r));
            for (String endpoint : new String[] {"/auth/password", "/auth/login"}) {
                ClientRequest post = ClientRequest.json("POST", endpoint, "{}").header("Authorization", "Bearer " + ALICE)
                        .header("Authorization", "ApiKey nk_x");
                ClientResponse p = adapter.send(post);
                assertEquals(mode + " " + endpoint, 400, p.status());
                assertEquals("invalid_request", AuthTestSupport.code(p));
            }
            assertEquals(0, reached.get());
            publicApp("203.0.113.9", true, builder().transport(mode));
            ClientRequest pub = ClientRequest.of("GET", "/health").header("Authorization", "Bearer " + ALICE)
                    .header("Authorization", "Bearer " + BOB);
            ClientResponse h = adapter.send(pub);
            assertEquals(mode + " " + h.text(), 200, h.status());
            assertEquals(mode.toString(), "anon", h.text());
        }
    }

    // SEC-FLOW-004 AC-8: COOKIE, valid naru_at plus naru_rt -> 200; naru_rt is not an access credential
    @Test
    public void SEC_FLOW_004_AC8_refreshCookieIsNoAccessCredential() throws Exception {
        secure(builder().transport(TokenTransport.COOKIE));
        ClientResponse r = get("/api/x", "Cookie", "naru_at=" + ALICE + "; naru_rt=some-refresh-token");
        assertEquals(r.text(), 200, r.status());
        unauthenticated(get("/api/x", "Cookie", "naru_rt=some-refresh-token"));
    }

    // SEC-FLOW-004 AC-9: without .transport(...) the mode is HEADER: Bearer -> 200, naru_at alone -> 401
    @Test
    public void SEC_FLOW_004_AC9_defaultModeIsHeader() throws Exception {
        secure(builder());
        ClientResponse r = get("/api/x", "Authorization", "Bearer " + ALICE);
        assertEquals(r.text(), 200, r.status());
        assertEquals("x:" + aliceId, r.text());
        unauthenticated(get("/api/x", "Cookie", "naru_at=" + ALICE));
        ClientResponse bad = get("/api/x", "Authorization", "Bearer forged");
        assertEquals(401, bad.status());
        assertEquals("invalid_token", AuthTestSupport.code(bad));
        assertEquals("Bearer error=\"invalid_token\"", bad.header("WWW-Authenticate"));
    }

    // BOTH: a valid Bearer and a valid naru_at together are multiple credentials on a protected path (the BOTH case of
    // the SEC-FLOW-004 multiple-credential rule; the API-key cases wait for .apiKeys(store))
    @Test
    public void bothModeBearerAndCookieIs400() throws Exception {
        secure(builder().transport(TokenTransport.BOTH));
        ClientResponse r = get("/api/x", "Authorization", "Bearer " + ALICE, "Cookie", "naru_at=" + ALICE);
        assertEquals(400, r.status());
        assertEquals("invalid_request", AuthTestSupport.code(r));
        assertEquals("x:" + aliceId, get("/api/x", "Cookie", "naru_at=" + ALICE).text());
        assertEquals("x:" + aliceId, get("/api/x", "Authorization", "Bearer " + ALICE).text());
        ClientResponse twoCookies = get("/api/x", "Cookie", "naru_at=" + ALICE + "; naru_at=" + BOB);
        assertEquals(400, twoCookies.status());
    }

    // SEC-AUTH-005 AC-1: .permit("/health") is exact: GET and HEAD only, raw path, no case or slash variants
    @Test
    public void SEC_AUTH_005_AC1_permitIsExactAndGetHeadOnly() throws Exception {
        secure(builder().permit("/health"));
        assertEquals(200, get("/health").status());
        assertEquals(200, adapter.send(ClientRequest.of("HEAD", "/health")).status());
        unauthenticated(adapter.send(ClientRequest.of("POST", "/health")));
        for (String variant : new String[] {"/health/x", "/HEALTH", "/health/", "/%68ealth"}) {
            unauthenticated(get(variant));
        }
    }

    // SEC-AUTH-005 AC-2: .permit(POST, path) and .permitPrefix(path) with a path-segment boundary
    @Test
    public void SEC_AUTH_005_AC2_methodPermitAndPrefix() throws Exception {
        secure(builder().permit(Method.POST, "/hook").permitPrefix("/docs"));
        assertEquals(200, adapter.send(ClientRequest.of("POST", "/hook")).status());
        assertEquals(200, get("/docs").status());
        assertEquals(200, get("/docs/a/b").status());
        unauthenticated(get("/docsx"));
    }

    // SEC-AUTH-005 AC-3: optional authentication on a public path: a valid token attaches, anything else is anonymous
    @Test
    public void SEC_AUTH_005_AC3_optionalAuthenticationOnPublicPaths() throws Exception {
        publicApp("203.0.113.9", true, builder());
        assertEquals(aliceId, get("/health", "Authorization", "Bearer " + ALICE).text());
        for (String[] h : new String[][] {{"Authorization", "Bearer expired-token"},
            {"Authorization", "Bearer forged"}}) {
            ClientResponse r = get("/health", h);
            assertEquals(200, r.status());
            assertEquals("anon", r.text());
        }
        ClientResponse two = adapter.send(ClientRequest.of("GET", "/health").header("Authorization", "Bearer " + ALICE)
                .header("Authorization", "Bearer " + BOB));
        assertEquals(200, two.status());
        assertEquals("anon", two.text());

        publicApp("203.0.113.9", true, builder().transport(TokenTransport.BOTH));
        ClientResponse both = get("/health", "Authorization", "Bearer " + ALICE, "Cookie", "naru_at=" + BOB);
        assertEquals(200, both.status());
        assertEquals("anon", both.text());

        publicApp("203.0.113.9", true, builder().transport(TokenTransport.HEADER));
        assertEquals(aliceId, get("/health", "Authorization", "Bearer " + ALICE, "Cookie", "naru_at=" + BOB).text());
    }

    // SEC-AUTH-005 AC-4: PROD plaintext non-local GET /health without a token stays public: 200
    @Test
    public void SEC_AUTH_005_AC4_publicPathStaysPublicOnPlaintext() throws Exception {
        publicApp("203.0.113.9", false, builder());
        ClientResponse r = get("/health");
        assertEquals(200, r.status());
        assertEquals("anon", r.text());
    }

    /** A store that counts every call, to show the login body is never read. */
    private static final class CountingStore extends ForwardingUserStore {
        final AtomicInteger calls = new AtomicInteger();

        CountingStore() {
            super(new InMemoryUserStore());
        }

        @Override
        public AuthUser findByUsername(String username) throws Exception {
            calls.incrementAndGet();
            return super.findByUsername(username);
        }

        @Override
        public AuthUser findById(String id) throws Exception {
            calls.incrementAndGet();
            return super.findById(id);
        }
    }

    // SEC-AUTH-005 AC-5: a .permit on a credential endpoint does not lift the R33 check: plaintext non-local POST
    // /auth/login or /auth/password in PROD -> 403 insecure_transport, nothing read
    @Test
    public void SEC_AUTH_005_AC5_permitDoesNotOpenCredentialEndpoints() throws Exception {
        for (String endpoint : new String[] {"/auth/login", "/auth/password"}) {
            CountingStore counting = new CountingStore();
            tokens = new AuthTestSupport.TestTokens();
            Auth.Builder b = AuthTestSupport.transportBuilder(tokens, new RecordingLogger()).users(counting)
                    .permit(Method.POST, endpoint);
            app("203.0.113.9", false, b);
            int probe = counting.calls.get(); // the start-time store probe
            ClientResponse r = AuthTestSupport.post(adapter, endpoint, "{\"username\":\"alice\",\"password\":\"x\"}",
                    "Authorization", "Bearer " + ALICE);
            assertEquals(endpoint + " " + r.text(), 403, r.status());
            assertEquals("insecure_transport", AuthTestSupport.code(r));
            assertEquals(0, reached.get());
            assertEquals(probe, counting.calls.get());
            assertEquals(0, tokens.calls.get());
        }
    }

    // SEC-AUTH-005 AC-6: local plaintext passes R33; a forwarded header makes it non-local; DEV and
    // .allowInsecureTransport turn the check off
    @Test
    public void SEC_AUTH_005_AC6_localDevAndInsecureOptIn() throws Exception {
        publicApp("127.0.0.1", false, builder());
        assertEquals(aliceId, get("/health", "Authorization", "Bearer " + ALICE).text());
        ClientResponse forwarded = get("/health", "Authorization", "Bearer " + ALICE, "X-Forwarded-For",
                "203.0.113.7");
        assertEquals(200, forwarded.status());
        assertEquals("anon", forwarded.text());

        publicApp("203.0.113.9", false, builder().mode(AuthMode.DEV));
        assertEquals(aliceId, get("/health", "Authorization", "Bearer " + ALICE).text());

        publicApp("10.0.0.5", false, builder().allowInsecureTransport("TLS adapter pending"));
        assertEquals(aliceId, get("/health", "Authorization", "Bearer " + ALICE).text());
        publicApp("10.0.0.5", false, builder());
        ClientResponse off = get("/health", "Authorization", "Bearer " + ALICE);
        assertEquals(200, off.status());
        assertEquals("anon", off.text());
    }

    // the unread Bearer of COOKIE mode is absent on a public path too: anonymous, never invalid
    @Test
    public void cookieModePublicPathIgnoresBearer() throws Exception {
        publicApp("203.0.113.9", true, builder().transport(TokenTransport.COOKIE));
        assertEquals("anon", get("/health", "Authorization", "Bearer " + ALICE).text());
        assertEquals(aliceId, get("/health", "Cookie", "naru_at=" + ALICE).text());
    }
}
