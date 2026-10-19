package com.snoworca.naruserve;

import java.io.Flushable;
import java.io.PrintStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Objects;

/**
 * Default logger: one line {@code <timestamp> <LEVEL> <message>} per record at or above the threshold (INFO by
 * default); TRACE, DEBUG and INFO go to {@code System.out}, WARN and ERROR to {@code System.err}, both read at
 * write time. {@link #close()} and {@link #flush()} flush both streams and never close them.
 */
// @req IR-APP-006
public final class ConsoleLogger implements Logger, Flushable, AutoCloseable {

    private final LogLevel threshold;

    public ConsoleLogger() {
        this(LogLevel.INFO);
    }

    // @req IR-APP-006
    public ConsoleLogger(LogLevel threshold) {
        this.threshold = Objects.requireNonNull(threshold, "threshold");
    }

    @Override
    public void trace(String message) {
        write(LogLevel.TRACE, message, null);
    }

    @Override
    public void debug(String message) {
        write(LogLevel.DEBUG, message, null);
    }

    @Override
    public void info(String message) {
        write(LogLevel.INFO, message, null);
    }

    @Override
    public void warn(String message) {
        write(LogLevel.WARN, message, null);
    }

    @Override
    public void error(String message) {
        write(LogLevel.ERROR, message, null);
    }

    @Override
    public void error(String message, Throwable error) {
        write(LogLevel.ERROR, message, error);
    }

    // @req IR-APP-006
    private void write(LogLevel level, String message, @org.jspecify.annotations.Nullable Throwable error) {
        if (level.compareTo(threshold) < 0) {
            return;
        }
        PrintStream stream = level.compareTo(LogLevel.WARN) < 0 ? System.out : System.err;
        String line = format(level, message);
        // One lock so the stack trace stays right after its line.
        synchronized (stream) {
            stream.println(line);
            if (error != null) {
                error.printStackTrace(stream);
            }
        }
    }

    /**
     * The IR-APP-006 line {@code <timestamp> <LEVEL> <message>} without a line end, shared by other loggers that
     * write the same format; the default time zone is read at each call.
     */
    // @req IR-APP-006
    public static String format(LogLevel level, String message) {
        // Locale.US keeps the digits ASCII under locales such as ar or fa.
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(new Date()) + " " + level.name() + " "
                + message;
    }

    // @req IR-APP-006
    @Override
    public void flush() {
        System.out.flush();
        System.err.flush();
    }

    /** Flushes both streams; they stay open. */
    // @req IR-APP-006
    @Override
    public void close() {
        flush();
    }
}
