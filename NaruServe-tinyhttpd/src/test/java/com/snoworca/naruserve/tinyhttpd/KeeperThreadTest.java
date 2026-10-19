package com.snoworca.naruserve.tinyhttpd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.tinyhttpd.RawTargetSocketTest.Http;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/** Keeper and worker threads. SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-5-6.sds.md. */
public class KeeperThreadTest {

    // SDS-AC-10; FR-ENGINE-006 AC-4
    @Test(timeout = 20000)
    public void sdsAc10_FR_ENGINE_006_AC4_oneNonDaemonKeeperWhileTinyHttpdThreadsStayDaemon() throws Exception {
        TinyHttpdAdapter adapter = new TinyHttpdAdapter();
        adapter.bind(Http.settings().build(), Http.ok(ex -> { }));
        int port = adapter.boundPort();
        try {
            assertEquals(200, Http.status(Http.send(port, Http.get("/x"))));
            List<Thread> keepers = ShutdownSocketTest.threads("naruserve-keeper-" + port);
            assertEquals(1, keepers.size());
            assertFalse(keepers.get(0).isDaemon());
            List<Thread> engine = new ArrayList<>(ShutdownSocketTest.threads("tinyhttpd-acceptor-" + port));
            engine.addAll(ShutdownSocketTest.threads("naruserve-" + port + "-worker-"));
            assertTrue(engine.size() >= 2);
            for (Thread t : engine) {
                assertTrue(t.getName(), t.isDaemon());
            }
            adapter.stop(1, TimeUnit.SECONDS);
            // The keeper ends when the stop begins.
            assertEquals(new ArrayList<Thread>(), ShutdownSocketTest.threads("naruserve-keeper-" + port));
        } finally {
            Http.stopQuietly(adapter);
        }
    }

    // SDS-AC-10; FR-ENGINE-006 AC-4
    @Test(timeout = 20000)
    public void sdsAc10_FR_ENGINE_006_AC4_noKeeperAfterAFailedBind() throws Exception {
        try (ServerSocket taken = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
            int port = taken.getLocalPort();
            TinyHttpdAdapter adapter = new TinyHttpdAdapter();
            try {
                adapter.bind(Http.settings().port(port).build(), Http.ok(ex -> { }));
                fail("bind on a taken port must fail");
            } catch (IOException expected) {
                // BindException
            }
            assertEquals(new ArrayList<Thread>(), ShutdownSocketTest.threads("naruserve-keeper-" + port));
        }
    }

    // SDS-AC-12; FR-ENGINE-006 AC-2
    @Test(timeout = 20000)
    public void sdsAc12_FR_ENGINE_006_AC2_workersAreNamedWithTheBoundPortDaemonAndLogUncaught() throws Exception {
        RecordingLogger log = new RecordingLogger();
        TinyHttpdAdapter adapter = new TinyHttpdAdapter();
        AtomicReference<Thread> worker = new AtomicReference<>();
        adapter.bind(Http.settings().port(0).logger(log).build(), Http.ok(ex -> worker.set(Thread.currentThread())));
        int port = adapter.boundPort();
        try {
            assertTrue(port > 0);
            assertEquals(200, Http.status(Http.send(port, Http.get("/x"))));
            assertEquals("naruserve-" + port + "-worker-1", worker.get().getName());
            assertTrue(worker.get().isDaemon());
        } finally {
            Http.stopQuietly(adapter);
        }

        RecordingLogger uncaught = new RecordingLogger();
        WorkerThreadFactory factory = new WorkerThreadFactory(uncaught);
        CountDownLatch ran = new CountDownLatch(1);
        Thread t = factory.newThread(() -> {
            ran.countDown();
            throw new IllegalStateException("uncaught in worker");
        });
        assertEquals("naruserve-0-worker-1", t.getName());
        assertTrue(t.isDaemon());
        t.start();
        t.join(5000);
        assertTrue(ran.await(1, TimeUnit.SECONDS));
        assertEquals(1, uncaught.errors.size());
        assertEquals("uncaught in worker", uncaught.errors.get(0).getMessage());
    }
}
