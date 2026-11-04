package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.testing.TestApp;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/**
 * The per-request secure-transport rule (SEC-FLOW-001). The account endpoints are not built yet, so a stand-in
 * handler registered on auth.routes() records whether a request reached the router; a 403 must stop it first.
 */
public class TransportRuleTest {

    private InMemoryHttpAdapter adapter;
    private AtomicInteger reached;
    private AuthTestSupport.TestCredentials creds;

    private Auth app(String peer, boolean secure, Auth.Builder b) {
        adapter = new InMemoryHttpAdapter(peer, secure);
        TestApp t = TestApp.create(adapter);
        reached = new AtomicInteger();
        Auth auth = b.build();
        for (String path : Arrays.asList("/login", "/refresh", "/logout", "/register", "/setup", "/password",
                "/me/apikeys")) {
            auth.routes().post(path, (req, res) -> {
                reached.incrementAndGet();
                res.send("reached");
            });
        }
        auth.adminRoutes().post("/apikeys", (req, res) -> {
            reached.incrementAndGet();
            res.send("issued");
        });
        auth.install(t.app());
        t.app().get("/health", (req, res) -> res.send(String.valueOf(auth.userOrNull(req))));
        t.app().get("/api/x", (req, res) -> res.send("x"));
        t.start();
        return auth;
    }

    private Auth.Builder builder() {
        creds = new AuthTestSupport.TestCredentials();
        return AuthTestSupport.builder(creds, new RecordingLogger());
    }

    private ClientResponse post(String path, String... headers) {
        return AuthTestSupport.post(adapter, path, "{\"username\":\"a\",\"password\":\"b\"}", headers);
    }

    private static void insecure(ClientResponse r) {
        assertEquals(r.text(), 403, r.status());
        assertEquals("insecure_transport", AuthTestSupport.code(r));
    }

    // SEC-FLOW-001 AC-1: PROD, plain text, not loopback, POST /auth/login -> 403 before the router runs
    @Test
    public void SEC_FLOW_001_AC1_plainRemoteLoginIs403BeforeTheHandler() {
        app("203.0.113.9", false, builder());
        insecure(post("/auth/login"));
        assertEquals(0, reached.get());
        assertEquals(0, creds.calls.get());
    }

    // SEC-FLOW-001 AC-2: plain remote protected request with two Authorization headers -> 403, not 400
    @Test
    public void SEC_FLOW_001_AC2_transportCheckRunsBeforeCredentialParsing() {
        app("203.0.113.9", false, builder());
        ClientResponse r = adapter.send(ClientRequest.of("GET", "/api/x").header("Authorization", "Bearer a.b.c")
                .header("Authorization", "Bearer d.e.f"));
        insecure(r);
        assertEquals(0, creds.calls.get());
    }

    // SEC-FLOW-001 AC-3: loopback without proxy headers passes; adding X-Forwarded-For or Forwarded -> 403
    @Test
    public void SEC_FLOW_001_AC3_loopbackIsLocalUnlessAProxyHeaderIsPresent() {
        for (String peer : Arrays.asList("127.0.0.1", "::1")) {
            app(peer, false, builder());
            ClientResponse ok = post("/auth/login");
            assertEquals(peer, 200, ok.status());
            assertEquals(1, reached.get());
            insecure(post("/auth/login", "X-Forwarded-For", "198.51.100.4"));
            insecure(post("/auth/login", "Forwarded", "for=198.51.100.4"));
            assertEquals(1, reached.get());
        }
    }

    // SEC-FLOW-001 AC-4: trustProxyHops(1) and X-Forwarded-Proto: https make req.isSecure() true; the request passes
    @Test
    public void SEC_FLOW_001_AC4_trustedProxyHttpsPasses() {
        adapter = new InMemoryHttpAdapter("10.0.0.2", false);
        TestApp t = TestApp.create(adapter);
        t.app().trustProxyHops(1);
        reached = new AtomicInteger();
        AtomicReference<Boolean> secure = new AtomicReference<>();
        Auth auth = builder().build();
        auth.routes().post("/login", (req, res) -> {
            secure.set(req.isSecure());
            reached.incrementAndGet();
            res.send("tokens");
        });
        auth.install(t.app());
        t.start();
        ClientResponse r = post("/auth/login", "X-Forwarded-Proto", "https", "X-Forwarded-For", "198.51.100.4");
        assertEquals(200, r.status());
        assertEquals(Boolean.TRUE, secure.get());
        insecure(post("/auth/login", "X-Forwarded-For", "198.51.100.4"));
    }

    // SEC-FLOW-001 AC-5: a permitted path over plain remote text is anonymous; the token is not read
    @Test
    public void SEC_FLOW_001_AC5_publicPathStaysAnonymousWithoutReadingTheToken() {
        app("203.0.113.9", false, builder().permit("/health"));
        ClientResponse r = adapter.send(ClientRequest.of("GET", "/health")
                .header(AuthTestSupport.HEADER, "1:0").header("Authorization", "Bearer expired.token.value"));
        assertEquals(200, r.status());
        assertEquals("null", r.text());
        assertEquals(0, creds.calls.get());
    }

    // SEC-FLOW-001 AC-6: each of the eight credential endpoints -> 403, also under .permitPrefix("/auth")
    @Test
    public void SEC_FLOW_001_AC6_everyCredentialEndpointIs403EvenWhenPermitted() {
        for (Auth.Builder b : Arrays.asList(builder(), builder().permitPrefix("/auth"))) {
            app("203.0.113.9", false, b);
            for (String path : Arrays.asList("/auth/login", "/auth/refresh", "/auth/logout", "/auth/register",
                    "/auth/setup", "/auth/password", "/auth/me/apikeys", "/auth/admin/apikeys")) {
                insecure(post(path));
            }
            assertEquals(0, reached.get());
        }
    }

    // SEC-FLOW-001 AC-7: DEV disables the check
    @Test
    public void SEC_FLOW_001_AC7_devDisablesTheCheck() {
        app("203.0.113.9", false, builder().mode(AuthMode.DEV));
        ClientResponse r = post("/auth/login");
        assertEquals(200, r.status());
        assertEquals(1, reached.get());
        assertNull(r.header("WWW-Authenticate"));
        assertTrue(Auth.isLoopback("127.10.0.3") && Auth.isLoopback("[::1]") && !Auth.isLoopback("128.0.0.1")
                && !Auth.isLoopback("localhost"));
    }
}
