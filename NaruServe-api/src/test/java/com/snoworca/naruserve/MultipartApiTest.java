package com.snoworca.naruserve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.internal.DefaultRestApp;
import com.snoworca.naruserve.internal.MultipartSettings;
import com.snoworca.naruserve.testing.SettingsCapturingAdapter;
import com.snoworca.naruserve.testing.StubJsonCodec;
import java.io.File;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/** app.multipart(...) and app.formLimit(...). SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-6-1.sds.md. */
public class MultipartApiTest {

    private static DefaultRestApp app() {
        return (DefaultRestApp) RestApp.builder().httpAdapter(new SettingsCapturingAdapter())
                .jsonCodec(new StubJsonCodec()).build();
    }

    private static void rejected(String name, String range, Configurer<MultipartOptions> setter) {
        DefaultRestApp app = app();
        try {
            app.multipart(setter);
            fail(name + " accepted an out-of-range value");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().startsWith(name + " must be in " + range + ": "));
        }
    }

    // IR-MULTIPART-001 AC-1
    @Test
    public void IR_MULTIPART_001_AC1_aConfigurerSettingAllFiveValuesIsApplied() {
        DefaultRestApp app = app();
        File dir = new File("uploads-tmp");
        RestApp returned = app.multipart(o -> o.maxFileSize(1024).maxRequestSize(2048).maxParts(3)
                .memoryThreshold(512).tempDirectory(dir));
        assertSame(app, returned);
        MultipartSettings s = app.multipartSettings();
        assertEquals(1024L, s.maxFileSize());
        assertEquals(2048L, s.maxRequestSize());
        assertEquals(3, s.maxParts());
        assertEquals(512, s.memoryThreshold());
        assertEquals(dir, s.tempDirectory());
    }

    // SDS-AC-1; IR-MULTIPART-001 AC-1
    @Test
    public void sdsAc1_IR_MULTIPART_001_AC1_aThrowingConfigurerKeepsEveryEarlierSetting() {
        DefaultRestApp app = app();
        app.multipart(o -> o.maxParts(7));
        RuntimeException boom = new RuntimeException("boom");
        try {
            app.multipart(o -> {
                o.maxFileSize(10).maxParts(2);
                throw boom;
            });
            fail("configurer exception was swallowed");
        } catch (RuntimeException e) {
            assertSame(boom, e);
        }
        MultipartSettings s = app.multipartSettings();
        assertEquals(33554432L, s.maxFileSize());
        assertEquals(7, s.maxParts());
    }

    // SDS-AC-2; IR-MULTIPART-001 AC-3
    @Test
    public void sdsAc2_IR_MULTIPART_001_AC3_settersRejectZeroNegativeAndAboveTheUpperBoundNamingTheSetting() {
        long maxSize = 4294967296L;
        for (long bad : new long[] {0, -1, Long.MIN_VALUE, maxSize + 1, Long.MAX_VALUE}) {
            rejected("maxFileSize", "1..4294967296", o -> o.maxFileSize(bad));
            rejected("maxRequestSize", "1..4294967296", o -> o.maxRequestSize(bad));
        }
        for (int bad : new int[] {0, -1, Integer.MIN_VALUE, 10001}) {
            rejected("maxParts", "1..10000", o -> o.maxParts(bad));
        }
        for (int bad : new int[] {0, -1, Integer.MIN_VALUE, 16777217}) {
            rejected("memoryThreshold", "1..16777216", o -> o.memoryThreshold(bad));
        }
        DefaultRestApp ok = app();
        ok.multipart(o -> o.maxFileSize(1).maxFileSize(maxSize).maxRequestSize(1).maxRequestSize(maxSize)
                .maxParts(1).maxParts(10000).memoryThreshold(1).memoryThreshold(16777216));
        assertEquals(maxSize, ok.multipartSettings().maxFileSize());
        assertEquals(16777216, ok.multipartSettings().memoryThreshold());
    }

    // SDS-AC-2; IR-MULTIPART-001 AC-3
    @Test
    public void sdsAc2_IR_MULTIPART_001_AC3_tempDirectoryNullThrowsNullPointerException() {
        DefaultRestApp app = app();
        File before = app.multipartSettings().tempDirectory();
        try {
            app.multipart(o -> o.tempDirectory(nullFile()));
            fail("tempDirectory(null) accepted");
        } catch (NullPointerException expected) {
            // refused
        }
        assertEquals(before, app.multipartSettings().tempDirectory());
    }

    private static File nullFile() {
        return null;
    }

    // SDS-AC-3; IR-MULTIPART-001 AC-6
    @Test
    public void sdsAc3_IR_MULTIPART_001_AC6_multipartAndFormLimitAfterListenThrowBeforeTheConfigurerRuns() {
        DefaultRestApp app = app();
        app.listen(0);
        try {
            AtomicInteger calls = new AtomicInteger();
            try {
                app.multipart(o -> {
                    calls.incrementAndGet();
                    o.maxParts(2);
                });
                fail("multipart after listen accepted");
            } catch (IllegalStateException expected) {
                // NEW-state guard
            }
            assertEquals(0, calls.get());
            assertEquals(20, app.multipartSettings().maxParts());
            try {
                app.formLimit(2048);
                fail("formLimit after listen accepted");
            } catch (IllegalStateException expected) {
                // NEW-state guard
            }
            assertEquals(1048576L, app.formLimit());
        } finally {
            app.shutdown(1, TimeUnit.SECONDS);
        }
    }

    // SDS-AC-4; IR-MULTIPART-001 AC-2
    @Test
    public void sdsAc4_IR_MULTIPART_001_AC2_defaultsAndTmpdirReadOnceAtConstruction() {
        String tmp = System.getProperty("java.io.tmpdir");
        DefaultRestApp app = app();
        try {
            System.setProperty("java.io.tmpdir", tmp + File.separator + "changed-after-construction");
            MultipartSettings s = app.multipartSettings();
            assertEquals(33554432L, s.maxFileSize());
            assertEquals(67108864L, s.maxRequestSize());
            assertEquals(20, s.maxParts());
            assertEquals(65536, s.memoryThreshold());
            assertEquals(new File(tmp), s.tempDirectory());
        } finally {
            System.setProperty("java.io.tmpdir", tmp);
        }
    }

    // IR-MULTIPART-001 AC-4
    @Test
    public void IR_MULTIPART_001_AC4_formLimitDefaultsToOneMebibyteAndRejectsOutOfRange() {
        DefaultRestApp app = app();
        assertEquals(1048576L, app.formLimit());
        assertSame(app, app.formLimit(1));
        assertEquals(1L, app.formLimit());
        app.formLimit(1073741824L);
        assertEquals(1073741824L, app.formLimit());
        for (long bad : new long[] {0, -1, Long.MIN_VALUE, 1073741825L}) {
            try {
                app.formLimit(bad);
                fail("formLimit accepted " + bad);
            } catch (IllegalArgumentException e) {
                assertTrue(e.getMessage(), e.getMessage().startsWith("formLimit must be in 1..1073741824"));
            }
            assertEquals(1073741824L, app.formLimit());
        }
    }

    // IR-MULTIPART-001 AC-5
    @Test
    public void IR_MULTIPART_001_AC5_partHeaderLimitsAreFixedAndHaveNoSetter() {
        assertEquals(8192, MultipartSettings.MAX_HEADER_LINE);
        assertEquals(16384, MultipartSettings.MAX_HEADER_TOTAL);
        for (Class<?> c : new Class<?>[] {MultipartOptions.class, RestApp.class}) {
            for (Method m : c.getMethods()) {
                String n = m.getName().toLowerCase(java.util.Locale.ROOT);
                assertTrue(c.getSimpleName() + "." + m.getName(), !n.contains("header"));
            }
        }
        for (Method m : MultipartOptions.class.getMethods()) {
            assertSame(m.getName(), MultipartOptions.class, m.getReturnType());
        }
    }
}
