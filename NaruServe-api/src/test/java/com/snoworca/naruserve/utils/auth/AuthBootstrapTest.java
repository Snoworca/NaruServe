package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.testing.TestApp;
import com.snoworca.naruserve.utils.auth.password.PasswordHasher;
import com.snoworca.naruserve.utils.auth.password.Pbkdf2PasswordHasher;
import com.snoworca.naruserve.utils.auth.rbac.RolePolicy;
import com.snoworca.naruserve.utils.auth.rbac.RoleStore;
import com.snoworca.naruserve.utils.auth.user.AuthUser;
import com.snoworca.naruserve.utils.auth.user.DuplicateUserException;
import com.snoworca.naruserve.utils.auth.user.ForwardingUserStore;
import com.snoworca.naruserve.utils.auth.user.InMemoryUserStore;
import com.snoworca.naruserve.utils.auth.user.UserStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.Test;

/** Bootstrap {@code .user(...)} seeding on start (FR-AUTH-004) and the store-enforced unique name (FR-IAM-001). */
public class AuthBootstrapTest {

    /** A real PBKDF2 hasher that counts its hash calls of the seed password "first-password-1" (not auth's own dummy hash). */
    static final class CountingHasher implements PasswordHasher {
        final Pbkdf2PasswordHasher real = new Pbkdf2PasswordHasher(1000);
        final AtomicInteger hashes = new AtomicInteger();

        @Override
        public boolean accepts(String stored) {
            return real.accepts(stored);
        }

        @Override
        public String hash(char[] password) {
            if (new String(password).equals("first-password-1")) {
                hashes.incrementAndGet();
            }
            return real.hash(password);
        }

        @Override
        public boolean verify(char[] password, String stored) {
            return real.verify(password, stored);
        }
    }

    /** Records the user events with their origin. */
    static final class Events implements AuthEvents {
        final List<String> seen = Collections.synchronizedList(new ArrayList<String>());

        @Override
        public void beforeUserCreate(AuthUser user, AuthEventContext ctx) {
            seen.add("before " + user.getUsername());
        }

        @Override
        public void onUserCreated(AuthUser user, AuthEventContext ctx) {
            seen.add("created " + user.getUsername() + " " + ctx.getOrigin() + " hash=" + user.getPasswordHash());
        }
    }

    private static Auth.Builder builder(RecordingLogger log) {
        return AuthTestSupport.builder(new AuthTestSupport.TestCredentials(), log);
    }

    private static TestApp started(Auth auth) {
        TestApp t = TestApp.create();
        auth.install(t.app());
        t.start();
        return t;
    }

    private static String failedStart(Auth auth) {
        TestApp t = TestApp.create();
        auth.install(t.app());
        try {
            t.start();
        } catch (RuntimeException e) {
            return String.valueOf(e.getMessage());
        }
        throw new AssertionError("the app started");
    }

    private static int count(List<String> lines, String level, String text) {
        int n = 0;
        for (String l : new ArrayList<>(lines)) {
            if (l.startsWith(level + " ") && l.contains(text)) {
                n++;
            }
        }
        return n;
    }

    // FR-AUTH-004 AC-1: an empty store gets admin with role admin; hashed once in build(); onUserCreated once with
    // origin BOOTSTRAP and no before event
    @Test
    public void FR_AUTH_004_AC1_seedsOnStartHashOnceInBuild() throws Exception {
        InMemoryUserStore store = new InMemoryUserStore();
        CountingHasher hasher = new CountingHasher();
        Events events = new Events();
        RecordingLogger log = new RecordingLogger();
        Auth auth = builder(log).users(store).passwordHasher(hasher).events(events)
                .user("admin", "first-password-1", "admin").build();
        assertEquals(1, hasher.hashes.get());
        assertNull(store.findByUsername("admin"));
        started(auth);
        assertEquals(1, hasher.hashes.get());
        AuthUser admin = store.findByUsername("admin");
        assertNotNull(admin);
        assertEquals(Collections.singleton("admin"), admin.getRoles());
        assertTrue(hasher.verify("first-password-1".toCharArray(), admin.getPasswordHash()));
        assertEquals(Collections.singletonList("created admin BOOTSTRAP hash=null"), events.seen);
    }

    // FR-AUTH-004 AC-2: a restart with another password leaves the stored hash unchanged and logs INFO only
    @Test
    public void FR_AUTH_004_AC2_restartNeverChangesThePassword() throws Exception {
        InMemoryUserStore store = new InMemoryUserStore();
        started(builder(new RecordingLogger()).users(store).user("admin", "first-password-1", "admin").build());
        String hash = store.findByUsername("admin").getPasswordHash();
        RecordingLogger log = new RecordingLogger();
        Auth again = builder(log).users(store).user("admin", "second-password-2", "admin").build();
        started(again);
        AuthUser admin = store.findByUsername("admin");
        assertEquals(hash, admin.getPasswordHash());
        assertTrue(again.users().checkPassword(admin.getId(), "first-password-1".toCharArray()));
        assertFalse(again.users().checkPassword(admin.getId(), "second-password-2".toCharArray()));
        assertEquals(1, count(log.lines, "INFO", ".user(\"admin\") already exists"));
        assertEquals(log.lines.toString(), 0, log.count("WARN"));
        assertEquals(0, log.count("ERROR"));
    }

    /** A store that misses another node's insert: findByUsername sees nothing, create says the name is taken. */
    static final class RacingStore extends ForwardingUserStore {
        final AtomicInteger creates = new AtomicInteger();

        RacingStore() {
            super(new InMemoryUserStore());
        }

        @Override
        public AuthUser create(AuthUser user) {
            creates.incrementAndGet();
            throw new DuplicateUserException("username " + user.getUsername() + " exists");
        }
    }

    // FR-AUTH-004 AC-3: create throwing DuplicateUserException on start logs INFO "already exists" and continues
    // FR-IAM-001 AC-4: the same for a bootstrap seed whose create is beaten by another node
    @Test
    public void FR_AUTH_004_AC3_FR_IAM_001_AC4_duplicateOnSeedIsInfo() {
        RecordingLogger log = new RecordingLogger();
        RacingStore store = new RacingStore();
        started(builder(log).users(store).user("admin", "first-password-1", "admin").build());
        assertEquals(1, store.creates.get());
        assertEquals(log.lines.toString(), 1, count(log.lines, "INFO", "already exists"));
        assertEquals(0, log.count("ERROR"));
    }

    // FR-IAM-001 AC-3: a store whose findByUsername misses a concurrent insert but whose create throws: auth.users()
    // .create surfaces DuplicateUserException and no second row exists
    @Test
    public void FR_IAM_001_AC3_storeIsTheFinalAuthority() throws Exception {
        final InMemoryUserStore real = new InMemoryUserStore();
        real.create(AuthUser.builder().username("neo").passwordHash("x").build());
        UserStore blind = new ForwardingUserStore(real) {
            @Override
            public @Nullable AuthUser findByUsername(String username) {
                return null; // the pre-check misses the existing row
            }
        };
        Auth auth = builder(new RecordingLogger()).users(blind).build();
        try {
            auth.users().create("neo", "pw-pw-pw-pw".toCharArray());
            fail("created a second neo");
        } catch (DuplicateUserException expected) {
            // the store's unique name is the final authority
        }
        assertEquals(1, real.list(0, 10, null).getItems().size());
    }

    // FR-AUTH-004 AC-4: after the first start, a null password for an existing account is ignored: start continues,
    // INFO only, no FAIL and no setup token (PROD, recovery off)
    @Test
    public void FR_AUTH_004_AC4_nullPasswordForAnExistingAccountIsIgnored() throws Exception {
        InMemoryUserStore store = new InMemoryUserStore();
        RolePolicy roles = RolePolicy.builder().role("editor", "posts:read").build();
        started(builder(new RecordingLogger()).users(store).roles(roles).user("admin", "first-password-1", "admin")
                .user("bob", "bob-password-b", "editor").build());
        RecordingLogger log = new RecordingLogger();
        String unset = System.getenv("BOB_PW_SURELY_UNSET_9C1D");
        assertNull(unset);
        Auth again = builder(log).users(store).roles(roles).user("bob", unset, "editor").user("admin", null, "admin")
                .build();
        assertEquals(AuthMode.PROD, again.mode());
        started(again);
        assertEquals(log.lines.toString(), 0, log.count("WARN"));
        assertEquals(0, log.count("ERROR"));
        assertEquals(2, count(log.lines, "INFO", "already exists"));
        assertNull(again.pendingSetupToken());
        for (String l : log.lines) {
            assertFalse(l, l.contains("setup token"));
        }
    }

    /** A database RoleStore: roles cannot be judged in advance and never count as admin. */
    private static final RoleStore DB_ROLES = role -> "superuser".equals(role)
            ? Collections.singleton("*") : Collections.<String>emptySet();

    // FR-AUTH-004 AC-5: the null-password scope check: one admin passes; a non-admin, two admins, a database role and a
    // replaced built-in admin FAIL (DEV included); a RolePolicy role granting * passes
    @Test
    public void FR_AUTH_004_AC5_nullPasswordScopeCheck() {
        // one null-password admin: no scope FAIL (with recovery the PROD start then succeeds, AC-6)
        RecordingLogger ok = new RecordingLogger();
        started(builder(ok).user("admin", null, "admin").recovery(true).build());
        assertEquals(0, count(ok.lines, "ERROR", "null-password user"));

        for (AuthMode mode : AuthMode.values()) {
            String editor = failedStart(builder(new RecordingLogger()).mode(mode)
                    .roles(RolePolicy.builder().role("editor", "posts:read").build()).user("c", null, "editor")
                    .build());
            assertTrue(editor, editor.contains("null-password user") && editor.contains(".user(\"c\""));
            String two = failedStart(builder(new RecordingLogger()).mode(mode).recovery(true)
                    .user("admin", null, "admin").user("root", null, "admin").build());
            assertTrue(two, two.contains("2 admin .user calls have no password"));
            String db = failedStart(builder(new RecordingLogger()).mode(mode).roles(DB_ROLES)
                    .user("ops", null, "superuser").build());
            assertTrue(db, db.contains(".user(\"ops\""));
            String replaced = failedStart(builder(new RecordingLogger()).mode(mode)
                    .roles(RolePolicy.builder().replaceBuiltinRoles().role("editor", "posts:read").build())
                    .user("admin", null, "admin").build());
            assertTrue(replaced, replaced.contains(".user(\"admin\""));
        }
        RecordingLogger root = new RecordingLogger();
        InMemoryUserStore store = new InMemoryUserStore();
        started(builder(root).mode(AuthMode.DEV).users(store)
                .roles(RolePolicy.builder().replaceBuiltinRoles().role("root", "*").build())
                .user("ops", null, "root").build());
        assertEquals(0, count(root.lines, "ERROR", "null-password user"));
    }

    // FR-AUTH-004 component (the null-password start paths that need no setup endpoint): PROD without recovery FAILs,
    // PROD with recovery logs a one-time setup token and continues, DEV logs a temporary password once and creates it
    @Test
    public void nullPasswordAdminByMode() throws Exception {
        String prod = failedStart(builder(new RecordingLogger()).user("admin", null, "admin").build());
        assertTrue(prod, prod.contains("start once with .recovery(true)"));

        RecordingLogger rec = new RecordingLogger();
        InMemoryUserStore store = new InMemoryUserStore();
        Auth recovering = builder(rec).users(store).recovery(true).user("admin", null, "admin").build();
        started(recovering);
        assertNull(store.findByUsername("admin"));
        String token = recovering.pendingSetupToken();
        assertNotNull(token);
        assertEquals(1, count(rec.lines, "WARN", "one-time setup token: " + token));

        RecordingLogger dev = new RecordingLogger();
        InMemoryUserStore devStore = new InMemoryUserStore();
        Auth devAuth = builder(dev).mode(AuthMode.DEV).users(devStore).user("admin", null, "admin").build();
        started(devAuth);
        AuthUser admin = devStore.findByUsername("admin");
        assertNotNull(admin);
        assertEquals(1, count(dev.lines, "WARN", "temporary password"));
        String line = null;
        for (String l : dev.lines) {
            if (l.contains("temporary password")) {
                line = l;
            }
        }
        String temporary = line.substring(line.indexOf("temporary password ") + 19, line.indexOf(" (shown once"));
        assertTrue(devAuth.users().checkPassword(admin.getId(), temporary.toCharArray()));
        assertSame(null, devAuth.pendingSetupToken());
    }
}
