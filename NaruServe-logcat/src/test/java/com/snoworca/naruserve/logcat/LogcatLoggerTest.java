package com.snoworca.naruserve.logcat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.util.Log;
import com.snoworca.naruserve.Logger;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-4-9.sds.md. */
public class LogcatLoggerTest {

    @Before
    @After
    public void reset() {
        Log.CALLS.clear();
        Log.failure = null;
    }

    private static void logAll(Logger logger) {
        logger.trace("t");
        logger.debug("d");
        logger.info("i");
        logger.warn("w");
        logger.error("e");
        logger.error("e2", new IllegalStateException("boom"));
    }

    // SDS-AC-1; FR-APP-016 AC-1
    @Test
    public void sdsAc1_FR_APP_016_AC1_sixMethodsCallVDIWEWithTagNaruServe() {
        logAll(new LogcatLogger());
        assertEquals(Arrays.asList("v|NaruServe|t", "d|NaruServe|d", "i|NaruServe|i", "w|NaruServe|w",
                "e|NaruServe|e", "e|NaruServe|e2|boom"), Log.CALLS);
    }

    // SDS-AC-2; FR-APP-016 AC-2
    @Test
    public void sdsAc2_FR_APP_016_AC2_customTagAndNullTagThrowsNpe() {
        new LogcatLogger("MyApp").info("hello");
        assertEquals(Arrays.asList("i|MyApp|hello"), Log.CALLS);
        try {
            new LogcatLogger(null);
            fail("null tag accepted");
        } catch (NullPointerException expected) {
            // checked before the class lookup
        }
    }

    // SDS-AC-3; FR-APP-016 AC-3
    @Test
    public void sdsAc3_FR_APP_016_AC3_throwingLogCallIsSwallowed() {
        LogcatLogger logger = new LogcatLogger();
        Log.failure = new IllegalStateException("log down");
        logAll(logger);
        assertTrue(Log.CALLS.isEmpty());
    }

    // SDS-AC-4, SDS-AC-3; FR-APP-016 AC-3
    @Test
    public void sdsAc4_FR_APP_016_AC3_withoutAndroidLogTheConstructorThrowsIseNamingTheClass() throws Exception {
        URL logcatMain = LogcatLogger.class.getProtectionDomain().getCodeSource().getLocation();
        URL apiMain = Logger.class.getProtectionDomain().getCodeSource().getLocation();
        URL testClasses = Log.class.getProtectionDomain().getCodeSource().getLocation();
        assertNotEquals(testClasses, logcatMain);
        assertNotEquals(testClasses, apiMain);
        // Parent null is the bootstrap loader, so the test classes (the fake Log) stay hidden.
        try (URLClassLoader isolated = new URLClassLoader(new URL[] {logcatMain, apiMain}, null)) {
            try {
                isolated.loadClass("android.util.Log");
                fail("android.util.Log is visible to the isolated loader");
            } catch (ClassNotFoundException expected) {
                // the case under test
            }
            Class<?> c = isolated.loadClass(LogcatLogger.class.getName());
            try {
                c.getConstructor().newInstance();
                fail("constructor succeeded without android.util.Log");
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                assertEquals(IllegalStateException.class.getName(), cause.getClass().getName());
                assertTrue(cause.getMessage(), cause.getMessage().contains("android.util.Log"));
            }
        }
    }
}
