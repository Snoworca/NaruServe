package com.snoworca.naruserve.bootstrap;

import com.snoworca.naruserve.ConsoleLogger;
import com.snoworca.naruserve.Logger;
import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.gson.GsonJsonCodec;
import com.snoworca.naruserve.logcat.LogcatLogger;
import com.snoworca.naruserve.spi.AppFactory;
import com.snoworca.naruserve.tinyhttpd.TinyHttpdAdapter;
import org.jspecify.annotations.Nullable;

/**
 * The {@link AppFactory} behind {@code RestApp.create()}: a new {@link TinyHttpdAdapter}, a {@link GsonJsonCodec}
 * with {@link GsonJsonCodec#defaultGson()} and the Logcat logger on Android or the console logger elsewhere.
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

    /** {@link LogcatLogger} when {@code android.util.Log} is visible to {@code loader}, else {@link ConsoleLogger}. */
    // @req FR-ENGINE-009
    static Logger defaultLogger(@Nullable ClassLoader loader) {
        try {
            Class.forName("android.util.Log", false, loader);
        } catch (ClassNotFoundException absent) {
            return new ConsoleLogger();
        }
        return new LogcatLogger();
    }
}
