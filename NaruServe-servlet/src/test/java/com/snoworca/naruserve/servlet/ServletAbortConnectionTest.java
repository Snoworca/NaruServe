package com.snoworca.naruserve.servlet;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/** FR-SVL-003: an aborted non-SSE exchange ends the connection abnormally, as the TinyHttpd adapter does. */
public class ServletAbortConnectionTest {

    @Rule
    public final ContainerRule container = new ContainerRule();

    @Before
    public void start() throws Exception {
        SharedApp.onJetty(container, "/*", app -> app.get("/broken", (req, res) -> res.stream(new InputStream() {
            private int n;

            @Override
            public int read() throws IOException {
                throw new UnsupportedOperationException();
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (n >= 100000) {
                    throw new IOException("source failed");
                }
                int k = Math.min(len, 100000 - n);
                n += k;
                return k;
            }
        }, "application/octet-stream")));
    }

    @Test(timeout = 30000)
    public void FR_SVL_003_aFailedUnknownLengthBodyIsNotEndedWithTheTerminatingChunk() throws Exception {
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", container.port()), 5000);
            s.setSoTimeout(10000);
            s.getOutputStream().write("GET /broken HTTP/1.1\r\nHost: x\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            s.getOutputStream().flush();
            byte[] buf = new byte[8192];
            try {
                int n;
                while ((n = s.getInputStream().read(buf)) >= 0) {
                    all.write(buf, 0, n);
                }
            } catch (IOException reset) {
                // a reset ends the connection abnormally too
            }
        }
        String text = new String(all.toByteArray(), StandardCharsets.ISO_8859_1);
        assertTrue("the response started", text.startsWith("HTTP/1.1 200"));
        assertFalse("a well-formed chunked terminator was sent", text.endsWith("\r\n0\r\n\r\n"));
    }
}
