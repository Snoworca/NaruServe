package com.snoworca.naruserve.logcat;

import com.snoworca.naruserve.Logger;
import java.lang.reflect.Method;
import java.util.Objects;

/**
 * {@link Logger} over {@code android.util.Log}: trace, debug, info, warn and error go to {@code Log.v}, {@code d},
 * {@code i}, {@code w} and {@code e}. The class is found by reflection, so this module builds without an Android SDK;
 * a failing {@code Log} call is swallowed.
 */
// @req FR-APP-016
public final class LogcatLogger implements Logger {

    private static final String LOG_CLASS = "android.util.Log";

    private final String tag;
    private final Method v;
    private final Method d;
    private final Method i;
    private final Method w;
    private final Method e;
    private final Method eThrowable;

    /** Tag {@code NaruServe}. */
    // @req FR-APP-016
    public LogcatLogger() {
        this("NaruServe");
    }

    /** {@link IllegalStateException} when {@code android.util.Log} is not on the class path. */
    // @req FR-APP-016
    public LogcatLogger(String tag) {
        this.tag = Objects.requireNonNull(tag, "tag");
        Class<?> log;
        try {
            log = Class.forName(LOG_CLASS);
        } catch (ClassNotFoundException ex) {
            throw new IllegalStateException(LOG_CLASS + " not found: LogcatLogger runs only on Android", ex);
        }
        try {
            v = log.getMethod("v", String.class, String.class);
            d = log.getMethod("d", String.class, String.class);
            i = log.getMethod("i", String.class, String.class);
            w = log.getMethod("w", String.class, String.class);
            e = log.getMethod("e", String.class, String.class);
            eThrowable = log.getMethod("e", String.class, String.class, Throwable.class);
        } catch (NoSuchMethodException ex) {
            throw new IllegalStateException(LOG_CLASS + " lacks a logging method", ex);
        }
    }

    @Override
    public void trace(String message) {
        invoke(v, tag, message);
    }

    @Override
    public void debug(String message) {
        invoke(d, tag, message);
    }

    @Override
    public void info(String message) {
        invoke(i, tag, message);
    }

    @Override
    public void warn(String message) {
        invoke(w, tag, message);
    }

    @Override
    public void error(String message) {
        invoke(e, tag, message);
    }

    @Override
    public void error(String message, Throwable error) {
        invoke(eThrowable, tag, message, error);
    }

    // A logger must never fail its caller, so every Throwable of the call stays here.
    // @req FR-APP-016
    private static void invoke(Method m, Object... args) {
        try {
            m.invoke(null, args);
        } catch (Throwable ignored) {
            // swallowed by contract (FR-APP-016 AC-3)
        }
    }
}
