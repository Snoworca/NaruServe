package com.snoworca.naruserve.servlet;

import com.snoworca.naruserve.RestApp;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.servlet.ServletContainerInitializer;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRegistration;
import org.jspecify.annotations.Nullable;

/**
 * Container entry, found through {@code META-INF/services/javax.servlet.ServletContainerInitializer}. It does
 * nothing unless the context init-parameter {@code naruserve.configurer} is set, because the jar may sit in an
 * application that does not use NaruServe; no {@code @HandlesTypes}, so no class path scan runs.
 */
// @req IR-SVL-001
public class NaruServeInitializer implements ServletContainerInitializer {

    // @req IR-SVL-001
    public NaruServeInitializer() {
    }

    /** Init-parameters, the app, {@code listen(0)} before any request, the destroy listener, then the servlet. */
    // @req IR-SVL-001
    // @req FR-SVL-001
    @Override
    public void onStartup(@Nullable Set<Class<?>> classes, ServletContext ctx) throws ServletException {
        String configurer = ctx.getInitParameter(InitParams.CONFIGURER);
        if (configurer == null) {
            return;
        }
        String pattern = InitParams.urlPattern(ctx);
        int seconds = InitParams.shutdownSeconds(ctx);
        ServletAdapter adapter = new ServletAdapter();
        RestApp app = AppStarter.create(ctx, configurer, adapter);
        AppStarter.start(app);
        try {
            register(ctx, app, seconds, adapter, pattern);
        } catch (ServletException | RuntimeException e) {
            // The listener only fires for a context that started, so a failed registration stops the app here.
            app.shutdown(seconds, TimeUnit.SECONDS);
            throw e;
        }
    }

    // @req FR-SVL-001
    private static void register(ServletContext ctx, RestApp app, int seconds, ServletAdapter adapter,
            String pattern) throws ServletException {
        ctx.addListener(new ShutdownListener(app, seconds));
        ServletRegistration.Dynamic reg = ctx.addServlet(NaruServeServlet.NAME,
                new NaruServeServlet(adapter, InitParams.prefix(pattern)));
        if (reg == null) {
            throw new ServletException("a servlet named '" + NaruServeServlet.NAME + "' is already registered");
        }
        reg.setAsyncSupported(true);
        reg.addMapping(pattern);
    }
}
