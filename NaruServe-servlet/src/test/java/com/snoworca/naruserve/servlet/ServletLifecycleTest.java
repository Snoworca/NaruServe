package com.snoworca.naruserve.servlet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.AppState;
import com.snoworca.naruserve.ConsoleLogger;
import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.ServerHandle;
import com.snoworca.naruserve.testing.RecordingLogger;
import java.lang.reflect.Field;
import java.util.EventListener;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.servlet.ServletContextEvent;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.webapp.WebAppContext;
import org.jspecify.annotations.Nullable;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/** Context startup and destruction drive the app through listen(0) and shutdown. */
public class ServletLifecycleTest {

    @Rule
    public final ContainerRule container = new ContainerRule();

    static final AtomicInteger STARTS = new AtomicInteger();
    static final AtomicInteger STOPS = new AtomicInteger();
    static volatile @Nullable RestApp app;
    static volatile @Nullable ServletAdapter adapter;

    /** onStart and onStop counters and GET /ping; the codec is the preset GsonJsonCodec. */
    public static class Counting implements ServletConfigurer {
        @Override
        public RestApp configure(RestApp.Builder builder) throws Exception {
            Field f = RestApp.Builder.class.getDeclaredField("adapter");
            f.setAccessible(true);
            adapter = (ServletAdapter) f.get(builder);
            RestApp a = customize(builder).build();
            a.onStart(ctx -> STARTS.incrementAndGet());
            a.onStop(ctx -> STOPS.incrementAndGet());
            a.get("/ping", (req, res) -> res.send("pong"));
            app = a;
            return a;
        }

        RestApp.Builder customize(RestApp.Builder builder) {
            return builder;
        }
    }

    /** As {@link Counting}, with a RecordingLogger set through Builder.logger. */
    public static final class Logging extends Counting {
        static final RecordingLogger LOG = new RecordingLogger();

        @Override
        RestApp.Builder customize(RestApp.Builder builder) {
            return builder.logger(LOG);
        }
    }

    @Before
    public void reset() {
        STARTS.set(0);
        STOPS.set(0);
        app = null;
        adapter = null;
    }

    private static Map<String, String> params(Class<?> configurer, String... keyValues) {
        Map<String, String> m = new HashMap<>(ServletConfigurerTest.configurer(configurer));
        for (int i = 0; i < keyValues.length; i += 2) {
            m.put(keyValues[i], keyValues[i + 1]);
        }
        return m;
    }

    private static RestApp started() {
        RestApp a = app;
        assertNotNull("configure ran", a);
        return a;
    }

    private static ShutdownListener listener(WebAppContext ctx) {
        for (EventListener l : ctx.getEventListeners()) {
            if (l instanceof ShutdownListener) {
                return (ShutdownListener) l;
            }
        }
        throw new AssertionError("no ShutdownListener on the context");
    }

    private static int seconds(ShutdownListener l) throws Exception {
        Field f = ShutdownListener.class.getDeclaredField("seconds");
        f.setAccessible(true);
        return f.getInt(l);
    }

    // SDS-AC-1; FR-SVL-001 AC-1
    @Test
    public void sdsAc1_FR_SVL_001_AC1_startupListensBeforeTheFirstRequest() throws Exception {
        container.webApp(params(Counting.class));
        RestApp a = started();
        assertEquals("onStart ran once", 1, STARTS.get());
        assertEquals(AppState.RUNNING, a.state());
        SharedApp.Reply r = SharedApp.http(container.port(), "GET", "/ping", null);
        assertEquals(200, r.status);
        assertEquals("pong", r.text());
        assertEquals(1, STARTS.get());
    }

    // SDS-AC-2; FR-SVL-001 AC-2
    @Test
    public void sdsAc2_FR_SVL_001_AC2_destructionShutsTheAppDown() throws Exception {
        WebAppContext ctx = container.webApp(params(Counting.class, "naruserve.shutdownTimeoutSeconds", "1"));
        RestApp a = started();
        ServletAdapter ad = adapter;
        assertNotNull(ad);
        assertEquals(1, seconds(listener(ctx)));
        long t0 = System.nanoTime();
        ctx.stop();
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        assertEquals("onStop ran once", 1, STOPS.get());
        assertEquals(AppState.TERMINATED, a.state());
        assertTrue("adapter stop was called", ad.stopping());
        assertNull("adapter release detached the handler", ad.handler());
        // docs/api/lifecycle.md: shutdown returns within the timeout plus 3 s
        assertTrue(millis + " ms", millis < 1000 + 3000);
    }

    // SDS-AC-2; FR-SVL-001 AC-2
    @Test
    public void sdsAc2_FR_SVL_001_AC2_defaultTimeoutIsTenSeconds() throws Exception {
        WebAppContext ctx = container.webApp(params(Counting.class));
        assertEquals(10, seconds(listener(ctx)));
    }

    // SDS-AC-2; FR-SVL-001 AC-2
    @Test
    public void sdsAc2_FR_SVL_001_AC2_secondDestructionIsHarmless() throws Exception {
        RestApp a = RestApp.builder().httpAdapter(new ServletAdapter()).jsonCodec(SharedApp.CODEC).build();
        a.onStop(ctx -> STOPS.incrementAndGet());
        a.listen(0);
        ServletContextHandler handler = new ServletContextHandler();
        ServletContextEvent event = new ServletContextEvent(handler.getServletContext());
        ShutdownListener l = new ShutdownListener(a, 1);
        l.contextDestroyed(event);
        l.contextDestroyed(event);
        assertEquals(1, STOPS.get());
        assertEquals(AppState.TERMINATED, a.state());
    }

    // SDS-AC-4; FR-SVL-001 AC-4
    @Test
    public void sdsAc4_FR_SVL_001_AC4_userListen8080BindsNothing() {
        RestApp a = RestApp.builder().httpAdapter(new ServletAdapter()).jsonCodec(SharedApp.CODEC).build();
        a.onStart(ctx -> STARTS.incrementAndGet());
        try {
            ServerHandle handle = a.listen(8080);
            assertEquals(0, handle.port());
            assertEquals(AppState.RUNNING, a.state());
            assertEquals(1, STARTS.get());
        } finally {
            a.shutdown(5, TimeUnit.SECONDS);
        }
    }

    // SDS-AC-6; FR-SVL-001 AC-6
    @Test
    public void sdsAc6_FR_SVL_001_AC6_defaultLoggerIsConsoleLogger() throws Exception {
        container.webApp(params(Counting.class));
        assertSame(ConsoleLogger.class, started().logger().getClass());
    }

    // SDS-AC-6; FR-SVL-001 AC-6
    @Test
    public void sdsAc6_FR_SVL_001_AC6_configuredLoggerIsKept() throws Exception {
        container.webApp(params(Logging.class));
        assertSame(Logging.LOG, started().logger());
    }
}
