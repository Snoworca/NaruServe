package com.snoworca.naruserve.tinyhttpd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.AppState;
import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.testing.NoLeftoverThreads;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.tinyhttpd.RawTargetSocketTest.Http;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Rule;
import org.junit.Test;

/** Adapter stop and release. SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-5-6.sds.md. */
public class ShutdownSocketTest {

    // I-015: no naruserve-* thread outlives the test
    @Rule
    public final NoLeftoverThreads noLeftoverThreads = new NoLeftoverThreads();

    private static RestApp app() {
        return Http.app(new TinyHttpdAdapter(), new RecordingLogger());
    }

    /** Sends {@code raw} on a new thread and keeps the full response (or the failure) in {@code out}. */
    private static Thread sendAsync(int port, String raw, AtomicReference<String> out) {
        Thread t = new Thread(() -> {
            try {
                out.set(Http.send(port, raw));
            } catch (IOException e) {
                out.set("failed: " + e);
            }
        });
        t.start();
        return t;
    }

    private static Thread shutdownAsync(RestApp app, long seconds, AtomicReference<Long> tookMillis) {
        Thread t = new Thread(() -> {
            long start = System.nanoTime();
            app.shutdown(seconds, TimeUnit.SECONDS);
            tookMillis.set(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        });
        t.start();
        return t;
    }

    // SDS-AC-7; FR-ENGINE-006 AC-1
    @Test(timeout = 30000)
    public void sdsAc7_FR_ENGINE_006_AC1_anInFlightRequestGetsTheFullTimeLeftAsGrace() throws Exception {
        RestApp app = app();
        CountDownLatch entered = new CountDownLatch(1);
        app.get("/slow", (req, res) -> {
            entered.countDown();
            Thread.sleep(1500);
            res.send("done");
        });
        int port = app.listen(0).port();
        AtomicReference<String> response = new AtomicReference<>();
        Thread client = sendAsync(port, Http.get("/slow"), response);
        assertTrue(entered.await(5, TimeUnit.SECONDS));

        AtomicReference<Long> took = new AtomicReference<>();
        shutdownAsync(app, 4, took).join(10000);
        client.join(5000);
        // 1.5 s of work outlives a fixed 1 s grace; the 4 s deadline covers it.
        assertEquals(response.get(), 200, Http.status(response.get()));
        assertEquals("done", Http.body(response.get()));
        assertTrue("took " + took.get(), took.get() < 4000 + 1500);
    }

    // SDS-AC-7; FR-ENGINE-006 AC-1
    @Test(timeout = 30000)
    public void sdsAc7_FR_ENGINE_006_AC1_stopReturnsWithinTheDeadlinePlus1500ms() throws Exception {
        RestApp app = app();
        CountDownLatch entered = new CountDownLatch(1);
        app.get("/forever", (req, res) -> {
            entered.countDown();
            Thread.sleep(60000); // ends when TinyHttpd interrupts the worker at the deadline
        });
        int port = app.listen(0).port();
        AtomicReference<String> response = new AtomicReference<>();
        Thread client = sendAsync(port, Http.get("/forever"), response);
        assertTrue(entered.await(5, TimeUnit.SECONDS));

        AtomicReference<Long> took = new AtomicReference<>();
        shutdownAsync(app, 1, took).join(10000);
        assertTrue("took " + took.get(), took.get() != null && took.get() >= 900 && took.get() < 1000 + 1500);
        client.join(5000);
        assertFalse(client.isAlive());
    }

    // SDS-AC-8; FR-ENGINE-006 AC-2
    @Test(timeout = 30000)
    public void sdsAc8_FR_ENGINE_006_AC2_noThreadOfTheAppSurvivesShutdown() throws Exception {
        RestApp app = app();
        app.get("/ok", (req, res) -> res.send("ok"));
        // TinyHttpd's watchdog name carries no port: only the one this app starts is checked.
        List<Thread> otherWatchdogs = Http.threads("tinyhttpd-watchdog");
        int port = app.listen(0).port();
        for (int i = 0; i < 3; i++) {
            assertEquals(200, Http.status(Http.send(port, Http.get("/ok"))));
        }
        assertFalse(Http.threads("naruserve-" + port + "-worker-").isEmpty());
        assertFalse(Http.threads("tinyhttpd-acceptor-" + port).isEmpty());
        assertFalse(Http.threads("naruserve-keeper-" + port).isEmpty());

        app.shutdown(2, TimeUnit.SECONDS);

        assertEquals(new ArrayList<Thread>(), Http.threads("naruserve-" + port + "-"));
        assertEquals(new ArrayList<Thread>(), Http.threads("tinyhttpd-acceptor-" + port));
        assertEquals(new ArrayList<Thread>(), Http.threads("naruserve-keeper-" + port));
        // TinyHttpd exposes no handle on its watchdog; it leaves its loop once the server is stopped.
        List<Thread> ours = Http.threads("tinyhttpd-watchdog");
        ours.removeAll(otherWatchdogs);
        for (Thread w : ours) {
            w.join(1000);
        }
        ours.removeIf(t -> !t.isAlive());
        assertEquals(new ArrayList<Thread>(), ours);
    }

    // SDS-AC-9; FR-ENGINE-006 AC-3
    @Test(timeout = 30000)
    public void sdsAc9_FR_ENGINE_006_AC3_keepAliveRaceGets503OrCloseAndInFlightGetsConnectionClose()
            throws Exception {
        RestApp app = app();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        app.get("/ok", (req, res) -> res.send("ok"));
        app.get("/held", (req, res) -> {
            entered.countDown();
            release.await(10, TimeUnit.SECONDS);
            res.send("held");
        });
        int port = app.listen(0).port();

        try (Socket idle = new Socket()) {
            idle.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            idle.setSoTimeout(10000);
            OutputStream idleOut = idle.getOutputStream();
            InputStream idleIn = idle.getInputStream();
            idleOut.write("GET /ok HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            idleOut.flush();
            String first = readOneResponse(idleIn);
            assertEquals(first, 200, Http.status(first));

            AtomicReference<String> held = new AtomicReference<>();
            Thread heldClient = sendAsync(port, "GET /held HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n", held);
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            AtomicReference<Long> took = new AtomicReference<>();
            Thread stopper = shutdownAsync(app, 5, took);
            long until = System.currentTimeMillis() + 5000;
            while (app.state() != AppState.STOPPING && System.currentTimeMillis() < until) {
                Thread.sleep(5);
            }
            assertEquals(AppState.STOPPING, app.state());

            String raced;
            try {
                idleOut.write("GET /ok HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                idleOut.flush();
                raced = Http.readAll(idleIn);
            } catch (SocketException e) {
                raced = "";
            }
            if (!raced.isEmpty()) {
                assertEquals(raced, 503, Http.status(raced));
                assertTrue(raced, raced.contains("\r\nConnection: close\r\n"));
            }

            release.countDown();
            heldClient.join(10000);
            stopper.join(10000);
            assertEquals(held.get(), 200, Http.status(held.get()));
            assertTrue(held.get(), held.get().contains("\r\nConnection: close\r\n"));
            assertEquals("held", Http.body(held.get()));
        }
    }

    /** One response with a Content-Length body from a connection that stays open. */
    private static String readOneResponse(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int length = -1;
        int headEnd = -1;
        while (headEnd < 0 || buf.size() < headEnd + length) {
            int b = in.read();
            if (b < 0) {
                break;
            }
            buf.write(b);
            if (headEnd < 0) {
                String s = new String(buf.toByteArray(), StandardCharsets.ISO_8859_1);
                if (s.endsWith("\r\n\r\n")) {
                    headEnd = s.length();
                    int i = s.toLowerCase(java.util.Locale.ROOT).indexOf("content-length:");
                    length = Integer.parseInt(s.substring(i + 15, s.indexOf("\r\n", i)).trim());
                }
            }
        }
        return new String(buf.toByteArray(), StandardCharsets.ISO_8859_1);
    }
}
