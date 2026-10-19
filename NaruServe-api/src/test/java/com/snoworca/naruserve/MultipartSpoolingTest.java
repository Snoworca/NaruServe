package com.snoworca.naruserve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.StubJsonCodec;
import java.io.ByteArrayOutputStream;
import java.io.File;
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
}
