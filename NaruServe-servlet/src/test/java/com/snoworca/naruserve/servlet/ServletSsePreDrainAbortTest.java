package com.snoworca.naruserve.servlet;

import static org.junit.Assert.*;
import com.snoworca.naruserve.SseClient;
import java.io.IOException;
import java.lang.reflect.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.servlet.*;
import javax.servlet.http.*;
import org.junit.Test;

/** Real Jetty requests with deterministic failure between core handler completion and drain submission. */
public class ServletSsePreDrainAbortTest extends SseJettyTest {
    // FR-SVL-005 AC-5; FR-SSE-004 AC-2; FR-APP-019 AC-10
    @Test public void abortBeforeDrainCleansCoreAndAdapterExactlyOnce() throws Exception { verify("abort"); }
    @Test public void asyncErrorBeforeDrainCleansCoreAndAdapterExactlyOnce() throws Exception { verify("error"); }
    @Test public void asyncTimeoutBeforeDrainCleansCoreAndAdapterExactlyOnce() throws Exception { verify("timeout"); }
    @Test public void rejectedDrainSubmissionCleansCoreAndAdapterExactlyOnce() throws Exception { verify("reject"); }

    private void verify(String failure) throws Exception {
        ServletAdapter adapter = new ServletAdapter();
        AtomicInteger closed = new AtomicInteger(), completed = new AtomicInteger();
        AtomicReference<SseClient> client = new AtomicReference<>();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();
        CountDownLatch handled = new CountDownLatch(1);
        app = SharedApp.listen(adapter, log, a -> {
            a.server(o -> o.maxSseStreams(1));
            a.get("/events", (req, res) -> {
                SseClient c = res.sse();
                client.set(c);
                c.onClose(closed::incrementAndGet);
            });
        });
        container.servlet(new HttpServlet() {
            @Override protected void service(HttpServletRequest req, HttpServletResponse res) throws IOException {
                AtomicReference<AsyncListener> listener = new AtomicReference<>();
                AtomicReference<AsyncContext> context = new AtomicReference<>();
                HttpServletRequest wrapped = new HttpServletRequestWrapper(req) {
                    @Override public AsyncContext startAsync() {
                        AsyncContext real = super.startAsync();
                        context.set(real);
                        return (AsyncContext) Proxy.newProxyInstance(AsyncContext.class.getClassLoader(),
                                new Class<?>[] {AsyncContext.class}, (p, method, args) -> {
                                    if (method.getName().equals("addListener")) listener.set((AsyncListener) args[0]);
                                    if (method.getName().equals("complete")) completed.incrementAndGet();
                                    try { return method.invoke(real, args); }
                                    catch (InvocationTargetException e) { throw e.getCause(); }
                                });
                    }
                };
                ServletExchange ex = new ServletExchange(wrapped, res, "/events", adapter);
                try {
                    adapter.handler().handle(ex);
                    if (failure.equals("error")) listener.get().onError(new AsyncEvent(context.get()));
                    else if (failure.equals("timeout")) listener.get().onTimeout(new AsyncEvent(context.get()));
                    else if (failure.equals("reject")) adapter.pool().shutdown();
                    else ex.abort();
                    ex.afterHandler();
                    ex.afterHandler(); // no repeated slot release or core callbacks
                } catch (Throwable error) {
                    unexpected.set(error);
                } finally {
                    handled.countDown();
                }
            }
        }, "/*");
        probes.add(SseProbe.open(container.port(), "/events"));
        assertTrue("handler did not return", handled.await(5, TimeUnit.SECONDS));
        if (unexpected.get() != null) throw new AssertionError(unexpected.get());
        assertFalse("aborted core client still open: " + failure, client.get().isOpen());
        assertEquals("core onClose", 1, closed.get());
        assertEquals("AsyncContext complete", 1, completed.get());
        assertEquals("adapter slots", 0, adapter.pool().open());
        Field registryField = app.getClass().getDeclaredField("sseRegistry");
        registryField.setAccessible(true);
        Object registry = registryField.get(app);
        Method size = registry.getClass().getDeclaredMethod("size");
        size.setAccessible(true);
        assertEquals("core registry", 0, size.invoke(registry));
        long lines = log.info().stream().filter(line -> line.contains(" sse start=")).count();
        assertEquals("one stream-end access line", 1, lines);
        assertTrue(log.info().toString(), log.info().stream().anyMatch(line -> line.contains("cause=client")));
    }
}
