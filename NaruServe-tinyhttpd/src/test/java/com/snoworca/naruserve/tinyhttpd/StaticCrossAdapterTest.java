package com.snoworca.naruserve.tinyhttpd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.testing.StubJsonCodec;
import com.snoworca.naruserve.testing.TestApp;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * T30 and T31 file responses over the in-memory adapter and TinyHttpd, and the bounded-heap static transfer.
 * SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-6-8.sds.md.
 */
public class StaticCrossAdapterTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    private static final String ETAG = "\"3e8-18bcfe5687b\"";

    /** method, target, then header name/value pairs. */
    private static final String[][] CASES = {
        {"GET", "/r"},
        {"GET", "/r", "Range", "bytes=10-19"},
        {"GET", "/r", "Range", "bytes=5000-"},
        {"GET", "/r", "Range", "bytes=0-9,20-29"},
        {"GET", "/r", "If-None-Match", ETAG},
        {"GET", "/r", "If-Modified-Since", "Tue, 14 Nov 2023 22:13:20 GMT"},
        {"GET", "/r", "Range", "bytes=0-9", "If-Range", ETAG},
        {"HEAD", "/r"},
        {"GET", "/s/report.pdf", "Range", "bytes=-5"},
        {"GET", "/s/report.pdf", "If-None-Match", "W/" + ETAG},
    };

    private static final String[] HEADERS = {"Content-Type", "Content-Length", "Content-Range", "ETag",
        "Last-Modified", "Accept-Ranges", "X-Content-Type-Options"};

    private File pdf() throws IOException {
        File f = new File(tmp.getRoot(), "report.pdf");
        byte[] b = new byte[1000];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) i;
        }
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(b);
        }
        assertTrue(f.setLastModified(1700000000123L));
        return f;
    }

    private static void routes(RestApp app, File f) {
        app.get("/r", (req, res) -> res.file(f));
        app.staticFiles("/s", f.getParentFile());
    }

    private static String line(int status, String[] headerValues, int bodyLength) {
        StringBuilder sb = new StringBuilder().append(status);
        for (int i = 0; i < HEADERS.length; i++) {
            sb.append(' ').append(HEADERS[i]).append('=').append(headerValues[i]);
        }
        return sb.append(" body=").append(bodyLength).toString();
    }

    private static String raw(String[] c) {
        StringBuilder sb = new StringBuilder(c[0]).append(' ').append(c[1]).append(" HTTP/1.1\r\nHost: 127.0.0.1\r\n");
        for (int i = 2; i < c.length; i += 2) {
            sb.append(c[i]).append(": ").append(c[i + 1]).append("\r\n");
        }
        return sb.append("Connection: close\r\n\r\n").toString();
    }

    // SDS-AC-11; FR-STATIC-011 AC-1; FR-STATIC-011 AC-3
    @Test
    public void sdsAc11_FR_STATIC_011_AC1_AC3_fileAndStaticAnswersMatchOnBothAdapters() throws IOException {
        File f = pdf();
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        TestApp t = TestApp.create(adapter);
        routes(t.app(), f);
        t.start();
        List<String> memory = new ArrayList<>();
        try {
            for (String[] c : CASES) {
                ClientRequest req = ClientRequest.of(c[0], c[1]).header("Host", "x");
                for (int i = 2; i < c.length; i += 2) {
                    req.header(c[i], c[i + 1]);
                }
                ClientResponse r = adapter.send(req);
                String[] values = new String[HEADERS.length];
                for (int i = 0; i < HEADERS.length; i++) {
                    values[i] = r.header(HEADERS[i]);
                }
                memory.add(line(r.status(), values, r.status() == 416 ? -1 : r.body().length));
            }
        } finally {
            t.app().shutdown(1, TimeUnit.SECONDS);
        }

        RestApp app = RestApp.builder().httpAdapter(new TinyHttpdAdapter()).jsonCodec(new StubJsonCodec())
                .logger(new RecordingLogger()).build();
        routes(app, f);
        int port = app.listen(0).port();
        List<String> socket = new ArrayList<>();
        try {
            for (String[] c : CASES) {
                String response = RawTargetSocketTest.Http.send(port, raw(c));
                int status = RawTargetSocketTest.Http.status(response);
                String[] values = new String[HEADERS.length];
                for (int i = 0; i < HEADERS.length; i++) {
                    values[i] = RawTargetSocketTest.Http.header(response, HEADERS[i]);
                }
                int body = response.length() - response.indexOf("\r\n\r\n") - 4;
                socket.add(line(status, values, status == 416 ? -1 : body));
            }
        } finally {
            app.shutdown(1, TimeUnit.SECONDS);
        }
        assertTrue(memory.get(0), memory.get(0).startsWith("200 Content-Type=application/pdf Content-Length=1000"));
        assertTrue(memory.get(1), memory.get(1).startsWith("206 "));
        assertTrue(memory.get(2), memory.get(2).startsWith("416 "));
        assertTrue(memory.get(4), memory.get(4).startsWith("304 "));
        assertTrue(memory.get(5), memory.get(5).startsWith("304 "));
        assertTrue(memory.get(7), memory.get(7).endsWith("body=0"));
        assertEquals(memory, socket);
    }

    // FR-STATIC-003 AC-4
    @Test
    public void FR_STATIC_003_AC4_a64MiBStaticFileStreamsWithinABoundedHeap() throws Exception {
        long size = 64L * 1024 * 1024;
        File big = new File(tmp.getRoot(), "big.bin");
        try (RandomAccessFile raf = new RandomAccessFile(big, "rw")) {
            raf.setLength(size);
        }
        RestApp app = RestApp.builder().httpAdapter(new TinyHttpdAdapter()).jsonCodec(new StubJsonCodec())
                .logger(new RecordingLogger()).build();
        app.staticFiles("/", tmp.getRoot());
        int port = app.listen(0).port();
        try (Socket s = new Socket()) {
            long before = usedHeap();
            s.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            s.setSoTimeout(30000);
            s.getOutputStream().write(raw(new String[] {"GET", "/big.bin"}).getBytes(StandardCharsets.ISO_8859_1));
            InputStream in = s.getInputStream();
            byte[] buf = new byte[8192];
            long total = 0;
            long during = -1;
            for (int n; (n = in.read(buf)) >= 0; ) {
                total += n;
                if (during < 0 && total > size / 2) {
                    during = usedHeap(); // measured while the server is still sending
                }
            }
            assertTrue("got " + total, total > size);
            assertTrue("heap grew " + (during - before), during - before < 8L * 1024 * 1024);
        } finally {
            app.shutdown(1, TimeUnit.SECONDS);
        }
    }

    private static long usedHeap() {
        Runtime rt = Runtime.getRuntime();
        System.gc();
        return rt.totalMemory() - rt.freeMemory();
    }
}
