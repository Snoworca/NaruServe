package com.snoworca.naruserve.servlet;

import static org.junit.Assert.assertNotNull;

import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.SseClient;
import com.snoworca.naruserve.testing.RecordingLogger;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.After;
import org.junit.Rule;

/** The scaffold of the SSE tests on Jetty: the container, the probes, the streams the app opened and the stop. */
abstract class SseJettyTest {

    @Rule
    public final ContainerRule container = new ContainerRule();

    final RecordingLogger log = new RecordingLogger();
    final List<SseProbe> probes = new ArrayList<>();
    final BlockingQueue<SseClient> opened = new LinkedBlockingQueue<>();
    RestApp app;

    @After
    public void stop() throws IOException {
        for (SseProbe p : probes) {
            p.close();
        }
        if (app != null) {
            app.shutdown(2, TimeUnit.SECONDS);
        }
    }

    /** Starts {@code routes} on a new ServletAdapter behind the container at {@code /*} and keeps the app for {@link #stop}. */
    RestApp jetty(Consumer<RestApp> routes) throws Exception {
        app = SharedApp.onJetty(container, new ServletAdapter(), "/*", log, routes);
        return app;
    }

    /** The next stream the app opened; fails after 10 s. */
    SseClient next() throws InterruptedException {
        SseClient c = opened.poll(10, TimeUnit.SECONDS);
        assertNotNull("no stream opened", c);
        return c;
    }
}
