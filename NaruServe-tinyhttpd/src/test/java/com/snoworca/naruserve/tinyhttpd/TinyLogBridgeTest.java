package com.snoworca.naruserve.tinyhttpd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.Logger;
import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.tinyhttpd.RawTargetSocketTest.Http;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import org.junit.Test;

/** TinyHttpd logging into the app logger. SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-5-6.sds.md. */
public class TinyLogBridgeTest {

    // SDS-AC-6; FR-ENGINE-005 AC-6
    @Test
    public void sdsAc6_FR_ENGINE_005_AC6_levelsMapToTheAppLoggerAndEveryLevelIsLoggable() {
        RecordingLogger log = new RecordingLogger();
        TinyLogBridge bridge = new TinyLogBridge(log);
        IOException boom = new IOException("boom");
        for (Level l : new Level[] {Level.SEVERE, Level.WARNING, Level.INFO, Level.CONFIG, Level.FINE, Level.FINER,
                Level.FINEST, Level.ALL, Level.OFF}) {
            assertTrue(l.getName(), bridge.isLoggable(l));
        }
        bridge.log(Level.SEVERE, "s", boom);
        bridge.log(Level.SEVERE, "s2", null);
        bridge.log(Level.WARNING, "w", boom);
        bridge.log(Level.WARNING, "w2", null);
        bridge.log(Level.INFO, "i", null);
        bridge.log(Level.CONFIG, "c", null);
        bridge.log(Level.FINE, "f", null);
        bridge.log(Level.FINER, "fr", null);
        bridge.log(Level.FINEST, "fs", null);
        assertEquals(Arrays.asList("ERROR s boom", "ERROR s2", "WARN w: " + boom, "WARN w2", "INFO i", "DEBUG c",
                "DEBUG f", "TRACE fr", "TRACE fs"), log.lines);
        assertEquals(1, log.errors.size());
        assertSame(boom, log.errors.get(0));
    }

    // SDS-AC-6; FR-ENGINE-005 AC-6
    @Test(timeout = 20000)
    public void sdsAc6_FR_ENGINE_005_AC6_aTinyHttpdWarningReachesTheAppLogger() throws Exception {
        RecordingLogger log = new RecordingLogger();
        TinyHttpdAdapter adapter = new TinyHttpdAdapter();
        CountDownLatch entered = new CountDownLatch(1);
        // A handler that ignores the stop interrupt: TinyHttpd warns that a worker is still running after stop.
        adapter.bind(Http.settings().logger(log).build(), ex -> {
            entered.countDown();
            long end = System.currentTimeMillis() + 1500;
            while (System.currentTimeMillis() < end) {
                Thread.interrupted();
            }
            ex.abort();
        });
        Thread client = new Thread(() -> {
            try {
                Http.send(adapter.boundPort(), Http.get("/stuck"));
            } catch (IOException ignored) {
                // the stop closes the socket
            }
        });
        client.start();
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        adapter.stop(0, TimeUnit.MILLISECONDS);
        adapter.release();
        client.join(5000);
        boolean found = false;
        for (String l : log.lines) {
            found |= l.startsWith("WARN ") && l.contains("worker threads still running after stop");
        }
        assertTrue(log.lines.toString(), found);
    }

    // SDS-AC-6; FR-ENGINE-005 AC-6
    @Test(timeout = 20000)
    public void sdsAc6_FR_ENGINE_005_AC6_aThrowingAppLoggerDoesNotStopServing() throws Exception {
        Logger throwing = new Logger() {
            @Override
            public void trace(String message) {
                throw new IllegalStateException("trace");
            }

            @Override
            public void debug(String message) {
                throw new IllegalStateException("debug");
            }

            @Override
            public void info(String message) {
                throw new IllegalStateException("info");
            }

            @Override
            public void warn(String message) {
                throw new IllegalStateException("warn");
            }

            @Override
            public void error(String message) {
                throw new IllegalStateException("error");
            }

            @Override
            public void error(String message, Throwable error) {
                throw new IllegalStateException("error");
            }
        };
        RestApp app = RestApp.builder().httpAdapter(new TinyHttpdAdapter())
                .jsonCodec(new com.snoworca.naruserve.testing.StubJsonCodec()).logger(throwing).build();
        app.get("/ok", (req, res) -> res.send("ok"));
        try {
            int port = app.listen(0).port();
            // A parse error makes TinyHttpd log; the logger throws; the next request is still answered.
            assertEquals(400, Http.status(Http.send(port, "NOT A REQUEST LINE\r\n\r\n")));
            String r = Http.send(port, Http.get("/ok"));
            assertEquals(r, 200, Http.status(r));
            assertEquals("ok", Http.body(r));
        } finally {
            app.shutdown(2, TimeUnit.SECONDS);
        }
    }
}
