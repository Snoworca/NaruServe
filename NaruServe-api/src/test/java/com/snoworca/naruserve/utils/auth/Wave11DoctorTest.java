package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.utils.auth.internal.Doctor;
import com.snoworca.naruserve.utils.auth.spi.LoginThrottle;
import com.snoworca.naruserve.utils.auth.user.FileUserStore;
import com.snoworca.naruserve.utils.auth.user.ForwardingUserStore;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The doctor report item by item (OPS-AUTH-001): one app per item (or per group of compatible items), each set up so
 * that the item under test reports; the report is read from {@code auth.doctor(app)} and, for a FAIL item,
 * from the start exception too.
 */
public class Wave11DoctorTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final List<RestApp> apps = new ArrayList<>();

    @After
    public void stop() {
        for (RestApp a : apps) {
            try {
                a.shutdown(5, TimeUnit.SECONDS);
            } catch (RuntimeException ignored) {
                // an app whose start failed
            }
        }
    }

    /** One app: the logger, the app and the installed auth. */
    final class Probe {
        final RecordingLogger log = new RecordingLogger();
        final InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        final RestApp app = RestApp.builder().httpAdapter(adapter).jsonCodec(new AdminFixture.MiniJsonCodec())
                .logger(log).build();
        Auth auth;

        Probe(UnaryOperator<Auth.Builder> config, Consumer<RestApp> beforeInstall, Consumer<Probe> afterInstall) {
            apps.add(app);
            beforeInstall.accept(app);
            auth = config.apply(Auth.builder(AuthTestSupport.SECRET).hashIterations(1000).logger(log)).build();
            auth.install(app);
            afterInstall.accept(this);
            app.onError(auth.errors());
        }

        /** {@code LEVEL item} of every finding. */
        List<String> report() {
            List<String> out = new ArrayList<>();
            for (Doctor.Finding x : auth.doctor(app).findings()) {
                out.add(x.getLevel() + " " + x.getItem());
            }
            return out;
        }

        /** The message of the failed start; fails when the app started. */
        String startFailure() {
            try {
                app.listen(0);
            } catch (RuntimeException e) {
                return String.valueOf(e.getMessage()) + (e.getCause() == null ? "" : e.getCause().getMessage());
            }
            fail("the app started");
            return "";
        }
    }

    private Probe probe(UnaryOperator<Auth.Builder> config) {
        return new Probe(config, a -> { }, p -> { });
    }

    private static void reports(Probe p, String finding) {
        List<String> r = p.report();
        assertTrue(finding + " in " + r, r.contains(finding));
    }

    private static void fails(Probe p, String item) {
        reports(p, "FAIL " + item);
        String m = p.startFailure();
        assertTrue(m, m.contains("FAIL " + item));
    }

    private static void silent(Probe p, String item) {
        for (String f : p.report()) {
            assertFalse(f, f.endsWith(" " + item));
        }
    }

    /**
     * MULTI_INSTANCE over shared app-provided stores (test implementations over one backing store, no JDBC driver);
     * {@code users} decides the atomic-field-write declaration.
     */
    private static Auth.Builder multi(Auth.Builder b, AppStores ds,
            com.snoworca.naruserve.utils.auth.user.UserStore users) {
        return b.users(users).deployment(Deployment.MULTI_INSTANCE)
                .refreshTokens(ds.refreshTokens())
                .loginThrottle(ds.loginThrottle())
                .deviceTrust(ds.deviceTrust());
    }

    /** An app-provided user store behind a decorator that does not declare atomic field writes. */
    static final class Undeclared extends ForwardingUserStore {
        Undeclared(com.snoworca.naruserve.utils.auth.user.UserStore d) {
            super(d);
        }

        @Override
        public boolean supportsAtomicFieldWrites() {
            return false;
        }
    }

    /** A throttle implementing only the three required methods: it does not use the limit arguments. */
    static final class ThreeMethods implements LoginThrottle {
        @Override
        public long retryAfter(String key) {
            return 0;
        }

        @Override
        public long recordFailure(String key) {
            return 0;
        }

        @Override
        public void reset(String key) {
        }
    }

    // OPS-AUTH-001 AC-2: the suite covers every listed item; a table of apps, each set up so that one item reports,
    // asserts that item's FAIL or WARN in that app's doctor report (a FAIL item is also named in the start exception):
    // store connectivity (findByUsername("__naru_probe__") throwing: FAIL), secret strength (DEV only: the WARN of
    // SEC-AUTH-001 AC-8), the public path list (listed, no FAIL or WARN), the pre-install middleware list (R28),
    // registration enabled (.register(true) in PROD: WARN), the measured hash cost (present with its time), the clock (a
    // TimeSource earlier than the module build time: WARN), proxy trust (R32), deployment vs stores (R35), the
    // null-password .user (R41), file state (R42), errors() placement (R30), the cookie-mode WebSocket origin and the
    // WebSocket wrapper count (R40), supportsAtomicFieldWrites (R37), supportsAtomicLimitInsert, the admin-scope key
    // count, the admin-scope opt-in and LoginThrottle.usesLimitArguments (R47)
    // IR-FLOW-003 AC-4: API keys on with a LoginThrottle whose usesLimitArguments() is false -> a doctor WARN stating
    // that the .apiKeyFailureLimit values are not used; with a throttle returning true -> no such WARN
    @Test
    public void OPS_AUTH_001_AC2_IR_FLOW_003_AC4_everyItemReports() throws Exception {
        // store connectivity
        fails(probe(b -> b.users(new AuthDoctorTest.AppStore(true))), "store connectivity");
        // secret strength: DEV only (PROD refuses the key in build(), SEC-AUTH-001 AC-2 to AC-4)
        RecordingLogger weak = new RecordingLogger();
        Auth.builder("short").mode(AuthMode.DEV).logger(weak).build();
        assertEquals(weak.lines.toString(), 1, RecordingLogger.countMatching(weak.lines, "WARN .*32 bytes.*"));
        // the public path list: a listing, no FAIL or WARN
        Probe pub = probe(b -> b.permit("/health"));
        pub.report();
        assertEquals(1, RecordingLogger.countMatching(pub.log.lines, "INFO auth doctor: public paths .*/health.*"));
        for (String f : pub.report()) {
            assertFalse(f, f.contains("public"));
        }
        // the pre-install middleware list
        Probe pre = new Probe(b -> b, a -> a.use("/hook", (req, res, next) -> next.run()), p -> { });
        fails(pre, "pre-auth middleware");
        assertTrue(RecordingLogger.countMatching(pre.log.lines, "INFO auth doctor: pre-auth middleware \\[/hook\\].*") >= 1);
        // registration enabled: a WARN in PROD, none in DEV
        reports(probe(b -> b.register(true)), "WARN registration");
        silent(probe(b -> b.register(true).mode(AuthMode.DEV)), "registration");
        silent(probe(b -> b), "registration");
        // the measured hash cost: present with the measured time
        Probe cost = probe(b -> b);
        cost.report();
        assertTrue(RecordingLogger.countMatching(cost.log.lines,
                "INFO auth doctor: hash cost \\d+ PBKDF2 iterations, \\d+\\.\\d ms per hash .*") >= 1);
        // the clock: a TimeSource before the module build time
        Probe clock = probe(b -> b.timeSource(() -> 946_684_800_000L)); // 2000-01-01
        reports(clock, "WARN clock");
        silent(probe(b -> b), "clock");
        // proxy trust: PROD legacy trustProxy(true)
        fails(new Probe(b -> b, a -> a.trustProxy(true), p -> { }), "proxy trust");
        // deployment vs stores: SINGLE_INSTANCE, a custom user store, the session stores left to memory
        reports(probe(b -> b.users(new AuthDoctorTest.AppStore(false))), "WARN restart logout");
        // the null-password .user
        fails(probe(b -> b.user("chief", null, "admin")), "null-password user");
        // file state: a leftover temp file next to the users file
        File dir = tmp.newFolder();
        File users = new File(dir, "users.json");
        FileUserStore fileStore = new FileUserStore(users, AuthTestSupport.FILE_KEY);
        fileStore.create(com.snoworca.naruserve.utils.auth.user.AuthUser.builder().username("ann").build());
        try (FileOutputStream o = new FileOutputStream(new File(dir, "users.json.tmp"))) {
            o.write('{');
        }
        reports(probe(b -> b.users(fileStore)), "WARN file state");
        // a users file whose directory does not exist (cannot be written): FAIL
        FileUserStore homeless = new FileUserStore(new File(new File(tmp.getRoot(), "missing-dir"), "users.json"),
                AuthTestSupport.FILE_KEY);
        fails(probe(b -> b.users(homeless)), "file state");
        FileUserStore clean = new FileUserStore(new File(tmp.newFolder(), "users.json"), AuthTestSupport.FILE_KEY);
        silent(probe(b -> b.users(clean)), "file state");
        // errors() placement: a route after the last auth error layer
        Probe late = new Probe(b -> b, a -> { }, p -> { });
        late.app.get("/late", (req, res) -> res.send("late"));
        reports(late, "WARN errors placement");
        // the cookie-mode WebSocket origin and the wrapper count
        reports(new Probe(b -> b.transport(TokenTransport.COOKIE), a -> { },
                p -> p.app.ws("/ws", p.auth.webSocket((req, ws) -> { }))), "WARN websocket origin");
        reports(new Probe(b -> b, a -> { }, p -> p.app.ws("/ws", (req, ws) -> { })), "WARN websocket wrapper");
        // supportsAtomicFieldWrites and supportsAtomicLimitInsert under MULTI_INSTANCE
        AppStores ds = new AppStores();
        com.snoworca.naruserve.utils.auth.user.UserStore shared = ds.users();
        fails(probe(b -> multi(b, ds, new Undeclared(shared))), "atomic field writes");
        fails(probe(b -> multi(b, ds, shared).apiKeys(new Wave9KeyOwnersTest.Declared(false))), "atomic limit insert");
        // the admin-scope key count (opt-in off) and the opt-in itself, in separate apps
        reports(probe(b -> b.apiKeys(new Wave9KeyOwnersTest.Counted(2)).allowAdminScopesOnApiKeys(false)),
                "WARN admin scopes on keys");
        Probe optIn = probe(b -> b.apiKeys(new Wave9KeyOwnersTest.Counted(0)).allowAdminScopesOnApiKeys(true));
        reports(optIn, "WARN admin scopes on keys");
        // LoginThrottle.usesLimitArguments with API keys on
        Probe three = probe(b -> b.apiKeys(new Wave9KeyOwnersTest.Counted(0)).loginThrottle(new ThreeMethods()));
        reports(three, "WARN login throttle limits");
        boolean stated = false;
        for (Doctor.Finding x : three.auth.doctor(three.app).findings()) {
            stated |= x.getItem().equals("login throttle limits") && x.getMessage().contains(".apiKeyFailureLimit")
                    && x.getMessage().contains("not used");
        }
        assertTrue("the WARN states that the .apiKeyFailureLimit values are not used", stated);
        silent(probe(b -> b.apiKeys(new Wave9KeyOwnersTest.Counted(0))
                .loginThrottle(InMemoryLoginThrottle.builder().build())), "login throttle limits");
        silent(probe(b -> b.loginThrottle(new ThreeMethods())), "login throttle limits");
    }

    // OPS-AUTH-001 AC-3: PROD with the legacy trustProxy(true) is a doctor FAIL; MULTI_INSTANCE with no proxy trust is a
    // WARN; hop mode (trustProxyHops) under MULTI_INSTANCE or STRICT is a WARN recommending CIDR mode
    @Test
    public void OPS_AUTH_001_AC3_proxyTrustItems() throws Exception {
        fails(new Probe(b -> b, a -> a.trustProxy(true), p -> { }), "proxy trust");
        silent(new Probe(b -> b.mode(AuthMode.DEV), a -> a.trustProxy(true), p -> { }), "proxy trust");
        AppStores ds = new AppStores();
        com.snoworca.naruserve.utils.auth.user.UserStore shared = ds.users();
        Probe none = probe(b -> multi(b, ds, shared));
        reports(none, "WARN proxy trust");
        Probe cidr = new Probe(b -> multi(b, ds, shared), a -> a.trustProxyCidrs("10.0.0.0/8"), p -> { });
        silent(cidr, "proxy trust");
        for (Probe hops : new Probe[] {new Probe(b -> multi(b, ds, shared), a -> a.trustProxyHops(1), p -> { }),
            new Probe(b -> b.profile(AuthProfile.STRICT), a -> a.trustProxyHops(1), p -> { })}) {
            reports(hops, "WARN proxy trust");
            boolean cidrAdvice = false;
            for (Doctor.Finding x : hops.auth.doctor(hops.app).findings()) {
                cidrAdvice |= x.getItem().equals("proxy trust") && x.getMessage().contains("trustProxyCidrs");
            }
            assertTrue(cidrAdvice);
        }
        silent(new Probe(b -> b, a -> a.trustProxyHops(1), p -> { }), "proxy trust");
    }

    // OPS-AUTH-001 AC-5: the two R40 WebSocket items run inside this doctor report at the levels FR-FLOW-008 AC-5
    // (cookie-mode origin: WARN) and AC-6 (wrapper count: WARN) give them, and are logged with the other items at start
    @Test
    public void OPS_AUTH_001_AC5_webSocketItemsInTheReport() throws Exception {
        Probe both = new Probe(b -> b.transport(TokenTransport.COOKIE), a -> { }, p -> {
            p.app.ws("/wrapped", p.auth.webSocket((req, ws) -> { }));
            p.app.ws("/bare", (req, ws) -> { });
        });
        List<String> r = both.report();
        assertTrue(r.toString(), r.contains("WARN websocket origin"));
        assertTrue(r.toString(), r.contains("WARN websocket wrapper"));
        both.app.listen(0);
        assertEquals(1, RecordingLogger.countMatching(both.log.lines, "WARN auth doctor WARN websocket origin: .*"));
        assertEquals(1, RecordingLogger.countMatching(both.log.lines, "WARN auth doctor WARN websocket wrapper: .*"));
        Probe header = new Probe(b -> b, a -> { }, p -> p.app.ws("/wrapped", p.auth.webSocket((req, ws) -> { })));
        silent(header, "websocket origin");
        silent(header, "websocket wrapper");
    }
}
