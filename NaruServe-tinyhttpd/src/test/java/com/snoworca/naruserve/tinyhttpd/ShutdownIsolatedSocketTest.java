package com.snoworca.naruserve.tinyhttpd;

import static com.snoworca.naruserve.tinyhttpd.RawWebSocketClient.TEXT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.SseClient;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.tinyhttpd.RawTargetSocketTest.Http;
import com.snoworca.naruserve.tinyhttpd.SseSocketTest.Stream;
import java.io.File;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * T44 in the isolatedTest task, one JVM per class, so no other app's threads are alive: after shutdown nothing of the
 * app is left. SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-12.sds.md.
 */
public class ShutdownIsolatedSocketTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    /** Live threads of the app on {@code port}: naruserve-*, tinyhttpd-acceptor-<port> and tinyhttpd-watchdog. */
    static List<String> appThreads(int port) {
        List<String> out = new ArrayList<>();
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            String n = t.getName();
            if (t.isAlive() && (n.startsWith("naruserve-") || n.equals("tinyhttpd-acceptor-" + port)
                    || n.equals("tinyhttpd-watchdog"))) {
                out.add(n);
            }
        }
        return out;
    }

    /** The size of the package-private registry {@code accessor} of {@code app}. */
    private static int registrySize(RestApp app, String accessor) throws Exception {
        Method m = app.getClass().getDeclaredMethod(accessor);
        m.setAccessible(true);
        Object registry = m.invoke(app);
        Method size = registry.getClass().getDeclaredMethod("size");
        size.setAccessible(true);
        return (Integer) size.invoke(registry);
    }

    // SDS-AC-9; FR-APP-020 AC-9: after a run with an open WebSocket session, an open SSE stream and an upload whose
    // part spooled to disk, shutdown leaves no app thread, empty session and stream registries and no temp file (T44)
    @Test(timeout = 60000)
    public void wave7SdsAc9_FR_APP_020_AC9_noThreadNoRegistryEntryAndNoTempFileAfterShutdown() throws Exception {
        File dir = tmp.newFolder("spool");
        BlockingQueue<SseClient> streams = new LinkedBlockingQueue<>();
        RestApp app = Http.app(new TinyHttpdAdapter(), new RecordingLogger());
        app.multipart(o -> o.memoryThreshold(16).tempDirectory(dir));
        app.ws("/ok", (req, ws) -> ws.onText(text -> ws.sendText("hello")));
        app.get("/events", (req, res) -> streams.add(res.sse()));
        app.post("/up", (req, res) -> res.send(String.valueOf(req.file("f").filename())));
        int port = app.listen(0).port();
        assertTrue("no app thread while running", !appThreads(port).isEmpty());
        try (RawWebSocketClient ws = RawWebSocketClient.open(port, "/ok");
                Stream sse = new Stream(port, Http.get("/events"));
                Socket upload = new Socket()) {
            assertEquals(ws.handshake, 101, ws.status());
            ws.text("hi");
            assertEquals("hello", ws.readFrame(TEXT).text());
            sse.readHeaders();
            SseClient client = streams.poll(10, TimeUnit.SECONDS);
            assertNotNull("no stream opened", client);
            assertTrue(client.send("one"));
            sse.readUntil("data: one\n\n");
            upload.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            OutputStream out = upload.getOutputStream();
            out.write(("POST /up HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: multipart/form-data; boundary=b\r\n"
                    + "Content-Length: 100000\r\n\r\n--b\r\nContent-Disposition: form-data; name=\"f\"; "
                    + "filename=\"a.bin\"\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            byte[] half = new byte[20000];
            Arrays.fill(half, (byte) 'h');
            out.write(half); // and never the rest: the upload is in progress at shutdown
            out.flush();
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (dir.list().length == 0 && System.nanoTime() < until) {
                Thread.sleep(5);
            }
            assertEquals("the part spooled before shutdown", 1, dir.list().length);
            assertEquals(1, registrySize(app, "webSocketRegistry"));
            assertEquals(1, registrySize(app, "sseRegistry"));
            app.shutdown(1, TimeUnit.SECONDS);
        }
        // a thread whose pool reports terminated may still be leaving its run method
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (!appThreads(port).isEmpty() && System.nanoTime() < end) {
            Thread.sleep(5);
        }
        assertEquals(new ArrayList<String>(), appThreads(port));
        assertEquals(0, registrySize(app, "webSocketRegistry"));
        assertEquals(0, registrySize(app, "sseRegistry"));
        assertEquals(new ArrayList<String>(), Arrays.asList(dir.list()));
    }
}
