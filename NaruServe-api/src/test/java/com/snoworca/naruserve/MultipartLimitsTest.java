package com.snoworca.naruserve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.internal.DefaultUploadFile;
import com.snoworca.naruserve.internal.FormBodyReader;
import com.snoworca.naruserve.internal.MultipartParser;
import com.snoworca.naruserve.internal.MultipartSettings;
import com.snoworca.naruserve.internal.SpoolRegistry;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.StubJsonCodec;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Multipart and urlencoded size limits. SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-6-2.sds.md. */
public class MultipartLimitsTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    private static final String B = "lim";

    /** Counts the bytes taken from the wrapped stream. */
    static final class Counting extends InputStream {
        final InputStream in;
        long count;

        Counting(InputStream in) {
            this.in = in;
        }

        @Override
        public int read() throws IOException {
            int r = in.read();
            if (r >= 0) {
                count++;
            }
            return r;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = in.read(b, off, len);
            if (n > 0) {
                count += n;
            }
            return n;
        }
    }

    /** An endless run of 'x', as a chunked body that never ends. */
    static final class Endless extends InputStream {
        @Override
        public int read() {
            return 'x';
        }

        @Override
        public int read(byte[] b, int off, int len) {
            Arrays.fill(b, off, off + len, (byte) 'x');
            return len;
        }
    }

    private static String repeat(char c, int n) {
        char[] a = new char[n];
        Arrays.fill(a, c);
        return new String(a);
    }

    private static InputStream in(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static String text(String name, String value) {
        return "--" + B + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n";
    }

    private static String file(String name, String content) {
        return "--" + B + "\r\nContent-Disposition: form-data; name=\"" + name + "\"; filename=\"f.bin\"\r\n\r\n"
                + content + "\r\n";
    }

    private static String close() {
        return "--" + B + "--\r\n";
    }

    private MultipartSettings settings() {
        MultipartSettings s = new MultipartSettings();
        s.tempDirectory(tmp.getRoot());
        return s;
    }

    private static List<DefaultUploadFile> parse(InputStream body, long contentLength, MultipartSettings s)
            throws IOException {
        return MultipartParser.parse(body, contentLength, B, s, 1048576L, new SpoolRegistry());
    }

    private static void assert413(InputStream body, long contentLength, MultipartSettings s) throws IOException {
        try {
            parse(body, contentLength, s);
            fail("parsed a body over a limit");
        } catch (HttpException e) {
            assertEquals(413, e.status());
        }
    }

    // FR-MULTIPART-001 AC-4
    // SDS-AC-4
    @Test
    public void sdsAc4_FR_MULTIPART_001_AC4_headerLineOf8192IsAcceptedAnd8193Gives413() throws IOException {
        String prefix = "Content-Disposition: form-data; name=\"a\"; x=\"";
        String line8192 = prefix + repeat('h', 8192 - prefix.length() - 1) + "\"";
        assertEquals(8192, line8192.length());
        String ok = "--" + B + "\r\n" + line8192 + "\r\n\r\nv\r\n" + close();
        assertEquals(1, parse(in(ok), -1, settings()).size());
        String line8193 = prefix + repeat('h', 8193 - prefix.length() - 1) + "\"";
        assert413(in("--" + B + "\r\n" + line8193 + "\r\n\r\nv\r\n" + close()), -1, settings());
    }

    // FR-MULTIPART-001 AC-4
    // SDS-AC-4
    @Test
    public void sdsAc4_FR_MULTIPART_001_AC4_headerTotalAbove16384Gives413AndCrlfIsNotCounted() throws IOException {
        String disposition = "Content-Disposition: form-data; name=\"a\"";
        String filler = "X-Fill: " + repeat('f', 8192 - 8);
        // disposition + filler + rest = exactly 16384 bytes of line content, CRLFs excluded.
        String rest = "X-Rest: " + repeat('r', 16384 - disposition.length() - filler.length() - 8);
        String ok = "--" + B + "\r\n" + disposition + "\r\n" + filler + "\r\n" + rest + "\r\n\r\nv\r\n" + close();
        assertEquals(1, parse(in(ok), -1, settings()).size());
        String over = "--" + B + "\r\n" + disposition + "\r\n" + filler + "\r\n" + rest + "r\r\n\r\nv\r\n" + close();
        assert413(in(over), -1, settings());
    }

    // FR-MULTIPART-002 AC-1
    // SDS-AC-5
    @Test
    public void sdsAc5_FR_MULTIPART_002_AC1_twentyPartsAreAcceptedAndTheTwentyFirstGives413BeforeItsHeaders()
            throws IOException {
        StringBuilder twenty = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            twenty.append(text("p" + i, "v"));
        }
        assertEquals(20, parse(in(twenty + close()), -1, settings()).size());
        // The 21st part has no Content-Disposition: reading its headers would give 400, the count gives 413 first.
        String body = twenty + "--" + B + "\r\nContent-Type: text/plain\r\n\r\nv\r\n" + close();
        assert413(in(body), -1, settings());
    }

    // FR-MULTIPART-002 AC-1
    // SDS-AC-6
    @Test
    public void sdsAc6_FR_MULTIPART_002_AC1_fileOf1024IsAcceptedAnd1025Gives413OnTheFirstByteAbove()
            throws IOException {
        MultipartSettings s = settings();
        s.maxFileSize(1024);
        List<DefaultUploadFile> ok = parse(in(file("f", repeat('a', 1024)) + close()), -1, s);
        assertEquals(1024, ok.get(0).size());
        ok.get(0).release();
        String head = "--" + B + "\r\nContent-Disposition: form-data; name=\"f\"; filename=\"f.bin\"\r\n\r\n";
        // The part never ends: only the 1025th content byte can stop the parse with 413.
        Counting src = new Counting(new SequenceInputStream(in(head), new Endless()));
        assert413(src, -1, s);
        assertTrue("read " + src.count, src.count <= head.length() + 1025 + 8192);
        assertEquals(0, tmp.getRoot().list().length);
    }

    // FR-MULTIPART-002 AC-1
    // SDS-AC-6
    @Test
    public void sdsAc6_FR_MULTIPART_002_AC1_textPartsDoNotCountAgainstMaxFileSize() throws IOException {
        MultipartSettings s = settings();
        s.maxFileSize(1024);
        List<DefaultUploadFile> parts = parse(in(text("t", repeat('t', 5000)) + close()), -1, s);
        assertEquals(5000, parts.get(0).size());
    }

    // FR-MULTIPART-002 AC-1
    @Test
    public void FR_MULTIPART_002_AC1_maxRequestSize4096RejectsALargerBody() throws IOException {
        MultipartSettings s = settings();
        s.maxRequestSize(4096);
        String small = text("a", repeat('a', 3000)) + close();
        assertEquals(1, parse(in(small), small.length(), s).size());
        String big = text("a", repeat('a', 4100)) + close();
        assert413(in(big), -1, s);
    }

    // FR-MULTIPART-002 AC-2
    // SDS-AC-7
    @Test
    public void sdsAc7_FR_MULTIPART_002_AC2_chunkedBodyOverMaxRequestSizeStopsWithinOneReadBuffer()
            throws IOException {
        MultipartSettings s = settings();
        s.maxRequestSize(4096);
        String head = "--" + B + "\r\nContent-Disposition: form-data; name=\"f\"; filename=\"f.bin\"\r\n\r\n";
        Counting src = new Counting(new SequenceInputStream(in(head), new Endless()));
        assert413(src, -1, s);
        assertTrue("read " + src.count, src.count <= 4096 + 8192);
        assertEquals(0, tmp.getRoot().list().length);
    }

    // FR-MULTIPART-002 AC-2
    // SDS-AC-7
    @Test
    public void sdsAc7_FR_MULTIPART_002_AC2_contentLengthAboveMaxRequestSizeGives413BeforeAnyRead()
            throws IOException {
        MultipartSettings s = settings();
        s.maxRequestSize(4096);
        String body = text("a", "v") + close();
        Counting src = new Counting(in(body));
        assert413(src, 4097, s);
        assertEquals(0, src.count);
    }

    // FR-MULTIPART-002 AC-3
    // SDS-AC-8
    @Test
    public void sdsAc8_FR_MULTIPART_002_AC3_urlencodedBodyOfOneMebibyteParsesAndOneByteMoreGives413()
            throws IOException {
        String ok = "a=" + repeat('x', 1048576 - 2);
        Map<String, List<String>> form = FormBodyReader.read(in(ok), ok.length(), 1048576L);
        assertEquals(1048574, form.get("a").get(0).length());
        assertEquals(1048574, FormBodyReader.read(in(ok), -1, 1048576L).get("a").get(0).length());
        String over = ok + "x";
        for (long declared : new long[] {over.length(), -1}) {
            try {
                FormBodyReader.read(in(over), declared, 1048576L);
                fail("read an urlencoded body over formLimit, declared " + declared);
            } catch (HttpException e) {
                assertEquals(413, e.status());
            }
        }
    }

    // FR-MULTIPART-002 AC-3
    // SDS-AC-8
    @Test
    public void sdsAc8_FR_MULTIPART_002_AC3_urlencodedPairsAreDecodedInOrder() throws IOException {
        Map<String, List<String>> form = FormBodyReader.read(in("name=Kim+Lee&name=x&&a&city=%EC%84%9C"), -1, 1048576L);
        assertEquals(Arrays.asList("Kim Lee", "x"), form.get("name"));
        assertEquals(Collections.singletonList(""), form.get("a"));
        assertEquals("서", form.get("city").get(0));
        assertEquals(Arrays.asList("name", "a", "city"), new java.util.ArrayList<>(form.keySet()));
        try {
            FormBodyReader.read(in("a=%G1"), -1, 1048576L);
            fail("malformed percent-encoding accepted");
        } catch (HttpException e) {
            assertEquals(400, e.status());
        }
    }

    /**
     * Posts {@code body} with {@code contentType} to an app limited by {@code config}; the handler reads
     * req.parts() and onError records the HttpException without answering, so the default answer goes out.
     */
    private ClientResponse viaApp(Configurer<MultipartOptions> config, String contentType, String body,
            List<Object> seen) {
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        RestApp app = RestApp.builder().httpAdapter(adapter).jsonCodec(new StubJsonCodec()).build();
        app.multipart(o -> config.configure(o.tempDirectory(tmp.getRoot())));
        app.onError((error, req, res) -> seen.add("onError " + ((HttpException) error).status()));
        app.post("/up", (req, res) -> {
            try {
                req.parts();
            } catch (HttpException e) {
                seen.add("accessor " + e.status());
                throw e;
            }
            res.send("ok");
        });
        app.listen(0);
        try {
            byte[] b = body.getBytes(StandardCharsets.ISO_8859_1);
            return adapter.send(ClientRequest.of("POST", "/up").header("Content-Type", contentType)
                    .header("Content-Length", String.valueOf(b.length)).body(b));
        } finally {
            app.shutdown(5, TimeUnit.SECONDS);
        }
    }

    private void assertAnswered(int status, String code, Configurer<MultipartOptions> config, String contentType,
            String body) {
        List<Object> seen = new ArrayList<>();
        ClientResponse r = viaApp(config, contentType, body, seen);
        String text = new String(r.body(), StandardCharsets.UTF_8);
        assertEquals(text, status, r.status());
        assertTrue(text, text.startsWith("{\"error\":{\"code\":\"" + code + "\""));
        assertEquals(Arrays.<Object>asList("accessor " + status, "onError " + status), seen);
        assertEquals(0, tmp.getRoot().list().length);
    }

    // FR-MULTIPART-002 AC-1, FR-MULTIPART-001 AC-4: every 413 limit answers the standard body through onError
    @Test
    public void FR_MULTIPART_002_AC1_limitsAnswer413StandardBodyThroughOnError() {
        String mp = "multipart/form-data; boundary=" + B;
        Configurer<MultipartOptions> none = o -> { };
        assertAnswered(413, "PAYLOAD_TOO_LARGE", o -> o.maxFileSize(1024).memoryThreshold(100), mp,
                file("f", repeat('a', 1025)) + close());
        assertAnswered(413, "PAYLOAD_TOO_LARGE", o -> o.maxRequestSize(4096), mp,
                text("a", repeat('a', 4100)) + close());
        StringBuilder parts = new StringBuilder();
        for (int i = 0; i < 21; i++) {
            parts.append(text("p" + i, "v"));
        }
        assertAnswered(413, "PAYLOAD_TOO_LARGE", none, mp, parts + close());
        String prefix = "Content-Disposition: form-data; name=\"a\"; x=\"";
        String line8193 = prefix + repeat('h', 8193 - prefix.length() - 1) + "\"";
        assertAnswered(413, "PAYLOAD_TOO_LARGE", none, mp, "--" + B + "\r\n" + line8193 + "\r\n\r\nv\r\n" + close());
        String filler = "X-Fill: " + repeat('f', 8000);
        assertAnswered(413, "PAYLOAD_TOO_LARGE", none, mp, "--" + B + "\r\nContent-Disposition: form-data; name=\"a\"\r\n"
                + filler + "\r\n" + filler + "\r\n" + filler + "\r\n\r\nv\r\n" + close());
    }

    // FR-MULTIPART-001 AC-2: a bad boundary or a missing delimiter answers 400 with the standard body through onError
    @Test
    public void FR_MULTIPART_001_AC2_malformedBodyAnswers400StandardBodyThroughOnError() {
        Configurer<MultipartOptions> none = o -> { };
        String body = text("a", "v") + close();
        assertAnswered(400, "BAD_REQUEST", none, "multipart/form-data", body);
        assertAnswered(400, "BAD_REQUEST", none, "multipart/form-data; boundary=", body);
        assertAnswered(400, "BAD_REQUEST", none, "multipart/form-data; boundary=" + repeat('b', 71), body);
        assertAnswered(400, "BAD_REQUEST", none, "multipart/form-data; boundary=a<b", body);
        String mp = "multipart/form-data; boundary=" + B;
        assertAnswered(400, "BAD_REQUEST", none, mp, "no delimiter here");
        assertAnswered(400, "BAD_REQUEST", none, mp, text("a", "v"));
    }
    // IR-MULTIPART-001: the dispatcher keeps the settings taken at listen; a retained configurer argument changes nothing
    @Test
    public void IR_MULTIPART_001_retainedOptionsChangedAfterListenHaveNoEffect() {
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        RestApp app = RestApp.builder().httpAdapter(adapter).jsonCodec(new StubJsonCodec()).build();
        MultipartOptions[] saved = new MultipartOptions[1];
        app.multipart(o -> saved[0] = o.tempDirectory(tmp.getRoot()));
        app.post("/up", (req, res) -> res.send(String.valueOf(req.parts().size())));
        app.listen(0);
        try {
            saved[0].maxParts(1);
            byte[] b = (text("a", "1") + text("b", "2") + close()).getBytes(StandardCharsets.ISO_8859_1);
            ClientResponse r = adapter.send(ClientRequest.of("POST", "/up")
                    .header("Content-Type", "multipart/form-data; boundary=" + B)
                    .header("Content-Length", String.valueOf(b.length)).body(b));
            assertEquals(new String(r.body(), StandardCharsets.UTF_8), 200, r.status());
        } finally {
            app.shutdown(5, TimeUnit.SECONDS);
        }
    }
}
