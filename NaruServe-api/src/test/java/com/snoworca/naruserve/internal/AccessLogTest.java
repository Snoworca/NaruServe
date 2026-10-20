package com.snoworca.naruserve.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.LogLevel;
import com.snoworca.naruserve.Logger;
import com.snoworca.naruserve.testing.RecordingLogger;
import java.util.Collections;
import org.junit.Test;

/** Access line format, level and isolation. SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-4-8.sds.md. */
public class AccessLogTest {

    private static final String RID = "0123456789abcdef";

    private final RecordingLogger log = new RecordingLogger();

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

    // SDS-AC-4; FR-APP-019 AC-1
    @Test
    public void sdsAc4_FR_APP_019_AC1_defaultFormatHasEveryPart() {
        assertEquals("GET /users/7 200 12ms in=0 out=5 rid=" + RID,
                accessLog(new DefaultAccessLogOptions()).format("GET", "/users/7", 200, false, 12, 0, 5, RID));
    }

    // SDS-AC-5; FR-APP-019 AC-2
    @Test
    public void sdsAc5_FR_APP_019_AC2_partsOffDropEachPartWithItsLeadingSpace() {
        DefaultAccessLogOptions o = new DefaultAccessLogOptions();
        o.includeDuration(false).includeBytes(false).includeRequestId(false);
        assertEquals("GET /users/7 200", accessLog(o).format("GET", "/users/7", 200, false, 12, 0, 5, RID));
        o.includeBytes(true);
        assertEquals("GET /users/7 200 aborted in=3 out=4",
                accessLog(o).format("GET", "/users/7", 200, true, 12, 3, 4, RID));
    }

    // SDS-AC-5; FR-APP-019 AC-2
    @Test
    public void sdsAc5_FR_APP_019_AC2_levelSelectsTheLoggerMethod() {
        for (LogLevel level : LogLevel.values()) {
            log.lines.clear();
            DefaultAccessLogOptions o = new DefaultAccessLogOptions();
            o.level(level).includeDuration(false).includeBytes(false).includeRequestId(false);
            accessLog(o).write("GET", "/users/7", 200, false, System.nanoTime(), 0, 5, RID);
            assertEquals(Collections.singletonList(level.name() + " GET /users/7 200"), log.lines);
        }
    }

    // SDS-AC-9; FR-APP-019 AC-6
    @Test
    public void sdsAc9_FR_APP_019_AC6_excludedPathAndDisabledLogWriteNothing() {
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

    // IR-APP-007 AC-3, FR-APP-019 AC-6 — the exclude set is fixed when the log is built at listen()
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

    // SDS-AC-3 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-12.sds.md; FR-APP-019 AC-9: the close line is
    // WS <path> <code>[ <n>ms][ rid=<id>], with no bytes part, for every close code
    @Test
    public void wave7SdsAc3_FR_APP_019_AC9_closeLineHasTheTokenWsTheCodeTheDurationAndTheRid() {
        for (int code : new int[] {1000, 1001, 1008, 4000, 1006}) {
            log.lines.clear();
            accessLog(new DefaultAccessLogOptions()).writeClose("/ws", code, System.nanoTime(), RID);
            assertEquals(log.lines.toString(), 1, log.lines.size());
            assertTrue(log.lines.get(0), log.lines.get(0).matches("INFO WS /ws " + code + " \\d+ms rid=" + RID));
        }
        log.lines.clear();
        DefaultAccessLogOptions o = new DefaultAccessLogOptions();
        o.includeDuration(false).includeRequestId(false);
        accessLog(o).writeClose("/a\nb", 1000, System.nanoTime(), RID);
        assertEquals(Collections.singletonList("INFO WS /a\\nb 1000"), log.lines);
    }

    // SDS-AC-4 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-12.sds.md; FR-APP-019 AC-10: the SSE line is
    // the D-173 line, never aborted, followed by sse start=<epochMillis> cause=<cause>
    @Test
    public void wave7SdsAc4_FR_APP_019_AC10_sseLineAppendsStartAndCauseToTheRequestLine() {
        accessLog(new DefaultAccessLogOptions()).writeSse("GET", "/events", 200, System.nanoTime(), 0, 18, RID,
                1700000000123L, "client");
        assertEquals(log.lines.toString(), 1, log.lines.size());
        assertTrue(log.lines.get(0), log.lines.get(0).matches(
                "INFO GET /events 200 \\d+ms in=0 out=18 rid=" + RID + " sse start=1700000000123 cause=client"));
        log.lines.clear();
        DefaultAccessLogOptions o = new DefaultAccessLogOptions();
        o.includeDuration(false).includeBytes(false).includeRequestId(false);
        accessLog(o).writeSse("GET", "/events", 200, System.nanoTime(), 0, 18, RID, 5L, "shutdown");
        assertEquals(Collections.singletonList("INFO GET /events 200 sse start=5 cause=shutdown"), log.lines);
    }

    // SDS-AC-5 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-12.sds.md; FR-APP-019 AC-11: exclude("/ws") and
    // exclude("/events") drop those lines, enabled(false) drops all, level(DEBUG) writes them through debug
    @Test
    public void wave7SdsAc5_FR_APP_019_AC11_closeAndSseLinesFollowExcludeEnabledAndLevel() {
        DefaultAccessLogOptions ex = new DefaultAccessLogOptions();
        ex.exclude("/ws").exclude("/events");
        AccessLog excluded = accessLog(ex);
        excluded.writeClose("/ws", 1000, System.nanoTime(), RID);
        excluded.writeSse("GET", "/events", 200, System.nanoTime(), 0, 0, RID, 1L, "app");
        excluded.write("GET", "/ws", 101, false, System.nanoTime(), 0, 0, RID);
        assertEquals(Collections.emptyList(), log.lines);
        excluded.writeClose("/ws2", 1000, System.nanoTime(), RID);
        assertEquals(log.lines.toString(), 1, log.lines.size());
        log.lines.clear();
        DefaultAccessLogOptions off = new DefaultAccessLogOptions();
        off.enabled(false);
        accessLog(off).writeClose("/ws", 1000, System.nanoTime(), RID);
        accessLog(off).writeSse("GET", "/events", 200, System.nanoTime(), 0, 0, RID, 1L, "app");
        assertEquals(Collections.emptyList(), log.lines);
        DefaultAccessLogOptions debug = new DefaultAccessLogOptions();
        debug.level(LogLevel.DEBUG).includeDuration(false).includeBytes(false).includeRequestId(false);
        accessLog(debug).writeClose("/ws", 1001, System.nanoTime(), RID);
        accessLog(debug).writeSse("GET", "/events", 200, System.nanoTime(), 0, 0, RID, 1L, "app");
        assertEquals(java.util.Arrays.asList("DEBUG WS /ws 1001", "DEBUG GET /events 200 sse start=1 cause=app"),
                log.lines);
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
