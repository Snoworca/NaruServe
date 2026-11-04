package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.utils.auth.internal.Doctor;
import com.snoworca.naruserve.utils.auth.password.Pbkdf2PasswordHasher;
import com.snoworca.naruserve.utils.auth.testing.AuthTesting;
import com.snoworca.naruserve.utils.auth.user.AuthUser;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.ServiceLoader;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.junit.After;
import org.junit.Test;

/**
 * Auth persistence is SPI-only (CON-IAM-003): no built-in database store in the main jar, no JDBC driver on any
 * NaruServe-api classpath, MULTI_INSTANCE over app-provided stores, and the docs that tell how to write one.
 */
public class SpiOnlyPersistenceTest {

    private final W8 node = new W8();

    @After
    public void stop() {
        node.stop();
    }

    // CON-IAM-003 AC-1: the main jar holds no class or resource under utils/auth/jdbc/ and no reference DDL, and no
    // compiled utils class references java/sql/ or javax/sql/
    @Test
    public void CON_IAM_003_AC1_noBuiltInDatabaseStoreInTheMainJar() throws IOException {
        List<String> jdbcEntries = new ArrayList<>();
        int utilsEntries = 0;
        try (JarFile jar = new JarFile(new File(System.getProperty("naruserve.apiJar")))) {
            Enumeration<JarEntry> e = jar.entries();
            while (e.hasMoreElements()) {
                String name = e.nextElement().getName();
                utilsEntries += name.startsWith("com/snoworca/naruserve/utils/") ? 1 : 0;
                if (name.startsWith("com/snoworca/naruserve/utils/auth/jdbc/") || name.endsWith(".sql")) {
                    jdbcEntries.add(name);
                }
            }
        }
        assertTrue("the jar was read", utilsEntries > 0);
        assertEquals(Collections.emptyList(), jdbcEntries);
        List<AuthArchitectureTest.ClassInfo> classes = AuthArchitectureTest.utilsClasses();
        assertEquals(Collections.emptyList(),
                AuthArchitectureTest.violations(classes, java.util.Arrays.asList("java/sql/", "javax/sql/")));
        List<String> ddl = new ArrayList<>();
        for (AuthArchitectureTest.ClassInfo c : classes) {
            for (String s : c.utf8) {
                if (s.toUpperCase(Locale.ROOT).contains("CREATE TABLE")) {
                    ddl.add(c.name + ": " + s);
                }
            }
        }
        assertEquals("no reference DDL", Collections.emptyList(), ddl);
    }

    // CON-IAM-003 AC-2 (the test classpath side; the build.gradle side is AuthPlacementTest): no JDBC driver is on the
    // NaruServe-api test runtime classpath, so no driver registers itself and the former SQLite driver is absent
    @Test
    public void CON_IAM_003_AC2_noJdbcDriverOnTheTestClasspath() {
        Iterator<java.sql.Driver> drivers = ServiceLoader.load(java.sql.Driver.class).iterator();
        List<String> found = new ArrayList<>();
        while (drivers.hasNext()) {
            found.add(drivers.next().getClass().getName());
        }
        assertEquals(Collections.emptyList(), found);
        for (String driver : new String[] {"org.sqlite.JDBC", "org.h2.Driver", "com.mysql.cj.jdbc.Driver",
                "org.postgresql.Driver", "org.mariadb.jdbc.Driver", "org.hsqldb.jdbc.JDBCDriver"}) {
            try {
                Class.forName(driver);
                fail(driver + " is on the test classpath");
            } catch (ClassNotFoundException expected) {
                // absent, as required
            }
        }
    }

    // CON-IAM-003 AC-3: deployment(MULTI_INSTANCE) over app-provided UserStore (supportsAtomicFieldWrites true),
    // GroupStore, RefreshTokenStore, LoginThrottle (usesLimitArguments true), DeviceTrust and ApiKeyStore
    // (supportsAtomicLimitInsert true), none a built-in in-memory or file store: build() succeeds, doctor reports no
    // FAIL, the app starts, and a login, a refresh and an API key request each succeed
    @Test
    public void CON_IAM_003_AC3_multiInstanceRunsOnAppProvidedStores() throws Exception {
        AppStores db = new AppStores(node.clock::get);
        AppStores.Users users = db.users();
        String alice = users.create(AuthUser.builder().username("alice")
                .passwordHash(new Pbkdf2PasswordHasher(1000).hash(W8.PW.toCharArray())).roles("reader").build())
                .getId();
        AppStores.Throttle throttle = db.loginThrottle();
        AppStores.Keys keys = db.apiKeys();
        assertTrue(users.supportsAtomicFieldWrites());
        assertTrue(throttle.usesLimitArguments());
        assertTrue(keys.supportsAtomicLimitInsert());
        node.users = users;
        node.start(b -> b.deployment(Deployment.MULTI_INSTANCE).groups(db.groups()).refreshTokens(db.refreshTokens())
                .loginThrottle(throttle).deviceTrust(db.deviceTrust()).apiKeys(keys));
        Auth auth = node.auth;
        assertSame(users, auth.userStore());
        for (String field : new String[] {"apiKeys", "groups"}) {
            java.lang.reflect.Field f = Auth.class.getDeclaredField(field);
            f.setAccessible(true);
            Object store = f.get(auth);
            assertNotNull(field, store);
            assertTrue(field + ": " + store.getClass().getName(),
                    store.getClass().getName().startsWith(AppStores.class.getName()));
        }
        assertSame(keys, readField(auth, "apiKeys"));
        for (Object store : new Object[] {auth.refreshTokens(), auth.loginThrottle(), auth.deviceTrust()}) {
            assertTrue(store.getClass().getName(), store.getClass().getName().startsWith(AppStores.class.getName()));
        }
        List<String> fails = new ArrayList<>();
        for (Doctor.Finding f : auth.doctor(node.app).findings()) {
            if (f.getLevel() == Doctor.Level.FAIL) {
                fails.add(f.toString());
            }
        }
        assertEquals(Collections.emptyList(), fails);

        ClientResponse login = node.post("/auth/login", W8.login("alice", W8.PW));
        assertEquals(login.text(), 200, login.status());
        String access = (String) W8.object(login).get("accessToken");
        String refreshToken = (String) W8.object(login).get("refreshToken");
        assertNotNull(access);
        assertNotNull(refreshToken);
        assertEquals(200, node.get("/api/x", "Authorization", "Bearer " + access).status());
        ClientResponse refresh = node.post("/auth/refresh", "{\"refreshToken\":\"" + refreshToken + "\"}");
        assertEquals(refresh.text(), 200, refresh.status());
        assertNotNull(W8.object(refresh).get("accessToken"));

        String key = AuthTesting.apiKeyFor(auth, alice, "posts:read");
        ClientResponse keyed = node.get("/api/x", "Authorization", "ApiKey " + key);
        assertEquals(keyed.text(), 200, keyed.status());
        assertEquals("x:" + alice, keyed.text());
        assertEquals(1, keys.listByOwner(alice).size());
    }

    private static Object readField(Auth auth, String name) throws ReflectiveOperationException {
        java.lang.reflect.Field f = Auth.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(auth);
    }

    // FR-AUTH-006 component (CON-IAM-003): a store failure is an IOException or any java.sql.SQLException subclass of
    // an app-provided database store, matched by class name; a runtime bug or another checked exception is not one
    @Test
    public void storeFailuresAreMatchedThroughTheClassHierarchy() {
        assertTrue(Auth.isStoreFailure(new java.sql.SQLException("down")));
        assertTrue(Auth.isStoreFailure(new java.sql.SQLTransientConnectionException("pool exhausted")));
        assertTrue(Auth.isStoreFailure(new java.sql.SQLIntegrityConstraintViolationException("dup")));
        assertTrue(Auth.isStoreFailure(new java.io.FileNotFoundException("users.json")));
        assertFalse(Auth.isStoreFailure(new IllegalStateException("bug")));
        assertFalse(Auth.isStoreFailure(new Exception("other")));
    }

    private static String page(String path) throws IOException {
        File f = new File(System.getProperty("user.dir"), "../docs/api/" + path);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[8192];
            for (int r; (r = in.read(b)) > 0;) {
                out.write(b, 0, r);
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8).replaceAll("\\s+", " ");
    }

    /** One level-2 section of a page, from its heading to the next level-2 heading. */
    private static String section(String page, String heading) {
        int start = page.indexOf(heading);
        assertTrue("no section " + heading, start >= 0);
        int end = page.indexOf(" ## ", start + heading.length());
        return page.substring(start, end < 0 ? page.length() : end);
    }

    private static void has(String where, String text, String... needles) {
        for (String n : needles) {
            assertTrue(where + " lacks: " + n, text.contains(n));
        }
    }

    /** The statements of the documented SQL UserStore skeleton: every field method is one conditional write. */
    private static final String[] SKELETON = {
        "implements UserStore",
        "UPDATE users SET password_hash=?, ver=ver+1 WHERE id=? AND ver=? AND (password_hash=? OR "
                + "(password_hash IS NULL AND ? IS NULL))",
        "UPDATE users SET password_hash=? WHERE id=? AND password_hash=?",
        "UPDATE users SET enabled=? WHERE id=?",
        "UPDATE users SET ver=ver+1 WHERE id=?",
        "UPDATE users SET roles=? WHERE id=?",
        "UPDATE users SET username=?, attributes=? WHERE id=?",
        "public boolean supportsAtomicFieldWrites() { return true; }",
    };

    private static final String[] DECLARATIONS = {
        "`supportsAtomicFieldWrites()`", "`supportsAtomicLimitInsert()`", "`usesLimitArguments()`",
    };

    // CON-IAM-003 AC-4: both auth pages state that auth has no built-in database store, show an app-provided SQL
    // UserStore skeleton whose changePassword and field methods are single conditional writes, and list the
    // atomicity declarations of a MULTI_INSTANCE store
    @Test
    public void CON_IAM_003_AC4_docsShowTheAppProvidedDatabaseStore() throws IOException {
        String ko = section(page("auth.md"), "## 자체 데이터베이스 연결");
        has("auth.md", ko, "auth에는 내장 데이터베이스 저장소가 없습니다.",
                "`deployment(MULTI_INSTANCE)`의 저장소가 해야 하는 원자성 선언:");
        has("auth.md", ko, SKELETON);
        has("auth.md", ko, DECLARATIONS);
        String en = section(page("en/auth.md"), "## Connecting your own database");
        has("en/auth.md", en, "auth has no built-in database store.",
                "The atomicity declarations a `deployment(MULTI_INSTANCE)` store must make:");
        has("en/auth.md", en, SKELETON);
        has("en/auth.md", en, DECLARATIONS);
        for (String p : new String[] {"auth.md", "en/auth.md"}) {
            String text = page(p);
            for (String gone : new String[] {"utils.auth.jdbc", "JdbcUserStore", "AuthSchema", "AuthDialect",
                "JdbcLoginThrottle", "JdbcDeviceTrust", "JdbcApiKeyStore", "JdbcRefreshTokenStore",
                "JdbcGroupStore"}) {
                assertFalse(p + " still names " + gone, text.contains(gone));
            }
        }
    }

    // REL-IAM-003 AC-5: both auth pages tell how an app-provided MULTI_INSTANCE UserStore declares atomicity: true from
    // supportsAtomicFieldWrites() only when each of the five field methods is one atomic write (for a SQL database, one
    // conditional UPDATE) and update() writes none of passwordHash, ver, enabled and roles
    @Test
    public void REL_IAM_003_AC5_docsTellTheAtomicFieldWritesDeclaration() throws IOException {
        has("auth.md", section(page("auth.md"), "## 자체 데이터베이스 연결"),
                "다섯 필드 메서드가 각각 원자적 쓰기 한 번(SQL 데이터베이스라면 조건부 `UPDATE` 한 문장)이고 "
                        + "`update()`가 `passwordHash`, `ver`, `enabled`, `roles`를 쓰지 않을 때만 "
                        + "`supportsAtomicFieldWrites()`가 `true`를 돌려줍니다.");
        has("en/auth.md", section(page("en/auth.md"), "## Connecting your own database"),
                "returns `true` from `supportsAtomicFieldWrites()` only when each of the five field methods is one "
                        + "atomic write (for a SQL database, one conditional `UPDATE`) and `update()` writes none of "
                        + "`passwordHash`, `ver`, `enabled` and `roles`.");
    }
}
