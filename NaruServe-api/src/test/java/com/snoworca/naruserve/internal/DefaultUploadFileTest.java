package com.snoworca.naruserve.internal;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.UploadFile;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * UploadFile content methods of a part built by SpoolingBuffer: save, transferTo, the bytes() limit and use after
 * release or close. SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-6-4.sds.md.
 */
public class DefaultUploadFileTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    private final SpoolRegistry spool = new SpoolRegistry();

    private static byte[] content(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) (i * 31 + 7);
        }
        return b;
    }

    /** A part of {@code content} through a SpoolingBuffer with {@code threshold}; spooled when above it. */
    private DefaultUploadFile part(byte[] content, int threshold) throws IOException {
        SpoolingBuffer buf = new SpoolingBuffer(threshold, tmp.getRoot(), spool);
        buf.write(content, 0, content.length);
        return buf.toUpload("doc", "../../evil.sh", "text/plain");
    }

    private static byte[] read(File f) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) >= 0) {
                out.write(b, 0, n);
            }
        }
        return out.toByteArray();
    }

    private static int temps(File dir) {
        int n = 0;
        for (String s : dir.list()) {
            if (s.startsWith("naru-upload-")) {
                n++;
            }
        }
        return n;
    }

    private interface ContentCall {
        void run(UploadFile f) throws Exception;
    }

    /** Every content method throws IllegalStateException; the metadata stays. */
    private static void assertContentGone(UploadFile f, long size) throws Exception {
        File other = File.createTempFile("dest", ".bin");
        other.deleteOnExit();
        ContentCall[] calls = {
            UploadFile::inputStream, UploadFile::bytes, x -> x.save(other),
            x -> x.transferTo(new ByteArrayOutputStream()),
        };
        for (ContentCall c : calls) {
            try {
                c.run(f);
                fail("content method did not throw");
            } catch (IllegalStateException expected) {
                // the content was released
            }
        }
        assertEquals("doc", f.name());
        assertEquals("../../evil.sh", f.filename());
        assertEquals("text/plain", f.contentType());
        assertEquals(size, f.size());
    }

    // FR-MULTIPART-005 AC-1, FR-MULTIPART-005 AC-5
    @Test
    public void sdsAc3_FR_MULTIPART_005_AC1_saveReplacesDestinationThenDeletesTempFile() throws Exception {
        byte[] c = content(5000);
        File dest = new File(tmp.newFolder("out"), "dest.bin");
        try (OutputStream o = new FileOutputStream(dest)) {
            o.write(content(20000)); // longer than the upload, so a non-truncating write would leave a tail
        }
        DefaultUploadFile spooled = part(c, 1024);
        assertEquals(1, temps(tmp.getRoot()));
        spooled.save(dest);
        assertArrayEquals(c, read(dest));
        assertEquals(0, temps(tmp.getRoot()));
        assertContentGone(spooled, 5000);

        DefaultUploadFile memory = part(c, 65536);
        File dest2 = new File(tmp.getRoot(), "dest2.bin");
        memory.save(dest2);
        assertArrayEquals(c, read(dest2));
        assertContentGone(memory, 5000);
    }

    // FR-MULTIPART-005 AC-2
    @Test
    public void FR_MULTIPART_005_AC2_transferToCopiesAndNeverClosesTarget() throws Exception {
        byte[] c = content(3000);
        for (int threshold : new int[] {100, 65536}) {
            final boolean[] closed = {false};
            ByteArrayOutputStream out = new ByteArrayOutputStream() {
                @Override
                public void close() {
                    closed[0] = true;
                }
            };
            DefaultUploadFile f = part(c, threshold);
            f.transferTo(out);
            assertArrayEquals(c, out.toByteArray());
            assertFalse(closed[0]);
            f.release();
        }
    }

    // FR-MULTIPART-005 AC-3, IR-MULTIPART-002 AC-4
    @Test
    public void FR_MULTIPART_005_AC3_bytesLimitIs8MiB() throws Exception {
        assertEquals(8388608L, DefaultUploadFile.BYTES_LIMIT);
        DefaultUploadFile at = part(new byte[8388608], 65536);
        assertEquals(8388608, at.bytes().length);
        assertEquals(8388608L, at.size());
        at.release();
        DefaultUploadFile above = part(new byte[8388609], 65536);
        try {
            above.bytes();
            fail("bytes() above the limit did not throw");
        } catch (IllegalStateException e) {
            String m = String.valueOf(e.getMessage());
            assertTrue(m, m.contains("8388609") && m.contains("8388608"));
        }
        assertEquals(8388609L, above.size());
        above.release();
    }

    // FR-MULTIPART-005 AC-4
    @Test
    public void sdsAc4_FR_MULTIPART_005_AC4_releaseClosesHandedOutStreams() throws Exception {
        byte[] c = content(4000);
        for (int threshold : new int[] {100, 65536}) {
            DefaultUploadFile f = part(c, threshold);
            InputStream in = f.inputStream();
            assertEquals(c[0] & 0xff, in.read());
            f.release();
            try {
                in.read();
                fail("read after the request ended did not throw");
            } catch (IOException expected) {
                // the stream was closed at request end
            }
            assertContentGone(f, 4000);
            assertEquals(0, temps(tmp.getRoot()));
            f.release(); // idempotent
        }
    }

    // FR-MULTIPART-005 AC-4, FR-MULTIPART-005 AC-5: a refused save leaves the destination untouched
    @Test
    public void FR_MULTIPART_005_AC5_refusedSaveNeverTruncatesDestination() throws Exception {
        byte[] c = content(3000);
        for (int threshold : new int[] {100, 65536}) {
            File dest = tmp.newFile();
            DefaultUploadFile f = part(c, threshold);
            f.save(dest);
            try {
                f.save(dest);
                fail("second save did not throw");
            } catch (IllegalStateException expected) {
                // the content was released by the first save
            }
            assertArrayEquals(c, read(dest));
            DefaultUploadFile ended = part(c, threshold);
            ended.release();
            try {
                ended.save(dest);
                fail("save after the request ended did not throw");
            } catch (IllegalStateException expected) {
                // the request ended
            }
            assertArrayEquals(c, read(dest));
        }
    }

    // FR-MULTIPART-005 AC-5
    @Test
    public void sdsAc5_FR_MULTIPART_005_AC5_closeReleasesContentAndKeepsMetadata() throws Exception {
        for (int threshold : new int[] {100, 65536}) {
            DefaultUploadFile f = part(content(2000), threshold);
            f.close();
            assertEquals(0, temps(tmp.getRoot()));
            assertContentGone(f, 2000);
            f.close();
        }
        assertEquals(Arrays.asList(), Arrays.asList(tmp.getRoot().list()));
    }
}
