package com.snoworca.naruserve.tinyhttpd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.Configurer;
import com.snoworca.naruserve.MultipartOptions;
import com.snoworca.naruserve.Request;
import com.snoworca.naruserve.Response;
import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.UploadFile;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.testing.StubJsonCodec;
import com.snoworca.naruserve.testing.TestApp;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The one core multipart parser over the in-memory adapter and a TinyHttpd socket, reached through the public
 * request accessors (req.parts, req.form, req.file) of an app configured with app.multipart, so the shared parse,
 * the app spool registry, onError and the standard error body run on both adapters (FR-MULTIPART-001 AC-1).
 * SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-6-2.sds.md.
 */
public class MultipartCrossAdapterTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    private static final String B = "cross";

    private static final String MP = "multipart/form-data; boundary=" + B;

    private static final Configurer<MultipartOptions> DEFAULTS = o -> { };

    /**
     * Answers every part as name=size; and, when present, the text field {@code t} and the file {@code f}
     * (filename/size and the temp directory entry count); the framework releases the uploads.
     */
    private static void upload(Request req, Response res, File tempDir) {
        StringBuilder sb = new StringBuilder();
        for (UploadFile p : req.parts()) {
            sb.append(p.name()).append('=').append(p.size()).append(';');
        }
        String t = req.form("t");
        if (t != null) {
            sb.append("form t=").append(t).append(';');
        }
        UploadFile f = req.file("f");
        if (f != null) {
            sb.append("file f=").append(f.filename()).append('/').append(f.size()).append(" spooled=")
                    .append(tempDir.list().length).append(';');
        }
        res.send(sb.toString());
    }

    private static RestApp configure(RestApp app, File dir, Configurer<MultipartOptions> config) {
        app.multipart(o -> config.configure(o.tempDirectory(dir)));
        app.post("/up", (req, res) -> upload(req, res, dir));
        return app;
    }

    /** One part whose Content-Disposition line is {@code lineLength} bytes, plus {@code extra} header lines. */
    private static String bodyWithHeaderLine(int lineLength, String... extra) {
        String prefix = "Content-Disposition: form-data; name=\"a\"; x=\"";
        StringBuilder sb = new StringBuilder("--" + B + "\r\n")
                .append(prefix).append(RawTargetSocketTest.Http.repeat('h', lineLength - prefix.length() - 1)).append("\"\r\n");
        for (String e : extra) {
            sb.append(e).append("\r\n");
        }
        return sb.append("\r\nv\r\n--").append(B).append("--\r\n").toString();
    }

    private static String reply(int status, String body) {
        return status + " " + body.replaceAll("\"requestId\":\"[^\"]*\"", "\"requestId\":\"*\"");
    }

    private String inMemory(String body) {
        return inMemory(MP, body, DEFAULTS);
    }

    private String inMemory(String contentType, String body, Configurer<MultipartOptions> config) {
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        TestApp t = TestApp.create(adapter);
        configure(t.app(), tmp.getRoot(), config);
        t.start();
        try {
            byte[] bytes = body.getBytes(StandardCharsets.ISO_8859_1);
            ClientResponse r = adapter.send(ClientRequest.of("POST", "/up")
                    .header("Host", "x").header("Content-Type", contentType)
                    .header("Content-Length", Integer.toString(bytes.length)).body(bytes));
            return reply(r.status(), new String(r.body(), StandardCharsets.UTF_8));
        } finally {
            t.app().shutdown(1, TimeUnit.SECONDS);
        }
    }


    /** Writes the request head and {@code body} parts to a TinyHttpd app and returns "status body". */
    private static String socket(int port, long length, BodyWriter body) throws IOException {
        return socket(port, MP, length, body);
    }

    private static String socket(int port, String contentType, long length, BodyWriter body) throws IOException {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            s.setSoTimeout(60000);
            OutputStream out = s.getOutputStream();
            String head = "POST /up HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: " + contentType
                    + "\r\nContent-Length: " + length + "\r\nConnection: close\r\n\r\n";
            try {
                out.write(head.getBytes(StandardCharsets.ISO_8859_1));
                body.write(out);
                out.flush();
            } catch (SocketException e) {
                // The core may answer 413 and close before the whole body went out; read what it sent.
            }
            String response = RawTargetSocketTest.Http.readAll(s.getInputStream());
            int status = RawTargetSocketTest.Http.status(response);
            String payload = RawTargetSocketTest.Http.body(response);
            return reply(status, payload);
        }
    }

    interface BodyWriter {
        void write(OutputStream out) throws IOException;
    }

    private RestApp tinyApp() {
        return tinyApp(DEFAULTS);
    }

    private RestApp tinyApp(Configurer<MultipartOptions> config) {
        RestApp app = RestApp.builder().httpAdapter(new TinyHttpdAdapter()).jsonCodec(new StubJsonCodec())
                .logger(new RecordingLogger()).build();
        return configure(app, tmp.getRoot(), config);
    }

    private String overSocket(String body) throws IOException {
        return overSocket(MP, body, DEFAULTS);
    }

    private String overSocket(String contentType, String body, Configurer<MultipartOptions> config)
            throws IOException {
        RestApp app = tinyApp(config);
        int port = app.listen(0).port();
        try {
            byte[] bytes = body.getBytes(StandardCharsets.ISO_8859_1);
            return socket(port, contentType, bytes.length, out -> out.write(bytes));
        } finally {
            app.shutdown(1, TimeUnit.SECONDS);
        }
    }

    // FR-MULTIPART-001 AC-4
    // SDS-AC-4
    @Test
    public void sdsAc4_FR_MULTIPART_001_AC4_partHeaderLimitsGiveTheSameResultsOnBothAdapters() throws IOException {
        String[] bodies = {
            bodyWithHeaderLine(8192),
            bodyWithHeaderLine(8193),
            bodyWithHeaderLine(8000, "X-A: " + RawTargetSocketTest.Http.repeat('a', 8187), "X-B: " + RawTargetSocketTest.Http.repeat('b', 200)),
        };
        List<String> memory = new ArrayList<>();
        List<String> socket = new ArrayList<>();
        for (String body : bodies) {
            memory.add(inMemory(body));
            socket.add(overSocket(body));
        }
        assertTrue(memory.get(0), memory.get(0).startsWith("200 a=1;"));
        assertTrue(memory.get(1), memory.get(1).startsWith("413 "));
        assertTrue(memory.get(2), memory.get(2).startsWith("413 "));
        assertEquals(memory, socket);
        assertEquals(0, tmp.getRoot().list().length);
    }

    private static void writeRun(OutputStream out, char c, long n) throws IOException {
        byte[] chunk = new byte[65536];
        Arrays.fill(chunk, (byte) c);
        while (n > 0) {
            int k = (int) Math.min(chunk.length, n);
            out.write(chunk, 0, k);
            n -= k;
        }
    }

    /** A body of {@code total} bytes: a file part of 32 MiB and a second file part filling the rest. */
    private static String sendLarge(int port, long total) throws IOException {
        byte[] headA = ("--" + B + "\r\nContent-Disposition: form-data; name=\"a\"; filename=\"a.bin\"\r\n\r\n")
                .getBytes(StandardCharsets.ISO_8859_1);
        byte[] headB = ("\r\n--" + B + "\r\nContent-Disposition: form-data; name=\"b\"; filename=\"b.bin\"\r\n\r\n")
                .getBytes(StandardCharsets.ISO_8859_1);
        byte[] tail = ("\r\n--" + B + "--\r\n").getBytes(StandardCharsets.ISO_8859_1);
        long fileA = 33554432L;
        long fileB = total - headA.length - fileA - headB.length - tail.length;
        return socket(port, total, out -> {
            out.write(headA);
            writeRun(out, 'a', fileA);
            out.write(headB);
            writeRun(out, 'b', fileB);
            out.write(tail);
        });
    }

    // FR-MULTIPART-001 AC-5
    // FR-MULTIPART-002 AC-2
    // SDS-AC-7
    @Test
    public void sdsAc7_FR_MULTIPART_001_AC5_sixtyFourMebibytesPassOverTinyHttpdAndOneByteMoreGives413()
            throws IOException {
        RestApp app = tinyApp();
        int port = app.listen(0).port();
        try {
            String ok = sendLarge(port, 67108864L);
            assertTrue(ok, ok.startsWith("200 a=33554432;b="));
            String over = sendLarge(port, 67108865L);
            assertTrue(over, over.startsWith("413 "));
        } finally {
            app.shutdown(1, TimeUnit.SECONDS);
        }
        assertEquals(0, tmp.getRoot().list().length);
    }

    private static String part(String name, @Nullable String filename, String content) {
        return "--" + B + "\r\nContent-Disposition: form-data; name=\"" + name + "\""
                + (filename == null ? "" : "; filename=\"" + filename + "\"") + "\r\n\r\n" + content + "\r\n";
    }

    private static String close() {
        return "--" + B + "--\r\n";
    }

    private static final String E413 = "413 {\"error\":{\"code\":\"PAYLOAD_TOO_LARGE\"";

    private static final String E400 = "400 {\"error\":{\"code\":\"BAD_REQUEST\"";

    /** Runs one case over both adapters, asserts equal results and an empty temp directory, returns the result. */
    private String both(String contentType, String body, Configurer<MultipartOptions> config) throws IOException {
        String memory = inMemory(contentType, body, config);
        String socket = overSocket(contentType, body, config);
        assertEquals(memory, socket);
        assertEquals(0, tmp.getRoot().list().length);
        return memory;
    }

    // FR-MULTIPART-001 AC-1 (T13, T14): fields, files and a spooled part through the accessors on both adapters
    @Test
    public void FR_MULTIPART_001_AC1_accessorsGiveTheSameResultsOnBothAdapters() throws IOException {
        String mixed = part("t", null, "Hello") + part("photos", "a.png", "A") + part("f", "d.txt", "D")
                + part("photos", "b.png", "B") + close();
        assertEquals("200 t=5;photos=1;f=1;photos=1;form t=Hello;file f=d.txt/1 spooled=0;",
                both(MP, mixed, DEFAULTS));
        String spooled = part("f", "big.bin", RawTargetSocketTest.Http.repeat('x', 2000)) + close();
        assertEquals("200 f=2000;file f=big.bin/2000 spooled=1;", both(MP, spooled, o -> o.memoryThreshold(1024)));
    }

    // FR-MULTIPART-001 AC-1 (T15), FR-MULTIPART-002 AC-1: limits answer 413 with the standard body on both adapters
    @Test
    public void FR_MULTIPART_001_AC1_limitsGiveTheSame413OnBothAdapters() throws IOException {
        String big = part("f", "a.bin", RawTargetSocketTest.Http.repeat('a', 1025)) + close();
        String r = both(MP, big, o -> o.maxFileSize(1024).memoryThreshold(100));
        assertTrue(r, r.startsWith(E413));
        StringBuilder parts = new StringBuilder();
        for (int i = 0; i < 21; i++) {
            parts.append(part("p" + i, null, "v"));
        }
        r = both(MP, parts + close(), DEFAULTS);
        assertTrue(r, r.startsWith(E413));
    }

    // FR-MULTIPART-001 AC-1, FR-MULTIPART-001 AC-2: malformed bodies answer 400 with the standard body on both
    @Test
    public void FR_MULTIPART_001_AC2_malformedBodiesGiveTheSame400OnBothAdapters() throws IOException {
        String body = part("a", null, "v") + close();
        for (String type : new String[] {"multipart/form-data", "multipart/form-data; boundary=a<b"}) {
            String r = both(type, body, DEFAULTS);
            assertTrue(type + ": " + r, r.startsWith(E400));
        }
        String truncated = both(MP, part("a", null, "v"), DEFAULTS);
        assertTrue(truncated, truncated.startsWith(E400));
    }
}
