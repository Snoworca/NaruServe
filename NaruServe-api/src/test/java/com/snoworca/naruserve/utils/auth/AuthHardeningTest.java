package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.TestApp;
import com.snoworca.naruserve.utils.auth.group.FileGroupStore;
import com.snoworca.naruserve.utils.auth.internal.Passwords;
import com.snoworca.naruserve.utils.auth.user.AuthUser;
import com.snoworca.naruserve.utils.auth.user.InMemoryUserStore;
import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.Properties;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Review findings of the facade: path variants, late routes, admin exposure, key roles, store durability. */
public class AuthHardeningTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static TestApp remote(Auth auth) {
        TestApp t = TestApp.create(new InMemoryHttpAdapter("203.0.113.9", false));
        auth.routes().post("/login", (req, res) -> res.send("login handler"));
        auth.adminRoutes().get("/users", (req, res) -> res.send("admin handler"));
        auth.install(t.app());
        t.app().get("/api/secret", (req, res) -> res.send("secret"));
        return t;
    }

    // SEC-FLOW-001 component (path variants): letter case and trailing slashes never escape the protected range or
    // the credential-endpoint rule, because the default router ignores both
    @Test
    public void caseAndTrailingSlashVariantsStayProtected() {
        TestApp only = remote(AuthTestSupport.builder().protectOnly("/api").mode(AuthMode.PROD).build());
        for (String[] r : new String[][] {{"GET", "/API/secret"}, {"GET", "/api/secret/"}, {"GET", "/Api//secret"},
                {"GET", "/Auth/admin/users"}, {"POST", "/AUTH/login"}, {"POST", "/auth/login/"}}) {
            ClientResponse res = only.send(r[0], r[1]);
            assertEquals(r[0] + " " + r[1] + " " + res.text(), 403, res.status());
            assertEquals("insecure_transport", AuthTestSupport.code(res));
        }
        TestApp prefixed = remote(AuthTestSupport.builder().permitPrefix("/auth").build());
        assertEquals(403, prefixed.send("POST", "/auth/login/").status());
        assertEquals(403, prefixed.send("POST", "/Auth/Login").status());
    }

    // FR-AUTH-005 component (admin range): a permitPrefix over the mount never makes the admin endpoints public
    @Test
    public void permitPrefixNeverOpensTheAdminRouter() {
        TestApp t = TestApp.create();
        Auth auth = AuthTestSupport.builder().permitPrefix("/auth").build();
        auth.adminRoutes().get("/users", (req, res) -> res.send("admin handler"));
        auth.install(t.app());
        ClientResponse r = t.send("GET", "/auth/admin/users");
        assertEquals(401, r.status());
        assertEquals(401, t.send("GET", "/AUTH/Admin/users/").status());
    }

    // FR-AUTH-006 component (late routes): a route added to auth.routes() after install still gets the JSON errors
    @Test
    public void routesAddedAfterInstallKeepTheJsonErrors() {
        TestApp t = TestApp.create();
        Auth auth = AuthTestSupport.builder().permitPrefix("/auth/late").build();
        auth.install(t.app());
        auth.routes().get("/late", (req, res) -> {
            throw new IllegalStateException("SECRET-XYZ");
        });
        ClientResponse r = t.send("GET", "/auth/late");
        assertEquals(500, r.status());
        assertTrue(r.header("Content-Type"), r.header("Content-Type").startsWith("application/json"));
        assertEquals("INTERNAL_ERROR", AuthTestSupport.code(r));
        assertFalse(r.text().contains("SECRET-XYZ"));
    }

    // IR-AUTH-004 component (role guards and keys): an API-key principal never passes requireRole
    @Test
    public void requireRoleRefusesApiKeys() {
        InMemoryUserStore store = new InMemoryUserStore();
        AuthUser admin = store.create(AuthUser.builder().username("root").roles("admin").build());
        Auth auth = AuthTestSupport.builder().users(store).build();
        TestApp t = TestApp.create();
        auth.install(t.app());
        t.app().get("/ops", auth.requireRole("admin"), (req, res) -> res.send("ops"));
        assertEquals(200, t.send("GET", "/ops", AuthTestSupport.HEADER, admin.getId() + ":0").status());
        ClientResponse key = t.send("GET", "/ops", AuthTestSupport.HEADER, admin.getId() + ":0:k1:posts:read");
        assertEquals(403, key.status());
        assertEquals("api_key_not_allowed", AuthTestSupport.code(key));
    }

    // IR-IAM-004 component (durability): a failed file write leaves memory as it was
    @Test
    public void failedGroupWriteRollsBack() throws Exception {
        File dir = tmp.newFolder();
        File f = new File(dir, "groups.txt");
        FileGroupStore s = new FileGroupStore(f);
        s.createGroup("eng");
        assertTrue(dir.setWritable(false));
        try {
            s.createGroup("ops");
            fail("the write succeeded in a read-only directory");
        } catch (java.io.IOException expected) {
            // the disk refused it
        } finally {
            dir.setWritable(true);
        }
        assertEquals(Collections.singletonList("eng"), s.listGroups());
        assertEquals(Collections.singletonList("eng"), new FileGroupStore(f).listGroups());
    }

    // IR-IAM-003 component (hash bounds): a stored hash with absurd rounds or key length is refused without hashing
    @Test
    public void storedHashCostIsBounded() {
        Passwords p = new Passwords(1000);
        String good = p.hash("pw".toCharArray());
        assertTrue(p.verify("pw".toCharArray(), good));
        String[] parts = good.split("\\$");
        long start = System.nanoTime();
        assertFalse(p.verify("pw".toCharArray(), "pbkdf2-sha256$2147483647$" + parts[2] + "$" + parts[3]));
        assertFalse(p.verify("pw".toCharArray(), "pbkdf2-sha256$1000$" + parts[2] + "$AA"));
        assertTrue((System.nanoTime() - start) / 1_000_000 < 5_000);
    }

    // FR-AUTH-002 component (reuse): one AuthConfig applied twice reports each violation once per builder
    @Test
    public void reusedConfigDoesNotRepeatViolations() {
        Properties p = new Properties();
        p.setProperty("naru.auth.mode", "staging");
        AuthConfig cfg = AuthConfig.from(p);
        for (int i = 0; i < 2; i++) {
            try {
                AuthTestSupport.builder().config(cfg).build();
                fail("accepted");
            } catch (AuthConfigException e) {
                assertEquals(e.getViolations().toString(), 1, e.getViolations().size());
            }
        }
        try {
            AuthTestSupport.builder().config(cfg).config(cfg).build();
            fail("accepted");
        } catch (AuthConfigException e) {
            assertEquals(Arrays.asList(e.getViolations().get(0), e.getViolations().get(0)), e.getViolations());
        }
    }
}
