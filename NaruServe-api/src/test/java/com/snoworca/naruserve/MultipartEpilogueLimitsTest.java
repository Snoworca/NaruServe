package com.snoworca.naruserve;

import static org.junit.Assert.*;

import com.snoworca.naruserve.internal.DefaultUploadFile;
import com.snoworca.naruserve.internal.MultipartParser;
import com.snoworca.naruserve.internal.MultipartSettings;
import com.snoworca.naruserve.internal.SpoolRegistry;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** FR-MULTIPART-002 AC-2: ignored epilogue bytes remain subject to the whole-request bound. */
public class MultipartEpilogueLimitsTest {
    private static final int LIMIT = 16384;
    @Rule public final TemporaryFolder tmp = new TemporaryFolder();

    private MultipartSettings settings() {
        MultipartSettings settings = new MultipartSettings();
        settings.maxRequestSize(LIMIT).memoryThreshold(16).tempDirectory(tmp.getRoot());
        return settings;
    }

    private static String prefix() {
        return "--b\r\nContent-Disposition: form-data; name=\"f\"; filename=\"x.bin\"\r\n\r\n"
                + MultipartLimitsTest.repeat('f', 4096) + "\r\n--b--\r\n";
    }

    private static ByteArrayInputStream input(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.ISO_8859_1));
    }

    private List<DefaultUploadFile> parse(InputStream body, long declared) throws Exception {
        return MultipartParser.parse(body, declared, "b", settings(), 1048576, new SpoolRegistry());
    }

    // FR-MULTIPART-002 AC-2: overflow in epilogue rejects, reads only limit+1, and releases preceding spooled files.
    @Test public void largeUnknownLengthEpilogueRejectsAndDeletesAlreadySpooledParts() throws Exception {
        MultipartLimitsTest.Counting body = new MultipartLimitsTest.Counting(input(prefix() + MultipartLimitsTest.repeat('e', 40000)));
        HttpException failure = assertThrows(HttpException.class, () -> parse(body, -1));
        assertEquals(413, failure.status());
        assertTrue("bytes read " + body.count, body.count <= LIMIT + 1);
        assertEquals(0, tmp.getRoot().list().length);
    }

    // FR-MULTIPART-002 AC-2: exact limit is accepted; one extra epilogue byte fails regardless of buffer boundary.
    @Test public void exactLimitAcceptsAndOneMoreEpilogueByteRejects() throws Exception {
        String atLimit = prefix() + MultipartLimitsTest.repeat('e', LIMIT - prefix().length());
        ByteArrayInputStream body = input(atLimit);
        List<DefaultUploadFile> parts = parse(body, -1);
        try {
            assertEquals(0, body.available());
            assertEquals(1, parts.size());
            assertEquals(4096, parts.get(0).size());
            assertEquals(1, tmp.getRoot().list().length);
        } finally {
            for (DefaultUploadFile part : parts) part.release();
        }
        assertEquals(0, tmp.getRoot().list().length);
        assertEquals(413, assertThrows(HttpException.class, () -> parse(input(atLimit + "e"), -1)).status());
        assertEquals(0, tmp.getRoot().list().length);
    }

    // FR-MULTIPART-002 AC-2: an infinite epilogue cannot make the bounded parser loop indefinitely.
    @Test(timeout = 3000) public void endlessEpilogueStopsAtTheSameLimitAndCleansSpool() throws Exception {
        MultipartLimitsTest.Counting body = new MultipartLimitsTest.Counting(
                new SequenceInputStream(input(prefix()), new MultipartLimitsTest.Endless()));
        assertEquals(413, assertThrows(HttpException.class, () -> parse(body, -1)).status());
        assertTrue("bytes read " + body.count, body.count <= LIMIT + 1);
        assertEquals(0, tmp.getRoot().list().length);
    }

    // FR-MULTIPART-002 AC-2: consuming epilogue never reads beyond a framed Content-Length into another message.
    @Test public void declaredLengthKeepsTheFollowingMessageUnread() throws Exception {
        String declared = prefix() + "ignored epilogue";
        ByteArrayInputStream body = input(declared + "NEXT");
        List<DefaultUploadFile> parts = parse(body, declared.length());
        for (DefaultUploadFile part : parts) part.release();
        assertEquals(4, body.available());
        assertEquals('N', body.read());
        assertEquals(0, tmp.getRoot().list().length);
    }
}
