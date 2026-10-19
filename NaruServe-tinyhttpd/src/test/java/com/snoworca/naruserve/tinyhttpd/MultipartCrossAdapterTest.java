package com.snoworca.naruserve.tinyhttpd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.Request;
import com.snoworca.naruserve.Response;
import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.internal.DefaultRequest;
import com.snoworca.naruserve.internal.DefaultUploadFile;
import com.snoworca.naruserve.internal.MultipartParser;
import com.snoworca.naruserve.internal.MultipartSettings;
import com.snoworca.naruserve.internal.SpoolRegistry;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.testing.StubJsonCodec;
import com.snoworca.naruserve.testing.TestApp;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The one core multipart parser over the in-memory adapter and a TinyHttpd socket. The handler reaches the parser
 * the way the request accessors of a later fragment will: through the request's counted body stream.
 * SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-6-2.sds.md.
 */
public class MultipartCrossAdapterTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    private static final String B = "cross";

    /** Parses the body with the default limits, answers part names and sizes, then deletes the temp files. */
    private static void upload(Request req, Response res, File tempDir) throws Exception {
        Method stream = DefaultRequest.class.getDeclaredMethod("bodyStream");
        stream.setAccessible(true);
        InputStream body = (InputStream) stream.invoke(req);
        String cl = req.header("Content-Length");
        MultipartSettings s = new MultipartSettings();
        s.tempDirectory(tempDir);
        List<DefaultUploadFile> parts = MultipartParser.parse(body, cl == null ? -1 : Long.parseLong(cl),
                MultipartParser.boundary(req.header("Content-Type")), s, 1048576L, new SpoolRegistry());
        StringBuilder sb = new StringBuilder();
        for (DefaultUploadFile p : parts) {
            sb.append(p.name()).append('=').append(p.size()).append(';');
            p.release();
        }
        res.send(sb.toString());
    }

    private static String repeat(char c, int n) {
        char[] a = new char[n];
        Arrays.fill(a, c);
        return new String(a);
    }

    /** One part whose Content-Disposition line is {@code lineLength} bytes, plus {@code extra} header lines. */
    private static String bodyWithHeaderLine(int lineLength, String... extra) {
        String prefix = "Content-Disposition: form-data; name=\"a\"; x=\"";
        StringBuilder sb = new StringBuilder("--" + B + "\r\n")
                .append(prefix).append(repeat('h', lineLength - prefix.length() - 1)).append("\"\r\n");
        for (String e : extra) {
            sb.append(e).append("\r\n");
        }
        return sb.append("\r\nv\r\n--").append(B).append("--\r\n").toString();
    }

    private static String reply(int status, String body) {
        return status + " " + body.replaceAll("\"requestId\":\"[^\"]*\"", "\"requestId\":\"*\"");
    }

    private String inMemory(String body) {
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        TestApp t = TestApp.create(adapter);
        File dir = tmp.getRoot();
        t.app().post("/up", (req, res) -> upload(req, res, dir));
        t.start();
        try {
            byte[] bytes = body.getBytes(StandardCharsets.ISO_8859_1);
            ClientResponse r = adapter.send(ClientRequest.of("POST", "/up")
                    .header("Host", "x").header("Content-Type", "multipart/form-data; boundary=" + B)
                    .header("Content-Length", Integer.toString(bytes.length)).body(bytes));
            return reply(r.status(), new String(r.body(), StandardCharsets.UTF_8));
        } finally {
            t.app().shutdown(1, TimeUnit.SECONDS);
        }
    }


    /** Writes the request head and {@code body} parts to a TinyHttpd app and returns "status body". */
    private static String socket(int port, long length, BodyWriter body) throws IOException {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            s.setSoTimeout(60000);
            OutputStream out = s.getOutputStream();
            String head = "POST /up HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: multipart/form-data; boundary=" + B
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
            String payload = response.substring(response.indexOf("\r\n\r\n") + 4);
            return reply(status, payload);
        }
    }

    interface BodyWriter {
        void write(OutputStream out) throws IOException;
    }

    private RestApp tinyApp() {
        RestApp app = RestApp.builder().httpAdapter(new TinyHttpdAdapter()).jsonCodec(new StubJsonCodec())
                .logger(new RecordingLogger()).build();
        File dir = tmp.getRoot();
        app.post("/up", (req, res) -> upload(req, res, dir));
        return app;
    }

    private String overSocket(String body) throws IOException {
        RestApp app = tinyApp();
        int port = app.listen(0).port();
        try {
            byte[] bytes = body.getBytes(StandardCharsets.ISO_8859_1);
            return socket(port, bytes.length, out -> out.write(bytes));
        } finally {
            app.shutdown(1, TimeUnit.SECONDS);
        }
    }

    // FR-MULTIPART-001 AC-4
    @Test
    public void sdsAc4_FR_MULTIPART_001_AC4_partHeaderLimitsGiveTheSameResultsOnBothAdapters() throws IOException {
        String[] bodies = {
            bodyWithHeaderLine(8192),
            bodyWithHeaderLine(8193),
            bodyWithHeaderLine(8000, "X-A: " + repeat('a', 8187), "X-B: " + repeat('b', 200)),
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
}
