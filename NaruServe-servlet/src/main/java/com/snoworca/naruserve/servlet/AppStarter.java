package com.snoworca.naruserve.servlet;

import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.spi.JsonCodec;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;

/** Builds the app of one web application from the class named by {@code naruserve.configurer}. */
// @req IR-SVL-001
final class AppStarter {

    private AppStarter() {
    }

    /** Builds the app over a new {@link ServletAdapter}. */
    // @req IR-SVL-001
    static RestApp create(ServletContext ctx, String className) throws ServletException {
        return create(ctx, className, new ServletAdapter());
    }

    /**
     * Loads {@code className} through the web application's class loader, instantiates it through its public
     * no-argument constructor and runs it on a builder holding {@code adapter} and, when found, the preset
     * GsonJsonCodec; every failure names the class, and a build without a codec also names GsonJsonCodec.
     */
    // @req FR-SVL-001
    // @req IR-SVL-001
    static RestApp create(ServletContext ctx, String className, ServletAdapter adapter) throws ServletException {
        String where = InitParams.CONFIGURER + " " + className;
        Class<?> c;
        try {
            c = Class.forName(className, true, ctx.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw failure(where + ": class not found", e);
        } catch (LinkageError e) {
            throw failure(where + ": class cannot be loaded or initialised: " + e, e);
        }
        if (!ServletConfigurer.class.isAssignableFrom(c)) {
            throw new ServletException(where + ": does not implement " + ServletConfigurer.class.getName());
        }
        ServletConfigurer configurer;
        try {
            Constructor<?> constructor = c.getConstructor();
            if (!Modifier.isPublic(c.getModifiers())) {
                throw new ServletException(where + ": class is not public");
            }
            configurer = (ServletConfigurer) constructor.newInstance();
        } catch (NoSuchMethodException e) {
            throw failure(where + ": no public no-argument constructor", e);
        } catch (InvocationTargetException e) {
            throw failure(where + ": constructor threw", e.getCause() != null ? e.getCause() : e);
        } catch (InstantiationException | IllegalAccessException e) {
            throw failure(where + ": cannot be instantiated", e);
        }
        RestApp.Builder builder = RestApp.builder().httpAdapter(adapter);
        boolean preset = presetCodec(ctx, builder);
        RestApp app;
        try {
            app = configurer.configure(builder);
        } catch (IllegalStateException e) {
            boolean noCodec = !preset && String.valueOf(e.getMessage()).contains("jsonCodec");
            throw failure(where + ": configure threw " + e + (noCodec ? NO_CODEC : ""), e);
        } catch (Exception e) {
            throw failure(where + ": configure threw " + e, e);
        }
        if (app == null) {
            throw new ServletException(where + ": configure returned null");
        }
        return app;
    }

    static final String GSON_CODEC = "com.snoworca.naruserve.gson.GsonJsonCodec";

    private static final String NO_CODEC = "; no JSON codec: put " + GSON_CODEC
            + " (NaruServe-gson) on the web application's class path or call Builder.jsonCodec(...)";

    /**
     * Sets {@code GsonJsonCodec} on {@code b} when the web application's class loader finds it, through its public
     * no-argument constructor; found by reflection so this module's runtime class path stays NaruServe-api only.
     * False when it is absent or unusable, and the configurer may still set a codec.
     */
    // @req FR-SVL-001
    static boolean presetCodec(ServletContext ctx, RestApp.Builder b) {
        try {
            Class<?> c = Class.forName(GSON_CODEC, true, ctx.getClassLoader());
            b.jsonCodec((JsonCodec) c.getConstructor().newInstance());
            return true;
        } catch (ReflectiveOperationException | LinkageError | ClassCastException e) {
            return false;
        }
    }

    /** {@code app.listen(0)}; a start failure, an {@code AppStartException} included, becomes its cause. */
    // @req FR-SVL-001
    static void start(RestApp app) throws ServletException {
        try {
            app.listen(0);
        } catch (RuntimeException e) {
            throw failure("NaruServe app start failed: " + e, e);
        }
    }

    private static ServletException failure(String message, Throwable cause) {
        return new ServletException(message, cause);
    }
}
