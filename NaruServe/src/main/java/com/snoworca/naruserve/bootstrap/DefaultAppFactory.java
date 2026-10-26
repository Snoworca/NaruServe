package com.snoworca.naruserve.bootstrap;

import com.snoworca.naruserve.ConsoleLogger;
import com.snoworca.naruserve.Logger;
import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.gson.GsonJsonCodec;
import com.snoworca.naruserve.spi.AppFactory;
import com.snoworca.naruserve.tinyhttpd.TinyHttpdAdapter;
import org.jspecify.annotations.Nullable;

/**
 * The {@link AppFactory} behind {@code RestApp.create()}: a new {@link TinyHttpdAdapter}, a {@link GsonJsonCodec}
 * with {@link GsonJsonCodec#defaultGson()} and the Logcat logger when NaruServe-logcat runs on Android (as
 * NaruServe-android brings it) or the console logger elsewhere.
 */
// @req FR-ENGINE-009
public final class DefaultAppFactory implements AppFactory {

    /** Public no-argument constructor, for {@link java.util.ServiceLoader}. */
    // @req FR-ENGINE-009
    public DefaultAppFactory() {
    }

    // @req FR-ENGINE-009
    @Override
    public RestApp create() {
        return RestApp.builder().httpAdapter(new TinyHttpdAdapter()).jsonCodec(new GsonJsonCodec())
                .logger(defaultLogger(DefaultAppFactory.class.getClassLoader())).build();
    }

    /** The Logcat logger, created by name: this module does not depend on NaruServe-logcat (FR-ENGINE-009 AC-6). */
    private static final String LOGCAT_LOGGER = "com.snoworca.naruserve.logcat.LogcatLogger";

    /**
     * The Logcat logger when both {@code android.util.Log} and NaruServe-logcat are visible to {@code loader}, else
     * {@link ConsoleLogger}.
     */
    // @req FR-ENGINE-009
    static Logger defaultLogger(@Nullable ClassLoader loader) {
        Class<?> logcat;
        try {
            Class.forName("android.util.Log", false, loader);
            logcat = Class.forName(LOGCAT_LOGGER, true, loader);
        } catch (ClassNotFoundException absent) {
            return new ConsoleLogger();
        }
        try {
            return (Logger) logcat.getConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot create " + LOGCAT_LOGGER, e);
        }
    }
}
