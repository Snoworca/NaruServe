package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.testing.TestApp;
import com.snoworca.naruserve.utils.auth.user.AuthUser;
import com.snoworca.naruserve.utils.auth.user.InMemoryUserStore;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Test;

/**
 * The token-response writer of the five issuing endpoints (FR-FLOW-002, at component level until the endpoints exist),
 * the naru_device cookie attributes (SEC-FLOW-013 Q118) and the allowInsecureTransport start log and PROD switch
 * (SEC-FLOW-002).
 */
public class Wave7DeliveryTest {

    private TestApp app;

    @After
    public void stop() {
        if (app != null) {
            app.app().shutdown(5, TimeUnit.SECONDS);
        }
    }

    private InMemoryHttpAdapter serve(final Auth auth, final TokenPair pair, final boolean byCookies,
            final RecordingLogger log, boolean secure, String remote) {
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter(remote, secure);
        app = TestApp.create(adapter);
        app.app().post("/issue", (req, res) -> auth.tokenDelivery().deliver(req, res, 200, pair, "device-token",
                byCookies, log));
        app.start();
        return adapter;
    }

    private static List<String> setCookies(ClientResponse r) {
        List<String> out = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, String> h : r.headers()) {
            if (h.getKey().equalsIgnoreCase("Set-Cookie")) {
                out.add(h.getValue());
            }
        }
        return out;
    }

    private static String setCookie(ClientResponse r, String name) {
        for (String c : setCookies(r)) {
            if (c.startsWith(name + "=")) {
                return c;
            }
        }
        return null;
    }

    // body delivery: tokens and deviceToken in the body, no Set-Cookie, Cache-Control no-store
    @Test
    public void bodyDelivery() throws Exception {
        InMemoryUserStore users = new InMemoryUserStore();
        AuthUser u = users.create(AuthUser.builder().username("alice").passwordHash("x").build());
        Auth auth = Auth.builder(AuthTestSupport.SECRET).hashIterations(1000).users(users).build();
        TokenPair pair = auth.tokens().issue(u);
        ClientResponse r = serve(auth, pair, false, new RecordingLogger(), true, "203.0.113.9")
                .send(ClientRequest.json("POST", "/issue", "{}"));
        assertEquals(200, r.status());
        assertTrue(setCookies(r).isEmpty());
        assertEquals("no-store", r.header("Cache-Control"));
        for (String k : new String[] {"\"accessToken\":", "\"refreshToken\":", "\"deviceToken\":\"device-token\""}) {
            assertTrue(r.text(), r.text().contains(k));
        }
        assertFalse(r.text().contains("csrfToken"));
    }

    // cookie delivery: naru_at, naru_rt, naru_csrf (bound to the pair's family) and naru_device; the body carries only
    // csrfToken; naru_device is HttpOnly, SameSite=Strict, Path=<mount>/login, Max-Age 7776000, Secure when secure
    @Test
    public void cookieDeliveryAndDeviceCookieAttributes() throws Exception {
        for (boolean secure : new boolean[] {true, false}) {
            InMemoryUserStore users = new InMemoryUserStore();
            AuthUser u = users.create(AuthUser.builder().username("alice").passwordHash("x").build());
            Auth auth = Auth.builder(AuthTestSupport.SECRET).hashIterations(1000).users(users)
                    .transport(TokenTransport.COOKIE).mountPath("/api/auth").build();
            TokenPair pair = auth.tokens().issue(u);
            ClientResponse r = serve(auth, pair, true, new RecordingLogger(), secure, "127.0.0.1")
                    .send(ClientRequest.json("POST", "/issue", "{}"));
            assertEquals("no-store", r.header("Cache-Control"));
            String body = r.text();
            assertTrue(body, body.startsWith("{\"csrfToken\":\"") && !body.contains("accessToken")
                    && !body.contains("refreshToken") && !body.contains("deviceToken"));
            String csrf = body.substring(14, body.length() - 2);
            String fam = auth.tokens().verifyAccess(pair.getAccessToken()).getString("fam");
            assertTrue(auth.csrfTokens().matches(csrf, u.getId(), fam));
            for (String n : new String[] {"naru_at", "naru_rt", "naru_csrf", "naru_device"}) {
                String c = setCookie(r, n);
                assertTrue(n + " set", c != null);
                assertEquals(n + " Secure", secure, c.contains("; Secure"));
            }
            String device = setCookie(r, "naru_device");
            assertTrue(device, device.contains("HttpOnly") && device.contains("SameSite=Strict")
                    && device.contains("Path=/api/auth/login") && device.contains("Max-Age=7776000"));
            assertTrue(setCookie(r, "naru_csrf").contains(csrf));
            stop();
            app = null;
        }
    }

    // under allowInsecureTransport a cookie delivery over plain text logs one WARN that the auth cookies lack Secure
    // (component level only; the login criterion of the insecure-transport requirement stays open)
    @Test
    public void insecureCookieDeliveryWarns() throws Exception {
        InMemoryUserStore users = new InMemoryUserStore();
        AuthUser u = users.create(AuthUser.builder().username("alice").passwordHash("x").build());
        Auth auth = Auth.builder(AuthTestSupport.SECRET).hashIterations(1000).users(users)
                .transport(TokenTransport.COOKIE).allowInsecureTransport("TLS adapter pending").build();
        RecordingLogger log = new RecordingLogger();
        InMemoryHttpAdapter a = serve(auth, auth.tokens().issue(u), true, log, false, "203.0.113.9");
        a.send(ClientRequest.json("POST", "/issue", "{}"));
        assertEquals(1, RecordingLogger.countMatching(log.lines, "WARN .*auth cookies.*without the Secure.*"));
    }

    // the delivery rule: HEADER body, COOKIE cookies, BOTH by the presented credential or the Origin
    @Test
    public void deliveryRule() {
        final List<Boolean> seen = new java.util.ArrayList<>();
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        app = TestApp.create(adapter);
        app.app().post("/rule", (req, res) -> {
            seen.add(TokenDelivery.byCookies(TokenTransport.HEADER, req, Collections.<String>emptySet(), null));
            seen.add(TokenDelivery.byCookies(TokenTransport.COOKIE, req, Collections.<String>emptySet(), null));
            seen.add(TokenDelivery.byCookies(TokenTransport.BOTH, req, Collections.<String>emptySet(), Boolean.TRUE));
            seen.add(TokenDelivery.byCookies(TokenTransport.BOTH, req, Collections.<String>emptySet(), Boolean.FALSE));
            seen.add(TokenDelivery.byCookies(TokenTransport.BOTH, req, Collections.<String>emptySet(), null));
            seen.add(TokenDelivery.byCookies(TokenTransport.BOTH, req,
                    Collections.singleton("https://spa.example"), null));
            res.status(204).end();
        });
        app.start();
        adapter.send(ClientRequest.json("POST", "/rule", "{}").header("Origin", "https://spa.example"));
        assertEquals(java.util.Arrays.asList(false, true, true, false, false, true), seen);
    }

    // SEC-FLOW-002 AC-3: every start of an app whose Auth has the opt-in logs one WARN with the reason; two starts, two
    // lines
    @Test
    public void SEC_FLOW_002_AC3_reasonIsWarnedOnEveryStart() {
        RecordingLogger log = new RecordingLogger();
        for (int i = 1; i <= 2; i++) {
            TestApp t = TestApp.create(new InMemoryHttpAdapter("203.0.113.9", false));
            Auth.builder(AuthTestSupport.SECRET).hashIterations(1000).logger(log)
                    .allowInsecureTransport("TLS adapter pending").build().install(t.app());
            t.start();
            assertEquals(i, RecordingLogger.countMatching(log.lines, "WARN .*TLS adapter pending.*"));
            t.app().shutdown(5, TimeUnit.SECONDS);
        }
    }

    // SEC-FLOW-002 AC-5: without the opt-in in PROD a plain-text non-local POST /auth/login -> 403 insecure_transport;
    // with it the secure-transport check lets the request through
    @Test
    public void SEC_FLOW_002_AC5_withoutTheOptInProdRefusesPlainText() {
        for (boolean optIn : new boolean[] {false, true}) {
            InMemoryHttpAdapter adapter = new InMemoryHttpAdapter("203.0.113.9", false);
            app = TestApp.create(adapter);
            Auth.Builder b = Auth.builder(AuthTestSupport.SECRET).hashIterations(1000).logger(new RecordingLogger());
            if (optIn) {
                b.allowInsecureTransport("TLS adapter pending");
            }
            Auth auth = b.build();
            auth.install(app.app());
            app.app().onError(auth.errors());
            app.start();
            ClientResponse r = adapter.send(ClientRequest.json("POST", "/auth/login",
                    "{\"username\":\"alice\",\"password\":\"pw\"}"));
            if (optIn) {
                assertFalse(r.text(), "insecure_transport".equals(AuthTestSupport.code(r)));
            } else {
                assertEquals(403, r.status());
                assertEquals("insecure_transport", AuthTestSupport.code(r));
            }
            stop();
            app = null;
        }
    }
}
