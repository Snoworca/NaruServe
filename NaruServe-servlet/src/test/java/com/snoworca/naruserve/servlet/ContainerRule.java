package com.snoworca.naruserve.servlet;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import javax.servlet.DispatcherType;
import javax.servlet.Filter;
import javax.servlet.http.HttpServlet;

import org.eclipse.jetty.annotations.AnnotationConfiguration;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.webapp.Configuration;
import org.eclipse.jetty.webapp.FragmentConfiguration;
import org.eclipse.jetty.webapp.MetaInfConfiguration;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.webapp.WebInfConfiguration;
import org.eclipse.jetty.webapp.WebXmlConfiguration;
import org.jspecify.annotations.Nullable;
import org.junit.rules.ExternalResource;

/**
 * Embedded Jetty 9.4 on port 0. {@link #filter} queues a filter for the next context; {@link #webApp} and
 * {@link #servlet} each stop any running server, then start one context and return it. A startup failure is
 * rethrown after the server is stopped.
 */
class ContainerRule extends ExternalResource {

    private final List<Filter> filters = new ArrayList<>();
    private @Nullable Server server;
    private @Nullable File base;

    /**
     * A web application whose initializers come from the services files on the class path (annotation
     * configuration), with the given context init-parameters; no test code names the initializer.
     */
    WebAppContext webApp(Map<String, String> initParams) throws Exception {
        WebAppContext ctx = new WebAppContext();
        ctx.setContextPath("/");
        ctx.setResourceBase(baseDir().getAbsolutePath());
        ctx.setDefaultsDescriptor(null);
        ctx.setParentLoaderPriority(true);
        ctx.setThrowUnavailableOnStartupException(true);
        ctx.setConfigurations(new Configuration[] {new WebInfConfiguration(), new WebXmlConfiguration(),
            new MetaInfConfiguration(), new FragmentConfiguration(), new AnnotationConfiguration()});
        for (Map.Entry<String, String> e : initParams.entrySet()) {
            ctx.setInitParameter(e.getKey(), e.getValue());
        }
        start(ctx);
        return ctx;
    }

    /** A plain servlet context serving {@code s} at {@code pattern}, without any initializer. */
    ServletContextHandler servlet(HttpServlet s, String pattern) throws Exception {
        ServletContextHandler ctx = new ServletContextHandler();
        ctx.setContextPath("/");
        ctx.addServlet(new ServletHolder(s), pattern);
        start(ctx);
        return ctx;
    }

    /** Installs {@code f} at {@code /*} in front of the context the next {@link #webApp} or {@link #servlet} starts. */
    void filter(Filter f) {
        filters.add(f);
    }

    int port() {
        Server s = server;
        if (s == null) {
            throw new IllegalStateException("no server started");
        }
        return ((ServerConnector) s.getConnectors()[0]).getLocalPort();
    }

    private void start(ServletContextHandler ctx) throws Exception {
        stopServer();
        for (Filter f : filters) {
            ctx.addFilter(new FilterHolder(f), "/*", EnumSet.of(DispatcherType.REQUEST, DispatcherType.ASYNC));
        }
        filters.clear();
        Server s = new Server(0);
        s.setHandler(ctx);
        server = s;
        try {
            s.start();
        } catch (Exception e) {
            stopServer();
            throw e;
        }
    }

    private File baseDir() throws IOException {
        File b = base;
        if (b == null) {
            b = Files.createTempDirectory("naruserve-webapp").toFile();
            base = b;
        }
        return b;
    }

    private void stopServer() throws Exception {
        Server s = server;
        server = null;
        if (s != null) {
            s.stop();
        }
    }

    @Override
    protected void after() {
        try {
            stopServer();
        } catch (Exception e) {
            throw new AssertionError("Jetty did not stop", e);
        }
        File b = base;
        if (b != null && !b.delete()) {
            b.deleteOnExit();
        }
    }
}
