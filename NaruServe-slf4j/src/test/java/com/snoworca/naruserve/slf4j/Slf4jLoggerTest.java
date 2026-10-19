package com.snoworca.naruserve.slf4j;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.slf4j.helpers.MarkerIgnoringBase;
import org.slf4j.impl.StaticLoggerBinder;

/** SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-4-9.sds.md. */
public class Slf4jLoggerTest {

    /**
     * Hand-written recording {@code org.slf4j.Logger} (test scope only, FR-APP-018 AC-4): the six overloads the
     * adapter may call are recorded as {@code name[arg, ...]}, so {@code error(String)} and
     * {@code error(String, Throwable)} stay distinct (FR-APP-018 AC-1); every other overload fails the test.
     */
    private static org.slf4j.Logger recording(List<String> calls) {
        return new RecordingLogger(calls);
    }

    private static final class RecordingLogger extends MarkerIgnoringBase {
        private static final long serialVersionUID = 1L;
        private final transient List<String> calls;

        RecordingLogger(List<String> calls) {
            this.calls = calls;
        }

        private static AssertionError unexpected(String overload) {
            return new AssertionError("unexpected overload " + overload);
        }

        @Override public boolean isTraceEnabled() { return true; }
        @Override public boolean isDebugEnabled() { return true; }
        @Override public boolean isInfoEnabled() { return true; }
        @Override public boolean isWarnEnabled() { return true; }
        @Override public boolean isErrorEnabled() { return true; }

        @Override public void trace(String msg) { calls.add("trace[" + msg + "]"); }
        @Override public void debug(String msg) { calls.add("debug[" + msg + "]"); }
        @Override public void info(String msg) { calls.add("info[" + msg + "]"); }
        @Override public void warn(String msg) { calls.add("warn[" + msg + "]"); }
        @Override public void error(String msg) { calls.add("error[" + msg + "]"); }
        @Override public void error(String msg, Throwable t) { calls.add("error[" + msg + ", " + t + "]"); }

        @Override public void trace(String format, Object arg) { throw unexpected("trace(String,Object)"); }
        @Override public void trace(String format, Object a1, Object a2) { throw unexpected("trace(String,Object,Object)"); }
        @Override public void trace(String format, Object... args) { throw unexpected("trace(String,Object...)"); }
        @Override public void trace(String msg, Throwable t) { throw unexpected("trace(String,Throwable)"); }
        @Override public void debug(String format, Object arg) { throw unexpected("debug(String,Object)"); }
        @Override public void debug(String format, Object a1, Object a2) { throw unexpected("debug(String,Object,Object)"); }
        @Override public void debug(String format, Object... args) { throw unexpected("debug(String,Object...)"); }
        @Override public void debug(String msg, Throwable t) { throw unexpected("debug(String,Throwable)"); }
        @Override public void info(String format, Object arg) { throw unexpected("info(String,Object)"); }
        @Override public void info(String format, Object a1, Object a2) { throw unexpected("info(String,Object,Object)"); }
        @Override public void info(String format, Object... args) { throw unexpected("info(String,Object...)"); }
        @Override public void info(String msg, Throwable t) { throw unexpected("info(String,Throwable)"); }
        @Override public void warn(String format, Object arg) { throw unexpected("warn(String,Object)"); }
        @Override public void warn(String format, Object a1, Object a2) { throw unexpected("warn(String,Object,Object)"); }
        @Override public void warn(String format, Object... args) { throw unexpected("warn(String,Object...)"); }
        @Override public void warn(String msg, Throwable t) { throw unexpected("warn(String,Throwable)"); }
        @Override public void error(String format, Object arg) { throw unexpected("error(String,Object)"); }
        @Override public void error(String format, Object a1, Object a2) { throw unexpected("error(String,Object,Object)"); }
        @Override public void error(String format, Object... args) { throw unexpected("error(String,Object...)"); }
    }

    // SDS-AC-12; FR-APP-018 AC-1, FR-APP-018 AC-4
    @Test
    public void sdsAc12_FR_APP_018_AC1_AC4_sixMethodsForwardOnceWithTheSameMessageAndThrowable() {
        List<String> calls = new ArrayList<>();
        Slf4jLogger logger = new Slf4jLogger(recording(calls));
        IllegalStateException boom = new IllegalStateException("boom");
        logger.trace("t");
        logger.debug("d");
        logger.info("i");
        logger.warn("w");
        logger.error("e");
        logger.error("e2", boom);
        assertEquals(Arrays.asList("trace[t]", "debug[d]", "info[i]", "warn[w]", "error[e]", "error[e2, " + boom + "]"),
                calls);
    }

    // SDS-AC-13; FR-APP-018 AC-2
    @Test
    public void sdsAc13_FR_APP_018_AC2_defaultConstructorRequestsNaruServeAndNullDelegateThrowsNpe() {
        new Slf4jLogger().info("hello");
        assertTrue(StaticLoggerBinder.REQUESTED_NAMES.toString(), StaticLoggerBinder.REQUESTED_NAMES.contains("NaruServe"));
        try {
            new Slf4jLogger(null);
            fail("null delegate accepted");
        } catch (NullPointerException expected) {
            // the delegate is required
        }
    }
}
