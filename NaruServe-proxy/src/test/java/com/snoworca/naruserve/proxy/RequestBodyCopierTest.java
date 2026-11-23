package com.snoworca.naruserve.proxy;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/**
 * The chunked copy of a request body under the write deadline of FR-PROXY-017: every write that reaches the backend
 * socket arms one deadline, so a chunk (size line, data and CRLF) goes out as one watched write, never as a small
 * size-line write followed by the data write.
 */
public class RequestBodyCopierTest {

    // FR-PROXY-007, FR-PROXY-017: a chunked body of full 8 KiB reads costs at most one watched socket write per data
    // chunk, plus the request head's and the last chunk's, and the backend still gets the exact chunked coding
    @Test(timeout = 30000)
    public void chunkedCopyMakesOneWatchedWritePerDataChunk() throws Exception {
        int chunks = 4;
        byte[] body = new byte[chunks * RequestBodyCopier.BUFFER_SIZE];
        for (int i = 0; i < body.length; i++) {
            body[i] = (byte) (i * 31 + 7);
        }
        byte[] head = "POST / HTTP/1.1\r\nHost: b\r\nTransfer-Encoding: chunked\r\n\r\n"
                .getBytes(StandardCharsets.ISO_8859_1);
        CountingOutputStream socket = new CountingOutputStream();
        ProxyExchange exchange = new ProxyExchange(null);
        try {
            OutputStream out = exchange.backendOutput(socket, TimeUnit.SECONDS.toNanos(30));
            out.write(head);
            RequestBodyCopier.copyChunked(new FullReads(body), -1, out);
            out.flush();
        } finally {
            exchange.release();
        }

        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        expected.write(head);
        for (int c = 0; c < chunks; c++) {
            expected.write((Integer.toHexString(RequestBodyCopier.BUFFER_SIZE) + "\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
            expected.write(body, c * RequestBodyCopier.BUFFER_SIZE, RequestBodyCopier.BUFFER_SIZE);
            expected.write('\r');
            expected.write('\n');
        }
        expected.write("0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
        assertArrayEquals("the bytes the backend got", expected.toByteArray(), socket.bytes.toByteArray());
        assertTrue("watched socket writes for " + chunks + " chunks: " + socket.writes,
                socket.writes <= chunks + 2);
    }

    // FR-PROXY-007, FR-PROXY-017: a pre-read first byte still leads the body, and short reads keep their own chunks
    @Test(timeout = 30000)
    public void chunkedCopyKeepsThePreReadByteAndShortChunks() throws Exception {
        byte[] rest = "ello, world".getBytes(StandardCharsets.ISO_8859_1);
        ByteArrayOutputStream socket = new ByteArrayOutputStream();
        RequestBodyCopier.copyChunked(new FullReads(rest), 'h', socket);
        assertArrayEquals("c\r\nhello, world\r\n0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1),
                socket.toByteArray());
    }

    /** The backend socket's output: counts the writes that reach it and keeps their bytes. */
    private static final class CountingOutputStream extends OutputStream {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int writes;

        @Override
        public void write(int b) {
            writes++;
            bytes.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) {
            writes++;
            bytes.write(b, off, len);
        }
    }

    /** A client body that fills every read as far as the bytes and the read's length allow. */
    private static final class FullReads extends InputStream {
        private final byte[] data;
        private int pos;

        FullReads(byte[] data) {
            this.data = data;
        }

        @Override
        public int read() {
            return pos < data.length ? data[pos++] & 0xff : -1;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (pos >= data.length) {
                return -1;
            }
            int n = Math.min(len, data.length - pos);
            System.arraycopy(data, pos, b, off, n);
            pos += n;
            return n;
        }
    }
}
