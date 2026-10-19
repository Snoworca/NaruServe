package com.snoworca.naruserve.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.ConsoleLogger;
import com.snoworca.naruserve.HttpException;
import com.snoworca.naruserve.UploadFile;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.StubJsonCodec;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Temp upload files deleted at request end, after save, after 413, by the after-onStop participant, and refused
 * once that participant closed the spool. SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-6-4.sds.md.
 */
public class SpoolCleanupTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    private static final String B = "clean";

    private final InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
    private final List<Object> seen = new CopyOnWriteArrayList<>();

    private static byte[] body(int size) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] head = ("--" + B + "\r\nContent-Disposition: form-data; name=\"f\"; filename=\"a.bin\"\r\n\r\n")
                .getBytes(StandardCharsets.ISO_8859_1);
        out.write(head, 0, head.length);
        byte[] content = new byte[size];
        Arrays.fill(content, (byte) 'z');
        out.write(content, 0, size);
        byte[] tail = ("\r\n--" + B + "--\r\n").getBytes(StandardCharsets.ISO_8859_1);
        out.write(tail, 0, tail.length);
        return out.toByteArray();
    }

    private static ClientRequest request(int size) {
        byte[] b = body(size);
        return ClientRequest.of("POST", "/up").header("Content-Type", "multipart/form-data; boundary=" + B)
                .header("Content-Length", String.valueOf(b.length)).body(b);
    }

    private DefaultRestApp app(File dir) {
        DefaultRestApp app = new DefaultRestApp(adapter, new StubJsonCodec(), new ConsoleLogger());
        app.multipart(o -> o.memoryThreshold(16).maxFileSize(1000).tempDirectory(dir));
        return app;
    }

    private static int files(File dir) {
        return dir.list().length;
    }

    // FR-MULTIPART-006 AC-1, FR-MULTIPART-006 AC-4
    // SDS-AC-6
    @Test
    public void sdsAc6_FR_MULTIPART_006_AC1_requestEndDeletesTempFilesOnEveryPath() throws Exception {
        File dir = tmp.newFolder("spool");
        File dest = new File(tmp.getRoot(), "saved.bin");
        DefaultRestApp app = app(dir);
        app.post("/ignore", (req, res) -> {
            seen.add(req.file("f") != null && files(dir) == 1);
            res.send("ok");
        });
        app.post("/save", (req, res) -> {
            UploadFile f = req.file("f");
            if (f != null) {
                f.save(dest);
            }
            seen.add(files(dir));
            res.send("ok");
        });
        app.post("/throw", (req, res) -> {
            seen.add(req.file("f") != null && files(dir) == 1);
            throw new IllegalArgumentException("boom");
        });
        app.post("/up", (req, res) -> {
            req.file("f");
            res.send("unreachable");
        });
        app.listen(0);
        try {
            byte[] b = body(500);
            assertEquals(200, adapter.send(ClientRequest.of("POST", "/ignore")
                    .header("Content-Type", "multipart/form-data; boundary=" + B)
                    .header("Content-Length", String.valueOf(b.length)).body(b)).status());
            assertEquals(0, files(dir));
            assertEquals(200, adapter.send(ClientRequest.of("POST", "/save")
                    .header("Content-Type", "multipart/form-data; boundary=" + B)
                    .header("Content-Length", String.valueOf(b.length)).body(b)).status());
            assertEquals(0, files(dir));
            assertEquals(500, adapter.send(ClientRequest.of("POST", "/throw")
                    .header("Content-Type", "multipart/form-data; boundary=" + B)
                    .header("Content-Length", String.valueOf(b.length)).body(b)).status());
            assertEquals(0, files(dir));
            assertEquals(413, adapter.send(request(2000)).status()); // past maxFileSize, after spooling began
            assertEquals(0, files(dir));
            assertEquals(Arrays.<Object>asList(true, 0, true), seen);
            assertTrue(dest.exists());
        } finally {
            app.shutdown(5, TimeUnit.SECONDS);
        }
    }

    // FR-MULTIPART-006 AC-4
    @Test
    public void FR_MULTIPART_006_AC4_eachUploadDeletesOnlyItsOwnTempFile() throws Exception {
        File dir = tmp.newFolder("spool");
        DefaultRestApp app = app(dir);
        CountDownLatch firstHolds = new CountDownLatch(1);
        CountDownLatch secondDone = new CountDownLatch(1);
        app.post("/up", (req, res) -> {
            UploadFile f = req.file("f");
            if ("1".equals(req.header("X-N"))) {
                firstHolds.countDown();
                secondDone.await(5, TimeUnit.SECONDS);
                seen.add(f != null && f.bytes().length == 500);
            }
            res.send("ok");
        });
        app.listen(0);
        try {
            Future<?> first = adapter.submit(request(500).header("X-N", "1"));
            assertTrue(firstHolds.await(5, TimeUnit.SECONDS));
            assertEquals(1, files(dir));
            assertEquals(200, adapter.send(request(500)).status());
            assertEquals("the holding request keeps its file", 1, files(dir));
            secondDone.countDown();
            first.get(5, TimeUnit.SECONDS);
            assertEquals(0, files(dir));
            assertEquals(Arrays.<Object>asList(true), seen);
        } finally {
            app.shutdown(5, TimeUnit.SECONDS);
        }
    }

    // FR-MULTIPART-006 AC-2
    // SDS-AC-8
    @Test
    public void sdsAc8_FR_MULTIPART_006_AC2_openUploadAtShutdownDeletedAfterOnStop() throws Exception {
        File dir = tmp.newFolder("spool");
        DefaultRestApp app = app(dir);
        CountDownLatch parsed = new CountDownLatch(1);
        CountDownLatch hold = new CountDownLatch(1);
        app.post("/up", (req, res) -> {
            req.file("f");
            parsed.countDown();
            hold.await(10, TimeUnit.SECONDS);
            res.send("late");
        });
        app.onStop(ctx -> seen.add("onStop sees " + files(dir)));
        app.listen(0);
        Future<?> f = adapter.submit(request(500));
        try {
            assertTrue(parsed.await(5, TimeUnit.SECONDS));
            assertEquals(1, files(dir));
            app.shutdown(100, TimeUnit.MILLISECONDS); // the adapter force-closes the held exchange
            assertEquals(Arrays.<Object>asList("onStop sees 1"), seen);
            assertEquals("deleted before shutdown returned", 0, files(dir));
        } finally {
            hold.countDown();
            try {
                f.get(5, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // the exchange was force-closed
            }
        }
    }

    // FR-MULTIPART-006 AC-3
    // SDS-AC-7
    @Test
    public void sdsAc7_FR_MULTIPART_006_AC3_parseAfterCleanupParticipantGets500AndNoFile() throws Exception {
        File dir = tmp.newFolder("spool");
        DefaultRestApp app = app(dir);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch cleaned = new CountDownLatch(1);
        // Added after the app's own cleanup participant, so it runs once that one has.
        app.shutdownParticipants().add(ShutdownParticipants.Phase.AFTER_ON_STOP, cleaned::countDown);
        app.post("/up", (req, res) -> {
            entered.countDown();
            cleaned.await(10, TimeUnit.SECONDS);
            try {
                req.file("f");
                seen.add("parsed");
            } catch (HttpException e) {
                seen.add(e.status());
            }
            seen.add(files(dir));
        });
        app.listen(0);
        Future<?> f = adapter.submit(request(500));
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        app.shutdown(100, TimeUnit.MILLISECONDS);
        try {
            f.get(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // the exchange was force-closed
        }
        assertEquals(Arrays.<Object>asList(500, 0), seen);
        assertFalse(dir.list().length > 0);

        SpoolRegistry spool = new SpoolRegistry();
        spool.closeAndDeleteAll();
        try {
            spool.createTemp(dir);
            throw new AssertionError("createTemp after closeAndDeleteAll did not throw");
        } catch (HttpException e) {
            assertEquals(500, e.status());
        }
        assertEquals(0, files(dir));
    }
}
