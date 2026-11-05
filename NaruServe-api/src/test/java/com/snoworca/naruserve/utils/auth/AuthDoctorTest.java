package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.testing.TestApp;
import com.snoworca.naruserve.utils.auth.user.AuthUser;
import com.snoworca.naruserve.utils.auth.user.ForwardingUserStore;
import com.snoworca.naruserve.utils.auth.user.InMemoryUserStore;
import org.jspecify.annotations.Nullable;
import org.junit.Test;

/** The startup checks and the runtime proxy warnings (OPS-AUTH-001, FR-AUTH-003 AC-4). */
public class AuthDoctorTest {

    /** A custom store (not one of the shipped ones), optionally failing every username read. */
    static final class AppStore extends ForwardingUserStore {
        final boolean failing;

        AppStore(boolean failing) {
            super(new InMemoryUserStore());
            this.failing = failing;
        }

        @Override
        public @Nullable AuthUser findByUsername(String username) throws Exception {
            if (failing) {
                throw new java.sql.SQLException("connection refused");
            }
            return super.findByUsername(username);
        }
    }

    private static boolean bound(InMemoryHttpAdapter adapter) {
        try {
            adapter.boundPort();
            return true;
        } catch (IllegalStateException notBound) {
            return false;
        }
    }

    // OPS-AUTH-001 AC-1: every FAIL is named in the start exception and the port is never bound; WARN-only starts,
    // logging each WARN once
    @Test
    public void OPS_AUTH_001_AC1_failStopsStartBeforeBindAndWarnsAreLoggedOnce() {
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        TestApp t = TestApp.create(adapter);
        RecordingLogger log = new RecordingLogger();
        t.app().trustProxy(true);
        AuthTestSupport.builder(new AuthTestSupport.TestCredentials(), log).users(new AppStore(true)).build()
                .install(t.app());
        try {
            t.start();
            fail("the app started with doctor FAILs");
        } catch (RuntimeException e) {
            String m = String.valueOf(e.getMessage()) + (e.getCause() == null ? "" : e.getCause().getMessage());
            assertTrue(m, m.contains("2 FAILs"));
            assertTrue(m, m.contains("FAIL store connectivity"));
            assertTrue(m, m.contains("FAIL proxy trust"));
        }
        assertFalse(bound(adapter));

        InMemoryHttpAdapter ok = new InMemoryHttpAdapter();
        TestApp w = TestApp.create(ok);
        RecordingLogger warnLog = new RecordingLogger();
        w.app().trustProxyHops(1);
        AuthTestSupport.builder(new AuthTestSupport.TestCredentials(), warnLog).users(new AppStore(false))
                .profile(AuthProfile.STRICT).build().install(w.app());
        w.start();
        assertTrue(bound(ok));
        assertEquals(warnLog.lines.toString(), 2, RecordingLogger.countMatching(warnLog.lines, ".*auth doctor WARN.*"));
        assertEquals(1, RecordingLogger.countMatching(warnLog.lines, ".*auth doctor WARN proxy trust.*"));
        assertEquals(1, RecordingLogger.countMatching(warnLog.lines, ".*auth doctor WARN restart logout.*"));
    }

    // OPS-AUTH-001 component (proxy trust items): PROD trustProxy(true) is a FAIL (DEV: no FAIL); hop mode under STRICT is a WARN
    // recommending CIDR mode (MULTI_INSTANCE cannot build until the shared token stores exist)
    @Test
    public void proxyTrustItems() {
        RecordingLogger log = new RecordingLogger();
        TestApp legacy = TestApp.create();
        legacy.app().trustProxy(true);
        AuthTestSupport.builder(new AuthTestSupport.TestCredentials(), log).mode(AuthMode.DEV).build()
                .install(legacy.app());
        legacy.start();
        assertEquals(0, RecordingLogger.countMatching(log.lines, ".*FAIL.*"));
        TestApp hops = TestApp.create();
        hops.app().trustProxyHops(2);
        RecordingLogger h = new RecordingLogger();
        AuthTestSupport.builder(new AuthTestSupport.TestCredentials(), h).profile(AuthProfile.STRICT).build()
                .install(hops.app());
        hops.start();
        assertEquals(1, RecordingLogger.countMatching(h.lines, ".*WARN proxy trust.*trustProxyCidrs.*"));
    }

    // OPS-AUTH-001 AC-4: no proxy trust, the first X-Forwarded-For request logs one WARN naming trustProxyHops
    @Test
    public void OPS_AUTH_001_AC4_untrustedForwardedForWarnsOnce() {
        RecordingLogger log = new RecordingLogger();
        TestApp t = TestApp.create();
        AuthTestSupport.builder(new AuthTestSupport.TestCredentials(), log).permit("/x").build().install(t.app());
        t.app().get("/x", (req, res) -> res.send("x"));
        t.send("GET", "/x");
        assertEquals(0, RecordingLogger.countMatching(log.lines, ".*trustProxyHops.*"));
        for (int i = 0; i < 3; i++) {
            t.send("GET", "/x", "X-Forwarded-For", "198.51.100." + i);
        }
        assertEquals(1, RecordingLogger.countMatching(log.lines, ".*share the load balancer's IP.*trustProxyHops.*"));
        // with hop trust configured there is no such warning
        RecordingLogger trusted = new RecordingLogger();
        TestApp h = TestApp.create();
        h.app().trustProxyHops(1);
        AuthTestSupport.builder(new AuthTestSupport.TestCredentials(), trusted).permit("/x").build().install(h.app());
        h.app().get("/x", (req, res) -> res.send("x"));
        h.send("GET", "/x", "X-Forwarded-For", "198.51.100.7");
        assertEquals(0, RecordingLogger.countMatching(trusted.lines, ".*share the load balancer's IP.*"));
    }

    // FR-AUTH-003 AC-4: SINGLE_INSTANCE with non-shared stores, the first forwarded request logs one MULTI_INSTANCE WARN
    @Test
    public void FR_AUTH_003_AC4_singleInstanceBehindAProxyWarnsOnce() {
        RecordingLogger log = new RecordingLogger();
        TestApp t = TestApp.create();
        t.app().trustProxyHops(1);
        AuthTestSupport.builder(new AuthTestSupport.TestCredentials(), log).permit("/x").build().install(t.app());
        t.app().get("/x", (req, res) -> res.send("x"));
        t.send("GET", "/x");
        assertEquals(0, RecordingLogger.countMatching(log.lines, ".*MULTI_INSTANCE.*"));
        t.send("GET", "/x", "X-Forwarded-Proto", "https");
        t.send("GET", "/x", "X-Forwarded-For", "198.51.100.1");
        t.send("GET", "/x", "X-Forwarded-For", "198.51.100.2", "X-Forwarded-Proto", "https");
        assertEquals(log.lines.toString(), 1, RecordingLogger.countMatching(log.lines,
                ".*WARN.*deployment\\(MULTI_INSTANCE\\).*"));
    }

    // FR-AUTH-003 AC-4: X-Forwarded-For alone is enough: the first such request logs the one WARN, later ones none
    @Test
    public void FR_AUTH_003_AC4_aForwardedForHeaderAloneWarnsOnce() {
        RecordingLogger log = new RecordingLogger();
        TestApp t = TestApp.create();
        t.app().trustProxyHops(1);
        AuthTestSupport.builder(new AuthTestSupport.TestCredentials(), log).permit("/x").build().install(t.app());
        t.app().get("/x", (req, res) -> res.send("x"));
        t.send("GET", "/x", "X-Forwarded-For", "198.51.100.1");
        assertEquals(log.lines.toString(), 1, RecordingLogger.countMatching(log.lines,
                ".*WARN.*deployment\\(MULTI_INSTANCE\\).*"));
        t.send("GET", "/x", "X-Forwarded-For", "198.51.100.2");
        assertEquals(log.lines.toString(), 1, RecordingLogger.countMatching(log.lines,
                ".*WARN.*deployment\\(MULTI_INSTANCE\\).*"));
    }

    // OPS-AUTH-001 component (restart logout): a custom UserStore under SINGLE_INSTANCE gets exactly one restart-logout WARN;
    // InMemoryUserStore gets none (setting the three shared stores comes with those stores)
    @Test
    public void restartLogoutWarning() {
        RecordingLogger custom = new RecordingLogger();
        TestApp a = TestApp.create();
        AuthTestSupport.builder(new AuthTestSupport.TestCredentials(), custom).users(new AppStore(false)).build()
                .install(a.app());
        a.start();
        assertEquals(1, RecordingLogger.countMatching(custom.lines, ".*restart logs every user out.*"));
        RecordingLogger shipped = new RecordingLogger();
        TestApp b = TestApp.create();
        AuthTestSupport.builder(new AuthTestSupport.TestCredentials(), shipped).users(new InMemoryUserStore()).build()
                .install(b.app());
        b.start();
        assertEquals(0, RecordingLogger.countMatching(shipped.lines, ".*restart logs every user out.*"));
    }
}
