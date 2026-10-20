package com.snoworca.naruserve.tinyhttpd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.HttpException;
import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.testing.NoLeftoverThreads;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.tinyhttpd.RawTargetSocketTest.Http;
import com.snoworca.tinyhttpd.http.BodyTooLargeException;
import com.snoworca.tinyhttpd.http.ContentLengthExceededException;
import com.snoworca.tinyhttpd.http.MalformedBodyException;
import com.snoworca.tinyhttpd.http.TruncatedBodyException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;

/** Request body exception mapping. SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-5-6.sds.md. */
public class TinyHttpdBodyStreamTest {

    // I-015: no naruserve-* thread outlives the test
    @Rule
    public final NoLeftoverThreads noLeftoverThreads = new NoLeftoverThreads();

    private RestApp app;

    @After
    public void stop() {
        if (app != null) {
            app.shutdown(2, TimeUnit.SECONDS);
        }
    }

    private static void assertMapped(int status, IOException original) throws IOException {
        HttpException e = TinyHttpdBodyStream.map(original);
        assertEquals(status, e.status());
        assertSame(original, e.getCause());
    }

    // SDS-AC-1; FR-ENGINE-005 AC-1
    @Test
    public void sdsAc1_FR_ENGINE_005_AC1_constructedBodyExceptionsMapToStatusesKeepingTheCause() throws IOException {
        assertMapped(413, new BodyTooLargeException("too large"));
        assertMapped(408, new SocketTimeoutException("slow"));
        assertMapped(400, new TruncatedBodyException("short"));
        assertMapped(400, new MalformedBodyException("bad chunk"));
    }

    // SDS-AC-1; FR-ENGINE-005 AC-1
    @Test
    public void sdsAc1_FR_ENGINE_005_AC1_readAndSkipThrowTheMappedException() {
        InputStream failing = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new TruncatedBodyException("short");
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                throw new SocketTimeoutException("slow");
            }

            @Override
            public long skip(long n) throws IOException {
                throw new MalformedBodyException("bad");
            }
        };
        TinyHttpdBodyStream in = new TinyHttpdBodyStream(failing);
        assertEquals(400, statusOf(() -> in.read()));
        assertEquals(408, statusOf(() -> in.read(new byte[4], 0, 4)));
        assertEquals(400, statusOf(() -> in.skip(3)));
    }

    // SDS-AC-1; FR-ENGINE-005 AC-1
    @Test(timeout = 20000)
    public void sdsAc1_FR_ENGINE_005_AC1_truncatedBodyOverTheSocketGives400() throws Exception {
        app = Http.app(new TinyHttpdAdapter(), new RecordingLogger());
        app.post("/json", (req, res) -> {
            req.body(Object.class);
            res.send("read");
        });
        int port = app.listen(0).port();
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            s.setSoTimeout(10000);
            OutputStream out = s.getOutputStream();
            // Whitespace only, so the fixture codec is never reached; 3 of the declared 50 bytes, then EOF.
            out.write(("POST /json HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\n"
                    + "Content-Length: 50\r\n\r\n   ").getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            s.shutdownOutput();
            String response = Http.readAll(s.getInputStream());
            assertEquals(response, 400, Http.status(response));
            assertTrue(response, Http.body(response).contains("\"code\":\"BAD_REQUEST\""));
        }
    }

    // SDS-AC-2; FR-ENGINE-005 AC-1
    @Test
    public void sdsAc2_FR_ENGINE_005_AC1_responseSideOverrunIsNotMapped() {
        ContentLengthExceededException overrun = new ContentLengthExceededException("wrote past length");
        try {
            TinyHttpdBodyStream.map(overrun);
            fail("ContentLengthExceededException must not become a client status");
        } catch (IOException e) {
            assertSame(overrun, e);
        }
    }

    private interface Read {
        long run() throws IOException;
    }

    private static int statusOf(Read r) {
        try {
            r.run();
        } catch (HttpException e) {
            return e.status();
        } catch (IOException e) {
            throw new AssertionError("unmapped " + e, e);
        }
        throw new AssertionError("no exception");
    }
}
