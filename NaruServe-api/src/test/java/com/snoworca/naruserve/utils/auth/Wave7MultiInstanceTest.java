package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.testing.TestApp;
import com.snoworca.naruserve.utils.auth.group.FileGroupStore;
import com.snoworca.naruserve.utils.auth.group.InMemoryGroupStore;
import com.snoworca.naruserve.utils.auth.spi.DeviceTrust;
import com.snoworca.naruserve.utils.auth.spi.LoginThrottle;
import com.snoworca.naruserve.utils.auth.spi.RefreshTokenStore;
import com.snoworca.naruserve.utils.auth.user.AuthUser;
import com.snoworca.naruserve.utils.auth.user.FileUserStore;
import com.snoworca.naruserve.utils.auth.user.InMemoryUserStore;
import com.snoworca.naruserve.utils.auth.user.UserStore;
import java.io.File;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * MULTI_INSTANCE with shared app-provided stores (FR-AUTH-003 AC-3, REL-IAM-003 AC-1 and AC-2) and the single
 * restart-logout WARN of a custom user store (OPS-AUTH-001 AC-6). The shared stores are {@link AppStores}: test
 * implementations of the SPIs over one backing store standing in for the application's database (no JDBC driver).
 */
public class Wave7MultiInstanceTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** A custom store implementing only the abstract UserStore methods (no supportsAtomicFieldWrites override). */
    static class PlainStore implements UserStore {
        final InMemoryUserStore d = new InMemoryUserStore();

        @Override
        public AuthUser findByUsername(String username) throws Exception {
            return d.findByUsername(username);
        }

        @Override
        public AuthUser findById(String id) throws Exception {
            return d.findById(id);
        }

        @Override
        public AuthUser create(AuthUser user) throws Exception {
            return d.create(user);
        }

        @Override
        public AuthUser update(AuthUser user) throws Exception {
            return d.update(user);
        }

        @Override
        public boolean delete(String id) throws Exception {
            return d.delete(id);
        }
    }

    /** The same store declaring atomic field writes while overriding none of the five field methods. */
    static final class DeclaringStore extends PlainStore {
        @Override
        public boolean supportsAtomicFieldWrites() {
            return true;
        }
    }

    private Auth.Builder multi(UserStore users, AppStores db) {
        return Auth.builder(AuthTestSupport.SECRET).hashIterations(1000).logger(new RecordingLogger())
                .deployment(Deployment.MULTI_INSTANCE).users(users)
                .refreshTokens(db.refreshTokens())
                .loginThrottle(db.loginThrottle())
                .deviceTrust(db.deviceTrust());
    }

    private static String startFailure(Auth auth) {
        TestApp t = TestApp.create();
        t.app().trustProxyCidrs("10.0.0.0/8");
        auth.install(t.app());
        try {
            t.start();
            t.app().shutdown(5, TimeUnit.SECONDS);
            return null;
        } catch (RuntimeException e) {
            return String.valueOf(e.getMessage()) + (e.getCause() == null ? "" : e.getCause().getMessage());
        }
    }

    // REL-IAM-003 AC-1: MULTI_INSTANCE, a custom UserStore not overriding supportsAtomicFieldWrites() and every other
    // store shared (app-provided test implementations, none a built-in in-memory or file store) -> build() passes and
    // the start fails with a doctor FAIL naming
    // supportsAtomicFieldWrites
    @Test
    public void REL_IAM_003_AC1_undeclaredStoreFailsTheDoctor() throws Exception {
        Auth auth = multi(new PlainStore(), new AppStores()).build();
        String m = startFailure(auth);
        assertTrue("the start failed", m != null);
        assertTrue(m, m.contains("FAIL atomic field writes") && m.contains("supportsAtomicFieldWrites"));
    }

    // REL-IAM-003 AC-2: the same configuration with a store returning true passes this doctor item although it
    // overrides none of the five field methods (no reflection)
    @Test
    public void REL_IAM_003_AC2_declarationAlonePasses() throws Exception {
        String m = startFailure(multi(new DeclaringStore(), new AppStores()).build());
        assertNull(String.valueOf(m), m);
    }

    // FR-AUTH-003 AC-3: MULTI_INSTANCE refuses an in-memory or file user, group, refresh, throttle or device-trust store,
    // and the implicit in-memory defaults a custom UserStore would get; with app-provided stores implementing the SPIs
    // it builds
    @Test
    public void FR_AUTH_003_AC3_multiInstanceNeedsSharedStores() throws Exception {
        AppStores ds = new AppStores();
        DeclaringStore custom = new DeclaringStore();
        Auth.Builder[] local = {
            multi(new InMemoryUserStore(), ds),
            multi(new FileUserStore(new File(tmp.newFolder(), "u.json")), ds),
            multi(custom, ds).groups(new InMemoryGroupStore()),
            multi(custom, ds).groups(new FileGroupStore(new File(tmp.newFolder(), "g.json"))),
            multi(custom, ds).refreshTokens(new InMemoryRefreshTokenStore()),
            multi(custom, ds).refreshTokens(new FileRefreshTokenStore(new File(tmp.newFolder(), "r.json"))),
            multi(custom, ds).loginThrottle(InMemoryLoginThrottle.builder().build()),
            multi(custom, ds).deviceTrust(new InMemoryDeviceTrust(System::currentTimeMillis)),
        };
        String[] named = {"(user store)", "(user store)", "(group store)", "(group store)", "(refresh token store)",
            "(refresh token store)", "(login throttle)", "(device trust)"};
        for (int i = 0; i < local.length; i++) {
            try {
                local[i].build();
                fail("MULTI_INSTANCE accepted case " + i);
            } catch (AuthConfigException e) {
                assertTrue(i + ": " + e.getMessage(), e.getMessage().contains(named[i]));
            }
        }
        String[] defaults = {"refresh token store", "login throttle", "device trust store"};
        for (int i = 0; i < 3; i++) {
            Auth.Builder b = Auth.builder(AuthTestSupport.SECRET).hashIterations(1000).logger(new RecordingLogger())
                    .deployment(Deployment.MULTI_INSTANCE).users(custom);
            if (i != 0) {
                b.refreshTokens(ds.refreshTokens());
            }
            if (i != 1) {
                b.loginThrottle(ds.loginThrottle());
            }
            if (i != 2) {
                b.deviceTrust(ds.deviceTrust());
            }
            try {
                b.build();
                fail("MULTI_INSTANCE fell back to the in-memory " + defaults[i]);
            } catch (AuthConfigException e) {
                assertTrue(e.getMessage(), e.getMessage().contains("in-memory defaults of the unset " + defaults[i]));
            }
        }
        Auth shared = multi(custom, ds).build();
        assertTrue(shared.loginThrottle() instanceof AppStores.Throttle);
        Auth all = multi(ds.users(), ds).groups(ds.groups()).apiKeys(ds.apiKeys()).build();
        assertTrue(all.loginThrottle() instanceof AppStores.Throttle);
        assertTrue(all.deviceTrust().getClass().getName().startsWith(AppStores.class.getName()));
        assertTrue(all.refreshTokens().getClass().getName().startsWith(AppStores.class.getName()));
    }

    private static int restartWarns(Auth.Builder b) {
        RecordingLogger log = new RecordingLogger();
        TestApp t = TestApp.create();
        b.logger(log).build().install(t.app());
        t.start();
        t.app().shutdown(5, TimeUnit.SECONDS);
        return RecordingLogger.countMatching(log.lines, ".*restart logs every user out.*");
    }

    // OPS-AUTH-001 AC-6: SINGLE_INSTANCE with a custom UserStore and any of the refresh, throttle or device-trust stores
    // unset -> exactly one restart-logout WARN however many fall back; all three set, or a shipped user store -> none;
    // MULTI_INSTANCE with the same set-up fails build()
    @Test
    public void OPS_AUTH_001_AC6_oneRestartWarnForCustomStoreFallbacks() throws Exception {
        AppStores ds = new AppStores();
        RefreshTokenStore r = ds.refreshTokens();
        LoginThrottle th = ds.loginThrottle();
        DeviceTrust dt = ds.deviceTrust();
        assertEquals(1, restartWarns(single(new DeclaringStore())));
        assertEquals(1, restartWarns(single(new DeclaringStore()).refreshTokens(r)));
        assertEquals(1, restartWarns(single(new DeclaringStore()).refreshTokens(r).loginThrottle(th)));
        assertEquals(1, restartWarns(single(new DeclaringStore()).loginThrottle(th).deviceTrust(dt)));
        assertEquals(0, restartWarns(single(new DeclaringStore()).refreshTokens(r).loginThrottle(th).deviceTrust(dt)));
        assertEquals(0, restartWarns(single(new InMemoryUserStore())));
        assertEquals(0, restartWarns(single(new FileUserStore(new File(tmp.newFolder(), "u.json"), AuthTestSupport.FILE_KEY))));
        try {
            single(new DeclaringStore()).deployment(Deployment.MULTI_INSTANCE).refreshTokens(r).build();
            fail("MULTI_INSTANCE fell back to memory");
        } catch (AuthConfigException expected) {
            assertFalse(expected.getMessage().contains("(user store)"));
        }
    }

    private static Auth.Builder single(UserStore users) {
        return Auth.builder(AuthTestSupport.SECRET).hashIterations(1000).users(users);
    }
}
