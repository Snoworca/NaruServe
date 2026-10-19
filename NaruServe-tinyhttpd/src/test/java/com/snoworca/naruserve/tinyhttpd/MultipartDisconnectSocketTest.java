package com.snoworca.naruserve.tinyhttpd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.testing.StubJsonCodec;
import java.io.File;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * A client that closes the socket halfway through a spooled part leaves no temp file.
 * SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-6-4.sds.md.
 */
public class MultipartDisconnectSocketTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    // FR-MULTIPART-006 AC-1
    // SDS-AC-6
    @Test
    public void sdsAc6_FR_MULTIPART_006_AC1_halfSentSpooledPartLeavesNoTempFile() throws Exception {
        File dir = tmp.newFolder("spool");
        CountDownLatch spooled = new CountDownLatch(1);
        CountDownLatch failed = new CountDownLatch(1);
        RestApp app = RestApp.builder().httpAdapter(new TinyHttpdAdapter()).jsonCodec(new StubJsonCodec())
                .logger(new RecordingLogger()).build();
        app.multipart(o -> o.memoryThreshold(16).tempDirectory(dir));
        app.post("/up", (req, res) -> {
            try {
                req.file("f");
            } catch (RuntimeException e) {
                failed.countDown();
                throw e;
            }
            res.send("complete?");
        });
        int port = app.listen(0).port();
        try {
            String head = "--b\r\nContent-Disposition: form-data; name=\"f\"; filename=\"a.bin\"\r\n\r\n";
            byte[] half = new byte[20000];
            Arrays.fill(half, (byte) 'h');
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", port), 5000);
                OutputStream out = s.getOutputStream();
                out.write(("POST /up HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: multipart/form-data; boundary=b"
                        + "\r\nContent-Length: 100000\r\n\r\n" + head).getBytes(StandardCharsets.ISO_8859_1));
                out.write(half);
                out.flush();
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (dir.list().length == 0 && System.nanoTime() < until) {
                    Thread.sleep(5);
                }
                if (dir.list().length == 1) {
                    spooled.countDown();
                }
            }
            assertTrue("the part spooled before the disconnect", spooled.await(0, TimeUnit.SECONDS));
            assertTrue("the parse failed on the disconnect", failed.await(10, TimeUnit.SECONDS));
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (dir.list().length > 0 && System.nanoTime() < until) {
                Thread.sleep(5);
            }
            assertEquals(Arrays.asList(), Arrays.asList(dir.list()));
        } finally {
            app.shutdown(1, TimeUnit.SECONDS);
        }
    }
}
