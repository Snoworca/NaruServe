package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.testing.TestApp;
import com.snoworca.naruserve.utils.auth.apikey.ApiKeyStore;
import com.snoworca.naruserve.utils.auth.apikey.StoredApiKey;
import com.snoworca.naruserve.utils.auth.internal.Base64Url;
import com.snoworca.naruserve.utils.auth.internal.CsrfTokens;
import com.snoworca.naruserve.utils.auth.internal.HmacSigner;
import com.snoworca.naruserve.utils.auth.internal.TokenKeys;
import com.snoworca.naruserve.utils.auth.rbac.RolePolicy;
import com.snoworca.naruserve.utils.auth.testing.AuthTesting;
import com.snoworca.naruserve.utils.auth.user.AuthUser;
import com.snoworca.naruserve.utils.auth.user.FileUserStore;
import com.snoworca.naruserve.utils.auth.user.InMemoryUserStore;
import java.io.File;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The signed double-submit CSRF check of cookie-authenticated writes (SEC-FLOW-006) and the AuthTesting kit that
 * issues real tokens, CSRF tokens and keys without a back door (IR-AUTH-005), over the real HS256 path.
 */
public class Wave7CsrfTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final String ORIGIN = "http://localhost";
    private InMemoryHttpAdapter adapter;
    private TestApp app;
    private Auth auth;

    @After
    public void stop() {
        if (app != null) {
            app.app().shutdown(5, TimeUnit.SECONDS);
        }
    }

    private Auth.Builder builder(TokenTransport transport) {
        return Auth.builder(AuthTestSupport.SECRET).hashIterations(1000).logger(new RecordingLogger())
                .transport(transport).roles(RolePolicy.builder().role("editor", "posts:write").build());
    }

    private void start(Auth.Builder b) {
        adapter = new InMemoryHttpAdapter();
        app = TestApp.create(adapter);
        auth = b.build();
        auth.install(app.app());
        app.app().post("/api/posts", auth.require("posts:write"), (req, res) -> res.status(201).send("created"));
        for (String m : new String[] {"PUT", "PATCH", "DELETE"}) {
            app.app().method(m, "/api/posts", (req, res, next) -> res.status(204).end());
        }
        app.app().get("/api/posts", (req, res) -> res.send("list"));
        app.app().method("HEAD", "/api/posts", (req, res, next) -> res.status(200).end());
        app.app().method("OPTIONS", "/api/posts", (req, res, next) -> res.status(204).end());
        app.app().onError(auth.errors());
        app.start();
    }

    private ClientResponse send(String method, String at, List<String> csrfCookies, String header) {
        ClientRequest r = method.equals("GET") || method.equals("HEAD") || method.equals("OPTIONS")
                ? ClientRequest.of(method, "/api/posts") : ClientRequest.json(method, "/api/posts", "{}");
        StringBuilder cookie = new StringBuilder("naru_at=" + at);
        for (String c : csrfCookies) {
            cookie.append("; naru_csrf=").append(c);
        }
        // the same-origin Origin of a request whose Host is localhost (SEC-FLOW-007 compares Origin with the request's
        // own origin, which needs the Host header a browser always sends)
        r.header("Cookie", cookie.toString()).header("Host", "localhost").header("Origin", ORIGIN);
        if (header != null) {
            r.header("X-CSRF-Token", header);
        }
        return adapter.send(r);
    }

    private ClientResponse sendTo(String path, String at, List<String> csrfCookies, String header) {
        ClientRequest r = ClientRequest.json("POST", path, "{}");
        StringBuilder cookie = new StringBuilder("naru_at=" + at);
        for (String c : csrfCookies) {
            cookie.append("; naru_csrf=").append(c);
        }
        r.header("Cookie", cookie.toString()).header("Host", "localhost").header("Origin", ORIGIN);
        if (header != null) {
            r.header("X-CSRF-Token", header);
        }
        return adapter.send(r);
    }

    private String editorToken() {
        return AuthTesting.tokenFor(auth, "alice", "editor");
    }

    /** A CSRF value for the same user in another family (an attacker-planted cookie of an old session). */
    private String otherFamily(String sub) {
        return auth.csrfTokens().issue(sub, "another-family");
    }

    private String sub(String token) {
        return auth.tokens().verifyAccess(token).getSubject();
    }

    // SEC-FLOW-006 AC-1: COOKIE, naru_at of family F and naru_csrf plus X-CSRF-Token built from F, same-origin Origin,
    // POST /api/posts passes CSRF and returns the route's 201
    @Test
    public void SEC_FLOW_006_AC1_matchingFamilyPasses() {
        start(builder(TokenTransport.COOKIE));
        String at = editorToken();
        String csrf = AuthTesting.csrfTokenFor(auth, at);
        ClientResponse r = send("POST", at, Collections.singletonList(csrf), csrf);
        assertEquals(r.text(), 201, r.status());
    }

    // SEC-FLOW-006 AC-2: COOKIE, naru_csrf and X-CSRF-Token carrying a value built for another family G -> 403
    // csrf_failed
    @Test
    public void SEC_FLOW_006_AC2_otherFamilyIsRefused() {
        start(builder(TokenTransport.COOKIE));
        String at = editorToken();
        String planted = otherFamily(sub(at));
        ClientResponse r = send("POST", at, Collections.singletonList(planted), planted);
        assertEquals(403, r.status());
        assertEquals("csrf_failed", AuthTestSupport.code(r));
    }

    // SEC-FLOW-006 AC-3: COOKIE, X-CSRF-Token missing, or differing from the naru_csrf cookie -> 403 csrf_failed
    @Test
    public void SEC_FLOW_006_AC3_missingOrDifferentHeaderIsRefused() {
        start(builder(TokenTransport.COOKIE));
        String at = editorToken();
        String csrf = AuthTesting.csrfTokenFor(auth, at);
        String other = AuthTesting.csrfTokenFor(auth, at); // valid for F, but not the cookie's value
        assertNotEquals(csrf, other);
        for (ClientResponse r : Arrays.asList(send("POST", at, Collections.singletonList(csrf), null),
                send("POST", at, Collections.singletonList(csrf), other))) {
            assertEquals(r.text(), 403, r.status());
            assertEquals("csrf_failed", AuthTestSupport.code(r));
        }
    }

    // SEC-FLOW-006 AC-4: two naru_csrf cookies (F and an old family): X-CSRF-Token equal to the F value passes, equal
    // only to the old-family value -> 403 csrf_failed
    @Test
    public void SEC_FLOW_006_AC4_duplicateCookiesPassOnTheFamilyValue() {
        start(builder(TokenTransport.COOKIE));
        String at = editorToken();
        String f = AuthTesting.csrfTokenFor(auth, at);
        String old = otherFamily(sub(at));
        List<String> both = Arrays.asList(old, f);
        assertEquals(201, send("POST", at, both, f).status());
        ClientResponse r = send("POST", at, both, old);
        assertEquals(403, r.status());
        assertEquals("csrf_failed", AuthTestSupport.code(r));
    }

    // SEC-FLOW-006 AC-5: COOKIE or BOTH, cookie-authenticated GET, HEAD and OPTIONS are not CSRF-checked
    @Test
    public void SEC_FLOW_006_AC5_safeMethodsAreNotChecked() {
        for (TokenTransport t : new TokenTransport[] {TokenTransport.COOKIE, TokenTransport.BOTH}) {
            start(builder(t));
            String at = editorToken();
            for (String m : new String[] {"GET", "HEAD", "OPTIONS"}) {
                ClientResponse r = send(m, at, Collections.<String>emptyList(), null);
                assertTrue(t + " " + m + ": " + r.status(), r.status() == 200 || r.status() == 204);
            }
            stop();
            app = null;
        }
    }

    // SEC-FLOW-006 AC-8: BOTH, no Authorization, naru_at of family F: POST without X-CSRF-Token -> 403 csrf_failed;
    // with naru_csrf and X-CSRF-Token built from F -> the route's 201
    @Test
    public void SEC_FLOW_006_AC8_bothModeChecksTheCookieToken() {
        start(builder(TokenTransport.BOTH));
        String at = editorToken();
        ClientResponse r = send("POST", at, Collections.<String>emptyList(), null);
        assertEquals(403, r.status());
        assertEquals("csrf_failed", AuthTestSupport.code(r));
        String csrf = AuthTesting.csrfTokenFor(auth, at);
        assertEquals(201, send("POST", at, Collections.singletonList(csrf), csrf).status());
    }

    // Bearer writes need no CSRF token in HEADER and BOTH (component evidence only, not a citation; API-key
    // authentication arrives with the API key endpoints), and PUT, PATCH, DELETE are checked like POST
    @Test
    public void bearerWritesPassAndEveryUnsafeMethodIsChecked() {
        for (TokenTransport t : new TokenTransport[] {TokenTransport.HEADER, TokenTransport.BOTH}) {
            start(builder(t));
            String at = editorToken();
            ClientResponse r = adapter.send(ClientRequest.json("POST", "/api/posts", "{}")
                    .header("Authorization", "Bearer " + at).header("Origin", ORIGIN));
            assertEquals(t + ": " + r.text(), 201, r.status());
            stop();
            app = null;
        }
        start(builder(TokenTransport.COOKIE));
        String at = editorToken();
        String csrf = AuthTesting.csrfTokenFor(auth, at);
        for (String m : new String[] {"PUT", "PATCH", "DELETE"}) {
            assertEquals(m, 403, send(m, at, Collections.singletonList(csrf), null).status());
            assertEquals(m, 204, send(m, at, Collections.singletonList(csrf), csrf).status());
        }
    }

    // the token form of 05 §7 item 1: base64url(random16) "." base64url(HMAC over sub.fam.random16) under the
    // naru-csrf key (component level; the login-response criterion stays open)
    @Test
    public void csrfTokenForm() throws Exception {
        start(builder(TokenTransport.COOKIE));
        String at = editorToken();
        String csrf = AuthTesting.csrfTokenFor(auth, at);
        String[] parts = csrf.split("\\.");
        assertEquals(2, parts.length);
        assertEquals(16, Base64Url.decode(parts[0]).length);
        String fam = auth.tokens().verifyAccess(at).getString("fam");
        byte[] key = TokenKeys.derive(AuthTestSupport.SECRET.getBytes(StandardCharsets.UTF_8), TokenKeys.CSRF);
        byte[] mac = new HmacSigner(key).sign((sub(at) + "." + fam + "." + parts[0]).getBytes(StandardCharsets.UTF_8));
        assertEquals(Base64Url.encode(mac), parts[1]);
        CsrfTokens c = auth.csrfTokens();
        assertFalse(c.matches(csrf, sub(at), "other"));
        assertFalse(c.matches("x.y.z", sub(at), fam));
    }

    // ---------------------------------------------------------------------------------------------- AuthTesting

    // IR-AUTH-005 AC-1: tokenFor(auth, "alice", "editor") creates or overwrites alice with role editor and the token
    // passes require("posts:write"); an undeclared role -> IllegalArgumentException; another store ->
    // IllegalStateException
    @Test
    public void IR_AUTH_005_AC1_tokenForWritesRolesOnTheInMemoryStore() throws Exception {
        start(builder(TokenTransport.HEADER));
        String t = AuthTesting.tokenFor(auth, "alice", "editor");
        AuthUser alice = auth.userStore().findByUsername("alice");
        assertEquals(Collections.singleton("editor"), alice.getRoles());
        assertEquals(201, adapter.send(ClientRequest.json("POST", "/api/posts", "{}")
                .header("Authorization", "Bearer " + t)).status());
        AuthTesting.tokenFor(auth, "alice"); // overwrite: no roles
        assertTrue(auth.userStore().findByUsername("alice").getRoles().isEmpty());
        assertEquals(403, adapter.send(ClientRequest.json("POST", "/api/posts", "{}")
                .header("Authorization", "Bearer " + AuthTesting.tokenFor(auth, "alice"))).status());
        String again = AuthTesting.tokenFor(auth, "alice", "editor");
        assertEquals(alice.getId(), auth.userStore().findByUsername("alice").getId());
        assertEquals(201, adapter.send(ClientRequest.json("POST", "/api/posts", "{}")
                .header("Authorization", "Bearer " + again)).status());
        try {
            AuthTesting.tokenFor(auth, "bob", "undeclared");
            fail("an undeclared role was accepted");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("undeclared"));
        }
        Auth file = builder(TokenTransport.HEADER).users(new FileUserStore(new File(tmp.getRoot(), "u.json"),
                AuthTestSupport.FILE_KEY)).build();
        try {
            AuthTesting.tokenFor(file, "alice", "editor");
            fail("tokenFor wrote a FileUserStore");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("InMemoryUserStore"));
        }
    }

    // IR-AUTH-005 AC-2: tokenFor(auth, "alice") resolves to the name overload and issues a token for alice with no roles
    @Test
    public void IR_AUTH_005_AC2_twoArgumentsIsTheNameOverload() throws Exception {
        start(builder(TokenTransport.HEADER));
        String t = AuthTesting.tokenFor(auth, "alice");
        AuthUser alice = auth.userStore().findByUsername("alice");
        assertEquals(alice.getId(), sub(t));
        assertTrue(alice.getRoles().isEmpty());
    }

    // IR-AUTH-005 AC-3: tokenForUserId issues at the user's current ver without modifying it, on a non-in-memory store,
    // and throws IllegalArgumentException("no user with id ...") for an unknown id
    @Test
    public void IR_AUTH_005_AC3_tokenForUserIdOnAnyStore() throws Exception {
        FileUserStore store = new FileUserStore(new File(tmp.getRoot(), "users.json"), AuthTestSupport.FILE_KEY);
        AuthUser u = store.create(AuthUser.builder().username("carol").passwordHash("x").roles("editor").build());
        store.bumpTokenVersion(u.getId());
        AuthUser before = store.findById(u.getId());
        start(builder(TokenTransport.HEADER).users(store));
        String t = AuthTesting.tokenForUserId(auth, u.getId());
        assertEquals(Long.valueOf(before.getTokenVersion()), auth.tokens().verifyAccess(t).getLong("ver"));
        assertEquals(before, store.findById(u.getId()));
        assertEquals(201, adapter.send(ClientRequest.json("POST", "/api/posts", "{}")
                .header("Authorization", "Bearer " + t)).status());
        try {
            AuthTesting.tokenForUserId(auth, "missing-id");
            fail("an unknown id was accepted");
        } catch (IllegalArgumentException expected) {
            assertEquals("no user with id missing-id", expected.getMessage());
        }
    }

    // IR-AUTH-005 AC-4: AuthTesting declares apiKeyFor(auth, ownerUserId, scopes...) and it stores the key through the
    // configured ApiKeyStore's normal insert, without a back door
    @Test
    public void IR_AUTH_005_AC4_apiKeyForLivesOnAuthTesting() throws Exception {
        Method m = AuthTesting.class.getMethod("apiKeyFor", Auth.class, String.class, String[].class);
        assertTrue(Modifier.isStatic(m.getModifiers()) && m.isVarArgs());
        assertEquals(String.class, m.getReturnType());
        final List<StoredApiKey> inserted = new ArrayList<>();
        ApiKeyStore keys = new RecordingKeys(inserted);
        InMemoryUserStore users = new InMemoryUserStore();
        AuthUser owner = users.create(AuthUser.builder().username("owner").passwordHash("x").build());
        Auth a = builder(TokenTransport.HEADER).users(users).apiKeys(keys).build();
        String plain = AuthTesting.apiKeyFor(a, owner.getId(), "posts:read");
        assertEquals(1, inserted.size());
        StoredApiKey k = inserted.get(0);
        assertTrue(plain.startsWith("nk_" + k.getPrefix() + "_"));
        assertEquals(owner.getId(), k.getOwnerUserId());
        assertEquals(Collections.singleton("posts:read"), k.getScopes());
        assertEquals(null, k.getExpiresAt());
        assertFalse("the plaintext is never stored", k.getSecretHash().contains(plain.substring(plain.lastIndexOf('_') + 1)));
    }

    // IR-AUTH-005 AC-5: COOKIE, naru_at = a tokenFor token (no refresh record, synthetic fam) with csrfTokenFor(auth,
    // token) in naru_csrf and X-CSRF-Token and a same-origin Origin -> accepted by the handler
    @Test
    public void IR_AUTH_005_AC5_csrfTokenForWorksForTestTokens() throws Exception {
        start(builder(TokenTransport.COOKIE));
        String t = AuthTesting.tokenFor(auth, "alice", "editor");
        String fam = auth.tokens().verifyAccess(t).getString("fam");
        assertTrue(fam != null && !fam.isEmpty());
        String csrf = AuthTesting.csrfTokenFor(auth, t);
        ClientResponse r = send("POST", t, Collections.singletonList(csrf), csrf);
        assertEquals(r.text(), 201, r.status());
        String byId = AuthTesting.tokenForUserId(auth, auth.userStore().findByUsername("alice").getId());
        String csrf2 = AuthTesting.csrfTokenFor(auth, byId);
        assertEquals(201, send("POST", byId, Collections.singletonList(csrf2), csrf2).status());
    }

    // IR-AUTH-005 AC-6: AuthTesting declares verifyUserStore(store) and verifyApiKeyStore(store)
    @Test
    public void IR_AUTH_005_AC6_contractTestsLiveOnAuthTesting() throws Exception {
        Method u = AuthTesting.class.getMethod("verifyUserStore", com.snoworca.naruserve.utils.auth.user.UserStore.class);
        Method k = AuthTesting.class.getMethod("verifyApiKeyStore", ApiKeyStore.class);
        assertTrue(Modifier.isStatic(u.getModifiers()) && Modifier.isPublic(u.getModifiers()));
        assertTrue(Modifier.isStatic(k.getModifiers()) && Modifier.isPublic(k.getModifiers()));
    }

    // IR-AUTH-005 AC-7: the NaruServe-api main jar holds utils/auth/testing/AuthTesting.class, the testFixtures source
    // set holds no class of that package, and AuthTesting references no JUnit or other test-framework class
    @Test
    public void IR_AUTH_005_AC7_mainJarLocationWithoutTestFramework() throws Exception {
        String jarPath = System.getProperty("naruserve.apiJar");
        assertTrue("naruserve.apiJar is set", jarPath != null);
        String entry = "com/snoworca/naruserve/utils/auth/testing/AuthTesting.class";
        List<String> testingClasses = new ArrayList<>();
        try (ZipFile jar = new ZipFile(jarPath)) {
            ZipEntry e = jar.getEntry(entry);
            assertTrue(entry + " in " + jarPath, e != null);
            java.util.Enumeration<? extends ZipEntry> all = jar.entries();
            while (all.hasMoreElements()) {
                String n = all.nextElement().getName();
                if (n.startsWith("com/snoworca/naruserve/utils/auth/testing/") && n.endsWith(".class")) {
                    testingClasses.add(n);
                }
            }
            for (String n : testingClasses) {
                String text;
                try (InputStream in = jar.getInputStream(jar.getEntry(n))) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] b = new byte[4096];
                    for (int r; (r = in.read(b)) > 0;) {
                        out.write(b, 0, r);
                    }
                    text = new String(out.toByteArray(), StandardCharsets.ISO_8859_1);
                }
                for (String banned : new String[] {"org/junit", "junit/framework", "org/opentest4j", "kotlin/test",
                    "org/hamcrest", "org/assertj", "org/testng"}) {
                    assertFalse(n + " references " + banned, text.contains(banned));
                }
            }
        }
        File fixtures = new File("src/testFixtures/java/com/snoworca/naruserve/utils/auth/testing");
        assertFalse(fixtures + " exists", fixtures.exists());
        File fixtureClasses = new File("build/classes/java/testFixtures/com/snoworca/naruserve/utils/auth/testing");
        assertFalse(fixtureClasses + " exists", fixtureClasses.exists());
    }

    /** An in-memory key store recording each insertIfUnderLimit. */
    static final class RecordingKeys implements ApiKeyStore {
        private final List<StoredApiKey> rows;

        RecordingKeys(List<StoredApiKey> rows) {
            this.rows = rows;
        }

        @Override
        public synchronized StoredApiKey findByPrefix(String prefix) {
            for (StoredApiKey k : rows) {
                if (k.getPrefix().equals(prefix)) {
                    return k;
                }
            }
            return null;
        }

        @Override
        public synchronized StoredApiKey findById(String id) {
            for (StoredApiKey k : rows) {
                if (k.getId().equals(id)) {
                    return k;
                }
            }
            return null;
        }

        @Override
        public synchronized List<StoredApiKey> listByOwner(String ownerUserId) {
            List<StoredApiKey> out = new ArrayList<>();
            for (StoredApiKey k : rows) {
                if (k.getOwnerUserId().equals(ownerUserId)) {
                    out.add(k);
                }
            }
            return out;
        }

        @Override
        public synchronized boolean insertIfUnderLimit(StoredApiKey key, int maxLiveKeys, long nowMillis) {
            if (listByOwner(key.getOwnerUserId()).size() >= maxLiveKeys) {
                return false;
            }
            rows.add(key);
            return true;
        }

        @Override
        public boolean deleteByIdAndOwner(String keyId, String ownerUserId) {
            return false;
        }

        @Override
        public boolean deleteById(String keyId) {
            return false;
        }

        @Override
        public int deleteAllByOwner(String ownerUserId) {
            return 0;
        }

        @Override
        public int deleteStaleByOwner(String ownerUserId, long currentOwnerVer) {
            return 0;
        }

        @Override
        public int deleteExpired(long nowMillis) {
            return 0;
        }
    }

    // SEC-FLOW-006: a guarded cookie write on a public path (outside .protectOnly) needs the CSRF token too: without
    // it the naru_at principal is not attached, so the guard answers 401 and the handler does not run; with the token
    // of the session the guarded handler runs
    @Test
    public void SEC_FLOW_006_guardedCookieWriteOnAPublicPathNeedsTheCsrfToken() {
        adapter = new InMemoryHttpAdapter();
        app = TestApp.create(adapter);
        auth = builder(TokenTransport.COOKIE).protectOnly("/api").build();
        auth.install(app.app());
        List<String> ran = Collections.synchronizedList(new ArrayList<String>());
        app.app().post("/account/close", auth.authenticated(), (req, res) -> {
            ran.add(auth.user(req).getId());
            res.send("closed");
        });
        app.app().post("/account/open", (req, res) -> res.send(auth.userOrNull(req) == null ? "anonymous" : "user"));
        app.app().onError(auth.errors());
        app.start();
        String at = editorToken();
        String csrf = AuthTesting.csrfTokenFor(auth, at);
        ClientResponse missing = sendTo("/account/close", at, Collections.singletonList(csrf), null);
        assertEquals(missing.text(), 401, missing.status());
        String planted = otherFamily(sub(at));
        ClientResponse foreign = sendTo("/account/close", at, Collections.singletonList(planted), planted);
        assertEquals(foreign.text(), 401, foreign.status());
        assertTrue(ran.isEmpty());
        assertEquals("anonymous", sendTo("/account/open", at, Collections.<String>emptyList(), null).text());
        ClientResponse ok = sendTo("/account/close", at, Collections.singletonList(csrf), csrf);
        assertEquals(ok.text(), 200, ok.status());
        assertEquals(1, ran.size());
        assertEquals("user", sendTo("/account/open", at, Collections.singletonList(csrf), csrf).text());
    }
}
