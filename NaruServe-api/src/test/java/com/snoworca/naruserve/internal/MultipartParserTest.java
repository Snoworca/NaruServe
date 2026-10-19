package com.snoworca.naruserve.internal;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.HttpException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** The engine-free multipart parser. SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-6-2.sds.md. */
public class MultipartParserTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    private static final String B = "XyZ";

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[4096];
        int n;
        while ((n = in.read(b)) > 0) {
            out.write(b, 0, n);
        }
        return out.toByteArray();
    }

    private MultipartSettings settings() {
        MultipartSettings s = new MultipartSettings();
        s.tempDirectory(tmp.getRoot());
        return s;
    }

    private List<DefaultUploadFile> parse(String body, MultipartSettings s) throws IOException {
        return MultipartParser.parse(new ByteArrayInputStream(bytes(body)), B, s, 1048576L, new SpoolRegistry());
    }

    private void assertStatus(int status, String body, MultipartSettings s) throws IOException {
        try {
            parse(body, s);
            fail("parsed a body that must give " + status);
        } catch (HttpException e) {
            assertEquals(status, e.status());
        }
    }

    // FR-MULTIPART-001 AC-2
    @Test
    public void sdsAc1_FR_MULTIPART_001_AC2_boundaryNameIsCaseInsensitiveAndQuotedValuesAreUnquoted() {
        assertEquals("abc", MultipartParser.boundary("multipart/form-data; BOUNDARY=abc"));
        assertEquals("abc", MultipartParser.boundary("multipart/form-data;boundary=abc;charset=utf-8"));
        assertEquals("a b'()+_,-./:=?", MultipartParser.boundary("multipart/form-data; boundary=\"a b'()+_,-./:=?\""));
        char[] seventy = new char[70];
        Arrays.fill(seventy, 'a');
        assertEquals(new String(seventy), MultipartParser.boundary("multipart/form-data; boundary=" + new String(seventy)));
    }

    // FR-MULTIPART-001 AC-2
    @Test
    public void sdsAc1_FR_MULTIPART_001_AC2_missingEmptyLongBadCharOrTrailingSpaceBoundaryIsNull() {
        char[] long71 = new char[71];
        Arrays.fill(long71, 'a');
        assertNull(MultipartParser.boundary(null));
        assertNull(MultipartParser.boundary("multipart/form-data"));
        assertNull(MultipartParser.boundary("multipart/form-data; boundary="));
        assertNull(MultipartParser.boundary("multipart/form-data; boundary=\"\""));
        assertNull(MultipartParser.boundary("multipart/form-data; boundary=" + new String(long71)));
        assertNull(MultipartParser.boundary("multipart/form-data; boundary=a@b"));
        assertNull(MultipartParser.boundary("multipart/form-data; boundary=\"abc \""));
        assertNull(MultipartParser.boundary("multipart/form-data; xboundary=abc"));
    }

    // FR-MULTIPART-001 AC-2
    @Test
    public void sdsAc2_FR_MULTIPART_001_AC2_partWithoutNameOrFormDataDispositionGives400() throws IOException {
        assertStatus(400, "--XyZ\r\nContent-Disposition: form-data\r\n\r\nv\r\n--XyZ--\r\n", settings());
        assertStatus(400, "--XyZ\r\nContent-Type: text/plain\r\n\r\nv\r\n--XyZ--\r\n", settings());
        assertStatus(400, "--XyZ\r\nContent-Disposition: attachment; name=\"a\"\r\n\r\nv\r\n--XyZ--\r\n", settings());
    }

    // FR-MULTIPART-001 AC-2
    @Test
    public void sdsAc2_FR_MULTIPART_001_AC2_missingFirstOrCloseDelimiterGives400() throws IOException {
        assertStatus(400, "no delimiter at all", settings());
        assertStatus(400, "--XyZ\r\nContent-Disposition: form-data; name=\"a\"\r\n\r\nv", settings());
        assertStatus(400, "--XyZ\r\nContent-Disposition: form-data; name=\"a\"\r\n\r\nv\r\n--XyZ\r\n", settings());
    }

    // FR-MULTIPART-001 AC-2
    @Test
    public void sdsAc2_FR_MULTIPART_001_AC2_truncatedSpooledPartLeavesNoTempFile() throws IOException {
        MultipartSettings s = settings();
        s.memoryThreshold(16);
        char[] big = new char[5000];
        Arrays.fill(big, 'x');
        String body = "--XyZ\r\nContent-Disposition: form-data; name=\"a\"; filename=\"f.bin\"\r\n\r\nfirst\r\n--XyZ\r\n"
                + "Content-Disposition: form-data; name=\"b\"; filename=\"g.bin\"\r\n\r\n" + new String(big);
        assertStatus(400, body, s);
        assertEquals(0, tmp.getRoot().list().length);
    }

    // FR-MULTIPART-001 AC-2
    @Test
    public void sdsAc3_FR_MULTIPART_001_AC2_preambleAndEpilogueAreIgnored() throws IOException {
        String body = "preamble line\r\nmore preamble -XyZ\r\n--XyZ\r\nContent-Disposition: form-data; name=\"a\"\r\n\r\n"
                + "one\r\n--XyZ\r\ncontent-disposition: form-data; name=\"f\"; filename=\"x.txt\"\r\nContent-Type: text/plain\r\n\r\n"
                + "two\r\n--XyZ--\r\nepilogue --XyZ\r\nContent-Disposition: form-data; name=\"z\"\r\n\r\nzz";
        List<DefaultUploadFile> parts = parse(body, settings());
        assertEquals(2, parts.size());
        assertEquals("a", parts.get(0).name());
        assertNull(parts.get(0).filename());
        assertArrayEquals(bytes("one"), readAll(parts.get(0).inputStream()));
        assertEquals("f", parts.get(1).name());
        assertEquals("x.txt", parts.get(1).filename());
        assertEquals("text/plain", parts.get(1).contentType());
        assertArrayEquals(bytes("two"), readAll(parts.get(1).inputStream()));
    }

    // FR-MULTIPART-001 AC-3
    @Test
    public void FR_MULTIPART_001_AC3_nestedMultipartPartIsOneRawPart() throws IOException {
        String nested = "--inner\r\nContent-Type: text/plain\r\n\r\nA\r\n--inner--";
        String body = "--XyZ\r\nContent-Disposition: form-data; name=\"m\"\r\nContent-Type: multipart/mixed\r\n\r\n"
                + nested + "\r\n--XyZ--";
        List<DefaultUploadFile> parts = parse(body, settings());
        assertEquals(1, parts.size());
        assertEquals("multipart/mixed", parts.get(0).contentType());
        assertArrayEquals(bytes(nested), readAll(parts.get(0).inputStream()));
    }

    // FR-MULTIPART-001 AC-2
    @Test
    public void sdsAc3_FR_MULTIPART_001_AC2_spooledFileContentIsKeptAcrossReadBuffers() throws IOException {
        MultipartSettings s = settings();
        s.memoryThreshold(100);
        byte[] content = new byte[30000];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) (i % 251);
        }
        String body = "--XyZ\r\nContent-Disposition: form-data; name=\"f\"; filename=\"b.bin\"\r\n\r\n"
                + new String(content, StandardCharsets.ISO_8859_1) + "\r\n--XyZ--\r\n";
        List<DefaultUploadFile> parts = parse(body, s);
        assertEquals(content.length, parts.get(0).size());
        assertArrayEquals(content, readAll(parts.get(0).inputStream()));
        File[] files = tmp.getRoot().listFiles();
        assertEquals(1, files.length);
        parts.get(0).release();
        assertEquals(0, tmp.getRoot().list().length);
    }
}
