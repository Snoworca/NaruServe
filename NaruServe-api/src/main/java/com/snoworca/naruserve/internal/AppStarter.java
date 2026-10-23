package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.AppState;
import com.snoworca.naruserve.Configurer;
import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.ServerHandle;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;

/**
 * The body of {@link RestApp#start(int, Configurer)}; public only because RestApp lives in another package. It
 * creates the app, runs the configuration lambda, calls {@code listen}, writes the start line and, on a JVM,
 * registers the shutdown hook; it changes no rule of {@code listen}.
 */
// @req FR-DX-012
// @req FR-DX-014
public final class AppStarter {

    /** Where the JVM shutdown hook is registered; the default is {@link Runtime}, tests use a recording one. */
    // @req FR-DX-014
    public interface Hooks {

        void add(Thread hook);

        /** Removes the hook; must be harmless when it is not registered. */
        void remove(Thread hook);
    }

    private static final long GRACE_SECONDS = 5;

    private static final Hooks RUNTIME = new Hooks() {
        @Override
        public void add(Thread hook) {
            Runtime.getRuntime().addShutdownHook(hook);
        }

        @Override
        public void remove(Thread hook) {
            Runtime.getRuntime().removeShutdownHook(hook);
        }
    };

    private AppStarter() {
    }

    /** {@code RestApp.create()}, then {@link #start(RestApp, int, Configurer, ClassLoader, Hooks)}. */
    // @req FR-DX-012
    public static ServerHandle start(int port, Configurer<RestApp> routes) {
        Objects.requireNonNull(routes, "routes");
        ClassLoader loader = RestApp.class.getClassLoader();
        return start(AppFactoryLoader.create(loader), port, routes, loader, RUNTIME);
    }

    /**
     * Runs {@code routes} on {@code app} (state {@code NEW}), calls {@code listen(port)}, logs the start line and
     * registers the hook unless {@code detect} sees {@code android.util.Log}. A failure of the lambda or of
     * {@code listen} ends the app with {@code shutdown} and is rethrown as it is.
     */
    // @req FR-DX-012
    // @req FR-DX-014
    public static ServerHandle start(RestApp app, int port, Configurer<RestApp> routes, @Nullable ClassLoader detect,
            Hooks hooks) {
        boolean jvm = !isAndroid(detect);
        AtomicReference<Thread> hook = new AtomicReference<>();
        ServerHandle handle;
        try {
            routes.configure(app);
            if (jvm) {
                app.onStop(ctx -> removeQuietly(hooks, hook.get()));
            }
            handle = app.listen(port);
        } catch (Throwable e) {
            endQuietly(app, e);
            throw e;
        }
        log(app, handle, jvm);
        if (jvm) {
            Thread t = new Thread(() -> stopFromHook(app), "naruserve-shutdown-hook");
            hook.set(t);
            try {
                hooks.add(t);
            } catch (Throwable e) {
                // the JVM is already shutting down or the hook is refused: no handle is returned, so end the app
                endQuietly(app, e);
                throw e;
            }
            // The app may have ended between listen and here, so its onStop could not see the hook yet.
            if (app.state() != AppState.RUNNING) {
                removeQuietly(hooks, t);
            }
        }
        return handle;
    }

    /** True when {@code android.util.Log} is visible to {@code loader}; no {@code android.*} class is referenced. */
    // @req FR-DX-014
    public static boolean isAndroid(@Nullable ClassLoader loader) {
        try {
            Class.forName("android.util.Log", false, loader);
            return true;
        } catch (ClassNotFoundException | LinkageError absent) {
            return false;
        }
    }

    private static void stopFromHook(RestApp app) {
        if (app.state() == AppState.TERMINATED) {
            return;
        }
        try {
            app.logger().info("Stopping (grace " + GRACE_SECONDS + "s)");
        } catch (Throwable ignored) {
            // a failing logger must not keep the app from stopping
        }
        app.shutdown(GRACE_SECONDS, TimeUnit.SECONDS);
    }

    private static void removeQuietly(Hooks hooks, @Nullable Thread hook) {
        if (hook == null) {
            return;
        }
        try {
            hooks.remove(hook);
        } catch (IllegalStateException jvmShuttingDown) {
            // Runtime.removeShutdownHook refuses once the JVM is already shutting down
        }
    }

    private static void endQuietly(RestApp app, Throwable cause) {
        try {
            app.shutdown(0, TimeUnit.SECONDS);
        } catch (Throwable e) {
            cause.addSuppressed(e);
        }
    }

    private static void log(RestApp app, ServerHandle handle, boolean jvm) {
        String address = handle.address();
        int port = handle.port();
        StringBuilder line = new StringBuilder("Listening on ");
        if (ServerUrls.isWildcard(address)) {
            line.append("http://").append(ServerUrls.bracketed(address)).append(':').append(port)
                    .append(" - open http://localhost:").append(port).append(" or http://<this-device-ip>:")
                    .append(port);
        } else {
            line.append(handle.url());
            if (DefaultRestApp.isLoopback(address)) {
                line.append(" (this device only)");
            }
        }
        if (jvm) {
            line.append(". Press Ctrl+C to stop.");
        }
        try {
            app.logger().info(line.toString());
        } catch (Throwable ignored) {
            // the app is running; a failing logger must not turn that into a start failure
        }
    }
}
