package com.snoworca.naruserve.utils.auth.testing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.utils.auth.AppStores;
import com.snoworca.naruserve.utils.auth.user.AuthUser;
import com.snoworca.naruserve.utils.auth.user.DuplicateUserException;
import com.snoworca.naruserve.utils.auth.user.FileUserStore;
import com.snoworca.naruserve.utils.auth.user.InMemoryUserStore;
import com.snoworca.naruserve.utils.auth.user.UserStore;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** AuthTesting.verifyUserStore (FR-IAM-004): the shipped stores pass, each broken store fails naming its clause. */
public class VerifyUserStoreTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** The four security fields update() must leave alone in an atomic store. */
    static final String PASSWORD_HASH = "passwordHash";
    static final String VER = "ver";
    static final String ENABLED = "enabled";
    static final String ROLES = "roles";

    /**
     * A map store with switchable faults: atomic field methods, a declaration chosen by the test, and an update() that
     * writes username and attributes plus the fields named in {@code leaks}. With no fault it keeps the contract.
     */
    static final class TestStore implements UserStore {
        final Map<String, AuthUser> rows = new LinkedHashMap<>();
        final Set<String> leaks = new HashSet<>();
        boolean declaresAtomic = true;
        boolean acceptsDuplicates;
        boolean dropsHash;
        boolean changePasswordIgnoresVer;
        boolean changePasswordRejectsNullHash;
        /** Gives a new user MAX(id)+1, so deleting the newest user frees its id for the next create. */
        boolean reusesHighestId;
        /**
         * The "disk" this store writes through to (a Gson file rewrite): the in-memory rows keep the hash, the disk
         * copy drops the transient passwordHash when {@link #diskDropsHash}; {@link #reopen()} reads the disk back.
         */
        Map<String, AuthUser> disk = new LinkedHashMap<>();
        boolean diskDropsHash;
        private long nextId = 1;

        /** A fresh instance over the same disk, as a restart would open it. */
        synchronized TestStore reopen() {
            TestStore fresh = new TestStore();
            fresh.disk = disk;
            fresh.diskDropsHash = diskDropsHash;
            fresh.rows.putAll(disk);
            fresh.nextId = nextId;
            return fresh;
        }

        private AuthUser persist(AuthUser u) {
            // a Gson-persisted row loses the transient passwordHash on save
            AuthUser stored = dropsHash ? u.toBuilder().passwordHash(null).build() : u;
            rows.put(stored.getId(), stored);
            disk.put(stored.getId(), diskDropsHash ? stored.toBuilder().passwordHash(null).build() : stored);
            return stored;
        }

        private boolean nameTaken(String username, @Nullable String exceptId) {
            for (AuthUser u : rows.values()) {
                if (u.getUsername().equals(username) && !u.getId().equals(exceptId)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public synchronized @Nullable AuthUser findByUsername(String username) {
            for (AuthUser u : rows.values()) {
                if (u.getUsername().equals(username)) {
                    return u;
                }
            }
            return null;
        }

        @Override
        public synchronized @Nullable AuthUser findById(String id) {
            return rows.get(id);
        }

        @Override
        public synchronized AuthUser create(AuthUser user) {
            if (!acceptsDuplicates && nameTaken(user.getUsername(), null)) {
                throw new DuplicateUserException("taken: " + user.getUsername());
            }
            if (reusesHighestId) {
                long max = 0;
                for (String id : rows.keySet()) {
                    max = Math.max(max, Long.parseLong(id.substring(1)));
                }
                return persist(user.toBuilder().id("t" + (max + 1)).build());
            }
            return persist(user.toBuilder().id("t" + nextId++).build());
        }

        @Override
        public synchronized @Nullable AuthUser update(AuthUser user) {
            AuthUser old = user.getId() == null ? null : rows.get(user.getId());
            if (old == null) {
                return null;
            }
            if (nameTaken(user.getUsername(), user.getId())) {
                throw new DuplicateUserException("taken: " + user.getUsername());
            }
            AuthUser.Builder b = old.toBuilder().username(user.getUsername()).attributes(user.getAttributes());
            if (leaks.contains(PASSWORD_HASH)) {
                b.passwordHash(user.getPasswordHash());
            }
            if (leaks.contains(VER)) {
                b.tokenVersion(user.getTokenVersion());
            }
            if (leaks.contains(ENABLED)) {
                b.enabled(user.isEnabled());
            }
            if (leaks.contains(ROLES)) {
                b.roles(user.getRoles());
            }
            return persist(b.build());
        }

        @Override
        public synchronized boolean delete(String id) {
            disk.remove(id);
            return rows.remove(id) != null;
        }

        @Override
        public synchronized boolean updatePasswordHash(String id, @Nullable String expectedOldHash, String newHash) {
            AuthUser u = rows.get(id);
            if (u == null || !Objects.equals(u.getPasswordHash(), expectedOldHash)) {
                return false;
            }
            persist(u.toBuilder().passwordHash(newHash).build());
            return true;
        }

        @Override
        public synchronized boolean changePassword(String id, @Nullable String expectedOldHash, long expectedVer,
                String newHash) {
            AuthUser u = rows.get(id);
            if (u == null) {
                return false;
            }
            if (changePasswordRejectsNullHash && expectedOldHash == null) {
                return false;
            }
            if (!Objects.equals(u.getPasswordHash(), expectedOldHash)
                    || !changePasswordIgnoresVer && u.getTokenVersion() != expectedVer) {
                return false;
            }
            persist(u.toBuilder().passwordHash(newHash).tokenVersion(u.getTokenVersion() + 1).build());
            return true;
        }

        @Override
        public synchronized boolean setEnabled(String id, boolean enabled) {
            AuthUser u = rows.get(id);
            if (u == null) {
                return false;
            }
            persist(u.toBuilder().enabled(enabled).build());
            return true;
        }

        @Override
        public synchronized boolean bumpTokenVersion(String id) {
            AuthUser u = rows.get(id);
            if (u == null) {
                return false;
            }
            persist(u.toBuilder().tokenVersion(u.getTokenVersion() + 1).build());
            return true;
        }

        @Override
        public synchronized boolean setRoles(String id, Set<String> roles) {
            AuthUser u = rows.get(id);
            if (u == null) {
                return false;
            }
            persist(u.toBuilder().roles(roles).build());
            return true;
        }

        @Override
        public boolean supportsAtomicFieldWrites() {
            return declaresAtomic;
        }
    }

    private static AssertionError failure(UserStore store) {
        try {
            AuthTesting.verifyUserStore(store);
        } catch (AssertionError e) {
            assertNotNull("the failure names its clause", e.getMessage());
            return e;
        }
        throw new AssertionError("verifyUserStore passed a broken store: " + store);
    }

    private static void assertNames(AssertionError e, String clause) {
        assertTrue("message should name " + clause + ": " + e.getMessage(), e.getMessage().contains(clause));
    }

    // FR-IAM-004 AC-1: verifyUserStore passes for InMemoryUserStore, FileUserStore and an app-provided test UserStore
    // that declares supportsAtomicFieldWrites() true and keeps the contract (each twice over the same store, and the
    // file store again after a reload)
    @Test
    public void FR_IAM_004_AC1_shippedStoresPass() throws Exception {
        InMemoryUserStore memory = new InMemoryUserStore();
        AuthTesting.verifyUserStore(memory);
        AuthTesting.verifyUserStore(memory);

        File file = new File(tmp.newFolder(), "users.json");
        FileUserStore fileStore = new FileUserStore(file);
        AuthTesting.verifyUserStore(fileStore);
        AuthTesting.verifyUserStore(fileStore);
        AuthTesting.verifyUserStore(new FileUserStore(file));

        AppStores.Users app = new AppStores().users();
        assertTrue(app.supportsAtomicFieldWrites());
        AuthTesting.verifyUserStore(app);
        AuthTesting.verifyUserStore(app);
        assertEquals("the contract cleans up the users it created", 0, app.list(0, 10, null).getTotal());
    }

    // FR-IAM-004 component: the fault-free fake keeps the contract, so each broken variant below fails for its fault only
    @Test
    public void faultFreeFakePasses() {
        AuthTesting.verifyUserStore(new TestStore());
    }

    // FR-IAM-004 AC-2: a store that persists rows through Gson (dropping the transient passwordHash) fails with a message
    // naming the hash round trip
    @Test
    public void FR_IAM_004_AC2_droppedHashFailsRoundTrip() {
        TestStore s = new TestStore();
        s.dropsHash = true;
        assertNames(failure(s), "password hash round trip");
    }

    // FR-IAM-004 AC-1 (the CRUD clause, UserStore's "a deleted id is never reused"): a MAX(id)+1 store, which hands the
    // deleted newest user's id (and its group memberships and API keys) to the next user, fails naming the clause
    @Test
    public void FR_IAM_004_AC1_reusedDeletedIdFails() {
        TestStore s = new TestStore();
        s.reusesHighestId = true;
        AssertionError e = failure(s);
        assertNames(e, "UserStore contract (CRUD)");
        assertNames(e, "a deleted id is never reused");
        assertTrue("the contract cleaned up after the failure: " + s.rows, s.rows.isEmpty());
    }

    // FR-IAM-004 AC-2 (after save and reload): a write-through cached store whose disk copy drops the transient
    // passwordHash (a Gson file rewrite, the shape of FileUserStore) passes the in-instance round trip of
    // verifyUserStore(store) and fails verifyUserStore(store, reopen), which reads the hash back through a fresh
    // instance; the shipped stores pass with their reopen
    @Test
    public void FR_IAM_004_AC2_reopenCatchesAHashLostOnDisk() throws Exception {
        final TestStore cached = new TestStore();
        cached.diskDropsHash = true;
        AuthTesting.verifyUserStore(cached); // in-instance only: the cache still holds the hash
        AssertionError lost = null;
        try {
            AuthTesting.verifyUserStore(cached, cached::reopen);
        } catch (AssertionError e) {
            lost = e;
        }
        assertNotNull("verifyUserStore(store, reopen) passed a store that loses every hash on restart", lost);
        assertNames(lost, "UserStore contract (password hash round trip)");
        assertNames(lost, "reopened");
        assertTrue("the contract cleaned up after the failure: " + cached.disk, cached.disk.isEmpty());
        TestStore sound = new TestStore();
        AuthTesting.verifyUserStore(sound, sound::reopen);

        final File file = new File(tmp.newFolder(), "users.json");
        FileUserStore fileStore = new FileUserStore(file);
        AuthTesting.verifyUserStore(fileStore, () -> new FileUserStore(file));
        final AppStores db = new AppStores();
        AppStores.Users app = db.users();
        AuthTesting.verifyUserStore(app, db::users); // a fresh store object over the same shared database
        assertEquals(0, app.list(0, 10, null).getTotal());
        try {
            AuthTesting.verifyUserStore(new InMemoryUserStore(), null);
            fail("a null reopen");
        } catch (NullPointerException expected) {
            // reopen is required by this overload
        }
        AssertionError thrown = null;
        try {
            AuthTesting.verifyUserStore(new TestStore(), () -> {
                throw new java.io.IOException("cannot open");
            });
        } catch (AssertionError e) {
            thrown = e;
        }
        assertNotNull("a reopen that throws fails the contract", thrown);
        assertNames(thrown, "reopen");
    }

    // FR-IAM-004 AC-3: a store whose create accepts a duplicate username fails with a message naming
    // DuplicateUserException
    // FR-IAM-001 AC-5: AuthTesting.verifyUserStore fails against a store whose create accepts a second user with an
    // existing username
    @Test
    public void FR_IAM_004_AC3_duplicateAcceptedFails() {
        TestStore s = new TestStore();
        s.acceptsDuplicates = true;
        assertNames(failure(s), "DuplicateUserException");
    }

    // FR-IAM-004 AC-4: a changePassword that ignores expectedVer, or returns false for expectedOldHash null on a
    // null-hash user, fails with a message naming changePassword
    @Test
    public void FR_IAM_004_AC4_brokenChangePasswordFails() {
        TestStore ignoresVer = new TestStore();
        ignoresVer.changePasswordIgnoresVer = true;
        assertNames(failure(ignoresVer), "changePassword");

        TestStore rejectsNull = new TestStore();
        rejectsNull.changePasswordRejectsNullHash = true;
        AssertionError e = failure(rejectsNull);
        assertNames(e, "changePassword");
        assertNames(e, "null");
    }

    // FR-IAM-004 AC-5: a store declaring supportsAtomicFieldWrites() true whose update() overwrites passwordHash, ver,
    // enabled or roles from the passed row fails with a message naming that field
    @Test
    public void FR_IAM_004_AC5_updateOverwritingSecurityFieldFails() {
        List<String> fields = Arrays.asList(PASSWORD_HASH, VER, ENABLED, ROLES);
        for (String field : fields) {
            TestStore s = new TestStore();
            s.leaks.add(field);
            AssertionError e = failure(s);
            assertNames(e, "update() overwrote " + field);
            for (String other : fields) {
                if (!other.equals(field)) {
                    assertFalse(field + " leak blamed on " + other + ": " + e.getMessage(),
                            e.getMessage().contains("overwrote " + other));
                }
            }
        }
    }

    // REL-IAM-003 AC-4: verifyUserStore on a store returning true from supportsAtomicFieldWrites() whose update() writes
    // ver or enabled from the passed row fails naming the overwritten field; a store returning false whose update()
    // writes the whole row passes
    @Test
    public void REL_IAM_003_AC4_declarationDecidesTheUpdateCheck() {
        for (String field : Arrays.asList(VER, ENABLED)) {
            TestStore s = new TestStore();
            s.leaks.add(field);
            assertNames(failure(s), "update() overwrote " + field);
        }
        TestStore wholeRow = new TestStore();
        wholeRow.leaks.addAll(Arrays.asList(PASSWORD_HASH, VER, ENABLED, ROLES));
        wholeRow.declaresAtomic = false;
        AuthTesting.verifyUserStore(wholeRow);
        // the shipped SINGLE_INSTANCE stores declare false and write the whole row from update()
        InMemoryUserStore memory = new InMemoryUserStore();
        assertFalse(memory.supportsAtomicFieldWrites());
        AuthTesting.verifyUserStore(memory);
    }

    // FR-IAM-004 AC-6: verifyUserStore throws an AssertionError (unchecked) and its classes name no test framework, so
    // JUnit 4, JUnit 5 and Kotlin tests can call it without one on the classpath
    @Test
    public void FR_IAM_004_AC6_assertionErrorWithoutTestFramework() throws Exception {
        TestStore s = new TestStore();
        s.acceptsDuplicates = true;
        Throwable thrown = null;
        try {
            AuthTesting.verifyUserStore(s);
        } catch (Throwable t) {
            thrown = t;
        }
        assertNotNull(thrown);
        assertTrue(thrown.getClass().getName(), thrown instanceof AssertionError);
        assertFalse(thrown instanceof Exception);

        java.util.Map<String, byte[]> classes = classFiles(AuthTesting.class, "AuthTesting", "UserStoreContract");
        Set<String> seen = new HashSet<>();
        for (java.util.Map.Entry<String, byte[]> f : classes.entrySet()) {
            String bytes = new String(f.getValue(), StandardCharsets.ISO_8859_1);
            for (String framework : Arrays.asList("org/junit", "org/opentest4j", "kotlin/test", "org/hamcrest",
                    "org/assertj", "junit/framework")) {
                assertFalse(f.getKey() + " references " + framework, bytes.contains(framework));
            }
            seen.add(f.getKey());
        }
        assertTrue(seen.toString(), seen.containsAll(Arrays.asList("AuthTesting.class", "UserStoreContract.class")));
    }

    // FR-IAM-004 component: a store throwing from a method fails the contract with an AssertionError that keeps the cause
    @Test
    public void storeExceptionBecomesAssertionError() {
        final IllegalStateException boom = new IllegalStateException("db down");
        TestStore s = new TestStore();
        UserStore throwing = new com.snoworca.naruserve.utils.auth.user.ForwardingUserStore(s) {
            @Override
            public @Nullable AuthUser findById(String id) {
                throw boom;
            }
        };
        AssertionError e = failure(throwing);
        assertTrue(e.getMessage(), e.getMessage().contains("findById"));
        assertEquals(boom, e.getCause());
        try {
            AuthTesting.verifyUserStore(null);
            fail("a null store");
        } catch (NullPointerException expected) {
            // null store
        }
    }

    /** The class files of {@code anchor}'s package whose simple names start with one of {@code prefixes}, by name. */
    static java.util.Map<String, byte[]> classFiles(Class<?> anchor, String... prefixes) throws Exception {
        java.util.Map<String, byte[]> out = new java.util.TreeMap<>();
        String pkg = anchor.getPackage().getName().replace('.', '/') + "/";
        File root = new File(anchor.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (root.isDirectory()) {
            File[] files = new File(root, pkg).listFiles();
            for (File f : files == null ? new File[0] : files) {
                if (matches(f.getName(), prefixes)) {
                    out.put(f.getName(), Files.readAllBytes(f.toPath()));
                }
            }
            return out;
        }
        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(root)) {
            java.util.Enumeration<java.util.jar.JarEntry> en = jar.entries();
            while (en.hasMoreElements()) {
                java.util.jar.JarEntry e = en.nextElement();
                String name = e.getName();
                if (name.startsWith(pkg) && name.indexOf('/', pkg.length()) < 0
                        && matches(name.substring(pkg.length()), prefixes)) {
                    try (java.io.InputStream in = jar.getInputStream(e)) {
                        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                        byte[] b = new byte[8192];
                        for (int n; (n = in.read(b)) > 0; ) {
                            buf.write(b, 0, n);
                        }
                        out.put(name.substring(pkg.length()), buf.toByteArray());
                    }
                }
            }
        }
        return out;
    }

    private static boolean matches(String name, String... prefixes) {
        if (!name.endsWith(".class")) {
            return false;
        }
        for (String p : prefixes) {
            if (name.startsWith(p + ".") || name.startsWith(p + "$")) {
                return true;
            }
        }
        return false;
    }
}
