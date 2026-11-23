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
 * length is not known. Any exception while reading the client body, checked or not (an adapter body stream may throw
 * the unchecked {@link HttpException}), is thrown as {@link ClientBodyFailure}, so the caller can tell it from a
 * backend failure (design D-057).
 */
// @req FR-PROXY-007
final class RequestBodyCopier {

    /** The size of the one copy buffer of a request body. */
    static final int BUFFER_SIZE = 8192;
    /** Room before a chunk's data for its size line: the hexadecimal size of at most {@link #BUFFER_SIZE} and CRLF. */
    private static final int SIZE_LINE_ROOM = Integer.toHexString(BUFFER_SIZE).length() + 2;
    private static final byte[] LAST_CHUNK = "0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);

    private RequestBodyCopier() { }

    /**
     * The one-byte pre-read of a body that came without length headers: its first byte, or -1 when the stream ends
     * at once and no body is sent.
     */
    // @req FR-PROXY-007
    // @req FR-PROXY-008
    static int preRead(InputStream in) {
        try {
            return in.read();
        } catch (IOException | RuntimeException e) {
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
     * Each chunk, its size line, data and CRLF, is assembled in the one copy buffer and handed to {@code out} as one
     * write, so a full chunk reaches the backend socket as one watched write (FR-PROXY-017), not as a size-line write
     * followed by a data write.
     */
    // @req FR-PROXY-007
    // @req FR-PROXY-017
    static void copyChunked(InputStream in, int first, OutputStream out) throws IOException {
        // The data starts after room for the longest size line and leaves room for the CRLF after it.
        byte[] b = new byte[SIZE_LINE_ROOM + BUFFER_SIZE + 2];
        int held = 0;
        if (first >= 0) {
            b[SIZE_LINE_ROOM] = (byte) first;
            held = 1;
        }
        while (true) {
            int n = clientRead(in, b, SIZE_LINE_ROOM + held, BUFFER_SIZE - held);
            if (n < 0) {
                writeChunk(out, b, held);
                break;
            }
            writeChunk(out, b, held + n);
            held = 0;
        }
        out.write(LAST_CHUNK);
    }

    /**
     * One chunk of the {@code size} data bytes at {@link #SIZE_LINE_ROOM} of {@code b}, written as one block with its
     * size line put right before the data and the CRLF right after it; nothing for size 0, which would end the body.
     */
    private static void writeChunk(OutputStream out, byte[] b, int size) throws IOException {
        if (size == 0) {
            return;
        }
        byte[] sizeLine = (Integer.toHexString(size) + "\r\n").getBytes(StandardCharsets.ISO_8859_1);
        int start = SIZE_LINE_ROOM - sizeLine.length;
        System.arraycopy(sizeLine, 0, b, start, sizeLine.length);
        int end = SIZE_LINE_ROOM + size;
        b[end] = '\r';
        b[end + 1] = '\n';
        out.write(b, start, end + 2 - start);
    }

    /** One read of the client body; any exception it throws is marked as the client's, apart from backend failures. */
    // @req FR-PROXY-008
    private static int clientRead(InputStream in, byte[] b, int off, int len) {
        try {
            return in.read(b, off, len);
        } catch (IOException | RuntimeException e) {
            throw new ClientBodyFailure(e);
        }
    }

    /**
     * An exception of the client body (an IOException, or an unchecked one such as {@link HttpException}), carried
     * past the backend's IOException-to-502 mapping to the error flow.
     */
    static final class ClientBodyFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final Exception failure;

        ClientBodyFailure(Exception failure) {
            super(failure);
            this.failure = failure;
        }

        /** An over-limit body as 413 with the limit exception as cause; any other failure unwrapped. */
        // @req IR-PROXY-001
        // @req FR-PROXY-008
        Exception forErrorFlow() {
            return failure instanceof BodyTooLargeException ? new HttpException(413, null, failure) : failure;
        }
    }
}
