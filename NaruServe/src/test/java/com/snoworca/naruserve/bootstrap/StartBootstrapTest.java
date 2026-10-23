package com.snoworca.naruserve.bootstrap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;

import com.snoworca.naruserve.AppState;
import com.snoworca.naruserve.Logger;
import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.ServerHandle;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/** RestApp.start over the real bootstrap engine and, for the JVM-only parts, in child JVMs (FR-DX-012, FR-DX-014). */
public class StartBootstrapTest {

    private static final class Capture implements Logger {
        final List<String> info = new CopyOnWriteArrayList<>();

        @Override
        public void trace(String message) {
        }

        @Override
        public void debug(String message) {
        }

        @Override
        public void info(String message) {
            info.add(message);
        }

        @Override
        public void warn(String message) {
        }

        @Override
        public void error(String message) {
        }

        @Override
        public void error(String message, Throwable error) {
        }
    }

    private static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(10000);
        try {
            assertEquals(200, c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[1024];
                for (int n; (n = in.read(buf)) > 0; ) {
                    out.write(buf, 0, n);
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            c.disconnect();
        }
    }

    // FR-DX-012 AC-3; FR-DX-013 AC-2, AC-3; FR-DX-014 AC-1
    @Test(timeout = 60000)
    public void FR_DX_012_AC3_startServesARealRequestOnTheActualPortAndTheHandleStopsIt() throws Exception {
        Capture log = new Capture();
        ServerHandle h = RestApp.start(0, app -> {
            app.logger(log);
            app.get("/x", (req, res) -> res.send("ok"));
        });
        try {
            assertTrue(h.port() > 0);
            assertEquals(AppState.RUNNING, h.state());
            assertEquals("127.0.0.1", h.address());
            assertEquals("http://127.0.0.1:" + h.port(), h.url());
            assertEquals("ok", get(h.url() + "/x"));
            List<String> start = new ArrayList<>();
            for (String l : log.info) {
                if (l.startsWith("Listening on")) {
                    start.add(l);
                }
            }
            assertEquals(java.util.Arrays.asList("Listening on http://127.0.0.1:" + h.port()
                    + " (this device only). Press Ctrl+C to stop."), start);
        } finally {
            h.shutdown(5, TimeUnit.SECONDS);
        }
        assertEquals(AppState.TERMINATED, h.state());
    }

    // FR-DX-012 AC-6: a failing lambda over the real factory
    @Test
    public void FR_DX_012_AC6_lambdaFailurePassesTheOriginalExceptionThroughTheRealFactory() {
        IllegalStateException failure = new IllegalStateException("config failed");
        try {
            RestApp.start(0, app -> {
                throw failure;
            });
            throw new AssertionError("lambda failure swallowed");
        } catch (IllegalStateException e) {
            assertTrue(e == failure);
        }
    }

    /** Child JVM: start() is the whole main; the app records onStop. */
    public static final class Child {

        public static void main(String[] args) {
            ServerHandle h = RestApp.start(0, app -> {
                app.get("/alive", (req, res) -> res.send("alive"));
                app.get("/shutdown", (req, res) -> {
                    app.shutdown(5, TimeUnit.SECONDS);
                    res.send("bye");
                });
                app.onStop(ctx -> System.out.println("ONSTOP"));
            });
            System.out.println("PORT=" + h.port());
            System.out.flush();
        }
    }

    /** The child writes to a file, so output printed just before it exits is never lost with a closed pipe. */
    private static Process startChild(File output) throws IOException {
        String java = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        return new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), Child.class.getName())
                .redirectErrorStream(true).redirectOutput(output).start();
    }

    private static List<String> lines(File output) throws IOException {
        return java.nio.file.Files.readAllLines(output.toPath(), StandardCharsets.UTF_8);
    }

    private static int awaitPort(File output) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < end) {
            for (String line : lines(output)) {
                if (line.startsWith("PORT=")) {
                    return Integer.parseInt(line.substring(5).trim());
                }
            }
            Thread.sleep(50);
        }
        throw new AssertionError("child printed no port: " + lines(output));
    }

    // FR-DX-012 AC-4: main only calls start and the JVM lives on until the app is shut down
    @Test(timeout = 90000)
    public void FR_DX_012_AC4_childJvmWhoseMainOnlyCallsStartServesAfterMainReturns() throws Exception {
        File output = File.createTempFile("start-child", ".log");
        Process child = startChild(output);
        try {
            int port = awaitPort(output);
            Thread.sleep(1000);
            assertTrue("child exited after main returned", child.isAlive());
            assertEquals("alive", get("http://127.0.0.1:" + port + "/alive"));
            assertEquals("bye", get("http://127.0.0.1:" + port + "/shutdown"));
            assertTrue("child still running after the shutdown route", child.waitFor(20, TimeUnit.SECONDS));
            assertEquals(0, child.exitValue());
            assertTrue(lines(output).toString(), lines(output).contains("ONSTOP"));
        } finally {
            child.destroyForcibly();
            output.delete();
        }
    }

    // FR-DX-014 AC-3: SIGTERM runs the hook: Stopping line, onStop, exit by the signal
    @Test(timeout = 90000)
    public void FR_DX_014_AC3_sigtermRunsTheHookWithAStoppingLineAndOnStop() throws Exception {
        assumeFalse("Process.destroy is not SIGTERM on Windows",
                System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win"));
        File output = File.createTempFile("start-child", ".log");
        Process child = startChild(output);
        try {
            int port = awaitPort(output);
            assertEquals("alive", get("http://127.0.0.1:" + port + "/alive"));
            child.destroy();
            assertTrue("child did not exit on SIGTERM", child.waitFor(30, TimeUnit.SECONDS));
            List<String> lines = lines(output);
            boolean listening = false;
            boolean stopping = false;
            for (String l : lines) {
                listening |= l.contains("Listening on http://127.0.0.1:" + port + " (this device only)");
                stopping |= l.contains("Stopping");
            }
            assertTrue("start line in " + lines, listening);
            assertTrue("Stopping line in " + lines + " exit=" + child.exitValue(), stopping);
            assertTrue("onStop ran in " + lines, lines.contains("ONSTOP"));
            assertFalse(lines.toString(), lines.toString().contains("Exception"));
        } finally {
            child.destroyForcibly();
            output.delete();
        }
    }
}
