package com.snoworca.naruserve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.spi.ServerSettings;
import com.snoworca.naruserve.testing.SettingsCapturingAdapter;
import com.snoworca.naruserve.testing.StubJsonCodec;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.Test;

/** app.server(...) and ServerOptions. SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-5-4.sds.md. */
public class ServerOptionsTest {

    private final SettingsCapturingAdapter adapter = new SettingsCapturingAdapter();

    private RestApp app() {
        return RestApp.builder().httpAdapter(adapter).jsonCodec(new StubJsonCodec()).build();
    }

    private ServerSettings frozen(RestApp app) {
        app.listen(8080);
        app.shutdown(1, TimeUnit.SECONDS);
        assertNotNull(adapter.settings);
        return adapter.settings;
    }

    private static void rejected(String name, String range, Consumer<ServerOptions> setter) {
        try {
            RestApp.builder().httpAdapter(new SettingsCapturingAdapter()).jsonCodec(new StubJsonCodec()).build()
                    .server(setter::accept);
            fail(name + " accepted an out-of-range value");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(name));
            assertTrue(e.getMessage(), e.getMessage().contains(range));
        }
    }

    private static void refused(String what, Consumer<ServerOptions> setter) {
        try {
            RestApp.builder().httpAdapter(new SettingsCapturingAdapter()).jsonCodec(new StubJsonCodec()).build()
                    .server(setter::accept);
            fail(what + " was accepted");
        } catch (NullPointerException | IllegalArgumentException expected) {
            // refused
        }
    }

    private static void accepted(Consumer<ServerOptions> setter) {
        RestApp.builder().httpAdapter(new SettingsCapturingAdapter()).jsonCodec(new StubJsonCodec()).build()
                .server(setter::accept);
    }

    // SDS-AC-1; IR-ENGINE-001 AC-2, IR-ENGINE-001 AC-5
    @Test
    public void sdsAc1_IR_ENGINE_001_AC2_AC5_withoutServerTheFrozenSettingsAreTheDocumentedDefaults() {
        ServerSettings s = frozen(app());
        assertEquals(8080, s.port());
        assertEquals("127.0.0.1", s.bindAddress());
        assertEquals(Collections.emptyList(), s.allowedHosts());
        assertEquals(16384, s.maxHeaderSize());
        assertEquals(100, s.maxHeaderCount());
        assertEquals(8190, s.maxRequestLineLength());
        assertEquals(10000L, s.headerTimeoutMillis());
        assertEquals(30000L, s.bodyReadTimeoutMillis());
        assertEquals(30000L, s.writeTimeoutMillis());
        assertEquals(240, s.minBodyRate());
        assertEquals(64, s.maxWorkers());
        assertEquals(128, s.maxSseStreams());
        assertEquals(512, s.maxConnections());
        assertEquals(128, s.maxUpgradedConnections());
        assertEquals(256, s.maxConnectionsPerAddress());
        assertNotNull(s.logger());
        assertNotNull(s.errorRenderer());
    }

    // SDS-AC-1; IR-ENGINE-001 AC-2
    @Test
    public void sdsAc1_IR_ENGINE_001_AC2_everySetterReachesTheFrozenSettings() {
        RestApp app = app().server(o -> o.bindAddress("0.0.0.0").allowedHosts("myphone.local", "box.lan")
                .maxHeaderSize(32768).maxHeaderCount(50).maxRequestLineLength(4096)
                .headerTimeout(2, TimeUnit.SECONDS).bodyReadTimeout(3, TimeUnit.SECONDS)
                .writeTimeout(4, TimeUnit.SECONDS).minBodyRate(1000).maxWorkers(8).maxSseStreams(4)
                .maxConnections(100).maxUpgradedConnections(10).maxConnectionsPerAddress(20));
        ServerSettings s = frozen(app);
        assertEquals("0.0.0.0", s.bindAddress());
        assertEquals(java.util.Arrays.asList("myphone.local", "box.lan"), s.allowedHosts());
        assertEquals(32768, s.maxHeaderSize());
        assertEquals(50, s.maxHeaderCount());
        assertEquals(4096, s.maxRequestLineLength());
        assertEquals(2000L, s.headerTimeoutMillis());
        assertEquals(3000L, s.bodyReadTimeoutMillis());
        assertEquals(4000L, s.writeTimeoutMillis());
        assertEquals(1000, s.minBodyRate());
        assertEquals(8, s.maxWorkers());
        assertEquals(4, s.maxSseStreams());
        assertEquals(100, s.maxConnections());
        assertEquals(10, s.maxUpgradedConnections());
        assertEquals(20, s.maxConnectionsPerAddress());
        try {
            s.allowedHosts().add("evil.example");
            fail("allowedHosts() is modifiable");
        } catch (UnsupportedOperationException expected) {
            // unmodifiable
        }
    }

    // SDS-AC-2; IR-ENGINE-001 AC-3
    @Test
    public void sdsAc2_IR_ENGINE_001_AC3_intSettersRejectZeroNegativeAndAboveTheMaximumNamingTheRange() {
        Object[][] table = {
            {"maxHeaderSize", 1048576}, {"maxHeaderCount", 10000}, {"maxRequestLineLength", 65534},
            {"minBodyRate", 104857600}, {"maxWorkers", 1024}, {"maxSseStreams", 1024},
            {"maxConnections", 65536}, {"maxUpgradedConnections", 65536}, {"maxConnectionsPerAddress", 65536},
        };
        for (Object[] row : table) {
            String name = (String) row[0];
            int max = (Integer) row[1];
            String range = "1.." + max;
            for (int bad : new int[] {0, -1, Integer.MIN_VALUE, max + 1, Integer.MAX_VALUE}) {
                rejected(name, range, o -> set(o, name, bad));
            }
            accepted(o -> set(o, name, 1));
            accepted(o -> set(o, name, max));
        }
    }

    private static void set(ServerOptions o, String name, int value) {
        try {
            ServerOptions.class.getMethod(name, int.class).invoke(o, value);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw (RuntimeException) e.getCause();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    // SDS-AC-2; IR-ENGINE-001 AC-3
    @Test
    public void sdsAc2_IR_ENGINE_001_AC3_timeoutsAreOneMillisecondToOneHour() {
        for (String name : new String[] {"headerTimeout", "bodyReadTimeout", "writeTimeout"}) {
            rejected(name, "1 ms..1 h", o -> timeout(o, name, 0, TimeUnit.MILLISECONDS));
            rejected(name, "1 ms..1 h", o -> timeout(o, name, -1, TimeUnit.SECONDS));
            rejected(name, "1 ms..1 h", o -> timeout(o, name, 3600001, TimeUnit.MILLISECONDS));
            rejected(name, "1 ms..1 h", o -> timeout(o, name, 2, TimeUnit.HOURS));
            rejected(name, "1 ms..1 h", o -> timeout(o, name, 999, TimeUnit.MICROSECONDS));
            rejected(name, "1 ms..1 h", o -> timeout(o, name, Long.MAX_VALUE, TimeUnit.DAYS));
            rejected(name, "1 ms..1 h", o -> timeout(o, name, 3600000999L, TimeUnit.MICROSECONDS));
            refused(name + " null unit", o -> timeout(o, name, 1, null));
            accepted(o -> timeout(o, name, 1, TimeUnit.MILLISECONDS));
            accepted(o -> timeout(o, name, 1, TimeUnit.HOURS));
        }
    }

    private static void timeout(ServerOptions o, String name, long t, TimeUnit u) {
        try {
            ServerOptions.class.getMethod(name, long.class, TimeUnit.class).invoke(o, t, u);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw (RuntimeException) e.getCause();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    // SDS-AC-2; IR-ENGINE-001 AC-3
    @Test
    public void sdsAc2_IR_ENGINE_001_AC3_nullAndEmptyNamesAreRefused() {
        refused("bindAddress(null)", o -> o.bindAddress(null));
        refused("bindAddress(\"\")", o -> o.bindAddress(""));
        refused("allowedHosts(null array)", o -> o.allowedHosts((String[]) null));
        refused("allowedHosts(null name)", o -> o.allowedHosts("ok.local", null));
        refused("allowedHosts(\"\")", o -> o.allowedHosts(""));
    }

    // IR-ENGINE-001 AC-1
    @Test
    public void IR_ENGINE_001_AC1_serverAndTrustProxyAreGuardedAndServerCallsApplyInOrder() {
        RestApp app = app();
        try {
            app.server(null);
            fail("server(null) accepted");
        } catch (NullPointerException expected) {
            // refused
        }
        app.server(o -> o.bindAddress("10.0.0.1").maxHeaderSize(32768));
        app.server(o -> o.bindAddress("0.0.0.0"));
        assertEquals(app, app.trustProxy(true));
        ServerSettings s = frozen(app);
        assertEquals("0.0.0.0", s.bindAddress());
        assertEquals(32768, s.maxHeaderSize());
        try {
            app.server(o -> o.maxWorkers(2));
            fail("server(...) accepted outside NEW");
        } catch (IllegalStateException expected) {
            // guarded
        }
        try {
            app.trustProxy(false);
            fail("trustProxy accepted outside NEW");
        } catch (IllegalStateException expected) {
            // guarded
        }
    }
}
