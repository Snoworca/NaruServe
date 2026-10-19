package com.snoworca.naruserve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.Flushable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ConsoleLoggerTest {

    private static final String TS = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}[+-]\\d{4}";

    /** Counts flush calls and records close calls on the wrapped bytes. */
    static final class TrackingStream extends OutputStream {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int flushes;
        boolean closed;

        @Override
        public void write(int b) {
            bytes.write(b);
        }

        @Override
        public void flush() {
            flushes++;
        }

        @Override
        public void close() {
            closed = true;
        }

        String text() {
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private PrintStream realOut;
    private PrintStream realErr;
    private TimeZone realZone;
    private TrackingStream out;
    private TrackingStream err;

    @Before
    public void captureStreams() {
        realOut = System.out;
        realErr = System.err;
        realZone = TimeZone.getDefault();
        out = new TrackingStream();
        err = new TrackingStream();
    }

    @After
    public void restoreStreams() {
        System.setOut(realOut);
        System.setErr(realErr);
        TimeZone.setDefault(realZone);
    }

    /** Swaps the streams after {@code logger} was built, so the logger must read them at write time. */
    private void redirect() {
        System.setOut(new PrintStream(out, true));
        System.setErr(new PrintStream(err, true));
    }

    private static List<String> lines(String text) {
        List<String> out = new ArrayList<>();
        for (String l : text.split("\\r?\\n")) {
            if (!l.isEmpty()) {
                out.add(l);
            }
        }
        return out;
    }

    private static void assertLine(String level, String message, String line) {
        assertTrue(line, Pattern.matches(TS + " " + level + " " + Pattern.quote(message), line));
    }

    // SDS-AC-9; IR-APP-006 AC-1
    @Test
    public void sdsAc9_IR_APP_006_AC1_loggerHasExactlySixMethodsAndLogLevelOrder() {
        Set<String> names = new TreeSet<>();
        for (java.lang.reflect.Method m : Logger.class.getMethods()) {
            assertTrue(m.getName(), Modifier.isAbstract(m.getModifiers()));
            names.add(m.getName() + Arrays.toString(m.getParameterTypes()));
        }
        assertEquals(new TreeSet<>(Arrays.asList("trace[class java.lang.String]", "debug[class java.lang.String]",
                "info[class java.lang.String]", "warn[class java.lang.String]", "error[class java.lang.String]",
                "error[class java.lang.String, class java.lang.Throwable]")), names);
        assertEquals(Arrays.asList(LogLevel.TRACE, LogLevel.DEBUG, LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR),
                Arrays.asList(LogLevel.values()));
    }

    // SDS-AC-9; IR-APP-006 AC-2
    @Test
    public void sdsAc9_IR_APP_006_AC2_defaultThresholdInfoWritesInfoToOutAndWarnErrorToErr() {
        ConsoleLogger logger = new ConsoleLogger();
        redirect();
        logger.trace("t");
        logger.debug("d");
        logger.info("i");
        logger.warn("w");
        logger.error("e");
        List<String> o = lines(out.text());
        List<String> e = lines(err.text());
        assertEquals(o.toString(), 1, o.size());
        assertLine("INFO", "i", o.get(0));
        assertEquals(e.toString(), 2, e.size());
        assertLine("WARN", "w", e.get(0));
        assertLine("ERROR", "e", e.get(1));
    }

    // SDS-AC-9; IR-APP-006 AC-2
    @Test
    public void sdsAc9_IR_APP_006_AC2_traceThresholdWritesAllFiveLevels() {
        ConsoleLogger logger = new ConsoleLogger(LogLevel.TRACE);
        redirect();
        logger.trace("t");
        logger.debug("d");
        logger.info("i");
        logger.warn("w");
        logger.error("e");
        List<String> o = lines(out.text());
        List<String> e = lines(err.text());
        assertEquals(o.toString(), 3, o.size());
        assertLine("TRACE", "t", o.get(0));
        assertLine("DEBUG", "d", o.get(1));
        assertLine("INFO", "i", o.get(2));
        assertEquals(e.toString(), 2, e.size());
        assertLine("WARN", "w", e.get(0));
        assertLine("ERROR", "e", e.get(1));
    }

    // SDS-AC-9; IR-APP-006 AC-2
    @Test
    public void sdsAc9_IR_APP_006_AC2_errorWithThrowableWritesTheStackTraceAfterTheLine() {
        ConsoleLogger logger = new ConsoleLogger();
        redirect();
        logger.error("failed", new IllegalStateException("boom"));
        List<String> e = lines(err.text());
        assertLine("ERROR", "failed", e.get(0));
        assertEquals("java.lang.IllegalStateException: boom", e.get(1));
        assertTrue(e.get(2), e.get(2).trim().startsWith("at "));
        assertEquals("", out.text());
    }

    // SDS-AC-9; IR-APP-006 AC-2
    @Test
    public void sdsAc9_IR_APP_006_AC2_timestampOffsetFollowsTheDefaultZoneAtWriteTime() {
        ConsoleLogger logger = new ConsoleLogger();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        String utc = logger.format("INFO", "m");
        assertLine("INFO", "m", utc);
        assertTrue(utc, utc.contains("+0000 INFO m"));
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
        String seoul = logger.format("WARN", "m");
        assertLine("WARN", "m", seoul);
        assertTrue(seoul, seoul.contains("+0900 WARN m"));
    }

    // SDS-AC-9; IR-APP-006 AC-2
    @Test
    public void sdsAc9_IR_APP_006_AC2_timestampDigitsStayAsciiUnderAnArabicLocale() {
        java.util.Locale realLocale = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(new java.util.Locale("ar", "EG"));
            assertLine("INFO", "m", new ConsoleLogger().format("INFO", "m"));
        } finally {
            java.util.Locale.setDefault(realLocale);
        }
    }

    // SDS-AC-10; IR-APP-006 AC-3
    @Test
    public void sdsAc10_IR_APP_006_AC3_closeAndFlushFlushBothStreamsAndLeaveThemOpen() {
        ConsoleLogger logger = new ConsoleLogger();
        System.setOut(new PrintStream(out, false));
        System.setErr(new PrintStream(err, false));
        logger.flush();
        assertTrue(out.flushes > 0);
        assertTrue(err.flushes > 0);
        int o = out.flushes;
        int e = err.flushes;
        logger.close();
        assertTrue(out.flushes > o);
        assertTrue(err.flushes > e);
        assertFalse(out.closed);
        assertFalse(err.closed);
        System.out.println("after");
        System.out.flush();
        assertEquals("after", lines(out.text()).get(0));
        assertFalse(System.out.checkError());
    }

    // SDS-AC-10; IR-APP-006 AC-5
    @Test
    public void sdsAc10_IR_APP_006_AC5_consoleLoggerIsPublicFinalAndReferencesNoSlf4jOrAndroidType()
            throws IOException {
        Class<ConsoleLogger> c = ConsoleLogger.class;
        assertTrue(Modifier.isPublic(c.getModifiers()));
        assertTrue(Modifier.isFinal(c.getModifiers()));
        assertTrue(Logger.class.isAssignableFrom(c));
        assertTrue(Flushable.class.isAssignableFrom(c));
        assertTrue(AutoCloseable.class.isAssignableFrom(c));
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (InputStream in = c.getResourceAsStream("ConsoleLogger.class")) {
            byte[] chunk = new byte[4096];
            for (int n; (n = in.read(chunk)) > 0; ) {
                buf.write(chunk, 0, n);
            }
        }
        String classFile = new String(buf.toByteArray(), StandardCharsets.ISO_8859_1);
        assertFalse(classFile.contains("org/slf4j"));
        assertFalse(classFile.contains("android/"));
    }
}
