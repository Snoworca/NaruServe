package com.snoworca.naruserve.servlet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.ServerHandle;
import com.snoworca.naruserve.spi.ExchangeHandler;
import com.snoworca.naruserve.spi.ServerSettings;
import com.snoworca.naruserve.testing.LiveThreads;
import com.snoworca.naruserve.testing.TestSupport;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/** The adapter alone: the bind contract without a container. */
public class ServletAdapterTest {

    private static final ExchangeHandler HANDLER = exchange -> {
    };

    private static ServerSettings settings(int port) {
        return TestSupport.settings().port(port).bindAddress("127.0.0.1").build();
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    /** True when this process can still open a listening socket on {@code port}, so nothing else holds it. */
    private static boolean portFree(int port) {
        try (ServerSocket s = new ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"))) {
            return s.getLocalPort() == port;
        } catch (IOException e) {
            return false;
        }
    }

    private interface Bind {
        void run(ServletAdapter adapter, int port) throws IOException;
    }

    /**
     * Binds a fresh adapter on a free port and asserts that it starts no thread, reports port 0 and opens no
     * socket. Another process or test may take the port between {@code freePort} and {@code portFree}, which looks
     * like an adapter socket, so a lost race is retried on a new port; three losses in a row fail the test.
     */
    private static void assertBindOpensNoSocket(Bind bind) throws IOException {
        int port = 0;
        for (int attempt = 0; attempt < 3; attempt++) {
            ServletAdapter adapter = new ServletAdapter();
            assertNotBound(adapter);
            port = freePort();
            Set<Thread> before = threads();
            bind.run(adapter, port);
            Set<Thread> started = threads();
            started.removeAll(before);
            assertTrue("no thread started: " + started, started.isEmpty());
            assertEquals(0, adapter.boundPort());
            if (portFree(port)) {
                return;
            }
        }
        fail("no socket on " + port + " expected, but it was held in 3 attempts");
    }

    /** The app-named threads only, so container threads of earlier tests cannot interfere. */
    private static Set<Thread> threads() {
        return new HashSet<>(LiveThreads.withPrefix("naruserve-"));
    }

    private static void assertNotBound(ServletAdapter adapter) {
        try {
            adapter.boundPort();
            fail("boundPort before bind");
        } catch (IllegalStateException expected) {
            // the HttpAdapter.boundPort contract
        }
    }

    // SDS-AC-3; FR-SVL-001 AC-3, CON-SVL-006 AC-1
    @Test
    public void sdsAc3_FR_SVL_001_AC3_hostPortBindStoresTheHandlerOnly() throws IOException {
        assertBindOpensNoSocket((adapter, port) -> adapter.bind("127.0.0.1", port, HANDLER));
    }

    // SDS-AC-3; FR-SVL-001 AC-3, CON-SVL-006 AC-1
    @Test
    public void sdsAc3_FR_SVL_001_AC3_settingsBindStoresTheHandlerOnly() throws IOException {
        assertBindOpensNoSocket((adapter, port) -> adapter.bind(settings(port), HANDLER));
    }

    // SDS-AC-3; FR-SVL-001 AC-3
    @Test
    public void sdsAc3_FR_SVL_001_AC3_secondBindIsRefused() {
        ServletAdapter adapter = new ServletAdapter();
        adapter.bind(settings(0), HANDLER);
        try {
            adapter.bind(settings(0), HANDLER);
            fail("second bind");
        } catch (IllegalStateException expected) {
            // one adapter serves one app
        }
    }

    // SDS-AC-3; FR-SVL-001 AC-3
    @Test
    public void sdsAc3_FR_SVL_001_AC3_serverHandlePortIsZero() {
        RestApp app = RestApp.builder().httpAdapter(new ServletAdapter()).jsonCodec(SharedApp.CODEC).build();
        try {
            ServerHandle handle = app.listen(0);
            assertEquals(0, handle.port());
        } finally {
            app.shutdown(5, TimeUnit.SECONDS);
        }
    }
}
