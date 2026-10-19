package com.snoworca.naruserve.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.LogLevel;
import com.snoworca.naruserve.Logger;
import java.util.Collections;
import org.junit.Test;

/** Access line format, level and isolation. SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-4-8.sds.md. */
public class AccessLogTest {

    private static final String RID = "0123456789abcdef";

    private final ErrorPathTest.RecordingLogger log = new ErrorPathTest.RecordingLogger();

    private AccessLog accessLog(DefaultAccessLogOptions options) {
        return new AccessLog(options, new SafeLogger(log));
    }

    // SDS-AC-3; FR-APP-014 AC-3
    @Test
    public void sdsAc3_FR_APP_014_AC3_writeWithAThrowingLoggerReturnsNormally() {
        Logger throwing = new Logger() {
            @Override
            public void trace(String message) {
                throw new IllegalStateException("trace");
            }

            @Override
            public void debug(String message) {
                throw new IllegalStateException("debug");
            }

            @Override
            public void info(String message) {
                throw new IllegalStateException("info");
            }

            @Override
            public void warn(String message) {
                throw new IllegalStateException("warn");
            }

            @Override
            public void error(String message) {
                throw new IllegalStateException("error");
            }

            @Override
            public void error(String message, Throwable error) {
                throw new IllegalStateException("error2");
            }
        };
        new AccessLog(new DefaultAccessLogOptions(), new SafeLogger(throwing))
                .write("GET", "/users/7", 200, false, System.nanoTime(), 0, 5, RID);
        // a null method token makes formatting itself throw; write still returns normally
        accessLog(new DefaultAccessLogOptions()).write(null, "/users/7", 200, false, System.nanoTime(), 0, 5, RID);
    }

    // SDS-AC-4; FR-APP-015 AC-1
    @Test
    public void sdsAc4_FR_APP_015_AC1_defaultFormatHasEveryPart() {
        assertEquals("GET /users/7 200 12ms in=0 out=5 rid=" + RID,
                accessLog(new DefaultAccessLogOptions()).format("GET", "/users/7", 200, false, 12, 0, 5, RID));
    }

    // SDS-AC-5; FR-APP-015 AC-2
    @Test
    public void sdsAc5_FR_APP_015_AC2_partsOffDropEachPartWithItsLeadingSpace() {
        DefaultAccessLogOptions o = new DefaultAccessLogOptions();
        o.includeDuration(false).includeBytes(false).includeRequestId(false);
        assertEquals("GET /users/7 200", accessLog(o).format("GET", "/users/7", 200, false, 12, 0, 5, RID));
        o.includeBytes(true);
        assertEquals("GET /users/7 200 aborted in=3 out=4",
                accessLog(o).format("GET", "/users/7", 200, true, 12, 3, 4, RID));
    }

    // SDS-AC-5; FR-APP-015 AC-2
    @Test
    public void sdsAc5_FR_APP_015_AC2_levelSelectsTheLoggerMethod() {
        for (LogLevel level : LogLevel.values()) {
            log.lines.clear();
            DefaultAccessLogOptions o = new DefaultAccessLogOptions();
            o.level(level).includeDuration(false).includeBytes(false).includeRequestId(false);
            accessLog(o).write("GET", "/users/7", 200, false, System.nanoTime(), 0, 5, RID);
            assertEquals(Collections.singletonList(level.name() + " GET /users/7 200"), log.lines);
        }
    }

    // SDS-AC-9; FR-APP-015 AC-6
    @Test
    public void sdsAc9_FR_APP_015_AC6_excludedPathAndDisabledLogWriteNothing() {
        DefaultAccessLogOptions o = new DefaultAccessLogOptions();
        o.exclude("/health");
        assertTrue(o.excluded("/health"));
        assertTrue(!o.excluded("/Health") && !o.excluded("/health/x") && !o.excluded("/healthz"));
        accessLog(o).write("GET", "/health", 200, false, System.nanoTime(), 0, 0, RID);
        assertEquals(Collections.emptyList(), log.lines);
        DefaultAccessLogOptions off = new DefaultAccessLogOptions();
        off.enabled(false);
        accessLog(off).write("GET", "/x", 200, false, System.nanoTime(), 0, 0, RID);
        assertEquals(Collections.emptyList(), log.lines);
    }

    // IR-APP-007 AC-3, FR-APP-015 AC-6 — the exclude set is fixed when the log is built at listen()
    @Test
    public void IR_APP_007_AC3_excludeAddedAfterTheLogIsBuiltHasNoEffect() {
        DefaultAccessLogOptions o = new DefaultAccessLogOptions();
        o.exclude("/health");
        AccessLog accessLog = accessLog(o);
        o.exclude("/late");
        accessLog.write("GET", "/health", 200, false, System.nanoTime(), 0, 0, RID);
        assertEquals(Collections.emptyList(), log.lines);
        accessLog.write("GET", "/late", 200, false, System.nanoTime(), 0, 0, RID);
        assertEquals(log.lines.toString(), 1, log.lines.size());
    }

    // SDS-AC-12; SEC-APP-001 AC-2
    @Test
    public void sdsAc12_SEC_APP_001_AC2_escapeControlCharsAndBackslashKeepPercentEncodingRaw() {
        assertEquals("a\\r\\n\\tb\\u0001\\u007f\\u0085\\\\c", AccessLog.escape("a\r\n\tb\u0001\u007f\u0085\\c"));
        assertEquals("\\u0000\\u001f\\u0080\\u009f ", AccessLog.escape("\u0000\u001f\u0080\u009f "));
        assertEquals("/a%0d%0a", AccessLog.escape("/a%0d%0a"));
        assertEquals("B\\rAD /a\\nb 501", accessLog(new DefaultAccessLogOptions()).format("B\rAD", "/a\nb", 501,
                false, 0, 0, 0, RID).substring(0, "B\\rAD /a\\nb 501".length()));
    }
}
