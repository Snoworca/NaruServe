package com.snoworca.naruserve.servlet;

import com.snoworca.naruserve.RestApp;
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
     * no-argument constructor and runs it on a builder holding {@code adapter}; every failure names the class.
     */
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
        RestApp app;
        try {
            app = configurer.configure(RestApp.builder().httpAdapter(adapter));
        } catch (Exception e) {
            throw failure(where + ": configure threw " + e, e);
        }
        if (app == null) {
            throw new ServletException(where + ": configure returned null");
        }
        return app;
    }

    private static ServletException failure(String message, Throwable cause) {
        return new ServletException(message, cause);
    }
}
