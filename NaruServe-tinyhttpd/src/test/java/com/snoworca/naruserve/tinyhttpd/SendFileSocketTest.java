package com.snoworca.naruserve.tinyhttpd;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap.SimpleEntry;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** TinyHttpd sendFile hook. SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-6-9.sds.md. */
public class SendFileSocketTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    private final TinyHttpdAdapter adapter = new TinyHttpdAdapter();

    @After
    public void stop() {
        RawTargetSocketTest.Http.stopQuietly(adapter);
    }

    private File thousandBytes() throws IOException {
        File f = tmp.newFile("data.bin");
        byte[] b = new byte[1000];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) i;
        }
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(b);
        }
        return f;
    }

    private static List<Map.Entry<String, String>> contentLength(long n) {
        return Collections.<Map.Entry<String, String>>singletonList(new SimpleEntry<>("Content-Length", "" + n));
    }

    private static String send(TinyHttpdAdapter a, String path) throws IOException {
        return RawTargetSocketTest.Http.send(a.boundPort(), RawTargetSocketTest.Http.get(path));
    }

    // SDS-AC-3; FR-ENGINE-010 AC-2
    @Test(timeout = 20000)
    public void sdsAc3_FR_ENGINE_010_AC2_sendFileWritesExactlyTheRequestedRangeAndCommitsOnce() throws Exception {
        File f = thousandBytes();
        AtomicReference<String> after = new AtomicReference<>();
        adapter.bind(RawTargetSocketTest.Http.settings().build(), ex -> {
            try {
                ex.sendFile(206, contentLength(50), f, 100, 50);
                try {
                    ex.commit(200, Collections.<Map.Entry<String, String>>emptyList(), 0);
                    after.set("second start allowed");
                } catch (IllegalStateException e) {
                    after.set("IllegalStateException");
                }
                ex.finish();
            } catch (IOException e) {
                after.set(String.valueOf(e));
                ex.abort();
            }
        });
        String response = send(adapter, "/file");
        assertEquals(206, RawTargetSocketTest.Http.status(response));
        assertEquals("50", RawTargetSocketTest.Http.header(response, "Content-Length"));
        byte[] body = RawTargetSocketTest.Http.body(response).getBytes(StandardCharsets.ISO_8859_1);
        byte[] expected = new byte[50];
        for (int i = 0; i < 50; i++) {
            expected[i] = (byte) (100 + i);
        }
        assertArrayEquals(expected, body);
        assertEquals("IllegalStateException", after.get());
    }

    // SDS-AC-1; FR-ENGINE-010 AC-2
    @Test(timeout = 20000)
    public void sdsAc1_FR_ENGINE_010_AC2_badOffsetOrLengthIsRefusedBeforeTheResponseStarts() throws Exception {
        File f = thousandBytes();
        AtomicReference<String> seen = new AtomicReference<>();
        adapter.bind(RawTargetSocketTest.Http.settings().build(), RawTargetSocketTest.Http.ok(ex -> {
            StringBuilder sb = new StringBuilder();
            long[][] bad = {{-1, 10}, {0, -1}, {990, 11}, {1001, 0}};
            for (long[] r : bad) {
                try {
                    ex.sendFile(200, contentLength(r[1]), f, r[0], r[1]);
                    sb.append("none,");
                } catch (IllegalArgumentException e) {
                    sb.append("IAE,");
                }
            }
            seen.set(sb.toString());
        }));
        // Http.ok commits after the refusals, so a 200 "ok" proves the response was still unstarted.
        String response = send(adapter, "/bad");
        assertEquals(200, RawTargetSocketTest.Http.status(response));
        assertEquals("ok", RawTargetSocketTest.Http.body(response));
        assertEquals("IAE,IAE,IAE,IAE,", seen.get());
    }

    // SDS-AC-2; FR-ENGINE-010 AC-2
    @Test(timeout = 20000)
    public void sdsAc2_FR_ENGINE_010_AC2_fileShrinkingDuringTheCopyThrowsIOExceptionAndAborts() throws Exception {
        File real = thousandBytes();
        // The file shrinks to 120 bytes right after sendFile has checked its length.
        File shrinking = new File(real.getPath()) {
            private static final long serialVersionUID = 1L;

            @Override
            public long length() {
                long n = super.length();
                try (RandomAccessFile raf = new RandomAccessFile(real, "rw")) {
                    raf.setLength(120);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
                return n;
            }
        };
        AtomicReference<String> seen = new AtomicReference<>();
        adapter.bind(RawTargetSocketTest.Http.settings().build(), ex -> {
            try {
                ex.sendFile(200, contentLength(1000), shrinking, 0, 1000);
                seen.set("no failure");
                ex.finish();
            } catch (IOException e) {
                seen.set("IOException");
                ex.abort();
            }
        });
        String response = send(adapter, "/shrink");
        assertEquals("IOException", seen.get());
        // The abort path never hands the client a complete 1000-byte 200: either the buffered head is replaced by
        // TinyHttpd's error response or the connection closes short of the declared length.
        boolean complete = RawTargetSocketTest.Http.status(response) == 200
                && RawTargetSocketTest.Http.body(response).length() >= 1000;
        assertTrue(response.substring(0, Math.min(200, response.length())), !complete);
    }

    /** The hook copies by plain buffered reads: no zero-copy FileChannel transfer in the module. */
    // FR-ENGINE-010 AC-2
    @Test
    public void FR_ENGINE_010_AC2_hookUsesNoZeroCopyTransfer() throws Exception {
        String text = AdapterSurfaceTest.classText(
                new File(TinyHttpdAdapter.class.getResource("TinyHttpdFeatures.class").toURI()));
        assertTrue(text.contains("java/io/RandomAccessFile"));
        for (String banned : Arrays.asList("transferTo", "transferFrom", "java/nio/channels/FileChannel")) {
            assertTrue(banned, !text.contains(banned));
        }
    }
}
