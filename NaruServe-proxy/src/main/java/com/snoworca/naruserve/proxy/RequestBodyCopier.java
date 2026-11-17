package com.snoworca.naruserve.proxy;

import com.snoworca.naruserve.BodyTooLargeException;
import com.snoworca.naruserve.HttpException;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Streams the client request body to the backend through one fixed-size buffer, never holding the whole body
 * (FR-PROXY-007): exactly the declared bytes of a {@code Content-Length} body, or the chunked coding of a body whose
 * length is not known. A failure while reading the client body is thrown as {@link ClientBodyFailure}, so the caller
 * can tell it from a backend write failure.
 */
// @req FR-PROXY-007
final class RequestBodyCopier {

    /** The size of the one copy buffer of a request body. */
    static final int BUFFER_SIZE = 8192;
    private static final byte[] LAST_CHUNK = "0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);

    private RequestBodyCopier() { }

    /**
     * The one-byte pre-read of a body that came without length headers: its first byte, or -1 when the stream ends
     * at once and no body is sent.
     */
    // @req FR-PROXY-007
    static int preRead(InputStream in) {
        try {
            return in.read();
        } catch (IOException e) {
            throw new ClientBodyFailure(e);
        }
    }

    /**
     * Copies exactly {@code length} bytes from the client body to {@code out}. A body that ends before
     * {@code length} bytes throws {@link ClientBodyFailure} with an {@link EOFException}, so the caller closes the
     * backend connection and the incomplete request is never complete at the backend.
     */
    // @req FR-PROXY-007
    static void copyLength(InputStream in, OutputStream out, long length) throws IOException {
        byte[] b = new byte[BUFFER_SIZE];
        for (long left = length; left > 0; ) {
            int n = clientRead(in, b, 0, (int) Math.min(b.length, left));
            if (n < 0) {
                throw new ClientBodyFailure(new EOFException("the client body ended " + left + " bytes early"));
            }
            out.write(b, 0, n);
            left -= n;
        }
    }

    /**
     * Writes the client body to {@code out} in the chunked coding, one chunk per read, then the last chunk without
     * trailer fields. {@code first} is a byte the pre-read already took from {@code in} and leads the body, or -1.
     */
    // @req FR-PROXY-007
    static void copyChunked(InputStream in, int first, OutputStream out) throws IOException {
        byte[] b = new byte[BUFFER_SIZE];
        int held = 0;
        if (first >= 0) {
            b[0] = (byte) first;
            held = 1;
        }
        while (true) {
            int n = clientRead(in, b, held, b.length - held);
            if (n < 0) {
                writeChunk(out, b, held);
                break;
            }
            writeChunk(out, b, held + n);
            held = 0;
        }
        out.write(LAST_CHUNK);
    }

    /** One chunk of {@code size} bytes of {@code b}; nothing for size 0, which would end the body. */
    private static void writeChunk(OutputStream out, byte[] b, int size) throws IOException {
        if (size == 0) {
            return;
        }
        out.write((Integer.toHexString(size) + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
        out.write(b, 0, size);
        out.write('\r');
        out.write('\n');
    }

    /** One read of the client body; its IOException is marked as the client's, apart from backend I/O failures. */
    private static int clientRead(InputStream in, byte[] b, int off, int len) {
        try {
            return in.read(b, off, len);
        } catch (IOException e) {
            throw new ClientBodyFailure(e);
        }
    }

    /** An IOException of the client body, carried past the backend's IOException-to-502 mapping. */
    static final class ClientBodyFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final IOException failure;

        ClientBodyFailure(IOException failure) {
            super(failure);
            this.failure = failure;
        }

        /** An over-limit body as 413 with the limit exception as cause; any other failure unwrapped. */
        // @req IR-PROXY-001
        Exception forErrorFlow() {
            return failure instanceof BodyTooLargeException ? new HttpException(413, null, failure) : failure;
        }
    }
}
