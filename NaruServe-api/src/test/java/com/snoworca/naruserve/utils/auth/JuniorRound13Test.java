package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.RecordingLogger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Test;

/** The code findings of the thirteenth junior study are answered with the fix (OPS-AUTH-025). */
public class JuniorRound13Test {

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

    // OPS-AUTH-025 AC-1: a CORS preflight that reaches auth names app.cors and logs one WARN
    @Test
    public void OPS_AUTH_025_AC1_preflightWithoutCorsIsNamed() {
        W8 f = fixture();
        f.start(b -> b);
        for (int i = 0; i < 2; i++) {
            ClientResponse r = f.adapter.send(ClientRequest.of("OPTIONS", "/api/x").header("Origin", W8.SAME)
                    .header("Access-Control-Request-Method", "GET"));
            assertEquals(401, r.status());
            assertTrue(message(r), message(r).contains("CORS preflight") && message(r).contains("app.cors("));
        }
        assertEquals(f.logger.lines.toString(), 1,
                RecordingLogger.matching(f.logger.lines, "(?s)WARN .*CORS preflight.*").size());
    }

    private static void expectStart(ClientResponse r, String start) {
        assertEquals(r.text(), 403, r.status());
        assertTrue(r.text(), message(r).startsWith(start));
    }

    // OPS-AUTH-025 AC-2: a COOKIE-mode csrf_failed names the check that failed
    @Test
    public void OPS_AUTH_025_AC2_csrfFailureNamesTheCheck() throws Exception {
        W8 f = fixture();
        f.start(b -> b.transport(TokenTransport.COOKIE));
        f.user("bob", W8.PW, "reader");
        ClientResponse login = f.post("/auth/login", W8.login("bob", W8.PW), "Host", W8.HOST, "Origin", W8.SAME);
        assertEquals(login.text(), 200, login.status());
        String at = W8.cookieValue(login, "naru_at");
        String csrf = String.valueOf(W8.object(login).get("csrfToken"));
        String cookie = "naru_at=" + at + "; naru_csrf=" + csrf;
        String h = W8.HOST;
        expectStart(f.post("/api/x", "{}", "Host", h, "Cookie", cookie, "Origin", W8.SAME), "no X-CSRF-Token header");
        expectStart(f.post("/api/x", "{}", "Host", h, "Cookie", cookie, "Origin", W8.SAME, "X-CSRF-Token", "wrong"), "the X-CSRF-Token does not match");
        expectStart(f.post("/api/x", "{}", "Host", h, "Cookie", cookie, "X-CSRF-Token", csrf), "no Origin or Referer header");
        expectStart(f.post("/api/x", "{}", "Host", h, "Cookie", cookie, "Origin", W8.EVIL, "X-CSRF-Token", csrf), "the Origin " + W8.EVIL + " is not trusted");
    }

    // OPS-AUTH-025 AC-3: JSON in another charset is named as a charset problem
    @Test
    public void OPS_AUTH_025_AC3_otherCharsetIsNamed() {
        W8 f = fixture();
        f.start(b -> b);
        ClientResponse r = f.adapter.send(ClientRequest.of("POST", "/auth/login")
                .header("Content-Type", "application/json; charset=ISO-8859-1")
                .body(W8.login("bob", W8.PW).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals(r.text(), 415, r.status());
        assertTrue(message(r), message(r).contains("UTF-8") && message(r).contains("charset=ISO-8859-1"));
    }

    // OPS-AUTH-025 AC-4: an invalid username on register names the rule
    @Test
    public void OPS_AUTH_025_AC4_invalidUsernameNamesTheRule() {
        W8 f = fixture();
        f.start(b -> b.register(true));
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            longName.append('a');
        }
        ClientResponse r = f.post("/auth/register", W8.login(longName.toString(), "long-password-1"));
        assertEquals(r.text(), 400, r.status());
        assertTrue(message(r), message(r).startsWith("invalid username: ") && message(r).contains("190"));
    }

    // OPS-AUTH-025 AC-5: ?token=, X-Auth-Token and a case variant of an exact permit are named
    @Test
    public void OPS_AUTH_025_AC5_moreVariantsAreNamed() {
        W8 f = fixture();
        f.start(b -> b.permit("/health"));
        assertTrue(message(f.get("/api/x?token=abc")).contains("?token"));
        assertTrue(message(f.get("/api/x", "X-Auth-Token", "abc")).contains("X-Auth-Token"));
        assertTrue(message(f.get("/Health")).contains(".permit(\"/health\") makes only GET and HEAD of exactly /health"));
    }

    // OPS-AUTH-025 AC-6: a lifetime below one second names the unit
    @Test
    public void OPS_AUTH_025_AC6_ttlUnitIsNamed() {
        try {
            Auth.builder(AuthTestSupport.SECRET).accessTtl(15).build();
            throw new AssertionError("built");
        } catch (AuthConfigException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("milliseconds") && e.getMessage().contains("900000"));
        }
    }

    // OPS-AUTH-025 AC-7: a body over the JSON limit names app.jsonLimit
    @Test
    public void OPS_AUTH_025_AC7_bodyOverLimitNamesJsonLimit() {
        W8 f = fixture();
        f.routes = w -> w.app.jsonLimit(100);
        f.start(b -> b);
        StringBuilder big = new StringBuilder("{\"username\":\"");
        for (int i = 0; i < 500; i++) {
            big.append('a');
        }
        big.append("\",\"password\":\"x\"}");
        ClientResponse r = f.post("/auth/login", big.toString());
        assertEquals(r.text(), 413, r.status());
        assertTrue(message(r), message(r).contains("app.jsonLimit("));
    }

    // OPS-AUTH-025 AC-1: the 401 and the WARN both say to register app.cors(...) before app.use(auth)
    @Test
    public void OPS_AUTH_025_AC1_preflightHintAndWarnNameTheOrder() {
        W8 f = fixture();
        f.start(b -> b);
        ClientResponse r = f.adapter.send(ClientRequest.of("OPTIONS", "/api/x").header("Origin", W8.SAME)
                .header("Access-Control-Request-Method", "GET"));
        assertTrue(message(r), message(r).contains("register app.cors(...) before app.use(auth)"));
        List<String> warns = RecordingLogger.matching(f.logger.lines, "(?s)WARN .*CORS preflight.*");
        assertEquals(f.logger.lines.toString(), 1, warns.size());
        assertTrue(warns.get(0), warns.get(0).contains("register app.cors(...) before app.use(auth)"));
    }

    // OPS-AUTH-025 AC-4: admin A2 and A4 refuse an invalid username with the same rule text as register
    @Test
    public void OPS_AUTH_025_AC4_adminEndpointsNameTheUsernameRule() throws Exception {
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            longName.append('a');
        }
        AdminFixture a = new AdminFixture();
        try {
            a.start();
            String root = a.user("root", "admin");
            String bob = a.user("bob");
            ClientResponse create = a.call("POST", "/auth/admin/users", root, W8.login(longName.toString(), "long-password-1"));
            assertEquals(create.text(), 400, create.status());
            assertTrue(message(create), message(create).startsWith("invalid username: "));
            ClientResponse rename = a.call("PATCH", "/auth/admin/users/" + bob, root, "{\"username\":\"" + longName + "\"}");
            assertEquals(rename.text(), 400, rename.status());
            assertTrue(message(rename), message(rename).startsWith("invalid username: "));
        } finally {
            a.stop();
        }
    }

    // OPS-AUTH-025 AC-6: .refreshTtl below one second names the unit like .accessTtl
    @Test
    public void OPS_AUTH_025_AC6_refreshTtlUnitIsNamed() {
        try {
            Auth.builder(AuthTestSupport.SECRET).refreshTtl(15).build();
            throw new AssertionError("built");
        } catch (AuthConfigException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("milliseconds") && e.getMessage().contains("900000"));
        }
    }
}
