package com.snoworca.naruserve.servlet;

import com.snoworca.naruserve.RestApp;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

/** Shuts the app down when the container destroys the web application; {@link NaruServeInitializer} adds it. */
// @req FR-SVL-001
final class ShutdownListener implements ServletContextListener {

    private final RestApp app;
    private final int seconds;
    private final AtomicBoolean destroyed = new AtomicBoolean();

    // @req FR-SVL-001
    ShutdownListener(RestApp app, int seconds) {
        this.app = app;
        this.seconds = seconds;
    }

    /** Nothing: the initializer already started the app. Servlet 3.1 gives this method no default body. */
    // @req FR-SVL-001
    @Override
    public void contextInitialized(ServletContextEvent e) {
    }

    /** The first call only runs {@code app.shutdown(seconds, SECONDS)}; a failure goes to the container log. */
    // @req FR-SVL-001
    @Override
    public void contextDestroyed(ServletContextEvent e) {
        if (!destroyed.compareAndSet(false, true)) {
            return;
        }
        try {
            app.shutdown(seconds, TimeUnit.SECONDS);
        } catch (Throwable t) {
            // The app logger is closed by shutdown, so the container log takes the failure.
            e.getServletContext().log("NaruServe app shutdown failed", t);
        }
    }
}
