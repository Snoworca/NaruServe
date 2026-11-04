package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.testing.TestApp;
import com.snoworca.naruserve.utils.auth.group.InMemoryGroupStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Test;

/** The code findings of the fourteenth junior study are answered with the fix (OPS-AUTH-026). */
public class JuniorRound14Test {

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

    // OPS-AUTH-026 AC-1: auth.user(req) of an Auth that was never installed names the missing install
    @Test
    public void OPS_AUTH_026_AC1_userOfUninstalledAuthNamesInstall() {
        Auth auth = Auth.builder(AuthTestSupport.SECRET).logger(new RecordingLogger()).build();
        TestApp t = TestApp.create();
        t.app().get("/me", (req, res) -> {
            try {
                res.send(auth.user(req).getId());
            } catch (AuthStateException e) {
                res.send(String.valueOf(e.getMessage()));
            }
        });
        t.start();
        String m = t.send("GET", "/me").text();
        assertTrue(m, m.contains("auth is not installed on this app") && m.contains("app.use(auth)"));
    }

    // OPS-AUTH-026 AC-2: a group role the RolePolicy does not declare is warned once when it reaches a user
    @Test
    public void OPS_AUTH_026_AC2_undeclaredGroupRoleIsWarned() throws Exception {
        W8 f = fixture();
        InMemoryGroupStore groups = new InMemoryGroupStore();
        f.start(b -> b.groups(groups).userCacheTtl(0));
        String id = f.user("dave", W8.PW);
        groups.createGroup("eng");
        groups.addMember("eng", id);
        groups.grantRole("eng", "editors");
        String t = String.valueOf(f.loginBody("dave", W8.PW).get("accessToken"));
        for (int i = 0; i < 2; i++) {
            f.get("/api/x", "Authorization", "Bearer " + t);
        }
        List<String> warns = RecordingLogger.matching(f.logger.lines, "(?s)WARN .*'editors'.*");
        assertEquals(f.logger.lines.toString(), 1, warns.size());
        assertTrue(warns.get(0), warns.get(0).contains("a group grant"));
    }

    // OPS-AUTH-026 AC-3: a device token sent as Bearer names the Naru-Device header
    @Test
    public void OPS_AUTH_026_AC3_bearerDeviceTokenIsNamed() throws Exception {
        W8 f = fixture();
        f.start(b -> b);
        f.user("bob", W8.PW, "reader");
        assertEquals(401, f.post("/auth/login", W8.login("bob", "wrong-password-x")).status());
        Object device = f.loginBody("bob", W8.PW).get("deviceToken");
        assertTrue(String.valueOf(device), device instanceof String);
        String m = message(f.get("/api/x", "Authorization", "Bearer " + device));
        assertTrue(m, m.contains("this is a device token") && m.contains("Naru-Device"));
    }

    // OPS-AUTH-026 AC-4: the non-admin null-password FAIL asks whether the environment variable is set
    @Test
    public void OPS_AUTH_026_AC4_nullPasswordFailNamesTheVariable() {
        Auth auth = Auth.builder(AuthTestSupport.SECRET).logger(new RecordingLogger())
                .user("bob", null, "reader").build();
        TestApp t = TestApp.create();
        auth.install(t.app());
        String all = String.valueOf(auth.doctor(t.app()).findings());
        assertTrue(all, all.contains("is the environment variable that holds its password set?"));
    }
}
