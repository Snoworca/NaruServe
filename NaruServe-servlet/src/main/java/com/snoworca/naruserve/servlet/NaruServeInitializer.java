package com.snoworca.naruserve.servlet;

import java.util.Set;
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

    // @req IR-SVL-001
    @Override
    public void onStartup(@Nullable Set<Class<?>> classes, ServletContext ctx) throws ServletException {
        String configurer = ctx.getInitParameter(InitParams.CONFIGURER);
        if (configurer == null) {
            return;
        }
        String pattern = InitParams.urlPattern(ctx);
        InitParams.shutdownSeconds(ctx);
        ServletAdapter adapter = new ServletAdapter();
        AppStarter.create(ctx, configurer, adapter);
        ServletRegistration.Dynamic reg = ctx.addServlet(NaruServeServlet.NAME,
                new NaruServeServlet(adapter, InitParams.prefix(pattern)));
        if (reg == null) {
            throw new ServletException("a servlet named '" + NaruServeServlet.NAME + "' is already registered");
        }
        reg.setAsyncSupported(true);
        reg.addMapping(pattern);
    }
}
