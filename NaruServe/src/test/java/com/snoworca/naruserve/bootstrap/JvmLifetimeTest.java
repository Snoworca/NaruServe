package com.snoworca.naruserve.bootstrap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.gson.GsonJsonCodec;
import com.snoworca.naruserve.tinyhttpd.TinyHttpdAdapter;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/** The keeper thread holds a JVM whose main returned. SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-5-6.sds.md. */
public class JvmLifetimeTest {

    /** The child JVM: registers a shutdown route, listens on port 0, prints the port and returns. */
    public static final class Child {

        public static void main(String[] args) {
            // shortcut: assembled by hand until DefaultAppFactory (SDS wave-5-7) backs RestApp.create().
            RestApp app = RestApp.builder().httpAdapter(new TinyHttpdAdapter()).jsonCodec(new GsonJsonCodec()).build();
            app.get("/alive", (req, res) -> res.send("alive"));
            app.get("/shutdown", (req, res) -> {
                app.shutdown(5, TimeUnit.SECONDS);
                res.send("bye");
            });
            System.out.println("PORT=" + app.listen(0).port());
            System.out.flush();
        }
    }

    private static int get(int port, String path) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(10000);
        try {
            return c.getResponseCode();
        } finally {
            c.disconnect();
        }
    }

    // SDS-AC-11; FR-ENGINE-006 AC-5
    @Test(timeout = 60000)
    public void sdsAc11_FR_ENGINE_006_AC5_childJvmServesAfterMainReturnsAndExitsAfterTheShutdownRoute()
            throws Exception {
        String java = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        Process child = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), Child.class.getName())
                .redirectErrorStream(true).start();
        try {
            BufferedReader out = new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8));
            int port = -1;
            for (String line; port < 0 && (line = out.readLine()) != null; ) {
                if (line.startsWith("PORT=")) {
                    port = Integer.parseInt(line.substring(5).trim());
                }
            }
            assertTrue("child printed no port", port > 0);
            // Drain the rest so the child never blocks on a full pipe.
            Thread drain = new Thread(() -> {
                try {
                    while (out.readLine() != null) {
                        // discard
                    }
                } catch (IOException ignored) {
                    // the child exited
                }
            });
            drain.setDaemon(true);
            drain.start();

            Thread.sleep(1000);
            assertTrue("child exited after main returned", child.isAlive());
            assertEquals(200, get(port, "/alive"));

            assertEquals(200, get(port, "/shutdown"));
            assertTrue("child still running after the shutdown route", child.waitFor(15, TimeUnit.SECONDS));
            assertEquals(0, child.exitValue());
        } finally {
            child.destroyForcibly();
        }
    }
}
