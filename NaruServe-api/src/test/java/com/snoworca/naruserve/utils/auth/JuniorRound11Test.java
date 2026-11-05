package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.testing.TestApp;
import com.snoworca.naruserve.utils.auth.internal.Doctor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Test;

/** The code findings of the eleventh junior study are answered with the fix (OPS-AUTH-023). */
public class JuniorRound11Test {

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

    // OPS-AUTH-023 AC-1: a guard of an Auth that was never installed answers 500 and logs the never-installed ERROR
    @Test
    public void OPS_AUTH_023_AC1_guardOfUninstalledAuthIsLoud() {
        RecordingLogger log = new RecordingLogger();
        Auth auth = Auth.builder(AuthTestSupport.SECRET).logger(log).build();
        TestApp t = TestApp.create();
        t.app().get("/x", auth.require("posts:read"), (req, res) -> res.send("x"));
        t.start();
        ClientResponse r = t.send("GET", "/x");
        assertEquals(r.text(), 500, r.status());
        List<String> errors = RecordingLogger.matching(log.lines, "(?s)ERROR .*never installed.*app\\.use\\(auth\\).*");
        assertEquals(log.lines.toString(), 1, errors.size());
    }

    // OPS-AUTH-023 AC-2: the secure-transport WARN names the lifetimes it forces over a configured accessTtl
    @Test
    public void OPS_AUTH_023_AC2_insecureTransportNamesForcedLifetimes() {
        W8 f = fixture();
        f.start(b -> b.allowInsecureTransport("LAN demo").accessTtl(3_600_000L));
        List<String> warns = RecordingLogger.matching(f.logger.lines, "(?s)WARN .*secure-transport check is off.*");
        assertEquals(f.logger.lines.toString(), 1, warns.size());
        assertTrue(warns.get(0), warns.get(0).contains("5 minutes") && warns.get(0).contains(".accessTtl(3600000)"));
    }

    // OPS-AUTH-023 AC-3: deleting an unknown own key names the id field
    @Test
    public void OPS_AUTH_023_AC3_unknownOwnKeyNamesTheId() throws Exception {
        W8 f = fixture();
        f.start(b -> b.apiKeys(new AdminFixture.ListKeys()));
        f.user("bob", W8.PW, "reader");
        String t = String.valueOf(f.loginBody("bob", W8.PW).get("accessToken"));
        ClientResponse r = f.adapter.send(ClientRequest.of("DELETE", "/auth/me/apikeys/aBcd1234")
                .header("Authorization", "Bearer " + t));
        assertEquals(r.text(), 404, r.status());
        assertEquals("no API key with id aBcd1234; use the id field of the key, not its prefix", message(r));
    }

    // OPS-AUTH-023 AC-4: a permitPrefix covering the auth mount is a doctor WARN
    @Test
    public void OPS_AUTH_023_AC4_permitPrefixOverMountIsWarned() {
        Auth auth = Auth.builder(AuthTestSupport.SECRET).logger(new RecordingLogger()).permitPrefix("/auth").build();
        TestApp t = TestApp.create();
        auth.install(t.app());
        List<String> warns = new ArrayList<>();
        for (Doctor.Finding x : auth.doctor(t.app()).findings()) {
            if (x.getLevel() == Doctor.Level.WARN && x.getItem().equals("permit prefix")) {
                warns.add(x.getMessage());
            }
        }
        assertEquals(warns.toString(), 1, warns.size());
        assertTrue(warns.get(0), warns.get(0).contains("/auth/me") && warns.get(0).contains("still need a token"));
    }

    // OPS-AUTH-023 AC-5: a secret with surrounding whitespace logs a WARN at build
    @Test
    public void OPS_AUTH_023_AC5_secretWhitespaceIsWarned() {
        RecordingLogger log = new RecordingLogger();
        Auth.builder(AuthTestSupport.SECRET + "\n").logger(log).build();
        List<String> warns = RecordingLogger.matching(log.lines, "(?s)WARN .*secret.*whitespace.*");
        assertEquals(log.lines.toString(), 1, warns.size());
    }

    // OPS-AUTH-023 AC-6: the sign-up hint says when sign-up is off
    @Test
    public void OPS_AUTH_023_AC6_signupHintSaysSignUpIsOff() {
        W8 f = fixture();
        f.start(b -> b);
        assertEquals("authentication is required; auth's register endpoint is POST /auth/register, and sign-up is off "
                + "(build with .register(true))", message(f.post("/auth/signup", "{}")));
    }

    // OPS-AUTH-023 AC-1: all four guard kinds of an Auth never installed answer 500 INTERNAL_ERROR and log the one ERROR
    @Test
    public void OPS_AUTH_023_AC1_everyGuardKindOfUninstalledAuthIsLoud() {
        for (int kind = 0; kind < 4; kind++) {
            RecordingLogger log = new RecordingLogger();
            Auth auth = Auth.builder(AuthTestSupport.SECRET).logger(log).build();
            com.snoworca.naruserve.Middleware guard = kind == 0 ? auth.require("posts:read")
                    : kind == 1 ? auth.requireRole("admin") : kind == 2 ? auth.check((p, req) -> true)
                    : auth.authenticated();
            TestApp t = TestApp.create();
            t.app().get("/x", guard, (req, res) -> res.send("x"));
            t.start();
            for (int i = 0; i < 2; i++) {
                ClientResponse r = t.send("GET", "/x");
                assertEquals(r.text(), 500, r.status());
                assertTrue(r.text(), r.text().contains("INTERNAL_ERROR"));
            }
            assertEquals(log.lines.toString(), 1,
                    RecordingLogger.matching(log.lines, "(?s)ERROR .*never installed.*app\\.use\\(auth\\).*").size());
        }
    }

    // OPS-AUTH-023 AC-2: the WARN names both forced lifetimes and a refreshTtl it overrides
    @Test
    public void OPS_AUTH_023_AC2_insecureTransportNamesForcedRefreshLifetime() {
        W8 f = fixture();
        f.start(b -> b.allowInsecureTransport("LAN demo").refreshTtl(604_800_000L));
        List<String> warns = RecordingLogger.matching(f.logger.lines, "(?s)WARN .*secure-transport check is off.*");
        assertEquals(f.logger.lines.toString(), 1, warns.size());
        assertTrue(warns.get(0), warns.get(0).contains("5 minutes") && warns.get(0).contains("24 hours")
                && warns.get(0).contains(".refreshTtl(604800000)"));
    }

    // OPS-AUTH-023 AC-4: the permit prefix WARN says the admin endpoints still need a token too
    @Test
    public void OPS_AUTH_023_AC4_permitPrefixWarnNamesTheAdminEndpoints() {
        Auth auth = Auth.builder(AuthTestSupport.SECRET).logger(new RecordingLogger()).permitPrefix("/auth").build();
        TestApp t = TestApp.create();
        auth.install(t.app());
        String all = String.valueOf(auth.doctor(t.app()).findings());
        assertTrue(all, all.contains("the admin endpoints still need a token"));
    }

    // OPS-AUTH-023 AC-5: leading whitespace in the secret is warned like trailing whitespace
    @Test
    public void OPS_AUTH_023_AC5_leadingSecretWhitespaceIsWarned() {
        RecordingLogger log = new RecordingLogger();
        Auth.builder(" " + AuthTestSupport.SECRET).logger(log).build();
        assertEquals(log.lines.toString(), 1, RecordingLogger.matching(log.lines, "(?s)WARN .*secret.*whitespace.*").size());
    }
}
