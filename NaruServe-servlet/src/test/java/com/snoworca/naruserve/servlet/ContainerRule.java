package com.snoworca.naruserve.servlet;

import com.snoworca.naruserve.testing.LiveThreads;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javax.servlet.DispatcherType;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServlet;

import org.eclipse.jetty.annotations.AnnotationConfiguration;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.ThreadPool;
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
 * rethrown after the server is stopped. After the test it stops Jetty and asserts that nothing of the app is left:
 * no SSE thread, no Jetty server thread and no upload file in {@link #uploads}.
 */
// @req FR-SVL-006
class ContainerRule extends ExternalResource {

    /** How long a thread or file may take to go away after shutdown. */
    private static final long SETTLE_MILLIS = 2000;

    private final List<Filter> filters = new ArrayList<>();
    /** The thread-name prefixes of every Jetty thread pool this rule started. */
    private final List<String> jettyPools = new ArrayList<>();
    private @Nullable Server server;
    private @Nullable File base;
    private @Nullable File uploads;

    /**
     * A web application whose initializers come from the services files on the class path (annotation
     * configuration), with the given context init-parameters; no test code names the initializer.
     */
    WebAppContext webApp(Map<String, String> initParams) throws Exception {
        return webApp(initParams, null);
    }

    /** As {@link #webApp(Map)}, with {@code before} applied to the context before it starts. */
    WebAppContext webApp(Map<String, String> initParams, @Nullable Consumer<WebAppContext> before) throws Exception {
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
        if (before != null) {
            before.accept(ctx);
        }
        start(ctx);
        return ctx;
    }

    /** A plain servlet context serving {@code s} at {@code pattern}, without any initializer. */
    ServletContextHandler servlet(HttpServlet s, String pattern) throws Exception {
        return servlet(s, pattern, "/");
    }

    /** As {@link #servlet(HttpServlet, String)}, with the context path {@code contextPath}. */
    ServletContextHandler servlet(HttpServlet s, String pattern, String contextPath) throws Exception {
        ServletContextHandler ctx = new ServletContextHandler();
        ctx.setContextPath(contextPath);
        ctx.addServlet(new ServletHolder(s), pattern);
        start(ctx);
        return ctx;
    }

    /** A filter's {@code doFilter}; Servlet 3.1 gives {@link Filter} no default init and destroy, so they live once below. */
    @FunctionalInterface
    interface Body {
        void filter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException;
    }

    /** Installs {@code body} at {@code /*} in front of the context the next {@link #webApp} or {@link #servlet} starts. */
    void filter(Body body) {
        filters.add(new Filter() {
            @Override
            public void init(FilterConfig config) {
            }

            @Override
            public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
                    throws IOException, ServletException {
                body.filter(req, res, chain);
            }

            @Override
            public void destroy() {
            }
        });
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
        ThreadPool tp = s.getThreadPool();
        if (tp instanceof QueuedThreadPool) {
            jettyPools.add(((QueuedThreadPool) tp).getName() + "-");
        }
        server = s;
        try {
            s.start();
        } catch (Exception e) {
            stopServer();
            throw e;
        }
    }

    /** The upload directory of the app under test: pass it to {@code app.multipart(o -> o.tempDirectory(...))}. */
    File uploads() throws IOException {
        File u = uploads;
        if (u == null) {
            u = Files.createTempDirectory("naruserve-uploads").toFile();
            uploads = u;
        }
        return u;
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

    // @req FR-SVL-006
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
        try {
            SharedApp.until(() -> leftovers().isEmpty(), SETTLE_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        List<String> left = leftovers();
        File u = uploads;
        if (u != null && left.isEmpty()) {
            u.delete();
        }
        if (!left.isEmpty()) {
            throw new AssertionError("left " + SETTLE_MILLIS + " ms after shutdown: " + left);
        }
    }

    /** Live SSE and Jetty threads and upload files of this rule's app. */
    private List<String> leftovers() {
        List<String> out = new ArrayList<>();
        for (Thread t : LiveThreads.withPrefix("naruserve-servlet-sse-")) {
            out.add("thread " + t.getName());
        }
        for (String pool : jettyPools) {
            for (Thread t : LiveThreads.withPrefix(pool)) {
                out.add("Jetty thread " + t.getName());
            }
        }
        File u = uploads;
        String[] files = u != null ? u.list() : null;
        if (files != null) {
            for (String f : files) {
                out.add("upload file " + f);
            }
        }
        return out;
    }
}
