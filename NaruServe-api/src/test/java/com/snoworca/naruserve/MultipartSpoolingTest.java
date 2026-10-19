package com.snoworca.naruserve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.StubJsonCodec;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Spooling to a temp file above memoryThreshold, and server-generated temp names, through the public API.
 * SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-6-4.sds.md.
 */
public class MultipartSpoolingTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    private static final String B = "spool";

    /** One file part named {@code f} with {@code filename} and {@code size} 'x' bytes. */
    static byte[] filePart(String filename, int size) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] head = ("--" + B + "\r\nContent-Disposition: form-data; name=\"f\"; filename=\"" + filename
                + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        out.write(head, 0, head.length);
        byte[] content = new byte[size];
        Arrays.fill(content, (byte) 'x');
        out.write(content, 0, size);
        byte[] tail = ("\r\n--" + B + "--\r\n").getBytes(StandardCharsets.UTF_8);
        out.write(tail, 0, tail.length);
        return out.toByteArray();
    }

    /** Posts {@code body}; the handler records the temp directory listing while it holds the upload. */
    private List<String> upload(File dir, byte[] body, List<Object> seen) {
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        RestApp app = RestApp.builder().httpAdapter(adapter).jsonCodec(new StubJsonCodec()).build();
        app.multipart(o -> o.memoryThreshold(1024).tempDirectory(dir));
        List<String> during = new ArrayList<>();
        app.post("/up", (req, res) -> {
            UploadFile f = req.file("f");
            seen.add(f == null ? "none" : f.filename());
            during.addAll(Arrays.asList(dir.list()));
            res.send("ok");
        });
        app.listen(0);
        try {
            assertEquals(200, adapter.send(ClientRequest.of("POST", "/up")
                    .header("Content-Type", "multipart/form-data; boundary=" + B)
                    .header("Content-Length", String.valueOf(body.length)).body(body)).status());
        } finally {
            app.shutdown(5, TimeUnit.SECONDS);
        }
        return during;
    }

    // FR-MULTIPART-004 AC-1
    // SDS-AC-1
    @Test
    public void sdsAc1_FR_MULTIPART_004_AC1_partAboveThresholdSpoolsToOneTempFile() throws Exception {
        File dir = tmp.newFolder("t");
        List<Object> seen = new ArrayList<>();
        assertEquals(0, upload(dir, filePart("a.bin", 1024), seen).size());
        assertEquals(0, dir.list().length);
        List<String> during = upload(dir, filePart("a.bin", 1025), seen);
        assertEquals(during.toString(), 1, during.size());
        assertEquals("the temp file is gone once the request ended", 0, dir.list().length);
    }

    // FR-MULTIPART-004 AC-3, FR-MULTIPART-004 AC-4
    // SDS-AC-2
    @Test
    public void sdsAc2_FR_MULTIPART_004_AC3_tempNamesNeverDeriveFromClientValues() throws Exception {
        char[] longName = new char[300];
        Arrays.fill(longName, 'q');
        String[] names = {"../../evil.sh", "a/b\\c.txt", new String(longName), "über.txt"};
        File dir = tmp.newFolder("t");
        for (String name : names) {
            List<Object> seen = new ArrayList<>();
            List<String> during = upload(dir, filePart(name.replace("\\", "\\\\"), 2000), seen);
            assertEquals(name, during.size(), 1);
            String temp = during.get(0);
            assertTrue(temp, temp.matches("naru-upload-[0-9]+\\.tmp"));
            assertFalse(temp, temp.contains("evil") || temp.contains("qqq") || temp.contains("c.txt"));
            assertEquals(Arrays.asList(name), seen);
        }
        assertFalse(new File(tmp.getRoot(), "evil.sh").exists());
        assertFalse(new File("evil.sh").exists());
        assertFalse(new File(dir, "b").exists());
        assertEquals(Arrays.asList("t"), Arrays.asList(tmp.getRoot().list()));
    }

    /** One text part named {@code name} of {@code size} 't' bytes, without the close delimiter. */
    private static String textPart(String name, int size) {
        char[] v = new char[size];
        Arrays.fill(v, 't');
        return "--" + B + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + new String(v) + "\r\n";
    }

    /** Posts {@code body} to an app with the default limits, a temp directory {@code dir} and {@code handler}. */
    private static ClientResponse post(File dir, byte[] body, RouteHandler handler) {
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        RestApp app = RestApp.builder().httpAdapter(adapter).jsonCodec(new StubJsonCodec()).build();
        app.multipart(o -> o.tempDirectory(dir));
        app.post("/up", handler);
        app.listen(0);
        try {
            return adapter.send(ClientRequest.of("POST", "/up")
                    .header("Content-Type", "multipart/form-data; boundary=" + B)
                    .header("Content-Length", String.valueOf(body.length)).body(body));
        } finally {
            app.shutdown(5, TimeUnit.SECONDS);
        }
    }

    // FR-MULTIPART-004 AC-4: a browser sends a backslash in filename raw; it reaches filename() unchanged
    @Test
    public void FR_MULTIPART_004_AC4_rawBackslashInFilenameIsKept() throws Exception {
        File dir = tmp.newFolder("t");
        String[][] cases = {{"a/b\\c.txt", "a/b\\c.txt"}, {"C:\\fakepath\\a.txt", "C:\\fakepath\\a.txt"},
            {"a\\\"b.txt", "a\"b.txt"}};
        for (String[] c : cases) {
            List<Object> seen = new ArrayList<>();
            upload(dir, filePart(c[0], 10), seen);
            assertEquals(c[0], Arrays.asList(c[1]), seen);
        }
    }

    // FR-MULTIPART-004 AC-5: text parts total at most formLimit (default 1 MiB); above it 413 with the standard body
    @Test
    public void FR_MULTIPART_004_AC5_textPartTotalIsCappedByFormLimit() throws Exception {
        File dir = tmp.newFolder("t");
        List<Object> seen = new ArrayList<>();
        RouteHandler h = (req, res) -> {
            String v = req.form("a");
            seen.add(v == null ? -1 : v.length());
            seen.add(req.parts().size());
            seen.add(req.parts().get(0).size());
            res.send("ok");
        };
        byte[] one = (textPart("a", 1048576) + "--" + B + "--\r\n").getBytes(StandardCharsets.UTF_8);
        assertEquals(200, post(dir, one, h).status());
        assertEquals(Arrays.<Object>asList(1048576, 1, 1048576L), seen);
        byte[] over = (textPart("a", 1048577) + "--" + B + "--\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] two = (textPart("a", 600000) + textPart("b", 600000) + "--" + B + "--\r\n")
                .getBytes(StandardCharsets.UTF_8);
        for (byte[] body : new byte[][] {over, two}) {
            seen.clear();
            ClientResponse r = post(dir, body, h);
            assertEquals(413, r.status());
            assertTrue(r.text(), r.text().startsWith("{\"error\":{\"code\":\"PAYLOAD_TOO_LARGE\""));
            assertEquals(Arrays.asList(), seen);
        }
        assertEquals(0, dir.list().length);
    }

    // FR-MULTIPART-004 AC-2: a 32 MiB upload with the default 64 KiB threshold grows the used heap by under 8 MiB
    @Test
    public void FR_MULTIPART_004_AC2_spooledUploadKeepsHeapGrowthUnder8MiB() throws Exception {
        File dir = tmp.newFolder("t");
        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        RestApp app = RestApp.builder().httpAdapter(adapter).jsonCodec(new StubJsonCodec()).build();
        app.multipart(o -> o.tempDirectory(dir));
        long[] heap = new long[2];
        app.post("/up", (req, res) -> {
            UploadFile f = req.file("f");
            System.gc();
            heap[1] = mem.getHeapMemoryUsage().getUsed();
            res.send(f == null ? "none" : String.valueOf(f.size()));
        });
        app.listen(0);
        try {
            byte[] body = filePart("big.bin", 33554432);
            ClientRequest request = ClientRequest.of("POST", "/up")
                    .header("Content-Type", "multipart/form-data; boundary=" + B)
                    .header("Content-Length", String.valueOf(body.length)).body(body);
            body = null; // the request holds its own copy; only parser growth is measured
            System.gc();
            heap[0] = mem.getHeapMemoryUsage().getUsed();
            ClientResponse r = adapter.send(request);
            assertEquals(r.text(), "33554432", r.text());
        } finally {
            app.shutdown(5, TimeUnit.SECONDS);
        }
        long growth = heap[1] - heap[0];
        assertTrue("heap grew by " + growth, growth < 8388608L);
    }

    // FR-MULTIPART-005 AC-4, FR-MULTIPART-006 AC-1: the request ends when the handler returned and the response
    // finished, so save() after res.send() in the same handler still works and the temp file is gone afterwards
    @Test
    public void FR_MULTIPART_006_AC1_saveAfterSendInTheSameHandlerWorks() throws Exception {
        File dir = tmp.newFolder("t");
        File dest = new File(tmp.getRoot(), "dest.bin");
        List<Object> seen = new ArrayList<>();
        ClientResponse r = post(dir, filePart("a.bin", 100000), (req, res) -> {
            UploadFile f = req.file("f");
            res.status(202).send("queued");
            try {
                if (f != null) {
                    f.save(dest);
                    seen.add(dest.length());
                }
            } catch (IllegalStateException | IOException e) {
                seen.add(e.toString());
            }
        });
        assertEquals(202, r.status());
        assertEquals(Arrays.<Object>asList(100000L), seen);
        try (InputStream in = new FileInputStream(dest)) {
            assertEquals('x', in.read());
        }
        assertEquals(0, dir.list().length);
    }
}
