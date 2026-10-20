package com.snoworca.naruserve.tinyhttpd;

import static org.junit.Assert.*;

import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.testing.NoLeftoverThreads;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.testing.StubJsonCodec;
import java.io.File;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** FR-MULTIPART-002 AC-2: actual chunked requests include epilogue bytes in maxRequestSize. */
public class MultipartEpilogueSocketTest {
    @Rule public final NoLeftoverThreads threads = new NoLeftoverThreads();
    @Rule public final TemporaryFolder tmp = new TemporaryFolder();

    // FR-MULTIPART-002 AC-2: 40KiB epilogue gives413 before the handler receives parts; spool is cleaned.
    @Test public void chunkedOversizedEpilogueRejectsAndLeavesNoTemporaryFile() throws Exception {
        File dir = tmp.newFolder("spool");
        RestApp app = RestApp.builder().httpAdapter(new TinyHttpdAdapter()).jsonCodec(new StubJsonCodec())
                .logger(new RecordingLogger()).build();
        app.multipart(o -> o.maxRequestSize(16384).memoryThreshold(16).tempDirectory(dir));
        AtomicInteger accepted = new AtomicInteger();
        app.post("/up", (req, res) -> {
            long bytes = req.file("f").size();
            accepted.incrementAndGet();
            res.send("accepted:" + bytes);
        });
        int port = app.listen(0).port();
        try {
            String part = "--b\r\nContent-Disposition: form-data; name=\"f\"; filename=\"x.bin\"\r\n\r\n"
                    + RawTargetSocketTest.Http.repeat('f', 4096) + "\r\n--b--\r\n";
            for (int total : new int[]{16384, 16385, 45000}) {
                String body = part + RawTargetSocketTest.Http.repeat('e', total - part.length());
                String raw = "POST /up HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n"
                        + "Content-Type: multipart/form-data; boundary=b\r\nTransfer-Encoding: chunked\r\n\r\n"
                        + Integer.toHexString(body.length()) + "\r\n" + body + "\r\n0\r\n\r\n";
                String response = RawTargetSocketTest.Http.send(port, raw);
                assertEquals(response, total == 16384 ? 200 : 413, RawTargetSocketTest.Http.status(response));
                if (total > 16384) assertTrue(response, response.contains("PAYLOAD_TOO_LARGE"));
                assertEquals(0, dir.list().length);
            }
            assertEquals(1, accepted.get());
        } finally {
            app.shutdown(1, TimeUnit.SECONDS);
        }
    }
}
